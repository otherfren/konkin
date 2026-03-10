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

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;

/**
 * Applies and reloads Logback logging configuration from {@link KonkinConfig}.
 * Implements {@link ConfigChangeListener} so log level changes take effect at runtime.
 */
public class LoggingConfigurator implements ConfigChangeListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingConfigurator.class);

    public static void applyLoggingConfig(KonkinConfig config) {
        System.setProperty("LOG_LEVEL", config.logLevel().toUpperCase());
        System.setProperty("LOG_FILE", config.logFile());
        System.setProperty("LOG_ROTATE_MAX_SIZE_MB", Integer.toString(config.logRotateMaxSizeMb()));

        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext loggerContext)) {
            return;
        }

        URL logbackConfig = LoggingConfigurator.class.getClassLoader().getResource("logback.xml");
        if (logbackConfig == null) {
            return;
        }

        try {
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(loggerContext);
            loggerContext.reset();
            configurator.doConfigure(logbackConfig);
            log.info("Logging configured (level={}, file={}, rotateMaxSizeMb={})",
                    config.logLevel(),
                    config.logFile(),
                    config.logRotateMaxSizeMb());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply logback configuration from config.toml", e);
        }
    }

    @Override
    public void onConfigChanged(KonkinConfig oldConfig, KonkinConfig newConfig) {
        boolean logLevelChanged = !oldConfig.logLevel().equalsIgnoreCase(newConfig.logLevel());
        boolean logFileChanged = !oldConfig.logFile().equals(newConfig.logFile());
        boolean rotateChanged = oldConfig.logRotateMaxSizeMb() != newConfig.logRotateMaxSizeMb();

        if (logLevelChanged || logFileChanged || rotateChanged) {
            log.info("Logging config changed — reapplying (level={} → {}, file={} → {}, rotateMaxSizeMb={} → {})",
                    oldConfig.logLevel(), newConfig.logLevel(),
                    oldConfig.logFile(), newConfig.logFile(),
                    oldConfig.logRotateMaxSizeMb(), newConfig.logRotateMaxSizeMb());
            applyLoggingConfig(newConfig);
        }
    }
}
