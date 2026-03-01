package io.konkin.web;

import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.staticfiles.Location;
import io.konkin.api.ApprovalChannelController;
import io.konkin.api.ApprovalRequestController;
import io.konkin.api.ApprovalVoteController;
import io.konkin.api.CoinRuntimeController;
import io.konkin.api.ExecutionAttemptController;
import io.konkin.api.HealthController;
import io.konkin.api.KvStoreController;
import io.konkin.api.RequestChannelController;
import io.konkin.api.StateTransitionController;
import io.konkin.agent.AgentEndpointServer;
import io.konkin.agent.AgentTokenStore;
import io.konkin.agent.primary.PrimaryAgentConfigRequirementsService;
import io.konkin.config.KonkinConfig;
import io.konkin.db.AuthQueueStore;
import io.konkin.db.DebugDataSeeder;
import io.konkin.db.KvStore;
import io.konkin.security.PasswordFileManager;
import io.konkin.web.controller.LandingPageController;
import io.konkin.web.service.HealthService;
import io.konkin.web.service.LandingPageService;
import io.konkin.web.service.LandingResourceWatcher;
import io.konkin.web.service.TelegramSecretService;
import io.konkin.web.service.TelegramService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class KonkinWebServer {

    private static final Logger log = LoggerFactory.getLogger(KonkinWebServer.class);

    private final KonkinConfig config;
    private final String version;
    private final DataSource dataSource;

    public KonkinWebServer(KonkinConfig config, String version) {
        this(config, version, null);
    }

    public KonkinWebServer(KonkinConfig config, String version, DataSource dataSource) {
        this.config = config;
        this.version = version;
        this.dataSource = dataSource;
    }

    private Javalin app;
    private LandingResourceWatcher landingResourceWatcher;
    private boolean running;
    private final List<AgentEndpointServer> agentEndpoints = new ArrayList<>();
    private AuthQueueStore authQueueStore;

    public void start() {
        running = false;

        HealthService healthService = new HealthService(version);
        HealthController healthController = new HealthController(healthService);

        authQueueStore = dataSource != null ? new AuthQueueStore(dataSource) : null;
        KvStoreController kvStoreController = dataSource != null ? new KvStoreController(new KvStore(dataSource)) : null;
        ApprovalRequestController requestController = new ApprovalRequestController(authQueueStore);
        ApprovalChannelController channelController = new ApprovalChannelController(authQueueStore);
        ApprovalVoteController voteController = new ApprovalVoteController(authQueueStore);
        StateTransitionController stateTransitionController = new StateTransitionController(authQueueStore);
        RequestChannelController requestChannelController = new RequestChannelController(authQueueStore);
        ExecutionAttemptController executionAttemptController = new ExecutionAttemptController(authQueueStore);
        CoinRuntimeController coinRuntimeController = new CoinRuntimeController(authQueueStore);

        if (dataSource != null) {
            DebugDataSeeder debugDataSeeder = new DebugDataSeeder(dataSource);
            debugDataSeeder.seedIfEnabled(config.debugEnabled(), config.debugSeedFakeData());
        }
        LandingPageController landingPageController = null;
        LandingPageService landingPageService = null;
        Path landingTemplateDirectory = null;
        Path landingStaticDirectory = null;

        TelegramService telegramService = null;
        TelegramSecretService telegramSecretService = null;

        if (config.telegramEnabled()) {
            Path secretFile = Path.of(config.telegramSecretFile());
            telegramSecretService = new TelegramSecretService(secretFile);

            if (!telegramSecretService.ensureExists()) {
                logTelegramStartupAction(
                        "Secret file could not be created.",
                        "KONKIN kept startup in safe mode and did not start HTTP services.",
                        secretFile
                );
                return;
            }

            TelegramSecretService.TelegramSecret secret = telegramSecretService.readSecret();
            if (!telegramSecretService.hasConfiguredBotToken(secret)) {
                logTelegramStartupAction(
                        "Missing required key 'bot-token' in telegram secret file.",
                        "KONKIN kept startup in safe mode and did not start HTTP services.",
                        secretFile
                );
                return;
            }

            List<String> approvedChatIds = TelegramSecretService.mergeChatIds(config.telegramChatIds(), secret.chatIds());

            if (!approvedChatIds.equals(secret.chatIds())) {
                boolean persisted = telegramSecretService.writeSecret(
                        new TelegramSecretService.TelegramSecret(secret.botToken(), approvedChatIds)
                );
                if (persisted) {
                    log.info("Telegram approved chat IDs synced to secret file {}: {}",
                            secretFile.toAbsolutePath().normalize(),
                            approvedChatIds.isEmpty() ? "(none)" : String.join(",", approvedChatIds));
                } else {
                    log.warn("Failed to sync approved Telegram chat IDs to secret file {}", secretFile.toAbsolutePath().normalize());
                }
            }

            telegramService = new TelegramService(
                    config.telegramApiBaseUrl(),
                    secret.botToken(),
                    approvedChatIds
            );

            if (approvedChatIds.isEmpty()) {
                log.info("Telegram initialized with bot token but no approved chat IDs yet. Web UI is optional; fill chat-ids in {} or approve via /telegram.",
                        secretFile.toAbsolutePath().normalize());
            } else {
                log.info("Telegram initialized with {} explicitly approved chat id(s): {}", approvedChatIds.size(), String.join(",", approvedChatIds));
            }
        }

        if (config.landingEnabled()) {
            landingTemplateDirectory = Path.of(config.landingTemplateDirectory()).toAbsolutePath().normalize();
            landingStaticDirectory = Path.of(config.landingStaticDirectory()).toAbsolutePath().normalize();

            PasswordFileManager landingPasswordFileManager = null;
            if (config.landingPasswordProtectionEnabled()) {
                landingPasswordFileManager = PasswordFileManager.bootstrap(Path.of(config.landingPasswordFile()));
            }

            landingPageService = new LandingPageService(
                    landingTemplateDirectory,
                    config.landingStaticHostedPath(),
                    config.landingAutoReloadEnabled(),
                    config.telegramEnabled() && config.landingEnabled()
            );

            landingPageController = new LandingPageController(
                    landingPageService,
                    config.landingPasswordProtectionEnabled(),
                    landingPasswordFileManager,
                    config.telegramEnabled(),
                    config.telegramChatIds(),
                    telegramService,
                    telegramSecretService,
                    authQueueStore,
                    config
            );
        }

        Path staticDirectoryFinal = landingStaticDirectory;
        app = Javalin.create(javalinConfig -> {
            javalinConfig.showJavalinBanner = false;
            javalinConfig.jetty.modifyServer(server -> server.setStopTimeout(3_000));

            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.registerModule(new JavaTimeModule());
            objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            javalinConfig.jsonMapper(new JavalinJackson(objectMapper, false));

            if (config.landingEnabled()) {
                javalinConfig.staticFiles.add(staticFileConfig -> {
                    staticFileConfig.hostedPath = config.landingStaticHostedPath();
                    staticFileConfig.directory = staticDirectoryFinal.toString();
                    staticFileConfig.location = Location.EXTERNAL;
                    staticFileConfig.precompress = false;
                });
            }
        });

        if (config.restApiEnabled()) {
            Path restApiSecretFile = Path.of(config.restApiSecretFile());
            String expectedApiKey = readRestApiKey(restApiSecretFile);
            app.before(ctx -> {
                String path = ctx.path();
                boolean apiRequest = "/api/v1".equals(path) || path.startsWith("/api/v1/");
                if (!apiRequest || "/api/v1/health".equals(path)) {
                    return;
                }

                String providedApiKey = ctx.header("X-API-Key");
                if (providedApiKey == null || !providedApiKey.equals(expectedApiKey)) {
                    throw new UnauthorizedResponse();
                }
            });
        } else {
            app.before(ctx -> {
                String path = ctx.path();
                boolean apiRequest = "/api/v1".equals(path) || path.startsWith("/api/v1/");
                if (apiRequest && !"/api/v1/health".equals(path)) {
                    throw new NotFoundResponse();
                }
            });
        }

        app.get("/api/v1/health", healthController::handle);

        if (config.restApiEnabled() && dataSource != null) {
            app.get("/api/v1/kv", kvStoreController::getAll);
            app.get("/api/v1/kv/{key}", kvStoreController::getOne);
            app.put("/api/v1/kv/{key}", kvStoreController::put);
            app.delete("/api/v1/kv/{key}", kvStoreController::delete);

            app.get("/api/v1/requests", requestController::getAll);
            app.post("/api/v1/requests", requestController::create);
            app.get("/api/v1/requests/filter-options", requestController::getFilterOptions);
            app.get("/api/v1/requests/{id}", requestController::getOne);
            app.put("/api/v1/requests/{id}", requestController::update);
            app.delete("/api/v1/requests/{id}", requestController::delete);
            app.get("/api/v1/requests/{id}/dependencies", requestController::getDependencies);

            app.get("/api/v1/channels", channelController::getAll);
            app.post("/api/v1/channels", channelController::create);
            app.get("/api/v1/channels/{id}", channelController::getOne);
            app.put("/api/v1/channels/{id}", channelController::update);
            app.delete("/api/v1/channels/{id}", channelController::delete);

            app.get("/api/v1/votes", voteController::getAll);
            app.post("/api/v1/votes", voteController::create);
            app.get("/api/v1/votes/{id}", voteController::getOne);
            app.put("/api/v1/votes/{id}", voteController::update);
            app.delete("/api/v1/votes/{id}", voteController::delete);
            app.get("/api/v1/requests/{requestId}/votes", voteController::getForRequest);

            app.get("/api/v1/state-transitions", stateTransitionController::getAll);
            app.post("/api/v1/state-transitions", stateTransitionController::create);
            app.get("/api/v1/state-transitions/{id}", stateTransitionController::getOne);
            app.delete("/api/v1/state-transitions/{id}", stateTransitionController::delete);

            app.get("/api/v1/request-channels", requestChannelController::getAll);
            app.post("/api/v1/request-channels", requestChannelController::create);
            app.get("/api/v1/request-channels/{id}", requestChannelController::getOne);
            app.put("/api/v1/request-channels/{id}", requestChannelController::update);
            app.delete("/api/v1/request-channels/{id}", requestChannelController::delete);

            app.get("/api/v1/execution-attempts", executionAttemptController::getAll);
            app.post("/api/v1/execution-attempts", executionAttemptController::create);
            app.get("/api/v1/execution-attempts/{id}", executionAttemptController::getOne);
            app.delete("/api/v1/execution-attempts/{id}", executionAttemptController::delete);

            app.get("/api/v1/coin-runtimes", coinRuntimeController::getAll);
            app.post("/api/v1/coin-runtimes", coinRuntimeController::create);
            app.get("/api/v1/coin-runtimes/{coin}", coinRuntimeController::getOne);
            app.put("/api/v1/coin-runtimes/{coin}", coinRuntimeController::update);
            app.delete("/api/v1/coin-runtimes/{coin}", coinRuntimeController::delete);
        }

        LandingPageController webUiPageControllerFinal = landingPageController;
        LandingPageService landingPageServiceFinal = landingPageService;
        Path landingTemplateDirectoryFinal = landingTemplateDirectory;

        if (config.landingEnabled()) {
            app.get("/", webUiPageControllerFinal::handleRoot);
            app.get("/log", webUiPageControllerFinal::handleLog);
            app.get("/details", webUiPageControllerFinal::handleDetailsPage);
            app.get("/wallets", webUiPageControllerFinal::handleWalletsPage);
            app.get("/auth_channels", webUiPageControllerFinal::handleAuthChannelsPage);
            app.get("/login", webUiPageControllerFinal::handleLoginPage);
            app.post("/login", webUiPageControllerFinal::handleLoginSubmit);
            app.post("/logout", webUiPageControllerFinal::handleLogout);

            if (config.telegramEnabled()) {
                app.get("/telegram", webUiPageControllerFinal::handleTelegramPage);
                app.post("/telegram/approve", webUiPageControllerFinal::handleTelegramApprove);
                app.post("/telegram/send", webUiPageControllerFinal::handleTelegramSubmit);
            }

            landingResourceWatcher = new LandingResourceWatcher(
                    config.landingAutoReloadEnabled(),
                    config.landingAssetsAutoReloadEnabled(),
                    landingTemplateDirectoryFinal,
                    staticDirectoryFinal,
                    landingPageServiceFinal::clearTemplateCache,
                    landingPageServiceFinal::markStaticAssetsChanged
            );
        }

        app.start(config.host(), config.port());

        try {
            startAgentEndpoints();
        } catch (RuntimeException e) {
            stopAgentEndpoints();
            if (app != null) {
                app.stop();
                app = null;
            }
            throw e;
        }

        running = true;

        if (landingResourceWatcher != null) {
            landingResourceWatcher.start();
        }

        log.info("KONKIN server running at http://{}:{}", config.host(), config.port());
        log.info("  /api/v1/health       — health check");
        if (config.restApiEnabled()) {
            log.info("  /api/v1/*            — REST API endpoints enabled");
        } else {
            log.info("  /api/v1/*            — disabled via config (except /api/v1/health)");
        }
        if (config.landingEnabled()) {
            log.info("  /                    — landing page (passwordLoginProtected={})", config.landingPasswordProtectionEnabled());
            log.info("  /log                 — audit log page (passwordLoginProtected={})", config.landingPasswordProtectionEnabled());
            log.info("  /details             — request details cleartext (passwordLoginProtected={})", config.landingPasswordProtectionEnabled());
            log.info("  /wallets             — wallets overview (passwordLoginProtected={})", config.landingPasswordProtectionEnabled());
            log.info("  /auth_channels       — auth channels overview (passwordLoginProtected={})", config.landingPasswordProtectionEnabled());
            if (config.telegramEnabled()) {
                log.info("  /telegram            — telegram onboarding and manual send page");
                log.info("  /telegram/approve    — approve discovered telegram chat request");
                log.info("  /telegram/send       — telegram send endpoint");
            } else {
                log.info("  /telegram            — disabled via config");
                log.info("  /telegram/approve    — disabled via config");
                log.info("  /telegram/send       — disabled via config");
            }

            log.info("  {}/*             — static assets from {}",
                    config.landingStaticHostedPath(),
                    staticDirectoryFinal);
            log.info(
                    "Landing auto-reload — templates={}, assets={}",
                    config.landingAutoReloadEnabled() ? "enabled" : "disabled",
                    config.landingAssetsAutoReloadEnabled() ? "enabled" : "disabled"
            );
        } else {
            log.info("  /                    — disabled via config");
            log.info("  /log                 — disabled via config");
            log.info("  /details             — disabled via config");
            log.info("  /wallets             — disabled via config");
            log.info("  /auth_channels       — disabled via config");
            log.info("  /telegram            — disabled via config (landing disabled)");
            log.info("  /telegram/approve    — disabled via config (landing disabled)");
            log.info("  /telegram/send       — disabled via config (landing disabled)");
        }
    }

    private void startAgentEndpoints() {
        AgentTokenStore tokenStore = new AgentTokenStore();
        agentEndpoints.clear();

        KonkinConfig.AgentConfig primaryAgent = config.primaryAgent();
        if (primaryAgent != null && primaryAgent.enabled()) {
            AgentEndpointServer endpoint = new AgentEndpointServer(
                    "konkin-primary",
                    "primary",
                    primaryAgent,
                    tokenStore,
                    new PrimaryAgentConfigRequirementsService(config),
                    authQueueStore,
                    config
            );
            endpoint.start();
            agentEndpoints.add(endpoint);
            log.info("Agent endpoint started — name={}, type={}, bind={}, port={}",
                    endpoint.agentName(), endpoint.agentType(), endpoint.bind(), endpoint.port());
        }

        for (Map.Entry<String, KonkinConfig.AgentConfig> entry : config.secondaryAgents().entrySet()) {
            KonkinConfig.AgentConfig secondaryAgent = entry.getValue();
            if (!secondaryAgent.enabled()) {
                continue;
            }

            AgentEndpointServer endpoint = new AgentEndpointServer(
                    entry.getKey(),
                    "secondary",
                    secondaryAgent,
                    tokenStore,
                    null,
                    authQueueStore,
                    config
            );
            endpoint.start();
            agentEndpoints.add(endpoint);
            log.info("Agent endpoint started — name={}, type={}, bind={}, port={}",
                    endpoint.agentName(), endpoint.agentType(), endpoint.bind(), endpoint.port());
        }
    }

    private void stopAgentEndpoints() {
        if (agentEndpoints.isEmpty()) {
            return;
        }

        for (AgentEndpointServer endpoint : agentEndpoints) {
            try {
                endpoint.stop();
            } catch (RuntimeException e) {
                log.warn("Failed to stop agent endpoint '{}' cleanly", endpoint.agentName(), e);
            }
        }
        agentEndpoints.clear();
    }

    private String readRestApiKey(Path secretFile) {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(secretFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read REST API secret file: " + secretFile.toAbsolutePath().normalize(),
                    e
            );
        }

        String apiKey = properties.getProperty("api-key", "").trim();
        if (apiKey.isEmpty()) {
            throw new IllegalStateException(
                    "Invalid REST API secret file: missing non-empty 'api-key' in " + secretFile.toAbsolutePath().normalize()
            );
        }

        return apiKey;
    }

    private void logTelegramStartupAction(String reason, String actionTaken, Path secretFile) {
        Path absolute = secretFile.toAbsolutePath().normalize();
        log.warn("");
        log.warn("################################################################################");
        log.warn("# KONKIN STARTUP ACTION REQUIRED — TELEGRAM SECRETS");
        log.warn("#");
        log.warn("# Reason      : {}", reason);
        log.warn("# KONKIN did  : {}", actionTaken);
        log.warn("# HTTP server : NOT STARTED (intentional safe stop)");
        log.warn("# Secret file : {}", absolute);
        log.warn("#");
        log.warn("# Required file content:");
        log.warn("#   bot-token=<telegram bot token>");
        log.warn("#   chat-ids=<id1,id2,...>");
        log.warn("#");
        log.warn("# Discovery note:");
        log.warn("#   Each target chat must send at least one message (e.g. /start) to your bot");
        log.warn("#   before it appears in /telegram approval requests.");
        log.warn("#");
        log.warn("# Next step   : Edit the file, replace placeholders, restart KONKIN.");
        log.warn("################################################################################");
        log.warn("");
    }

    public void stop() {
        stopAgentEndpoints();

        if (landingResourceWatcher != null) {
            landingResourceWatcher.stop();
            landingResourceWatcher = null;
        }

        if (app != null) {
            app.stop();
            app = null;
        }

        running = false;
    }

    public boolean isRunning() {
        return running;
    }
}
