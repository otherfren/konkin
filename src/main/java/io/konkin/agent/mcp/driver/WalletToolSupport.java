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
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.konkin.config.KonkinConfig;
import io.konkin.crypto.Coin;
import io.konkin.crypto.WalletConnectionException;
import io.konkin.crypto.WalletException;
import io.konkin.crypto.WalletInsufficientFundsException;
import io.konkin.crypto.WalletSupervisor;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

public final class WalletToolSupport {

    private static final Logger log = LoggerFactory.getLogger(WalletToolSupport.class);

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private WalletToolSupport() {}

    public static CallToolResult errorResult(String error, String message) {
        String json = toJson(Map.of("error", error, "message", message));
        return new CallToolResult(List.of(new TextContent(json)), true, null, null);
    }

    public static CallToolResult walletError(WalletException e) {
        if (e instanceof WalletConnectionException) {
            return errorResult("wallet_offline", e.getMessage());
        }
        if (e instanceof WalletInsufficientFundsException ise) {
            return errorResult("insufficient_funds",
                    "Requested " + ise.requested() + " but only " + ise.available() + " available");
        }
        return errorResult("wallet_error", e.getMessage());
    }

    public static CallToolResult unexpectedError(String toolName, Exception e) {
        log.error("MCP tool {} unexpected error: {}", toolName, e.getMessage(), e);
        return errorResult("internal_error", "Unexpected error in " + toolName + ": " + e.getMessage());
    }

    public static CallToolResult jsonResult(Object value) {
        return new CallToolResult(List.of(new TextContent(toJson(value))), false, null, null);
    }

    public static CallToolResult validateCoinEnabled(KonkinConfig config, String coin) {
        if (coin == null || coin.isBlank()) {
            return errorResult("invalid_input", "coin is required");
        }
        String normalized = coin.trim().toLowerCase();
        return switch (normalized) {
            case "bitcoin" -> config.bitcoin().enabled() ? null
                    : errorResult("coin_not_enabled", "Bitcoin is currently disabled in config.");
            case "monero" -> config.monero().enabled() ? null
                    : errorResult("coin_not_enabled", "Monero is currently disabled in config.");
            default -> errorResult("unsupported_coin",
                    "Coin '" + normalized + "' is not supported. Supported: bitcoin, monero.");
        };
    }

    public static Coin resolveCoin(String coin) {
        return switch (coin.trim().toLowerCase()) {
            case "bitcoin" -> Coin.BTC;
            case "monero" -> Coin.XMR;
            default -> null;
        };
    }

    public static WalletSupervisor lookupSupervisor(Map<Coin, WalletSupervisor> supervisors, Coin coin) {
        return supervisors.get(coin);
    }

    public static String argString(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? null : value.toString();
    }

    public static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    public static String optionalTrim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static BigDecimal validateAmount(String amountNative) {
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(amountNative);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "amountNative '" + amountNative + "' is not a valid number. Provide a decimal value like '0.001'.");
        }
        if (parsed.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "amountNative must be greater than zero, got: " + parsed.toPlainString());
        }
        return parsed;
    }

    public static void validateFeeCap(String feeCapNative) {
        try {
            BigDecimal parsed = new BigDecimal(feeCapNative);
            if (parsed.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(
                        "feeCapNative must be greater than zero, got: " + parsed.toPlainString());
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "feeCapNative '" + feeCapNative + "' is not a valid number. Provide a decimal value like '0.0001'.");
        }
    }

    public static String normalizeCoin(String coin) {
        if (coin == null || coin.isBlank()) {
            throw new IllegalArgumentException("coin is required");
        }
        return coin.trim().toLowerCase();
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
    }

    public static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }
}
