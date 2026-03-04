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

import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.util.List;

public final class DriverReadinessPrompt {

    private DriverReadinessPrompt() {
    }

    public static SyncPromptSpecification create() {
        McpSchema.Prompt prompt = new McpSchema.Prompt(
                "driver_readiness_check",
                "Driver Agent Readiness Check",
                "Guides you through verifying server readiness and submitting a cryptocurrency send action.",
                List.of(
                        new PromptArgument("coin", null, "Target coin for the operation (e.g. bitcoin, testdummycoin)", false)
                )
        );

        return new SyncPromptSpecification(prompt, (exchange, request) -> {
            String coin = null;
            if (request.arguments() != null) {
                Object coinArg = request.arguments().get("coin");
                if (coinArg != null) {
                    coin = coinArg.toString().trim();
                    if (coin.isEmpty()) coin = null;
                }
            }

            String instructions = buildInstructions(coin);
            return new GetPromptResult(
                    "Step-by-step guide for driver agent readiness check and action submission",
                    List.of(new PromptMessage(McpSchema.Role.USER, new TextContent(instructions)))
            );
        });
    }

    private static String buildInstructions(String coin) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Driver Agent Readiness Check\n\n");
        sb.append("Follow these steps in order:\n\n");

        sb.append("## Step 1: Check Server Readiness\n");
        sb.append("Read the resource `konkin://runtime/config/requirements` to check overall server readiness.\n");
        sb.append("If the status is NOT_READY, inform the operator about missing requirements and stop.\n\n");

        if (coin != null) {
            sb.append("## Step 2: Check Coin Readiness\n");
            sb.append("Read the resource `konkin://runtime/config/requirements/").append(coin).append("` ");
            sb.append("to verify coin-specific readiness.\n");
            sb.append("If the status is NOT_READY, inform the operator about the missing coin requirements and stop.\n\n");
        } else {
            sb.append("## Step 2: Check Coin Readiness\n");
            sb.append("Read `konkin://runtime/config/requirements/{coin}` for each coin you plan to use ");
            sb.append("(e.g. bitcoin, testdummycoin).\n");
            sb.append("If any coin is NOT_READY, inform the operator and stop.\n\n");
        }

        sb.append("## Step 3: Submit Send Action\n");
        sb.append("Use the `send_coin` tool with the required parameters:\n");
        sb.append("- coin: the coin identifier\n");
        sb.append("- toAddress: destination wallet address\n");
        sb.append("- amountNative: amount in native coin units\n");
        sb.append("- feePolicy (optional): normal, priority, or economy\n");
        sb.append("- feeCapNative (optional): maximum fee cap\n");
        sb.append("- memo (optional): transaction note\n\n");

        sb.append("## Step 4: Monitor Decision\n");
        sb.append("After submitting, read `konkin://decisions/{requestId}` to check the approval status.\n");
        sb.append("Subscribe to the resource for real-time state change notifications.\n");
        sb.append("Wait for a terminal state (COMPLETED, DENIED, FAILED, etc.) before reporting the outcome.\n");

        return sb.toString();
    }
}