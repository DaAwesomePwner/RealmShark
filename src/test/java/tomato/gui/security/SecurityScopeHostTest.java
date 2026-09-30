package tomato.gui.security;

import java.beans.PropertyChangeEvent;
import java.nio.file.Path;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.activity.ActivityNativeFixtures;
import tomato.gui.activity.ActivityQueries;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.history.ArchiveNativeSupport.Memory;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.LiveFilterHost;
import tomato.gui.history.ScopeChip;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.FilterBarAssert;
import tomato.gui.modern.TestPages;
import tomato.history.SessionStore;
import ui.VisualEvidence;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.activity.ActivityArchiveUiTest.*;
import static tomato.gui.modern.FormattingTestSupport.field;

/** P6b Task 7: Party lends each tab's own filter row to its workspace's Scope chip while live. Synthetic history only. */
public class SecurityScopeHostTest {
    private static final String[][] TABS = {{"area", "inspect-roster-filter-bar"}, {"runs", "inspect-runs-filter-bar"}, {"ability", "ability-filter-bar"}};
    /** Drawer-open keys of every bar on the page, the mode, and the live roster's application view state (SecurityGUI(log) binds it). */
    private static final String[] KEYS = {"ui.filters.inspect-roster.open", "ui.filters.inspect-runs.open", "ui.filters.ability.open",
        "ui.filters.inspect.open", DisplayModeModel.KEY, "ux.archive.inspect-live-roster"};
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence("p6b-party");
    private final Map<String, String> saved = new HashMap<>();
    private DisplayModeModel.Mode mode;

    @Before public void isolate() throws Exception {
        for (String key : KEYS) saved.put(key, PropertiesManager.getProperty(key));
        for (String key : KEYS) if (key.startsWith("ui.filters.")) PropertiesManager.setProperties(key, "false");
        mode = edt(() -> DisplayModeModel.application().mode());
    }
    @After public void restore() throws Exception {
        edt(() -> { DisplayModeModel.application().set(mode); ParsePanelGUI.clear(); return null; });
        for (String key : KEYS) PropertiesManager.setProperties(key, saved.get(key) == null ? "" : saved.get(key));
    }

    @Test public void eachTabsOwnBarHostsTheChipWhileLiveAndTheChipFollowsTheTab() throws Exception {
        try (Fixture f = new Fixture()) {
            edt(() -> {
                DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
                assertTrue("SecurityGUI lends its tab's bar", f.live instanceof LiveFilterHost);
                for (String[] tab : TABS) {
                    tabs(f.live).select(tab[0]);
                    FilterBar bar = f.workspace.liveFilterBar();
                    assertNotNull(tab[0] + " has a live bar", bar);
                    assertEquals(tab[0], tab[1], bar.getName());
                    assertTrue(tab[0] + ": the bar is the tab's own", SwingUtilities.isDescendingFrom(bar, tabs(f.live).component().getSelectedComponent()));
                    assertTrue(tab[0] + ": the chip sits in that bar", SwingUtilities.isDescendingFrom(ArchiveNativeSupport.scope(f.workspace), bar));
                    assertFalse(tab[0] + ": the workspace row hides while a tab bar hosts the chip", f.workspace.filterBar().isVisible());
                    FilterBarAssert.assertChipInVisibleBar(f.workspace);
                }
                tabs(f.live).select("runs");
                assertTrue("Back to Runs: the chip follows",
                    SwingUtilities.isDescendingFrom(ArchiveNativeSupport.scope(f.workspace), named(f.live, FilterBar.class, "inspect-runs-filter-bar")));
                return null;
            });
        }
    }

    @Test public void barFiresOncePerFinalSelectionAndNeverDuringATabRebuild() throws Exception {
        try (Fixture f = new Fixture()) {
            List<PropertyChangeEvent> events = new ArrayList<>(); List<Boolean> rebuilding = new ArrayList<>();
            edt(() -> {
                DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
                f.live.addPropertyChangeListener(LiveFilterHost.BAR, e -> { events.add(e); rebuilding.add(tabs(f.live).isRebuilding()); });
                tabs(f.live).select("runs");
                assertEquals("One event for the switch to Runs", 1, events.size());
                assertEquals("inspect-roster-filter-bar", ((FilterBar) events.get(0).getOldValue()).getName());
                assertEquals("inspect-runs-filter-bar", ((FilterBar) events.get(0).getNewValue()).getName());
                tabs(f.live).select("runs");
                assertEquals("Selecting the shown tab again fires nothing", 1, events.size());
                tabs(f.live).select("ability");
                assertEquals(2, events.size());
                // Simple hides the selected Analyst tab: the rebuild passes through transient selections, then lands on Current Area.
                DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
                return null;
            });
            edt(() -> null);   // the deferred final-selection sync
            edt(() -> {
                assertEquals("One event for the rebuild's final selection: " + events.size(), 3, events.size());
                assertEquals("ability-filter-bar", ((FilterBar) events.get(2).getOldValue()).getName());
                assertEquals("inspect-roster-filter-bar", ((FilterBar) events.get(2).getNewValue()).getName());
                assertEquals("No event while the tabs rebuild", Arrays.asList(false, false, false), rebuilding);
                assertSame(events.get(2).getNewValue(), f.workspace.liveFilterBar());
                FilterBarAssert.assertChipInVisibleBar(f.workspace);
                return null;
            });
        }
    }

