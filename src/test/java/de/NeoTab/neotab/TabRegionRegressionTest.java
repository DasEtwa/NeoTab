package de.NeoTab.neotab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.junit.jupiter.api.Test;

final class TabRegionRegressionTest {
    @Test
    void joinAndQuitRefreshesAreBatchedIntoOnePendingTask() {
        TabUpdater.MembershipBatch batch = new TabUpdater.MembershipBatch();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(batch.requestJoin(first));
        assertFalse(batch.requestJoin(second));
        assertFalse(batch.requestMembershipChange());
        assertTrue(batch.pending());

        Set<UUID> joined = batch.drain();
        assertEquals(Set.of(first, second), joined);
        assertFalse(batch.pending());
        assertTrue(batch.requestMembershipChange());
    }

    @Test
    void independentHeaderAndFooterOwnershipSurvivesPlatformReserialization() {
        TabUpdater.FieldDecision foreignHeader = TabUpdater.decideField(
            true,
            "\u00A7dNeoTab",
            "\u00A7cForeign header",
            "\u00A7bNext header"
        );
        TabUpdater.FieldDecision normalizedFooter = TabUpdater.decideField(
            true,
            "\u00A7dFooter",
            "\u00A7dFooter\u00A7r",
            "\u00A7bNext footer"
        );

        assertFalse(foreignHeader.owned());
        assertFalse(foreignHeader.write());
        assertTrue(normalizedFooter.owned());
        assertTrue(normalizedFooter.write());
    }

    @Test
    void moveListenerIgnoresCancelledEventsAtMonitorPriority() throws Exception {
        Method method = RegionMoveListener.class.getDeclaredMethod("onPlayerMove", PlayerMoveEvent.class);
        EventHandler handler = method.getAnnotation(EventHandler.class);

        assertNotNull(handler);
        assertEquals(EventPriority.MONITOR, handler.priority());
        assertTrue(handler.ignoreCancelled());
        assertNotNull(RegionManager.class.getDeclaredMethod("handleMove", Player.class, Location.class));
    }

    @Test
    void respawnHasAnExplicitRegionReevaluationPath() throws Exception {
        Method listener = RegionMoveListener.class.getDeclaredMethod("onPlayerRespawn", PlayerRespawnEvent.class);
        EventHandler handler = listener.getAnnotation(EventHandler.class);

        assertNotNull(handler);
        assertEquals(EventPriority.MONITOR, handler.priority());
        assertNotNull(RegionManager.class.getDeclaredMethod("handleRespawn", Player.class, Location.class));
    }

    @Test
    void regionDebounceKeepsOnlyTheLatestTargetAndEscalatesForcedRendering() {
        Map<UUID, RegionManager.PendingRegionUpdate> pending = new HashMap<>();
        UUID uuid = UUID.randomUUID();

        assertTrue(RegionManager.mergePendingRegionUpdate(
            pending, uuid, new Location(null, 1.0, 2.0, 3.0), false
        ));
        assertFalse(RegionManager.mergePendingRegionUpdate(
            pending, uuid, new Location(null, 9.0, 8.0, 7.0), true
        ));

        assertEquals(1, pending.size());
        assertEquals(9, pending.get(uuid).target().getBlockX());
        assertTrue(pending.get(uuid).forceRender());
    }

    @Test
    void regionLimitsRejectCountAndChunkBudgetGrowth() {
        RegionProfile oneChunk = new RegionProfile(
            "small", true, "world", 0, 0, 0, 15, 255, 15, 0, "default", "default"
        );

        RegionManager.RegionMutationResult countResult = RegionManager.validateCandidate(
            RegionManager.MAX_REGIONS, 0L, 0, null, oneChunk
        );
        RegionManager.RegionMutationResult chunkResult = RegionManager.validateCandidate(
            1, RegionManager.MAX_TOTAL_INDEXED_CHUNKS, 0, null, oneChunk
        );

        assertEquals(RegionManager.MutationFailure.REGION_COUNT_LIMIT, countResult.failure());
        assertEquals(RegionManager.MutationFailure.CHUNK_BUDGET_LIMIT, chunkResult.failure());
    }

    @Test
    void regionPriorityButtonsClampInsteadOfWrappingAtIntegerLimits() {
        assertEquals(Integer.MAX_VALUE, RegionProfileGui.adjustedPriority(Integer.MAX_VALUE, 1));
        assertEquals(Integer.MIN_VALUE, RegionProfileGui.adjustedPriority(Integer.MIN_VALUE, -1));
        assertEquals(43, RegionProfileGui.adjustedPriority(42, 1));
        assertEquals(41, RegionProfileGui.adjustedPriority(42, -1));
    }

    @Test
    void regionPositionWorldMismatchUsesFailureInsteadOfSuccessMessage() {
        RegionManager.RegionMutationResult result = new RegionManager.RegionMutationResult(
            false,
            RegionManager.MutationFailure.WORLD_MISMATCH,
            "different worlds",
            "world",
            "world_nether"
        );

        assertEquals(
            "region-position-world-mismatch",
            RegionCommand.mutationFailureMessageKey(result, "region-pos1-set")
        );

        RegionManager.RegionMutationResult missing = new RegionManager.RegionMutationResult(
            false,
            RegionManager.MutationFailure.NOT_FOUND,
            "missing",
            "",
            ""
        );
        assertEquals("region-missing", RegionCommand.mutationFailureMessageKey(missing, "region-pos1-set"));
    }

    @Test
    void nullMoveTargetIsNeverTreatedAsARegionChange() {
        assertFalse(RegionMoveListener.changedBlockOrWorld(new Location(null, 0, 0, 0), null));
    }
}
