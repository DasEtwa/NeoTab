package de.NeoTab.neotab;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class ActionBarService implements Listener {
    public static final int PRIORITY_TIMER = 100;
    public static final int PRIORITY_STOPWATCH = 100;
    public static final int PRIORITY_BIOME = 90;
    public static final int PRIORITY_STRUCTURE = 85;
    public static final int PRIORITY_WELCOME = 70;
    public static final int PRIORITY_NEAREST_PLAYER = 50;
    public static final int PRIORITY_ACHIEVEMENTS = 40;
    public static final int PRIORITY_CLOCK = 30;
    public static final int PRIORITY_RANDOM = 10;

    private static final long SEND_INTERVAL_TICKS = 20L;
    // The dispatcher runs once per second; 1.5s makes unchanged long-lived
    // messages refresh every other pass without risking the client fade window.
    private static final long KEEP_ALIVE_INTERVAL_MILLIS = 1_500L;

    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final PlatformBridge platformBridge;
    private final Map<UUID, Map<String, ActionBarMessage>> messages;
    private final Map<UUID, DeliveredMessage> deliveredMessages;
    private final long monotonicOriginNanos;

    private BukkitTask task;

    public ActionBarService(NeoTab plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        platformBridge = new PlatformBridge(plugin, configManager);
        messages = new HashMap<>();
        deliveredMessages = new HashMap<>();
        monotonicOriginNanos = System.nanoTime();
    }

    public void start() {
        stopTask();
        if (!configManager.getActionBarConfig().enabled()) {
            clearAll();
            return;
        }

        task = new BukkitRunnable() {
            @Override
            public void run() {
                dispatchAll();
            }
        }.runTaskTimer(plugin, 0L, SEND_INTERVAL_TICKS);
    }

    public void restart() {
        stopTask();
        clearAll();
        start();
    }

    public void stop() {
        stopTask();
        clearAll();
    }

    public boolean submit(Player player, String source, Component text, int priority, long durationMillis) {
        if (!configManager.getActionBarConfig().enabled() || player == null || !player.isOnline() || source == null || source.isBlank()) {
            return false;
        }
        if (durationMillis <= 0L) {
            clear(player, source);
            return false;
        }

        long expiresAt = monotonicMillis() + durationMillis;
        messages.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>())
            .put(source, new ActionBarMessage(source, text == null ? Component.empty() : text, priority, expiresAt));
        dispatch(player);
        return true;
    }

    public void clear(Player player, String source) {
        if (player == null || source == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        Map<String, ActionBarMessage> playerMessages = messages.get(uuid);
        if (playerMessages == null) {
            return;
        }

        playerMessages.remove(source);
        if (playerMessages.isEmpty()) {
            messages.remove(uuid);
        }
        dispatch(player);
    }

    public void clear(UUID uuid, String source) {
        Map<String, ActionBarMessage> playerMessages = messages.get(uuid);
        if (playerMessages == null) {
            return;
        }
        playerMessages.remove(source);
        if (playerMessages.isEmpty()) {
            messages.remove(uuid);
        }
    }

    public void clearSource(String source) {
        for (UUID uuid : new HashMap<>(messages).keySet()) {
            clear(uuid, source);
        }
        dispatchAll();
    }

    public void clearAll() {
        for (UUID uuid : deliveredMessages.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                platformBridge.sendActionBar(player, Component.empty());
            }
        }
        messages.clear();
        deliveredMessages.clear();
    }

    private void dispatchAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            dispatch(player);
        }
    }

    private void dispatch(Player player) {
        UUID uuid = player.getUniqueId();
        ActionBarMessage message = winningMessage(uuid);
        DeliveredMessage delivered = deliveredMessages.get(uuid);
        if (message == null) {
            if (deliveredMessages.remove(uuid) != null) {
                platformBridge.sendActionBar(player, Component.empty());
            }
            return;
        }

        long now = monotonicMillis();
        if (!shouldSend(delivered, message, now, KEEP_ALIVE_INTERVAL_MILLIS)) {
            return;
        }

        deliveredMessages.put(uuid, new DeliveredMessage(message.source(), message.text(), now));
        platformBridge.sendActionBar(player, message.text());
    }

    static boolean shouldSend(DeliveredMessage delivered, ActionBarMessage message, long nowMillis, long keepAliveMillis) {
        if (delivered == null) {
            return true;
        }
        if (!delivered.source().equals(message.source()) || !delivered.text().equals(message.text())) {
            return true;
        }
        return nowMillis - delivered.sentAtMillis() >= Math.max(1L, keepAliveMillis);
    }

    private ActionBarMessage winningMessage(UUID uuid) {
        Map<String, ActionBarMessage> playerMessages = messages.get(uuid);
        if (playerMessages == null || playerMessages.isEmpty()) {
            return null;
        }

        long now = monotonicMillis();
        ActionBarMessage winning = null;
        Iterator<Map.Entry<String, ActionBarMessage>> iterator = playerMessages.entrySet().iterator();
        while (iterator.hasNext()) {
            ActionBarMessage message = iterator.next().getValue();
            if (message.expired(now)) {
                iterator.remove();
                continue;
            }
            if (winning == null
                || message.priority() > winning.priority()
                || (message.priority() == winning.priority() && message.source().compareTo(winning.source()) < 0)) {
                winning = message;
            }
        }
        if (playerMessages.isEmpty()) {
            messages.remove(uuid);
        }
        return winning;
    }

    private long monotonicMillis() {
        return elapsedMillis(monotonicOriginNanos, System.nanoTime());
    }

    static long elapsedMillis(long originNanos, long nowNanos) {
        return TimeUnit.NANOSECONDS.toMillis(nowNanos - originNanos);
    }

    private void stopTask() {
        if (task == null) {
            return;
        }
        task.cancel();
        task = null;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        messages.remove(uuid);
        deliveredMessages.remove(uuid);
    }

    record DeliveredMessage(String source, Component text, long sentAtMillis) {
    }
}
