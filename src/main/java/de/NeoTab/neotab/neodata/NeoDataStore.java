package de.NeoTab.neotab.neodata;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.UnaryOperator;

/** Experimental M1 storage core. All operations, including initialization, run on one worker. */
public final class NeoDataStore implements AutoCloseable {
    private final Path directory;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NeoData-storage"); t.setDaemon(true); return t;
    });
    private FileChannel lockChannel;
    private FileLock lock;
    private final CompletableFuture<Void> ready;
    private boolean closed;

    public NeoDataStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        ready = CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(this.directory);
                lockChannel = FileChannel.open(this.directory.resolve(".host.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                lock = lockChannel.tryLock();
                if (lock == null) throw new IOException("NeoData directory is already locked");
            } catch (Exception e) {
                try { if (lockChannel != null) lockChannel.close(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
                throw new CompletionException(e);
            }
        }, worker);
    }

    public CompletableFuture<Void> ready() { return ready; }
    public CompletableFuture<Optional<String>> get(String namespace, String key) {
        return submit(() -> Optional.ofNullable(read(namespace).get(valid(key))));
    }
    public CompletableFuture<String> put(String namespace, String key, String value) {
        Objects.requireNonNull(value, "value");
        return update(namespace, key, ignored -> value);
    }
    /** A null result deletes the key. Completion means the file replacement succeeded. */
    public CompletableFuture<String> update(String namespace, String key, UnaryOperator<String> operation) {
        Objects.requireNonNull(operation, "operation");
        return submit(() -> {
            String checkedKey = valid(key);
            Map<String, String> data = read(namespace);
            String result = operation.apply(data.get(checkedKey));
            if (result == null) data.remove(checkedKey); else data.put(checkedKey, result);
            write(namespace, data);
            return result;
        });
    }
    private synchronized <T> CompletableFuture<T> submit(Callable<T> operation) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("NeoData is closed"));
        return CompletableFuture.supplyAsync(() -> {
            ready.join();
            try { return operation.call(); } catch (Exception e) { throw new CompletionException(e); }
        }, worker);
    }
    private static String valid(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid namespace or key");
        return value;
    }
    private Path path(String namespace) { return directory.resolve(valid(namespace) + ".properties"); }
    private Map<String, String> read(String namespace) throws IOException {
        Path file = path(namespace);
        Properties props = new Properties();
        if (Files.exists(file)) try (Reader reader = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) { props.load(reader); }
        Map<String, String> result = new TreeMap<>();
        for (String key : props.stringPropertyNames()) result.put(key, props.getProperty(key));
        return result;
    }
    private void write(String namespace, Map<String, String> values) throws IOException {
        Path target = path(namespace), temporary = Files.createTempFile(directory, ".neodata-", ".tmp");
        try {
            Properties props = new Properties(); props.putAll(values);
            try (Writer out = Files.newBufferedWriter(temporary, java.nio.charset.StandardCharsets.UTF_8)) { props.store(out, "NeoData M1"); }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { throw new IOException("Atomic replacement unavailable", e); }
        } finally { Files.deleteIfExists(temporary); }
    }
    /** Stop accepting work and release the lock after already queued work. */
    public synchronized CompletableFuture<Void> shutdown() {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("NeoData is closed"));
        closed = true;
        CompletableFuture<Void> done = CompletableFuture.runAsync(() -> {
            try { if (lock != null) lock.release(); if (lockChannel != null && lockChannel.isOpen()) lockChannel.close(); }
            catch (IOException e) { throw new CompletionException(e); }
            ready.join();
        }, worker);
        done.whenComplete((v, e) -> worker.shutdown());
        return done;
    }
    @Override public void close() { shutdown(); }
}
