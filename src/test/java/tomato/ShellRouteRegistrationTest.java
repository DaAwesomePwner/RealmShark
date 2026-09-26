package tomato;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;
import tomato.gui.stats.LootQuery;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveQuery;
import tomato.history.link.VisitRef;

import javax.swing.*;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Production composition installs the shell navigator with the analytics targets ahead of generic ones. */
public class ShellRouteRegistrationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void createdWorkspaceRegistersAnalyticsTargetsAndCloseUninstallsTheNavigator() throws Exception {
        Field storeField = AppHistory.class.getDeclaredField("store"); storeField.setAccessible(true);
        Object previous = storeField.get(null);
        Field previewField = Tomato.class.getDeclaredField("preview"); previewField.setAccessible(true);
        Object previousPreview = previewField.get(null); previewField.set(null, true);
        String temporaryDirectory = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", temp.newFolder("scratch").getAbsolutePath());
        SessionStore store = new SessionStore(temp.newFolder().toPath(), false, "synthetic");
        TomatoGUI gui = new TomatoGUI(new TomatoData());
        AtomicReference<JComponent> shell = new AtomicReference<>();
        try {
            storeField.set(null, store);
            VisitRef visit = new VisitRef(store.currentId(), "journal:1");
            SwingUtilities.invokeAndWait(() -> {
                shell.set(gui.createWorkspace());
                Navigator navigator = Navigator.current();
                assertNotSame(Navigator.NONE, navigator);
                assertTrue("Loot resolves an exact visit through the analytics target",
                    navigator.canOpen(Route.to(Destination.LOOT).withVisit(visit)));
                ArchiveQuery<LootQuery.Facets, LootQuery.Sort> query = ArchiveQuery.of(ArchiveQuery.CURRENT, new LootQuery.Facets(), LootQuery.Facets.class, LootQuery.Sort.TIME);
                assertTrue(navigator.canOpen(Route.to(Destination.STATISTICS).withQuery(query)));
                assertFalse("Statistics cannot resolve a visit and says so by rejecting it",
                    navigator.canOpen(Route.to(Destination.STATISTICS).withVisit(visit)));
                assertTrue(navigator.canOpen(Route.to(Destination.LOGGING).withPayload(tomato.gui.logging.LoggingRouteTarget.issuesFor(Destination.RUNS))));
                assertFalse("Only allowlisted packets of the affected view are routable",
                    navigator.canOpen(Route.to(Destination.LOGGING).withPayload(tomato.gui.logging.LoggingRouteTarget.packetFor(Destination.RUNS, "TEXT"))));
            });
            gui.closeWorkspace();
            SwingUtilities.invokeAndWait(() -> assertSame(Navigator.NONE, Navigator.current()));
        } finally {
            gui.closeWorkspace();
            SwingUtilities.invokeAndWait(() -> { if (shell.get() != null) shell.get().removeNotify(); });
            System.setProperty("java.io.tmpdir", temporaryDirectory);
            storeField.set(null, previous); previewField.set(null, previousPreview); store.close();
        }
    }
}
