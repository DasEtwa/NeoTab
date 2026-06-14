package de.NeoTab.neotab;

import java.util.concurrent.atomic.AtomicBoolean;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class PlaceholderSupport {
    private final NeoTab plugin;
    private final AtomicBoolean failureWarned = new AtomicBoolean(false);
    private boolean available;

    public PlaceholderSupport(NeoTab plugin) {
        this.plugin = plugin;
        refresh();
    }

    public void refresh() {
        available = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        if (available) {
            plugin.getLogger().info("PlaceholderAPI support enabled.");
        }
    }

    public String setPlaceholders(Player player, String input) {
        if (!available || input == null || input.isBlank()) {
            return input;
        }

        try {
            return PlaceholderAPI.setPlaceholders(player, input);
        } catch (RuntimeException ex) {
            if (failureWarned.compareAndSet(false, true)) {
                plugin.getLogger().warning("PlaceholderAPI failed to parse placeholders: " + ex.getMessage());
            }
            return input;
        }
    }
}
