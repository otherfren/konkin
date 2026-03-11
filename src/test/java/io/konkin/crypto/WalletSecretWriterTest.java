package io.konkin.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WalletSecretWriterTest {

    @TempDir Path tempDir;

    // ── Bitcoin round-trip ──

    @Test void bitcoinRoundTripWithWallet() {
        WalletSecretWriter.WrittenSecrets written = WalletSecretWriter.writeBitcoinSecrets(
                tempDir, "192.168.1.5", "8332", "myuser", "mypass", "mywallet");

        assertEquals(tempDir.resolve("bitcoin-daemon.conf"), written.daemonConfigPath());
        assertEquals(tempDir.resolve("bitcoin-wallet.conf"), written.walletConfigPath());

        WalletConnectionConfig cfg = WalletSecretLoader.loadBitcoin(
                written.daemonConfigPath().toString(), written.walletConfigPath().toString());

        assertEquals(Coin.BTC, cfg.coin());
        assertEquals("http://192.168.1.5:8332", cfg.rpcUrl());
        assertEquals("myuser", cfg.username());
        assertEquals("mypass", cfg.password());
        assertEquals("mywallet", cfg.extras().get("walletName"));
    }

    @Test void bitcoinRoundTripWithoutWallet() {
        WalletSecretWriter.WrittenSecrets written = WalletSecretWriter.writeBitcoinSecrets(
                tempDir, "127.0.0.1", "8332", "user1", "pass1", "");

        WalletConnectionConfig cfg = WalletSecretLoader.loadBitcoin(
                written.daemonConfigPath().toString(), written.walletConfigPath().toString());

        assertEquals("http://127.0.0.1:8332", cfg.rpcUrl());
        assertEquals("user1", cfg.username());
        assertEquals("pass1", cfg.password());
        assertFalse(cfg.extras().containsKey("walletName"));
    }

    @Test void bitcoinRoundTripNullWallet() {
        WalletSecretWriter.WrittenSecrets written = WalletSecretWriter.writeBitcoinSecrets(
                tempDir, "127.0.0.1", "18332", "u", "p", null);

        WalletConnectionConfig cfg = WalletSecretLoader.loadBitcoin(
                written.daemonConfigPath().toString(), written.walletConfigPath().toString());

        assertEquals("http://127.0.0.1:18332", cfg.rpcUrl());
        assertFalse(cfg.extras().containsKey("walletName"));
        assertEquals("testnet", cfg.extras().get("network"));
    }

    // ── Litecoin round-trip ──

    @Test void litecoinRoundTrip() {
        WalletSecretWriter.WrittenSecrets written = WalletSecretWriter.writeLitecoinSecrets(
                tempDir, "10.0.0.1", "9332", "ltcuser", "ltcpass", "ltcwallet");

        assertEquals(tempDir.resolve("litecoin-daemon.conf"), written.daemonConfigPath());
        assertEquals(tempDir.resolve("litecoin-wallet.conf"), written.walletConfigPath());

        WalletConnectionConfig cfg = WalletSecretLoader.loadLitecoin(
                written.daemonConfigPath().toString(), written.walletConfigPath().toString());

        assertEquals(Coin.LTC, cfg.coin());
        assertEquals("http://10.0.0.1:9332", cfg.rpcUrl());
        assertEquals("ltcuser", cfg.username());
        assertEquals("ltcpass", cfg.password());
        assertEquals("ltcwallet", cfg.extras().get("walletName"));
    }

    @Test void litecoinRoundTripWithoutWallet() {
        WalletSecretWriter.WrittenSecrets written = WalletSecretWriter.writeLitecoinSecrets(
                tempDir, "127.0.0.1", "9332", "u", "p", "");

        WalletConnectionConfig cfg = WalletSecretLoader.loadLitecoin(
                written.daemonConfigPath().toString(), written.walletConfigPath().toString());

        assertFalse(cfg.extras().containsKey("walletName"));
    }

    // ── Monero round-trip ──

    @Test void moneroRoundTripWithDaemonLogin() {
        WalletSecretWriter.WrittenSecrets written = WalletSecretWriter.writeMoneroSecrets(
                tempDir, "127.0.0.1", "18081", "duser", "dpass",
                "127.0.0.1", "18083", "wuser", "wpass");

        assertEquals(tempDir.resolve("monero-daemon.conf"), written.daemonConfigPath());
        assertEquals(tempDir.resolve("monero-wallet-rpc.conf"), written.walletConfigPath());

        WalletConnectionConfig cfg = WalletSecretLoader.loadMonero(
                written.daemonConfigPath().toString(), written.walletConfigPath().toString());

        assertEquals(Coin.XMR, cfg.coin());
        assertEquals("http://127.0.0.1:18083", cfg.rpcUrl());
        assertEquals("wuser", cfg.username());
        assertEquals("wpass", cfg.password());
        assertEquals("http://127.0.0.1:18081", cfg.extras().get("daemonRpcUrl"));
        assertEquals("duser", cfg.extras().get("daemonRpcUsername"));
        assertEquals("dpass", cfg.extras().get("daemonRpcPassword"));
        assertEquals("mainnet", cfg.extras().get("network"));
    }

    @Test void moneroRoundTripWithoutDaemonLogin() {
        WalletSecretWriter.WrittenSecrets written = WalletSecretWriter.writeMoneroSecrets(
                tempDir, "127.0.0.1", "18081", "", "",
                "127.0.0.1", "18083", "wuser", "wpass");

        WalletConnectionConfig cfg = WalletSecretLoader.loadMonero(
                written.daemonConfigPath().toString(), written.walletConfigPath().toString());

        assertEquals("wuser", cfg.username());
        assertEquals("wpass", cfg.password());
        assertFalse(cfg.extras().containsKey("daemonRpcUsername"));
        assertFalse(cfg.extras().containsKey("daemonRpcPassword"));
    }

    @Test void moneroRoundTripNullDaemonLogin() {
        WalletSecretWriter.WrittenSecrets written = WalletSecretWriter.writeMoneroSecrets(
                tempDir, "127.0.0.1", "18081", null, null,
                "127.0.0.1", "18083", "wuser", "wpass");

        WalletConnectionConfig cfg = WalletSecretLoader.loadMonero(
                written.daemonConfigPath().toString(), written.walletConfigPath().toString());

        assertFalse(cfg.extras().containsKey("daemonRpcUsername"));
    }

    // ── Overwrite ──

    @Test void overwriteExistingFiles() throws IOException {
        // Write initial files
        WalletSecretWriter.writeBitcoinSecrets(tempDir, "127.0.0.1", "8332", "old", "old", "oldwallet");

        // Overwrite with new values
        WalletSecretWriter.WrittenSecrets written = WalletSecretWriter.writeBitcoinSecrets(
                tempDir, "10.0.0.1", "18332", "new", "newpass", "newwallet");

        WalletConnectionConfig cfg = WalletSecretLoader.loadBitcoin(
                written.daemonConfigPath().toString(), written.walletConfigPath().toString());

        assertEquals("http://10.0.0.1:18332", cfg.rpcUrl());
        assertEquals("new", cfg.username());
        assertEquals("newpass", cfg.password());
        assertEquals("newwallet", cfg.extras().get("walletName"));
    }

    // ── Directory creation ──

    @Test void createsDirectoriesIfMissing() {
        Path nested = tempDir.resolve("a").resolve("b").resolve("c");

        WalletSecretWriter.WrittenSecrets written = WalletSecretWriter.writeBitcoinSecrets(
                nested, "127.0.0.1", "8332", "u", "p", "w");

        assertTrue(Files.exists(written.daemonConfigPath()));
        assertTrue(Files.exists(written.walletConfigPath()));
    }

    // ── POSIX permissions ──

    @Test void posixPermissionsAreOwnerOnly() {
        WalletSecretWriter.WrittenSecrets written = WalletSecretWriter.writeBitcoinSecrets(
                tempDir, "127.0.0.1", "8332", "u", "p", "w");

        try {
            Set<PosixFilePermission> daemonPerms = Files.getPosixFilePermissions(written.daemonConfigPath());
            Set<PosixFilePermission> walletPerms = Files.getPosixFilePermissions(written.walletConfigPath());

            Set<PosixFilePermission> expected = Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

            assertEquals(expected, daemonPerms);
            assertEquals(expected, walletPerms);
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem, skip assertion
        } catch (IOException e) {
            fail("Failed to read POSIX permissions: " + e.getMessage());
        }
    }

    // ── Returned paths are correct ──

    @Test void returnedPathsAreCorrectForAllCoins() {
        WalletSecretWriter.WrittenSecrets btc = WalletSecretWriter.writeBitcoinSecrets(
                tempDir, "127.0.0.1", "8332", "u", "p", "");
        assertEquals(tempDir.resolve("bitcoin-daemon.conf"), btc.daemonConfigPath());
        assertEquals(tempDir.resolve("bitcoin-wallet.conf"), btc.walletConfigPath());

        WalletSecretWriter.WrittenSecrets ltc = WalletSecretWriter.writeLitecoinSecrets(
                tempDir, "127.0.0.1", "9332", "u", "p", "");
        assertEquals(tempDir.resolve("litecoin-daemon.conf"), ltc.daemonConfigPath());
        assertEquals(tempDir.resolve("litecoin-wallet.conf"), ltc.walletConfigPath());

        WalletSecretWriter.WrittenSecrets xmr = WalletSecretWriter.writeMoneroSecrets(
                tempDir, "127.0.0.1", "18081", "", "",
                "127.0.0.1", "18083", "u", "p");
        assertEquals(tempDir.resolve("monero-daemon.conf"), xmr.daemonConfigPath());
        assertEquals(tempDir.resolve("monero-wallet-rpc.conf"), xmr.walletConfigPath());
    }
}
