package de.NeoTab.neotab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class TabCommand implements CommandExecutor, TabCompleter {
    private final ConfigManager configManager;
    private final TabUpdater tabUpdater;

    public TabCommand(ConfigManager configManager, TabUpdater tabUpdater) {
        this.configManager = configManager;
        this.tabUpdater = tabUpdater;
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

        return completions;
    }

    private void addIfAllowed(List<String> completions, String option, String permission, String prefix, CommandSender sender) {
        if (sender.hasPermission(permission) && option.startsWith(prefix)) {
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
