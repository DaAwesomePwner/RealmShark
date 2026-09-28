package tomato.gui.runs;

import java.util.*;
import org.junit.Test;
import tomato.gui.stats.LootFacts;
import tomato.history.encounter.CombatRecord;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeHistoryFixture.MINUTE;
import static tomato.gui.runs.RunOutcome.*;

/**
 * One Dungeons card from its runs' feed cards (user decision 2026-09-28): completion over finished runs only, averages and
 * best DPS from completed runs only, loot per completed run over runs whose loot is known, and every unknown null with a
 * one-line reason, never 0.
 */
public class DungeonCardModelTest {
    private static final String SESSION = "s", OTHER = "o";
    /** Loot: every bag of the run's session (null = unreadable, empty = none saved). */
    private static final List<LootFacts.Bag> UNREADABLE = null, NOT_SAVED = List.of();
    private int next;

    private VisitRef ref() { return new VisitRef(SESSION, "v" + (++next)); }
    private static LootFacts.Item item(int id) { return new LootFacts.Item(id, false, false, false, false); }

    /** The run's session saved bags: {@code items} of them linked to this run (0 = a known none: the bag is another run's). */
    private static List<LootFacts.Bag> loot(VisitRef ref, int items) {
        List<LootFacts.Item> list = new ArrayList<>();
        for (int i = 0; i < items; i++) list.add(item(100 + i));
        return items == 0 ? List.of(new LootFacts.Bag(ref.sessionId, 1, true, "White", new VisitRef(ref.sessionId, "elsewhere"), List.of(item(1))))
            : List.of(new LootFacts.Bag(ref.sessionId, 1, true, "White", ref, list));
    }

    /** A recording of {@code dps} for the verified local player 1 over 100 s; {@code local} false: not verified. */
    private static List<CombatRecord> recording(VisitRef ref, String id, double dps, boolean local) {
        return List.of(RunFixtures.record(id, ref, local ? 1 : null, 100, Math.round(dps * 100), 500));
    }

    private RunCardModel run(RunOutcome outcome, long entered, Long durationMs, List<LootFacts.Bag> loot, List<CombatRecord> records) {
        return run(ref(), "Lost Halls", outcome, entered, durationMs, loot, records);
    }
    private static RunCardModel run(VisitRef ref, String map, RunOutcome outcome, long entered, Long durationMs, List<LootFacts.Bag> loot,
                                    List<CombatRecord> records) {
        return RunCardModel.of(ref, map, outcome, entered, durationMs, null, 0, records, loot, null);
    }
    /** A run with known, empty loot and no recording. */
    private RunCardModel plain(RunOutcome outcome) {
        VisitRef ref = ref();
        return run(ref, "Lost Halls", outcome, 1_000 + next, 10 * MINUTE, loot(ref, 0), List.of());
    }

    @Test public void completionCountsFinishedRunsOnlyAndCountsInProgressAndUnknownApart() {
        DungeonCardModel card = DungeonCardModel.of("Lost Halls", List.of(plain(COMPLETED), plain(COMPLETED), plain(LEFT), plain(APP_ENDED),
            plain(IN_PROGRESS), plain(UNKNOWN), plain(UNKNOWN)));
        assertEquals("Lost Halls", card.canonical()); assertEquals("Lost Halls", card.displayName());
        assertEquals(7, card.visits());
        assertEquals(2, card.completed()); assertEquals(1, card.left()); assertEquals(1, card.appEnded());
        assertEquals(1, card.inProgress()); assertEquals(2, card.unknown());
        assertEquals(4, card.finished());
        assertEquals("Completed ÷ (Completed + Left + App ended)", 0.5, card.completionRate(), 1e-9);
        assertNotNull("The excluded runs are named", card.completionReason());
        assertTrue(card.completionReason(), card.completionReason().contains("1 in progress") && card.completionReason().contains("2 unknown"));

        DungeonCardModel all = DungeonCardModel.of("Lost Halls", List.of(plain(COMPLETED), plain(LEFT)));
        assertEquals(0.5, all.completionRate(), 1e-9);
        assertNull("Every run is finished: nothing to explain", all.completionReason());
        assertEquals("A known 0 % is a finished run without a clear", 0.0,
            DungeonCardModel.of("Lost Halls", List.of(plain(LEFT), plain(APP_ENDED))).completionRate(), 1e-9);
    }

