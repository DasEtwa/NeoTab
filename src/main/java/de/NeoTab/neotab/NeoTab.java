package de.NeoTab.neotab;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.luckperms.api.LuckPerms;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
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
    private LuckPerms luckPerms;
    private boolean luckPermsWarned;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        configManager = new ConfigManager(this);
        hookLuckPerms();

        tabUpdater = new TabUpdater(this, configManager);
        updateChecker = new UpdateChecker(this, configManager);
        chatInputManager = new ChatInputManager(this, configManager);
        actionBarService = new ActionBarService(this, configManager);
        actionBarTextFormatter = new ActionBarTextFormatter(this, configManager);
        scoreboardService = new ScoreboardService(this, configManager);
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
        registerCommands();

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(chatInputManager, this);
        getServer().getPluginManager().registerEvents(actionBarService, this);
        getServer().getPluginManager().registerEvents(scoreboardService, this);
        getServer().getPluginManager().registerEvents(actionBarTimerService, this);
        getServer().getPluginManager().registerEvents(stopwatchService, this);
        getServer().getPluginManager().registerEvents(welcomeActionBarModule, this);
        getServer().getPluginManager().registerEvents(biomePopupModule, this);
        getServer().getPluginManager().registerEvents(neoTabGui, this);
        tabUpdater.initializeCounts(getServer().getOnlinePlayers().size(), getServer().getMaxPlayers());
        tabUpdater.start();
        tabUpdater.updateAllNow();
        scoreboardService.start();
        startActionBarExtras();
        updateChecker.start();

        logInfo("<gradient:#AA00AA:#BA55D3><bold>NeoTab enabled.</bold></gradient>");
    }

    @Override
    public void onDisable() {
        if (neoTabGui != null) {
            neoTabGui.closeAll();
        }
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
        if (tabUpdater != null) {
            tabUpdater.stop();
            tabUpdater.clearAll();
        }
        if (updateChecker != null) {
            updateChecker.stop();
        }
        logInfo("<gradient:#AA00AA:#BA55D3><bold>NeoTab disabled.</bold></gradient>");
    }

    public void logInfo(String message) {
        getComponentLogger().info(miniMessage.deserialize(message));
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

    public LuckPerms ensureLuckPerms() {
        if (luckPerms == null && configManager != null && configManager.isLuckPermsPrefixEnabled()) {
            luckPerms = fetchLuckPerms(true);
        }
        return luckPerms;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (tabUpdater != null) {
            tabUpdater.handleJoin();
        }
        if (updateChecker != null) {
            getServer().getScheduler().runTaskLater(this, () -> updateChecker.notifyPlayer(event.getPlayer()), 20L);
        }
        if (scoreboardService != null) {
            scoreboardService.handleJoin(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (tabUpdater != null) {
            tabUpdater.handleQuit();
        }
    }

    private void registerCommands() {
        PluginCommand command = getCommand("tab");
        if (command == null) {
            logInfo("<color:#FF55FF>Command registration failed: /tab missing from plugin.yml.</color>");
            return;
        }

        TabCommand tabCommand = new TabCommand(this, configManager, tabUpdater, updateChecker, chatInputManager, scoreboardService, actionBarTimerService, neoTabGui);
        command.setExecutor(tabCommand);
        command.setTabCompleter(tabCommand);
    }

    private void startActionBarExtras() {
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

    private void hookLuckPerms() {
        if (configManager != null && !configManager.isLuckPermsPrefixEnabled()) {
            luckPerms = null;
            return;
        }
        luckPerms = fetchLuckPerms(true);
    }

    private LuckPerms fetchLuckPerms(boolean warn) {
        if (!getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            if (warn && !luckPermsWarned) {
                luckPermsWarned = true;
                getLogger().warning("LuckPerms not found; prefix/suffix support disabled.");
            }
            return null;
        }

        RegisteredServiceProvider<LuckPerms> provider = getServer().getServicesManager().getRegistration(LuckPerms.class);
        LuckPerms resolved = provider == null ? null : provider.getProvider();

        if (resolved == null && warn && !luckPermsWarned) {
            luckPermsWarned = true;
            getLogger().warning("LuckPerms not found; prefix/suffix support disabled.");
        }

        return resolved;
    }
}
