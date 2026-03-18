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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestStatusToolTest {

    private ApprovalRequestRepository requestRepo;
    private SyncToolSpecification toolSpec;

    @BeforeEach
    void setUp() {
        requestRepo = mock(ApprovalRequestRepository.class);
        toolSpec = RequestStatusTool.create(requestRepo);
    }

    private CallToolResult invoke(Map<String, Object> args) {
        return toolSpec.callHandler().apply(null, new CallToolRequest("request_status", args, null));
    }

    @Test
    void toolName_isRequestStatus() {
        assertEquals("request_status", toolSpec.tool().name());
    }

    @Test
    void returnsRequestDetails() {
        Instant now = Instant.now();
        ApprovalRequestRow row = new ApprovalRequestRow(
                "req-123", "bitcoin", "wallet_send", "session-1",
                "nonce-1", "hash-1", "composite-1",
                "bc1qtest", "0.5", "normal", null, null, "test reason",
                now, now.plusSeconds(3600), "PENDING", "pending", "Awaiting approval",
                2, 1, 0, "manual",
                now, now, null);

        when(requestRepo.findApprovalRequestById("req-123")).thenReturn(row);

        CallToolResult result = invoke(Map.of("requestId", "req-123"));

        assertFalse(result.isError());
        String json = ((TextContent) result.content().getFirst()).text();
        assertTrue(json.contains("\"id\":\"req-123\""));
        assertTrue(json.contains("\"coin\":\"bitcoin\""));
        assertTrue(json.contains("\"state\":\"PENDING\""));
        assertTrue(json.contains("\"toAddress\":\"bc1qtest\""));
        assertTrue(json.contains("\"amountNative\":\"0.5\""));
        assertTrue(json.contains("\"approvalsGranted\":1"));
        assertTrue(json.contains("\"approvalsDenied\":0"));
        assertTrue(json.contains("\"minApprovalsRequired\":2"));
        assertTrue(json.contains("\"reason\":\"test reason\""));
    }

    @Test
    void requestNotFound_returnsError() {
        when(requestRepo.findApprovalRequestById("req-unknown")).thenReturn(null);

        CallToolResult result = invoke(Map.of("requestId", "req-unknown"));

        assertTrue(result.isError());
        String json = ((TextContent) result.content().getFirst()).text();
        assertTrue(json.contains("not_found"));
    }

    @Test
    void missingRequestId_returnsError() {
        CallToolResult result = invoke(Map.of());

        assertTrue(result.isError());
        String json = ((TextContent) result.content().getFirst()).text();
        assertTrue(json.contains("validation_error"));
    }

    @Test
    void blankRequestId_returnsError() {
        CallToolResult result = invoke(Map.of("requestId", "  "));

        assertTrue(result.isError());
        String json = ((TextContent) result.content().getFirst()).text();
        assertTrue(json.contains("validation_error"));
    }

    @Test
    void includesStateReasonFields() {
        Instant now = Instant.now();
        ApprovalRequestRow row = new ApprovalRequestRow(
                "req-fail", "monero", "wallet_send", null,
                "nonce-1", "hash-1", "composite-1",
                "addr-xmr", "10.0", null, null, null, "test",
                now, now.plusSeconds(3600), "FAILED", "insufficient_total_balance",
                "Insufficient total balance: need 10 but only 0 available",
                2, 2, 0, "manual",
                now, now, now);

        when(requestRepo.findApprovalRequestById("req-fail")).thenReturn(row);

        CallToolResult result = invoke(Map.of("requestId", "req-fail"));

        assertFalse(result.isError());
        String json = ((TextContent) result.content().getFirst()).text();
        assertTrue(json.contains("\"state\":\"FAILED\""));
        assertTrue(json.contains("\"stateReasonCode\":\"insufficient_total_balance\""));
        assertTrue(json.contains("Insufficient total balance"));
    }
}
