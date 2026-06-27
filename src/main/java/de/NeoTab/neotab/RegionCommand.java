package de.NeoTab.neotab;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class RegionCommand {
    private final ConfigManager configManager;
    private final RegionManager regionManager;
    private final RegionSelectionManager selectionManager;
    private final RegionWandListener wandListener;
    private final RegionProfileGui regionProfileGui;

    public RegionCommand(ConfigManager configManager, RegionManager regionManager, RegionSelectionManager selectionManager, RegionWandListener wandListener, RegionProfileGui regionProfileGui) {
        this.configManager = configManager;
        this.regionManager = regionManager;
        this.selectionManager = selectionManager;
        this.wandListener = wandListener;
        this.regionProfileGui = regionProfileGui;
    }

    public boolean handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("neotab.region")) {
            sender.sendMessage(configManager.message("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(configManager.message("region-usage"));
            return true;
        }

        String action = args[1].toLowerCase(java.util.Locale.ROOT);
        switch (action) {
            case "create" -> {
                return handleCreate(sender, args);
            }
            case "delete", "remove" -> {
                return handleDelete(sender, args);
            }
            case "list" -> {
                return handleList(sender);
            }
            case "info" -> {
                return handleInfo(sender, args);
            }
            case "pos1" -> {
                return handlePosition(sender, args, true);
            }
            case "pos2" -> {
                return handlePosition(sender, args, false);
            }
            case "priority" -> {
                return handlePriority(sender, args);
            }
            case "tab" -> {
                return handleTabProfile(sender, args);
            }
            case "scoreboard" -> {
                return handleScoreboardProfile(sender, args);
            }
            case "wand" -> {
                return handleWand(sender);
            }
            case "importselection" -> {
                return handleImportSelection(sender, args);
            }
            case "gui" -> {
                return handleGui(sender);
            }
            default -> {
                sender.sendMessage(configManager.message("region-usage"));
                return true;
            }
        }
    }

    public List<String> complete(CommandSender sender, String[] args) {
        ArrayList<String> completions = new ArrayList<>();
        if (!sender.hasPermission("neotab.region")) {
            return completions;
        }
        if (args.length == 2) {
            addMatching(completions, args[1], List.of("create", "delete", "list", "info", "pos1", "pos2", "priority", "tab", "scoreboard", "wand", "importselection", "gui"));
            return completions;
        }
        if (args.length == 3 && List.of("delete", "remove", "info", "pos1", "pos2", "priority", "tab", "scoreboard", "importselection").contains(args[1].toLowerCase(java.util.Locale.ROOT))) {
            addMatching(completions, args[2], regionManager.regions().stream().map(RegionProfile::name).toList());
            return completions;
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("tab")) {
            addMatching(completions, args[3], List.of("default"));
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("scoreboard")) {
            addMatching(completions, args[3], configManager.getScoreboardConfig().presets().keySet().stream().toList());
            addMatching(completions, args[3], List.of("default"));
        }
        return completions;
    }

    private boolean handleGui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.message("player-only"));
            return true;
        }
        regionProfileGui.openList(player);
        return true;
    }

    private boolean handleCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.message("player-only"));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(configManager.message("region-create-usage"));
            return true;
        }
        String name = regionManager.normalizeName(args[2]);
        if (!regionManager.isValidRegionName(name)) {
            sender.sendMessage(configManager.message("region-invalid-name"));
            return true;
        }
        if (regionManager.hasRegion(name)) {
            sender.sendMessage(configManager.message("region-duplicate", Map.of("name", name)));
            return true;
        }

        Optional<RegionSelectionManager.RegionSelection> selection = selectionManager.selection(player.getUniqueId());
        if (selection.isEmpty()) {
            sender.sendMessage(configManager.message("region-selection-missing"));
            return true;
        }
        if (!regionManager.createRegion(name, selection.get())) {
            sender.sendMessage(configManager.message("region-create-failed", Map.of("name", name)));
            return true;
        }
        sender.sendMessage(configManager.message("region-created", Map.of("name", name, "bounds", selection.get().format())));
        return true;
    }

    private boolean handleDelete(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(configManager.message("region-delete-usage"));
            return true;
        }
        String name = regionManager.normalizeName(args[2]);
        if (!regionManager.deleteRegion(name)) {
            sender.sendMessage(configManager.message("region-missing", Map.of("name", name)));
            return true;
        }
        sender.sendMessage(configManager.message("region-deleted", Map.of("name", name)));
        return true;
    }

    private boolean handleList(CommandSender sender) {
        List<String> names = regionManager.regions().stream().map(RegionProfile::name).toList();
        sender.sendMessage(configManager.message("region-list", Map.of("names", names.isEmpty() ? "none" : String.join(", ", names))));
        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(configManager.message("region-info-usage"));
            return true;
        }
        String name = regionManager.normalizeName(args[2]);
        Optional<RegionProfile> region = regionManager.region(name);
        if (region.isEmpty()) {
            sender.sendMessage(configManager.message("region-missing", Map.of("name", name)));
            return true;
        }
        RegionProfile profile = region.get();
        sender.sendMessage(configManager.message("region-info", Map.of(
            "name", profile.name(),
            "enabled", Boolean.toString(profile.enabled()),
            "priority", Integer.toString(profile.priority()),
            "world", profile.world(),
            "bounds", "[" + profile.minX() + ", " + profile.minY() + ", " + profile.minZ() + "] -> [" + profile.maxX() + ", " + profile.maxY() + ", " + profile.maxZ() + "]",
            "tab", profile.tabProfile(),
            "scoreboard", profile.scoreboardProfile()
        )));
        return true;
    }

    private boolean handlePosition(CommandSender sender, String[] args, boolean pos1) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.message("player-only"));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(configManager.message(pos1 ? "region-pos1-usage" : "region-pos2-usage"));
            return true;
        }
        String name = regionManager.normalizeName(args[2]);
        Location location = player.getLocation();
        if (pos1) {
            selectionManager.setPos1(player.getUniqueId(), location);
        } else {
            selectionManager.setPos2(player.getUniqueId(), location);
        }

        Optional<RegionProfile> existingRegion = regionManager.region(name);
        if (existingRegion.isPresent()) {
            RegionSelectionManager.RegionSelection selection = selectionFromExisting(existingRegion.get(), location, pos1);
            regionManager.updateBounds(name, selection);
        }

        sender.sendMessage(configManager.message(pos1 ? "region-pos1-set" : "region-pos2-set", Map.of(
            "name", name,
            "position", location.getWorld().getName() + " " + location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ()
        )));
        return true;
    }

    private boolean handlePriority(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(configManager.message("region-priority-usage"));
            return true;
        }
        String name = regionManager.normalizeName(args[2]);
        Integer priority = parseInteger(args[3]);
        if (priority == null) {
            sender.sendMessage(configManager.message("region-invalid-priority"));
            return true;
        }
        if (!regionManager.updatePriority(name, priority)) {
            sender.sendMessage(configManager.message("region-missing", Map.of("name", name)));
            return true;
        }
        sender.sendMessage(configManager.message("region-priority-set", Map.of("name", name, "priority", Integer.toString(priority))));
        return true;
    }

    private boolean handleTabProfile(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(configManager.message("region-tab-usage"));
            return true;
        }
        String name = regionManager.normalizeName(args[2]);
        String tabProfile = regionManager.normalizeProfileName(args[3]);
        boolean exists = configManager.hasTabProfile(tabProfile);
        if (!regionManager.updateTabProfile(name, tabProfile)) {
            sender.sendMessage(configManager.message("region-missing", Map.of("name", name)));
            return true;
        }
        sender.sendMessage(configManager.message(exists ? "region-tab-set" : "region-tab-set-missing", Map.of("name", name, "profile", tabProfile)));
        return true;
    }

    private boolean handleScoreboardProfile(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(configManager.message("region-scoreboard-usage"));
            return true;
        }
        String name = regionManager.normalizeName(args[2]);
        String scoreboardProfile = regionManager.normalizeProfileName(args[3]);
        boolean exists = configManager.hasScoreboardProfile(scoreboardProfile);
        if (!regionManager.updateScoreboardProfile(name, scoreboardProfile)) {
            sender.sendMessage(configManager.message("region-missing", Map.of("name", name)));
            return true;
        }
        sender.sendMessage(configManager.message(exists ? "region-scoreboard-set" : "region-scoreboard-set-missing", Map.of("name", name, "profile", scoreboardProfile)));
        return true;
    }

    private boolean handleWand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.message("player-only"));
            return true;
        }
        player.getInventory().addItem(wandListener.createWand());
        sender.sendMessage(configManager.message("region-wand-given"));
        return true;
    }

    private boolean handleImportSelection(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.message("player-only"));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(configManager.message("region-import-usage"));
            return true;
        }
        String name = regionManager.normalizeName(args[2]);
        if (!regionManager.isValidRegionName(name)) {
            sender.sendMessage(configManager.message("region-invalid-name"));
            return true;
        }
        if (!regionManager.isWorldEditAvailable()) {
            sender.sendMessage(configManager.message("region-worldedit-missing"));
            return true;
        }
        Optional<RegionSelectionManager.RegionSelection> selection = regionManager.importWorldEditSelection(player);
        if (selection.isEmpty()) {
            sender.sendMessage(configManager.message("region-worldedit-selection-missing"));
            return true;
        }
        selectionManager.setSelection(player.getUniqueId(), selection.get());
        boolean changed = regionManager.hasRegion(name)
            ? regionManager.updateBounds(name, selection.get())
            : regionManager.createRegion(name, selection.get());
        if (!changed) {
            sender.sendMessage(configManager.message("region-create-failed", Map.of("name", name)));
            return true;
        }
        sender.sendMessage(configManager.message("region-imported", Map.of("name", name, "bounds", selection.get().format())));
        return true;
    }

    private RegionSelectionManager.RegionSelection selectionFromExisting(RegionProfile region, Location location, boolean pos1) {
        if (pos1) {
            return new RegionSelectionManager.RegionSelection(
                location.getWorld().getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ(),
                region.maxX(),
                region.maxY(),
                region.maxZ()
            );
        }
        return new RegionSelectionManager.RegionSelection(
            location.getWorld().getName(),
            region.minX(),
            region.minY(),
            region.minZ(),
            location.getBlockX(),
            location.getBlockY(),
            location.getBlockZ()
        );
    }

    private Integer parseInteger(String input) {
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void addMatching(List<String> completions, String rawPrefix, Iterable<String> options) {
        String prefix = rawPrefix == null ? "" : rawPrefix.toLowerCase(java.util.Locale.ROOT);
        for (String option : options) {
            if (option.toLowerCase(java.util.Locale.ROOT).startsWith(prefix) && !completions.contains(option)) {
                completions.add(option);
            }
        }
    }
}
