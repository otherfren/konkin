package io.konkin.agent.mcp.driver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.konkin.agent.mcp.entity.McpDataContracts.SendCoinActionAcceptedResponse;
import io.konkin.config.CoinConfig;
import io.konkin.config.KonkinConfig;
import io.konkin.db.ApprovalRequestRepository;
import io.konkin.db.HistoryRepository;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.StateTransitionRow;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class SendCoinTool {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private SendCoinTool() {
    }

    public static SyncToolSpecification create(
            String agentName,
            ApprovalRequestRepository requestRepo,
            HistoryRepository historyRepo,
            KonkinConfig runtimeConfig
    ) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("coin", Map.of("type", "string", "description", "Coin identifier: bitcoin, testdummycoin"));
        properties.put("toAddress", Map.of("type", "string", "description", "Destination wallet address"));
        properties.put("amountNative", Map.of("type", "string", "description", "Amount in native coin units"));
        properties.put("feePolicy", Map.of("type", "string", "description", "Fee policy: normal, priority, economy"));
        properties.put("feeCapNative", Map.of("type", "string", "description", "Maximum fee cap in native units"));
        properties.put("memo", Map.of("type", "string", "description", "Optional memo/note for this transaction"));

        McpSchema.Tool tool = new McpSchema.Tool(
                "send_coin",
                null,
                "Submit a cryptocurrency send action for approval. Creates an approval request that must be voted on by auth agents before execution.",
                new McpSchema.JsonSchema("object", properties, List.of("coin", "toAddress", "amountNative"), null, null, null),
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            String coin = normalizeCoin(argString(args, "coin"));
            String toAddress = requireNonBlank(argString(args, "toAddress"), "toAddress is required");
            String amountNative = requireNonBlank(argString(args, "amountNative"), "amountNative is required");
            String feePolicy = optionalTrim(argString(args, "feePolicy"));
            String feeCapNative = optionalTrim(argString(args, "feeCapNative"));
            String memo = optionalTrim(argString(args, "memo"));

            if (!hasAnyEnabledCoin(runtimeConfig)) {
                return errorResult("no_coin_runtime_available",
                        "No coin runtime is enabled on this server. Enable at least one coin before calling send actions.");
            }

            CoinConfig coinConfig = sendActionCoinConfig(runtimeConfig, coin);
            if (coinConfig == null) {
                return errorResult("unsupported_coin",
                        "Coin '" + coin + "' is not supported by this endpoint. Supported coins: bitcoin, testdummycoin.");
            }

            if (!coinConfig.enabled()) {
                String hint = "testdummycoin".equals(coin)
                        ? "Enable [debug].enabled=true and [coins.testdummycoin].enabled=true in config.toml."
                        : "Enable [coins." + coin + "].enabled=true in config.toml.";
                return errorResult("coin_not_enabled",
                        "Coin '" + coin + "' is currently disabled. " + hint);
            }

            Instant now = Instant.now();
            String requestId = "req-" + UUID.randomUUID();
            String nonceUuid = UUID.randomUUID().toString();
            String payloadHash = sha256Hex(String.join("|",
                    coin,
                    toAddress,
                    amountNative,
                    Objects.toString(feePolicy, ""),
                    Objects.toString(feeCapNative, ""),
                    Objects.toString(memo, "")
            ));
            String nonceComposite = coin + "|" + nonceUuid + "|" + payloadHash;
            int minApprovalsRequired = coinConfig.auth().minApprovalsRequired();

            ApprovalRequestRow row = new ApprovalRequestRow(
                    requestId,
                    coin,
                    "wallet_send",
                    null,
                    nonceUuid,
                    payloadHash,
                    nonceComposite,
                    toAddress,
                    amountNative,
                    feePolicy,
                    feeCapNative,
                    memo,
                    now,
                    now.plus(30, ChronoUnit.MINUTES),
                    "QUEUED",
                    "queued_for_approval",
                    "Request accepted and queued for approval",
                    minApprovalsRequired,
                    0,
                    0,
                    "manual",
                    now,
                    now,
                    null
            );

            requestRepo.insertApprovalRequest(row);
            historyRepo.insertStateTransition(new StateTransitionRow(
                    0L,
                    requestId,
                    null,
                    "QUEUED",
                    "driver_agent",
                    agentName,
                    "queued_for_approval",
                    now
            ));

            var accepted = new SendCoinActionAcceptedResponse("accepted", requestId, coin, "send", "QUEUED");
            return new CallToolResult(List.of(new TextContent(toJson(accepted))), false, null, null);
        });
    }

    private static CallToolResult errorResult(String error, String message) {
        String json = toJson(Map.of("error", error, "message", message));
        return new CallToolResult(List.of(new TextContent(json)), true, null, null);
    }

    private static boolean hasAnyEnabledCoin(KonkinConfig config) {
        return config.bitcoin().enabled()
                || config.litecoin().enabled()
                || config.monero().enabled()
                || config.testDummyCoin().enabled();
    }

    private static CoinConfig sendActionCoinConfig(KonkinConfig config, String coin) {
        return switch (coin) {
            case "bitcoin" -> config.bitcoin();
            case "testdummycoin" -> config.testDummyCoin();
            default -> null;
        };
    }

    private static String normalizeCoin(String coin) {
        if (coin == null || coin.isBlank()) {
            throw new IllegalArgumentException("coin is required");
        }
        return coin.trim().toLowerCase();
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String optionalTrim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String argString(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? null : value.toString();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }
}
