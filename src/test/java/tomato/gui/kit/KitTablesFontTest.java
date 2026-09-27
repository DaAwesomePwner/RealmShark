package tomato.gui.kit;

import java.awt.Font;
import java.util.Collections;
import javax.swing.*;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import org.junit.*;
import tomato.gui.history.HistoryTables;
import tomato.gui.history.ViewState;
import tomato.gui.modern.ContentStyle;
import static org.junit.Assert.*;

/** ColumnKind widths follow runtime font changes, except widths a user dragged or a saved layout restored. */
public class KitTablesFontTest {
    private static final String LONG = "Observations recorded this session";
    private Font previous;

    @Before public void smallFont() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            previous = ContentStyle.body();
            ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 13));
        });
    }

    @After public void restoreFont() throws Exception { SwingUtilities.invokeAndWait(() -> ContentStyle.setBodyFont(previous)); }

    @Test public void kindWidthsGrowWithTheFontOnALaterTurn() throws Exception {
        JTable[] table = new JTable[1];
        SwingUtilities.invokeAndWait(() -> {
            table[0] = table();
            KitTables.fitKind(table[0], table[0].getColumn("when"), ColumnKind.DATE_TIME);
            KitTables.apply(table[0], Collections.singletonMap("damage", ColumnKind.NUMBER));
            Font small = table[0].getFont();
            assertEquals(ColumnKind.DATE_TIME.width(small), table[0].getColumn("when").getPreferredWidth());
            assertEquals(ColumnKind.DATE_TIME.width(small), table[0].getColumn("when").getWidth());
            grow(table[0]);
            assertEquals("The refit waits for a later EDT turn, when table and header fonts have both changed",
                ColumnKind.DATE_TIME.width(small), table[0].getColumn("when").getPreferredWidth());
        });
        SwingUtilities.invokeAndWait(() -> {
            Font large = table[0].getFont();
            assertEquals(26f, large.getSize2D(), 0.01f);
            assertEquals(ColumnKind.DATE_TIME.width(large), table[0].getColumn("when").getPreferredWidth());
            assertEquals(ColumnKind.DATE_TIME.width(large), table[0].getColumn("when").getWidth());
            assertEquals("KitTables.apply widths follow too", ColumnKind.NUMBER.width(large), table[0].getColumn("damage").getPreferredWidth());
            assertEquals("Columns without a kind keep their width", 145, table[0].getColumn("who").getPreferredWidth());
        });
    }

    @Test public void draggedAndRestoredWidthsSurviveAndTheHeaderStaysAFloor() throws Exception {
        JTable[] table = new JTable[1];
        SwingUtilities.invokeAndWait(() -> {
            table[0] = table();
            int tableListeners = table[0].getPropertyChangeListeners("font").length;
            int headerListeners = table[0].getTableHeader().getPropertyChangeListeners("font").length;
            for (String id : new String[] {"when", "damage", "who"}) KitTables.fitKind(table[0], table[0].getColumn(id), ColumnKind.PLAYER);
            KitTables.fitKind(table[0], table[0].getColumn(LONG), ColumnKind.COUNT);
            KitTables.fitKind(table[0], table[0].getColumn("when"), ColumnKind.DATE_TIME);
            assertEquals("One font listener per table", tableListeners + 1, table[0].getPropertyChangeListeners("font").length);
            assertEquals("and one on its header", headerListeners + 1, table[0].getTableHeader().getPropertyChangeListeners("font").length);
            TableColumn longHeader = table[0].getColumn(LONG);
            assertTrue("The header text is wider than a count", header(table[0], longHeader) > ColumnKind.COUNT.width(table[0].getFont()));
            assertEquals(header(table[0], longHeader), longHeader.getPreferredWidth());
            // A header drag: with AUTO_RESIZE_OFF, JTable copies the dragged width into the preferred width.
            TableColumn damage = table[0].getColumn("damage");
            JTableHeader header = table[0].getTableHeader();
            header.setResizingColumn(damage); damage.setWidth(200); header.setResizingColumn(null);
            assertEquals(200, damage.getPreferredWidth());
            HistoryTables.applyColumns(table[0], new ViewState.Table("Custom", Collections.singletonList(new ViewState.Column("who", 260, true))));
            assertEquals(260, table[0].getColumn("who").getWidth());
            grow(table[0]);
        });
        SwingUtilities.invokeAndWait(() -> {
            Font large = table[0].getFont();
            TableColumn longHeader = table[0].getColumn(LONG);
            assertEquals("The header floor is measured at the new header font",
                Math.max(ColumnKind.COUNT.width(large), header(table[0], longHeader)), longHeader.getPreferredWidth());
            assertEquals(ColumnKind.DATE_TIME.width(large), table[0].getColumn("when").getPreferredWidth());
            assertEquals("A dragged width is the user's", 200, table[0].getColumn("damage").getPreferredWidth());
            assertEquals("A restored layout keeps its width", 260, table[0].getColumn("who").getPreferredWidth());
            assertEquals(260, table[0].getColumn("who").getWidth());
        });
    }

    private static JTable table() {
        return HistoryTables.table("font-kinds", new String[] {"when", "damage", "who", LONG},
            new Class<?>[] {Long.class, Double.class, String.class, Integer.class}, Collections.emptyList());
    }

    /** What the running app does after Edit > Font: a new body font, then refreshFonts over the tree. */
    private static void grow(JTable table) {
        ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 26));
        ContentStyle.refreshFonts(table);
    }

    private static int header(JTable table, TableColumn column) {
        return table.getTableHeader().getDefaultRenderer()
            .getTableCellRendererComponent(table, column.getHeaderValue(), false, false, -1, 0).getPreferredSize().width;
    }
}
