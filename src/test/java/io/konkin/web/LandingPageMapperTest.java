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

package io.konkin.web;

import io.konkin.TestConfigBuilder;
import io.konkin.config.KonkinConfig;
import io.konkin.crypto.Coin;
import io.konkin.crypto.WalletConnectionConfig;
import io.konkin.crypto.WalletSnapshot;
import io.konkin.crypto.WalletStatus;
import io.konkin.crypto.WalletSupervisor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LandingPageMapperTest {

    @TempDir
    Path tempDir;

    // ── Helper methods ────────────────────────────────────────────────────

    private String writeConfig(String toml) throws IOException {
        Path path = tempDir.resolve("config-" + System.nanoTime() + ".toml");
        Files.writeString(path, toml, StandardCharsets.UTF_8);
        return path.toString();
    }

    private Path writeBitcoinDaemonSecret(String rpcUser, String rpcPassword, String rpcConnect, String rpcPort) throws IOException {
        Path file = tempDir.resolve("bitcoin-daemon-" + System.nanoTime() + ".conf");
        String content = "rpcuser=%s\nrpcpassword=%s\nrpcconnect=%s\nrpcport=%s\n"
                .formatted(rpcUser, rpcPassword, rpcConnect, rpcPort);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private Path writeBitcoinWalletSecret(String walletName) throws IOException {
        Path file = tempDir.resolve("bitcoin-wallet-" + System.nanoTime() + ".conf");
        String content = walletName.isEmpty() ? "" : "wallet=%s\n".formatted(walletName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private Path writeLitecoinDaemonSecret(String rpcUser, String rpcPassword, String rpcConnect, String rpcPort) throws IOException {
        Path file = tempDir.resolve("litecoin-daemon-" + System.nanoTime() + ".conf");
        String content = "rpcuser=%s\nrpcpassword=%s\nrpcconnect=%s\nrpcport=%s\n"
                .formatted(rpcUser, rpcPassword, rpcConnect, rpcPort);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private Path writeLitecoinWalletSecret(String walletName) throws IOException {
        Path file = tempDir.resolve("litecoin-wallet-" + System.nanoTime() + ".conf");
        String content = walletName.isEmpty() ? "" : "wallet=%s\n".formatted(walletName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private Path writeMoneroDaemonSecret(String host, String port) throws IOException {
        Path file = tempDir.resolve("monero-daemon-" + System.nanoTime() + ".conf");
        String content = "rpc-bind-ip=%s\nrpc-bind-port=%s\n".formatted(host, port);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private Path writeMoneroWalletRpcSecret(String host, String port, String user, String password) throws IOException {
        Path file = tempDir.resolve("monero-wallet-rpc-" + System.nanoTime() + ".conf");
        String content = "rpc-bind-ip=%s\nrpc-bind-port=%s\nrpc-login=%s:%s\n"
                .formatted(host, port, user, password);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    @Test
    void buildWalletsModelReturnsAllThreeCoinsWhenNoneConfigured() throws Exception {
        String toml = TestConfigBuilder.create(9999)
                .withDatabase("jdbc:h2:mem:mapper-test-none;DB_CLOSE_DELAY=-1")
                .build();
        KonkinConfig config = KonkinConfig.load(writeConfig(toml));

        LandingPageMapper mapper = new LandingPageMapper(config, Map.of());

        Map<String, Object> model = mapper.buildWalletsModel();
        assertNotNull(model);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> coins = (List<Map<String, Object>>) model.get("coins");
        assertNotNull(coins);
        assertEquals(3, coins.size(), "Should always list all 3 known coins");

        // Verify all 3 coin ids are present
        List<String> coinIds = coins.stream().map(c -> (String) c.get("coin")).toList();
        assertTrue(coinIds.contains("bitcoin"));
        assertTrue(coinIds.contains("litecoin"));
        assertTrue(coinIds.contains("monero"));

        // All should be unconfigured / disabled
        for (Map<String, Object> coin : coins) {
            assertFalse(Boolean.TRUE.equals(coin.get("enabled")),
                    coin.get("coin") + " should not be enabled");
        }
    }

    @Test
    void unconfiguredCoinHasConfiguredFalseAndMaskedConfigNull() throws Exception {
        String toml = TestConfigBuilder.create(9999)
                .withDatabase("jdbc:h2:mem:mapper-test-unconf;DB_CLOSE_DELAY=-1")
                .build();
        KonkinConfig config = KonkinConfig.load(writeConfig(toml));

        LandingPageMapper mapper = new LandingPageMapper(config, Map.of());
        Map<String, Object> model = mapper.buildWalletsModel();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> coins = (List<Map<String, Object>>) model.get("coins");

        for (Map<String, Object> coin : coins) {
            // All coins are not configured (no secret files, or disabled with no files)
            // They should have connectionStatus either "disabled" or "not configured"
            String status = (String) coin.get("connectionStatus");
            assertTrue("not configured".equals(status) || "disabled".equals(status),
                    coin.get("coin") + " should be 'not configured' or 'disabled', got: " + status);
            assertTrue(Boolean.TRUE.equals(coin.get("disconnected")),
                    coin.get("coin") + " should be disconnected");
        }
    }

    @Test
    void configuredBitcoinHasMaskedConfigWithExpectedKeys() throws Exception {
        Path daemonFile = writeBitcoinDaemonSecret("myrpcuser", "secretpass", "127.0.0.1", "8332");
        Path walletFile = writeBitcoinWalletSecret("default");

        String toml = TestConfigBuilder.create(9999)
                .withDatabase("jdbc:h2:mem:mapper-test-btc;DB_CLOSE_DELAY=-1")
                .withWebUi(true)
                .withBitcoin(daemonFile, walletFile)
                .withBitcoinAuth(true, false, false)
                .build();
        KonkinConfig config = KonkinConfig.load(writeConfig(toml));

        LandingPageMapper mapper = new LandingPageMapper(config, Map.of());
        Map<String, Object> model = mapper.buildWalletsModel();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> coins = (List<Map<String, Object>>) model.get("coins");

        Map<String, Object> btc = coins.stream()
                .filter(c -> "bitcoin".equals(c.get("coin")))
                .findFirst().orElseThrow();

        assertTrue(Boolean.TRUE.equals(btc.get("configured")), "Bitcoin should be configured");
        assertTrue(Boolean.TRUE.equals(btc.get("enabled")), "Bitcoin should be enabled");

        @SuppressWarnings("unchecked")
        Map<String, String> maskedConfig = (Map<String, String>) btc.get("maskedConfig");
        assertNotNull(maskedConfig, "maskedConfig should not be null for configured coin");
        assertEquals("127.0.0.1:8332", maskedConfig.get("rpcEndpoint"));
        assertEquals("myrpcuser", maskedConfig.get("rpcUser"));
        assertEquals("default", maskedConfig.get("walletName"));

        // Password should NOT be present
        assertFalse(maskedConfig.containsKey("rpcPassword"), "maskedConfig must not contain password");
    }

    @Test
    void configuredMoneroHasMaskedConfigWithExpectedKeys() throws Exception {
        Path daemonFile = writeMoneroDaemonSecret("127.0.0.1", "18081");
        Path walletRpcFile = writeMoneroWalletRpcSecret("127.0.0.1", "18083", "monero-rpc", "secret123");

        String toml = TestConfigBuilder.create(9999)
                .withDatabase("jdbc:h2:mem:mapper-test-xmr;DB_CLOSE_DELAY=-1")
                .withWebUi(true)
                .withRawToml("""
                        [coins.monero]
                        enabled = true

                        [coins.monero.secret-files]
                        monero-daemon-config-file = "%s"
                        monero-wallet-rpc-config-file = "%s"
                        """.formatted(
                        daemonFile.toAbsolutePath().normalize().toString().replace("\\", "\\\\"),
                        walletRpcFile.toAbsolutePath().normalize().toString().replace("\\", "\\\\")))
                .withMoneroAuth(true, false, false)
                .build();
        KonkinConfig config = KonkinConfig.load(writeConfig(toml));

        LandingPageMapper mapper = new LandingPageMapper(config, Map.of());
        Map<String, Object> model = mapper.buildWalletsModel();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> coins = (List<Map<String, Object>>) model.get("coins");

        Map<String, Object> xmr = coins.stream()
                .filter(c -> "monero".equals(c.get("coin")))
                .findFirst().orElseThrow();

        assertTrue(Boolean.TRUE.equals(xmr.get("configured")), "Monero should be configured");

        @SuppressWarnings("unchecked")
        Map<String, String> maskedConfig = (Map<String, String>) xmr.get("maskedConfig");
        assertNotNull(maskedConfig, "maskedConfig should not be null for configured Monero");
        assertEquals("127.0.0.1:18081", maskedConfig.get("daemonEndpoint"));
        assertEquals("127.0.0.1:18083", maskedConfig.get("walletRpcEndpoint"));
        assertEquals("monero-rpc", maskedConfig.get("walletRpcUser"));

        // Passwords should NOT be present
        assertFalse(maskedConfig.containsKey("walletRpcPassword"), "maskedConfig must not contain password");
        assertFalse(maskedConfig.containsKey("daemonPassword"), "maskedConfig must not contain daemon password");
    }

    @Test
    void sortOrderConfiguredConnectedFirst_UnconfiguredLast() throws Exception {
        Path btcDaemon = writeBitcoinDaemonSecret("u", "p", "127.0.0.1", "8332");
        Path btcWallet = writeBitcoinWalletSecret("w");
        Path ltcDaemon = writeLitecoinDaemonSecret("u", "p", "127.0.0.1", "9332");
        Path ltcWallet = writeLitecoinWalletSecret("");

        String toml = TestConfigBuilder.create(9999)
                .withDatabase("jdbc:h2:mem:mapper-test-sort;DB_CLOSE_DELAY=-1")
                .withWebUi(true)
                .withBitcoin(btcDaemon, btcWallet)
                .withBitcoinAuth(true, false, false)
                .withLitecoin(ltcDaemon, ltcWallet)
                .withLitecoinAuth(true, false, false)
                // Monero not configured
                .build();
        KonkinConfig config = KonkinConfig.load(writeConfig(toml));

        // Create a mock-like supervisor that reports AVAILABLE for bitcoin
        WalletSupervisor btcSupervisor = new StubWalletSupervisor(Coin.BTC, WalletStatus.AVAILABLE);
        Map<Coin, WalletSupervisor> supervisors = new LinkedHashMap<>();
        supervisors.put(Coin.BTC, btcSupervisor);
        // Litecoin has no supervisor (disconnected but configured)

        LandingPageMapper mapper = new LandingPageMapper(config, supervisors);
        Map<String, Object> model = mapper.buildWalletsModel();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> coins = (List<Map<String, Object>>) model.get("coins");

        assertEquals(3, coins.size());
        // Bitcoin: configured + connected → sort 0
        assertEquals("bitcoin", coins.get(0).get("coin"), "Connected configured coin should come first");
        // Litecoin: configured + disconnected → sort 1
        assertEquals("litecoin", coins.get(1).get("coin"), "Disconnected configured coin should come second");
        // Monero: unconfigured → sort 3
        assertEquals("monero", coins.get(2).get("coin"), "Unconfigured coin should come last");
    }

    @Test
    void buildSingleCoinWalletModelReturnsModelForUnconfiguredCoin() throws Exception {
        String toml = TestConfigBuilder.create(9999)
                .withDatabase("jdbc:h2:mem:mapper-test-single;DB_CLOSE_DELAY=-1")
                .build();
        KonkinConfig config = KonkinConfig.load(writeConfig(toml));

        LandingPageMapper mapper = new LandingPageMapper(config, Map.of());

        // Even for a coin that's not enabled, should return a model (not null)
        Map<String, Object> model = mapper.buildSingleCoinWalletModel("bitcoin");
        assertNotNull(model, "buildSingleCoinWalletModel should return non-null for unconfigured coin");

        @SuppressWarnings("unchecked")
        Map<String, Object> coin = (Map<String, Object>) model.get("coin");
        assertNotNull(coin);
        assertEquals("bitcoin", coin.get("coin"));
        assertFalse(Boolean.TRUE.equals(coin.get("configured")));
        assertEquals("not configured", coin.get("connectionStatus"));
    }

    @Test
    void getAllKnownCoinIdsReturnsExpectedList() throws Exception {
        String toml = TestConfigBuilder.create(9999)
                .withDatabase("jdbc:h2:mem:mapper-test-ids;DB_CLOSE_DELAY=-1")
                .build();
        KonkinConfig config = KonkinConfig.load(writeConfig(toml));

        LandingPageMapper mapper = new LandingPageMapper(config, Map.of());
        List<String> ids = mapper.getAllKnownCoinIds();
        assertEquals(List.of("bitcoin", "litecoin", "monero"), ids);
    }

    @Test
    void missingSecretFilesResultInConfiguredFalse() throws Exception {
        // Create secret files with enabled=true, then delete them after config loads.
        // The bootstrapper will create defaults, so we delete them to simulate missing files.
        Path daemonFile = tempDir.resolve("btc-missing-daemon.conf");
        Path walletFile = tempDir.resolve("btc-missing-wallet.conf");

        String toml = TestConfigBuilder.create(9999)
                .withDatabase("jdbc:h2:mem:mapper-test-missing;DB_CLOSE_DELAY=-1")
                .withWebUi(true)
                .withBitcoinEnabled(true, daemonFile, walletFile)
                .withBitcoinAuth(true, false, false)
                .build();
        KonkinConfig config = KonkinConfig.load(writeConfig(toml));

        // Delete the secret files that the bootstrapper created
        Files.deleteIfExists(daemonFile);
        Files.deleteIfExists(walletFile);

        LandingPageMapper mapper = new LandingPageMapper(config, Map.of());
        Map<String, Object> model = mapper.buildWalletsModel();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> coins = (List<Map<String, Object>>) model.get("coins");

        Map<String, Object> btc = coins.stream()
                .filter(c -> "bitcoin".equals(c.get("coin")))
                .findFirst().orElseThrow();

        assertFalse(Boolean.TRUE.equals(btc.get("configured")),
                "Bitcoin with missing secret files should not be configured");
        assertNull(btc.get("maskedConfig"),
                "maskedConfig should be null when secret files are missing");
        assertEquals("not configured", btc.get("connectionStatus"));
    }

    // ── Stub WalletSupervisor for sort-order test ─────────────────────────

    /**
     * Minimal stub that returns a fixed WalletSnapshot.
     * Only the snapshot() method is used by buildWalletOverviewEntry.
     */
    private static class StubWalletSupervisor extends WalletSupervisor {
        private final WalletSnapshot fixedSnapshot;

        StubWalletSupervisor(Coin coin, WalletStatus status) {
            super(new WalletConnectionConfig(coin, "http://127.0.0.1:1", "", "", Map.of()), null);
            this.fixedSnapshot = new WalletSnapshot(coin, status, null, null, null);
        }

        @Override
        public WalletSnapshot snapshot() {
            return fixedSnapshot;
        }

        @Override
        public void start() {
            // no-op
        }
    }
}