    @Test public void everyTabIsOneFilterRowAt1240x800Font13InTheShell() throws Exception {
        try (Fixture f = new Fixture()) {
            JComponent shell = edt(() -> TestPages.shell("party", f.workspace));
            try {
                edt(() -> { DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST); evidence.show(shell, "Party filter rows", 1240, 800, 13); return null; });
                for (String[] tab : TABS) {
                    edt(() -> { tabs(f.live).select(tab[0]); return null; });
                    evidence.settle();
                    edt(() -> {
                        evidence.capture("party-" + tab[0] + "-filter-row");
                        FilterBar bar = f.workspace.liveFilterBar();
                        assertEquals(tab[1], bar.getName());
                        assertTrue(tab[0] + ": the bar has the shell's content width (" + bar.getWidth() + ")", bar.getWidth() > 850 && bar.getWidth() < 1100);
                        FilterBarAssert.assertOneRow(bar);
                        FilterBarAssert.assertChipInVisibleBar(f.workspace);
                        ScopeChip chip = ArchiveNativeSupport.scope(f.workspace);
                        System.out.println("Party " + tab[0] + " bar width=" + bar.getWidth() + ", search slot=" + bar.searchSlot().getWidth()
                            + ", chip=" + chip.getWidth() + ", chip x=" + SwingUtilities.convertPoint(chip, 0, 0, bar).x);
                        assertTrue(tab[0] + ": the chip shows", chip.isShowing());
                        assertFalse(tab[0] + ": at this width the chip keeps the scope slot, not the search slot",
                            SwingUtilities.isDescendingFrom(chip, bar.searchSlot()));
                        return null;
                    });
                }
            } finally { edt(() -> { evidence.closeWindow(); return null; }); }
        }
    }

    @Test public void savedModeKeepsTheChipInTheWorkspaceRowOnEveryTab() throws Exception {
        try (Fixture f = new Fixture()) {
            edt(() -> { DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST); tabs(f.live).select("runs"); f.workspace.selectSession(SessionStore.ALL); return null; });
            await(() -> ArchiveNativeSupport.ready(f.workspace));
            edt(() -> {
                assertTrue(f.workspace.state().archive);
                for (String[] tab : TABS) {
                    tabs(f.live).select(tab[0]);
                    assertTrue(tab[0] + ": the workspace row shows while saved", f.workspace.filterBar().isVisible());
                    assertTrue(tab[0] + ": the chip stays in the workspace row", SwingUtilities.isDescendingFrom(ArchiveNativeSupport.scope(f.workspace), f.workspace.filterBar()));
                    assertEquals("The tab's bar is still the live row", tab[1], f.workspace.liveFilterBar().getName());
                    FilterBarAssert.assertChipInVisibleBar(f.workspace);
                }
                ArchiveNativeSupport.scopeItem(f.workspace, "live").doClick();
                assertFalse(f.workspace.state().archive);
                assertTrue("Live again: the chip returns to the shown tab's bar",
                    SwingUtilities.isDescendingFrom(ArchiveNativeSupport.scope(f.workspace), named(f.live, FilterBar.class, "ability-filter-bar")));
                FilterBarAssert.assertChipInVisibleBar(f.workspace);
                return null;
            });
        }
    }

    static CustomizableTabs tabs(SecurityGUI live) { return field(live, "tabs", CustomizableTabs.class); }

    /** A live Party page (empty live log) in its workspace over two synthetic saved sessions, with in-memory view states. */
    private final class Fixture implements AutoCloseable {
        final SessionStore store; final DiscoveryLog log; final SecurityGUI live;
        final ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> workspace;
        Fixture() throws Exception {
            Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath(); ActivityNativeFixtures.seed(root);
            Memory memory = new Memory();
            store = new SessionStore(root, true, "native-reader"); log = new DiscoveryLog(null); log.setSaving(false);
            live = edt(() -> new SecurityGUI(log));
            workspace = edt(() -> SecurityGUI.workspace(store, live, scratch, memory.states));
        }
        @Override public void close() throws Exception {
            try { edt(() -> { workspace.close(); return null; }); } finally { log.close(); store.close(); }
        }
    }
}
