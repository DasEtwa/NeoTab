package de.NeoTab.neotab;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class TabCommand implements CommandExecutor, TabCompleter {
    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final TabUpdater tabUpdater;
    private final UpdateChecker updateChecker;
    private final ChatInputManager chatInputManager;
    private final ScoreboardService scoreboardService;
    private final ActionBarTimerService actionBarTimerService;
    private final NeoTabGui neoTabGui;
    private static final Set<String> RESERVED_PERFORMANCE_NAMES = Set.of("custom", "save");

    public TabCommand(
        NeoTab plugin,
        ConfigManager configManager,
        TabUpdater tabUpdater,
        UpdateChecker updateChecker,
        ChatInputManager chatInputManager,
        ScoreboardService scoreboardService,
        ActionBarTimerService actionBarTimerService,
        NeoTabGui neoTabGui
    ) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.tabUpdater = tabUpdater;
        this.updateChecker = updateChecker;
        this.chatInputManager = chatInputManager;
        this.scoreboardService = scoreboardService;
        this.actionBarTimerService = actionBarTimerService;
        this.neoTabGui = neoTabGui;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(configManager.message("usage"));
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "reload" -> {
                if (!sender.hasPermission("neotab.reload")) {
                    sender.sendMessage(configManager.message("no-permission"));
                    return true;
                }

                configManager.reload();
                tabUpdater.restart();
                tabUpdater.updateAllNow();
                updateChecker.start();
                chatInputManager.cancelAll(true);
                scoreboardService.restart();
                plugin.restartActionBarExtras();
                sender.sendMessage(configManager.message("reload-success"));
                return true;
            }
            case "setname" -> {
                if (!sender.hasPermission("neotab.setname")) {
                    sender.sendMessage(configManager.message("no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(configManager.message("setname-usage"));
                    return true;
                }

                String name = joinArgs(args, 1);
                configManager.setServerName(name);
                tabUpdater.updateAllNow();
                sender.sendMessage(configManager.message("setname-success"));
                return true;
            }
            case "style" -> {
                if (!sender.hasPermission("neotab.style")) {
                    sender.sendMessage(configManager.message("no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(configManager.message("style-usage"));
                    return true;
                }

                AnimationUtils.Style style = AnimationUtils.Style.fromString(args[1]);
                if (style == null) {
                    sender.sendMessage(configManager.message("style-invalid"));
                    return true;
                }

                configManager.setAnimationStyle(style);
                tabUpdater.restart();
                tabUpdater.updateAllNow();
                sender.sendMessage(configManager.message("style-success", Map.of("style", style.id())));
                return true;
            }
            case "performance" -> {
                return handlePerformance(sender, args);
            }
            case "color", "colour" -> {
                return handleColor(sender, args);
            }
            case "gui" -> {
                return handleGui(sender);
            }
            case "sb", "scoreboard" -> {
                return handleScoreboard(sender, args);
            }
            case "timer" -> {
                return handleTimer(sender, args);
            }
            case "stopwatch" -> {
                return handleStopwatch(sender, args);
            }
            case "clock" -> {
                return handleClock(sender, args);
            }
            case "welcome" -> {
                return handleActionBarToggle(sender, args, "welcome", "neotab.extras.welcome", "Welcome");
            }
            case "randommessages", "randommessage" -> {
                return handleActionBarToggle(sender, args, "random-messages", "neotab.extras.randommessages", "Random Messages");
            }
            case "biomepopup" -> {
                return handleActionBarToggle(sender, args, "biome-popup", "neotab.extras.biome", "Biome Popup");
            }
            case "nearestplayer" -> {
                return handleActionBarToggle(sender, args, "nearest-player", "neotab.extras.nearestplayer", "Nearest Player");
            }
            case "achievements" -> {
                return handleActionBarToggle(sender, args, "achievements", "neotab.extras.achievements", "Achievements");
            }
            default -> {
                sender.sendMessage(configManager.message("command-unknown"));
                return true;
            }
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        ArrayList<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            addIfAllowed(completions, "reload", "neotab.reload", prefix, sender);
            addIfAllowed(completions, "setname", "neotab.setname", prefix, sender);
            addIfAllowed(completions, "style", "neotab.style", prefix, sender);
            addIfAllowed(completions, "performance", "neotab.performance", prefix, sender);
            addIfAllowed(completions, "color", "neotab.color", prefix, sender);
            addIfAllowed(completions, "gui", "neotab.gui", prefix, sender);
            addIfAllowed(completions, "sb", "neotab.scoreboard", prefix, sender);
            addIfAllowed(completions, "timer", "neotab.timer", prefix, sender);
            addIfAllowed(completions, "stopwatch", "neotab.extras.stopwatch", prefix, sender);
            addIfAllowed(completions, "clock", "neotab.extras.clock", prefix, sender);
            addIfAllowed(completions, "welcome", "neotab.extras.welcome", prefix, sender);
            addIfAllowed(completions, "randommessages", "neotab.extras.randommessages", prefix, sender);
            addIfAllowed(completions, "biomepopup", "neotab.extras.biome", prefix, sender);
            addIfAllowed(completions, "nearestplayer", "neotab.extras.nearestplayer", prefix, sender);
            addIfAllowed(completions, "achievements", "neotab.extras.achievements", prefix, sender);
            return completions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("style") && sender.hasPermission("neotab.style")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (AnimationUtils.Style style : AnimationUtils.Style.values()) {
                String id = style.id();
                if (id.startsWith(prefix)) {
                    completions.add(id);
                }
            }
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("color") && sender.hasPermission("neotab.color")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (String preset : HeaderColorPalette.PRESETS.keySet()) {
                if (preset.startsWith(prefix)) {
                    completions.add(preset);
                }
            }
            if ("#AA00AA,#BA55D3".startsWith(prefix)) {
                completions.add("#AA00AA,#BA55D3");
            }
        }

        if (args[0].equalsIgnoreCase("performance") && sender.hasPermission("neotab.performance")) {
            if (args.length == 2) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                addPerformanceCompletion(completions, "custom", prefix);
                addPerformanceCompletion(completions, "save", prefix);
                for (String preset : configManager.getPerformancePresets().keySet()) {
                    addPerformanceCompletion(completions, preset, prefix);
                }
                for (String preset : configManager.getSavedPerformancePresets().keySet()) {
                    addPerformanceCompletion(completions, preset, prefix);
                }
            } else if (args.length == 3 && args[1].equalsIgnoreCase("custom")) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                addPerformanceCompletion(completions, "3", prefix);
                addPerformanceCompletion(completions, "10", prefix);
                addPerformanceCompletion(completions, "20", prefix);
            }
        }

        if ((args[0].equalsIgnoreCase("sb") || args[0].equalsIgnoreCase("scoreboard")) && sender.hasPermission("neotab.scoreboard")) {
            completeScoreboard(completions, args);
        }

        if (args[0].equalsIgnoreCase("timer") && sender.hasPermission("neotab.timer")) {
            completeTimer(completions, args);
        }

        if (args[0].equalsIgnoreCase("stopwatch") && sender.hasPermission("neotab.extras.stopwatch")) {
            completeSimple(completions, args, List.of("start", "stop", "pause", "resume", "reset"));
        }

        if (args[0].equalsIgnoreCase("clock") && sender.hasPermission("neotab.extras.clock")) {
            completeSimple(completions, args, List.of("on", "off", "timezone", "format"));
        }

        if (List.of("welcome", "randommessages", "biomepopup", "nearestplayer", "achievements").contains(args[0].toLowerCase(Locale.ROOT))) {
            completeSimple(completions, args, List.of("on", "off"));
        }

        return completions;
    }

    private boolean handleColor(CommandSender sender, String[] args) {
        if (!sender.hasPermission("neotab.color")) {
            sender.sendMessage(configManager.message("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(configManager.message("color-usage"));
            return true;
        }

        String input = joinArgs(args, 1).trim();
        String presetName = input.toLowerCase(Locale.ROOT);
        List<String> colors = HeaderColorPalette.PRESETS.get(presetName);
        String displayName = presetName;

        if (colors == null) {
            colors = HeaderColorPalette.parseCustomColors(input);
            displayName = "custom";
        }

        if (colors == null) {
            sender.sendMessage(configManager.message("color-invalid"));
            return true;
        }

        configManager.setCustomColors(colors);
        tabUpdater.restart();
        tabUpdater.updateAllNow();
        scoreboardService.updateAll();
        sender.sendMessage(configManager.message(
            "color-success",
            Map.of("preset", displayName, "colors", String.join(", ", colors))
        ));
        return true;
    }

    private boolean handleGui(CommandSender sender) {
        if (!sender.hasPermission("neotab.gui")) {
            sender.sendMessage(configManager.message("no-permission"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.message("player-only"));
            return true;
        }

        neoTabGui.openMain(player);
        return true;
    }

    private boolean handleScoreboard(CommandSender sender, String[] args) {
        if (!sender.hasPermission("neotab.scoreboard")) {
            sender.sendMessage(configManager.message("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(configManager.message("scoreboard-usage"));
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "on" -> {
                if (!sender.hasPermission("neotab.scoreboard.toggle")) {
                    sender.sendMessage(configManager.message("no-permission"));
                    return true;
                }
                setScoreboardEnabled(sender, true);
                return true;
            }
            case "off" -> {
                if (!sender.hasPermission("neotab.scoreboard.toggle")) {
                    sender.sendMessage(configManager.message("no-permission"));
                    return true;
                }
                setScoreboardEnabled(sender, false);
                return true;
            }
            case "toggle" -> {
                if (!sender.hasPermission("neotab.scoreboard.toggle")) {
                    sender.sendMessage(configManager.message("no-permission"));
                    return true;
                }
                if (sender instanceof Player player) {
                    boolean enabled = scoreboardService.toggle(player);
                    sender.sendMessage(configManager.message(enabled ? "scoreboard-enabled" : "scoreboard-disabled"));
                } else {
                    boolean enabled = !configManager.getScoreboardConfig().enabled();
                    scoreboardService.setGlobalEnabled(enabled);
                    sender.sendMessage(configManager.message(enabled ? "scoreboard-enabled" : "scoreboard-disabled"));
                }
                return true;
            }
            case "title" -> {
                if (!sender.hasPermission("neotab.scoreboard.edit")) {
                    sender.sendMessage(configManager.message("no-permission"));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(configManager.message("scoreboard-title-usage"));
                    return true;
                }
                scoreboardService.setTitle(joinArgs(args, 2));
                sender.sendMessage(configManager.message("scoreboard-title-changed"));
                return true;
            }
            case "style" -> {
                if (!sender.hasPermission("neotab.scoreboard.edit")) {
                    sender.sendMessage(configManager.message("no-permission"));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(configManager.message("scoreboard-style-usage"));
                    return true;
                }
                String input = args[2].toLowerCase(Locale.ROOT);
                if (input.equals("off")) {
                    scoreboardService.setTitleAnimationEnabled(false);
                    sender.sendMessage(configManager.message("scoreboard-style-changed", Map.of("style", "off")));
                    return true;
                }
                AnimationUtils.Style style = AnimationUtils.Style.fromString(input);
                if (style == null) {
                    sender.sendMessage(configManager.message("style-invalid"));
                    return true;
                }
                scoreboardService.setTitleStyle(style);
                sender.sendMessage(configManager.message("scoreboard-style-changed", Map.of("style", style.id())));
                return true;
            }
            case "interval" -> {
                if (!sender.hasPermission("neotab.scoreboard.edit")) {
                    sender.sendMessage(configManager.message("no-permission"));
                    return true;
                }
                return handleScoreboardInterval(sender, args);
            }
            case "line" -> {
                if (!sender.hasPermission("neotab.scoreboard.edit")) {
                    sender.sendMessage(configManager.message("no-permission"));
                    return true;
                }
                if (args.length < 4) {
                    sender.sendMessage(configManager.message("scoreboard-line-usage"));
                    return true;
                }
                Integer lineNumber = parseLineNumber(args[2]);
                if (lineNumber == null) {
                    sender.sendMessage(configManager.message("scoreboard-invalid-line"));
                    return true;
                }
                scoreboardService.setLine(lineNumber, joinArgs(args, 3));
                sender.sendMessage(configManager.message("scoreboard-line-changed", Map.of("line", Integer.toString(lineNumber))));
                return true;
            }
            case "clear" -> {
                if (!sender.hasPermission("neotab.scoreboard.edit")) {
                    sender.sendMessage(configManager.message("no-permission"));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(configManager.message("scoreboard-clear-usage"));
                    return true;
                }
                Integer lineNumber = parseLineNumber(args[2]);
                if (lineNumber == null) {
                    sender.sendMessage(configManager.message("scoreboard-invalid-line"));
                    return true;
                }
                scoreboardService.clearLine(lineNumber);
                sender.sendMessage(configManager.message("scoreboard-line-cleared", Map.of("line", Integer.toString(lineNumber))));
                return true;
            }
            case "clearall" -> {
                if (!sender.hasPermission("neotab.scoreboard.edit")) {
                    sender.sendMessage(configManager.message("no-permission"));
                    return true;
                }
                scoreboardService.clearAllLines();
                sender.sendMessage(configManager.message("scoreboard-cleared"));
                return true;
            }
            case "preset", "load" -> {
                if (!sender.hasPermission("neotab.scoreboard.presets")) {
                    sender.sendMessage(configManager.message("no-permission"));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(configManager.message("scoreboard-preset-usage"));
                    return true;
                }
                String presetName = configManager.normalizePerformancePresetName(args[2]);
                if (!scoreboardService.loadPreset(presetName)) {
                    sender.sendMessage(configManager.message("scoreboard-preset-missing"));
                    return true;
                }
                sender.sendMessage(configManager.message("scoreboard-preset-loaded", Map.of("name", presetName)));
                return true;
            }
            case "save" -> {
                if (!sender.hasPermission("neotab.scoreboard.presets")) {
                    sender.sendMessage(configManager.message("no-permission"));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(configManager.message("scoreboard-save-usage"));
                    return true;
                }
                String presetName = configManager.normalizePerformancePresetName(args[2]);
                if (!configManager.isValidPerformancePresetName(presetName)) {
                    sender.sendMessage(configManager.message("performance-invalid-name"));
                    return true;
                }
                scoreboardService.savePreset(presetName);
                sender.sendMessage(configManager.message("scoreboard-preset-saved", Map.of("name", presetName)));
                return true;
            }
            case "delete", "remove" -> {
                if (!sender.hasPermission("neotab.scoreboard.presets")) {
                    sender.sendMessage(configManager.message("no-permission"));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(configManager.message("scoreboard-delete-usage"));
                    return true;
                }
                String presetName = configManager.normalizePerformancePresetName(args[2]);
                if (!scoreboardService.deletePreset(presetName)) {
                    sender.sendMessage(configManager.message("scoreboard-preset-missing"));
                    return true;
                }
                sender.sendMessage(configManager.message("scoreboard-preset-deleted", Map.of("name", presetName)));
                return true;
            }
            case "list" -> {
                if (!sender.hasPermission("neotab.scoreboard.presets")) {
                    sender.sendMessage(configManager.message("no-permission"));
                    return true;
                }
                List<String> presets = scoreboardService.listPresets();
                String names = presets.isEmpty() ? "none" : String.join(", ", presets);
                sender.sendMessage(configManager.message("scoreboard-preset-list", Map.of("names", names)));
                return true;
            }
            default -> {
                sender.sendMessage(configManager.message("scoreboard-usage"));
                return true;
            }
        }
    }

    private boolean handleTimer(CommandSender sender, String[] args) {
        if (!sender.hasPermission("neotab.timer")) {
            sender.sendMessage(configManager.message("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(configManager.message("timer-usage"));
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("text")) {
            if (args.length < 3) {
                sender.sendMessage(configManager.message("timer-text-usage"));
                return true;
            }
            configManager.setActionBarTimerRunningFormat(joinArgs(args, 2));
            sender.sendMessage(configManager.message("timer-text-changed"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.message("player-only"));
            return true;
        }

        switch (action) {
            case "start" -> {
                if (args.length < 3) {
                    sender.sendMessage(configManager.message("timer-start-usage"));
                    return true;
                }
                int durationSeconds = ActionBarTimerService.parseDurationSeconds(args[2]);
                if (durationSeconds < 0) {
                    sender.sendMessage(configManager.message("timer-invalid-duration"));
                    return true;
                }
                boolean started = actionBarTimerService.start(player, durationSeconds);
                sender.sendMessage(configManager.message(started ? "timer-started" : "timer-disabled"));
                return true;
            }
            case "stop" -> {
                boolean stopped = actionBarTimerService.stop(player);
                sender.sendMessage(configManager.message(stopped ? "timer-stopped" : "timer-not-running"));
                return true;
            }
            case "pause" -> {
                boolean paused = actionBarTimerService.pause(player);
                sender.sendMessage(configManager.message(paused ? "timer-paused" : "timer-not-running"));
                return true;
            }
            case "resume" -> {
                boolean resumed = actionBarTimerService.resume(player);
                sender.sendMessage(configManager.message(resumed ? "timer-resumed" : "timer-not-running"));
                return true;
            }
            default -> {
                sender.sendMessage(configManager.message("timer-usage"));
                return true;
            }
        }
    }

    private boolean handleStopwatch(CommandSender sender, String[] args) {
        if (!sender.hasPermission("neotab.extras.stopwatch")) {
            sender.sendMessage(configManager.message("no-permission"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.message("player-only"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(configManager.message("stopwatch-usage"));
            return true;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                boolean started = plugin.getStopwatchService().start(player);
                sender.sendMessage(configManager.message(started ? "stopwatch-started" : "stopwatch-conflict"));
                return true;
            }
            case "stop" -> {
                boolean stopped = plugin.getStopwatchService().stop(player);
                sender.sendMessage(configManager.message(stopped ? "stopwatch-stopped" : "stopwatch-not-running"));
                return true;
            }
            case "pause" -> {
                boolean paused = plugin.getStopwatchService().pause(player);
                sender.sendMessage(configManager.message(paused ? "stopwatch-paused" : "stopwatch-not-running"));
                return true;
            }
            case "resume" -> {
                boolean resumed = plugin.getStopwatchService().resume(player);
                sender.sendMessage(configManager.message(resumed ? "stopwatch-resumed" : "stopwatch-not-running"));
                return true;
            }
            case "reset" -> {
                boolean reset = plugin.getStopwatchService().reset(player);
                sender.sendMessage(configManager.message(reset ? "stopwatch-reset" : "stopwatch-not-running"));
                return true;
            }
            default -> {
                sender.sendMessage(configManager.message("stopwatch-usage"));
                return true;
            }
        }
    }

    private boolean handleClock(CommandSender sender, String[] args) {
        if (!sender.hasPermission("neotab.extras.clock")) {
            sender.sendMessage(configManager.message("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(configManager.message("clock-usage"));
            return true;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on" -> {
                setActionBarModule(sender, "clock", true, "Clock");
                return true;
            }
            case "off" -> {
                setActionBarModule(sender, "clock", false, "Clock");
                return true;
            }
            case "timezone" -> {
                if (args.length < 3) {
                    sender.sendMessage(configManager.message("clock-usage"));
                    return true;
                }
                String timezone = args[2];
                try {
                    ZoneId.of(timezone);
                } catch (DateTimeException ex) {
                    sender.sendMessage(configManager.message("clock-invalid-timezone"));
                    return true;
                }
                configManager.setClockTimezone(timezone);
                plugin.restartActionBarExtras();
                sender.sendMessage(configManager.message("clock-timezone-changed", Map.of("timezone", timezone)));
                return true;
            }
            case "format" -> {
                if (args.length < 3) {
                    sender.sendMessage(configManager.message("clock-usage"));
                    return true;
                }
                String format = joinArgs(args, 2);
                try {
                    DateTimeFormatter.ofPattern(format);
                } catch (IllegalArgumentException ex) {
                    sender.sendMessage(configManager.message("clock-invalid-format"));
                    return true;
                }
                configManager.setClockFormat(format);
                plugin.restartActionBarExtras();
                sender.sendMessage(configManager.message("clock-format-changed", Map.of("format", format)));
                return true;
            }
            default -> {
                sender.sendMessage(configManager.message("clock-usage"));
                return true;
            }
        }
    }

    private boolean handleActionBarToggle(CommandSender sender, String[] args, String moduleName, String permission, String displayName) {
        if (!sender.hasPermission(permission)) {
            sender.sendMessage(configManager.message("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(configManager.message("actionbar-toggle-usage"));
            return true;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on" -> {
                setActionBarModule(sender, moduleName, true, displayName);
                return true;
            }
            case "off" -> {
                setActionBarModule(sender, moduleName, false, displayName);
                return true;
            }
            default -> {
                sender.sendMessage(configManager.message("actionbar-toggle-usage"));
                return true;
            }
        }
    }

    private void setActionBarModule(CommandSender sender, String moduleName, boolean enabled, String displayName) {
        configManager.setActionBarModuleEnabled(moduleName, enabled);
        plugin.restartActionBarExtras();
        sender.sendMessage(configManager.message(
            enabled ? "actionbar-module-enabled" : "actionbar-module-disabled",
            Map.of("module", displayName)
        ));
    }

    private boolean handleScoreboardInterval(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(configManager.message("scoreboard-interval-usage"));
            return true;
        }

        String presetName = configManager.normalizePerformancePresetName(args[2]);
        Integer ticks;
        if (presetName.equals("custom")) {
            if (args.length < 4) {
                sender.sendMessage(configManager.message("scoreboard-interval-usage"));
                return true;
            }
            ticks = parseTicks(args[3]);
        } else {
            ticks = configManager.getPerformancePresetTicks(presetName);
        }

        if (ticks == null && presetName.equals("custom")) {
            sender.sendMessage(configManager.message(
                "performance-invalid-ticks",
                Map.of(
                    "min", String.valueOf(ConfigManager.MIN_PERFORMANCE_INTERVAL_TICKS),
                    "max", String.valueOf(ConfigManager.MAX_PERFORMANCE_INTERVAL_TICKS)
                )
            ));
            return true;
        }
        if (ticks == null) {
            sender.sendMessage(configManager.message("performance-invalid-preset"));
            return true;
        }

        String displayName = presetName.equals("custom") ? "custom" : presetName;
        scoreboardService.setUpdateIntervalTicks(ticks);
        sender.sendMessage(configManager.message(
            "scoreboard-interval-success",
            Map.of("preset", displayName, "ticks", Integer.toString(configManager.getScoreboardConfig().updateIntervalTicks()))
        ));
        return true;
    }

    private void setScoreboardEnabled(CommandSender sender, boolean enabled) {
        if (sender instanceof Player player) {
            scoreboardService.setEnabled(player, enabled);
        } else {
            scoreboardService.setGlobalEnabled(enabled);
        }
        sender.sendMessage(configManager.message(enabled ? "scoreboard-enabled" : "scoreboard-disabled"));
    }

    private boolean handlePerformance(CommandSender sender, String[] args) {
        if (!sender.hasPermission("neotab.performance")) {
            sender.sendMessage(configManager.message("no-permission"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(configManager.message(
                "performance-usage",
                Map.of(
                    "preset", configManager.getActivePerformancePreset(),
                    "ticks", String.valueOf(configManager.getUpdateIntervalTicks())
                )
            ));
            return true;
        }

        String action = configManager.normalizePerformancePresetName(args[1]);
        switch (action) {
            case "custom" -> {
                if (args.length < 3) {
                    sender.sendMessage(configManager.message("performance-custom-usage"));
                    return true;
                }

                Integer ticks = parseTicks(args[2]);
                if (ticks == null) {
                    sender.sendMessage(configManager.message(
                        "performance-invalid-ticks",
                        Map.of(
                            "min", String.valueOf(ConfigManager.MIN_PERFORMANCE_INTERVAL_TICKS),
                            "max", String.valueOf(ConfigManager.MAX_PERFORMANCE_INTERVAL_TICKS)
                        )
                    ));
                    return true;
                }

                applyPerformance(sender, "custom", ticks);
                return true;
            }
            case "save" -> {
                if (args.length < 3) {
                    sender.sendMessage(configManager.message("performance-save-usage"));
                    return true;
                }

                String presetName = configManager.normalizePerformancePresetName(args[2]);
                if (
                    !configManager.isValidPerformancePresetName(presetName)
                        || RESERVED_PERFORMANCE_NAMES.contains(presetName)
                        || configManager.getPerformancePresets().containsKey(presetName)
                ) {
                    sender.sendMessage(configManager.message("performance-invalid-name"));
                    return true;
                }

                configManager.saveCurrentPerformancePreset(presetName);
                sender.sendMessage(configManager.message(
                    "performance-save-success",
                    Map.of("name", presetName, "ticks", String.valueOf(configManager.getUpdateIntervalTicks()))
                ));
                return true;
            }
            default -> {
                Integer ticks = configManager.getPerformancePresetTicks(action);
                if (ticks == null) {
                    sender.sendMessage(configManager.message("performance-invalid-preset"));
                    return true;
                }

                applyPerformance(sender, action, ticks);
                return true;
            }
        }
    }

    private void applyPerformance(CommandSender sender, String presetName, int ticks) {
        configManager.setPerformancePreset(presetName, ticks);
        tabUpdater.restart();
        tabUpdater.updateAllNow();
        sender.sendMessage(configManager.message(
            "performance-success",
            Map.of("preset", presetName, "ticks", String.valueOf(configManager.getUpdateIntervalTicks()))
        ));
    }

    private Integer parseTicks(String input) {
        try {
            int ticks = Integer.parseInt(input);
            if (ticks < ConfigManager.MIN_PERFORMANCE_INTERVAL_TICKS || ticks > ConfigManager.MAX_PERFORMANCE_INTERVAL_TICKS) {
                return null;
            }
            return ticks;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer parseLineNumber(String input) {
        try {
            int lineNumber = Integer.parseInt(input);
            if (lineNumber < 1 || lineNumber > ConfigManager.MAX_SCOREBOARD_LINES) {
                return null;
            }
            return lineNumber;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void addIfAllowed(List<String> completions, String option, String permission, String prefix, CommandSender sender) {
        if (sender.hasPermission(permission) && option.startsWith(prefix)) {
            completions.add(option);
        }
    }

    private void addPerformanceCompletion(List<String> completions, String option, String prefix) {
        if (option.startsWith(prefix) && !completions.contains(option)) {
            completions.add(option);
        }
    }

    private void completeScoreboard(List<String> completions, String[] args) {
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (String option : List.of("on", "off", "toggle", "title", "style", "interval", "line", "clear", "clearall", "preset", "save", "load", "delete", "list")) {
                if (option.startsWith(prefix)) {
                    completions.add(option);
                }
            }
        } else if (args.length == 3 && (args[1].equalsIgnoreCase("line") || args[1].equalsIgnoreCase("clear"))) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (int i = 1; i <= ConfigManager.MAX_SCOREBOARD_LINES; i++) {
                String value = Integer.toString(i);
                if (value.startsWith(prefix)) {
                    completions.add(value);
                }
            }
        } else if (args.length == 3 && (args[1].equalsIgnoreCase("preset") || args[1].equalsIgnoreCase("load") || args[1].equalsIgnoreCase("delete") || args[1].equalsIgnoreCase("remove"))) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (String preset : scoreboardService.listPresets()) {
                if (preset.startsWith(prefix)) {
                    completions.add(preset);
                }
            }
        } else if (args.length == 3 && args[1].equalsIgnoreCase("style")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            addPerformanceCompletion(completions, "off", prefix);
            for (AnimationUtils.Style style : AnimationUtils.Style.values()) {
                addPerformanceCompletion(completions, style.id(), prefix);
            }
        } else if (args[1].equalsIgnoreCase("interval")) {
            if (args.length == 3) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                addPerformanceCompletion(completions, "custom", prefix);
                for (String preset : configManager.getPerformancePresets().keySet()) {
                    addPerformanceCompletion(completions, preset, prefix);
                }
                for (String preset : configManager.getSavedPerformancePresets().keySet()) {
                    addPerformanceCompletion(completions, preset, prefix);
                }
            } else if (args.length == 4 && args[2].equalsIgnoreCase("custom")) {
                String prefix = args[3].toLowerCase(Locale.ROOT);
                addPerformanceCompletion(completions, "10", prefix);
                addPerformanceCompletion(completions, "20", prefix);
                addPerformanceCompletion(completions, "40", prefix);
            }
        }
    }

    private void completeTimer(List<String> completions, String[] args) {
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (String option : List.of("start", "stop", "pause", "resume", "text")) {
                if (option.startsWith(prefix)) {
                    completions.add(option);
                }
            }
        } else if (args.length == 3 && args[1].equalsIgnoreCase("start")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (String duration : List.of("30s", "5m", "10m", "1h")) {
                if (duration.startsWith(prefix)) {
                    completions.add(duration);
                }
            }
        }
    }

    private void completeSimple(List<String> completions, String[] args, List<String> options) {
        if (args.length != 2) {
            return;
        }
        String prefix = args[1].toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (option.startsWith(prefix)) {
                completions.add(option);
            }
        }
    }

    private String joinArgs(String[] args, int start) {
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) {
                builder.append(' ');
            }
            builder.append(args[i]);
        }
        return builder.toString();
    }
}
