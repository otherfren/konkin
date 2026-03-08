package io.konkin.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PasswordFileManagerTest {

    @TempDir Path tempDir;

    @Test void bootstrapCreatesFileAndVerifies() {
        Path pwFile = tempDir.resolve("sub/test.password");
        // First bootstrap creates the file
        PasswordFileManager manager = PasswordFileManager.bootstrap(pwFile);
        assertTrue(PasswordFileManager.exists(pwFile));
        assertNotNull(manager.passwordFile());
    }

    @Test void bootstrapLoadExistingFile() {
        Path pwFile = tempDir.resolve("test.password");
        PasswordFileManager.bootstrap(pwFile);
        // Second bootstrap loads existing file
        PasswordFileManager manager2 = PasswordFileManager.bootstrap(pwFile);
        assertNotNull(manager2);
    }

    @Test void createNewAndVerify() {
        Path pwFile = tempDir.resolve("new.password");
        PasswordFileManager.CreateResult result = PasswordFileManager.createNew(pwFile);
        assertNotNull(result.cleartextPassword());
        assertFalse(result.cleartextPassword().isEmpty());
        assertTrue(result.manager().verifyPassword(result.cleartextPassword()));
    }

    @Test void verifyWrongPassword() {
        Path pwFile = tempDir.resolve("test.password");
        PasswordFileManager.CreateResult result = PasswordFileManager.createNew(pwFile);
        assertFalse(result.manager().verifyPassword("wrong-password"));
    }

    @Test void verifyNullPassword() {
        Path pwFile = tempDir.resolve("test.password");
        PasswordFileManager.CreateResult result = PasswordFileManager.createNew(pwFile);
        assertFalse(result.manager().verifyPassword(null));
    }

    @Test void verifyEmptyPassword() {
        Path pwFile = tempDir.resolve("test.password");
        PasswordFileManager.CreateResult result = PasswordFileManager.createNew(pwFile);
        assertFalse(result.manager().verifyPassword(""));
    }

    @Test void existsFalseForMissing() {
        assertFalse(PasswordFileManager.exists(tempDir.resolve("nonexistent")));
    }

    @Test void createNewOverwritesExisting() {
        Path pwFile = tempDir.resolve("test.password");
        PasswordFileManager.CreateResult first = PasswordFileManager.createNew(pwFile);
        PasswordFileManager.CreateResult second = PasswordFileManager.createNew(pwFile);
        // Old password should not work with new file
        assertFalse(second.manager().verifyPassword(first.cleartextPassword()));
        assertTrue(second.manager().verifyPassword(second.cleartextPassword()));
    }
}
