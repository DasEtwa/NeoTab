package de.NeoTab.neotab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CoreRegressionTest {
    private static final Pattern MESSAGE_PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");

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
        assertTrue(UpdateChecker.compareVersions("1.3.3", "1.3.3-Beta.9") > 0);
        assertTrue(UpdateChecker.compareVersions("1.3.3-Beta.10", "1.3.3-Beta.2") > 0);
        assertEquals(0, UpdateChecker.compareVersions("v1.3.3", "1.3.3"));
    }

    @Test
    void minecraftVersionIsDerivedFromBukkitVersionWithoutPaperMetadata() {
        assertEquals("1.20.6", UpdateChecker.minecraftVersionFromBukkitVersion("1.20.6-R0.1-SNAPSHOT"));
        assertEquals("26.2", UpdateChecker.minecraftVersionFromBukkitVersion("26.2-R0.1-SNAPSHOT"));
    }

    @Test
    void languageParserAcceptsEnglishAndGermanAliases() {
        assertEquals(ConfigManager.Language.ENGLISH, ConfigManager.Language.parse("English"));
        assertEquals(ConfigManager.Language.ENGLISH, ConfigManager.Language.parse("englisch"));
        assertEquals(ConfigManager.Language.ENGLISH, ConfigManager.Language.parse("en-US"));
        assertEquals(ConfigManager.Language.GERMAN, ConfigManager.Language.parse("gErMaN"));
        assertEquals(ConfigManager.Language.GERMAN, ConfigManager.Language.parse("deutsch"));
        assertEquals(ConfigManager.Language.GERMAN, ConfigManager.Language.parse("de_DE"));
        assertEquals(null, ConfigManager.Language.parse("français"));

        assertEquals("Localized", ConfigManager.selectLocalizedDefault("Default", "Default", "Localized"));
        assertEquals("Localized", ConfigManager.selectLocalizedDefault(null, "Default", "Localized"));
        assertEquals("Custom", ConfigManager.selectLocalizedDefault("Custom", "Default", "Localized"));
        assertEquals("Default", ConfigManager.selectLocalizedDefault(null, "Default", null));
    }

    @Test
    void languageResourcesKeepKeysTypesPlaceholdersAndMiniMessageInSync() throws Exception {
        YamlConfiguration english = loadMessageResource("messages.yml");
        YamlConfiguration german = loadMessageResource("messages_de.yml");
        Set<String> englishKeys = leafKeys(english);
        Set<String> germanKeys = leafKeys(german);
        assertEquals(englishKeys, germanKeys);

        MiniMessage miniMessage = MiniMessage.miniMessage();
        for (String key : englishKeys) {
            Object englishValue = english.get(key);
            Object germanValue = german.get(key);
            assertNotNull(englishValue, "Missing English value for " + key);
            assertNotNull(germanValue, "Missing German value for " + key);
            assertEquals(englishValue.getClass(), germanValue.getClass(), "Value type mismatch for " + key);

            if (englishValue instanceof String englishText && germanValue instanceof String germanText) {
                assertEquals(placeholders(englishText), placeholders(germanText), "Placeholder mismatch for " + key);
                assertDoesNotThrow(() -> miniMessage.deserialize(englishText), "Invalid English MiniMessage for " + key);
                assertDoesNotThrow(() -> miniMessage.deserialize(germanText), "Invalid German MiniMessage for " + key);
                continue;
            }

            assertTrue(englishValue instanceof List<?>, "Unsupported English value type for " + key);
            List<?> englishList = (List<?>) englishValue;
            List<?> germanList = (List<?>) germanValue;
            assertEquals(englishList.size(), germanList.size(), "List length mismatch for " + key);
            for (int index = 0; index < englishList.size(); index++) {
                assertTrue(englishList.get(index) instanceof String, "Non-string English list item for " + key);
                assertTrue(germanList.get(index) instanceof String, "Non-string German list item for " + key);
                String englishText = (String) englishList.get(index);
                String germanText = (String) germanList.get(index);
                assertEquals(placeholders(englishText), placeholders(germanText), "Placeholder mismatch for " + key + "." + index);
                assertDoesNotThrow(() -> miniMessage.deserialize(englishText), "Invalid English MiniMessage for " + key + "." + index);
                assertDoesNotThrow(() -> miniMessage.deserialize(germanText), "Invalid German MiniMessage for " + key + "." + index);
            }
        }
    }

    @Test
    void optionalLuckPermsTypesDoNotLeakIntoCoreListenerMetadata() {
        Method[] listenerMethods = assertDoesNotThrow(NeoTab.class::getDeclaredMethods);
        assertFalse(Arrays.stream(listenerMethods).anyMatch(CoreRegressionTest::referencesLuckPerms));

        Method[] updaterMethods = assertDoesNotThrow(TabUpdater.class::getDeclaredMethods);
        assertFalse(Arrays.stream(updaterMethods).anyMatch(CoreRegressionTest::referencesLuckPerms));
    }

    @Test
    void craftBukkitActionBarPrefersPublicPacketSender() throws Exception {
        assertEquals(
            "sendPacket",
            PlatformBridge.findPacketSendMethod(PacketConnectionStub.class, PacketStub.class).getName()
        );
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

    @Test
    void scoreboardOwnershipIsReevaluatedAfterTemporaryTakeover() {
        assertEquals(
            ScoreboardService.ScoreboardClaimAction.WAIT_FOR_EXTERNAL_OWNER,
            ScoreboardService.claimAction(true, false, false)
        );
        assertEquals(
            ScoreboardService.ScoreboardClaimAction.REUSE_SESSION,
            ScoreboardService.claimAction(true, true, false)
        );
        assertEquals(
            ScoreboardService.ScoreboardClaimAction.CREATE_SESSION,
            ScoreboardService.claimAction(true, false, true)
        );
        assertEquals(
            ScoreboardService.ScoreboardClaimAction.CREATE_SESSION,
            ScoreboardService.claimAction(false, false, true)
        );
    }

    @Test
    void tabOwnershipAcceptsPlatformNormalizedLegacyColors() {
        String upperHex = "§x§A§A§0§0§A§AHello";
        String lowerHex = "§x§a§a§0§0§a§aHello";

        assertTrue(TabUpdater.legacyEquivalent(upperHex, lowerHex));
        assertTrue(TabUpdater.legacyEquivalent("§dHello", "§dHello§r"));
        assertFalse(TabUpdater.legacyEquivalent("§dHello", "§dChanged"));
    }

    @Test
    void animatedStylesProduceVisibleFramesWhileStaticRemainsStable() {
        List<TextColor> purplePalette = List.of(TextColor.color(0xAA00AA), TextColor.color(0xBA55D3));

        assertFalse(AnimationUtils.buildLegacyText("NeoTab", purplePalette, AnimationUtils.Style.RAINBOW, 0, false)
            .equals(AnimationUtils.buildLegacyText("NeoTab", purplePalette, AnimationUtils.Style.RAINBOW, 1, false)));
        assertFalse(AnimationUtils.buildLegacyText("NeoTab", purplePalette, AnimationUtils.Style.PURPLE_PULSE, 0, false)
            .equals(AnimationUtils.buildLegacyText("NeoTab", purplePalette, AnimationUtils.Style.PURPLE_PULSE, 1, false)));
        assertFalse(AnimationUtils.buildLegacyText("NeoTab", purplePalette, AnimationUtils.Style.GRADIENT_WAVE, 0, false)
            .equals(AnimationUtils.buildLegacyText("NeoTab", purplePalette, AnimationUtils.Style.GRADIENT_WAVE, 1, false)));
        assertEquals(
            AnimationUtils.buildLegacyText("NeoTab", purplePalette, AnimationUtils.Style.STATIC, 0, false),
            AnimationUtils.buildLegacyText("NeoTab", purplePalette, AnimationUtils.Style.STATIC, 20, false)
        );
    }

    @Test
    void randomMessageFormattingFallbackDoesNotMistakeHeartTextForMiniMessage() {
        assertFalse(ActionBarTextFormatter.hasExplicitFormatting("Drink water! <3"));
        assertTrue(ActionBarTextFormatter.hasExplicitFormatting("<gradient:#AA00AA:#BA55D3>Hello</gradient>"));
        assertTrue(ActionBarTextFormatter.hasExplicitFormatting("&dHello"));
    }

    private static boolean referencesLuckPerms(Method method) {
        if (method.getReturnType().getName().startsWith("net.luckperms.")) {
            return true;
        }
        return Arrays.stream(method.getParameterTypes())
            .map(Class::getName)
            .anyMatch(name -> name.startsWith("net.luckperms."));
    }

    private static YamlConfiguration loadMessageResource(String resourceName) throws Exception {
        try (InputStream stream = CoreRegressionTest.class.getResourceAsStream("/" + resourceName)) {
            assertNotNull(stream, "Missing test resource " + resourceName);
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        }
    }

    private static Set<String> leafKeys(YamlConfiguration configuration) {
        Set<String> keys = new HashSet<>();
        for (String key : configuration.getKeys(true)) {
            if (!configuration.isConfigurationSection(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static Set<String> placeholders(String input) {
        Set<String> placeholders = new HashSet<>();
        Matcher matcher = MESSAGE_PLACEHOLDER.matcher(input);
        while (matcher.find()) {
            placeholders.add(matcher.group(1));
        }
        return placeholders;
    }

    private static final class PacketStub {
    }

    public static final class PacketConnectionStub {
        public void a(Object packet) {
        }

        public void sendPacket(PacketStub packet) {
        }
    }
}
