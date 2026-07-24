package de.NeoTab.neotab;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class NearestPlayerModule implements ActionBarModule {
    private static final String SOURCE = "nearest-player";

    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final ActionBarService actionBarService;
    private final ActionBarTextFormatter formatter;

    private BukkitTask task;

    public NearestPlayerModule(NeoTab plugin, ConfigManager configManager, ActionBarService actionBarService, ActionBarTextFormatter formatter) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.actionBarService = actionBarService;
        this.formatter = formatter;
    }

    @Override
    public void start() {
        stopTask();
        formatter.refresh();
        ConfigManager.NearestPlayerActionBarConfig config = configManager.getActionBarConfig().nearestPlayer();
        if (!config.enabled()) {
            actionBarService.clearSource(SOURCE);
            return;
        }

        long intervalTicks = Math.max(40L, config.checkIntervalTicks());
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
        actionBarService.clearSource(SOURCE);
    }

    private void checkPlayers() {
        ConfigManager.NearestPlayerActionBarConfig config = configManager.getActionBarConfig().nearestPlayer();
        if (!config.enabled()) {
            stop();
            return;
        }

        double maxDistanceSquared = (double) config.maxDistance() * (double) config.maxDistance();
        double cellSize = Math.max(1.0, config.maxDistance());
        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        Map<CellKey, List<Player>> spatialIndex = new HashMap<>();
        for (Player player : onlinePlayers) {
            if (!player.isOnline() || !player.isValid()) {
                continue;
            }
            spatialIndex.computeIfAbsent(cellKey(player.getLocation(), cellSize, config.sameWorldOnly()), ignored -> new ArrayList<>()).add(player);
        }

        for (Player player : onlinePlayers) {
            if (!player.isOnline() || !player.isValid()) {
                actionBarService.clear(player, SOURCE);
                continue;
            }
            Location playerLocation = player.getLocation();
            Player nearest = null;
            double nearestDistanceSquared = Double.MAX_VALUE;
            CellKey origin = cellKey(playerLocation, cellSize, config.sameWorldOnly());
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                for (int offsetY = -1; offsetY <= 1; offsetY++) {
                    for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                        CellKey neighbor = new CellKey(origin.world(), origin.x() + offsetX, origin.y() + offsetY, origin.z() + offsetZ);
                        for (Player candidate : spatialIndex.getOrDefault(neighbor, List.of())) {
                            if (!isEligibleCandidate(player, candidate)) {
                                continue;
                            }
                            Location candidateLocation = candidate.getLocation();
                            boolean sameWorld = candidate.getWorld().equals(player.getWorld());
                            if (config.sameWorldOnly() && !sameWorld) {
                                continue;
                            }
                            double distanceSquared = sameWorld
                                ? playerLocation.distanceSquared(candidateLocation)
                                : distanceSquared(playerLocation, candidateLocation);
                            if (distanceSquared > maxDistanceSquared || distanceSquared >= nearestDistanceSquared) {
                                continue;
                            }
                            nearest = candidate;
                            nearestDistanceSquared = distanceSquared;
                        }
                    }
                }
            }

            if (nearest == null) {
                actionBarService.clear(player, SOURCE);
                continue;
            }

            long distance = Math.round(Math.sqrt(nearestDistanceSquared));
            actionBarService.submit(
                player,
                SOURCE,
                formatter.render(
                    player,
                    config.text(),
                    Map.of("player", nearest.getName(), "player_name", nearest.getName(), "distance", Long.toString(distance)),
                    "actionbar.nearest-player"
                ),
                ActionBarService.PRIORITY_NEAREST_PLAYER,
                Math.max(3L, config.checkIntervalTicks() / 20L + 2L) * 1000L
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

    private double distanceSquared(Location left, Location right) {
        double dx = left.getX() - right.getX();
        double dy = left.getY() - right.getY();
        double dz = left.getZ() - right.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    static boolean isEligibleCandidate(Player viewer, Player candidate) {
        if (viewer == null || candidate == null || viewer == candidate) {
            return false;
        }
        if (!candidate.isOnline() || !candidate.isValid()) {
            return false;
        }
        return viewer.canSee(candidate);
    }

    private CellKey cellKey(Location location, double cellSize, boolean sameWorldOnly) {
        String world = sameWorldOnly && location.getWorld() != null ? location.getWorld().getName() : "*";
        return new CellKey(
            world,
            (int) Math.floor(location.getX() / cellSize),
            (int) Math.floor(location.getY() / cellSize),
            (int) Math.floor(location.getZ() / cellSize)
        );
    }

    private record CellKey(String world, int x, int y, int z) {
    }
}
