package de.NeoTab.neotab;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.papermc.paper.advancement.AdvancementDisplay;
import org.bukkit.Bukkit;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class AdvancementCounterModule implements ActionBarModule, Listener {
    private static final String SOURCE = "achievements";

    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final ActionBarService actionBarService;
    private final ActionBarTextFormatter formatter;
    private final Map<UUID, Integer> completedCounts;

    private BukkitTask task;
    private List<Advancement> advancements = List.of();

    public AdvancementCounterModule(NeoTab plugin, ConfigManager configManager, ActionBarService actionBarService, ActionBarTextFormatter formatter) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.actionBarService = actionBarService;
        this.formatter = formatter;
        completedCounts = new HashMap<>();
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
        refreshAdvancements();

        long intervalTicks = Math.max(60L, config.intervalSeconds()) * 20L;
        task = new BukkitRunnable() {
            @Override
            public void run() {
                updateAll();
            }
        }.runTaskTimer(plugin, 20L, intervalTicks);
    }

    @Override
    public void stop() {
        stopTask();
        completedCounts.clear();
        advancements = List.of();
        actionBarService.clearSource(SOURCE);
    }

    private void updateAll() {
        ConfigManager.AchievementsActionBarConfig config = configManager.getActionBarConfig().achievements();
        if (!config.enabled() || !"minecraft".equalsIgnoreCase(config.provider())) {
            stop();
            return;
        }

        int total = advancements.size();
        long durationMillis = Math.max(1L, config.durationSeconds()) * 1000L;
        for (Player player : Bukkit.getOnlinePlayers()) {
            int completed = completedCounts.computeIfAbsent(player.getUniqueId(), ignored -> countCompleted(player));
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

    private boolean shouldCount(Advancement advancement) {
        AdvancementDisplay display = advancement.getDisplay();
        return display != null && !display.isHidden();
    }

    @EventHandler
    public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
        if (!shouldCount(event.getAdvancement())) {
            return;
        }
        completedCounts.computeIfPresent(event.getPlayer().getUniqueId(), (ignored, count) -> count + 1);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        completedCounts.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        if (event.getType() == ServerLoadEvent.LoadType.RELOAD && task != null) {
            refreshAdvancements();
        }
    }

    private int countCompleted(Player player) {
        int completed = 0;
        for (Advancement advancement : advancements) {
            if (player.getAdvancementProgress(advancement).isDone()) {
                completed++;
            }
        }
        return completed;
    }

    private void refreshAdvancements() {
        ArrayList<Advancement> visibleAdvancements = new ArrayList<>();
        Bukkit.advancementIterator().forEachRemaining(advancement -> {
            if (shouldCount(advancement)) {
                visibleAdvancements.add(advancement);
            }
        });
        advancements = List.copyOf(visibleAdvancements);
        completedCounts.clear();
    }
}
