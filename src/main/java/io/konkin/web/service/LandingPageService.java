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

package io.konkin.web.service;

import freemarker.core.HTMLOutputFormat;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Renders landing-related pages from FreeMarker templates.
 */
public class LandingPageService {

    private static final String MAIN_TEMPLATE_NAME = "landing.ftl";
    private static final String LOGIN_TEMPLATE_NAME = "landing-login.ftl";
    private static final String SETUP_TEMPLATE_NAME = "landing-setup.ftl";
    private static final String AUDIT_LOG_TEMPLATE_NAME = "landing-log.ftl";
    private static final String TELEGRAM_TEMPLATE_NAME = "landing-telegram.ftl";
    private static final String WALLETS_TEMPLATE_NAME = "landing-auth-definitions.ftl";
    private static final String WALLET_TEMPLATE_NAME = "landing-wallet.ftl";
    private static final String AUTH_CHANNELS_TEMPLATE_NAME = "landing-auth-channels.ftl";
    private static final String AUTH_CHANNEL_WEBUI_TEMPLATE_NAME = "landing-auth-channel-webui.ftl";
    private static final String DRIVER_AGENT_TEMPLATE_NAME = "landing-driver-agent.ftl";
    private static final String API_KEYS_TEMPLATE_NAME = "landing-api-keys.ftl";

    private final Configuration freemarker;
    private final String staticHostedPath;
    private final AtomicLong staticAssetsVersion;
    private final boolean telegramEnabled;
    private volatile boolean restApiKeyMissing;
    private volatile List<String> enabledCoins = List.of();

    public LandingPageService(
            Path templateDirectory,
            String staticHostedPath,
            boolean autoReloadEnabled,
            boolean telegramEnabled
    ) {
        this.staticHostedPath = staticHostedPath;
        this.staticAssetsVersion = new AtomicLong(1L);
        this.telegramEnabled = telegramEnabled;

        this.freemarker = new Configuration(Configuration.VERSION_2_3_34);
        this.freemarker.setDefaultEncoding("UTF-8");
        this.freemarker.setOutputFormat(HTMLOutputFormat.INSTANCE);
        this.freemarker.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        this.freemarker.setLogTemplateExceptions(false);
        this.freemarker.setWrapUncheckedExceptions(true);
        this.freemarker.setFallbackOnNullLoopVariable(false);
        this.freemarker.setTemplateUpdateDelayMilliseconds(autoReloadEnabled ? 0 : 60_000);

        try {
            this.freemarker.setDirectoryForTemplateLoading(templateDirectory.toFile());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to configure template directory: " + templateDirectory, e);
        }
    }

    public String renderLanding(boolean showLogout, String activePage) {
        return renderLanding(showLogout, activePage, "", false, "", List.of(), List.of(), false, "", "", "-", "", List.of(), Map.of(), List.of(), Map.of(), List.of(), Map.of());
    }

