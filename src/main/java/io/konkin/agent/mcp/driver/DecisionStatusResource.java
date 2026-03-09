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

import io.konkin.agent.mcp.entity.McpDataContracts.DecisionStatusResponse;
import io.konkin.db.ApprovalRequestRepository;
import io.konkin.db.RequestDependencyLoader;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.ExecutionAttemptDetail;
import io.konkin.db.entity.RequestDependencies;
import io.konkin.db.entity.StateTransitionDetail;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;

import java.util.List;
import java.util.Set;

import static io.konkin.agent.mcp.driver.WalletToolSupport.toJson;

public final class DecisionStatusResource {

    private static final Set<String> TERMINAL_STATES = Set.of(
            "DENIED", "TIMED_OUT", "CANCELLED", "COMPLETED", "FAILED", "REJECTED", "EXPIRED"
    );

    private DecisionStatusResource() {
    }

    public static SyncResourceTemplateSpecification template(
            ApprovalRequestRepository requestRepo,
            RequestDependencyLoader depLoader
    ) {
        return new SyncResourceTemplateSpecification(
                new McpSchema.ResourceTemplate(
                        "konkin://decisions/{requestId}",
                        "decision-status",
                        null,
                        "Decision status for an approval request",
                        "application/json",
                        null
                ),
                (exchange, request) -> {
                    String requestId = extractRequestId(request.uri());
                    DecisionStatusResponse status = loadDecisionStatus(requestRepo, depLoader, requestId);
                    if (status == null) {
                        return new ReadResourceResult(List.of(
                                new TextResourceContents(request.uri(), "application/json",
                                        "{\"error\":\"request_not_found\"}")
                        ));
                    }
                    return new ReadResourceResult(List.of(
                            new TextResourceContents(request.uri(), "application/json", toJson(status))
                    ));
                }
        );
    }

    public static DecisionStatusResponse loadDecisionStatus(
            ApprovalRequestRepository requestRepo,
            RequestDependencyLoader depLoader,
            String requestId
    ) {
        ApprovalRequestRow row = requestRepo.findApprovalRequestById(requestId);
        if (row == null) {
            return null;
        }

        String latestReasonCode = row.stateReasonCode();
        String latestReasonText = row.stateReasonText();
        String txid = null;

        RequestDependencies dependencies = depLoader.loadRequestDependencies(List.of(requestId)).get(requestId);
        if (dependencies != null) {
            for (StateTransitionDetail transition : dependencies.transitions()) {
                if (transition.reasonCode() != null && !transition.reasonCode().isBlank()) {
                    latestReasonCode = transition.reasonCode();
                }
                if (transition.reasonText() != null && !transition.reasonText().isBlank()) {
                    latestReasonText = transition.reasonText();
                }
            }
            for (ExecutionAttemptDetail attempt : dependencies.executionAttempts()) {
                if (attempt.txid() != null && !attempt.txid().isBlank()) {
                    txid = attempt.txid();
                }
            }
        }

        String state = row.state();
        return new DecisionStatusResponse(
                row.id(),
                row.coin(),
                state,
                isTerminalState(state),
                row.minApprovalsRequired(),
                row.approvalsGranted(),
                row.approvalsDenied(),
                latestReasonCode,
                latestReasonText,
                txid
        );
    }

    private static boolean isTerminalState(String state) {
        return state != null && TERMINAL_STATES.contains(state.trim().toUpperCase());
    }

    private static String extractRequestId(String uri) {
        String prefix = "konkin://decisions/";
        if (uri != null && uri.startsWith(prefix) && uri.length() > prefix.length()) {
            return uri.substring(prefix.length());
        }
        return null;
    }

}