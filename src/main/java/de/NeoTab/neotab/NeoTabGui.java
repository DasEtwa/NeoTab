package de.NeoTab.neotab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
        inventory.setItem(11, guiItem(Material.NAME_TAG, "main.tab", "Tab", "Header name and animation style."));
        inventory.setItem(13, guiItem(Material.PAPER, "main.scoreboard", "Scoreboard", "Sidebar scoreboard controls."));
        inventory.setItem(15, guiItem(Material.CLOCK, "main.extras", "Extras", "Interval and ActionBar Timer."));
        inventory.setItem(17, guiItem(Material.COMPASS, "main.language", "Language", "Choose English or German."));
        inventory.setItem(22, guiItem(Material.BARRIER, "main.close", "Close", "Close this menu."));
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

        int slot = event.getRawSlot();
        GuiActions.nextTick(plugin, player, topInventory, () -> handleClick(player, guiHolder, slot));
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
            case ACTIONBAR -> handleActionBarClick(player, slot);
            case TAB_INTERVAL -> handleTabIntervalClick(player, slot);
            case SCOREBOARD_INTERVAL -> handleScoreboardIntervalClick(player, slot);
            case TIMER -> handleTimerClick(player, slot);
            case STOPWATCH -> handleStopwatchClick(player, slot);
            case PERFORMANCE_NOTICE -> handlePerformanceNoticeClick(player, slot);
            case LANGUAGE -> handleLanguageClick(player, slot);
        }
    }

    private void handleMainClick(Player player, int slot) {
        switch (slot) {
            case 11 -> openTab(player);
            case 13 -> openScoreboard(player);
            case 15 -> openExtras(player);
            case 17 -> openLanguage(player);
            case 22 -> player.closeInventory();
            default -> {
            }
        }
    }

    private void openTab(Player player) {
        Inventory inventory = createInventory(MenuType.TAB, "NeoTab - Tab");
        inventory.setItem(11, guiItem(Material.NAME_TAG, "tab.name", "Name", "Current: " + configManager.getServerNamePlain(), Map.of("current", configManager.getServerNamePlain())));
        inventory.setItem(13, guiItem(Material.PAINTING, "tab.style", "Style", "Current: " + configManager.getStyle().id(), Map.of("current", configManager.getStyle().id())));
        inventory.setItem(15, guiItem(Material.MAGENTA_DYE, "tab.colors", "Colors", "Current: " + currentColorSummary(), Map.of("current", currentColorSummary())));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void openLanguage(Player player) {
        if (!requirePermission(player, "neotab.language")) {
            return;
        }
        Inventory inventory = createInventory(MenuType.LANGUAGE, "NeoTab - Language");
        inventory.setItem(10, guiItem(Material.WRITABLE_BOOK, "language.english", "English", "Switch to English.", Map.of(
            "current", configManager.getLanguage() == ConfigManager.Language.ENGLISH ? configManager.plainMessage("status.current") : ""
        )));
        inventory.setItem(16, guiItem(Material.WRITABLE_BOOK, "language.german", "German", "Auf Deutsch wechseln.", Map.of(
            "current", configManager.getLanguage() == ConfigManager.Language.GERMAN ? configManager.plainMessage("status.current") : ""
        )));
        inventory.setItem(13, guiItem(Material.PAPER, "language.current", "Current Language", configManager.languageDisplayName(configManager.getLanguage()), Map.of(
            "current", configManager.languageDisplayName(configManager.getLanguage())
        )));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleLanguageClick(Player player, int slot) {
        if (slot == 22) {
            openMain(player, false);
            return;
        }
        if (!requirePermission(player, "neotab.language")) {
            return;
        }
        ConfigManager.Language selected = switch (slot) {
            case 10 -> ConfigManager.Language.ENGLISH;
            case 16 -> ConfigManager.Language.GERMAN;
            default -> null;
        };
        if (selected == null) {
            return;
        }
        configManager.setLanguage(selected);
        player.sendMessage(configManager.message("language-changed", Map.of(
            "language", configManager.languageDisplayName(selected)
        )));
        openLanguage(player);
    }

    private void handleTabClick(Player player, int slot) {
        switch (slot) {
            case 11 -> {
                if (!requirePermission(player, "neotab.setname")) {
                    return;
                }
                player.closeInventory();
                chatInputManager.request(player, configManager.message("input-name-start"), (inputPlayer, input) -> {
                    if (!requirePermission(inputPlayer, "neotab.setname")) {
                        return;
                    }
                    configManager.setServerName(input);
                    tabUpdater.updateAllNow();
                    inputPlayer.sendMessage(configManager.message("name-changed"));
                });
            }
            case 13 -> {
                if (requirePermission(player, "neotab.style")) {
                    openStyle(player);
                }
            }
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
        inventory.setItem(10, guiItem(Material.PURPLE_DYE, "colors.purple", "Purple", colorPresetLore("purple"), Map.of("colors", colorPresetLore("purple"))));
        inventory.setItem(11, guiItem(Material.RED_DYE, "colors.red", "Red", colorPresetLore("red"), Map.of("colors", colorPresetLore("red"))));
        inventory.setItem(12, guiItem(Material.GREEN_DYE, "colors.green", "Green", colorPresetLore("green"), Map.of("colors", colorPresetLore("green"))));
        inventory.setItem(13, guiItem(Material.YELLOW_DYE, "colors.gold", "Gold", colorPresetLore("gold"), Map.of("colors", colorPresetLore("gold"))));
        inventory.setItem(15, guiItem(Material.WRITABLE_BOOK, "colors.custom", "Custom", "Type 1-5 hex colors in chat."));
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
                if (!requirePermission(inputPlayer, "neotab.color")) {
                    return;
                }
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
            inventory.setItem(slot, guiItem(Material.PAINTING, "style." + style.id(), style.id(), lore, Map.of(
                "status", configManager.plainMessage(style == currentStyle ? "status.current-style" : "status.apply-style")
            )));
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
        if (!requirePermission(player, "neotab.style")) {
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
        boolean enabled = configManager.getScoreboardConfig().enabled();
        String toggleLore = enabled ? "Disable and save the sidebar scoreboard." : "Enable and save the sidebar scoreboard.";
        inventory.setItem(10, guiItem(enabled ? Material.REDSTONE_TORCH : Material.LEVER, "scoreboard.toggle", "Toggle: " + (enabled ? "On" : "Off"), toggleLore, Map.of(
            "status", onOff(enabled)
        )));
        inventory.setItem(12, guiItem(Material.OAK_SIGN, "scoreboard.lines", "Lines", "Edit lines 1-15."));
        inventory.setItem(14, guiItem(Material.BOOK, "scoreboard.presets", "Presets", "Save or load scoreboard presets."));
        inventory.setItem(16, guiItem(Material.PAINTING, "scoreboard.title-style", "Title Style", "Current: " + scoreboardTitleStyleLabel(), Map.of(
            "current", scoreboardTitleStyleLabel()
        )));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleScoreboardClick(Player player, int slot) {
        switch (slot) {
            case 10 -> {
                if (!requireScoreboardPermission(player, "neotab.scoreboard.toggle")) {
                    return;
                }
                boolean enabled = !configManager.getScoreboardConfig().enabled();
                scoreboardService.setGlobalEnabled(enabled);
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
        if (!requireScoreboardPermission(player, "neotab.scoreboard.presets")) {
            return;
        }

        Inventory inventory = createInventory(MenuType.SCOREBOARD_PRESETS, "NeoTab - Presets");
        inventory.setItem(4, guiItem(Material.CHEST, "scoreboard.save-current", "Save Current", "Type a preset name in chat."));

        List<String> presets = scoreboardService.listPresets();
        if (presets.isEmpty()) {
            inventory.setItem(13, guiItem(Material.GRAY_DYE, "scoreboard.no-presets", "No Presets", "Save the current scoreboard first."));
        } else {
            int count = Math.min(presets.size(), PRESET_SLOTS.length);
            for (int index = 0; index < count; index++) {
                String preset = presets.get(index);
                inventory.setItem(PRESET_SLOTS[index], guiItem(Material.BOOK, "scoreboard.preset", preset, "Open load/delete actions.", Map.of("preset", preset)));
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
        if (!requireScoreboardPermission(player, "neotab.scoreboard.presets")) {
            return;
        }

        if (slot == 4) {
            player.closeInventory();
            chatInputManager.request(player, configManager.message("input-scoreboard-preset-start"), (inputPlayer, input) -> {
                if (!requireScoreboardPermission(inputPlayer, "neotab.scoreboard.presets")) {
                    return;
                }
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
        inventory.setItem(4, guiItem(Material.BOOK, "scoreboard.saved-preset", presetName, "Saved scoreboard preset.", Map.of("preset", presetName)));
        inventory.setItem(11, guiItem(Material.LIME_DYE, "scoreboard.load", "Load", "Apply this preset."));
        inventory.setItem(15, guiItem(Material.BARRIER, "scoreboard.delete", "Delete", "Delete this preset."));
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
        if (!requireScoreboardPermission(player, "neotab.scoreboard.presets")) {
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
            inventory.setItem(index, guiItem(Material.OAK_SIGN, "scoreboard.line", "Line " + (index + 1), "Current: " + current, Map.of(
                "line", Integer.toString(index + 1),
                "current", current
            )));
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
        if (!requireScoreboardPermission(player, "neotab.scoreboard.edit")) {
            return;
        }

        int lineNumber = slot + 1;
        openScoreboardLinePresets(player, lineNumber);
    }

    private void openScoreboardLinePresets(Player player, int lineNumber) {
        Inventory inventory = createInventory(MenuType.SCOREBOARD_LINE_PRESETS, "NeoTab - Line " + lineNumber, lineNumber);
        inventory.setItem(10, guiItem(Material.PLAYER_HEAD, "scoreboard.online-players", "Online Players", "Online: {online}/{max}"));
        inventory.setItem(11, guiItem(Material.NAME_TAG, "scoreboard.player-name", "Player Name", "Player: {player}"));
        inventory.setItem(12, guiItem(Material.COMPASS, "scoreboard.ping", "Ping", "Ping: {ping}ms"));
        inventory.setItem(13, guiItem(Material.REDSTONE, "scoreboard.ram", "RAM", "RAM: {ram_used}/{ram_max} MB"));
        inventory.setItem(15, guiItem(Material.WRITABLE_BOOK, "scoreboard.custom", "Custom", "Type this line in chat."));
        inventory.setItem(16, guiItem(Material.BARRIER, "scoreboard.clear", "Clear", "Clear this line."));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleScoreboardLinePresetsClick(Player player, int lineNumber, int slot) {
        if (slot == 22) {
            openScoreboardLines(player);
            return;
        }
        if (!requireScoreboardPermission(player, "neotab.scoreboard.edit")) {
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
                if (!requireScoreboardPermission(inputPlayer, "neotab.scoreboard.edit")) {
                    return;
                }
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
        boolean animationEnabled = configManager.getScoreboardConfig().titleAnimationEnabled();
        inventory.setItem(4, guiItem(Material.BARRIER, "scoreboard-style.off", "Off", animationEnabled ? "Disable title animation." : "Current style.", Map.of(
            "status", configManager.plainMessage(animationEnabled ? "status.disable-title-animation" : "status.current-style")
        )));
        int slot = 10;
        AnimationUtils.Style currentStyle = configManager.getScoreboardConfig().titleAnimationStyle();
        for (AnimationUtils.Style style : AnimationUtils.Style.values()) {
            String lore = configManager.getScoreboardConfig().titleAnimationEnabled() && style == currentStyle ? "Current style." : "Apply this title animation.";
            inventory.setItem(slot, guiItem(Material.PAINTING, "scoreboard-style." + style.id(), style.id(), lore, Map.of(
                "status", configManager.plainMessage(configManager.getScoreboardConfig().titleAnimationEnabled() && style == currentStyle
                    ? "status.current-style" : "status.apply-style")
            )));
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
        if (!requireScoreboardPermission(player, "neotab.scoreboard.edit")) {
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
        return scoreboardConfig.titleAnimationEnabled()
            ? scoreboardConfig.titleAnimationStyle().id()
            : configManager.plainMessage("status.off");
    }

    private void openExtras(Player player) {
        Inventory inventory = createInventory(MenuType.EXTRAS, "NeoTab - Extras");
        inventory.setItem(11, guiItem(Material.FEATHER, "extras.tab-interval", "Tab Interval", "Current: " + configManager.getUpdateIntervalTicks() + " ticks.", Map.of("current", Integer.toString(configManager.getUpdateIntervalTicks()))));
        inventory.setItem(13, guiItem(Material.COMPARATOR, "extras.scoreboard-interval", "Scoreboard Interval", "Current: " + configManager.getScoreboardConfig().updateIntervalTicks() + " ticks.", Map.of("current", Integer.toString(configManager.getScoreboardConfig().updateIntervalTicks()))));
        inventory.setItem(15, guiItem(Material.CLOCK, "extras.actionbar", "ActionBar", "Timer, stopwatch, clock, popups, and messages."));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleExtrasClick(Player player, int slot) {
        switch (slot) {
            case 11 -> openTabInterval(player);
            case 13 -> openScoreboardInterval(player);
            case 15 -> openActionBar(player);
            case 22 -> openMain(player, false);
            default -> {
            }
        }
    }

    private void openActionBar(Player player) {
        Inventory inventory = createInventory(MenuType.ACTIONBAR, "NeoTab - ActionBar");
        ConfigManager.ActionBarConfig config = configManager.getActionBarConfig();
        inventory.setItem(9, guiItem(Material.CLOCK, "actionbar.timer", "Timer", "Countdown controls."));
        inventory.setItem(10, guiItem(Material.COMPASS, "actionbar.stopwatch", "Stopwatch", "Count upward from zero."));
        inventory.setItem(11, guiItem(Material.DAYLIGHT_DETECTOR, "actionbar.clock", "Clock: " + onOff(config.clock().enabled()), "Shows real time every " + config.clock().intervalSeconds() + "s.", Map.of("status", onOff(config.clock().enabled()), "seconds", Integer.toString(config.clock().intervalSeconds()))));
        inventory.setItem(12, guiItem(Material.BELL, "actionbar.welcome", "Welcome: " + onOff(config.welcome().enabled()), "Shows a join ActionBar message.", Map.of("status", onOff(config.welcome().enabled()))));
        inventory.setItem(13, guiItem(Material.PAPER, "actionbar.random-messages", "Random Messages: " + onOff(config.randomMessages().enabled()), "Shows occasional low-priority messages.", Map.of("status", onOff(config.randomMessages().enabled()))));
        inventory.setItem(14, guiItem(Material.GRASS_BLOCK, "actionbar.biome-popup", "Biome Popup: " + onOff(config.biomePopup().enabled()), "Shows when a player enters a new biome.", Map.of("status", onOff(config.biomePopup().enabled()))));
        inventory.setItem(15, guiItem(Material.EXPERIENCE_BOTTLE, "actionbar.achievements", "Achievements: " + onOff(config.achievements().enabled()), "Counts visible Minecraft advancements.", Map.of("status", onOff(config.achievements().enabled()))));
        inventory.setItem(16, guiItem(Material.REDSTONE_TORCH, "actionbar.performance", "Performance Notice", "Nearest player and structure popup settings."));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleActionBarClick(Player player, int slot) {
        switch (slot) {
            case 9 -> openTimer(player);
            case 10 -> openStopwatch(player);
            case 11 -> toggleActionBarModule(player, "clock", "neotab.extras.clock", configManager.plainMessage("module.clock"));
            case 12 -> toggleActionBarModule(player, "welcome", "neotab.extras.welcome", configManager.plainMessage("module.welcome"));
            case 13 -> toggleActionBarModule(player, "random-messages", "neotab.extras.randommessages", configManager.plainMessage("module.random-messages"));
            case 14 -> toggleActionBarModule(player, "biome-popup", "neotab.extras.biome", configManager.plainMessage("module.biome-popup"));
            case 15 -> toggleActionBarModule(player, "achievements", "neotab.extras.achievements", configManager.plainMessage("module.achievements"));
            case 16 -> {
                player.sendMessage(configManager.message("performance-notice-warning"));
                openPerformanceNotice(player);
            }
            case 22 -> openExtras(player);
            default -> {
            }
        }
    }

    private void openTabInterval(Player player) {
        Inventory inventory = createInventory(MenuType.TAB_INTERVAL, "NeoTab - Tab Interval");
        inventory.setItem(10, guiItem(Material.FEATHER, "interval.smooth", "Smooth", tabIntervalLore("smooth"), intervalPlaceholders("smooth", false)));
        inventory.setItem(13, guiItem(Material.GOLD_INGOT, "interval.balanced", "Balanced", tabIntervalLore("balanced"), intervalPlaceholders("balanced", false)));
        inventory.setItem(16, guiItem(Material.IRON_INGOT, "interval.light", "Light", tabIntervalLore("light"), intervalPlaceholders("light", false)));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void openScoreboardInterval(Player player) {
        Inventory inventory = createInventory(MenuType.SCOREBOARD_INTERVAL, "NeoTab - SB Interval");
        inventory.setItem(10, guiItem(Material.FEATHER, "interval.smooth", "Smooth", scoreboardIntervalLore("smooth"), intervalPlaceholders("smooth", true)));
        inventory.setItem(13, guiItem(Material.GOLD_INGOT, "interval.balanced", "Balanced", scoreboardIntervalLore("balanced"), intervalPlaceholders("balanced", true)));
        inventory.setItem(16, guiItem(Material.IRON_INGOT, "interval.light", "Light", scoreboardIntervalLore("light"), intervalPlaceholders("light", true)));
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
        if (!requireScoreboardPermission(player, "neotab.scoreboard.edit")) {
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
        inventory.setItem(10, guiItem(Material.EMERALD, "timer.start-5m", "Start 5m", "Start a 5 minute timer."));
        inventory.setItem(11, guiItem(Material.OAK_SIGN, "timer.custom-duration", "Custom Duration", "Type a duration in chat."));
        inventory.setItem(12, guiItem(Material.YELLOW_DYE, "timer.pause", "Pause", "Pause your timer."));
        inventory.setItem(13, guiItem(Material.LIME_DYE, "timer.resume", "Resume", "Resume your timer."));
        inventory.setItem(14, guiItem(Material.RED_DYE, "timer.stop", "Stop", "Stop your timer."));
        inventory.setItem(15, guiItem(Material.WRITABLE_BOOK, "timer.text", "Text", "Current: " + configManager.getActionBarTimerConfig().runningFormat(), Map.of("current", configManager.getActionBarTimerConfig().runningFormat())));
        inventory.setItem(16, guiItem(Material.DIAMOND, "timer.start-10m", "Start 10m", "Start a 10 minute timer."));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleTimerClick(Player player, int slot) {
        if (slot == 22) {
            openActionBar(player);
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
                    if (!requirePermission(inputPlayer, "neotab.timer")) {
                        return;
                    }
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
                if (!requireTimerTextPermission(player)) {
                    return;
                }
                player.closeInventory();
                chatInputManager.request(player, configManager.message("input-timer-text-start"), (inputPlayer, input) -> {
                    if (!requireTimerTextPermission(inputPlayer)) {
                        return;
                    }
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

    private void openStopwatch(Player player) {
        Inventory inventory = createInventory(MenuType.STOPWATCH, "NeoTab - Stopwatch");
        inventory.setItem(10, guiItem(Material.EMERALD, "stopwatch.start", "Start", "Start your stopwatch."));
        inventory.setItem(12, guiItem(Material.YELLOW_DYE, "stopwatch.pause", "Pause", "Pause your stopwatch."));
        inventory.setItem(13, guiItem(Material.LIME_DYE, "stopwatch.resume", "Resume", "Resume your stopwatch."));
        inventory.setItem(14, guiItem(Material.RED_DYE, "stopwatch.stop", "Stop", "Stop your stopwatch."));
        inventory.setItem(16, guiItem(Material.BARRIER, "stopwatch.reset", "Reset", "Reset to zero."));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleStopwatchClick(Player player, int slot) {
        if (slot == 22) {
            openActionBar(player);
            return;
        }
        if (!player.hasPermission("neotab.extras.stopwatch")) {
            player.sendMessage(configManager.message("no-permission"));
            return;
        }

        switch (slot) {
            case 10 -> {
                boolean started = plugin.getStopwatchService().start(player);
                player.sendMessage(configManager.message(started ? "stopwatch-started" : "stopwatch-conflict"));
            }
            case 12 -> {
                boolean paused = plugin.getStopwatchService().pause(player);
                player.sendMessage(configManager.message(paused ? "stopwatch-paused" : "stopwatch-not-running"));
            }
            case 13 -> {
                boolean resumed = plugin.getStopwatchService().resume(player);
                player.sendMessage(configManager.message(resumed ? "stopwatch-resumed" : "stopwatch-not-running"));
            }
            case 14 -> {
                boolean stopped = plugin.getStopwatchService().stop(player);
                player.sendMessage(configManager.message(stopped ? "stopwatch-stopped" : "stopwatch-not-running"));
            }
            case 16 -> {
                boolean reset = plugin.getStopwatchService().reset(player);
                player.sendMessage(configManager.message(reset ? "stopwatch-reset" : "stopwatch-not-running"));
            }
            default -> {
            }
        }
    }

    private void openPerformanceNotice(Player player) {
        Inventory inventory = createInventory(MenuType.PERFORMANCE_NOTICE, "NeoTab - Performance");
        ConfigManager.ActionBarConfig config = configManager.getActionBarConfig();
        inventory.setItem(11, guiItem(Material.PLAYER_HEAD, "performance.nearest-player", "Nearest Player: " + onOff(config.nearestPlayer().enabled()), "Can be heavier on large servers. Interval: " + config.nearestPlayer().checkIntervalTicks() + " ticks.", Map.of(
            "status", onOff(config.nearestPlayer().enabled()),
            "ticks", Integer.toString(config.nearestPlayer().checkIntervalTicks())
        )));
        inventory.setItem(15, guiItem(Material.STRUCTURE_BLOCK, "performance.structure-popup", "Structure Popup", "Experimental placeholder. Detection is planned."));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handlePerformanceNoticeClick(Player player, int slot) {
        switch (slot) {
            case 11 -> toggleActionBarModule(player, "nearest-player", "neotab.extras.nearestplayer", configManager.plainMessage("module.nearest-player"));
            case 15 -> {
                player.sendMessage(configManager.message("structure-popup-coming-soon"));
                openPerformanceNotice(player);
            }
            case 22 -> openActionBar(player);
            default -> {
            }
        }
    }

    private void toggleActionBarModule(Player player, String moduleName, String permission, String displayName) {
        if (!player.hasPermission(permission)) {
            player.sendMessage(configManager.message("no-permission"));
            return;
        }

        boolean enabled = switch (moduleName) {
            case "clock" -> !configManager.getActionBarConfig().clock().enabled();
            case "welcome" -> !configManager.getActionBarConfig().welcome().enabled();
            case "random-messages" -> !configManager.getActionBarConfig().randomMessages().enabled();
            case "biome-popup" -> !configManager.getActionBarConfig().biomePopup().enabled();
            case "achievements" -> !configManager.getActionBarConfig().achievements().enabled();
            case "nearest-player" -> !configManager.getActionBarConfig().nearestPlayer().enabled();
            default -> false;
        };

        configManager.setActionBarModuleEnabled(moduleName, enabled);
        plugin.restartActionBarExtras();
        player.sendMessage(configManager.message(enabled ? "actionbar-module-enabled" : "actionbar-module-disabled", Map.of("module", displayName)));
        if ("nearest-player".equals(moduleName)) {
            player.sendMessage(configManager.message("performance-notice-warning"));
            openPerformanceNotice(player);
            return;
        }
        openActionBar(player);
    }

    private boolean requirePermission(Player player, String permission) {
        if (player.hasPermission(permission)) {
            return true;
        }
        player.sendMessage(configManager.message("no-permission"));
        return false;
    }

    private boolean requireScoreboardPermission(Player player, String childPermission) {
        if (hasScoreboardMutationPermission(
            player.hasPermission("neotab.scoreboard"),
            player.hasPermission(childPermission)
        )) {
            return true;
        }
        player.sendMessage(configManager.message("no-permission"));
        return false;
    }

    private boolean requireTimerTextPermission(Player player) {
        if (TabCommand.canEditTimerText(
            player.hasPermission("neotab.timer"),
            player.hasPermission("neotab.timer.admin")
        )) {
            return true;
        }
        player.sendMessage(configManager.message("no-permission"));
        return false;
    }

    static boolean hasScoreboardMutationPermission(boolean basePermission, boolean childPermission) {
        return basePermission && childPermission;
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

    private Map<String, String> intervalPlaceholders(String preset, boolean scoreboard) {
        Integer ticks = configManager.getPerformancePresetTicks(preset);
        boolean current = scoreboard
            ? ticks != null && ticks == configManager.getScoreboardConfig().updateIntervalTicks()
            : preset.equals(configManager.getActivePerformancePreset());
        return Map.of(
            "ticks", ticks == null ? "?" : Integer.toString(ticks),
            "status", current ? configManager.plainMessage("status.current") : ""
        );
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

    private String onOff(boolean enabled) {
        return configManager.plainMessage(enabled ? "status.on" : "status.off");
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
        Map<String, String> placeholders = presetName == null
            ? (lineNumber > 0 ? Map.of("line", Integer.toString(lineNumber)) : Map.of())
            : Map.of("preset", presetName);
        String titleKey = switch (menuType) {
            case MAIN -> "main";
            case TAB -> "tab";
            case COLORS -> "colors";
            case STYLE -> "style";
            case SCOREBOARD -> "scoreboard";
            case SCOREBOARD_PRESETS -> "scoreboard-presets";
            case SCOREBOARD_PRESET_ACTIONS -> "scoreboard-preset";
            case SCOREBOARD_LINES -> "scoreboard-lines";
            case SCOREBOARD_LINE_PRESETS -> "scoreboard-line-presets";
            case SCOREBOARD_STYLE -> "scoreboard-style";
            case EXTRAS -> "extras";
            case ACTIONBAR -> "actionbar";
            case TAB_INTERVAL -> "tab-interval";
            case SCOREBOARD_INTERVAL -> "scoreboard-interval";
            case TIMER -> "timer";
            case STOPWATCH -> "stopwatch";
            case PERFORMANCE_NOTICE -> "performance";
            case LANGUAGE -> "language";
        };
        String localizedTitle = configManager.messageOrDefault("gui.title." + titleKey, title, placeholders);
        Inventory inventory = Bukkit.createInventory(holder, 27, localizedTitle);
        holder.setInventory(inventory);
        return inventory;
    }

    private ItemStack backItem() {
        return guiItem(Material.ARROW, "common.back", "Back", "Return to the previous menu.");
    }

    private ItemStack guiItem(Material material, String key, String fallbackName, String fallbackLore) {
        return guiItem(material, key, fallbackName, fallbackLore, Map.of());
    }

    private ItemStack guiItem(Material material, String key, String fallbackName, String fallbackLore, Map<String, String> placeholders) {
        String name = configManager.messageOrDefault(
            "gui.item." + key + ".name",
            "<light_purple>" + fallbackName + "</light_purple>",
            placeholders
        );
        String lore = configManager.messageOrDefault(
            "gui.item." + key + ".lore",
            "<gray>" + fallbackLore + "</gray>",
            placeholders
        );
        return rawItem(material, name, lore);
    }

    private ItemStack rawItem(Material material, String name, String lore) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        meta.setDisplayName(ChatColor.RESET.toString() + name);
        meta.setLore(List.of(ChatColor.RESET.toString() + lore));
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
        ACTIONBAR,
        TAB_INTERVAL,
        SCOREBOARD_INTERVAL,
        TIMER,
        STOPWATCH,
        PERFORMANCE_NOTICE,
        LANGUAGE
    }

    private static final class GuiHolder implements NeoTabInventoryHolder {
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
