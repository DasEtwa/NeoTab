package de.NeoTab.neotab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

public final class NeoTabMetrics {
    private static final int BSTATS_PLUGIN_ID = 32846;

    private static final String DEFAULT_HEADER = "<gradient:#AA00AA:#BA55D3>Welcome from NeoTab</gradient>";
    private static final String DEFAULT_FOOTER = "<gray>RAM: <light_purple>{used}MB / {total}MB ({percent}%)</light_purple> | "
        + "<light_purple>Ping: {playerPing}ms</light_purple> | Avg: {avgPing}ms</gray>";
    private static final String DEFAULT_ANIMATION_STYLE = "rainbow";

    private final NeoTab plugin;
    private volatile MetricsSnapshot snapshot;
    private Metrics metrics;

    public NeoTabMetrics(NeoTab plugin) {
        this.plugin = plugin;
        snapshot = MetricsSnapshot.defaults();
    }

    public synchronized void start() {
        if (metrics != null) {
            return;
        }

        refreshSnapshot();
        startConfiguredMetrics();
    }

    private void startConfiguredMetrics() {
        if (!shouldStartMetrics(plugin.getConfig(), BSTATS_PLUGIN_ID)) {
            if (!isMetricsEnabled(plugin.getConfig())) {
                debug("log.metrics.disabled", java.util.Map.of(), null);
            } else {
                debug("log.metrics.invalid-id", java.util.Map.of(), null);
            }
            return;
        }

        Metrics created = null;
        try {
            created = new Metrics(plugin, BSTATS_PLUGIN_ID);
            registerCharts(created);
            metrics = created;
        } catch (Throwable error) {
            if (created != null) {
                try {
                    created.shutdown();
                } catch (Throwable ignored) {
                    // The original initialization error is more useful in debug mode.
                }
            }
            debug("log.metrics.initialization-failed", java.util.Map.of(), error);
        }
    }

    public synchronized void refresh() {
        refreshSnapshot();
        switch (reloadAction(isMetricsEnabled(plugin.getConfig()), metrics != null)) {
            case START -> startConfiguredMetrics();
            case STOP -> {
                shutdown();
                debug("log.metrics.stopped-after-reload", java.util.Map.of(), null);
            }
            case NONE -> {
                // The running state already matches the reloaded configuration.
            }
        }
    }

    public synchronized void shutdown() {
        Metrics active = metrics;
        metrics = null;
        if (active == null) {
            return;
        }
        try {
            active.shutdown();
        } catch (Throwable error) {
            debug("log.metrics.shutdown-failed", java.util.Map.of(), error);
        }
    }

    private void registerCharts(Metrics target) {
        target.addCustomChart(new SimplePie("configured_language", () -> chartValue(MetricsSnapshot::configuredLanguage, "other")));
        target.addCustomChart(new SimplePie("scoreboard_enabled", () -> chartValue(MetricsSnapshot::scoreboardEnabled, "disabled")));
        target.addCustomChart(new SimplePie("header_enabled", () -> chartValue(MetricsSnapshot::headerEnabled, "disabled")));
        target.addCustomChart(new SimplePie("footer_enabled", () -> chartValue(MetricsSnapshot::footerEnabled, "disabled")));
        target.addCustomChart(new SimplePie("ram_display_enabled", () -> chartValue(MetricsSnapshot::ramDisplayEnabled, "disabled")));
        target.addCustomChart(new SimplePie("ping_display_enabled", () -> chartValue(MetricsSnapshot::pingDisplayEnabled, "disabled")));
        target.addCustomChart(new SimplePie("average_display_enabled", () -> chartValue(MetricsSnapshot::averageDisplayEnabled, "disabled")));
        target.addCustomChart(new SimplePie("afk_feature_enabled", () -> chartValue(MetricsSnapshot::afkFeatureEnabled, "disabled")));
        target.addCustomChart(new SimplePie("update_checker_enabled", () -> chartValue(MetricsSnapshot::updateCheckerEnabled, "disabled")));
        target.addCustomChart(new SimplePie("luckperms_status", () -> chartValue(MetricsSnapshot::luckPermsStatus, "not_installed")));
        target.addCustomChart(new SimplePie("placeholderapi_status", () -> chartValue(MetricsSnapshot::placeholderApiStatus, "not_installed")));
        target.addCustomChart(new SimplePie("geyser_status", () -> chartValue(MetricsSnapshot::geyserStatus, "not_installed")));
        target.addCustomChart(new SimplePie("server_platform", () -> chartValue(MetricsSnapshot::serverPlatform, "other")));
        target.addCustomChart(new SimplePie("animation_count", () -> chartValue(MetricsSnapshot::animationCount, "none")));
    }

