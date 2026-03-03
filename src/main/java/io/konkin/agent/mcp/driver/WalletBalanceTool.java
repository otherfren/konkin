package io.konkin.agent.mcp.driver;

import io.konkin.config.KonkinConfig;
import io.konkin.crypto.WalletBalance;
import io.konkin.crypto.WalletException;
import io.konkin.crypto.WalletSupervisor;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.konkin.agent.mcp.driver.WalletToolSupport.*;

public final class WalletBalanceTool {

    private WalletBalanceTool() {}

    public static SyncToolSpecification create(WalletSupervisor supervisor, KonkinConfig config) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("coin", Map.of("type", "string", "description", "Coin identifier: bitcoin"));

        McpSchema.Tool tool = new McpSchema.Tool(
                "wallet_balance",
                null,
                "Get the total and spendable balance of a connected wallet.",
                new McpSchema.JsonSchema("object", properties, List.of("coin"), null, null, null),
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            String coin = argString(request.arguments(), "coin");
            CallToolResult validation = validateCoinEnabled(config, coin);
            if (validation != null) return validation;

            try {
                WalletBalance balance = supervisor.execute(w -> w.balance());
                return jsonResult(Map.of(
                        "coin", balance.coin().name(),
                        "total", balance.total().toPlainString(),
                        "spendable", balance.spendable().toPlainString()
                ));
            } catch (WalletException e) {
                return walletError(e);
            }
        });
    }
}
