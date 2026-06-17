package de.NeoTab.neotab;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.format.TextColor;

final class HeaderColorPalette {
    static final Map<String, List<String>> PRESETS = createColorPresets();

    private static final int MAX_COLORS = 5;

    private HeaderColorPalette() {
    }

    static List<String> parseCustomColors(String input) {
        List<String> colors = Arrays.stream(input.split(","))
            .map(String::trim)
            .filter(entry -> !entry.isEmpty())
            .map(HeaderColorPalette::normalizeHexColor)
            .toList();

        if (colors.isEmpty() || colors.size() > MAX_COLORS) {
            return null;
        }

        for (String color : colors) {
            if (TextColor.fromHexString(color) == null) {
                return null;
            }
        }
        return colors;
    }

    private static String normalizeHexColor(String input) {
        String normalized = input.trim();
        if (!normalized.startsWith("#") && normalized.matches("(?i)[0-9a-f]{6}")) {
            normalized = "#" + normalized;
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private static Map<String, List<String>> createColorPresets() {
        LinkedHashMap<String, List<String>> presets = new LinkedHashMap<>();
        presets.put("purple", List.of("#AA00AA", "#9932CC", "#BA55D3", "#DDA0DD", "#9370DB"));
        presets.put("red", List.of("#7F0000", "#B00020", "#FF1744", "#FF6B6B", "#FFCDD2"));
        presets.put("green", List.of("#005F2F", "#00A86B", "#00E676", "#69F0AE", "#B9F6CA"));
        presets.put("gold", List.of("#8A5A00", "#C88719", "#FFC107", "#FFD54F", "#FFF176"));
        return Collections.unmodifiableMap(presets);
    }
}