    private String chartValue(Function<MetricsSnapshot, String> accessor, String fallback) {
        try {
            MetricsSnapshot current = snapshot;
            String value = current == null ? null : accessor.apply(current);
            return value == null || value.isBlank() ? fallback : value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private void refreshSnapshot() {
        try {
            Set<String> installedPlugins = new HashSet<>();
            for (Plugin installed : plugin.getServer().getPluginManager().getPlugins()) {
                if (installed != null && installed.getName() != null) {
                    installedPlugins.add(installed.getName());
                }
            }
            snapshot = createSnapshot(
                plugin.getConfig(),
                plugin.getServer().getName(),
                plugin.getServer().getVersion(),
                installedPlugins
            );
        } catch (Throwable error) {
            snapshot = MetricsSnapshot.defaults();
            debug("log.metrics.refresh-failed", java.util.Map.of(), error);
        }
    }

    private void debug(String key, java.util.Map<String, String> placeholders, Throwable error) {
        try {
            if (!isDebugEnabled(plugin.getConfig())) {
                return;
            }
            plugin.getConfigManager().log(Level.FINE, key, placeholders, error);
        } catch (Throwable ignored) {
            // Metrics diagnostics must never interfere with the plugin lifecycle.
        }
    }

    static boolean shouldStartMetrics(ConfigurationSection config, int pluginId) {
        return isMetricsEnabled(config) && isValidPluginId(pluginId);
    }

    static MetricsReloadAction reloadAction(boolean configuredEnabled, boolean running) {
        if (configuredEnabled) {
            return running ? MetricsReloadAction.NONE : MetricsReloadAction.START;
        }
        return running ? MetricsReloadAction.STOP : MetricsReloadAction.NONE;
    }

    static boolean isValidPluginId(int pluginId) {
        return pluginId > 0;
    }

    enum MetricsReloadAction {
        NONE,
        START,
        STOP
    }

    static boolean isMetricsEnabled(ConfigurationSection config) {
        return readBoolean(config, "metrics.enabled", true);
    }

    static boolean isDebugEnabled(ConfigurationSection config) {
        return readBoolean(config, "debug", readBoolean(config, "debug.enabled", false));
    }

    static String normalizeLanguage(Object rawLanguage) {
        if (!(rawLanguage instanceof String language) || language.isBlank()) {
            return "other";
        }
        ConfigManager.Language parsed = ConfigManager.Language.parse(language);
        return parsed == null ? "other" : parsed.id();
    }

    static String normalizePlatform(String serverName, String serverVersion) {
        String combined = ((serverName == null ? "" : serverName) + " "
            + (serverVersion == null ? "" : serverVersion)).toLowerCase(Locale.ROOT);
        if (combined.contains("paper")) {
            return "paper";
        }
        if (combined.contains("spigot")) {
            return "spigot";
        }
        if (combined.contains("craftbukkit") || combined.contains("bukkit")) {
            return "craftbukkit";
        }
        return "other";
    }

    static String bucketAnimationCount(int count) {
        if (count <= 0) {
            return "none";
        }
        if (count <= 5) {
            return "1-5";
        }
        if (count <= 10) {
            return "6-10";
        }
        return "11_plus";
    }

    static MetricsSnapshot createSnapshot(
        ConfigurationSection config,
        String serverName,
        String serverVersion,
        Set<String> installedPlugins
    ) {
        String globalHeader = readString(config, "server-name", DEFAULT_HEADER);
        String globalFooter = readString(config, "ram-format", DEFAULT_FOOTER);
        String globalAnimationStyle = readString(config, "animation-style", DEFAULT_ANIMATION_STYLE);

        List<String> headers = new ArrayList<>();
        List<String> footers = new ArrayList<>();
        headers.add(globalHeader);
        footers.add(globalFooter);

        int animations = isAnimated(globalHeader, globalAnimationStyle) ? 1 : 0;
        ConfigurationSection profiles = readSection(config, "tab-profiles");
        for (String profileName : readKeys(profiles)) {
            String profileHeader = readString(profiles, profileName + ".server-name", globalHeader);
            String profileFooter = readString(profiles, profileName + ".ram-format", globalFooter);
            String profileStyle = readString(profiles, profileName + ".animation-style", globalAnimationStyle);
            headers.add(profileHeader);
            footers.add(profileFooter);
            if (isAnimated(profileHeader, profileStyle)) {
                animations++;
            }
        }

        boolean scoreboardEnabled = readBoolean(config, "scoreboard.enabled", false);
        if (scoreboardEnabled
            && readBoolean(config, "scoreboard.title-animation.enabled", true)
            && isAnimated(
                readString(config, "scoreboard.title", "NeoTab"),
                readString(config, "scoreboard.title-animation.style", "static")
            )) {
            animations++;
        }

        String language = normalizeLanguage(readString(config, "language", "en"));
        Set<String> plugins = normalizePluginNames(installedPlugins);
        return new MetricsSnapshot(
            language,
            enabledDisabled(scoreboardEnabled),
            enabledDisabled(anyNonBlank(headers)),
            enabledDisabled(anyNonBlank(footers)),
            enabledDisabled(containsAnyPlaceholder(footers, "{used}", "{total}", "{percent}", "{ram_used}", "{ram_max}", "{ram_percent}")),
            enabledDisabled(containsAnyPlaceholder(footers, "{playerping}", "{player_ping}", "{ping}")),
            enabledDisabled(containsAnyPlaceholder(footers, "{avgping}", "{avg_ping}")),
            "disabled",
            enabledDisabled(readBoolean(config, "update-checker.enabled", true)),
            installedStatus(containsPlugin(plugins, "luckperms")),
            installedStatus(containsPlugin(plugins, "placeholderapi")),
            installedStatus(containsGeyser(plugins)),
            normalizePlatform(serverName, serverVersion),
            bucketAnimationCount(animations)
        );
    }

    private static boolean isAnimated(String text, String style) {
        return text != null && !text.isBlank()
            && style != null && !style.isBlank()
            && !style.trim().equalsIgnoreCase("static");
    }

    private static boolean anyNonBlank(List<String> values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAnyPlaceholder(List<String> values, String... placeholders) {
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String normalized = value.toLowerCase(Locale.ROOT);
            for (String placeholder : placeholders) {
                if (normalized.contains(placeholder)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String enabledDisabled(boolean enabled) {
        return enabled ? "enabled" : "disabled";
    }

    private static String installedStatus(boolean installed) {
        return installed ? "installed" : "not_installed";
    }

    private static Set<String> normalizePluginNames(Set<String> installedPlugins) {
        if (installedPlugins == null || installedPlugins.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> normalized = new HashSet<>();
        for (String pluginName : installedPlugins) {
            if (pluginName != null && !pluginName.isBlank()) {
                normalized.add(pluginName.trim().toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }

    private static boolean containsPlugin(Set<String> installedPlugins, String name) {
        return installedPlugins.contains(name);
    }

    private static boolean containsGeyser(Set<String> installedPlugins) {
        for (String pluginName : installedPlugins) {
            if (pluginName.equals("geyser") || pluginName.startsWith("geyser-")) {
                return true;
            }
        }
        return false;
    }

    private static Object readValue(ConfigurationSection config, String path) {
        if (config == null || path == null || path.isBlank()) {
            return null;
        }
        try {
            return config.get(path);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String readString(ConfigurationSection config, String path, String fallback) {
        Object value = readValue(config, path);
        return value instanceof String text ? text : fallback;
    }

    private static boolean readBoolean(ConfigurationSection config, String path, boolean fallback) {
        Object value = readValue(config, path);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String text) {
            return switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "true", "enabled", "yes", "on", "1" -> true;
                case "false", "disabled", "no", "off", "0" -> false;
                default -> fallback;
            };
        }
        return fallback;
    }

    private static ConfigurationSection readSection(ConfigurationSection config, String path) {
        if (config == null) {
            return null;
        }
        try {
            return config.getConfigurationSection(path);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Set<String> readKeys(ConfigurationSection section) {
        if (section == null) {
            return Collections.emptySet();
        }
        try {
            return section.getKeys(false);
        } catch (RuntimeException ignored) {
            return Collections.emptySet();
        }
    }

    record MetricsSnapshot(
        String configuredLanguage,
        String scoreboardEnabled,
        String headerEnabled,
        String footerEnabled,
        String ramDisplayEnabled,
        String pingDisplayEnabled,
        String averageDisplayEnabled,
        String afkFeatureEnabled,
        String updateCheckerEnabled,
        String luckPermsStatus,
        String placeholderApiStatus,
        String geyserStatus,
        String serverPlatform,
        String animationCount
    ) {
        static MetricsSnapshot defaults() {
            return createSnapshot(null, null, null, Collections.emptySet());
        }
    }
}
