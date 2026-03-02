package io.konkin.api;

import io.javalin.http.Context;
import io.konkin.db.HistoryRepository;
import io.konkin.db.entity.StateTransitionRow;

public class StateTransitionController {
    private final HistoryRepository historyRepo;

    public StateTransitionController(HistoryRepository historyRepo) {
        this.historyRepo = historyRepo;
    }

    public void getAll(Context ctx) {
        ctx.json(historyRepo.listAllStateTransitions());
    }

    public void getOne(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        StateTransitionRow row = historyRepo.findStateTransitionById(id);
        if (row != null) {
            ctx.json(row);
        } else {
            ctx.status(404);
        }
    }

    public void create(Context ctx) {
        StateTransitionRow row = ctx.bodyAsClass(StateTransitionRow.class);
        historyRepo.insertStateTransition(row);
        ctx.status(201).json(row);
    }

    public void delete(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        if (historyRepo.deleteStateTransition(id)) {
            ctx.status(204);
        } else {
            ctx.status(404);
        }
    }
}
