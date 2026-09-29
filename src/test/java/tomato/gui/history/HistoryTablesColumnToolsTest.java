package tomato.gui.history;

// Explicit AWT imports: java.awt.* would make ArchiveFixtures.Event ambiguous with java.awt.Event.
import java.awt.Component;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javax.swing.table.TableColumn;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.KitTables;
import tomato.gui.kit.OverflowMenu;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import static org.junit.Assert.*;
import static tomato.history.archive.ArchiveFixtures.*;

/**
 * The ⋯ column tools (Columns ▸, Column preset ▸, Reset columns, Copy, Row details) and the mode-hidden layout rules: a column
 * {@code KitTables.analystOnly} hides in Simple is never saved, preset or reset as hidden by the user. Detached mode models and
 * synthetic tables and history only; no preference is written.
 */
public class HistoryTablesColumnToolsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void modeResizeSaveModeKeepsTheColumnVisible() throws Exception {
        DisplayModeModel mode = mode(DisplayModeModel.Mode.ANALYST);
        List<ViewState.Table> saves = new ArrayList<>();
        JTable table = edt(() -> {
            JTable t = table("rows");
            HistoryTables.rememberLayout(t, saves::add);
            KitTables.analystOnly(t, mode, "session");
            mode.set(DisplayModeModel.Mode.SIMPLE);
            return t;
        });
        edt(() -> {
            assertTrue("A mode switch saves no layout", saves.isEmpty());
            assertEquals(List.of("area", "summary"), order(table));
            column(table, "area").setWidth(300);   // the user's resize, in Simple
            return null;
        });
        edt(() -> {
            assertEquals("One save for the resize", 1, saves.size());
            ViewState.Table saved = saves.get(0);
            assertEquals("The mode-hidden column keeps its place", List.of("area", "session", "summary"), ids(saved));
            assertTrue("…and is saved as the user left it: shown", entry(saved, "session").visible);
            assertEquals(300, entry(saved, "area").width);
            mode.set(DisplayModeModel.Mode.ANALYST);
            return null;
        });
        edt(() -> {
            assertEquals("Switching back saves nothing", 1, saves.size());
            assertEquals(List.of("area", "session", "summary"), order(table));
            // The layout saved in Simple, restored by a later render in Analyst, shows the column.
            JTable rerendered = table("rows");
            KitTables.analystOnly(rerendered, mode, "session");
            HistoryTables.applyColumns(rerendered, saves.get(0));
            assertEquals(List.of("area", "session", "summary"), order(rerendered));
            assertEquals(300, column(rerendered, "area").getWidth());
            return null;
        });
    }

    @Test public void aPresetAndResetInSimpleLeaveModeHiddenColumnsAlone() throws Exception {
        DisplayModeModel mode = mode(DisplayModeModel.Mode.ANALYST);
        List<ViewState.Table> saves = new ArrayList<>();
        edt(() -> {
            JTable table = table("rows");
            KitTables.analystOnly(table, mode, "session");
            ViewState.Table defaults = HistoryTables.columnState(table, "Default");
            HistoryTables.ColumnTools tools = HistoryTables.columnTools(table, defaults, Collections.singletonMap("Compact", List.of("area")), saves::add);
            mode.set(DisplayModeModel.Mode.SIMPLE);
            radio(tools, "Compact").doClick();
            assertEquals(List.of("area"), order(table));
            ViewState.Table preset = last(saves);
            assertEquals("Compact", preset.preset);
            assertTrue("A preset in Simple leaves the mode-hidden column as it was", entry(preset, "session").visible);
            assertFalse(entry(preset, "summary").visible);
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertEquals(List.of("area", "session"), order(table));
            mode.set(DisplayModeModel.Mode.SIMPLE);
            tools.reset().doClick();
            assertEquals(List.of("area", "summary"), order(table));
            assertTrue(entry(last(saves), "session").visible);
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertEquals(List.of("area", "session", "summary"), order(table));
            // The user's own hide (in Analyst) survives a Reset in Simple.
            tools.refresh();
            item(tools.columns(), "rows-column-session").doClick();
            assertEquals(List.of("area", "summary"), order(table));
            mode.set(DisplayModeModel.Mode.SIMPLE);
            tools.reset().doClick();
            assertFalse("Reset in Simple keeps the user's hide", entry(last(saves), "session").visible);
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertEquals(List.of("area", "summary"), order(table));
            return null;
        });
    }

    @Test public void aRestoredLayoutShowsInAnalyst() throws Exception {
        ViewState.Table saved = new ViewState.Table("Custom", List.of(new ViewState.Column("area", 200, true),
            new ViewState.Column("session", 180, true), new ViewState.Column("summary", 150, true)));
        edt(() -> {
            DisplayModeModel analyst = mode(DisplayModeModel.Mode.ANALYST);
            JTable direct = table("rows");
            KitTables.analystOnly(direct, analyst, "session");
            HistoryTables.applyColumns(direct, saved);
            assertEquals(List.of("area", "session", "summary"), order(direct));
            assertEquals(180, column(direct, "session").getWidth());

            // Restored while Simple hides it: the column comes back in Analyst with the layout's width, not its old one.
            DisplayModeModel simple = mode(DisplayModeModel.Mode.SIMPLE);
            JTable later = table("rows");
            KitTables.analystOnly(later, simple, "session");
            HistoryTables.applyColumns(later, saved);
            assertEquals(List.of("area", "summary"), order(later));
            assertEquals(200, column(later, "area").getWidth());
            assertEquals("A restore in Simple keeps the hidden column's saved state", saved.columns.size(),
                HistoryTables.columnState(later, "Custom").columns.size());
            assertTrue(entry(HistoryTables.columnState(later, "Custom"), "session").visible);
            assertEquals(180, entry(HistoryTables.columnState(later, "Custom"), "session").width);
            simple.set(DisplayModeModel.Mode.ANALYST);
            assertEquals(List.of("area", "session", "summary"), order(later));
            assertEquals(180, column(later, "session").getWidth());

            // A layout that hides the column keeps it hidden when Analyst would bring it back.
            DisplayModeModel other = mode(DisplayModeModel.Mode.SIMPLE);
            JTable hidden = table("rows");
            KitTables.analystOnly(hidden, other, "session");
            HistoryTables.applyColumns(hidden, new ViewState.Table("Custom", List.of(new ViewState.Column("area", 200, true),
                new ViewState.Column("session", 180, false), new ViewState.Column("summary", 150, true))));
            other.set(DisplayModeModel.Mode.ANALYST);
            assertEquals(List.of("area", "summary"), order(hidden));
            assertFalse(entry(HistoryTables.columnState(hidden, "Custom"), "session").visible);
            return null;
        });
    }

    @Test public void analystColumnsAreDisabledInSimpleAndTheLastVisibleGuardCountsShownColumns() throws Exception {
        DisplayModeModel mode = mode(DisplayModeModel.Mode.SIMPLE);
        List<ViewState.Table> saves = new ArrayList<>();
        edt(() -> {
            JTable table = table("rows");
            KitTables.analystOnly(table, mode, "session");
            HistoryTables.ColumnTools tools = HistoryTables.columnTools(table, HistoryTables.columnState(table, "Default"), Collections.emptyMap(), saves::add);
            assertEquals("rows-columns", tools.columns().getName());
            assertEquals("rows-column-preset", tools.presets().getName());
            assertEquals("rows-reset-columns", tools.reset().getName());
            tools.refresh();
            JMenuItem session = item(tools.columns(), "rows-column-session");
            assertEquals("Session (Analyst)", session.getText());
            assertFalse("A mode-hidden column is not toggled in Simple", session.isEnabled());
            assertTrue("…and shows the user's choice", session.isSelected());
            assertTrue(item(tools.columns(), "rows-column-area").isEnabled());
            // The guard counts shown columns: Area is the last one, though Session is the user's choice too.
            item(tools.columns(), "rows-column-summary").doClick();
            assertEquals(List.of("area"), order(table));
            int saved = saves.size();
            JMenuItem area = item(tools.columns(), "rows-column-area");
            area.doClick();
            assertEquals("The last shown column stays", List.of("area"), order(table));
            assertEquals("…and nothing is saved", saved, saves.size());
            assertTrue(item(tools.columns(), "rows-column-area").isSelected());
            mode.set(DisplayModeModel.Mode.ANALYST);
            tools.refresh();
            assertEquals("Session", item(tools.columns(), "rows-column-session").getText());
            assertTrue(item(tools.columns(), "rows-column-session").isEnabled());
            return null;
        });
    }

    @Test public void theIntegerRendererShowsADashForNullAndNoGrouping() throws Exception {
        edt(() -> {
            List<Object[]> rows = new ArrayList<>();
            rows.add(new Object[]{null});
            rows.add(new Object[]{2591});
            JTable table = HistoryTables.table("ids", new String[]{"Item ID"}, new Class<?>[]{Integer.class}, rows);
            // One shared stamp renders every cell: read each cell's text before rendering the next.
            JLabel empty = (JLabel) table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0);
            assertEquals("—", empty.getText());
            JLabel id = (JLabel) table.prepareRenderer(table.getCellRenderer(1, 0), 1, 0);
            assertEquals("IDs are never grouped", "2591", id.getText());
            assertEquals(SwingConstants.RIGHT, id.getHorizontalAlignment());
            assertEquals("The model keeps the raw value", 2591, table.getValueAt(1, 0));
            return null;
        });
    }

    @Test public void theWorkspaceSwapsTheToolsSectionOnEachRenderAndDisablesItWhileStale() throws Exception {
        Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath(); String id = session(root, 40);
        ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory();
        try (SessionStore store = new SessionStore(root, false, "test")) {
            Tooled client = new Tooled(id, scratch);
            ArchiveWorkspace<Event, Facets, Sort> workspace = edt(() -> SessionPanel.queried(store, "tooled", new JLabel("Live"), client, memory.states));
            try {
                edt(() -> { workspace.showSaved(); return null; });
                ArchiveNativeSupport.await(() -> ArchiveNativeSupport.ready(workspace) && client.renders == 1);
                HistoryTables.ColumnTools first = edt(() -> {
                    OverflowMenu more = ArchiveNativeSupport.more(workspace);
                    assertEquals(1, count(more, "tooled-rows-columns"));
                    assertSame(client.tools, owner(more));
                    for (String label : new String[]{"Columns", "Column preset", "Reset columns", "Copy selected rows", "Row details…"})
                        assertNotNull("⋯ " + label, more.item(label));
                    assertTrue(more.item("Columns").isEnabled());
                    assertEquals("Refresh stays first", "Refresh", ((JMenuItem) more.menu().getComponent(0)).getText());
                    HistoryTables.ColumnTools shown = client.tools;
                    workspace.refresh();
                    assertFalse("A stale view's tools are disabled", more.item("Columns").isEnabled());
                    assertFalse(more.item("Reset columns").isEnabled());
                    return shown;
                });
                ArchiveNativeSupport.await(() -> ArchiveNativeSupport.ready(workspace) && client.renders == 2);
                edt(() -> {
                    OverflowMenu more = ArchiveNativeSupport.more(workspace);
                    assertEquals("Swapped, not duplicated", 1, count(more, "tooled-rows-columns"));
                    assertEquals(1, count(more, "tooled-rows-reset-columns"));
                    assertNotSame(first, client.tools);
                    assertSame(client.tools, owner(more));
                    assertTrue(more.item("Columns").isEnabled());
                    workspace.selectSession(ArchiveQuery.CURRENT);   // live
                    assertNull("Live has no saved table tools", more.item("Columns"));
                    assertEquals(0, count(more, "tooled-rows-reset-columns"));
                    return null;
                });
            } finally { edt(() -> { workspace.close(); return null; }); }
        }
    }

    @Test public void controlsStayAThinWrapperThatStillSavesLayouts() throws Exception {
        List<ViewState.Table> saves = new ArrayList<>();
        JTable table = edt(() -> {
            JTable t = table("wrapped");
            JComponent controls = HistoryTables.controls(t, HistoryTables.columnState(t, "Default"), Collections.singletonMap("Compact", List.of("area")), saves::add);
            JComboBox<?> preset = null;
            for (Component c : controls.getComponents()) if (c instanceof JComboBox) preset = (JComboBox<?>) c;
            assertNotNull(preset);
            assertEquals("Column preset", preset.getAccessibleContext().getAccessibleName());
            preset.setSelectedItem("Compact");
            assertEquals(List.of("area"), order(t));
            column(t, "area").setWidth(310);
            return t;
        });
        edt(() -> {
            assertEquals("Compact", saves.get(0).preset);
            assertEquals("The layout listener still saves a resize", "Custom", last(saves).preset);
            assertEquals(310, entry(last(saves), "area").width);
            assertEquals(List.of("area"), order(table));
            return null;
        });
    }

    // ---- fixtures ----

    /** Area, Session and Summary (IDs area, session, summary) at the default 240/145/145 px, as HistoryTables builds tables. */
    private static JTable table(String name) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"Nexus", "s1", "first"});
        rows.add(new Object[]{"Realm", "s2", "second"});
        JTable table = HistoryTables.table(name, new String[]{"Area", "Session", "Summary"}, new Class<?>[]{String.class, String.class, String.class}, rows);
        String[] ids = {"area", "session", "summary"};
        for (int i = 0; i < ids.length; i++) table.getColumnModel().getColumn(i).setIdentifier(ids[i]);
        return table;
    }

    private static DisplayModeModel mode(DisplayModeModel.Mode initial) {
        Map<String, String> store = new HashMap<>();
        DisplayModeModel mode = new DisplayModeModel(store::get, store::put);
        mode.set(initial);
        return mode;
    }

    private static List<String> order(JTable table) {
        List<String> ids = new ArrayList<>();
        for (TableColumn column : Collections.list(table.getColumnModel().getColumns())) ids.add(String.valueOf(column.getIdentifier()));
        return ids;
    }

    private static TableColumn column(JTable table, String id) {
        for (TableColumn column : Collections.list(table.getColumnModel().getColumns())) if (id.equals(column.getIdentifier())) return column;
        throw new AssertionError("No shown column " + id);
    }

    private static List<String> ids(ViewState.Table state) {
        List<String> ids = new ArrayList<>();
        for (ViewState.Column column : state.columns) ids.add(column.id);
        return ids;
    }

    private static ViewState.Column entry(ViewState.Table state, String id) {
        for (ViewState.Column column : state.columns) if (column.id.equals(id)) return column;
        throw new AssertionError("No saved column " + id);
    }

    private static ViewState.Table last(List<ViewState.Table> saves) {
        assertFalse("A layout was saved", saves.isEmpty());
        return saves.get(saves.size() - 1);
    }

    private static JMenuItem item(JMenu menu, String name) {
        for (Component child : menu.getMenuComponents()) if (child instanceof JMenuItem && name.equals(child.getName())) return (JMenuItem) child;
        throw new AssertionError("No menu item " + name);
    }

    private static JRadioButtonMenuItem radio(HistoryTables.ColumnTools tools, String label) {
        tools.refresh();
        for (Component child : tools.presets().getMenuComponents())
            if (child instanceof JRadioButtonMenuItem && label.equals(((JRadioButtonMenuItem) child).getText())) return (JRadioButtonMenuItem) child;
        throw new AssertionError("No preset " + label);
    }

    private static int count(OverflowMenu more, String name) {
        int found = 0;
        for (Component child : more.menu().getComponents()) if (name.equals(child.getName())) found++;
        return found;
    }

    /** The tools whose Columns ▸ sits in the ⋯ menu right now. */
    private static HistoryTables.ColumnTools owner(OverflowMenu more) {
        for (Component child : more.menu().getComponents())
            if (child instanceof JMenu && "Columns".equals(((JMenu) child).getText()))
                return (HistoryTables.ColumnTools) ((JMenu) child).getClientProperty(HistoryTables.ColumnTools.class);
        return null;
    }

    /** A saved-history client whose view hands its column tools to the workspace ⋯ through ArchiveFilters. */
    private static final class Tooled implements ArchiveClient<Event, Facets, Sort> {
        final String scope; final Path scratch; int renders; HistoryTables.ColumnTools tools;
        Tooled(String scope, Path scratch) { this.scope = scope; this.scratch = scratch; }
        public ArchiveQuery<Facets, Sort> initialQuery() { return query(scope); }
        public Path scratchDirectory() { return scratch; }
        public ArchiveAdapter<Event, Facets, Sort> adapter(ArchiveQuery<Facets, Sort> query) { return ArchiveFixtures.adapter(); }
        public JComponent render(ArchivePage<Event> page, ViewState<Facets, Sort> state, Binding<Facets, Sort> binding) {
            renders++;
            JTable table = HistoryTables.queried("tooled-rows", List.of(new HistoryTables.Column<>("value", "Value", Integer.class, e -> e.value, null),
                new HistoryTables.Column<>("text", "Message", String.class, e -> e.text, null)), page, Collections.singletonMap("value", Sort.VALUE),
                state.query, binding::queryChanged, row -> {});
            tools = HistoryTables.columnTools(table, HistoryTables.columnState(table, "Default"), Collections.emptyMap(), layout -> {});
            return new JScrollPane(table);
        }
        @Override public ArchiveFilters filters(ArchivePage<Event> page, ViewState<Facets, Sort> state, Binding<Facets, Sort> binding) {
            return new ArchiveFilters(null, Collections.emptyList(), tools);
        }
    }

    @FunctionalInterface private interface Checked<T> { T get() throws Exception; }
    private static <T> T edt(Checked<T> value) throws Exception {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(value.get()); } catch (Throwable t) { failure.set(t); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }
}