    public String renderLanding(
            boolean showLogout,
            String activePage,
            String telegramNotice,
            boolean telegramNoticeError,
            String telegramDraft,
            List<Map<String, String>> telegramChatRequests,
            List<Map<String, String>> telegramApprovedChats,
            boolean telegramConfirmRequired,
            String telegramConfirmMode,
            String telegramConfirmChatId,
            String telegramConfirmChatIdShort,
            String telegramConfirmActionPath,
            List<Map<String, Object>> queueRows,
            Map<String, Object> queuePage,
            List<Map<String, Object>> auditRows,
            Map<String, Object> auditPage,
            List<Map<String, Object>> logQueueRows,
            Map<String, Object> logQueuePage
    ) {
        Map<String, Object> model = new HashMap<>();
        model.put("assetsPath", staticHostedPath);
        model.put("assetsVersion", staticAssetsVersion.get());
        model.put("queuePath", "/");
        model.put("auditLogPath", "/history");
        model.put("telegramPath", "/auth_channels/telegram");
        model.put("githubPath", "https://github.com/otherfren/konkin");
        model.put("walletsPath", "/wallets");
        model.put("driverAgentPath", "/driver_agent");
        model.put("authChannelsPath", "/auth_channels");
        model.put("apiKeysPath", "/auth_channels/api_keys");
        model.put("restApiKeyMissing", restApiKeyMissing);
        model.put("title", "KONKIN.io");
        model.put("showLogout", showLogout);
        model.put("activePage", activePage);
        model.put("telegramPageAvailable", telegramEnabled);
        model.put("enabledCoins", enabledCoins);
        model.put("telegramNotice", telegramNotice == null ? "" : telegramNotice);
        model.put("telegramNoticeError", telegramNoticeError);
        model.put("telegramDraft", telegramDraft == null ? "" : telegramDraft);
        model.put("telegramChatRequests", telegramChatRequests == null ? List.of() : telegramChatRequests);
        model.put("telegramApprovedChats", telegramApprovedChats == null ? List.of() : telegramApprovedChats);
        model.put("telegramConfirmRequired", telegramConfirmRequired);
        model.put("telegramConfirmMode", telegramConfirmMode == null ? "" : telegramConfirmMode);
        model.put("telegramConfirmChatId", telegramConfirmChatId == null ? "" : telegramConfirmChatId);
        model.put("telegramConfirmChatIdShort", telegramConfirmChatIdShort == null ? "-" : telegramConfirmChatIdShort);
        model.put("telegramConfirmActionPath", telegramConfirmActionPath == null ? "" : telegramConfirmActionPath);
        model.put("queueRows", queueRows == null ? List.of() : queueRows);
        model.put("queuePage", queuePage == null ? Map.of() : queuePage);
        model.put("auditRows", auditRows == null ? List.of() : auditRows);
        model.put("auditPage", auditPage == null ? Map.of() : auditPage);
        model.put("logQueueRows", logQueueRows == null ? List.of() : logQueueRows);
        model.put("logQueuePage", logQueuePage == null ? Map.of() : logQueuePage);

        String selectedTemplate;
        if ("history".equals(activePage)) {
            selectedTemplate = AUDIT_LOG_TEMPLATE_NAME;
        } else if ("auth_channel_telegram".equals(activePage)) {
            selectedTemplate = TELEGRAM_TEMPLATE_NAME;
        } else {
            selectedTemplate = MAIN_TEMPLATE_NAME;
        }

        return renderTemplate(selectedTemplate, model);
    }

    public String renderWallets(boolean showLogout, Map<String, Object> walletsData) {
        Map<String, Object> model = new HashMap<>();
        model.put("assetsPath", staticHostedPath);
        model.put("assetsVersion", staticAssetsVersion.get());
        model.put("queuePath", "/");
        model.put("auditLogPath", "/history");
        model.put("telegramPath", "/auth_channels/telegram");
        model.put("walletsPath", "/wallets");
        model.put("driverAgentPath", "/driver_agent");
        model.put("githubPath", "https://github.com/otherfren/konkin");
        model.put("authChannelsPath", "/auth_channels");
        model.put("apiKeysPath", "/auth_channels/api_keys");
        model.put("restApiKeyMissing", restApiKeyMissing);
        model.put("title", "KONKIN.io");
        model.put("showLogout", showLogout);
        model.put("activePage", "wallets");
        model.put("telegramPageAvailable", telegramEnabled);
        model.put("enabledCoins", enabledCoins);
        model.put("wallets", walletsData == null ? Map.of() : walletsData);

        return renderTemplate(WALLETS_TEMPLATE_NAME, model);
    }

