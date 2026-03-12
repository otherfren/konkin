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

    public static SyncToolSpecification create(Map<Coin, WalletSupervisor> supervisors, KonkinConfig config) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("coin", Map.of("type", "string", "description", "Coin identifier: bitcoin, litecoin, monero"));

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

            Coin resolved = resolveCoin(coin);
            WalletSupervisor supervisor = lookupSupervisor(supervisors, resolved);
            if (supervisor == null) return errorResult("wallet_offline", "No wallet supervisor for " + coin);

            try {
                WalletBalance balance = supervisor.execute(w -> w.balance());
                return jsonResult(Map.of(
                        "coin", balance.coin().name(),
                        "total", balance.total().toPlainString(),
                        "spendable", balance.spendable().toPlainString()
                ));
            } catch (WalletException e) {
                return walletError(e);
            } catch (Exception e) {
                return unexpectedError("wallet_balance", e);
            }
        });
    }
}