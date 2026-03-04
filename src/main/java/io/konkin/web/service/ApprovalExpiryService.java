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

package io.konkin.web.service;

import io.konkin.db.ApprovalRequestRepository;
import io.konkin.db.HistoryRepository;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.StateTransitionRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background service that periodically marks expired approval requests as EXPIRED.
 * Expired requests (expires_at &lt; now, state still QUEUED/PENDING) are transitioned
 * to the EXPIRED terminal state with an audit trail in approval_state_transitions.
 */
public class ApprovalExpiryService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalExpiryService.class);

    private final ApprovalRequestRepository requestRepo;
    private final HistoryRepository historyRepo;
    private ScheduledExecutorService scheduler;

    public ApprovalExpiryService(ApprovalRequestRepository requestRepo, HistoryRepository historyRepo) {
        this.requestRepo = requestRepo;
        this.historyRepo = historyRepo;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "approval-expiry-sweeper");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::sweep, 5, 5, TimeUnit.SECONDS);
        log.info("Approval expiry sweeper started (interval=5s)");
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void sweep() {
        try {
            List<ApprovalRequestRow> expired = requestRepo.findExpiredPendingRequests();
            if (expired.isEmpty()) {
                return;
            }

            Instant now = Instant.now();
            for (ApprovalRequestRow row : expired) {
                try {
                    String previousState = row.state();

                    ApprovalRequestRow updated = new ApprovalRequestRow(
                            row.id(),
                            row.coin(),
                            row.toolName(),
                            row.requestSessionId(),
                            row.nonceUuid(),
                            row.payloadHashSha256(),
                            row.nonceComposite(),
                            row.toAddress(),
                            row.amountNative(),
                            row.feePolicy(),
                            row.feeCapNative(),
                            row.memo(),
                            row.requestedAt(),
                            row.expiresAt(),
                            "EXPIRED",
                            "request_expired",
                            "Request expired without sufficient approvals",
                            row.minApprovalsRequired(),
                            row.approvalsGranted(),
                            row.approvalsDenied(),
                            row.policyActionAtCreation(),
                            row.createdAt(),
                            now,
                            now
                    );
                    requestRepo.updateApprovalRequest(updated);

                    historyRepo.insertStateTransition(new StateTransitionRow(
                            0L,
                            row.id(),
                            previousState,
                            "EXPIRED",
                            "system",
                            "expiry-sweeper",
                            "request_expired",
                            now
                    ));

                    log.info("Expired approval request {} (was {} since {})",
                            row.id(), previousState, row.requestedAt());
                } catch (RuntimeException e) {
                    log.warn("Failed to expire request {}: {}", row.id(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Expiry sweep failed: {}", e.getMessage());
        }
    }
}