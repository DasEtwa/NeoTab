package de.NeoTab.neotab;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

public final class ChatInputManager implements Listener {
    private static final long TIMEOUT_TICKS = 20L * 60L;

    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final Map<UUID, PendingInput> pendingInputs;

    public ChatInputManager(NeoTab plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        pendingInputs = new ConcurrentHashMap<>();
    }

    public void request(Player player, Component prompt, InputCallback callback) {
        cancel(player, false);

        BukkitTask timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingInput removed = pendingInputs.remove(player.getUniqueId());
            if (removed != null && player.isOnline()) {
                player.sendMessage(configManager.message("input-timeout"));
            }
        }, TIMEOUT_TICKS);

        pendingInputs.put(player.getUniqueId(), new PendingInput(callback, timeoutTask));
        player.closeInventory();
        player.sendMessage(prompt);
        player.sendMessage(configManager.message("input-cancel-hint"));
    }

    public void cancel(Player player, boolean notify) {
        PendingInput pendingInput = pendingInputs.remove(player.getUniqueId());
        if (pendingInput == null) {
            return;
        }

        pendingInput.timeoutTask().cancel();
        if (notify && player.isOnline()) {
            player.sendMessage(configManager.message("input-cancelled"));
        }
    }

    public void cancelAll(boolean notify) {
        for (UUID uuid : pendingInputs.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                cancel(player, notify);
            }
        }
        pendingInputs.clear();
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        PendingInput pendingInput = pendingInputs.get(event.getPlayer().getUniqueId());
        if (pendingInput == null) {
            return;
        }

        event.setCancelled(true);
        String input = event.getMessage();
        Bukkit.getScheduler().runTask(plugin, () -> complete(event.getPlayer(), input));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer(), false);
    }

    private void complete(Player player, String input) {
        PendingInput pendingInput = pendingInputs.remove(player.getUniqueId());
        if (pendingInput == null) {
            return;
        }

        pendingInput.timeoutTask().cancel();
        if ("cancel".equalsIgnoreCase(input.trim())) {
            player.sendMessage(configManager.message("input-cancelled"));
            return;
        }

        pendingInput.callback().accept(player, input);
    }

    @FunctionalInterface
    public interface InputCallback {
        void accept(Player player, String input);
    }

    private record PendingInput(
        InputCallback callback,
        BukkitTask timeoutTask
    ) {
    }
}
