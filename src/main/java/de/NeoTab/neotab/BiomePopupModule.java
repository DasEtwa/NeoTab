package de.NeoTab.neotab;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class BiomePopupModule implements ActionBarModule, Listener {
    private static final String SOURCE = "biome-popup";

    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final ActionBarService actionBarService;
    private final ActionBarTextFormatter formatter;
    private final Map<UUID, String> lastBiomes;

    private BukkitTask task;

    public BiomePopupModule(NeoTab plugin, ConfigManager configManager, ActionBarService actionBarService, ActionBarTextFormatter formatter) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.actionBarService = actionBarService;
        this.formatter = formatter;
        lastBiomes = new HashMap<>();
    }

    @Override
    public void start() {
        stopTask();
        formatter.refresh();
        ConfigManager.BiomePopupActionBarConfig config = configManager.getActionBarConfig().biomePopup();
        if (!config.enabled()) {
            lastBiomes.clear();
            actionBarService.clearSource(SOURCE);
            return;
        }

        long intervalTicks = Math.max(30L, config.checkIntervalTicks());
        task = new BukkitRunnable() {
            @Override
            public void run() {
                checkPlayers();
            }
        }.runTaskTimer(plugin, intervalTicks, intervalTicks);
    }

    @Override
    public void stop() {
        stopTask();
        lastBiomes.clear();
        actionBarService.clearSource(SOURCE);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastBiomes.remove(uuid);
        actionBarService.clear(uuid, SOURCE);
    }

    private void checkPlayers() {
        ConfigManager.BiomePopupActionBarConfig config = configManager.getActionBarConfig().biomePopup();
        if (!config.enabled()) {
            stop();
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            Biome biome = player.getLocation().getBlock().getBiome();
            NamespacedKey key = biome.getKey();
            String biomeKey = key == null ? biome.name().toLowerCase(Locale.ROOT) : key.toString();
            String previous = lastBiomes.put(player.getUniqueId(), biomeKey);
            if (previous == null || previous.equals(biomeKey)) {
                continue;
            }
            actionBarService.submit(
                player,
                SOURCE,
                formatter.render(player, config.text(), Map.of("biome", formatBiomeName(biomeKey)), "actionbar.biome-popup"),
                ActionBarService.PRIORITY_BIOME,
                Math.max(1L, config.durationSeconds()) * 1000L
            );
        }
    }

    private String formatBiomeName(String key) {
        String raw = key;
        int namespaceIndex = raw.indexOf(':');
        if (namespaceIndex >= 0 && namespaceIndex + 1 < raw.length()) {
            raw = raw.substring(namespaceIndex + 1);
        }

        StringBuilder builder = new StringBuilder();
        for (String part : raw.split("_")) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return builder.isEmpty() ? key : builder.toString();
    }

    private void stopTask() {
        if (task == null) {
            return;
        }
        task.cancel();
        task = null;
    }
}
