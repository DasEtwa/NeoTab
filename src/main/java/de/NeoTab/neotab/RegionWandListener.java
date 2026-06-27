package de.NeoTab.neotab;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public final class RegionWandListener implements Listener {
    private final ConfigManager configManager;
    private final RegionSelectionManager selectionManager;
    private final NamespacedKey wandKey;

    public RegionWandListener(NeoTab plugin, ConfigManager configManager, RegionSelectionManager selectionManager) {
        this.configManager = configManager;
        this.selectionManager = selectionManager;
        wandKey = new NamespacedKey(plugin, "region_wand");
    }

    public ItemStack createWand() {
        ItemStack itemStack = new ItemStack(Material.BLAZE_ROD);
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.displayName(configManager.deserialize("<gradient:#AA00AA:#BA55D3>NeoTab Region Wand</gradient>", "region-wand.name"));
        itemMeta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || !isWand(event.getItem())) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("neotab.region")) {
            return;
        }

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            selectionManager.setPos1(player.getUniqueId(), event.getClickedBlock());
            player.sendMessage(configManager.message("region-pos1-set", java.util.Map.of("name", "selection", "position", event.getClickedBlock().getLocation().getBlockX() + " " + event.getClickedBlock().getLocation().getBlockY() + " " + event.getClickedBlock().getLocation().getBlockZ())));
            event.setCancelled(true);
            return;
        }

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            selectionManager.setPos2(player.getUniqueId(), event.getClickedBlock());
            player.sendMessage(configManager.message("region-pos2-set", java.util.Map.of("name", "selection", "position", event.getClickedBlock().getLocation().getBlockX() + " " + event.getClickedBlock().getLocation().getBlockY() + " " + event.getClickedBlock().getLocation().getBlockZ())));
            event.setCancelled(true);
        }
    }

    private boolean isWand(ItemStack itemStack) {
        if (itemStack == null || !itemStack.hasItemMeta()) {
            return false;
        }
        Byte marker = itemStack.getItemMeta().getPersistentDataContainer().get(wandKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }
}
