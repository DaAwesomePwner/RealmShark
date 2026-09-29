package tomato.gui.kit;

import java.awt.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.function.LongSupplier;
import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.*;
import org.junit.*;
import tomato.gui.history.HistoryTables;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import static org.junit.Assert.*;

/** KitTables.relativeTime, analystOnly and epoch: display only, so models, sorting, search and Copy never see the mode. */
public class KitTablesModeTest {
    private static final long NOW = 1_800_000_000_000L, MINUTE = 60_000L;
    private LongSupplier previousClock;
    private Locale previous;

    @Before public void fix() {
        previousClock = KitFormat.clock; KitFormat.clock = () -> NOW;
        previous = Locale.getDefault(Locale.Category.FORMAT); Locale.setDefault(Locale.Category.FORMAT, Locale.US);
    }
    @After public void restore() { KitFormat.clock = previousClock; Locale.setDefault(Locale.Category.FORMAT, previous); }

    @Test public void simpleShowsRelativeTextWithTheAbsoluteTimeAndZoneInTheTooltip() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel mode = mode(DisplayModeModel.Mode.SIMPLE);
            Instant twelve = Instant.ofEpochMilli(NOW - 12 * MINUTE), three = Instant.ofEpochMilli(NOW - 3 * 60 * MINUTE);
            JTable table = table(new Object[][]{{twelve, "a"}, {null, "b"}, {three, "c"}});
            KitTables.relativeTime(table, "when", mode, KitTables::epoch, "UTC");
            JLabel first = render(table, 0, 0);
            assertEquals("12 min ago", first.getText());
            assertEquals(DisplayFormat.formatTimestamp(twelve) + " (UTC)", first.getToolTipText());
            JLabel unknown = render(table, 1, 0);
            assertEquals("An unknown time keeps the base renderer's own wording", "Unknown time", unknown.getText());
            assertNull("…and its own tooltip: the previous row's absolute time never leaks into it", unknown.getToolTipText());
            assertEquals("…dimmed", Tokens.color(Tokens.Role.TEXT_MUTED), unknown.getForeground());
            table.setRowSelectionInterval(1, 1);
            assertEquals("A selected unknown cell keeps the selection ink", table.getSelectionForeground(), render(table, 1, 0).getForeground());
            table.clearSelection();
            assertEquals("3 h ago", render(table, 2, 0).getText());
            assertEquals("Other columns are untouched", "c", render(table, 2, 1).getText());
            assertEquals("A null zone label adds nothing", DisplayFormat.formatTimestamp(twelve),
                renderWith(table, mode, null, 0).getToolTipText());
        });
    }

    @Test public void analystMatchesTheBaseRendererExactly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel mode = mode(DisplayModeModel.Mode.SIMPLE);
            Instant twelve = Instant.ofEpochMilli(NOW - 12 * MINUTE);
            JTable table = table(new Object[][]{{twelve, "a"}, {null, "b"}});
            TableCellRenderer base = table.getColumnModel().getColumn(0).getCellRenderer();
            KitTables.relativeTime(table, "when", mode, KitTables::epoch, "UTC");
            render(table, 0, 0); render(table, 1, 0);   // Simple changes the shared stamp first
            mode.set(DisplayModeModel.Mode.ANALYST);
            for (int row = 0; row < 2; row++) {
                JLabel shown = render(table, row, 0);
                String text = shown.getText(), tip = shown.getToolTipText();
                Color ink = shown.getForeground(), back = shown.getBackground();
                Font font = shown.getFont();
                int align = shown.getHorizontalAlignment();
                JLabel direct = (JLabel) base.getTableCellRendererComponent(table, table.getValueAt(row, 0), false, false, row, 0);
                assertSame("Analyst returns the base renderer's own component", direct, shown);
                assertEquals(direct.getText(), text);
                assertEquals(direct.getToolTipText(), tip);
                assertEquals(direct.getForeground(), ink);
                assertEquals(direct.getBackground(), back);
                assertEquals(direct.getFont(), font);
                assertEquals(direct.getHorizontalAlignment(), align);
            }
            assertEquals(DisplayFormat.formatTimestamp(twelve), render(table, 0, 0).getText());
            assertEquals("Unknown time", render(table, 1, 0).getText());
            assertEquals(table.getForeground(), render(table, 1, 0).getForeground());
        });
    }

    @Test public void modelSortingSearchAndCopyAreIdenticalInBothModes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel mode = mode(DisplayModeModel.Mode.ANALYST);
            JTable table = table(new Object[][]{{Instant.ofEpochMilli(NOW - 3 * 60 * MINUTE), "c"},
                {Instant.ofEpochMilli(NOW - 12 * MINUTE), "a"}, {Instant.ofEpochMilli(NOW - 40 * MINUTE), "b"}});
            table.setAutoCreateRowSorter(true);
            table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
            KitTables.relativeTime(table, "when", mode, KitTables::epoch, "UTC");
            int width = table.getColumnModel().getColumn(0).getWidth(), preferred = table.getColumnModel().getColumn(0).getPreferredWidth();
            String analyst = snapshot(table);
            mode.set(DisplayModeModel.Mode.SIMPLE);
            assertEquals("12 min ago", render(table, 2, 0).getText());
            assertEquals("Values, view order and Copy do not follow the mode", analyst, snapshot(table));
            assertTrue(table.getModel().getValueAt(1, 0) instanceof Instant);
            assertEquals("No refit on a mode switch", width, table.getColumnModel().getColumn(0).getWidth());
            assertEquals(preferred, table.getColumnModel().getColumn(0).getPreferredWidth());
            @SuppressWarnings("unchecked") TableRowSorter<TableModel> sorter = (TableRowSorter<TableModel>) table.getRowSorter();
            sorter.setRowFilter(RowFilter.regexFilter("min ago"));
            assertEquals("Search never matches the relative text", 0, table.getRowCount());
            sorter.setRowFilter(RowFilter.regexFilter(String.valueOf(Instant.ofEpochMilli(NOW - 12 * MINUTE).atZone(java.time.ZoneOffset.UTC).getYear())));
            assertEquals("…it keeps matching the absolute value", 3, table.getRowCount());
            sorter.setRowFilter(null);
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertEquals(analyst, snapshot(table));
        });
    }

    @Test public void tickRepaintsOnlyShowingSimpleTablesAndASwitchRepaints() throws Exception {
        CountingTable[] tables = new CountingTable[3];
        DisplayModeModel[] modes = new DisplayModeModel[3];
        JFrame[] frame = new JFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                DisplayModeModel.Mode[] kinds = {DisplayModeModel.Mode.SIMPLE, DisplayModeModel.Mode.ANALYST, DisplayModeModel.Mode.SIMPLE};
                JPanel shown = new JPanel(new GridLayout(1, 2));
                for (int i = 0; i < 3; i++) {
                    modes[i] = mode(kinds[i]);
                    tables[i] = new CountingTable(model(new Object[][]{{Instant.ofEpochMilli(NOW - 12 * MINUTE), "a"}}));
                    KitTables.relativeTime(tables[i], "when", modes[i], KitTables::epoch, "UTC");
                    if (i < 2) shown.add(new JScrollPane(tables[i]));   // the third table is never shown
                }
                frame[0] = new JFrame("KitTablesModeTest");
                frame[0].setContentPane(shown);
                frame[0].setSize(600, 200);
                frame[0].setVisible(true);
            });
            for (int pass = 0; pass < 3; pass++) SwingUtilities.invokeAndWait(() -> { });
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(tables[0].isShowing() && tables[1].isShowing() && !tables[2].isShowing());
                for (CountingTable table : tables) table.repaints = 0;
                KitTables.tick();
                assertTrue("A showing Simple table repaints on the tick", tables[0].repaints > 0);
                assertEquals("An Analyst table does not", 0, tables[1].repaints);
                assertEquals("A hidden table does not", 0, tables[2].repaints);
                assertTrue("The shared minute timer runs while tables are registered", KitTables.tickerRunning());
                tables[1].repaints = 0;
                modes[1].set(DisplayModeModel.Mode.SIMPLE);
                assertTrue("A mode switch repaints the table", tables[1].repaints > 0);
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (frame[0] != null) frame[0].dispose(); });
        }
    }

    @Test public void analystOnlyRemovesAndRestoresAtTheSameIndexAndWidthAndDropsTheSortKey() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel mode = mode(DisplayModeModel.Mode.ANALYST);
            JTable table = wide();
            table.setAutoCreateRowSorter(true);
            table.getColumnModel().moveColumn(4, 1);   // view order differs from the model: time, summary, map, meaning, session
            table.getColumnModel().getColumn(3).setPreferredWidth(211); table.getColumnModel().getColumn(3).setWidth(211);
            table.getColumnModel().getColumn(4).setPreferredWidth(97); table.getColumnModel().getColumn(4).setWidth(97);
            List<Object> order = order(table);
            Map<Object, Integer> widths = widths(table);
            table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(2, SortOrder.DESCENDING), new RowSorter.SortKey(0, SortOrder.ASCENDING)));
            KitTables.analystOnly(table, mode, "meaning", "session");
            assertEquals("Analyst shows every column", order, order(table));
            assertTrue(KitTables.modeHidden(table).isEmpty());
            mode.set(DisplayModeModel.Mode.SIMPLE);
            assertEquals(List.of("time", "summary", "map"), order(table));
            assertEquals(Set.of("meaning", "session"), KitTables.modeHidden(table));
            assertEquals(KitTables.modeHidden(table), table.getClientProperty(KitTables.MODE_HIDDEN));
            assertEquals("The sort key on a hidden column is dropped", List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING)), table.getRowSorter().getSortKeys());
            assertEquals("A hidden column's cells are not read by Copy", 3, table.getColumnCount());
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertEquals("Restored at the same view index", order, order(table));
            assertEquals("…and the same width", widths, widths(table));
            assertTrue(KitTables.modeHidden(table).isEmpty());
            // Bound in Simple: hidden at once. A column the user removed first is not brought back by Analyst.
            DisplayModeModel simple = mode(DisplayModeModel.Mode.SIMPLE);
            JTable other = wide();
            TableColumn userHidden = other.getColumnModel().getColumn(3);   // session
            other.removeColumn(userHidden);
            KitTables.analystOnly(other, simple, "meaning", "session");
            assertEquals(List.of("time", "map", "summary"), order(other));
            simple.set(DisplayModeModel.Mode.ANALYST);
            assertEquals(List.of("time", "map", "meaning", "summary"), order(other));
        });
    }

    @Test public void modeChangingIsSetOnlyDuringTheChange() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel mode = mode(DisplayModeModel.Mode.ANALYST);
            JTable table = wide();
            KitTables.analystOnly(table, mode, "meaning");
            List<Object> seen = new ArrayList<>();
            table.getColumnModel().addColumnModelListener(new TableColumnModelListener() {
                private void see() { seen.add(table.getClientProperty(KitTables.MODE_CHANGING)); }
                @Override public void columnAdded(TableColumnModelEvent e) { see(); }
                @Override public void columnRemoved(TableColumnModelEvent e) { see(); }
                @Override public void columnMoved(TableColumnModelEvent e) { see(); }
                @Override public void columnMarginChanged(ChangeEvent e) { see(); }
                @Override public void columnSelectionChanged(ListSelectionEvent e) { }
            });
            assertNull(table.getClientProperty(KitTables.MODE_CHANGING));
            mode.set(DisplayModeModel.Mode.SIMPLE);
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertFalse("The switches changed the column model", seen.isEmpty());
            for (Object flag : seen) assertEquals("Every mode-driven column event is marked", Boolean.TRUE, flag);
            assertNull("…and the mark is cleared afterwards", table.getClientProperty(KitTables.MODE_CHANGING));
            seen.clear();
            table.getColumnModel().moveColumn(0, 1);
            assertEquals("A user's move is not marked", Collections.singletonList(null), seen);
        });
    }

    @Test public void listenersStayBoundedAfterRepeatedSwitchesAndInstalls() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel mode = mode(DisplayModeModel.Mode.SIMPLE);
            JTable table = wide();
            Instant twelve = Instant.ofEpochMilli(NOW - 12 * MINUTE);
            table.setValueAt(twelve, 0, 0);   // no renderer of its own: the table's default renderer is the base
            KitTables.relativeTime(table, "time", mode, KitTables::epoch, "UTC");
            KitTables.analystOnly(table, mode, "meaning", "session");
            int listeners = mode.listenerCount(), hierarchy = table.getHierarchyListeners().length;
            int columnListeners = ((DefaultTableColumnModel) table.getColumnModel()).getColumnModelListeners().length;
            Map<Object, Integer> perColumn = new HashMap<>();
            for (TableColumn column : columns(table)) perColumn.put(column.getIdentifier(), column.getPropertyChangeListeners().length);
            for (int i = 0; i < 20; i++) mode.toggle();
            KitTables.relativeTime(table, "time", mode, KitTables::epoch, "UTC");   // a re-render installs again
            KitTables.analystOnly(table, mode, "meaning", "session");
            assertEquals(listeners, mode.listenerCount());
            assertEquals(hierarchy, table.getHierarchyListeners().length);
            assertEquals(columnListeners, ((DefaultTableColumnModel) table.getColumnModel()).getColumnModelListeners().length);
            for (TableColumn column : columns(table)) assertEquals(perColumn.get(column.getIdentifier()), (Integer) column.getPropertyChangeListeners().length);
            JLabel cell = render(table, 0, 0);
            assertEquals("12 min ago", cell.getText());
            assertEquals("A second install wraps the base renderer, never its own wrapper",
                twelve + " (UTC)", cell.getToolTipText());
        });
    }

    @Test public void relativeTimeFindsAColumnTheModeHidAndRejectsAnUnknownOne() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel mode = mode(DisplayModeModel.Mode.SIMPLE);
            JTable table = wide();
            KitTables.analystOnly(table, mode, "meaning");
            KitTables.relativeTime(table, "meaning", mode, KitTables::epoch, "UTC");
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertTrue(columns(table).stream().anyMatch(c -> "meaning".equals(c.getIdentifier()) && c.getCellRenderer() != null));
            try { KitTables.relativeTime(table, "nope", mode, KitTables::epoch, "UTC"); fail("An unknown column is a caller error"); }
            catch (IllegalArgumentException expected) { }
        });
    }

    @Test public void theTimeRelativeKindKeepsItsBehavior() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DefaultTableModel model = new DefaultTableModel(new Object[]{"Time"}, 0);
            model.addRow(new Object[]{"12:04:11"});   // RunRecapView feeds formatted clock times
            model.addRow(new Object[]{NOW - 12 * MINUTE});
            JTable table = new JTable(model);
            KitTables.apply(table, ColumnKind.TIME_RELATIVE);
            assertEquals("12:04:11", render(table, 0, 0).getText());
            assertNull(render(table, 0, 0).getToolTipText());
            assertEquals("12 min ago", render(table, 1, 0).getText());
            assertEquals(DisplayFormat.formatTimestamp(Instant.ofEpochMilli(NOW - 12 * MINUTE)), render(table, 1, 0).getToolTipText());
        });
    }

    @Test public void epochReadsInstantsNumbersAndIsoStrings() {
        Instant at = Instant.parse("2026-09-29T12:04:11.123456Z");
        assertEquals((Long) at.toEpochMilli(), KitTables.epoch(at));
        assertEquals((Long) 1_800_000_000_000L, KitTables.epoch(1_800_000_000_000L));
        assertEquals((Long) 42L, KitTables.epoch(42));
        assertEquals((Long) at.toEpochMilli(), KitTables.epoch("2026-09-29T12:04:11.123456Z"));
        assertEquals((Long) Instant.parse("2026-09-29T12:04:11Z").toEpochMilli(), KitTables.epoch("2026-09-29T12:04:11Z"));
        assertEquals((Long) Instant.parse("2026-09-29T10:04:11.5Z").toEpochMilli(), KitTables.epoch("2026-09-29T12:04:11.5+02:00"));
        assertEquals((Long) Instant.parse("2026-09-29T10:04:11Z").toEpochMilli(), KitTables.epoch(" 2026-09-29T12:04:11+02:00[Europe/Berlin] "));
        assertNull("A time without a zone is not guessed", KitTables.epoch("2026-09-29T12:04:11"));
        assertNull(KitTables.epoch("12:04:11"));
        assertNull(KitTables.epoch("yesterday"));
        assertNull(KitTables.epoch("2026-13-45T99:99:99Z"));
        assertNull(KitTables.epoch(""));
        assertNull(KitTables.epoch(null));
        assertNull(KitTables.epoch(Double.NaN));
        assertNull(KitTables.epoch(new Object()));
    }

    // ---- fixtures ----

    private static DisplayModeModel mode(DisplayModeModel.Mode initial) {
        Map<String, String> store = new HashMap<>();
        DisplayModeModel mode = new DisplayModeModel(store::get, store::put);
        mode.set(initial);
        return mode;
    }

    private static DefaultTableModel model(Object[][] rows) {
        DefaultTableModel model = new DefaultTableModel(new Object[]{"when", "what"}, 0) {
            @Override public Class<?> getColumnClass(int column) { return column == 0 ? Instant.class : String.class; }
        };
        for (Object[] row : rows) model.addRow(row);
        return model;
    }

    /** A table whose time column has an absolute renderer of its own, as the adopting pages install. */
    private static JTable table(Object[][] rows) {
        JTable table = new JTable(model(rows));
        ContentStyle.table(table);
        table.getColumnModel().getColumn(0).setCellRenderer(new ContentStyle.Cell() {
            @Override protected void setValue(Object value) { setText(value == null ? "Unknown time" : DisplayFormat.formatTimestamp((Instant) value)); }
        });
        return table;
    }

    private static JTable wide() {
        DefaultTableModel model = new DefaultTableModel(new Object[]{"time", "map", "meaning", "session", "summary"}, 0);
        model.addRow(new Object[]{"t1", "Nexus", "m1", "s1", "sum1"});
        model.addRow(new Object[]{"t2", "Realm", "m2", "s2", "sum2"});
        JTable table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        return table;
    }

    private static JLabel render(JTable table, int row, int column) {
        return (JLabel) table.prepareRenderer(table.getCellRenderer(row, column), row, column);
    }

    private static JLabel renderWith(JTable table, DisplayModeModel mode, String zone, int row) {
        KitTables.relativeTime(table, "when", mode, KitTables::epoch, zone);
        return render(table, row, 0);
    }

    private static String snapshot(JTable table) {
        StringBuilder out = new StringBuilder();
        for (int row = 0; row < table.getRowCount(); row++) {
            out.append(table.convertRowIndexToModel(row)).append(':');
            for (int column = 0; column < table.getColumnCount(); column++) out.append(table.getValueAt(row, column)).append('|');
            out.append('\n');
        }
        table.selectAll();
        out.append(HistoryTables.selectedText(table));
        table.clearSelection();
        return out.toString();
    }

    private static List<TableColumn> columns(JTable table) { return Collections.list(table.getColumnModel().getColumns()); }

    private static List<Object> order(JTable table) {
        List<Object> ids = new ArrayList<>();
        for (TableColumn column : columns(table)) ids.add(column.getIdentifier());
        return ids;
    }

    private static Map<Object, Integer> widths(JTable table) {
        Map<Object, Integer> widths = new LinkedHashMap<>();
        for (TableColumn column : columns(table)) widths.put(column.getIdentifier(), column.getPreferredWidth() * 100_000 + column.getWidth());
        return widths;
    }

    /** Counts repaint requests; tests reset the count right before the call they measure, on the same EDT turn. */
    private static final class CountingTable extends JTable {
        int repaints;
        CountingTable(TableModel model) {
            super(model);
            getColumnModel().getColumn(0).setCellRenderer(new ContentStyle.Cell() {
                @Override protected void setValue(Object value) { setText(value == null ? "Unknown time" : DisplayFormat.formatTimestamp((Instant) value)); }
            });
        }
        @Override public void repaint(long time, int x, int y, int width, int height) { repaints++; super.repaint(time, x, y, width, height); }
    }
}
