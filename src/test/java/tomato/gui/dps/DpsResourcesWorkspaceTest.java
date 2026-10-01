package tomato.gui.dps;

import java.nio.file.Path;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.*;
import tomato.backend.data.TomatoData;
import tomato.gui.activity.ActivityPanel;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.route.*;
import tomato.gui.runs.*;
import tomato.history.SessionStore;
import static org.junit.Assert.*;
import static tomato.gui.roster.RosterStateTestSupport.*;

public class DpsResourcesWorkspaceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public final RunsViewRule runsView = RunsViewRule.cards();
    private String savedRuns;
    @Before public void isolateTabs() {
        savedRuns = util.PropertiesManager.getProperty("ui.tabs.runs");
        util.PropertiesManager.setProperties("ui.tabs.runs", "");
    }
    @After public void restoreTabs() { util.PropertiesManager.setProperties("ui.tabs.runs", savedRuns == null ? "" : savedRuns); }
    @Test public void resourcesHistoryActionUsesTypedWorkspaceAndAllSessionScope() throws Exception {
        Path root = temp.getRoot().toPath(); SessionStore store = new SessionStore(root.resolve("history"), true, "fixture"); Memory states = new Memory();
        try {
            SwingUtilities.invokeAndWait(() -> {
                ArchiveWorkspace<?, ?, ?> resources = ActivityPanel.workspace(store, new JPanel(), ActivityPanel.Mode.COMBAT, root.resolve("scratch"), states.store);
                String savedTabs = util.PropertiesManager.getProperty("ui.tabs.dps");
                util.PropertiesManager.setProperties("ui.tabs.dps", "resources,meters|meters");
                DpsGUI dps = new DpsGUI(new TomatoData(), DiscoveryLog.historyView(new ActivityJournal.State()), resources);
                RunsDpsPage page = new RunsDpsPage(new RunsPage(new JPanel(), () -> null), dps);
                page.setContent(RunsTab.RESOURCES, resources);
                RouteTarget target = dps.resourcesRouteTarget();
                page.owner(RunsTab.RESOURCES, target);
                String[] selected = {"runs"};
                ShellNavigator navigator = new ShellNavigator(() -> selected[0], value -> selected[0] = value,
                    tomato.gui.modern.WorkspaceShell::pageOf, 20);
                navigator.register(page.routes(RunsTab.RESOURCES, target));
                navigator.register(page.tabTarget());
                Navigator previous = Navigator.current();
                Navigator.install(navigator);
                try {
                    page.bring(RunsTab.LIVE_METER);
                    assertTrue(dps.browseSavedResources()); assertEquals(SessionStore.ALL, resources.state().query.scope()); assertTrue(resources.state().archive);
                    assertEquals(RunsTab.RESOURCES, page.selectedTab());
                    assertTrue(SwingUtilities.isDescendingFrom(resources, page.tabs().component().getSelectedComponent()));
                    assertNull("No nested meter tabs remain", named(dps, "dps-tabs", JTabbedPane.class));
                    assertEquals("Stale DPS preferences are ignored", "resources,meters|meters", util.PropertiesManager.getProperty("ui.tabs.dps"));
                    assertTrue(navigator.back());
                    assertEquals(RunsTab.LIVE_METER, page.selectedTab());
                    assertTrue(navigator.open(Route.to(Destination.RESOURCES)));
                    resources.selectSession(store.currentId());
                    com.google.gson.JsonObject before = resources.state().toJson();
                    assertTrue(dps.browseSavedResources());
                    assertEquals(SessionStore.ALL, resources.state().query.scope());
                    assertTrue(navigator.back());
                    assertEquals(RunsTab.RESOURCES, page.selectedTab());
                    assertEquals("Back restores Resources' previous scope and view", before, resources.state().toJson());
                } finally {
                    Navigator.install(previous);
                    page.close();
                    util.PropertiesManager.setProperties("ui.tabs.dps", savedTabs == null ? "" : savedTabs);
                }
            });
        } finally { store.close(); }
    }
}
