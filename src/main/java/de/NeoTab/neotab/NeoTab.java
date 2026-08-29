package de.NeoTab.neotab;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class NeoTab extends JavaPlugin implements Listener {
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private ConfigManager configManager;
    private TabUpdater tabUpdater;
    private UpdateChecker updateChecker;
    private ChatInputManager chatInputManager;
    private ActionBarService actionBarService;
    private ActionBarTextFormatter actionBarTextFormatter;
    private ScoreboardService scoreboardService;
    private RegionSelectionManager regionSelectionManager;
    private WorldEditSelectionProvider worldEditSelectionProvider;
    private RegionManager regionManager;
    private RegionWandListener regionWandListener;
    private RegionMoveListener regionMoveListener;
    private RegionProfileGui regionProfileGui;
    private RegionCommand regionCommand;
    private ActionBarTimerService actionBarTimerService;
    private StopwatchService stopwatchService;
    private ClockActionBarModule clockActionBarModule;
    private WelcomeActionBarModule welcomeActionBarModule;
    private RandomActionBarModule randomActionBarModule;
    private BiomePopupModule biomePopupModule;
    private NearestPlayerModule nearestPlayerModule;
    private AdvancementCounterModule advancementCounterModule;
    private StructurePopupModule structurePopupModule;
    private NeoTabGui neoTabGui;
    private LuckPermsSupport luckPermsSupport;
    private boolean luckPermsWarned;
    private AsyncYamlWriter yamlWriter;
    private NeoTabMetrics neoTabMetrics;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        yamlWriter = new AsyncYamlWriter(getLogger());
        configManager = new ConfigManager(this, yamlWriter);
        yamlWriter.setMessageResolver(configManager::plainMessage);
        hookLuckPerms();

        tabUpdater = new TabUpdater(this, configManager);
        updateChecker = new UpdateChecker(this, configManager);
        chatInputManager = new ChatInputManager(this, configManager);
        actionBarService = new ActionBarService(this, configManager);
        actionBarTextFormatter = new ActionBarTextFormatter(this, configManager);
        scoreboardService = new ScoreboardService(this, configManager);
        regionSelectionManager = new RegionSelectionManager();
        worldEditSelectionProvider = new WorldEditSelectionProvider(this);
        regionManager = new RegionManager(this, configManager, regionSelectionManager, worldEditSelectionProvider, yamlWriter);
        tabUpdater.setRegionManager(regionManager);
        scoreboardService.setRegionManager(regionManager);
        regionWandListener = new RegionWandListener(this, configManager, regionSelectionManager);
        regionMoveListener = new RegionMoveListener(regionManager);
        regionProfileGui = new RegionProfileGui(this, configManager, regionManager, regionSelectionManager, chatInputManager);
        actionBarTimerService = new ActionBarTimerService(this, configManager, actionBarService, actionBarTextFormatter);
        stopwatchService = new StopwatchService(this, configManager, actionBarService, actionBarTextFormatter);
        actionBarTimerService.setStopwatchService(stopwatchService);
        stopwatchService.setTimerService(actionBarTimerService);
        clockActionBarModule = new ClockActionBarModule(this, configManager, actionBarService, actionBarTextFormatter);
        welcomeActionBarModule = new WelcomeActionBarModule(this, configManager, actionBarService, actionBarTextFormatter);
        randomActionBarModule = new RandomActionBarModule(this, configManager, actionBarService, actionBarTextFormatter);
        biomePopupModule = new BiomePopupModule(this, configManager, actionBarService, actionBarTextFormatter);
        nearestPlayerModule = new NearestPlayerModule(this, configManager, actionBarService, actionBarTextFormatter);
        advancementCounterModule = new AdvancementCounterModule(this, configManager, actionBarService, actionBarTextFormatter);
        structurePopupModule = new StructurePopupModule(this, configManager);
        neoTabGui = new NeoTabGui(this, configManager, tabUpdater, scoreboardService, actionBarTimerService, chatInputManager);
        regionCommand = new RegionCommand(configManager, regionManager, regionSelectionManager, regionWandListener, regionProfileGui);
        registerCommands();

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(chatInputManager, this);
        getServer().getPluginManager().registerEvents(actionBarService, this);
        getServer().getPluginManager().registerEvents(scoreboardService, this);
        getServer().getPluginManager().registerEvents(regionWandListener, this);
        getServer().getPluginManager().registerEvents(regionMoveListener, this);
        getServer().getPluginManager().registerEvents(regionProfileGui, this);
        getServer().getPluginManager().registerEvents(actionBarTimerService, this);
        getServer().getPluginManager().registerEvents(stopwatchService, this);
        getServer().getPluginManager().registerEvents(welcomeActionBarModule, this);
        getServer().getPluginManager().registerEvents(biomePopupModule, this);
        getServer().getPluginManager().registerEvents(advancementCounterModule, this);
        getServer().getPluginManager().registerEvents(neoTabGui, this);
        tabUpdater.initializeCounts(getServer().getOnlinePlayers().size(), getServer().getMaxPlayers());
        tabUpdater.start();
        tabUpdater.updateAllNow();
        scoreboardService.start();
        scoreboardService.warnIfAggressiveInterval();
        startActionBarExtras();
        updateChecker.start();
        initializeMetrics();

        configManager.log(java.util.logging.Level.INFO, "log.plugin.enabled");
    }

    @Override
    public void onDisable() {
        closePluginInventories();
        if (chatInputManager != null) {
            chatInputManager.cancelAll(false);
        }
        if (actionBarTimerService != null) {
            actionBarTimerService.stopAll();
        }
        stopActionBarExtras();
        if (scoreboardService != null) {
            scoreboardService.stop();
        }
        if (regionManager != null) {
            regionManager.shutdown();
        }
        if (tabUpdater != null) {
            tabUpdater.stop();
            tabUpdater.clearAll();
        }
        if (updateChecker != null) {
            updateChecker.stop();
        }
        if (neoTabMetrics != null) {
            neoTabMetrics.shutdown();
        }
        if (yamlWriter != null) {
            yamlWriter.close();
        }
        if (configManager != null) {
            configManager.log(java.util.logging.Level.INFO, "log.plugin.disabled");
        }
    }

    void closePluginInventories() {
        for (Player player : getServer().getOnlinePlayers()) {
            org.bukkit.inventory.InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
            if (holder instanceof NeoTabInventoryHolder) {
                player.closeInventory();
            }
        }
    }

    public void logInfo(String message) {
        getLogger().info(PlainTextComponentSerializer.plainText().serialize(miniMessage.deserialize(message)));
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public TabUpdater getTabUpdater() {
        return tabUpdater;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public ChatInputManager getChatInputManager() {
        return chatInputManager;
    }

    public ScoreboardService getScoreboardService() {
        return scoreboardService;
    }

    public RegionManager getRegionManager() {
        return regionManager;
    }

    public ActionBarTimerService getActionBarTimerService() {
        return actionBarTimerService;
    }

    public StopwatchService getStopwatchService() {
        return stopwatchService;
    }

    public void restartActionBarExtras() {
        if (actionBarService == null) {
            return;
        }

        if (!isActionBarGloballyEnabled()) {
            stopActionBarExtras();
            return;
        }

        actionBarTextFormatter.refresh();
        actionBarService.restart();
        actionBarTimerService.restart();
        stopwatchService.restart();
        clockActionBarModule.restart();
        welcomeActionBarModule.restart();
        randomActionBarModule.restart();
        biomePopupModule.restart();
        nearestPlayerModule.restart();
        advancementCounterModule.restart();
        structurePopupModule.restart();
    }

    public NeoTabGui getNeoTabGui() {
        return neoTabGui;
    }

    public void refreshMetrics() {
        if (neoTabMetrics != null) {
            neoTabMetrics.refresh();
        }
    }

    LuckPermsSupport ensureLuckPerms() {
        if (luckPermsSupport == null && configManager != null && configManager.isLuckPermsPrefixEnabled()) {
            luckPermsSupport = fetchLuckPerms(true);
        }
        return luckPermsSupport;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (tabUpdater != null) {
            tabUpdater.handleJoin(event.getPlayer());
        }
        if (updateChecker != null) {
            java.util.UUID uuid = event.getPlayer().getUniqueId();
            getServer().getScheduler().runTaskLater(this, () -> {
                Player player = getServer().getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    updateChecker.notifyPlayer(player);
                }
            }, 20L);
        }
        if (scoreboardService != null) {
            scoreboardService.handleJoin(event.getPlayer());
        }
        if (regionManager != null) {
            regionManager.handleMove(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (tabUpdater != null) {
            tabUpdater.handleDisconnect(event.getPlayer().getUniqueId());
            tabUpdater.handleQuit();
        }
        if (regionManager != null) {
            regionManager.handleQuit(event.getPlayer());
        }
    }

    private void registerCommands() {
        PluginCommand command = getCommand("tab");
        if (command == null) {
            configManager.log(java.util.logging.Level.SEVERE, "log.plugin.command-registration-failed");
            return;
        }

        TabCommand tabCommand = new TabCommand(this, configManager, tabUpdater, updateChecker, chatInputManager, scoreboardService, actionBarTimerService, neoTabGui, regionCommand);
        command.setExecutor(tabCommand);
        command.setTabCompleter(tabCommand);
    }

    private void startActionBarExtras() {
        if (!isActionBarGloballyEnabled()) {
            stopActionBarExtras();
            return;
        }

        actionBarService.start();
        actionBarTimerService.restart();
        stopwatchService.start();
        clockActionBarModule.start();
        welcomeActionBarModule.start();
        randomActionBarModule.start();
        biomePopupModule.start();
        nearestPlayerModule.start();
        advancementCounterModule.start();
        structurePopupModule.start();
    }

    private void stopActionBarExtras() {
        if (clockActionBarModule != null) {
            clockActionBarModule.stop();
        }
        if (welcomeActionBarModule != null) {
            welcomeActionBarModule.stop();
        }
        if (randomActionBarModule != null) {
            randomActionBarModule.stop();
        }
        if (biomePopupModule != null) {
            biomePopupModule.stop();
        }
        if (nearestPlayerModule != null) {
            nearestPlayerModule.stop();
        }
        if (advancementCounterModule != null) {
            advancementCounterModule.stop();
        }
        if (structurePopupModule != null) {
            structurePopupModule.stop();
        }
        if (stopwatchService != null) {
            stopwatchService.stopAll();
        }
        if (actionBarTimerService != null) {
            actionBarTimerService.stopAll();
        }
        if (actionBarService != null) {
            actionBarService.stop();
        }
    }

    private boolean isActionBarGloballyEnabled() {
        return configManager != null && configManager.getActionBarConfig().enabled();
    }

    private void initializeMetrics() {
        try {
            neoTabMetrics = new NeoTabMetrics(this);
            neoTabMetrics.start();
        } catch (Throwable error) {
            neoTabMetrics = null;
            try {
                if (getConfig().getBoolean("debug", false)) {
                    configManager.log(java.util.logging.Level.FINE, "log.plugin.bstats-failed", java.util.Map.of(), error);
                }
            } catch (Throwable ignored) {
                // Metrics failures must never affect the plugin lifecycle.
            }
        }
    }

    private void hookLuckPerms() {
        if (configManager != null && !configManager.isLuckPermsPrefixEnabled()) {
            luckPermsSupport = null;
            return;
        }
        luckPermsSupport = fetchLuckPerms(true);
    }

    private LuckPermsSupport fetchLuckPerms(boolean warn) {
        if (!getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            warnLuckPermsUnavailable(warn, null);
            return null;
        }

        try {
            LuckPermsSupport resolved = LuckPermsIntegration.create(this);
            if (resolved == null) {
                warnLuckPermsUnavailable(warn, null);
            }
            return resolved;
        } catch (LinkageError | RuntimeException ex) {
            warnLuckPermsUnavailable(warn, ex);
            return null;
        }
    }

    private void warnLuckPermsUnavailable(boolean warn, Throwable cause) {
        if (!warn || luckPermsWarned) {
            return;
        }
        luckPermsWarned = true;
        String detail = cause == null || cause.getMessage() == null ? "" : " (" + cause.getMessage() + ")";
        configManager.log(java.util.logging.Level.WARNING, "log.plugin.luckperms-unavailable", java.util.Map.of("detail", detail));
    }
}
