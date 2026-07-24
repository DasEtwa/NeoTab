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
import org.bukkit.scheduler.BukkitTask;

public final class RegionManager {
    private static final String DEFAULT_PROFILE = "default";

    // These limits keep movement lookup and the in-memory chunk index bounded. The axis limit still
    // permits a region spanning the complete vanilla world border.
    static final int MAX_REGIONS = 512;
    static final long MAX_REGION_AXIS_BLOCKS = 60_000_001L;
    static final long MAX_INDEXED_CHUNKS_PER_REGION = 4096L;
    static final long MAX_TOTAL_INDEXED_CHUNKS = 131_072L;
    static final int MAX_LARGE_REGIONS = 64;
    static final long REGION_CHANGE_DEBOUNCE_TICKS = 1L;

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
    private final Map<UUID, PendingRegionUpdate> pendingRegionUpdates;
    private final Map<String, Boolean> warnedTabProfiles;
    private final Map<String, Boolean> warnedScoreboardProfiles;
    private final File regionsFile;

    private long indexedChunkCount;
    private int largeRegionCount;

    public RegionManager(
        NeoTab plugin,
        ConfigManager configManager,
        RegionSelectionManager selectionManager,
        WorldEditSelectionProvider worldEditSelectionProvider,
        AsyncYamlWriter yamlWriter
    ) {
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
        pendingRegionUpdates = new HashMap<>();
        warnedTabProfiles = new HashMap<>();
        warnedScoreboardProfiles = new HashMap<>();
        regionsFile = new File(plugin.getDataFolder(), "regions.yml");
        reload();
    }

