package de.NeoTab.neotab;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPermsProvider;
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
    private final LegacyComponentSerializer legacyAmpersandSerializer;
    private final AtomicInteger tick;
    private final AtomicInteger onlineCount;
    private final AtomicInteger maxPlayers;
    private final AtomicInteger avgPingCache;
    private final AtomicBoolean xmxWarned;
    private final AtomicBoolean luckPermsAdapterWarned;
    private final PlaceholderSupport placeholderSupport;

    private BukkitTask task;
    private int avgPingCountdown;

    public TabUpdater(NeoTab plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        legacySerializer = LegacyComponentSerializer.legacySection();
        legacyAmpersandSerializer = LegacyComponentSerializer.legacyAmpersand();
        tick = new AtomicInteger();
        onlineCount = new AtomicInteger();
        maxPlayers = new AtomicInteger();
        avgPingCache = new AtomicInteger();
        xmxWarned = new AtomicBoolean(false);
        luckPermsAdapterWarned = new AtomicBoolean(false);
        placeholderSupport = new PlaceholderSupport(plugin);
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
                TabSnapshot snapshot = buildSnapshot(tick.getAndIncrement());
                Bukkit.getScheduler().runTask(plugin, () -> applySnapshot(snapshot));
            }
        }.runTaskTimerAsynchronously(plugin, 0L, interval);
    }

    public void stop() {
        if (task == null) {
            return;
        }

        task.cancel();
        task = null;
    }

    public void handleJoin() {
        onlineCount.incrementAndGet();
        updateAllNow();
    }

    public void handleQuit() {
        onlineCount.updateAndGet(current -> Math.max(0, current - 1));
        updateAllNow();
    }

    public void updateAllNow() {
        TabSnapshot snapshot = buildSnapshot(tick.get());
        applySnapshot(snapshot);
    }

    public void clearAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }
    }

    private TabSnapshot buildSnapshot(int tickValue) {
        RamStats stats = readRam();
        int online = onlineCount.get();
        int max = Math.max(1, maxPlayers.get());
        String footerMiniMessageBase = AnimationUtils.buildFooterMiniMessageBase(configManager, stats, online, max);
        return new TabSnapshot(tickValue, footerMiniMessageBase);
    }

    private void applySnapshot(TabSnapshot snapshot) {
        if (avgPingCountdown-- <= 0) {
            avgPingCountdown = AVG_PING_REFRESH_TICKS;
            avgPingCache.set(computeAveragePing());
        }

        onlineCount.set(Bukkit.getOnlinePlayers().size());
        maxPlayers.set(Math.max(1, Bukkit.getServer().getMaxPlayers()));

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
                .replace("{avgPing}", AnimationUtils.colorizePingMiniMessage(avgPing));
            footerMiniMessage = applyPlaceholders(player, footerMiniMessage);

            String legacyFooter = legacySerializer.serialize(configManager.deserialize(footerMiniMessage, "ram-format"));
            if (legacyFooter.contains("<")) {
                legacyFooter = manualLegacyFallback(legacyFooter);
            }

            Component footerComponent = legacySerializer.deserialize(legacyFooter);
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
            return LuckPermsProvider.get().getPlayerAdapter(Player.class);
        } catch (IllegalStateException ex) {
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
        long totalMb = readXmxMb();
        int percent = totalMb > 0L ? (int) Math.round((double) usedMb / (double) totalMb * 100.0) : 0;
        return new RamStats(usedMb, totalMb, percent);
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
        String prefix = sanitizeLuckPermsMeta(metaData.getPrefix());
        String suffix = sanitizeLuckPermsMeta(metaData.getSuffix());
        Component nameComponent = Component.text(player.getName());

        if (prefix.isEmpty() && suffix.isEmpty()) {
            return nameComponent;
        }

        Component prefixComponent = legacyAmpersandSerializer.deserialize(prefix);
        Component suffixComponent = legacyAmpersandSerializer.deserialize(suffix);
        return prefixComponent.append(nameComponent).append(suffixComponent);
    }

    private String sanitizeLuckPermsMeta(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        String sanitized = input.replace("\u00C2", "").replace('\u00A7', '&');
        if (sanitized.indexOf('<') >= 0) {
            sanitized = sanitized.replaceAll("<[^>]+>", "");
        }
        return sanitized;
    }

    private String manualLegacyFallback(String input) {
        String legacy = input
            .replace("<gray>", "\u00A77")
            .replace("</gray>", "")
            .replace("<light_purple>", "\u00A7d")
            .replace("</light_purple>", "")
            .replace("<green>", "\u00A7a")
            .replace("</green>", "")
            .replace("<yellow>", "\u00A7e")
            .replace("</yellow>", "")
            .replace("<red>", "\u00A7c")
            .replace("</red>", "")
            .replace("<white>", "\u00A7f")
            .replace("</white>", "");

        return legacy.replaceAll("<[^>]+>", "");
    }

    public record TabSnapshot(int tickValue, String footerMiniMessageBase) {
    }

    public record RamStats(long usedMb, long totalMb, int percent) {
    }
}
