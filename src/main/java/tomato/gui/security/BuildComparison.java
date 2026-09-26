package tomato.gui.security;

import tomato.gui.modern.DisplayFormat;
import tomato.gui.modern.Evidence;
import tomato.history.link.VisitRef;

import java.util.*;

/**
 * Detached comparison of two recorded builds (INS-3). Every value is copied when a build is pinned, so later
 * roster refreshes cannot change a comparison. Missing fields stay Not captured, a class change is stated
 * before any stat/equipment row, each DPS value keeps its own recorded window, and a DPS difference is never
 * attributed to equipment.
 */
public final class BuildComparison {
    static final String[] STATS = {"HP", "MP", "Attack", "Defense", "Speed", "Dexterity", "Vitality", "Wisdom"};
    static final String[] SLOTS = {"Weapon", "Ability", "Armor", "Ring"};
    public static final String NO_GEAR_ATTRIBUTION = "DPS differences are not attributed to equipment: party, enemies, uptime, "
        + "positioning and each recording's hit window also differ, and neither recording isolates gear.";

    /** One pinned build; all arrays are private copies. */
    public static final class Build {
        public final String name, className, origin, map, outcome;
        public final int classId;
        public final Integer level;
        /** Base stats; -1 means Not captured. */
        final int[] baseStats;
        /** Equipment IDs; null means Not captured and -1 means Empty. */
        final Integer[] equipment;
        final String[] equipmentNames;
        /** Producer build/change time (epoch ms), 0 when not captured. Never the pin or dialog time. */
        public final long recordedAt;
        public final VisitRef source;
        public final Long damage, windowStart, windowEnd;

        public Build(String name, String className, int classId, Integer level, int[] baseStats, Integer[] equipment, String[] equipmentNames,
                     long recordedAt, VisitRef source, String origin, String map, String outcome, Long damage, Long windowStart, Long windowEnd) {
            this.name = name == null || name.isEmpty() ? "Unknown player" : name;
            this.className = className == null ? "Unknown class" : className; this.classId = classId; this.level = level;
            this.baseStats = new int[8]; Arrays.fill(this.baseStats, -1);
            if (baseStats != null) System.arraycopy(baseStats, 0, this.baseStats, 0, Math.min(8, baseStats.length));
            this.equipment = new Integer[4]; this.equipmentNames = new String[4];
            for (int i = 0; i < 4; i++) {
                this.equipment[i] = equipment == null || i >= equipment.length ? null : equipment[i];
                this.equipmentNames[i] = equipmentNames == null || i >= equipmentNames.length ? null : equipmentNames[i];
            }
            this.recordedAt = Math.max(0, recordedAt); this.source = source;
            this.origin = origin == null ? "Source not supplied" : origin; this.map = map; this.outcome = outcome;
            this.damage = damage;
            boolean window = windowStart != null && windowEnd != null && windowEnd > windowStart;
            this.windowStart = window ? windowStart : null; this.windowEnd = window ? windowEnd : null;
        }
        /** Recorded DPS over this build's own window, or null when damage or the window is unknown. */
        public Double dps() { return damage == null || windowStart == null ? null : damage * 1000.0 / (windowEnd - windowStart); }
        String window() {
            return windowStart == null ? "window unknown" : "window " + DisplayFormat.formatTimestamp(windowStart) + " – " + DisplayFormat.formatTimestamp(windowEnd)
                + " (" + DisplayFormat.formatDurationSeconds(windowEnd - windowStart, 1) + " s, shared first-to-last recorded hit)";
        }
    }

    /** One comparison row: field, baseline, candidate and an explanatory note. */
    public static final class Line {
        public final String field, baseline, candidate, note;
        Line(String field, String baseline, String candidate, String note) { this.field = field; this.baseline = baseline; this.candidate = candidate; this.note = note; }
    }

    public final Build baseline, candidate;
    public final List<Line> lines;
    public final boolean classChanged;

