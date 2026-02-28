package io.konkin.api;

import io.javalin.http.Context;
import io.konkin.db.AuthQueueStore;
import io.konkin.db.entity.ExecutionAttemptDetail;

public class ExecutionAttemptController {
    private final AuthQueueStore authQueueStore;

    public ExecutionAttemptController(AuthQueueStore authQueueStore) {
        this.authQueueStore = authQueueStore;
    }

    public void getAll(Context ctx) {
        ctx.json(authQueueStore.listAllExecutionAttempts());
    }

    public void getOne(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        ExecutionAttemptDetail row = authQueueStore.findExecutionAttemptById(id);
        if (row != null) {
            ctx.json(row);
        } else {
            ctx.status(404);
        }
    }

    public void create(Context ctx) {
        ExecutionAttemptDetail row = ctx.bodyAsClass(ExecutionAttemptDetail.class);
        authQueueStore.insertExecutionAttempt(row);
        ctx.status(201).json(row);
    }

    public void delete(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        if (authQueueStore.deleteExecutionAttempt(id)) {
            ctx.status(204);
        } else {
            ctx.status(404);
        }
    }
}