    /** Reloads the last fully persisted snapshot. The caller must coordinate pending async writes. */
    public void reload() {
        cancelPendingRegionUpdates();
        ensureRegionsFile();
        regions.clear();
        regionsByChunk.clear();
        largeRegionsByWorld.clear();
        regionEndpoints.clear();
        warnedTabProfiles.clear();
        warnedScoreboardProfiles.clear();
        indexedChunkCount = 0L;
        largeRegionCount = 0;
        worldEditSelectionProvider.refresh();

        YamlConfiguration config = YamlConfiguration.loadConfiguration(regionsFile);
        ConfigurationSection section = config.getConfigurationSection("regions");
        if (section == null) {
            refreshAllPlayers(true);
            return;
        }

        for (String key : section.getKeys(false)) {
            String name = normalizeName(key);
            if (!isValidRegionName(name)) {
                plugin.getLogger().warning("Invalid region name in regions.yml: " + key);
                continue;
            }
            if (regions.containsKey(name)) {
                plugin.getLogger().warning("Duplicate normalized region name in regions.yml: " + key);
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

            RegionMutationResult validation = validateCandidate(
                regions.size(), indexedChunkCount, largeRegionCount, null, region
            );
            if (!validation.changed()) {
                plugin.getLogger().warning("Region " + key + " was not loaded: " + validation.detail());
                continue;
            }
            addRegion(region);
        }

        refreshAllPlayers(true);
    }

    public boolean createRegion(String name, RegionSelectionManager.RegionSelection selection) {
        return createRegionChecked(name, selection).changed();
    }

    /** Returns a detailed result so commands and GUIs can explain limit rejections. */
    public RegionMutationResult createRegionChecked(String name, RegionSelectionManager.RegionSelection selection) {
        String normalizedName = normalizeName(name);
        if (!isValidRegionName(normalizedName) || selection == null) {
            return RegionMutationResult.failure(MutationFailure.INVALID_INPUT, "invalid region name or selection");
        }
        if (regions.containsKey(normalizedName)) {
            return RegionMutationResult.failure(MutationFailure.DUPLICATE, "a region with this name already exists");
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
        RegionMutationResult validation = validateCurrentCandidate(null, region);
        if (!validation.changed()) {
            return validation;
        }

        addRegion(region);
        save();
        refreshAllPlayers(false);
        return RegionMutationResult.success();
    }

    public boolean deleteRegion(String name) {
        String normalizedName = normalizeName(name);
        RegionProfile removed = regions.remove(normalizedName);
        if (removed == null) {
            return false;
        }
        removeFromIndex(removed);
        regionEndpoints.remove(normalizedName);
        save();
        refreshAllPlayers(false);
        return true;
    }

    public boolean updateBounds(String name, RegionSelectionManager.RegionSelection selection) {
        return updateBoundsChecked(name, selection).changed();
    }

    /** Returns a detailed result so commands and GUIs can explain size/budget rejections. */
    public RegionMutationResult updateBoundsChecked(String name, RegionSelectionManager.RegionSelection selection) {
        String normalizedName = normalizeName(name);
        RegionProfile region = regions.get(normalizedName);
        if (region == null) {
            return RegionMutationResult.failure(MutationFailure.NOT_FOUND, "region not found");
        }
        if (selection == null || selection.world() == null || selection.world().isBlank()) {
            return RegionMutationResult.failure(MutationFailure.INVALID_INPUT, "invalid region selection");
        }

        RegionProfile updated = region.withBounds(selection);
        RegionMutationResult result = replaceIndexedRegion(region, updated);
        if (!result.changed()) {
            return result;
        }
        regionEndpoints.put(normalizedName, RegionEndpoints.from(selection));
        save();
        refreshAllPlayers(false);
        return result;
    }

    public boolean updatePriority(String name, int priority) {
        String normalizedName = normalizeName(name);
        RegionProfile region = regions.get(normalizedName);
        if (region == null) {
            return false;
        }
        RegionMutationResult result = replaceIndexedRegion(region, region.withPriority(priority));
        if (!result.changed()) {
            return false;
        }
        save();
        refreshAllPlayers(false);
        return true;
    }

    public boolean updateEnabled(String name, boolean enabled) {
        return updateEnabledChecked(name, enabled).changed();
    }

    /** Returns a detailed result because enabling a legacy region can exceed the live index budget. */
    public RegionMutationResult updateEnabledChecked(String name, boolean enabled) {
        String normalizedName = normalizeName(name);
        RegionProfile region = regions.get(normalizedName);
        if (region == null) {
            return RegionMutationResult.failure(MutationFailure.NOT_FOUND, "region not found");
        }
        RegionMutationResult result = replaceIndexedRegion(region, region.withEnabled(enabled));
        if (!result.changed()) {
            return result;
        }
        save();
        refreshAllPlayers(false);
        return result;
    }

    public boolean updateBoundaryFromLocation(String name, Location location, boolean pos1) {
        return updateBoundaryFromLocationChecked(name, location, pos1).changed();
    }

    /** Returns WORLD_MISMATCH without modifying endpoints when the other endpoint is in another world. */
    public RegionMutationResult updateBoundaryFromLocationChecked(String name, Location location, boolean pos1) {
        String normalizedName = normalizeName(name);
        RegionProfile region = regions.get(normalizedName);
        if (region == null) {
            return RegionMutationResult.failure(MutationFailure.NOT_FOUND, "region not found");
        }
        if (location == null || location.getWorld() == null) {
            return RegionMutationResult.failure(MutationFailure.INVALID_INPUT, "position has no valid world");
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
            return RegionMutationResult.worldMismatch(other.world(), updated.world());
        }

        RegionEndpoints updatedEndpoints = pos1
            ? new RegionEndpoints(updated, endpoints.pos2())
            : new RegionEndpoints(endpoints.pos1(), updated);
        RegionProfile updatedRegion = region.withBounds(updatedEndpoints.selection());
        RegionMutationResult result = replaceIndexedRegion(region, updatedRegion);
        if (!result.changed()) {
            return result;
        }
        regionEndpoints.put(normalizedName, updatedEndpoints);
        save();
        refreshAllPlayers(false);
        return result;
    }

    public boolean updateTabProfile(String name, String tabProfile) {
        String normalizedName = normalizeName(name);
        RegionProfile region = regions.get(normalizedName);
        if (region == null) {
            return false;
        }
        regions.put(normalizedName, region.withTabProfile(normalizeProfileName(tabProfile)));
        save();
        refreshPlayersForRegion(normalizedName);
        return true;
    }

    public boolean updateScoreboardProfile(String name, String scoreboardProfile) {
        String normalizedName = normalizeName(name);
        RegionProfile region = regions.get(normalizedName);
        if (region == null) {
            return false;
        }
        regions.put(normalizedName, region.withScoreboardProfile(normalizeProfileName(scoreboardProfile)));
        save();
        refreshPlayersForRegion(normalizedName);
        return true;
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
        if (player == null) {
            return Optional.empty();
        }
        String name = activeRegions.get(player.getUniqueId());
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(regions.get(name));
    }

    public void handleMove(Player player) {
        if (player != null) {
            handleMove(player, player.getLocation());
        }
    }

    /** Queues the latest event target; a player can own at most one pending region/render task. */
    public void handleMove(Player player, Location target) {
        queueRegionUpdate(player, target, false);
    }

    /** Respawns are evaluated explicitly after Bukkit has installed the respawn location. */
    public void handleRespawn(Player player, Location respawnLocation) {
        queueRegionUpdate(player, respawnLocation, false);
    }

    public void handleQuit(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        cancelPendingRegionUpdate(uuid);
        activeRegions.remove(uuid);
        selectionManager.clear(uuid);
    }

    /** Cancels all manager-owned tasks; NeoTab.onDisable must call this before services are torn down. */
    public void shutdown() {
        cancelPendingRegionUpdates();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            selectionManager.clear(player.getUniqueId());
        }
        activeRegions.clear();
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
        return profileName == null || profileName.isBlank()
            ? DEFAULT_PROFILE
            : configManager.normalizePerformancePresetName(profileName);
    }

    public boolean isValidRegionName(String name) {
        return configManager.isValidPerformancePresetName(name);
    }

    private void queueRegionUpdate(Player player, Location target, boolean forceRender) {
        if (player == null || !player.isOnline() || target == null || target.getWorld() == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!mergePendingRegionUpdate(pendingRegionUpdates, uuid, target, forceRender)) {
            return;
        }

        PendingRegionUpdate created = pendingRegionUpdates.get(uuid);
        try {
            created.task = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> applyPendingRegionUpdate(uuid),
                REGION_CHANGE_DEBOUNCE_TICKS
            );
        } catch (RuntimeException ex) {
            pendingRegionUpdates.remove(uuid);
            throw ex;
        }
    }

