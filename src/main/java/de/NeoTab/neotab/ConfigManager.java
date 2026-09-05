package de.NeoTab.neotab;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public final class ConfigManager {
    public static final int MIN_PERFORMANCE_INTERVAL_TICKS = 1;
    public static final int MAX_PERFORMANCE_INTERVAL_TICKS = 1200;
    public static final int MAX_SCOREBOARD_LINES = 15;
    public static final int MIN_ACTIONBAR_SECONDS_INTERVAL = 60;
    public static final int MIN_BIOME_CHECK_INTERVAL_TICKS = 30;
    public static final int MIN_NEAREST_PLAYER_CHECK_INTERVAL_TICKS = 40;
    public static final int MIN_STRUCTURE_CHECK_INTERVAL_TICKS = 100;

    private static final List<TextColor> DEFAULT_COLORS = List.of(
        TextColor.color(0xAA00AA),
        TextColor.color(0x9932CC),
        TextColor.color(0xBA55D3),
        TextColor.color(0xDDA0DD),
        TextColor.color(0x9370DB)
    );

    private final NeoTab plugin;
    private final MiniMessage miniMessage;
    private final LegacyComponentSerializer legacySerializer;
    private final AtomicReference<ConfigSnapshot> snapshot;
    private final AsyncYamlWriter yamlWriter;
    private volatile YamlConfiguration messages;
    private volatile YamlConfiguration germanMessages;
    private volatile Language language = Language.ENGLISH;

    public enum Language {
        ENGLISH("en"),
        GERMAN("de");

        private final String id;

        Language(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Language fromString(String value) {
            Language parsed = parse(value);
            return parsed == null ? ENGLISH : parsed;
        }

        public static Language parse(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            int localeSeparator = normalized.indexOf('-');
            if (localeSeparator > 0) {
                normalized = normalized.substring(0, localeSeparator);
            }
            return switch (normalized) {
                case "de", "deutsch", "german", "ger" -> GERMAN;
                case "en", "english", "englisch" -> ENGLISH;
                default -> null;
            };
        }
    }

    public ConfigManager(NeoTab plugin, AsyncYamlWriter yamlWriter) {
        this.plugin = plugin;
        this.yamlWriter = yamlWriter;
        miniMessage = MiniMessage.miniMessage();
        legacySerializer = LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
        snapshot = new AtomicReference<>();
        reload();
    }

    public void reload() {
        reload(plugin.prepareConfigReload());
    }

    void reload(YamlConfiguration candidate) {
        plugin.applyConfigReload(candidate);
        language = Language.fromString(plugin.getConfig().getString("language", "en"));
        loadMessages();
        rebuildSnapshot();
        plugin.refreshMetrics();
    }

    public CompletableFuture<Void> flushWritesAsync() {
        return yamlWriter.flushAsync();
    }

    private void rebuildSnapshot() {
        FileConfiguration config = plugin.getConfig();
        language = Language.fromString(config.getString("language", "en"));

        String serverName = config.getString("server-name", "<gradient:#AA00AA:#BA55D3>Welcome from NeoTab</gradient>");
        String footerFormat = config.getString("ram-format", "<gray>RAM: <light_purple>{used}MB / {total}MB ({percent}%)</light_purple></gray>");
        Map<String, Integer> performancePresets = loadPerformancePresets(config);
        Map<String, Integer> savedPerformancePresets = loadPerformanceValues(config, "performance.saved-presets");
        String activePerformancePreset = normalizePerformancePresetName(config.getString("performance.active-preset", "custom"));
        int interval = resolveUpdateInterval(config, activePerformancePreset, performancePresets, savedPerformancePresets);
        boolean luckPermsPrefixEnabled = config.getBoolean("enable-luckperms-prefix", true);
        boolean placeholderApiEnabled = config.getBoolean("enable-placeholderapi", true);
        boolean headerBoldAnimation = config.getBoolean("header.bold-animation", false);
        boolean guiEnabled = config.getBoolean("gui.enabled", true);
        UpdateCheckerConfig updateCheckerConfig = new UpdateCheckerConfig(
            config.getBoolean("update-checker.enabled", true),
            config.getBoolean("update-checker.include-beta", false),
            config.getBoolean("update-checker.notify-admins", true),
            Math.max(0, config.getInt("update-checker.check-delay-seconds", 5))
        );
        ScoreboardConfig scoreboardConfig = new ScoreboardConfig(
            config.getBoolean("scoreboard.enabled", false),
            Math.max(1, config.getInt("scoreboard.update-interval-ticks", 20)),
            validateMiniMessage(config.getString("scoreboard.title", "NeoTab"), "scoreboard.title"),
            config.getBoolean("scoreboard.title-animation.enabled", true),
            resolveStyle(config.getString("scoreboard.title-animation.style", "static"), AnimationUtils.Style.STATIC, "scoreboard.title-animation.style"),
            loadScoreboardLines(config),
            loadScoreboardPresets(config)
        );
        ActionBarConfig actionBarConfig = loadActionBarConfig(config);

        AnimationUtils.Style style = resolveStyle(config.getString("animation-style", "rainbow"), AnimationUtils.Style.RAINBOW, "animation-style");

        List<TextColor> colors = parseColors(config.getStringList("custom-colors"));
        if (colors.isEmpty()) {
            colors = DEFAULT_COLORS;
            logWarn("log.config.no-valid-colors", Collections.emptyMap());
        }

        String validatedServerName = validateMiniMessage(serverName, "server-name");
        String validatedFooter = validateMiniMessage(footerFormat, "ram-format");
        Component serverComponent = deserialize(validatedServerName, "server-name");
        String serverPlain = PlainTextComponentSerializer.plainText().serialize(serverComponent);
        TabProfile defaultTabProfile = new TabProfile(
            "default",
            validatedServerName,
            serverPlain,
            style,
            List.copyOf(colors),
            validatedFooter,
            headerBoldAnimation
        );
        Map<String, TabProfile> tabProfiles = loadTabProfiles(config, defaultTabProfile);

        snapshot.set(new ConfigSnapshot(
            validatedServerName,
            serverPlain,
            style,
            interval,
            List.copyOf(colors),
            validatedFooter,
            luckPermsPrefixEnabled,
            placeholderApiEnabled,
            headerBoldAnimation,
            updateCheckerConfig,
            activePerformancePreset,
            performancePresets,
            savedPerformancePresets,
            guiEnabled,
            tabProfiles,
            scoreboardConfig,
            actionBarConfig,
            language
        ));
    }

    private void persistAndRefresh() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        yamlWriter.write(configFile.toPath(), plugin.getWritableConfig().saveToString());
        rebuildSnapshot();
        plugin.refreshMetrics();
    }

    public ConfigSnapshot snapshot() {
        return snapshot.get();
    }

    public void setServerName(String serverName) {
        plugin.getWritableConfig().set("server-name", serverName);
        persistAndRefresh();
    }

    public void setAnimationStyle(AnimationUtils.Style style) {
        plugin.getWritableConfig().set("animation-style", style.id());
        persistAndRefresh();
    }

    public void setCustomColors(List<String> colors) {
        plugin.getWritableConfig().set("custom-colors", colors);
        persistAndRefresh();
    }

    public void setPerformancePreset(String presetName, int intervalTicks) {
        String normalizedPreset = normalizePerformancePresetName(presetName);
        int clampedTicks = clampPerformanceTicks(intervalTicks);
        FileConfiguration config = plugin.getWritableConfig();
        config.set("performance.active-preset", normalizedPreset);
        config.set("update-interval-ticks", clampedTicks);
        persistAndRefresh();
    }

    public void saveCurrentPerformancePreset(String presetName) {
        String normalizedPreset = normalizePerformancePresetName(presetName);
        int intervalTicks = getUpdateIntervalTicks();
        FileConfiguration config = plugin.getWritableConfig();
        config.set("performance.saved-presets." + normalizedPreset, intervalTicks);
        config.set("performance.active-preset", normalizedPreset);
        config.set("update-interval-ticks", intervalTicks);
        persistAndRefresh();
    }

    public void setScoreboardEnabled(boolean enabled) {
        plugin.getWritableConfig().set("scoreboard.enabled", enabled);
        persistAndRefresh();
    }

    public void setScoreboardTitle(String title) {
        plugin.getWritableConfig().set("scoreboard.title", title);
        persistAndRefresh();
    }

    public void setScoreboardUpdateIntervalTicks(int intervalTicks) {
        plugin.getWritableConfig().set("scoreboard.update-interval-ticks", clampPerformanceTicks(intervalTicks));
        persistAndRefresh();
    }

    public void setScoreboardTitleStyle(AnimationUtils.Style style) {
        FileConfiguration config = plugin.getWritableConfig();
        config.set("scoreboard.title-animation.enabled", true);
        config.set("scoreboard.title-animation.style", style.id());
        persistAndRefresh();
    }

    public void setScoreboardTitleAnimationEnabled(boolean enabled) {
        plugin.getWritableConfig().set("scoreboard.title-animation.enabled", enabled);
        persistAndRefresh();
    }

    public void setScoreboardLine(int lineNumber, String text) {
        if (lineNumber < 1 || lineNumber > MAX_SCOREBOARD_LINES) {
            return;
        }

        ArrayList<String> lines = new ArrayList<>(getScoreboardConfig().lines());
        while (lines.size() < lineNumber) {
            lines.add("");
        }
        lines.set(lineNumber - 1, text);
        plugin.getWritableConfig().set("scoreboard.lines", lines);
        persistAndRefresh();
    }

    public void clearScoreboardLine(int lineNumber) {
        if (lineNumber < 1 || lineNumber > MAX_SCOREBOARD_LINES) {
            return;
        }

        ArrayList<String> lines = new ArrayList<>(getScoreboardConfig().lines());
        if (lineNumber > lines.size()) {
            return;
        }
        lines.set(lineNumber - 1, "");
        trimTrailingBlankLines(lines);
        plugin.getWritableConfig().set("scoreboard.lines", lines);
        persistAndRefresh();
    }

    public void clearAllScoreboardLines() {
        plugin.getWritableConfig().set("scoreboard.lines", List.of());
        persistAndRefresh();
    }

    public void saveScoreboardPreset(String presetName) {
        String normalizedPreset = normalizePerformancePresetName(presetName);
        if (!isValidPerformancePresetName(normalizedPreset)) {
            return;
        }

        FileConfiguration config = plugin.getWritableConfig();
        config.set("scoreboard.presets." + normalizedPreset + ".title", getScoreboardConfig().title());
        config.set("scoreboard.presets." + normalizedPreset + ".lines", getScoreboardConfig().lines());
        persistAndRefresh();
    }

    public boolean loadScoreboardPreset(String presetName) {
        String normalizedPreset = normalizePerformancePresetName(presetName);
        ScoreboardPreset preset = getScoreboardConfig().presets().get(normalizedPreset);
        if (preset == null) {
            return false;
        }

        FileConfiguration config = plugin.getWritableConfig();
        config.set("scoreboard.title", preset.title());
        config.set("scoreboard.lines", preset.lines());
        persistAndRefresh();
        return true;
    }

    public boolean deleteScoreboardPreset(String presetName) {
        String normalizedPreset = normalizePerformancePresetName(presetName);
        if (!isValidPerformancePresetName(normalizedPreset)) {
            return false;
        }

        FileConfiguration config = plugin.getWritableConfig();
        ConfigurationSection section = config.getConfigurationSection("scoreboard.presets");
        if (section == null) {
            return false;
        }

        String configuredKey = null;
        for (String key : section.getKeys(false)) {
            if (normalizePerformancePresetName(key).equals(normalizedPreset)) {
                configuredKey = key;
                break;
            }
        }
        if (configuredKey == null) {
            return false;
        }

        config.set("scoreboard.presets." + configuredKey, null);
        persistAndRefresh();
        return true;
    }

    public void setActionBarTimerRunningFormat(String format) {
        FileConfiguration config = plugin.getWritableConfig();
        String path = hasNewTimerConfig(config) ? "extras.actionbar.timer" : "extras.actionbar-timer";
        config.set(path + ".running-format", format);
        persistAndRefresh();
    }

    public void setActionBarModuleEnabled(String moduleName, boolean enabled) {
        String path = switch (normalizePerformancePresetName(moduleName)) {
            case "timer" -> "extras.actionbar.timer.enabled";
            case "stopwatch" -> "extras.actionbar.stopwatch.enabled";
            case "clock" -> "extras.actionbar.clock.enabled";
            case "welcome" -> "extras.actionbar.welcome.enabled";
            case "randommessages", "random-messages" -> "extras.actionbar.random-messages.enabled";
            case "biomepopup", "biome-popup" -> "extras.actionbar.biome-popup.enabled";
            case "achievements" -> "extras.actionbar.achievements.enabled";
            case "nearestplayer", "nearest-player" -> "extras.actionbar.nearest-player.enabled";
            case "structurepopup", "structure-popup" -> "extras.actionbar.structure-popup.enabled";
            default -> null;
        };
        if (path == null) {
            return;
        }
        plugin.getWritableConfig().set(path, enabled);
        persistAndRefresh();
    }

    public void setLanguage(Language newLanguage) {
        Language selected = newLanguage == null ? Language.ENGLISH : newLanguage;
        plugin.getWritableConfig().set("language", selected.id());
        language = selected;
        persistAndRefresh();
    }

    public List<String> getRandomActionBarMessages() {
        return getActionBarConfig().randomMessages().messages();
    }

    public void addRandomActionBarMessage(String message) {
        ArrayList<String> messages = new ArrayList<>(getRandomActionBarMessages());
        messages.add(validateMiniMessage(message, "extras.actionbar.random-messages.messages." + messages.size()));
        setRandomActionBarMessages(messages);
    }

    public boolean removeRandomActionBarMessage(int oneBasedIndex) {
        ArrayList<String> messages = new ArrayList<>(getRandomActionBarMessages());
        int index = oneBasedIndex - 1;
        if (index < 0 || index >= messages.size()) {
            return false;
        }
        messages.remove(index);
        setRandomActionBarMessages(messages);
        return true;
    }

    public void clearRandomActionBarMessages() {
        setRandomActionBarMessages(List.of());
    }

    public void setRandomActionBarMessages(List<String> messages) {
        plugin.getWritableConfig().set("extras.actionbar.random-messages.messages", messages == null ? List.of() : List.copyOf(messages));
        persistAndRefresh();
    }

    public void setClockTimezone(String timezone) {
        plugin.getWritableConfig().set("extras.actionbar.clock.timezone", timezone);
        persistAndRefresh();
    }

    public void setClockFormat(String format) {
        plugin.getWritableConfig().set("extras.actionbar.clock.format", format);
        persistAndRefresh();
    }

    public Integer getPerformancePresetTicks(String presetName) {
        String normalizedPreset = normalizePerformancePresetName(presetName);
        Integer ticks = snapshot.get().performancePresets().get(normalizedPreset);
        if (ticks != null) {
            return ticks;
        }
        return snapshot.get().savedPerformancePresets().get(normalizedPreset);
    }

    public Map<String, Integer> getPerformancePresets() {
        return snapshot.get().performancePresets();
    }

    public Map<String, Integer> getSavedPerformancePresets() {
        return snapshot.get().savedPerformancePresets();
    }

    public String message(String key) {
        return message(key, Collections.emptyMap());
    }

    public String message(String key, Map<String, String> placeholders) {
        String raw = rawMessage(key);
        if (raw == null) {
            raw = "<color:#FF55FF>Missing message: " + key + "</color>";
        }

        String resolved = replacePlaceholders(normalizeMessageTheme(raw), placeholders == null ? Collections.emptyMap() : placeholders);
        return toLegacy(deserialize(resolved, "messages." + key));
    }

    public String messageOrDefault(String key, String fallback, Map<String, String> placeholders) {
        String raw = rawMessage(key);
        if (raw == null) {
            raw = fallback;
        }
        String resolved = replacePlaceholders(
            normalizeMessageTheme(raw == null ? "" : raw),
            placeholders == null ? Collections.emptyMap() : placeholders
        );
        return toLegacy(deserialize(resolved, "messages." + key));
    }

    public String plainMessage(String key) {
        return plainMessage(key, Collections.emptyMap());
    }

    public String plainMessage(String key, Map<String, String> placeholders) {
        String raw = rawMessage(key);
        if (raw == null) {
            raw = "Missing message: " + key;
        }
        String resolved = replacePlaceholders(normalizeMessageTheme(raw), placeholders == null ? Collections.emptyMap() : placeholders);
        return toPlain(resolved, "messages." + key);
    }

    public void log(Level level, String key) {
        log(level, key, Collections.emptyMap(), null);
    }

    public void log(Level level, String key, Map<String, String> placeholders) {
        log(level, key, placeholders, null);
    }

    public void log(Level level, String key, Map<String, String> placeholders, Throwable throwable) {
        java.util.logging.Logger logger = plugin.getLogger();
        if (!logger.isLoggable(level)) {
            return;
        }
        String message = plainMessage(key, sanitizeLogPlaceholders(placeholders));
        if (throwable == null) {
            logger.log(level, message);
        } else {
            logger.log(level, message, throwable);
        }
    }

    public Language getLanguage() {
        return language;
    }

    public String languageDisplayName(Language selected) {
        Language value = selected == null ? Language.ENGLISH : selected;
        return plainMessage("language." + value.id());
    }

    public Component deserialize(String input, String context) {
        if (input == null) {
            return Component.empty();
        }

        String prepared = translateLegacyCodes(sanitizeMiniMessage(input));
        try {
            return miniMessage.deserialize(prepared);
        } catch (Exception ex) {
            // Do not route this parser failure through localized messages: doing so would call
            // deserialize() again and could recurse if the diagnostic message is also invalid.
            plugin.getLogger().warning(
                "MiniMessage parse error for " + String.valueOf(context) + ": " + String.valueOf(ex.getMessage())
            );
            return Component.text(prepared);
        }
    }

    public String toLegacy(String input, String context) {
        Component component = deserialize(input, context);
        return toLegacy(component);
    }

    public String toLegacy(Component component) {
        return legacySerializer.serialize(component == null ? Component.empty() : component);
    }

    public String toPlain(String input, String context) {
        Component component = deserialize(input, context);
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    public String getServerNameRaw() {
        return snapshot.get().serverNameRaw();
    }

    public String getServerNamePlain() {
        return snapshot.get().serverNamePlain();
    }

    public AnimationUtils.Style getStyle() {
        return snapshot.get().style();
    }

    public int getUpdateIntervalTicks() {
        return snapshot.get().updateIntervalTicks();
    }

    public boolean isLuckPermsPrefixEnabled() {
        return snapshot.get().luckPermsPrefixEnabled();
    }

    public boolean isPlaceholderApiEnabled() {
        return snapshot.get().placeholderApiEnabled();
    }

    public boolean isHeaderBoldAnimationEnabled() {
        return snapshot.get().headerBoldAnimation();
    }

    public UpdateCheckerConfig getUpdateCheckerConfig() {
        return snapshot.get().updateCheckerConfig();
    }

    public String getActivePerformancePreset() {
        return snapshot.get().activePerformancePreset();
    }

    public boolean isGuiEnabled() {
        return snapshot.get().guiEnabled();
    }

    public ScoreboardConfig getScoreboardConfig() {
        return snapshot.get().scoreboardConfig();
    }

    public TabProfile getTabProfile(String profileName) {
        String normalizedProfile = normalizePerformancePresetName(profileName);
        if (normalizedProfile.isEmpty() || normalizedProfile.equals("default")) {
            return snapshot.get().defaultTabProfile();
        }

        TabProfile profile = snapshot.get().tabProfiles().get(normalizedProfile);
        return profile == null ? snapshot.get().defaultTabProfile() : profile;
    }

    public boolean hasTabProfile(String profileName) {
        String normalizedProfile = normalizePerformancePresetName(profileName);
        return normalizedProfile.equals("default") || snapshot.get().tabProfiles().containsKey(normalizedProfile);
    }

    public ScoreboardProfile getScoreboardProfile(String profileName) {
        String normalizedProfile = normalizePerformancePresetName(profileName);
        ScoreboardConfig scoreboardConfig = getScoreboardConfig();
        if (normalizedProfile.isEmpty() || normalizedProfile.equals("default")) {
            return new ScoreboardProfile(
                "default",
                scoreboardConfig.title(),
                scoreboardConfig.titleAnimationEnabled(),
                scoreboardConfig.titleAnimationStyle(),
                scoreboardConfig.lines()
            );
        }

        ScoreboardPreset preset = scoreboardConfig.presets().get(normalizedProfile);
        if (preset == null) {
            return new ScoreboardProfile(
                "default",
                scoreboardConfig.title(),
                scoreboardConfig.titleAnimationEnabled(),
                scoreboardConfig.titleAnimationStyle(),
                scoreboardConfig.lines()
            );
        }

        return new ScoreboardProfile(
            normalizedProfile,
            preset.title(),
            scoreboardConfig.titleAnimationEnabled(),
            scoreboardConfig.titleAnimationStyle(),
            preset.lines()
        );
    }

    public boolean hasScoreboardProfile(String profileName) {
        String normalizedProfile = normalizePerformancePresetName(profileName);
        return normalizedProfile.equals("default") || getScoreboardConfig().presets().containsKey(normalizedProfile);
    }

    public ActionBarTimerConfig getActionBarTimerConfig() {
        return snapshot.get().actionBarConfig().timer();
    }

    public ActionBarConfig getActionBarConfig() {
        return snapshot.get().actionBarConfig();
    }

    public List<TextColor> getCustomColors() {
        return snapshot.get().customColors();
    }

    public String getFooterFormat() {
        return snapshot.get().footerFormat();
    }

    private void loadMessages() {
        messages = loadMessageFile("messages.yml");
        germanMessages = loadMessageFile("messages_de.yml");
    }

    private YamlConfiguration loadMessageFile(String resourceName) {
        File messageFile = new File(plugin.getDataFolder(), resourceName);
        InputStream packagedDefaults = plugin.getResource(resourceName);
        if (!messageFile.exists() && packagedDefaults != null) {
            try {
                plugin.saveResource(resourceName, false);
            } catch (RuntimeException ex) {
                logMessageFileFailure("create", resourceName, ex);
            }
        }

        YamlConfiguration loaded = new YamlConfiguration();
        boolean userFileValid = false;
        if (messageFile.isFile()) {
            try {
                loaded.load(messageFile);
                userFileValid = true;
            } catch (IOException | InvalidConfigurationException ex) {
                // Never replace a malformed user translation with generated defaults. Keeping the
                // original file intact gives the administrator a chance to repair or recover it.
                logMessageFileFailure("load", resourceName, ex);
                loaded = new YamlConfiguration();
            }
        } else if (messageFile.exists()) {
            logMessageFileFailure("load", resourceName, new IOException("path is not a regular file"));
        }

        if (packagedDefaults == null) {
            plugin.getLogger().warning(
                "Bundled NeoTab resource " + resourceName + " is missing; continuing with available fallback messages."
            );
            return loaded;
        }

        try (InputStream defaultsStream = packagedDefaults;
             InputStreamReader reader = new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)) {
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.load(reader);
            YamlConfiguration loadedMessages = loaded;
            boolean missingDefaults = userFileValid && defaults.getKeys(true).stream()
                .anyMatch(key -> !loadedMessages.contains(key, true));
            loaded.setDefaults(defaults);
            loaded.options().copyDefaults(true);
            if (missingDefaults) {
                yamlWriter.write(messageFile.toPath(), loaded.saveToString());
            }
        } catch (IOException | InvalidConfigurationException ex) {
            logMessageFileFailure("load bundled defaults for", resourceName, ex);
        }
        return loaded;
    }

    private void logMessageFileFailure(String action, String resourceName, Throwable error) {
        // This is deliberately not localized: the localization files themselves are unavailable
        // or invalid on this bootstrap/error path.
        plugin.getLogger().log(
            Level.WARNING,
            "Could not " + action + " NeoTab message file " + resourceName
                + "; the existing file was left unchanged and available fallbacks will be used.",
            error
        );
    }

    private List<TextColor> parseColors(List<String> entries) {
        ArrayList<TextColor> colors = new ArrayList<>();
        if (entries == null) {
            return colors;
        }

        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }

            TextColor color = TextColor.fromHexString(entry.trim());
            if (color == null) {
                logWarn("log.config.invalid-color", Map.of("entry", entry));
                continue;
            }
            colors.add(color);
        }
        return colors;
    }

    private AnimationUtils.Style resolveStyle(String input, AnimationUtils.Style fallback, String path) {
        AnimationUtils.Style style = AnimationUtils.Style.fromString(input);
        if (style != null) {
            return style;
        }

        logWarn("log.config.invalid-style", Map.of("path", path, "fallback", fallback.id()));
        return fallback;
    }

    private Map<String, Integer> loadPerformancePresets(FileConfiguration config) {
        LinkedHashMap<String, Integer> presets = new LinkedHashMap<>();
        presets.put("smooth", 3);
        presets.put("balanced", 10);
        presets.put("light", 20);

        ConfigurationSection section = config.getConfigurationSection("performance.presets");
        if (section == null) {
            return Collections.unmodifiableMap(presets);
        }

        for (String key : section.getKeys(false)) {
            String normalizedKey = normalizePerformancePresetName(key);
            int ticks = section.getInt(key, -1);
            if (!isValidPerformancePresetName(normalizedKey) || ticks < MIN_PERFORMANCE_INTERVAL_TICKS) {
                logWarn("log.config.invalid-performance-preset", Map.of("key", key));
                continue;
            }
            presets.put(normalizedKey, clampPerformanceTicks(ticks));
        }
        return Collections.unmodifiableMap(presets);
    }

    private Map<String, Integer> loadPerformanceValues(FileConfiguration config, String path) {
        LinkedHashMap<String, Integer> values = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return Collections.emptyMap();
        }

        for (String key : section.getKeys(false)) {
            String normalizedKey = normalizePerformancePresetName(key);
            int ticks = section.getInt(key, -1);
            if (!isValidPerformancePresetName(normalizedKey) || ticks < MIN_PERFORMANCE_INTERVAL_TICKS) {
                logWarn("log.config.invalid-saved-performance-preset", Map.of("key", key));
                continue;
            }
            values.put(normalizedKey, clampPerformanceTicks(ticks));
        }
        return Collections.unmodifiableMap(values);
    }

    private List<String> loadScoreboardLines(FileConfiguration config) {
        List<String> configuredLines;
        if (config.contains("scoreboard.lines")) {
            configuredLines = config.getStringList("scoreboard.lines");
        } else {
            configuredLines = List.of(
                "&7Online: &d{online}&7/&d{max}",
                "&7Ping: &d{ping}ms",
                "&7RAM: &d{ram_used}&7/&d{ram_max} MB"
            );
        }

        ArrayList<String> lines = new ArrayList<>();
        for (String line : configuredLines) {
            if (lines.size() >= MAX_SCOREBOARD_LINES) {
                break;
            }
            lines.add(line == null ? "" : line);
        }
        return List.copyOf(lines);
    }

    private Map<String, ScoreboardPreset> loadScoreboardPresets(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("scoreboard.presets");
        if (section == null) {
            return Collections.emptyMap();
        }

        LinkedHashMap<String, ScoreboardPreset> presets = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            String normalizedKey = normalizePerformancePresetName(key);
            if (!isValidPerformancePresetName(normalizedKey)) {
                logWarn("log.config.invalid-scoreboard-preset", Map.of("key", key));
                continue;
            }

            String path = "scoreboard.presets." + key;
            String title = validateMiniMessage(config.getString(path + ".title", "NeoTab"), path + ".title");
            ArrayList<String> lines = new ArrayList<>();
            for (String line : config.getStringList(path + ".lines")) {
                if (lines.size() >= MAX_SCOREBOARD_LINES) {
                    break;
                }
                lines.add(line == null ? "" : line);
            }
            presets.put(normalizedKey, new ScoreboardPreset(title, List.copyOf(lines)));
        }
        return Collections.unmodifiableMap(presets);
    }

    private Map<String, TabProfile> loadTabProfiles(FileConfiguration config, TabProfile defaultProfile) {
        ConfigurationSection section = config.getConfigurationSection("tab-profiles");
        if (section == null) {
            return Collections.emptyMap();
        }

        LinkedHashMap<String, TabProfile> profiles = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            String normalizedKey = normalizePerformancePresetName(key);
            if (normalizedKey.equals("default")) {
                continue;
            }
            if (!isValidPerformancePresetName(normalizedKey)) {
                logWarn("log.config.invalid-tab-profile", Map.of("key", key));
                continue;
            }

            String path = "tab-profiles." + key;
            String serverNameRaw = validateMiniMessage(config.getString(path + ".server-name", defaultProfile.serverNameRaw()), path + ".server-name");
            Component serverComponent = deserialize(serverNameRaw, path + ".server-name");
            String serverPlain = PlainTextComponentSerializer.plainText().serialize(serverComponent);
            AnimationUtils.Style style = resolveStyle(config.getString(path + ".animation-style", defaultProfile.style().id()), defaultProfile.style(), path + ".animation-style");
            List<TextColor> profileColors = config.contains(path + ".custom-colors") ? parseColors(config.getStringList(path + ".custom-colors")) : defaultProfile.customColors();
            if (profileColors.isEmpty()) {
                logWarn("log.config.no-valid-profile-colors", Map.of("profile", normalizedKey));
                profileColors = defaultProfile.customColors();
            }
            String footerFormat = validateMiniMessage(config.getString(path + ".ram-format", defaultProfile.footerFormat()), path + ".ram-format");
            boolean boldAnimation = config.getBoolean(path + ".header.bold-animation", defaultProfile.headerBoldAnimation());

            profiles.put(normalizedKey, new TabProfile(
                normalizedKey,
                serverNameRaw,
                serverPlain,
                style,
                List.copyOf(profileColors),
                footerFormat,
                boldAnimation
            ));
        }
        return Collections.unmodifiableMap(profiles);
    }

    private ActionBarConfig loadActionBarConfig(FileConfiguration config) {
        return new ActionBarConfig(
            config.getBoolean("extras.actionbar.enabled", true),
            loadActionBarTimerConfig(config),
            new StopwatchActionBarConfig(
                config.getBoolean("extras.actionbar.stopwatch.enabled", true),
                validateMiniMessage(localizedDefaultConfigValue(
                    config,
                    "extras.actionbar.stopwatch.text",
                    "<light_purple>Stopwatch {time}</light_purple>",
                    "actionbar.stopwatch.text"
                ), "extras.actionbar.stopwatch.text")
            ),
            new ClockActionBarConfig(
                config.getBoolean("extras.actionbar.clock.enabled", false),
                resolveZoneId(config.getString("extras.actionbar.clock.timezone", "Europe/Berlin")),
                clampSecondsInterval(config.getInt("extras.actionbar.clock.interval-seconds", 60), MIN_ACTIONBAR_SECONDS_INTERVAL, "extras.actionbar.clock.interval-seconds"),
                validateClockFormat(config.getString("extras.actionbar.clock.format", "HH:mm")),
                validateMiniMessage(localizedDefaultConfigValue(
                    config,
                    "extras.actionbar.clock.text",
                    "<gray>Time: <light_purple>{time}</light_purple></gray>",
                    "actionbar.clock.text"
                ), "extras.actionbar.clock.text")
            ),
            new WelcomeActionBarConfig(
                config.getBoolean("extras.actionbar.welcome.enabled", true),
                Math.max(0, config.getInt("extras.actionbar.welcome.delay-ticks", 20)),
                Math.max(1, config.getInt("extras.actionbar.welcome.duration-seconds", 5)),
                validateMiniMessage(localizedDefaultConfigValue(
                    config,
                    "extras.actionbar.welcome.text",
                    "<gradient:#AA00AA:#BA55D3>Welcome {player}!</gradient>",
                    "actionbar.welcome.text"
                ), "extras.actionbar.welcome.text")
            ),
            new RandomMessagesActionBarConfig(
                config.getBoolean("extras.actionbar.random-messages.enabled", false),
                clampSecondsInterval(config.getInt("extras.actionbar.random-messages.interval-seconds", 300), MIN_ACTIONBAR_SECONDS_INTERVAL, "extras.actionbar.random-messages.interval-seconds"),
                Math.max(1, config.getInt("extras.actionbar.random-messages.duration-seconds", 5)),
                loadRandomMessages(config)
            ),
            new BiomePopupActionBarConfig(
                config.getBoolean("extras.actionbar.biome-popup.enabled", false),
                clampTicksInterval(config.getInt("extras.actionbar.biome-popup.check-interval-ticks", 40), MIN_BIOME_CHECK_INTERVAL_TICKS, "extras.actionbar.biome-popup.check-interval-ticks"),
                Math.max(1, config.getInt("extras.actionbar.biome-popup.duration-seconds", 7)),
                validateMiniMessage(localizedDefaultConfigValue(
                    config,
                    "extras.actionbar.biome-popup.text",
                    "<gray>Entering <light_purple>{biome}</light_purple></gray>",
                    "actionbar.biome-popup.text"
                ), "extras.actionbar.biome-popup.text")
            ),
            new AchievementsActionBarConfig(
                config.getBoolean("extras.actionbar.achievements.enabled", false),
                config.getString("extras.actionbar.achievements.provider", "minecraft"),
                clampSecondsInterval(config.getInt("extras.actionbar.achievements.interval-seconds", 60), MIN_ACTIONBAR_SECONDS_INTERVAL, "extras.actionbar.achievements.interval-seconds"),
                Math.max(1, config.getInt("extras.actionbar.achievements.duration-seconds", 5)),
                validateMiniMessage(localizedDefaultConfigValue(
                    config,
                    "extras.actionbar.achievements.text",
                    "<gray>Achievements: <light_purple>{completed}</light_purple>/<light_purple>{total}</light_purple></gray>",
                    "actionbar.achievements.text"
                ), "extras.actionbar.achievements.text")
            ),
            new NearestPlayerActionBarConfig(
                config.getBoolean("extras.actionbar.nearest-player.enabled", false),
                clampTicksInterval(config.getInt("extras.actionbar.nearest-player.check-interval-ticks", 60), MIN_NEAREST_PLAYER_CHECK_INTERVAL_TICKS, "extras.actionbar.nearest-player.check-interval-ticks"),
                Math.max(1, config.getInt("extras.actionbar.nearest-player.max-distance", 100)),
                config.getBoolean("extras.actionbar.nearest-player.same-world-only", true),
                validateMiniMessage(localizedDefaultConfigValue(
                    config,
                    "extras.actionbar.nearest-player.text",
                    "<gray>Nearest: <light_purple>{player}</light_purple> <gray>({distance} blocks)</gray>",
                    "actionbar.nearest-player.text"
                ), "extras.actionbar.nearest-player.text")
            ),
            new StructurePopupActionBarConfig(
                config.getBoolean("extras.actionbar.structure-popup.enabled", false),
                config.getBoolean("extras.actionbar.structure-popup.experimental", true),
                clampTicksInterval(config.getInt("extras.actionbar.structure-popup.check-interval-ticks", 200), MIN_STRUCTURE_CHECK_INTERVAL_TICKS, "extras.actionbar.structure-popup.check-interval-ticks"),
                Math.max(1, config.getInt("extras.actionbar.structure-popup.max-distance", 64)),
                Math.max(1, config.getInt("extras.actionbar.structure-popup.duration-seconds", 7)),
                validateMiniMessage(localizedDefaultConfigValue(
                    config,
                    "extras.actionbar.structure-popup.text",
                    "<gray>Nearby structure: <light_purple>{structure}</light_purple></gray>",
                    "actionbar.structure-popup.text"
                ), "extras.actionbar.structure-popup.text")
            )
        );
    }

    private ActionBarTimerConfig loadActionBarTimerConfig(FileConfiguration config) {
        String path = hasNewTimerConfig(config) ? "extras.actionbar.timer" : "extras.actionbar-timer";
        String runningDefault = "{time}";
        String pausedDefault = "Paused {time}";
        String endedDefault = "timer ends";
        return new ActionBarTimerConfig(
            config.getBoolean(path + ".enabled", true),
            validateMiniMessage(localizedDefaultConfigValue(config, path + ".running-format", runningDefault, "actionbar.timer.running-format"), path + ".running-format"),
            validateMiniMessage(localizedDefaultConfigValue(config, path + ".paused-format", pausedDefault, "actionbar.timer.paused-format"), path + ".paused-format"),
            validateMiniMessage(localizedDefaultConfigValue(config, path + ".ended-format", endedDefault, "actionbar.timer.ended-format"), path + ".ended-format")
        );
    }

    private boolean hasNewTimerConfig(FileConfiguration config) {
        return config.isConfigurationSection("extras.actionbar.timer")
            || config.contains("extras.actionbar.timer.enabled")
            || config.contains("extras.actionbar.timer.running-format");
    }

    private List<String> loadRandomMessages(FileConfiguration config) {
        List<String> defaultMessages = defaultRandomMessages();
        if (!config.contains("extras.actionbar.random-messages.messages")) {
            return localizedRandomMessages(defaultMessages);
        }

        List<String> entries = config.getStringList("extras.actionbar.random-messages.messages");
        if (entries.equals(defaultMessages)) {
            List<String> legacyGermanMessages = enabledLegacyGermanRandomMessages(config);
            if (legacyGermanMessages != null) {
                return legacyGermanMessages;
            }
            return localizedRandomMessages(defaultMessages);
        }
        return validateRandomMessages(entries, "extras.actionbar.random-messages.messages");
    }

    private List<String> enabledLegacyGermanRandomMessages(FileConfiguration config) {
        String path = "extras.actionbar.random-messages.inactive-message-packs.german";
        if (language != Language.GERMAN || !config.getBoolean(path + ".enabled", false)) {
            return null;
        }
        return validateRandomMessages(config.getStringList(path + ".messages"), path + ".messages");
    }

    private List<String> validateRandomMessages(List<String> entries, String context) {
        ArrayList<String> validated = new ArrayList<>();
        int index = 0;
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            validated.add(validateMiniMessage(entry, context + "." + index));
            index++;
        }
        return List.copyOf(validated);
    }

    private List<String> defaultRandomMessages() {
        return List.of(
            "Drink water! <3", "Stay hydrated!", "Take a small break :)", "Remember to stretch!",
            "Don't forget to blink :)", "Have fun playing!", "Good luck and have fun!",
            "Be kind to other players <3", "Enjoy your stay!", "Need help? Use /help",
            "Join our Discord with /discord", "Found a bug? Tell the staff!", "Invite your friends :)",
            "Explore, build, survive!", "Your adventure starts here!", "Stay awesome!",
            "Keep calm and mine on!", "Watch your back!", "Don't dig straight down!",
            "Diamonds are waiting for you!", "Teamwork makes it easier!", "Respect other players.",
            "A friendly chat makes the server better.", "Take care of your inventory!", "Remember to set your home.",
            "Check out the server rules.", "Use /spawn to return safely.", "New here? Ask the team for help!",
            "Thanks for playing on this server!", "Have a cozy session :)"
        );
    }

    private List<String> localizedRandomMessages(List<String> fallback) {
        YamlConfiguration selectedMessages = language == Language.GERMAN ? germanMessages : messages;
        List<String> localized = localizedRandomMessages(selectedMessages);
        if (localized.isEmpty() && language == Language.GERMAN) {
            localized = localizedRandomMessages(messages);
        }
        return localized.isEmpty() ? fallback : localized;
    }

    private List<String> localizedRandomMessages(YamlConfiguration source) {
        if (source == null) {
            return List.of();
        }
        return validateRandomMessages(source.getStringList("actionbar.random-messages"), "actionbar.random-messages");
    }

    private String localizedDefaultConfigValue(FileConfiguration config, String path, String englishDefault, String messageKey) {
        String configured = config.getString(path);
        return selectLocalizedDefault(configured, englishDefault, rawMessage(messageKey));
    }

    static String selectLocalizedDefault(String configured, String englishDefault, String localized) {
        if (configured != null && !configured.equals(englishDefault)) {
            return configured;
        }
        if (localized != null) {
            return localized;
        }
        return configured == null ? englishDefault : configured;
    }

    private ZoneId resolveZoneId(String input) {
        String zoneName = input == null || input.isBlank() ? "Europe/Berlin" : input.trim();
        try {
            return ZoneId.of(zoneName);
        } catch (DateTimeException ex) {
            logWarn("log.config.invalid-timezone", Map.of("timezone", zoneName));
            return ZoneId.of("Europe/Berlin");
        }
    }

    private String validateClockFormat(String input) {
        String format = input == null || input.isBlank() ? "HH:mm" : input.trim();
        try {
            DateTimeFormatter.ofPattern(format);
            return format;
        } catch (IllegalArgumentException ex) {
            logWarn("log.config.invalid-clock-format", Collections.emptyMap());
            return "HH:mm";
        }
    }

    private int clampSecondsInterval(int value, int min, String path) {
        if (value >= min) {
            return value;
        }
        logWarn("log.config.seconds-clamped", Map.of("path", path, "min", String.valueOf(min)));
        return min;
    }

    private int clampTicksInterval(int value, int min, String path) {
        if (value >= min) {
            return value;
        }
        logWarn("log.config.ticks-clamped", Map.of("path", path, "min", String.valueOf(min)));
        return min;
    }

    private void trimTrailingBlankLines(ArrayList<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (!lines.get(i).isBlank()) {
                return;
            }
            lines.remove(i);
        }
    }

    private int resolveUpdateInterval(
        FileConfiguration config,
        String activePerformancePreset,
        Map<String, Integer> performancePresets,
        Map<String, Integer> savedPerformancePresets
    ) {
        Integer presetTicks = null;
        if (config.contains("performance.active-preset") && !"custom".equals(activePerformancePreset)) {
            presetTicks = performancePresets.get(activePerformancePreset);
            if (presetTicks == null) {
                presetTicks = savedPerformancePresets.get(activePerformancePreset);
            }
        }

        if (presetTicks != null) {
            return clampPerformanceTicks(presetTicks);
        }
        return clampPerformanceTicks(config.getInt("update-interval-ticks", 3));
    }

    public int clampPerformanceTicks(int ticks) {
        return Math.max(MIN_PERFORMANCE_INTERVAL_TICKS, Math.min(MAX_PERFORMANCE_INTERVAL_TICKS, ticks));
    }

    public String normalizePerformancePresetName(String presetName) {
        if (presetName == null) {
            return "";
        }
        return presetName.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isValidPerformancePresetName(String presetName) {
        String normalizedPreset = normalizePerformancePresetName(presetName);
        if (normalizedPreset.isEmpty() || normalizedPreset.length() > 32) {
            return false;
        }

        for (int i = 0; i < normalizedPreset.length(); i++) {
            char character = normalizedPreset.charAt(i);
            if (!Character.isLetterOrDigit(character) && character != '-' && character != '_') {
                return false;
            }
        }
        return true;
    }

    private String replacePlaceholders(String input, Map<String, String> placeholders) {
        String resolved = input;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            resolved = resolved.replace(
                "{" + entry.getKey() + "}",
                entry.getValue() == null ? "" : entry.getValue()
            );
        }
        return resolved;
    }

    private Map<String, String> sanitizeLogPlaceholders(Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, String> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            value = value.replace('\r', ' ').replace('\n', ' ');
            sanitized.put(entry.getKey(), miniMessage.escapeTags(value));
        }
        return sanitized;
    }

    private String normalizeMessageTheme(String input) {
        return input
            .replace("<gradient:#00ffaa:#0088ff>", "<gradient:#AA00AA:#BA55D3>")
            .replace("<gradient:#00FFAA:#0088FF>", "<gradient:#AA00AA:#BA55D3>");
    }

    private String translateLegacyCodes(String input) {
        String output = input;
        String[][] mappings = {
            {"0", "<black>"},
            {"1", "<dark_blue>"},
            {"2", "<dark_green>"},
            {"3", "<dark_aqua>"},
            {"4", "<dark_red>"},
            {"5", "<dark_purple>"},
            {"6", "<gold>"},
            {"7", "<gray>"},
            {"8", "<dark_gray>"},
            {"9", "<blue>"},
            {"a", "<green>"},
            {"b", "<aqua>"},
            {"c", "<red>"},
            {"d", "<light_purple>"},
            {"e", "<yellow>"},
            {"f", "<white>"},
            {"k", "<obfuscated>"},
            {"l", "<bold>"},
            {"m", "<strikethrough>"},
            {"n", "<underlined>"},
            {"o", "<italic>"},
            {"r", "<reset>"}
        };

        for (String[] mapping : mappings) {
            String code = mapping[0];
            String tag = mapping[1];
            output = output
                .replace("\u00A7" + code, tag)
                .replace("\u00A7" + code.toUpperCase(Locale.ROOT), tag)
                .replace("&" + code, tag)
                .replace("&" + code.toUpperCase(Locale.ROOT), tag);
        }
        return output;
    }

    private String sanitizeMiniMessage(String input) {
        return input
            .replace("\u00C2", "")
            .replace("<purple>", "<light_purple>")
            .replace("</purple>", "</light_purple>")
            .replace(":purple>", ">")
            .replace(":lila>", ">");
    }

    private String validateMiniMessage(String input, String context) {
        String sanitized = sanitizeMiniMessage(input);
        try {
            miniMessage.deserialize(translateLegacyCodes(sanitized));
            return sanitized;
        } catch (Exception ex) {
            logWarn("log.config.invalid-minimessage", Map.of("context", context));
            return sanitized.replaceAll("<[^>]+>", "");
        }
    }

    private String rawMessage(String key) {
        if (language == Language.GERMAN && germanMessages != null) {
            String german = germanMessages.getString(key);
            if (german != null) {
                return german;
            }
        }
        return messages == null ? null : messages.getString(key);
    }

    private void logWarn(String key, Map<String, String> placeholders) {
        log(Level.WARNING, key, placeholders);
    }

    public record ConfigSnapshot(
        String serverNameRaw,
        String serverNamePlain,
        AnimationUtils.Style style,
        int updateIntervalTicks,
        List<TextColor> customColors,
        String footerFormat,
        boolean luckPermsPrefixEnabled,
        boolean placeholderApiEnabled,
        boolean headerBoldAnimation,
        UpdateCheckerConfig updateCheckerConfig,
        String activePerformancePreset,
        Map<String, Integer> performancePresets,
        Map<String, Integer> savedPerformancePresets,
        boolean guiEnabled,
        Map<String, TabProfile> tabProfiles,
        ScoreboardConfig scoreboardConfig,
        ActionBarConfig actionBarConfig,
        Language language
    ) {
        private TabProfile defaultTabProfile() {
            return new TabProfile(
                "default",
                serverNameRaw,
                serverNamePlain,
                style,
                customColors,
                footerFormat,
                headerBoldAnimation
            );
        }
    }

    public record UpdateCheckerConfig(
        boolean enabled,
        boolean includeBeta,
        boolean notifyAdmins,
        int checkDelaySeconds
    ) {
    }

    public record ScoreboardConfig(
        boolean enabled,
        int updateIntervalTicks,
        String title,
        boolean titleAnimationEnabled,
        AnimationUtils.Style titleAnimationStyle,
        List<String> lines,
        Map<String, ScoreboardPreset> presets
    ) {
    }

    public record ScoreboardPreset(
        String title,
        List<String> lines
    ) {
    }

    public record TabProfile(
        String name,
        String serverNameRaw,
        String serverNamePlain,
        AnimationUtils.Style style,
        List<TextColor> customColors,
        String footerFormat,
        boolean headerBoldAnimation
    ) {
    }

    public record ScoreboardProfile(
        String name,
        String title,
        boolean titleAnimationEnabled,
        AnimationUtils.Style titleAnimationStyle,
        List<String> lines
    ) {
    }

    public record ActionBarTimerConfig(
        boolean enabled,
        String runningFormat,
        String pausedFormat,
        String endedFormat
    ) {
    }

    public record ActionBarConfig(
        boolean enabled,
        ActionBarTimerConfig timer,
        StopwatchActionBarConfig stopwatch,
        ClockActionBarConfig clock,
        WelcomeActionBarConfig welcome,
        RandomMessagesActionBarConfig randomMessages,
        BiomePopupActionBarConfig biomePopup,
        AchievementsActionBarConfig achievements,
        NearestPlayerActionBarConfig nearestPlayer,
        StructurePopupActionBarConfig structurePopup
    ) {
    }

    public record StopwatchActionBarConfig(
        boolean enabled,
        String text
    ) {
    }

    public record ClockActionBarConfig(
        boolean enabled,
        ZoneId zoneId,
        int intervalSeconds,
        String format,
        String text
    ) {
    }

    public record WelcomeActionBarConfig(
        boolean enabled,
        int delayTicks,
        int durationSeconds,
        String text
    ) {
    }

    public record RandomMessagesActionBarConfig(
        boolean enabled,
        int intervalSeconds,
        int durationSeconds,
        List<String> messages
    ) {
    }

    public record BiomePopupActionBarConfig(
        boolean enabled,
        int checkIntervalTicks,
        int durationSeconds,
        String text
    ) {
    }

    public record AchievementsActionBarConfig(
        boolean enabled,
        String provider,
        int intervalSeconds,
        int durationSeconds,
        String text
    ) {
    }

    public record NearestPlayerActionBarConfig(
        boolean enabled,
        int checkIntervalTicks,
        int maxDistance,
        boolean sameWorldOnly,
        String text
    ) {
    }

    public record StructurePopupActionBarConfig(
        boolean enabled,
        boolean experimental,
        int checkIntervalTicks,
        int maxDistance,
        int durationSeconds,
        String text
    ) {
    }
}
