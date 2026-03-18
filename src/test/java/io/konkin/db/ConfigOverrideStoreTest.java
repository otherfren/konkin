package io.konkin.db;

import io.konkin.TestDatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigOverrideStoreTest {

    private static final DataSource dataSource = TestDatabaseManager.dataSource("config-override-test");
    private ConfigOverrideStore store;

    @BeforeEach
    void setUp() {
        TestDatabaseManager.truncateAll(dataSource);
        store = new ConfigOverrideStore(dataSource);
    }

    @Test
    void getAll_returnsEmpty_whenNoEntries() {
        assertTrue(store.getAll().isEmpty());
    }

    @Test
    void putAll_andGetAll_roundTrips() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("server.port", "8080");
        entries.put("server.host", "0.0.0.0");
        store.putAll(entries);

        Map<String, String> result = store.getAll();
        assertEquals(2, result.size());
        assertEquals("8080", result.get("server.port"));
        assertEquals("0.0.0.0", result.get("server.host"));
    }

    @Test
    void putAll_upserts_existingKeys() {
        store.putAll(Map.of("server.port", "8080"));
        store.putAll(Map.of("server.port", "9090"));

        Map<String, String> result = store.getAll();
        assertEquals(1, result.size());
        assertEquals("9090", result.get("server.port"));
    }

    @Test
    void delete_removesSpecificKey() {
        store.putAll(Map.of("server.port", "8080", "server.host", "localhost"));
        store.delete("server.port");

        Map<String, String> result = store.getAll();
        assertEquals(1, result.size());
        assertNull(result.get("server.port"));
        assertEquals("localhost", result.get("server.host"));
    }

    @Test
    void delete_noopForMissingKey() {
        store.putAll(Map.of("server.port", "8080"));
        store.delete("nonexistent.key");
        assertEquals(1, store.getAll().size());
    }

    @Test
    void deleteByPrefix_removesMatchingKeys() {
        store.putAll(Map.of(
                "server.port", "8080",
                "server.host", "localhost",
                "database.url", "jdbc:h2:mem"));
        store.deleteByPrefix("server.");

        Map<String, String> result = store.getAll();
        assertEquals(1, result.size());
        assertEquals("jdbc:h2:mem", result.get("database.url"));
    }

    @Test
    void deleteByPrefix_noopWhenNothingMatches() {
        store.putAll(Map.of("server.port", "8080"));
        store.deleteByPrefix("telegram.");
        assertEquals(1, store.getAll().size());
    }

    @Test
    void deleteAll_clearsEverything() {
        store.putAll(Map.of("a", "1", "b", "2", "c", "3"));
        store.deleteAll();
        assertTrue(store.getAll().isEmpty());
    }

    @Test
    void getAll_returnsSortedByKey() {
        store.putAll(Map.of("z.key", "z", "a.key", "a", "m.key", "m"));
        var keys = store.getAll().keySet().stream().toList();
        assertEquals("a.key", keys.get(0));
        assertEquals("m.key", keys.get(1));
        assertEquals("z.key", keys.get(2));
    }
}
