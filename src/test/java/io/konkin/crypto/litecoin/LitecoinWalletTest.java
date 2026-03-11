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

package io.konkin.crypto.litecoin;

import io.konkin.crypto.*;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Sha256Hash;
import org.consensusj.bitcoin.json.pojo.BitcoinTransactionInfo;
import org.consensusj.bitcoin.json.pojo.BlockChainInfo;
import org.consensusj.bitcoin.json.pojo.WalletTransactionInfo;
import org.consensusj.bitcoin.jsonrpc.BitcoinClient;
import org.consensusj.jsonrpc.JsonRpcStatusException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LitecoinWalletTest {

    private BitcoinClient mockClient;
    private LitecoinWallet wallet;

    @BeforeEach
    void setUp() throws Exception {
        WalletConnectionConfig config = new WalletConnectionConfig(
                io.konkin.crypto.Coin.LTC,
                "http://127.0.0.1:9332",
                "testuser",
                "testpass",
                Map.of(LitecoinExtras.NETWORK, "mainnet")
        );
        wallet = new LitecoinWallet(config);

        // Replace the client with a mock via reflection
        mockClient = mock(BitcoinClient.class);
        when(mockClient.getServerURI()).thenReturn(URI.create("http://127.0.0.1:9332"));
        Field clientField = LitecoinWallet.class.getDeclaredField("client");
        clientField.setAccessible(true);
        clientField.set(wallet, mockClient);
    }

    @Test
    void status_returnsAvailable_whenFullySynced() throws Exception {
        BlockChainInfo info = mock(BlockChainInfo.class);
        when(info.getVerificationProgress()).thenReturn(BigDecimal.valueOf(0.9999));
        when(mockClient.getBlockChainInfo()).thenReturn(info);

        assertEquals(WalletStatus.AVAILABLE, wallet.status());
    }

    @Test
    void status_returnsSyncing_whenNotFullySynced() throws Exception {
        BlockChainInfo info = mock(BlockChainInfo.class);
        when(info.getVerificationProgress()).thenReturn(BigDecimal.valueOf(0.5));
        when(mockClient.getBlockChainInfo()).thenReturn(info);

        assertEquals(WalletStatus.SYNCING, wallet.status());
    }

    @Test
    void status_returnsOffline_onConnectionError() throws Exception {
        when(mockClient.getBlockChainInfo()).thenThrow(new IOException("Connection refused"));

        assertEquals(WalletStatus.OFFLINE, wallet.status());
    }

    @Test
    void status_returnsOffline_onRpcError() throws Exception {
        when(mockClient.getBlockChainInfo()).thenThrow(new JsonRpcStatusException("error", 500, null, 0, null, null));

        assertEquals(WalletStatus.OFFLINE, wallet.status());
    }

    @Test
    void balance_returnsCorrectBalance() throws Exception {
        when(mockClient.getBalance()).thenReturn(Coin.valueOf(150_000_000)); // 1.5 LTC

        WalletBalance bal = wallet.balance();
        assertEquals(io.konkin.crypto.Coin.LTC, bal.coin());
        assertEquals(0, new BigDecimal("1.50000000").compareTo(bal.total()));
        assertEquals(0, new BigDecimal("1.50000000").compareTo(bal.spendable()));
    }

    @Test
    void balance_throwsConnectionException_onIOError() throws Exception {
        when(mockClient.getBalance()).thenThrow(new IOException("timeout"));

        assertThrows(WalletConnectionException.class, () -> wallet.balance());
    }

    @Test
    void depositAddress_returnsAddress() throws Exception {
        org.bitcoinj.base.Address mockAddr = mock(org.bitcoinj.base.Address.class);
        when(mockAddr.toString()).thenReturn("ltc1qtest123");
        when(mockClient.getNewAddress(anyString())).thenReturn(mockAddr);

        DepositAddress addr = wallet.depositAddress();
        assertEquals(io.konkin.crypto.Coin.LTC, addr.coin());
        assertEquals("ltc1qtest123", addr.address());
    }

    @Test
    void send_returnsResult() throws Exception {
        String txHash = "a".repeat(64);
        when(mockClient.send(eq("sendtoaddress"), eq(String.class), any(), any(), any(), any()))
                .thenReturn(txHash);

        WalletTransactionInfo txInfo = mock(WalletTransactionInfo.class);
        when(txInfo.getFee()).thenReturn(Coin.valueOf(-10_000)); // -0.0001 LTC fee
        when(mockClient.getTransaction(any(Sha256Hash.class))).thenReturn(txInfo);

        SendRequest request = new SendRequest(io.konkin.crypto.Coin.LTC, "ltc1qdest", new BigDecimal("1.0"), Map.of());
        SendResult result = wallet.send(request);

        assertEquals(io.konkin.crypto.Coin.LTC, result.coin());
        assertEquals(txHash, result.txId());
        assertEquals(0, new BigDecimal("1.0").compareTo(result.amount()));
    }

    @Test
    void send_throwsInsufficientFunds_onRpcError() throws Exception {
        when(mockClient.send(eq("sendtoaddress"), eq(String.class), any(), any(), any(), any()))
                .thenThrow(new JsonRpcStatusException("Insufficient funds", 500, null, -6, null, null));
        when(mockClient.getBalance()).thenReturn(Coin.valueOf(50_000));

        SendRequest request = new SendRequest(io.konkin.crypto.Coin.LTC, "ltc1qdest", new BigDecimal("100.0"), Map.of());
        assertThrows(WalletInsufficientFundsException.class, () -> wallet.send(request));
    }

    @Test
    void sweep_sendsFullBalance() throws Exception {
        when(mockClient.getBalance()).thenReturn(Coin.valueOf(200_000_000)); // 2.0 LTC

        String txHash = "b".repeat(64);
        when(mockClient.send(eq("sendtoaddress"), eq(String.class), any(), any(), any(), any(), eq(true)))
                .thenReturn(txHash);

        WalletTransactionInfo txInfo = mock(WalletTransactionInfo.class);
        when(txInfo.getFee()).thenReturn(Coin.valueOf(-20_000));
        when(mockClient.getTransaction(any(Sha256Hash.class))).thenReturn(txInfo);

        SweepRequest request = new SweepRequest(io.konkin.crypto.Coin.LTC, "ltc1qdest", Map.of());
        SweepResult result = wallet.sweep(request);

        assertEquals(io.konkin.crypto.Coin.LTC, result.coin());
        assertEquals(1, result.txIds().size());
        assertEquals(txHash, result.txIds().get(0));
    }

    @Test
    void sweep_throwsInsufficientFunds_whenBalanceZero() throws Exception {
        when(mockClient.getBalance()).thenReturn(Coin.ZERO);

        SweepRequest request = new SweepRequest(io.konkin.crypto.Coin.LTC, "ltc1qdest", Map.of());
        assertThrows(WalletInsufficientFundsException.class, () -> wallet.sweep(request));
    }

    @Test
    void pendingIncoming_filtersCorrectly() throws Exception {
        BitcoinTransactionInfo receivePending = mockTx("receive", 0, "c".repeat(64));
        BitcoinTransactionInfo receiveConfirmed = mockTx("receive", 3, "d".repeat(64));
        BitcoinTransactionInfo sendPending = mockTx("send", 0, "e".repeat(64));

        when(mockClient.listTransactions(isNull(), eq(100)))
                .thenReturn(List.of(receivePending, receiveConfirmed, sendPending));

        List<Transaction> pending = wallet.pendingIncoming();
        assertEquals(1, pending.size());
        assertEquals("c".repeat(64), pending.get(0).txId());
        assertEquals(TransactionDirection.INCOMING, pending.get(0).direction());
    }

    @Test
    void pendingOutgoing_filtersCorrectly() throws Exception {
        BitcoinTransactionInfo receivePending = mockTx("receive", 0, "c".repeat(64));
        BitcoinTransactionInfo sendPending = mockTx("send", 0, "e".repeat(64));
        BitcoinTransactionInfo sendConfirmed = mockTx("send", 6, "f".repeat(64));

        when(mockClient.listTransactions(isNull(), eq(100)))
                .thenReturn(List.of(receivePending, sendPending, sendConfirmed));

        List<Transaction> pending = wallet.pendingOutgoing();
        assertEquals(1, pending.size());
        assertEquals("e".repeat(64), pending.get(0).txId());
        assertEquals(TransactionDirection.OUTGOING, pending.get(0).direction());
    }

    @Test
    void recentIncoming_includesAllConfirmations() throws Exception {
        BitcoinTransactionInfo receivePending = mockTx("receive", 0, "c".repeat(64));
        BitcoinTransactionInfo receiveConfirmed = mockTx("receive", 3, "d".repeat(64));
        BitcoinTransactionInfo sendPending = mockTx("send", 0, "e".repeat(64));

        when(mockClient.listTransactions(isNull(), eq(100)))
                .thenReturn(List.of(receivePending, receiveConfirmed, sendPending));

        List<Transaction> recent = wallet.recentIncoming();
        assertEquals(2, recent.size());
    }

    @Test
    void recentOutgoing_includesAllConfirmations() throws Exception {
        BitcoinTransactionInfo sendPending = mockTx("send", 0, "e".repeat(64));
        BitcoinTransactionInfo sendConfirmed = mockTx("send", 6, "f".repeat(64));
        BitcoinTransactionInfo receivePending = mockTx("receive", 0, "c".repeat(64));

        when(mockClient.listTransactions(isNull(), eq(100)))
                .thenReturn(List.of(sendPending, sendConfirmed, receivePending));

        List<Transaction> recent = wallet.recentOutgoing();
        assertEquals(2, recent.size());
    }

    @Test
    void signMessage_usesLegacyAddress() throws Exception {
        when(mockClient.send(eq("getnewaddress"), eq(String.class), eq("signing"), eq("legacy")))
                .thenReturn("LSignAddr123");
        when(mockClient.send(eq("signmessage"), eq(String.class), eq("LSignAddr123"), eq("hello")))
                .thenReturn("base64signature");

        SignedMessage signed = wallet.signMessage("hello");
        assertEquals(io.konkin.crypto.Coin.LTC, signed.coin());
        assertEquals("LSignAddr123", signed.address());
        assertEquals("base64signature", signed.signature());
    }

    @Test
    void verifyMessage_returnsBoolean() throws Exception {
        when(mockClient.send(eq("verifymessage"), eq(Boolean.class), eq("LAddr"), eq("sig"), eq("msg")))
                .thenReturn(true);

        assertTrue(wallet.verifyMessage("msg", "LAddr", "sig"));
    }

    @Test
    void verifyMessage_returnsFalse_forInvalidSignature() throws Exception {
        when(mockClient.send(eq("verifymessage"), eq(Boolean.class), eq("LAddr"), eq("badsig"), eq("msg")))
                .thenReturn(false);

        assertFalse(wallet.verifyMessage("msg", "LAddr", "badsig"));
    }

    @Test
    void signMessage_reusesExistingAddress() throws Exception {
        // Set signing address via reflection
        Field sigField = LitecoinWallet.class.getDeclaredField("signingAddress");
        sigField.setAccessible(true);
        sigField.set(wallet, "LExistingAddr");

        when(mockClient.send(eq("signmessage"), eq(String.class), eq("LExistingAddr"), eq("test")))
                .thenReturn("sig123");

        SignedMessage signed = wallet.signMessage("test");
        assertEquals("LExistingAddr", signed.address());
        // Should NOT call getnewaddress
        verify(mockClient, never()).send(eq("getnewaddress"), eq(String.class), any(), any());
    }

    private BitcoinTransactionInfo mockTx(String category, int confirmations, String txId) {
        BitcoinTransactionInfo tx = mock(BitcoinTransactionInfo.class);
        when(tx.getCategory()).thenReturn(category);
        when(tx.getConfirmations()).thenReturn(confirmations);
        when(tx.getTxId()).thenReturn(Sha256Hash.wrap(txId));
        when(tx.getAmount()).thenReturn(Coin.valueOf(100_000_000)); // 1 LTC
        when(tx.getFee()).thenReturn(null);
        org.bitcoinj.base.Address mockAddr = mock(org.bitcoinj.base.Address.class);
        when(mockAddr.toString()).thenReturn("ltc1qaddr");
        when(tx.getAddress()).thenReturn(mockAddr);
        when(tx.getTime()).thenReturn(Instant.now());
        return tx;
    }
}
