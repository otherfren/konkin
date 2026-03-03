package io.konkin.agent.mcp.driver;

import io.konkin.config.KonkinConfig;
import io.konkin.crypto.SignedMessage;
import io.konkin.crypto.WalletException;
import io.konkin.crypto.WalletSupervisor;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.konkin.agent.mcp.driver.WalletToolSupport.*;

public final class SignMessageTool {

    private SignMessageTool() {}

    public static SyncToolSpecification create(WalletSupervisor supervisor, KonkinConfig config) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("coin", Map.of("type", "string", "description", "Coin identifier: bitcoin"));
        properties.put("message", Map.of("type", "string", "description", "The message to sign"));

        McpSchema.Tool tool = new McpSchema.Tool(
                "sign_message",
                null,
                "Sign a message with a wallet address to prove ownership.",
                new McpSchema.JsonSchema("object", properties, List.of("coin", "message"), null, null, null),
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            String coin = argString(request.arguments(), "coin");
            String message = argString(request.arguments(), "message");
            CallToolResult validation = validateCoinEnabled(config, coin);
            if (validation != null) return validation;

            if (message == null || message.isBlank()) {
                return errorResult("invalid_input", "message is required");
            }

            try {
                SignedMessage signed = supervisor.execute(w -> w.signMessage(message.trim()));
                return jsonResult(Map.of(
                        "coin", signed.coin().name(),
                        "address", signed.address(),
                        "message", signed.message(),
                        "signature", signed.signature()
                ));
            } catch (WalletException e) {
                return walletError(e);
            }
        });
    }
}