    @Test public void withoutAFinishedRunCompletionIsUnknownNotZero() {
        DungeonCardModel card = DungeonCardModel.of("Lost Halls", List.of(plain(IN_PROGRESS), plain(UNKNOWN)));
        assertEquals(2, card.visits()); assertEquals(0, card.finished());
        assertNull("Unknown, never 0 %", card.completionRate());
        assertTrue(card.completionReason(), card.completionReason().startsWith(DungeonCardModel.NO_FINISHED_RUN));
        assertTrue(card.completionReason(), card.completionReason().contains("1 in progress") && card.completionReason().contains("1 unknown"));
        assertNull(card.averageDurationMs()); assertEquals(0, card.durationRuns()); assertEquals(DungeonCardModel.NO_COMPLETED_RUN, card.durationReason());
        assertNull(card.lootPerCompletedRun()); assertEquals(0, card.lootRuns()); assertEquals(DungeonCardModel.NO_COMPLETED_RUN, card.lootReason());
        assertNull(card.bestLocalDps()); assertNull(card.bestRun()); assertNull(card.bestRecordingId());
        assertEquals(DungeonCardModel.NO_COMPLETED_RUN, card.dpsReason());
    }

    @Test public void averageDurationAndBestDpsComeFromCompletedRunsOnly() {
        VisitRef fast = ref(), slow = ref(), left = ref(), open = ref();
        DungeonCardModel card = DungeonCardModel.of("Lost Halls", List.of(
            run(fast, "Lost Halls", COMPLETED, 100, 10 * MINUTE, loot(fast, 1), recording(fast, "r-fast", 40, true)),
            run(slow, "Lost Halls", COMPLETED, 200, 20 * MINUTE, loot(slow, 1), recording(slow, "r-slow", 25, true)),
            run(left, "Lost Halls", LEFT, 300, 90 * MINUTE, loot(left, 1), recording(left, "r-left", 900, true)),
            run(open, "Lost Halls", IN_PROGRESS, 400, 60 * MINUTE, loot(open, 1), recording(open, "r-open", 800, true))));
        assertEquals("Mean observed span of the completed runs", Long.valueOf(15 * MINUTE), card.averageDurationMs());
        assertEquals(2, card.durationRuns()); assertNull(card.durationReason());
        assertEquals("A higher DPS in a run that did not complete never counts", 40.0, card.bestLocalDps(), 1e-9);
        assertEquals(fast, card.bestRun()); assertEquals("r-fast", card.bestRecordingId());
        assertNull("Every completed run has your verified DPS", card.dpsReason());
        assertEquals("The newest entry, of any outcome", 400, card.lastVisit());
    }

    @Test public void aCompletedRunWithoutAnObservedSpanIsLeftOutOfTheAverageAndNamed() {
        VisitRef a = ref(), b = ref();
        DungeonCardModel card = DungeonCardModel.of("Lost Halls", List.of(run(a, "Lost Halls", COMPLETED, 100, 12 * MINUTE, loot(a, 0), List.of()),
            run(b, "Lost Halls", COMPLETED, 0, null, loot(b, 0), List.of())));
        assertEquals(Long.valueOf(12 * MINUTE), card.averageDurationMs()); assertEquals(1, card.durationRuns());
        assertTrue(card.durationReason(), card.durationReason().startsWith("1 of 2 completed runs"));
        assertEquals("A run without an entry time does not set the last visit", 100, card.lastVisit());

        VisitRef c = ref();
        DungeonCardModel none = DungeonCardModel.of("Lost Halls", List.of(run(c, "Lost Halls", COMPLETED, 0, null, loot(c, 0), List.of())));
        assertNull("Unknown, never 0 s", none.averageDurationMs()); assertEquals(0, none.durationRuns());
        assertEquals(DungeonCardModel.NO_OBSERVED_SPAN, none.durationReason());
        assertEquals("No run has an entry time", 0, none.lastVisit());
    }

