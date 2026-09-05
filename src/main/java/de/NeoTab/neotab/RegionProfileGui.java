package de.NeoTab.neotab;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
        Inventory inventory = Bukkit.createInventory(holder, 54, configManager.messageOrDefault(
            "gui.region.title.list", "NeoTab - Regions", Map.of()
        ));
        holder.setInventory(inventory);

        int start = resolvedPage * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, regions.size());
        for (int index = start; index < end; index++) {
            inventory.setItem(index - start, regionItem(regions.get(index)));
        }

        if (regions.isEmpty()) {
            inventory.setItem(22, guiItem(Material.PAPER, "region.no-regions", "No regions", Map.of(), "Create one from your wand selection."));
        }

        inventory.setItem(45, guiItem(Material.LIME_DYE, "region.create-wand", "Create from Wand Selection", Map.of(), "Uses your temporary NeoTab wand selection."));
        if (regionManager.isWorldEditAvailable()) {
            inventory.setItem(47, guiItem(Material.STRUCTURE_BLOCK, "region.import-worldedit", "Import WorldEdit Selection", Map.of(), "Create or update a region from your current WorldEdit selection."));
        }
        if (resolvedPage > 0) {
            inventory.setItem(48, guiItem(Material.ARROW, "region.previous", "Previous Page", Map.of(
                "page", Integer.toString(resolvedPage), "pages", Integer.toString(maxPage + 1)
            ), "Page " + resolvedPage + " of " + (maxPage + 1) + "."));
        }
        if (resolvedPage < maxPage) {
            inventory.setItem(50, guiItem(Material.ARROW, "region.next", "Next Page", Map.of(
                "page", Integer.toString(resolvedPage + 2), "pages", Integer.toString(maxPage + 1)
            ), "Page " + (resolvedPage + 2) + " of " + (maxPage + 1) + "."));
        }
        inventory.setItem(53, guiItem(Material.BARRIER, "region.close", "Close", Map.of(), "Close this menu."));
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
        Inventory inventory = Bukkit.createInventory(holder, 54, configManager.messageOrDefault(
            "gui.region.title.edit", "NeoTab - " + region.name(), Map.of("name", region.name())
        ));
        holder.setInventory(inventory);

        inventory.setItem(4, regionItem(region));
        String currentLocation = formatLocation(player.getLocation());
        inventory.setItem(10, guiItem(Material.LODESTONE, "region.pos1", "Set Pos1 to My Location", Map.of("current", currentLocation), "Current: " + currentLocation));
        inventory.setItem(12, guiItem(Material.RESPAWN_ANCHOR, "region.pos2", "Set Pos2 to My Location", Map.of("current", currentLocation), "Current: " + currentLocation));
        inventory.setItem(14, guiItem(Material.LIME_DYE, "region.increase-priority", "Increase Priority", Map.of("current", Integer.toString(region.priority())), "Current: " + region.priority()));
        inventory.setItem(16, guiItem(Material.RED_DYE, "region.decrease-priority", "Decrease Priority", Map.of("current", Integer.toString(region.priority())), "Current: " + region.priority()));
        inventory.setItem(28, guiItem(Material.NAME_TAG, "region.tab-profile", "Change Tab Profile", Map.of("current", region.tabProfile()), "Current: " + region.tabProfile()));
        inventory.setItem(30, guiItem(Material.MAP, "region.scoreboard-profile", "Change Scoreboard Profile", Map.of("current", region.scoreboardProfile()), "Current: " + region.scoreboardProfile()));
        String enabledStatus = configManager.plainMessage(region.enabled() ? "status.enabled" : "status.disabled");
        inventory.setItem(32, guiItem(region.enabled() ? Material.LEVER : Material.REDSTONE_TORCH, "region.toggle", "Toggle Enabled", Map.of("status", enabledStatus), "Current: " + enabledStatus));
        inventory.setItem(34, guiItem(Material.TNT, "region.delete", "Delete Region", Map.of(), "Requires confirmation."));
        if (regionManager.isWorldEditAvailable()) {
            inventory.setItem(40, guiItem(Material.STRUCTURE_BLOCK, "region.import-worldedit-edit", "Import WorldEdit Selection", Map.of(), "Replace this region's bounds from WorldEdit."));
        }
        inventory.setItem(49, guiItem(Material.ARROW, "region.back", "Back", Map.of(), "Return to the region list."));
        player.openInventory(inventory);
    }

    private void openDeleteConfirm(Player player, String regionName) {
        GuiHolder holder = new GuiHolder(MenuType.DELETE_CONFIRM, regionName, 0);
        Inventory inventory = Bukkit.createInventory(holder, 27, configManager.messageOrDefault(
            "gui.region.title.delete", "Delete " + regionName + "?", Map.of("name", regionName)
        ));
        holder.setInventory(inventory);
        inventory.setItem(11, guiItem(Material.LIME_DYE, "region.confirm-delete", "Confirm Delete", Map.of("name", regionName), "Permanently delete region " + regionName + "."));
        inventory.setItem(15, guiItem(Material.BARRIER, "region.cancel", "Cancel", Map.of(), "Keep this region."));
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
        int slot = event.getRawSlot();
        GuiActions.nextTick(plugin, player, topInventory, () -> {
            if (!requirePermission(player)) {
                player.closeInventory();
                return;
            }
            switch (guiHolder.menuType()) {
                case LIST -> handleListClick(player, guiHolder, slot);
                case EDIT -> handleEditClick(player, guiHolder, slot);
                case DELETE_CONFIRM -> handleDeleteConfirmClick(player, guiHolder, slot);
            }
        });
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
            if (!requirePermission(inputPlayer)) {
                return;
            }
            String name = regionManager.normalizeName(input);
            if (!validateNewRegionName(inputPlayer, name)) {
                return;
            }
            RegionManager.RegionMutationResult result = regionManager.createRegionChecked(name, selection.get());
            if (!result.changed()) {
                sendMutationFailure(inputPlayer, name, result, "region-create-failed");
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
            if (!requirePermission(inputPlayer)) {
                return;
            }
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

        RegionManager.RegionMutationResult result = regionManager.hasRegion(regionName)
            ? regionManager.updateBoundsChecked(regionName, selection.get())
            : regionManager.createRegionChecked(regionName, selection.get());
        if (!result.changed()) {
            sendMutationFailure(player, regionName, result, "region-create-failed");
            return;
        }
        selectionManager.setSelection(player.getUniqueId(), selection.get());
        player.sendMessage(configManager.message("region-imported", Map.of("name", regionName, "bounds", selection.get().format())));
        openEdit(player, regionName);
    }

    private void setBoundary(Player player, String regionName, boolean pos1) {
        Location location = player.getLocation();
        RegionManager.RegionMutationResult result = regionManager.updateBoundaryFromLocationChecked(regionName, location, pos1);
        if (!result.changed()) {
            sendMutationFailure(player, regionName, result, "region-position-invalid");
            if (result.failure() == RegionManager.MutationFailure.NOT_FOUND) {
                openList(player);
            } else {
                openEdit(player, regionName);
            }
            return;
        }
        if (pos1) {
            selectionManager.setPos1(player.getUniqueId(), location);
        } else {
            selectionManager.setPos2(player.getUniqueId(), location);
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
        int priority = adjustedPriority(optionalRegion.get().priority(), delta);
        regionManager.updatePriority(regionName, priority);
        player.sendMessage(configManager.message("region-priority-set", Map.of("name", regionName, "priority", Integer.toString(priority))));
        openEdit(player, regionName);
    }

    static int adjustedPriority(int priority, int delta) {
        long adjusted = (long) priority + delta;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, adjusted));
    }

    private void requestTabProfile(Player player, String regionName) {
        chatInputManager.request(player, configManager.message("input-region-tab-profile-start"), (inputPlayer, input) -> {
            if (!requirePermission(inputPlayer)) {
                return;
            }
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
            if (!requirePermission(inputPlayer)) {
                return;
            }
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
        RegionManager.RegionMutationResult result = regionManager.updateEnabledChecked(regionName, enabled);
        if (!result.changed()) {
            sendMutationFailure(player, regionName, result, "region-create-failed");
            openEdit(player, regionName);
            return;
        }
        player.sendMessage(configManager.message(enabled ? "region-enabled" : "region-disabled", Map.of("name", regionName)));
        openEdit(player, regionName);
    }

    private void sendMutationFailure(
        Player player,
        String regionName,
        RegionManager.RegionMutationResult result,
        String fallbackMessageKey
    ) {
        String messageKey = RegionCommand.mutationFailureMessageKey(result, fallbackMessageKey);
        if (result.failure() == RegionManager.MutationFailure.WORLD_MISMATCH) {
            player.sendMessage(configManager.message(messageKey, Map.of(
                "name", regionName,
                "expected", result.expectedWorld(),
                "actual", result.actualWorld()
            )));
            return;
        }
        if (result.limitExceeded()) {
            player.sendMessage(configManager.message(messageKey, Map.of(
                "name", regionName,
                "reason", regionManager.localizedMutationDetail(result)
            )));
            return;
        }
        player.sendMessage(configManager.message(messageKey, Map.of("name", regionName)));
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

    private boolean requirePermission(Player player) {
        if (player.hasPermission("neotab.region")) {
            return true;
        }
        player.sendMessage(configManager.message("no-permission"));
        return false;
    }

    private List<RegionProfile> sortedRegions() {
        return regionManager.regions().stream()
            .sorted(Comparator.comparing(RegionProfile::name))
            .toList();
    }

    private ItemStack regionItem(RegionProfile region) {
        return guiItem(
            region.enabled() ? Material.FILLED_MAP : Material.MAP,
            "region.details",
            region.name(),
            Map.of(
                "name", region.name(),
                "priority", Integer.toString(region.priority()),
                "world", region.world(),
                "bounds", "[" + region.minX() + ", " + region.minY() + ", " + region.minZ() + "] -> [" + region.maxX() + ", " + region.maxY() + ", " + region.maxZ() + "]",
                "tab", region.tabProfile(),
                "scoreboard", region.scoreboardProfile(),
                "enabled", configManager.plainMessage(region.enabled() ? "status.enabled" : "status.disabled")
            ),
            "Priority: " + region.priority(),
            "World: " + region.world(),
            "Bounds: [" + region.minX() + ", " + region.minY() + ", " + region.minZ() + "] -> [" + region.maxX() + ", " + region.maxY() + ", " + region.maxZ() + "]",
            "Tab: " + region.tabProfile(),
            "Scoreboard: " + region.scoreboardProfile(),
            "Enabled: " + region.enabled()
        );
    }

    private ItemStack guiItem(Material material, String key, String fallbackName, Map<String, String> placeholders, String... fallbackLore) {
        String name = configManager.messageOrDefault(
            "gui.item." + key + ".name",
            "<light_purple>" + fallbackName + "</light_purple>",
            placeholders
        );
        List<String> lore = new java.util.ArrayList<>();
        for (int index = 0; index < fallbackLore.length; index++) {
            lore.add(configManager.messageOrDefault(
                "gui.item." + key + ".lore." + index,
                "<gray>" + fallbackLore[index] + "</gray>",
                placeholders
            ));
        }
        return rawItem(material, name, lore);
    }

    private ItemStack rawItem(Material material, String name, List<String> lore) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        meta.setDisplayName(ChatColor.RESET.toString() + name);
        if (!lore.isEmpty()) {
            meta.setLore(lore.stream().map(line -> ChatColor.RESET.toString() + line).toList());
        }
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    private String formatLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return configManager.plainMessage("status.unknown");
        }
        return location.getWorld().getName() + " " + location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ();
    }

    private enum MenuType {
        LIST,
        EDIT,
        DELETE_CONFIRM
    }

    private static final class GuiHolder implements NeoTabInventoryHolder {
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
