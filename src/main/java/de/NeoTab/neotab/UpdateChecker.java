package de.NeoTab.neotab;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public final class UpdateChecker {
    public static final String NOTIFY_PERMISSION = "neotab.update.notify";

    private static final String PROJECT_SLUG = "neotab";
    private static final String LOADER = "paper";
    private static final String DOWNLOAD_URL = "https://modrinth.com/plugin/neotab/versions";
    private static final String API_URL = "https://api.modrinth.com/v2/project/" + PROJECT_SLUG + "/version";
    private static final Pattern STRING_VALUE_PATTERN = Pattern.compile("\"((?:\\\\.|[^\"])*)\"");

    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final HttpClient httpClient;
    private final AtomicInteger generation;

    private volatile CheckResult latestResult;
    private BukkitTask task;

    public UpdateChecker(NeoTab plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        generation = new AtomicInteger();
    }

    public void start() {
        stop();
        latestResult = null;
        int checkGeneration = generation.incrementAndGet();

        ConfigManager.UpdateCheckerConfig config = configManager.getUpdateCheckerConfig();
        if (!config.enabled()) {
            return;
        }

        String currentVersion = plugin.getPluginMeta().getVersion();
        String minecraftVersion = Bukkit.getMinecraftVersion();
        long delayTicks = config.checkDelaySeconds() * 20L;
        task = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> check(config, currentVersion, minecraftVersion, checkGeneration), delayTicks);
    }

    public void stop() {
        generation.incrementAndGet();
        if (task == null) {
            return;
        }

        task.cancel();
        task = null;
    }

    public void notifyPlayer(Player player) {
        CheckResult result = latestResult;
        if (result == null || !result.updateAvailable() || !configManager.getUpdateCheckerConfig().notifyAdmins()) {
            return;
        }
        if (!player.hasPermission(NOTIFY_PERMISSION)) {
            return;
        }

        player.sendMessage(Component.text(result.message(), NamedTextColor.LIGHT_PURPLE));
    }

    private void check(ConfigManager.UpdateCheckerConfig config, String currentVersion, String minecraftVersion, int checkGeneration) {
        try {
            List<ModrinthVersion> versions = fetchVersions(currentVersion);
            Optional<ModrinthVersion> latest = versions.stream()
                .filter(version -> isAllowedVersionType(version, config.includeBeta()))
                .filter(version -> version.loaders().contains(LOADER))
                .filter(version -> version.gameVersions().contains(minecraftVersion))
                .max(this::compareModrinthVersions);

            if (latest.isEmpty()) {
                publish(CheckResult.noUpdate(currentVersion, "No compatible Modrinth version found for " + minecraftVersion + "."), checkGeneration);
                return;
            }

            ModrinthVersion latestVersion = latest.get();
            if (compareVersions(latestVersion.versionNumber(), currentVersion) > 0) {
                publish(CheckResult.updateAvailable(currentVersion, latestVersion.versionNumber()), checkGeneration);
                return;
            }

            publish(CheckResult.noUpdate(currentVersion, "NeoTab is up to date for " + minecraftVersion + "."), checkGeneration);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            publish(CheckResult.failed("NeoTab update check failed: " + cleanMessage(ex)), checkGeneration);
        } catch (RuntimeException ex) {
            publish(CheckResult.failed("NeoTab update check failed: " + cleanMessage(ex)), checkGeneration);
        }
    }

    private List<ModrinthVersion> fetchVersions(String currentVersion) throws IOException, InterruptedException {
        String query = "loaders=" + URLEncoder.encode("[\"" + LOADER + "\"]", StandardCharsets.UTF_8)
            + "&include_changelog=false";
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL + "?" + query))
            .timeout(Duration.ofSeconds(8))
            .header("User-Agent", "DasEtwa/NeoTab/" + currentVersion + " (https://github.com/DasEtwa/NeoTab)")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("Modrinth returned HTTP " + statusCode);
        }

        return parseVersions(response.body());
    }

    private void publish(CheckResult result, int checkGeneration) {
        if (generation.get() != checkGeneration) {
            return;
        }
        if (!plugin.isEnabled()) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (generation.get() != checkGeneration) {
                return;
            }
            if (!plugin.isEnabled()) {
                return;
            }

            if (result.failed()) {
                plugin.getLogger().warning(result.message());
                return;
            }

            if (!result.updateAvailable()) {
                plugin.getLogger().fine(result.message());
                latestResult = null;
                return;
            }

            latestResult = result;
            plugin.getLogger().info(result.message());
            if (!configManager.getUpdateCheckerConfig().notifyAdmins()) {
                return;
            }

            for (Player player : Bukkit.getOnlinePlayers()) {
                notifyPlayer(player);
            }
        });
    }

    private boolean isAllowedVersionType(ModrinthVersion version, boolean includeBeta) {
        String type = version.versionType().toLowerCase(Locale.ROOT);
        return "release".equals(type) || (includeBeta && "beta".equals(type));
    }

    private int compareModrinthVersions(ModrinthVersion left, ModrinthVersion right) {
        int versionCompare = compareVersions(left.versionNumber(), right.versionNumber());
        if (versionCompare != 0) {
            return versionCompare;
        }
        return left.datePublished().compareTo(right.datePublished());
    }

    static int compareVersions(String left, String right) {
        ParsedVersion leftVersion = ParsedVersion.parse(left);
        ParsedVersion rightVersion = ParsedVersion.parse(right);

        int majorCompare = Integer.compare(leftVersion.major(), rightVersion.major());
        if (majorCompare != 0) {
            return majorCompare;
        }

        int minorCompare = Integer.compare(leftVersion.minor(), rightVersion.minor());
        if (minorCompare != 0) {
            return minorCompare;
        }

        int patchCompare = Integer.compare(leftVersion.patch(), rightVersion.patch());
        if (patchCompare != 0) {
            return patchCompare;
        }

        if (leftVersion.preRelease().isBlank() && rightVersion.preRelease().isBlank()) {
            return 0;
        }
        if (leftVersion.preRelease().isBlank()) {
            return 1;
        }
        if (rightVersion.preRelease().isBlank()) {
            return -1;
        }

        int preTypeCompare = Integer.compare(preReleaseRank(leftVersion.preRelease()), preReleaseRank(rightVersion.preRelease()));
        if (preTypeCompare != 0) {
            return preTypeCompare;
        }
        return Integer.compare(leftVersion.preReleaseNumber(), rightVersion.preReleaseNumber());
    }

    private static int preReleaseRank(String preRelease) {
        String normalized = preRelease.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("beta")) {
            return 2;
        }
        if (normalized.startsWith("alpha")) {
            return 1;
        }
        return 0;
    }

    private List<ModrinthVersion> parseVersions(String json) {
        ArrayList<ModrinthVersion> versions = new ArrayList<>();
        for (String object : splitTopLevelObjects(json)) {
            String versionNumber = readStringField(object, "version_number");
            String versionType = readStringField(object, "version_type");
            String status = readStringField(object, "status");
            String datePublished = readStringField(object, "date_published");
            List<String> gameVersions = readStringArrayField(object, "game_versions");
            List<String> loaders = readStringArrayField(object, "loaders");

            if (versionNumber.isBlank() || versionType.isBlank()) {
                continue;
            }
            if (!status.isBlank() && !"listed".equalsIgnoreCase(status)) {
                continue;
            }

            versions.add(new ModrinthVersion(versionNumber, versionType, gameVersions, loaders, datePublished));
        }
        return versions;
    }

    private List<String> splitTopLevelObjects(String json) {
        ArrayList<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char current = json.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (current == '"') {
                inString = true;
                continue;
            }

            if (current == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(json.substring(start, i + 1));
                    start = -1;
                }
            }
        }

        return objects;
    }

    private String readStringField(String object, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");
        Matcher matcher = pattern.matcher(object);
        return matcher.find() ? unescapeJsonString(matcher.group(1)) : "";
    }

    private List<String> readStringArrayField(String object, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(object);
        if (!matcher.find()) {
            return List.of();
        }

        ArrayList<String> values = new ArrayList<>();
        Matcher valueMatcher = STRING_VALUE_PATTERN.matcher(matcher.group(1));
        while (valueMatcher.find()) {
            values.add(unescapeJsonString(valueMatcher.group(1)));
        }
        return List.copyOf(values);
    }

    private String unescapeJsonString(String input) {
        return input
            .replace("\\\"", "\"")
            .replace("\\\\", "\\");
    }

    private String cleanMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private record ModrinthVersion(
        String versionNumber,
        String versionType,
        List<String> gameVersions,
        List<String> loaders,
        String datePublished
    ) {
    }

    private record CheckResult(
        boolean updateAvailable,
        boolean failed,
        String currentVersion,
        String latestVersion,
        String message
    ) {
        static CheckResult updateAvailable(String currentVersion, String latestVersion) {
            return new CheckResult(
                true,
                false,
                currentVersion,
                latestVersion,
                "NeoTab update available: current " + currentVersion + ", latest " + latestVersion + ". Download: " + DOWNLOAD_URL
            );
        }

        static CheckResult noUpdate(String currentVersion, String message) {
            return new CheckResult(false, false, currentVersion, "", message);
        }

        static CheckResult failed(String message) {
            return new CheckResult(false, true, "", "", message);
        }
    }

    private record ParsedVersion(
        int major,
        int minor,
        int patch,
        String preRelease,
        int preReleaseNumber
    ) {
        static ParsedVersion parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return new ParsedVersion(0, 0, 0, "", 0);
            }

            String normalized = raw.trim()
                .replaceFirst("^[vV]", "")
                .replace('_', '-');
            String[] releaseSplit = normalized.split("-", 2);
            String[] numbers = releaseSplit[0].split("\\.");
            String preRelease = releaseSplit.length > 1 ? releaseSplit[1].toLowerCase(Locale.ROOT) : "";

            int preNumber = 0;
            if (!preRelease.isBlank()) {
                Matcher matcher = Pattern.compile("(\\d+)").matcher(preRelease);
                if (matcher.find()) {
                    preNumber = parseInt(matcher.group(1));
                }
            }

            return new ParsedVersion(
                numbers.length > 0 ? parseInt(numbers[0]) : 0,
                numbers.length > 1 ? parseInt(numbers[1]) : 0,
                numbers.length > 2 ? parseInt(numbers[2]) : 0,
                preRelease,
                preNumber
            );
        }

        private static int parseInt(String value) {
            try {
                return Integer.parseInt(value.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ex) {
                return 0;
            }
        }
    }
}
