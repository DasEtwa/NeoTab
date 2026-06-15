package de.NeoTab.neotab;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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

        handleClick(player, guiHolder.menuType(), event.getRawSlot());
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof GuiHolder) {
            event.setCancelled(true);
        }
    }

    private void handleClick(Player player, MenuType menuType, int slot) {
        switch (menuType) {
            case MAIN -> handleMainClick(player, slot);
            case TAB -> handleTabClick(player, slot);
            case STYLE -> handleStyleClick(player, slot);
            case SCOREBOARD -> handleScoreboardClick(player, slot);
            case SCOREBOARD_LINES -> handleScoreboardLinesClick(player, slot);
            case EXTRAS -> handleExtrasClick(player, slot);
            case INTERVAL -> handleIntervalClick(player, slot);
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
        inventory.setItem(11, item(Material.NAME_TAG, "Name", "Type a new tab header name."));
        inventory.setItem(13, item(Material.PAINTING, "Style", "Choose an animation style."));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleTabClick(Player player, int slot) {
        switch (slot) {
            case 11 -> chatInputManager.request(player, configManager.message("input-name-start"), (inputPlayer, input) -> {
                configManager.setServerName(input);
                tabUpdater.updateAllNow();
                inputPlayer.sendMessage(configManager.message("name-changed"));
            });
            case 13 -> openStyle(player);
            case 22 -> openMain(player, false);
            default -> {
            }
        }
    }

    private void openStyle(Player player) {
        Inventory inventory = createInventory(MenuType.STYLE, "NeoTab - Style");
        int slot = 10;
        for (AnimationUtils.Style style : AnimationUtils.Style.values()) {
            inventory.setItem(slot, item(Material.PAINTING, style.id(), "Apply this header animation."));
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
        String toggleLore = scoreboardService.isEnabled(player) ? "Disable your sidebar scoreboard." : "Enable your sidebar scoreboard.";
        inventory.setItem(10, item(Material.REDSTONE_TORCH, "Toggle", toggleLore));
        inventory.setItem(12, item(Material.BOOK, "Presets", "Preset menu foundation."));
        inventory.setItem(14, item(Material.OAK_SIGN, "Lines", "Edit lines 1-15."));
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
            case 12 -> player.sendMessage(configManager.message("scoreboard-presets-coming-soon"));
            case 14 -> openScoreboardLines(player);
            case 22 -> openMain(player, false);
            default -> {
            }
        }
    }

    private void openScoreboardLines(Player player) {
        Inventory inventory = createInventory(MenuType.SCOREBOARD_LINES, "NeoTab - Lines");
        for (int index = 0; index < ConfigManager.MAX_SCOREBOARD_LINES; index++) {
            inventory.setItem(index, item(Material.OAK_SIGN, "Line " + (index + 1), "Click to edit this line."));
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
        chatInputManager.request(player, configManager.message("input-scoreboard-line-start", Map.of("line", Integer.toString(lineNumber))), (inputPlayer, input) -> {
            scoreboardService.setLine(lineNumber, input);
            inputPlayer.sendMessage(configManager.message("scoreboard-line-changed", Map.of("line", Integer.toString(lineNumber))));
        });
    }

    private void openExtras(Player player) {
        Inventory inventory = createInventory(MenuType.EXTRAS, "NeoTab - Extras");
        inventory.setItem(11, item(Material.COMPARATOR, "Interval", "Choose a performance preset."));
        inventory.setItem(15, item(Material.CLOCK, "ActionBar Timer", "Start or control a countdown."));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleExtrasClick(Player player, int slot) {
        switch (slot) {
            case 11 -> openInterval(player);
            case 15 -> openTimer(player);
            case 22 -> openMain(player, false);
            default -> {
            }
        }
    }

    private void openInterval(Player player) {
        Inventory inventory = createInventory(MenuType.INTERVAL, "NeoTab - Interval");
        inventory.setItem(10, item(Material.FEATHER, "Smooth", "3 ticks."));
        inventory.setItem(13, item(Material.GOLD_INGOT, "Balanced", "10 ticks."));
        inventory.setItem(16, item(Material.IRON_INGOT, "Light", "20 ticks."));
        inventory.setItem(22, backItem());
        player.openInventory(inventory);
    }

    private void handleIntervalClick(Player player, int slot) {
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

    private void openTimer(Player player) {
        Inventory inventory = createInventory(MenuType.TIMER, "NeoTab - ActionBar Timer");
        inventory.setItem(10, item(Material.EMERALD, "Start 5m", "Start a 5 minute timer."));
        inventory.setItem(12, item(Material.YELLOW_DYE, "Pause", "Pause your timer."));
        inventory.setItem(13, item(Material.LIME_DYE, "Resume", "Resume your timer."));
        inventory.setItem(14, item(Material.RED_DYE, "Stop", "Stop your timer."));
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
            case 16 -> startTimer(player, 10 * 60);
            default -> {
            }
        }
    }

    private void startTimer(Player player, int seconds) {
        boolean started = timerService.start(player, seconds);
        player.sendMessage(configManager.message(started ? "timer-started" : "timer-disabled"));
    }

    private Inventory createInventory(MenuType menuType, String title) {
        GuiHolder holder = new GuiHolder(menuType);
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
        STYLE,
        SCOREBOARD,
        SCOREBOARD_LINES,
        EXTRAS,
        INTERVAL,
        TIMER
    }

    private static final class GuiHolder implements InventoryHolder {
        private final MenuType menuType;
        private Inventory inventory;

        private GuiHolder(MenuType menuType) {
            this.menuType = menuType;
        }

        private MenuType menuType() {
            return menuType;
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
