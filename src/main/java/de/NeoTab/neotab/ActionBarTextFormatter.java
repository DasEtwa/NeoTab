package de.NeoTab.neotab;

import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

public final class ActionBarTextFormatter {
    private final ConfigManager configManager;
    private final PlaceholderSupport placeholderSupport;
    private final LegacyComponentSerializer legacySerializer;

    public ActionBarTextFormatter(NeoTab plugin, ConfigManager configManager) {
        this.configManager = configManager;
        placeholderSupport = new PlaceholderSupport(plugin);
        legacySerializer = LegacyComponentSerializer.legacySection();
    }

    public void refresh() {
        placeholderSupport.refresh();
    }

    public Component render(Player player, String rawText, Map<String, String> placeholders, String context) {
        String resolved = replace(rawText == null ? "" : rawText, placeholders);
        if (configManager.isPlaceholderApiEnabled()) {
            resolved = placeholderSupport.setPlaceholders(player, resolved);
        }
        return configManager.deserialize(resolved, context);
    }

    public Component renderPalette(Player player, String rawText, Map<String, String> placeholders, String context) {
        String resolved = replace(rawText == null ? "" : rawText, placeholders);
        if (configManager.isPlaceholderApiEnabled()) {
            resolved = placeholderSupport.setPlaceholders(player, resolved);
        }
        String plain = configManager.toPlain(resolved, context);
        String legacy = AnimationUtils.buildLegacyText(plain, configManager.getCustomColors(), AnimationUtils.Style.STATIC, 0, false);
        return legacySerializer.deserialize(legacy);
    }

    private String replace(String input, Map<String, String> placeholders) {
        String resolved = input;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            resolved = resolved.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return resolved;
    }
}
