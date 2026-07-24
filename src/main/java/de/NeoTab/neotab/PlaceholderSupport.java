package de.NeoTab.neotab;

import java.util.concurrent.atomic.AtomicBoolean;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class PlaceholderSupport {
    private static final AtomicBoolean availabilityLogged = new AtomicBoolean(false);
    private static final AtomicBoolean failureWarned = new AtomicBoolean(false);

    private final NeoTab plugin;
    private boolean available;

    public PlaceholderSupport(NeoTab plugin) {
        this.plugin = plugin;
        refresh();
    }

    public void refresh() {
        boolean wasAvailable = available;
        available = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        if (available && !wasAvailable && availabilityLogged.compareAndSet(false, true)) {
            plugin.getLogger().info("PlaceholderAPI support enabled.");
        }
    }

    public boolean isAvailable() {
        return available;
    }

    static boolean containsPlaceholderToken(String input) {
        if (input == null) {
            return false;
        }
        int opening = input.indexOf('%');
        while (opening >= 0 && opening + 2 < input.length()) {
            int closing = input.indexOf('%', opening + 1);
            if (closing < 0) {
                return false;
            }
            boolean valid = closing > opening + 1;
            for (int index = opening + 1; valid && index < closing; index++) {
                char character = input.charAt(index);
                valid = !Character.isWhitespace(character) && character != '%';
            }
            if (valid) {
                return true;
            }
            opening = input.indexOf('%', opening + 1);
        }
        return false;
    }

    public String setPlaceholders(Player player, String input) {
        if (
            !available
                || input == null
                || input.isBlank()
                || !containsPlaceholderToken(input)
                || Bukkit.getOnlinePlayers().isEmpty()
        ) {
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
