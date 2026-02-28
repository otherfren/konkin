package io.konkin.api;

import io.javalin.http.Context;
import io.konkin.db.AuthQueueStore;
import io.konkin.db.entity.ApprovalChannelRow;

/**
 * Controller for approval channels.
 */
public class ApprovalChannelController {

    private final AuthQueueStore authQueueStore;

    public ApprovalChannelController(AuthQueueStore authQueueStore) {
        this.authQueueStore = authQueueStore;
    }

    public void getAll(Context ctx) {
        ctx.json(authQueueStore.listChannels());
    }

    public void create(Context ctx) {
        ApprovalChannelRow row = ctx.bodyAsClass(ApprovalChannelRow.class);
        authQueueStore.insertChannel(row);
        ctx.status(201).json(row);
    }

    public void update(Context ctx) {
        String id = ctx.pathParam("id");
        ApprovalChannelRow row = ctx.bodyAsClass(ApprovalChannelRow.class);
        if (!id.equals(row.id())) {
            ctx.status(400).result("ID in path does not match ID in body");
            return;
        }
        authQueueStore.updateChannel(row);
        ctx.status(200).json(row);
    }

    public void delete(Context ctx) {
        String id = ctx.pathParam("id");
        if (authQueueStore.deleteChannel(id)) {
            ctx.status(204);
        } else {
            ctx.status(404);
        }
    }

    public void getOne(Context ctx) {
        String id = ctx.pathParam("id");
        var channel = authQueueStore.findChannelById(id);
        if (channel != null) {
            ctx.json(channel);
        } else {
            ctx.status(404);
        }
    }
}
