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

package io.konkin.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.FileConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import io.konkin.db.ConfigOverrideStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe manager for runtime config updates.
 * <p>
 * Config edits are persisted to a database table (config_overrides) for fast,
 * atomic writes. TOML file write-back is serialized behind a semaphore to
 * prevent concurrent editing of config.toml.
 */
public class ConfigManager {

    private static final Logger log = LoggerFactory.getLogger(ConfigManager.class);

    private static final Set<String> RESTART_REQUIRED_PATHS = Set.of(
            "server.host",
            "server.port",
            "server.secrets-dir",
            "database.url",
            "database.user",
            "database.password",
            "database.pool-size",
            "web-ui.template.directory",
            "web-ui.static.directory"
    );

    private final AtomicReference<KonkinConfig> configRef;
    private final List<ConfigChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> pendingRestartFields = ConcurrentHashMap.newKeySet();
    private final ConfigOverrideStore overrideStore;
    private final Semaphore tomlWriteSemaphore = new Semaphore(1);
    private final byte[] startupTomlBytes;

    /**
     * Production constructor: persists config overrides to DB and writes back to TOML.
     * Caches the startup TOML content so runtime reloads never re-read the on-disk file.
     */
    public ConfigManager(KonkinConfig initialConfig, ConfigOverrideStore overrideStore) {
        this.configRef = new AtomicReference<>(initialConfig);
        this.overrideStore = overrideStore;
        this.startupTomlBytes = readStartupToml(initialConfig.configFilePath());
    }

    /**
     * Test/legacy constructor: direct TOML write-back only (no DB persistence).
     */
    public ConfigManager(KonkinConfig initialConfig) {
        this.configRef = new AtomicReference<>(initialConfig);
        this.overrideStore = null;
        this.startupTomlBytes = null;
    }

    /**
     * Returns the current config snapshot (thread-safe).
     */
    public KonkinConfig get() {
        return configRef.get();
    }

