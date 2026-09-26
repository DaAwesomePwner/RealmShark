package tomato.gui.stats;

import java.io.IOException;
import java.util.*;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.stats.LootQuery.*;
import tomato.history.SessionStore;
import tomato.history.archive.*;

/**
 * STAT-3 controlled A/B comparison. Baseline and candidate share the same dungeon, outcome, query-bounds and
 * loot-coverage predicates; they differ only by their explicit session choice and visit-entry bounds. Eligibility,
 * exclusions, unassigned drops and rates reuse {@link StatisticsArchiveAdapter}'s cohort semantics
 * ({@link HistoricalStatistics.Profile}): run-only imports and sessions without saved loot evidence are excluded,
 * zero-loot runs stay in the denominator, and any unassigned bag makes rates unavailable. A zero or unavailable
 * baseline never produces a percentage change.
 */
public final class CohortArchiveAdapter implements ArchiveAdapter<Row,Facets,Sort> {
    static final int BUCKETS = 10;
    private final Map<String,Count> counts = new LinkedHashMap<>();
    private ArchiveDefinitions definitions;
    public CohortArchiveAdapter(ArchiveQuery<Facets,Sort> q) { }
    public Class<Row> rowType() { return Row.class; }
    public String unit() { return "cohort summaries"; }
    public List<ReadSnapshot.Source> sources(SessionStore store, ArchiveQuery<Facets,Sort> q) {
        String scope = q.resolvedScope(store);
        return Arrays.asList(new ReadSnapshot.Source(scope, "runs"), new ReadSnapshot.Source(scope, "loot"));
    }
    public void validate(ArchiveQuery<Facets,Sort> q) { q.facets().validate(); if (q.facets().view != View.COHORTS) throw new IllegalArgumentException("Not an A/B cohort view"); }
    public boolean matches(ArchiveRow<Row> row, ArchiveQuery<Facets,Sort> q) { return true; }
    public boolean inBounds(ArchiveRow<Row> row, ArchiveQuery<Facets,Sort> q) { return true; }
    public Long time(ArchiveRow<Row> row) { return row.value.time; }
    public Comparator<Row> comparator(Sort sort) { return LootQuery.comparator(sort); }
    public Map<String,Count> counts() { return counts; }
    public Map<String,String> dependencies() {
        Map<String,String> values = new LinkedHashMap<>();
        values.put("cohort-comparison", "v1: shared dungeon/outcome/bounds/coverage predicates; per-cohort sessions and visit-entry bounds; statistics-cohort eligibility; zero-loot runs in denominators; unassigned bags invalidate rates; no percentage from a zero or unavailable baseline");
        if (definitions != null) values.put("asset-generation", definitions.description());
        return values;
    }

    /** One side of the comparison; the Profile carries the shared denominator semantics. */
    static final class Side {
        final String label; final Cohort cohort;
        final HistoricalStatistics.Profile profile = new HistoricalStatistics.Profile();
        final Map<String,Long> perRun = new HashMap<>();
        Side(String label, Cohort cohort) { this.label = label; this.cohort = cohort; }
        boolean rated() { return profile.lootEvidence && profile.unassignedBags == 0 && profile.runs > 0; }
    }
    private static final class Run { final String map; final boolean[] member; Run(String map, boolean[] member) { this.map = map; this.member = member; } }

