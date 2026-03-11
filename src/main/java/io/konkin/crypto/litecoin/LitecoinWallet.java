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
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Network;
import org.consensusj.bitcoin.json.pojo.BitcoinTransactionInfo;
import org.consensusj.bitcoin.json.pojo.BlockChainInfo;
import org.consensusj.bitcoin.json.pojo.WalletTransactionInfo;
import org.consensusj.bitcoin.jsonrpc.BitcoinClient;
import org.consensusj.jsonrpc.JsonRpcStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class LitecoinWallet extends CoinWallet {

    private final BitcoinClient client;
    private volatile String signingAddress;

    public LitecoinWallet(WalletConnectionConfig config) {
        super(Coin.LTC, config);
        this.signingAddress = config.extras().get(LitecoinExtras.SIGNING_ADDRESS);
        URI rpcUri = URI.create(config.rpcUrl());
        String walletName = config.extras().get(LitecoinExtras.WALLET_NAME);
        if (walletName != null && !walletName.isBlank()) {
            String encodedWalletName = URLEncoder.encode(walletName, StandardCharsets.UTF_8);
            rpcUri = URI.create(config.rpcUrl() + "/wallet/" + encodedWalletName);
        }
        // BitcoinClient works with any Bitcoin-compatible daemon; use MAINNET as dummy network param
        this.client = new BitcoinClient(BitcoinNetwork.MAINNET, rpcUri, config.username(), config.password());
    }

    @Override
    public WalletStatus status() {
        try {
            BlockChainInfo info = client.getBlockChainInfo();
            BigDecimal progress = info.getVerificationProgress();
            if (progress.compareTo(BigDecimal.valueOf(0.999)) < 0) {
                return WalletStatus.SYNCING;
            }
            return WalletStatus.AVAILABLE;
        } catch (JsonRpcStatusException e) {
            log.warn("Litecoin RPC error during status check (getblockchaininfo → {}): {}",
                    client.getServerURI(), e.getMessage());
            return WalletStatus.OFFLINE;
        } catch (IOException e) {
            log.warn("Could not reach Litecoin node (getblockchaininfo → {}): {} ({})",
                    client.getServerURI(), e.getMessage(), e.getClass().getSimpleName());
            return WalletStatus.OFFLINE;
        }
    }

    @Override
    public WalletBalance balance() {
        try {
            org.bitcoinj.base.Coin bal = client.getBalance();
            BigDecimal total = bal.toBtc();
            return new WalletBalance(Coin.LTC, total, total);
        } catch (JsonRpcStatusException e) {
            throw new WalletConnectionException("Failed to get LTC balance (getbalance → " + client.getServerURI() + "): " + e.getMessage(), e);
        } catch (IOException e) {
            throw new WalletConnectionException("Failed to connect to Litecoin node (getbalance → " + client.getServerURI() + "): " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public DepositAddress depositAddress() {
        try {
            String label = config().extras().getOrDefault(LitecoinExtras.MEMO, "");
            org.bitcoinj.base.Address addr = client.getNewAddress(label);
            return new DepositAddress(Coin.LTC, addr.toString(), Map.of());
        } catch (JsonRpcStatusException e) {
            throw new WalletOperationException("Failed to generate LTC deposit address (getnewaddress → " + client.getServerURI() + "): " + e.getMessage(), e);
        } catch (IOException e) {
            throw new WalletConnectionException("Failed to connect to Litecoin node (getnewaddress → " + client.getServerURI() + "): " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public SendResult send(SendRequest request) {
        try {
            String comment = request.extras().getOrDefault(LitecoinExtras.MEMO, "");
            String txIdStr = client.send("sendtoaddress", String.class,
                    request.toAddress(), request.amount().toPlainString(), comment, "");

            org.bitcoinj.base.Sha256Hash txId = org.bitcoinj.base.Sha256Hash.wrap(txIdStr);
            WalletTransactionInfo txInfo = client.getTransaction(txId);
            BigDecimal fee = txInfo.getFee() != null ? txInfo.getFee().toBtc().abs() : BigDecimal.ZERO;

            String feeCapStr = request.extras().get("feeCapNative");
            if (feeCapStr != null && !feeCapStr.isBlank()) {
                BigDecimal feeCap = new BigDecimal(feeCapStr);
                if (fee.compareTo(feeCap) > 0) {
                    log.warn("Transaction {} fee {} exceeds fee cap {} — transaction already sent, recording warning",
                            txIdStr, fee.toPlainString(), feeCap.toPlainString());
                }
            }

            return new SendResult(Coin.LTC, txIdStr, request.amount(), fee, Map.of());
        } catch (JsonRpcStatusException e) {
            if (isInsufficientFunds(e)) {
                throw new WalletInsufficientFundsException(request.amount(), safeBalance());
            }
            throw new WalletOperationException("LTC send failed (sendtoaddress → " + client.getServerURI() + "): " + e.getMessage(), e);
        } catch (IOException e) {
            throw new WalletConnectionException("Failed to connect to Litecoin node (sendtoaddress → " + client.getServerURI() + "): " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public SweepResult sweep(SweepRequest request) {
        try {
            BigDecimal balance = client.getBalance().toBtc();
            if (balance.compareTo(BigDecimal.ZERO) <= 0) {
                throw new WalletInsufficientFundsException(BigDecimal.ONE, BigDecimal.ZERO);
            }

            String comment = request.extras().getOrDefault(LitecoinExtras.MEMO, "");
            String txIdStr = client.send("sendtoaddress", String.class,
                    request.toAddress(), balance.toPlainString(), comment, "",
                    true); // subtractfeefromamount

            org.bitcoinj.base.Sha256Hash txId = org.bitcoinj.base.Sha256Hash.wrap(txIdStr);
            WalletTransactionInfo txInfo = client.getTransaction(txId);
            BigDecimal fee = txInfo.getFee() != null ? txInfo.getFee().toBtc().abs() : BigDecimal.ZERO;
            BigDecimal swept = balance.subtract(fee);

            return new SweepResult(Coin.LTC, List.of(txIdStr), swept, fee, Map.of());
        } catch (WalletException e) {
            throw e;
        } catch (JsonRpcStatusException e) {
            if (isInsufficientFunds(e)) {
                throw new WalletInsufficientFundsException(BigDecimal.ONE, safeBalance());
            }
            throw new WalletOperationException("LTC sweep failed (sendtoaddress → " + client.getServerURI() + "): " + e.getMessage(), e);
        } catch (IOException e) {
            throw new WalletConnectionException("Failed to connect to Litecoin node (sendtoaddress → " + client.getServerURI() + "): " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<Transaction> pendingIncoming() {
        return listPending(TransactionDirection.INCOMING);
    }

    @Override
    public List<Transaction> pendingOutgoing() {
        return listPending(TransactionDirection.OUTGOING);
    }

    @Override
    public List<Transaction> recentIncoming() {
        return listRecent(TransactionDirection.INCOMING);
    }

    @Override
    public List<Transaction> recentOutgoing() {
        return listRecent(TransactionDirection.OUTGOING);
    }

    @Override
    public SignedMessage signMessage(String message) {
        try {
            String addr = getOrCreateSigningAddress();
            String signature = client.send("signmessage", String.class, addr, message);
            return new SignedMessage(Coin.LTC, addr, message, signature);
        } catch (JsonRpcStatusException e) {
            throw new WalletOperationException("LTC signMessage failed (signmessage → " + client.getServerURI() + "): " + e.getMessage(), e);
        } catch (IOException e) {
            throw new WalletConnectionException("Failed to connect to Litecoin node (signmessage → " + client.getServerURI() + "): " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private String getOrCreateSigningAddress() throws JsonRpcStatusException, IOException {
        if (signingAddress != null && !signingAddress.isBlank()) {
            return signingAddress;
        }
        // signmessage requires a legacy (p2pkh) address
        String addr = client.send("getnewaddress", String.class, "signing", "legacy");
        signingAddress = addr;
        persistSigningAddress(addr);
        log.info("Generated and persisted Litecoin signing address: {}", addr);
        return addr;
    }

    private void persistSigningAddress(String addr) {
        String configFile = config().extras().get(LitecoinExtras.CONFIG_FILE_PATH);
        if (configFile == null || configFile.isBlank()) {
            log.warn("No config file path available — signing address will not persist across restarts");
            return;
        }
        try (var toml = com.electronwill.nightconfig.core.file.FileConfig.of(Path.of(configFile))) {
            toml.load();
            toml.set("coins.litecoin.signing-address", addr);
            toml.save();
            log.info("Persisted signing address to config.toml");
        } catch (Exception e) {
            log.warn("Failed to persist signing address to {}: {}", configFile, e.getMessage());
        }
    }

    @Override
    public boolean verifyMessage(String message, String address, String signature) {
        try {
            return client.send("verifymessage", Boolean.class, address, signature, message);
        } catch (JsonRpcStatusException e) {
            throw new WalletOperationException("LTC verifyMessage failed (verifymessage → " + client.getServerURI() + "): " + e.getMessage(), e);
        } catch (IOException e) {
            throw new WalletConnectionException("Failed to connect to Litecoin node (verifymessage → " + client.getServerURI() + "): " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private List<Transaction> listPending(TransactionDirection direction) {
        try {
            String category = direction == TransactionDirection.INCOMING ? "receive" : "send";
            List<BitcoinTransactionInfo> all = client.listTransactions(null, 100);
            List<Transaction> pending = new ArrayList<>();
            for (BitcoinTransactionInfo tx : all) {
                if (!category.equals(tx.getCategory())) continue;
                if (tx.getConfirmations() > 0) continue;
                pending.add(toTransaction(tx, direction));
            }
            return List.copyOf(pending);
        } catch (JsonRpcStatusException e) {
            throw new WalletOperationException("Failed to list LTC pending transactions (listtransactions → " + client.getServerURI() + "): " + e.getMessage(), e);
        } catch (IOException e) {
            throw new WalletConnectionException("Failed to connect to Litecoin node (listtransactions → " + client.getServerURI() + "): " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private List<Transaction> listRecent(TransactionDirection direction) {
        try {
            String category = direction == TransactionDirection.INCOMING ? "receive" : "send";
            List<BitcoinTransactionInfo> all = client.listTransactions(null, 100);
            List<Transaction> recent = new ArrayList<>();
            for (BitcoinTransactionInfo tx : all) {
                if (!category.equals(tx.getCategory())) continue;
                recent.add(toTransaction(tx, direction));
            }
            return List.copyOf(recent);
        } catch (JsonRpcStatusException e) {
            throw new WalletOperationException("Failed to list LTC recent transactions (listtransactions → " + client.getServerURI() + "): " + e.getMessage(), e);
        } catch (IOException e) {
            throw new WalletConnectionException("Failed to connect to Litecoin node (listtransactions → " + client.getServerURI() + "): " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private Transaction toTransaction(BitcoinTransactionInfo tx, TransactionDirection direction) {
        BigDecimal amount = tx.getAmount().toBtc().abs();
        BigDecimal fee = tx.getFee() != null ? tx.getFee().toBtc().abs() : BigDecimal.ZERO;
        String address = tx.getAddress() != null ? tx.getAddress().toString() : "";
        Instant timestamp = tx.getTime() != null ? tx.getTime() : Instant.EPOCH;
        boolean confirmed = tx.getConfirmations() > 0;
        return new Transaction(
                Coin.LTC,
                tx.getTxId().toString(),
                direction,
                address,
                amount,
                fee,
                null,
                tx.getConfirmations(),
                confirmed,
                timestamp,
                Map.of()
        );
    }

    private boolean isInsufficientFunds(JsonRpcStatusException e) {
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains("insufficient funds");
    }

    private BigDecimal safeBalance() {
        try {
            return client.getBalance().toBtc();
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
}
