package tomato.gui.runs;

import java.util.*;
import tomato.history.AppHistory;
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
 * <p>A visit's gain is known only when every reading tagged with it has a predecessor in the same session and series: a reading
 * that opens its series (or has no time to be placed by) hides the gain before it, so that visit is absent from the result —
 * unknown, never zero and never a partial sum. A visit whose readings all follow a predecessor without an increase is a real
 * zero. Untagged readings are baselines for the next reading but are never credited. Readings tagged with another session's
 * visit are ignored entirely (the producer never tags a reading with another session's visit, so they cannot belong here).
 * An exact copy of a reading (a {@code fame-latest} checkpoint read beside {@code fame}) is a zero step and counts once.
 */
public final class FameGains {
    private FameGains() {}

    /**
     * Known gains by exact visit of {@code session}, from its readings ({@code fame}, optionally with {@code fame-latest}); readings
     * of other sessions must not be mixed in untagged. Visits whose gain is unknown are absent.
     */
    public static Map<VisitRef, Long> byVisit(List<AppHistory.FameSample> samples, String session) {
        Objects.requireNonNull(session, "session");
        if (samples == null || samples.isEmpty()) return Map.of();
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
            for (int i = 0; i < readings.size(); i++) {
                VisitRef visit = readings.get(i).visit();
                if (visit == null) continue;
                if (i == 0) { unknown.add(visit); continue; }   // no reading before it in this series: its step is not known
                long step = readings.get(i).fame - readings.get(i - 1).fame;
                gains.merge(visit, Math.max(0, step), Long::sum);
            }
        }
        gains.keySet().removeAll(unknown);
        return Map.copyOf(gains);
    }

    /** The known gain of {@code ref}, or empty when it is unknown (or {@code ref} is null). */
    public static Optional<Long> of(Map<VisitRef, Long> gains, VisitRef ref) {
        return gains == null || ref == null ? Optional.empty() : Optional.ofNullable(gains.get(ref));
    }
}
