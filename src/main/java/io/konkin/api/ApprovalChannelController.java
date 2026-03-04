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