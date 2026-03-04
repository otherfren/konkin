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

    public void create(Context ctx) {
        ApprovalRequestRow row = ctx.bodyAsClass(ApprovalRequestRow.class);
        requestRepo.insertApprovalRequest(row);
        ctx.status(201).json(row);
    }

    public void update(Context ctx) {
        String id = ctx.pathParam("id");
        ApprovalRequestRow row = ctx.bodyAsClass(ApprovalRequestRow.class);
        if (!id.equals(row.id())) {
            ctx.status(400).result("ID in path does not match ID in body");
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