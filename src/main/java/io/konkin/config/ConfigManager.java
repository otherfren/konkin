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

import com.electronwill.nightconfig.core.file.FileConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe manager for runtime config updates.
 * Holds an {@link AtomicReference} to the current {@link KonkinConfig},
 * supports field-level TOML write-back with validation and rollback,
 * and notifies {@link ConfigChangeListener}s on successful updates.
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
    private final KonkinConfig startupConfig;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<ConfigChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> pendingRestartFields = ConcurrentHashMap.newKeySet();

    public ConfigManager(KonkinConfig initialConfig) {
        this.configRef = new AtomicReference<>(initialConfig);
        this.startupConfig = initialConfig;
    }

    /**
     * Returns the current config snapshot (thread-safe).
     */
    public KonkinConfig get() {
        return configRef.get();
    }

    /**
     * Update a single TOML path.
     */
    public ConfigUpdateResult update(String tomlPath, Object value) {
        return updateBatch(Map.of(tomlPath, value));
    }

    /**
     * Update a section: all keys are prefixed with sectionPrefix + ".".
     */
    public ConfigUpdateResult updateSection(String sectionPrefix, Map<String, Object> values) {
        Map<String, Object> fullPaths = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String fullPath = sectionPrefix.isEmpty()
                    ? entry.getKey()
                    : sectionPrefix + "." + entry.getKey();
            fullPaths.put(fullPath, entry.getValue());
        }
        return updateBatch(fullPaths);
    }

    /**
     * Batch update multiple TOML paths atomically (single file write).
     */
    public ConfigUpdateResult updateBatch(Map<String, Object> pathValues) {
        String configFilePath = configRef.get().configFilePath();
        if (configFilePath == null) {
            return ConfigUpdateResult.error("No config file path available — config is read-only");
        }

        lock.writeLock().lock();
        try {
            Path path = Path.of(configFilePath);
            Path backup = path.resolveSibling(path.getFileName() + ".bak");

            // 1. Create backup
            Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);

            // 2. Load, modify, save
            try (FileConfig toml = FileConfig.of(path)) {
                toml.load();
                for (Map.Entry<String, Object> entry : pathValues.entrySet()) {
                    toml.set(entry.getKey(), entry.getValue());
                }
                toml.save();
            }

            // 3. Reload and validate
            KonkinConfig oldConfig = configRef.get();
            KonkinConfig newConfig;
            try {
                newConfig = KonkinConfig.load(configFilePath);
            } catch (Exception e) {
                // 4. Revert on failure
                try {
                    Files.copy(backup, path, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException revertEx) {
                    log.error("Failed to revert config file from backup after validation failure", revertEx);
                }
                return ConfigUpdateResult.error("Validation failed: " + e.getMessage());
            }

            // 5. Determine changed paths and restart requirements
            List<String> changedPaths = new ArrayList<>(pathValues.keySet());
            boolean restartRequired = false;
            for (String changedPath : changedPaths) {
                if (isRestartRequired(changedPath)) {
                    restartRequired = true;
                    pendingRestartFields.add(changedPath);
                }
            }

            // 6. Swap config and notify listeners
            configRef.set(newConfig);
            for (ConfigChangeListener listener : listeners) {
                try {
                    listener.onConfigChanged(oldConfig, newConfig);
                } catch (Exception e) {
                    log.warn("Config change listener threw exception", e);
                }
            }

            // 7. Clean up backup
            try {
                Files.deleteIfExists(backup);
            } catch (IOException e) {
                log.debug("Could not delete config backup file: {}", backup, e);
            }

            log.info("Config updated — changedPaths={}, restartRequired={}", changedPaths, restartRequired);
            return ConfigUpdateResult.success(restartRequired, changedPaths);

        } catch (IOException e) {
            return ConfigUpdateResult.error("Failed to update config file: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Register a listener for config change notifications.
     */
    public void addListener(ConfigChangeListener listener) {
        listeners.add(listener);
    }

    /**
     * Returns the set of restart-required TOML paths that have been changed since startup.
     */
    public Set<String> pendingRestartFields() {
        return Set.copyOf(pendingRestartFields);
    }

    private boolean isRestartRequired(String tomlPath) {
        if (RESTART_REQUIRED_PATHS.contains(tomlPath)) {
            return true;
        }
        // Dynamic patterns: agents.primary.bind/port, agents.secondary.*.bind/port
        if (tomlPath.startsWith("agents.") && (tomlPath.endsWith(".bind") || tomlPath.endsWith(".port"))) {
            return true;
        }
        return false;
    }
}
