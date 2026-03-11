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

package io.konkin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder that produces a TOML config string for integration tests.
 * Reduces boilerplate when multiple tests need similar config structures.
 */
public class TestConfigBuilder {

    private final int port;
    private final List<String> sections = new ArrayList<>();

    private TestConfigBuilder(int port) {
        this.port = port;
    }

    public static TestConfigBuilder create(int port) {
        return new TestConfigBuilder(port);
    }

    // --- Database ---

    public TestConfigBuilder withDatabase(String dbUrl) {
        sections.add("""
                [database]
                url = "%s"
                """.formatted(dbUrl));
        return this;
    }

    public TestConfigBuilder withDatabase(String dbUrl, String user, String password, int poolSize) {
        sections.add("""
                [database]
                url = "%s"
                user = "%s"
                password = "%s"
                pool-size = %d
                """.formatted(dbUrl, user, password, poolSize));
        return this;
    }

    // --- Web UI / Landing ---

    public TestConfigBuilder withWebUi(boolean enabled) {
        sections.add("""
                [web-ui]
                enabled = %s
                """.formatted(enabled));
        return this;
    }

    public TestConfigBuilder withWebUiPasswordProtection(boolean enabled, Path passwordFile) {
        sections.add("""
                [web-ui.password-protection]
                enabled = %s
                password-file = "%s"
                """.formatted(enabled, tomlPath(passwordFile)));
        return this;
    }

    public TestConfigBuilder withWebUiTemplate(Path directory) {
        sections.add("""
                [web-ui.template]
                directory = "%s"
                name = "landing.ftl"
                """.formatted(tomlPath(directory)));
        return this;
    }

    public TestConfigBuilder withWebUiStatic(Path directory) {
        sections.add("""
                [web-ui.static]
                directory = "%s"
                hosted-path = "/assets"
                """.formatted(tomlPath(directory)));
        return this;
    }

    public TestConfigBuilder withAutoReload(boolean enabled) {
        sections.add("""
                [web-ui.auto-reload]
                enabled = %s
                assets-enabled = %s
                """.formatted(enabled, enabled));
        return this;
    }

    public TestConfigBuilder withAutoReload(boolean enabled, boolean assetsEnabled) {
        sections.add("""
                [web-ui.auto-reload]
                enabled = %s
                assets-enabled = %s
                """.formatted(enabled, assetsEnabled));
        return this;
    }

    // --- Landing (legacy naming, used in WebLandingTelegramIntegrationTest) ---

    public TestConfigBuilder withLanding(boolean enabled) {
        sections.add("""
                [landing]
                enabled = %s
                """.formatted(enabled));
        return this;
    }

    public TestConfigBuilder withLandingPasswordProtection(boolean enabled, Path passwordFile) {
        sections.add("""
                [landing.password-protection]
                enabled = %s
                password-file = "%s"
                """.formatted(enabled, tomlPath(passwordFile)));
        return this;
    }

    public TestConfigBuilder withLandingTemplate(Path directory) {
        sections.add("""
                [landing.template]
                directory = "%s"
                name = "landing.ftl"
                """.formatted(tomlPath(directory)));
        return this;
    }

    public TestConfigBuilder withLandingStatic(Path directory) {
        sections.add("""
                [landing.static]
                directory = "%s"
                hosted-path = "/assets"
                """.formatted(tomlPath(directory)));
        return this;
    }

    public TestConfigBuilder withLandingAutoReload(boolean enabled) {
        sections.add("""
                [landing.auto-reload]
                enabled = %s
                """.formatted(enabled));
        return this;
    }

    public TestConfigBuilder withLandingAutoReload(boolean enabled, boolean assetsEnabled) {
        sections.add("""
                [landing.auto-reload]
                enabled = %s
                assets-enabled = %s
                """.formatted(enabled, assetsEnabled));
        return this;
    }

    // --- REST API ---

    public TestConfigBuilder withRestApi(boolean enabled) {
        sections.add("""
                [rest-api]
                enabled = %s
                """.formatted(enabled));
        return this;
    }

    public TestConfigBuilder withRestApiSecret(Path secretFile) {
        sections.add("""
                [rest-api]
                enabled = true
                secret-file = "%s"
                """.formatted(tomlPath(secretFile)));
        return this;
    }

