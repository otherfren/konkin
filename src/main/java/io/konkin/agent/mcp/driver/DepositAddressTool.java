package io.konkin.agent.mcp.driver;

import io.konkin.config.KonkinConfig;
import io.konkin.crypto.DepositAddress;
import io.konkin.crypto.WalletException;
import io.konkin.crypto.WalletSupervisor;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.konkin.agent.mcp.driver.WalletToolSupport.*;

public final class DepositAddressTool {

    private DepositAddressTool() {}

    public static SyncToolSpecification create(WalletSupervisor supervisor, KonkinConfig config) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("coin", Map.of("type", "string", "description", "Coin identifier: bitcoin"));

        McpSchema.Tool tool = new McpSchema.Tool(
                "deposit_address",
                null,
                "Generate a new deposit address for receiving funds.",
                new McpSchema.JsonSchema("object", properties, List.of("coin"), null, null, null),
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            String coin = argString(request.arguments(), "coin");
            CallToolResult validation = validateCoinEnabled(config, coin);
            if (validation != null) return validation;

            try {
                DepositAddress addr = supervisor.execute(w -> w.depositAddress());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("coin", addr.coin().name());
                result.put("address", addr.address());
                result.put("extras", addr.extras());
                return jsonResult(result);
            } catch (WalletException e) {
                return walletError(e);
            }
        });
    }
}
