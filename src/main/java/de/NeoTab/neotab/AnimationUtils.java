package de.NeoTab.neotab;

import java.util.List;
import java.util.function.IntFunction;
import net.kyori.adventure.text.format.TextColor;

public final class AnimationUtils {
    private AnimationUtils() {
    }

    public static String buildLegacyHeader(ConfigManager config, int tick) {
        return buildLegacyHeader(config, tick, config.getServerNameRaw());
    }

    public static String buildLegacyHeader(ConfigManager config, int tick, String serverNameRaw) {
        String plain = config.toPlain(serverNameRaw, "server-name");
        if (plain == null || plain.isBlank()) {
            return "";
        }

        List<TextColor> colors = config.getCustomColors();
        if (colors.isEmpty()) {
            return config.toLegacy(serverNameRaw, "server-name");
        }

        boolean bold = config.isHeaderBoldAnimationEnabled();
        return switch (config.getStyle()) {
            case RAINBOW -> buildRainbow(plain, colors, tick, bold);
            case PURPLE_PULSE -> buildPulse(plain, colors, tick, bold);
            case GRADIENT_WAVE -> buildGradientWave(plain, colors, tick, bold);
            case STATIC -> buildStaticGradient(plain, colors, bold);
        };
    }

    public static String buildFooterMiniMessageBase(ConfigManager config, TabUpdater.RamStats stats, int online, int max) {
        return config.getFooterFormat()
            .replace("{used}", Long.toString(stats.usedMb()))
            .replace("{total}", Long.toString(stats.totalMb()))
            .replace("{percent}", Integer.toString(stats.percent()))
            .replace("{ram_used}", Long.toString(stats.usedMb()))
            .replace("{ram_max}", Long.toString(stats.totalMb()))
            .replace("{ram_percent}", Integer.toString(stats.percent()))
            .replace("{online}", Integer.toString(online))
            .replace("{max}", Integer.toString(max));
    }

    public static String colorizePingMiniMessage(int ping) {
        String color = ping < 100 ? "green" : ping < 200 ? "yellow" : "red";
        return "<" + color + ">" + ping + "</" + color + ">";
    }

    private static String buildRainbow(String text, List<TextColor> colors, int tick, boolean bold) {
        return buildColored(text, index -> colors.get(Math.floorMod(index + tick, colors.size())), bold);
    }

    private static String buildPulse(String text, List<TextColor> colors, int tick, boolean bold) {
        double position = (Math.sin(tick * 0.14) + 1.0) / 2.0;
        TextColor color = gradientColor(colors, position);
        return buildColored(text, index -> color, bold);
    }

    private static String buildGradientWave(String text, List<TextColor> colors, int tick, boolean bold) {
        int length = text.length();
        double wave = (Math.sin(tick * 0.12) + 1.0) / 2.0;
        return buildColored(text, index -> {
            if (length <= 1) {
                return colors.get(0);
            }

            double base = (double) index / (double) (length - 1);
            double position = (base + wave) % 1.0;
            return gradientColor(colors, position);
        }, bold);
    }

    private static String buildStaticGradient(String text, List<TextColor> colors, boolean bold) {
        int length = text.length();
        return buildColored(text, index -> {
            if (length <= 1) {
                return colors.get(0);
            }

            double position = (double) index / (double) (length - 1);
            return gradientColor(colors, position);
        }, bold);
    }

    private static String buildColored(String text, IntFunction<TextColor> colorProvider, boolean bold) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            TextColor color = colorProvider.apply(i);
            builder.append(legacyHex(color));
            if (bold) {
                builder.append("\u00A7l");
            }
            builder.append(text.charAt(i));
        }
        return builder.toString();
    }

    private static String legacyHex(TextColor color) {
        String hex = String.format("%02X%02X%02X", color.red(), color.green(), color.blue());
        StringBuilder builder = new StringBuilder("\u00A7x");
        for (int i = 0; i < hex.length(); i++) {
            builder.append('\u00A7').append(hex.charAt(i));
        }
        return builder.toString();
    }

    private static TextColor gradientColor(List<TextColor> colors, double position) {
        if (colors.isEmpty()) {
            return TextColor.color(0xAA00AA);
        }
        if (colors.size() == 1) {
            return colors.get(0);
        }

        double clamped = position - Math.floor(position);
        double scaled = clamped * (colors.size() - 1);
        int index = (int) Math.floor(scaled);
        int nextIndex = Math.min(index + 1, colors.size() - 1);
        double t = scaled - index;
        return interpolate(colors.get(index), colors.get(nextIndex), t);
    }

    private static TextColor interpolate(TextColor from, TextColor to, double t) {
        int red = (int) Math.round(from.red() + (to.red() - from.red()) * t);
        int green = (int) Math.round(from.green() + (to.green() - from.green()) * t);
        int blue = (int) Math.round(from.blue() + (to.blue() - from.blue()) * t);
        return TextColor.color(red, green, blue);
    }

    public enum Style {
        RAINBOW("rainbow"),
        PURPLE_PULSE("purple-pulse"),
        GRADIENT_WAVE("gradient-wave"),
        STATIC("static");

        private final String id;

        Style(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Style fromString(String input) {
            if (input == null) {
                return null;
            }

            String normalized = input.trim().toLowerCase();
            for (Style style : values()) {
                if (style.id.equalsIgnoreCase(normalized)) {
                    return style;
                }
            }
            return null;
        }
    }
}
