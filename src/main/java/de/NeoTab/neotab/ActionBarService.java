package de.NeoTab.neotab;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
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

    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final Map<UUID, Map<String, ActionBarMessage>> messages;
    private final Map<UUID, String> lastSource;

    private BukkitTask task;

    public ActionBarService(NeoTab plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        messages = new HashMap<>();
        lastSource = new HashMap<>();
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

        long expiresAt = System.currentTimeMillis() + durationMillis;
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
        lastSource.remove(uuid);
    }

    public void clearSource(String source) {
        for (UUID uuid : new HashMap<>(messages).keySet()) {
            clear(uuid, source);
        }
        dispatchAll();
    }

    public void clearAll() {
        for (UUID uuid : lastSource.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.sendActionBar(Component.empty());
            }
        }
        messages.clear();
        lastSource.clear();
    }

    private void dispatchAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            dispatch(player);
        }
    }

    private void dispatch(Player player) {
        UUID uuid = player.getUniqueId();
        ActionBarMessage message = winningMessage(uuid);
        if (message == null) {
            if (lastSource.remove(uuid) != null) {
                player.sendActionBar(Component.empty());
            }
            return;
        }

        lastSource.put(uuid, message.source());
        player.sendActionBar(message.text());
    }

    private ActionBarMessage winningMessage(UUID uuid) {
        Map<String, ActionBarMessage> playerMessages = messages.get(uuid);
        if (playerMessages == null || playerMessages.isEmpty()) {
            return null;
        }

        long now = System.currentTimeMillis();
        ActionBarMessage winning = null;
        Iterator<Map.Entry<String, ActionBarMessage>> iterator = playerMessages.entrySet().iterator();
        while (iterator.hasNext()) {
            ActionBarMessage message = iterator.next().getValue();
            if (message.expired(now)) {
                iterator.remove();
                continue;
            }
            if (winning == null || message.priority() > winning.priority()) {
                winning = message;
            }
        }
        if (playerMessages.isEmpty()) {
            messages.remove(uuid);
        }
        return winning;
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
        lastSource.remove(uuid);
    }
}
