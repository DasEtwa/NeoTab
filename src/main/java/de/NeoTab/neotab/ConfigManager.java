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
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
    private volatile YamlConfiguration messages;

    public ConfigManager(NeoTab plugin) {
        this.plugin = plugin;
        miniMessage = MiniMessage.miniMessage();
        legacySerializer = LegacyComponentSerializer.legacySection();
        snapshot = new AtomicReference<>();
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

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
            logWarn("<color:#FF55FF>No valid custom-colors found; using defaults.</color>");
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
            actionBarConfig
        ));
        loadMessages();
    }

    public ConfigSnapshot snapshot() {
        return snapshot.get();
    }

    public void setServerName(String serverName) {
        plugin.getConfig().set("server-name", serverName);
        plugin.saveConfig();
        reload();
    }

    public void setAnimationStyle(AnimationUtils.Style style) {
        plugin.getConfig().set("animation-style", style.id());
        plugin.saveConfig();
        reload();
    }

    public void setCustomColors(List<String> colors) {
        plugin.getConfig().set("custom-colors", colors);
        plugin.saveConfig();
        reload();
    }

    public void setPerformancePreset(String presetName, int intervalTicks) {
        String normalizedPreset = normalizePerformancePresetName(presetName);
        int clampedTicks = clampPerformanceTicks(intervalTicks);
        FileConfiguration config = plugin.getConfig();
        config.set("performance.active-preset", normalizedPreset);
        config.set("update-interval-ticks", clampedTicks);
        plugin.saveConfig();
        reload();
    }

    public void saveCurrentPerformancePreset(String presetName) {
        String normalizedPreset = normalizePerformancePresetName(presetName);
        int intervalTicks = getUpdateIntervalTicks();
        FileConfiguration config = plugin.getConfig();
        config.set("performance.saved-presets." + normalizedPreset, intervalTicks);
        config.set("performance.active-preset", normalizedPreset);
        config.set("update-interval-ticks", intervalTicks);
        plugin.saveConfig();
        reload();
    }

    public void setScoreboardEnabled(boolean enabled) {
        plugin.getConfig().set("scoreboard.enabled", enabled);
        plugin.saveConfig();
        reload();
    }

    public void setScoreboardTitle(String title) {
        plugin.getConfig().set("scoreboard.title", title);
        plugin.saveConfig();
        reload();
    }

    public void setScoreboardUpdateIntervalTicks(int intervalTicks) {
        plugin.getConfig().set("scoreboard.update-interval-ticks", clampPerformanceTicks(intervalTicks));
        plugin.saveConfig();
        reload();
    }

    public void setScoreboardTitleStyle(AnimationUtils.Style style) {
        FileConfiguration config = plugin.getConfig();
        config.set("scoreboard.title-animation.enabled", true);
        config.set("scoreboard.title-animation.style", style.id());
        plugin.saveConfig();
        reload();
    }

    public void setScoreboardTitleAnimationEnabled(boolean enabled) {
        plugin.getConfig().set("scoreboard.title-animation.enabled", enabled);
        plugin.saveConfig();
        reload();
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
        plugin.getConfig().set("scoreboard.lines", lines);
        plugin.saveConfig();
        reload();
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
        plugin.getConfig().set("scoreboard.lines", lines);
        plugin.saveConfig();
        reload();
    }

    public void clearAllScoreboardLines() {
        plugin.getConfig().set("scoreboard.lines", List.of());
        plugin.saveConfig();
        reload();
    }

    public void saveScoreboardPreset(String presetName) {
        String normalizedPreset = normalizePerformancePresetName(presetName);
        if (!isValidPerformancePresetName(normalizedPreset)) {
            return;
        }

        FileConfiguration config = plugin.getConfig();
        config.set("scoreboard.presets." + normalizedPreset + ".title", getScoreboardConfig().title());
        config.set("scoreboard.presets." + normalizedPreset + ".lines", getScoreboardConfig().lines());
        plugin.saveConfig();
        reload();
    }

    public boolean loadScoreboardPreset(String presetName) {
        String normalizedPreset = normalizePerformancePresetName(presetName);
        ScoreboardPreset preset = getScoreboardConfig().presets().get(normalizedPreset);
        if (preset == null) {
            return false;
        }

        FileConfiguration config = plugin.getConfig();
        config.set("scoreboard.title", preset.title());
        config.set("scoreboard.lines", preset.lines());
        plugin.saveConfig();
        reload();
        return true;
    }

    public boolean deleteScoreboardPreset(String presetName) {
        String normalizedPreset = normalizePerformancePresetName(presetName);
        if (!isValidPerformancePresetName(normalizedPreset)) {
            return false;
        }

        FileConfiguration config = plugin.getConfig();
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
        plugin.saveConfig();
        reload();
        return true;
    }

    public void setActionBarTimerRunningFormat(String format) {
        plugin.getConfig().set("extras.actionbar.timer.running-format", format);
        plugin.saveConfig();
        reload();
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
        plugin.getConfig().set(path, enabled);
        plugin.saveConfig();
        reload();
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
        plugin.getConfig().set("extras.actionbar.random-messages.messages", messages == null ? List.of() : List.copyOf(messages));
        plugin.saveConfig();
        reload();
    }

    public void setClockTimezone(String timezone) {
        plugin.getConfig().set("extras.actionbar.clock.timezone", timezone);
        plugin.saveConfig();
        reload();
    }

    public void setClockFormat(String format) {
        plugin.getConfig().set("extras.actionbar.clock.format", format);
        plugin.saveConfig();
        reload();
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

    public Component message(String key) {
        return message(key, Collections.emptyMap());
    }

    public Component message(String key, Map<String, String> placeholders) {
        String raw = messages == null ? null : messages.getString(key);
        if (raw == null) {
            raw = "<color:#FF55FF>Missing message: " + key + "</color>";
        }

        String resolved = replacePlaceholders(normalizeMessageTheme(raw), placeholders);
        return deserialize(resolved, "messages." + key);
    }

    public Component deserialize(String input, String context) {
        if (input == null) {
            return Component.empty();
        }

        String prepared = translateLegacyCodes(sanitizeMiniMessage(input));
        try {
            return miniMessage.deserialize(prepared);
        } catch (Exception ex) {
            logWarn("<color:#FF55FF>MiniMessage parse error for " + context + ": " + ex.getMessage() + "</color>");
            return Component.text(prepared);
        }
    }

    public String toLegacy(String input, String context) {
        Component component = deserialize(input, context);
        return legacySerializer.serialize(component);
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
        File messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(messagesFile);
        try (InputStream defaultsStream = plugin.getResource("messages.yml")) {
            if (defaultsStream == null) {
                return;
            }

            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultsStream, StandardCharsets.UTF_8));
            messages.setDefaults(defaults);
            messages.options().copyDefaults(true);
            messages.save(messagesFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not update messages.yml defaults: " + ex.getMessage());
        }
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
                logWarn("<color:#FF55FF>Invalid color in custom-colors: " + entry + "</color>");
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

        logWarn("<color:#FF55FF>Invalid " + path + " in config.yml; falling back to " + fallback.id() + ".</color>");
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
                logWarn("<color:#FF55FF>Invalid performance preset in config.yml: " + key + "</color>");
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
                logWarn("<color:#FF55FF>Invalid saved performance preset in config.yml: " + key + "</color>");
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
                logWarn("<color:#FF55FF>Invalid scoreboard preset in config.yml: " + key + "</color>");
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
                logWarn("<color:#FF55FF>Invalid tab profile in config.yml: " + key + "</color>");
                continue;
            }

            String path = "tab-profiles." + key;
            String serverNameRaw = validateMiniMessage(config.getString(path + ".server-name", defaultProfile.serverNameRaw()), path + ".server-name");
            Component serverComponent = deserialize(serverNameRaw, path + ".server-name");
            String serverPlain = PlainTextComponentSerializer.plainText().serialize(serverComponent);
            AnimationUtils.Style style = resolveStyle(config.getString(path + ".animation-style", defaultProfile.style().id()), defaultProfile.style(), path + ".animation-style");
            List<TextColor> profileColors = config.contains(path + ".custom-colors") ? parseColors(config.getStringList(path + ".custom-colors")) : defaultProfile.customColors();
            if (profileColors.isEmpty()) {
                logWarn("<color:#FF55FF>No valid custom-colors found for tab profile " + normalizedKey + "; using default colors.</color>");
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
                validateMiniMessage(config.getString("extras.actionbar.stopwatch.text", "<light_purple>Stopwatch {time}</light_purple>"), "extras.actionbar.stopwatch.text")
            ),
            new ClockActionBarConfig(
                config.getBoolean("extras.actionbar.clock.enabled", false),
                resolveZoneId(config.getString("extras.actionbar.clock.timezone", "Europe/Berlin")),
                clampSecondsInterval(config.getInt("extras.actionbar.clock.interval-seconds", 60), MIN_ACTIONBAR_SECONDS_INTERVAL, "extras.actionbar.clock.interval-seconds"),
                validateClockFormat(config.getString("extras.actionbar.clock.format", "HH:mm")),
                validateMiniMessage(config.getString("extras.actionbar.clock.text", "<gray>Time: <light_purple>{time}</light_purple></gray>"), "extras.actionbar.clock.text")
            ),
            new WelcomeActionBarConfig(
                config.getBoolean("extras.actionbar.welcome.enabled", true),
                Math.max(0, config.getInt("extras.actionbar.welcome.delay-ticks", 20)),
                Math.max(1, config.getInt("extras.actionbar.welcome.duration-seconds", 5)),
                validateMiniMessage(config.getString("extras.actionbar.welcome.text", "<gradient:#AA00AA:#BA55D3>Welcome {player}!</gradient>"), "extras.actionbar.welcome.text")
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
                validateMiniMessage(config.getString("extras.actionbar.biome-popup.text", "<gray>Entering <light_purple>{biome}</light_purple></gray>"), "extras.actionbar.biome-popup.text")
            ),
            new AchievementsActionBarConfig(
                config.getBoolean("extras.actionbar.achievements.enabled", false),
                config.getString("extras.actionbar.achievements.provider", "minecraft"),
                clampSecondsInterval(config.getInt("extras.actionbar.achievements.interval-seconds", 60), MIN_ACTIONBAR_SECONDS_INTERVAL, "extras.actionbar.achievements.interval-seconds"),
                Math.max(1, config.getInt("extras.actionbar.achievements.duration-seconds", 5)),
                validateMiniMessage(config.getString("extras.actionbar.achievements.text", "<gray>Achievements: <light_purple>{completed}</light_purple>/<light_purple>{total}</light_purple></gray>"), "extras.actionbar.achievements.text")
            ),
            new NearestPlayerActionBarConfig(
                config.getBoolean("extras.actionbar.nearest-player.enabled", false),
                clampTicksInterval(config.getInt("extras.actionbar.nearest-player.check-interval-ticks", 60), MIN_NEAREST_PLAYER_CHECK_INTERVAL_TICKS, "extras.actionbar.nearest-player.check-interval-ticks"),
                Math.max(1, config.getInt("extras.actionbar.nearest-player.max-distance", 100)),
                config.getBoolean("extras.actionbar.nearest-player.same-world-only", true),
                validateMiniMessage(config.getString("extras.actionbar.nearest-player.text", "<gray>Nearest: <light_purple>{player}</light_purple> <gray>({distance} blocks)</gray>"), "extras.actionbar.nearest-player.text")
            ),
            new StructurePopupActionBarConfig(
                config.getBoolean("extras.actionbar.structure-popup.enabled", false),
                config.getBoolean("extras.actionbar.structure-popup.experimental", true),
                clampTicksInterval(config.getInt("extras.actionbar.structure-popup.check-interval-ticks", 200), MIN_STRUCTURE_CHECK_INTERVAL_TICKS, "extras.actionbar.structure-popup.check-interval-ticks"),
                Math.max(1, config.getInt("extras.actionbar.structure-popup.max-distance", 64)),
                Math.max(1, config.getInt("extras.actionbar.structure-popup.duration-seconds", 7)),
                validateMiniMessage(config.getString("extras.actionbar.structure-popup.text", "<gray>Nearby structure: <light_purple>{structure}</light_purple></gray>"), "extras.actionbar.structure-popup.text")
            )
        );
    }

    private ActionBarTimerConfig loadActionBarTimerConfig(FileConfiguration config) {
        String path = hasNewTimerConfig(config) ? "extras.actionbar.timer" : "extras.actionbar-timer";
        return new ActionBarTimerConfig(
            config.getBoolean(path + ".enabled", true),
            validateMiniMessage(config.getString(path + ".running-format", "{time}"), path + ".running-format"),
            validateMiniMessage(config.getString(path + ".paused-format", "Paused {time}"), path + ".paused-format"),
            validateMiniMessage(config.getString(path + ".ended-format", "timer ends"), path + ".ended-format")
        );
    }

    private boolean hasNewTimerConfig(FileConfiguration config) {
        return config.isConfigurationSection("extras.actionbar.timer")
            || config.contains("extras.actionbar.timer.enabled")
            || config.contains("extras.actionbar.timer.running-format");
    }

    private List<String> loadRandomMessages(FileConfiguration config) {
        if (!config.contains("extras.actionbar.random-messages.messages")) {
            return List.of(
                "Drink water! <3",
                "Stay hydrated!",
                "Take a small break :)",
                "Remember to stretch!",
                "Don't forget to blink :)",
                "Have fun playing!",
                "Good luck and have fun!",
                "Be kind to other players <3",
                "Enjoy your stay!",
                "Need help? Use /help",
                "Join our Discord with /discord",
                "Found a bug? Tell the staff!",
                "Invite your friends :)",
                "Explore, build, survive!",
                "Your adventure starts here!",
                "Stay awesome!",
                "Keep calm and mine on!",
                "Watch your back!",
                "Don't dig straight down!",
                "Diamonds are waiting for you!",
                "Teamwork makes it easier!",
                "Respect other players.",
                "A friendly chat makes the server better.",
                "Take care of your inventory!",
                "Remember to set your home.",
                "Check out the server rules.",
                "Use /spawn to return safely.",
                "New here? Ask the team for help!",
                "Thanks for playing on this server!",
                "Have a cozy session :)"
            );
        }

        List<String> entries = config.getStringList("extras.actionbar.random-messages.messages");
        ArrayList<String> messages = new ArrayList<>();
        int index = 0;
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            messages.add(validateMiniMessage(entry, "extras.actionbar.random-messages.messages." + index));
            index++;
        }
        return List.copyOf(messages);
    }

    private ZoneId resolveZoneId(String input) {
        String zoneName = input == null || input.isBlank() ? "Europe/Berlin" : input.trim();
        try {
            return ZoneId.of(zoneName);
        } catch (DateTimeException ex) {
            logWarn("<color:#FF55FF>Invalid extras.actionbar.clock.timezone '" + zoneName + "'; falling back to Europe/Berlin.</color>");
            return ZoneId.of("Europe/Berlin");
        }
    }

    private String validateClockFormat(String input) {
        String format = input == null || input.isBlank() ? "HH:mm" : input.trim();
        try {
            DateTimeFormatter.ofPattern(format);
            return format;
        } catch (IllegalArgumentException ex) {
            logWarn("<color:#FF55FF>Invalid extras.actionbar.clock.format; falling back to HH:mm.</color>");
            return "HH:mm";
        }
    }

    private int clampSecondsInterval(int value, int min, String path) {
        if (value >= min) {
            return value;
        }
        logWarn("<color:#FF55FF>" + path + " was below " + min + " seconds; clamped.</color>");
        return min;
    }

    private int clampTicksInterval(int value, int min, String path) {
        if (value >= min) {
            return value;
        }
        logWarn("<color:#FF55FF>" + path + " was below " + min + " ticks; clamped.</color>");
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
            resolved = resolved.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return resolved;
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
            logWarn("<color:#FF55FF>Invalid MiniMessage for " + context + "; stripping tags.</color>");
            return sanitized.replaceAll("<[^>]+>", "");
        }
    }

    private void logWarn(String message) {
        plugin.getComponentLogger().warn(miniMessage.deserialize(message));
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
        ActionBarConfig actionBarConfig
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
