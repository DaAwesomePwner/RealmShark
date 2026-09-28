package tomato.gui.runs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;

/** Fame gained per run: positive steps credited to the later reading's exact visit, per session and series. Synthetic data. */
public class FameGainsTest {
    private static final String SESSION = "session-a", OTHER = "session-b";
    private static final String ACCOUNT_A = "a".repeat(64), ACCOUNT_B = "b".repeat(64);
    private static final VisitRef V1 = new VisitRef(SESSION, "v1"), V2 = new VisitRef(SESSION, "v2"), V3 = new VisitRef(SESSION, "v3");
    /** One capture interval around every reading of the rule tests below: coverage is required for any gain. */
    private static final SessionStore.ModuleAvailability COVERED = runs(false, interval(500, 10_000));

    private static SessionStore.Interval interval(long from, long until) { return new SessionStore.Interval(from, until, "Capture stopped"); }
    /** The runs module's saved coverage as session.json holds it: intervals oldest first. */
    private static SessionStore.ModuleAvailability runs(boolean truncated, SessionStore.Interval... intervals) {
        return new SessionStore.ModuleAvailability(SessionStore.ModuleAvailability.State.PARTIAL, "Fixture", intervals[0].from,
            intervals[intervals.length - 1].until, List.of(intervals), truncated);
    }

    private static AppHistory.FameSample untagged(int character, long fame, long time) {
        return new AppHistory.FameSample(character, ACCOUNT_A, fame, time, "Wizard");
    }
    private static AppHistory.FameSample in(VisitRef visit, int character, long fame, long time) {
        return new AppHistory.FameSample(character, ACCOUNT_A, fame, time, "Wizard", visit, "Lost Halls");
    }

    @Test public void theFirstIncreaseInsideAVisitIsCountedAgainstThePreviousReading() {
        // Samples append only on change: the reading before entry is outside the visit. "Last − first" of V1's samples says 30.
        Map<VisitRef, Long> gains = FameGains.byVisit(List.of(untagged(1, 100, 1_000), in(V1, 1, 150, 2_000), in(V1, 1, 180, 3_000),
            untagged(1, 200, 4_000)), SESSION, COVERED);
        assertEquals("100 → 150 → 180: the first step belongs to V1 too", Map.of(V1, 80L), gains);
        assertEquals(Optional.of(80L), FameGains.of(gains, V1));
    }

    @Test public void negativeStepsAreNotGainsAndAVisitWithOnlyADropIsAKnownZero() {
        Map<VisitRef, Long> gains = FameGains.byVisit(List.of(untagged(1, 200, 1_000), in(V1, 1, 190, 2_000), in(V1, 1, 250, 3_000),
            in(V2, 1, 240, 4_000)), SESSION, COVERED);
        assertEquals("−10 is ignored; +60 counts", Long.valueOf(60), gains.get(V1));
        assertEquals("Every V2 reading has a predecessor: a real zero, not unknown", Optional.of(0L), FameGains.of(gains, V2));
    }

    @Test public void samplesOfOtherSessionsAreIgnoredAndNeverServeAsABaseline() {
        VisitRef foreign = new VisitRef(OTHER, "v1");
        Map<VisitRef, Long> gains = FameGains.byVisit(List.of(in(foreign, 1, 100, 1_000), in(V1, 1, 150, 2_000), in(V1, 1, 170, 3_000),
            in(foreign, 1, 500, 4_000)), SESSION, COVERED);
        assertFalse("Never credited to another session's visit", gains.containsKey(foreign));
        assertEquals("The foreign reading is not V1's predecessor, so V1's first step is not known", Map.of(), gains);
        assertEquals(Optional.empty(), FameGains.of(gains, V1));
        assertEquals(Optional.empty(), FameGains.of(gains, foreign));
    }

    @Test public void untaggedReadingsAreBaselinesButNeverCredited() {
        Map<VisitRef, Long> gains = FameGains.byVisit(List.of(untagged(1, 100, 1_000), untagged(1, 130, 2_000), in(V1, 1, 160, 3_000),
            untagged(1, 400, 4_000), in(V2, 1, 410, 5_000)), SESSION, COVERED);
        assertEquals("130 → 160 only; 100 → 130 and 160 → 400 have no visit", Long.valueOf(30), gains.get(V1));
        assertEquals("400 → 410: credited to the later reading's visit", Long.valueOf(10), gains.get(V2));
        assertEquals(2, gains.size());
        assertEquals("Only untagged readings: no visit has a gain", Map.of(),
            FameGains.byVisit(List.of(untagged(1, 100, 1_000), untagged(1, 200, 2_000)), SESSION, COVERED));
    }

    @Test public void aVisitWhoseFirstTaggedReadingHasNoPredecessorIsUnknownNotZeroOrPartial() {
        Map<VisitRef, Long> gains = FameGains.byVisit(List.of(in(V1, 1, 100, 1_000), in(V1, 1, 150, 2_000), in(V2, 1, 170, 3_000)), SESSION, COVERED);
        assertNull("V1's first reading opens the series: its gain before it is unknown, so 50 would undercount", gains.get(V1));
        assertEquals(Optional.empty(), FameGains.of(gains, V1));
        assertEquals(Long.valueOf(20), gains.get(V2));
        // One of V3's readings opens character #2's series: the whole visit is unknown even though #1's step is known.
        gains = FameGains.byVisit(List.of(untagged(1, 100, 1_000), in(V3, 1, 120, 2_000), in(V3, 2, 50, 3_000)), SESSION, COVERED);
        assertFalse(gains.containsKey(V3));
        // A tagged reading without a time cannot be placed in its series: unknown too.
        gains = FameGains.byVisit(List.of(untagged(1, 100, 1_000), in(V1, 1, 120, 2_000), in(V1, 1, 130, 0)), SESSION, COVERED);
        assertFalse(gains.containsKey(V1));
    }

