package io.konkin.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Simple key/value store backed by the kv_store table.
 */
public class KvStore {

    private static final Logger log = LoggerFactory.getLogger(KvStore.class);

    private final DataSource dataSource;

    public KvStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Get a value by key.
     */
    public Optional<String> get(String key) {
        String sql = "SELECT value FROM kv_store WHERE key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString("value"));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get key '{}' from kv_store", key, e);
        }
        return Optional.empty();
    }

    /**
     * Put a key/value pair. Inserts or updates (MERGE).
     */
    public void put(String key, String value) {
        String sql = "MERGE INTO kv_store (key, value, last_edit) VALUES (?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to put key '{}' into kv_store", key, e);
        }
    }

    /**
     * Delete a key.
     */
    public boolean delete(String key) {
        String sql = "DELETE FROM kv_store WHERE key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete key '{}' from kv_store", key, e);
            return false;
        }
    }

    /**
     * List all key/value pairs.
     */
    public Map<String, String> listAll() {
        Map<String, String> result = new LinkedHashMap<>();
        String sql = "SELECT key, value FROM kv_store ORDER BY last_edit DESC";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.put(rs.getString("key"), rs.getString("value"));
            }
        } catch (SQLException e) {
            log.error("Failed to list all entries from kv_store", e);
        }
        return result;
    }
}
