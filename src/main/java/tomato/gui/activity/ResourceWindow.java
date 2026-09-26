package tomato.gui.activity;

import packets.data.enums.ConditionBits;
import packets.data.enums.ConditionNewBits;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.modern.DisplayFormat;

import java.util.*;

/**
 * Detached analysis of one selected half-open window [from, until) of a recorded visit (COMBAT-5). Raw HP and
 * MP extrema come only from samples inside the window (no per-sample maxima exist, so no percentage of max).
 * Condition intervals are clipped to the window and unioned per flag family: observed time is where that
 * family's flags were recorded, active time is where a flag was set, and the rest of the window is unknown.
 * Observed uptime = active / observed; unknown time never counts as inactive.
 */
public final class ResourceWindow {
    public final long from, until;
    public final Extrema hp, mp;
    public final List<Lane> lanes;
    /** Observed milliseconds per family (primary, extra) inside the window. */
    public final long primaryObserved, extraObserved;

    public static final class Extrema {
        public final Integer min, max;
        public final int samples, missing;
        Extrema(Integer min, Integer max, int samples, int missing) { this.min = min; this.max = max; this.samples = samples; this.missing = missing; }
        public String describe(String name) {
            if (samples == 0) return name + ": no samples in this window" + (missing > 0 ? " (" + missing + " without a recorded value)" : "");
            return name + " " + DisplayFormat.formatExact(min) + " – " + DisplayFormat.formatExact(max) + " (raw, " + samples + " samples"
                + (missing > 0 ? ", " + missing + " without a value" : "") + ")";
        }
    }

    public static final class Lane {
        public final String name;
        public final boolean extra;
        public final long active, observed, unknown;
        Lane(String name, boolean extra, long active, long observed, long unknown) {
            this.name = name; this.extra = extra; this.active = active; this.observed = observed; this.unknown = unknown;
        }
        /** Active share of observed time in percent, or null when nothing in the window was observed. */
        public Double observedUptime() { return observed <= 0 ? null : active * 100.0 / observed; }
    }

    private ResourceWindow(long from, long until, Extrema hp, Extrema mp, List<Lane> lanes, long primaryObserved, long extraObserved) {
        this.from = from; this.until = until; this.hp = hp; this.mp = mp; this.lanes = Collections.unmodifiableList(lanes);
        this.primaryObserved = primaryObserved; this.extraObserved = extraObserved;
    }

    public long length() { return until - from; }

    /**
     * Analyses {@code [from, until)}. Lanes are every flag recorded for the visit plus any flag active in the
     * window; with {@code allFlags} every known flag of an observed family is listed, including zero-active lanes.
     */
    public static ResourceWindow of(ActivityJournal.Visit visit, long from, long until, boolean allFlags) {
        if (until <= from) throw new IllegalArgumentException("Expected from < until");
        Integer hpMin = null, hpMax = null, mpMin = null, mpMax = null; int hpCount = 0, mpCount = 0, hpMissing = 0, mpMissing = 0;
        for (ActivityJournal.ResourcePoint p : visit.resourceTimeline) {
            if (p.time < from || p.time >= until) continue;
            if (p.hp == null) hpMissing++; else { hpCount++; hpMin = hpMin == null ? p.hp : Math.min(hpMin, p.hp); hpMax = hpMax == null ? p.hp : Math.max(hpMax, p.hp); }
            if (p.mp == null) mpMissing++; else { mpCount++; mpMin = mpMin == null ? p.mp : Math.min(mpMin, p.mp); mpMax = mpMax == null ? p.mp : Math.max(mpMax, p.mp); }
        }
        long primaryObserved = union(visit.conditionTimeline, from, until, false, 0);
        long extraObserved = union(visit.conditionTimeline, from, until, true, 0);
        List<Lane> lanes = new ArrayList<>();
        for (ConditionBits bit : ConditionBits.values()) {
            long active = union(visit.conditionTimeline, from, until, false, bit.value());
            if (active > 0 || visit.conditions.containsKey(bit.name()) || allFlags && primaryObserved > 0)
                lanes.add(new Lane(bit.name(), false, active, primaryObserved, (until - from) - primaryObserved));
        }
        for (ConditionNewBits bit : ConditionNewBits.values()) {
            long active = union(visit.conditionTimeline, from, until, true, bit.value());
            if (active > 0 || visit.extraConditions.containsKey(bit.name()) || allFlags && extraObserved > 0)
                lanes.add(new Lane(bit.name(), true, active, extraObserved, (until - from) - extraObserved));
        }
        return new ResourceWindow(from, until, new Extrema(hpMin, hpMax, hpCount, hpMissing), new Extrema(mpMin, mpMax, mpCount, mpMissing),
            lanes, primaryObserved, extraObserved);
    }

    /** Union length of slices clipped to [from, until) whose family mask is recorded and, when {@code bit} != 0, has that bit. */
    static long union(List<ActivityJournal.ConditionSlice> slices, long from, long until, boolean extra, int bit) {
        List<long[]> intervals = new ArrayList<>();
        for (ActivityJournal.ConditionSlice slice : slices) {
            Integer mask = extra ? slice.secondary : slice.primary;
            if (mask == null || bit != 0 && (mask & bit) == 0) continue;
            long start = Math.max(from, slice.start), end = Math.min(until, slice.end);
            if (end > start) intervals.add(new long[]{start, end});
        }
        intervals.sort(Comparator.comparingLong(interval -> interval[0]));
        long total = 0, currentStart = Long.MIN_VALUE, currentEnd = Long.MIN_VALUE;
        for (long[] interval : intervals) {
            if (interval[0] > currentEnd) { if (currentEnd > currentStart) total += currentEnd - currentStart; currentStart = interval[0]; currentEnd = interval[1]; }
            else currentEnd = Math.max(currentEnd, interval[1]);
        }
        if (currentEnd > currentStart) total += currentEnd - currentStart;
        return total;
    }

    /** Plain-language summary: window, raw extrema and per-family coverage. */
    public String summary(String label) {
        StringBuilder text = new StringBuilder(label).append(" · ").append(DisplayFormat.formatDurationSeconds(length(), 1)).append(" s half-open window\n");
        text.append(hp.describe("HP")).append(" · ").append(mp.describe("MP")).append(". Percentage of maximum is unavailable: per-sample maxima are not recorded.\n");
        text.append("Condition coverage: primary flags observed ").append(DisplayFormat.formatDurationSeconds(primaryObserved, 1)).append(" s, unknown ")
            .append(DisplayFormat.formatDurationSeconds(length() - primaryObserved, 1)).append(" s; extra flags observed ")
            .append(DisplayFormat.formatDurationSeconds(extraObserved, 1)).append(" s, unknown ").append(DisplayFormat.formatDurationSeconds(length() - extraObserved, 1))
            .append(" s. Uptime = active / observed; unknown time is not counted as inactive.");
        return text.toString();
    }
}
