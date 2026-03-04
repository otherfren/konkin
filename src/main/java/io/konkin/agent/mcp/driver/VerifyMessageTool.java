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
import io.konkin.crypto.WalletException;
import io.konkin.crypto.WalletSupervisor;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.konkin.agent.mcp.driver.WalletToolSupport.*;

public final class VerifyMessageTool {

    private VerifyMessageTool() {}

    public static SyncToolSpecification create(WalletSupervisor supervisor, KonkinConfig config) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("coin", Map.of("type", "string", "description", "Coin identifier: bitcoin"));
        properties.put("message", Map.of("type", "string", "description", "The original message that was signed"));
        properties.put("address", Map.of("type", "string", "description", "The address that allegedly signed the message"));
        properties.put("signature", Map.of("type", "string", "description", "The signature to verify"));

        McpSchema.Tool tool = new McpSchema.Tool(
                "verify_message",
                null,
                "Verify a signed message against an address and signature.",
                new McpSchema.JsonSchema("object", properties,
                        List.of("coin", "message", "address", "signature"), null, null, null),
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            String coin = argString(args, "coin");
            String message = argString(args, "message");
            String address = argString(args, "address");
            String signature = argString(args, "signature");

            CallToolResult validation = validateCoinEnabled(config, coin);
            if (validation != null) return validation;

            if (message == null || message.isBlank()) return errorResult("invalid_input", "message is required");
            if (address == null || address.isBlank()) return errorResult("invalid_input", "address is required");
            if (signature == null || signature.isBlank()) return errorResult("invalid_input", "signature is required");

            try {
                boolean valid = supervisor.execute(w ->
                        w.verifyMessage(message.trim(), address.trim(), signature.trim()));
                return jsonResult(Map.of(
                        "coin", "bitcoin",
                        "address", address.trim(),
                        "valid", valid
                ));
            } catch (WalletException e) {
                return walletError(e);
            }
        });
    }
}