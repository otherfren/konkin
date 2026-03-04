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
import io.konkin.db.HistoryRepository;
import io.konkin.db.entity.StateTransitionRow;

public class StateTransitionController {
    private final HistoryRepository historyRepo;

    public StateTransitionController(HistoryRepository historyRepo) {
        this.historyRepo = historyRepo;
    }

    public void getAll(Context ctx) {
        ctx.json(historyRepo.listAllStateTransitions());
    }

    public void getOne(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        StateTransitionRow row = historyRepo.findStateTransitionById(id);
        if (row != null) {
            ctx.json(row);
        } else {
            ctx.status(404);
        }
    }

    public void create(Context ctx) {
        StateTransitionRow row = ctx.bodyAsClass(StateTransitionRow.class);
        historyRepo.insertStateTransition(row);
        ctx.status(201).json(row);
    }

    public void delete(Context ctx) {
        long id = ctx.pathParamAsClass("id", Long.class).get();
        if (historyRepo.deleteStateTransition(id)) {
            ctx.status(204);
        } else {
            ctx.status(404);
        }
    }
}