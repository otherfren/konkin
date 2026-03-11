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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * Writes coin secret files from wizard form inputs.
 * Counterpart to {@link WalletSecretLoader} — files written here are readable by the loader.
 */
public final class WalletSecretWriter {

    private static final Logger log = LoggerFactory.getLogger(WalletSecretWriter.class);

    private WalletSecretWriter() {}

    /**
     * Writes bitcoin-daemon.conf + bitcoin-wallet.conf. Returns written paths.
     */
    public static WrittenSecrets writeBitcoinSecrets(Path secretsDir, String rpcHost, String rpcPort,
            String rpcUser, String rpcPassword, String walletName) {
        String daemonContent = "rpcuser=" + rpcUser + "\n"
                + "rpcpassword=" + rpcPassword + "\n"
                + "rpcconnect=" + rpcHost + "\n"
                + "rpcport=" + rpcPort + "\n";

        StringBuilder walletContent = new StringBuilder();
        if (walletName != null && !walletName.isBlank()) {
            walletContent.append("wallet=").append(walletName.trim()).append("\n");
        }

        Path daemonPath = writeSecretFile(secretsDir, "bitcoin-daemon.conf", daemonContent);
        Path walletPath = writeSecretFile(secretsDir, "bitcoin-wallet.conf", walletContent.toString());

        log.info("Wrote Bitcoin secret files — daemon={}, wallet={}", daemonPath, walletPath);
        return new WrittenSecrets(daemonPath, walletPath);
    }

    /**
     * Writes litecoin-daemon.conf + litecoin-wallet.conf.
     */
    public static WrittenSecrets writeLitecoinSecrets(Path secretsDir, String rpcHost, String rpcPort,
            String rpcUser, String rpcPassword, String walletName) {
        String daemonContent = "rpcuser=" + rpcUser + "\n"
                + "rpcpassword=" + rpcPassword + "\n"
                + "rpcconnect=" + rpcHost + "\n"
                + "rpcport=" + rpcPort + "\n";

        StringBuilder walletContent = new StringBuilder();
        if (walletName != null && !walletName.isBlank()) {
            walletContent.append("wallet=").append(walletName.trim()).append("\n");
        }

        Path daemonPath = writeSecretFile(secretsDir, "litecoin-daemon.conf", daemonContent);
        Path walletPath = writeSecretFile(secretsDir, "litecoin-wallet.conf", walletContent.toString());

        log.info("Wrote Litecoin secret files — daemon={}, wallet={}", daemonPath, walletPath);
        return new WrittenSecrets(daemonPath, walletPath);
    }

    /**
     * Writes monero-daemon.conf + monero-wallet-rpc.conf.
     */
    public static WrittenSecrets writeMoneroSecrets(Path secretsDir, String daemonHost, String daemonPort,
            String daemonUser, String daemonPassword, String walletRpcHost, String walletRpcPort,
            String walletRpcUser, String walletRpcPassword) {
        StringBuilder daemonContent = new StringBuilder();
        daemonContent.append("rpc-bind-ip=").append(daemonHost).append("\n");
        daemonContent.append("rpc-bind-port=").append(daemonPort).append("\n");
        if (daemonUser != null && !daemonUser.isBlank()) {
            daemonContent.append("rpc-login=").append(daemonUser.trim())
                    .append(":").append(daemonPassword).append("\n");
        }

        String walletRpcContent = "rpc-bind-ip=" + walletRpcHost + "\n"
                + "rpc-bind-port=" + walletRpcPort + "\n"
                + "rpc-login=" + walletRpcUser + ":" + walletRpcPassword + "\n";

        Path daemonPath = writeSecretFile(secretsDir, "monero-daemon.conf", daemonContent.toString());
        Path walletRpcPath = writeSecretFile(secretsDir, "monero-wallet-rpc.conf", walletRpcContent);

        log.info("Wrote Monero secret files — daemon={}, walletRpc={}", daemonPath, walletRpcPath);
        return new WrittenSecrets(daemonPath, walletRpcPath);
    }

    /**
     * Paths of the two secret files written by a write operation.
     */
    public record WrittenSecrets(Path daemonConfigPath, Path walletConfigPath) {}

    private static Path writeSecretFile(Path directory, String filename, String content) {
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(filename);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            setOwnerOnlyPermissionsIfPossible(file);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write secret file: " + directory.resolve(filename), e);
        }
    }

    private static void setOwnerOnlyPermissionsIfPossible(Path file) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            );
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (e.g. Windows), ignore.
        } catch (IOException e) {
            log.warn("Failed to set owner-only permissions on secret file: {}", file, e);
        }
    }
}
