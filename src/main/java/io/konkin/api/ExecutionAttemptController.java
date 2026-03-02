package io.konkin.api;

import io.javalin.http.Context;
import io.konkin.db.HistoryRepository;
import io.konkin.db.entity.ExecutionAttemptDetail;

public class ExecutionAttemptController {
    private final HistoryRepository historyRepo;

    public ExecutionAttemptController(HistoryRepository historyRepo) {
        this.historyRepo = historyRepo;
    }

    public void getAll(Context ctx) {
        ctx.json(historyRepo.listAllExecutionAttempts());
    }

    public void getOne(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        ExecutionAttemptDetail row = historyRepo.findExecutionAttemptById(id);
        if (row != null) {
            ctx.json(row);
        } else {
            ctx.status(404);
        }
    }

    public void create(Context ctx) {
        ExecutionAttemptDetail row = ctx.bodyAsClass(ExecutionAttemptDetail.class);
        historyRepo.insertExecutionAttempt(row);
        ctx.status(201).json(row);
    }

    public void delete(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        if (historyRepo.deleteExecutionAttempt(id)) {
            ctx.status(204);
        } else {
            ctx.status(404);
        }
    }
}
