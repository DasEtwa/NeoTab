package de.NeoTab.neotab;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class NeoTabMetricsTest {
    @Test
    void languageNormalizationOnlyExposesStableBuckets() {
        assertEquals("de", NeoTabMetrics.normalizeLanguage("de"));
        assertEquals("de", NeoTabMetrics.normalizeLanguage("de_DE"));
        assertEquals("de", NeoTabMetrics.normalizeLanguage("deutsch"));
        assertEquals("de", NeoTabMetrics.normalizeLanguage("german"));
        assertEquals("en", NeoTabMetrics.normalizeLanguage("en-US"));
        assertEquals("en", NeoTabMetrics.normalizeLanguage("englisch"));
        assertEquals("other", NeoTabMetrics.normalizeLanguage("fr"));
        assertEquals("other", NeoTabMetrics.normalizeLanguage(null));
        assertEquals("other", NeoTabMetrics.normalizeLanguage(42));
    }

    @Test
    void platformNormalizationHandlesSupportedServerFamilies() {
        assertEquals("paper", NeoTabMetrics.normalizePlatform("Paper", "1.21.11"));
        assertEquals("spigot", NeoTabMetrics.normalizePlatform("CraftBukkit", "4598-Spigot-d74c5d8"));
        assertEquals("craftbukkit", NeoTabMetrics.normalizePlatform("CraftBukkit", "4598-Bukkit-d74c5d8"));
        assertEquals("other", NeoTabMetrics.normalizePlatform("UnknownFork", "custom"));
        assertEquals("other", NeoTabMetrics.normalizePlatform(null, null));
    }

    @Test
    void animationCountsAreGroupedWithoutSendingExactValues() {
        assertEquals("none", NeoTabMetrics.bucketAnimationCount(-1));
        assertEquals("none", NeoTabMetrics.bucketAnimationCount(0));
        assertEquals("1-5", NeoTabMetrics.bucketAnimationCount(1));
        assertEquals("1-5", NeoTabMetrics.bucketAnimationCount(5));
        assertEquals("6-10", NeoTabMetrics.bucketAnimationCount(6));
        assertEquals("6-10", NeoTabMetrics.bucketAnimationCount(10));
        assertEquals("11_plus", NeoTabMetrics.bucketAnimationCount(11));
    }

    @Test
    void missingConfigUsesBackwardsCompatibleDefaults() {
        YamlConfiguration config = new YamlConfiguration();
        assertTrue(NeoTabMetrics.isMetricsEnabled(config));

        NeoTabMetrics.MetricsSnapshot snapshot = NeoTabMetrics.createSnapshot(config, "Paper", "Paper 1.20.6", Set.of());
        assertEquals("en", snapshot.configuredLanguage());
        assertEquals("disabled", snapshot.scoreboardEnabled());
        assertEquals("enabled", snapshot.headerEnabled());
        assertEquals("enabled", snapshot.footerEnabled());
        assertEquals("enabled", snapshot.ramDisplayEnabled());
        assertEquals("enabled", snapshot.pingDisplayEnabled());
        assertEquals("enabled", snapshot.averageDisplayEnabled());
        assertEquals("disabled", snapshot.afkFeatureEnabled());
        assertEquals("enabled", snapshot.updateCheckerEnabled());
        assertEquals("1-5", snapshot.animationCount());
    }

    @Test
    void invalidBStatsIdNeverStartsMetrics() {
        YamlConfiguration config = new YamlConfiguration();
        assertFalse(NeoTabMetrics.isValidPluginId(-1));
        assertFalse(NeoTabMetrics.isValidPluginId(0));
        assertTrue(NeoTabMetrics.isValidPluginId(1));
        assertFalse(NeoTabMetrics.shouldStartMetrics(config, -1));
        assertFalse(NeoTabMetrics.shouldStartMetrics(config, 0));
        assertTrue(NeoTabMetrics.shouldStartMetrics(config, 1));
    }

    @Test
    void localMetricsOptOutPreventsStartup() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("metrics.enabled", false);
        assertFalse(NeoTabMetrics.isMetricsEnabled(config));
        assertFalse(NeoTabMetrics.shouldStartMetrics(config, 12345));
    }

    @Test
    void reloadStartsAndStopsMetricsWhenTheConfiguredStateChanges() {
        assertEquals(
            NeoTabMetrics.MetricsReloadAction.START,
            NeoTabMetrics.reloadAction(true, false)
        );
        assertEquals(
            NeoTabMetrics.MetricsReloadAction.STOP,
            NeoTabMetrics.reloadAction(false, true)
        );
        assertEquals(
            NeoTabMetrics.MetricsReloadAction.NONE,
            NeoTabMetrics.reloadAction(true, true)
        );
        assertEquals(
            NeoTabMetrics.MetricsReloadAction.NONE,
            NeoTabMetrics.reloadAction(false, false)
        );
    }

    @Test
    void nullAndMalformedConfigValuesFallBackSafely() {
        assertTrue(NeoTabMetrics.isMetricsEnabled(null));
        assertDoesNotThrow(() -> NeoTabMetrics.createSnapshot(null, null, null, null));

        YamlConfiguration malformed = new YamlConfiguration();
        malformed.set("metrics.enabled", List.of("false"));
        malformed.set("language", 42);
        malformed.set("server-name", List.of("not", "a", "string"));
        malformed.set("ram-format", 42);
        malformed.set("scoreboard.enabled", "not-a-boolean");
        malformed.set("update-checker.enabled", "also-invalid");
        malformed.set("tab-profiles", "not-a-section");

        assertTrue(NeoTabMetrics.isMetricsEnabled(malformed));
        NeoTabMetrics.MetricsSnapshot snapshot = NeoTabMetrics.createSnapshot(
            malformed,
            null,
            null,
            new HashSet<>(Arrays.asList("LuckPerms", "PlaceholderAPI", "Geyser-Spigot", null))
        );
        assertEquals("en", snapshot.configuredLanguage());
        assertEquals("disabled", snapshot.scoreboardEnabled());
        assertEquals("enabled", snapshot.headerEnabled());
        assertEquals("enabled", snapshot.footerEnabled());
        assertEquals("installed", snapshot.luckPermsStatus());
        assertEquals("installed", snapshot.placeholderApiStatus());
        assertEquals("installed", snapshot.geyserStatus());
        assertEquals("other", snapshot.serverPlatform());
    }

    @Test
    void featureAndPluginStatesAreReducedToPrivacySafeValues() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("language", "de-DE");
        config.set("server-name", "");
        config.set("ram-format", "plain footer without technical placeholders");
        config.set("scoreboard.enabled", true);
        config.set("update-checker.enabled", false);

        NeoTabMetrics.MetricsSnapshot snapshot = NeoTabMetrics.createSnapshot(
            config,
            "CraftBukkit",
            "4598-Spigot-d74c5d8",
            Set.of("LuckPerms", "PlaceholderAPI", "Geyser-Spigot")
        );
        assertEquals("de", snapshot.configuredLanguage());
        assertEquals("enabled", snapshot.scoreboardEnabled());
        assertEquals("disabled", snapshot.headerEnabled());
        assertEquals("enabled", snapshot.footerEnabled());
        assertEquals("disabled", snapshot.ramDisplayEnabled());
        assertEquals("disabled", snapshot.pingDisplayEnabled());
        assertEquals("disabled", snapshot.averageDisplayEnabled());
        assertEquals("disabled", snapshot.updateCheckerEnabled());
        assertEquals("installed", snapshot.luckPermsStatus());
        assertEquals("installed", snapshot.placeholderApiStatus());
        assertEquals("installed", snapshot.geyserStatus());
        assertEquals("spigot", snapshot.serverPlatform());
    }
}