    @Test public void lootPerCompletedRunCountsOnlyRunsWhoseLootIsKnownAndIsPartialOtherwise() {
        VisitRef six = ref(), none = ref(), unsaved = ref(), unreadable = ref(), left = ref();
        DungeonCardModel card = DungeonCardModel.of("Lost Halls", List.of(
            run(six, "Lost Halls", COMPLETED, 1, 10 * MINUTE, loot(six, 6), List.of()),
            run(none, "Lost Halls", COMPLETED, 2, 10 * MINUTE, loot(none, 0), List.of()),
            run(unsaved, "Lost Halls", COMPLETED, 3, 10 * MINUTE, NOT_SAVED, List.of()),
            run(unreadable, "Lost Halls", COMPLETED, 4, 10 * MINUTE, UNREADABLE, List.of()),
            run(left, "Lost Halls", LEFT, 5, 10 * MINUTE, loot(left, 50), List.of())));
        assertEquals("6 items over the 2 completed runs whose loot is known (a known none is 0 and counts)", 3.0, card.lootPerCompletedRun(), 1e-9);
        assertEquals(2, card.lootRuns()); assertEquals(2, card.lootExcluded());
        assertTrue(card.lootPartial());
        assertTrue(card.lootReason(), card.lootReason().startsWith("2 of 4 completed runs"));
        assertTrue(card.lootReason(), card.lootReason().contains("saved no loot bag") && card.lootReason().contains("could not be read"));

        VisitRef zero = ref();
        DungeonCardModel known = DungeonCardModel.of("Lost Halls", List.of(run(zero, "Lost Halls", COMPLETED, 1, MINUTE, loot(zero, 0), List.of())));
        assertEquals("A known none is a real 0", 0.0, known.lootPerCompletedRun(), 1e-9);
        assertEquals(1, known.lootRuns()); assertEquals(0, known.lootExcluded()); assertFalse(known.lootPartial()); assertNull(known.lootReason());
    }

    @Test public void lootIsUnknownWhenNoCompletedRunsLootIsKnown() {
        DungeonCardModel unsaved = DungeonCardModel.of("Lost Halls", List.of(run(COMPLETED, 1, MINUTE, NOT_SAVED, List.of()),
            run(COMPLETED, 2, MINUTE, NOT_SAVED, List.of())));
        assertNull("Unknown, never 0", unsaved.lootPerCompletedRun());
        assertEquals(0, unsaved.lootRuns()); assertEquals(2, unsaved.lootExcluded()); assertFalse("Unknown, not partial", unsaved.lootPartial());
        assertEquals(DungeonCardModel.LOOT_NOT_SAVED, unsaved.lootReason());
        assertEquals(DungeonCardModel.LOOT_UNREADABLE,
            DungeonCardModel.of("Lost Halls", List.of(run(COMPLETED, 1, MINUTE, UNREADABLE, List.of()))).lootReason());
        DungeonCardModel mixed = DungeonCardModel.of("Lost Halls", List.of(run(COMPLETED, 1, MINUTE, NOT_SAVED, List.of()),
            run(COMPLETED, 2, MINUTE, UNREADABLE, List.of())));
        assertNull(mixed.lootPerCompletedRun());
        assertTrue(mixed.lootReason(), mixed.lootReason().contains("1 in a session that saved no loot bag") && mixed.lootReason().contains("1 whose session's loot could not be read"));
    }

    @Test public void aVerifiedPlayersRealZeroDpsNeverWins() {
        VisitRef zero = ref(), some = ref();
        DungeonCardModel card = DungeonCardModel.of("Lost Halls", List.of(
            run(zero, "Lost Halls", COMPLETED, 900, MINUTE, loot(zero, 0), recording(zero, "r-zero", 0, true)),
            run(some, "Lost Halls", COMPLETED, 100, MINUTE, loot(some, 0), recording(some, "r-some", 12.5, true))));
        assertEquals(12.5, card.bestLocalDps(), 1e-9); assertEquals(some, card.bestRun()); assertEquals("r-some", card.bestRecordingId());
        assertTrue("The zero run is named as not counted", card.dpsReason() != null && card.dpsReason().contains("recorded no damage"));

        VisitRef only = ref();
        DungeonCardModel zeros = DungeonCardModel.of("Lost Halls", List.of(run(only, "Lost Halls", COMPLETED, 1, MINUTE, loot(only, 0),
            recording(only, "r-only", 0, true))));
        assertNull("A zero is never a best", zeros.bestLocalDps()); assertNull(zeros.bestRun()); assertNull(zeros.bestRecordingId());
        assertEquals(DungeonCardModel.NO_DAMAGE, zeros.dpsReason());
    }

