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

package io.konkin.crypto.monero;

import io.konkin.crypto.*;
import monero.common.MoneroError;
import monero.daemon.model.MoneroDaemonInfo;
import monero.wallet.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MoneroWalletTest {

    private MoneroRpcAdapter rpc;
    private MoneroWallet wallet;

    @BeforeEach
    void setUp() {
        rpc = mock(MoneroRpcAdapter.class);

        WalletConnectionConfig config = new WalletConnectionConfig(
                Coin.XMR, "http://127.0.0.1:18083", "user", "pass", Map.of(
                MoneroExtras.DAEMON_RPC_URL, "http://127.0.0.1:18081",
                MoneroExtras.NETWORK, "mainnet"
        ));

        wallet = new MoneroWallet(config, rpc);
    }

    // --- status() ---

    @Test
    void status_synced_returnsAvailable() {
        when(rpc.hasDaemon()).thenReturn(true);
        MoneroDaemonInfo info = mock(MoneroDaemonInfo.class);
        when(info.isSynchronized()).thenReturn(true);
        when(rpc.getDaemonInfo()).thenReturn(info);
        when(rpc.getWalletHeight()).thenReturn(100L);

        assertEquals(WalletStatus.AVAILABLE, wallet.status());
    }

    @Test
    void status_notSynced_returnsSyncing() {
        when(rpc.hasDaemon()).thenReturn(true);
        MoneroDaemonInfo info = mock(MoneroDaemonInfo.class);
        when(info.isSynchronized()).thenReturn(false);
        when(rpc.getDaemonInfo()).thenReturn(info);

        assertEquals(WalletStatus.SYNCING, wallet.status());
    }

    @Test
    void status_syncedNull_returnsSyncing() {
        when(rpc.hasDaemon()).thenReturn(true);
        MoneroDaemonInfo info = mock(MoneroDaemonInfo.class);
        when(info.isSynchronized()).thenReturn(null);
        when(rpc.getDaemonInfo()).thenReturn(info);

        assertEquals(WalletStatus.SYNCING, wallet.status());
    }

    @Test
    void status_daemonError_returnsOffline() {
        when(rpc.hasDaemon()).thenReturn(true);
        when(rpc.getDaemonInfo()).thenThrow(new MoneroError("Connection refused"));

        assertEquals(WalletStatus.OFFLINE, wallet.status());
    }

    @Test
    void status_noDaemon_walletResponds_returnsAvailable() {
        when(rpc.hasDaemon()).thenReturn(false);
        when(rpc.getWalletHeight()).thenReturn(100L);

        assertEquals(WalletStatus.AVAILABLE, wallet.status());
    }

    @Test
    void status_noDaemon_walletError_returnsOffline() {
        when(rpc.hasDaemon()).thenReturn(false);
        when(rpc.getWalletHeight()).thenThrow(new MoneroError("Connection refused"));

        assertEquals(WalletStatus.OFFLINE, wallet.status());
    }

    // --- balance() ---

    @Test
    void balance_returnsCorrectConversion() {
        // 1.5 XMR = 1_500_000_000_000 piconero
        when(rpc.getBalance(0)).thenReturn(new BigInteger("1500000000000"));
        when(rpc.getUnlockedBalance(0)).thenReturn(new BigInteger("1000000000000"));

        WalletBalance balance = wallet.balance();

        assertEquals(Coin.XMR, balance.coin());
        assertEquals(new BigDecimal("1.500000000000"), balance.total());
        assertEquals(new BigDecimal("1.000000000000"), balance.spendable());
    }

    @Test
    void balance_zero_returnsZero() {
        when(rpc.getBalance(0)).thenReturn(BigInteger.ZERO);
        when(rpc.getUnlockedBalance(0)).thenReturn(BigInteger.ZERO);

        WalletBalance balance = wallet.balance();

        assertEquals(0, BigDecimal.ZERO.compareTo(balance.total()));
        assertEquals(0, BigDecimal.ZERO.compareTo(balance.spendable()));
    }

    @Test
    void balance_connectionError_throwsWalletConnectionException() {
        when(rpc.getBalance(0)).thenThrow(new MoneroError("Connection refused"));

        assertThrows(WalletConnectionException.class, () -> wallet.balance());
    }

    @Test
    void balance_operationError_throwsWalletOperationException() {
        when(rpc.getBalance(0)).thenThrow(new MoneroError("unknown error"));

        assertThrows(WalletOperationException.class, () -> wallet.balance());
    }

    // --- depositAddress() ---

    @Test
    void depositAddress_createsSubaddress() {
        MoneroSubaddress subaddress = mock(MoneroSubaddress.class);
        String addr = "888tNkZrPN6JsEgekjMnABU4TBzc2Dt29EPAvkRxbANsAnjyPbb3iQ1YBRk1UXcdRsiKc9dhwMVgN5S9cQUiyoogDavup3H";
        when(subaddress.getAddress()).thenReturn(addr);
        when(rpc.createSubaddress(0)).thenReturn(subaddress);

        DepositAddress address = wallet.depositAddress();

        assertEquals(Coin.XMR, address.coin());
        assertEquals(addr, address.address());
        verify(rpc).createSubaddress(0);
    }

    // --- send() ---

    @Test
    void send_success_returnsTxHashAndFeeAndKey() {
        MoneroTxWallet tx = mock(MoneroTxWallet.class);
        when(tx.getHash()).thenReturn("abcdef1234567890");
        when(tx.getFee()).thenReturn(new BigInteger("30000000")); // 0.00003 XMR
        when(tx.getKey()).thenReturn("tx_secret_key_here");
        when(rpc.createTx(any(MoneroTxConfig.class))).thenReturn(tx);

        SendRequest request = new SendRequest(
                Coin.XMR,
                "888tNkZrPN6JsEgekjMnABU4TBzc2Dt29EPAvkRxbANsAnjyPbb3iQ1YBRk1UXcdRsiKc9dhwMVgN5S9cQUiyoogDavup3H",
                new BigDecimal("0.500000000000"),
                Map.of()
        );

        SendResult result = wallet.send(request);

        assertEquals(Coin.XMR, result.coin());
        assertEquals("abcdef1234567890", result.txId());
        assertEquals(new BigDecimal("0.500000000000"), result.amount());
        assertEquals("tx_secret_key_here", result.extras().get(MoneroExtras.TX_KEY));
    }

    @Test
    void send_noTxKey_extrasEmpty() {
        MoneroTxWallet tx = mock(MoneroTxWallet.class);
        when(tx.getHash()).thenReturn("hash123");
        when(tx.getFee()).thenReturn(BigInteger.ZERO);
        when(tx.getKey()).thenReturn(null);
        when(rpc.createTx(any(MoneroTxConfig.class))).thenReturn(tx);

        SendRequest request = new SendRequest(Coin.XMR, "addr", new BigDecimal("1.000000000000"), Map.of());
        SendResult result = wallet.send(request);

        assertFalse(result.extras().containsKey(MoneroExtras.TX_KEY));
    }

    @Test
    void send_insufficientFunds_throwsWalletInsufficientFundsException() {
        when(rpc.createTx(any(MoneroTxConfig.class)))
                .thenThrow(new MoneroError("not enough money"));
        when(rpc.getUnlockedBalance(0)).thenReturn(BigInteger.ZERO);

        SendRequest request = new SendRequest(Coin.XMR, "addr", new BigDecimal("10.000000000000"), Map.of());

        assertThrows(WalletInsufficientFundsException.class, () -> wallet.send(request));
    }

    @Test
    void send_notEnoughUnlocked_throwsWalletInsufficientFundsException() {
        when(rpc.createTx(any(MoneroTxConfig.class)))
                .thenThrow(new MoneroError("not enough unlocked money"));
        when(rpc.getUnlockedBalance(0)).thenReturn(BigInteger.ZERO);

        SendRequest request = new SendRequest(Coin.XMR, "addr", new BigDecimal("1.000000000000"), Map.of());

        assertThrows(WalletInsufficientFundsException.class, () -> wallet.send(request));
    }

    @Test
    void send_operationError_throwsWalletOperationException() {
        when(rpc.createTx(any(MoneroTxConfig.class)))
                .thenThrow(new MoneroError("invalid address"));

        SendRequest request = new SendRequest(Coin.XMR, "bad_addr", new BigDecimal("1.000000000000"), Map.of());

        assertThrows(WalletOperationException.class, () -> wallet.send(request));
    }

    // --- pendingIncoming() / pendingOutgoing() ---

    @Test
    void pendingIncoming_returnsUnconfirmedTransfers() {
        MoneroTxWallet tx = mock(MoneroTxWallet.class);
        when(tx.getHash()).thenReturn("txhash123");
        when(tx.getNumConfirmations()).thenReturn(0L);
        when(tx.getFee()).thenReturn(BigInteger.ZERO);
        when(tx.getReceivedTimestamp()).thenReturn(1700000000L);
        when(tx.getKey()).thenReturn(null);

        MoneroIncomingTransfer transfer = mock(MoneroIncomingTransfer.class);
        when(transfer.getTx()).thenReturn(tx);
        when(transfer.getAddress()).thenReturn("sender_address");
        when(transfer.getAmount()).thenReturn(new BigInteger("2000000000000")); // 2 XMR

        when(rpc.getIncomingTransfers(any(MoneroTransferQuery.class)))
                .thenReturn(List.of(transfer));

        List<Transaction> pending = wallet.pendingIncoming();

        assertEquals(1, pending.size());
        Transaction t = pending.get(0);
        assertEquals(Coin.XMR, t.coin());
        assertEquals("txhash123", t.txId());
        assertEquals(TransactionDirection.INCOMING, t.direction());
        assertEquals("sender_address", t.address());
        assertEquals(0, new BigDecimal("2.000000000000").compareTo(t.amount()));
        assertEquals(0, t.confirmations());
        assertFalse(t.confirmed());
    }

    @Test
    void pendingOutgoing_returnsUnconfirmedTransfers() {
        MoneroTxWallet tx = mock(MoneroTxWallet.class);
        when(tx.getHash()).thenReturn("txhash456");
        when(tx.getNumConfirmations()).thenReturn(0L);
        when(tx.getFee()).thenReturn(new BigInteger("10000000")); // 0.00001 XMR
        when(tx.getReceivedTimestamp()).thenReturn(1700000000L);
        when(tx.getKey()).thenReturn("outgoing_key");

        MoneroDestination dest = mock(MoneroDestination.class);
        when(dest.getAddress()).thenReturn("dest_address");

        MoneroOutgoingTransfer transfer = mock(MoneroOutgoingTransfer.class);
        when(transfer.getTx()).thenReturn(tx);
        when(transfer.getDestinations()).thenReturn(List.of(dest));
        when(transfer.getAmount()).thenReturn(new BigInteger("500000000000")); // 0.5 XMR

        when(rpc.getOutgoingTransfers(any(MoneroTransferQuery.class)))
                .thenReturn(List.of(transfer));

        List<Transaction> pending = wallet.pendingOutgoing();

        assertEquals(1, pending.size());
        Transaction t = pending.get(0);
        assertEquals(TransactionDirection.OUTGOING, t.direction());
        assertEquals("dest_address", t.address());
        assertEquals(0, new BigDecimal("0.500000000000").compareTo(t.amount()));
        assertEquals("outgoing_key", t.txKey());
    }

    @Test
    void pendingOutgoing_noDestinations_fallsBackToAddresses() {
        MoneroTxWallet tx = mock(MoneroTxWallet.class);
        when(tx.getHash()).thenReturn("txhash789");
        when(tx.getNumConfirmations()).thenReturn(0L);
        when(tx.getFee()).thenReturn(BigInteger.ZERO);
        when(tx.getReceivedTimestamp()).thenReturn(1700000000L);
        when(tx.getKey()).thenReturn(null);

        MoneroOutgoingTransfer transfer = mock(MoneroOutgoingTransfer.class);
        when(transfer.getTx()).thenReturn(tx);
        when(transfer.getDestinations()).thenReturn(null);
        when(transfer.getAddresses()).thenReturn(List.of("fallback_addr"));
        when(transfer.getAmount()).thenReturn(new BigInteger("100000000000"));

        when(rpc.getOutgoingTransfers(any(MoneroTransferQuery.class)))
                .thenReturn(List.of(transfer));

        List<Transaction> pending = wallet.pendingOutgoing();

        assertEquals("fallback_addr", pending.get(0).address());
    }

    @Test
    void pendingOutgoing_noAddressInfo_usesUnknown() {
        MoneroTxWallet tx = mock(MoneroTxWallet.class);
        when(tx.getHash()).thenReturn("txhash000");
        when(tx.getNumConfirmations()).thenReturn(0L);
        when(tx.getFee()).thenReturn(BigInteger.ZERO);
        when(tx.getReceivedTimestamp()).thenReturn(1700000000L);
        when(tx.getKey()).thenReturn(null);

        MoneroOutgoingTransfer transfer = mock(MoneroOutgoingTransfer.class);
        when(transfer.getTx()).thenReturn(tx);
        when(transfer.getDestinations()).thenReturn(null);
        when(transfer.getAddresses()).thenReturn(null);
        when(transfer.getAmount()).thenReturn(new BigInteger("100000000000"));

        when(rpc.getOutgoingTransfers(any(MoneroTransferQuery.class)))
                .thenReturn(List.of(transfer));

        List<Transaction> pending = wallet.pendingOutgoing();

        assertEquals("unknown", pending.get(0).address());
    }

    @Test
    void pendingIncoming_emptyList_returnsEmpty() {
        when(rpc.getIncomingTransfers(any(MoneroTransferQuery.class)))
                .thenReturn(List.of());

        assertTrue(wallet.pendingIncoming().isEmpty());
    }

    @Test
    void pendingIncoming_nullAddress_fallsToPrimary() {
        MoneroTxWallet tx = mock(MoneroTxWallet.class);
        when(tx.getHash()).thenReturn("txhash");
        when(tx.getNumConfirmations()).thenReturn(5L);
        when(tx.getFee()).thenReturn(BigInteger.ZERO);
        when(tx.getReceivedTimestamp()).thenReturn(1700000000L);
        when(tx.getKey()).thenReturn(null);

        MoneroIncomingTransfer transfer = mock(MoneroIncomingTransfer.class);
        when(transfer.getTx()).thenReturn(tx);
        when(transfer.getAddress()).thenReturn(null);
        when(transfer.getAmount()).thenReturn(new BigInteger("1000000000000"));
        when(rpc.getPrimaryAddress()).thenReturn("primary_addr");

        when(rpc.getIncomingTransfers(any(MoneroTransferQuery.class)))
                .thenReturn(List.of(transfer));

        List<Transaction> pending = wallet.pendingIncoming();

        assertEquals("primary_addr", pending.get(0).address());
        assertTrue(pending.get(0).confirmed());
        assertEquals(5, pending.get(0).confirmations());
    }

    // --- signMessage() ---

    @Test
    void signMessage_returnsSignedMessage() {
        when(rpc.getPrimaryAddress()).thenReturn("primary_address");
        when(rpc.signMessage("hello")).thenReturn("SigV2...");

        SignedMessage signed = wallet.signMessage("hello");

        assertEquals(Coin.XMR, signed.coin());
        assertEquals("primary_address", signed.address());
        assertEquals("hello", signed.message());
        assertEquals("SigV2...", signed.signature());
    }

    @Test
    void signMessage_error_throwsWalletOperationException() {
        when(rpc.getPrimaryAddress()).thenReturn("addr");
        when(rpc.signMessage(anyString())).thenThrow(new MoneroError("signing failed"));

        assertThrows(WalletOperationException.class, () -> wallet.signMessage("msg"));
    }

    // --- verifyMessage() ---

    @Test
    void verifyMessage_valid_returnsTrue() {
        MoneroMessageSignatureResult result = new MoneroMessageSignatureResult(true, false, null, 2);
        when(rpc.verifyMessage("hello", "addr", "sig")).thenReturn(result);

        assertTrue(wallet.verifyMessage("hello", "addr", "sig"));
    }

    @Test
    void verifyMessage_invalid_returnsFalse() {
        MoneroMessageSignatureResult result = new MoneroMessageSignatureResult(false, false, null, 2);
        when(rpc.verifyMessage("hello", "addr", "bad_sig")).thenReturn(result);

        assertFalse(wallet.verifyMessage("hello", "addr", "bad_sig"));
    }

    @Test
    void verifyMessage_nullIsGood_returnsFalse() {
        MoneroMessageSignatureResult result = new MoneroMessageSignatureResult(null, false, null, 2);
        when(rpc.verifyMessage("hello", "addr", "sig")).thenReturn(result);

        assertFalse(wallet.verifyMessage("hello", "addr", "sig"));
    }

    // --- account index ---

    @Test
    void customAccountIndex_usedInBalanceCall() {
        WalletConnectionConfig config = new WalletConnectionConfig(
                Coin.XMR, "http://127.0.0.1:18083", "", "", Map.of(
                MoneroExtras.ACCOUNT_INDEX, "2"
        ));
        MoneroRpcAdapter customRpc = mock(MoneroRpcAdapter.class);
        MoneroWallet customWallet = new MoneroWallet(config, customRpc);

        when(customRpc.getBalance(2)).thenReturn(BigInteger.ZERO);
        when(customRpc.getUnlockedBalance(2)).thenReturn(BigInteger.ZERO);

        customWallet.balance();

        verify(customRpc).getBalance(2);
        verify(customRpc).getUnlockedBalance(2);
    }

    @Test
    void invalidAccountIndex_defaultsToZero() {
        WalletConnectionConfig config = new WalletConnectionConfig(
                Coin.XMR, "http://127.0.0.1:18083", "", "", Map.of(
                MoneroExtras.ACCOUNT_INDEX, "not_a_number"
        ));
        MoneroRpcAdapter customRpc = mock(MoneroRpcAdapter.class);
        MoneroWallet customWallet = new MoneroWallet(config, customRpc);

        when(customRpc.getBalance(0)).thenReturn(BigInteger.ZERO);
        when(customRpc.getUnlockedBalance(0)).thenReturn(BigInteger.ZERO);

        customWallet.balance();

        verify(customRpc).getBalance(0);
    }

    // --- recent transactions ---

    @Test
    void recentIncoming_includesConfirmedTransfers() {
        MoneroTxWallet tx = mock(MoneroTxWallet.class);
        when(tx.getHash()).thenReturn("confirmed_tx");
        when(tx.getNumConfirmations()).thenReturn(15L);
        when(tx.getFee()).thenReturn(BigInteger.ZERO);
        when(tx.getReceivedTimestamp()).thenReturn(1700000000L);
        when(tx.getKey()).thenReturn(null);

        MoneroIncomingTransfer transfer = mock(MoneroIncomingTransfer.class);
        when(transfer.getTx()).thenReturn(tx);
        when(transfer.getAddress()).thenReturn("addr");
        when(transfer.getAmount()).thenReturn(new BigInteger("1000000000000"));

        when(rpc.getIncomingTransfers(any(MoneroTransferQuery.class)))
                .thenReturn(List.of(transfer));

        List<Transaction> recent = wallet.recentIncoming();

        assertEquals(1, recent.size());
        assertTrue(recent.get(0).confirmed());
        assertEquals(15, recent.get(0).confirmations());
    }

    // --- sweep() ---

    @Test
    void sweep_success_singleTx() {
        MoneroTxWallet tx = mock(MoneroTxWallet.class);
        when(tx.getHash()).thenReturn("sweep_tx_hash_1");
        when(tx.getOutgoingAmount()).thenReturn(new BigInteger("5000000000000")); // 5 XMR
        when(tx.getFee()).thenReturn(new BigInteger("20000000")); // 0.00002 XMR
        when(tx.getKey()).thenReturn("sweep_tx_key");
        when(rpc.sweepAll(any(MoneroTxConfig.class))).thenReturn(List.of(tx));

        SweepRequest request = new SweepRequest(Coin.XMR, "dest_address", Map.of());
        SweepResult result = wallet.sweep(request);

        assertEquals(Coin.XMR, result.coin());
        assertEquals(List.of("sweep_tx_hash_1"), result.txIds());
        assertEquals(0, new BigDecimal("5.000000000000").compareTo(result.totalAmount()));
        assertEquals(0, new BigDecimal("0.000020000000").compareTo(result.totalFee()));
        assertEquals("sweep_tx_key", result.extras().get(MoneroExtras.TX_KEY));
    }

    @Test
    void sweep_success_multipleTxs() {
        MoneroTxWallet tx1 = mock(MoneroTxWallet.class);
        when(tx1.getHash()).thenReturn("sweep_hash_a");
        when(tx1.getOutgoingAmount()).thenReturn(new BigInteger("3000000000000")); // 3 XMR
        when(tx1.getFee()).thenReturn(new BigInteger("10000000")); // 0.00001 XMR
        when(tx1.getKey()).thenReturn("key_a");

        MoneroTxWallet tx2 = mock(MoneroTxWallet.class);
        when(tx2.getHash()).thenReturn("sweep_hash_b");
        when(tx2.getOutgoingAmount()).thenReturn(new BigInteger("2000000000000")); // 2 XMR
        when(tx2.getFee()).thenReturn(new BigInteger("15000000")); // 0.000015 XMR
        when(tx2.getKey()).thenReturn("key_b");

        when(rpc.sweepAll(any(MoneroTxConfig.class))).thenReturn(List.of(tx1, tx2));

        SweepRequest request = new SweepRequest(Coin.XMR, "dest_address", Map.of());
        SweepResult result = wallet.sweep(request);

        assertEquals(2, result.txIds().size());
        assertTrue(result.txIds().contains("sweep_hash_a"));
        assertTrue(result.txIds().contains("sweep_hash_b"));
        assertEquals(0, new BigDecimal("5.000000000000").compareTo(result.totalAmount()));
        assertEquals(0, new BigDecimal("0.000025000000").compareTo(result.totalFee()));
        // tx_key not set when multiple txs
        assertFalse(result.extras().containsKey(MoneroExtras.TX_KEY));
    }

    @Test
    void sweep_notEnoughFunds_throwsInsufficientFunds() {
        when(rpc.sweepAll(any(MoneroTxConfig.class)))
                .thenThrow(new MoneroError("not enough money"));
        when(rpc.getUnlockedBalance(0)).thenReturn(BigInteger.ZERO);

        SweepRequest request = new SweepRequest(Coin.XMR, "dest_address", Map.of());

        assertThrows(WalletInsufficientFundsException.class, () -> wallet.sweep(request));
    }

    @Test
    void sweep_connectionError_throwsWalletConnectionException() {
        when(rpc.sweepAll(any(MoneroTxConfig.class)))
                .thenThrow(new MoneroError("connection refused"));

        SweepRequest request = new SweepRequest(Coin.XMR, "dest_address", Map.of());

        assertThrows(WalletConnectionException.class, () -> wallet.sweep(request));
    }

    @Test
    void sweep_withPriority() {
        MoneroTxWallet tx = mock(MoneroTxWallet.class);
        when(tx.getHash()).thenReturn("sweep_priority_hash");
        when(tx.getOutgoingAmount()).thenReturn(new BigInteger("1000000000000"));
        when(tx.getFee()).thenReturn(new BigInteger("50000000"));
        when(tx.getKey()).thenReturn(null);
        when(rpc.sweepAll(any(MoneroTxConfig.class))).thenReturn(List.of(tx));

        SweepRequest request = new SweepRequest(Coin.XMR, "dest_address",
                Map.of(MoneroExtras.PRIORITY, "elevated"));
        wallet.sweep(request);

        var configCaptor = org.mockito.ArgumentCaptor.forClass(MoneroTxConfig.class);
        verify(rpc).sweepAll(configCaptor.capture());
        assertEquals(MoneroTxPriority.ELEVATED, configCaptor.getValue().getPriority());
    }
}