    public BuildComparison(Build baseline, Build candidate) {
        this.baseline = Objects.requireNonNull(baseline); this.candidate = Objects.requireNonNull(candidate);
        classChanged = baseline.classId != candidate.classId;
        List<Line> rows = new ArrayList<>();
        rows.add(new Line("Source", source(baseline), source(candidate), "Exact recorded source of each build"));
        rows.add(new Line("Build/change time", time(baseline.recordedAt), time(candidate.recordedAt), "Producer observation time; not when it was pinned"));
        rows.add(new Line("Class", baseline.className, candidate.className, classChanged ? "Class changed: stats and equipment are not like-for-like" : "Same class"));
        rows.add(new Line("Level", text(baseline.level), text(candidate.level), delta(baseline.level, candidate.level)));
        for (int i = 0; i < 8; i++) {
            Integer a = baseline.baseStats[i] < 0 ? null : baseline.baseStats[i], b = candidate.baseStats[i] < 0 ? null : candidate.baseStats[i];
            rows.add(new Line("Base " + STATS[i], text(a), text(b), classChanged ? "Different class caps" : delta(a, b)));
        }
        for (int i = 0; i < 4; i++) {
            String a = item(baseline, i), b = item(candidate, i);
            rows.add(new Line(SLOTS[i], a, b, baseline.equipment[i] == null || candidate.equipment[i] == null ? "Missing on one side; not compared"
                : Objects.equals(baseline.equipment[i], candidate.equipment[i]) ? "Same item" : "Different item"));
        }
        rows.add(new Line("Outcome", Objects.toString(baseline.outcome, "Not recorded"), Objects.toString(candidate.outcome, "Not recorded"), "Recorded run outcome evidence"));
        rows.add(new Line("Recorded damage", number(baseline.damage), number(candidate.damage), "Each within its own recording"));
        rows.add(new Line("Recorded DPS", dps(baseline), dps(candidate), dpsNote()));
        lines = Collections.unmodifiableList(rows);
    }

    private String dpsNote() {
        Double a = baseline.dps(), b = candidate.dps();
        if (a == null || b == null) return "Unavailable on at least one side (damage or hit window not recorded); no difference computed";
        return "Difference " + (b - a >= 0 ? "+" : "") + DisplayFormat.formatNumber(b - a, 0, 1) + " DPS between different windows; not attributed to gear";
    }

    /** Readable report including both DPS windows and the no-attribution statement. */
    public String summary() {
        StringBuilder text = new StringBuilder();
        text.append("Baseline: ").append(baseline.name).append(" · ").append(baseline.className).append(" · ").append(source(baseline)).append('\n');
        text.append("Candidate: ").append(candidate.name).append(" · ").append(candidate.className).append(" · ").append(source(candidate)).append('\n');
        if (classChanged) text.append("Class changed (").append(baseline.className).append(" → ").append(candidate.className).append("): stat and equipment rows are not like-for-like.\n");
        int missing = 0;
        for (Line line : lines) if (line.baseline.equals(Evidence.Coverage.NOT_CAPTURED.toString()) || line.candidate.equals(Evidence.Coverage.NOT_CAPTURED.toString())) missing++;
        text.append("Fields not captured on at least one side: ").append(missing).append('\n');
        text.append("Baseline DPS ").append(dps(baseline)).append(" · ").append(baseline.window()).append('\n');
        text.append("Candidate DPS ").append(dps(candidate)).append(" · ").append(candidate.window()).append('\n');
        text.append(NO_GEAR_ATTRIBUTION);
        return text.toString();
    }

    static String source(Build build) {
        String where = build.source == null ? build.origin : "session " + build.source.sessionId + " · visit " + build.source.visitId;
        return (build.map == null ? "" : build.map + " · ") + where;
    }
    private static String time(long millis) { return millis > 0 ? DisplayFormat.formatTimestamp(millis) : Evidence.Coverage.NOT_CAPTURED.toString(); }
    private static String text(Integer value) { return value == null ? Evidence.Coverage.NOT_CAPTURED.toString() : Integer.toString(value); }
    private static String number(Long value) { return value == null ? "Not recorded" : DisplayFormat.formatInteger(value); }
    private static String dps(Build build) { Double value = build.dps(); return value == null ? "Unavailable" : DisplayFormat.formatNumber(value, 0, 1); }
    private static String delta(Integer a, Integer b) {
        if (a == null || b == null) return "Missing on one side; not compared";
        return a.equals(b) ? "Same" : (b - a > 0 ? "+" : "") + (b - a);
    }
    private static String item(Build build, int slot) {
        Integer id = build.equipment[slot];
        if (id == null) return Evidence.Coverage.NOT_CAPTURED.toString();
        if (id < 0) return "Empty";
        String name = build.equipmentNames[slot];
        return (name == null || name.isEmpty() ? "Item" : name) + " (ID " + id + ")";
    }
}
