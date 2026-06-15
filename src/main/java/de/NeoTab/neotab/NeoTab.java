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
    private ScoreboardService scoreboardService;
    private ActionBarTimerService actionBarTimerService;
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
        scoreboardService = new ScoreboardService(this, configManager);
        actionBarTimerService = new ActionBarTimerService(this, configManager);
        neoTabGui = new NeoTabGui(this, configManager, tabUpdater, scoreboardService, actionBarTimerService, chatInputManager);
        registerCommands();

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(chatInputManager, this);
        getServer().getPluginManager().registerEvents(scoreboardService, this);
        getServer().getPluginManager().registerEvents(actionBarTimerService, this);
        getServer().getPluginManager().registerEvents(neoTabGui, this);
        tabUpdater.initializeCounts(getServer().getOnlinePlayers().size(), getServer().getMaxPlayers());
        tabUpdater.start();
        tabUpdater.updateAllNow();
        scoreboardService.start();
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

        TabCommand tabCommand = new TabCommand(configManager, tabUpdater, updateChecker, chatInputManager, scoreboardService, actionBarTimerService, neoTabGui);
        command.setExecutor(tabCommand);
        command.setTabCompleter(tabCommand);
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
