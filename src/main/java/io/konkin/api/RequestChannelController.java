package io.konkin.api;

import io.javalin.http.Context;
import io.konkin.db.ChannelRepository;
import io.konkin.db.entity.ApprovalRequestChannelRow;

public class RequestChannelController {
    private final ChannelRepository channelRepo;

    public RequestChannelController(ChannelRepository channelRepo) {
        this.channelRepo = channelRepo;
    }

    public void getAll(Context ctx) {
        ctx.json(channelRepo.listAllRequestChannels());
    }

    public void getOne(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        ApprovalRequestChannelRow row = channelRepo.findRequestChannelById(id);
        if (row != null) {
            ctx.json(row);
        } else {
            ctx.status(404);
        }
    }

    public void create(Context ctx) {
        ApprovalRequestChannelRow row = ctx.bodyAsClass(ApprovalRequestChannelRow.class);
        channelRepo.insertRequestChannel(row);
        ctx.status(201).json(row);
    }

    public void update(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        ApprovalRequestChannelRow row = ctx.bodyAsClass(ApprovalRequestChannelRow.class);
        if (id != row.id()) {
            ctx.status(400).result("ID in path does not match ID in body");
            return;
        }
        channelRepo.updateRequestChannel(row);
        ctx.status(200).json(row);
    }

    public void delete(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        if (channelRepo.deleteRequestChannel(id)) {
            ctx.status(204);
        } else {
            ctx.status(404);
        }
    }
}
