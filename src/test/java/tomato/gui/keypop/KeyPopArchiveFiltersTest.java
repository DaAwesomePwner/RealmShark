package tomato.gui.keypop;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.history.*;
import tomato.history.SessionStore;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.*;

/** Saved contribution facets live in the drawer; mode tabs and exports stay in the view. */
public class KeyPopArchiveFiltersTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void contributionFacetsMoveToTheDrawerAndBecomeChips() throws Exception {
        Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath(); ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory(); String session;
        try (SessionStore source = new SessionStore(root, true, "keypop-filters")) {
            session = source.currentId(); Instant base = Instant.parse("2026-09-22T12:00:00Z");
            source.append("keypops", new KeyPopEvent(base, "Ann", "Lost Halls", KeyPopEvent.Kind.KEY));
            source.append("keypops", new KeyPopEvent(base.plusSeconds(1), "Bo", "Ice Citadel", KeyPopEvent.Kind.VIAL));
            source.flush();
        }
        try (SessionStore store = new SessionStore(root, false, "reader")) {
            ArchiveWorkspace<KeyPopArchiveClient.Row, KeyPopArchiveClient.Facets, KeyPopArchiveClient.Sort> ws =
                edt(() -> SessionPanel.queried(store, "keypops", new JPanel(), new KeyPopArchiveClient(scratch), memory.states));
            try {
                edt(() -> { ws.changeQuery(KeyPopArchiveClient.query().withScope(session)); return null; });
                await(() -> ArchiveNativeSupport.ready(ws) && ws.displayedPage().matches == 2);
                edt(() -> {
                    JComponent drawer = ws.filterBar().drawerContent();
                    assertTrue(SwingUtilities.isDescendingFrom(named(ws, "keypop-archive-player", JTextField.class), drawer));
                    assertFalse("Mode tabs stay in the view", SwingUtilities.isDescendingFrom(named(ws, "keypop-archive-tabs", JTabbedPane.class), drawer));
                    KeyPopArchiveClient.Facets f = ws.state().query.facets(); f.exactPlayer = "Ann"; f.kinds.add("KEY");
                    ws.changeQuery(ws.state().query.withFacets(f)); return null; });
                await(() -> ArchiveNativeSupport.ready(ws) && ws.displayedPage().matches == 1);
                edt(() -> { assertEquals(Arrays.asList("Player: Ann", "Types: Key"), ArchiveNativeSupport.chipLabels(ws.filterBar()));
                    ArchiveNativeSupport.removeChip(ws.filterBar(), "Player: Ann"); return null; });
                await(() -> ArchiveNativeSupport.ready(ws) && ws.state().query.facets().exactPlayer.isEmpty());
                edt(() -> { assertEquals(Collections.singleton("KEY"), ws.state().query.facets().kinds); return null; });
            } finally { edt(() -> { ws.close(); return null; }); }
        }
    }
}
