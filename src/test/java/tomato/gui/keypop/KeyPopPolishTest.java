package tomato.gui.keypop;

import java.awt.Color;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.TableColumn;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.SessionPanel;
import tomato.gui.history.ViewState;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.modern.ContentStyle;
import tomato.history.SessionStore;
import tomato.history.archive.ArchivePage;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.*;

/**
 * P6b Polish C: saved Key-pops keeps the pinned revision out of Simple (spec §3.2), the live footer's bounds and denominator lines
 * are Analyst-only, and saved Events shows Type as the live tone badges in the live column order. Synthetic pops and an in-memory
 * display mode and view states only.
 */
public class KeyPopPolishTest {
    private static final String SAVED_TABS = "ui.tabs.keypops-saved";
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final Map<String, String> prefs = new HashMap<>();
    /** Simple until a test switches it; never the application's mode. */
    private final DisplayModeModel mode = new DisplayModeModel(prefs::get, prefs::put);
    private String savedTabs;
    private final Instant now = Instant.now();

    @Before public void isolate() { savedTabs = PropertiesManager.getProperty(SAVED_TABS); PropertiesManager.setProperties(SAVED_TABS, ""); }
    @After public void restore() { PropertiesManager.setProperties(SAVED_TABS, savedTabs == null ? "" : savedTabs); }

    private KeyPopEvent[] pops() {
        return new KeyPopEvent[]{new KeyPopEvent(now.minusSeconds(480), "Echo", "Ice Citadel", KeyPopEvent.Kind.KEY),
            new KeyPopEvent(now.minusSeconds(720), "Kai", "Inc", KeyPopEvent.Kind.INC), new KeyPopEvent(now.minusSeconds(960), "Aster", "Shield Rune", KeyPopEvent.Kind.RUNE),
            new KeyPopEvent(now.minusSeconds(1200), "Nova", "Vial", KeyPopEvent.Kind.VIAL)};
    }
    private final class Fixture implements AutoCloseable {
        final SessionStore store; final ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory(); final Path scratch;
        ArchiveWorkspace<KeyPopArchiveClient.Row, KeyPopArchiveClient.Facets, KeyPopArchiveClient.Sort> workspace;
        Fixture() throws Exception {
            Path root = temp.newFolder().toPath(); scratch = temp.newFolder().toPath();
            store = new SessionStore(root, true, "synthetic-keypops");
            for (KeyPopEvent pop : pops()) store.append("keypops", pop);
            store.flush(); open();
            edt(() -> { workspace.changeQuery(KeyPopArchiveClient.query().withScope(SessionStore.ALL)); return null; });
            await(() -> ArchiveNativeSupport.ready(workspace) && workspace.displayedPage().matches == 4);
        }
        void open() throws Exception {
            KeyPopArchiveClient client = new KeyPopArchiveClient(scratch, mode);
            workspace = edt(() -> SessionPanel.queried(store, "keypops", new JPanel(), client, memory.states));
        }
        @Override public void close() throws Exception { try { edt(() -> { workspace.close(); return null; }); } finally { store.close(); } }
    }

    @Test public void theSavedCountLineShowsThePinnedRevisionInAnalystOnly() throws Exception {
        try (Fixture f = new Fixture()) {
            edt(() -> {
                ArchivePage<KeyPopArchiveClient.Row> page = f.workspace.displayedPage();
                String revision = page.revision.substring(0, 8);
                JTextArea note = named(f.workspace, "keypop-archive-population", JTextArea.class);
                assertTrue("Simple keeps the plain count: " + note.getText(), note.getText().startsWith("4 displayed / 4 matching pop events · page 1"));
                assertFalse("Simple hides the revision: " + note.getText(), note.getText().contains(revision));
                assertFalse(note.getText().contains(" · pinned "));
                assertTrue("The denominator line stays", note.getText().contains("4 matching observed pop events across the whole query"));
                mode.set(DisplayModeModel.Mode.ANALYST);
                assertTrue("Analyst shows the pinned revision: " + note.getText(), note.getText().contains("pinned " + revision));
                mode.set(DisplayModeModel.Mode.SIMPLE);
                assertFalse("The line rebinds on a mode change", note.getText().contains(revision));
                return null;
            });
        }
    }

