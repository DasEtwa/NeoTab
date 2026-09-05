package de.NeoTab.neotab;

import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/** Inventory navigation runs outside the click transaction and expires on reload/close. */
final class GuiActions {
    private GuiActions() { }

    static void nextTick(NeoTab plugin, Player player, Inventory source, Runnable action) {
        long generation = plugin.uiGeneration();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled() || !player.isOnline() || generation != plugin.uiGeneration()
                || !player.getOpenInventory().getTopInventory().equals(source)) {
                return;
            }
            try {
                action.run();
            } catch (ConfigurationStorageException failure) {
                player.sendMessage(plugin.getConfigManager().message("storage-read-only", Map.of("file", failure.fileName())));
            }
        });
    }
}
