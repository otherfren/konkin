package io.konkin.api;

import io.javalin.http.Context;
import io.konkin.db.AuthQueueStore;
import io.konkin.db.entity.ApprovalRequestRow;
import io.konkin.db.entity.PageResult;

/**
 * Controller for approval requests.
 */
public class ApprovalRequestController {

    private final AuthQueueStore authQueueStore;

    public ApprovalRequestController(AuthQueueStore authQueueStore) {
        this.authQueueStore = authQueueStore;
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

        PageResult<ApprovalRequestRow> result = authQueueStore.pageApprovalRequestsWithFilter(
                sortBy, sortDir, page, pageSize, coin, tool, state, text
        );
        ctx.json(result);
    }

    public void create(Context ctx) {
        ApprovalRequestRow row = ctx.bodyAsClass(ApprovalRequestRow.class);
        authQueueStore.insertApprovalRequest(row);
        ctx.status(201).json(row);
    }

    public void update(Context ctx) {
        String id = ctx.pathParam("id");
        ApprovalRequestRow row = ctx.bodyAsClass(ApprovalRequestRow.class);
        if (!id.equals(row.id())) {
            ctx.status(400).result("ID in path does not match ID in body");
            return;
        }
        authQueueStore.updateApprovalRequest(row);
        ctx.status(200).json(row);
    }

    public void delete(Context ctx) {
        String id = ctx.pathParam("id");
        if (authQueueStore.deleteApprovalRequest(id)) {
            ctx.status(204);
        } else {
            ctx.status(404);
        }
    }

    public void getOne(Context ctx) {
        String id = ctx.pathParam("id");
        ApprovalRequestRow row = authQueueStore.findApprovalRequestById(id);
        if (row != null) {
            ctx.json(row);
        } else {
            ctx.status(404);
        }
    }

    public void getFilterOptions(Context ctx) {
        ctx.json(authQueueStore.loadNonPendingFilterOptions());
    }

    public void getDependencies(Context ctx) {
        String id = ctx.pathParam("id");
        var depsMap = authQueueStore.loadRequestDependencies(java.util.List.of(id));
        if (depsMap.containsKey(id)) {
            ctx.json(depsMap.get(id));
        } else {
            ctx.status(404);
        }
    }
}
