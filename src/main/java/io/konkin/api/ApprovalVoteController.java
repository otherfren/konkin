package io.konkin.api;

import io.javalin.http.Context;
import io.konkin.db.AuthQueueStore;
import io.konkin.db.entity.VoteDetail;

/**
 * Controller for approval votes.
 */
public class ApprovalVoteController {

    private final AuthQueueStore authQueueStore;

    public ApprovalVoteController(AuthQueueStore authQueueStore) {
        this.authQueueStore = authQueueStore;
    }

    public void getAll(Context ctx) {
        ctx.json(authQueueStore.listAllVotes());
    }

    public void create(Context ctx) {
        VoteDetail row = ctx.bodyAsClass(VoteDetail.class);
        authQueueStore.insertVote(row);
        ctx.status(201).json(row);
    }

    public void update(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        VoteDetail row = ctx.bodyAsClass(VoteDetail.class);
        if (id != row.id()) {
            ctx.status(400).result("ID in path does not match ID in body");
            return;
        }
        authQueueStore.updateVote(row);
        ctx.status(200).json(row);
    }

    public void delete(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        if (authQueueStore.deleteVote(id)) {
            ctx.status(204);
        } else {
            ctx.status(404);
        }
    }

    public void getOne(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        VoteDetail row = authQueueStore.findVoteById(id);
        if (row != null) {
            ctx.json(row);
        } else {
            ctx.status(404);
        }
    }

    public void getForRequest(Context ctx) {
        String requestId = ctx.pathParam("requestId");
        ctx.json(authQueueStore.listVotesForRequest(requestId));
    }
}
