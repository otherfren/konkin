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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.konkin.config.KonkinConfig;
import io.konkin.crypto.WalletConnectionException;
import io.konkin.crypto.WalletException;
import io.konkin.crypto.WalletInsufficientFundsException;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.util.List;
import java.util.Map;

final class WalletToolSupport {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private WalletToolSupport() {}

    static CallToolResult errorResult(String error, String message) {
        String json = toJson(Map.of("error", error, "message", message));
        return new CallToolResult(List.of(new TextContent(json)), true, null, null);
    }

    static CallToolResult walletError(WalletException e) {
        if (e instanceof WalletConnectionException) {
            return errorResult("wallet_offline", e.getMessage());
        }
        if (e instanceof WalletInsufficientFundsException ise) {
            return errorResult("insufficient_funds",
                    "Requested " + ise.requested() + " but only " + ise.available() + " available");
        }
        return errorResult("wallet_error", e.getMessage());
    }

    static CallToolResult jsonResult(Object value) {
        return new CallToolResult(List.of(new TextContent(toJson(value))), false, null, null);
    }

    static CallToolResult validateCoinEnabled(KonkinConfig config, String coin) {
        if (coin == null || coin.isBlank()) {
            return errorResult("invalid_input", "coin is required");
        }
        String normalized = coin.trim().toLowerCase();
        if (!"bitcoin".equals(normalized)) {
            return errorResult("unsupported_coin", "Coin '" + normalized + "' is not supported. Supported: bitcoin.");
        }
        if (!config.bitcoin().enabled()) {
            return errorResult("coin_not_enabled", "Bitcoin is currently disabled in config.");
        }
        return null;
    }

    static String argString(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? null : value.toString();
    }

    static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }
}