package tomato.gui.security;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import javax.swing.*;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tomato.gui.activity.ActivityArchiveUiTest;
import tomato.gui.activity.ActivityQueries;
import tomato.gui.activity.ActivityRouteTarget;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.route.*;
import tomato.history.SessionStore;
import tomato.history.link.VisitRef;
import ui.UiTestLayout;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static tomato.gui.history.ArchiveNativeSupport.await;
import static ui.WaveThreeEvidence.*;

/** Wave 3 visual evidence for INS-2 provenance in the routed saved roster and the INS-3 build comparison dialog. Synthetic runs only. */
public class WaveThreeEvidenceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence(FOLDER);

    @After public void restore() throws Exception {
        run(() -> { ParsePanelGUI.clearPinnedBaseline(); ParsePanelGUI.clear(); Navigator.install(Navigator.NONE); });
    }

    @Test public void inspectProvenanceRoutedAndUnavailable() throws Exception {
        Path scratch = temp.newFolder().toPath();
        ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory();
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "synthetic")) {
            store.put("runs", "journal:1", InspectComparisonTest.run("journal:1", 782, BASE + 1_111, BASE + 10_000, BASE + 70_000, 41_000, false));
            store.put("runs", "journal:2", InspectComparisonTest.run("journal:2", 782, BASE + 72_222, BASE + 80_000, BASE + 90_000, 18_000, false)); store.flush();
            ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> inspect =
                edt(() -> SecurityGUI.workspace(store, new JLabel("Live Inspect"), scratch, memory.states));
            try {
                WorkspaceShell shell = edt(() -> {
                    JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length]; Arrays.setAll(pages, i -> new JPanel());
                    pages[2] = inspect;
                    WorkspaceShell created = new WorkspaceShell(pages, () -> fail("Synthetic workspace must not capture"), true);
                    ShellNavigator navigator = created.createNavigator(); Navigator.install(navigator);
                    navigator.register(ActivityRouteTarget.of(Destination.INSPECT, inspect));
                    created.select(10); return created;
                });
                VisitRef ref = new VisitRef(store.currentId(), "journal:2");
                assertTrue(edt(() -> Navigator.current().open(Route.to(Destination.INSPECT).withVisit(ref))));
                await(() -> !inspect.loading() && inspect.displayedPage() != null && inspect.displayedPage().matches == 1
                    && ActivityArchiveUiTest.named(inspect, ParsePanelGUI.class, null) != null);
                wideAndCompact(evidence, shell, "inspect-provenance-recorded-run",
                    () -> !inspect.loading() && ActivityArchiveUiTest.named(inspect, ParsePanelGUI.class, null) != null, () -> {
                    ParsePanelGUI saved = ActivityArchiveUiTest.named(inspect, ParsePanelGUI.class, null);
                    saved.refreshRoster(); saved.rosterTable().setRowSelectionInterval(0, 0);
                    JToggleButton details = VisualEvidence.find(saved, JToggleButton.class, b -> "Requirement details".equals(b.getText()));
                    if (!details.isSelected()) details.doClick();
                    UiTestLayout.settle(shell);
                    VisualEvidence.reachable(named(saved, "inspect-requirement-reasons", JTextArea.class));
                    assertTrue(saved.selectedOrigin(), saved.selectedOrigin().contains("session " + store.currentId() + " · visit journal:2"));
                    assertShows(inspect, "Recorded run: session " + store.currentId());
                    assertShows(shell, "Back to Runs");
                    // The players table keeps at least three rows of height in the run workbench (realized size), and all
                    // of it can be scrolled into view: it never collapses to a bare horizontal scroll bar.
                    JScrollPane players = named(saved, "security-roster-scroll", JScrollPane.class);
                    int rows = players.getViewport().getHeight() / saved.rosterTable().getRowHeight();
                    assertTrue("Players table viewport " + players.getViewport().getSize() + " = " + rows + " rows", rows >= 3);
                    reveal(players, players.getHeight());
                    assertEquals("Whole players table reachable", players.getHeight(), players.getVisibleRect().height);
                });
                VisitRef absent = new VisitRef(UUID.randomUUID().toString(), "journal:2");
                assertTrue(edt(() -> Navigator.current().open(Route.to(Destination.INSPECT).withVisit(absent))));
                await(() -> !inspect.loading() && inspect.displayedPage() != null && inspect.displayedPage().matches == 0
                    && absent.sessionId.equals(inspect.state().query.facets().visitSession));
                wideAndCompact(evidence, shell, "inspect-provenance-unavailable", () -> {
                    assertShows(inspect, "Linked visit unavailable");
                    reveal(VisualEvidence.find(inspect, JTextArea.class, a -> a.isShowing() && a.getText().contains("Linked visit unavailable")));
                });
            } finally { run(() -> { inspect.close(); evidence.closeWindow(); }); }
        }
    }

    /** InspectComparisonTest's fixture with a realistic recorded build time. */
    private static BuildComparison.Build build(int classId, int[] stats, Integer weapon, Long damage, Long start, Long end, VisitRef source, long recordedAt) {
        return new BuildComparison.Build("Player7", classId == 782 ? "Wizard" : "Priest", classId, 20, stats, new Integer[]{weapon, 11, -1, 13},
            new String[]{"Staff", "Spell", null, "Ring"}, recordedAt, source, "fixture", "Lost Halls", "Completed · Server victory", damage, start, end);
    }

    @Test public void inspectBuildComparisonDialogWithMissingStatsAndClassChange() throws Exception {
        String session = "11111111-2222-3333-4444-555555555555";
        BuildComparison.Build baseline = build(782, new int[]{100, 100, 10, 0, 10, 10, 10, 10}, 101, 60_000L, BASE + 10_000L, BASE + 70_000L, new VisitRef(session, "journal:1"), BASE + 5_555);
        BuildComparison.Build candidate = build(784, new int[]{120, 100, 12, 0, 10, 10, 10, -1}, null, 90_000L, BASE + 200_000L, BASE + 245_000L, new VisitRef(session, "journal:2"), BASE + 195_000);
        JDialog[] dialog = new JDialog[1];
        try {
            JPanel owner = edt(() -> new JPanel());
            run(() -> evidence.show(owner, "Inspect comparison owner", 900, 600, FONT));
            run(() -> dialog[0] = ParsePanelGUI.showComparison(owner, new BuildComparison(baseline, candidate)));
            windowWideAndCompact(dialog[0], "inspect-build-comparison", 1100, 720, () -> {
                JTable table = named(dialog[0].getContentPane() instanceof JComponent ? (JComponent) dialog[0].getContentPane() : dialog[0].getRootPane(),
                    "inspect-build-comparison-table", JTable.class);
                assertTrue(table.getRowCount() > 10);
                JTextArea summary = named(dialog[0].getRootPane(), "inspect-build-comparison-summary", JTextArea.class);
                assertTrue(summary.getText().contains(BuildComparison.NO_GEAR_ATTRIBUTION));
                assertShows(dialog[0].getRootPane(), "Not captured", "not like-for-like");
            });
        } finally { run(() -> { if (dialog[0] != null) dialog[0].dispose(); evidence.closeWindow(); }); }
    }
}
