package tomato.gui.kit;

import java.awt.*;
import java.time.Instant;
import java.util.*;
import java.util.function.LongSupplier;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.junit.*;
import tomato.gui.modern.ContentStyle;
import static org.junit.Assert.*;

public class KitTablesTest {
    private static final long NOW = 1_800_000_000_000L;
    private LongSupplier previousClock;
    private Locale previous;

    @Before public void fix() {
        previousClock = KitFormat.clock; KitFormat.clock = () -> NOW;
        previous = Locale.getDefault(Locale.Category.FORMAT); Locale.setDefault(Locale.Category.FORMAT, Locale.US);
    }
    @After public void restore() { KitFormat.clock = previousClock; Locale.setDefault(Locale.Category.FORMAT, previous); }

    @Test public void widthsScaleWithTheBodyFont() {
        Font small = new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 13), large = new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 26);
        assertEquals(Math.round(11 * 13f) + 16, ColumnKind.DATE_TIME.width(small));
        assertTrue(ColumnKind.DATE_TIME.width(large) > ColumnKind.DATE_TIME.width(small));
        assertEquals(SwingConstants.RIGHT, ColumnKind.NUMBER.alignment);
    }

    @Test public void renderersFormatByKindAndKeepModelValues() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DefaultTableModel model = new DefaultTableModel(new Object[]{"when", "damage", "left", "note"}, 0);
            model.addRow(new Object[]{NOW - 12 * 60_000L, 41_234.0, DisplayValue.unknown("Not captured"), null});
            JTable table = new JTable(model);
            ContentStyle.table(table);
            Map<String, ColumnKind> kinds = new HashMap<>();
            kinds.put("when", ColumnKind.TIME_RELATIVE); kinds.put("damage", ColumnKind.NUMBER);
            kinds.put("left", ColumnKind.COUNT); kinds.put("note", ColumnKind.TEXT);
            KitTables.apply(table, kinds);
            JLabel when = render(table, 0, 0), damage = render(table, 0, 1), left = render(table, 0, 2), note = render(table, 0, 3);
            assertEquals("12 min ago", when.getText());
            assertTrue(when.getToolTipText().matches("\\d{4}-\\d{2}-\\d{2} .*"));
            assertEquals("41.2k", damage.getText());
            assertEquals("41,234", damage.getToolTipText());
            assertEquals(SwingConstants.RIGHT, damage.getHorizontalAlignment());
            assertEquals("—", left.getText());
            assertEquals("Not captured", left.getToolTipText());
            assertEquals(Tokens.color(Tokens.Role.TEXT_MUTED), left.getForeground());
            assertEquals("—", note.getText());
            assertEquals(41_234.0, model.getValueAt(0, 1));
            assertEquals(ColumnKind.NUMBER.width(table.getFont()), table.getColumnModel().getColumn(1).getPreferredWidth());
        });
    }

    @Test public void iconTextSortsByTextAndRendersItsIcon() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Icon icon = new ImageIcon(new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB));
            KitTables.IconText a = new KitTables.IconText(icon, "abyss"), b = new KitTables.IconText(icon, "Lost Halls");
            assertTrue(a.compareTo(b) < 0);
            DefaultTableModel model = new DefaultTableModel(new Object[]{"dungeon"}, 0);
            model.addRow(new Object[]{b});
            JTable table = new JTable(model);
            KitTables.apply(table, ColumnKind.DUNGEON);
            JLabel cell = render(table, 0, 0);
            assertEquals("Lost Halls", cell.getText());
            assertSame(icon, cell.getIcon());
        });
    }

    private static JLabel render(JTable table, int row, int column) {
        return (JLabel) table.prepareRenderer(table.getCellRenderer(row, column), row, column);
    }
}
