package de.NeoTab.neotab;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

public final class RegionSelectionManager {
    private final Map<UUID, MutableSelection> selections = new HashMap<>();

    public void setPos1(UUID uuid, Block block) {
        if (block == null) {
            return;
        }
        setPos1(uuid, block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public void setPos2(UUID uuid, Block block) {
        if (block == null) {
            return;
        }
        setPos2(uuid, block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public void setPos1(UUID uuid, Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        setPos1(uuid, location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public void setPos2(UUID uuid, Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        setPos2(uuid, location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public void setSelection(UUID uuid, RegionSelection selection) {
        MutableSelection mutableSelection = selections.computeIfAbsent(uuid, ignored -> new MutableSelection());
        mutableSelection.pos1 = new SelectionPoint(selection.world(), selection.minX(), selection.minY(), selection.minZ());
        mutableSelection.pos2 = new SelectionPoint(selection.world(), selection.maxX(), selection.maxY(), selection.maxZ());
    }

    public Optional<RegionSelection> selection(UUID uuid) {
        MutableSelection selection = selections.get(uuid);
        if (selection == null || selection.pos1 == null || selection.pos2 == null) {
            return Optional.empty();
        }
        if (!selection.pos1.world().equalsIgnoreCase(selection.pos2.world())) {
            return Optional.empty();
        }

        return Optional.of(new RegionSelection(
            selection.pos1.world(),
            Math.min(selection.pos1.x(), selection.pos2.x()),
            Math.min(selection.pos1.y(), selection.pos2.y()),
            Math.min(selection.pos1.z(), selection.pos2.z()),
            Math.max(selection.pos1.x(), selection.pos2.x()),
            Math.max(selection.pos1.y(), selection.pos2.y()),
            Math.max(selection.pos1.z(), selection.pos2.z())
        ));
    }

    public Optional<SelectionPoint> pos1(UUID uuid) {
        MutableSelection selection = selections.get(uuid);
        return selection == null ? Optional.empty() : Optional.ofNullable(selection.pos1);
    }

    public Optional<SelectionPoint> pos2(UUID uuid) {
        MutableSelection selection = selections.get(uuid);
        return selection == null ? Optional.empty() : Optional.ofNullable(selection.pos2);
    }

    public void clear(UUID uuid) {
        selections.remove(uuid);
    }

    private void setPos1(UUID uuid, String world, int x, int y, int z) {
        selections.computeIfAbsent(uuid, ignored -> new MutableSelection()).pos1 = new SelectionPoint(world, x, y, z);
    }

    private void setPos2(UUID uuid, String world, int x, int y, int z) {
        selections.computeIfAbsent(uuid, ignored -> new MutableSelection()).pos2 = new SelectionPoint(world, x, y, z);
    }

    public static RegionSelection fromLocations(Location first, Location second) {
        World world = first.getWorld();
        if (world == null || second.getWorld() == null || !world.getName().equalsIgnoreCase(second.getWorld().getName())) {
            return null;
        }
        return new RegionSelection(
            world.getName(),
            Math.min(first.getBlockX(), second.getBlockX()),
            Math.min(first.getBlockY(), second.getBlockY()),
            Math.min(first.getBlockZ(), second.getBlockZ()),
            Math.max(first.getBlockX(), second.getBlockX()),
            Math.max(first.getBlockY(), second.getBlockY()),
            Math.max(first.getBlockZ(), second.getBlockZ())
        );
    }

    private static final class MutableSelection {
        private SelectionPoint pos1;
        private SelectionPoint pos2;
    }

    public record SelectionPoint(String world, int x, int y, int z) {
        public String format() {
            return world + " " + x + " " + y + " " + z;
        }
    }

    public record RegionSelection(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public String format() {
            return world + " [" + minX + ", " + minY + ", " + minZ + "] -> [" + maxX + ", " + maxY + ", " + maxZ + "]";
        }
    }
}
