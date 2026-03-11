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
import io.konkin.crypto.Coin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LitecoinWalletIT {

    private static final int RPC_PORT = 19332;

    private static final String CONFIG = """
# start with
# litecoind -conf=/path/to/litecoin-testnet.conf

rpcconnect=localhost
rpcuser=testuser
rpcpassword=testpassword
server=1
testnet=1

[test]
rpcbind=127.0.0.1
rpcport=19332
rpcallowip=127.0.0.1
            """;

    static LitecoinWallet wallet;

    @BeforeAll
    static void setUp() {
        assumeTrue(isNodeReachable(), "Litecoin testnet node is not running on localhost:" + RPC_PORT);
        loadWalletIfNeeded();
        WalletConnectionConfig config = new WalletConnectionConfig(
                Coin.LTC,
                "http://127.0.0.1:" + RPC_PORT,
                "testuser",
                "testpassword",
                Map.of(LitecoinExtras.NETWORK, "testnet", LitecoinExtras.WALLET_NAME, "testwallet")
        );
        wallet = new LitecoinWallet(config);
    }

    @Test
    @Order(1)
    void status_returnsAvailableOrSyncing() {
        WalletStatus status = wallet.status();
        assertNotNull(status);
        assertTrue(status == WalletStatus.AVAILABLE || status == WalletStatus.SYNCING,
                "Expected AVAILABLE or SYNCING, got " + status);
        System.out.println("Status: " + status);
    }

    @Test
    @Order(2)
    void balance_returnsNonNull() {
        WalletBalance balance = wallet.balance();
        assertNotNull(balance);
        assertEquals(Coin.LTC, balance.coin());
        assertNotNull(balance.total());
        assertNotNull(balance.spendable());
        System.out.println("Balance: total=" + balance.total() + " spendable=" + balance.spendable());
    }

    @Test
    @Order(3)
    void depositAddress_returnsValidAddress() {
        DepositAddress addr = wallet.depositAddress();
        assertNotNull(addr);
        assertEquals(Coin.LTC, addr.coin());
        assertFalse(addr.address().isBlank());
        System.out.println("Deposit address: " + addr.address());
    }

    @Test
    @Order(4)
    void pendingIncoming_returnsList() {
        List<Transaction> pending = wallet.pendingIncoming();
        assertNotNull(pending);
        System.out.println("Pending incoming: " + pending.size());
    }

    @Test
    @Order(5)
    void pendingOutgoing_returnsList() {
        List<Transaction> pending = wallet.pendingOutgoing();
        assertNotNull(pending);
        System.out.println("Pending outgoing: " + pending.size());
    }

    @Test
    @Order(6)
    void signAndVerifyMessage() {
        String message = "hello from konkin";
        SignedMessage signed = wallet.signMessage(message);
        assertNotNull(signed);
        assertEquals(Coin.LTC, signed.coin());
        assertFalse(signed.signature().isBlank());
        System.out.println("Signed message: addr=" + signed.address() + " sig=" + signed.signature());

        boolean valid = wallet.verifyMessage(message, signed.address(), signed.signature());
        assertTrue(valid, "Signature should be valid");

        boolean invalid = wallet.verifyMessage("tampered", signed.address(), signed.signature());
        assertFalse(invalid, "Tampered message should fail verification");
    }

    private static boolean isNodeReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", RPC_PORT), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void loadWalletIfNeeded() {
        try {
            String body = """
                    {"jsonrpc":"2.0","id":1,"method":"loadwallet","params":{"filename":"testwallet","load_on_startup":true}}""";
            HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + RPC_PORT + "/").toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            String auth = Base64.getEncoder().encodeToString("testuser:testpassword".getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + auth);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            conn.getResponseCode(); // ignore result — already loaded is fine
            conn.disconnect();
        } catch (IOException e) {
            // best-effort, tests will fail with a clear message if wallet is missing
        }
    }
}
