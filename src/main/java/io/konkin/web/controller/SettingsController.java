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
import io.konkin.config.KonkinConfig;
import io.konkin.web.LandingPageMapper;
import io.konkin.web.service.LandingPageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
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
    private final KonkinConfig config;
    private final boolean passwordProtectionEnabled;
    private final Path restApiSecretFilePath;
    private final AtomicReference<String> activeApiKey;
    private final Predicate<Context> sessionValidator;
    private final Consumer<Context> loginRedirect;

    public SettingsController(
            LandingPageService landingPageService,
            LandingPageMapper mapper,
            KonkinConfig config,
            boolean passwordProtectionEnabled,
            Path restApiSecretFilePath,
            AtomicReference<String> activeApiKey,
            Predicate<Context> sessionValidator,
            Consumer<Context> loginRedirect
    ) {
        this.landingPageService = landingPageService;
        this.mapper = mapper;
        this.config = config;
        this.passwordProtectionEnabled = passwordProtectionEnabled;
        this.restApiSecretFilePath = restApiSecretFilePath;
        this.activeApiKey = activeApiKey != null ? activeApiKey : new AtomicReference<>();
        this.sessionValidator = sessionValidator;
        this.loginRedirect = loginRedirect;
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

        boolean restApiEnabled = config.restApiEnabled();
        boolean hasKey = activeApiKey.get() != null;
        String secretFile = restApiSecretFilePath != null ? restApiSecretFilePath.toString() : "";
        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(landingPageService.renderApiKeys(
                passwordProtectionEnabled,
                restApiEnabled, hasKey, "", secretFile,
                mapper.buildRestApiChannelModel()));
    }

    public void handleApiKeysRotate(Context ctx) {
        if (passwordProtectionEnabled && !sessionValidator.test(ctx)) {
            loginRedirect.accept(ctx);
            return;
        }

        if (!config.restApiEnabled() || restApiSecretFilePath == null) {
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
                mapper.buildRestApiChannelModel()));
    }

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
