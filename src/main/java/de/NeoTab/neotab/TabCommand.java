package de.NeoTab.neotab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class TabCommand implements CommandExecutor, TabCompleter {
    private final ConfigManager configManager;
    private final TabUpdater tabUpdater;
    private final UpdateChecker updateChecker;
    private static final Set<String> RESERVED_PERFORMANCE_NAMES = Set.of("custom", "save");

    public TabCommand(ConfigManager configManager, TabUpdater tabUpdater, UpdateChecker updateChecker) {
        this.configManager = configManager;
        this.tabUpdater = tabUpdater;
        this.updateChecker = updateChecker;
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

        return completions;
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
