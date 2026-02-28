package io.konkin.api;

import io.javalin.http.Context;
import io.konkin.db.AuthQueueStore;
import io.konkin.db.entity.StateTransitionRow;

public class StateTransitionController {
    private final AuthQueueStore authQueueStore;

    public StateTransitionController(AuthQueueStore authQueueStore) {
        this.authQueueStore = authQueueStore;
    }

    public void getAll(Context ctx) {
        ctx.json(authQueueStore.listAllStateTransitions());
    }

    public void getOne(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        StateTransitionRow row = authQueueStore.findStateTransitionById(id);
        if (row != null) {
            ctx.json(row);
        } else {
            ctx.status(404);
        }
    }

    public void create(Context ctx) {
        StateTransitionRow row = ctx.bodyAsClass(StateTransitionRow.class);
        authQueueStore.insertStateTransition(row);
        ctx.status(201).json(row);
    }

    public void delete(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        if (authQueueStore.deleteStateTransition(id)) {
            ctx.status(204);
        } else {
            ctx.status(404);
        }
    }
}
