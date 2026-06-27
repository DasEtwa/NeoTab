package de.NeoTab.neotab;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public final class ScoreboardService implements Listener {
    private static final String[] UNIQUE_ENTRIES = {
        ChatColor.BLACK.toString(),
        ChatColor.DARK_BLUE.toString(),
        ChatColor.DARK_GREEN.toString(),
        ChatColor.DARK_AQUA.toString(),
        ChatColor.DARK_RED.toString(),
        ChatColor.DARK_PURPLE.toString(),
        ChatColor.GOLD.toString(),
        ChatColor.GRAY.toString(),
        ChatColor.DARK_GRAY.toString(),
        ChatColor.BLUE.toString(),
        ChatColor.GREEN.toString(),
        ChatColor.AQUA.toString(),
        ChatColor.RED.toString(),
        ChatColor.LIGHT_PURPLE.toString(),
        ChatColor.YELLOW.toString()
    };

    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final PlaceholderSupport placeholderSupport;
    private final Map<UUID, BoardSession> sessions;
    private final Set<UUID> enabledPlayers;
    private final Set<UUID> disabledPlayers;

    private RegionManager regionManager;
    private BukkitTask task;
    private int animationTick;

    public ScoreboardService(NeoTab plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        placeholderSupport = new PlaceholderSupport(plugin);
        sessions = new HashMap<>();
        enabledPlayers = new HashSet<>();
        disabledPlayers = new HashSet<>();
    }

    public void setRegionManager(RegionManager regionManager) {
        this.regionManager = regionManager;
    }

    public void start() {
        stopTaskOnly();
        placeholderSupport.refresh();
        startTaskIfNeeded();
    }

    public void restart() {
        stopTaskOnly();
        placeholderSupport.refresh();
        if (shouldRunTask()) {
            startTaskIfNeeded();
            updateAll();
            return;
        }

        clearAllSessions();
    }

    public void stop() {
        stopTaskOnly();
        clearAllSessions();
        sessions.clear();
        enabledPlayers.clear();
        disabledPlayers.clear();
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
        return configManager.getScoreboardConfig().enabled()
            || !enabledPlayers.isEmpty()
            || Bukkit.getOnlinePlayers().stream().anyMatch(this::hasRegionScoreboardProfile);
    }

    public boolean toggle(Player player) {
        if (isEnabled(player)) {
            setEnabled(player, false);
            return false;
        }

        setEnabled(player, true);
        return true;
    }

    public void setEnabled(Player player, boolean enabled) {
        UUID uuid = player.getUniqueId();
        if (enabled) {
            enabledPlayers.add(uuid);
            disabledPlayers.remove(uuid);
            startTaskIfNeeded();
            update(player, buildRenderContext(), animationTick);
            return;
        }

        enabledPlayers.remove(uuid);
        disabledPlayers.add(uuid);
        clear(player);
        stopTaskIfIdle();
    }

    public void setGlobalEnabled(boolean enabled) {
        configManager.setScoreboardEnabled(enabled);
        enabledPlayers.clear();
        disabledPlayers.clear();
        if (enabled) {
            startTaskIfNeeded();
            updateAll();
            return;
        }

        updateAll();
        stopTaskIfIdle();
    }

    private void clearAllSessions() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            clear(player);
        }
    }

    public void setTitle(String title) {
        configManager.setScoreboardTitle(title);
        updateAll();
    }

    public void setTitleStyle(AnimationUtils.Style style) {
        configManager.setScoreboardTitleStyle(style);
        updateAll();
    }

    public void setTitleAnimationEnabled(boolean enabled) {
        configManager.setScoreboardTitleAnimationEnabled(enabled);
        updateAll();
    }

    public void setUpdateIntervalTicks(int intervalTicks) {
        configManager.setScoreboardUpdateIntervalTicks(intervalTicks);
        restart();
    }

    public void setLine(int lineNumber, String text) {
        configManager.setScoreboardLine(lineNumber, text);
        updateAll();
    }

    public void clearLine(int lineNumber) {
        configManager.clearScoreboardLine(lineNumber);
        updateAll();
    }

    public void clearAllLines() {
        configManager.clearAllScoreboardLines();
        updateAll();
    }

    public void savePreset(String presetName) {
        configManager.saveScoreboardPreset(presetName);
    }

    public boolean loadPreset(String presetName) {
        boolean loaded = configManager.loadScoreboardPreset(presetName);
        if (loaded) {
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
            update(player, buildRenderContext(), animationTick);
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
        ScoreboardRenderContext renderContext = buildRenderContext();
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
        if (isEnabled(player)) {
            startTaskIfNeeded();
            Bukkit.getScheduler().runTaskLater(plugin, () -> update(player, buildRenderContext(), animationTick), 5L);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        sessions.remove(uuid);
        enabledPlayers.remove(uuid);
        disabledPlayers.remove(uuid);
        stopTaskIfIdle();
    }

    private void stopTaskOnly() {
        if (task == null) {
            return;
        }

        task.cancel();
        task = null;
    }

    private void update(Player player, ScoreboardRenderContext renderContext, int titleTick) {
        BoardSession session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> createSession());
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

    private BoardSession createSession() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective("neotab", "dummy", "NeoTab");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        HashMap<Integer, Team> teams = new HashMap<>();
        for (int index = 0; index < ConfigManager.MAX_SCOREBOARD_LINES; index++) {
            Team team = scoreboard.registerNewTeam("nt_line_" + index);
            team.addEntry(UNIQUE_ENTRIES[index]);
            teams.put(index, team);
        }

        return new BoardSession(scoreboard, objective, teams);
    }

    private void clear(Player player) {
        BoardSession removed = sessions.remove(player.getUniqueId());
        if (removed == null) {
            return;
        }

        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
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

    private String renderTitle(Player player, ConfigManager.ScoreboardProfile scoreboardProfile, ScoreboardRenderContext renderContext, int titleTick) {
        if (!scoreboardProfile.titleAnimationEnabled()) {
            return trimScoreboardText(renderText(player, scoreboardProfile.title(), "scoreboard.title", renderContext));
        }

        String resolved = replaceInternalPlaceholders(player, scoreboardProfile.title(), renderContext);
        if (configManager.isPlaceholderApiEnabled()) {
            resolved = placeholderSupport.setPlaceholders(player, resolved);
        }
        String plain = configManager.toPlain(resolved, "scoreboard.title");
        return trimScoreboardText(AnimationUtils.buildLegacyText(
            plain,
            configManager.getCustomColors(),
            scoreboardProfile.titleAnimationStyle(),
            titleTick,
            false
        ));
    }

    private String renderLine(Player player, String rawLine, ScoreboardRenderContext renderContext) {
        return trimTeamText(renderText(player, rawLine, "scoreboard.line", renderContext));
    }

    private String renderText(Player player, String rawText, String context, ScoreboardRenderContext renderContext) {
        String resolved = replaceInternalPlaceholders(player, rawText == null ? "" : rawText, renderContext);
        if (configManager.isPlaceholderApiEnabled()) {
            resolved = placeholderSupport.setPlaceholders(player, resolved);
        }
        return configManager.toLegacy(resolved, context);
    }

    private String replaceInternalPlaceholders(Player player, String input, ScoreboardRenderContext renderContext) {
        int ping = Math.max(0, player.getPing());

        return input
            .replace("{online}", Integer.toString(renderContext.online()))
            .replace("{max}", Integer.toString(renderContext.max()))
            .replace("{ping}", Integer.toString(ping))
            .replace("{avg_ping}", Integer.toString(renderContext.avgPing()))
            .replace("{avgPing}", Integer.toString(renderContext.avgPing()))
            .replace("{ram_used}", Long.toString(renderContext.usedMb()))
            .replace("{ram_max}", Long.toString(renderContext.maxMb()))
            .replace("{ram_percent}", Integer.toString(renderContext.percent()))
            .replace("{player}", player.getName())
            .replace("{player_name}", player.getName())
            .replace("{server_name}", activeTabProfile(player).serverNamePlain());
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
        if (text == null) {
            return "";
        }
        return trimLegacyText(text, 128);
    }

    private String trimTeamText(String text) {
        return trimLegacyText(text, 64);
    }

    private String trimLegacyText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }

        int end = maxLength;
        if (end > 0 && text.charAt(end - 1) == ChatColor.COLOR_CHAR) {
            end--;
        }
        return text.substring(0, end);
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
        private final String[] lines;
        private final boolean[] visibleLines;
        private String title;

        private BoardSession(Scoreboard scoreboard, Objective objective, Map<Integer, Team> teams) {
            this.scoreboard = scoreboard;
            this.objective = objective;
            this.teams = teams;
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
