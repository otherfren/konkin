package io.konkin.api;

import io.javalin.http.Context;
import io.konkin.db.AuthQueueStore;
import io.konkin.db.entity.ApprovalCoinRuntimeRow;

public class CoinRuntimeController {
    private final AuthQueueStore authQueueStore;

    public CoinRuntimeController(AuthQueueStore authQueueStore) {
        this.authQueueStore = authQueueStore;
    }

    public void getAll(Context ctx) {
        ctx.json(authQueueStore.listAllCoinRuntimes());
    }

    public void getOne(Context ctx) {
        String coin = ctx.pathParam("coin");
        ApprovalCoinRuntimeRow row = authQueueStore.findCoinRuntimeByCoin(coin);
        if (row != null) {
            ctx.json(row);
        } else {
            ctx.status(404);
        }
    }

    public void create(Context ctx) {
        ApprovalCoinRuntimeRow row = ctx.bodyAsClass(ApprovalCoinRuntimeRow.class);
        authQueueStore.insertCoinRuntime(row);
        ctx.status(201).json(row);
    }

    public void update(Context ctx) {
        String coin = ctx.pathParam("coin");
        ApprovalCoinRuntimeRow row = ctx.bodyAsClass(ApprovalCoinRuntimeRow.class);
        if (!coin.equals(row.coin())) {
            ctx.status(400).result("Coin in path does not match coin in body");
            return;
        }
        authQueueStore.updateCoinRuntime(row);
        ctx.status(200).json(row);
    }

    public void delete(Context ctx) {
        String coin = ctx.pathParam("coin");
        if (authQueueStore.deleteCoinRuntime(coin)) {
            ctx.status(204);
        } else {
            ctx.status(404);
        }
    }
}
