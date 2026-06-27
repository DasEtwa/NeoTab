package de.NeoTab.neotab;

import org.bukkit.Location;

public record RegionProfile(
    String name,
    boolean enabled,
    String world,
    int minX,
    int minY,
    int minZ,
    int maxX,
    int maxY,
    int maxZ,
    int priority,
    String tabProfile,
    String scoreboardProfile
) {
    public RegionProfile {
        String normalizedWorld = world == null ? "" : world.trim();
        int resolvedMinX = Math.min(minX, maxX);
        int resolvedMinY = Math.min(minY, maxY);
        int resolvedMinZ = Math.min(minZ, maxZ);
        int resolvedMaxX = Math.max(minX, maxX);
        int resolvedMaxY = Math.max(minY, maxY);
        int resolvedMaxZ = Math.max(minZ, maxZ);
        minX = resolvedMinX;
        minY = resolvedMinY;
        minZ = resolvedMinZ;
        maxX = resolvedMaxX;
        maxY = resolvedMaxY;
        maxZ = resolvedMaxZ;
        world = normalizedWorld;
        tabProfile = tabProfile == null || tabProfile.isBlank() ? "default" : tabProfile.trim();
        scoreboardProfile = scoreboardProfile == null || scoreboardProfile.isBlank() ? "default" : scoreboardProfile.trim();
    }

    public boolean contains(Location location) {
        if (!enabled || location == null || location.getWorld() == null) {
            return false;
        }
        if (!world.equalsIgnoreCase(location.getWorld().getName())) {
            return false;
        }

        int blockX = location.getBlockX();
        int blockY = location.getBlockY();
        int blockZ = location.getBlockZ();
        return blockX >= minX && blockX <= maxX
            && blockY >= minY && blockY <= maxY
            && blockZ >= minZ && blockZ <= maxZ;
    }

    public RegionProfile withBounds(RegionSelectionManager.RegionSelection selection) {
        return new RegionProfile(
            name,
            enabled,
            selection.world(),
            selection.minX(),
            selection.minY(),
            selection.minZ(),
            selection.maxX(),
            selection.maxY(),
            selection.maxZ(),
            priority,
            tabProfile,
            scoreboardProfile
        );
    }

    public RegionProfile withPriority(int newPriority) {
        return new RegionProfile(name, enabled, world, minX, minY, minZ, maxX, maxY, maxZ, newPriority, tabProfile, scoreboardProfile);
    }

    public RegionProfile withEnabled(boolean newEnabled) {
        return new RegionProfile(name, newEnabled, world, minX, minY, minZ, maxX, maxY, maxZ, priority, tabProfile, scoreboardProfile);
    }

    public RegionProfile withTabProfile(String newTabProfile) {
        return new RegionProfile(name, enabled, world, minX, minY, minZ, maxX, maxY, maxZ, priority, newTabProfile, scoreboardProfile);
    }

    public RegionProfile withScoreboardProfile(String newScoreboardProfile) {
        return new RegionProfile(name, enabled, world, minX, minY, minZ, maxX, maxY, maxZ, priority, tabProfile, newScoreboardProfile);
    }
}
