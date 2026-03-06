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

import io.konkin.crypto.WalletConnectionConfig;
import monero.daemon.MoneroDaemonRpc;
import monero.daemon.model.MoneroDaemonInfo;
import monero.wallet.MoneroWalletRpc;
import monero.wallet.model.*;

import java.math.BigInteger;
import java.util.List;

/**
 * Production implementation that delegates to real monero-java RPC clients.
 */
final class DefaultMoneroRpcAdapter implements MoneroRpcAdapter {

    private final MoneroWalletRpc walletRpc;
    private final MoneroDaemonRpc daemonRpc;

    DefaultMoneroRpcAdapter(WalletConnectionConfig config) {
        String walletRpcUrl = config.rpcUrl();
        if (config.username() != null && !config.username().isEmpty()) {
            this.walletRpc = new MoneroWalletRpc(walletRpcUrl, config.username(), config.password());
        } else {
            this.walletRpc = new MoneroWalletRpc(walletRpcUrl);
        }

        String daemonUrl = config.extras().get(MoneroExtras.DAEMON_RPC_URL);
        if (daemonUrl != null && !daemonUrl.isBlank()) {
            String daemonUser = config.extras().get(MoneroExtras.DAEMON_RPC_USERNAME);
            String daemonPass = config.extras().get(MoneroExtras.DAEMON_RPC_PASSWORD);
            if (daemonUser != null && !daemonUser.isEmpty()) {
                this.daemonRpc = new MoneroDaemonRpc(daemonUrl, daemonUser, daemonPass);
            } else {
                this.daemonRpc = new MoneroDaemonRpc(daemonUrl);
            }
        } else {
            this.daemonRpc = null;
        }
    }

    @Override
    public MoneroDaemonInfo getDaemonInfo() {
        return daemonRpc.getInfo();
    }

    @Override
    public boolean hasDaemon() {
        return daemonRpc != null;
    }

    @Override
    public long getWalletHeight() {
        return walletRpc.getHeight();
    }

    @Override
    public BigInteger getBalance(int accountIndex) {
        return walletRpc.getBalance(accountIndex);
    }

    @Override
    public BigInteger getUnlockedBalance(int accountIndex) {
        return walletRpc.getUnlockedBalance(accountIndex);
    }

    @Override
    public MoneroSubaddress createSubaddress(int accountIndex) {
        return walletRpc.createSubaddress(accountIndex);
    }

    @Override
    public String getPrimaryAddress() {
        return walletRpc.getPrimaryAddress();
    }

    @Override
    public MoneroTxWallet createTx(MoneroTxConfig config) {
        return walletRpc.createTx(config);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<MoneroIncomingTransfer> getIncomingTransfers(MoneroTransferQuery query) {
        return walletRpc.getIncomingTransfers(query);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<MoneroOutgoingTransfer> getOutgoingTransfers(MoneroTransferQuery query) {
        return walletRpc.getOutgoingTransfers(query);
    }

    @Override
    public String signMessage(String message) {
        return walletRpc.signMessage(message);
    }

    @Override
    public MoneroMessageSignatureResult verifyMessage(String message, String address, String signature) {
        return walletRpc.verifyMessage(message, address, signature);
    }
}
