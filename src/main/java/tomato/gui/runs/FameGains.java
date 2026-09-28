package tomato.gui.runs;

import java.util.*;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.history.link.VisitRef;

/**
 * Fame gained per run, from one session's saved fame readings. Pure; no I/O.
 *
 * <p>Readings are appended only when a character's fame changes ({@code AppHistory.record}), so a visit rarely holds the
 * reading taken before it began: "last − first" of the readings tagged with a visit (spec §8.5) loses its first increase.
 * Instead, each series (one account, character id and class inside the session, as Home's totals group them) is put in time
 * order, and a positive step from reading <i>a</i> to the next reading <i>b</i> is credited to <i>b</i>'s exact visit
 * ({@link AppHistory.FameSample#visit()}), whatever <i>a</i> is tagged with. Negative steps are not gains.
 *
 * <p>A step is an observation inside the run only when capture ran without a break from <i>a</i> to <i>b</i>: fame earned while
 * capture was stopped would otherwise land on the first run after the restart (entering a dungeon sends the player's full
 * stats, which appends a reading tagged with that run). So both readings must fall inside the same recorded capture interval
 * of the session's {@code runs} coverage ({@link SessionStore.ModuleAvailability#intervals}, which the collector closes on
 * capture stop, restart, interruption, collection off and frame gaps). Two readings after the last persisted interval are in
 * the live session's open interval (it is persisted only every so often) and match. A reading in a gap, readings in different
 * intervals, a reading before the first interval of truncated coverage, or no interval evidence at all make the step unknown.
 *
 * <p>A visit's gain is known only when every reading tagged with it has a predecessor in the same session and series, inside
 * one unbroken capture interval with it: a reading that opens its series, follows a gap or cannot be placed (no time) hides the
 * gain before it, so that visit is absent from the result — unknown, never zero and never a partial sum. A visit whose readings
 * all follow such a predecessor without an increase is a real zero. Untagged readings are baselines for the next reading but are
 * never credited. Readings tagged with another session's visit are ignored entirely (the producer never tags a reading with
 * another session's visit, so they cannot belong here). An exact copy of a reading (a {@code fame-latest} checkpoint read beside
 * {@code fame}) is a zero step and counts once.
 */
public final class FameGains {
    private FameGains() {}

    /**
     * Known gains by exact visit of {@code session}, from its readings ({@code fame}, optionally with {@code fame-latest}); readings
     * of other sessions must not be mixed in untagged. {@code runs} is that session's saved coverage of the {@code runs} module
     * ({@code SessionEntry.availability("runs")}); null or without intervals, no gain is known. Unknown visits are absent.
     */
    public static Map<VisitRef, Long> byVisit(List<AppHistory.FameSample> samples, String session, SessionStore.ModuleAvailability runs) {
        Objects.requireNonNull(session, "session");
        if (samples == null || samples.isEmpty()) return Map.of();
        // No usable interval evidence: no step can be shown to lie inside one capture interval, so no gain is known.
        if (runs == null || !runs.valid() || runs.intervals == null || runs.intervals.isEmpty()) return Map.of();
        List<SessionStore.Interval> intervals = runs.intervals;
        Map<List<Object>, List<AppHistory.FameSample>> series = new HashMap<>();
        Set<VisitRef> unknown = new HashSet<>();
        for (AppHistory.FameSample sample : samples) {
            if (sample == null) continue;
            VisitRef visit = sample.visit();
            if (visit != null && !session.equals(visit.sessionId)) continue;   // another session's reading
            if (sample.time <= 0) { if (visit != null) unknown.add(visit); continue; }   // cannot be ordered
            // Gson fills the fields without the constructor, so the saved account is used as is (Home's totals do the same).
            series.computeIfAbsent(Arrays.asList(sample.account, sample.character, sample.className), key -> new ArrayList<>()).add(sample);
        }
        Map<VisitRef, Long> gains = new HashMap<>();
        for (List<AppHistory.FameSample> readings : series.values()) {
            readings.sort(Comparator.comparingLong((AppHistory.FameSample s) -> s.time).thenComparingLong(s -> s.fame));   // Home's order
            int previous = UNPLACED;
            for (int i = 0; i < readings.size(); i++) {
                int place = place(readings.get(i).time, intervals, runs.truncated);
                VisitRef visit = readings.get(i).visit();
                if (visit != null) {
                    // No reading before it in this series, or a capture gap (or unknown coverage) between the two: not known.
                    if (i == 0 || place == UNPLACED || place != previous) unknown.add(visit);
                    else gains.merge(visit, Math.max(0, readings.get(i).fame - readings.get(i - 1).fame), Long::sum);
                }
                previous = place;
            }
        }
        gains.keySet().removeAll(unknown);
        return Map.copyOf(gains);
    }

    /** {@link #place}: after the last persisted interval (the live session's open interval), or in no known interval. */
    private static final int OPEN = -1, UNPLACED = -2;

    /**
     * Where a reading falls in the saved capture intervals (oldest first, bounds inclusive as {@code recordedAt}): the index of
     * the interval holding it; {@link #OPEN} after the last one's {@code until}; {@link #UNPLACED} in no interval before that (a
     * gap, or before the first; with {@code truncated}, older intervals were dropped, so coverage there is unknown).
     */
    private static int place(long time, List<SessionStore.Interval> intervals, boolean truncated) {
        if (truncated && time < intervals.get(0).from) return UNPLACED;
        if (time > intervals.get(intervals.size() - 1).until) return OPEN;
        for (int i = 0; i < intervals.size(); i++) if (intervals.get(i).from <= time && time <= intervals.get(i).until) return i;
        return UNPLACED;
    }

    /** The known gain of {@code ref}, or empty when it is unknown (or {@code ref} is null). */
    public static Optional<Long> of(Map<VisitRef, Long> gains, VisitRef ref) {
        return gains == null || ref == null ? Optional.empty() : Optional.ofNullable(gains.get(ref));
    }
}