    // --- Telegram ---

    public TestConfigBuilder withTelegram(boolean enabled) {
        sections.add("""
                [telegram]
                enabled = %s
                """.formatted(enabled));
        return this;
    }

    public TestConfigBuilder withTelegramSecret(Path secretFile) {
        sections.add("""
                [telegram]
                enabled = true
                secret-file = "%s"
                """.formatted(tomlPath(secretFile)));
        return this;
    }

    public TestConfigBuilder withTelegramFull(Path secretFile, String autoDenyTimeout) {
        sections.add("""
                [telegram]
                enabled = true
                secret-file = "%s"
                auto-deny-timeout = "%s"
                """.formatted(tomlPath(secretFile), autoDenyTimeout));
        return this;
    }

    public TestConfigBuilder withTelegramAutoDenyTimeout(String timeout) {
        sections.add("""
                [telegram]
                auto-deny-timeout = "%s"
                """.formatted(timeout));
        return this;
    }

    public TestConfigBuilder withTelegramApiBaseUrl(Path secretFile, String apiBaseUrl) {
        sections.add("""
                [telegram]
                enabled = true
                secret-file = "%s"
                api-base-url = "%s"
                """.formatted(tomlPath(secretFile), apiBaseUrl));
        return this;
    }

    public TestConfigBuilder withTelegramApiBaseUrlAndChatIds(Path secretFile, String apiBaseUrl, String... chatIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                [telegram]
                enabled = true
                secret-file = "%s"
                api-base-url = "%s"
                """.formatted(tomlPath(secretFile), apiBaseUrl));
        if (chatIds.length > 0) {
            sb.append("chat-ids = [");
            for (int i = 0; i < chatIds.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append("\"").append(chatIds[i]).append("\"");
            }
            sb.append("]\n");
        }
        sections.add(sb.toString());
        return this;
    }

    // --- Bitcoin ---

    public TestConfigBuilder withBitcoin(Path daemonFile, Path walletFile) {
        sections.add("""
                [coins.bitcoin]
                enabled = true

                [coins.bitcoin.secret-files]
                bitcoin-daemon-config-file = "%s"
                bitcoin-wallet-config-file = "%s"
                """.formatted(tomlPath(daemonFile), tomlPath(walletFile)));
        return this;
    }

    public TestConfigBuilder withBitcoinEnabled(boolean enabled, Path daemonFile, Path walletFile) {
        sections.add("""
                [coins.bitcoin]
                enabled = %s

                [coins.bitcoin.secret-files]
                bitcoin-daemon-config-file = "%s"
                bitcoin-wallet-config-file = "%s"
                """.formatted(enabled, tomlPath(daemonFile), tomlPath(walletFile)));
        return this;
    }

    public TestConfigBuilder withBitcoinAuth(boolean webUi, boolean restApi, boolean telegram) {
        sections.add("""
                [coins.bitcoin.auth]
                web-ui = %s
                rest-api = %s
                telegram = %s
                """.formatted(webUi, restApi, telegram));
        return this;
    }

    public TestConfigBuilder withBitcoinAuthMcp(boolean webUi, boolean restApi, boolean telegram, String mcp) {
        sections.add("""
                [coins.bitcoin.auth]
                web-ui = %s
                rest-api = %s
                telegram = %s
                mcp = "%s"
                """.formatted(webUi, restApi, telegram, mcp));
        return this;
    }

    public TestConfigBuilder withBitcoinAuthFull(boolean webUi, boolean restApi, boolean telegram, String mcp,
                                                  List<String> mcpAuthChannels, int minApprovalsRequired) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                [coins.bitcoin.auth]
                web-ui = %s
                rest-api = %s
                telegram = %s
                """.formatted(webUi, restApi, telegram));
        if (mcp != null) {
            sb.append("mcp = \"%s\"\n".formatted(mcp));
        }
        if (mcpAuthChannels != null) {
            sb.append("mcp-auth-channels = [");
            for (int i = 0; i < mcpAuthChannels.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("\"").append(mcpAuthChannels.get(i)).append("\"");
            }
            sb.append("]\n");
        }
        sb.append("min-approvals-required = %d\n".formatted(minApprovalsRequired));
        sections.add(sb.toString());
        return this;
    }

    public TestConfigBuilder withBitcoinAuthWithMcpAuthChannels(boolean webUi, boolean restApi, boolean telegram,
                                                                 List<String> mcpAuthChannels) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                [coins.bitcoin.auth]
                web-ui = %s
                rest-api = %s
                telegram = %s
                """.formatted(webUi, restApi, telegram));
        sb.append("mcp-auth-channels = [");
        for (int i = 0; i < mcpAuthChannels.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(mcpAuthChannels.get(i)).append("\"");
        }
        sb.append("]\n");
        sections.add(sb.toString());
        return this;
    }

    public TestConfigBuilder withBitcoinAuthWithMcpChannelsAndQuorum(boolean webUi, boolean restApi, boolean telegram,
                                                                      List<String> mcpAuthChannels,
                                                                      int minApprovalsRequired,
                                                                      List<String> vetoChannels) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                [coins.bitcoin.auth]
                web-ui = %s
                rest-api = %s
                telegram = %s
                """.formatted(webUi, restApi, telegram));
        sb.append("mcp-auth-channels = [");
        for (int i = 0; i < mcpAuthChannels.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(mcpAuthChannels.get(i)).append("\"");
        }
        sb.append("]\n");
        sb.append("min-approvals-required = %d\n".formatted(minApprovalsRequired));
        if (vetoChannels != null && !vetoChannels.isEmpty()) {
            sb.append("veto-channels = [");
            for (int i = 0; i < vetoChannels.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("\"").append(vetoChannels.get(i)).append("\"");
            }
            sb.append("]\n");
        }
        sections.add(sb.toString());
        return this;
    }

    public TestConfigBuilder withBitcoinAuthMinApprovals(boolean webUi, boolean restApi, boolean telegram,
                                                          String mcp, int minApprovalsRequired) {
        sections.add("""
                [coins.bitcoin.auth]
                web-ui = %s
                rest-api = %s
                telegram = %s
                mcp = "%s"
                min-approvals-required = %d
                """.formatted(webUi, restApi, telegram, mcp, minApprovalsRequired));
        return this;
    }

    public TestConfigBuilder withBitcoinAuthVetoChannels(boolean webUi, boolean restApi, boolean telegram,
                                                          String mcp, String... vetoChannels) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                [coins.bitcoin.auth]
                web-ui = %s
                rest-api = %s
                telegram = %s
                mcp = "%s"
                """.formatted(webUi, restApi, telegram, mcp));
        sb.append("veto-channels = [");
        for (int i = 0; i < vetoChannels.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(vetoChannels[i]).append("\"");
        }
        sb.append("]\n");
        sections.add(sb.toString());
        return this;
    }

    public TestConfigBuilder withBitcoinAutoAcceptRule(String type, double value) {
        sections.add("""
                [[coins.bitcoin.auth.auto-accept]]
                [coins.bitcoin.auth.auto-accept.criteria]
                type = "%s"
                value = %s
                """.formatted(type, formatDouble(value)));
        return this;
    }

    public TestConfigBuilder withBitcoinAutoAcceptRule(String type, double value, String period) {
        sections.add("""
                [[coins.bitcoin.auth.auto-accept]]
                [coins.bitcoin.auth.auto-accept.criteria]
                type = "%s"
                value = %s
                period = "%s"
                """.formatted(type, formatDouble(value), period));
        return this;
    }

    public TestConfigBuilder withBitcoinAutoDenyRule(String type, double value) {
        sections.add("""
                [[coins.bitcoin.auth.auto-deny]]
                [coins.bitcoin.auth.auto-deny.criteria]
                type = "%s"
                value = %s
                """.formatted(type, formatDouble(value)));
        return this;
    }

    public TestConfigBuilder withBitcoinAutoDenyRule(String type, double value, String period) {
        sections.add("""
                [[coins.bitcoin.auth.auto-deny]]
                [coins.bitcoin.auth.auto-deny.criteria]
                type = "%s"
                value = %s
                period = "%s"
                """.formatted(type, formatDouble(value), period));
        return this;
    }

    // --- Litecoin ---

    public TestConfigBuilder withLitecoin(Path daemonFile, Path walletFile) {
        sections.add("""
                [coins.litecoin]
                enabled = true

                [coins.litecoin.secret-files]
                litecoin-daemon-config-file = "%s"
                litecoin-wallet-config-file = "%s"
                """.formatted(tomlPath(daemonFile), tomlPath(walletFile)));
        return this;
    }

    public TestConfigBuilder withLitecoinAuth(boolean webUi, boolean restApi, boolean telegram) {
        sections.add("""
                [coins.litecoin.auth]
                web-ui = %s
                rest-api = %s
                telegram = %s
                """.formatted(webUi, restApi, telegram));
        return this;
    }

    // --- Monero ---

    public TestConfigBuilder withMonero(Path daemonFile, Path walletFile) {
        sections.add("""
                [coins.monero]
                enabled = true

                [coins.monero.secret-files]
                monero-daemon-config-file = "%s"
                monero-wallet-config-file = "%s"
                """.formatted(tomlPath(daemonFile), tomlPath(walletFile)));
        return this;
    }

    public TestConfigBuilder withMoneroAuth(boolean webUi, boolean restApi, boolean telegram) {
        sections.add("""
                [coins.monero.auth]
                web-ui = %s
                rest-api = %s
                telegram = %s
                """.formatted(webUi, restApi, telegram));
        return this;
    }

    // --- Debug ---

    public TestConfigBuilder withDebug(boolean enabled) {
        sections.add("""
                [debug]
                enabled = %s
                """.formatted(enabled));
        return this;
    }

    public TestConfigBuilder withDebugSeedFakeData(boolean seed) {
        sections.add("""
                [debug]
                enabled = true
                seed-fake-data = %s
                """.formatted(seed));
        return this;
    }

    // --- Test Dummy Coin ---

    public TestConfigBuilder withTestDummyCoin(boolean enabled) {
        sections.add("""
                [coins.testdummycoin]
                enabled = %s
                """.formatted(enabled));
        return this;
    }

    public TestConfigBuilder withTestDummyCoinAuth(boolean webUi, boolean restApi, boolean telegram,
                                                    String mcp, List<String> mcpAuthChannels, int minApprovalsRequired) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                [coins.testdummycoin.auth]
                web-ui = %s
                rest-api = %s
                telegram = %s
                """.formatted(webUi, restApi, telegram));
        if (mcp != null) {
            sb.append("mcp = \"%s\"\n".formatted(mcp));
        }
        if (mcpAuthChannels != null) {
            sb.append("mcp-auth-channels = [");
            for (int i = 0; i < mcpAuthChannels.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("\"").append(mcpAuthChannels.get(i)).append("\"");
            }
            sb.append("]\n");
        }
        sb.append("min-approvals-required = %d\n".formatted(minApprovalsRequired));
        sections.add(sb.toString());
        return this;
    }

    // --- Agents ---

    public TestConfigBuilder withPrimaryAgent(boolean enabled, String bind, int agentPort, Path secretFile) {
        sections.add("""
                [agents.primary]
                bind = "%s"
                port = %d
                secret-file = "%s"
                """.formatted(bind, agentPort, tomlPath(secretFile)));
        return this;
    }

    public TestConfigBuilder withSecondaryAgent(String name, boolean visible, String bind, int agentPort, Path secretFile) {
        sections.add("""
                [agents.secondary.%s]
                visible = %s
                bind = "%s"
                port = %d
                secret-file = "%s"
                """.formatted(name, visible, bind, agentPort, tomlPath(secretFile)));
        return this;
    }

    // --- Raw TOML (escape hatch for unique one-off sections) ---

    public TestConfigBuilder withRawToml(String toml) {
        sections.add(toml);
        return this;
    }

    // --- Build ---

    public String build() {
        StringBuilder sb = new StringBuilder();
        sb.append("config-version = 1\n\n");
        sb.append("[server]\n");
        sb.append("host = \"127.0.0.1\"\n");
        sb.append("port = ").append(port).append("\n");

        for (String section : sections) {
            sb.append("\n");
            sb.append(section);
        }

        return sb.toString();
    }

    // --- Utilities ---

    static String tomlPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("\\", "\\\\");
    }

    private static String formatDouble(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf(value);
        }
        return String.valueOf(value);
    }
}