    public void scan(ReadSnapshot pin, ArchiveQuery<Facets,Sort> q, Sink<Row> out, Cancellation cancel) throws IOException {
        counts.clear(); definitions = new ArchiveDefinitions(); Facets f = q.facets();
        for (String id : pin.sessionIds()) {
            SessionStore.Session meta = pin.session(id); LootArchiveAdapter.label(meta.label);
            counts.put("facet.session." + id, new Count(0, "sessions", meta.toString()));
        }
        if (f.baseline == null || f.candidate == null) {
            counts.put("cohorts chosen", new Count((f.baseline == null ? 0 : 1) + (f.candidate == null ? 0 : 1), "cohorts", "choose both a baseline and a candidate cohort"));
            return;
        }
        Outcome outcome = f.outcome == null ? Outcome.ANY : f.outcome;
        Side[] sides = {new Side("Baseline", f.baseline), new Side("Candidate", f.candidate)};
        Set<String> evidence = new HashSet<>();
        pin.read("loot", LootDashboard.Drop.class, row -> { LootArchiveAdapter.checkDrop(row.value); evidence.add(row.ref.session); LootArchiveAdapter.bounded(evidence.size(), LootArchiveAdapter.MAX_KEYS, "Sessions with loot evidence"); }, cancel);
        Map<String,Run> runs = new HashMap<>(); long[] overlap = {0};
        pin.read("runs", ActivityJournal.Visit.class, source -> {
            cancel.check(); ActivityJournal.Visit visit = source.value; LootArchiveAdapter.label(visit.map); LootArchiveAdapter.label(visit.id);
            if (!definitions.dungeon(visit.map)) return;
            String map = definitions.canonical(visit.map), session = source.ref.session;
            boolean shared = f.dungeon(map) && outcome.matches(visit.runStatus()) && q.bounds().contains(LootQuery.time(visit.started), LootQuery.time(visit.lastSeen));
            boolean[] member = new boolean[2]; int memberships = 0;
            for (int i = 0; i < 2; i++) if (shared && sides[i].cohort.contains(session, visit.started)) { member[i] = true; memberships++; }
            runs.put(session + "/" + visit.id, new Run(map, member)); LootArchiveAdapter.bounded(runs.size(), LootArchiveAdapter.MAX_VISITS, "Qualified visit joins");
            if (memberships == 2) overlap[0]++;
            boolean imported = "Imported".equals(pin.session(session).version);
            for (int i = 0; i < 2; i++) if (member[i]) {
                HistoricalStatistics.Profile p = sides[i].profile;
                if (imported) p.excludedRuns++;
                else if (!evidence.contains(session)) { p.unknownRuns++; p.unknownMillis += visit.observedMillis(); }
                else { p.lootEvidence = true; StatisticsArchiveAdapter.addVisit(p, visit); sides[i].perRun.put(session + "/" + visit.id, 0L); }
            }
        }, cancel);
        pin.read("loot", LootDashboard.Drop.class, source -> {
            cancel.check(); LootDashboard.Drop drop = source.value; String session = source.ref.session, map = definitions.canonical(drop.dungeon);
            String key = session + "/" + drop.visitId; Run run = drop.visitId == null || drop.visitId.isEmpty() ? null : runs.get(key);
            boolean agreement = run != null && map.equals(run.map), imported = "Imported".equals(pin.session(session).version);
            for (int i = 0; i < 2; i++) {
                Side side = sides[i];
                if (agreement) {
                    if (!run.member[i]) continue;
                    boolean linked = !imported && side.perRun.containsKey(key);
                    side.profile.add(drop, linked);
                    if (linked) { side.profile.lootRuns.add(key); side.perRun.merge(key, (long)drop.items.size(), Long::sum); }
                } else if (definitions.dungeon(drop.dungeon) && f.dungeon(map) && q.bounds().contains(LootQuery.time(drop.time), LootQuery.time(drop.time))
                        && side.cohort.contains(session, drop.time)) side.profile.add(drop, false);
            }
        }, cancel);
        Row[] rows = new Row[2];
        for (int i = 0; i < 2; i++) { rows[i] = cohortRow(sides[i]); out.accept(new ArchiveRow<>(ref(pin, "cohort:" + (i + 1) + ":" + sides[i].label), rows[i])); }
        out.accept(new ArchiveRow<>(ref(pin, "cohort:3:delta"), delta(rows[0], rows[1], overlap[0])));
        for (int i = 0; i < 2; i++) {
            long[] buckets = new long[BUCKETS + 1];
            for (long items : sides[i].perRun.values()) buckets[(int)Math.min(BUCKETS, items)]++;
            for (int b = 0; b <= BUCKETS; b++) {
                Row r = new Row(); r.type = "distribution"; r.name = sides[i].label + " · " + (b == BUCKETS ? BUCKETS + "+" : Integer.toString(b)) + " items per run";
                r.runs = sides[i].rated() ? buckets[b] : null; r.items = b == BUCKETS ? null : (long)b;
                r.evidence = sides[i].rated() ? "Eligible runs in " + sides[i].label.toLowerCase(Locale.ROOT) + " with this many observed items (linked bags only). Zero-loot runs are the 0 bucket."
                    : "Distribution unavailable: " + RateCalculation.perRunUnavailable(rows[i]) + ".";
                out.accept(new ArchiveRow<>(ref(pin, "cohort:" + (4 + i) + ":" + String.format(Locale.ROOT, "%02d", b)), r));
            }
        }
        counts.put("overlapping runs", new Count(overlap[0], "runs", "runs matching both cohorts; they count in both"));
        counts.put("baseline eligible runs", new Count(sides[0].profile.runs, "runs", "shared predicates + baseline cohort"));
        counts.put("candidate eligible runs", new Count(sides[1].profile.runs, "runs", "shared predicates + candidate cohort"));
        definitions.check();
    }
    private static ArchiveRow.Ref ref(ReadSnapshot pin, String locator) {
        return new ArchiveRow.Ref(pin.sessionIds().size() == 1 ? pin.sessionIds().iterator().next() : SessionStore.ALL, "statistics", locator, "");
    }
    private static Row cohortRow(Side side) {
        Row r = StatisticsArchiveAdapter.profile(side.profile); r.type = "cohort"; r.name = r.dungeon = side.label; r.dungeon = "";
        if (side.rated()) {
            List<Long> values = new ArrayList<>(side.perRun.values()); Collections.sort(values);
            r.minPerRun = values.get(0); r.maxPerRun = values.get(values.size() - 1);
            int n = values.size(); r.medianPerRun = n % 2 == 1 ? (double)values.get(n / 2) : (values.get(n / 2 - 1) + values.get(n / 2)) / 2.0;
        }
        r.evidence = side.label + " cohort: " + describe(side.cohort) + ". " + RateCalculation.describe(r).replace("Rate calculation · " + side.label + "\n", "") + "\n" + side.profile.explanation();
        return r;
    }
    private static String describe(Cohort c) {
        return (c.sessions.isEmpty() ? "every session in scope" : c.sessions.size() + " chosen session(s)")
            + (c.from == null && c.until == null ? "" : ", visit entry [" + (c.from == null ? "any" : DisplayFormat.formatTimestamp(c.from)) + ", " + (c.until == null ? "any" : DisplayFormat.formatTimestamp(c.until)) + ")");
    }
    /** Absolute deltas always; relative change only from a positive, available baseline. */
    static Double change(Double baseline, Double candidate) { return baseline == null || candidate == null || baseline == 0 ? null : (candidate - baseline) * 100.0 / baseline; }
    static String changeReason(String metric, Double baseline, Double candidate) {
        if (baseline == null) return "No percentage change for " + metric + ": baseline rate unavailable.";
        if (candidate == null) return "No percentage change for " + metric + ": candidate rate unavailable.";
        if (baseline == 0) return "No percentage change for " + metric + ": baseline is zero (the change is not 0% and not infinite); compare absolute values.";
        return metric + " change: " + DisplayFormat.formatNumber(change(baseline, candidate), 1) + "% of baseline.";
    }
    private static Row delta(Row a, Row b, long overlap) {
        Row r = new Row(); r.type = "delta"; r.name = "Candidate − baseline";
        r.runs = b.runs - a.runs; r.millis = b.millis - a.millis;
        r.items = a.items == null || b.items == null ? null : b.items - a.items;
        r.perRun = a.perRun == null || b.perRun == null ? null : b.perRun - a.perRun;
        r.perHour = a.perHour == null || b.perHour == null ? null : b.perHour - a.perHour;
        r.perRunChange = change(a.perRun, b.perRun); r.perHourChange = change(a.perHour, b.perHour);
        r.evidence = "Totals and denominators: baseline " + DisplayFormat.formatInteger(a.items) + " items / " + DisplayFormat.formatInteger(a.runs) + " eligible runs / "
            + DisplayFormat.formatInteger(a.millis) + " ms; candidate " + DisplayFormat.formatInteger(b.items) + " items / " + DisplayFormat.formatInteger(b.runs) + " eligible runs / "
            + DisplayFormat.formatInteger(b.millis) + " ms. Totals depend on cohort size; compare the per-run and per-hour rates.\n"
            + changeReason("Items / run", a.perRun, b.perRun) + "\n" + changeReason("Items / hour", a.perHour, b.perHour)
            + (overlap > 0 ? "\n" + overlap + " run(s) match both cohorts and count in both; the comparison is not independent." : "");
        return r;
    }
}
