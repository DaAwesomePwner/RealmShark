package tomato.gui.history;

// Explicit AWT imports: java.awt.* would make ArchiveFixtures.Event ambiguous with java.awt.Event.
import java.awt.Component;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.kit.ColumnKind;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.KitTables;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import static org.junit.Assert.*;
import static tomato.history.archive.ArchiveFixtures.*;

/**
 * Polish D: a saved table (HistoryTables-built, AUTO_RESIZE_OFF) uses its spare width. Cut values are widened first, then one
 * free-text column (the named fill column, else the last shown TEXT, ITEM, PLAYER or DUNGEON column) takes the rest; the fitted
 * widths are display only (no layout records them), follow the viewport, a layout, preset or Reset, and a font change, and a
 * display-mode switch changes no width. Detached mode models, synthetic rows and windows; no preference is written.
 */
public class HistoryTablesFillTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final List<Window> windows = new ArrayList<>();

    @After public void dispose() throws Exception {
        edt(() -> { for (Window window : windows) window.dispose(); return null; });
    }

    /** A value the Outcome column cuts at 60 px. */
    private static final String LONG = "Left · completion unknown";

    @Test public void aWideViewportFillsTheLastTextColumnAndNoLayoutRecordsIt() throws Exception {
        List<ViewState.Table> saves = new ArrayList<>();
        JTable table = edt(() -> { JTable t = table(120, 200, 150, 60); HistoryTables.rememberLayout(t, saves::add); show(t, 900); return t; });
        await(() -> fills(table));
        edt(() -> {
            int viewport = table.getParent().getWidth();
            assertEquals("Summary, the last free-text column, takes the spare width", viewport - 120 - 200 - 60, column(table, "summary").getWidth());
            assertEquals("Status and count columns keep their widths", 200, column(table, "outcome").getWidth());
            assertEquals(60, column(table, "count").getWidth());
            assertFalse("No sideways scroll bar", scroll(table).getHorizontalScrollBar().isShowing());
            assertTrue("The fill saves no layout", saves.isEmpty());
            assertEquals("columnState keeps each column's own width", List.of(120, 200, 150, 60), widths(HistoryTables.columnState(table, "Custom")));
            return null;
        });
    }

    @Test public void aUserResizeSavesTheUserWidthAndTheFillFollowsOnRelease() throws Exception {
        List<ViewState.Table> saves = new ArrayList<>();
        JTable table = edt(() -> { JTable t = table(120, 200, 150, 60); HistoryTables.rememberLayout(t, saves::add); show(t, 900); return t; });
        await(() -> fills(table));
        int fitted = edt(() -> column(table, "summary").getWidth());
        edt(() -> { drag(table, "area", 150); return null; });   // the user drags another column wider
        settle();
        edt(() -> {
            ViewState.Table saved = last(saves);
            assertEquals("Custom", saved.preset);
            assertEquals("The user's width is saved; the fill column saves its own width, never the fitted one", List.of(150, 200, 150, 60), widths(saved));
            assertEquals("While the mouse is down the fit waits", fitted, column(table, "summary").getWidth());
            release(table);
            return null;
        });
        await(() -> fills(table));
        edt(() -> {
            assertEquals("On release the fill column gives up what the resize took", table.getParent().getWidth() - 150 - 200 - 60, column(table, "summary").getWidth());
            drag(table, "summary", 170);   // the user drags the fill column itself narrower
            return null;
        });
        await(() -> entry(last(saves), "summary").width == 170);
        edt(() -> { release(table); return null; });
        await(() -> fills(table));
        edt(() -> {
            assertEquals("The fill column's own width is the user's", 170, entry(HistoryTables.columnState(table, "Custom"), "summary").width);
            assertEquals("…and it fills again while there is room", table.getParent().getWidth() - 150 - 200 - 60, column(table, "summary").getWidth());
            assertEquals("The refit saved nothing", 170, entry(last(saves), "summary").width);
            return null;
        });
    }

    @Test public void narrowingGivesTheSpaceBackAndWideningRefills() throws Exception {
        JTable table = edt(() -> { JTable t = table(120, 200, 150, 60); show(t, 900); return t; });
        await(() -> fills(table));
        edt(() -> { SwingUtilities.getWindowAncestor(table).setSize(420, 300); return null; });
        await(() -> table.getParent().getWidth() < 530 && total(table) == 530);
        edt(() -> {
            assertEquals("Wider columns than the viewport: every column is back at its own width", List.of(120, 200, 150, 60), shownWidths(table));
            assertTrue("…and the table scrolls sideways as before", table.getWidth() > table.getParent().getWidth());
            SwingUtilities.getWindowAncestor(table).setSize(1000, 300);
            return null;
        });
        await(() -> table.getParent().getWidth() > 530 && fills(table));
        edt(() -> { assertEquals(table.getParent().getWidth() - 380, column(table, "summary").getWidth()); return null; });
    }

    @Test public void aModeSwitchChangesNoWidthAndALaterResizeRefits() throws Exception {
        DisplayModeModel mode = mode(DisplayModeModel.Mode.ANALYST);
        List<ViewState.Table> saves = new ArrayList<>();
        JTable table = edt(() -> {
            JTable t = table(120, 200, 150, 60);
            HistoryTables.rememberLayout(t, saves::add);
            KitTables.analystOnly(t, mode, "outcome");
            show(t, 900);
            return t;
        });
        await(() -> fills(table));
        Map<String, Integer> analyst = edt(() -> byId(table));
        edt(() -> { mode.set(DisplayModeModel.Mode.SIMPLE); return null; });
        settle();
        edt(() -> {
            assertEquals(List.of("area", "summary", "count"), order(table));
            Map<String, Integer> simple = byId(table);
            for (String id : simple.keySet()) assertEquals("Simple changes no width: " + id, analyst.get(id), simple.get(id));
            assertTrue("The switch leaves the spare width spare (no refit)", total(table) < table.getParent().getWidth());
            mode.set(DisplayModeModel.Mode.ANALYST);
            return null;
        });
        settle();
        edt(() -> {
            assertEquals("Analyst changes no width either", analyst, byId(table));
            assertTrue("A mode switch saves no layout", saves.isEmpty());
            assertEquals("Nothing fitted is saved", List.of(120, 200, 150, 60), widths(HistoryTables.columnState(table, "Custom")));
            mode.set(DisplayModeModel.Mode.SIMPLE);
            return null;
        });
        settle();
        // A viewport resize after the switch refits the columns Simple shows.
        edt(() -> { Window window = SwingUtilities.getWindowAncestor(table); window.setSize(window.getWidth() + 40, 300); return null; });
        await(() -> fills(table));
        edt(() -> { assertEquals(table.getParent().getWidth() - 120 - 60, column(table, "summary").getWidth()); return null; });
    }

    @Test public void aPresetAndResetRefillAndSaveOwnWidths() throws Exception {
        List<ViewState.Table> saves = new ArrayList<>();
        AtomicReference<HistoryTables.ColumnTools> tools = new AtomicReference<>();
        JTable table = edt(() -> {
            JTable t = table(120, 200, 150, 60);
            HistoryTables.rememberLayout(t, saves::add);
            tools.set(HistoryTables.columnTools(t, HistoryTables.columnState(t, "Default"), Collections.singletonMap("Compact", List.of("area", "summary")), saves::add));
            show(t, 900);
            return t;
        });
        await(() -> fills(table));
        edt(() -> { radio(tools.get(), "Compact").doClick(); return null; });
        await(() -> order(table).equals(List.of("area", "summary")) && fills(table));
        edt(() -> {
            assertEquals("The preset refills", table.getParent().getWidth() - 120, column(table, "summary").getWidth());
            assertEquals("Compact", last(saves).preset);
            assertEquals("The preset saves its own widths", 150, entry(last(saves), "summary").width);
            assertEquals(150, entry(HistoryTables.columnState(table, "Custom"), "summary").width);
            tools.get().reset().doClick();
            return null;
        });
        await(() -> order(table).equals(List.of("area", "outcome", "summary", "count")) && fills(table));
        edt(() -> {
            assertEquals("Reset refills", table.getParent().getWidth() - 380, column(table, "summary").getWidth());
            assertEquals("Reset saves the defaults", List.of(120, 200, 150, 60), widths(last(saves)));
            return null;
        });
    }

    @Test public void cutValuesAreWidenedFirstThenTheFillTakesTheRest() throws Exception {
        JTable table = edt(() -> { JTable t = table(120, 60, 150, 60); show(t, 900); return t; });
        await(() -> fills(table));
        int need = edt(() -> needed(table, "outcome"));
        assertTrue("The fixture cuts Outcome at 60 px: " + need, need > 60);
        edt(() -> {
            assertEquals("The cut Outcome grows to its widest value", need, column(table, "outcome").getWidth());
            assertEquals("Summary takes what is left", table.getParent().getWidth() - 120 - need - 60, column(table, "summary").getWidth());
            assertEquals("Neither fitted width is saved", List.of(120, 60, 150, 60), widths(HistoryTables.columnState(table, "Custom")));
            // Ten spare pixels: all of them go to the cut column, none to the fill.
            Window window = SwingUtilities.getWindowAncestor(table);
            window.setSize(window.getWidth() - table.getParent().getWidth() + 390 + 10, 300);
            return null;
        });
        await(() -> table.getParent().getWidth() == 400 && fills(table));
        edt(() -> {
            assertEquals(70, column(table, "outcome").getWidth());
            assertEquals(150, column(table, "summary").getWidth());
            return null;
        });
    }

    @Test public void aNamedFillColumnTakesTheSpareUntilItIsHidden() throws Exception {
        List<ViewState.Table> saves = new ArrayList<>();
        JTable table = edt(() -> { JTable t = table(120, 200, 150, 60); HistoryTables.rememberLayout(t, saves::add); HistoryTables.fill(t, "area"); show(t, 900); return t; });
        await(() -> fills(table));
        edt(() -> {
            assertEquals("The named column takes the spare width", table.getParent().getWidth() - 200 - 150 - 60, column(table, "area").getWidth());
            assertEquals(150, column(table, "summary").getWidth());
            assertEquals(120, entry(HistoryTables.columnState(table, "Custom"), "area").width);
            HistoryTables.applyColumns(table, new ViewState.Table("Custom", Arrays.asList(new ViewState.Column("area", 120, false),
                new ViewState.Column("outcome", 200, true), new ViewState.Column("summary", 150, true), new ViewState.Column("count", 60, true))));
            return null;
        });
        await(() -> order(table).equals(List.of("outcome", "summary", "count")) && fills(table));
        edt(() -> {
            assertEquals("Hidden, the named column leaves the default fill", table.getParent().getWidth() - 200 - 60, column(table, "summary").getWidth());
            assertEquals("A layout HistoryTables applies saves nothing", 0, saves.size());
            return null;
        });
    }

    @Test public void aFontChangeRefitsTheKindsAndThenTheFill() throws Exception {
        JTable table = edt(() -> { JTable t = table(-1, -1, -1, -1); show(t, 1100); return t; });
        await(() -> fills(table));
        edt(() -> { table.setFont(table.getFont().deriveFont(table.getFont().getSize2D() + 4f)); return null; });
        await(() -> fills(table) && entry(HistoryTables.columnState(table, "Custom"), "summary").width == ColumnKind.TEXT.width(table.getFont()));
        edt(() -> {
            Font font = table.getFont();
            assertEquals("The kind width follows the font for the fill column too", ColumnKind.TEXT.width(font), entry(HistoryTables.columnState(table, "Custom"), "summary").width);
            assertEquals(ColumnKind.DUNGEON.width(font), column(table, "area").getWidth());
            assertTrue("…and the fill still takes the spare width", column(table, "summary").getWidth() > ColumnKind.TEXT.width(font));
            return null;
        });
    }

    @Test public void queriedTablesFillTheirLastFreeTextKind() throws Exception {
        Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath(); String id = session(root, 5);
        try (SessionStore store = new SessionStore(root, false, "test"); ArchiveResult<Event> result = ArchiveResult.open(store, query(id), adapter(), scratch, new Cancellation())) {
            ArchivePage<Event> page = result.page(0, 5, new Cancellation());
            JTable table = edt(() -> {
                JTable t = HistoryTables.queried("fill-queried", Arrays.asList(new HistoryTables.Column<>("value", "Value", Integer.class, e -> e.value, null, ColumnKind.COUNT),
                    new HistoryTables.Column<>("text", "Message", String.class, e -> e.text, null, ColumnKind.TEXT),
                    new HistoryTables.Column<>("group", "Group", String.class, e -> e.group, null, ColumnKind.STATUS)), page, Collections.emptyMap(), query(id), q -> {}, row -> {});
                show(t, 1000);
                return t;
            });
            await(() -> fills(table));
            edt(() -> {
                Font font = table.getFont();
                assertEquals("A status column is never the fill", ColumnKind.STATUS.width(font), column(table, "group").getWidth());
                assertEquals(table.getParent().getWidth() - ColumnKind.COUNT.width(font) - ColumnKind.STATUS.width(font), column(table, "text").getWidth());
                return null;
            });
        }
    }

    // ---- fixtures ----

    /**
     * Area (DUNGEON), Outcome (STATUS), Summary (TEXT) and Count (COUNT), IDs area, outcome, summary and count, at these widths
     * (a layout HistoryTables applies), or at their kind widths when every width is negative.
     */
    private static JTable table(int area, int outcome, int summary, int count) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"Lost Halls", "Completed", "first", 3L});
        rows.add(new Object[]{"Nexus", LONG, "second", 1L});
        JTable table = HistoryTables.table("fill-rows", new String[]{"Area", "Outcome", "Summary", "Count"},
            new Class<?>[]{String.class, String.class, String.class, Long.class}, rows);
        String[] ids = {"area", "outcome", "summary", "count"};
        for (int i = 0; i < ids.length; i++) table.getColumnModel().getColumn(i).setIdentifier(ids[i]);
        Map<String, ColumnKind> kinds = new HashMap<>();
        kinds.put("area", ColumnKind.DUNGEON); kinds.put("outcome", ColumnKind.STATUS); kinds.put("summary", ColumnKind.TEXT); kinds.put("count", ColumnKind.COUNT);
        HistoryTables.kinds(table, kinds);
        if (area >= 0) HistoryTables.applyColumns(table, new ViewState.Table("Custom", Arrays.asList(new ViewState.Column("area", area, true),
            new ViewState.Column("outcome", outcome, true), new ViewState.Column("summary", summary, true), new ViewState.Column("count", count, true))));
        return table;
    }

    private void show(JTable table, int width) {
        JFrame frame = new JFrame("Fill fixture");
        windows.add(frame);
        frame.setContentPane(new JScrollPane(table));
        frame.setSize(width, 300);
        frame.setVisible(true);
    }

    private static JScrollPane scroll(JTable table) { return (JScrollPane) table.getParent().getParent(); }

    /** A header drag in progress, as BasicTableHeaderUI does it: the resizing column, then its new width (JTable copies it to the preferred width). */
    private static void drag(JTable table, String id, int width) {
        table.getTableHeader().setResizingColumn(column(table, id));
        column(table, id).setWidth(width);
    }

    /** The drag's end: the mouse released over the header. */
    private static void release(JTable table) {
        JTableHeader header = table.getTableHeader();
        header.dispatchEvent(new MouseEvent(header, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, 5, 5, 1, false, MouseEvent.BUTTON1));
        header.setResizingColumn(null);
    }

    /** The shown columns exactly fill the viewport. */
    private static boolean fills(JTable table) {
        return table.isShowing() && table.getParent().getWidth() > 0 && total(table) == table.getParent().getWidth();
    }

    private static int total(JTable table) {
        int total = 0;
        for (TableColumn column : Collections.list(table.getColumnModel().getColumns())) total += column.getWidth();
        return total;
    }

    /** The widest value of a column (or its header) plus the column margin, as the renderer lays it out. */
    private static int needed(JTable table, String id) {
        int view = table.getColumnModel().getColumnIndex(id), need = 0;
        for (int row = 0; row < table.getRowCount(); row++)
            need = Math.max(need, table.prepareRenderer(table.getCellRenderer(row, view), row, view).getPreferredSize().width + table.getColumnModel().getColumnMargin());
        return need;
    }

    /** Waits for the EDT to finish the deferred work of a mode switch (and any layout it queued). */
    private static void settle() throws Exception {
        for (int i = 0; i < 3; i++) { edt(() -> null); Thread.sleep(100); }
        edt(() -> null);
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

    private static Map<String, Integer> byId(JTable table) {
        Map<String, Integer> widths = new LinkedHashMap<>();
        for (TableColumn column : Collections.list(table.getColumnModel().getColumns())) widths.put(String.valueOf(column.getIdentifier()), column.getWidth());
        return widths;
    }

    private static List<Integer> shownWidths(JTable table) { return new ArrayList<>(byId(table).values()); }

    private static TableColumn column(JTable table, String id) {
        for (TableColumn column : Collections.list(table.getColumnModel().getColumns())) if (id.equals(column.getIdentifier())) return column;
        throw new AssertionError("No shown column " + id);
    }

    private static List<Integer> widths(ViewState.Table state) {
        List<Integer> widths = new ArrayList<>();
        for (ViewState.Column column : state.columns) widths.add(column.width);
        return widths;
    }

    private static ViewState.Column entry(ViewState.Table state, String id) {
        for (ViewState.Column column : state.columns) if (column.id.equals(id)) return column;
        throw new AssertionError("No saved column " + id);
    }

    private static ViewState.Table last(List<ViewState.Table> saves) {
        assertFalse("A layout was saved", saves.isEmpty());
        return saves.get(saves.size() - 1);
    }

    private static JRadioButtonMenuItem radio(HistoryTables.ColumnTools tools, String label) {
        tools.refresh();
        for (Component child : tools.presets().getMenuComponents())
            if (child instanceof JRadioButtonMenuItem && label.equals(((JRadioButtonMenuItem) child).getText())) return (JRadioButtonMenuItem) child;
        throw new AssertionError("No preset " + label);
    }

    @FunctionalInterface private interface Checked<T> { T get() throws Exception; }
    private static <T> T edt(Checked<T> value) throws Exception {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(value.get()); } catch (Throwable t) { failure.set(t); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) { if (edt(condition::getAsBoolean)) return; Thread.sleep(20); }
        fail("Timed out waiting for EDT state");
    }
}
