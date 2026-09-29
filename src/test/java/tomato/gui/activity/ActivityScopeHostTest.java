package tomato.gui.activity;

import java.nio.file.Path;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.history.ArchiveNativeSupport.Memory;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.LiveFilterHost;
import tomato.gui.history.ScopeChip;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.FilterBarAssert;
import tomato.gui.modern.TestPages;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.runs.RunFeedView;
import tomato.gui.runs.RunsDpsPage;
import tomato.gui.runs.RunsPage;
import tomato.gui.runs.RunsTab;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveQuery;
import ui.VisualEvidence;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.activity.ActivityArchiveUiTest.*;

/**
 * P6b Task 11: Runs (its Table view), Timeline and Resources lend their live filter row to their workspace's Scope chip, so each
 * page keeps one filter row; Runs' live row offers the Cards view in its ⋯. Synthetic history, in-memory view states only.
 */
public class ActivityScopeHostTest {
    private static final ActivityPanel.Mode[] MODES = ActivityPanel.Mode.values();
    /** Drawer-open keys of every bar here, the mode, the Runs view and the tab orders the pages read. */
    private static final String[] KEYS = {"ui.filters.activity-runs.open", "ui.filters.activity-timeline.open", "ui.filters.activity-combat.open",
        "ui.filters.runs.open", "ui.filters.timeline.open", "ui.filters.combat.open", "ui.filters.run-feed.open", DisplayModeModel.KEY,
        RunFeedView.VIEW_KEY, "ui.tabs.runs", "ui.tabs.activity-combat", "ui.tabs.saved-resources"};
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence("p6b-activity");
    private final Map<String, String> saved = new HashMap<>();
    private DisplayModeModel.Mode mode;

    @Before public void isolate() throws Exception {
        for (String key : KEYS) saved.put(key, PropertiesManager.getProperty(key));
        for (String key : KEYS) PropertiesManager.setProperties(key, key.startsWith("ui.filters.") ? "false" : "");
        mode = edt(() -> DisplayModeModel.application().mode());
    }
    @After public void restore() throws Exception {
        edt(() -> { DisplayModeModel.application().set(mode); return null; });
        for (String key : KEYS) PropertiesManager.setProperties(key, saved.get(key) == null ? "" : saved.get(key));
    }

    private static String module(ActivityPanel.Mode which) { return which == ActivityPanel.Mode.RUNS ? "runs" : which == ActivityPanel.Mode.TIMELINE ? "timeline" : "combat"; }
    private static String bar(ActivityPanel.Mode which) { return "activity-" + which.name().toLowerCase(Locale.ROOT) + "-filter-bar"; }

    @Test public void eachModesLiveRowHostsTheChipWhileLiveAndTheWorkspaceRowWhileSaved() throws Exception {
        try (Fixture f = new Fixture()) {
            for (ActivityPanel.Mode which : MODES) {
                ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> workspace = f.workspace(which);
                edt(() -> {
                    assertTrue(which + ": the live panel lends its row", (Object) f.live(which) instanceof LiveFilterHost);
                    FilterBar live = workspace.liveFilterBar();
                    assertNotNull(which + ": a live row", live);
                    assertEquals(bar(which), live.getName());
                    assertTrue(which + ": the row is the live panel's own", SwingUtilities.isDescendingFrom(live, f.live(which)));
                    ScopeChip chip = ArchiveNativeSupport.scope(workspace);
                    assertTrue(which + ": the chip sits in the live row", SwingUtilities.isDescendingFrom(chip, live));
                    assertFalse(which + ": the workspace row hides while live", workspace.filterBar().isVisible());
                    assertEquals("Scope: Live", chip.getText().trim());
                    FilterBarAssert.assertChipInVisibleBar(workspace);
                    workspace.showSaved(ArchiveQuery.CURRENT);
                    return null;
                });
                await(() -> ArchiveNativeSupport.ready(workspace));
                edt(() -> {
                    assertTrue(workspace.state().archive);
                    assertTrue(which + ": the workspace row shows while saved", workspace.filterBar().isVisible());
                    assertTrue(which + ": the chip moves into it", SwingUtilities.isDescendingFrom(ArchiveNativeSupport.scope(workspace), workspace.filterBar()));
                    FilterBarAssert.assertChipInVisibleBar(workspace);
                    ArchiveNativeSupport.scopeItem(workspace, "live").doClick();
                    assertFalse(workspace.state().archive);
                    assertTrue(which + ": live again, the chip returns", SwingUtilities.isDescendingFrom(ArchiveNativeSupport.scope(workspace), workspace.liveFilterBar()));
                    FilterBarAssert.assertChipInVisibleBar(workspace);
                    return null;
                });
            }
        }
    }

