package de.NeoTab.neotab;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
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

    static final int MAX_CANDIDATES_PER_TICK = 4096;
    static final int MAX_VIEWERS_PER_TICK = 64;
    private BukkitTask task;
    private BukkitTask continuationTask;
    private SearchPass searchPass;

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
        if (searchPass != null) {
            return;
        }
        List<PlayerSnapshot> snapshots = new ArrayList<>();
        Map<CellKey, List<PlayerSnapshot>> index = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isOnline() || !player.isValid()) {
                continue;
            }
            PlayerSnapshot snapshot = new PlayerSnapshot(player, player.getLocation());
            snapshots.add(snapshot);
            index.computeIfAbsent(cellKey(snapshot.location(), config.maxDistance(), config.sameWorldOnly()),
                ignored -> new ArrayList<>()).add(snapshot);
        }
        searchPass = new SearchPass(config, snapshots.iterator(), index);
        runSearchSlice();
    }

    private void runSearchSlice() {
        continuationTask = null;
        if (searchPass == null || !plugin.isEnabled()) {
            searchPass = null;
            return;
        }
        if (advanceSearch()) {
            searchPass = null;
        } else {
            continuationTask = Bukkit.getScheduler().runTask(plugin, this::runSearchSlice);
        }
    }

    /** Bound candidate work and rendered viewers separately, including all-hidden dense groups. */
    private boolean advanceSearch() {
        SearchPass pass = searchPass;
        int work = 0;
        int completed = 0;
        while (work < MAX_CANDIDATES_PER_TICK && completed < MAX_VIEWERS_PER_TICK) {
            if (pass.viewer == null) {
                if (!pass.viewers.hasNext()) {
                    return true;
                }
                pass.viewer = pass.viewers.next();
                CellKey origin = cellKey(pass.viewer.location(), pass.config.maxDistance(), pass.config.sameWorldOnly());
                List<List<PlayerSnapshot>> neighbors = new ArrayList<>(27);
                for (int x = -1; x <= 1; x++) {
                    for (int y = -1; y <= 1; y++) {
                        for (int z = -1; z <= 1; z++) {
                            neighbors.add(pass.index.getOrDefault(new CellKey(origin.world(), origin.x() + x,
                                origin.y() + y, origin.z() + z), List.of()));
                        }
                    }
                }
                pass.candidates = neighbors.stream().flatMap(List::stream).iterator();
                pass.nearest = null;
                pass.nearestDistanceSquared = Double.MAX_VALUE;
            }
            if (!pass.viewer.player().isOnline() || !pass.viewer.player().isValid()) {
                pass.viewer = null;
                completed++;
                continue;
            }
            if (pass.nearestDistanceSquared == 0.0 || !pass.candidates.hasNext()) {
                publishNearest(pass);
                pass.viewer = null;
                completed++;
                continue;
            }
            PlayerSnapshot candidate = pass.candidates.next();
            work++;
            if (candidate.player() == pass.viewer.player()) {
                continue;
            }
            double distance = distanceSquared(pass.viewer.location(), candidate.location());
            if (distance > pass.maxDistanceSquared || distance >= pass.nearestDistanceSquared) {
                continue;
            }
            if (isEligibleCandidate(pass.viewer.player(), candidate.player())) {
                pass.nearest = candidate;
                pass.nearestDistanceSquared = distance;
            }
        }
        return false;
    }

    private void publishNearest(SearchPass pass) {
        Player viewer = pass.viewer.player();
        Player nearest = pass.nearest == null ? null : pass.nearest.player();
        // A search may span ticks: never publish a now-hidden, disconnected or out-of-range target.
        if (nearest == null || !isEligibleCandidate(viewer, nearest)) {
            actionBarService.clear(viewer, SOURCE);
            return;
        }
        Location currentViewer = viewer.getLocation();
        Location currentTarget = nearest.getLocation();
        double distanceSquared = distanceSquared(currentViewer, currentTarget);
        if ((pass.config.sameWorldOnly() && !java.util.Objects.equals(currentViewer.getWorld(), currentTarget.getWorld()))
            || distanceSquared > pass.maxDistanceSquared) {
            actionBarService.clear(viewer, SOURCE);
            return;
        }
        long distance = Math.round(Math.sqrt(distanceSquared));
        actionBarService.submit(viewer, SOURCE,
            formatter.render(viewer, pass.config.text(),
                Map.of("player", nearest.getName(), "player_name", nearest.getName(), "distance", Long.toString(distance)),
                "actionbar.nearest-player"),
            ActionBarService.PRIORITY_NEAREST_PLAYER,
            Math.max(3L, pass.config.checkIntervalTicks() / 20L + 2L) * 1000L);
    }

    private record PlayerSnapshot(Player player, Location location) { }

    private static final class SearchPass {
        final ConfigManager.NearestPlayerActionBarConfig config;
        final Iterator<PlayerSnapshot> viewers;
        final Map<CellKey, List<PlayerSnapshot>> index;
        final double maxDistanceSquared;
        PlayerSnapshot viewer;
        Iterator<PlayerSnapshot> candidates;
        PlayerSnapshot nearest;
        double nearestDistanceSquared;

        SearchPass(ConfigManager.NearestPlayerActionBarConfig config, Iterator<PlayerSnapshot> viewers,
                   Map<CellKey, List<PlayerSnapshot>> index) {
            this.config = config;
            this.viewers = viewers;
            this.index = index;
            maxDistanceSquared = (double) config.maxDistance() * config.maxDistance();
        }
    }

    private void stopTask() {
        if (continuationTask != null) {
            continuationTask.cancel();
            continuationTask = null;
        }
        searchPass = null;
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