    @Test public void theLiveFootersBoundsAndDenominatorAreAnalystOnly() throws Exception {
        edt(() -> {
            KeyPopHistory history = new KeyPopHistory(); for (KeyPopEvent pop : pops()) history.add(pop);
            KeyPopDashboard ui = new KeyPopDashboard(history, false, mode);
            JTextArea period = named(ui, "keypop-live-resolved-period", JTextArea.class);
            assertTrue("The status line stays in Simple", ui.status.getText().startsWith("4 shown / 4 retained"));
            assertFalse("Simple hides the bounds and denominator lines", period.isVisible());
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertTrue("Analyst shows them", period.isVisible());
            assertTrue(period.getText(), period.getText().contains("until exclusive") && period.getText().contains("Share denominator: 4 matching retained pop events"));
            ui.search.setText("Echo");
            assertTrue("Analyst's lines follow the filters", period.getText().contains("Share denominator: 1 matching"));
            mode.set(DisplayModeModel.Mode.SIMPLE);
            assertFalse("Hidden again in Simple", period.isVisible());
            return null;
        });
    }

    @Test public void savedEventsShowTypeAsLiveToneBadgesInLiveColumnOrder() throws Exception {
        List<String> liveHeaders = new ArrayList<>(); Map<String, Color> liveInk = new HashMap<>();
        edt(() -> {
            KeyPopHistory history = new KeyPopHistory(); for (KeyPopEvent pop : pops()) history.add(pop);
            KeyPopDashboard ui = new KeyPopDashboard(history, false, mode);
            for (int c = 0; c < ui.events.getColumnCount(); c++) liveHeaders.add(ui.events.getColumnName(c));
            int type = liveHeaders.indexOf("Type");
            for (int row = 0; row < ui.events.getRowCount(); row++) {
                JLabel cell = (JLabel) ui.events.prepareRenderer(ui.events.getCellRenderer(row, type), row, type);
                liveInk.put(String.valueOf(ui.events.getValueAt(row, type)), cell.getForeground());
            }
            return null;
        });
        assertEquals(Arrays.asList("Time", "Player", "Type", "Dungeon / item"), liveHeaders);
        try (Fixture f = new Fixture()) {
            edt(() -> {
                JTable rows = named(f.workspace, "keypop-archive-rows", JTable.class);
                List<String> headers = new ArrayList<>(); for (int c = 0; c < rows.getColumnCount(); c++) headers.add(rows.getColumnName(c));
                assertEquals("Saved Events uses the live order", liveHeaders, headers);
                int type = rows.getColumnModel().getColumnIndex("kind");
                assertTrue("Type is a tone badge", rows.getCellRenderer(0, type) instanceof ContentStyle.Badge);
                for (int row = 0; row < rows.getRowCount(); row++) {
                    JLabel cell = (JLabel) rows.prepareRenderer(rows.getCellRenderer(row, type), row, type);
                    String value = String.valueOf(rows.getValueAt(row, type));
                    assertEquals(value + " has the live badge's tone", liveInk.get(value), cell.getForeground());
                }
                assertEquals("The model keeps the type text", "Key", rows.getValueAt(0, type));
                return null;
            });
            // A layout saved before the reorder (Player, Dungeon / item, Time, Type) is restored as saved: layouts name columns by ID.
            ViewState<KeyPopArchiveClient.Facets, KeyPopArchiveClient.Sort> state = edt(() -> f.workspace.state());
            List<ViewState.Column> old = Arrays.asList(new ViewState.Column("player", 150, true), new ViewState.Column("item", 170, true),
                new ViewState.Column("time", 160, true), new ViewState.Column("kind", 110, true));
            ViewState<KeyPopArchiveClient.Facets, KeyPopArchiveClient.Sort> stored = state.withTable(KeyPopArchiveClient.Mode.EVENTS.name(), new ViewState.Table("Custom", old));
            edt(() -> { f.workspace.close(); return null; });
            f.memory.states.save("keypops", stored).toCompletableFuture().get();
            f.open();
            await(() -> ArchiveNativeSupport.ready(f.workspace) && named(f.workspace, "keypop-archive-rows", JTable.class) != null);
            edt(() -> {
                JTable rows = named(f.workspace, "keypop-archive-rows", JTable.class);
                List<Object> ids = new ArrayList<>(); for (Enumeration<TableColumn> e = rows.getColumnModel().getColumns(); e.hasMoreElements();) ids.add(e.nextElement().getIdentifier());
                assertEquals("The stored order wins", Arrays.asList("player", "item", "time", "kind"), ids);
                assertEquals(170, rows.getColumnModel().getColumn(1).getPreferredWidth());
                return null;
            });
        }
    }
}
