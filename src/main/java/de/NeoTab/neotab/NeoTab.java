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
    private LuckPerms luckPerms;
    private boolean luckPermsWarned;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        configManager = new ConfigManager(this);
        hookLuckPerms();

        tabUpdater = new TabUpdater(this, configManager);
        registerCommands();

        getServer().getPluginManager().registerEvents(this, this);
        tabUpdater.initializeCounts(getServer().getOnlinePlayers().size(), getServer().getMaxPlayers());
        tabUpdater.start();
        tabUpdater.updateAllNow();

        logInfo("<gradient:#AA00AA:#BA55D3><bold>NeoTab enabled.</bold></gradient>");
    }

    @Override
    public void onDisable() {
        if (tabUpdater != null) {
            tabUpdater.stop();
            tabUpdater.clearAll();
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

        TabCommand tabCommand = new TabCommand(configManager, tabUpdater);
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
