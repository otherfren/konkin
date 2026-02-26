package io.konkin.web.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Low-resource watcher for landing template/static folders.
 */
public class LandingResourceWatcher {

    private static final Logger log = LoggerFactory.getLogger(LandingResourceWatcher.class);

    private final boolean templateAutoReloadEnabled;
    private final boolean staticAssetsAutoReloadEnabled;
    private final Path templateDirectory;
    private final Path staticDirectory;
    private final Runnable onTemplateChange;
    private final Runnable onStaticChange;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<WatchKey, Path> watchRoots = new HashMap<>();

    private WatchService watchService;
    private Thread watchThread;

    private volatile Instant lastTemplateReload = Instant.EPOCH;
    private volatile Instant lastStaticReload = Instant.EPOCH;

    public LandingResourceWatcher(
            boolean templateAutoReloadEnabled,
            boolean staticAssetsAutoReloadEnabled,
            Path templateDirectory,
            Path staticDirectory,
            Runnable onTemplateChange,
            Runnable onStaticChange
    ) {
        this.templateAutoReloadEnabled = templateAutoReloadEnabled;
        this.staticAssetsAutoReloadEnabled = staticAssetsAutoReloadEnabled;
        this.templateDirectory = templateDirectory.toAbsolutePath().normalize();
        this.staticDirectory = staticDirectory.toAbsolutePath().normalize();
        this.onTemplateChange = onTemplateChange;
        this.onStaticChange = onStaticChange;
    }

    public void start() {
        if (!templateAutoReloadEnabled && !staticAssetsAutoReloadEnabled) {
            return;
        }

        if (!running.compareAndSet(false, true)) {
            return;
        }

        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            if (templateAutoReloadEnabled) {
                registerAll(templateDirectory);
            }
            if (staticAssetsAutoReloadEnabled) {
                registerAll(staticDirectory);
            }
        } catch (IOException e) {
            running.set(false);
            throw new IllegalStateException("Failed to initialize landing resource watcher", e);
        }

        this.watchThread = new Thread(this::watchLoop, "landing-resource-watcher");
        this.watchThread.setDaemon(true);
        this.watchThread.start();

        log.info(
                "Landing resource watcher started (templateAutoReload={}, staticAssetsAutoReload={}, templateDir={}, staticDir={})",
                templateAutoReloadEnabled,
                staticAssetsAutoReloadEnabled,
                templateDirectory,
                staticDirectory
        );
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        if (watchThread != null) {
            watchThread.interrupt();
        }

        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.warn("Failed to close landing resource watcher", e);
            }
        }

        watchRoots.clear();
        log.info("Landing resource watcher stopped");
    }

    private void registerAll(Path root) throws IOException {
        if (!Files.exists(root)) {
            throw new IllegalStateException("Landing resource directory does not exist: " + root);
        }

        try (var paths = Files.walk(root)) {
            paths.filter(Files::isDirectory)
                    .forEach(this::registerDirectory);
        }
    }

    private void registerDirectory(Path dir) {
        try {
            WatchKey key = dir.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
            );
            watchRoots.put(key, dir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to register directory for watching: " + dir, e);
        }
    }

    private void watchLoop() {
        while (running.get()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("Landing resource watcher encountered an error", e);
                }
                return;
            }

            Path watchedDirectory = watchRoots.get(key);
            if (watchedDirectory == null) {
                key.reset();
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                Path changed = watchedDirectory.resolve(pathEvent.context()).toAbsolutePath().normalize();

                if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)) {
                    registerDirectory(changed);
                }

                notifyChange(changed);
            }

            boolean valid = key.reset();
            if (!valid) {
                watchRoots.remove(key);
            }
        }
    }

    private void notifyChange(Path changedPath) {
        if (templateAutoReloadEnabled && changedPath.startsWith(templateDirectory)) {
            if (debounceTemplate()) {
                onTemplateChange.run();
                log.info("Template change detected: {}", changedPath);
            }
            return;
        }

        if (staticAssetsAutoReloadEnabled && changedPath.startsWith(staticDirectory)) {
            if (debounceStatic()) {
                onStaticChange.run();
                log.info("Static asset change detected: {}", changedPath);
            }
        }
    }

    private boolean debounceTemplate() {
        Instant now = Instant.now();
        if (Duration.between(lastTemplateReload, now).toMillis() < 200) {
            return false;
        }
        lastTemplateReload = now;
        return true;
    }

    private boolean debounceStatic() {
        Instant now = Instant.now();
        if (Duration.between(lastStaticReload, now).toMillis() < 200) {
            return false;
        }
        lastStaticReload = now;
        return true;
    }
}
