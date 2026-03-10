/*
 * Copyright 2026 Peter Geschel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.konkin.web.controller;

import io.javalin.http.Context;
import io.konkin.config.ConfigManager;
import io.konkin.config.ConfigUpdateResult;
import io.konkin.config.KonkinConfig;
import io.konkin.web.LandingPageMapper;
import io.konkin.web.service.LandingPageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Handles settings-related web UI pages: API keys, driver agent.
 */
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final LandingPageService landingPageService;
    private final LandingPageMapper mapper;
    private final ConfigManager configManager;
    private final boolean passwordProtectionEnabled;
    private final Path restApiSecretFilePath;
    private final AtomicReference<String> activeApiKey;
    private final Predicate<Context> sessionValidator;
    private final Consumer<Context> loginRedirect;

    public SettingsController(
            LandingPageService landingPageService,
            LandingPageMapper mapper,
            ConfigManager configManager,
            boolean passwordProtectionEnabled,
            Path restApiSecretFilePath,
            AtomicReference<String> activeApiKey,
            Predicate<Context> sessionValidator,
            Consumer<Context> loginRedirect
    ) {
        this.landingPageService = landingPageService;
        this.mapper = mapper;
        this.configManager = configManager;
        this.passwordProtectionEnabled = passwordProtectionEnabled;
        this.restApiSecretFilePath = restApiSecretFilePath;
        this.activeApiKey = activeApiKey != null ? activeApiKey : new AtomicReference<>();
        this.sessionValidator = sessionValidator;
        this.loginRedirect = loginRedirect;
    }

    private KonkinConfig config() {
        return configManager.get();
    }

    public void handleDriverAgentPage(Context ctx) {
        if (passwordProtectionEnabled && !sessionValidator.test(ctx)) {
            loginRedirect.accept(ctx);
            return;
        }

        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(landingPageService.renderDriverAgent(
                passwordProtectionEnabled,
                mapper.buildDriverAgentModel()
        ));
    }

    // ── API key management ─────────────────────────────────────────────────

    public void handleApiKeysPage(Context ctx) {
        if (passwordProtectionEnabled && !sessionValidator.test(ctx)) {
            loginRedirect.accept(ctx);
            return;
        }

        boolean restApiEnabled = config().restApiEnabled();
        boolean hasKey = activeApiKey.get() != null;
        String secretFile = restApiSecretFilePath != null ? restApiSecretFilePath.toString() : "";
        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(landingPageService.renderApiKeys(
                passwordProtectionEnabled,
                restApiEnabled, hasKey, "", secretFile,
                mapper.buildRestApiChannelModel(),
                mapper.buildRestApiSettingsModel()));
    }

    public void handleApiKeysRotate(Context ctx) {
        if (passwordProtectionEnabled && !sessionValidator.test(ctx)) {
            loginRedirect.accept(ctx);
            return;
        }

        if (!config().restApiEnabled() || restApiSecretFilePath == null) {
            ctx.redirect("/auth_channels/api_keys");
            return;
        }

        String newKey = generateApiKey();
        writeApiKeyFile(restApiSecretFilePath, newKey);
        activeApiKey.set(newKey);
        landingPageService.setRestApiKeyMissing(false);
        log.info("REST API key rotated via web UI, secret file: {}", restApiSecretFilePath.toAbsolutePath());

        String secretFile = restApiSecretFilePath.toString();
        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(landingPageService.renderApiKeys(
                passwordProtectionEnabled,
                true, true, newKey, secretFile,
                mapper.buildRestApiChannelModel(),
                mapper.buildRestApiSettingsModel()));
    }

    public void handleSettingsPage(Context ctx) {
        if (passwordProtectionEnabled && !sessionValidator.test(ctx)) {
            loginRedirect.accept(ctx);
            return;
        }

        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(landingPageService.renderSettings(
                passwordProtectionEnabled,
                mapper.buildSettingsModel()
        ));
    }

    // ── Settings update endpoints ────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public void handleUpdateServer(Context ctx) {
        if (!checkSession(ctx)) return;
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String error = SettingsValidator.validateServer(body);
        if (error != null) { ctx.json(ConfigUpdateResult.error(error)); return; }
        ctx.json(configManager.updateSection("server", body));
    }

    @SuppressWarnings("unchecked")
    public void handleUpdateDatabase(Context ctx) {
        if (!checkSession(ctx)) return;
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String error = SettingsValidator.validateDatabase(body);
        if (error != null) { ctx.json(ConfigUpdateResult.error(error)); return; }
        ctx.json(configManager.updateSection("database", body));
    }

    @SuppressWarnings("unchecked")
    public void handleUpdateWebUi(Context ctx) {
        if (!checkSession(ctx)) return;
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String error = SettingsValidator.validateWebUi(body);
        if (error != null) { ctx.json(ConfigUpdateResult.error(error)); return; }
        ctx.json(configManager.updateSection("web-ui", body));
    }

    @SuppressWarnings("unchecked")
    public void handleUpdateRestApi(Context ctx) {
        if (!checkSession(ctx)) return;
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String error = SettingsValidator.validateRestApi(body);
        if (error != null) { ctx.json(ConfigUpdateResult.error(error)); return; }
        ctx.json(configManager.updateSection("rest-api", body));
    }

    @SuppressWarnings("unchecked")
    public void handleUpdateTelegram(Context ctx) {
        if (!checkSession(ctx)) return;
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String error = SettingsValidator.validateTelegram(body);
        if (error != null) { ctx.json(ConfigUpdateResult.error(error)); return; }
        ctx.json(configManager.updateSection("telegram", body));
    }

    @SuppressWarnings("unchecked")
    public void handleUpdateAgent(Context ctx) {
        if (!checkSession(ctx)) return;
        String name = ctx.pathParam("name");
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String error = SettingsValidator.validateAgent(body);
        if (error != null) { ctx.json(ConfigUpdateResult.error(error)); return; }

        String sectionPrefix;
        if ("primary".equals(name)) {
            sectionPrefix = "agents.primary";
        } else {
            if (!config().secondaryAgents().containsKey(name)) {
                ctx.json(ConfigUpdateResult.error("Unknown agent: " + name));
                return;
            }
            sectionPrefix = "agents.secondary." + name;
        }
        ctx.json(configManager.updateSection(sectionPrefix, body));
    }

    @SuppressWarnings("unchecked")
    public void handleUpdateDebug(Context ctx) {
        if (!checkSession(ctx)) return;
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String error = SettingsValidator.validateDebug(body);
        if (error != null) { ctx.json(ConfigUpdateResult.error(error)); return; }
        ctx.json(configManager.updateSection("debug", body));
    }

    @SuppressWarnings("unchecked")
    public void handleUpdateCoin(Context ctx) {
        if (!checkSession(ctx)) return;
        String coin = ctx.pathParam("coin").toLowerCase();
        if (config().resolveCoinConfig(coin) == null) {
            ctx.json(ConfigUpdateResult.error("Unknown coin: " + coin));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>(ctx.bodyAsClass(Map.class));
        String error = SettingsValidator.validateCoin(body);
        if (error != null) { ctx.json(ConfigUpdateResult.error(error)); return; }
        transformRuleLists(body);
        ctx.json(configManager.updateSection("coins." + coin, body));
    }

    public void handlePendingRestart(Context ctx) {
        if (!checkSession(ctx)) return;
        ctx.json(Map.of("fields", configManager.pendingRestartFields()));
    }

    private boolean checkSession(Context ctx) {
        if (passwordProtectionEnabled && !sessionValidator.test(ctx)) {
            ctx.status(401).json(ConfigUpdateResult.error("Unauthorized"));
            return false;
        }
        return true;
    }

    /**
     * Transforms flat rule objects [{type, value, period}] into TOML-compatible
     * structure [{criteria: {type, value, period}}].
     */
    @SuppressWarnings("unchecked")
    private static void transformRuleLists(Map<String, Object> body) {
        for (String key : new String[]{"auth.auto-accept", "auth.auto-deny"}) {
            Object raw = body.get(key);
            if (!(raw instanceof List<?> list)) continue;
            List<Map<String, Object>> transformed = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> rule)) continue;
                Map<String, Object> ruleMap = (Map<String, Object>) rule;
                Map<String, Object> criteria = new LinkedHashMap<>();
                criteria.put("type", ruleMap.get("type"));
                Object val = ruleMap.get("value");
                if (val instanceof Number n) {
                    criteria.put("value", n.doubleValue());
                } else if (val instanceof String s) {
                    criteria.put("value", Double.parseDouble(s));
                }
                Object period = ruleMap.get("period");
                if (period instanceof String ps && !ps.isBlank()) {
                    criteria.put("period", ps);
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("criteria", criteria);
                transformed.add(entry);
            }
            body.put(key, transformed);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String generateApiKey() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static void writeApiKeyFile(Path secretFile, String apiKey) {
        try {
            Path parent = secretFile.getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
            }
            java.nio.file.Files.writeString(secretFile,
                    "api-key=" + apiKey + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8);
            setOwnerOnlyPermissions(secretFile);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to write REST API secret file: " + secretFile, e);
        }
    }

    private static void setOwnerOnlyPermissions(Path file) {
        try {
            java.nio.file.Files.setPosixFilePermissions(file, java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
            ));
        } catch (UnsupportedOperationException ignored) {
        } catch (java.io.IOException e) {
            log.warn("Failed to set owner-only permissions on secret file: {}", file, e);
        }
    }
}
