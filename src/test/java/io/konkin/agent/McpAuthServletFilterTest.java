package io.konkin.agent;

import io.konkin.TestDatabaseManager;
import io.konkin.db.AgentTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpAuthServletFilterTest {

    private static final DataSource dataSource = TestDatabaseManager.dataSource("mcp-auth-filter-test");

    private AgentTokenStore tokenStore;

    @BeforeEach
    void setUp() {
        TestDatabaseManager.truncateAll(dataSource);
        tokenStore = new AgentTokenStore(dataSource);
    }

    @Test
    void validToken_forCorrectAgent_recordsActivity() throws Exception {
        String tokenMerlin = tokenStore.issueToken("agent-merlin");

        McpAuthServletFilter filter = createFilter("agent-merlin");
        HttpServletRequest request = bearerRequest(tokenMerlin, "/sse");
        HttpServletResponse response = mockResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertTrue(tokenStore.lastActivity("agent-merlin").isPresent(),
                "activity should be recorded for the authenticated agent");
    }

    @Test
    void validToken_forWrongAgent_doesNotRecordActivityForEitherAgent() throws Exception {
        String tokenMerlin = tokenStore.issueToken("agent-merlin");
        tokenStore.issueToken("agent-lancelot");

        // send merlin's token to lancelot's endpoint
        McpAuthServletFilter lancelotFilter = createFilter("agent-lancelot");
        HttpServletRequest request = bearerRequest(tokenMerlin, "/sse");
        HttpServletResponse response = mockResponse();
        FilterChain chain = mock(FilterChain.class);

        lancelotFilter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertTrue(tokenStore.lastActivity("agent-merlin").isEmpty(),
                "merlin's activity must not be recorded when his token is used on lancelot's endpoint");
        assertTrue(tokenStore.lastActivity("agent-lancelot").isEmpty(),
                "lancelot's activity must not be recorded for a rejected request");
    }

    @Test
    void multipleAgents_activityIsIsolated() throws Exception {
        String tokenMerlin = tokenStore.issueToken("agent-merlin");
        String tokenLancelot = tokenStore.issueToken("agent-lancelot");
        tokenStore.issueToken("agent-morgana");

        // authenticate merlin on merlin's endpoint
        McpAuthServletFilter merlinFilter = createFilter("agent-merlin");
        merlinFilter.doFilter(
                bearerRequest(tokenMerlin, "/sse"),
                mockResponse(),
                mock(FilterChain.class));

        assertTrue(tokenStore.lastActivity("agent-merlin").isPresent());
        assertTrue(tokenStore.lastActivity("agent-lancelot").isEmpty(),
                "lancelot must have no activity after merlin authenticates");
        assertTrue(tokenStore.lastActivity("agent-morgana").isEmpty(),
                "morgana must have no activity after merlin authenticates");

        // now authenticate lancelot on lancelot's endpoint
        McpAuthServletFilter lancelotFilter = createFilter("agent-lancelot");
        lancelotFilter.doFilter(
                bearerRequest(tokenLancelot, "/sse"),
                mockResponse(),
                mock(FilterChain.class));

        assertTrue(tokenStore.lastActivity("agent-lancelot").isPresent());
        assertTrue(tokenStore.lastActivity("agent-morgana").isEmpty(),
                "morgana must still have no activity");
    }

    @Test
    void noAuthorizationHeader_returns401_noActivityRecorded() throws Exception {
        tokenStore.issueToken("agent-merlin");

        McpAuthServletFilter filter = createFilter("agent-merlin");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/sse");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("Authorization")).thenReturn(null);

        HttpServletResponse response = mockResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(401);
        assertTrue(tokenStore.lastActivity("agent-merlin").isEmpty());
    }

    @Test
    void invalidToken_returns401_noActivityRecorded() throws Exception {
        tokenStore.issueToken("agent-merlin");

        McpAuthServletFilter filter = createFilter("agent-merlin");
        HttpServletRequest request = bearerRequest("bogus-token", "/sse");
        HttpServletResponse response = mockResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(401);
        assertTrue(tokenStore.lastActivity("agent-merlin").isEmpty());
    }

    // ── Token revocation ──────────────────────────────────────────────────────

    @Test
    void revokedToken_isRejected_afterRevocation() throws Exception {
        String token = tokenStore.issueToken("agent-merlin");

        // first request succeeds
        McpAuthServletFilter filter = createFilter("agent-merlin");
        HttpServletRequest request1 = bearerRequest(token, "/mcp");
        HttpServletResponse response1 = mockResponse();
        FilterChain chain1 = mock(FilterChain.class);
        filter.doFilter(request1, response1, chain1);
        verify(chain1).doFilter(request1, response1);

        // revoke
        tokenStore.revokeByAgent("agent-merlin");

        // second request with same token is rejected
        HttpServletRequest request2 = bearerRequest(token, "/mcp");
        HttpServletResponse response2 = mockResponse();
        FilterChain chain2 = mock(FilterChain.class);
        filter.doFilter(request2, response2, chain2);
        verify(chain2, never()).doFilter(request2, response2);
        verify(response2).setStatus(401);
    }

    @Test
    void revokedToken_doesNotAffectOtherAgents() throws Exception {
        String tokenMerlin = tokenStore.issueToken("agent-merlin");
        String tokenLancelot = tokenStore.issueToken("agent-lancelot");

        // revoke merlin
        tokenStore.revokeByAgent("agent-merlin");

        // merlin's token is rejected
        McpAuthServletFilter merlinFilter = createFilter("agent-merlin");
        HttpServletRequest reqMerlin = bearerRequest(tokenMerlin, "/mcp");
        HttpServletResponse resMerlin = mockResponse();
        FilterChain chainMerlin = mock(FilterChain.class);
        merlinFilter.doFilter(reqMerlin, resMerlin, chainMerlin);
        verify(chainMerlin, never()).doFilter(reqMerlin, resMerlin);

        // lancelot's token still works
        McpAuthServletFilter lancelotFilter = createFilter("agent-lancelot");
        HttpServletRequest reqLancelot = bearerRequest(tokenLancelot, "/mcp");
        HttpServletResponse resLancelot = mockResponse();
        FilterChain chainLancelot = mock(FilterChain.class);
        lancelotFilter.doFilter(reqLancelot, resLancelot, chainLancelot);
        verify(chainLancelot).doFilter(reqLancelot, resLancelot);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private McpAuthServletFilter createFilter(String agentName) {
        AgentOAuthHandler oauthHandler = mock(AgentOAuthHandler.class);
        return new McpAuthServletFilter(agentName, tokenStore, oauthHandler);
    }

    private static HttpServletRequest bearerRequest(String token, String path) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        return request;
    }

    private static HttpServletResponse mockResponse() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return response;
    }
}
