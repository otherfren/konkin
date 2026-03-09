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

package io.konkin.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creates missing secret files with sensible defaults on first start.
 */
final class SecretFileBootstrapper {

    private static final Logger log = LoggerFactory.getLogger(SecretFileBootstrapper.class);
    private static final String PRIMARY_AGENT_CLIENT_ID = "konkin-primary";

    private SecretFileBootstrapper() {
    }

    private static final String DB_SECRET_FILENAME = "db.secret";
    private static final String DB_SECRET_KEY = "db-password";

    static Set<String> bootstrap(KonkinConfig config) {
        Set<String> freshlyCreated = bootstrapSecretFiles(config);
        warnOnInsecurePermissions(config);
        return freshlyCreated;
    }

    /**
     * [M-3] Ensures a random DB password file exists, generates one on first boot.
     * Returns the password from the file, or null if the config doesn't use default sa/sa credentials.
     */
    static String ensureDbPassword(String configuredPassword, String secretsDir) {
        Path DB_SECRET_FILE = Path.of(secretsDir, DB_SECRET_FILENAME);
        // Only auto-generate if the user left the default insecure password
        if (!"sa".equals(configuredPassword)) {
            return configuredPassword;
        }

        if (Files.exists(DB_SECRET_FILE)) {
            // Read existing generated password
            try {
                java.util.Properties props = new java.util.Properties();
                try (var reader = Files.newBufferedReader(DB_SECRET_FILE, StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
                String stored = props.getProperty(DB_SECRET_KEY);
                if (stored != null && !stored.isBlank()) {
                    return stored;
                }
            } catch (IOException e) {
                log.warn("Failed to read DB secret file {}, falling back to configured password", DB_SECRET_FILE, e);
                return configuredPassword;
            }
        }

        // Generate and persist a new random password
        String generatedPassword = generateHexSecret(24);
        try {
            Path parent = DB_SECRET_FILE.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(DB_SECRET_FILE,
                    "# Auto-generated database password — do not edit" + System.lineSeparator()
                            + DB_SECRET_KEY + "=" + generatedPassword + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            setOwnerOnlyPermissionsIfPossible(DB_SECRET_FILE);
            log.warn("Generated random database password and stored in {}", DB_SECRET_FILE.toAbsolutePath());
            log.warn("The default 'sa' password in config.toml is no longer used for the database connection.");
        } catch (IOException e) {
            log.warn("Failed to write DB secret file {}, falling back to configured password", DB_SECRET_FILE, e);
            return configuredPassword;
        }

        return generatedPassword;
    }

    private static Set<String> bootstrapSecretFiles(KonkinConfig config) {
        // REST API secret is no longer auto-created here; managed via web UI wizard

        Set<String> freshlyCreated = new HashSet<>();

        if (config.primaryAgent() != null && config.primaryAgent().enabled()) {
            if (ensureAgentSecretFileExists(Path.of(config.primaryAgent().secretFile()), PRIMARY_AGENT_CLIENT_ID, "driver")) {
                freshlyCreated.add(PRIMARY_AGENT_CLIENT_ID);
            }
        }

        for (Map.Entry<String, AgentConfig> entry : config.secondaryAgents().entrySet()) {
            AgentConfig agentConfig = entry.getValue();
            if (agentConfig.enabled()) {
                if (ensureAgentSecretFileExists(Path.of(agentConfig.secretFile()), entry.getKey(), "auth")) {
                    freshlyCreated.add(entry.getKey());
                }
            }
        }

        if (config.bitcoin().enabled()) {
            ensureSecretFileExists(
                    Path.of(config.bitcoin().daemonConfigSecretFile()),
                    "coins.bitcoin.secret-files.bitcoin-daemon-config-file",
                    defaultBitcoinDaemonSecretContent()
            );
            ensureSecretFileExists(
                    Path.of(config.bitcoin().walletConfigSecretFile()),
                    "coins.bitcoin.secret-files.bitcoin-wallet-config-file",
                    defaultBitcoinWalletSecretContent()
            );
        }

        if (config.monero().enabled()) {
            ensureSecretFileExists(
                    Path.of(config.monero().daemonConfigSecretFile()),
                    "coins.monero.secret-files.monero-daemon-config-file",
                    defaultMoneroDaemonSecretContent()
            );
            ensureSecretFileExists(
                    Path.of(config.monero().walletConfigSecretFile()),
                    "coins.monero.secret-files.monero-wallet-rpc-config-file",
                    defaultMoneroWalletRpcSecretContent()
            );
        }
        return freshlyCreated;
    }

    /**
     * [SEC-2] Warns at startup when existing secret files have permissions other than 600 (owner-read-write only).
     */
    private static void warnOnInsecurePermissions(KonkinConfig config) {
        Set<PosixFilePermission> insecure = EnumSet.of(
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE
        );

        List<String> paths = new ArrayList<>();

        // Agent secret files
        if (config.primaryAgent() != null && config.primaryAgent().enabled()) {
            paths.add(config.primaryAgent().secretFile());
        }
        for (Map.Entry<String, AgentConfig> entry : config.secondaryAgents().entrySet()) {
            AgentConfig agentConfig = entry.getValue();
            if (agentConfig.enabled()) {
                paths.add(agentConfig.secretFile());
            }
        }

        // Coin daemon/wallet secret files
        if (config.bitcoin().enabled()) {
            paths.add(config.bitcoin().daemonConfigSecretFile());
            paths.add(config.bitcoin().walletConfigSecretFile());
        }
        if (config.monero().enabled()) {
            paths.add(config.monero().daemonConfigSecretFile());
            paths.add(config.monero().walletConfigSecretFile());
        }

        // REST API secret file
        if (config.restApiEnabled()) {
            paths.add(config.restApiSecretFile());
        }

        // Telegram secret file
        if (config.telegramEnabled()) {
            paths.add(config.telegramSecretFile());
        }

        // Landing password file
        if (config.landingPasswordProtectionEnabled()) {
            paths.add(config.landingPasswordFile());
        }

        for (String pathStr : paths) {
            if (pathStr == null || pathStr.isBlank()) {
                continue;
            }
            Path path = Path.of(pathStr);
            if (!Files.exists(path)) {
                continue;
            }
            try {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
                for (PosixFilePermission perm : insecure) {
                    if (perms.contains(perm)) {
                        log.warn("Secret file has insecure permissions ({}): {} — expected owner-only (600)", perms, path.toAbsolutePath());
                        break;
                    }
                }
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem (e.g. Windows), skip silently.
                return;
            } catch (IOException e) {
                log.warn("Failed to read permissions of secret file: {}", path.toAbsolutePath(), e);
            }
        }
    }

    private static void ensureSecretFileExists(Path secretFile, String keyName, String content) {
        if (Files.exists(secretFile)) {
            return;
        }

        try {
            Path parent = secretFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(secretFile, content, StandardCharsets.UTF_8);
            setOwnerOnlyPermissionsIfPossible(secretFile);
            log.warn("Created missing secret file for {} at {}", keyName, secretFile.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create secret file for " + keyName + ": " + secretFile, e);
        }
    }

    /**
     * @return {@code true} if the secret file was freshly created (did not exist before)
     */
    private static boolean ensureAgentSecretFileExists(Path secretFile, String clientId, String agentRole) {
        if (Files.exists(secretFile)) {
            return false;
        }

        try {
            Path parent = secretFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String clientSecret = generateHexSecret(32);
            String content = "client-id=" + clientId + System.lineSeparator()
                    + "client-secret=" + clientSecret + System.lineSeparator();
            Files.writeString(secretFile, content, StandardCharsets.UTF_8);
            setOwnerOnlyPermissionsIfPossible(secretFile);
            log.warn("Created missing {} agent secret file at {}", agentRole, secretFile.toAbsolutePath());
            printAgentSecretBanner(secretFile, clientId, agentRole, clientSecret);
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create agent secret file: " + secretFile, e);
        }
    }

    private static void ensureRestApiSecretFileExists(Path secretFile) {
        if (Files.exists(secretFile)) {
            return;
        }

        try {
            Path parent = secretFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String apiKey = generateApiKey();
            Files.writeString(secretFile, "api-key=" + apiKey + System.lineSeparator(), StandardCharsets.UTF_8);
            setOwnerOnlyPermissionsIfPossible(secretFile);
            log.warn("Created missing rest-api secret file at {}", secretFile.toAbsolutePath());
            printRestApiKeyBanner(secretFile, apiKey);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create rest-api secret file: " + secretFile, e);
        }
    }

    private static void printAgentSecretBanner(Path secretFile, String clientId, String agentRole, String clientSecret) {
        System.out.println();
        System.out.println("============================================================");
        System.out.println("!!! KONKIN AGENT SECRET CREATED !!!");
        System.out.println("   Agent   : " + clientId + " (" + agentRole + ")");
        System.out.println("   File    : " + secretFile.toAbsolutePath());
        System.out.println("   ID      : " + clientId);
        System.out.println("   Secret  : " + clientSecret);
        System.out.println("============================================================");
        System.out.println();
    }

    private static void printRestApiKeyBanner(Path secretFile, String apiKey) {
        System.out.println();
        System.out.println("============================================================");
        System.out.println("!!! KONKIN REST API KEY CREATED !!!");
        System.out.println("============================================================");
        System.out.println("File: " + secretFile.toAbsolutePath());
        System.out.println("API key (shown only once): " + apiKey);
        System.out.println("IMPORTANT: this cleartext key is never written to log files.");
        System.out.println("Rotate key by deleting the referenced file and restarting.");
        System.out.println("============================================================");
        System.out.println();
    }

    private static String defaultBitcoinDaemonSecretContent() {
        return """
                # KONKIN Bitcoin daemon secret config
                # Fill with your node RPC credentials.
                rpcuser=REPLACE_WITH_BITCOIN_RPC_USER
                rpcpassword=REPLACE_WITH_BITCOIN_RPC_PASSWORD
                rpcconnect=127.0.0.1
                rpcport=8332
                """;
    }

    private static String defaultBitcoinWalletSecretContent() {
        return """
                # KONKIN Bitcoin wallet secret config
                # Fill with your wallet details.
                wallet=REPLACE_WITH_BITCOIN_WALLET_NAME
                wallet-passphrase=REPLACE_WITH_BITCOIN_WALLET_PASSPHRASE
                """;
    }

    private static String defaultMoneroDaemonSecretContent() {
        return """
                # KONKIN Monero daemon secret config (monerod)
                # Fill with your monerod RPC bind details.
                rpc-bind-ip=127.0.0.1
                rpc-bind-port=18081
                """;
    }

    private static String defaultMoneroWalletRpcSecretContent() {
        return """
                # KONKIN Monero wallet-rpc secret config (monero-wallet-rpc)
                # Fill with your monero-wallet-rpc credentials.
                rpc-bind-ip=127.0.0.1
                rpc-bind-port=18082
                rpc-login=REPLACE_WITH_WALLET_RPC_USER:REPLACE_WITH_WALLET_RPC_PASSWORD
                """;
    }

    private static String generateApiKey() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static String generateHexSecret(int byteLength) {
        byte[] random = new byte[byteLength];
        new SecureRandom().nextBytes(random);

        StringBuilder hex = new StringBuilder(byteLength * 2);
        for (byte value : random) {
            hex.append(Character.forDigit((value >>> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }

    /**
     * [M-1] Set owner-only (600) permissions on secret files to prevent other users from reading them.
     */
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