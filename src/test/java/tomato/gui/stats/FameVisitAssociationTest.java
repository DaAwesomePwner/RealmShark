package tomato.gui.stats;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.route.*;
import tomato.gui.stats.LootQuery.*;
import tomato.gui.stats.session.FameSession;
import tomato.gui.stats.session.FameSessionViewer;
import tomato.history.*;
import tomato.history.archive.*;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;
import static tomato.gui.stats.FameGraphPinTest.named;

/** STAT-2 round 2: recorded per-sample visits are shown; legacy samples stay Not recorded. Synthetic data only. */
public class FameVisitAssociationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @After public void restoreNavigator() { Navigator.install(Navigator.NONE); }

    private FameSession mixed(SessionStore store, List<Row> rows) throws Exception {
        String id = store.currentId();
        store.append("fame", new AppHistory.FameSample(7, 100, 1000, "Wizard"));                                  // legacy
        store.append("fame", new AppHistory.FameSample(7, 120, 2000, "Wizard", new VisitRef(id, "v1"), "Ice Citadel"));
        store.append("fame", new AppHistory.FameSample(7, 150, 3000, "Wizard", new VisitRef(id, "v1"), "Ice Citadel"));
        store.append("fame", new AppHistory.FameSample(7, 170, 4000, "Wizard", new VisitRef(id, "v2"), "Lost Halls"));
        store.append("fame", new AppHistory.FameSample(8, 10, 1000, "Priest"));                                   // legacy only
        store.append("fame", new AppHistory.FameSample(8, 20, 2000, "Priest"));
        store.flush();
        Facets f = new Facets(); f.view = View.FAME;
        ArchiveQuery<Facets,Sort> q = LootQuery.initial(true).withScope(id).withFacets(f);
        try (ArchiveResult<Row> result = ArchiveResult.open(store, q, new StatisticsArchiveAdapter(q), temp.newFolder().toPath(), new Cancellation());
             ArchiveResult.Lease<Row> lease = result.lease()) {
            result.stream(ExportSelection.all(), row -> rows.add(row.value), new Cancellation());
            return LootArchiveClient.readFame(lease, id, new Cancellation());
        }
    }

    @Test public void legacyJsonHasNoVisitAndMixedSummariesCountBoth() throws Exception {
        AppHistory.FameSample legacy = SessionStore.JSON.fromJson("{\"character\":7,\"fame\":100,\"time\":1000,\"className\":\"Wizard\"}", AppHistory.FameSample.class);
        assertNull(legacy.visit()); assertNull(legacy.map);
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "fixture")) {
            List<Row> rows = new ArrayList<>(); FameSession graph = mixed(store, rows);
            Map<Integer,Row> byCharacter = new HashMap<>(); for (Row row : rows) byCharacter.put(row.character, row);
            String wizard = byCharacter.get(7).evidence, priest = byCharacter.get(8).evidence;
            assertTrue(wizard, wizard.contains("Map association: 3 sample(s) with a recorded visit (Ice Citadel ×2, Lost Halls ×1); 1 sample(s) Not recorded."));
            assertTrue(priest, priest.contains("Map association: Not recorded"));
            assertEquals(3, graph.sampleVisits(7).size()); assertTrue(graph.sampleVisits(8).isEmpty());
            assertEquals(new VisitRef(store.currentId(), "v2"), graph.sampleVisits(7).get(2).visit());
            assertEquals("Lost Halls", graph.sampleVisits(7).get(2).map);
        }
    }

    @Test public void viewerShowsRecordedRunsAndGatesOpeningOnTheNavigator() throws Exception {
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "fixture")) {
            FameSession graph = mixed(store, new ArrayList<>()); String id = store.currentId();
            List<Route> opened = new ArrayList<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                FameSessionViewer viewer = new FameSessionViewer(graph);
                try {
                    JComboBox<?> character = named(viewer, "saved-fame-character", JComboBox.class);
                    JLabel map = named(viewer, "saved-fame-map-association", JLabel.class); JTextArea status = named(viewer, "saved-fame-run-status", JTextArea.class);
                    JComboBox<?> runs = named(viewer, "saved-fame-recorded-runs", JComboBox.class); JButton open = named(viewer, "saved-fame-open-run", JButton.class);
                    character.setSelectedIndex(0); // Wizard #7
                    assertTrue(map.getText(), map.getText().contains("3 of 4 samples with a recorded visit (Ice Citadel ×2, Lost Halls ×1); 1 Not recorded"));
                    assertEquals(2, runs.getItemCount()); assertFalse(open.isEnabled());
                    assertTrue(status.getText(), status.getText().contains("Runs navigation not registered"));
                    Navigator.install(new Navigator() {
                        public boolean open(Route route) { opened.add(route); return true; }
                        public boolean back() { return false; }
                        public boolean canGoBack() { return false; }
                        public boolean canOpen(Route route) { return route.destination == Destination.RUNS && route.visit != null; }
                    });
                    runs.setSelectedIndex(1); assertTrue(open.isEnabled()); open.doClick();
                    assertEquals(new VisitRef(id, "v2"), opened.get(0).visit); assertEquals(Destination.RUNS, opened.get(0).destination);
                    character.setSelectedIndex(1); // Priest #8: legacy only
                    assertTrue(map.getText(), map.getText().startsWith(FameSessionViewer.MAP_NOT_RECORDED));
                    assertEquals(0, runs.getItemCount()); assertFalse(open.isEnabled());
                    assertTrue(status.getText().contains("Not recorded"));
                } catch (Throwable t) { failure.set(t); }
                finally { viewer.dispose(); }
            });
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }
}
