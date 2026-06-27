package de.NeoTab.neotab;

import java.util.List;
import java.util.Map;
import java.util.Random;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class RandomActionBarModule implements ActionBarModule {
    private static final String SOURCE = "random";

    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final ActionBarService actionBarService;
    private final ActionBarTextFormatter formatter;
    private final Random random;

    private BukkitTask task;

    public RandomActionBarModule(NeoTab plugin, ConfigManager configManager, ActionBarService actionBarService, ActionBarTextFormatter formatter) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.actionBarService = actionBarService;
        this.formatter = formatter;
        random = new Random();
    }

    @Override
    public void start() {
        stopTask();
        formatter.refresh();
        ConfigManager.RandomMessagesActionBarConfig config = configManager.getActionBarConfig().randomMessages();
        if (!config.enabled() || config.messages().isEmpty()) {
            actionBarService.clearSource(SOURCE);
            return;
        }

        long intervalTicks = Math.max(60L, config.intervalSeconds()) * 20L;
        task = new BukkitRunnable() {
            @Override
            public void run() {
                showRandomMessage();
            }
        }.runTaskTimer(plugin, 20L, intervalTicks);
    }

    @Override
    public void stop() {
        stopTask();
        actionBarService.clearSource(SOURCE);
    }

    private void showRandomMessage() {
        ConfigManager.RandomMessagesActionBarConfig config = configManager.getActionBarConfig().randomMessages();
        List<String> messages = config.messages();
        if (!config.enabled() || messages.isEmpty()) {
            stop();
            return;
        }

        String text = messages.get(random.nextInt(messages.size()));
        long durationMillis = Math.max(1L, config.durationSeconds()) * 1000L;
        for (Player player : Bukkit.getOnlinePlayers()) {
            actionBarService.submit(
                player,
                SOURCE,
                formatter.render(player, text, Map.of("player", player.getName(), "player_name", player.getName()), "actionbar.random"),
                ActionBarService.PRIORITY_RANDOM,
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
