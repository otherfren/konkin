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
import io.konkin.db.VoteRepository;
import io.konkin.db.entity.VoteDetail;

/**
 * Controller for approval votes.
 */
public class ApprovalVoteController {

    private final VoteRepository voteRepo;

    public ApprovalVoteController(VoteRepository voteRepo) {
        this.voteRepo = voteRepo;
    }

    public void getAll(Context ctx) {
        ctx.json(voteRepo.listAllVotes());
    }

    /**
     * [C-2] Security: vote creation via REST API is disabled.
     * Votes must be cast through the proper approval channels (web UI, Telegram, MCP agents).
     */
    public void create(Context ctx) {
        ctx.status(403).result("Direct vote creation via REST API is not allowed. Use the proper approval channels.");
    }

    /**
     * [C-2] Security: vote update via REST API is disabled.
     * Vote decisions are immutable once cast.
     */
    public void update(Context ctx) {
        ctx.status(403).result("Vote modification via REST API is not allowed. Votes are immutable once cast.");
    }

    public void delete(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        if (voteRepo.deleteVote(id)) {
            ctx.status(204);
        } else {
            ctx.status(404);
        }
    }

    public void getOne(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        VoteDetail row = voteRepo.findVoteById(id);
        if (row != null) {
            ctx.json(row);
        } else {
            ctx.status(404);
        }
    }

    public void getForRequest(Context ctx) {
        String requestId = ctx.pathParam("requestId");
        ctx.json(voteRepo.listVotesForRequest(requestId));
    }
}