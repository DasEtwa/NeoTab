package de.NeoTab.neotab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class ScoreboardAndPermissionRegressionTest {
    @Test
    void lowScoreboardIntervalWarningHonorsEnabledStateAndWorkload() {
        assertFalse(ScoreboardService.shouldWarnAboutInterval(false, 1, true, 15));
        assertTrue(ScoreboardService.shouldWarnAboutInterval(true, 3, false, 0));
        assertFalse(ScoreboardService.shouldWarnAboutInterval(true, 7, false, 4));
        assertTrue(ScoreboardService.shouldWarnAboutInterval(true, 7, false, 5));
        assertTrue(ScoreboardService.shouldWarnAboutInterval(true, 7, true, 0));
        assertFalse(ScoreboardService.shouldWarnAboutInterval(true, 10, true, 15));
    }

    @Test
    void delayedJoinUpdateRequiresCurrentEnabledGeneration() {
        assertTrue(ScoreboardService.isJoinUpdateValid(true, true, true, true, 4L, 4L));
        assertFalse(ScoreboardService.isJoinUpdateValid(false, true, true, true, 4L, 4L));
        assertFalse(ScoreboardService.isJoinUpdateValid(true, false, true, true, 4L, 4L));
        assertFalse(ScoreboardService.isJoinUpdateValid(true, true, false, true, 4L, 4L));
        assertFalse(ScoreboardService.isJoinUpdateValid(true, true, true, false, 4L, 4L));
        assertFalse(ScoreboardService.isJoinUpdateValid(true, true, true, true, 4L, 5L));
    }

    @Test
    void legacyTrimmingPreservesHexCodesEmojiAndCombiningMarks() {
        String hexColor = "§x§A§A§0§0§A§A";
        String emoji = "\uD83D\uDE80";
        assertEquals(hexColor + emoji, ScoreboardService.truncateLegacy(hexColor + emoji + "x", 16));
        assertEquals("", ScoreboardService.truncateLegacy(emoji, 1));
        assertEquals(emoji, ScoreboardService.truncateLegacy(emoji + "x", 2));
        assertEquals("", ScoreboardService.truncateLegacy("e\u0301x", 1));
        assertEquals("e\u0301", ScoreboardService.truncateLegacy("e\u0301x", 2));
        String flag = "\uD83C\uDDE9\uD83C\uDDEA";
        assertEquals("", ScoreboardService.truncateLegacy(flag + "x", 2));
        assertEquals(flag, ScoreboardService.truncateLegacy(flag + "x", 4));
        String subdivisionFlag = new String(
            new int[] {0x1F3F4, 0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067, 0xE007F},
            0,
            7
        );
        assertEquals("", ScoreboardService.truncateLegacy(subdivisionFlag, subdivisionFlag.length() - 1));
        assertEquals(subdivisionFlag, ScoreboardService.truncateLegacy(subdivisionFlag, subdivisionFlag.length()));
        assertEquals("§dHi", ScoreboardService.truncateLegacy("§dHi there", 4));
    }

    @Test
    void placeholderApiDetectionIgnoresOrdinaryPercentText() {
        assertFalse(ScoreboardService.containsPlaceholderApiToken("CPU: 100%"));
        assertFalse(ScoreboardService.containsPlaceholderApiToken("100% complete"));
        assertTrue(ScoreboardService.containsPlaceholderApiToken("Player: %player_name%"));
        assertTrue(ScoreboardService.containsPlaceholderApiToken("100% complete %player_name%"));
    }

    @Test
    void scoreboardGuiMutationRequiresBaseAndChildPermissions() {
        assertTrue(NeoTabGui.hasScoreboardMutationPermission(true, true));
        assertFalse(NeoTabGui.hasScoreboardMutationPermission(false, true));
        assertFalse(NeoTabGui.hasScoreboardMutationPermission(true, false));
    }

    @Test
    void timerTextRequiresNormalAndAdminPermissions() {
        assertTrue(TabCommand.canEditTimerText(true, true));
        assertFalse(TabCommand.canEditTimerText(true, false));
        assertFalse(TabCommand.canEditTimerText(false, true));
    }

    @Test
    void concurrentReloadRequestsAreDeduplicated() {
        AtomicBoolean reloadInProgress = new AtomicBoolean(false);
        assertTrue(TabCommand.beginReload(reloadInProgress));
        assertFalse(TabCommand.beginReload(reloadInProgress));
        assertFalse(TabCommand.commandAllowedDuringReload(true, "sb"));
        assertTrue(TabCommand.commandAllowedDuringReload(true, "reload"));
        reloadInProgress.set(false);
        assertTrue(TabCommand.beginReload(reloadInProgress));
        assertTrue(TabCommand.commandAllowedDuringReload(false, "region"));
    }
}
