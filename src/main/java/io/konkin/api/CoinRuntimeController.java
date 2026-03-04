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