    @Test public void bestDpsIsUnknownWithTheReasonOfTheCompletedRunsThatLackIt() {
        VisitRef unverified = ref(), unlinked = ref();
        assertEquals(DungeonCardModel.UNVERIFIED_LOCAL, DungeonCardModel.of("Lost Halls", List.of(
            run(unverified, "Lost Halls", COMPLETED, 1, MINUTE, loot(unverified, 0), recording(unverified, "r-u", 30, false)))).dpsReason());
        assertEquals(DungeonCardModel.NO_RECORDING, DungeonCardModel.of("Lost Halls", List.of(
            run(unlinked, "Lost Halls", COMPLETED, 1, MINUTE, loot(unlinked, 0), List.of()))).dpsReason());
        assertEquals(DungeonCardModel.COMBAT_UNREADABLE, DungeonCardModel.of("Lost Halls", List.of(
            run(COMPLETED, 1, MINUTE, NOT_SAVED, null))).dpsReason());
        VisitRef a = ref(), b = ref(), left = ref();
        DungeonCardModel mixed = DungeonCardModel.of("Lost Halls", List.of(
            run(a, "Lost Halls", COMPLETED, 1, MINUTE, loot(a, 0), recording(a, "r-a", 30, false)),
            run(b, "Lost Halls", COMPLETED, 2, MINUTE, loot(b, 0), List.of()),
            run(left, "Lost Halls", LEFT, 3, MINUTE, NOT_SAVED, recording(left, "r-left", 99, true))));
        assertNull(mixed.bestLocalDps());
        assertTrue(mixed.dpsReason(), mixed.dpsReason().contains("1 without a linked recording") && mixed.dpsReason().contains("1 without your verified row"));

        VisitRef good = ref(), bad = ref();
        DungeonCardModel partial = DungeonCardModel.of("Lost Halls", List.of(
            run(good, "Lost Halls", COMPLETED, 1, MINUTE, loot(good, 0), recording(good, "r-good", 30, true)),
            run(bad, "Lost Halls", COMPLETED, 2, MINUTE, loot(bad, 0), recording(bad, "r-bad", 30, false))));
        assertEquals(30.0, partial.bestLocalDps(), 1e-9);
        assertTrue("A best of some completed runs says so", partial.dpsReason() != null && partial.dpsReason().startsWith("Best of 1 of 2 completed runs"));
    }

    @Test public void sessionPartialsMergeToTheSameCardInAnyOrder() {
        List<RunCardModel> first = new ArrayList<>(), second = new ArrayList<>();
        VisitRef a = ref(), b = new VisitRef(OTHER, "v1"), c = new VisitRef(OTHER, "v2");
        first.add(run(a, "Lost Halls", COMPLETED, 500, 10 * MINUTE, loot(a, 2), recording(a, "r-a", 20, true)));
        first.add(plain(LEFT));
        second.add(run(b, "Lost Halls", COMPLETED, 500, 30 * MINUTE, loot(b, 4), recording(b, "r-b", 20, true)));   // same DPS and entry
        second.add(run(c, "Lost Halls", IN_PROGRESS, 2_000, null, NOT_SAVED, List.of()));
        List<RunCardModel> all = new ArrayList<>(first); all.addAll(second);
        DungeonCardModel whole = DungeonCardModel.of("Lost Halls", all);

        DungeonCardModel.Tally one = new DungeonCardModel.Tally("Lost Halls"), two = new DungeonCardModel.Tally("Lost Halls");
        first.forEach(one::add); second.forEach(two::add);
        DungeonCardModel.Tally forward = new DungeonCardModel.Tally("Lost Halls"), backward = new DungeonCardModel.Tally("Lost Halls");
        forward.merge(one); forward.merge(two); backward.merge(two); backward.merge(one);
        assertEquals(whole, DungeonCardModel.of(forward));
        assertEquals(whole, DungeonCardModel.of(backward));
        assertEquals("Merging leaves the session partials as they were", 2, one.visits());
        assertEquals("Equal DPS and entry: the greater run reference (s/v1 over o/v1) wins, whatever the order", a, whole.bestRun());
        assertEquals(Long.valueOf(20 * MINUTE), whole.averageDurationMs());
        assertEquals(3.0, whole.lootPerCompletedRun(), 1e-9);
        assertEquals("The newest entry of any outcome and session", 2_000, whole.lastVisit());
    }

    @Test public void theCardNamesItsDungeonAndPortal() {
        DungeonCardModel card = DungeonCardModel.of("Ice Citadel", List.of(run(ref(), "Ice Citadel", LEFT, 1, MINUTE, NOT_SAVED, List.of())));
        assertEquals("Ice Citadel", card.displayName());
        assertEquals(tomato.gui.kit.Portals.spriteId("Ice Citadel"), card.portalId());
        assertTrue(card.portalId() > 0);
    }
}
