package io.konkin.crypto.bitcoin;

import io.konkin.crypto.*;
import io.konkin.crypto.Coin;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Network;
import org.bitcoinj.core.NetworkParameters;
import org.consensusj.bitcoin.json.pojo.BitcoinTransactionInfo;
import org.consensusj.bitcoin.json.pojo.BlockChainInfo;
import org.consensusj.bitcoin.json.pojo.WalletTransactionInfo;
import org.consensusj.bitcoin.jsonrpc.BitcoinClient;
import org.consensusj.jsonrpc.JsonRpcStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BitcoinWallet extends CoinWallet {

    private final BitcoinClient client;
    private final Network network;

    public BitcoinWallet(WalletConnectionConfig config) {
        super(Coin.BTC, config);
        this.network = resolveNetwork(config);
        URI rpcUri = URI.create(config.rpcUrl());
        String walletName = config.extras().get(BitcoinExtras.WALLET_NAME);
        if (walletName != null && !walletName.isBlank()) {
            rpcUri = URI.create(config.rpcUrl() + "/wallet/" + walletName);
        }
        this.client = new BitcoinClient(network, rpcUri, config.username(), config.password());
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
            log.warn("Bitcoin RPC error during status check: {}", e.getMessage());
            return WalletStatus.OFFLINE;
        } catch (IOException e) {
            log.warn("Could not reach Bitcoin node: {}", e.getMessage());
            return WalletStatus.OFFLINE;
        }
    }

    @Override
    public WalletBalance balance() {
        try {
            org.bitcoinj.base.Coin bal = client.getBalance();
            BigDecimal total = bal.toBtc();
            // Bitcoin Core's getbalance returns the spendable (confirmed) balance
            return new WalletBalance(Coin.BTC, total, total);
        } catch (JsonRpcStatusException e) {
            throw new WalletConnectionException("Failed to get BTC balance: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new WalletConnectionException("Failed to connect to Bitcoin node", e);
        }
    }

    @Override
    public DepositAddress depositAddress() {
        try {
            String label = config().extras().getOrDefault(BitcoinExtras.MEMO, "");
            org.bitcoinj.base.Address addr = client.getNewAddress(label);
            return new DepositAddress(Coin.BTC, addr.toString(), Map.of());
        } catch (JsonRpcStatusException e) {
            throw new WalletOperationException("Failed to generate BTC deposit address: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new WalletConnectionException("Failed to connect to Bitcoin node", e);
        }
    }

    @Override
    public SendResult send(SendRequest request) {
        try {
            org.bitcoinj.base.Address toAddr = parseAddress(request.toAddress());
            org.bitcoinj.base.Coin amount = org.bitcoinj.base.Coin.ofBtc(request.amount());

            String comment = request.extras().getOrDefault(BitcoinExtras.MEMO, "");
            org.bitcoinj.base.Sha256Hash txId = client.sendToAddress(toAddr, amount, comment, "");

            WalletTransactionInfo txInfo = client.getTransaction(txId);
            BigDecimal fee = txInfo.getFee() != null ? txInfo.getFee().toBtc().abs() : BigDecimal.ZERO;

            return new SendResult(Coin.BTC, txId.toString(), request.amount(), fee, Map.of());
        } catch (JsonRpcStatusException e) {
            if (isInsufficientFunds(e)) {
                throw new WalletInsufficientFundsException(request.amount(), safeBalance());
            }
            throw new WalletOperationException("BTC send failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new WalletConnectionException("Failed to connect to Bitcoin node", e);
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
    public SignedMessage signMessage(String message) {
        try {
            // signmessage requires a legacy (p2pkh) address
            String addr = client.send("getnewaddress", String.class, "", "legacy");
            String signature = client.send("signmessage", String.class, addr, message);
            return new SignedMessage(Coin.BTC, addr, message, signature);
        } catch (JsonRpcStatusException e) {
            throw new WalletOperationException("BTC signMessage failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new WalletConnectionException("Failed to connect to Bitcoin node", e);
        }
    }

    @Override
    public boolean verifyMessage(String message, String address, String signature) {
        try {
            return client.send("verifymessage", Boolean.class, address, signature, message);
        } catch (JsonRpcStatusException e) {
            throw new WalletOperationException("BTC verifyMessage failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new WalletConnectionException("Failed to connect to Bitcoin node", e);
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
            throw new WalletOperationException("Failed to list BTC pending transactions: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new WalletConnectionException("Failed to connect to Bitcoin node", e);
        }
    }

    private Transaction toTransaction(BitcoinTransactionInfo tx, TransactionDirection direction) {
        BigDecimal amount = tx.getAmount().toBtc().abs();
        BigDecimal fee = tx.getFee() != null ? tx.getFee().toBtc().abs() : BigDecimal.ZERO;
        String address = tx.getAddress() != null ? tx.getAddress().toString() : "";
        Instant timestamp = tx.getTime() != null ? tx.getTime() : Instant.EPOCH;
        boolean confirmed = tx.getConfirmations() > 0;
        return new Transaction(
                Coin.BTC,
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

    private org.bitcoinj.base.Address parseAddress(String address) {
        return org.bitcoinj.base.Address.fromString(NetworkParameters.of(network), address);
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

    private static Network resolveNetwork(WalletConnectionConfig config) {
        String net = config.extras().getOrDefault(BitcoinExtras.NETWORK, "mainnet").toLowerCase();
        return switch (net) {
            case "testnet", "testnet3", "testnet4", "test" -> BitcoinNetwork.TESTNET;
            case "signet" -> BitcoinNetwork.SIGNET;
            case "regtest" -> BitcoinNetwork.REGTEST;
            default -> BitcoinNetwork.MAINNET;
        };
    }
}
