package de.NeoTab.neotab;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class ClockActionBarModule implements ActionBarModule {
    private static final String SOURCE = "clock";

    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final ActionBarService actionBarService;
    private final ActionBarTextFormatter formatter;

    private BukkitTask task;

    public ClockActionBarModule(NeoTab plugin, ConfigManager configManager, ActionBarService actionBarService, ActionBarTextFormatter formatter) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.actionBarService = actionBarService;
        this.formatter = formatter;
    }

    @Override
    public void start() {
        stopTask();
        formatter.refresh();
        ConfigManager.ClockActionBarConfig config = configManager.getActionBarConfig().clock();
        if (!config.enabled()) {
            actionBarService.clearSource(SOURCE);
            return;
        }

        long intervalTicks = Math.max(60L, config.intervalSeconds()) * 20L;
        task = new BukkitRunnable() {
            @Override
            public void run() {
                updateAll();
            }
        }.runTaskTimer(plugin, 0L, intervalTicks);
    }

    @Override
    public void stop() {
        stopTask();
        actionBarService.clearSource(SOURCE);
    }

    private void updateAll() {
        ConfigManager.ClockActionBarConfig config = configManager.getActionBarConfig().clock();
        if (!config.enabled()) {
            stop();
            return;
        }

        String time = LocalTime.now(config.zoneId()).format(DateTimeFormatter.ofPattern(config.format()));
        long durationMillis = (Math.max(60L, config.intervalSeconds()) + 2L) * 1000L;
        for (Player player : Bukkit.getOnlinePlayers()) {
            actionBarService.submit(
                player,
                SOURCE,
                formatter.render(player, config.text(), Map.of("time", time), "actionbar.clock"),
                ActionBarService.PRIORITY_CLOCK,
                durationMillis
            );
        }
    }

    private void stopTask() {
        if (task == null) {
            return;
        }
        task.cancel();
        task = null;
    }
}
