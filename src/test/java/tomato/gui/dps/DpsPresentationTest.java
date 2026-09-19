package tomato.gui.dps;

import com.formdev.flatlaf.FlatLightLaf;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.table.TableCellRenderer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.Entity;
import tomato.backend.data.Projectile;
import tomato.backend.data.TomatoData;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.VioletTheme;
import static org.junit.Assert.*;

public class DpsPresentationTest {
    private Font previousFont;
    private LookAndFeel previousLaf;

    @Before public void installTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            previousFont = ContentStyle.body(); previousLaf = UIManager.getLookAndFeel();
            ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 14));
            setLaf(new VioletTheme());
        });
    }

    @After public void restoreTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ContentStyle.setBodyFont(previousFont); setLaf(previousLaf);
        });
    }

    @Test public void explicitLegacyFontCanReturnToReportAcrossRefreshesAndThemeChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Font explicit = new Font(Font.SERIF, Font.ITALIC, 24);
            ContentStyle.setBodyFont(explicit); ContentStyle.applyFontDefaults();
            StringDpsGUI legacy = new StringDpsGUI(new TomatoData());
            MeterDpsGUI meter = new MeterDpsGUI();
            JPanel root = new JPanel(); root.add(legacy); root.add(meter);
            JTextArea report = find(legacy, JTextArea.class);
            legacy.editFont(explicit); meter.editFont(explicit);
            ContentStyle.refreshFonts(root);
            assertEquals(Font.SERIF, report.getFont().getFamily());
            assertEquals(24, report.getFont().getSize()); assertTrue(report.getFont().isItalic());

            Font modern = new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 18);
            ContentStyle.setBodyFont(modern); ContentStyle.applyFontDefaults();
            legacy.editFont(modern); meter.editFont(modern);
            ContentStyle.refreshFonts(root); ContentStyle.refreshFonts(root);
            assertReportFont(report, modern);
            assertMeterFonts(meter, modern);

            setLaf(new FlatLightLaf()); SwingUtilities.updateComponentTreeUI(root);
            ContentStyle.refreshFonts(root); ContentStyle.refreshFonts(root);
            assertReportFont(report, modern);
            assertMeterFonts(meter, modern);
            assertTrue(report.isEnabled()); assertFalse(report.isEditable());

            // Deliberate font edits must work in both directions after a role has been cached.
            ContentStyle.setBodyFont(explicit); ContentStyle.applyFontDefaults();
            legacy.editFont(explicit); ContentStyle.refreshFonts(root);
            assertEquals(Font.SERIF, report.getFont().getFamily());
            assertEquals(24, report.getFont().getSize());
            ContentStyle.setBodyFont(modern); ContentStyle.applyFontDefaults();
            legacy.editFont(modern); meter.editFont(modern);
            setLaf(new VioletTheme()); SwingUtilities.updateComponentTreeUI(root);
            ContentStyle.refreshFonts(root);
            assertReportFont(report, modern);
            assertMeterFonts(meter, modern);
        });
    }

    @Test public void existingMeterLabelsAndPlayerRenderersFollowLightTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MeterDpsGUI meter = populatedMeter();
            JLabel scope = field(meter, "scope", JLabel.class);
            JTextArea warning = field(meter, "captureWarning", JTextArea.class);
            JTable table = field(meter, "table", JTable.class);
            JList<Entity> enemies = enemyList(meter);
            TableCellRenderer playerRenderer = table.getCellRenderer(0, 0), classRenderer = table.getCellRenderer(0, 1);
            ListCellRenderer<? super Entity> enemyRenderer = enemies.getCellRenderer();
            field(meter, "colors", JCheckBox.class).setSelected(false);
            assertEquals(new Color(0xAAA7BD), scope.getForeground());
            assertEquals(new Color(0xEBC384), warning.getForeground());
            assertTrue(warning.isVisible());
            assertRendererColors(table, enemies, new Color(0xAAA7BD));

            setLaf(new FlatLightLaf()); SwingUtilities.updateComponentTreeUI(meter);
            ContentStyle.refreshFonts(meter);
            assertEquals(new Color(0x626071), scope.getForeground());
            assertEquals(new Color(0x805510), warning.getForeground());
            assertSame(playerRenderer, table.getCellRenderer(0, 0));
            assertSame(classRenderer, table.getCellRenderer(0, 1));
            assertSame(enemyRenderer, enemies.getCellRenderer());
            assertRendererColors(table, enemies, new Color(0x626071));
            assertEquals("Alice", table.getValueAt(0, 0)); assertEquals(1200L, table.getValueAt(0, 2));

            setLaf(new VioletTheme()); SwingUtilities.updateComponentTreeUI(meter);
            ContentStyle.refreshFonts(meter);
            assertEquals(new Color(0xAAA7BD), scope.getForeground());
            assertEquals(new Color(0xEBC384), warning.getForeground());
            assertRendererColors(table, enemies, new Color(0xAAA7BD));
        });
    }

    @Test public void actualEnemyAndPlayerRenderersPaintFocusBordersWithTheirPadding() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MeterDpsGUI meter = populatedMeter();
            JTable table = field(meter, "table", JTable.class);
            JList<Entity> enemies = enemyList(meter);
            for (LookAndFeel laf : new LookAndFeel[]{new VioletTheme(), new FlatLightLaf()}) {
                setLaf(laf); SwingUtilities.updateComponentTreeUI(meter); ContentStyle.refreshFonts(meter);
                Border tableFocus = BorderFactory.createLineBorder(Color.MAGENTA, 2);
                Border listFocus = BorderFactory.createLineBorder(Color.CYAN, 2);
                UIDefaults defaults = UIManager.getLookAndFeelDefaults();
                defaults.put("Table.focusCellHighlightBorder", tableFocus);
                defaults.put("Table.focusSelectedCellHighlightBorder", tableFocus);
                defaults.put("List.focusCellHighlightBorder", listFocus);
                defaults.put("List.focusSelectedCellHighlightBorder", listFocus);
                for (boolean selected : new boolean[]{false, true}) {
                    JComponent player = playerCell(table, selected, true);
                    assertTrue("Player renderer must retain the LAF focus border", contains(player.getBorder(), tableFocus));
                    Insets playerInsets = player.getInsets();
                    assertTrue(playerInsets.left >= 8); assertTrue(playerInsets.right > playerInsets.left);
                    assertPaintedBorder(player, Color.MAGENTA);
                    assertFalse(contains(playerCell(table, selected, false).getBorder(), tableFocus));
                    assertEquals("Repeated rendering must not accumulate padding", playerInsets, playerCell(table, selected, true).getInsets());

                    JComponent enemy = enemyCell(enemies, selected, true);
                    assertTrue("Returned enemy panel must retain the LAF focus border", contains(enemy.getBorder(), listFocus));
                    Insets enemyInsets = enemy.getInsets();
                    assertTrue(enemyInsets.left >= 10); assertTrue(enemyInsets.top >= 6);
                    assertPaintedBorder(enemy, Color.CYAN);
                    assertFalse(contains(enemyCell(enemies, selected, false).getBorder(), listFocus));
                    assertEquals(enemyInsets, enemyCell(enemies, selected, true).getInsets());
                }
            }
        });
    }

    private static void assertMeterFonts(MeterDpsGUI meter, Font base) {
        assertReportFont(field(meter, "details", JTextArea.class), base);
        assertEquals(base.getName(), enemyList(meter).getFont().getName());
        assertEquals(base.getSize(), field(meter, "table", JTable.class).getFont().getSize());
        JLabel summary = field(meter, "summary", JLabel.class), scope = field(meter, "scope", JLabel.class);
        assertTrue(summary.getFont().isBold()); assertFalse(summary.getFont().isItalic());
        assertEquals(base.getSize2D(), summary.getFont().getSize2D(), .01f);
        assertEquals(ContentStyle.metadata(base).getSize2D(), scope.getFont().getSize2D(), .01f);
        assertEquals(base.getSize(), field(meter, "captureWarning", JTextArea.class).getFont().getSize());
    }

    private static void assertReportFont(JTextArea area, Font base) {
        Font expected = ContentStyle.report(base);
        assertEquals(expected.getName(), area.getFont().getName());
        assertEquals(base.getSize2D(), area.getFont().getSize2D(), .01f);
        assertEquals(base.getStyle(), area.getFont().getStyle());
    }

    private static void assertRendererColors(JTable table, JList<Entity> enemies, Color muted) {
        TableCellRenderer classes = table.getCellRenderer(0, 1);
        Component classLabel = classes.getTableCellRendererComponent(table, table.getValueAt(0, 1), false, false, 0, 1);
        assertEquals(muted, classLabel.getForeground());
        assertEquals(table.getForeground(), playerCell(table, false, false).getForeground());
        assertEquals(table.getSelectionForeground(), playerCell(table, true, false).getForeground());
        classLabel = classes.getTableCellRendererComponent(table, table.getValueAt(0, 1), true, false, 0, 1);
        assertEquals(table.getSelectionForeground(), classLabel.getForeground());
        JPanel row = (JPanel)enemyCell(enemies, false, false);
        BorderLayout layout = (BorderLayout)row.getLayout();
        assertEquals(muted, layout.getLayoutComponent(BorderLayout.SOUTH).getForeground());
        assertEquals(enemies.getForeground(), layout.getLayoutComponent(BorderLayout.NORTH).getForeground());
        enemyCell(enemies, true, false);
        assertEquals(enemies.getSelectionForeground(), layout.getLayoutComponent(BorderLayout.SOUTH).getForeground());
    }

    private static JComponent playerCell(JTable table, boolean selected, boolean focus) {
        return (JComponent)table.getCellRenderer(0, 0).getTableCellRendererComponent(table, table.getValueAt(0, 0), selected, focus, 0, 0);
    }
    private static JComponent enemyCell(JList<Entity> enemies, boolean selected, boolean focus) {
        return (JComponent)enemies.getCellRenderer().getListCellRendererComponent(enemies, enemies.getModel().getElementAt(1), 1, selected, focus);
    }
    private static boolean contains(Border border, Border expected) {
        if (border == expected) return true;
        if (!(border instanceof CompoundBorder)) return false;
        CompoundBorder compound = (CompoundBorder)border;
        return contains(compound.getOutsideBorder(), expected) || contains(compound.getInsideBorder(), expected);
    }
    private static void assertPaintedBorder(JComponent cell, Color color) {
        cell.setSize(340, 80); cell.doLayout();
        BufferedImage image = new BufferedImage(340, 80, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { cell.paint(graphics); } finally { graphics.dispose(); }
        assertEquals("The focus outline must actually be painted", color.getRGB(), image.getRGB(0, 0));
    }
    private static MeterDpsGUI populatedMeter() {
        Filter.disable();
        Entity player = new Entity(null, 1, 0) { @Override public String name() { return "Alice"; } };
        player.objectType = 768;
        Entity enemy = new Entity(null, 11, 0) { @Override public String name() { return "Test boss"; } };
        enemy.genericDamageHit(player, new Projectile(1200), 1000);
        enemy.updateDamageTaken(1000); enemy.updateDamageTaken(3000);
        MapInfoPacket map = new MapInfoPacket(); map.name = "Test encounter";
        MeterDpsGUI meter = new MeterDpsGUI();
        meter.renderData(map, Collections.singletonList(enemy), new ArrayList<>(), 3000, true);
        return meter;
    }
    @SuppressWarnings("unchecked") private static JList<Entity> enemyList(MeterDpsGUI meter) { return field(meter, "enemyList", JList.class); }
    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(target));
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static <T> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container) { T found = find((Container)child, type); if (found != null) return found; }
        }
        return null;
    }
    private static void setLaf(LookAndFeel laf) {
        try { UIManager.setLookAndFeel(laf); ContentStyle.applyFontDefaults(); }
        catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
    }
}
