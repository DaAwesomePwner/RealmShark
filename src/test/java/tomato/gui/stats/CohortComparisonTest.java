package tomato.gui.stats;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.history.*;
import tomato.gui.stats.LootQuery.*;
import tomato.history.*;
import tomato.history.archive.*;
import util.PreferencesStore;
import static org.junit.Assert.*;
import static tomato.gui.history.SessionPanelTest.named;
import static tomato.gui.stats.LootArchiveQueryTest.*;
import static tomato.gui.stats.LootDrillDownTest.await;
import static tomato.gui.stats.LootDrillDownTest.edt;

/** STAT-3: explicit baseline/candidate cohorts with shared predicates, totals, rates and distributions. Synthetic data only. */
public class CohortComparisonTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private Map<String,Row> compare(SessionStore store, Cohort baseline, Cohort candidate, Set<String> dungeons, Outcome outcome) throws Exception {
        Facets f = new Facets(); f.view = View.COHORTS; f.baseline = baseline; f.candidate = candidate; f.dungeons.addAll(dungeons); f.outcome = outcome;
        ArchiveQuery<Facets,Sort> q = LootQuery.initial(true).withScope(SessionStore.ALL).withFacets(f);
        Map<String,Row> rows = new LinkedHashMap<>();
        try (ArchiveResult<Row> result = ArchiveResult.open(store, q, new CohortArchiveAdapter(q), temp.newFolder().toPath(), new Cancellation())) {
            result.stream(ExportSelection.all(), row -> rows.put(row.value.name, row.value), new Cancellation());
        }
        return rows;
    }
    private static Cohort cohort(String... sessions) { return new Cohort(Arrays.asList(sessions), null, null); }
    private static void runs(SessionStore store, String prefix, int count, int itemsPerRun, long start) {
        for (int i = 0; i < count; i++) {
            ActivityJournal.Visit v = visit(prefix + i, "Ice Citadel", start + i * 120000L, 60000); v.status = "Completed"; store.put("runs", v.id, v);
            LootDashboard.Item[] items = new LootDashboard.Item[itemsPerRun];
            for (int k = 0; k < itemsPerRun; k++) items[k] = item(1, "A", "WEAPON,UT", "");
            store.append("loot", drop(start + i * 120000L + 1000, "Ice Citadel", "White", prefix + i, items));
        }
    }

    @Test public void tenRunVersusTwoRunCohortsExposeTotalsRatesAndDistributions() throws Exception {
        Path root = temp.newFolder().toPath(); String baselineId, unknownId;
        try (SessionStore a = new SessionStore(root, true, "baseline")) {
            baselineId = a.currentId(); runs(a, "a", 10, 2, 1000);
            a.put("runs", "lh", visit("lh", "Lost Halls", 5000000, 60000)); a.append("loot", drop(5001000, "Lost Halls", "White", "lh", item(9, "Other", "WEAPON,UT", "")));
            a.flush();
        }
        try (SessionStore unknown = new SessionStore(root, true, "no loot journal")) { unknownId = unknown.currentId(); unknown.put("runs", "u", visit("u", "Ice Citadel", 1000, 60000)); unknown.flush(); }
        try (SessionStore b = new SessionStore(root, true, "candidate")) {
            runs(b, "b", 2, 3, 1000); b.flush();
            Map<String,Row> rows = compare(b, cohort(baselineId, unknownId), cohort(b.currentId()), Collections.singleton("Ice Citadel"), null);
            Row base = rows.get("Baseline"), cand = rows.get("Candidate"), delta = rows.get("Candidate − baseline");
            assertEquals(10L, base.runs.longValue()); assertEquals(20L, base.items.longValue()); assertEquals(600000L, base.millis.longValue());
            assertEquals(2.0, base.perRun, 0); assertEquals(120.0, base.perHour, 0); assertEquals(1L, base.unknownRuns.longValue());
            assertEquals(2L, cand.runs.longValue()); assertEquals(6L, cand.items.longValue()); assertEquals(3.0, cand.perRun, 0); assertEquals(180.0, cand.perHour, 0);
            assertEquals(-14L, delta.items.longValue()); assertEquals(-8L, delta.runs.longValue());
            assertEquals(1.0, delta.perRun, 0); assertEquals(60.0, delta.perHour, 0); assertEquals(50.0, delta.perRunChange, 1e-9); assertEquals(50.0, delta.perHourChange, 1e-9);
            assertTrue(delta.evidence, delta.evidence.contains("Totals depend on cohort size"));
            assertEquals(2.0, base.medianPerRun, 0); assertEquals(2L, base.minPerRun.longValue()); assertEquals(3L, cand.maxPerRun.longValue());
            assertEquals(10L, rows.get("Baseline · 2 items per run").runs.longValue()); assertEquals(0L, rows.get("Baseline · 3 items per run").runs.longValue());
            assertEquals(2L, rows.get("Candidate · 3 items per run").runs.longValue());
            assertTrue(base.evidence, base.evidence.contains("Items / run = 20 ÷ 10 = 2"));
            // Shared predicates: the Lost Halls run belongs to neither cohort; a Completed-only outcome keeps all these runs.
            assertEquals(10L, compare(b, cohort(baselineId), cohort(b.currentId()), Collections.singleton("Ice Citadel"), Outcome.COMPLETED).get("Baseline").runs.longValue());
            assertEquals(0L, compare(b, cohort(baselineId), cohort(b.currentId()), Collections.singleton("Ice Citadel"), Outcome.NOT_COMPLETED).get("Baseline").runs.longValue());
            assertEquals(11L, compare(b, cohort(baselineId), cohort(b.currentId()), Collections.<String>emptySet(), null).get("Baseline").runs.longValue());
        }
    }

    @Test public void zeroBaselineHasNoInventedPercentageAndCohortBoundsSplitOneSession() throws Exception {
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "fixture")) {
            String id = store.currentId();
            ActivityJournal.Visit empty = visit("empty", "Ice Citadel", 1000, 60000); store.put("runs", "empty", empty);
            store.append("loot", drop(2000, "Ice Citadel", "Blue", "empty")); // Observed empty bag: evidence of zero.
            runs(store, "later", 2, 1, 10000000);
            store.flush();
            Map<String,Row> rows = compare(store, new Cohort(Collections.singleton(id), null, 5000000L), new Cohort(Collections.<String>emptySet(), 5000000L, null), Collections.<String>emptySet(), null);
            Row base = rows.get("Baseline"), cand = rows.get("Candidate"), delta = rows.get("Candidate − baseline");
            assertEquals(1L, base.runs.longValue()); assertEquals(0L, base.items.longValue()); assertEquals(0.0, base.perRun, 0); assertEquals(0L, base.zeroLootRuns.longValue()); // Its empty bag is linked.
            assertEquals(2L, cand.runs.longValue()); assertEquals(1.0, cand.perRun, 0);
            assertEquals(1.0, delta.perRun, 0); assertNull(delta.perRunChange); assertNull(delta.perHourChange);
            assertTrue(delta.evidence, delta.evidence.contains("No percentage change for Items / run: baseline is zero"));
            assertEquals(1L, rows.get("Baseline · 0 items per run").runs.longValue());
            // Overlapping cohorts are disclosed rather than silently double counted.
            Map<String,Row> overlap = compare(store, cohort(), cohort(id), Collections.<String>emptySet(), null);
            assertTrue(overlap.get("Candidate − baseline").evidence.contains("3 run(s) match both cohorts"));
        }
        try (SessionStore unknown = new SessionStore(temp.newFolder().toPath(), true, "unknown")) {
            unknown.put("runs", "u", visit("u", "Ice Citadel", 1000, 60000)); unknown.flush();
            Map<String,Row> rows = compare(unknown, cohort(), cohort(), Collections.<String>emptySet(), null);
            assertNull(rows.get("Baseline").items); assertNull(rows.get("Baseline").perRun); assertNull(rows.get("Candidate − baseline").perRunChange);
            assertTrue(rows.get("Candidate − baseline").evidence.contains("baseline rate unavailable"));
            assertNull(rows.get("Baseline · 0 items per run").runs);
            assertTrue(rows.get("Baseline · 0 items per run").evidence.contains("Distribution unavailable"));
        }
    }

    @Test public void unchosenCohortsProduceNoRowsAndSavedStateRoundTrips() throws Exception {
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "fixture")) {
            runs(store, "r", 1, 1, 1000); store.flush();
            Facets f = new Facets(); f.view = View.COHORTS;
            ArchiveQuery<Facets,Sort> q = LootQuery.initial(true).withFacets(f);
            try (ArchiveResult<Row> result = ArchiveResult.open(store, q, new CohortArchiveAdapter(q), temp.newFolder().toPath(), new Cancellation())) {
                assertEquals(0, result.matches);
                assertEquals(0, result.page(0, 10, new Cancellation()).counts.get("cohorts chosen").value);
                assertTrue(result.page(0, 10, new Cancellation()).counts.containsKey("facet.session." + store.currentId()));
            }
            f.baseline = new Cohort(Collections.singleton(store.currentId()), 1000L, 2000L); f.candidate = new Cohort(); f.outcome = Outcome.COMPLETED;
            ArchiveQuery<Facets,Sort> chosen = q.withFacets(f);
            assertEquals(chosen, q.restore(chosen.toJson()));
            f.candidate.from = 5L; f.candidate.until = 5L;
            try { f.validate(); fail(); } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("from < until")); }
        }
    }

    @Test public void statisticsWorkspaceEditsCohortsThroughQueryIntent() throws Exception {
        Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath();
        PreferencesStore prefs = new PreferencesStore(temp.getRoot().toPath().resolve("views.properties")); prefs.preload();
        ViewStateStore states = ViewStateStore.preferences(prefs);
        String first;
        try (SessionStore a = new SessionStore(root, true, "first")) { first = a.currentId(); runs(a, "a", 3, 1, 1000); a.flush(); }
        try (SessionStore store = new SessionStore(root, true, "second")) {
            runs(store, "b", 1, 4, 1000); store.flush();
            ArchiveWorkspace<Row,Facets,Sort> workspace = edt(() -> SessionPanel.queried(store, "statistics", new JLabel("Live"), new LootArchiveClient(scratch, true), states));
            try {
                edt(() -> { Facets f = workspace.state().query.facets(); f.view = View.COHORTS; workspace.changeQuery(workspace.state().query.withScope(SessionStore.ALL).withFacets(f)); return null; });
                await(() -> !workspace.loading() && workspace.displayedPage() != null && workspace.state().query.facets().view == View.COHORTS);
                edt(() -> {
                    JTabbedPane tabs = named(workspace, "loot-archive-tabs", JTabbedPane.class); assertTrue(tabs.indexOfTab("A/B cohorts") >= 0);
                    JList<?> baseline = named(workspace, "cohort-baseline-sessions", JList.class), candidate = named(workspace, "cohort-candidate-sessions", JList.class);
                    assertEquals(2, baseline.getModel().getSize());
                    for (int i = 0; i < 2; i++) { String label = baseline.getModel().getElementAt(i).toString(); if (label.endsWith(first)) baseline.setSelectedIndex(i); else candidate.setSelectedIndex(i); }
                    named(workspace, "cohort-dungeons", JTextField.class).setText("Ice Citadel");
                    named(workspace, "cohort-apply", JButton.class).doClick(); return null; });
                await(() -> !workspace.loading() && workspace.state().query.facets().baseline != null && workspace.displayedPage().matches > 0);
                assertEquals(Collections.singleton(first), edt(() -> workspace.state().query.facets().baseline.sessions));
                Row delta = edt(() -> { for (ArchiveRow<Row> row : workspace.displayedPage().rows) if ("delta".equals(row.value.type)) return row.value; return null; });
                assertNotNull(delta); assertEquals(3.0, delta.perRun, 0); assertEquals(300.0, delta.perRunChange, 1e-9);
                edt(() -> { named(workspace, "cohort-baseline-from", JTextField.class).setText("not a time"); named(workspace, "cohort-apply", JButton.class).doClick();
                    assertFalse(named(workspace, "cohort-error", JTextArea.class).getText().trim().isEmpty()); return null; });
            } finally { edt(() -> { workspace.close(); return null; }); }
        } finally { prefs.shutdown(5, TimeUnit.SECONDS, message -> {}); }
    }
}
