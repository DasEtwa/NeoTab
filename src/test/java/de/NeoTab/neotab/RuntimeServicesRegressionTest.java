package de.NeoTab.neotab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeServicesRegressionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void actionBarOnlyResendsChangedOrKeepAliveMessages() {
        ActionBarMessage original = new ActionBarMessage("timer", Component.text("00:10"), 100, 10_000L);
        ActionBarService.DeliveredMessage delivered =
            new ActionBarService.DeliveredMessage("timer", Component.text("00:10"), 1_000L);

        assertTrue(ActionBarService.shouldSend(null, original, 1_000L, 2_000L));
        assertFalse(ActionBarService.shouldSend(delivered, original, 2_999L, 2_000L));
        assertTrue(ActionBarService.shouldSend(delivered, original, 3_000L, 2_000L));
        assertTrue(ActionBarService.shouldSend(
            delivered,
            new ActionBarMessage("timer", Component.text("00:09"), 100, 10_000L),
            1_001L,
            2_000L
        ));
        assertTrue(ActionBarService.shouldSend(
            delivered,
            new ActionBarMessage("stopwatch", Component.text("00:10"), 100, 10_000L),
            1_001L,
            2_000L
        ));
    }

    @Test
    void flushAsyncReturnsImmediatelyAndCompletesAfterPriorWrite() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        AsyncYamlWriter.AtomicWriteOperation blockingWrite = (target, contents) -> {
            writeStarted.countDown();
            try {
                if (!releaseWrite.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("test write was not released");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("test write interrupted", ex);
            }
        };

        try (AsyncYamlWriter writer = new AsyncYamlWriter(Logger.getAnonymousLogger(), blockingWrite)) {
            writer.write(temporaryDirectory.resolve("config.yml"), "value: pending\n");
            assertTrue(writeStarted.await(1, TimeUnit.SECONDS));

            long startedAt = System.nanoTime();
            CompletableFuture<Void> barrier = writer.flushAsync();
            long callMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertTrue(callMillis < 250L, "flushAsync must not wait for disk I/O");
            assertFalse(barrier.isDone());
            releaseWrite.countDown();
            barrier.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentWritesRemainOrderedAndLatestSnapshotWins() throws Exception {
        Path target = temporaryDirectory.resolve("regions.yml");
        ExecutorService callers = Executors.newFixedThreadPool(4);
        try (AsyncYamlWriter writer = new AsyncYamlWriter(Logger.getAnonymousLogger())) {
            CountDownLatch start = new CountDownLatch(1);
            Future<?>[] writes = new Future<?>[24];
            for (int i = 0; i < writes.length; i++) {
                int value = i;
                writes[i] = callers.submit(() -> {
                    start.await();
                    writer.write(target, "value: " + value + "\n");
                    return null;
                });
            }
            start.countDown();
            for (Future<?> write : writes) {
                write.get(1, TimeUnit.SECONDS);
            }

            writer.write(target, "value: final\n");
            writer.flushAsync().get(2, TimeUnit.SECONDS);
        } finally {
            callers.shutdownNow();
        }

        assertEquals("value: final\n", Files.readString(target));
        assertFalse(Files.exists(temporaryDirectory.resolve("regions.yml.tmp")));
    }

    @Test
    void coalescedWritesPreserveSubmissionOrderAcrossTargets() throws Exception {
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        List<String> persistedOrder = new CopyOnWriteArrayList<>();
        Path blocker = temporaryDirectory.resolve("blocker.yml").toAbsolutePath().normalize();

        AsyncYamlWriter.AtomicWriteOperation orderedWrite = (target, contents) -> {
            persistedOrder.add(contents.trim());
            if (target.equals(blocker)) {
                blockerStarted.countDown();
                try {
                    if (!releaseBlocker.await(2, TimeUnit.SECONDS)) {
                        throw new IOException("test blocker was not released");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("test blocker interrupted", ex);
                }
            }
        };

        try (AsyncYamlWriter writer = new AsyncYamlWriter(Logger.getAnonymousLogger(), orderedWrite)) {
            writer.write(blocker, "C0\n");
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));
            writer.write(temporaryDirectory.resolve("a.yml"), "A1\n");
            writer.write(temporaryDirectory.resolve("b.yml"), "B2\n");
            writer.write(temporaryDirectory.resolve("a.yml"), "A3\n");
            releaseBlocker.countDown();
            writer.flushAsync().get(2, TimeUnit.SECONDS);
        }

        assertEquals(List.of("C0", "B2", "A3"), persistedOrder);
    }

    @Test
    void flushAsyncReportsPersistenceFailures() throws Exception {
        AsyncYamlWriter writer = new AsyncYamlWriter(
            Logger.getAnonymousLogger(),
            (target, contents) -> {
                throw new IOException("disk unavailable");
            }
        );
        try {
            writer.write(temporaryDirectory.resolve("messages.yml"), "message: test\n");
            ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> writer.flushAsync().get(1, TimeUnit.SECONDS)
            );
            assertTrue(failure.getCause().getMessage().contains("messages.yml"));
        } finally {
            writer.close();
        }
    }

    @Test
    void writerShutdownPersistsQueuedDataAndRejectsNewWrites() throws Exception {
        Path target = temporaryDirectory.resolve("shutdown.yml");
        AsyncYamlWriter writer = new AsyncYamlWriter(Logger.getAnonymousLogger());
        writer.write(target, "state: final\n");

        writer.close();

        assertEquals("state: final\n", Files.readString(target));
        assertThrows(IllegalStateException.class, () -> writer.write(target, "state: too-late\n"));
        writer.close();
    }

    @Test
    void advancementRecountTracksGrantRevokeAndRepeatedGrantExactly() {
        List<String> advancements = List.of("story/root", "story/mine_stone");
        Set<String> completed = new HashSet<>();
        UUID playerId = UUID.randomUUID();
        AdvancementCounterModule.CompletionCountCache cache = new AdvancementCounterModule.CompletionCountCache();

        assertEquals(0, cache.reconcile(playerId, advancements, completed::contains));
        completed.add("story/root");
        assertEquals(1, cache.reconcile(playerId, advancements, completed::contains));
        assertEquals(1, cache.get(playerId));
        completed.remove("story/root");
        assertEquals(0, cache.reconcile(playerId, advancements, completed::contains));
        assertEquals(0, cache.get(playerId));
        completed.add("story/root");
        completed.add("story/root");
        assertEquals(1, cache.reconcile(playerId, advancements, completed::contains));
        assertEquals(1, cache.get(playerId));
    }

    @Test
    void animationKeepsUnicodeGlyphsIntact() {
        String combined = "A😀öe\u0301👩‍💻🇩🇪";
        assertEquals(List.of("A", "😀", "ö", "e\u0301", "👩‍💻", "🇩🇪"), AnimationUtils.unicodeGlyphs(combined));

        String rendered = AnimationUtils.buildLegacyText(
            combined,
            List.of(TextColor.color(0xAA00AA), TextColor.color(0x55FFFF)),
            AnimationUtils.Style.GRADIENT_WAVE,
            3,
            true
        );
        assertTrue(rendered.contains("😀"));
        assertTrue(rendered.contains("ö"));
        assertTrue(rendered.contains("e\u0301"));
        assertTrue(rendered.contains("👩‍💻"));
        assertTrue(rendered.contains("🇩🇪"));
    }

    @Test
    void animationPreservesLegacyFormattingTokensAtomically() {
        String legacyHex = "\u00A7x\u00A7A\u00A7A\u00A70\u00A70\u00A7A\u00A7A";
        String rendered = AnimationUtils.buildLegacyText(
            "\u00A7lHi" + legacyHex + "😀\u00A7r!",
            List.of(TextColor.color(0xAA00AA), TextColor.color(0x55FFFF)),
            AnimationUtils.Style.STATIC,
            0,
            false
        );

        assertEquals(List.of("H", "i", "😀", "!"), AnimationUtils.unicodeGlyphs("\u00A7lHi" + legacyHex + "😀\u00A7r!"));
        assertTrue(rendered.contains("\u00A7lH"));
        assertTrue(rendered.contains("\u00A7li"));
        assertTrue(rendered.contains(legacyHex + "😀"));
    }
}
