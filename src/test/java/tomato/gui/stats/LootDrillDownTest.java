package tomato.gui.stats;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.history.*;
import tomato.gui.route.*;
import tomato.gui.stats.LootQuery.*;
import tomato.history.*;
import tomato.history.archive.*;
import tomato.history.link.VisitRef;
import util.PreferencesStore;
import static org.junit.Assert.*;
import static tomato.gui.history.SessionPanelTest.named;
import static tomato.gui.stats.LootArchiveQueryTest.*;

/** LOOT-3: exact variant/visit drill-down, verified run links and adapter-computed rate details. Synthetic data only. */
public class LootDrillDownTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @After public void restoreNavigator() { Navigator.install(Navigator.NONE); }

    private ArchiveResult<Row> open(SessionStore store, ArchiveQuery<Facets,Sort> q) throws Exception {
        return ArchiveResult.open(store, q, q.facets().view.loot() ? new LootArchiveAdapter(q) : new StatisticsArchiveAdapter(q), temp.newFolder().toPath(), new Cancellation());
    }
    private static List<Row> rows(ArchiveResult<Row> result) throws Exception {
        List<Row> rows = new ArrayList<>(); result.stream(ExportSelection.all(), row -> rows.add(row.value), new Cancellation()); return rows;
    }

    @Test public void threeItemsAcrossTwoEligibleOneMinuteRunsWithOneZeroLootRunYieldOnePointFivePerRunAndNinetyPerHour() throws Exception {
        Path root = temp.newFolder().toPath();
        try (SessionStore unknown = new SessionStore(root, true, "unknown coverage")) { unknown.put("runs", "u", visit("u", "Ice Citadel", 1000, 60000)); unknown.flush(); }
        try (SessionStore store = new SessionStore(root, true, "fixture")) {
            store.put("runs", "one", visit("one", "Ice Citadel", 1000, 60000));
            store.put("runs", "zero", visit("zero", "Ice Citadel", 61000, 60000));
            store.append("loot", drop(2000, "Ice Citadel", "White", "one", item(1, "A", "WEAPON,UT", ""), item(1, "A", "WEAPON,UT", ""), item(2, "B", "ARMOR,T13", "")));
            store.flush();
            store.importSnapshot("synthetic-import", "Run-only", 1000, "runs", "imported", visit("imported", "Ice Citadel", 1000, 60000));
            ArchiveQuery<Facets,Sort> q = query(SessionStore.ALL, View.RATES);
            Row rate;
            try (ArchiveResult<Row> result = open(store, q)) { rate = rows(result).get(0); }
            assertEquals(3L, rate.items.longValue()); assertEquals(2L, rate.runs.longValue()); assertEquals(120000L, rate.millis.longValue());
            assertEquals(1.5, rate.perRun, 0); assertEquals(90.0, rate.perHour, 0);
            assertEquals(1L, rate.zeroLootRuns.longValue()); assertEquals(0L, rate.unassignedBags.longValue());
            assertEquals(1L, rate.unknownRuns.longValue()); assertEquals(1L, rate.importedRuns.longValue());
            String text = RateCalculation.describe(rate);
            assertTrue(text, text.contains("Items / run = 3 ÷ 2 = 1.5"));
            assertTrue(text, text.contains("Items / hour = 3 × 3,600,000 ÷ 120,000 ms = 90"));
            assertTrue(text, text.contains("2 (1 with no linked bags, kept in the denominator)"));
            assertTrue(text, text.contains("1 run-only imported runs; 1 unknown-coverage runs"));
            assertTrue(text, text.contains("Unassigned drops: 0 bags"));
            // A variant drill-down facet narrows occurrences but never the rate cohort.
            Facets f = q.facets(); f.variant = "2/0/0";
            try (ArchiveResult<Row> result = open(store, q.withFacets(f))) { assertEquals(1.5, rows(result).get(0).perRun, 0); }
            // An unassigned drop makes the rate unavailable and the details say why.
            store.append("loot", drop(3000, "Ice Citadel", "White", "missing", item(3, "C", "WEAPON,UT", "")));
            store.flush();
            try (ArchiveResult<Row> result = open(store, q)) {
                Row unavailable = rows(result).get(0); assertNull(unavailable.perRun); assertNull(unavailable.perHour);
                String reason = RateCalculation.describe(unavailable);
                assertTrue(reason, reason.contains("Items / run = unavailable: 1 unassigned bag(s)"));
                assertTrue(reason, reason.contains("Items / hour = unavailable: 1 unassigned bag(s)"));
            }
        }
        try (SessionStore alone = new SessionStore(temp.newFolder().toPath(), true, "no loot")) {
            alone.put("runs", "r", visit("r", "Ice Citadel", 1000, 60000)); alone.flush();
            try (ArchiveResult<Row> result = open(alone, query(alone.currentId(), View.RATES))) {
                String reason = RateCalculation.describe(rows(result).get(0));
                assertTrue(reason, reason.contains("Numerator: unavailable"));
                assertTrue(reason, reason.contains("unavailable: no saved loot evidence"));
            }
        }
    }

    @Test public void exactVariantAndVisitFacetsApplyBeforePagingAndSameNameVisitsNeverCrossLink() throws Exception {
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "fixture")) {
            String session = store.currentId();
            store.put("runs", "a", visit("a", "Ice Citadel", 1000, 60000));
            store.put("runs", "b", visit("b", "Ice Citadel", 61000, 60000));
            store.put("runs", "elsewhere", visit("elsewhere", "Lost Halls", 200000, 60000));
            for (int i = 0; i < 150; i++) store.append("loot", drop(2000 + i, "Ice Citadel", "White", "a", item(1, "A", "WEAPON,UT", ""), item(2, "B", "WEAPON,T13", "")));
            store.append("loot", drop(62000, "Ice Citadel", "White", "b", item(1, "A", "WEAPON,UT", "")));
            store.append("loot", drop(63000, "Ice Citadel", "Blue", "elsewhere", item(1, "A", "WEAPON,UT", "")));
            store.append("loot", drop(64000, "Ice Citadel", "Blue", "", item(1, "A", "WEAPON,UT", "")));
            store.flush();
            ArchiveQuery<Facets,Sort> q = query(session, View.OCCURRENCES); Facets f = q.facets(); f.variant = "1/0/0";
            try (ArchiveResult<Row> result = open(store, q.withFacets(f))) {
                assertEquals(153, result.matches);
                ArchivePage<Row> page = result.page(0, 10, new Cancellation());
                assertEquals(153, page.counts.get("matching occurrences").value);
                assertEquals(151, page.counts.get("run-linked occurrences").value);
                assertEquals(2, page.counts.get("unlinked occurrences").value);
                for (Row row : rows(result)) assertEquals("1/0/0", row.variantKey());
            }
            f.visitSession = session; f.visitId = "b";
            try (ArchiveResult<Row> result = open(store, q.withFacets(f))) {
                List<Row> rows = rows(result); assertEquals(1, rows.size());
                assertEquals(new VisitRef(session, "b"), rows.get(0).visitRef()); assertTrue(rows.get(0).evidence.contains("verified"));
            }
            f.variant = null; f.visitId = "a";
            try (ArchiveResult<Row> result = open(store, q.withFacets(f))) { assertEquals(300, result.matches); assertEquals(1, result.page(0, 1, new Cancellation()).rows.size()); }
            f.visitSession = f.visitId = null;
            try (ArchiveResult<Row> result = open(store, q.withFacets(f))) {
                int legacy = 0, mismatched = 0;
                for (Row row : rows(result)) {
                    if (row.visitId.isEmpty()) { legacy++; assertNull(row.visitRef()); assertTrue(row.evidence.contains("no recorded visit ID")); }
                    if ("elsewhere".equals(row.visitId)) { mismatched++; assertFalse(row.runLinked); assertNull(row.visitRef()); assertTrue(row.evidence.contains("dungeon differs")); }
                }
                assertEquals(1, legacy); assertEquals(1, mismatched);
            }
            try (ArchiveResult<Row> recent = open(store, query(session, View.RECENT))) {
                for (Row row : rows(recent)) assertEquals(!row.visitId.isEmpty() && !"elsewhere".equals(row.visitId), row.runLinked);
            }
        }
    }

    @Test public void savedQueriesWithoutDrillFacetsRestoreUnchangedAndDrillFacetsRoundTrip() {
        ArchiveQuery<Facets,Sort> q = LootQuery.initial(false);
        JsonObject legacy = q.toJson();
        assertFalse(legacy.getAsJsonObject("facets").has("variant")); assertFalse(legacy.getAsJsonObject("facets").has("visitId"));
        JsonObject older = JsonParser.parseString(legacy.toString()).getAsJsonObject();
        assertEquals(q, q.restore(older));
        Facets f = q.facets(); f.variant = "42/2/0"; f.visitSession = "01234567-89ab-cdef-0123-456789abcdef"; f.visitId = "visit-1";
        ArchiveQuery<Facets,Sort> drilled = q.withFacets(f);
        assertEquals(drilled, q.restore(drilled.toJson())); assertEquals("visit-1", q.restore(drilled.toJson()).facets().visitId);
        f.visitId = null;
        try { f.validate(); fail(); } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("both")); }
        f.visitSession = null; f.variant = "Sword";
        try { f.validate(); fail(); } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("variant")); }
    }

    @Test public void routeTargetOpensExactVisitOccurrencesAndRejectsUnresolvableReferences() {
        String session = "01234567-89ab-cdef-0123-456789abcdef";
        ArchiveQuery<Facets,Sort> current = LootQuery.initial(false);
        Facets f = current.facets(); f.view = View.ITEMS; f.variant = "1/0/0"; current = current.withFacets(f);
        List<ArchiveQuery<Facets,Sort>> opened = new ArrayList<>(); List<Object> restored = new ArrayList<>();
        ViewState<Facets,Sort> origin = ViewState.initial(current);
        LootRouteTarget target = new LootRouteTarget(Destination.LOOT, () -> origin, opened::add, restored::add);
        Route route = Route.to(Destination.LOOT).withVisit(new VisitRef(session, "v"));
        assertTrue(target.accepts(route));
        assertSame(origin, target.captureState());
        target.open(route);
        Facets result = opened.get(0).facets();
        assertEquals(View.OCCURRENCES, result.view); assertEquals(session, result.visitSession); assertEquals("v", result.visitId);
        assertNull(result.variant); assertEquals(session, opened.get(0).scope());
        target.restoreState(origin); assertSame(origin, restored.get(0));
        assertFalse(target.accepts(Route.to(Destination.RUNS).withVisit(new VisitRef(session, "v"))));
        assertFalse(target.accepts(route.withRecording("recording", 7)));
        assertFalse(target.accepts(Route.to(Destination.LOOT)));
        assertFalse(target.accepts(Route.to(Destination.LOOT).withVisit(new VisitRef("not-a-session", "v"))));
        assertFalse(new LootRouteTarget(Destination.STATISTICS, () -> origin, opened::add, restored::add).accepts(Route.to(Destination.STATISTICS).withVisit(new VisitRef(session, "v"))));
        assertTrue(new LootRouteTarget(Destination.STATISTICS, () -> origin, opened::add, restored::add).accepts(Route.to(Destination.STATISTICS).withQuery(current)));
    }

    @Test public void workspaceDrillsVariantToOccurrenceToVerifiedRunAndExplainsUnavailableNavigation() throws Exception {
        Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath();
        PreferencesStore prefs = new PreferencesStore(temp.getRoot().toPath().resolve("views.properties")); prefs.preload();
        ViewStateStore states = ViewStateStore.preferences(prefs);
        try (SessionStore store = new SessionStore(root, true, "fixture")) {
            String session = store.currentId();
            store.put("runs", "a", visit("a", "Ice Citadel", 1000, 60000));
            store.append("loot", drop(2000, "Ice Citadel", "White", "a", item(1, "Needle", "WEAPON,UT", "")));
            store.append("loot", drop(3000, "Ice Citadel", "White", "", item(1, "Needle", "WEAPON,UT", "")));
            store.flush();
            ArchiveWorkspace<Row,Facets,Sort> workspace = edt(() -> HistoricalStatistics.lootWorkspace(store, new LootDashboard(), scratch, states));
            try {
                edt(() -> { Facets f = workspace.state().query.facets(); f.view = View.ITEMS; workspace.changeQuery(workspace.state().query.withFacets(f)); return null; });
                await(() -> !workspace.loading() && workspace.displayedPage() != null && workspace.displayedPage().unit.equals("item variants"));
                edt(() -> {
                    JButton occurrences = named(workspace, "loot-drill-occurrences", JButton.class);
                    assertFalse(occurrences.isEnabled());
                    named(workspace, "loot-archive-table", JTable.class).setRowSelectionInterval(0, 0);
                    assertTrue(named(workspace, "loot-run-link-status", JLabel.class).getText().contains("Variants combine many runs"));
                    assertTrue(occurrences.isEnabled()); occurrences.doClick(); return null; });
                await(() -> !workspace.loading() && workspace.state().query.facets().view == View.OCCURRENCES && workspace.displayedPage().matches == 2);
                assertEquals("1/0/0", edt(() -> workspace.state().query.facets().variant));
                int linkedRow = edt(() -> { List<ArchiveRow<Row>> rows = workspace.displayedPage().rows; for (int i = 0; i < rows.size(); i++) if (Boolean.TRUE.equals(rows.get(i).value.runLinked)) return i; return -1; });
                assertTrue(linkedRow >= 0);
                edt(() -> {
                    named(workspace, "loot-archive-table", JTable.class).setRowSelectionInterval(linkedRow, linkedRow);
                    assertFalse(named(workspace, "loot-open-run", JButton.class).isEnabled());
                    assertTrue(named(workspace, "loot-run-link-status", JLabel.class).getText().contains("opening runs is unavailable"));
                    assertTrue(named(workspace, "loot-drill-summary", JLabel.class).getText().contains("exact variant 1/0/0"));
                    named(workspace, "loot-archive-table", JTable.class).setRowSelectionInterval(1 - linkedRow, 1 - linkedRow);
                    assertTrue(named(workspace, "loot-run-link-status", JLabel.class).getText().contains("no recorded visit ID"));
                    assertFalse(named(workspace, "loot-drill-visit", JButton.class).isEnabled());
                    return null; });
                List<Route> routes = new ArrayList<>();
                Navigator.install(new Navigator() {
                    public boolean open(Route route) { routes.add(route); return true; }
                    public boolean back() { return false; }
                    public boolean canGoBack() { return false; }
                    public boolean canOpen(Route route) { return route.destination == Destination.RUNS && route.visit != null; }
                });
                edt(() -> {
                    JTable table = named(workspace, "loot-archive-table", JTable.class);
                    table.setRowSelectionInterval(linkedRow, linkedRow);
                    JButton run = named(workspace, "loot-open-run", JButton.class);
                    assertTrue(run.isEnabled()); run.doClick(); return null; });
                assertEquals(1, routes.size()); assertEquals(new VisitRef(session, "a"), routes.get(0).visit); assertEquals(Destination.RUNS, routes.get(0).destination);
                edt(() -> { named(workspace, "loot-drill-visit", JButton.class).doClick(); return null; });
                await(() -> !workspace.loading() && "a".equals(workspace.state().query.facets().visitId) && workspace.displayedPage().matches == 1);
                edt(() -> { named(workspace, "loot-archive-table", JTable.class).setRowSelectionInterval(0, 0); named(workspace, "loot-drill-rate", JButton.class).doClick(); return null; });
                await(() -> !workspace.loading() && workspace.state().query.facets().view == View.RATES && workspace.displayedPage().matches == 1);
                edt(() -> {
                    assertFalse(workspace.state().query.facets().drilled());
                    assertEquals(Collections.singleton("Ice Citadel"), workspace.state().query.facets().dungeons);
                    named(workspace, "loot-archive-table", JTable.class).setRowSelectionInterval(0, 0);
                    String details = named(workspace, "loot-archive-details", JTextArea.class).getText();
                    assertTrue(details, details.startsWith("Rate calculation · Ice Citadel"));
                    assertTrue(details, details.contains("Items / run = unavailable: 1 unassigned bag(s)"));
                    return null; });
            } finally { edt(() -> { workspace.close(); return null; }); }
        } finally { prefs.shutdown(5, TimeUnit.SECONDS, message -> {}); }
    }

    private interface Checked<T> { T get() throws Exception; }
    static <T> T edt(Checked<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { value.set(action.get()); } catch (Throwable t) { failure.set(t); } });
        if (failure.get() != null) throw new AssertionError(failure.get()); return value.get();
    }
    static void await(java.util.function.BooleanSupplier ready) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < end) { if (edt(ready::getAsBoolean)) return; Thread.sleep(20); }
        fail("Archive state did not settle");
    }
}
