package tomato.gui.dps;

import java.nio.file.Path;
import java.util.*;
import javax.swing.*;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.backend.data.*;
import tomato.gui.activity.ActivityPanel;
import tomato.gui.activity.ActivityQueries;
import tomato.gui.activity.ActivityRouteTarget;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.route.*;
import tomato.history.SessionStore;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static ui.WaveThreeEvidence.*;

/** Wave 3 visual evidence for COMBAT-3 link status/actions and the COMBAT-4 damage event explorer. Synthetic encounters only. */
public class WaveThreeEvidenceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence(FOLDER);

    private final int previousFilter = Filter.filter;

    @After public void restore() throws Exception { run(() -> { Navigator.install(Navigator.NONE); Filter.filter = previousFilter; }); }

    @Test public void dpsEncounterLinkLinkedUnlinkedAndLegacy() throws Exception {
        Path scratch = temp.newFolder().toPath();
        ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory();
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "synthetic")) {
            Filter.disable(); // Show every meter row; the preset filter is restored afterwards.
            ActivityJournal.Visit a = DpsInvestigationTest.visit("journal:a", BASE + 10_000), b = DpsInvestigationTest.visit("journal:b", BASE + 80_000);
            store.put("runs", a.id, a); store.put("runs", b.id, b); store.flush();
            TomatoData data = new TomatoData();
            DpsData linked = DpsInvestigationTest.encounter(data, new EncounterContext(new VisitRef(store.currentId(), b.id), 7, BASE + 80_000));
            DpsData unlinked = DpsInvestigationTest.encounter(data, new EncounterContext(null, 7, BASE + 90_000));
            DpsData legacy = DpsInvestigationTest.encounter(data, null);
            data.dpsData.addAll(Arrays.asList(linked, unlinked, legacy));
            ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> resources =
                edt(() -> ActivityPanel.workspace(store, new JPanel(), ActivityPanel.Mode.COMBAT, scratch, memory.states));
            ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> runs =
                edt(() -> ActivityPanel.workspace(store, new JPanel(), ActivityPanel.Mode.RUNS, scratch, memory.states));
            ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> timeline =
                edt(() -> ActivityPanel.workspace(store, new JPanel(), ActivityPanel.Mode.TIMELINE, scratch, memory.states));
            DpsGUI dps = edt(() -> new DpsGUI(data, DiscoveryLog.historyView(new ActivityJournal.State()), resources));
            try {
                WorkspaceShell shell = edt(() -> {
                    JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length]; Arrays.setAll(pages, i -> new JPanel());
                    pages[7] = dps; pages[10] = runs; pages[11] = timeline;
                    WorkspaceShell created = new WorkspaceShell(pages, () -> fail("Synthetic workspace must not capture"), true);
                    ShellNavigator navigator = created.createNavigator(); Navigator.install(navigator);
                    navigator.register(ActivityRouteTarget.of(Destination.RUNS, runs));
                    navigator.register(ActivityRouteTarget.of(Destination.TIMELINE, timeline));
                    navigator.register(dps.resourcesRouteTarget()); navigator.register(dps.encounterRouteTarget());
                    created.select(7); return created;
                });
                String[] names = {"dps-link-linked", "dps-link-unlinked", "dps-link-legacy"};
                DpsData[] shown = {linked, unlinked, legacy};
                EncounterLink.State[] states = {EncounterLink.State.LINKED, EncounterLink.State.UNLINKED, EncounterLink.State.LEGACY};
                for (int i = 0; i < shown.length; i++) {
                    DpsData encounter = shown[i]; EncounterLink.State state = states[i];
                    run(() -> assertTrue(dps.showEncounter(DpsInvestigationTest.entry(dps, encounter))));
                    for (boolean compact : new boolean[]{false, true}) frame(evidence, shell, names[i], compact, () -> {
                        if (!compact) assertDetailsUsable(dps);
                        assertEquals(state, dps.shownLink().state);
                        JTextArea link = named(dps, "dps-encounter-link", JTextArea.class);
                        reveal(link);
                        assertFalse(link.getText().trim().isEmpty());
                        boolean offered = state == EncounterLink.State.LINKED;
                        for (String action : new String[]{"dps-open-run", "dps-open-timeline", "dps-open-resources"})
                            assertEquals(action, offered, named(dps, action, JButton.class).isEnabled());
                        if (state == EncounterLink.State.LEGACY) assertTrue(link.getText().contains("Legacy recording"));
                        if (state == EncounterLink.State.UNLINKED) assertTrue(link.getText().contains("Unlinked"));
                    });
                }
            } finally { run(() -> { resources.close(); runs.close(); timeline.close(); evidence.closeWindow(); }); }
        }
    }

    @Test public void dpsDamageEventExplorerFilteredAndEmptyResult() throws Exception {
        TomatoData data = new TomatoData(); Entity self = DpsInvestigationTest.actor(data, 7, "Self"); DpsInvestigationTest.gear(self, 100);
        List<Damage> hits = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            if (i == 700) DpsInvestigationTest.gear(self, 200); // Weapon swap retained on later events.
            Projectile projectile = new Projectile(10 + i);
            projectile.setSource(i % 3 == 0 ? DamageSource.ABILITY : DamageSource.WEAPON, i % 3 == 0 ? 0 : i < 700 ? 100 : 200);
            Damage hit = new Damage(self, projectile, 1000 + i * 10L);
            hit.oryx3GuardDmg = i == 600;
            hits.add(hit);
        }
        JDialog[] dialog = new JDialog[1];
        try {
            DamageEventExplorer explorer = edt(() -> new DamageEventExplorer("Self", hits, Collections.emptyList(), false, 1000, "Lost Halls · all enemies"));
            run(() -> { dialog[0] = DamageEventExplorer.open(null, explorer); assertTrue(explorer.goToEvent(1)); });
            windowWideAndCompact(dialog[0], "dps-explorer-all-events", 1100, 700, () -> {
                assertTrue(explorer.statusText(), explorer.statusText().contains("Page 1 of 6"));
                assertTrue(explorer.detailText().contains("Event 1 · Outgoing"));
                assertEquals(200, explorer.table().getRowCount());
            });
            run(() -> {
                field(explorer, "Minimum damage").setText("705"); field(explorer, "Maximum damage").setText("760");
                named(explorer, "dps-event-apply", JButton.class).doClick();
                explorer.table().setRowSelectionInterval(0, 0);
            });
            windowWideAndCompact(dialog[0], "dps-explorer-filtered", 1100, 700, () -> {
                assertEquals(56, explorer.table().getRowCount());
                assertTrue(explorer.statusText(), explorer.statusText().contains("56 matching of " + DpsInvestigationTest.n(1200)));
                assertTrue(explorer.detailText(), explorer.detailText().contains("Item #200"));
            });
            run(() -> {
                field(explorer, "Minimum damage").setText("99999"); field(explorer, "Maximum damage").setText("");
                named(explorer, "dps-event-apply", JButton.class).doClick();
            });
            windowWideAndCompact(dialog[0], "dps-explorer-empty-filter", 1100, 700, () -> {
                assertEquals(0, explorer.table().getRowCount());
                assertTrue(explorer.statusText(), explorer.statusText().contains("0 matching of " + DpsInvestigationTest.n(1200)));
            });
        } finally { run(() -> { if (dialog[0] != null) dialog[0].dispose(); }); }
    }

    /**
     * Wide frame (whatever width/height the display realizes): the meter's hit details keep at least one full text line
     * visible beneath the table and link row. The compact frame clips the whole meter area and is reported separately.
     */
    private static void assertDetailsUsable(DpsGUI dps) {
        JTextArea details = named(dps, "dps-hit-details", JTextArea.class);
        JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, details);
        int line = details.getFontMetrics(details.getFont()).getHeight();
        java.awt.Rectangle inWindow = SwingUtilities.convertRectangle(viewport, new java.awt.Rectangle(viewport.getSize()), dps);
        JSplitPane split = (JSplitPane) SwingUtilities.getAncestorOfClass(JSplitPane.class, details);
        String diag = " split=" + split.getSize() + " div=" + split.getDividerLocation() + " top=" + split.getTopComponent().getSize() + "/min" + split.getTopComponent().getMinimumSize()
            + " bottom=" + split.getBottomComponent().getSize() + "/min" + split.getBottomComponent().getMinimumSize();
        assertTrue("Hit details visible height " + viewport.getHeight() + " < one line " + line + " (meter " + dps.getSize() + ")" + diag,
            details.isShowing() && viewport.getHeight() >= line && inWindow.y + line <= dps.getHeight());
        JButton explore = named(dps, "dps-explore-events", JButton.class);
        assertTrue("Explore button showing", explore.isShowing() && explore.getWidth() > 0 && explore.getHeight() > 0);
    }

    private static JTextField field(JComponent root, String accessibleName) {
        return VisualEvidence.find(root, JTextField.class, f -> accessibleName.equals(f.getAccessibleContext().getAccessibleName()));
    }
}
