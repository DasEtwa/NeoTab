package de.NeoTab.neotab;

import net.kyori.adventure.text.Component;

public record ActionBarMessage(
    String source,
    Component text,
    int priority,
    long expiresAtMillis
) {
    public boolean expired(long nowMillis) {
        return expiresAtMillis <= nowMillis;
    }
}
