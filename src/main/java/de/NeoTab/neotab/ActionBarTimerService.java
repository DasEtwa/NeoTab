package de.NeoTab.neotab;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class ActionBarTimerService implements Listener {
    private static final int MAX_DURATION_SECONDS = 24 * 60 * 60;

    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final ActionBarService actionBarService;
    private final ActionBarTextFormatter formatter;
    private final Map<UUID, TimerSession> sessions;

    private StopwatchService stopwatchService;
    private BukkitTask task;

    public ActionBarTimerService(NeoTab plugin, ConfigManager configManager, ActionBarService actionBarService, ActionBarTextFormatter formatter) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.actionBarService = actionBarService;
        this.formatter = formatter;
        sessions = new HashMap<>();
    }

    public void setStopwatchService(StopwatchService stopwatchService) {
        this.stopwatchService = stopwatchService;
    }

    public void restart() {
        formatter.refresh();
        if (!configManager.getActionBarTimerConfig().enabled()) {
            stopAll();
            return;
        }
        if (!sessions.isEmpty()) {
            ensureTask();
        }
    }

    public boolean start(Player player, int durationSeconds) {
        if (!configManager.getActionBarTimerConfig().enabled() || durationSeconds <= 0 || durationSeconds > MAX_DURATION_SECONDS) {
            return false;
        }

        if (stopwatchService != null && stopwatchService.isRunning(player)) {
            stopwatchService.stopSilently(player);
        }
        sessions.put(player.getUniqueId(), new TimerSession(durationSeconds, false));
        ensureTask();
        send(player, durationSeconds);
        return true;
    }

    public boolean stop(Player player) {
        TimerSession removed = sessions.remove(player.getUniqueId());
        if (removed == null) {
            return false;
        }

        actionBarService.clear(player, "timer");
        stopTaskIfIdle();
        return true;
    }

    public boolean pause(Player player) {
        TimerSession session = sessions.get(player.getUniqueId());
        if (session == null || session.paused()) {
            return false;
        }

        sessions.put(player.getUniqueId(), new TimerSession(session.remainingSeconds(), true));
        return true;
    }

    public boolean resume(Player player) {
        TimerSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.paused()) {
            return false;
        }

        sessions.put(player.getUniqueId(), new TimerSession(session.remainingSeconds(), false));
        ensureTask();
        return true;
    }

    public void stopAll() {
        sessions.clear();
        actionBarService.clearSource("timer");
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        sessions.remove(uuid);
        actionBarService.clear(uuid, "timer");
        stopTaskIfIdle();
    }

    public boolean isRunning(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public static int parseDurationSeconds(String input) {
        if (input == null || input.isBlank()) {
            return -1;
        }

        String normalized = input.trim().toLowerCase();
        int multiplier = 1;
        char last = normalized.charAt(normalized.length() - 1);
        if (Character.isLetter(last)) {
            normalized = normalized.substring(0, normalized.length() - 1);
            switch (last) {
                case 's' -> multiplier = 1;
                case 'm' -> multiplier = 60;
                case 'h' -> multiplier = 60 * 60;
                default -> {
                    return -1;
                }
            }
        }

        try {
            int amount = Integer.parseInt(normalized);
            long seconds = (long) amount * multiplier;
            if (seconds <= 0L || seconds > MAX_DURATION_SECONDS) {
                return -1;
            }
            return (int) seconds;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private void ensureTask() {
        if (task != null) {
            return;
        }

        task = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void tick() {
        Iterator<Map.Entry<UUID, TimerSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TimerSession> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                iterator.remove();
                continue;
            }

            TimerSession session = entry.getValue();
            if (session.paused()) {
                sendPaused(player, session.remainingSeconds());
                continue;
            }

            int remainingSeconds = session.remainingSeconds() - 1;
            if (remainingSeconds <= 0) {
                actionBarService.submit(player, "timer", renderTimerText(player, configManager.getActionBarTimerConfig().endedFormat(), 0, false), ActionBarService.PRIORITY_TIMER, 2500L);
                iterator.remove();
                continue;
            }

            entry.setValue(new TimerSession(remainingSeconds, false));
            send(player, remainingSeconds);
        }

        stopTaskIfIdle();
    }

    private void stopTaskIfIdle() {
        if (!sessions.isEmpty() || task == null) {
            return;
        }

        task.cancel();
        task = null;
    }

    private void send(Player player, int remainingSeconds) {
        actionBarService.submit(player, "timer", renderTimerText(player, configManager.getActionBarTimerConfig().runningFormat(), remainingSeconds, true), ActionBarService.PRIORITY_TIMER, 2500L);
    }

    private void sendPaused(Player player, int remainingSeconds) {
        actionBarService.submit(player, "timer", renderTimerText(player, configManager.getActionBarTimerConfig().pausedFormat(), remainingSeconds, true), ActionBarService.PRIORITY_TIMER, 2500L);
    }

    private net.kyori.adventure.text.Component renderTimerText(Player player, String format, int remainingSeconds, boolean appendTimeIfMissing) {
        String rawFormat = format == null || format.isBlank() ? "{time}" : format;
        if (appendTimeIfMissing && !rawFormat.contains("{time}")) {
            rawFormat = rawFormat + " {time}";
        }

        return formatter.renderPalette(
            player,
            rawFormat,
            Map.of("time", formatDuration(remainingSeconds), "seconds", Integer.toString(Math.max(0, remainingSeconds))),
            "actionbar-timer"
        );
    }

    private String formatDuration(int seconds) {
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int remainingSeconds = seconds % 60;
        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, remainingSeconds);
        }
        return String.format("%02d:%02d", minutes, remainingSeconds);
    }

    private record TimerSession(
        int remainingSeconds,
        boolean paused
    ) {
    }
}
