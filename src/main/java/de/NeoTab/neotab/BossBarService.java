package de.NeoTab.neotab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/** Global, independently scheduled boss bars. All access is on the server thread. */
final class BossBarService {
    private final NeoTab plugin;
    private final ConfigManager config;
    private final Map<String, BossBar> bars = new LinkedHashMap<>();
    private final Map<String, List<BukkitTask>> tasks = new LinkedHashMap<>();

    BossBarService(NeoTab plugin, ConfigManager config) { this.plugin = plugin; this.config = config; }

    void stop() {
        tasks.values().forEach(list -> list.forEach(BukkitTask::cancel));
        tasks.clear();
        bars.values().forEach(BossBar::removeAll);
        bars.clear();
    }

    void restart() {
        stop();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("bossbars");
        if (section == null) return;
        for (String id : section.getKeys(false)) refresh(id);
    }

    private void refresh(String id) {
        List<BukkitTask> previousTasks = tasks.remove(id);
        if (previousTasks != null) previousTasks.forEach(BukkitTask::cancel);
        BossBar previous = bars.remove(id);
        if (previous != null) previous.removeAll();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("bossbars");
        if (section == null) return;
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null || !entry.getBoolean("enabled", true)) return;
            BarColor color;
            try { color = BarColor.valueOf(entry.getString("color", "PURPLE").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) { color = BarColor.PURPLE; }
            String title = config.toLegacy(entry.getString("title", id), "bossbars." + id + ".title");
            BossBar bar = Bukkit.createBossBar(title, color, BarStyle.SOLID);
            bars.put(id, bar);
            long interval = entry.getLong("interval-seconds", 0);
            long duration = entry.getLong("duration-seconds", 0);
            if ((interval != 0 || duration != 0) && !(interval > 0 && duration > 0 && duration < interval && interval <= 604800)) {
                plugin.getLogger().warning("BossBar '" + id + "' has an invalid schedule and was disabled.");
                bars.remove(id);
                return;
            }
            boolean scheduled = interval > 0 && duration > 0 && duration < interval && interval <= 604800;
            bar.setVisible(true);
            Bukkit.getOnlinePlayers().forEach(bar::addPlayer);
            if (scheduled) {
                List<BukkitTask> scheduledTasks = new ArrayList<>();
                tasks.put(id, scheduledTasks);
                // Show immediately after creation/reload; interval is measured between starts.
                scheduledTasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> bar.setVisible(false), duration * 20L, interval * 20L));
                scheduledTasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> bar.setVisible(true), interval * 20L, interval * 20L));
            }
    }

    void join(Player player) { bars.values().forEach(bar -> bar.addPlayer(player)); }
    void quit(Player player) { bars.values().forEach(bar -> bar.removePlayer(player)); }

    boolean command(CommandSender sender, String[] args) {
        if (!sender.hasPermission("neotab.bossbar")) { sender.sendMessage(config.message("no-permission")); return true; }
        if (args.length < 2) { help(sender); return true; }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            ConfigurationSection section = plugin.getConfig().getConfigurationSection("bossbars");
            sender.sendMessage("BossBars: " + (section == null ? "-" : String.join(", ", section.getKeys(false))));
            return true;
        }
        if (args.length < 3 || !args[2].matches("[A-Za-z0-9_-]{1,32}")) { help(sender); return true; }
        String id = args[2].toLowerCase(Locale.ROOT);
        boolean exists = plugin.getConfig().isConfigurationSection("bossbars." + id);
        if (action.equals("add")) {
            if (exists) { reply(sender, "BossBar existiert bereits.", "BossBar already exists."); return true; }
            String title = args.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : id;
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("title", title); values.put("color", "PURPLE"); values.put("enabled", true);
            values.put("interval-seconds", 0); values.put("duration-seconds", 0);
            config.setBossBarValue(id, values);
        } else {
            if (!exists) { reply(sender, "BossBar nicht gefunden.", "BossBar not found."); return true; }
            switch (action) {
                case "edit", "title" -> {
                    if (args.length < 4) { help(sender); return true; }
                    config.setBossBarValue(id + ".title", String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)));
                }
                case "color", "colour" -> {
                    if (args.length != 4) { help(sender); return true; }
                    try { config.setBossBarValue(id + ".color", BarColor.valueOf(args[3].toUpperCase(Locale.ROOT)).name()); }
                    catch (IllegalArgumentException ex) { reply(sender, "Farben: pink, blue, red, green, yellow, purple, white", "Colors: pink, blue, red, green, yellow, purple, white"); return true; }
                }
                case "on", "off" -> config.setBossBarValue(id + ".enabled", action.equals("on"));
                case "remove", "delete" -> config.setBossBarValue(id, null);
                case "schedule" -> {
                    if (args.length == 4 && args[3].equalsIgnoreCase("off")) {
                        Map<String, Object> values = new LinkedHashMap<>(plugin.getConfig().getConfigurationSection("bossbars." + id).getValues(false));
                        values.put("interval-seconds", 0); values.put("duration-seconds", 0);
                        config.setBossBarValue(id, values);
                    } else {
                        if (args.length != 5) { help(sender); return true; }
                        int interval = parseScheduleSeconds(args[3]);
                        int duration = parseScheduleSeconds(args[4]);
                        if (interval <= 0 || interval > 604800 || duration <= 0 || duration >= interval) {
                            reply(sender, "Dauer muss kuerzer als das Intervall sein (maximal 7 Tage).", "Duration must be shorter than the interval (maximum 7 days)."); return true;
                        }
                        Map<String, Object> values = new LinkedHashMap<>(plugin.getConfig().getConfigurationSection("bossbars." + id).getValues(false));
                        values.put("interval-seconds", interval); values.put("duration-seconds", duration);
                        config.setBossBarValue(id, values);
                    }
                }
                default -> { help(sender); return true; }
            }
        }
        refresh(id);
        reply(sender, "BossBar aktualisiert: " + id, "BossBar updated: " + id);
        return true;
    }

    static int parseScheduleSeconds(String input) {
        if (input == null || !input.matches("(?i)[0-9]+[smh]?")) return -1;
        String value = input.toLowerCase(Locale.ROOT);
        char unit = value.charAt(value.length() - 1);
        long multiplier = unit == 'h' ? 3600 : unit == 'm' ? 60 : 1;
        if (Character.isLetter(unit)) value = value.substring(0, value.length() - 1);
        try {
            long amount = Long.parseLong(value);
            if (amount <= 0 || amount > 604800 / multiplier) return -1;
            return (int) (amount * multiplier);
        } catch (NumberFormatException ex) { return -1; }
    }

    List<String> complete(String[] args) {
        List<String> candidates = new ArrayList<>();
        if (args.length == 2) candidates.addAll(List.of("add", "edit", "color", "schedule", "on", "off", "remove", "list"));
        if (args.length == 3 && !args[1].equalsIgnoreCase("add")) {
            ConfigurationSection section = plugin.getConfig().getConfigurationSection("bossbars");
            if (section != null) candidates.addAll(section.getKeys(false));
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("color"))
            for (BarColor color : BarColor.values()) candidates.add(color.name().toLowerCase(Locale.ROOT));
        if (args.length == 4 && args[1].equalsIgnoreCase("schedule")) candidates.addAll(List.of("off", "5m", "1h"));
        if (args.length == 5 && args[1].equalsIgnoreCase("schedule")) candidates.addAll(List.of("30s", "1m"));
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return candidates.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private void help(CommandSender sender) {
        sender.sendMessage("/neotab bb add <id> [title] | edit <id> <title> | color <id> <color>");
        sender.sendMessage("/neotab bb schedule <id> <interval> <duration>|off | on|off|remove <id> | list");
    }
    private void reply(CommandSender sender, String de, String en) {
        sender.sendMessage(config.getLanguage() == ConfigManager.Language.GERMAN ? de : en);
    }
}
