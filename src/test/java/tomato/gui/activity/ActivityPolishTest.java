package tomato.gui.activity;

import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.history.ArchiveNativeSupport.Memory;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.SessionPanel;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.modern.CollectionControl;
import tomato.gui.modern.Themes;
import tomato.history.SessionStore;
import tomato.history.archive.ArchivePage;
import static org.junit.Assert.*;
import static tomato.gui.activity.ActivityArchiveUiTest.*;
import static tomato.gui.activity.ActivityLiveStateTest.retainedLog;

/**
 * P6b Polish C: saved Runs, Party, Resources and Timeline keep IDs and revisions out of Simple (spec §3.2), "Cancel linked export"
 * is enabled only while a linked export runs, the live summary does not repeat the status line's collection checkbox, and the
 * collection wording names Party, and Resources' sample scroll stays borderless through a live theme switch. Synthetic history and an
 * in-memory display mode and view states only; the dark theme is installed again afterwards.
 */
public class ActivityPolishTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final Map<String, String> prefs = new HashMap<>();
    /** Simple until a test switches it; never the application's mode. */
    private final DisplayModeModel mode = new DisplayModeModel(prefs::get, prefs::put);

    private SessionStore store(Path root) throws Exception {
        SessionStore store = new SessionStore(root, true, "synthetic");
        for (int i = 1; i <= 7; i++) store.put("runs", "v" + i, ActivityArchiveTest.visit("v" + i, i));
        for (int i = 1; i <= 4; i++) store.append("timeline", ActivityArchiveTest.event("v1", i));
        store.flush(); return store;
    }
    private ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> saved(SessionStore store, ActivityPanel.Mode which) throws Exception {
        ActivityArchiveClient client = new ActivityArchiveClient(which, temp.newFolder().toPath(), null, mode);
        String name = which == ActivityPanel.Mode.RUNS ? "runs" : which == ActivityPanel.Mode.TIMELINE ? "timeline" : "combat";
        ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> workspace =
            edt(() -> SessionPanel.queried(store, name, new JLabel("Live " + name), client, new Memory().states));
        edt(() -> { workspace.showSaved(); return null; });
        await(() -> ArchiveNativeSupport.ready(workspace) && named(workspace, JTextArea.class, "activity-archive-counts") != null);
        return workspace;
    }

    @Test public void savedCountLinesShowThePinnedRevisionInAnalystOnly() throws Exception {
        try (SessionStore store = store(temp.newFolder().toPath())) {
            for (ActivityPanel.Mode which : ActivityPanel.Mode.values()) {
                ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> workspace = saved(store, which);
                try {
                    edt(() -> {
                        ArchivePage<ActivityQueries.Row> page = workspace.displayedPage();
                        String pinned = "pinned " + page.revision.substring(0, 8);
                        JTextArea counts = named(workspace, JTextArea.class, "activity-archive-counts");
                        String plain = page.rows.size() + " displayed / " + page.matches + " matching " + page.unit + " · page 1";
                        assertTrue(which + " Simple keeps the plain count: " + counts.getText(), counts.getText().startsWith(plain));
                        assertFalse(which + " Simple hides the revision: " + counts.getText(), counts.getText().contains(page.revision.substring(0, 8)));
                        assertFalse(which + " Simple: no pinned wording", counts.getText().contains("pinned"));
                        mode.set(DisplayModeModel.Mode.ANALYST);
                        assertTrue(which + " Analyst shows the pinned revision: " + counts.getText(), counts.getText().contains(pinned));
                        assertTrue(which + " Analyst keeps the count", counts.getText().startsWith(plain));
                        mode.set(DisplayModeModel.Mode.SIMPLE);
                        assertFalse(which + " the line rebinds on a mode change", counts.getText().contains("pinned"));
                        if (which == ActivityPanel.Mode.RUNS)
                            assertTrue("Runs keeps its linked-evidence note in Simple", counts.getText().contains("Export selected visit + Timeline below includes full linked evidence."));
                        return null;
                    });
                } finally { edt(() -> { workspace.close(); mode.set(DisplayModeModel.Mode.SIMPLE); return null; }); }
            }
        }
    }

    @Test public void cancelLinkedExportIsEnabledOnlyWhileALinkedExportRuns() throws Exception {
        try (SessionStore store = store(temp.newFolder().toPath())) {
            for (ActivityPanel.Mode which : new ActivityPanel.Mode[]{ActivityPanel.Mode.RUNS, ActivityPanel.Mode.COMBAT}) {
                ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> workspace = saved(store, which);
                try {
                    edt(() -> {
                        assertFalse(which + ": nothing to cancel before an export", button(workspace, "Cancel linked export").isEnabled());
                        table(workspace).setRowSelectionInterval(0, 0);
                        assertTrue(button(workspace, "Export selected visit + Timeline…").isEnabled());
                        assertFalse(which + ": a selection alone starts no export", button(workspace, "Cancel linked export").isEnabled());
                        return null;
                    });
                    SwingUtilities.invokeLater(() -> button(workspace, "Export selected visit + Timeline…").doClick());
                    await(() -> ArchiveNativeSupport.dialog("Export selected visit and linked evidence") != null);
                    JDialog preview = edt(() -> ArchiveNativeSupport.dialog("Export selected visit and linked evidence"));
                    try {
                        assertTrue(which + ": the running export can be cancelled", edt(() -> button(workspace, "Cancel linked export").isEnabled()));
                        assertFalse(edt(() -> button(workspace, "Export selected visit + Timeline…").isEnabled()));
                    } finally { edt(() -> { button(preview, "Cancel").doClick(); return null; }); }
                    await(() -> !preview.isShowing() && button(workspace, "Export selected visit + Timeline…").isEnabled());
                    assertFalse(which + ": disabled again once the export ends", edt(() -> button(workspace, "Cancel linked export").isEnabled()));
                } finally { edt(() -> { workspace.close(); return null; }); }
            }
        }
    }

    @Test public void theLiveSummaryDoesNotRepeatTheCollectionCheckbox() throws Exception {
        ActivityJournal.State history = new ActivityJournal.State();
        ActivityJournal.Visit visit = ActivityArchiveTest.visit("synthetic-visit", 1); visit.started = System.currentTimeMillis() - 60_000; history.visits.add(visit);
        try (DiscoveryLog log = retainedLog(temp.newFolder().toPath(), history)) {
            for (ActivityPanel.Mode which : ActivityPanel.Mode.values()) {
                ActivityPanel panel = edt(() -> { ActivityPanel view = new ActivityPanel(log, which, mode); view.refresh(); return view; });
                edt(() -> {
                    CollectionControl record = named(panel, CollectionControl.class, null);
                    JComponent line = named(panel, JComponent.class, "activity-status-line");
                    assertTrue(which + ": the checkbox is on the status line", record.isVisible() && SwingUtilities.isDescendingFrom(record, line));
                    String summary = named(panel, JLabel.class, "activity-summary").getText();
                    assertFalse(which + ": the summary does not repeat the checkbox: " + summary, summary.contains("Gameplay & diagnostics collection"));
                    assertTrue(which + ": the counts stay: " + summary, summary.contains(which == ActivityPanel.Mode.RUNS ? "dungeon runs" : "retained events"));
                    checkbox(panel, "Pause this view").doClick();
                    summary = named(panel, JLabel.class, "activity-summary").getText();
                    assertTrue(which + ": a paused view stays labeled: " + summary, summary.contains("View paused (collection state is current)"));
                    assertFalse(summary.contains("Gameplay & diagnostics collection"));
                    checkbox(panel, "Pause this view").doClick();
                    return null;
                });
            }
        }
        // Without the checkbox (a legacy saved page), the summary keeps saying what it shows.
        ActivityPanel legacy = edt(() -> { ActivityPanel view = new ActivityPanel(DiscoveryLog.historyView(history), ActivityPanel.Mode.RUNS, mode); view.refresh(); return view; });
        edt(() -> {
            assertFalse(named(legacy, CollectionControl.class, null).isVisible());
            assertTrue(named(legacy, JLabel.class, "activity-summary").getText().contains("Saved history"));
            return null;
        });
    }

    /**
     * Polish B's finding: a scroll pane whose border is null gets the look and feel's outline back on a live theme switch (nested frames
     * in the light theme). Resources' sample summary keeps an empty border through dark → light.
     */
    @Test public void theResourceSampleScrollStaysBorderlessThroughALiveThemeSwitch() throws Exception {
        ActivityJournal.State history = new ActivityJournal.State(); history.visits.add(ActivityArchiveTest.visit("synthetic-visit", 1));
        try (DiscoveryLog log = retainedLog(temp.newFolder().toPath(), history)) {
            edt(() -> {
                Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
                ActivityPanel panel = new ActivityPanel(log, ActivityPanel.Mode.COMBAT, mode);
                JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, named(panel, JTextArea.class, "combat-sample-summary"));
                assertEquals("dark: no outline", new Insets(0, 0, 0, 0), scroll.getInsets());
                Themes.install(new Themes.Choice(Themes.Variant.LIGHT, false));
                SwingUtilities.updateComponentTreeUI(panel);
                assertBorderless("after the switch to light", scroll);
                return null;
            });
        } finally { edt(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false))); }
    }
    /** No outline: an empty, non-UIResource border (the look and feel replaces only null or UIResource borders). */
    static void assertBorderless(String when, JComponent scroll) {
        javax.swing.border.Border border = scroll.getBorder();
        assertNotNull(when + ": an explicit empty border, not null (which the look and feel refills)", border);
        assertFalse(when + ": not the look and feel's border: " + border, border instanceof javax.swing.plaf.UIResource);
        assertEquals(when + ": no outline", new Insets(0, 0, 0, 0), border.getBorderInsets(scroll));
    }

    @Test public void theCollectionEffectNamesPartyBuilds() {
        assertTrue(CollectionControl.EFFECT, CollectionControl.EFFECT.contains("recorded Party builds"));
        assertFalse("Inspect is Party now", CollectionControl.EFFECT.contains("Inspect"));
    }

    private static AbstractButton button(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton && text.equals(((AbstractButton) child).getText())) return (AbstractButton) child;
            if (child instanceof Container) { AbstractButton found = button((Container) child, text); if (found != null) return found; }
        }
        return null;
    }
    private static JCheckBox checkbox(Container root, String text) {
        AbstractButton found = button(root, text); return found instanceof JCheckBox ? (JCheckBox) found : null;
    }
}
