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

package io.konkin.crypto.bitcoincash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.konkin.crypto.*;
import org.bitcoinj.base.Coin;
import org.consensusj.bitcoin.json.pojo.BlockChainInfo;
import org.consensusj.bitcoin.jsonrpc.BitcoinClient;
import org.consensusj.jsonrpc.JsonRpcStatusException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BitcoinCashWalletTest {

    private BitcoinClient mockClient;
    private BitcoinCashWallet wallet;
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        WalletConnectionConfig config = new WalletConnectionConfig(
                io.konkin.crypto.Coin.BCH,
                "http://127.0.0.1:8332",
                "testuser",
                "testpass",
                Map.of(BitcoinCashExtras.NETWORK, "mainnet")
        );
        wallet = new BitcoinCashWallet(config);

        mockClient = mock(BitcoinClient.class);
        when(mockClient.getServerURI()).thenReturn(URI.create("http://127.0.0.1:8332"));
        Field clientField = BitcoinCashWallet.class.getDeclaredField("client");
        clientField.setAccessible(true);
        clientField.set(wallet, mockClient);
    }

    @Test
    void coin_isBCH() {
        assertEquals(io.konkin.crypto.Coin.BCH, wallet.coin());
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
    void status_returnsOffline_onRpcError() throws Exception {
        when(mockClient.getBlockChainInfo()).thenThrow(new JsonRpcStatusException("error", 500, null, 0, null, null));

        assertEquals(WalletStatus.OFFLINE, wallet.status());
    }

    @Test
    void status_returnsOffline_onIOException() throws Exception {
        when(mockClient.getBlockChainInfo()).thenThrow(new IOException("connection refused"));

        assertEquals(WalletStatus.OFFLINE, wallet.status());
    }

    @Test
    void balance_returnsBchAmount() throws Exception {
        when(mockClient.getBalance()).thenReturn(Coin.valueOf(150_000_000)); // 1.5 BCH

        WalletBalance balance = wallet.balance();
        assertEquals(io.konkin.crypto.Coin.BCH, balance.coin());
        assertEquals(0, new BigDecimal("1.50000000").compareTo(balance.total()));
    }

    @Test
    void balance_throwsConnectionException_onIOError() throws Exception {
        when(mockClient.getBalance()).thenThrow(new IOException("timeout"));

        assertThrows(WalletConnectionException.class, wallet::balance);
    }

    @Test
    void depositAddress_returnsAddress() throws Exception {
        when(mockClient.send(eq("getnewaddress"), eq(String.class), anyString()))
                .thenReturn("bitcoincash:qr5atxmlenz4g54dv9230tyss0d75z4elc9rag0f8r");

        DepositAddress addr = wallet.depositAddress();
        assertEquals(io.konkin.crypto.Coin.BCH, addr.coin());
        assertTrue(addr.address().startsWith("bitcoincash:"));
    }

    @Test
    void send_returnsSendResult() throws Exception {
        when(mockClient.send(eq("sendtoaddress"), eq(String.class), any(), any(), any(), any()))
                .thenReturn("txid-bch-123");
        JsonNode txInfo = mapper.readTree("{\"fee\": -0.00001}");
        when(mockClient.send(eq("gettransaction"), eq(JsonNode.class), eq("txid-bch-123")))
                .thenReturn(txInfo);

        SendRequest req = new SendRequest(io.konkin.crypto.Coin.BCH,
                "bitcoincash:qr5atxmlenz4g54dv9230tyss0d75z4elc9rag0f8r",
                new BigDecimal("0.5"), Map.of());

        SendResult result = wallet.send(req);
        assertEquals("txid-bch-123", result.txId());
        assertEquals(io.konkin.crypto.Coin.BCH, result.coin());
        assertEquals(0, new BigDecimal("0.5").compareTo(result.amount()));
    }

    @Test
    void send_throwsInsufficientFunds() throws Exception {
        JsonRpcStatusException rpcError = new JsonRpcStatusException("Insufficient funds", 500, null, -6, null, null);
        when(mockClient.send(eq("sendtoaddress"), eq(String.class), any(), any(), any(), any()))
                .thenThrow(rpcError);
        when(mockClient.getBalance()).thenReturn(Coin.valueOf(10_000)); // 0.0001 BCH

        SendRequest req = new SendRequest(io.konkin.crypto.Coin.BCH,
                "bitcoincash:qr5atxmlenz4g54dv9230tyss0d75z4elc9rag0f8r",
                new BigDecimal("10.0"), Map.of());

        assertThrows(WalletInsufficientFundsException.class, () -> wallet.send(req));
    }

    @Test
    void sweep_returnsSweepResult() throws Exception {
        when(mockClient.getBalance()).thenReturn(Coin.valueOf(200_000_000)); // 2.0 BCH
        when(mockClient.send(eq("sendtoaddress"), eq(String.class), any(), any(), any(), any(), eq(true)))
                .thenReturn("txid-sweep-bch");
        JsonNode txInfo = mapper.readTree("{\"fee\": -0.00001}");
        when(mockClient.send(eq("gettransaction"), eq(JsonNode.class), eq("txid-sweep-bch")))
                .thenReturn(txInfo);

        SweepRequest req = new SweepRequest(io.konkin.crypto.Coin.BCH,
                "bitcoincash:qr5atxmlenz4g54dv9230tyss0d75z4elc9rag0f8r", Map.of());

        SweepResult result = wallet.sweep(req);
        assertEquals(io.konkin.crypto.Coin.BCH, result.coin());
        assertTrue(result.txIds().contains("txid-sweep-bch"));
    }

    @Test
    void sweep_throwsInsufficientFunds_whenBalanceIsZero() throws Exception {
        when(mockClient.getBalance()).thenReturn(Coin.ZERO);

        SweepRequest req = new SweepRequest(io.konkin.crypto.Coin.BCH,
                "bitcoincash:qr5atxmlenz4g54dv9230tyss0d75z4elc9rag0f8r", Map.of());

        assertThrows(WalletInsufficientFundsException.class, () -> wallet.sweep(req));
    }

    @Test
    void signMessage_returnsSignedMessage() throws Exception {
        Field sigField = BitcoinCashWallet.class.getDeclaredField("signingAddress");
        sigField.setAccessible(true);
        sigField.set(wallet, "1BCHaddr");

        when(mockClient.send(eq("signmessage"), eq(String.class), eq("1BCHaddr"), eq("hello")))
                .thenReturn("signature-bch");

        SignedMessage result = wallet.signMessage("hello");
        assertEquals(io.konkin.crypto.Coin.BCH, result.coin());
        assertEquals("1BCHaddr", result.address());
        assertEquals("signature-bch", result.signature());
    }

    @Test
    void verifyMessage_returnsTrue() throws Exception {
        when(mockClient.send(eq("verifymessage"), eq(Boolean.class), eq("1BCHaddr"), eq("sig"), eq("msg")))
                .thenReturn(true);

        assertTrue(wallet.verifyMessage("msg", "1BCHaddr", "sig"));
    }

    @Test
    void verifyMessage_returnsFalse() throws Exception {
        when(mockClient.send(eq("verifymessage"), eq(Boolean.class), any(), any(), any()))
                .thenReturn(false);

        assertFalse(wallet.verifyMessage("msg", "addr", "badsig"));
    }

    @Test
    void pendingIncoming_returnsUnconfirmedReceive() throws Exception {
        String json = """
                [
                  {"txid":"tx1","category":"receive","address":"bchaddr1","amount":0.5,"fee":0,"confirmations":0,"time":1700000000},
                  {"txid":"tx2","category":"send","address":"bchaddr2","amount":-1.0,"fee":-0.0001,"confirmations":0,"time":1700000001},
                  {"txid":"tx3","category":"receive","address":"bchaddr3","amount":0.3,"fee":0,"confirmations":6,"time":1700000002}
                ]
                """;
        when(mockClient.send(eq("listtransactions"), eq(JsonNode.class), eq("*"), eq(100)))
                .thenReturn(mapper.readTree(json));

        List<Transaction> pending = wallet.pendingIncoming();
        assertEquals(1, pending.size());
        assertEquals("tx1", pending.get(0).txId());
        assertEquals(TransactionDirection.INCOMING, pending.get(0).direction());
    }

    @Test
    void pendingOutgoing_returnsUnconfirmedSend() throws Exception {
        String json = """
                [
                  {"txid":"tx1","category":"send","address":"bchaddr1","amount":-1.0,"fee":-0.0001,"confirmations":0,"time":1700000000},
                  {"txid":"tx2","category":"receive","address":"bchaddr2","amount":0.5,"fee":0,"confirmations":0,"time":1700000001}
                ]
                """;
        when(mockClient.send(eq("listtransactions"), eq(JsonNode.class), eq("*"), eq(100)))
                .thenReturn(mapper.readTree(json));

        List<Transaction> pending = wallet.pendingOutgoing();
        assertEquals(1, pending.size());
        assertEquals("tx1", pending.get(0).txId());
        assertEquals(TransactionDirection.OUTGOING, pending.get(0).direction());
    }

    @Test
    void supportsBatchSend_returnsTrue() {
        assertTrue(wallet.supportsBatchSend());
    }

    @Test
    void batchSend_returnsBatchResult() throws Exception {
        when(mockClient.send(eq("sendmany"), eq(String.class), eq(""), any()))
                .thenReturn("txid-batch-bch");
        JsonNode txInfo = mapper.readTree("{\"fee\": -0.00002}");
        when(mockClient.send(eq("gettransaction"), eq(JsonNode.class), eq("txid-batch-bch")))
                .thenReturn(txInfo);

        List<SendRequest> requests = List.of(
                new SendRequest(io.konkin.crypto.Coin.BCH, "addr1", new BigDecimal("0.1"), Map.of()),
                new SendRequest(io.konkin.crypto.Coin.BCH, "addr2", new BigDecimal("0.2"), Map.of())
        );

        BatchSendResult result = wallet.batchSend(requests);
        assertEquals(io.konkin.crypto.Coin.BCH, result.coin());
        assertEquals("txid-batch-bch", result.txId());
        assertEquals(2, result.amountsByAddress().size());
    }
}
