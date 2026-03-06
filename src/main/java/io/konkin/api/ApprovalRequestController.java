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

package io.konkin.api;

import io.javalin.http.Context;
import io.konkin.db.ApprovalRequestRepository;
import io.konkin.db.RequestDependencyLoader;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.PageResult;

/**
 * Controller for approval requests.
 */
public class ApprovalRequestController {

    private final ApprovalRequestRepository requestRepo;
    private final RequestDependencyLoader depLoader;

    public ApprovalRequestController(ApprovalRequestRepository requestRepo, RequestDependencyLoader depLoader) {
        this.requestRepo = requestRepo;
        this.depLoader = depLoader;
    }

    public void getAll(Context ctx) {
        String sortBy = ctx.queryParamAsClass("sortBy", String.class).getOrDefault("requestedAt");
        String sortDir = ctx.queryParamAsClass("sortDir", String.class).getOrDefault("desc");
        int page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(1);
        int pageSize = ctx.queryParamAsClass("pageSize", Integer.class).getOrDefault(20);

        String coin = ctx.queryParam("coin");
        String tool = ctx.queryParam("tool");
        String state = ctx.queryParam("state");
        String text = ctx.queryParam("text");

        PageResult<ApprovalRequestRow> result = requestRepo.pageApprovalRequestsWithFilter(
                sortBy, sortDir, page, pageSize, coin, tool, state, text
        );
        ctx.json(result);
    }

    /**
     * [C-2] Security: create is restricted — state must be PENDING/QUEUED, vote counters must be zero.
     * Prevents attackers from inserting pre-approved requests.
     */
    public void create(Context ctx) {
        ApprovalRequestRow row = ctx.bodyAsClass(ApprovalRequestRow.class);
        String state = row.state();
        if (state != null && !"PENDING".equalsIgnoreCase(state) && !"QUEUED".equalsIgnoreCase(state)) {
            ctx.status(400).result("Cannot create request with state '" + state + "'. Only PENDING or QUEUED allowed.");
            return;
        }
        if (row.approvalsGranted() != 0 || row.approvalsDenied() != 0) {
            ctx.status(400).result("Cannot create request with non-zero approval/denial counts.");
            return;
        }
        requestRepo.insertApprovalRequest(row);
        ctx.status(201).json(row);
    }

    /**
     * [C-2] Security: update is restricted — cannot change state, vote counters, or nonce fields via API.
     */
    public void update(Context ctx) {
        String id = ctx.pathParam("id");
        ApprovalRequestRow row = ctx.bodyAsClass(ApprovalRequestRow.class);
        if (!id.equals(row.id())) {
            ctx.status(400).result("ID in path does not match ID in body");
            return;
        }
        ApprovalRequestRow existing = requestRepo.findApprovalRequestById(id);
        if (existing == null) {
            ctx.status(404).result("Request not found");
            return;
        }
        // Prevent state/vote manipulation via PUT
        if (row.state() != null && !row.state().equals(existing.state())) {
            ctx.status(400).result("Cannot change request state via API update. State transitions are managed internally.");
            return;
        }
        if (row.approvalsGranted() != existing.approvalsGranted() || row.approvalsDenied() != existing.approvalsDenied()) {
            ctx.status(400).result("Cannot change approval/denial counts via API update.");
            return;
        }
        requestRepo.updateApprovalRequest(row);
        ctx.status(200).json(row);
    }

    public void delete(Context ctx) {
        String id = ctx.pathParam("id");
        if (requestRepo.deleteApprovalRequest(id)) {
            ctx.status(204);
        } else {
            ctx.status(404);
        }
    }

    public void getOne(Context ctx) {
        String id = ctx.pathParam("id");
        ApprovalRequestRow row = requestRepo.findApprovalRequestById(id);
        if (row != null) {
            ctx.json(row);
        } else {
            ctx.status(404);
        }
    }

    public void getFilterOptions(Context ctx) {
        ctx.json(requestRepo.loadNonPendingFilterOptions());
    }

    public void getDependencies(Context ctx) {
        String id = ctx.pathParam("id");
        var depsMap = depLoader.loadRequestDependencies(java.util.List.of(id));
        if (depsMap.containsKey(id)) {
            ctx.json(depsMap.get(id));
        } else {
            ctx.status(404);
        }
    }
}