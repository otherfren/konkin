package io.konkin.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WalletSecretLoaderTest {

    @TempDir Path tempDir;

    // ── loadBitcoin ──

    @Test void loadBitcoinDefaults() throws IOException {
        Path daemon = writePropFile("daemon.conf", "rpcuser=user1\nrpcpassword=pass1\n");
        Path wallet = writePropFile("wallet.conf", "wallet=mywallet\n");

        WalletConnectionConfig cfg = WalletSecretLoader.loadBitcoin(daemon.toString(), wallet.toString());

        assertEquals(Coin.BTC, cfg.coin());
        assertEquals("http://127.0.0.1:8332", cfg.rpcUrl());
        assertEquals("user1", cfg.username());
        assertEquals("pass1", cfg.password());
        assertEquals("mywallet", cfg.extras().get("walletName"));
        assertEquals("mainnet", cfg.extras().get("network"));
    }

    @Test void loadBitcoinCustomHostPort() throws IOException {
        Path daemon = writePropFile("daemon.conf", "rpcuser=u\nrpcpassword=p\nrpcconnect=192.168.1.1\nrpcport=18332\n");
        Path wallet = writePropFile("wallet.conf", "");

        WalletConnectionConfig cfg = WalletSecretLoader.loadBitcoin(daemon.toString(), wallet.toString());

        assertEquals("http://192.168.1.1:18332", cfg.rpcUrl());
        assertEquals("testnet", cfg.extras().get("network"));
    }

    @Test void loadBitcoinSignet() throws IOException {
        Path daemon = writePropFile("daemon.conf", "rpcuser=u\nrpcpassword=p\nrpcport=38332\n");
        Path wallet = writePropFile("wallet.conf", "");

        assertEquals("signet", WalletSecretLoader.loadBitcoin(daemon.toString(), wallet.toString()).extras().get("network"));
    }

    @Test void loadBitcoinRegtest() throws IOException {
        Path daemon = writePropFile("daemon.conf", "rpcuser=u\nrpcpassword=p\nrpcport=18444\n");
        Path wallet = writePropFile("wallet.conf", "");

        assertEquals("regtest", WalletSecretLoader.loadBitcoin(daemon.toString(), wallet.toString()).extras().get("network"));
    }

    @Test void loadBitcoinStripsInlineComments() throws IOException {
        Path daemon = writePropFile("daemon.conf", "rpcuser=myuser # admin user\nrpcpassword=secret123 # do not share\n");
        Path wallet = writePropFile("wallet.conf", "wallet=w1 # primary\n");

        WalletConnectionConfig cfg = WalletSecretLoader.loadBitcoin(daemon.toString(), wallet.toString());

        assertEquals("myuser", cfg.username());
        assertEquals("secret123", cfg.password());
        assertEquals("w1", cfg.extras().get("walletName"));
    }

    @Test void loadBitcoinEmptyWalletOmitsExtra() throws IOException {
        Path daemon = writePropFile("daemon.conf", "rpcuser=u\nrpcpassword=p\n");
        Path wallet = writePropFile("wallet.conf", "wallet=\n");

        WalletConnectionConfig cfg = WalletSecretLoader.loadBitcoin(daemon.toString(), wallet.toString());

        assertFalse(cfg.extras().containsKey("walletName"));
    }

    @Test void loadBitcoinMissingFileThrows() {
        assertThrows(IllegalStateException.class,
                () -> WalletSecretLoader.loadBitcoin("/nonexistent/path", "/also/missing"));
    }

    // ── loadMonero ──

    @Test void loadMoneroDefaults() throws IOException {
        Path daemon = writePropFile("monerod.conf", "rpc-bind-ip=127.0.0.1\nrpc-bind-port=18081\n");
        Path walletRpc = writePropFile("wallet-rpc.conf", "rpc-bind-ip=127.0.0.1\nrpc-bind-port=18083\nrpc-login=user:pass\n");

        WalletConnectionConfig cfg = WalletSecretLoader.loadMonero(daemon.toString(), walletRpc.toString());

        assertEquals(Coin.XMR, cfg.coin());
        assertEquals("http://127.0.0.1:18083", cfg.rpcUrl());
        assertEquals("user", cfg.username());
        assertEquals("pass", cfg.password());
        assertEquals("http://127.0.0.1:18081", cfg.extras().get("daemonRpcUrl"));
        assertEquals("mainnet", cfg.extras().get("network"));
    }

    @Test void loadMoneroTestnet() throws IOException {
        Path daemon = writePropFile("monerod.conf", "rpc-bind-port=28081\n");
        Path walletRpc = writePropFile("wallet-rpc.conf", "rpc-bind-port=18083\n");

        assertEquals("testnet", WalletSecretLoader.loadMonero(daemon.toString(), walletRpc.toString()).extras().get("network"));
    }

    @Test void loadMoneroStagenet() throws IOException {
        Path daemon = writePropFile("monerod.conf", "rpc-bind-port=38081\n");
        Path walletRpc = writePropFile("wallet-rpc.conf", "rpc-bind-port=18083\n");

        assertEquals("stagenet", WalletSecretLoader.loadMonero(daemon.toString(), walletRpc.toString()).extras().get("network"));
    }

    @Test void loadMoneroNoLogin() throws IOException {
        Path daemon = writePropFile("monerod.conf", "rpc-bind-port=18081\n");
        Path walletRpc = writePropFile("wallet-rpc.conf", "rpc-bind-port=18083\n");

        WalletConnectionConfig cfg = WalletSecretLoader.loadMonero(daemon.toString(), walletRpc.toString());

        assertEquals("", cfg.username());
        assertEquals("", cfg.password());
    }

    @Test void loadMoneroDaemonLogin() throws IOException {
        Path daemon = writePropFile("monerod.conf", "rpc-bind-port=18081\nrpc-login=duser:dpass\n");
        Path walletRpc = writePropFile("wallet-rpc.conf", "rpc-bind-port=18083\n");

        WalletConnectionConfig cfg = WalletSecretLoader.loadMonero(daemon.toString(), walletRpc.toString());

        assertEquals("duser", cfg.extras().get("daemonRpcUsername"));
        assertEquals("dpass", cfg.extras().get("daemonRpcPassword"));
    }

    @Test void loadMoneroNoDaemonLogin() throws IOException {
        Path daemon = writePropFile("monerod.conf", "rpc-bind-port=18081\n");
        Path walletRpc = writePropFile("wallet-rpc.conf", "rpc-bind-port=18083\n");

        WalletConnectionConfig cfg = WalletSecretLoader.loadMonero(daemon.toString(), walletRpc.toString());

        assertFalse(cfg.extras().containsKey("daemonRpcUsername"));
    }

    @Test void loadMoneroCustomHost() throws IOException {
        Path daemon = writePropFile("monerod.conf", "rpc-bind-ip=10.0.0.1\nrpc-bind-port=18081\n");
        Path walletRpc = writePropFile("wallet-rpc.conf", "rpc-bind-ip=10.0.0.2\nrpc-bind-port=18083\n");

        WalletConnectionConfig cfg = WalletSecretLoader.loadMonero(daemon.toString(), walletRpc.toString());

        assertEquals("http://10.0.0.2:18083", cfg.rpcUrl());
        assertEquals("http://10.0.0.1:18081", cfg.extras().get("daemonRpcUrl"));
    }

    @Test void loadMoneroMissingFileThrows() {
        assertThrows(IllegalStateException.class,
                () -> WalletSecretLoader.loadMonero("/nonexistent", "/also/missing"));
    }

    @Test void loadMoneroTestnet28082() throws IOException {
        Path daemon = writePropFile("monerod.conf", "rpc-bind-port=28082\n");
        Path walletRpc = writePropFile("wallet-rpc.conf", "rpc-bind-port=18083\n");

        assertEquals("testnet", WalletSecretLoader.loadMonero(daemon.toString(), walletRpc.toString()).extras().get("network"));
    }

    @Test void loadMoneroStagenet38082() throws IOException {
        Path daemon = writePropFile("monerod.conf", "rpc-bind-port=38082\n");
        Path walletRpc = writePropFile("wallet-rpc.conf", "rpc-bind-port=18083\n");

        assertEquals("stagenet", WalletSecretLoader.loadMonero(daemon.toString(), walletRpc.toString()).extras().get("network"));
    }

    private Path writePropFile(String name, String content) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, content);
        return path;
    }
}
