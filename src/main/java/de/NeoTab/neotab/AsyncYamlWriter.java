package de.NeoTab.neotab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Serializes and coalesces YAML writes without blocking the server thread on disk I/O. */
public final class AsyncYamlWriter implements AutoCloseable {
    private final Logger logger;
    private final ExecutorService executor;
    private final Map<Path, String> pendingWrites = new LinkedHashMap<>();
    private boolean drainScheduled;
    private boolean closed;

    public AsyncYamlWriter(Logger logger) {
        this.logger = logger;
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "NeoTab-YamlWriter");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void write(Path target, String contents) {
        if (closed) {
            throw new IllegalStateException("YAML writer is closed");
        }
        pendingWrites.put(target.toAbsolutePath().normalize(), contents);
        if (!drainScheduled) {
            drainScheduled = true;
            executor.execute(this::drain);
        }
    }

    public void flush() {
        Future<?> barrier;
        synchronized (this) {
            if (closed && executor.isShutdown()) {
                return;
            }
            barrier = executor.submit(() -> {
            });
        }
        try {
            barrier.get(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            logger.warning("Timed out while flushing NeoTab YAML files: " + ex.getMessage());
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        flush();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("NeoTab YAML writer did not stop within 10 seconds.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void drain() {
        while (true) {
            Map<Path, String> batch;
            synchronized (this) {
                if (pendingWrites.isEmpty()) {
                    drainScheduled = false;
                    return;
                }
                batch = new LinkedHashMap<>(pendingWrites);
                pendingWrites.clear();
            }
            for (Map.Entry<Path, String> entry : batch.entrySet()) {
                writeAtomically(entry.getKey(), entry.getValue());
            }
        }
    }

    private void writeAtomically(Path target, String contents) {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            logger.warning("Could not save " + target.getFileName() + ": " + ex.getMessage());
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
    }
}