    @Test public void theLiveCardsItemFollowsTheMode() throws Exception {
        try (Fixture f = new Fixture()) {
            ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> runs = f.workspace(ActivityPanel.Mode.RUNS);
            RunFeedView feed = edt(() -> new RunFeedView(runs, () -> f.store));
            try {
                edt(() -> {
                    DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
                    JPopupMenu menu = runs.liveFilterBar().overflow().menu();
                    JMenuItem live = runs.liveFilterBar().overflow().item("Cards view"), own = runs.filterBar().overflow().item("Cards view");
                    assertNotNull("The live row's ⋯ offers the Cards view", live);
                    assertEquals("run-feed-live-cards-item", live.getName());
                    assertSame("First in the live row's ⋯", live, menu.getComponent(0));
                    assertEquals("run-feed-cards-item", own.getName());
                    assertTrue(live.isVisible()); assertTrue(own.isVisible());
                    assertTrue("The live row's ⋯ shows while live", runs.liveFilterBar().overflow().isVisible());
                    feed.showTable();
                    live.doClick();
                    assertFalse("Cards view from the live row", feed.tableShown());
                    assertEquals(RunFeedView.CARDS, PropertiesManager.getProperty(RunFeedView.VIEW_KEY));
                    DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
                    assertFalse("Analyst: the toggle above the views replaces both items", live.isVisible());
                    assertFalse(own.isVisible());
                    DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
                    assertTrue(live.isVisible()); assertTrue(own.isVisible());
                    return null;
                });
            } finally { edt(() -> { feed.close(); return null; }); }
        }
    }

    @Test public void everyLiveRowIsOneFilterRowAt1240x800Font13InBothModes() throws Exception {
        try (Fixture f = new Fixture()) {
            JTabbedPane meter = edt(() -> {
                // The Live meter's nesting (DpsGUI's "Damage meters" and "Resources & buffs" tabs) around the Resources workspace.
                JTabbedPane tabs = new JTabbedPane(); tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
                tabs.addTab("Damage meters", new JPanel()); tabs.addTab("Resources & buffs", f.workspace(ActivityPanel.Mode.COMBAT)); return tabs;
            });
            RunsDpsPage page = edt(() -> new RunsDpsPage(new RunsPage(f.workspace(ActivityPanel.Mode.RUNS), () -> f.store), meter));
            WorkspaceShell shell = edt(() -> {
                Map<String, JComponent> pages = TestPages.placeholders();
                pages.put("timeline", f.workspace(ActivityPanel.Mode.TIMELINE)); pages.put("runs", page);
                return TestPages.shell(pages);
            });
            try {
                for (DisplayModeModel.Mode shown : DisplayModeModel.Mode.values()) {
                    String suffix = shown.name().toLowerCase(Locale.ROOT);
                    edt(() -> { DisplayModeModel.application().set(shown); evidence.show(shell, "Activity filter rows", 1240, 800, 13); shell.select("timeline"); return null; });
                    evidence.settle();
                    edt(() -> { evidence.capture("timeline-live-" + suffix); assertRow(f, ActivityPanel.Mode.TIMELINE); shell.select("runs"); page.bring(RunsTab.FEED); page.feed().feed().showTable(); return null; });
                    evidence.settle();
                    edt(() -> { evidence.capture("runs-table-live-" + suffix); assertRow(f, ActivityPanel.Mode.RUNS); page.bring(RunsTab.LIVE_METER); meter.setSelectedIndex(1); return null; });
                    evidence.settle();
                    edt(() -> { evidence.capture("resources-live-" + suffix); assertRow(f, ActivityPanel.Mode.COMBAT); return null; });
                }
            } finally { edt(() -> { evidence.closeWindow(); page.close(); return null; }); }
        }
    }

    private static void assertRow(Fixture f, ActivityPanel.Mode which) {
        ArchiveWorkspace<?, ?, ?> workspace = f.workspace(which);
        FilterBar bar = workspace.liveFilterBar();
        assertNotNull(which + ": a live row", bar);
        assertTrue(which + ": the row shows", bar.isShowing());
        assertTrue(which + ": the row has the page's content width (" + bar.getWidth() + ")", bar.getWidth() > 800 && bar.getWidth() < 1100);
        FilterBarAssert.assertOneRow(bar);
        FilterBarAssert.assertChipInVisibleBar(workspace);
        ScopeChip chip = ArchiveNativeSupport.scope(workspace);
        assertTrue(which + ": the chip shows", chip.isShowing());
        assertFalse(which + ": at this width the chip keeps the scope slot", SwingUtilities.isDescendingFrom(chip, bar.searchSlot()));
    }

    /** Live panels (an empty live log) in their workspaces over two synthetic saved sessions, with in-memory view states. */
    private final class Fixture implements AutoCloseable {
        final SessionStore store; final DiscoveryLog log;
        private final Map<ActivityPanel.Mode, ActivityPanel> live = new EnumMap<>(ActivityPanel.Mode.class);
        private final Map<ActivityPanel.Mode, ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort>> workspaces = new EnumMap<>(ActivityPanel.Mode.class);
        Fixture() throws Exception {
            Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath(); ActivityNativeFixtures.seed(root);
            Memory memory = new Memory();
            store = new SessionStore(root, true, "native-reader"); log = new DiscoveryLog(null); log.setSaving(false);
            edt(() -> {
                for (ActivityPanel.Mode which : MODES) {
                    ActivityPanel panel = new ActivityPanel(log, which); live.put(which, panel);
                    workspaces.put(which, ActivityPanel.workspace(store, panel, which, scratch.resolve(module(which)), memory.states));
                }
                return null;
            });
        }
        ActivityPanel live(ActivityPanel.Mode which) { return live.get(which); }
        ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> workspace(ActivityPanel.Mode which) { return workspaces.get(which); }
        @Override public void close() throws Exception {
            try { edt(() -> { for (ArchiveWorkspace<?, ?, ?> workspace : workspaces.values()) workspace.close(); return null; }); } finally { log.close(); store.close(); }
        }
    }
}
