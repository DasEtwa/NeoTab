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

    private BukkitTask task;

    public ScoreboardService(NeoTab plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        placeholderSupport = new PlaceholderSupport(plugin);
        sessions = new HashMap<>();
        enabledPlayers = new HashSet<>();
        disabledPlayers = new HashSet<>();
    }

    public void start() {
        stopTaskOnly();
        placeholderSupport.refresh();
        int interval = Math.max(1, configManager.getScoreboardConfig().updateIntervalTicks());
        task = new BukkitRunnable() {
            @Override
            public void run() {
                updateAll();
            }
        }.runTaskTimer(plugin, 0L, interval);
    }

    public void restart() {
        placeholderSupport.refresh();
        start();
        updateAll();
    }

    public void stop() {
        stopTaskOnly();
        for (Player player : Bukkit.getOnlinePlayers()) {
            clear(player);
        }
        sessions.clear();
        enabledPlayers.clear();
        disabledPlayers.clear();
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
            update(player);
            return;
        }

        enabledPlayers.remove(uuid);
        disabledPlayers.add(uuid);
        clear(player);
    }

    public void setGlobalEnabled(boolean enabled) {
        configManager.setScoreboardEnabled(enabled);
        enabledPlayers.clear();
        disabledPlayers.clear();
        updateAll();
    }

    public void setTitle(String title) {
        configManager.setScoreboardTitle(title);
        updateAll();
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

    public List<String> listPresets() {
        return new ArrayList<>(configManager.getScoreboardConfig().presets().keySet());
    }

    public boolean isEnabled(Player player) {
        UUID uuid = player.getUniqueId();
        if (enabledPlayers.contains(uuid)) {
            return true;
        }
        return configManager.getScoreboardConfig().enabled() && !disabledPlayers.contains(uuid);
    }

    public void updateAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isEnabled(player)) {
                update(player);
            } else {
                clear(player);
            }
        }
    }

    public void handleJoin(Player player) {
        if (isEnabled(player)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> update(player), 5L);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        sessions.remove(uuid);
        enabledPlayers.remove(uuid);
        disabledPlayers.remove(uuid);
    }

    private void stopTaskOnly() {
        if (task == null) {
            return;
        }

        task.cancel();
        task = null;
    }

    private void update(Player player) {
        BoardSession session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> createSession());
        ConfigManager.ScoreboardConfig scoreboardConfig = configManager.getScoreboardConfig();

        session.objective().setDisplayName(renderTitle(player, scoreboardConfig.title()));
        List<String> lines = scoreboardConfig.lines();
        int visibleLineCount = Math.min(lines.size(), ConfigManager.MAX_SCOREBOARD_LINES);

        for (int index = 0; index < ConfigManager.MAX_SCOREBOARD_LINES; index++) {
            Team team = session.teams().get(index);
            String entry = UNIQUE_ENTRIES[index];
            if (index < visibleLineCount) {
                team.setPrefix(renderLine(player, lines.get(index)));
                session.objective().getScore(entry).setScore(ConfigManager.MAX_SCOREBOARD_LINES - index);
            } else {
                team.setPrefix("");
                session.scoreboard().resetScores(entry);
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

    private String renderTitle(Player player, String rawTitle) {
        return trimScoreboardText(renderText(player, rawTitle, "scoreboard.title"));
    }

    private String renderLine(Player player, String rawLine) {
        return trimScoreboardText(renderText(player, rawLine, "scoreboard.line"));
    }

    private String renderText(Player player, String rawText, String context) {
        String resolved = replaceInternalPlaceholders(player, rawText == null ? "" : rawText);
        if (configManager.isPlaceholderApiEnabled()) {
            resolved = placeholderSupport.setPlaceholders(player, resolved);
        }
        return configManager.toLegacy(resolved, context);
    }

    private String replaceInternalPlaceholders(Player player, String input) {
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 0x100000L;
        long maxMb = Math.max(1L, runtime.maxMemory() / 0x100000L);
        int percent = (int) Math.round((double) usedMb / (double) maxMb * 100.0);
        int online = Bukkit.getOnlinePlayers().size();
        int max = Math.max(1, Bukkit.getMaxPlayers());
        int ping = Math.max(0, player.getPing());
        int avgPing = computeAveragePing();

        return input
            .replace("{online}", Integer.toString(online))
            .replace("{max}", Integer.toString(max))
            .replace("{ping}", Integer.toString(ping))
            .replace("{avg_ping}", Integer.toString(avgPing))
            .replace("{avgPing}", Integer.toString(avgPing))
            .replace("{ram_used}", Long.toString(usedMb))
            .replace("{ram_max}", Long.toString(maxMb))
            .replace("{ram_percent}", Integer.toString(percent))
            .replace("{server_name}", configManager.getServerNamePlain());
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
        return text.length() <= 128 ? text : text.substring(0, 128);
    }

    private record BoardSession(
        Scoreboard scoreboard,
        Objective objective,
        Map<Integer, Team> teams
    ) {
    }
}
