package io.konkin.db;

import io.konkin.TestDatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTokenStoreTest {

    private static final DataSource dataSource = TestDatabaseManager.dataSource("agent-token-store-test");
    private AgentTokenStore store;

    @BeforeEach
    void setUp() {
        TestDatabaseManager.truncateAll(dataSource);
        store = new AgentTokenStore(dataSource);
    }

    @Test
    void issueToken_returnsNonBlankToken() {
        String token = store.issueToken("agent-a");
        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    void validateToken_returnsAgentName_forValidToken() {
        String token = store.issueToken("agent-a");
        Optional<String> result = store.validateToken(token);
        assertTrue(result.isPresent());
        assertEquals("agent-a", result.get());
    }

    @Test
    void validateToken_returnsEmpty_forUnknownToken() {
        Optional<String> result = store.validateToken("not-a-real-token");
        assertTrue(result.isEmpty());
    }

    @Test
    void validateToken_returnsEmpty_forNull() {
        assertTrue(store.validateToken(null).isEmpty());
    }

    @Test
    void validateToken_returnsEmpty_forBlank() {
        assertTrue(store.validateToken("").isEmpty());
        assertTrue(store.validateToken("   ").isEmpty());
    }

    @Test
    void hasTokens_returnsFalse_whenNoTokensExist() {
        assertFalse(store.hasTokens("agent-a"));
    }

    @Test
    void hasTokens_returnsTrue_afterIssuingToken() {
        store.issueToken("agent-a");
        assertTrue(store.hasTokens("agent-a"));
    }

    @Test
    void revokeByAgent_removesOnlyThatAgentsTokens() {
        String tokenA = store.issueToken("agent-a");
        String tokenB = store.issueToken("agent-b");

        store.revokeByAgent("agent-a");

        assertFalse(store.hasTokens("agent-a"));
        assertTrue(store.validateToken(tokenA).isEmpty());
        assertTrue(store.hasTokens("agent-b"));
        assertEquals("agent-b", store.validateToken(tokenB).orElseThrow());
    }

    @Test
    void revokeAll_removesAllTokens() {
        store.issueToken("agent-a");
        store.issueToken("agent-b");

        store.revokeAll();

        assertFalse(store.hasTokens("agent-a"));
        assertFalse(store.hasTokens("agent-b"));
    }

    @Test
    void eviction_thirdTokenEvictsOldest() throws InterruptedException {
        String token1 = store.issueToken("agent-a");
        Thread.sleep(5);
        String token2 = store.issueToken("agent-a");
        Thread.sleep(5);
        String token3 = store.issueToken("agent-a");

        // oldest token should be evicted
        assertTrue(store.validateToken(token1).isEmpty());
        // two newest should remain
        assertEquals("agent-a", store.validateToken(token2).orElseThrow());
        assertEquals("agent-a", store.validateToken(token3).orElseThrow());
    }

    @Test
    void multipleAgents_haveIndependentTokens() {
        String tokenA = store.issueToken("agent-a");
        String tokenB = store.issueToken("agent-b");

        assertEquals("agent-a", store.validateToken(tokenA).orElseThrow());
        assertEquals("agent-b", store.validateToken(tokenB).orElseThrow());
    }

    @Test
    void issuedToken_survivesNewStoreInstance() {
        String token = store.issueToken("agent-a");
        AgentTokenStore anotherInstance = new AgentTokenStore(dataSource);
        assertEquals("agent-a", anotherInstance.validateToken(token).orElseThrow());
    }

    // ── Activity scoping ────────────────────────────────────────────────────

    @Test
    void validateToken_doesNotRecordActivity() {
        String token = store.issueToken("agent-a");
        store.validateToken(token);
        assertTrue(store.lastActivity("agent-a").isEmpty(),
                "validateToken must not update lastActivity");
    }

    @Test
    void recordActivity_updatesLastActivityForSpecificAgent() {
        store.issueToken("agent-a");
        store.issueToken("agent-b");

        store.recordActivity("agent-a");

        assertTrue(store.lastActivity("agent-a").isPresent(),
                "recordActivity should set lastActivity for agent-a");
        assertTrue(store.lastActivity("agent-b").isEmpty(),
                "recordActivity for agent-a must not affect agent-b");
    }

    @Test
    void validatingTokenOfAgentA_doesNotRecordActivityForAgentA() {
        String tokenA = store.issueToken("agent-a");
        store.issueToken("agent-b");

        // validate agent-a's token multiple times
        store.validateToken(tokenA);
        store.validateToken(tokenA);

        assertTrue(store.lastActivity("agent-a").isEmpty(),
                "validateToken must never update lastActivity regardless of how many calls");
        assertTrue(store.lastActivity("agent-b").isEmpty());
    }
}