    public String renderWallet(boolean showLogout, String coinId, Map<String, Object> walletData) {
        Map<String, Object> model = new HashMap<>();
        model.put("assetsPath", staticHostedPath);
        model.put("assetsVersion", staticAssetsVersion.get());
        model.put("queuePath", "/");
        model.put("auditLogPath", "/history");
        model.put("telegramPath", "/auth_channels/telegram");
        model.put("walletsPath", "/wallets");
        model.put("driverAgentPath", "/driver_agent");
        model.put("githubPath", "https://github.com/otherfren/konkin");
        model.put("authChannelsPath", "/auth_channels");
        model.put("apiKeysPath", "/auth_channels/api_keys");
        model.put("restApiKeyMissing", restApiKeyMissing);
        model.put("title", "KONKIN.io");
        model.put("showLogout", showLogout);
        model.put("activePage", "wallet_" + coinId);
        model.put("telegramPageAvailable", telegramEnabled);
        model.put("enabledCoins", enabledCoins);
        model.put("walletData", walletData == null ? Map.of() : walletData);

        return renderTemplate(WALLET_TEMPLATE_NAME, model);
    }

    public String renderAuthChannels(boolean showLogout, Map<String, Object> authChannelsData) {
        Map<String, Object> model = new HashMap<>();
        model.put("assetsPath", staticHostedPath);
        model.put("assetsVersion", staticAssetsVersion.get());
        model.put("queuePath", "/");
        model.put("auditLogPath", "/history");
        model.put("telegramPath", "/auth_channels/telegram");
        model.put("walletsPath", "/wallets");
        model.put("driverAgentPath", "/driver_agent");
        model.put("authChannelsPath", "/auth_channels");
        model.put("apiKeysPath", "/auth_channels/api_keys");
        model.put("restApiKeyMissing", restApiKeyMissing);
        model.put("githubPath", "https://github.com/otherfren/konkin");
        model.put("title", "KONKIN.io");
        model.put("showLogout", showLogout);
        model.put("activePage", "auth_channels");
        model.put("telegramPageAvailable", telegramEnabled);
        model.put("enabledCoins", enabledCoins);
        model.put("authChannels", authChannelsData == null ? Map.of() : authChannelsData);

        return renderTemplate(AUTH_CHANNELS_TEMPLATE_NAME, model);
    }

    public String renderAuthChannelWebUi(boolean showLogout, Map<String, Object> webUiData, String revealedPassword) {
        Map<String, Object> model = new HashMap<>();
        model.put("assetsPath", staticHostedPath);
        model.put("assetsVersion", staticAssetsVersion.get());
        model.put("queuePath", "/");
        model.put("auditLogPath", "/history");
        model.put("telegramPath", "/auth_channels/telegram");
        model.put("walletsPath", "/wallets");
        model.put("driverAgentPath", "/driver_agent");
        model.put("authChannelsPath", "/auth_channels");
        model.put("apiKeysPath", "/auth_channels/api_keys");
        model.put("restApiKeyMissing", restApiKeyMissing);
        model.put("githubPath", "https://github.com/otherfren/konkin");
        model.put("title", "KONKIN.io");
        model.put("showLogout", showLogout);
        model.put("activePage", "auth_channel_webui");
        model.put("telegramPageAvailable", telegramEnabled);
        model.put("enabledCoins", enabledCoins);
        model.put("webUi", webUiData == null ? Map.of() : webUiData);
        model.put("revealedPassword", revealedPassword != null ? revealedPassword : "");

        return renderTemplate(AUTH_CHANNEL_WEBUI_TEMPLATE_NAME, model);
    }