    static boolean mergePendingRegionUpdate(
        Map<UUID, PendingRegionUpdate> pendingUpdates,
        UUID uuid,
        Location target,
        boolean forceRender
    ) {
        PendingRegionUpdate pending = pendingUpdates.get(uuid);
        if (pending != null) {
            pending.update(target, forceRender);
            return false;
        }
        pendingUpdates.put(uuid, new PendingRegionUpdate(target, forceRender));
        return true;
    }

    private void applyPendingRegionUpdate(UUID uuid) {
        PendingRegionUpdate pending = pendingRegionUpdates.remove(uuid);
        if (pending == null || !plugin.isEnabled()) {
            return;
        }
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }

        RegionProfile winningRegion = findWinningRegion(pending.target);
        String newRegionName = winningRegion == null ? "" : winningRegion.name();
        String previousRegionName = activeRegions.put(uuid, newRegionName);
        boolean changed = !newRegionName.equals(previousRegionName == null ? "" : previousRegionName);
        if (!changed && !pending.forceRender) {
            return;
        }

        plugin.getTabUpdater().updatePlayerNow(player);
        plugin.getScoreboardService().handleRegionProfileChange(player);
    }

    private void refreshAllPlayers(boolean forceRender) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            queueRegionUpdate(player, player.getLocation(), forceRender);
        }
    }

    private void refreshPlayersForRegion(String normalizedRegionName) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (normalizedRegionName.equals(activeRegions.get(player.getUniqueId()))) {
                queueRegionUpdate(player, player.getLocation(), true);
            }
        }
    }

    private void cancelPendingRegionUpdate(UUID uuid) {
        PendingRegionUpdate pending = pendingRegionUpdates.remove(uuid);
        if (pending != null && pending.task != null) {
            pending.task.cancel();
        }
    }

    private void cancelPendingRegionUpdates() {
        for (PendingRegionUpdate pending : pendingRegionUpdates.values()) {
            if (pending.task != null) {
                pending.task.cancel();
            }
        }
        pendingRegionUpdates.clear();
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

    private RegionProfile firstContaining(List<RegionProfile> candidates, Location location) {
        for (RegionProfile region : candidates) {
            if (region.contains(location)) {
                return region;
            }
        }
        return null;
    }

    private RegionMutationResult replaceIndexedRegion(RegionProfile previous, RegionProfile updated) {
        RegionMutationResult validation = validateCurrentCandidate(previous, updated);
        if (!validation.changed()) {
            return validation;
        }
        removeFromIndex(previous);
        regions.put(updated.name(), updated);
        addToIndex(updated);
        return RegionMutationResult.success();
    }

    private RegionMutationResult validateCurrentCandidate(RegionProfile previous, RegionProfile candidate) {
        return validateCandidate(regions.size(), indexedChunkCount, largeRegionCount, previous, candidate);
    }

    static RegionMutationResult validateCandidate(
        int currentRegionCount,
        long currentIndexedChunks,
        int currentLargeRegions,
        RegionProfile previous,
        RegionProfile candidate
    ) {
        if (candidate == null || candidate.world() == null || candidate.world().isBlank()) {
            return RegionMutationResult.failure(MutationFailure.INVALID_INPUT, "region has no valid world");
        }
        if (previous == null && currentRegionCount >= MAX_REGIONS) {
            return RegionMutationResult.failure(
                MutationFailure.REGION_COUNT_LIMIT,
                "maximum of " + MAX_REGIONS + " regions reached"
            );
        }

        RegionFootprint candidateFootprint = footprint(candidate);
        if (candidateFootprint.maxAxisBlocks() > MAX_REGION_AXIS_BLOCKS) {
            return RegionMutationResult.failure(
                MutationFailure.REGION_SIZE_LIMIT,
                "region axis exceeds the supported " + MAX_REGION_AXIS_BLOCKS + " block span"
            );
        }

        RegionFootprint previousFootprint = previous == null ? RegionFootprint.EMPTY : footprint(previous);
        long previousIndexed = previous != null && previous.enabled() && !previousFootprint.large()
            ? previousFootprint.chunkCount()
            : 0L;
        long candidateIndexed = candidate.enabled() && !candidateFootprint.large()
            ? candidateFootprint.chunkCount()
            : 0L;
        long prospectiveIndexed = currentIndexedChunks - previousIndexed + candidateIndexed;
        if (prospectiveIndexed > MAX_TOTAL_INDEXED_CHUNKS) {
            return RegionMutationResult.failure(
                MutationFailure.CHUNK_BUDGET_LIMIT,
                "indexed chunk budget of " + MAX_TOTAL_INDEXED_CHUNKS + " would be exceeded"
            );
        }

        int previousLarge = previous != null && previous.enabled() && previousFootprint.large() ? 1 : 0;
        int candidateLarge = candidate.enabled() && candidateFootprint.large() ? 1 : 0;
        int prospectiveLarge = currentLargeRegions - previousLarge + candidateLarge;
        if (prospectiveLarge > MAX_LARGE_REGIONS) {
            return RegionMutationResult.failure(
                MutationFailure.LARGE_REGION_LIMIT,
                "maximum of " + MAX_LARGE_REGIONS + " oversized regions reached"
            );
        }
        return RegionMutationResult.success();
    }

    static RegionFootprint footprint(RegionProfile region) {
        long blockWidth = (long) region.maxX() - region.minX() + 1L;
        long blockHeight = (long) region.maxY() - region.minY() + 1L;
        long blockDepth = (long) region.maxZ() - region.minZ() + 1L;
        int minChunkX = region.minX() >> 4;
        int maxChunkX = region.maxX() >> 4;
        int minChunkZ = region.minZ() >> 4;
        int maxChunkZ = region.maxZ() >> 4;
        long chunkWidth = (long) maxChunkX - minChunkX + 1L;
        long chunkDepth = (long) maxChunkZ - minChunkZ + 1L;
        long chunkCount = chunkWidth * chunkDepth;
        long maxAxis = Math.max(blockWidth, Math.max(blockHeight, blockDepth));
        return new RegionFootprint(minChunkX, maxChunkX, minChunkZ, maxChunkZ, chunkCount, maxAxis);
    }

    private void addRegion(RegionProfile region) {
        regions.put(region.name(), region);
        regionEndpoints.put(region.name(), RegionEndpoints.from(region));
        addToIndex(region);
    }

    private void addToIndex(RegionProfile region) {
        if (!region.enabled()) {
            return;
        }
        RegionFootprint footprint = footprint(region);
        String worldName = region.world().toLowerCase(Locale.ROOT);
        if (footprint.large()) {
            List<RegionProfile> worldRegions = largeRegionsByWorld.computeIfAbsent(worldName, ignored -> new ArrayList<>());
            insertOrdered(worldRegions, region);
            largeRegionCount++;
            return;
        }

        Map<Long, List<RegionProfile>> worldIndex = regionsByChunk.computeIfAbsent(worldName, ignored -> new HashMap<>());
        for (int chunkX = footprint.minChunkX(); chunkX <= footprint.maxChunkX(); chunkX++) {
            for (int chunkZ = footprint.minChunkZ(); chunkZ <= footprint.maxChunkZ(); chunkZ++) {
                List<RegionProfile> chunkRegions = worldIndex.computeIfAbsent(chunkKey(chunkX, chunkZ), ignored -> new ArrayList<>());
                insertOrdered(chunkRegions, region);
            }
        }
        indexedChunkCount += footprint.chunkCount();
    }

    private void removeFromIndex(RegionProfile region) {
        if (region == null || !region.enabled()) {
            return;
        }
        RegionFootprint footprint = footprint(region);
        String worldName = region.world().toLowerCase(Locale.ROOT);
        if (footprint.large()) {
            List<RegionProfile> worldRegions = largeRegionsByWorld.get(worldName);
            if (worldRegions != null && worldRegions.removeIf(candidate -> candidate.name().equals(region.name()))) {
                largeRegionCount--;
                if (worldRegions.isEmpty()) {
                    largeRegionsByWorld.remove(worldName);
                }
            }
            return;
        }

        Map<Long, List<RegionProfile>> worldIndex = regionsByChunk.get(worldName);
        if (worldIndex == null) {
            return;
        }
        for (int chunkX = footprint.minChunkX(); chunkX <= footprint.maxChunkX(); chunkX++) {
            for (int chunkZ = footprint.minChunkZ(); chunkZ <= footprint.maxChunkZ(); chunkZ++) {
                long key = chunkKey(chunkX, chunkZ);
                List<RegionProfile> chunkRegions = worldIndex.get(key);
                if (chunkRegions != null) {
                    chunkRegions.removeIf(candidate -> candidate.name().equals(region.name()));
                    if (chunkRegions.isEmpty()) {
                        worldIndex.remove(key);
                    }
                }
            }
        }
        if (worldIndex.isEmpty()) {
            regionsByChunk.remove(worldName);
        }
        indexedChunkCount -= footprint.chunkCount();
    }

    private static void insertOrdered(List<RegionProfile> regions, RegionProfile region) {
        int index = Collections.binarySearch(regions, region, REGION_ORDER);
        regions.add(index < 0 ? -index - 1 : index, region);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
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

    public enum MutationFailure {
        NONE,
        INVALID_INPUT,
        NOT_FOUND,
        DUPLICATE,
        WORLD_MISMATCH,
        REGION_COUNT_LIMIT,
        REGION_SIZE_LIMIT,
        CHUNK_BUDGET_LIMIT,
        LARGE_REGION_LIMIT
    }

    public record RegionMutationResult(
        boolean changed,
        MutationFailure failure,
        String detail,
        String expectedWorld,
        String actualWorld
    ) {
        private static RegionMutationResult success() {
            return new RegionMutationResult(true, MutationFailure.NONE, "", "", "");
        }

        private static RegionMutationResult failure(MutationFailure failure, String detail) {
            return new RegionMutationResult(false, failure, detail, "", "");
        }

        private static RegionMutationResult worldMismatch(String expectedWorld, String actualWorld) {
            return new RegionMutationResult(
                false,
                MutationFailure.WORLD_MISMATCH,
                "position world does not match the region's other endpoint",
                expectedWorld,
                actualWorld
            );
        }

        public boolean limitExceeded() {
            return failure == MutationFailure.REGION_COUNT_LIMIT
                || failure == MutationFailure.REGION_SIZE_LIMIT
                || failure == MutationFailure.CHUNK_BUDGET_LIMIT
                || failure == MutationFailure.LARGE_REGION_LIMIT;
        }
    }

    record RegionFootprint(
        int minChunkX,
        int maxChunkX,
        int minChunkZ,
        int maxChunkZ,
        long chunkCount,
        long maxAxisBlocks
    ) {
        private static final RegionFootprint EMPTY = new RegionFootprint(0, -1, 0, -1, 0L, 0L);

        boolean large() {
            return chunkCount > MAX_INDEXED_CHUNKS_PER_REGION;
        }
    }

    static final class PendingRegionUpdate {
        private Location target;
        private boolean forceRender;
        private BukkitTask task;

        private PendingRegionUpdate(Location target, boolean forceRender) {
            update(target, forceRender);
        }

        private void update(Location target, boolean forceRender) {
            this.target = target.clone();
            this.forceRender |= forceRender;
        }

        Location target() {
            return target.clone();
        }

        boolean forceRender() {
            return forceRender;
        }
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
