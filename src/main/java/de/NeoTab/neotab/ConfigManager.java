package de.NeoTab.neotab;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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

        String serverName = config.getString("server-name", "<gradient:#AA00AA:#BA55D3>Mein Epic Server</gradient>");
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
            validateMiniMessage(config.getString("scoreboard.title", "<gradient:#00ffaa:#0088ff>NeoTab</gradient>"), "scoreboard.title"),
            loadScoreboardLines(config),
            loadScoreboardPresets(config)
        );
        ActionBarTimerConfig actionBarTimerConfig = new ActionBarTimerConfig(
            config.getBoolean("extras.actionbar-timer.enabled", true)
        );

        AnimationUtils.Style style = AnimationUtils.Style.fromString(config.getString("animation-style", "rainbow"));
        if (style == null) {
            style = AnimationUtils.Style.RAINBOW;
            logWarn("<color:#FF55FF>Invalid animation-style in config.yml; falling back to rainbow.</color>");
        }

        List<TextColor> colors = parseColors(config.getStringList("custom-colors"));
        if (colors.isEmpty()) {
            colors = DEFAULT_COLORS;
            logWarn("<color:#FF55FF>No valid custom-colors found; using defaults.</color>");
        }

        String validatedServerName = validateMiniMessage(serverName, "server-name");
        String validatedFooter = validateMiniMessage(footerFormat, "ram-format");
        Component serverComponent = deserialize(validatedServerName, "server-name");
        String serverPlain = PlainTextComponentSerializer.plainText().serialize(serverComponent);

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
            scoreboardConfig,
            actionBarTimerConfig
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

        String resolved = replacePlaceholders(raw, placeholders);
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

    public ActionBarTimerConfig getActionBarTimerConfig() {
        return snapshot.get().actionBarTimerConfig();
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
                "&7Online: &b{online}/{max}",
                "&7Ping: &e{ping}ms",
                "&7RAM: &a{ram_used}/{ram_max} MB"
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
            String title = validateMiniMessage(config.getString(path + ".title", "<gradient:#00ffaa:#0088ff>NeoTab</gradient>"), path + ".title");
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
        ScoreboardConfig scoreboardConfig,
        ActionBarTimerConfig actionBarTimerConfig
    ) {
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
        List<String> lines,
        Map<String, ScoreboardPreset> presets
    ) {
    }

    public record ScoreboardPreset(
        String title,
        List<String> lines
    ) {
    }

    public record ActionBarTimerConfig(
        boolean enabled
    ) {
    }
}
