package de.NeoTab.neotab;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public final class ConfigManager {
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
        int interval = Math.max(1, config.getInt("update-interval-ticks", 3));
        boolean luckPermsPrefixEnabled = config.getBoolean("enable-luckperms-prefix", true);
        boolean placeholderApiEnabled = config.getBoolean("enable-placeholderapi", true);

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
            placeholderApiEnabled
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
            {"\u00A70", "<black>"},
            {"\u00A71", "<dark_blue>"},
            {"\u00A72", "<dark_green>"},
            {"\u00A73", "<dark_aqua>"},
            {"\u00A74", "<dark_red>"},
            {"\u00A75", "<dark_purple>"},
            {"\u00A76", "<gold>"},
            {"\u00A77", "<gray>"},
            {"\u00A78", "<dark_gray>"},
            {"\u00A79", "<blue>"},
            {"\u00A7a", "<green>"},
            {"\u00A7b", "<aqua>"},
            {"\u00A7c", "<red>"},
            {"\u00A7d", "<light_purple>"},
            {"\u00A7e", "<yellow>"},
            {"\u00A7f", "<white>"},
            {"\u00A7k", "<obfuscated>"},
            {"\u00A7l", "<bold>"},
            {"\u00A7m", "<strikethrough>"},
            {"\u00A7n", "<underlined>"},
            {"\u00A7o", "<italic>"},
            {"\u00A7r", "<reset>"}
        };

        for (String[] mapping : mappings) {
            String code = mapping[0];
            String tag = mapping[1];
            output = output.replace(code, tag).replace(code.toUpperCase(Locale.ROOT), tag);
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
        boolean placeholderApiEnabled
    ) {
    }
}
