package com.aiproxyoauth.server;

import com.aiproxyoauth.util.ApiKeyUtils;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe, hot-reloadable API key store.
 *
 * Inline keys (from --api-key) are immutable for the process lifetime.
 * File keys (from --api-keys-file) are reloaded on WatchService events or
 * lazily when a 401 is issued and the file timestamp has advanced.
 *
 * Both sources are merged into a single Snapshot via AtomicReference,
 * so ProxyServer always sees a consistent (keys, adminKey) pair.
 */
public class ApiKeyStore {

    private record Snapshot(Map<String, String> keys, String adminKey) {}

    private final Map<String, String> inlineKeys;
    private final String filePath;
    private final String cliAdminKey;
    private final AtomicReference<Snapshot> snapshot;
    private volatile FileTime lastModified = FileTime.fromMillis(0);
    private volatile Thread watchThread;

    public ApiKeyStore(Map<String, String> inlineKeys, String filePath, String cliAdminKey) {
        this.inlineKeys = Map.copyOf(inlineKeys);
        this.filePath = (filePath != null && !filePath.isBlank()) ? filePath : null;
        this.cliAdminKey = cliAdminKey;
        this.snapshot = new AtomicReference<>(buildSnapshot(inlineKeys, Map.of()));
    }

    /** Returns the key owner name, or null if not found. */
    public String lookup(String key) {
        return snapshot.get().keys().get(key);
    }

    /** Returns the current admin key, or null if none configured. */
    public String adminKey() {
        return snapshot.get().adminKey();
    }

    /** True when any key enforcement is active (keys or admin key present). */
    public boolean isEnforcing() {
        Snapshot s = snapshot.get();
        return !s.keys().isEmpty() || s.adminKey() != null;
    }

    /** Exposed for testing: the file timestamp at the time of last successful load. */
    public FileTime lastModified() {
        return lastModified;
    }

    /**
     * Re-reads the keys file and atomically swaps the live snapshot.
     * On error or empty result, logs a warning and keeps the existing snapshot.
     */
    public void reload() {
        if (filePath == null) return;
        try {
            Map<String, String> fileKeys = new HashMap<>();
            for (String line : Files.readAllLines(Path.of(filePath))) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    ApiKeyUtils.parseKeyEntry(trimmed, fileKeys);
                }
            }
            if (fileKeys.isEmpty()) {
                System.err.println("Warning: Reloaded API keys file is empty — keeping existing keys");
                return;
            }
            Snapshot next = buildSnapshot(inlineKeys, fileKeys);
            snapshot.set(next);
            lastModified = Files.getLastModifiedTime(Path.of(filePath));
            System.out.println("INFO: Reloaded " + next.keys().size() + " API key(s) from " + filePath);
        } catch (IOException e) {
            System.err.println("Warning: Failed to reload API keys from " + filePath + ": " + e.getMessage());
        }
    }

    /**
     * Reloads only when the file's last-modified timestamp is newer than the last
     * successful load. Called on every 401 as a backstop for missed WatchService events.
     */
    public void reloadIfFileChanged() {
        if (filePath == null) return;
        try {
            FileTime current = Files.getLastModifiedTime(Path.of(filePath));
            if (current.compareTo(lastModified) > 0) {
                reload();
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Starts a virtual thread that watches the keys file's parent directory for
     * ENTRY_MODIFY events. No-op if no file path is configured or the directory
     * does not exist.
     */
    public void startWatching() {
        if (filePath == null) return;
        Path path = Path.of(filePath);
        Path dir = path.getParent();
        if (dir == null || !Files.exists(dir)) {
            System.err.println("Warning: Cannot watch API keys file directory: " + dir);
            return;
        }
        watchThread = Thread.ofVirtual().start(() -> {
            try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
                dir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY);
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = watcher.take();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changed = (Path) event.context();
                        if (path.getFileName().equals(changed)) {
                            reload();
                        }
                    }
                    key.reset();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                System.err.println("Warning: API keys file watcher failed: " + e.getMessage());
            }
        });
    }

    /** Interrupts the WatchService thread. Safe to call if watching was never started. */
    public void stopWatching() {
        if (watchThread != null) {
            watchThread.interrupt();
        }
    }

    private Snapshot buildSnapshot(Map<String, String> inline, Map<String, String> fileKeys) {
        Map<String, String> merged = new HashMap<>(inline);
        merged.putAll(fileKeys);

        String adminKey = cliAdminKey;
        if (adminKey == null) {
            for (Map.Entry<String, String> entry : merged.entrySet()) {
                if ("admin".equalsIgnoreCase(entry.getValue())) {
                    adminKey = entry.getKey();
                    break;
                }
            }
        }
        if (adminKey != null) {
            merged.remove(adminKey);
        }
        return new Snapshot(Map.copyOf(merged), adminKey);
    }
}
