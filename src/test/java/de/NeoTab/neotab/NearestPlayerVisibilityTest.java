package de.NeoTab.neotab;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class NearestPlayerVisibilityTest {
    @Test
    void hiddenPlayersAreNeverEligibleCandidates() {
        Player candidate = player(true, true, true);
        Player viewer = player(true, true, false);

        assertFalse(NearestPlayerModule.isEligibleCandidate(viewer, candidate));
    }

    @Test
    void selfOfflineAndInvalidPlayersAreExcluded() {
        Player viewer = player(true, true, true);

        assertFalse(NearestPlayerModule.isEligibleCandidate(viewer, viewer));
        assertFalse(NearestPlayerModule.isEligibleCandidate(viewer, player(false, true, true)));
        assertFalse(NearestPlayerModule.isEligibleCandidate(viewer, player(true, false, true)));
        assertTrue(NearestPlayerModule.isEligibleCandidate(viewer, player(true, true, true)));
    }

    private static Player player(boolean online, boolean valid, boolean canSee) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "isOnline" -> online;
                case "isValid" -> valid;
                case "canSee" -> canSee;
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "PlayerStub";
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        if (type == double.class) {
            return 0.0D;
        }
        return null;
    }
}
