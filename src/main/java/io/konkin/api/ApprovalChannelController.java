package io.konkin.api;

import io.javalin.http.Context;
import io.konkin.db.ChannelRepository;
import io.konkin.db.entity.ApprovalChannelRow;

/**
 * Controller for approval channels.
 */
public class ApprovalChannelController {

    private final ChannelRepository channelRepo;

    public ApprovalChannelController(ChannelRepository channelRepo) {
        this.channelRepo = channelRepo;
    }

    public void getAll(Context ctx) {
        ctx.json(channelRepo.listChannels());
    }

    public void create(Context ctx) {
        ApprovalChannelRow row = ctx.bodyAsClass(ApprovalChannelRow.class);
        channelRepo.insertChannel(row);
        ctx.status(201).json(row);
    }

    public void update(Context ctx) {
        String id = ctx.pathParam("id");
        ApprovalChannelRow row = ctx.bodyAsClass(ApprovalChannelRow.class);
        if (!id.equals(row.id())) {
            ctx.status(400).result("ID in path does not match ID in body");
            return;
        }
        channelRepo.updateChannel(row);
        ctx.status(200).json(row);
    }

    public void delete(Context ctx) {
        String id = ctx.pathParam("id");
        if (channelRepo.deleteChannel(id)) {
            ctx.status(204);
        } else {
            ctx.status(404);
        }
    }

    public void getOne(Context ctx) {
        String id = ctx.pathParam("id");
        var channel = channelRepo.findChannelById(id);
        if (channel != null) {
            ctx.json(channel);
        } else {
            ctx.status(404);
        }
    }
}
