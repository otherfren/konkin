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

    public static SyncToolSpecification create(Map<Coin, WalletSupervisor> supervisors, KonkinConfig config) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("coin", Map.of("type", "string", "description", "Coin identifier: bitcoin, monero"));
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

            Coin resolved = resolveCoin(coin);
            WalletSupervisor supervisor = lookupSupervisor(supervisors, resolved);
            if (supervisor == null) return errorResult("wallet_offline", "No wallet supervisor for " + coin);

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
            } catch (Exception e) {
                return unexpectedError("sign_message", e);
            }
        });
    }
}