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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MoneroWallet extends CoinWallet {

    private final MoneroRpcAdapter rpc;
    private final int accountIndex;

    public MoneroWallet(WalletConnectionConfig config) {
        this(config, new DefaultMoneroRpcAdapter(config));
    }

    // Visible for testing
    MoneroWallet(WalletConnectionConfig config, MoneroRpcAdapter rpc) {
        super(Coin.XMR, config);
        this.rpc = rpc;
        this.accountIndex = parseAccountIndex(config);
    }

    @Override
    public WalletStatus status() {
        try {
            if (rpc.hasDaemon()) {
                MoneroDaemonInfo info = rpc.getDaemonInfo();
                if (info.isSynchronized() == null || !info.isSynchronized()) {
                    return WalletStatus.SYNCING;
                }
            }
            // Verify wallet-rpc is responding
            rpc.getWalletHeight();
            return WalletStatus.AVAILABLE;
        } catch (MoneroError e) {
            log.warn("Monero status check failed: {}", e.getMessage());
            return WalletStatus.OFFLINE;
        }
    }

    @Override
    public WalletBalance balance() {
        try {
            BigInteger total = rpc.getBalance(accountIndex);
            BigInteger unlocked = rpc.getUnlockedBalance(accountIndex);
            return new WalletBalance(Coin.XMR, atomicToXmr(total), atomicToXmr(unlocked));
        } catch (MoneroError e) {
            throw mapMoneroError(e, "balance");
        }
    }

    @Override
    public DepositAddress depositAddress() {
        try {
            MoneroSubaddress subaddress = rpc.createSubaddress(accountIndex);
            return new DepositAddress(Coin.XMR, subaddress.getAddress(), Map.of());
        } catch (MoneroError e) {
            throw mapMoneroError(e, "depositAddress");
        }
    }

    @Override
    public SendResult send(SendRequest request) {
        try {
            BigInteger amountAtomic = xmrToAtomic(request.amount());

            MoneroTxConfig txConfig = new MoneroTxConfig()
                    .setAccountIndex(accountIndex)
                    .addDestination(request.toAddress(), amountAtomic)
                    .setRelay(true);

            String priorityStr = request.extras().get(MoneroExtras.PRIORITY);
            if (priorityStr != null && !priorityStr.isBlank()) {
                txConfig.setPriority(parsePriority(priorityStr));
            }

            MoneroTxWallet tx = rpc.createTx(txConfig);

            String txHash = tx.getHash();
            BigDecimal fee = tx.getFee() != null ? atomicToXmr(tx.getFee()) : BigDecimal.ZERO;
            String txKey = tx.getKey();

            // Fee cap check (post-send warning, same as Bitcoin)
            String feeCapStr = request.extras().get(MoneroExtras.FEE_CAP_NATIVE);
            if (feeCapStr != null && !feeCapStr.isBlank()) {
                BigDecimal feeCap = new BigDecimal(feeCapStr);
                if (fee.compareTo(feeCap) > 0) {
                    log.warn("Transaction {} fee {} exceeds fee cap {} — transaction already sent, recording warning",
                            txHash, fee.toPlainString(), feeCap.toPlainString());
                }
            }

            Map<String, String> extras = new LinkedHashMap<>();
            if (txKey != null) {
                extras.put(MoneroExtras.TX_KEY, txKey);
            }

            return new SendResult(Coin.XMR, txHash, request.amount(), fee, extras);
        } catch (MoneroError e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("not enough")) {
                throw new WalletInsufficientFundsException(request.amount(), safeBalance());
            }
            throw mapMoneroError(e, "send");
        }
    }

    @Override
    public List<Transaction> pendingIncoming() {
        return listTransfers(TransactionDirection.INCOMING, true);
    }

    @Override
    public List<Transaction> pendingOutgoing() {
        return listTransfers(TransactionDirection.OUTGOING, true);
    }

    @Override
    public List<Transaction> recentIncoming() {
        return listTransfers(TransactionDirection.INCOMING, false);
    }

    @Override
    public List<Transaction> recentOutgoing() {
        return listTransfers(TransactionDirection.OUTGOING, false);
    }

    @Override
    public SignedMessage signMessage(String message) {
        try {
            String address = rpc.getPrimaryAddress();
            String signature = rpc.signMessage(message);
            return new SignedMessage(Coin.XMR, address, message, signature);
        } catch (MoneroError e) {
            throw mapMoneroError(e, "signMessage");
        }
    }

    @Override
    public boolean verifyMessage(String message, String address, String signature) {
        try {
            MoneroMessageSignatureResult result = rpc.verifyMessage(message, address, signature);
            return result.isGood() != null && result.isGood();
        } catch (MoneroError e) {
            throw mapMoneroError(e, "verifyMessage");
        }
    }

    private List<Transaction> listTransfers(TransactionDirection direction, boolean pendingOnly) {
        try {
            List<Transaction> result = new ArrayList<>();

            if (direction == TransactionDirection.INCOMING) {
                MoneroTransferQuery query = new MoneroTransferQuery()
                        .setAccountIndex(accountIndex);
                if (pendingOnly) {
                    query.setTxQuery(new MoneroTxQuery().setIsConfirmed(false));
                }
                List<MoneroIncomingTransfer> transfers = rpc.getIncomingTransfers(query);
                for (MoneroIncomingTransfer transfer : transfers) {
                    result.add(toTransaction(transfer));
                }
            } else {
                MoneroTransferQuery query = new MoneroTransferQuery()
                        .setAccountIndex(accountIndex);
                if (pendingOnly) {
                    query.setTxQuery(new MoneroTxQuery().setIsConfirmed(false));
                }
                List<MoneroOutgoingTransfer> transfers = rpc.getOutgoingTransfers(query);
                for (MoneroOutgoingTransfer transfer : transfers) {
                    result.add(toTransaction(transfer));
                }
            }

            return List.copyOf(result);
        } catch (MoneroError e) {
            throw mapMoneroError(e, "listTransfers");
        }
    }

    private Transaction toTransaction(MoneroIncomingTransfer transfer) {
        MoneroTxWallet tx = transfer.getTx();
        String address = transfer.getAddress() != null ? transfer.getAddress() : rpc.getPrimaryAddress();
        long confirmations = tx.getNumConfirmations() != null ? tx.getNumConfirmations() : 0;
        BigDecimal fee = tx.getFee() != null ? atomicToXmr(tx.getFee()) : BigDecimal.ZERO;
        Instant timestamp = tx.getReceivedTimestamp() != null
                ? Instant.ofEpochSecond(tx.getReceivedTimestamp())
                : Instant.now();

        return new Transaction(
                Coin.XMR,
                tx.getHash(),
                TransactionDirection.INCOMING,
                address,
                atomicToXmr(transfer.getAmount()),
                fee,
                tx.getKey(),
                (int) confirmations,
                confirmations > 0,
                timestamp,
                Map.of()
        );
    }

    private Transaction toTransaction(MoneroOutgoingTransfer transfer) {
        MoneroTxWallet tx = transfer.getTx();
        // Use first destination address, or first subaddress
        String address = "";
        List<MoneroDestination> destinations = transfer.getDestinations();
        if (destinations != null && !destinations.isEmpty()) {
            address = destinations.get(0).getAddress();
        } else {
            List<String> addresses = transfer.getAddresses();
            if (addresses != null && !addresses.isEmpty()) {
                address = addresses.get(0);
            }
        }
        if (address == null || address.isEmpty()) {
            address = "unknown";
        }
        long confirmations = tx.getNumConfirmations() != null ? tx.getNumConfirmations() : 0;
        BigDecimal fee = tx.getFee() != null ? atomicToXmr(tx.getFee()) : BigDecimal.ZERO;
        Instant timestamp = tx.getReceivedTimestamp() != null
                ? Instant.ofEpochSecond(tx.getReceivedTimestamp())
                : Instant.now();

        return new Transaction(
                Coin.XMR,
                tx.getHash(),
                TransactionDirection.OUTGOING,
                address,
                atomicToXmr(transfer.getAmount()),
                fee,
                tx.getKey(),
                (int) confirmations,
                confirmations > 0,
                timestamp,
                Map.of()
        );
    }

    private WalletException mapMoneroError(MoneroError e, String operation) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("connection") || msg.contains("refused") || msg.contains("timeout")
                || msg.contains("failed to connect") || msg.contains("unreachable")) {
            return new WalletConnectionException("XMR " + operation + " failed: " + e.getMessage(), e);
        }
        return new WalletOperationException("XMR " + operation + " failed: " + e.getMessage(), e);
    }

    private BigDecimal safeBalance() {
        try {
            return atomicToXmr(rpc.getUnlockedBalance(accountIndex));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal atomicToXmr(BigInteger atomicUnits) {
        return new BigDecimal(atomicUnits).movePointLeft(12);
    }

    private static BigInteger xmrToAtomic(BigDecimal xmr) {
        return xmr.movePointRight(12).toBigIntegerExact();
    }

    private static MoneroTxPriority parsePriority(String priority) {
        return switch (priority.toLowerCase()) {
            case "0", "default" -> MoneroTxPriority.DEFAULT;
            case "1", "unimportant" -> MoneroTxPriority.UNIMPORTANT;
            case "2", "normal" -> MoneroTxPriority.NORMAL;
            case "3", "elevated" -> MoneroTxPriority.ELEVATED;
            default -> MoneroTxPriority.DEFAULT;
        };
    }

    private static int parseAccountIndex(WalletConnectionConfig config) {
        String indexStr = config.extras().get(MoneroExtras.ACCOUNT_INDEX);
        if (indexStr == null || indexStr.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(indexStr.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}