    @Test public void twoCharactersAndTwoAccountsAreKeptApart() {
        // Interleaved readings: as one series, #2's lower fame reads as a drop and #1's next reading as a gain of the difference.
        Map<VisitRef, Long> gains = FameGains.byVisit(List.of(untagged(1, 1_000, 1_000), untagged(2, 50, 2_000), in(V1, 1, 1_100, 3_000),
            in(V2, 2, 80, 4_000)), SESSION, COVERED);
        assertEquals(Map.of(V1, 100L, V2, 30L), gains);
        List<AppHistory.FameSample> accounts = List.of(
            new AppHistory.FameSample(7, ACCOUNT_A, 1_000, 1_000, "Wizard"), new AppHistory.FameSample(7, ACCOUNT_B, 50, 2_000, "Wizard"),
            new AppHistory.FameSample(7, ACCOUNT_A, 1_100, 3_000, "Wizard", V1, "Lost Halls"),
            new AppHistory.FameSample(7, ACCOUNT_B, 80, 4_000, "Wizard", V2, "Lost Halls"));
        assertEquals("Character #7 on two accounts: two series", Map.of(V1, 100L, V2, 30L), FameGains.byVisit(accounts, SESSION, COVERED));
    }

    @Test public void aCheckpointCopyOfTheLastReadingIsNotCountedTwiceAndOrderDoesNotMatter() {
        // fame-latest holds the last observation; read beside fame.jsonl it can repeat the last appended reading exactly.
        List<AppHistory.FameSample> samples = new ArrayList<>(List.of(in(V1, 1, 180, 3_000), untagged(1, 100, 1_000), in(V1, 1, 150, 2_000)));
        samples.add(in(V1, 1, 180, 3_000));
        assertEquals("Readings are ordered by time, and an identical copy is a zero step", Map.of(V1, 80L), FameGains.byVisit(samples, SESSION, COVERED));
        assertEquals(Map.of(), FameGains.byVisit(List.of(), SESSION, COVERED));
        assertEquals(Optional.empty(), FameGains.of(Map.of(), null));
    }

    @Test public void aCaptureGapBeforeTheTaggedReadingMakesItsGainUnknownWhileOneIntervalCounts() {
        // Capture stops at 2,000 and restarts at 5,000; fame rose while nothing was recorded.
        SessionStore.ModuleAvailability gap = runs(false, interval(1_000, 2_000), interval(5_000, 9_000));
        assertEquals("Predecessor and tagged reading in different intervals: the offline gain is not this run's", Map.of(),
            FameGains.byVisit(List.of(untagged(1, 100, 1_500), in(V1, 1, 400, 6_000)), SESSION, gap));
        assertEquals("A reading inside no interval (the gap itself) cannot be placed", Map.of(),
            FameGains.byVisit(List.of(untagged(1, 100, 1_500), in(V1, 1, 150, 3_000)), SESSION, gap));
        assertEquals("Both readings in the second interval: counted", Map.of(V2, 20L),
            FameGains.byVisit(List.of(untagged(1, 100, 1_500), untagged(1, 400, 5_500), in(V2, 1, 420, 6_000)), SESSION, gap));
        assertEquals("One unplaceable step hides the whole visit", Map.of(),
            FameGains.byVisit(List.of(untagged(1, 100, 1_500), in(V1, 1, 400, 6_000), in(V1, 1, 450, 7_000)), SESSION, gap));
    }

    @Test public void theOpenIntervalOfALiveSessionCountsAfterTheLastPersistedInterval() {
        // The current interval is persisted only every so often: readings after the last saved until are in that open interval.
        SessionStore.ModuleAvailability live = runs(false, interval(1_000, 2_000));
        assertEquals(Map.of(V1, 30L), FameGains.byVisit(List.of(untagged(1, 100, 3_000), in(V1, 1, 130, 4_000)), SESSION, live));
        assertEquals("A persisted interval and the open one are two intervals", Map.of(),
            FameGains.byVisit(List.of(untagged(1, 100, 1_500), in(V1, 1, 130, 4_000)), SESSION, live));
    }

    @Test public void withoutSavedCoverageNoGainIsKnown() {
        List<AppHistory.FameSample> samples = List.of(untagged(1, 100, 1_000), in(V1, 1, 130, 2_000));
        assertEquals("No availability", Map.of(), FameGains.byVisit(samples, SESSION, null));
        assertEquals("Coverage unknown (histories before Wave 3, or unreadable evidence)", Map.of(), FameGains.byVisit(samples, SESSION,
            new SessionStore.ModuleAvailability(SessionStore.ModuleAvailability.State.UNKNOWN, "Recording coverage unknown", null, null)));
        assertEquals("No intervals", Map.of(), FameGains.byVisit(samples, SESSION,
            new SessionStore.ModuleAvailability(SessionStore.ModuleAvailability.State.PARTIAL, "Fixture", null, null, List.of(), false)));
    }

    @Test public void readingsBeforeTheFirstIntervalOfTruncatedCoverageAreUnknown() {
        // Older intervals were dropped: 1,000 and 1,500 may have been one interval, or not.
        SessionStore.ModuleAvailability truncated = runs(true, interval(2_000, 9_000));
        assertEquals(Map.of(), FameGains.byVisit(List.of(untagged(1, 100, 1_000), in(V1, 1, 130, 1_500)), SESSION, truncated));
        assertEquals("Inside the kept interval: counted", Map.of(V1, 30L),
            FameGains.byVisit(List.of(untagged(1, 100, 3_000), in(V1, 1, 130, 4_000)), SESSION, truncated));
    }
}
