package io.konkin.agent;

import io.konkin.db.AgentTokenStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentOAuthHandlerTest {

    @TempDir Path tempDir;

    private Path writeSecret(String clientId, String clientSecret) throws IOException {
        Path f = tempDir.resolve("agent.secret");
        Files.writeString(f, "client-id=" + clientId + "\nclient-secret=" + clientSecret + "\n");
        return f;
    }

    @Test void constructorLoadsCredentials() throws IOException {
        Path f = writeSecret("myid", "mysecret");
        AgentTokenStore store = mock(AgentTokenStore.class);
        var handler = new AgentOAuthHandler("test-agent", f, store);
        assertTrue(handler.validateCredentials("myid", "mysecret"));
    }

    @Test void constructorMissingFileThrows() {
        AgentTokenStore store = mock(AgentTokenStore.class);
        assertThrows(IllegalStateException.class,
                () -> new AgentOAuthHandler("test", tempDir.resolve("missing"), store));
    }

    @Test void constructorEmptyClientIdThrows() throws IOException {
        Path f = writeSecret("", "secret");
        AgentTokenStore store = mock(AgentTokenStore.class);
        assertThrows(IllegalStateException.class,
                () -> new AgentOAuthHandler("test", f, store));
    }

    @Test void constructorEmptyClientSecretThrows() throws IOException {
        Path f = writeSecret("id", "");
        AgentTokenStore store = mock(AgentTokenStore.class);
        assertThrows(IllegalStateException.class,
                () -> new AgentOAuthHandler("test", f, store));
    }

    @Test void validateCredentialsCorrect() throws IOException {
        Path f = writeSecret("id1", "sec1");
        AgentTokenStore store = mock(AgentTokenStore.class);
        var handler = new AgentOAuthHandler("test", f, store);
        assertTrue(handler.validateCredentials("id1", "sec1"));
    }

    @Test void validateCredentialsWrongId() throws IOException {
        Path f = writeSecret("id1", "sec1");
        AgentTokenStore store = mock(AgentTokenStore.class);
        var handler = new AgentOAuthHandler("test", f, store);
        assertFalse(handler.validateCredentials("wrong", "sec1"));
    }

    @Test void validateCredentialsWrongSecret() throws IOException {
        Path f = writeSecret("id1", "sec1");
        AgentTokenStore store = mock(AgentTokenStore.class);
        var handler = new AgentOAuthHandler("test", f, store);
        assertFalse(handler.validateCredentials("id1", "wrong"));
    }

    @Test void validateCredentialsNullId() throws IOException {
        Path f = writeSecret("id1", "sec1");
        AgentTokenStore store = mock(AgentTokenStore.class);
        var handler = new AgentOAuthHandler("test", f, store);
        assertFalse(handler.validateCredentials(null, "sec1"));
    }

    @Test void validateCredentialsNullSecret() throws IOException {
        Path f = writeSecret("id1", "sec1");
        AgentTokenStore store = mock(AgentTokenStore.class);
        var handler = new AgentOAuthHandler("test", f, store);
        assertFalse(handler.validateCredentials("id1", null));
    }

    @Test void validateCredentialsBothNull() throws IOException {
        Path f = writeSecret("id1", "sec1");
        AgentTokenStore store = mock(AgentTokenStore.class);
        var handler = new AgentOAuthHandler("test", f, store);
        assertFalse(handler.validateCredentials(null, null));
    }
}
