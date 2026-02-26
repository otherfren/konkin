package io.konkin.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;

/**
 * Handles auth_queue password-file lifecycle:
 * - create hashed/salted password file if missing
 * - load/verify stored password hash
 */
public class PasswordFileManager {

    private static final Logger log = LoggerFactory.getLogger(PasswordFileManager.class);

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH = 16;
    private static final int GENERATED_PASSWORD_LENGTH = 28;

    private static final char[] PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789-_".toCharArray();

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Path passwordFile;
    private final Credentials credentials;

    private PasswordFileManager(Path passwordFile, Credentials credentials) {
        this.passwordFile = passwordFile;
        this.credentials = credentials;
    }

    public static PasswordFileManager bootstrap(Path passwordFile) {
        ensureFileExists(passwordFile);
        Credentials credentials = loadCredentials(passwordFile);
        log.info("auth_queue password hash file is ready at {}", passwordFile.toAbsolutePath());
        return new PasswordFileManager(passwordFile, credentials);
    }

    public boolean verifyPassword(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        byte[] candidateHash = hash(candidate.toCharArray(), credentials.salt(), credentials.iterations(), KEY_LENGTH_BITS);
        return MessageDigest.isEqual(credentials.hash(), candidateHash);
    }

    public Path passwordFile() {
        return passwordFile;
    }

    private static void ensureFileExists(Path passwordFile) {
        if (Files.exists(passwordFile)) {
            return;
        }

        try {
            Path parent = passwordFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            String cleartextPassword = generatePassword(GENERATED_PASSWORD_LENGTH);
            byte[] salt = randomBytes(SALT_LENGTH);
            byte[] hash = hash(cleartextPassword.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS);

            writePasswordFile(passwordFile, salt, hash);
            setOwnerOnlyPermissionsIfPossible(passwordFile);

            // stdout only by requirement (never log cleartext to file logger)
            printApiKeyBanner(passwordFile, cleartextPassword);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create auth_queue password file: " + passwordFile, e);
        }
    }

    private static void printApiKeyBanner(Path passwordFile, String cleartextPassword) {
        System.out.println();
        System.out.println("============================================================");
        System.out.println("!!! KONKIN AUTH_QUEUE API KEY CREATED / RECREATED !!!");
        System.out.println("============================================================");
        System.out.println("File: " + passwordFile.toAbsolutePath());
        System.out.println("API Key (shown only once): " + cleartextPassword);
        System.out.println("IMPORTANT: this cleartext key is never written to log files.");
        System.out.println("Rotate key by deleting the referenced file and restarting.");
        System.out.println("============================================================");
        System.out.println();
    }

    private static void writePasswordFile(Path passwordFile, byte[] salt, byte[] hash) throws IOException {
        Properties props = new Properties();
        props.setProperty("algorithm", ALGORITHM);
        props.setProperty("iterations", Integer.toString(ITERATIONS));
        props.setProperty("salt", Base64.getEncoder().encodeToString(salt));
        props.setProperty("hash", Base64.getEncoder().encodeToString(hash));

        try (Writer writer = Files.newBufferedWriter(
                passwordFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            writer.write("# KONKIN auth_queue password hash file\n");
            writer.write("# rotate keys by deleting referenced file\n");
            props.store(writer, null);
        }
    }

    private static Credentials loadCredentials(Path passwordFile) {
        try (Reader reader = Files.newBufferedReader(passwordFile)) {
            Properties props = new Properties();
            props.load(reader);

            String algorithm = mustGet(props, "algorithm", passwordFile);
            int iterations = Integer.parseInt(mustGet(props, "iterations", passwordFile));
            byte[] salt = Base64.getDecoder().decode(mustGet(props, "salt", passwordFile));
            byte[] hash = Base64.getDecoder().decode(mustGet(props, "hash", passwordFile));

            if (iterations <= 0) {
                throw new IllegalStateException("Invalid iterations in password file: " + passwordFile);
            }
            if (salt.length == 0 || hash.length == 0) {
                throw new IllegalStateException("Invalid salt/hash in password file: " + passwordFile);
            }
            if (!ALGORITHM.equals(algorithm)) {
                throw new IllegalStateException("Unsupported algorithm in password file: " + algorithm);
            }

            return new Credentials(algorithm, iterations, salt, hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load auth_queue password file: " + passwordFile, e);
        }
    }

    private static String mustGet(Properties props, String key, Path file) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing '" + key + "' in password file: " + file);
        }
        return value.trim();
    }

    private static byte[] hash(char[] password, byte[] salt, int iterations, int keyLengthBits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLengthBits);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash password", e);
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String generatePassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(PASSWORD_ALPHABET[SECURE_RANDOM.nextInt(PASSWORD_ALPHABET.length)]);
        }
        return sb.toString();
    }

    private static void setOwnerOnlyPermissionsIfPossible(Path file) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            );
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem, ignore.
        } catch (IOException e) {
            log.warn("Failed to set owner-only permissions on password file: {}", file, e);
        }
    }

    private record Credentials(String algorithm, int iterations, byte[] salt, byte[] hash) {
    }
}
