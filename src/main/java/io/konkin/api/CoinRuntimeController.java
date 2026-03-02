package io.konkin.api;

import io.javalin.http.Context;
import io.konkin.db.ApprovalRequestRepository;
import io.konkin.db.entity.ApprovalCoinRuntimeRow;

public class CoinRuntimeController {
    private final ApprovalRequestRepository requestRepo;

    public CoinRuntimeController(ApprovalRequestRepository requestRepo) {
        this.requestRepo = requestRepo;
    }

    public void getAll(Context ctx) {
        ctx.json(requestRepo.listAllCoinRuntimes());
    }

    public void getOne(Context ctx) {
        String coin = ctx.pathParam("coin");
        ApprovalCoinRuntimeRow row = requestRepo.findCoinRuntimeByCoin(coin);
        if (row != null) {
            ctx.json(row);
        } else {
            ctx.status(404);
        }
    }

    public void create(Context ctx) {
        ApprovalCoinRuntimeRow row = ctx.bodyAsClass(ApprovalCoinRuntimeRow.class);
        requestRepo.insertCoinRuntime(row);
        ctx.status(201).json(row);
    }

    public void update(Context ctx) {
        String coin = ctx.pathParam("coin");
        ApprovalCoinRuntimeRow row = ctx.bodyAsClass(ApprovalCoinRuntimeRow.class);
        if (!coin.equals(row.coin())) {
            ctx.status(400).result("Coin in path does not match coin in body");
            return;
        }
        requestRepo.updateCoinRuntime(row);
        ctx.status(200).json(row);
    }

    public void delete(Context ctx) {
        String coin = ctx.pathParam("coin");
        if (requestRepo.deleteCoinRuntime(coin)) {
            ctx.status(204);
        } else {
            ctx.status(404);
        }
    }
}
