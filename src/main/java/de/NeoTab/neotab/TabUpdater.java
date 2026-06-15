package de.NeoTab.neotab;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.user.User;
import net.luckperms.api.platform.PlayerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class TabUpdater {
    private static final int AVG_PING_REFRESH_TICKS = 10;

    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final LegacyComponentSerializer legacySerializer;
    private final AtomicInteger tick;
    private final AtomicInteger onlineCount;
    private final AtomicInteger maxPlayers;
    private final AtomicInteger avgPingCache;
    private final AtomicBoolean xmxWarned;
    private final AtomicBoolean luckPermsAdapterWarned;
    private final PlaceholderSupport placeholderSupport;

    private BukkitTask task;
    private int avgPingCountdown;
    private long maxRamMbCache;

    public TabUpdater(NeoTab plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        legacySerializer = LegacyComponentSerializer.legacySection();
        tick = new AtomicInteger();
        onlineCount = new AtomicInteger();
        maxPlayers = new AtomicInteger();
        avgPingCache = new AtomicInteger();
        xmxWarned = new AtomicBoolean(false);
        luckPermsAdapterWarned = new AtomicBoolean(false);
        placeholderSupport = new PlaceholderSupport(plugin);
        maxRamMbCache = -1L;
    }

    public void initializeCounts(int online, int max) {
        onlineCount.set(online);
        maxPlayers.set(max);
    }

    public void start() {
        restart();
    }

    public void restart() {
        stop();
        placeholderSupport.refresh();
        int interval = Math.max(1, configManager.getUpdateIntervalTicks());
        task = new BukkitRunnable() {
            @Override
            public void run() {
                updateAllNow();
            }
        }.runTaskTimer(plugin, 0L, interval);
    }

    public void stop() {
        if (task == null) {
            return;
        }

        task.cancel();
        task = null;
    }

    public void handleJoin() {
        updateNowOrSchedule();
    }

    public void handleQuit() {
        updateNowOrSchedule();
    }

    public void updateAllNow() {
        int online = Bukkit.getOnlinePlayers().size();
        int max = Math.max(1, Bukkit.getServer().getMaxPlayers());
        onlineCount.set(online);
        maxPlayers.set(max);

        TabSnapshot snapshot = buildSnapshot(tick.getAndIncrement(), online, max);
        applySnapshot(snapshot);
    }

    private void updateNowOrSchedule() {
        if (Bukkit.isPrimaryThread()) {
            updateAllNow();
            return;
        }

        Bukkit.getScheduler().runTask(plugin, this::updateAllNow);
    }

    public void clearAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }
    }

    private TabSnapshot buildSnapshot(int tickValue, int online, int max) {
        RamStats stats = readRam();
        String footerMiniMessageBase = AnimationUtils.buildFooterMiniMessageBase(configManager, stats, online, max);
        return new TabSnapshot(tickValue, footerMiniMessageBase);
    }

    private void applySnapshot(TabSnapshot snapshot) {
        if (avgPingCountdown-- <= 0) {
            avgPingCountdown = AVG_PING_REFRESH_TICKS;
            avgPingCache.set(computeAveragePing());
        }

        int avgPing = avgPingCache.get();
        boolean useLuckPerms = configManager.isLuckPermsPrefixEnabled() && plugin.ensureLuckPerms() != null;
        PlayerAdapter<Player> playerAdapter = resolvePlayerAdapter(useLuckPerms);

        for (Player player : Bukkit.getOnlinePlayers()) {
            String serverNameRaw = applyPlaceholders(player, configManager.getServerNameRaw());
            String headerLegacy = AnimationUtils.buildLegacyHeader(configManager, snapshot.tickValue(), serverNameRaw);
            Component headerComponent = legacySerializer.deserialize(headerLegacy);

            int playerPing = Math.max(0, player.getPing());
            String footerMiniMessage = snapshot.footerMiniMessageBase()
                .replace("{playerPing}", AnimationUtils.colorizePingMiniMessage(playerPing))
                .replace("{player_ping}", AnimationUtils.colorizePingMiniMessage(playerPing))
                .replace("{ping}", AnimationUtils.colorizePingMiniMessage(playerPing))
                .replace("{avgPing}", AnimationUtils.colorizePingMiniMessage(avgPing))
                .replace("{avg_ping}", AnimationUtils.colorizePingMiniMessage(avgPing));
            footerMiniMessage = applyPlaceholders(player, footerMiniMessage);

            Component footerComponent = configManager.deserialize(footerMiniMessage, "ram-format");
            player.playerListName(buildPlayerListName(player, playerAdapter));
            player.sendPlayerListHeaderAndFooter(headerComponent, footerComponent);
        }
    }

    private String applyPlaceholders(Player player, String input) {
        if (!configManager.isPlaceholderApiEnabled()) {
            return input;
        }
        return placeholderSupport.setPlaceholders(player, input);
    }

    private PlayerAdapter<Player> resolvePlayerAdapter(boolean useLuckPerms) {
        if (!useLuckPerms) {
            return null;
        }

        try {
            LuckPerms luckPerms = plugin.ensureLuckPerms();
            return luckPerms == null ? null : luckPerms.getPlayerAdapter(Player.class);
        } catch (RuntimeException ex) {
            if (luckPermsAdapterWarned.compareAndSet(false, true)) {
                plugin.getLogger().fine("LuckPerms PlayerAdapter not available: " + ex.getMessage());
            }
            return null;
        }
    }

    private RamStats readRam() {
        Runtime runtime = Runtime.getRuntime();
        long usedBytes = runtime.totalMemory() - runtime.freeMemory();
        long usedMb = usedBytes / 0x100000L;
        long totalMb = readMaxRamMb();
        int percent = totalMb > 0L ? (int) Math.round((double) usedMb / (double) totalMb * 100.0) : 0;
        return new RamStats(usedMb, totalMb, percent);
    }

    private long readMaxRamMb() {
        long cached = maxRamMbCache;
        if (cached > 0L) {
            return cached;
        }

        long resolved = readXmxMb();
        maxRamMbCache = resolved;
        return resolved;
    }

    private long readXmxMb() {
        RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
        List<String> args = bean.getInputArguments();

        for (String arg : args) {
            if (arg == null) {
                continue;
            }

            String trimmed = arg.trim();
            if (!trimmed.startsWith("-Xmx")) {
                continue;
            }

            String value = trimmed.substring(4);
            if (value.startsWith("=")) {
                value = value.substring(1);
            }

            Long parsed = parseHeapSizeToMb(value);
            if (parsed != null && parsed > 0L) {
                return parsed;
            }
        }

        if (xmxWarned.compareAndSet(false, true)) {
            plugin.getLogger().warning("No -Xmx value found; falling back to Runtime.maxMemory().");
        }
        return Runtime.getRuntime().maxMemory() / 0x100000L;
    }

    private Long parseHeapSizeToMb(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String value = raw.trim();
        char last = value.charAt(value.length() - 1);
        String numberPart = value;
        double multiplier = 1.0;

        if (Character.isLetter(last)) {
            numberPart = value.substring(0, value.length() - 1);
            switch (Character.toLowerCase(last)) {
                case 'g' -> multiplier = 1024.0;
                case 'm' -> multiplier = 1.0;
                case 'k' -> multiplier = 9.765625E-4;
                case 'b' -> multiplier = 9.5367431640625E-7;
                default -> {
                    return null;
                }
            }
        }

        try {
            long numeric = Long.parseLong(numberPart);
            long result = Math.round(numeric * multiplier);
            return result > 0L ? result : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int computeAveragePing() {
        int total = 0;
        int count = 0;

        for (Player player : Bukkit.getOnlinePlayers()) {
            total += Math.max(0, player.getPing());
            count++;
        }

        if (count == 0) {
            return 0;
        }
        return (int) Math.round((double) total / (double) count);
    }

    private Component buildPlayerListName(Player player, PlayerAdapter<Player> playerAdapter) {
        if (playerAdapter == null) {
            return Component.text(player.getName());
        }

        User user = playerAdapter.getUser(player);
        if (user == null) {
            return Component.text(player.getName());
        }

        CachedMetaData metaData = user.getCachedData().getMetaData();
        String prefix = metaData.getPrefix();
        String suffix = metaData.getSuffix();
        Component nameComponent = Component.text(player.getName());

        if ((prefix == null || prefix.isBlank()) && (suffix == null || suffix.isBlank())) {
            return nameComponent;
        }

        Component prefixComponent = deserializeLuckPermsMeta(prefix, "luckperms-prefix");
        Component suffixComponent = deserializeLuckPermsMeta(suffix, "luckperms-suffix");
        return prefixComponent.append(nameComponent).append(suffixComponent);
    }

    private Component deserializeLuckPermsMeta(String input, String context) {
        if (input == null || input.isBlank()) {
            return Component.empty();
        }

        return configManager.deserialize(input, context);
    }

    public record TabSnapshot(int tickValue, String footerMiniMessageBase) {
    }

    public record RamStats(long usedMb, long totalMb, int percent) {
    }
}
