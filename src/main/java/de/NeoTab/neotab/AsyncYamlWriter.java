package de.NeoTab.neotab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/** Serializes and coalesces YAML writes without blocking the server thread on disk I/O. */
public final class AsyncYamlWriter implements AutoCloseable {
    private static final long BLOCKING_TIMEOUT_SECONDS = 3L;

    private final Logger logger;
    private final ExecutorService executor;
    private final AtomicWriteOperation atomicWriteOperation;
    private final Map<Path, PendingWrite> pendingWrites = new LinkedHashMap<>();
    private final Map<Path, WriteFailure> writeFailures = new LinkedHashMap<>();

    private long submittedSequence;
    private boolean drainScheduled;
    private boolean closed;

    public AsyncYamlWriter(Logger logger) {
        this(logger, AsyncYamlWriter::writeAtomically);
    }

    AsyncYamlWriter(Logger logger, AtomicWriteOperation atomicWriteOperation) {
        this.logger = logger;
        this.atomicWriteOperation = atomicWriteOperation;
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

        Path normalizedTarget = target.toAbsolutePath().normalize();
        PendingWrite pendingWrite = new PendingWrite(contents, ++submittedSequence);
        pendingWrites.put(normalizedTarget, pendingWrite);
        if (!drainScheduled) {
            drainScheduled = true;
            executor.execute(this::drain);
        }
    }

    /**
     * Returns a non-blocking barrier for every write submitted before this call.
     * The future completes exceptionally when the latest persisted snapshot of a
     * target failed, allowing reload callers to keep their old in-memory state.
     */
    public CompletableFuture<Void> flushAsync() {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        synchronized (this) {
            if (executor.isShutdown()) {
                return CompletableFuture.failedFuture(new IllegalStateException("YAML writer is shut down"));
            }

            long barrierSequence = submittedSequence;
            try {
                executor.execute(() -> completeBarrier(completion, barrierSequence));
            } catch (RejectedExecutionException ex) {
                completion.completeExceptionally(ex);
            }
        }
        return completion;
    }

    /**
     * Blocking compatibility helper for tests and bounded shutdown only. Runtime
     * reload paths must compose {@link #flushAsync()} instead of calling this on
     * the Bukkit main thread.
     */
    public void flush() {
        try {
            flushAsync().get(BLOCKING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logger.warning("Interrupted while flushing NeoTab YAML files.");
        } catch (ExecutionException ex) {
            logger.warning("Could not flush NeoTab YAML files: " + failureMessage(ex.getCause()));
        } catch (TimeoutException ex) {
            logger.warning("Timed out after " + BLOCKING_TIMEOUT_SECONDS + " seconds while flushing NeoTab YAML files.");
        }
    }

    /**
     * Stops accepting writes and waits at most three seconds. If storage remains
     * blocked, the orderly daemon executor is left to finish its queued writes
     * instead of interrupting an atomic replacement half-way through.
     */
    @Override
    public void close() {
        CompletableFuture<Void> finalFlush;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            finalFlush = enqueueBarrier(submittedSequence);
            executor.shutdown();
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BLOCKING_TIMEOUT_SECONDS);
        boolean failureLogged = false;
        try {
            finalFlush.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logger.warning("Interrupted while shutting down the NeoTab YAML writer; queued writes continue in the background.");
            failureLogged = true;
        } catch (ExecutionException ex) {
            logger.warning("Could not persist all NeoTab YAML files during shutdown: " + failureMessage(ex.getCause()));
            failureLogged = true;
        } catch (TimeoutException ex) {
            logger.warning(
                "NeoTab YAML writer shutdown exceeded " + BLOCKING_TIMEOUT_SECONDS
                    + " seconds; orderly queued writes continue in the background."
            );
            failureLogged = true;
        }

        if (!Thread.currentThread().isInterrupted()) {
            try {
                if (!executor.awaitTermination(remainingNanos(deadline), TimeUnit.NANOSECONDS) && !failureLogged) {
                    logger.warning(
                        "NeoTab YAML writer shutdown exceeded " + BLOCKING_TIMEOUT_SECONDS
                            + " seconds; orderly queued writes continue in the background."
                    );
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                if (!failureLogged) {
                    logger.warning("Interrupted while shutting down the NeoTab YAML writer; queued writes continue in the background.");
                }
            }
        }
    }

    private CompletableFuture<Void> enqueueBarrier(long barrierSequence) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        try {
            executor.execute(() -> completeBarrier(completion, barrierSequence));
        } catch (RejectedExecutionException ex) {
            completion.completeExceptionally(ex);
        }
        return completion;
    }

    private void completeBarrier(CompletableFuture<Void> completion, long barrierSequence) {
        IOException combinedFailure = null;
        synchronized (this) {
            for (Map.Entry<Path, WriteFailure> entry : writeFailures.entrySet()) {
                WriteFailure failure = entry.getValue();
                if (failure.sequence() > barrierSequence) {
                    continue;
                }
                IOException targetFailure = new IOException(
                    "Could not save " + entry.getKey().getFileName() + ": " + failure.cause().getMessage(),
                    failure.cause()
                );
                if (combinedFailure == null) {
                    combinedFailure = targetFailure;
                } else {
                    combinedFailure.addSuppressed(targetFailure);
                }
            }
        }

        if (combinedFailure == null) {
            completion.complete(null);
        } else {
            completion.completeExceptionally(combinedFailure);
        }
    }

    private void drain() {
        while (true) {
            Map<Path, PendingWrite> batch;
            synchronized (this) {
                if (pendingWrites.isEmpty()) {
                    drainScheduled = false;
                    return;
                }
                batch = new LinkedHashMap<>(pendingWrites);
                pendingWrites.clear();
            }

            ArrayList<Map.Entry<Path, PendingWrite>> orderedBatch = new ArrayList<>(batch.entrySet());
            orderedBatch.sort(Comparator.comparingLong(entry -> entry.getValue().sequence()));
            for (Map.Entry<Path, PendingWrite> entry : orderedBatch) {
                Path target = entry.getKey();
                PendingWrite pendingWrite = entry.getValue();
                try {
                    atomicWriteOperation.write(target, pendingWrite.contents());
                    synchronized (this) {
                        WriteFailure previousFailure = writeFailures.get(target);
                        if (previousFailure != null && previousFailure.sequence() <= pendingWrite.sequence()) {
                            writeFailures.remove(target);
                        }
                    }
                } catch (IOException | RuntimeException ex) {
                    synchronized (this) {
                        writeFailures.put(target, new WriteFailure(pendingWrite.sequence(), ex));
                    }
                    logger.warning("Could not save " + target.getFileName() + ": " + ex.getMessage());
                }
            }
        }
    }

    private static void writeAtomically(Path target, String contents) throws IOException {
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
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                ex.addSuppressed(cleanupFailure);
            }
            throw ex;
        }
    }

    private static long remainingNanos(long deadline) {
        return Math.max(1L, deadline - System.nanoTime());
    }

    private static String failureMessage(Throwable failure) {
        if (failure == null) {
            return "unknown failure";
        }
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    @FunctionalInterface
    interface AtomicWriteOperation {
        void write(Path target, String contents) throws IOException;
    }

    private record PendingWrite(String contents, long sequence) {
    }

    private record WriteFailure(long sequence, Throwable cause) {
    }
}
