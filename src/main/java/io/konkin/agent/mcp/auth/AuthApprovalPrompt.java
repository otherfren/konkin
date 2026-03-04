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

package io.konkin.agent.mcp.auth;

import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.util.List;

public final class AuthApprovalPrompt {

    private AuthApprovalPrompt() {
    }

    public static SyncPromptSpecification create() {
        McpSchema.Prompt prompt = new McpSchema.Prompt(
                "auth_approval_guide",
                "Auth Agent Approval Guide",
                "Guides you through reviewing and voting on pending approval requests.",
                List.of()
        );

        return new SyncPromptSpecification(prompt, (exchange, request) -> {
            String instructions = """
                    # Auth Agent Approval Guide

                    Follow these steps to review and vote on pending approval requests:

                    ## Step 1: List Pending Approvals
                    Read the resource `konkin://approvals/pending` to get all pending approval requests assigned to you.
                    Subscribe to this resource for real-time notifications when new requests arrive or existing ones change.

                    ## Step 2: Review Request Details
                    For each pending request, read `konkin://approvals/{requestId}` to see full details including:
                    - Coin, amount, and destination address
                    - Current vote counts and existing votes
                    - Request nonce for verification
                    - Expiration time

                    ## Step 3: Make Your Decision
                    Carefully evaluate each request:
                    - Verify the destination address is expected
                    - Verify the amount is reasonable
                    - Check that the request hasn't expired
                    - Consider any security policies

                    ## Step 4: Cast Your Vote
                    Use the `vote_on_approval` tool with:
                    - requestId: the request to vote on
                    - decision: "approve" or "deny"
                    - reason (optional): explanation for your decision

                    ## Important Notes
                    - You can only vote once per request
                    - A single deny vote immediately denies the request
                    - Multiple approvals may be required depending on coin configuration
                    - Expired requests cannot be voted on
                    """;

            return new GetPromptResult(
                    "Step-by-step guide for reviewing and voting on approval requests",
                    List.of(new PromptMessage(McpSchema.Role.USER, new TextContent(instructions)))
            );
        });
    }
}