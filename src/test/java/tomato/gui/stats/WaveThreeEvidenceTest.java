package tomato.gui.stats;

import java.nio.file.Path;
import java.util.*;
import javax.swing.*;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.history.*;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.route.*;
import tomato.gui.stats.LootQuery.*;
import tomato.gui.stats.session.FameSession;
import tomato.gui.stats.session.FameSessionViewer;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import tomato.history.link.VisitRef;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static tomato.gui.history.ArchiveNativeSupport.await;
import static tomato.gui.stats.LootArchiveQueryTest.*;
import static ui.WaveThreeEvidence.*;

/**
 * Wave 3 visual evidence for LOOT-3 drill-down and rate details, STAT-3 A/B cohorts and STAT-2 saved fame pins
 * and map association. Synthetic session stores and samples only; no capture.
 */
public class WaveThreeEvidenceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence(FOLDER);
    private final ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory();

    @After public void restore() throws Exception { run(() -> Navigator.install(Navigator.NONE)); }

    private static WorkspaceShell shell(int page, JComponent content) {
        JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length]; Arrays.setAll(pages, i -> new JPanel());
        pages[page] = content;
        WorkspaceShell shell = new WorkspaceShell(pages, () -> fail("Synthetic workspace must not capture"), true);
        shell.select(page); return shell;
    }

    private static JTable table(JComponent workspace) { return named(workspace, "loot-archive-table", JTable.class); }
    private static String label(JComponent workspace, String name) { return named(workspace, name, JLabel.class).getText(); }

    @Test public void lootDrillDownRateDetailsAndUnavailableRun() throws Exception {
        Path scratch = temp.newFolder().toPath();
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "fixture")) {
            store.put("runs", "a", visit("a", "Ice Citadel", BASE + 1000, 60000));
            store.put("runs", "zero", visit("zero", "Ice Citadel", BASE + 61000, 60000));
            store.append("loot", drop(BASE + 2000, "Ice Citadel", "White", "a", item(1, "Needle", "WEAPON,UT", ""), item(1, "Needle", "WEAPON,UT", ""), item(2, "Plate", "ARMOR,T13", "")));
            store.append("loot", drop(BASE + 3000, "Nexus", "Brown", "", item(1, "Needle", "WEAPON,UT", ""))); // legacy: no visit ID
            store.flush();
            ArchiveWorkspace<Row,Facets,Sort> workspace = edt(() -> HistoricalStatistics.lootWorkspace(store, new LootDashboard(), scratch, memory.states));
            try {
                WorkspaceShell shell = edt(() -> shell(8, workspace));
                run(() -> { Facets f = workspace.state().query.facets(); f.view = View.ITEMS; workspace.changeQuery(workspace.state().query.withFacets(f)); });
                await(() -> !workspace.loading() && workspace.displayedPage() != null && workspace.displayedPage().unit.equals("item variants"));
                run(() -> {
                    int needle = -1;
                    for (int i = 0; i < workspace.displayedPage().rows.size(); i++) if ("1/0/0".equals(workspace.displayedPage().rows.get(i).value.variantKey())) needle = i;
                    table(workspace).setRowSelectionInterval(needle, needle);
                    named(workspace, "loot-drill-occurrences", JButton.class).doClick();
                });
                await(() -> !workspace.loading() && workspace.state().query.facets().view == View.OCCURRENCES && workspace.displayedPage().matches == 3);
                int linked = edt(() -> { List<ArchiveRow<Row>> rows = workspace.displayedPage().rows; for (int i = 0; i < rows.size(); i++) if (Boolean.TRUE.equals(rows.get(i).value.runLinked)) return i; return -1; });
                int legacy = edt(() -> { List<ArchiveRow<Row>> rows = workspace.displayedPage().rows; for (int i = 0; i < rows.size(); i++) if (rows.get(i).value.visitId.isEmpty()) return i; return -1; });
                assertTrue(linked >= 0 && legacy >= 0);
                run(() -> table(workspace).setRowSelectionInterval(linked, linked));
                wideAndCompact(evidence, shell, "loot-occurrences-open-run-unavailable", () -> {
                    reveal(named(workspace, "loot-run-link-status", JLabel.class));
                    assertFalse(named(workspace, "loot-open-run", JButton.class).isEnabled());
                    assertTrue(label(workspace, "loot-run-link-status"), label(workspace, "loot-run-link-status").contains("opening runs is unavailable"));
                    assertTrue(label(workspace, "loot-drill-summary").contains("exact variant 1/0/0"));
                });
                run(() -> table(workspace).setRowSelectionInterval(legacy, legacy));
                wideAndCompact(evidence, shell, "loot-occurrence-no-visit-id", () -> {
                    reveal(named(workspace, "loot-run-link-status", JLabel.class));
                    assertTrue(label(workspace, "loot-run-link-status").contains("no recorded visit ID"));
                });
                run(() -> { table(workspace).setRowSelectionInterval(linked, linked); named(workspace, "loot-drill-rate", JButton.class).doClick(); });
                await(() -> !workspace.loading() && workspace.state().query.facets().view == View.RATES && workspace.displayedPage().matches == 1);
                run(() -> table(workspace).setRowSelectionInterval(0, 0));
                wideAndCompact(evidence, shell, "loot-rate-calculation", () -> {
                    JTextArea details = named(workspace, "loot-archive-details", JTextArea.class);
                    reveal(details, 220);
                    assertTrue(details.getText(), details.getText().startsWith("Rate calculation · Ice Citadel"));
                    assertTrue(details.getText(), details.getText().contains("Items / run = 3 ÷ 2 = 1.5"));
                    assertTrue(details.getText(), details.getText().contains("1 with no linked bags"));
                });
                // A route to a run in a session that is not in this history shows an explicit unavailable state.
                run(() -> {
                    ShellNavigator navigator = shell.createNavigator(); Navigator.install(navigator);
                    navigator.register(LootRouteTarget.forWorkspace(Destination.LOOT, workspace, workspace::restore));
                    shell.select(10);
                    assertTrue(navigator.open(Route.to(Destination.LOOT).withVisit(new VisitRef(UUID.randomUUID().toString(), "a"))));
                });
                await(() -> !workspace.loading() && workspace.displayedPage() != null && workspace.displayedPage().matches == 0
                    && label(workspace, "loot-drill-summary").contains("Linked run unavailable here"));
                wideAndCompact(evidence, shell, "loot-linked-run-unavailable", () -> {
                    reveal(named(workspace, "loot-drill-summary", JLabel.class));
                    assertTrue(label(workspace, "loot-drill-summary").contains("No other run is substituted"));
                    assertShows(shell, "Back to Runs");
                });
            } finally { run(() -> { workspace.close(); evidence.closeWindow(); }); }
        }
    }

    private static void runs(SessionStore store, String prefix, int count, int itemsPerRun, long start) {
        for (int i = 0; i < count; i++) {
            ActivityJournal.Visit v = visit(prefix + i, "Ice Citadel", start + i * 120000L, 60000); v.status = "Completed"; store.put("runs", v.id, v);
            LootDashboard.Item[] items = new LootDashboard.Item[itemsPerRun];
            for (int k = 0; k < itemsPerRun; k++) items[k] = item(1, "A", "WEAPON,UT", "");
            store.append("loot", drop(start + i * 120000L + 1000, "Ice Citadel", "White", prefix + i, items));
        }
    }

    @Test public void cohortComparisonZeroBaselineEmptyAndInputError() throws Exception {
        Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath();
        String baselineId;
        try (SessionStore a = new SessionStore(root, true, "baseline")) { baselineId = a.currentId(); runs(a, "a", 2, 0, BASE); a.flush(); }
        try (SessionStore store = new SessionStore(root, true, "candidate")) {
            runs(store, "b", 3, 2, BASE + 3_600_000); store.flush();
            ArchiveWorkspace<Row,Facets,Sort> workspace = edt(() -> SessionPanel.queried(store, "statistics", new JLabel("Live statistics"), new LootArchiveClient(scratch, true), memory.states));
            try {
                WorkspaceShell shell = edt(() -> shell(4, workspace));
                run(() -> { Facets f = workspace.state().query.facets(); f.view = View.COHORTS; workspace.changeQuery(workspace.state().query.withScope(SessionStore.ALL).withFacets(f)); });
                await(() -> !workspace.loading() && workspace.displayedPage() != null && workspace.state().query.facets().view == View.COHORTS);
                wideAndCompact(evidence, shell, "cohort-not-chosen", () -> {
                    assertEquals(0, workspace.displayedPage().matches);
                    reveal(named(workspace, "cohort-baseline-sessions", JList.class));
                });
                run(() -> {
                    JList<?> baseline = named(workspace, "cohort-baseline-sessions", JList.class), candidate = named(workspace, "cohort-candidate-sessions", JList.class);
                    for (int i = 0; i < baseline.getModel().getSize(); i++) { if (baseline.getModel().getElementAt(i).toString().endsWith(baselineId)) baseline.setSelectedIndex(i); else candidate.setSelectedIndex(i); }
                    named(workspace, "cohort-dungeons", JTextField.class).setText("Ice Citadel");
                    named(workspace, "cohort-apply", JButton.class).doClick();
                });
                await(() -> !workspace.loading() && workspace.state().query.facets().baseline != null && workspace.displayedPage().matches > 0);
                Row delta = edt(() -> { for (ArchiveRow<Row> row : workspace.displayedPage().rows) if ("delta".equals(row.value.type)) return row.value; return null; });
                assertNotNull(delta); assertNull("No invented percentage from a zero baseline", delta.perRunChange);
                run(() -> {
                    for (int i = 0; i < workspace.displayedPage().rows.size(); i++)
                        if ("delta".equals(workspace.displayedPage().rows.get(i).value.type)) table(workspace).setRowSelectionInterval(i, i);
                });
                wideAndCompact(evidence, shell, "cohort-zero-baseline", () -> {
                    JTable rows = table(workspace);
                    reveal(rows, 400);
                    revealRow(rows, rows.getSelectedRow());
                    assertEquals(1.0 * 2, delta.perRun, 0);
                    assertShows(workspace, "baseline is zero");
                });
                run(() -> { named(workspace, "cohort-baseline-from", JTextField.class).setText("not a time"); named(workspace, "cohort-apply", JButton.class).doClick(); });
                wideAndCompact(evidence, shell, "cohort-input-error", () -> {
                    reveal(named(workspace, "cohort-baseline-from", JTextField.class), 200);
                    assertFalse(named(workspace, "cohort-error", JLabel.class).getText().trim().isEmpty());
                    assertTrue(named(workspace, "cohort-error", JLabel.class).isShowing());
                });
            } finally { run(() -> { workspace.close(); evidence.closeWindow(); }); }
        }
    }

    @Test public void savedFamePinDeltaAndMapAssociation() throws Exception {
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "fixture")) {
            String id = store.currentId();
            long minute = 60_000;
            store.append("fame", new AppHistory.FameSample(7, 100, BASE, "Wizard"));
            store.append("fame", new AppHistory.FameSample(7, 120, BASE + 10 * minute, "Wizard", new VisitRef(id, "v1"), "Ice Citadel"));
            store.append("fame", new AppHistory.FameSample(7, 150, BASE + 20 * minute, "Wizard", new VisitRef(id, "v1"), "Ice Citadel"));
            store.append("fame", new AppHistory.FameSample(7, 170, BASE + 30 * minute, "Wizard", new VisitRef(id, "v2"), "Lost Halls"));
            store.append("fame", new AppHistory.FameSample(8, 10, BASE, "Priest"));
            store.append("fame", new AppHistory.FameSample(8, 20, BASE + 10 * minute, "Priest"));
            store.append("fame", new AppHistory.FameSample(8, 45, BASE + 20 * minute, "Priest"));
            store.flush();
            Facets f = new Facets(); f.view = View.FAME;
            ArchiveQuery<Facets,Sort> q = LootQuery.initial(true).withScope(id).withFacets(f);
            FameSession session;
            try (ArchiveResult<Row> result = ArchiveResult.open(store, q, new StatisticsArchiveAdapter(q), temp.newFolder().toPath(), new Cancellation());
                 ArchiveResult.Lease<Row> lease = result.lease()) {
                session = LootArchiveClient.readFame(lease, id, new Cancellation());
            }
            run(() -> Navigator.install(new Navigator() {
                public boolean open(Route route) { return false; }
                public boolean back() { return false; }
                public boolean canGoBack() { return false; }
                public boolean canOpen(Route route) { return route.destination == Destination.RUNS && route.visit != null; }
            }));
            FameSessionViewer viewer = edt(() -> new FameSessionViewer(session));
            try {
                run(() -> {
                    named(viewer.getRootPane(), "saved-fame-character", JComboBox.class).setSelectedIndex(0);
                    selectTab(viewer.getRootPane(), "Fame Graph");
                    named(viewer.getRootPane(), "saved-fame-graph", GraphPanel.class).pinInterval(BASE + 600_000L, BASE + 1_800_000L);
                    named(viewer.getRootPane(), "saved-fame-recorded-runs", JComboBox.class).setSelectedIndex(1);
                });
                windowWideAndCompact(viewer, "fame-saved-pin-recorded-visit", 1100, 760, () -> {
                    JLabel delta = named(viewer.getRootPane(), "saved-fame-delta", JLabel.class);
                    assertTrue(delta.getText(), delta.getText().startsWith("Pinned") && delta.getText().contains("+50"));
                    assertTrue(named(viewer.getRootPane(), "saved-fame-map-association", JLabel.class).getText().contains("3 of 4 samples with a recorded visit"));
                    assertTrue(named(viewer.getRootPane(), "saved-fame-open-run", JButton.class).isEnabled());
                    assertRunStatusReadable(viewer, "Opens verified run");
                });
                run(() -> {
                    named(viewer.getRootPane(), "saved-fame-character", JComboBox.class).setSelectedIndex(1);
                    named(viewer.getRootPane(), "saved-fame-graph", GraphPanel.class).pinInterval(BASE, BASE + 1_200_000L);
                });
                windowWideAndCompact(viewer, "fame-saved-pin-not-recorded", 1100, 760, () -> {
                    assertTrue(named(viewer.getRootPane(), "saved-fame-map-association", JLabel.class).getText().startsWith(FameSessionViewer.MAP_NOT_RECORDED));
                    assertTrue(named(viewer.getRootPane(), "saved-fame-delta", JLabel.class).getText().contains("+35"));
                    assertFalse(named(viewer.getRootPane(), "saved-fame-open-run", JButton.class).isEnabled());
                    assertTrue(named(viewer.getRootPane(), "saved-fame-run-status", JTextArea.class).getText().contains("Not recorded"));
                    assertRunStatusReadable(viewer, "Not recorded");
                });
            } finally { run(viewer::dispose); }
        }
    }

    /** The run status sits on its own wrapping line: showing, inside the realized window width, and given its full wrapped height. */
    private static void assertRunStatusReadable(JFrame viewer, String expected) {
        JTextArea status = named(viewer.getRootPane(), "saved-fame-run-status", JTextArea.class);
        assertTrue(status.getText(), status.getText().contains(expected));
        assertWithinWidth(status);
        assertTrue("Run status height " + status.getHeight(), status.getHeight() >= status.getFontMetrics(status.getFont()).getHeight());
        assertTrue("Run status allocated its wrapped height", measureText(status));
        java.awt.Rectangle inRoot = SwingUtilities.convertRectangle(status, new java.awt.Rectangle(status.getSize()), viewer.getRootPane());
        assertTrue("Run status " + inRoot + " inside window height " + viewer.getRootPane().getHeight(), inRoot.y + inRoot.height <= viewer.getRootPane().getHeight());
    }
}
