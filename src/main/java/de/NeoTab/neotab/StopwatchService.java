package de.NeoTab.neotab;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class StopwatchService implements ActionBarModule, Listener {
    private static final String SOURCE = "stopwatch";

    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final ActionBarService actionBarService;
    private final ActionBarTextFormatter formatter;
    private final Map<UUID, ElapsedSession> sessions;

    private ActionBarTimerService timerService;
    private BukkitTask task;
    private final LongSupplier nanoTime;

    public StopwatchService(NeoTab plugin, ConfigManager configManager, ActionBarService actionBarService, ActionBarTextFormatter formatter) {
        this(plugin, configManager, actionBarService, formatter, System::nanoTime);
    }

    StopwatchService(NeoTab plugin, ConfigManager configManager, ActionBarService actionBarService, ActionBarTextFormatter formatter, LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.plugin = plugin;
        this.configManager = configManager;
        this.actionBarService = actionBarService;
        this.formatter = formatter;
        sessions = new HashMap<>();
    }

    public void setTimerService(ActionBarTimerService timerService) {
        this.timerService = timerService;
    }

    @Override
    public void start() {
        formatter.refresh();
        if (!configManager.getActionBarConfig().enabled() || !configManager.getActionBarConfig().stopwatch().enabled()) {
            stopAll();
            return;
        }
        if (!sessions.isEmpty()) {
            ensureTask();
        }
    }

    @Override
    public void stop() {
        stopAll();
    }

    public boolean start(Player player) {
        if (!configManager.getActionBarConfig().enabled() || !configManager.getActionBarConfig().stopwatch().enabled()) {
            return false;
        }
        if (timerService != null && timerService.isRunning(player)) {
            return false;
        }

        sessions.put(player.getUniqueId(), ElapsedSession.start(nanoTime.getAsLong()));
        ensureTask();
        send(player, 0);
        return true;
    }

    public boolean stop(Player player) {
        ElapsedSession removed = sessions.remove(player.getUniqueId());
        if (removed == null) {
            return false;
        }
        actionBarService.clear(player, SOURCE);
        stopTaskIfIdle();
        return true;
    }

    public void stopSilently(Player player) {
        sessions.remove(player.getUniqueId());
        actionBarService.clear(player, SOURCE);
        stopTaskIfIdle();
    }

    public boolean pause(Player player) {
        ElapsedSession session = sessions.get(player.getUniqueId());
        if (session == null || session.paused()) {
            return false;
        }
        sessions.put(player.getUniqueId(), session.pause(nanoTime.getAsLong()));
        send(player, elapsedSeconds(session));
        return true;
    }

    public boolean resume(Player player) {
        ElapsedSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.paused()) {
            return false;
        }
        sessions.put(player.getUniqueId(), session.resume(nanoTime.getAsLong()));
        ensureTask();
        send(player, elapsedSeconds(session));
        return true;
    }

    public boolean reset(Player player) {
        ElapsedSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return false;
        }
        sessions.put(player.getUniqueId(), ElapsedSession.start(nanoTime.getAsLong()));
        ensureTask();
        send(player, 0);
        return true;
    }

    public boolean isRunning(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public void stopAll() {
        sessions.clear();
        actionBarService.clearSource(SOURCE);
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        sessions.remove(uuid);
        actionBarService.clear(uuid, SOURCE);
        stopTaskIfIdle();
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
        if (!configManager.getActionBarConfig().enabled() || !configManager.getActionBarConfig().stopwatch().enabled()) {
            stopAll();
            return;
        }

        Iterator<Map.Entry<UUID, ElapsedSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ElapsedSession> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                iterator.remove();
                continue;
            }

            ElapsedSession session = entry.getValue();
            if (session.paused()) {
                send(player, elapsedSeconds(session));
                continue;
            }

            int elapsedSeconds = elapsedSeconds(session);
            send(player, elapsedSeconds);
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

    private void send(Player player, int elapsedSeconds) {
        actionBarService.submit(
            player,
            SOURCE,
            formatter.renderPalette(player, configManager.getActionBarConfig().stopwatch().text(), Map.of("time", formatDuration(elapsedSeconds), "seconds", Integer.toString(elapsedSeconds)), "actionbar.stopwatch"),
            ActionBarService.PRIORITY_STOPWATCH,
            2500L
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

    private int elapsedSeconds(ElapsedSession session) {
        return (int) Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toSeconds(session.elapsedNanos(nanoTime.getAsLong())));
    }
}