    /**
     * Update a section: all keys are prefixed with sectionPrefix + ".".
     */
    public ConfigUpdateResult updateSection(String sectionPrefix, Map<String, Object> values) {
        Map<String, Object> fullPaths = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String fullPath = sectionPrefix.isEmpty()
                    ? entry.getKey()
                    : sectionPrefix + "." + entry.getKey();
            fullPaths.put(fullPath, entry.getValue());
        }
        return updateBatch(fullPaths);
    }

    /**
     * Batch update multiple config paths atomically.
     * Writes to DB first, reloads config from TOML + DB overrides,
     * then asynchronously writes back to config.toml behind the semaphore.
     */
    public ConfigUpdateResult updateBatch(Map<String, Object> pathValues) {
        String configFilePath = configRef.get().configFilePath();
        if (configFilePath == null) {
            return ConfigUpdateResult.error("No config file path available — config is read-only");
        }

        if (overrideStore != null) {
            return updateBatchViaDb(pathValues, configFilePath);
        } else {
            return updateBatchDirectToml(pathValues, configFilePath);
        }
    }

    private ConfigUpdateResult updateBatchViaDb(Map<String, Object> pathValues, String configFilePath) {
        // 1. Write overrides to DB
        Map<String, String> serialized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : pathValues.entrySet()) {
            serialized.put(entry.getKey(), serializeValue(entry.getValue()));
        }
        overrideStore.putAll(serialized);

        // 2. Apply overrides to TOML in-memory and reload config
        KonkinConfig oldConfig = configRef.get();
        KonkinConfig newConfig;
        try {
            newConfig = loadWithOverrides(configFilePath);
        } catch (Exception e) {
            // Rollback DB overrides for the failed keys
            for (String key : pathValues.keySet()) {
                overrideStore.delete(key);
            }
            return ConfigUpdateResult.error("Validation failed: " + e.getMessage());
        }

        // 3. Determine changed paths and restart requirements
        List<String> changedPaths = new ArrayList<>(pathValues.keySet());
        boolean restartRequired = false;
        for (String changedPath : changedPaths) {
            if (isRestartRequired(changedPath)) {
                restartRequired = true;
                pendingRestartFields.add(changedPath);
            }
        }

        // 4. Swap config and notify listeners
        configRef.set(newConfig);
        notifyListeners(oldConfig, newConfig);

        log.info("Config updated via DB — changedPaths={}, restartRequired={}", changedPaths, restartRequired);

        // 5. Async TOML write-back (serialized behind semaphore)
        Map<String, Object> tomlValues = new LinkedHashMap<>(pathValues);
        Thread.ofVirtual().name("toml-writeback").start(() -> writeBackToToml(configFilePath, tomlValues));

        return ConfigUpdateResult.success(restartRequired, changedPaths);
    }

    private ConfigUpdateResult updateBatchDirectToml(Map<String, Object> pathValues, String configFilePath) {
        try {
            tomlWriteSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ConfigUpdateResult.error("Interrupted while acquiring write lock");
        }
        try {
            Path path = Path.of(configFilePath);
            Path tempFile = path.resolveSibling(path.getFileName() + ".tmp.toml");

            // Write to temp file, never touch original directly
            Files.copy(path, tempFile, StandardCopyOption.REPLACE_EXISTING);

            try (FileConfig toml = FileConfig.of(tempFile)) {
                toml.load();
                for (Map.Entry<String, Object> entry : pathValues.entrySet()) {
                    toml.set(entry.getKey(), entry.getValue());
                }
                toml.save();
            }

            // Verify non-empty before replacing
            if (Files.size(tempFile) == 0) {
                Files.deleteIfExists(tempFile);
                return ConfigUpdateResult.error("TOML write produced empty file — original preserved");
            }

            KonkinConfig oldConfig = configRef.get();
            KonkinConfig newConfig;
            try {
                // Validate from temp file before replacing original
                newConfig = KonkinConfig.load(tempFile.toString());
            } catch (Exception e) {
                Files.deleteIfExists(tempFile);
                return ConfigUpdateResult.error("Validation failed: " + e.getMessage());
            }

            // Atomic move: only replaces original after validation passes
            Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            List<String> changedPaths = new ArrayList<>(pathValues.keySet());
            boolean restartRequired = false;
            for (String changedPath : changedPaths) {
                if (isRestartRequired(changedPath)) {
                    restartRequired = true;
                    pendingRestartFields.add(changedPath);
                }
            }

            // Fix the config file path to point to the real file, not the temp
            newConfig.configFilePath = Path.of(configFilePath).toAbsolutePath().normalize().toString();
            configRef.set(newConfig);
            notifyListeners(oldConfig, newConfig);

            log.info("Config updated — changedPaths={}, restartRequired={}", changedPaths, restartRequired);
            return ConfigUpdateResult.success(restartRequired, changedPaths);

        } catch (IOException e) {
            return ConfigUpdateResult.error("Failed to update config file: " + e.getMessage());
        } finally {
            tomlWriteSemaphore.release();
        }
    }

    /**
     * Register a listener for config change notifications.
     */
    public void addListener(ConfigChangeListener listener) {
        listeners.add(listener);
    }

    private void notifyListeners(KonkinConfig oldConfig, KonkinConfig newConfig) {
        for (ConfigChangeListener listener : listeners) {
            try {
                listener.onConfigChanged(oldConfig, newConfig);
            } catch (Exception e) {
                log.warn("Config change listener threw exception", e);
            }
        }
    }

    /**
     * Returns the set of restart-required TOML paths that have been changed since startup.
     */
    public Set<String> pendingRestartFields() {
        return Set.copyOf(pendingRestartFields);
    }

    /**
     * Parses config from cached startup TOML bytes and applies all DB overrides on top.
     * Never re-reads the on-disk TOML file, so async write-back cannot cause stale reads.
     */
    KonkinConfig loadWithOverrides(String configFilePath) {
        String tomlContent = new String(startupTomlBytes, StandardCharsets.UTF_8);
        Config baseConfig = new TomlParser().parse(new StringReader(tomlContent));

        // Apply all DB overrides
        Map<String, String> overrides = overrideStore.getAll();
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            baseConfig.set(entry.getKey(), deserializeValue(entry.getValue()));
        }

        KonkinConfig config = KonkinConfigLoader.load(baseConfig);
        config.configFilePath = Path.of(configFilePath).toAbsolutePath().normalize().toString();
        KonkinConfigValidator.validate(config);
        return config;
    }

    private void writeBackToToml(String configFilePath, Map<String, Object> pathValues) {
        try {
            tomlWriteSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("TOML write-back interrupted while waiting for semaphore");
            return;
        }
        try {
            Path path = Path.of(configFilePath);
            Path tempFile = path.resolveSibling(path.getFileName() + ".tmp.toml");

            // Write to a temp file, never touch the original directly
            Files.copy(path, tempFile, StandardCopyOption.REPLACE_EXISTING);

            try (FileConfig toml = FileConfig.of(tempFile)) {
                toml.load();
                for (Map.Entry<String, Object> entry : pathValues.entrySet()) {
                    toml.set(entry.getKey(), entry.getValue());
                }
                toml.save();
            }

            // Verify the temp file is non-empty before replacing original
            long size = Files.size(tempFile);
            if (size == 0) {
                log.error("TOML write-back produced empty file, aborting — original config.toml preserved");
                Files.deleteIfExists(tempFile);
                return;
            }

            // Atomic move: replaces original only after temp is fully written
            Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.debug("TOML write-back complete for {} paths", pathValues.size());
        } catch (IOException e) {
            log.error("Failed to write config changes back to TOML file: {}", configFilePath, e);
        } finally {
            tomlWriteSemaphore.release();
        }
    }

    private boolean isRestartRequired(String tomlPath) {
        if (RESTART_REQUIRED_PATHS.contains(tomlPath)) {
            return true;
        }
        if (tomlPath.startsWith("agents.") && (tomlPath.endsWith(".bind") || tomlPath.endsWith(".port"))) {
            return true;
        }
        return false;
    }

    private static byte[] readStartupToml(String configFilePath) {
        if (configFilePath == null) return new byte[0];
        try {
            return Files.readAllBytes(Path.of(configFilePath));
        } catch (IOException e) {
            log.warn("Failed to cache startup TOML file: {}", configFilePath, e);
            return new byte[0];
        }
    }

    @SuppressWarnings("unchecked")
    static String serializeValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number) return value.toString();
        if (value instanceof String s) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(serializeValue(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(entry.getKey()).append("\":").append(serializeValue(entry.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        return "\"" + value + "\"";
    }

    static Object deserializeValue(String serialized) {
        if (serialized == null || "null".equals(serialized)) return null;
        if ("true".equals(serialized)) return true;
        if ("false".equals(serialized)) return false;

        // Try integer first, then double
        try {
            return Integer.parseInt(serialized);
        } catch (NumberFormatException ignored) {}
        try {
            return Double.parseDouble(serialized);
        } catch (NumberFormatException ignored) {}

        // Quoted string
        if (serialized.startsWith("\"") && serialized.endsWith("\"")) {
            return serialized.substring(1, serialized.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }

        // JSON array
        if (serialized.startsWith("[")) {
            return parseJsonArray(serialized);
        }

        // JSON object
        if (serialized.startsWith("{")) {
            return parseJsonObject(serialized);
        }

        return serialized;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> parseJsonArray(String json) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Object> list = mapper.readValue(json, List.class);
            return list;
        } catch (Exception e) {
            log.warn("Failed to parse JSON array from config override: {}", json, e);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonObject(String json) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse JSON object from config override: {}", json, e);
            return Map.of();
        }
    }
}
