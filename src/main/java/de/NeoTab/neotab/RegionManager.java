package de.NeoTab.neotab;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

public final class RegionManager {
    private static final String DEFAULT_PROFILE = "default";
    private static final long MAX_INDEXED_CHUNKS_PER_REGION = 4096L;
    private static final Comparator<RegionProfile> REGION_ORDER = Comparator
        .comparingInt(RegionProfile::priority)
        .reversed()
        .thenComparing(RegionProfile::name);

    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final RegionSelectionManager selectionManager;
    private final WorldEditSelectionProvider worldEditSelectionProvider;
    private final AsyncYamlWriter yamlWriter;
    private final Map<String, RegionProfile> regions;
    private final Map<String, Map<Long, List<RegionProfile>>> regionsByChunk;
    private final Map<String, List<RegionProfile>> largeRegionsByWorld;
    private final Map<String, RegionEndpoints> regionEndpoints;
    private final Map<UUID, String> activeRegions;
    private final Map<String, Boolean> warnedTabProfiles;
    private final Map<String, Boolean> warnedScoreboardProfiles;
    private final File regionsFile;

    public RegionManager(NeoTab plugin, ConfigManager configManager, RegionSelectionManager selectionManager, WorldEditSelectionProvider worldEditSelectionProvider, AsyncYamlWriter yamlWriter) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.selectionManager = selectionManager;
        this.worldEditSelectionProvider = worldEditSelectionProvider;
        this.yamlWriter = yamlWriter;
        regions = new LinkedHashMap<>();
        regionsByChunk = new HashMap<>();
        largeRegionsByWorld = new HashMap<>();
        regionEndpoints = new HashMap<>();
        activeRegions = new HashMap<>();
        warnedTabProfiles = new HashMap<>();
        warnedScoreboardProfiles = new HashMap<>();
        regionsFile = new File(plugin.getDataFolder(), "regions.yml");
        reload();
    }

    public void reload() {
        yamlWriter.flush();
        ensureRegionsFile();
        regions.clear();
        regionsByChunk.clear();
        largeRegionsByWorld.clear();
        regionEndpoints.clear();
        warnedTabProfiles.clear();
        warnedScoreboardProfiles.clear();
        worldEditSelectionProvider.refresh();

        YamlConfiguration config = YamlConfiguration.loadConfiguration(regionsFile);
        ConfigurationSection section = config.getConfigurationSection("regions");
        if (section == null) {
            refreshAllPlayers();
            return;
        }

        for (String key : section.getKeys(false)) {
            String name = normalizeName(key);
            if (!isValidRegionName(name)) {
                plugin.getLogger().warning("Invalid region name in regions.yml: " + key);
                continue;
            }

            String path = "regions." + key;
            String world = config.getString(path + ".world", "");
            if (world == null || world.isBlank()) {
                plugin.getLogger().warning("Region " + key + " has no world; skipping.");
                continue;
            }

            RegionProfile region = new RegionProfile(
                name,
                config.getBoolean(path + ".enabled", true),
                world,
                config.getInt(path + ".min.x", 0),
                config.getInt(path + ".min.y", 0),
                config.getInt(path + ".min.z", 0),
                config.getInt(path + ".max.x", 0),
                config.getInt(path + ".max.y", 0),
                config.getInt(path + ".max.z", 0),
                config.getInt(path + ".priority", 0),
                normalizeProfileName(config.getString(path + ".tab-profile", DEFAULT_PROFILE)),
                normalizeProfileName(config.getString(path + ".scoreboard-profile", DEFAULT_PROFILE))
            );
            regions.put(name, region);
            regionEndpoints.put(name, RegionEndpoints.from(region));
        }

        rebuildWorldIndex();
        refreshAllPlayers();
    }

    public boolean createRegion(String name, RegionSelectionManager.RegionSelection selection) {
        String normalizedName = normalizeName(name);
        if (!isValidRegionName(normalizedName) || selection == null || regions.containsKey(normalizedName)) {
            return false;
        }

        RegionProfile region = new RegionProfile(
            normalizedName,
            true,
            selection.world(),
            selection.minX(),
            selection.minY(),
            selection.minZ(),
            selection.maxX(),
            selection.maxY(),
            selection.maxZ(),
            0,
            DEFAULT_PROFILE,
            DEFAULT_PROFILE
        );
        regions.put(normalizedName, region);
        regionEndpoints.put(normalizedName, RegionEndpoints.from(region));
        save();
        rebuildWorldIndex();
        refreshAllPlayers();
        return true;
    }

    public boolean deleteRegion(String name) {
        String normalizedName = normalizeName(name);
        if (regions.remove(normalizedName) == null) {
            return false;
        }
        regionEndpoints.remove(normalizedName);
        save();
        rebuildWorldIndex();
        refreshAllPlayers();
        return true;
    }

    public boolean updateBounds(String name, RegionSelectionManager.RegionSelection selection) {
        String normalizedName = normalizeName(name);
        RegionProfile region = regions.get(normalizedName);
        if (region == null || selection == null) {
            return false;
        }
        regions.put(normalizedName, region.withBounds(selection));
        regionEndpoints.put(normalizedName, RegionEndpoints.from(selection));
        save();
        rebuildWorldIndex();
        refreshAllPlayers();
        return true;
    }

    public boolean updatePriority(String name, int priority) {
        return updateRegion(normalizeName(name), region -> region.withPriority(priority));
    }

    public boolean updateEnabled(String name, boolean enabled) {
        return updateRegion(normalizeName(name), region -> region.withEnabled(enabled));
    }

    public boolean updateBoundaryFromLocation(String name, Location location, boolean pos1) {
        String normalizedName = normalizeName(name);
        RegionProfile region = regions.get(normalizedName);
        if (region == null || location == null || location.getWorld() == null) {
            return false;
        }

        RegionEndpoints endpoints = regionEndpoints.computeIfAbsent(normalizedName, ignored -> RegionEndpoints.from(region));
        RegionSelectionManager.SelectionPoint updated = new RegionSelectionManager.SelectionPoint(
            location.getWorld().getName(),
            location.getBlockX(),
            location.getBlockY(),
            location.getBlockZ()
        );
        RegionSelectionManager.SelectionPoint other = pos1 ? endpoints.pos2() : endpoints.pos1();
        if (!updated.world().equalsIgnoreCase(other.world())) {
            return false;
        }

        RegionEndpoints updatedEndpoints;
        if (pos1) {
            updatedEndpoints = new RegionEndpoints(updated, endpoints.pos2());
        } else {
            updatedEndpoints = new RegionEndpoints(endpoints.pos1(), updated);
        }
        regionEndpoints.put(normalizedName, updatedEndpoints);
        return updateBoundsPreservingEndpoints(normalizedName, updatedEndpoints);
    }

    public boolean updateTabProfile(String name, String tabProfile) {
        return updateRegion(normalizeName(name), region -> region.withTabProfile(normalizeProfileName(tabProfile)));
    }

    public boolean updateScoreboardProfile(String name, String scoreboardProfile) {
        return updateRegion(normalizeName(name), region -> region.withScoreboardProfile(normalizeProfileName(scoreboardProfile)));
    }

    public Optional<RegionProfile> region(String name) {
        return Optional.ofNullable(regions.get(normalizeName(name)));
    }

    public Collection<RegionProfile> regions() {
        return Collections.unmodifiableCollection(regions.values());
    }

    public boolean hasRegion(String name) {
        return regions.containsKey(normalizeName(name));
    }

    public String activeTabProfile(Player player) {
        RegionProfile region = activeRegion(player).orElse(null);
        if (region == null) {
            return DEFAULT_PROFILE;
        }
        String profileName = normalizeProfileName(region.tabProfile());
        if (!configManager.hasTabProfile(profileName)) {
            warnMissingTabProfile(profileName, region.name());
            return DEFAULT_PROFILE;
        }
        return profileName;
    }

    public String activeScoreboardProfile(Player player) {
        RegionProfile region = activeRegion(player).orElse(null);
        if (region == null) {
            return DEFAULT_PROFILE;
        }
        String profileName = normalizeProfileName(region.scoreboardProfile());
        if (!configManager.hasScoreboardProfile(profileName)) {
            warnMissingScoreboardProfile(profileName, region.name());
            return DEFAULT_PROFILE;
        }
        return profileName;
    }

    public boolean hasActiveScoreboardProfile(Player player) {
        return !activeScoreboardProfile(player).equals(DEFAULT_PROFILE);
    }

    public Optional<RegionProfile> activeRegion(Player player) {
        String name = activeRegions.get(player.getUniqueId());
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(regions.get(name));
    }

    public void handleMove(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        RegionProfile winningRegion = findWinningRegion(player.getLocation());
        String newRegionName = winningRegion == null ? "" : winningRegion.name();
        String previousRegionName = activeRegions.put(player.getUniqueId(), newRegionName);
        if (newRegionName.equals(previousRegionName == null ? "" : previousRegionName)) {
            return;
        }

        plugin.getTabUpdater().updatePlayerNow(player);
        plugin.getScoreboardService().handleRegionProfileChange(player);
    }

    public void handleQuit(Player player) {
        activeRegions.remove(player.getUniqueId());
        selectionManager.clear(player.getUniqueId());
    }

    public Optional<RegionSelectionManager.RegionSelection> importWorldEditSelection(Player player) {
        return worldEditSelectionProvider.selection(player);
    }

    public boolean isWorldEditAvailable() {
        return worldEditSelectionProvider.isAvailable();
    }

    public String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }

    public String normalizeProfileName(String profileName) {
        return profileName == null || profileName.isBlank() ? DEFAULT_PROFILE : configManager.normalizePerformancePresetName(profileName);
    }

    public boolean isValidRegionName(String name) {
        return configManager.isValidPerformancePresetName(name);
    }

    private void refreshAllPlayers() {
        activeRegions.clear();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            handleMove(player);
        }
    }

    private RegionProfile findWinningRegion(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        String worldName = location.getWorld().getName().toLowerCase(Locale.ROOT);
        long chunkKey = chunkKey(location.getBlockX() >> 4, location.getBlockZ() >> 4);
        RegionProfile winner = firstContaining(
            regionsByChunk.getOrDefault(worldName, Map.of()).getOrDefault(chunkKey, List.of()),
            location
        );
        RegionProfile largeWinner = firstContaining(largeRegionsByWorld.getOrDefault(worldName, List.of()), location);
        if (winner == null) {
            return largeWinner;
        }
        if (largeWinner == null) {
            return winner;
        }
        return REGION_ORDER.compare(winner, largeWinner) <= 0 ? winner : largeWinner;
    }

    private void rebuildWorldIndex() {
        regionsByChunk.clear();
        largeRegionsByWorld.clear();
        for (RegionProfile region : regions.values()) {
            if (!region.enabled()) {
                continue;
            }
            String worldName = region.world().toLowerCase(Locale.ROOT);
            int minChunkX = region.minX() >> 4;
            int maxChunkX = region.maxX() >> 4;
            int minChunkZ = region.minZ() >> 4;
            int maxChunkZ = region.maxZ() >> 4;
            long width = (long) maxChunkX - minChunkX + 1L;
            long depth = (long) maxChunkZ - minChunkZ + 1L;
            if (width * depth > MAX_INDEXED_CHUNKS_PER_REGION) {
                largeRegionsByWorld.computeIfAbsent(worldName, ignored -> new ArrayList<>()).add(region);
                continue;
            }
            Map<Long, List<RegionProfile>> worldIndex = regionsByChunk.computeIfAbsent(worldName, ignored -> new HashMap<>());
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    worldIndex.computeIfAbsent(chunkKey(chunkX, chunkZ), ignored -> new ArrayList<>()).add(region);
                }
            }
        }
        for (Map<Long, List<RegionProfile>> worldIndex : regionsByChunk.values()) {
            for (List<RegionProfile> chunkRegions : worldIndex.values()) {
                chunkRegions.sort(REGION_ORDER);
            }
        }
        for (List<RegionProfile> worldRegions : largeRegionsByWorld.values()) {
            worldRegions.sort(REGION_ORDER);
        }
    }

    private RegionProfile firstContaining(List<RegionProfile> candidates, Location location) {
        for (RegionProfile region : candidates) {
            if (region.contains(location)) {
                return region;
            }
        }
        return null;
    }

    private long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private boolean updateBoundsPreservingEndpoints(String normalizedName, RegionEndpoints endpoints) {
        RegionProfile region = regions.get(normalizedName);
        if (region == null) {
            return false;
        }
        regions.put(normalizedName, region.withBounds(endpoints.selection()));
        save();
        rebuildWorldIndex();
        refreshAllPlayers();
        return true;
    }

    private boolean updateRegion(String normalizedName, RegionUpdater updater) {
        RegionProfile region = regions.get(normalizedName);
        if (region == null) {
            return false;
        }
        regions.put(normalizedName, updater.update(region));
        save();
        rebuildWorldIndex();
        refreshAllPlayers();
        return true;
    }

    private void save() {
        YamlConfiguration config = new YamlConfiguration();
        for (RegionProfile region : regions.values()) {
            String path = "regions." + region.name();
            config.set(path + ".enabled", region.enabled());
            config.set(path + ".world", region.world());
            config.set(path + ".priority", region.priority());
            config.set(path + ".min.x", region.minX());
            config.set(path + ".min.y", region.minY());
            config.set(path + ".min.z", region.minZ());
            config.set(path + ".max.x", region.maxX());
            config.set(path + ".max.y", region.maxY());
            config.set(path + ".max.z", region.maxZ());
            config.set(path + ".tab-profile", region.tabProfile());
            config.set(path + ".scoreboard-profile", region.scoreboardProfile());
        }

        yamlWriter.write(regionsFile.toPath(), config.saveToString());
    }

    private void ensureRegionsFile() {
        if (regionsFile.exists()) {
            return;
        }
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create NeoTab data folder for regions.yml.");
            return;
        }
        try {
            plugin.saveResource("regions.yml", false);
        } catch (IllegalArgumentException ex) {
            try {
                YamlConfiguration config = new YamlConfiguration();
                config.set("regions", new LinkedHashMap<>());
                config.save(regionsFile);
            } catch (java.io.IOException ioException) {
                plugin.getLogger().warning("Could not create regions.yml: " + ioException.getMessage());
            }
        }
    }

    private void warnMissingTabProfile(String profileName, String regionName) {
        String key = regionName + ":" + profileName;
        if (warnedTabProfiles.putIfAbsent(key, true) == null) {
            plugin.getLogger().warning("Region " + regionName + " references missing tab profile '" + profileName + "'. Using default.");
        }
    }

    private void warnMissingScoreboardProfile(String profileName, String regionName) {
        String key = regionName + ":" + profileName;
        if (warnedScoreboardProfiles.putIfAbsent(key, true) == null) {
            plugin.getLogger().warning("Region " + regionName + " references missing scoreboard profile '" + profileName + "'. Using default.");
        }
    }

    private interface RegionUpdater {
        RegionProfile update(RegionProfile region);
    }

    private record RegionEndpoints(
        RegionSelectionManager.SelectionPoint pos1,
        RegionSelectionManager.SelectionPoint pos2
    ) {
        private static RegionEndpoints from(RegionProfile region) {
            return new RegionEndpoints(
                new RegionSelectionManager.SelectionPoint(region.world(), region.minX(), region.minY(), region.minZ()),
                new RegionSelectionManager.SelectionPoint(region.world(), region.maxX(), region.maxY(), region.maxZ())
            );
        }

        private static RegionEndpoints from(RegionSelectionManager.RegionSelection selection) {
            return new RegionEndpoints(
                new RegionSelectionManager.SelectionPoint(selection.world(), selection.minX(), selection.minY(), selection.minZ()),
                new RegionSelectionManager.SelectionPoint(selection.world(), selection.maxX(), selection.maxY(), selection.maxZ())
            );
        }

        private RegionSelectionManager.RegionSelection selection() {
            return new RegionSelectionManager.RegionSelection(
                pos1.world(),
                Math.min(pos1.x(), pos2.x()),
                Math.min(pos1.y(), pos2.y()),
                Math.min(pos1.z(), pos2.z()),
                Math.max(pos1.x(), pos2.x()),
                Math.max(pos1.y(), pos2.y()),
                Math.max(pos1.z(), pos2.z())
            );
        }
    }
}
