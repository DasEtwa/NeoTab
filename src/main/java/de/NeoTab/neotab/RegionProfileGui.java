package de.NeoTab.neotab;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class RegionProfileGui implements Listener {
    private static final int PAGE_SIZE = 45;

    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final RegionManager regionManager;
    private final RegionSelectionManager selectionManager;
    private final ChatInputManager chatInputManager;

    public RegionProfileGui(
        NeoTab plugin,
        ConfigManager configManager,
        RegionManager regionManager,
        RegionSelectionManager selectionManager,
        ChatInputManager chatInputManager
    ) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.regionManager = regionManager;
        this.selectionManager = selectionManager;
        this.chatInputManager = chatInputManager;
    }

    public void openList(Player player) {
        openList(player, 0);
    }

    private void openList(Player player, int page) {
        List<RegionProfile> regions = sortedRegions();
        int maxPage = Math.max(0, (regions.size() - 1) / PAGE_SIZE);
        int resolvedPage = Math.max(0, Math.min(page, maxPage));
        GuiHolder holder = new GuiHolder(MenuType.LIST, null, resolvedPage);
        Inventory inventory = Bukkit.createInventory(holder, 54, "NeoTab - Regions");
        holder.setInventory(inventory);

        int start = resolvedPage * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, regions.size());
        for (int index = start; index < end; index++) {
            inventory.setItem(index - start, regionItem(regions.get(index)));
        }

        if (regions.isEmpty()) {
            inventory.setItem(22, item(Material.PAPER, "No regions", "Create one from your wand selection."));
        }

        inventory.setItem(45, item(Material.LIME_DYE, "Create from Wand Selection", "Uses your temporary NeoTab wand selection."));
        if (regionManager.isWorldEditAvailable()) {
            inventory.setItem(47, item(Material.STRUCTURE_BLOCK, "Import WorldEdit Selection", "Create or update a region from your current WorldEdit selection."));
        }
        if (resolvedPage > 0) {
            inventory.setItem(48, item(Material.ARROW, "Previous Page", "Page " + resolvedPage + " of " + (maxPage + 1) + "."));
        }
        if (resolvedPage < maxPage) {
            inventory.setItem(50, item(Material.ARROW, "Next Page", "Page " + (resolvedPage + 2) + " of " + (maxPage + 1) + "."));
        }
        inventory.setItem(53, item(Material.BARRIER, "Close", "Close this menu."));
        player.openInventory(inventory);
    }

    private void openEdit(Player player, String regionName) {
        Optional<RegionProfile> optionalRegion = regionManager.region(regionName);
        if (optionalRegion.isEmpty()) {
            player.sendMessage(configManager.message("region-missing", Map.of("name", regionName)));
            openList(player);
            return;
        }

        RegionProfile region = optionalRegion.get();
        GuiHolder holder = new GuiHolder(MenuType.EDIT, region.name(), 0);
        Inventory inventory = Bukkit.createInventory(holder, 54, "NeoTab - " + region.name());
        holder.setInventory(inventory);

        inventory.setItem(4, regionItem(region));
        inventory.setItem(10, item(Material.LODESTONE, "Set Pos1 to My Location", locationLore(player.getLocation())));
        inventory.setItem(12, item(Material.RESPAWN_ANCHOR, "Set Pos2 to My Location", locationLore(player.getLocation())));
        inventory.setItem(14, item(Material.LIME_DYE, "Increase Priority", "Current: " + region.priority()));
        inventory.setItem(16, item(Material.RED_DYE, "Decrease Priority", "Current: " + region.priority()));
        inventory.setItem(28, item(Material.NAME_TAG, "Change Tab Profile", "Current: " + region.tabProfile()));
        inventory.setItem(30, item(Material.MAP, "Change Scoreboard Profile", "Current: " + region.scoreboardProfile()));
        inventory.setItem(32, item(region.enabled() ? Material.LEVER : Material.REDSTONE_TORCH, "Toggle Enabled", "Current: " + (region.enabled() ? "enabled" : "disabled")));
        inventory.setItem(34, item(Material.TNT, "Delete Region", "Requires confirmation."));
        if (regionManager.isWorldEditAvailable()) {
            inventory.setItem(40, item(Material.STRUCTURE_BLOCK, "Import WorldEdit Selection", "Replace this region's bounds from WorldEdit."));
        }
        inventory.setItem(49, item(Material.ARROW, "Back", "Return to the region list."));
        player.openInventory(inventory);
    }

    private void openDeleteConfirm(Player player, String regionName) {
        GuiHolder holder = new GuiHolder(MenuType.DELETE_CONFIRM, regionName, 0);
        Inventory inventory = Bukkit.createInventory(holder, 27, "Delete " + regionName + "?");
        holder.setInventory(inventory);
        inventory.setItem(11, item(Material.LIME_DYE, "Confirm Delete", "Permanently delete region " + regionName + "."));
        inventory.setItem(15, item(Material.BARRIER, "Cancel", "Keep this region."));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        InventoryHolder holder = topInventory.getHolder();
        if (!(holder instanceof GuiHolder guiHolder)) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= topInventory.getSize()) {
            return;
        }

        switch (guiHolder.menuType()) {
            case LIST -> handleListClick(player, guiHolder, event.getRawSlot());
            case EDIT -> handleEditClick(player, guiHolder, event.getRawSlot());
            case DELETE_CONFIRM -> handleDeleteConfirmClick(player, guiHolder, event.getRawSlot());
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder() instanceof GuiHolder)) {
            return;
        }
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topInventory.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void handleListClick(Player player, GuiHolder holder, int slot) {
        if (slot < PAGE_SIZE) {
            int regionIndex = holder.page() * PAGE_SIZE + slot;
            List<RegionProfile> regions = sortedRegions();
            if (regionIndex < regions.size()) {
                openEdit(player, regions.get(regionIndex).name());
            }
            return;
        }

        switch (slot) {
            case 45 -> requestCreateFromSelection(player);
            case 47 -> {
                if (regionManager.isWorldEditAvailable()) {
                    requestImportWorldEdit(player, null);
                }
            }
            case 48 -> openList(player, holder.page() - 1);
            case 50 -> openList(player, holder.page() + 1);
            case 53 -> player.closeInventory();
            default -> {
            }
        }
    }

    private void handleEditClick(Player player, GuiHolder holder, int slot) {
        String regionName = holder.regionName();
        if (regionName == null || regionName.isBlank()) {
            openList(player);
            return;
        }

        switch (slot) {
            case 10 -> setBoundary(player, regionName, true);
            case 12 -> setBoundary(player, regionName, false);
            case 14 -> changePriority(player, regionName, 1);
            case 16 -> changePriority(player, regionName, -1);
            case 28 -> requestTabProfile(player, regionName);
            case 30 -> requestScoreboardProfile(player, regionName);
            case 32 -> toggleEnabled(player, regionName);
            case 34 -> openDeleteConfirm(player, regionName);
            case 40 -> {
                if (regionManager.isWorldEditAvailable()) {
                    importWorldEdit(player, regionName);
                }
            }
            case 49 -> openList(player);
            default -> {
            }
        }
    }

    private void handleDeleteConfirmClick(Player player, GuiHolder holder, int slot) {
        String regionName = holder.regionName();
        if (slot == 11) {
            if (regionManager.deleteRegion(regionName)) {
                player.sendMessage(configManager.message("region-deleted", Map.of("name", regionName)));
            } else {
                player.sendMessage(configManager.message("region-missing", Map.of("name", regionName)));
            }
            openList(player);
            return;
        }
        if (slot == 15) {
            openEdit(player, regionName);
        }
    }

    private void requestCreateFromSelection(Player player) {
        Optional<RegionSelectionManager.RegionSelection> selection = selectionManager.selection(player.getUniqueId());
        if (selection.isEmpty()) {
            player.sendMessage(configManager.message("region-selection-missing"));
            openList(player);
            return;
        }

        chatInputManager.request(player, configManager.message("input-region-name-start"), (inputPlayer, input) -> {
            String name = regionManager.normalizeName(input);
            if (!validateNewRegionName(inputPlayer, name)) {
                return;
            }
            if (!regionManager.createRegion(name, selection.get())) {
                inputPlayer.sendMessage(configManager.message("region-create-failed", Map.of("name", name)));
                return;
            }
            inputPlayer.sendMessage(configManager.message("region-created", Map.of("name", name, "bounds", selection.get().format())));
            openEdit(inputPlayer, name);
        });
    }

    private void requestImportWorldEdit(Player player, String existingRegionName) {
        if (!regionManager.isWorldEditAvailable()) {
            player.sendMessage(configManager.message("region-worldedit-missing"));
            openList(player);
            return;
        }
        if (existingRegionName != null) {
            importWorldEdit(player, existingRegionName);
            return;
        }

        chatInputManager.request(player, configManager.message("input-region-name-start"), (inputPlayer, input) -> {
            String name = regionManager.normalizeName(input);
            if (!regionManager.isValidRegionName(name)) {
                inputPlayer.sendMessage(configManager.message("region-invalid-name"));
                return;
            }
            importWorldEdit(inputPlayer, name);
        });
    }

    private void importWorldEdit(Player player, String regionName) {
        Optional<RegionSelectionManager.RegionSelection> selection = regionManager.importWorldEditSelection(player);
        if (selection.isEmpty()) {
            player.sendMessage(configManager.message("region-worldedit-selection-missing"));
            openList(player);
            return;
        }

        selectionManager.setSelection(player.getUniqueId(), selection.get());
        boolean changed = regionManager.hasRegion(regionName)
            ? regionManager.updateBounds(regionName, selection.get())
            : regionManager.createRegion(regionName, selection.get());
        if (!changed) {
            player.sendMessage(configManager.message("region-create-failed", Map.of("name", regionName)));
            return;
        }
        player.sendMessage(configManager.message("region-imported", Map.of("name", regionName, "bounds", selection.get().format())));
        openEdit(player, regionName);
    }

    private void setBoundary(Player player, String regionName, boolean pos1) {
        Location location = player.getLocation();
        if (pos1) {
            selectionManager.setPos1(player.getUniqueId(), location);
        } else {
            selectionManager.setPos2(player.getUniqueId(), location);
        }

        if (!regionManager.updateBoundaryFromLocation(regionName, location, pos1)) {
            player.sendMessage(configManager.message("region-missing", Map.of("name", regionName)));
            openList(player);
            return;
        }
        player.sendMessage(configManager.message(pos1 ? "region-pos1-set" : "region-pos2-set", Map.of(
            "name", regionName,
            "position", formatLocation(location)
        )));
        openEdit(player, regionName);
    }

    private void changePriority(Player player, String regionName, int delta) {
        Optional<RegionProfile> optionalRegion = regionManager.region(regionName);
        if (optionalRegion.isEmpty()) {
            player.sendMessage(configManager.message("region-missing", Map.of("name", regionName)));
            openList(player);
            return;
        }
        int priority = optionalRegion.get().priority() + delta;
        regionManager.updatePriority(regionName, priority);
        player.sendMessage(configManager.message("region-priority-set", Map.of("name", regionName, "priority", Integer.toString(priority))));
        openEdit(player, regionName);
    }

    private void requestTabProfile(Player player, String regionName) {
        chatInputManager.request(player, configManager.message("input-region-tab-profile-start"), (inputPlayer, input) -> {
            String tabProfile = regionManager.normalizeProfileName(input);
            boolean exists = configManager.hasTabProfile(tabProfile);
            if (!regionManager.updateTabProfile(regionName, tabProfile)) {
                inputPlayer.sendMessage(configManager.message("region-missing", Map.of("name", regionName)));
                return;
            }
            inputPlayer.sendMessage(configManager.message(exists ? "region-tab-set" : "region-tab-set-missing", Map.of("name", regionName, "profile", tabProfile)));
            openEdit(inputPlayer, regionName);
        });
    }

    private void requestScoreboardProfile(Player player, String regionName) {
        chatInputManager.request(player, configManager.message("input-region-scoreboard-profile-start"), (inputPlayer, input) -> {
            String scoreboardProfile = regionManager.normalizeProfileName(input);
            boolean exists = configManager.hasScoreboardProfile(scoreboardProfile);
            if (!regionManager.updateScoreboardProfile(regionName, scoreboardProfile)) {
                inputPlayer.sendMessage(configManager.message("region-missing", Map.of("name", regionName)));
                return;
            }
            inputPlayer.sendMessage(configManager.message(exists ? "region-scoreboard-set" : "region-scoreboard-set-missing", Map.of("name", regionName, "profile", scoreboardProfile)));
            openEdit(inputPlayer, regionName);
        });
    }

    private void toggleEnabled(Player player, String regionName) {
        Optional<RegionProfile> optionalRegion = regionManager.region(regionName);
        if (optionalRegion.isEmpty()) {
            player.sendMessage(configManager.message("region-missing", Map.of("name", regionName)));
            openList(player);
            return;
        }
        boolean enabled = !optionalRegion.get().enabled();
        regionManager.updateEnabled(regionName, enabled);
        player.sendMessage(configManager.message(enabled ? "region-enabled" : "region-disabled", Map.of("name", regionName)));
        openEdit(player, regionName);
    }

    private boolean validateNewRegionName(Player player, String name) {
        if (!regionManager.isValidRegionName(name)) {
            player.sendMessage(configManager.message("region-invalid-name"));
            return false;
        }
        if (regionManager.hasRegion(name)) {
            player.sendMessage(configManager.message("region-duplicate", Map.of("name", name)));
            return false;
        }
        return true;
    }

    private List<RegionProfile> sortedRegions() {
        return regionManager.regions().stream()
            .sorted(Comparator.comparing(RegionProfile::name))
            .toList();
    }

    private ItemStack regionItem(RegionProfile region) {
        return item(
            region.enabled() ? Material.FILLED_MAP : Material.MAP,
            region.name(),
            "Priority: " + region.priority(),
            "World: " + region.world(),
            "Bounds: [" + region.minX() + ", " + region.minY() + ", " + region.minZ() + "] -> [" + region.maxX() + ", " + region.maxY() + ", " + region.maxZ() + "]",
            "Tab: " + region.tabProfile(),
            "Scoreboard: " + region.scoreboardProfile(),
            "Enabled: " + region.enabled()
        );
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) {
            meta.lore(List.of(lore).stream()
                .map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                .toList());
        }
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    private String locationLore(Location location) {
        return "Current: " + formatLocation(location);
    }

    private String formatLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return "unknown";
        }
        return location.getWorld().getName() + " " + location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ();
    }

    private enum MenuType {
        LIST,
        EDIT,
        DELETE_CONFIRM
    }

    private static final class GuiHolder implements InventoryHolder {
        private final MenuType menuType;
        private final String regionName;
        private final int page;
        private Inventory inventory;

        private GuiHolder(MenuType menuType, String regionName, int page) {
            this.menuType = menuType;
            this.regionName = regionName;
            this.page = page;
        }

        private MenuType menuType() {
            return menuType;
        }

        private String regionName() {
            return regionName;
        }

        private int page() {
            return page;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
