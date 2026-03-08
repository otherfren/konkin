package io.konkin.crypto;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WalletSupervisorTest {

    private CoinWalletFactory factory;
    private CoinWallet wallet;
    private WalletConnectionConfig config;
    private WalletSupervisor supervisor;

    @BeforeEach
    void setUp() {
        factory = mock(CoinWalletFactory.class);
        wallet = mock(CoinWallet.class);
        config = new WalletConnectionConfig(Coin.BTC, "http://127.0.0.1:8332", "user", "pass", Map.of());
        supervisor = new WalletSupervisor(config, factory);
    }

    @AfterEach
    void tearDown() {
        supervisor.close();
    }

    // --- 1. Initial snapshot is OFFLINE with null balance before start ---

    @Test
    void initialSnapshot_isOffline_withNullBalance() {
        WalletSnapshot snap = supervisor.snapshot();

        assertEquals(Coin.BTC, snap.coin());
        assertEquals(WalletStatus.OFFLINE, snap.status());
        assertNull(snap.totalBalance());
        assertNull(snap.spendableBalance());
        assertNull(snap.lastHeartbeat());
    }

    // --- 2. After start + reconnect(), snapshot updates to AVAILABLE ---

    @Test
    void afterStartAndReconnect_walletAvailable_snapshotIsAvailable() {
        when(factory.create(config)).thenReturn(wallet);
        when(wallet.status()).thenReturn(WalletStatus.AVAILABLE);
        when(wallet.balance()).thenReturn(new WalletBalance(Coin.BTC, new BigDecimal("1.5"), new BigDecimal("1.0")));

        supervisor.start();
        supervisor.reconnect();

        WalletSnapshot snap = supervisor.snapshot();
        assertEquals(WalletStatus.AVAILABLE, snap.status());
        assertEquals(new BigDecimal("1.5"), snap.totalBalance());
        assertEquals(new BigDecimal("1.0"), snap.spendableBalance());
        assertNotNull(snap.lastHeartbeat());
    }

    @Test
    void afterStartAndReconnect_walletSyncing_snapshotIsSyncing() {
        when(factory.create(config)).thenReturn(wallet);
        when(wallet.status()).thenReturn(WalletStatus.SYNCING);
        when(wallet.balance()).thenReturn(new WalletBalance(Coin.BTC, BigDecimal.ZERO, BigDecimal.ZERO));

        supervisor.start();
        supervisor.reconnect();

        WalletSnapshot snap = supervisor.snapshot();
        assertEquals(WalletStatus.SYNCING, snap.status());
    }

    // --- 3. After start + reconnect(), snapshot stays OFFLINE when wallet creation throws ---

    @Test
    void afterStartAndReconnect_factoryThrows_snapshotRemainsOffline() {
        when(factory.create(config)).thenThrow(new RuntimeException("Connection refused"));

        supervisor.start();
        supervisor.reconnect();

        WalletSnapshot snap = supervisor.snapshot();
        assertEquals(WalletStatus.OFFLINE, snap.status());
        assertNull(snap.totalBalance());
        assertNull(snap.spendableBalance());
    }

    // --- 4. execute() throws WalletConnectionException when supervisor is not running ---

    @Test
    void execute_notRunning_throwsWalletConnectionException() {
        WalletConnectionException ex = assertThrows(WalletConnectionException.class,
                () -> supervisor.execute(CoinWallet::status));

        assertTrue(ex.getMessage().contains("not running"));
    }

    // --- 5. execute() throws WalletConnectionException when wallet is OFFLINE ---

    @Test
    void execute_walletOffline_throwsWalletConnectionException() {
        // Start supervisor but factory throws, so wallet stays OFFLINE
        when(factory.create(config)).thenThrow(new RuntimeException("Connection refused"));

        supervisor.start();
        supervisor.reconnect();

        assertEquals(WalletStatus.OFFLINE, supervisor.snapshot().status());

        WalletConnectionException ex = assertThrows(WalletConnectionException.class,
                () -> supervisor.execute(CoinWallet::status));

        assertTrue(ex.getMessage().contains("offline"));
    }

    // --- 6. execute() delegates to the wallet when AVAILABLE ---

    @Test
    void execute_walletAvailable_delegatesToWallet() {
        when(factory.create(config)).thenReturn(wallet);
        when(wallet.status()).thenReturn(WalletStatus.AVAILABLE);
        when(wallet.balance()).thenReturn(new WalletBalance(Coin.BTC, new BigDecimal("2.0"), new BigDecimal("1.5")));

        supervisor.start();
        supervisor.reconnect();

        assertEquals(WalletStatus.AVAILABLE, supervisor.snapshot().status());

        WalletBalance result = supervisor.execute(CoinWallet::balance);

        assertEquals(new BigDecimal("2.0"), result.total());
        assertEquals(new BigDecimal("1.5"), result.spendable());
    }

    @Test
    void execute_walletThrowsWalletException_propagates() {
        when(factory.create(config)).thenReturn(wallet);
        when(wallet.status()).thenReturn(WalletStatus.AVAILABLE);
        WalletBalance normalBalance = new WalletBalance(Coin.BTC, BigDecimal.ONE, BigDecimal.ONE);
        when(wallet.balance())
                .thenReturn(normalBalance, normalBalance)
                .thenThrow(new WalletConnectionException("RPC error"));

        supervisor.start();
        supervisor.reconnect();

        assertThrows(WalletConnectionException.class, () -> supervisor.execute(CoinWallet::balance));
    }

    @Test
    void execute_walletThrowsRuntimeException_wrapsInWalletConnectionException() {
        when(factory.create(config)).thenReturn(wallet);
        when(wallet.status()).thenReturn(WalletStatus.AVAILABLE);
        WalletBalance normalBalance = new WalletBalance(Coin.BTC, BigDecimal.ONE, BigDecimal.ONE);
        when(wallet.balance())
                .thenReturn(normalBalance, normalBalance)
                .thenThrow(new RuntimeException("unexpected error"));

        supervisor.start();
        supervisor.reconnect();

        WalletConnectionException ex = assertThrows(WalletConnectionException.class,
                () -> supervisor.execute(CoinWallet::balance));

        assertNotNull(ex.getMessage());
        assertNotNull(ex.getCause());
    }

    // --- 7. close() sets snapshot back to OFFLINE ---

    @Test
    void close_setsSnapshotToOffline() {
        when(factory.create(config)).thenReturn(wallet);
        when(wallet.status()).thenReturn(WalletStatus.AVAILABLE);
        when(wallet.balance()).thenReturn(new WalletBalance(Coin.BTC, BigDecimal.ONE, BigDecimal.ONE));

        supervisor.start();
        supervisor.reconnect();

        assertEquals(WalletStatus.AVAILABLE, supervisor.snapshot().status());

        supervisor.close();

        WalletSnapshot snap = supervisor.snapshot();
        assertEquals(WalletStatus.OFFLINE, snap.status());
        assertNull(snap.totalBalance());
        assertNull(snap.spendableBalance());
    }

    // --- 8. reconnect() is a no-op when not running ---

    @Test
    void reconnect_notRunning_isNoOp() {
        supervisor.reconnect();

        // Snapshot should remain in its initial OFFLINE state
        assertEquals(WalletStatus.OFFLINE, supervisor.snapshot().status());
        verifyNoInteractions(factory);
    }

    @Test
    void reconnect_afterClose_isNoOp() {
        when(factory.create(config)).thenReturn(wallet);
        when(wallet.status()).thenReturn(WalletStatus.AVAILABLE);
        when(wallet.balance()).thenReturn(new WalletBalance(Coin.BTC, BigDecimal.ONE, BigDecimal.ONE));

        supervisor.start();
        supervisor.reconnect();
        supervisor.close();

        // Reset mock interactions from start/reconnect/close
        reset(factory);

        supervisor.reconnect();

        verifyNoInteractions(factory);
        assertEquals(WalletStatus.OFFLINE, supervisor.snapshot().status());
    }

    // --- 9. Heartbeat handles wallet going OFFLINE after being AVAILABLE ---

    @Test
    void heartbeat_walletGoesOffline_transitionsToOffline() {
        // First heartbeat: wallet is AVAILABLE
        when(factory.create(config)).thenReturn(wallet);
        when(wallet.status()).thenReturn(WalletStatus.AVAILABLE);
        when(wallet.balance()).thenReturn(new WalletBalance(Coin.BTC, BigDecimal.ONE, BigDecimal.ONE));

        supervisor.start();
        supervisor.reconnect();

        assertEquals(WalletStatus.AVAILABLE, supervisor.snapshot().status());

        // Now wallet goes OFFLINE; handleOffline will try to recreate and that also returns OFFLINE
        when(wallet.status()).thenReturn(WalletStatus.OFFLINE);

        supervisor.reconnect();

        assertEquals(WalletStatus.OFFLINE, supervisor.snapshot().status());
    }

    @Test
    void heartbeat_walletGoesOfflineThenRecovers_transitionsBackToAvailable() {
        when(factory.create(config)).thenReturn(wallet);
        when(wallet.status()).thenReturn(WalletStatus.AVAILABLE);
        when(wallet.balance()).thenReturn(new WalletBalance(Coin.BTC, BigDecimal.ONE, BigDecimal.ONE));

        supervisor.start();
        supervisor.reconnect();

        assertEquals(WalletStatus.AVAILABLE, supervisor.snapshot().status());

        // Wallet goes OFFLINE, but factory recreates a working wallet
        CoinWallet newWallet = mock(CoinWallet.class);
        when(wallet.status()).thenReturn(WalletStatus.OFFLINE);
        when(factory.create(config)).thenReturn(newWallet);
        when(newWallet.status()).thenReturn(WalletStatus.AVAILABLE);
        when(newWallet.balance()).thenReturn(new WalletBalance(Coin.BTC, new BigDecimal("3.0"), new BigDecimal("2.0")));

        supervisor.reconnect();

        WalletSnapshot snap = supervisor.snapshot();
        assertEquals(WalletStatus.AVAILABLE, snap.status());
        assertEquals(new BigDecimal("3.0"), snap.totalBalance());
        assertEquals(new BigDecimal("2.0"), snap.spendableBalance());
    }

    // --- Additional edge cases ---

    @Test
    void heartbeat_balanceFetchFails_snapshotStillUpdatesStatus() {
        when(factory.create(config)).thenReturn(wallet);
        when(wallet.status()).thenReturn(WalletStatus.AVAILABLE);
        when(wallet.balance()).thenThrow(new WalletConnectionException("balance RPC failed"));

        supervisor.start();
        supervisor.reconnect();

        WalletSnapshot snap = supervisor.snapshot();
        assertEquals(WalletStatus.AVAILABLE, snap.status());
        assertNull(snap.totalBalance());
        assertNull(snap.spendableBalance());
        assertNotNull(snap.lastHeartbeat());
    }

    @Test
    void start_calledTwice_isIdempotent() {
        when(factory.create(config)).thenReturn(wallet);
        when(wallet.status()).thenReturn(WalletStatus.AVAILABLE);
        when(wallet.balance()).thenReturn(new WalletBalance(Coin.BTC, BigDecimal.ONE, BigDecimal.ONE));

        supervisor.start();
        supervisor.start(); // second call should be a no-op

        supervisor.reconnect();

        assertEquals(WalletStatus.AVAILABLE, supervisor.snapshot().status());
    }

    @Test
    void prepareNode_calledBeforeCreate() {
        when(factory.create(config)).thenReturn(wallet);
        when(wallet.status()).thenReturn(WalletStatus.AVAILABLE);
        when(wallet.balance()).thenReturn(new WalletBalance(Coin.BTC, BigDecimal.ONE, BigDecimal.ONE));

        supervisor.start();
        supervisor.reconnect();

        verify(factory).prepareNode(config);
        verify(factory).create(config);
    }
}
