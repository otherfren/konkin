package io.konkin.agent.mcp.driver;

import io.konkin.config.KonkinConfig;
import io.konkin.crypto.Transaction;
import io.konkin.crypto.WalletException;
import io.konkin.crypto.WalletSupervisor;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.konkin.agent.mcp.driver.WalletToolSupport.*;

public final class PendingTransactionsTool {

    private PendingTransactionsTool() {}

    public static SyncToolSpecification create(WalletSupervisor supervisor, KonkinConfig config) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("coin", Map.of("type", "string", "description", "Coin identifier: bitcoin"));
        properties.put("direction", Map.of("type", "string",
                "description", "Filter by direction: incoming, outgoing, or both (default: both)"));

        McpSchema.Tool tool = new McpSchema.Tool(
                "pending_transactions",
                null,
                "List pending (unconfirmed) incoming and/or outgoing transactions.",
                new McpSchema.JsonSchema("object", properties, List.of("coin"), null, null, null),
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            String coin = argString(request.arguments(), "coin");
            String direction = argString(request.arguments(), "direction");
            CallToolResult validation = validateCoinEnabled(config, coin);
            if (validation != null) return validation;

            String dir = direction == null ? "both" : direction.trim().toLowerCase();

            try {
                List<Transaction> transactions = new ArrayList<>();

                if ("incoming".equals(dir) || "both".equals(dir)) {
                    transactions.addAll(supervisor.execute(w -> w.pendingIncoming()));
                }
                if ("outgoing".equals(dir) || "both".equals(dir)) {
                    transactions.addAll(supervisor.execute(w -> w.pendingOutgoing()));
                }

                List<Map<String, Object>> txList = new ArrayList<>();
                for (Transaction tx : transactions) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("txId", tx.txId());
                    entry.put("direction", tx.direction().name());
                    entry.put("address", tx.address());
                    entry.put("amount", tx.amount().toPlainString());
                    entry.put("fee", tx.fee().toPlainString());
                    entry.put("txKey", tx.txKey());
                    entry.put("confirmations", tx.confirmations());
                    entry.put("confirmed", tx.confirmed());
                    entry.put("timestamp", tx.timestamp().toString());
                    entry.put("extras", tx.extras());
                    txList.add(entry);
                }

                return jsonResult(Map.of(
                        "coin", "bitcoin",
                        "direction", dir,
                        "transactions", txList,
                        "count", txList.size()
                ));
            } catch (WalletException e) {
                return walletError(e);
            }
        });
    }
}
