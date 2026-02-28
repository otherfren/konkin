package io.konkin.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KvStoreTest {

    private static HikariDataSource dataSource;
    private KvStore store;

    @BeforeAll
    static void setUpDatabase() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:kvstoretest_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(2);
        dataSource = new HikariDataSource(cfg);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) dataSource.close();
    }

    @BeforeEach
    void setUp() {
        store = new KvStore(dataSource);
        JdbiFactory.create(dataSource).useHandle(h -> h.execute("DELETE FROM kv_store"));
    }

    @Test
    void get_returnsEmpty_whenKeyDoesNotExist() {
        Optional<String> result = store.get("missing-key");
        assertTrue(result.isEmpty());
    }

    @Test
    void put_and_get_roundtrip() {
        store.put("foo", "bar");
        Optional<String> result = store.get("foo");
        assertTrue(result.isPresent());
        assertEquals("bar", result.get());
    }

    @Test
    void put_updatesExistingValue() {
        store.put("key", "first");
        store.put("key", "second");
        assertEquals("second", store.get("key").orElseThrow());
    }

    @Test
    void delete_removesKey_andReturnsTrue() {
        store.put("toDelete", "value");
        boolean deleted = store.delete("toDelete");
        assertTrue(deleted);
        assertTrue(store.get("toDelete").isEmpty());
    }

    @Test
    void delete_returnsFalse_whenKeyNotFound() {
        boolean deleted = store.delete("nonexistent");
        assertFalse(deleted);
    }

    @Test
    void listAll_returnsEmpty_whenStoreIsEmpty() {
        Map<String, String> all = store.listAll();
        assertTrue(all.isEmpty());
    }

    @Test
    void listAll_returnsAllEntries() {
        store.put("a", "1");
        store.put("b", "2");
        store.put("c", "3");
        Map<String, String> all = store.listAll();
        assertEquals(3, all.size());
        assertEquals("1", all.get("a"));
        assertEquals("2", all.get("b"));
        assertEquals("3", all.get("c"));
    }

    @Test
    void listAll_orderedByLastEditDescending() throws InterruptedException {
        store.put("oldest", "x");
        Thread.sleep(5);
        store.put("newest", "y");
        Map<String, String> all = store.listAll();
        // LinkedHashMap preserves insertion order — first key should be newest
        String firstKey = all.keySet().iterator().next();
        assertEquals("newest", firstKey);
    }

    @Test
    void put_storedValueSurvivesNewStoreInstance() {
        store.put("persistent", "value");
        KvStore anotherInstance = new KvStore(dataSource);
        assertEquals("value", anotherInstance.get("persistent").orElseThrow());
    }

    static DataSource dataSource() {
        return dataSource;
    }
}
