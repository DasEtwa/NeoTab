package de.NeoTab.neotab;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import org.bukkit.entity.Player;

public final class WorldEditSelectionProvider {
    private final NeoTab plugin;
    private boolean availabilityChecked;
    private boolean available;

    public WorldEditSelectionProvider(NeoTab plugin) {
        this.plugin = plugin;
    }

    public boolean isAvailable() {
        if (!availabilityChecked) {
            availabilityChecked = true;
            available = plugin.getServer().getPluginManager().isPluginEnabled("WorldEdit")
                || plugin.getServer().getPluginManager().isPluginEnabled("FastAsyncWorldEdit");
            if (available) {
                try {
                    Class.forName("com.sk89q.worldedit.WorldEdit");
                    Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
                } catch (ClassNotFoundException ex) {
                    available = false;
                }
            }
        }
        return available;
    }

    public Optional<RegionSelectionManager.RegionSelection> selection(Player player) {
        if (player == null || !isAvailable()) {
            return Optional.empty();
        }

        try {
            Class<?> worldEditClass = Class.forName("com.sk89q.worldedit.WorldEdit");
            Class<?> bukkitAdapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Class<?> actorClass = Class.forName("com.sk89q.worldedit.extension.platform.Actor");
            Class<?> worldClass = Class.forName("com.sk89q.worldedit.world.World");

            Object worldEdit = worldEditClass.getMethod("getInstance").invoke(null);
            Object sessionManager = worldEditClass.getMethod("getSessionManager").invoke(worldEdit);
            Object actor = bukkitAdapterClass.getMethod("adapt", Player.class).invoke(null, player);
            Object localSession = sessionManager.getClass().getMethod("get", actorClass).invoke(sessionManager, actor);
            Object world = bukkitAdapterClass.getMethod("adapt", org.bukkit.World.class).invoke(null, player.getWorld());
            Object region = localSession.getClass().getMethod("getSelection", worldClass).invoke(localSession, world);
            Object minimum = region.getClass().getMethod("getMinimumPoint").invoke(region);
            Object maximum = region.getClass().getMethod("getMaximumPoint").invoke(region);

            return Optional.of(new RegionSelectionManager.RegionSelection(
                player.getWorld().getName(),
                readBlockCoordinate(minimum, "getBlockX"),
                readBlockCoordinate(minimum, "getBlockY"),
                readBlockCoordinate(minimum, "getBlockZ"),
                readBlockCoordinate(maximum, "getBlockX"),
                readBlockCoordinate(maximum, "getBlockY"),
                readBlockCoordinate(maximum, "getBlockZ")
            ));
        } catch (InvocationTargetException ex) {
            plugin.getLogger().fine("WorldEdit selection import failed: " + ex.getTargetException().getMessage());
            return Optional.empty();
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().fine("WorldEdit selection import unavailable: " + ex.getMessage());
            return Optional.empty();
        } catch (RuntimeException ex) {
            plugin.getLogger().fine("WorldEdit selection import failed: " + ex.getMessage());
            return Optional.empty();
        }
    }

    private int readBlockCoordinate(Object vector, String methodName) throws ReflectiveOperationException {
        Method method = vector.getClass().getMethod(methodName);
        return ((Number) method.invoke(vector)).intValue();
    }
}
