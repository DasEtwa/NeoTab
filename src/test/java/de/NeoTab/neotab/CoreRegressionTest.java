package de.NeoTab.neotab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CoreRegressionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void asyncYamlWriterCoalescesToLatestSnapshotAndFlushes() throws Exception {
        Path target = temporaryDirectory.resolve("config.yml");
        try (AsyncYamlWriter writer = new AsyncYamlWriter(Logger.getAnonymousLogger())) {
            writer.write(target, "value: first\n");
            writer.write(target, "value: latest\n");
            writer.flush();
        }
        assertEquals("value: latest\n", Files.readString(target));
        assertFalse(Files.exists(temporaryDirectory.resolve("config.yml.tmp")));
    }

    @Test
    void versionComparisonKeepsStableAbovePrereleaseAndOrdersBetaNumbers() {
        assertTrue(UpdateChecker.compareVersions("1.3.2", "1.3.2-Beta.9") > 0);
        assertTrue(UpdateChecker.compareVersions("1.3.2-Beta.10", "1.3.2-Beta.2") > 0);
        assertEquals(0, UpdateChecker.compareVersions("v1.3.2", "1.3.2"));
    }

    @Test
    void timerDurationParserRejectsOverflowAndInvalidUnits() {
        assertEquals(300, ActionBarTimerService.parseDurationSeconds("5m"));
        assertEquals(3600, ActionBarTimerService.parseDurationSeconds("1h"));
        assertEquals(-1, ActionBarTimerService.parseDurationSeconds("25h"));
        assertEquals(-1, ActionBarTimerService.parseDurationSeconds("10d"));
    }

    @Test
    void regionBoundsRemainCanonicalForReverseSelections() {
        RegionProfile profile = new RegionProfile("spawn", true, "world", 20, 80, 30, 10, 60, -5, 1, "default", "default");
        assertEquals(10, profile.minX());
        assertEquals(20, profile.maxX());
        assertEquals(60, profile.minY());
        assertEquals(80, profile.maxY());
        assertEquals(-5, profile.minZ());
        assertEquals(30, profile.maxZ());
    }
}
