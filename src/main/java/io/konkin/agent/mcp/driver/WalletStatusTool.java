package io.konkin.agent.mcp.driver;

import io.konkin.config.KonkinConfig;
import io.konkin.crypto.WalletException;
import io.konkin.crypto.WalletSupervisor;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.konkin.agent.mcp.driver.WalletToolSupport.*;

public final class WalletStatusTool {

    private WalletStatusTool() {}

    public static SyncToolSpecification create(WalletSupervisor supervisor, KonkinConfig config) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("coin", Map.of("type", "string", "description", "Coin identifier: bitcoin"));

        McpSchema.Tool tool = new McpSchema.Tool(
                "wallet_status",
                null,
                "Check the synchronization status of a connected wallet.",
                new McpSchema.JsonSchema("object", properties, List.of("coin"), null, null, null),
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            String coin = argString(request.arguments(), "coin");
            CallToolResult validation = validateCoinEnabled(config, coin);
            if (validation != null) return validation;

            try {
                var status = supervisor.execute(w -> w.status());
                return jsonResult(Map.of("coin", "bitcoin", "status", status.name()));
            } catch (WalletException e) {
                return walletError(e);
            }
        });
    }
}
