package de.NeoTab.neotab;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public final class ScoreboardService implements Listener {
    private static final int MAX_OBJECTIVE_DISPLAY_NAME_LENGTH = 128;
    private static final int MAX_TEAM_PREFIX_LENGTH = 64;
    private static final int MANY_DYNAMIC_LINES_THRESHOLD = 5;
    private static final long RENDER_CONTEXT_CACHE_NANOS = TimeUnit.SECONDS.toNanos(1L);
    private static final List<String> INTERNAL_PLACEHOLDERS = List.of(
        "{online}", "{max}", "{ping}", "{avg_ping}", "{avgPing}",
        "{ram_used}", "{ram_max}", "{ram_percent}", "{player}",
        "{player_name}", "{server_name}"
    );
    private static final List<String> PLAYER_PLACEHOLDERS = List.of(
        "{ping}", "{player}", "{player_name}", "{server_name}"
    );
    private static final String[] UNIQUE_ENTRIES = {
        "§0", "§1", "§2", "§3", "§4", "§5", "§6", "§7",
        "§8", "§9", "§a", "§b", "§c", "§d", "§e"
    };

    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final PlaceholderSupport placeholderSupport;
    private final Map<UUID, BoardSession> sessions;
    private final Set<UUID> enabledPlayers;
    private final Set<UUID> disabledPlayers;
    private final Set<UUID> externalScoreboardOwners;
    private final Map<UUID, PendingJoinUpdate> pendingJoinUpdates;
    private final Map<TemplateKey, CompiledTemplate> templateCache;
    private final Map<TitleFrameKey, String> titleFrameCache;
    private final Map<TemplateKey, String> sharedLegacyCache;
    private final Map<TemplateKey, String> sharedPlainCache;

    private RegionManager regionManager;
    private BukkitTask task;
    private int animationTick;
    private long joinGeneration;
    private ScoreboardRenderContext cachedRenderContext;
    private long renderContextCreatedNanos;
    private int titleFrameCacheTick = Integer.MIN_VALUE;

    public ScoreboardService(NeoTab plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        placeholderSupport = new PlaceholderSupport(plugin);
        sessions = new HashMap<>();
        enabledPlayers = new HashSet<>();
        disabledPlayers = new HashSet<>();
        externalScoreboardOwners = new HashSet<>();
        pendingJoinUpdates = new HashMap<>();
        templateCache = new HashMap<>();
        titleFrameCache = new HashMap<>();
        sharedLegacyCache = new HashMap<>();
        sharedPlainCache = new HashMap<>();
    }

    public void setRegionManager(RegionManager regionManager) {
        this.regionManager = regionManager;
    }

    public void start() {
        stopTaskOnly();
        cancelAllPendingJoinUpdates();
        placeholderSupport.refresh();
        invalidateRenderCaches();
        startTaskIfNeeded();
    }

    public void restart() {
        stopTaskOnly();
        cancelAllPendingJoinUpdates();
        placeholderSupport.refresh();
        invalidateRenderCaches();
        if (shouldRunTask()) {
            startTaskIfNeeded();
            updateAll();
            return;
        }

        clearAllSessions();
    }

    public void stop() {
        stopTaskOnly();
        cancelAllPendingJoinUpdates();
        clearAllSessions();
        sessions.clear();
        enabledPlayers.clear();
        disabledPlayers.clear();
        externalScoreboardOwners.clear();
        invalidateRenderCaches();
    }

    private void startTaskIfNeeded() {
        if (task != null || !shouldRunTask()) {
            return;
        }

        int interval = Math.max(1, configManager.getScoreboardConfig().updateIntervalTicks());
        task = new BukkitRunnable() {
            @Override
            public void run() {
                updateAll();
            }
        }.runTaskTimer(plugin, 0L, interval);
    }

    private boolean shouldRunTask() {
        return configManager.getScoreboardConfig().enabled();
    }

    public boolean toggle(Player player) {
        if (isEnabled(player)) {
            setEnabled(player, false);
            return false;
        }

        setEnabled(player, true);
        return isEnabled(player);
    }

    public void setEnabled(Player player, boolean enabled) {
        UUID uuid = player.getUniqueId();
        if (enabled) {
            if (!configManager.getScoreboardConfig().enabled()) {
                clear(player);
                return;
            }
            externalScoreboardOwners.remove(uuid);
            enabledPlayers.add(uuid);
            disabledPlayers.remove(uuid);
            startTaskIfNeeded();
            update(player, sharedRenderContext(), animationTick);
            return;
        }

        enabledPlayers.remove(uuid);
        disabledPlayers.add(uuid);
        externalScoreboardOwners.remove(uuid);
        cancelPendingJoinUpdate(uuid);
        clear(player);
        stopTaskIfIdle();
    }

    public void setGlobalEnabled(boolean enabled) {
        cancelAllPendingJoinUpdates();
        configManager.setScoreboardEnabled(enabled);
        enabledPlayers.clear();
        disabledPlayers.clear();
        externalScoreboardOwners.clear();
        if (enabled) {
            invalidateSharedRenderContext();
            startTaskIfNeeded();
            updateAll();
            return;
        }

        clearAllSessions();
        stopTaskIfIdle();
    }

    private void clearAllSessions() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            clear(player);
        }
    }

    public void setTitle(String title) {
        configManager.setScoreboardTitle(title);
        invalidateTemplateCache();
        updateAll();
    }

    public void setTitleStyle(AnimationUtils.Style style) {
        configManager.setScoreboardTitleStyle(style);
        invalidateTemplateCache();
        updateAll();
    }

    public void setTitleAnimationEnabled(boolean enabled) {
        configManager.setScoreboardTitleAnimationEnabled(enabled);
        invalidateTemplateCache();
        updateAll();
    }

    public void setUpdateIntervalTicks(int intervalTicks) {
        configManager.setScoreboardUpdateIntervalTicks(intervalTicks);
        restart();
    }

    public void setLine(int lineNumber, String text) {
        configManager.setScoreboardLine(lineNumber, text);
        invalidateTemplateCache();
        updateAll();
    }

    public void clearLine(int lineNumber) {
        configManager.clearScoreboardLine(lineNumber);
        invalidateTemplateCache();
        updateAll();
    }

    public void clearAllLines() {
        configManager.clearAllScoreboardLines();
        invalidateTemplateCache();
        updateAll();
    }

    public void savePreset(String presetName) {
        configManager.saveScoreboardPreset(presetName);
    }

    public boolean loadPreset(String presetName) {
        boolean loaded = configManager.loadScoreboardPreset(presetName);
        if (loaded) {
            invalidateTemplateCache();
            updateAll();
        }
        return loaded;
    }

    public boolean deletePreset(String presetName) {
        return configManager.deleteScoreboardPreset(presetName);
    }

    public List<String> listPresets() {
        return new ArrayList<>(configManager.getScoreboardConfig().presets().keySet());
    }

    public boolean isEnabled(Player player) {
        if (!configManager.getScoreboardConfig().enabled()) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        if (enabledPlayers.contains(uuid)) {
            return true;
        }
        if (hasRegionScoreboardProfile(player)) {
            return !disabledPlayers.contains(uuid);
        }
        return configManager.getScoreboardConfig().enabled() && !disabledPlayers.contains(uuid);
    }

    public void handleRegionProfileChange(Player player) {
        if (isEnabled(player)) {
            startTaskIfNeeded();
            update(player, sharedRenderContext(), animationTick);
            return;
        }
        clear(player);
        stopTaskIfIdle();
    }

    public void updateAll() {
        if (!shouldRunTask()) {
            clearAllSessions();
            stopTaskOnly();
            return;
        }

        startTaskIfNeeded();
        ScoreboardRenderContext renderContext = sharedRenderContext();
        int titleTick = animationTick++;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isEnabled(player)) {
                update(player, renderContext, titleTick);
            } else {
                clear(player);
            }
        }
    }

    public void handleJoin(Player player) {
        UUID uuid = player.getUniqueId();
        externalScoreboardOwners.remove(uuid);
        cancelPendingJoinUpdate(uuid);
        invalidateSharedRenderContext();
        if (!isEnabled(player)) {
            return;
        }

        startTaskIfNeeded();
        long generation = ++joinGeneration;
        BukkitTask pendingTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingJoinUpdate pendingUpdate = pendingJoinUpdates.get(uuid);
            Player currentPlayer = Bukkit.getPlayer(uuid);
            boolean valid = pendingUpdate != null && isJoinUpdateValid(
                plugin.isEnabled(),
                currentPlayer != null && currentPlayer.isOnline(),
                configManager.getScoreboardConfig().enabled(),
                currentPlayer != null && isEnabled(currentPlayer),
                generation,
                pendingUpdate.generation()
            );
            if (pendingUpdate != null && pendingUpdate.generation() == generation) {
                pendingJoinUpdates.remove(uuid);
            }
            if (!valid) {
                return;
            }
            update(currentPlayer, sharedRenderContext(), animationTick);
        }, 5L);
        pendingJoinUpdates.put(uuid, new PendingJoinUpdate(generation, pendingTask));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        sessions.remove(uuid);
        enabledPlayers.remove(uuid);
        disabledPlayers.remove(uuid);
        externalScoreboardOwners.remove(uuid);
        cancelPendingJoinUpdate(uuid);
        invalidateSharedRenderContext();
        stopTaskIfIdle();
    }

    static boolean isJoinUpdateValid(
        boolean pluginEnabled,
        boolean playerOnline,
        boolean globalEnabled,
        boolean playerEnabled,
        long scheduledGeneration,
        long currentGeneration
    ) {
        return pluginEnabled
            && playerOnline
            && globalEnabled
            && playerEnabled
            && scheduledGeneration == currentGeneration;
    }

    private void cancelPendingJoinUpdate(UUID uuid) {
        PendingJoinUpdate pendingUpdate = pendingJoinUpdates.remove(uuid);
        if (pendingUpdate != null) {
            pendingUpdate.task().cancel();
        }
    }

    private void cancelAllPendingJoinUpdates() {
        for (PendingJoinUpdate pendingUpdate : pendingJoinUpdates.values()) {
            pendingUpdate.task().cancel();
        }
        pendingJoinUpdates.clear();
        joinGeneration++;
    }

    private void stopTaskOnly() {
        if (task == null) {
            return;
        }

        task.cancel();
        task = null;
    }

    private void update(Player player, ScoreboardRenderContext renderContext, int titleTick) {
        if (player == null || !player.isOnline()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        BoardSession session = sessions.get(uuid);
        Scoreboard currentScoreboard = player.getScoreboard();
        ScoreboardClaimAction claimAction = claimAction(
            session != null,
            session != null && currentScoreboard == session.scoreboard(),
            isAvailable(currentScoreboard)
        );
        if (claimAction == ScoreboardClaimAction.WAIT_FOR_EXTERNAL_OWNER) {
            if (externalScoreboardOwners.add(uuid)) {
                plugin.getLogger().fine("Skipping NeoTab scoreboard for " + player.getName() + " because their current scoreboard is managed externally.");
            }
            return;
        }

        boolean resumed = externalScoreboardOwners.remove(uuid);
        if (claimAction == ScoreboardClaimAction.CREATE_SESSION) {
            session = createSession(currentScoreboard);
            sessions.put(uuid, session);
        }
        if (resumed) {
            plugin.getLogger().fine("Resuming NeoTab scoreboard for " + player.getName() + " after external ownership ended.");
        }
        ConfigManager.ScoreboardProfile scoreboardProfile = activeScoreboardProfile(player);

        String renderedTitle = renderTitle(player, scoreboardProfile, renderContext, titleTick);
        if (!renderedTitle.equals(session.title())) {
            session.objective().setDisplayName(renderedTitle);
            session.setTitle(renderedTitle);
        }

        List<String> lines = scoreboardProfile.lines();
        int visibleLineCount = Math.min(lines.size(), ConfigManager.MAX_SCOREBOARD_LINES);

        for (int index = 0; index < ConfigManager.MAX_SCOREBOARD_LINES; index++) {
            Team team = session.teams().get(index);
            String entry = UNIQUE_ENTRIES[index];
            if (index < visibleLineCount) {
                String renderedLine = renderLine(player, lines.get(index), renderContext);
                if (!session.isVisible(index)) {
                    session.objective().getScore(entry).setScore(ConfigManager.MAX_SCOREBOARD_LINES - index);
                    session.setVisible(index, true);
                }
                if (!renderedLine.equals(session.line(index))) {
                    team.setPrefix(renderedLine);
                    session.setLine(index, renderedLine);
                }
            } else {
                if (session.isVisible(index)) {
                    team.setPrefix("");
                    session.scoreboard().resetScores(entry);
                    session.setLine(index, "");
                    session.setVisible(index, false);
                }
            }
        }

        if (player.getScoreboard() != session.scoreboard()) {
            player.setScoreboard(session.scoreboard());
        }
    }

    private BoardSession createSession(Scoreboard previousScoreboard) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective("neotab", Criteria.DUMMY, "NeoTab");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        HashMap<Integer, Team> teams = new HashMap<>();
        for (int index = 0; index < ConfigManager.MAX_SCOREBOARD_LINES; index++) {
            Team team = scoreboard.registerNewTeam("nt_line_" + index);
            team.addEntry(UNIQUE_ENTRIES[index]);
            teams.put(index, team);
        }

        return new BoardSession(scoreboard, objective, teams, previousScoreboard);
    }

    private void clear(Player player) {
        UUID uuid = player.getUniqueId();
        externalScoreboardOwners.remove(uuid);
        BoardSession removed = sessions.remove(uuid);
        if (removed == null) {
            return;
        }

        if (player.getScoreboard() == removed.scoreboard()) {
            player.setScoreboard(removed.previousScoreboard());
        }
    }

    private boolean isAvailable(Scoreboard scoreboard) {
        return scoreboard.getObjectives().isEmpty() && scoreboard.getTeams().isEmpty();
    }

    static ScoreboardClaimAction claimAction(boolean hasSession, boolean currentIsSession, boolean currentIsAvailable) {
        if (hasSession && currentIsSession) {
            return ScoreboardClaimAction.REUSE_SESSION;
        }
        if (currentIsAvailable) {
            return ScoreboardClaimAction.CREATE_SESSION;
        }
        return ScoreboardClaimAction.WAIT_FOR_EXTERNAL_OWNER;
    }

    enum ScoreboardClaimAction {
        REUSE_SESSION,
        CREATE_SESSION,
        WAIT_FOR_EXTERNAL_OWNER
    }

    private ScoreboardRenderContext buildRenderContext() {
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 0x100000L;
        long maxMb = Math.max(1L, runtime.maxMemory() / 0x100000L);
        int percent = (int) Math.round((double) usedMb / (double) maxMb * 100.0);
        int online = Bukkit.getOnlinePlayers().size();
        int max = Math.max(1, Bukkit.getMaxPlayers());
        int avgPing = computeAveragePing();
        return new ScoreboardRenderContext(online, max, usedMb, maxMb, percent, avgPing);
    }

    private ScoreboardRenderContext sharedRenderContext() {
        long now = System.nanoTime();
        if (cachedRenderContext == null || now - renderContextCreatedNanos >= RENDER_CONTEXT_CACHE_NANOS) {
            sharedLegacyCache.clear();
            sharedPlainCache.clear();
            cachedRenderContext = buildRenderContext();
            renderContextCreatedNanos = now;
        }
        return cachedRenderContext;
    }

    private void invalidateSharedRenderContext() {
        cachedRenderContext = null;
        renderContextCreatedNanos = 0L;
        sharedLegacyCache.clear();
        sharedPlainCache.clear();
    }

    private String renderTitle(Player player, ConfigManager.ScoreboardProfile scoreboardProfile, ScoreboardRenderContext renderContext, int titleTick) {
        CompiledTemplate template = compiledTemplate(scoreboardProfile.title(), "scoreboard.title");
        if (!scoreboardProfile.titleAnimationEnabled()) {
            return trimScoreboardText(renderText(player, template, renderContext));
        }

        boolean usePlaceholderApi = usesPlaceholderApi(template);
        if (!template.dynamicFor(usePlaceholderApi)) {
            return cachedTitleFrame(template.staticPlain(), scoreboardProfile.titleAnimationStyle(), titleTick);
        }
        if (template.sharedDynamic(usePlaceholderApi)) {
            String sharedPlain = sharedPlainCache.computeIfAbsent(
                new TemplateKey(template.rawText(), template.context()),
                ignored -> configManager.toPlain(
                    replaceSharedInternalPlaceholders(template.rawText(), renderContext),
                    template.context()
                )
            );
            return cachedTitleFrame(sharedPlain, scoreboardProfile.titleAnimationStyle(), titleTick);
        }
        String resolved = template.internalDynamic()
            ? replaceInternalPlaceholders(player, template.rawText(), renderContext)
            : template.rawText();
        if (usePlaceholderApi) {
            resolved = placeholderSupport.setPlaceholders(player, resolved);
        }
        String plain = configManager.toPlain(resolved, template.context());
        return trimScoreboardText(AnimationUtils.buildLegacyText(
            plain,
            configManager.getCustomColors(),
            scoreboardProfile.titleAnimationStyle(),
            titleTick,
            false
        ));
    }

    private String cachedTitleFrame(String plain, AnimationUtils.Style style, int titleTick) {
        if (titleFrameCacheTick != titleTick) {
            titleFrameCache.clear();
            titleFrameCacheTick = titleTick;
        }
        List<TextColor> colors = configManager.getCustomColors();
        TitleFrameKey key = new TitleFrameKey(plain, style, colors);
        return titleFrameCache.computeIfAbsent(key, ignored -> trimScoreboardText(AnimationUtils.buildLegacyText(
            plain,
            colors,
            style,
            titleTick,
            false
        )));
    }

    private String renderLine(Player player, String rawLine, ScoreboardRenderContext renderContext) {
        return trimTeamText(renderText(player, compiledTemplate(rawLine, "scoreboard.line"), renderContext));
    }

    private String renderText(Player player, CompiledTemplate template, ScoreboardRenderContext renderContext) {
        boolean usePlaceholderApi = usesPlaceholderApi(template);
        if (!template.dynamicFor(usePlaceholderApi)) {
            return template.staticLegacy();
        }
        if (template.sharedDynamic(usePlaceholderApi)) {
            return sharedLegacyCache.computeIfAbsent(
                new TemplateKey(template.rawText(), template.context()),
                ignored -> configManager.toLegacy(
                    replaceSharedInternalPlaceholders(template.rawText(), renderContext),
                    template.context()
                )
            );
        }
        String resolved = template.internalDynamic()
            ? replaceInternalPlaceholders(player, template.rawText(), renderContext)
            : template.rawText();
        if (usePlaceholderApi) {
            resolved = placeholderSupport.setPlaceholders(player, resolved);
        }
        return configManager.toLegacy(resolved, template.context());
    }

    private boolean usesPlaceholderApi(CompiledTemplate template) {
        return template.placeholderApiDynamic()
            && configManager.isPlaceholderApiEnabled()
            && placeholderSupport.isAvailable();
    }

    private CompiledTemplate compiledTemplate(String rawText, String context) {
        String normalizedText = rawText == null ? "" : rawText;
        TemplateKey key = new TemplateKey(normalizedText, context);
        return templateCache.computeIfAbsent(key, ignored -> {
            boolean internalDynamic = containsInternalPlaceholder(normalizedText);
            boolean playerDynamic = containsPlayerPlaceholder(normalizedText);
            boolean placeholderApiDynamic = containsPlaceholderApiToken(normalizedText);
            return new CompiledTemplate(
                normalizedText,
                context,
                internalDynamic,
                playerDynamic,
                placeholderApiDynamic,
                configManager.toLegacy(normalizedText, context),
                configManager.toPlain(normalizedText, context)
            );
        });
    }

    private static boolean containsInternalPlaceholder(String input) {
        for (String placeholder : INTERNAL_PLACEHOLDERS) {
            if (input.contains(placeholder)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsPlayerPlaceholder(String input) {
        for (String placeholder : PLAYER_PLACEHOLDERS) {
            if (input.contains(placeholder)) {
                return true;
            }
        }
        return false;
    }

    static boolean containsPlaceholderApiToken(String input) {
        return PlaceholderSupport.containsPlaceholderToken(input);
    }

    private String replaceInternalPlaceholders(Player player, String input, ScoreboardRenderContext renderContext) {
        String resolved = replaceSharedInternalPlaceholders(input, renderContext);
        if (resolved.contains("{ping}")) {
            resolved = resolved.replace("{ping}", Integer.toString(Math.max(0, player.getPing())));
        }
        if (resolved.contains("{player}") || resolved.contains("{player_name}")) {
            resolved = resolved
                .replace("{player}", player.getName())
                .replace("{player_name}", player.getName());
        }
        if (resolved.contains("{server_name}")) {
            resolved = resolved.replace("{server_name}", activeTabProfile(player).serverNamePlain());
        }
        return resolved;
    }

    private String replaceSharedInternalPlaceholders(String input, ScoreboardRenderContext renderContext) {
        return input
            .replace("{online}", Integer.toString(renderContext.online()))
            .replace("{max}", Integer.toString(renderContext.max()))
            .replace("{avg_ping}", Integer.toString(renderContext.avgPing()))
            .replace("{avgPing}", Integer.toString(renderContext.avgPing()))
            .replace("{ram_used}", Long.toString(renderContext.usedMb()))
            .replace("{ram_max}", Long.toString(renderContext.maxMb()))
            .replace("{ram_percent}", Integer.toString(renderContext.percent()));
    }

    private ConfigManager.ScoreboardProfile activeScoreboardProfile(Player player) {
        return configManager.getScoreboardProfile(regionManager == null ? "default" : regionManager.activeScoreboardProfile(player));
    }

    private ConfigManager.TabProfile activeTabProfile(Player player) {
        return configManager.getTabProfile(regionManager == null ? "default" : regionManager.activeTabProfile(player));
    }

    private boolean hasRegionScoreboardProfile(Player player) {
        return regionManager != null && regionManager.hasActiveScoreboardProfile(player);
    }

    private int computeAveragePing() {
        int total = 0;
        int count = 0;
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            total += Math.max(0, onlinePlayer.getPing());
            count++;
        }
        return count == 0 ? 0 : (int) Math.round((double) total / (double) count);
    }

    private String trimScoreboardText(String text) {
        return truncateLegacy(text, MAX_OBJECTIVE_DISPLAY_NAME_LENGTH);
    }

    private String trimTeamText(String text) {
        return truncateLegacy(text, MAX_TEAM_PREFIX_LENGTH);
    }

    static String truncateLegacy(String text, int maxLength) {
        if (text == null || maxLength <= 0) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }

        StringBuilder result = new StringBuilder(maxLength);
        int index = 0;
        while (index < text.length()) {
            int tokenLength = legacyFormatTokenLength(text, index);
            if (tokenLength > 0) {
                if (result.length() + tokenLength > maxLength) {
                    break;
                }
                result.append(text, index, index + tokenLength);
                index += tokenLength;
                continue;
            }

            int clusterEnd = nextUnicodeClusterEnd(text, index);
            int clusterLength = clusterEnd - index;
            if (result.length() + clusterLength > maxLength) {
                break;
            }
            result.append(text, index, clusterEnd);
            index = clusterEnd;
        }
        return result.toString();
    }

    private static int legacyFormatTokenLength(String text, int index) {
        if (text.charAt(index) != '§' || index + 1 >= text.length()) {
            return 0;
        }
        if ((text.charAt(index + 1) == 'x' || text.charAt(index + 1) == 'X') && index + 13 < text.length()) {
            for (int offset = 2; offset < 14; offset += 2) {
                if (text.charAt(index + offset) != '§' || !isHexDigit(text.charAt(index + offset + 1))) {
                    return 2;
                }
            }
            return 14;
        }
        return 2;
    }

    private static boolean isHexDigit(char character) {
        return character >= '0' && character <= '9'
            || character >= 'a' && character <= 'f'
            || character >= 'A' && character <= 'F';
    }

    private static int nextUnicodeClusterEnd(String text, int start) {
        int firstCodePoint = text.codePointAt(start);
        int end = start + Character.charCount(firstCodePoint);
        if (isRegionalIndicator(firstCodePoint) && end < text.length()) {
            int secondCodePoint = text.codePointAt(end);
            if (isRegionalIndicator(secondCodePoint)) {
                end += Character.charCount(secondCodePoint);
            }
        }
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            int type = Character.getType(codePoint);
            if (type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || isVariationSelector(codePoint)
                || isEmojiModifier(codePoint)
                || isEmojiTag(codePoint)) {
                end += Character.charCount(codePoint);
                continue;
            }
            if (codePoint == 0x200D) {
                int joinedStart = end + 1;
                if (joinedStart >= text.length()) {
                    break;
                }
                end = joinedStart + Character.charCount(text.codePointAt(joinedStart));
                continue;
            }
            break;
        }
        return end;
    }

    private static boolean isVariationSelector(int codePoint) {
        return codePoint >= 0xFE00 && codePoint <= 0xFE0F
            || codePoint >= 0xE0100 && codePoint <= 0xE01EF;
    }

    private static boolean isEmojiModifier(int codePoint) {
        return codePoint >= 0x1F3FB && codePoint <= 0x1F3FF;
    }

    private static boolean isEmojiTag(int codePoint) {
        return codePoint >= 0xE0020 && codePoint <= 0xE007F;
    }

    private static boolean isRegionalIndicator(int codePoint) {
        return codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF;
    }

    public void warnIfAggressiveInterval() {
        ConfigManager.ScoreboardConfig scoreboardConfig = configManager.getScoreboardConfig();
        int dynamicLines = countDynamicLines(scoreboardConfig.lines());
        for (ConfigManager.ScoreboardPreset preset : scoreboardConfig.presets().values()) {
            // Region profiles can select presets even when the default board is static.
            dynamicLines = Math.max(dynamicLines, countDynamicLines(preset.lines()));
        }
        if (!shouldWarnAboutInterval(
            scoreboardConfig.enabled(),
            scoreboardConfig.updateIntervalTicks(),
            placeholderSupport.isAvailable(),
            dynamicLines
        )) {
            return;
        }

        plugin.getLogger().warning(
            "[NeoTab] Warning: Scoreboard update interval is set to "
                + scoreboardConfig.updateIntervalTicks()
                + " ticks.\n"
                + "This is allowed and can provide smoother animations on small servers.\n"
                + "On larger servers, especially with PlaceholderAPI or many dynamic lines,\n"
                + "this may cause high CPU usage on the main thread. Recommended for larger servers: 10-20 ticks."
        );
    }

    private static int countDynamicLines(List<String> lines) {
        int dynamicLines = 0;
        for (String line : lines) {
            if (containsInternalPlaceholder(line) || containsPlaceholderApiToken(line)) {
                dynamicLines++;
            }
        }
        return dynamicLines;
    }

    static boolean shouldWarnAboutInterval(boolean enabled, int intervalTicks, boolean placeholderApiAvailable, int dynamicLines) {
        if (!enabled) {
            return false;
        }
        if (intervalTicks < 5) {
            return true;
        }
        return intervalTicks < 10
            && (placeholderApiAvailable || dynamicLines >= MANY_DYNAMIC_LINES_THRESHOLD);
    }

    private void invalidateTemplateCache() {
        templateCache.clear();
        titleFrameCache.clear();
        sharedLegacyCache.clear();
        sharedPlainCache.clear();
        titleFrameCacheTick = Integer.MIN_VALUE;
    }

    private void invalidateRenderCaches() {
        invalidateTemplateCache();
        invalidateSharedRenderContext();
    }

    private void stopTaskIfIdle() {
        if (shouldRunTask()) {
            return;
        }

        stopTaskOnly();
    }

    private static final class BoardSession {
        private final Scoreboard scoreboard;
        private final Objective objective;
        private final Map<Integer, Team> teams;
        private final Scoreboard previousScoreboard;
        private final String[] lines;
        private final boolean[] visibleLines;
        private String title;

        private BoardSession(Scoreboard scoreboard, Objective objective, Map<Integer, Team> teams, Scoreboard previousScoreboard) {
            this.scoreboard = scoreboard;
            this.objective = objective;
            this.teams = teams;
            this.previousScoreboard = previousScoreboard;
            lines = new String[ConfigManager.MAX_SCOREBOARD_LINES];
            visibleLines = new boolean[ConfigManager.MAX_SCOREBOARD_LINES];
            title = "";
            for (int index = 0; index < lines.length; index++) {
                lines[index] = "";
            }
        }

        private Scoreboard scoreboard() {
            return scoreboard;
        }

        private Objective objective() {
            return objective;
        }

        private Map<Integer, Team> teams() {
            return teams;
        }

        private Scoreboard previousScoreboard() {
            return previousScoreboard == null ? Bukkit.getScoreboardManager().getMainScoreboard() : previousScoreboard;
        }

        private String title() {
            return title;
        }

        private void setTitle(String title) {
            this.title = title;
        }

        private String line(int index) {
            return lines[index];
        }

        private void setLine(int index, String line) {
            lines[index] = line;
        }

        private boolean isVisible(int index) {
            return visibleLines[index];
        }

        private void setVisible(int index, boolean visible) {
            visibleLines[index] = visible;
        }
    }

    private record PendingJoinUpdate(long generation, BukkitTask task) {
    }

    private record TemplateKey(String rawText, String context) {
    }

    private record TitleFrameKey(String plainText, AnimationUtils.Style style, List<TextColor> colors) {
    }

    private record CompiledTemplate(
        String rawText,
        String context,
        boolean internalDynamic,
        boolean playerDynamic,
        boolean placeholderApiDynamic,
        String staticLegacy,
        String staticPlain
    ) {
        private boolean dynamicFor(boolean usePlaceholderApi) {
            return internalDynamic || placeholderApiDynamic && usePlaceholderApi;
        }

        private boolean sharedDynamic(boolean usePlaceholderApi) {
            return internalDynamic && !playerDynamic && !usePlaceholderApi;
        }
    }

    private record ScoreboardRenderContext(
        int online,
        int max,
        long usedMb,
        long maxMb,
        int percent,
        int avgPing
    ) {
    }
}
