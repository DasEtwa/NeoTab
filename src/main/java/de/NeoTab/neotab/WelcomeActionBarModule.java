package de.NeoTab.neotab;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

public final class WelcomeActionBarModule implements ActionBarModule, Listener {
    private static final String SOURCE = "welcome";

    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final ActionBarService actionBarService;
    private final ActionBarTextFormatter formatter;
    private final Map<UUID, BukkitTask> pendingTasks;

    public WelcomeActionBarModule(NeoTab plugin, ConfigManager configManager, ActionBarService actionBarService, ActionBarTextFormatter formatter) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.actionBarService = actionBarService;
        this.formatter = formatter;
        pendingTasks = new HashMap<>();
    }

    @Override
    public void start() {
        formatter.refresh();
    }

    @Override
    public void stop() {
        for (BukkitTask task : pendingTasks.values()) {
            task.cancel();
        }
        pendingTasks.clear();
        actionBarService.clearSource(SOURCE);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        ConfigManager.WelcomeActionBarConfig config = configManager.getActionBarConfig().welcome();
        if (!config.enabled()) {
            return;
        }

        UUID uuid = event.getPlayer().getUniqueId();
        BukkitTask previous = pendingTasks.remove(uuid);
        if (previous != null) {
            previous.cancel();
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingTasks.remove(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                return;
            }
            show(player);
        }, Math.max(0L, config.delayTicks()));
        pendingTasks.put(uuid, task);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        BukkitTask task = pendingTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
        actionBarService.clear(uuid, SOURCE);
    }

    private void show(Player player) {
        ConfigManager.WelcomeActionBarConfig config = configManager.getActionBarConfig().welcome();
        if (!config.enabled()) {
            return;
        }
        actionBarService.submit(
            player,
            SOURCE,
            formatter.render(
                player,
                config.text(),
                Map.of("player", player.getName(), "player_name", player.getName()),
                "actionbar.welcome"
            ),
            ActionBarService.PRIORITY_WELCOME,
            Math.max(1L, config.durationSeconds()) * 1000L
        );
    }
}
