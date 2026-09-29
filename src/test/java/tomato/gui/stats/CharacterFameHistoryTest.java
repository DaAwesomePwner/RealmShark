package tomato.gui.stats;

import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.backend.data.TomatoData;
import tomato.gui.character.CharacterPanelGUI;
import tomato.gui.glance.character.SheetContext;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.ViewState;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.stats.LootQuery.*;
import tomato.gui.stats.session.FameSessionViewer;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveFixtures;
import tomato.history.archive.ArchiveQuery;
import tomato.history.archive.ArchiveRow;
import tomato.planning.PlanningStore;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.stats.LootDrillDownTest.await;
import static tomato.gui.stats.LootDrillDownTest.edt;

/**
 * Characters › Fame history (P6a Task 9): the saved Character fame view moved from Statistics to an Analyst tab of Characters. A
 * saved-only workspace that offers only Character fame and opens on every saved session; "Open selected session's full fame
 * graph" opens the selected session as on Statistics; the view adds a header line and "Open fame session file…"; the Characters
 * tab builds it on its first selection only, is offered in Analyst only, and closing the app's workspaces reaches it while hidden.
 */
public class CharacterFameHistoryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String[] KEYS = {"ux.archive." + CharacterFameHistory.NAME, "ui.tabs.characters", DisplayModeModel.KEY,
        "ui.filters." + CharacterFameHistory.NAME + ".open", "ux.archive.characters-live-roster", "ui.tabs.character", "ui.filters.characters.open"};
    private final Map<String, String> remembered = new HashMap<>();
    private final List<AutoCloseable> closing = new ArrayList<>();
    private final List<Window> windows = new ArrayList<>();
    private DisplayModeModel.Mode mode;

    @Before public void remember() throws Exception {
        for (String key : KEYS) { remembered.put(key, PropertiesManager.getProperty(key)); PropertiesManager.setProperties(key, ""); }
        mode = edt(() -> DisplayModeModel.application().mode());
    }

    @After public void restore() throws Exception {
        edt(() -> {
            for (AutoCloseable item : closing) item.close();
            for (Window window : windows) window.dispose();
            for (Frame frame : Frame.getFrames()) if (frame instanceof FameSessionViewer) frame.dispose();
            DisplayModeModel.application().set(mode);
            return null;
        });
        for (String key : KEYS) PropertiesManager.setProperties(key, remembered.get(key) == null ? "" : remembered.get(key));
    }

    /** Two saved sessions, each with one synthetic character's fame readings (100 → 125, then 300 → 340). */
    private SessionStore history() throws Exception {
        Path root = temp.newFolder("history").toPath();
        int[][] readings = {{7, 100, 125}, {9, 300, 340}};
        for (int[] character : readings) try (SessionStore source = new SessionStore(root, true, "fame-fixture")) {
            source.append("fame", new AppHistory.FameSample(character[0], character[1], 1000, "Wizard"));
            source.append("fame", new AppHistory.FameSample(character[0], character[2], 121000, "Wizard"));
            source.flush();
        }
        SessionStore store = new SessionStore(root, false, "reader");
        closing.add(store);
        return store;
    }
    private ArchiveWorkspace<Row, Facets, Sort> workspace(SessionStore store, Path scratch, ArchiveNativeSupport.Memory memory) throws Exception {
        ArchiveWorkspace<Row, Facets, Sort> workspace = edt(() -> CharacterFameHistory.workspace(store, scratch, memory.states));
        closing.add(0, workspace::close);
        return workspace;
    }
    private static boolean ready(ArchiveWorkspace<?, ?, ?> workspace) { return !workspace.loading() && workspace.displayedPage() != null; }

    /** A Characters page on an isolated journal and view-state store. */
    private CharacterPanelGUI characters(ArchiveNativeSupport.Memory memory) throws Exception {
        CharacterJournal journal = new CharacterJournal(temp.newFolder().toPath().resolve("journal.json"));
        closing.add(journal);
        TomatoData data = new TomatoData() { @Override public synchronized CharacterJournal characterJournal() { return journal; } };
        return edt(() -> new CharacterPanelGUI(data,
            new SheetContext(data, journal, RosterDefinitions::empty, DisplayModeModel.application(), () -> 5000, PlanningStore.shared()), memory.states));
    }
    private static CustomizableTabs tabs(CharacterPanelGUI panel) {
        return (CustomizableTabs) find(panel, "characters-tabs", JTabbedPane.class).getClientProperty(CustomizableTabs.class);
    }

    @Test public void onlyCharacterFameIsOfferedAndItOpensOnEverySavedSession() {
        assertEquals("character-fame", CharacterFameHistory.NAME);
        assertEquals(EnumSet.of(View.FAME), CharacterFameHistory.VIEWS);
        ArchiveQuery<Facets, Sort> initial = CharacterFameHistory.initialQuery();
        assertEquals("Every saved session", SessionStore.ALL, initial.scope());
        assertEquals(View.FAME, initial.facets().view);
        assertEquals(LootQuery.initial(View.FAME, SessionStore.ALL).toJson(), initial.toJson());
        LootArchiveClient client = CharacterFameHistory.client(temp.getRoot().toPath());
        assertEquals("Only Character fame", EnumSet.of(View.FAME), client.views());
        assertEquals(initial.toJson(), client.initialQuery().toJson());
    }

    @Test public void theWorkspaceIsSavedOnlyAndListsEachSessionsCharacterFame() throws Exception {
        SessionStore store = history();
        ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory();
        // A remembered state that asks for the live view: a saved-only workspace still reads saved history.
        memory.states.save(CharacterFameHistory.NAME, ViewState.initial(CharacterFameHistory.initialQuery()).withArchive(false));
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store, temp.newFolder("scratch").toPath(), memory);
        await(() -> ready(workspace) && workspace.displayedPage().matches == 2);
        edt(() -> {
            assertEquals("character-fame-session-view", workspace.getName());
            assertTrue(workspace.savedOnly());
            assertTrue(workspace.state().archive);
            assertEquals(SessionStore.ALL, workspace.state().query.scope());
            assertEquals(View.FAME, workspace.state().query.facets().view);
            assertEquals("Only Character fame is offered", List.of("Character fame"), offeredViews(workspace));
            assertNull("No Live item in a saved-only Scope menu", ArchiveNativeSupport.scope(workspace).item("live"));
            assertEquals("Scope: Saved · all sessions", ArchiveNativeSupport.scope(workspace).getText());
            assertEquals("Its own filter row", "character-fame-filter-bar", workspace.filterBar().getName());
            Map<Integer, Double> gains = new TreeMap<>();
            for (ArchiveRow<Row> row : workspace.displayedPage().rows) gains.put(row.value.character, row.value.gain);
            assertEquals("First and last reading per session and character", Map.of(7, 25.0, 9, 40.0), gains);
            assertNotNull("The fame graph button is kept", find(workspace, "archive-open-fame", JButton.class));
            return null;
        });
    }

    /** Polish B1: with one view there is nothing to choose, so the saved view's selector row is not shown (it listed "Character fame" alone). */
    @Test public void theOneViewWorkspaceShowsNoViewSelector() throws Exception {
        SessionStore store = history();
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store, temp.newFolder("scratch").toPath(), new ArchiveNativeSupport.Memory());
        await(() -> ready(workspace) && workspace.displayedPage().matches == 2);
        edt(() -> {
            JComponent row = find(workspace, "loot-archive-view-row", JComponent.class);
            assertFalse("The one-view selector row is hidden", row.isVisible());
            assertFalse("…so its selector is not shown within the workspace", visibleWithin(find(workspace, "loot-archive-view", JComboBox.class), workspace));
            assertTrue("The table is shown", visibleWithin(find(workspace, "loot-archive-table", JTable.class), workspace));
            return null;
        });
    }
    /**
     * B7: fame is filtered by Character ID only (dungeon selection and loot facets do not filter fame), so the drawer has no dungeon
     * field and the row no loot chips; and while the columns fit, Name takes the table's spare width, which no layout records.
     */
    @Test public void fameFiltersAreCharacterIdOnlyAndNameTakesTheSpareWidth() throws Exception {
        SessionStore store = history();
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store, temp.newFolder("scratch").toPath(), new ArchiveNativeSupport.Memory());
        JComponent view = edt(() -> CharacterFameHistory.view(() -> workspace, () -> { }));
        JFrame frame = edt(() -> {
            JFrame shown = new JFrame("Character fame filters fixture");
            windows.add(shown);
            shown.setContentPane(view);
            shown.setSize(1240, 800);
            shown.setVisible(true);
            return shown;
        });
        await(() -> ready(workspace) && workspace.displayedPage().matches == 2);
        edt(() -> {
            JComponent drawer = workspace.filterBar().drawerContent();
            assertNotNull(drawer);
            assertNull("No dungeon field for fame", label(drawer, "Dungeons (semicolon-separated)"));
            assertNotNull("Character ID only", label(drawer, "Character ID"));
            Facets f = workspace.state().query.facets(); f.character = "7"; f.dungeons.add("Lost Halls"); f.kind = Kind.UT_EQUIPMENT;
            workspace.changeQuery(workspace.state().query.withFacets(f));
            return null;
        });
        await(() -> ready(workspace) && workspace.displayedPage().matches == 1);
        edt(() -> {
            assertEquals("Loot facets do not filter fame, so they are no chips", List.of("Character ID 7"), ArchiveNativeSupport.chipLabels(workspace.filterBar()));
            return null;
        });
        await(() -> {
            JTable table = find(workspace, "loot-archive-table", JTable.class);
            return table.isShowing() && table.getWidth() == table.getParent().getWidth();
        });
        edt(() -> {
            JTable table = find(workspace, "loot-archive-table", JTable.class);
            int others = 0; javax.swing.table.TableColumn name = null;
            for (javax.swing.table.TableColumn column : Collections.list(table.getColumnModel().getColumns()))
                if ("name".equals(column.getIdentifier())) name = column; else others += column.getWidth();
            assertNotNull(name);
            assertEquals("Name takes the spare width", table.getParent().getWidth() - others, name.getWidth());
            assertFalse("No sideways scroll bar", ((JScrollPane) table.getParent().getParent()).getHorizontalScrollBar().isShowing());
            assertFalse("The fitted width is not saved as a layout", workspace.state().tables.containsKey(View.FAME.name()));
            return null;
        });
        // A layout saved for another reason (the user widens Fame change) records Name's own width, and Name keeps filling.
        int fittedName = edt(() -> column(find(workspace, "loot-archive-table", JTable.class), "name").getWidth());
        edt(() -> { javax.swing.table.TableColumn gain = column(find(workspace, "loot-archive-table", JTable.class), "gain"); gain.setWidth(gain.getWidth() + 20); return null; });
        await(() -> workspace.state().tables.containsKey(View.FAME.name()));
        int savedName = edt(() -> { for (ViewState.Column saved : workspace.state().tables.get(View.FAME.name()).columns) if ("name".equals(saved.id)) return saved.width; return -1; });
        assertTrue("The saved layout keeps Name's own width, not the fitted " + fittedName + ": " + savedName, savedName > 0 && savedName < fittedName);
        await(() -> fills(find(workspace, "loot-archive-table", JTable.class), savedName));
        // Narrower: the fitted width goes, Name keeps at least its own width (a font change or resize never locks the fit in).
        edt(() -> { frame.setSize(700, 800); return null; });
        await(() -> fills(find(workspace, "loot-archive-table", JTable.class), savedName));
        assertTrue("Narrower, Name gives the fitted width back", edt(() -> column(find(workspace, "loot-archive-table", JTable.class), "name").getWidth()) < fittedName);
    }
    /** Name is as wide as the spare width, or its own width when nothing is spare. */
    private static boolean fills(JTable table, int own) {
        int others = 0; for (javax.swing.table.TableColumn column : Collections.list(table.getColumnModel().getColumns())) if (!"name".equals(column.getIdentifier())) others += column.getWidth();
        return table.isShowing() && column(table, "name").getWidth() == Math.max(own, table.getParent().getWidth() - others);
    }
    private static javax.swing.table.TableColumn column(JTable table, String id) {
        for (javax.swing.table.TableColumn column : Collections.list(table.getColumnModel().getColumns())) if (id.equals(column.getIdentifier())) return column;
        throw new AssertionError("No column " + id);
    }
    private static JLabel label(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel && text.equals(((JLabel) child).getText())) return (JLabel) child;
            if (child instanceof Container) { JLabel found = label((Container) child, text); if (found != null) return found; }
        }
        return null;
    }

    /** Every component from {@code component} up to {@code root} is visible (the workspace is not in a window, so isShowing is false). */
    private static boolean visibleWithin(Component component, Container root) {
        for (Component c = component; c != null && c != root; c = c.getParent()) if (!c.isVisible()) return false;
        return true;
    }

    @Test public void openFameOpensTheSelectedSessionsFullGraph() throws Exception {
        SessionStore store = history();
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store, temp.newFolder("scratch").toPath(), new ArchiveNativeSupport.Memory());
        JComponent view = edt(() -> CharacterFameHistory.view(() -> workspace, () -> { }));
        edt(() -> {
            JFrame frame = new JFrame("Character fame fixture");
            windows.add(frame);
            frame.setContentPane(view);
            frame.setSize(1240, 800);
            frame.setVisible(true);
            return null;
        });
        await(() -> ready(workspace) && workspace.displayedPage().matches == 2);
        String session = edt(() -> {
            find(workspace, "loot-archive-table", JTable.class).setRowSelectionInterval(1, 1);
            find(workspace, "archive-open-fame", JButton.class).doClick();
            return workspace.displayedPage().rows.get(1).value.session;
        });
        AtomicReference<FameSessionViewer> opened = new AtomicReference<>();
        await(() -> {
            for (Frame frame : Frame.getFrames()) if (frame instanceof FameSessionViewer && frame.isShowing()) opened.set((FameSessionViewer) frame);
            return opened.get() != null;
        });
        edt(() -> {
            assertTrue("The selected row's whole pinned session: " + opened.get().getTitle(), opened.get().getTitle().endsWith(session));
            opened.get().dispose();
            return null;
        });
    }

    @Test public void theViewAddsTheHeaderLineAndOpenFameSessionFile() throws Exception {
        SessionStore store = history();
        ArchiveWorkspace<Row, Facets, Sort> workspace = workspace(store, temp.newFolder("scratch").toPath(), new ArchiveNativeSupport.Memory());
        AtomicInteger supplied = new AtomicInteger(), opened = new AtomicInteger();
        JComponent view = edt(() -> CharacterFameHistory.view(() -> { supplied.incrementAndGet(); return workspace; }, opened::incrementAndGet));
        edt(() -> {
            assertEquals(1, supplied.get());
            assertTrue(SwingUtilities.isDescendingFrom(workspace, view));
            JTextArea header = find(view, "character-fame-header", JTextArea.class);
            assertEquals("Character fame per saved session: first and last reading, gain and observed time.", header.getText());
            AbstractButton open = find(view, "character-fame-open-file", AbstractButton.class);
            assertEquals("Open fame session file…", open.getText());
            assertEquals("Nothing opens until it is chosen", 0, opened.get());
            open.doClick();
            assertEquals("It runs the open-file action (FameSessionViewer.openSessionViewer in the app)", 1, opened.get());
            return null;
        });
    }

    @Test public void theCharactersTabIsBuiltOnItsFirstSelectionAndOfferedOnlyInAnalyst() throws Exception {
        ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory();
        edt(() -> { DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE); return null; });
        CharacterPanelGUI plain = characters(memory);
        edt(() -> {
            assertEquals("No history store, no tab", List.of("roster", "exalts", "pets"), tabs(plain).order());
            return null;
        });
        CharacterPanelGUI panel = characters(memory);
        AtomicInteger built = new AtomicInteger();
        edt(() -> {
            JPanel content = new JPanel();
            panel.hostFame(() -> { built.incrementAndGet(); return content; });
            CustomizableTabs tabs = tabs(panel);
            JTabbedPane strip = tabs.component();
            assertEquals("After Pets", List.of("roster", "exalts", "pets", "fame-history"), tabs.order());
            assertEquals("Skipped in Simple", List.of("roster", "exalts", "pets"), tabs.visibleIds());
            assertEquals(-1, strip.indexOfTab("Fame history"));
            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            assertEquals("Shown in Analyst", 3, strip.indexOfTab("Fame history"));
            assertEquals("Roster", strip.getTitleAt(strip.getSelectedIndex()));
            assertEquals("Not built at construction or when offered", 0, built.get());
            tabs.select("fame-history");
            assertEquals("Built on its first selection", 1, built.get());
            assertTrue(SwingUtilities.isDescendingFrom(content, strip));
            tabs.select("roster");
            tabs.select("fame-history");
            DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
            DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
            tabs.select("fame-history");
            assertEquals("Built once", 1, built.get());
            try { panel.hostFame(JPanel::new); fail("Hosted once"); } catch (IllegalStateException expected) { }
            return null;
        });
    }

    @Test public void closingTheAppsWorkspacesReachesTheBuiltWorkspaceWhileTheTabIsHidden() throws Exception {
        SessionStore store = history();
        Path scratch = temp.newFolder("scratch").toPath();
        ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory();
        edt(() -> { DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST); return null; });
        CharacterPanelGUI panel = characters(memory);
        AtomicReference<ArchiveWorkspace<Row, Facets, Sort>> made = new AtomicReference<>();
        Supplier<JComponent> factory = () -> CharacterFameHistory.view(() -> {
            made.set(CharacterFameHistory.workspace(store, scratch, memory.states));
            return made.get();
        }, () -> { });
        edt(() -> {
            panel.hostFame(factory);
            assertNull("Nothing is read before the tab is chosen", made.get());
            tabs(panel).select("fame-history");
            return null;
        });
        ArchiveWorkspace<Row, Facets, Sort> workspace = made.get();
        assertNotNull("Built on the tab's first selection", workspace);
        closing.add(0, workspace::close);
        await(() -> ready(workspace) && workspace.displayedPage().matches == 2);
        ArchiveQuery<Facets, Sort> before = edt(() -> {
            DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
            assertFalse("Skipped in Simple, the tab is detached", SwingUtilities.isDescendingFrom(workspace, panel));
            // TomatoGUI.closeWorkspace's walk: it visits every CustomizableTabs' contents, so a detached tab's workspace is closed.
            java.lang.reflect.Method close = tomato.gui.TomatoGUI.class.getDeclaredMethod("closeArchiveWorkspaces", Component.class);
            close.setAccessible(true);
            close.invoke(null, panel);
            return workspace.state().query;
        });
        edt(() -> {
            workspace.changeQuery(before.withText("after close"));
            assertEquals("A closed workspace takes no query", before, workspace.state().query);
            return null;
        });
        long until = System.nanoTime() + 20_000_000_000L;
        while (ArchiveFixtures.children(scratch) > 0 && System.nanoTime() < until) Thread.sleep(20);
        assertEquals("Its pinned results are released", 0, ArchiveFixtures.children(scratch));
    }

    /**
     * The views the saved workspace offers, by title: the {@code loot-archive-tabs} titles, or the items of the
     * {@code loot-archive-view} selector that replaces them (P6a Task 8), whichever the client renders.
     */
    private static List<String> offeredViews(Container root) {
        List<String> titles = new ArrayList<>();
        JTabbedPane tabs = search(root, "loot-archive-tabs", JTabbedPane.class);
        if (tabs != null) { for (int i = 0; i < tabs.getTabCount(); i++) titles.add(tabs.getTitleAt(i)); return titles; }
        JComboBox<?> selector = find(root, "loot-archive-view", JComboBox.class);
        for (int i = 0; i < selector.getItemCount(); i++) if (selector.getItemAt(i) instanceof View) titles.add(selector.getItemAt(i).toString());
        return titles;
    }

    private static <T extends Component> T find(Container root, String name, Class<T> type) {
        T found = search(root, name, type);
        if (found == null) throw new AssertionError("No " + type.getSimpleName() + " named " + name);
        return found;
    }
    private static <T extends Component> T search(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = search((Container) child, name, type); if (found != null) return found; }
        }
        return null;
    }
}
