package de.NeoTab.neotab;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class AdvancementCounterModule implements ActionBarModule {
    private static final String SOURCE = "achievements";

    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final ActionBarService actionBarService;
    private final ActionBarTextFormatter formatter;

    private BukkitTask task;

    public AdvancementCounterModule(NeoTab plugin, ConfigManager configManager, ActionBarService actionBarService, ActionBarTextFormatter formatter) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.actionBarService = actionBarService;
        this.formatter = formatter;
    }

    @Override
    public void start() {
        stopTask();
        formatter.refresh();
        ConfigManager.AchievementsActionBarConfig config = configManager.getActionBarConfig().achievements();
        if (!config.enabled() || !"minecraft".equalsIgnoreCase(config.provider())) {
            actionBarService.clearSource(SOURCE);
            return;
        }

        long intervalTicks = Math.max(60L, config.intervalSeconds()) * 20L;
        task = new BukkitRunnable() {
            @Override
            public void run() {
                updateAll();
            }
        }.runTaskTimer(plugin, intervalTicks, intervalTicks);
    }

    @Override
    public void stop() {
        stopTask();
        actionBarService.clearSource(SOURCE);
    }

    private void updateAll() {
        ConfigManager.AchievementsActionBarConfig config = configManager.getActionBarConfig().achievements();
        if (!config.enabled() || !"minecraft".equalsIgnoreCase(config.provider())) {
            stop();
            return;
        }

        List<Advancement> advancements = new ArrayList<>();
        Bukkit.advancementIterator().forEachRemaining(advancements::add);
        int total = advancements.size();
        long durationMillis = (Math.max(60L, config.intervalSeconds()) + 2L) * 1000L;
        for (Player player : Bukkit.getOnlinePlayers()) {
            int completed = 0;
            for (Advancement advancement : advancements) {
                if (player.getAdvancementProgress(advancement).isDone()) {
                    completed++;
                }
            }
            actionBarService.submit(
                player,
                SOURCE,
                formatter.render(
                    player,
                    config.text(),
                    Map.of("completed", Integer.toString(completed), "total", Integer.toString(total)),
                    "actionbar.achievements"
                ),
                ActionBarService.PRIORITY_ACHIEVEMENTS,
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
