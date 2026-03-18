package io.konkin.agent.mcp.driver;

import io.konkin.db.ApprovalRequestRepository;
import io.konkin.db.entity.ApprovalRequestRow;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PendingOutgoingToolTest {

    private ApprovalRequestRepository requestRepo;
    private SyncToolSpecification toolSpec;

    @BeforeEach
    void setUp() {
        requestRepo = mock(ApprovalRequestRepository.class);
        toolSpec = PendingOutgoingTool.create(requestRepo);
    }

    private ApprovalRequestRow queuedRow(String id, String coin, String toAddress, String amount) {
        Instant now = Instant.now();
        return new ApprovalRequestRow(
                id, coin, "send_coin", "session-1",
                "nonce-1", "hash-1", "composite-1",
                toAddress, amount, "normal", null, null, "test reason",
                now, now.plusSeconds(3600), "QUEUED_FOR_EXECUTION", "queued_for_execution", "Waiting for spendable balance",
                1, 1, 0, "require_approval",
                now, now, null);
    }

    private CallToolResult invoke(Map<String, Object> args) {
        return toolSpec.callHandler().apply(null, new CallToolRequest("pending_outgoing", args, null));
    }

    @Test
    void toolName_isPendingOutgoing() {
        assertEquals("pending_outgoing", toolSpec.tool().name());
    }

    @Test
    void returnsQueuedRequests() {
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of(
                queuedRow("req-1", "bitcoin", "addr-btc", "0.5"),
                queuedRow("req-2", "monero", "addr-xmr", "10.0")
        ));

        CallToolResult result = invoke(Map.of());

        assertFalse(result.isError());
        String json = ((TextContent) result.content().getFirst()).text();
        assertTrue(json.contains("\"id\":\"req-1\""));
        assertTrue(json.contains("\"id\":\"req-2\""));
        assertTrue(json.contains("\"count\":2"));
    }

    @Test
    void filtersByCoin() {
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of(
                queuedRow("req-1", "bitcoin", "addr-btc", "0.5"),
                queuedRow("req-2", "monero", "addr-xmr", "10.0"),
                queuedRow("req-3", "bitcoin", "addr-btc2", "1.0")
        ));

        CallToolResult result = invoke(Map.of("coin", "bitcoin"));

        assertFalse(result.isError());
        String json = ((TextContent) result.content().getFirst()).text();
        assertTrue(json.contains("\"id\":\"req-1\""));
        assertTrue(json.contains("\"id\":\"req-3\""));
        assertFalse(json.contains("\"id\":\"req-2\""));
        assertTrue(json.contains("\"count\":2"));
    }

    @Test
    void returnsEmptyWhenNoQueuedRequests() {
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of());

        CallToolResult result = invoke(Map.of());

        assertFalse(result.isError());
        String json = ((TextContent) result.content().getFirst()).text();
        assertTrue(json.contains("\"count\":0"));
        assertTrue(json.contains("\"pendingOutgoing\":[]"));
    }

    @Test
    void includesRequestFields() {
        when(requestRepo.findQueuedForExecution()).thenReturn(List.of(
                queuedRow("req-1", "bitcoin", "bc1qtest", "0.123")
        ));

        CallToolResult result = invoke(Map.of());

        assertFalse(result.isError());
        String json = ((TextContent) result.content().getFirst()).text();
        assertTrue(json.contains("\"id\":\"req-1\""));
        assertTrue(json.contains("\"coin\":\"bitcoin\""));
        assertTrue(json.contains("\"toAddress\":\"bc1qtest\""));
        assertTrue(json.contains("\"amountNative\":\"0.123\""));
        assertTrue(json.contains("\"toolName\":\"send_coin\""));
        assertTrue(json.contains("\"reason\":\"test reason\""));
        assertTrue(json.contains("\"state\":\"QUEUED_FOR_EXECUTION\""));
        assertTrue(json.contains("\"stateReasonText\":\"Waiting for spendable balance\""));
    }
}
