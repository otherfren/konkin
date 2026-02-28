package io.konkin.api;

import io.javalin.http.Context;
import io.konkin.db.KvStore;
import java.util.Map;

/**
 * CRUD controller for the kv_store table.
 */
public class KvStoreController {

    private final KvStore kvStore;

    public KvStoreController(KvStore kvStore) {
        this.kvStore = kvStore;
    }

    public void getAll(Context ctx) {
        ctx.json(kvStore.listAll());
    }

    public void getOne(Context ctx) {
        String key = ctx.pathParam("key");
        kvStore.get(key)
                .ifPresentOrElse(ctx::json, () -> ctx.status(404));
    }

    public void put(Context ctx) {
        String key = ctx.pathParam("key");
        String value = ctx.body();
        kvStore.put(key, value);
        ctx.status(204);
    }

    public void delete(Context ctx) {
        String key = ctx.pathParam("key");
        if (kvStore.delete(key)) {
            ctx.status(204);
        } else {
            ctx.status(404);
        }
    }
}
