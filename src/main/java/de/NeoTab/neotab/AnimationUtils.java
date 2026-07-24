package de.NeoTab.neotab;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import net.kyori.adventure.text.format.TextColor;

public final class AnimationUtils {
    private static final List<TextColor> RAINBOW_COLORS = List.of(
        TextColor.color(0xFF3B30),
        TextColor.color(0xFF9500),
        TextColor.color(0xFFCC00),
        TextColor.color(0x34C759),
        TextColor.color(0x00C7BE),
        TextColor.color(0x007AFF),
        TextColor.color(0x5856D6),
        TextColor.color(0xAF52DE),
        TextColor.color(0xFF2D55),
        TextColor.color(0xFF3B30)
    );

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

        return buildLegacyText(plain, colors, config.getStyle(), tick, config.isHeaderBoldAnimationEnabled());
    }

    public static String buildLegacyText(String plain, List<TextColor> colors, Style style, int tick, boolean bold) {
        if (plain == null || plain.isBlank()) {
            return "";
        }
        if (colors == null || colors.isEmpty()) {
            return plain;
        }

        Style resolvedStyle = style == null ? Style.STATIC : style;
        return switch (resolvedStyle) {
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

    private static String buildRainbow(String text, List<TextColor> ignoredColors, int tick, boolean bold) {
        List<FormattedGlyph> glyphs = formattedGlyphs(text);
        int length = Math.max(1, glyphs.size());
        double phase = tick * 0.055;
        return buildColored(glyphs, index -> {
            double position = ((double) index / (double) length + phase) % 1.0;
            return gradientColorLinear(RAINBOW_COLORS, position);
        }, bold);
    }

    private static String buildPulse(String text, List<TextColor> colors, int tick, boolean bold) {
        double position = (Math.sin(tick * 0.14) + 1.0) / 2.0;
        TextColor color = gradientColorLinear(colors, position);
        return buildColored(formattedGlyphs(text), index -> color, bold);
    }

    private static String buildGradientWave(String text, List<TextColor> colors, int tick, boolean bold) {
        List<FormattedGlyph> glyphs = formattedGlyphs(text);
        int length = glyphs.size();
        double wave = tick * 0.045;
        return buildColored(glyphs, index -> {
            if (length <= 1) {
                return colors.get(0);
            }

            double base = (double) index / (double) (length - 1);
            double position = (base + wave) % 1.0;
            return gradientColorCyclic(colors, position);
        }, bold);
    }

    private static String buildStaticGradient(String text, List<TextColor> colors, boolean bold) {
        List<FormattedGlyph> glyphs = formattedGlyphs(text);
        int length = glyphs.size();
        return buildColored(glyphs, index -> {
            if (length <= 1) {
                return colors.get(0);
            }

            double position = (double) index / (double) (length - 1);
            return gradientColorLinear(colors, position);
        }, bold);
    }

    private static String buildColored(List<FormattedGlyph> glyphs, IntFunction<TextColor> colorProvider, boolean bold) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < glyphs.size(); i++) {
            TextColor color = colorProvider.apply(i);
            builder.append(legacyHex(color));
            builder.append(glyphs.get(i).formatting());
            if (bold) {
                builder.append("\u00A7l");
            }
            builder.append(glyphs.get(i).text());
        }
        return builder.toString();
    }

    /**
     * Splits visible text without ever separating a surrogate pair. Combining marks,
     * variation selectors, emoji modifiers and zero-width-joiner sequences stay with
     * their base glyph so inserting a legacy colour sequence cannot corrupt them.
     */
    static List<String> unicodeGlyphs(String text) {
        return formattedGlyphs(text).stream().map(FormattedGlyph::text).toList();
    }

    private static List<FormattedGlyph> formattedGlyphs(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        ArrayList<FormattedGlyph> glyphs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String activeFormatting = "";
        String currentFormatting = "";
        boolean joinNext = false;
        int regionalIndicatorCount = 0;
        int offset = 0;
        while (offset < text.length()) {
            String legacyToken = legacyTokenAt(text, offset);
            if (legacyToken != null) {
                if (current.length() > 0) {
                    glyphs.add(new FormattedGlyph(currentFormatting, current.toString()));
                    current.setLength(0);
                }
                activeFormatting = applyLegacyFormatting(activeFormatting, legacyToken);
                offset += legacyToken.length();
                joinNext = false;
                regionalIndicatorCount = 0;
                continue;
            }

            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);

            boolean continuation = current.length() > 0
                && (joinNext
                    || codePoint == 0x200D
                    || isCombiningMark(codePoint)
                    || isVariationSelector(codePoint)
                    || isEmojiModifier(codePoint)
                    || isEmojiTag(codePoint)
                    || (isRegionalIndicator(codePoint) && regionalIndicatorCount % 2 == 1));
            if (!continuation && current.length() > 0) {
                glyphs.add(new FormattedGlyph(currentFormatting, current.toString()));
                current.setLength(0);
                regionalIndicatorCount = 0;
            }

            if (current.length() == 0) {
                currentFormatting = activeFormatting;
            }
            current.appendCodePoint(codePoint);
            joinNext = codePoint == 0x200D;
            if (isRegionalIndicator(codePoint)) {
                regionalIndicatorCount++;
            }
        }
        if (current.length() > 0) {
            glyphs.add(new FormattedGlyph(currentFormatting, current.toString()));
        }
        return List.copyOf(glyphs);
    }

    private static String legacyTokenAt(String text, int offset) {
        if (text.charAt(offset) != '\u00A7' || offset + 1 >= text.length()) {
            return null;
        }

        char code = Character.toLowerCase(text.charAt(offset + 1));
        if (code == 'x' && offset + 14 <= text.length()) {
            for (int index = offset + 2; index < offset + 14; index += 2) {
                if (text.charAt(index) != '\u00A7' || Character.digit(text.charAt(index + 1), 16) < 0) {
                    return null;
                }
            }
            return text.substring(offset, offset + 14);
        }
        if ("0123456789abcdefklmnor".indexOf(code) >= 0) {
            return text.substring(offset, offset + 2);
        }
        return null;
    }

    private static String applyLegacyFormatting(String activeFormatting, String token) {
        char code = Character.toLowerCase(token.charAt(1));
        if (code == 'r') {
            return "";
        }
        if (code == 'x' || "0123456789abcdef".indexOf(code) >= 0) {
            // A legacy colour code also resets all active decorations.
            return token;
        }
        return activeFormatting + token;
    }

    private static boolean isCombiningMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
            || type == Character.COMBINING_SPACING_MARK
            || type == Character.ENCLOSING_MARK;
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

    private record FormattedGlyph(String formatting, String text) {
    }

    private static String legacyHex(TextColor color) {
        String hex = String.format("%02X%02X%02X", color.red(), color.green(), color.blue());
        StringBuilder builder = new StringBuilder("\u00A7x");
        for (int i = 0; i < hex.length(); i++) {
            builder.append('\u00A7').append(hex.charAt(i));
        }
        return builder.toString();
    }

    private static TextColor gradientColorLinear(List<TextColor> colors, double position) {
        if (colors.isEmpty()) {
            return TextColor.color(0xAA00AA);
        }
        if (colors.size() == 1) {
            return colors.get(0);
        }

        double clamped = Math.max(0.0, Math.min(1.0, position));
        double scaled = clamped * (colors.size() - 1);
        int index = (int) Math.floor(scaled);
        int nextIndex = Math.min(index + 1, colors.size() - 1);
        double t = scaled - index;
        return interpolate(colors.get(index), colors.get(nextIndex), t);
    }

    private static TextColor gradientColorCyclic(List<TextColor> colors, double position) {
        if (colors.isEmpty()) {
            return TextColor.color(0xAA00AA);
        }
        if (colors.size() == 1) {
            return colors.get(0);
        }

        double wrapped = position - Math.floor(position);
        double scaled = wrapped * colors.size();
        int index = Math.min((int) Math.floor(scaled), colors.size() - 1);
        int nextIndex = (index + 1) % colors.size();
        return interpolate(colors.get(index), colors.get(nextIndex), scaled - index);
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
