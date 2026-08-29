package de.NeoTab.neotab;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class TabUpdater {
    static final int SHARED_STATS_REFRESH_TICKS = 20;
    static final long PLAYER_NAME_CACHE_TTL_NANOS = 5_000_000_000L;

    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.builder()
        .character('\u00A7')
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build();

    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final LegacyComponentSerializer legacySerializer;
    private final AtomicInteger animationTick;
    private final AtomicInteger onlineCount;
    private final AtomicInteger maxPlayers;
    private final AtomicBoolean xmxWarned;
    private final AtomicBoolean luckPermsLookupWarned;
    private final PlaceholderSupport placeholderSupport;
    private final Map<UUID, TabState> tabStates;
    private final Map<UUID, CachedPlayerName> playerNameCache;
    private final Map<String, String> staticHeaderCache;
    private final MembershipBatch membershipBatch;

    private RegionManager regionManager;
    private BukkitTask animationTask;
    private BukkitTask sharedStatsTask;
    private BukkitTask membershipRefreshTask;
    private SharedRenderData sharedRenderData;
    private long maxRamMbCache;

    public TabUpdater(NeoTab plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        legacySerializer = LEGACY_SERIALIZER;
        animationTick = new AtomicInteger();
        onlineCount = new AtomicInteger();
        maxPlayers = new AtomicInteger();
        xmxWarned = new AtomicBoolean(false);
        luckPermsLookupWarned = new AtomicBoolean(false);
        placeholderSupport = new PlaceholderSupport(plugin);
        tabStates = new HashMap<>();
        playerNameCache = new HashMap<>();
        staticHeaderCache = new HashMap<>();
        membershipBatch = new MembershipBatch();
        maxRamMbCache = -1L;
    }

    public void setRegionManager(RegionManager regionManager) {
        this.regionManager = regionManager;
    }

    public void initializeCounts(int online, int max) {
        onlineCount.set(Math.max(0, online));
        maxPlayers.set(Math.max(1, max));
    }

    public void start() {
        restart();
    }

    /**
     * Restarts both independent update clocks. The configured interval controls only the animation
     * clock; shared footer statistics and expiring player-name metadata use their own slower clock.
     */
    public void restart() {
        stopTasks();
        invalidateConfigurationCaches();
        playerNameCache.clear();
        placeholderSupport.refresh();
        refreshSharedRenderData();

        int animationInterval = Math.max(1, configManager.getUpdateIntervalTicks());
        animationTask = new BukkitRunnable() {
            @Override
            public void run() {
                updateAnimatedHeaders();
            }
        }.runTaskTimer(plugin, animationInterval, animationInterval);

        sharedStatsTask = new BukkitRunnable() {
            @Override
            public void run() {
                updateSharedStatistics();
            }
        }.runTaskTimer(plugin, SHARED_STATS_REFRESH_TICKS, SHARED_STATS_REFRESH_TICKS);
    }

    /** Cancels every updater-owned task and drops all derived caches. */
    public void stop() {
        stopTasks();
        invalidateConfigurationCaches();
        playerNameCache.clear();
    }

    private void stopTasks() {
        cancelTask(animationTask);
        cancelTask(sharedStatsTask);
        cancelTask(membershipRefreshTask);
        animationTask = null;
        sharedStatsTask = null;
        membershipRefreshTask = null;
        membershipBatch.clear();
    }

    /**
     * Queues a coalesced membership refresh. All joins and quits observed before the next scheduler
     * turn share one task; only joined players require a full initial render.
     */
    public void handleJoin(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        invalidatePlayer(uuid);
        if (membershipBatch.requestJoin(uuid)) {
            scheduleMembershipRefresh();
        }
    }

    /** Compatibility overload for callers that do not have the joining player. */
    public void handleJoin() {
        if (membershipBatch.requestMembershipChange()) {
            scheduleMembershipRefresh();
        }
    }

    public void handleQuit() {
        if (membershipBatch.requestMembershipChange()) {
            scheduleMembershipRefresh();
        }
    }

    public void handleDisconnect(UUID uuid) {
        if (uuid == null) {
            return;
        }
        tabStates.remove(uuid);
        playerNameCache.remove(uuid);
        membershipBatch.removeJoin(uuid);
    }

    /** Invalidates cached LuckPerms/player-list metadata for one player. */
    public void invalidatePlayer(UUID uuid) {
        if (uuid != null) {
            playerNameCache.remove(uuid);
        }
    }

    /**
     * Renders only one player using the already cached server-wide statistics. Region changes call
     * this method, so crossing a boundary never performs an all-player ping scan.
     */
    public void updatePlayerNow(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        SharedRenderData data = ensureSharedRenderData();
        RenderBatch batch = new RenderBatch(data, resolveLuckPermsSupport());
        renderPlayer(player, batch, true, true, true, false);
    }

    /** Forces a complete render after a configuration change. */
    public void updateAllNow() {
        invalidateConfigurationCaches();
        refreshSharedRenderData();
        RenderBatch batch = new RenderBatch(sharedRenderData, resolveLuckPermsSupport());
        for (Player player : Bukkit.getOnlinePlayers()) {
            renderPlayer(player, batch, true, true, true, true);
        }
    }

    private void updateAnimatedHeaders() {
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }

        int frame = animationTick.incrementAndGet();
        SharedRenderData data = ensureSharedRenderData();
        RenderBatch batch = new RenderBatch(data, null);
        for (Player player : Bukkit.getOnlinePlayers()) {
            ConfigManager.TabProfile profile = activeTabProfile(player);
            if (!hasFastHeaderAnimation(profile)) {
                continue;
            }
            String header = buildHeader(player, profile, frame, batch.animatedHeaders());
            applyIfChanged(player, null, header, null);
        }
    }

    private void updateSharedStatistics() {
        refreshSharedRenderData();
        RenderBatch batch = new RenderBatch(sharedRenderData, resolveLuckPermsSupport());
        for (Player player : Bukkit.getOnlinePlayers()) {
            ConfigManager.TabProfile profile = activeTabProfile(player);
            boolean refreshHeader = !hasFastHeaderAnimation(profile) && headerMayChange(profile);
            renderPlayer(player, batch, true, refreshHeader, true, false);
        }
    }

    private void scheduleMembershipRefresh() {
        membershipRefreshTask = Bukkit.getScheduler().runTask(plugin, () -> {
            membershipRefreshTask = null;
            Set<UUID> joinedPlayers = membershipBatch.drain();
            if (!plugin.isEnabled()) {
                return;
            }

            refreshSharedRenderData();
            RenderBatch batch = new RenderBatch(sharedRenderData, resolveLuckPermsSupport());
            for (Player player : Bukkit.getOnlinePlayers()) {
                boolean fullRender = joinedPlayers.contains(player.getUniqueId());
                renderPlayer(player, batch, fullRender, fullRender, true, fullRender);
            }
        });
    }

    public void clearAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            TabState state = tabStates.get(player.getUniqueId());
            if (state == null) {
                continue;
            }
            if (state.nameOwned && legacyEquivalent(player.getPlayerListName(), state.lastAppliedName)) {
                player.setPlayerListName(state.previousName);
            }
            if (state.headerOwned && legacyEquivalent(player.getPlayerListHeader(), state.lastAppliedHeader)) {
                player.setPlayerListHeader(state.previousHeader);
            }
            if (state.footerOwned && legacyEquivalent(player.getPlayerListFooter(), state.lastAppliedFooter)) {
                player.setPlayerListFooter(state.previousFooter);
            }
        }
        tabStates.clear();
    }

    private void renderPlayer(
        Player player,
        RenderBatch batch,
        boolean renderName,
        boolean renderHeader,
        boolean renderFooter,
        boolean forceNameRefresh
    ) {
        if (player == null || !player.isOnline()) {
            return;
        }

        ConfigManager.TabProfile profile = activeTabProfile(player);
        String name = renderName ? cachedPlayerListName(player, batch.luckPermsSupport(), forceNameRefresh) : null;
        String header = renderHeader
            ? buildHeader(player, profile, animationTick.get(), batch.animatedHeaders())
            : null;
        String footer = renderFooter ? buildFooter(player, profile, batch.sharedData()) : null;
        applyIfChanged(player, name, header, footer);
    }

    private String buildHeader(
        Player player,
        ConfigManager.TabProfile tabProfile,
        int frame,
        Map<String, String> animatedHeaders
    ) {
        boolean playerSpecific = hasPlaceholderToken(tabProfile.serverNameRaw());
        if (!playerSpecific && hasFastHeaderAnimation(tabProfile)) {
            return animatedHeaders.computeIfAbsent(
                tabProfile.name(),
                ignored -> configManager.toLegacy(buildHeaderComponent(null, tabProfile, frame))
            );
        }
        if (!playerSpecific) {
            return staticHeaderCache.computeIfAbsent(
                tabProfile.name(),
                ignored -> configManager.toLegacy(buildHeaderComponent(null, tabProfile, frame))
            );
        }
        return configManager.toLegacy(buildHeaderComponent(player, tabProfile, frame));
    }

    private Component buildHeaderComponent(Player player, ConfigManager.TabProfile tabProfile, int frame) {
        String serverNameRaw = player == null
            ? tabProfile.serverNameRaw()
            : applyPlaceholders(player, tabProfile.serverNameRaw());
        String context = "tab-profiles." + tabProfile.name() + ".server-name";
        String plain = configManager.toPlain(serverNameRaw, context);
        if (plain == null || plain.isBlank()) {
            return Component.empty();
        }
        if (tabProfile.customColors().isEmpty()) {
            return configManager.deserialize(serverNameRaw, context);
        }
        return legacySerializer.deserialize(AnimationUtils.buildLegacyText(
            plain,
            tabProfile.customColors(),
            tabProfile.style(),
            frame,
            tabProfile.headerBoldAnimation()
        ));
    }

    private String buildFooter(Player player, ConfigManager.TabProfile tabProfile, SharedRenderData data) {
        if (canShareFooter(tabProfile.footerFormat())) {
            return data.sharedFooters().computeIfAbsent(tabProfile.name(), ignored -> configManager.toLegacy(
                configManager.deserialize(
                    buildFooterMiniMessageBase(tabProfile, data.stats(), data.online(), data.max()),
                    "tab-profiles." + tabProfile.name() + ".ram-format"
                )
            ));
        }

        int playerPing = Math.max(0, player.getPing());
        String footerMiniMessage = buildFooterMiniMessageBase(tabProfile, data.stats(), data.online(), data.max())
            .replace("{playerPing}", AnimationUtils.colorizePingMiniMessage(playerPing))
            .replace("{player_ping}", AnimationUtils.colorizePingMiniMessage(playerPing))
            .replace("{ping}", AnimationUtils.colorizePingMiniMessage(playerPing))
            .replace("{avgPing}", AnimationUtils.colorizePingMiniMessage(data.avgPing()))
            .replace("{avg_ping}", AnimationUtils.colorizePingMiniMessage(data.avgPing()));
        footerMiniMessage = applyPlaceholders(player, footerMiniMessage);
        return configManager.toLegacy(configManager.deserialize(
            footerMiniMessage,
            "tab-profiles." + tabProfile.name() + ".ram-format"
        ));
    }

    private boolean canShareFooter(String footerFormat) {
        if (footerFormat == null || hasPlaceholderToken(footerFormat)) {
            return false;
        }
        return !footerFormat.contains("{playerPing}")
            && !footerFormat.contains("{player_ping}")
            && !footerFormat.contains("{ping}")
            && !footerFormat.contains("{avgPing}")
            && !footerFormat.contains("{avg_ping}");
    }

    private boolean hasFastHeaderAnimation(ConfigManager.TabProfile profile) {
        return profile != null
            && !profile.customColors().isEmpty()
            && profile.style() != AnimationUtils.Style.STATIC;
    }

    private boolean headerMayChange(ConfigManager.TabProfile profile) {
        return profile != null && hasPlaceholderToken(profile.serverNameRaw());
    }

    private boolean hasPlaceholderToken(String input) {
        return configManager.isPlaceholderApiEnabled()
            && placeholderSupport.isAvailable()
            && input != null
            && PlaceholderSupport.containsPlaceholderToken(input);
    }

    private void applyIfChanged(Player player, String desiredName, String desiredHeader, String desiredFooter) {
        TabState state = tabStates.computeIfAbsent(player.getUniqueId(), ignored -> new TabState(
            player.getPlayerListName(),
            player.getPlayerListHeader(),
            player.getPlayerListFooter()
        ));

        if (desiredName != null) {
            String current = player.getPlayerListName();
            FieldDecision decision = decideField(state.nameOwned, state.lastAppliedName, current, desiredName);
            state.nameOwned = decision.owned();
            if (decision.write()) {
                player.setPlayerListName(desiredName);
                current = player.getPlayerListName();
            }
            if (state.nameOwned) {
                state.lastAppliedName = current;
            }
        }

        if (desiredHeader != null) {
            String current = player.getPlayerListHeader();
            FieldDecision decision = decideField(state.headerOwned, state.lastAppliedHeader, current, desiredHeader);
            state.headerOwned = decision.owned();
            if (decision.write()) {
                player.setPlayerListHeader(desiredHeader);
                current = player.getPlayerListHeader();
            }
            if (state.headerOwned) {
                state.lastAppliedHeader = current;
            }
        }

        if (desiredFooter != null) {
            String current = player.getPlayerListFooter();
            FieldDecision decision = decideField(state.footerOwned, state.lastAppliedFooter, current, desiredFooter);
            state.footerOwned = decision.owned();
            if (decision.write()) {
                player.setPlayerListFooter(desiredFooter);
                current = player.getPlayerListFooter();
            }
            if (state.footerOwned) {
                state.lastAppliedFooter = current;
            }
        }
    }

    static FieldDecision decideField(boolean owned, String lastApplied, String current, String desired) {
        boolean stillOwned = owned;
        if (stillOwned && lastApplied != null && !legacyEquivalent(current, lastApplied)) {
            stillOwned = false;
        }
        return new FieldDecision(stillOwned, stillOwned && !legacyEquivalent(current, desired));
    }

    static boolean legacyEquivalent(String first, String second) {
        if (Objects.equals(first, second)) {
            return true;
        }
        try {
            return Objects.equals(canonicalLegacy(first), canonicalLegacy(second));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String canonicalLegacy(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return LEGACY_SERIALIZER.serialize(LEGACY_SERIALIZER.deserialize(input));
    }

    private String applyPlaceholders(Player player, String input) {
        if (!hasPlaceholderToken(input)) {
            return input;
        }
        return placeholderSupport.setPlaceholders(player, input);
    }

    private ConfigManager.TabProfile activeTabProfile(Player player) {
        return configManager.getTabProfile(regionManager == null ? "default" : regionManager.activeTabProfile(player));
    }

    private String buildFooterMiniMessageBase(ConfigManager.TabProfile tabProfile, RamStats stats, int online, int max) {
        return tabProfile.footerFormat()
            .replace("{used}", Long.toString(stats.usedMb()))
            .replace("{total}", Long.toString(stats.totalMb()))
            .replace("{percent}", Integer.toString(stats.percent()))
            .replace("{ram_used}", Long.toString(stats.usedMb()))
            .replace("{ram_max}", Long.toString(stats.totalMb()))
            .replace("{ram_percent}", Integer.toString(stats.percent()))
            .replace("{online}", Integer.toString(online))
            .replace("{max}", Integer.toString(max));
    }

    private LuckPermsSupport resolveLuckPermsSupport() {
        return configManager.isLuckPermsPrefixEnabled() ? plugin.ensureLuckPerms() : null;
    }

    private void refreshSharedRenderData() {
        int online = Bukkit.getOnlinePlayers().size();
        int max = Math.max(1, Bukkit.getServer().getMaxPlayers());
        onlineCount.set(online);
        maxPlayers.set(max);
        sharedRenderData = new SharedRenderData(online, max, computeAveragePing(), readRam(), new HashMap<>());
    }

    private SharedRenderData ensureSharedRenderData() {
        if (sharedRenderData == null) {
            refreshSharedRenderData();
        }
        return sharedRenderData;
    }

    private void invalidateConfigurationCaches() {
        staticHeaderCache.clear();
        sharedRenderData = null;
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
            configManager.log(java.util.logging.Level.WARNING, "log.tab.no-xmx");
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
        return count == 0 ? 0 : (int) Math.round((double) total / (double) count);
    }

    private String cachedPlayerListName(Player player, LuckPermsSupport luckPermsSupport, boolean forceRefresh) {
        UUID uuid = player.getUniqueId();
        long now = System.nanoTime();
        CachedPlayerName cached = playerNameCache.get(uuid);
        if (!forceRefresh && cached != null && cached.validAt(now)) {
            return cached.legacyName();
        }

        String legacyName = configManager.toLegacy(buildPlayerListName(player, luckPermsSupport));
        long expiresAt = luckPermsSupport == null ? Long.MAX_VALUE : saturatingAdd(now, PLAYER_NAME_CACHE_TTL_NANOS);
        playerNameCache.put(uuid, new CachedPlayerName(legacyName, expiresAt));
        return legacyName;
    }

    static long saturatingAdd(long value, long increment) {
        long result = value + increment;
        if (((value ^ result) & (increment ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private Component buildPlayerListName(Player player, LuckPermsSupport luckPermsSupport) {
        if (luckPermsSupport == null) {
            return Component.text(player.getName());
        }
        try {
            String decoratedName = luckPermsSupport.decoratePlayerName(player);
            return decoratedName == null
                ? Component.text(player.getName())
                : configManager.deserialize(decoratedName, "luckperms-player-list-name");
        } catch (RuntimeException ex) {
            if (luckPermsLookupWarned.compareAndSet(false, true)) {
                configManager.log(java.util.logging.Level.WARNING, "log.tab.luckperms-metadata-failed", java.util.Map.of(
                    "error", String.valueOf(ex.getMessage())
                ));
            }
            return Component.text(player.getName());
        }
    }

    private static void cancelTask(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    public record RamStats(long usedMb, long totalMb, int percent) {
    }

    record FieldDecision(boolean owned, boolean write) {
    }

    private record CachedPlayerName(String legacyName, long expiresAtNanos) {
        private boolean validAt(long now) {
            return expiresAtNanos == Long.MAX_VALUE || now - expiresAtNanos < 0L;
        }
    }

    private record SharedRenderData(
        int online,
        int max,
        int avgPing,
        RamStats stats,
        Map<String, String> sharedFooters
    ) {
    }

    private record RenderBatch(
        SharedRenderData sharedData,
        LuckPermsSupport luckPermsSupport,
        Map<String, String> animatedHeaders
    ) {
        private RenderBatch(SharedRenderData sharedData, LuckPermsSupport luckPermsSupport) {
            this(sharedData, luckPermsSupport, new HashMap<>());
        }
    }

    static final class MembershipBatch {
        private final Set<UUID> joinedPlayers = new HashSet<>();
        private boolean pending;

        boolean requestJoin(UUID uuid) {
            if (uuid != null) {
                joinedPlayers.add(uuid);
            }
            return requestMembershipChange();
        }

        boolean requestMembershipChange() {
            if (pending) {
                return false;
            }
            pending = true;
            return true;
        }

        Set<UUID> drain() {
            Set<UUID> result = Set.copyOf(joinedPlayers);
            joinedPlayers.clear();
            pending = false;
            return result;
        }

        void removeJoin(UUID uuid) {
            joinedPlayers.remove(uuid);
        }

        void clear() {
            joinedPlayers.clear();
            pending = false;
        }

        boolean pending() {
            return pending;
        }
    }

    private static final class TabState {
        private final String previousName;
        private final String previousHeader;
        private final String previousFooter;
        private boolean nameOwned = true;
        private boolean headerOwned = true;
        private boolean footerOwned = true;
        private String lastAppliedName;
        private String lastAppliedHeader;
        private String lastAppliedFooter;

        private TabState(String previousName, String previousHeader, String previousFooter) {
            this.previousName = previousName;
            this.previousHeader = previousHeader == null ? "" : previousHeader;
            this.previousFooter = previousFooter == null ? "" : previousFooter;
        }
    }
}
