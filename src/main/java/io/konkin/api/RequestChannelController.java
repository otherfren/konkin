package io.konkin.api;

import io.javalin.http.Context;
import io.konkin.db.AuthQueueStore;
import io.konkin.db.entity.ApprovalRequestChannelRow;

public class RequestChannelController {
    private final AuthQueueStore authQueueStore;

    public RequestChannelController(AuthQueueStore authQueueStore) {
        this.authQueueStore = authQueueStore;
    }

    public void getAll(Context ctx) {
        ctx.json(authQueueStore.listAllRequestChannels());
    }

    public void getOne(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        ApprovalRequestChannelRow row = authQueueStore.findRequestChannelById(id);
        if (row != null) {
            ctx.json(row);
        } else {
            ctx.status(404);
        }
    }

    public void create(Context ctx) {
        ApprovalRequestChannelRow row = ctx.bodyAsClass(ApprovalRequestChannelRow.class);
        authQueueStore.insertRequestChannel(row);
        ctx.status(201).json(row);
    }

    public void update(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        ApprovalRequestChannelRow row = ctx.bodyAsClass(ApprovalRequestChannelRow.class);
        if (id != row.id()) {
            ctx.status(400).result("ID in path does not match ID in body");
            return;
        }
        authQueueStore.updateRequestChannel(row);
        ctx.status(200).json(row);
    }

    public void delete(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        if (authQueueStore.deleteRequestChannel(id)) {
            ctx.status(204);
        } else {
            ctx.status(404);
        }
    }
}