    public String renderDriverAgent(boolean showLogout, Map<String, Object> driverAgentData) {
        Map<String, Object> model = new HashMap<>();
        model.put("assetsPath", staticHostedPath);
        model.put("assetsVersion", staticAssetsVersion.get());
        model.put("queuePath", "/");
        model.put("auditLogPath", "/history");
        model.put("telegramPath", "/auth_channels/telegram");
        model.put("walletsPath", "/wallets");
        model.put("driverAgentPath", "/driver_agent");
        model.put("authChannelsPath", "/auth_channels");
        model.put("apiKeysPath", "/auth_channels/api_keys");
        model.put("restApiKeyMissing", restApiKeyMissing);
        model.put("githubPath", "https://github.com/otherfren/konkin");
        model.put("title", "KONKIN.io");
        model.put("showLogout", showLogout);
        model.put("activePage", "driver_agent");
        model.put("telegramPageAvailable", telegramEnabled);
        model.put("enabledCoins", enabledCoins);
        model.put("driverAgent", driverAgentData == null ? Map.of() : driverAgentData);

        return renderTemplate(DRIVER_AGENT_TEMPLATE_NAME, model);
    }

    public String renderLogin(boolean invalidPassword) {
        Map<String, Object> model = Map.of(
                "assetsPath", staticHostedPath,
                "assetsVersion", staticAssetsVersion.get(),
                "title", "KONKIN Login",
                "invalidPassword", invalidPassword
        );

        return renderTemplate(LOGIN_TEMPLATE_NAME, model);
    }

    public String renderSetup(String wizardStep, String password) {
        Map<String, Object> model = new HashMap<>();
        model.put("assetsPath", staticHostedPath);
        model.put("assetsVersion", staticAssetsVersion.get());
        model.put("title", "KONKIN Setup");
        model.put("wizardStep", wizardStep);
        model.put("password", password != null ? password : "");
        return renderTemplate(SETUP_TEMPLATE_NAME, model);
    }

    public String renderApiKeys(
            boolean showLogout,
            boolean restApiEnabled,
            boolean hasApiKey,
            String revealedApiKey,
            String secretFilePath,
            Map<String, Object> restApiChannelData
    ) {
        Map<String, Object> model = new HashMap<>();
        model.put("assetsPath", staticHostedPath);
        model.put("assetsVersion", staticAssetsVersion.get());
        model.put("queuePath", "/");
        model.put("auditLogPath", "/history");
        model.put("telegramPath", "/auth_channels/telegram");
        model.put("walletsPath", "/wallets");
        model.put("driverAgentPath", "/driver_agent");
        model.put("authChannelsPath", "/auth_channels");
        model.put("apiKeysPath", "/auth_channels/api_keys");
        model.put("restApiKeyMissing", restApiKeyMissing);
        model.put("githubPath", "https://github.com/otherfren/konkin");
        model.put("title", "KONKIN.io");
        model.put("showLogout", showLogout);
        model.put("activePage", "auth_channel_api_keys");
        model.put("telegramPageAvailable", telegramEnabled);
        model.put("enabledCoins", enabledCoins);
        model.put("restApiEnabled", restApiEnabled);
        model.put("hasApiKey", hasApiKey);
        model.put("revealedApiKey", revealedApiKey != null ? revealedApiKey : "");
        model.put("secretFilePath", secretFilePath != null ? secretFilePath : "");
        model.put("restApi", restApiChannelData == null ? Map.of() : restApiChannelData);
        return renderTemplate(API_KEYS_TEMPLATE_NAME, model);
    }

    public void clearTemplateCache() {
        freemarker.clearTemplateCache();
    }

    public void setRestApiKeyMissing(boolean missing) {
        this.restApiKeyMissing = missing;
    }

    public void setEnabledCoins(List<String> coins) {
        this.enabledCoins = coins == null ? List.of() : List.copyOf(coins);
    }

    public void markStaticAssetsChanged() {
        staticAssetsVersion.incrementAndGet();
    }

    private String renderTemplate(String template, Map<String, Object> model) {
        try {
            Template pageTemplate = freemarker.getTemplate(template);
            StringWriter out = new StringWriter();
            pageTemplate.process(model, out);
            return out.toString();
        } catch (IOException | TemplateException e) {
            throw new IllegalStateException("Failed to render landing template: " + template, e);
        }
    }
}