package tomato.gui.dps;

import java.nio.file.Path;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.*;
import tomato.backend.data.TomatoData;
import tomato.gui.activity.ActivityPanel;
import tomato.gui.history.ArchiveWorkspace;
import tomato.history.SessionStore;
import static org.junit.Assert.*;
import static tomato.gui.roster.RosterStateTestSupport.*;

public class DpsResourcesWorkspaceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void resourcesHistoryActionUsesTypedWorkspaceAndAllSessionScope() throws Exception {
        Path root = temp.getRoot().toPath(); SessionStore store = new SessionStore(root.resolve("history"), true, "fixture"); Memory states = new Memory();
        try {
            SwingUtilities.invokeAndWait(() -> {
                ArchiveWorkspace<?, ?, ?> resources = ActivityPanel.workspace(store, new JPanel(), ActivityPanel.Mode.COMBAT, root.resolve("scratch"), states.store);
                DpsGUI dps = new DpsGUI(new TomatoData(), DiscoveryLog.historyView(new ActivityJournal.State()), resources);
                try {
                    assertTrue(dps.browseSavedResources()); assertEquals(SessionStore.ALL, resources.state().query.scope()); assertTrue(resources.state().archive);
                    assertSame(resources, named(dps, "dps-tabs", JTabbedPane.class).getSelectedComponent());
                } finally { resources.close(); }
            });
        } finally { store.close(); }
    }
}
