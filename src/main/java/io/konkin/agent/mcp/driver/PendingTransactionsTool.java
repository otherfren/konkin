/*
 * Copyright 2026 Peter Geschel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.konkin.agent.mcp.driver;

import io.konkin.config.KonkinConfig;
import io.konkin.crypto.Coin;
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

    public static SyncToolSpecification create(Map<Coin, WalletSupervisor> supervisors, KonkinConfig config) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("coin", Map.of("type", "string", "description", "Coin identifier: bitcoin, litecoin, monero"));
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

            Coin resolved = resolveCoin(coin);
            WalletSupervisor supervisor = lookupSupervisor(supervisors, resolved);
            if (supervisor == null) return errorResult("wallet_offline", "No wallet supervisor for " + coin);

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
                        "coin", coin.trim().toLowerCase(),
                        "direction", dir,
                        "transactions", txList,
                        "count", txList.size()
                ));
            } catch (WalletException e) {
                return walletError(e);
            } catch (Exception e) {
                return unexpectedError("pending_transactions", e);
            }
        });
    }
}