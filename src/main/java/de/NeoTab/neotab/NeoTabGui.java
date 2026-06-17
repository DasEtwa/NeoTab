package de.NeoTab.neotab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
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

public final class NeoTabGui implements Listener {
    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final TabUpdater tabUpdater;
    private final ScoreboardService scoreboardService;
    private final ActionBarTimerService timerService;
    private final ChatInputManager chatInputManager;
    private static final int[] PRESET_SLOTS = {9, 10, 11, 12, 13, 14, 15, 16, 17};

    public NeoTabGui(
        NeoTab plugin,
        ConfigManager configManager,
        TabUpdater tabUpdater,
        ScoreboardService scoreboardService,
        ActionBarTimerService timerService,
        ChatInputManager chatInputManager
    ) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.tabUpdater = tabUpdater;
        this.scoreboardService = scoreboardService;
        this.timerService = timerService;
        this.chatInputManager = chatInputManager;
    }

    public void openMain(Player player) {
        openMain(player, true);
    }

    private void openMain(Player player, boolean notify) {
        if (!configManager.isGuiEnabled()) {
            player.sendMessage(configManager.message("gui-disabled"));
            return;
        }

        Inventory inventory = createInventory(MenuType.MAIN, "NeoTab");
        inventory.setItem(11, item(Material.NAME_TAG, "Tab", "Header name and animation style."));
        inventory.setItem(13, item(Material.PAPER, "Scoreboard", "Sidebar scoreboard controls."));
        inventory.setItem(15, item(Material.CLOCK, "Extras", "Interval and ActionBar Timer."));
        inventory.setItem(22, item(Material.BARRIER, "Close", "Close this menu."));
        player.openInventory(inventory);
        if (notify) {
            player.sendMessage(configManager.message("gui-opened"));
        }
    }

    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
            if (holder instanceof GuiHolder) {
                player.closeInventory();
            }
        }
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

        handleClick(player, guiHolder, event.getRawSlot());
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof GuiHolder) {
            event.setCancelled(true);
        }
    }

    private void handleClick(Player player, GuiHolder guiHolder, int slot) {
        switch (guiHolder.menuType()) {
            case MAIN -> handleMainClick(player, slot);
            case TAB -> handleTabClick(player, slot);
            case COLORS -> handleColorsClick(player, slot);
            case STYLE -> handleStyleClick(player, slot);
            case SCOREBOARD -> handleScoreboardClick(player, slot);
            case SCOREBOARD_PRESETS -> handleScoreboardPresetsClick(player, slot);
            case SCOREBOARD_PRESET_ACTIONS -> handleScoreboardPresetActionsClick(player, guiHolder.presetName(), slot);
            case SCOREBOARD_LINES -> handleScoreboardLinesClick(player, slot);
            case SCOREBOARD_LINE_PRESETS -> handleScoreboardLinePresetsClick(player, guiHolder.lineNumber(), slot);
            case SCOREBOARD_STYLE -> handleScoreboardStyleClick(player, slot);
            case EXTRAS -> handleExtrasClick(player, slot);
            case TAB_INTERVAL -> handleTabIntervalClick(player, slot);
            case SCOREBOARD_INTERVAL -> handleScoreboardIntervalClick(player, slot);
            case TIMER -> handleTimerClick(player, slot);
        }
    }

    private void handleMainClick(Player player, int slot) {
        switch (slot) {
            case 11 -> openTab(player);
            case 13 -> openScoreboard(player);
            case 15 -> openExtras(player);
            case 22 -> player.closeInventory();
            default -> {
            }
        }
    }

    private void openTab(Player player) {
        Inventory inventory = createInventory(MenuType.TAB, "NeoTab - Tab");
        inventory.setItem(11, item(Material.NAME_TAG, "Name", "Current: " + configManager.getServerNamePlain()));
        inventory.setItem(13, item(Material.PAINTING, "Style", "Current: " + configManager.getStyle().id()));
        inventory.setItem(15, item(Material.MAGENTA_DYE, "Colors", "Current: " + currentColorSummary()));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleTabClick(Player player, int slot) {
        switch (slot) {
            case 11 -> {
                player.closeInventory();
                chatInputManager.request(player, configManager.message("input-name-start"), (inputPlayer, input) -> {
                configManager.setServerName(input);
                tabUpdater.updateAllNow();
                inputPlayer.sendMessage(configManager.message("name-changed"));
                });
            }
            case 13 -> openStyle(player);
            case 15 -> openColors(player);
            case 22 -> openMain(player, false);
            default -> {
            }
        }
    }

    private void openColors(Player player) {
        if (!player.hasPermission("neotab.color")) {
            player.sendMessage(configManager.message("no-permission"));
            return;
        }

        Inventory inventory = createInventory(MenuType.COLORS, "NeoTab - Colors");
        inventory.setItem(10, item(Material.PURPLE_DYE, "Purple", colorPresetLore("purple")));
        inventory.setItem(11, item(Material.RED_DYE, "Red", colorPresetLore("red")));
        inventory.setItem(12, item(Material.GREEN_DYE, "Green", colorPresetLore("green")));
        inventory.setItem(13, item(Material.YELLOW_DYE, "Gold", colorPresetLore("gold")));
        inventory.setItem(15, item(Material.WRITABLE_BOOK, "Custom", "Type 1-5 hex colors in chat."));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleColorsClick(Player player, int slot) {
        if (slot == 22) {
            openTab(player);
            return;
        }
        if (!player.hasPermission("neotab.color")) {
            player.sendMessage(configManager.message("no-permission"));
            return;
        }

        String presetName = switch (slot) {
            case 10 -> "purple";
            case 11 -> "red";
            case 12 -> "green";
            case 13 -> "gold";
            default -> null;
        };
        if (presetName != null) {
            applyColors(player, presetName, HeaderColorPalette.PRESETS.get(presetName));
            openColors(player);
            return;
        }

        if (slot == 15) {
            player.closeInventory();
            chatInputManager.request(player, configManager.message("input-color-start"), (inputPlayer, input) -> {
                List<String> colors = HeaderColorPalette.parseCustomColors(input);
                if (colors == null) {
                    inputPlayer.sendMessage(configManager.message("color-invalid"));
                    return;
                }
                applyColors(inputPlayer, "custom", colors);
                openColors(inputPlayer);
            });
        }
    }

    private void applyColors(Player player, String presetName, List<String> colors) {
        configManager.setCustomColors(colors);
        tabUpdater.restart();
        tabUpdater.updateAllNow();
        scoreboardService.updateAll();
        player.sendMessage(configManager.message("color-success", Map.of("preset", presetName, "colors", String.join(", ", colors))));
    }

    private void openStyle(Player player) {
        Inventory inventory = createInventory(MenuType.STYLE, "NeoTab - Style");
        int slot = 10;
        AnimationUtils.Style currentStyle = configManager.getStyle();
        for (AnimationUtils.Style style : AnimationUtils.Style.values()) {
            String lore = style == currentStyle ? "Current style." : "Apply this header animation.";
            inventory.setItem(slot, item(Material.PAINTING, style.id(), lore));
            slot += 2;
        }
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleStyleClick(Player player, int slot) {
        int index = switch (slot) {
            case 10 -> 0;
            case 12 -> 1;
            case 14 -> 2;
            case 16 -> 3;
            default -> -1;
        };
        if (slot == 22) {
            openTab(player);
            return;
        }
        if (index < 0 || index >= AnimationUtils.Style.values().length) {
            return;
        }

        AnimationUtils.Style style = AnimationUtils.Style.values()[index];
        configManager.setAnimationStyle(style);
        tabUpdater.restart();
        tabUpdater.updateAllNow();
        player.sendMessage(configManager.message("style-success", Map.of("style", style.id())));
        openTab(player);
    }

    private void openScoreboard(Player player) {
        Inventory inventory = createInventory(MenuType.SCOREBOARD, "NeoTab - Scoreboard");
        boolean enabled = scoreboardService.isEnabled(player);
        String toggleLore = enabled ? "Disable your sidebar scoreboard." : "Enable your sidebar scoreboard.";
        inventory.setItem(10, item(enabled ? Material.REDSTONE_TORCH : Material.LEVER, "Toggle: " + (enabled ? "On" : "Off"), toggleLore));
        inventory.setItem(12, item(Material.OAK_SIGN, "Lines", "Edit lines 1-15."));
        inventory.setItem(14, item(Material.BOOK, "Presets", "Save or load scoreboard presets."));
        inventory.setItem(16, item(Material.PAINTING, "Title Style", "Current: " + scoreboardTitleStyleLabel()));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleScoreboardClick(Player player, int slot) {
        switch (slot) {
            case 10 -> {
                if (!player.hasPermission("neotab.scoreboard.toggle")) {
                    player.sendMessage(configManager.message("no-permission"));
                    return;
                }
                boolean enabled = scoreboardService.toggle(player);
                player.sendMessage(configManager.message(enabled ? "scoreboard-enabled" : "scoreboard-disabled"));
                openScoreboard(player);
            }
            case 12 -> openScoreboardLines(player);
            case 14 -> openScoreboardPresets(player);
            case 16 -> openScoreboardStyle(player);
            case 22 -> openMain(player, false);
            default -> {
            }
        }
    }

    private void openScoreboardPresets(Player player) {
        if (!player.hasPermission("neotab.scoreboard.presets")) {
            player.sendMessage(configManager.message("no-permission"));
            return;
        }

        Inventory inventory = createInventory(MenuType.SCOREBOARD_PRESETS, "NeoTab - Presets");
        inventory.setItem(4, item(Material.CHEST, "Save Current", "Type a preset name in chat."));

        List<String> presets = scoreboardService.listPresets();
        if (presets.isEmpty()) {
            inventory.setItem(13, item(Material.GRAY_DYE, "No Presets", "Save the current scoreboard first."));
        } else {
            int count = Math.min(presets.size(), PRESET_SLOTS.length);
            for (int index = 0; index < count; index++) {
                String preset = presets.get(index);
                inventory.setItem(PRESET_SLOTS[index], item(Material.BOOK, preset, "Open load/delete actions."));
            }
        }

        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleScoreboardPresetsClick(Player player, int slot) {
        if (slot == 22) {
            openScoreboard(player);
            return;
        }
        if (!player.hasPermission("neotab.scoreboard.presets")) {
            player.sendMessage(configManager.message("no-permission"));
            return;
        }

        if (slot == 4) {
            player.closeInventory();
            chatInputManager.request(player, configManager.message("input-scoreboard-preset-start"), (inputPlayer, input) -> {
                String presetName = configManager.normalizePerformancePresetName(input);
                if (!configManager.isValidPerformancePresetName(presetName)) {
                    inputPlayer.sendMessage(configManager.message("performance-invalid-name"));
                    return;
                }
                scoreboardService.savePreset(presetName);
                inputPlayer.sendMessage(configManager.message("scoreboard-preset-saved", Map.of("name", presetName)));
                openScoreboardPresets(inputPlayer);
            });
            return;
        }

        int presetIndex = presetIndexForSlot(slot);
        if (presetIndex < 0) {
            return;
        }

        List<String> presets = scoreboardService.listPresets();
        if (presetIndex >= presets.size()) {
            return;
        }

        String presetName = presets.get(presetIndex);
        openScoreboardPresetActions(player, presetName);
    }

    private void openScoreboardPresetActions(Player player, String presetName) {
        Inventory inventory = createInventory(MenuType.SCOREBOARD_PRESET_ACTIONS, "NeoTab - Preset", presetName);
        inventory.setItem(4, item(Material.BOOK, presetName, "Saved scoreboard preset."));
        inventory.setItem(11, item(Material.LIME_DYE, "Load", "Apply this preset."));
        inventory.setItem(15, item(Material.BARRIER, "Delete", "Delete this preset."));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleScoreboardPresetActionsClick(Player player, String presetName, int slot) {
        if (slot == 22) {
            openScoreboardPresets(player);
            return;
        }
        if (presetName == null || presetName.isBlank()) {
            openScoreboardPresets(player);
            return;
        }
        if (!player.hasPermission("neotab.scoreboard.presets")) {
            player.sendMessage(configManager.message("no-permission"));
            return;
        }

        if (slot == 11) {
            if (!scoreboardService.loadPreset(presetName)) {
                player.sendMessage(configManager.message("scoreboard-preset-missing"));
                openScoreboardPresets(player);
                return;
            }

            player.sendMessage(configManager.message("scoreboard-preset-loaded", Map.of("name", presetName)));
            openScoreboard(player);
            return;
        }

        if (slot == 15) {
            if (!scoreboardService.deletePreset(presetName)) {
                player.sendMessage(configManager.message("scoreboard-preset-missing"));
                openScoreboardPresets(player);
                return;
            }

            player.sendMessage(configManager.message("scoreboard-preset-deleted", Map.of("name", presetName)));
            openScoreboardPresets(player);
        }
    }

    private void openScoreboardLines(Player player) {
        Inventory inventory = createInventory(MenuType.SCOREBOARD_LINES, "NeoTab - Lines");
        List<String> lines = configManager.getScoreboardConfig().lines();
        for (int index = 0; index < ConfigManager.MAX_SCOREBOARD_LINES; index++) {
            String current = index < lines.size() && !lines.get(index).isBlank() ? configManager.toPlain(lines.get(index), "scoreboard-line-preview") : "empty";
            inventory.setItem(index, item(Material.OAK_SIGN, "Line " + (index + 1), "Current: " + current));
        }
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleScoreboardLinesClick(Player player, int slot) {
        if (slot == 22) {
            openScoreboard(player);
            return;
        }
        if (slot < 0 || slot >= ConfigManager.MAX_SCOREBOARD_LINES) {
            return;
        }
        if (!player.hasPermission("neotab.scoreboard.edit")) {
            player.sendMessage(configManager.message("no-permission"));
            return;
        }

        int lineNumber = slot + 1;
        openScoreboardLinePresets(player, lineNumber);
    }

    private void openScoreboardLinePresets(Player player, int lineNumber) {
        Inventory inventory = createInventory(MenuType.SCOREBOARD_LINE_PRESETS, "NeoTab - Line " + lineNumber, lineNumber);
        inventory.setItem(10, item(Material.PLAYER_HEAD, "Online Players", "Online: {online}/{max}"));
        inventory.setItem(11, item(Material.NAME_TAG, "Player Name", "Player: {player}"));
        inventory.setItem(12, item(Material.COMPASS, "Ping", "Ping: {ping}ms"));
        inventory.setItem(13, item(Material.REDSTONE, "RAM", "RAM: {ram_used}/{ram_max} MB"));
        inventory.setItem(15, item(Material.WRITABLE_BOOK, "Custom", "Type this line in chat."));
        inventory.setItem(16, item(Material.BARRIER, "Clear", "Clear this line."));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleScoreboardLinePresetsClick(Player player, int lineNumber, int slot) {
        if (slot == 22) {
            openScoreboardLines(player);
            return;
        }
        if (!player.hasPermission("neotab.scoreboard.edit")) {
            player.sendMessage(configManager.message("no-permission"));
            return;
        }

        String line = switch (slot) {
            case 10 -> "&7Online: &d{online}&7/&d{max}";
            case 11 -> "&7Player: &d{player}";
            case 12 -> "&7Ping: &d{ping}ms";
            case 13 -> "&7RAM: &d{ram_used}&7/&d{ram_max} MB";
            default -> null;
        };

        if (line != null) {
            scoreboardService.setLine(lineNumber, line);
            player.sendMessage(configManager.message("scoreboard-line-changed", Map.of("line", Integer.toString(lineNumber))));
            openScoreboardLines(player);
            return;
        }

        if (slot == 15) {
            player.closeInventory();
            chatInputManager.request(player, configManager.message("input-scoreboard-line-start", Map.of("line", Integer.toString(lineNumber))), (inputPlayer, input) -> {
                scoreboardService.setLine(lineNumber, input);
                inputPlayer.sendMessage(configManager.message("scoreboard-line-changed", Map.of("line", Integer.toString(lineNumber))));
                openScoreboardLines(inputPlayer);
            });
            return;
        }

        if (slot == 16) {
            scoreboardService.clearLine(lineNumber);
            player.sendMessage(configManager.message("scoreboard-line-cleared", Map.of("line", Integer.toString(lineNumber))));
            openScoreboardLines(player);
        }
    }

    private void openScoreboardStyle(Player player) {
        Inventory inventory = createInventory(MenuType.SCOREBOARD_STYLE, "NeoTab - SB Style");
        inventory.setItem(4, item(Material.BARRIER, "Off", configManager.getScoreboardConfig().titleAnimationEnabled() ? "Disable title animation." : "Current style."));
        int slot = 10;
        AnimationUtils.Style currentStyle = configManager.getScoreboardConfig().titleAnimationStyle();
        for (AnimationUtils.Style style : AnimationUtils.Style.values()) {
            String lore = configManager.getScoreboardConfig().titleAnimationEnabled() && style == currentStyle ? "Current style." : "Apply this title animation.";
            inventory.setItem(slot, item(Material.PAINTING, style.id(), lore));
            slot += 2;
        }
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleScoreboardStyleClick(Player player, int slot) {
        if (slot == 22) {
            openScoreboard(player);
            return;
        }
        if (!player.hasPermission("neotab.scoreboard.edit")) {
            player.sendMessage(configManager.message("no-permission"));
            return;
        }
        if (slot == 4) {
            scoreboardService.setTitleAnimationEnabled(false);
            player.sendMessage(configManager.message("scoreboard-style-changed", Map.of("style", "off")));
            openScoreboard(player);
            return;
        }

        int index = switch (slot) {
            case 10 -> 0;
            case 12 -> 1;
            case 14 -> 2;
            case 16 -> 3;
            default -> -1;
        };
        if (index < 0 || index >= AnimationUtils.Style.values().length) {
            return;
        }

        AnimationUtils.Style style = AnimationUtils.Style.values()[index];
        scoreboardService.setTitleStyle(style);
        player.sendMessage(configManager.message("scoreboard-style-changed", Map.of("style", style.id())));
        openScoreboard(player);
    }

    private String scoreboardTitleStyleLabel() {
        ConfigManager.ScoreboardConfig scoreboardConfig = configManager.getScoreboardConfig();
        return scoreboardConfig.titleAnimationEnabled() ? scoreboardConfig.titleAnimationStyle().id() : "off";
    }

    private void openExtras(Player player) {
        Inventory inventory = createInventory(MenuType.EXTRAS, "NeoTab - Extras");
        inventory.setItem(11, item(Material.FEATHER, "Tab Interval", "Current: " + configManager.getUpdateIntervalTicks() + " ticks."));
        inventory.setItem(13, item(Material.COMPARATOR, "Scoreboard Interval", "Current: " + configManager.getScoreboardConfig().updateIntervalTicks() + " ticks."));
        inventory.setItem(15, item(Material.CLOCK, "ActionBar Timer", "Start or control a countdown."));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleExtrasClick(Player player, int slot) {
        switch (slot) {
            case 11 -> openTabInterval(player);
            case 13 -> openScoreboardInterval(player);
            case 15 -> openTimer(player);
            case 22 -> openMain(player, false);
            default -> {
            }
        }
    }

    private void openTabInterval(Player player) {
        Inventory inventory = createInventory(MenuType.TAB_INTERVAL, "NeoTab - Tab Interval");
        inventory.setItem(10, item(Material.FEATHER, "Smooth", tabIntervalLore("smooth")));
        inventory.setItem(13, item(Material.GOLD_INGOT, "Balanced", tabIntervalLore("balanced")));
        inventory.setItem(16, item(Material.IRON_INGOT, "Light", tabIntervalLore("light")));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void openScoreboardInterval(Player player) {
        Inventory inventory = createInventory(MenuType.SCOREBOARD_INTERVAL, "NeoTab - SB Interval");
        inventory.setItem(10, item(Material.FEATHER, "Smooth", scoreboardIntervalLore("smooth")));
        inventory.setItem(13, item(Material.GOLD_INGOT, "Balanced", scoreboardIntervalLore("balanced")));
        inventory.setItem(16, item(Material.IRON_INGOT, "Light", scoreboardIntervalLore("light")));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleTabIntervalClick(Player player, int slot) {
        String preset = switch (slot) {
            case 10 -> "smooth";
            case 13 -> "balanced";
            case 16 -> "light";
            default -> null;
        };
        if (slot == 22) {
            openExtras(player);
            return;
        }
        if (preset == null) {
            return;
        }
        if (!player.hasPermission("neotab.performance")) {
            player.sendMessage(configManager.message("no-permission"));
            return;
        }

        Integer ticks = configManager.getPerformancePresetTicks(preset);
        if (ticks == null) {
            player.sendMessage(configManager.message("performance-invalid-preset"));
            return;
        }
        configManager.setPerformancePreset(preset, ticks);
        tabUpdater.restart();
        tabUpdater.updateAllNow();
        player.sendMessage(configManager.message("performance-success", Map.of("preset", preset, "ticks", Integer.toString(configManager.getUpdateIntervalTicks()))));
        openExtras(player);
    }

    private void handleScoreboardIntervalClick(Player player, int slot) {
        String preset = switch (slot) {
            case 10 -> "smooth";
            case 13 -> "balanced";
            case 16 -> "light";
            default -> null;
        };
        if (slot == 22) {
            openExtras(player);
            return;
        }
        if (preset == null) {
            return;
        }
        if (!player.hasPermission("neotab.scoreboard.edit")) {
            player.sendMessage(configManager.message("no-permission"));
            return;
        }

        Integer ticks = configManager.getPerformancePresetTicks(preset);
        if (ticks == null) {
            player.sendMessage(configManager.message("performance-invalid-preset"));
            return;
        }
        scoreboardService.setUpdateIntervalTicks(ticks);
        player.sendMessage(configManager.message("scoreboard-interval-success", Map.of("preset", preset, "ticks", Integer.toString(configManager.getScoreboardConfig().updateIntervalTicks()))));
        openExtras(player);
    }

    private void openTimer(Player player) {
        Inventory inventory = createInventory(MenuType.TIMER, "NeoTab - ActionBar Timer");
        inventory.setItem(10, item(Material.EMERALD, "Start 5m", "Start a 5 minute timer."));
        inventory.setItem(11, item(Material.OAK_SIGN, "Custom Duration", "Type a duration in chat."));
        inventory.setItem(12, item(Material.YELLOW_DYE, "Pause", "Pause your timer."));
        inventory.setItem(13, item(Material.LIME_DYE, "Resume", "Resume your timer."));
        inventory.setItem(14, item(Material.RED_DYE, "Stop", "Stop your timer."));
        inventory.setItem(15, item(Material.WRITABLE_BOOK, "Text", "Current: " + configManager.getActionBarTimerConfig().runningFormat()));
        inventory.setItem(16, item(Material.DIAMOND, "Start 10m", "Start a 10 minute timer."));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleTimerClick(Player player, int slot) {
        if (slot == 22) {
            openExtras(player);
            return;
        }
        if (!player.hasPermission("neotab.timer")) {
            player.sendMessage(configManager.message("no-permission"));
            return;
        }

        switch (slot) {
            case 10 -> startTimer(player, 5 * 60);
            case 11 -> {
                player.closeInventory();
                chatInputManager.request(player, configManager.message("input-timer-duration-start"), (inputPlayer, input) -> {
                int durationSeconds = ActionBarTimerService.parseDurationSeconds(input);
                if (durationSeconds < 1) {
                    inputPlayer.sendMessage(configManager.message("timer-invalid-duration"));
                    return;
                }
                startTimer(inputPlayer, durationSeconds);
                });
            }
            case 12 -> {
                boolean paused = timerService.pause(player);
                player.sendMessage(configManager.message(paused ? "timer-paused" : "timer-not-running"));
            }
            case 13 -> {
                boolean resumed = timerService.resume(player);
                player.sendMessage(configManager.message(resumed ? "timer-resumed" : "timer-not-running"));
            }
            case 14 -> {
                boolean stopped = timerService.stop(player);
                player.sendMessage(configManager.message(stopped ? "timer-stopped" : "timer-not-running"));
            }
            case 15 -> {
                player.closeInventory();
                chatInputManager.request(player, configManager.message("input-timer-text-start"), (inputPlayer, input) -> {
                    configManager.setActionBarTimerRunningFormat(input);
                    inputPlayer.sendMessage(configManager.message("timer-text-changed"));
                    openTimer(inputPlayer);
                });
            }
            case 16 -> startTimer(player, 10 * 60);
            default -> {
            }
        }
    }

    private void startTimer(Player player, int seconds) {
        boolean started = timerService.start(player, seconds);
        player.sendMessage(configManager.message(started ? "timer-started" : "timer-disabled"));
    }

    private String tabIntervalLore(String preset) {
        Integer ticks = configManager.getPerformancePresetTicks(preset);
        String suffix = preset.equals(configManager.getActivePerformancePreset()) ? " Current preset." : "";
        return (ticks == null ? "Unavailable." : ticks + " ticks.") + suffix;
    }

    private String scoreboardIntervalLore(String preset) {
        Integer ticks = configManager.getPerformancePresetTicks(preset);
        String suffix = ticks != null && ticks == configManager.getScoreboardConfig().updateIntervalTicks() ? " Current preset." : "";
        return (ticks == null ? "Unavailable." : ticks + " ticks.") + suffix;
    }

    private int presetIndexForSlot(int slot) {
        for (int index = 0; index < PRESET_SLOTS.length; index++) {
            if (PRESET_SLOTS[index] == slot) {
                return index;
            }
        }
        return -1;
    }

    private String colorPresetLore(String presetName) {
        List<String> colors = HeaderColorPalette.PRESETS.get(presetName);
        return colors == null ? "Unavailable." : String.join(", ", colors);
    }

    private String currentColorSummary() {
        ArrayList<String> colors = new ArrayList<>();
        for (TextColor color : configManager.getCustomColors()) {
            colors.add(color.asHexString().toUpperCase(Locale.ROOT));
        }
        return String.join(", ", colors);
    }

    private Inventory createInventory(MenuType menuType, String title) {
        return createInventory(menuType, title, 0);
    }

    private Inventory createInventory(MenuType menuType, String title, String presetName) {
        return createInventory(menuType, title, 0, presetName);
    }

    private Inventory createInventory(MenuType menuType, String title, int lineNumber) {
        return createInventory(menuType, title, lineNumber, null);
    }

    private Inventory createInventory(MenuType menuType, String title, int lineNumber, String presetName) {
        GuiHolder holder = new GuiHolder(menuType);
        holder.setLineNumber(lineNumber);
        holder.setPresetName(presetName);
        Inventory inventory = Bukkit.createInventory(holder, 27, title);
        holder.setInventory(inventory);
        return inventory;
    }

    private ItemStack backItem() {
        return item(Material.ARROW, "Back", "Return to the previous menu.");
    }

    private ItemStack item(Material material, String name, String lore) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));

        ArrayList<Component> loreComponents = new ArrayList<>();
        loreComponents.add(Component.text(lore, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(loreComponents);
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    private enum MenuType {
        MAIN,
        TAB,
        COLORS,
        STYLE,
        SCOREBOARD,
        SCOREBOARD_PRESETS,
        SCOREBOARD_PRESET_ACTIONS,
        SCOREBOARD_LINES,
        SCOREBOARD_LINE_PRESETS,
        SCOREBOARD_STYLE,
        EXTRAS,
        TAB_INTERVAL,
        SCOREBOARD_INTERVAL,
        TIMER
    }

    private static final class GuiHolder implements InventoryHolder {
        private final MenuType menuType;
        private Inventory inventory;
        private int lineNumber;
        private String presetName;

        private GuiHolder(MenuType menuType) {
            this.menuType = menuType;
        }

        private MenuType menuType() {
            return menuType;
        }

        private int lineNumber() {
            return lineNumber;
        }

        private void setLineNumber(int lineNumber) {
            this.lineNumber = lineNumber;
        }

        private String presetName() {
            return presetName;
        }

        private void setPresetName(String presetName) {
            this.presetName = presetName;
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
