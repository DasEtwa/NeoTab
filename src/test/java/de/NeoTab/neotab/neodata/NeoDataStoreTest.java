package de.NeoTab.neotab.neodata;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NeoDataStoreTest {
    @TempDir Path directory;
    @Test void persistsIsolatedNamespacesAndSerializesUpdates() {
        NeoDataStore store = new NeoDataStore(directory);
        store.ready().join();
        store.put("neotab", "count", "0").join();
        var updates = new ArrayList<CompletableFuture<String>>();
        for (int i = 0; i < 100; i++) updates.add(store.update("neotab", "count", old -> Integer.toString(Integer.parseInt(old) + 1)));
        CompletableFuture.allOf(updates.toArray(CompletableFuture[]::new)).join();
        assertEquals("100", store.get("neotab", "count").join().orElseThrow());
        assertTrue(store.get("neotalk", "count").join().isEmpty());
        store.shutdown().join();
        NeoDataStore reopened = new NeoDataStore(directory);
        assertEquals("100", reopened.get("neotab", "count").join().orElseThrow());
        reopened.shutdown().join();
    }
    @Test void duplicateOwnerIsRejectedAndLockIsReleased() {
        NeoDataStore first = new NeoDataStore(directory); first.ready().join();
        NeoDataStore second = new NeoDataStore(directory);
        assertThrows(CompletionException.class, () -> second.ready().join());
        assertThrows(CompletionException.class, () -> second.shutdown().join());
        first.shutdown().join();
        NeoDataStore third = new NeoDataStore(directory); third.ready().join(); third.shutdown().join();
    }
    @Test void invalidPathsAndFailedInitializationAreReported() throws Exception {
        NeoDataStore store = new NeoDataStore(directory); store.ready().join();
        assertThrows(CompletionException.class, () -> store.put("../escape", "key", "value").join());
        store.shutdown().join();
        Path file = directory.resolve("file"); java.nio.file.Files.writeString(file, "x");
        NeoDataStore failed = new NeoDataStore(file);
        assertThrows(CompletionException.class, () -> failed.ready().join());
        assertThrows(CompletionException.class, () -> failed.shutdown().join());
    }
}
