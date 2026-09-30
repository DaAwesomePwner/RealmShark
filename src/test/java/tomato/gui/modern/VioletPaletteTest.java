package tomato.gui.modern;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.*;
import org.junit.*;
import tomato.gui.kit.ColumnKind;
import tomato.gui.kit.KitTables;
import static org.junit.Assert.*;

public class VioletPaletteTest {
    private LookAndFeel previous;

    @Before public void remember() throws Exception { SwingUtilities.invokeAndWait(() -> previous = UIManager.getLookAndFeel()); }
    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
            if (previous != null && !(previous instanceof VioletTheme)) {
                try { UIManager.setLookAndFeel(previous); } catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
            }
        });
    }

    @Test public void darkVariantKeepsTheExistingPalette() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
            assertTrue(UIManager.getLookAndFeel() instanceof VioletTheme);
            assertEquals(new Color(0x181627), UIManager.getColor("Table.background"));
            assertEquals(new Color(0x38334F), UIManager.getColor("Component.borderColor"));
            assertEquals(new Color(0xAAA7BD), ContentStyle.color("muted"));
            assertEquals(new Color(0xE9E6F7), ContentStyle.color("text"));
        });
    }

    @Test public void lightVariantUsesTheLightRoles() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Themes.install(new Themes.Choice(Themes.Variant.LIGHT, false));
            assertTrue(UIManager.getLookAndFeel() instanceof VioletLightTheme);
            assertEquals(new Color(0xFFFFFF), UIManager.getColor("Table.background"));
            assertEquals(new Color(0xF5F4F9), ContentStyle.color("background"));
            assertEquals(new Color(0x24222E), ContentStyle.color("text"));
            assertEquals(new Color(0x6241AA), ContentStyle.color("violet"));
            assertEquals(new Color(0x626071), ContentStyle.color("muted"));
            // Plain menu items (for example Find settings, Ctrl+K) must not retain
            // FlatLightLaf's white accelerator text on our pale selection surface.
            assertEquals(new Color(0x302048), UIManager.getColor("MenuItem.acceleratorSelectionForeground"));
        });
    }

    @Test public void increaseContrastStrengthensBordersAndMutedTextInBothVariants() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Themes.install(new Themes.Choice(Themes.Variant.DARK, true));
            assertTrue(Themes.increaseContrast());
            assertEquals(new Color(0x5A5378), UIManager.getColor("Component.borderColor"));
            assertEquals(new Color(0xCFCBE0), ContentStyle.color("muted"));
            assertEquals(2, UIManager.getInt("Component.focusWidth"));

            Themes.install(new Themes.Choice(Themes.Variant.LIGHT, true));
            assertEquals(new Color(0x8C86A3), UIManager.getColor("Component.borderColor"));
            assertEquals(new Color(0x4A4757), ContentStyle.color("muted"));

            Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
            assertFalse(Themes.increaseContrast());
            assertEquals(new Color(0x38334F), UIManager.getColor("Component.borderColor"));
            assertEquals(1, UIManager.getInt("Component.focusWidth"));
        });
    }

    /**
     * P6b polish: a table that does not own focus (its detail pane or a filter does) keeps the selected row clearly marked, with
     * readable text, in both themes and under Increase contrast. Painted, not printed: JTable hides its selection while it
     * paints for print (JTable.prepareRenderer), so print-based screen captures never show a selected row.
     */
    @Test public void anUnfocusedTableKeepsItsSelectedRowClearlyVisibleWithReadableText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Themes.Variant variant : Themes.Variant.values())
                for (boolean contrast : new boolean[]{false, true}) {
                    Themes.install(new Themes.Choice(variant, contrast));
                    String theme = variant + (contrast ? " + contrast" : "");
                    JTable table = new JTable(new Object[][]{{"Pirate Cave", 840}, {"Lost Halls", 1560}, {"Ice Citadel", 1680}},
                        new Object[]{"Dungeon / area", "Seconds"});
                    KitTables.apply(table, ColumnKind.DUNGEON, ColumnKind.NUMBER);
                    table.setRowSelectionInterval(1, 1);
                    int rowHeight = table.getRowHeight();
                    table.setSize(320, rowHeight * 3);
                    table.doLayout();
                    assertFalse(table.isFocusOwner());
                    Color background = UIManager.getColor("Table.selectionInactiveBackground");
                    Color ink = UIManager.getColor("Table.selectionInactiveForeground");
                    assertEquals(theme + ": the unfocused table uses the inactive selection", background, table.getSelectionBackground());
                    Component cell = table.prepareRenderer(table.getCellRenderer(1, 0), 1, 0);
                    assertEquals(theme + ": selected cell background", background, cell.getBackground());
                    assertEquals(theme + ": selected cell text", ink, cell.getForeground());
                    assertTrue(theme + ": readable text " + ratio(ink, background), ratio(ink, background) >= 4.5);

                    BufferedImage image = new BufferedImage(table.getWidth(), table.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = image.createGraphics();
                    try { table.paint(g); } finally { g.dispose(); }
                    int x = table.getColumnModel().getColumn(0).getWidth() - 6;   // the first column's right padding: no text
                    assertEquals(theme + ": the selected row is painted with the selection", background.getRGB(), image.getRGB(x, rowHeight + rowHeight / 2));
                    for (int row : new int[]{0, 2}) {
                        Color plain = new Color(image.getRGB(x, row * rowHeight + rowHeight / 2));
                        assertTrue(theme + ": row " + row + " " + plain + " vs the selection " + background + " = " + ratio(plain, background),
                            ratio(plain, background) >= 1.2);
                    }
                }
        });
    }

    /** WCAG 2 contrast ratio. */
    private static double ratio(Color a, Color b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        double[] rgb = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
        for (int i = 0; i < 3; i++) rgb[i] = rgb[i] <= 0.03928 ? rgb[i] / 12.92 : Math.pow((rgb[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
    }
}
