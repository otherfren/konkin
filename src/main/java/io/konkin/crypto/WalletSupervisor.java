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

package io.konkin.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.function.Function;

public class WalletSupervisor {

    private static final Logger log = LoggerFactory.getLogger(WalletSupervisor.class);
    private static final long HEARTBEAT_INTERVAL_SECONDS = 30;
    private static final long RPC_TIMEOUT_SECONDS = 15;

    private final WalletConnectionConfig config;
    private final CoinWalletFactory walletFactory;

    private volatile CoinWallet wallet;
    private volatile WalletSnapshot snapshot;
    private volatile boolean running;

    private ExecutorService rpcExecutor;
    private ScheduledExecutorService scheduler;

    public WalletSupervisor(WalletConnectionConfig config, CoinWalletFactory walletFactory) {
        this.config = config;
        this.walletFactory = walletFactory;
        this.snapshot = new WalletSnapshot(config.coin(), WalletStatus.OFFLINE, null, null, null);
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;

        rpcExecutor = Executors.newFixedThreadPool(4, new ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "wallet-rpc-" + config.coin() + "-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wallet-heartbeat-" + config.coin());
            t.setDaemon(true);
            return t;
        });

        // Initial connect attempt (non-blocking — runs on rpc executor)
        scheduler.schedule(this::heartbeat, 0, TimeUnit.SECONDS);

        // Periodic heartbeat
        scheduler.scheduleAtFixedRate(this::heartbeat,
                HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);

        log.info("WalletSupervisor started for {} (heartbeat={}s)", config.coin(), HEARTBEAT_INTERVAL_SECONDS);
    }

    public void close() {
        running = false;

        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        if (rpcExecutor != null) {
            rpcExecutor.shutdown();
            try {
                if (!rpcExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    rpcExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                rpcExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            rpcExecutor = null;
        }

        snapshot = new WalletSnapshot(config.coin(), WalletStatus.OFFLINE, null, null, null);
        log.info("WalletSupervisor closed for {}", config.coin());
    }

    public WalletSnapshot snapshot() {
        return snapshot;
    }

    public <T> T execute(Function<CoinWallet, T> action) {
        if (!running) {
            throw new WalletConnectionException("Wallet supervisor is not running");
        }

        WalletSnapshot current = snapshot;
        if (current.status() == WalletStatus.OFFLINE) {
            throw new WalletConnectionException("Wallet is offline");
        }

        try {
            Future<T> future = rpcExecutor.submit(() -> action.apply(wallet));
            return future.get(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof WalletException we) {
                throw we;
            }
            throw new WalletConnectionException("Wallet operation failed: " + cause.getMessage(), cause);
        } catch (TimeoutException e) {
            throw new WalletConnectionException("Wallet operation timed out after " + RPC_TIMEOUT_SECONDS + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WalletConnectionException("Wallet operation interrupted");
        } catch (RejectedExecutionException e) {
            throw new WalletConnectionException("Wallet supervisor is shutting down");
        }
    }

    private void heartbeat() {
        if (!running) {
            return;
        }

        try {
            doHeartbeat();
        } catch (Exception e) {
            log.debug("Heartbeat failed: {}", e.getMessage());
        }
    }

    private void doHeartbeat() {
        WalletStatus previousStatus = snapshot.status();

        try {
            if (wallet == null) {
                connectWallet();
            }

            WalletStatus status = wallet.status();
            if (status == WalletStatus.OFFLINE) {
                handleOffline(previousStatus);
                return;
            }

            // Fetch balance for snapshot
            BigDecimal totalBalance = null;
            BigDecimal spendableBalance = null;
            try {
                WalletBalance balance = wallet.balance();
                totalBalance = balance.total();
                spendableBalance = balance.spendable();
            } catch (WalletException e) {
                log.debug("Balance fetch failed during heartbeat: {}", e.getMessage());
            }

            WalletSnapshot newSnapshot = new WalletSnapshot(
                    config.coin(), status, totalBalance, spendableBalance, Instant.now());
            snapshot = newSnapshot;

            if (previousStatus != status) {
                log.info("Wallet {} status: {} → {}", config.coin(), previousStatus, status);
            }
        } catch (Exception e) {
            log.warn("Heartbeat failed for {}: {}", config.coin(), e.getMessage());
            handleOffline(previousStatus);
        }
    }

    private void handleOffline(WalletStatus previousStatus) {
        // Let the factory prepare the node (e.g., Bitcoin's loadwallet after node restart)
        walletFactory.prepareNode(config);

        // Try recreating wallet connection
        try {
            wallet = walletFactory.create(config);
            WalletStatus retryStatus = wallet.status();
            if (retryStatus != WalletStatus.OFFLINE) {
                BigDecimal total = null;
                BigDecimal spendable = null;
                try {
                    WalletBalance bal = wallet.balance();
                    total = bal.total();
                    spendable = bal.spendable();
                } catch (WalletException ignored) {}

                snapshot = new WalletSnapshot(config.coin(), retryStatus, total, spendable, Instant.now());
                log.info("Wallet {} reconnected: {} → {}", config.coin(), previousStatus, retryStatus);
                return;
            }
        } catch (Exception e) {
            log.debug("Wallet reconnect failed: {}", e.getMessage());
        }

        if (previousStatus != WalletStatus.OFFLINE) {
            log.warn("Wallet {} went OFFLINE (was {})", config.coin(), previousStatus);
        }
        snapshot = new WalletSnapshot(config.coin(), WalletStatus.OFFLINE, null, null, snapshot.lastHeartbeat());
    }

    private void connectWallet() {
        walletFactory.prepareNode(config);
        wallet = walletFactory.create(config);
        log.info("Wallet {} created", config.coin());
    }
}