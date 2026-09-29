package tomato.gui.stats;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * The saved fame graph's windows and the live loot views' shared counts. (P6a removed the Statistics page: its live fame graph,
 * fame table, dungeon stats and page renders went with it.)
 */
public class StatisticsExplorerTest {
    @Test public void graphWindowsUseLatestSampleAndHandleFlatAndDuplicateTimestamps() throws Exception {
        ArrayList<Fame> samples = new ArrayList<>(Arrays.asList(new Fame(100, 0), new Fame(110, 60000), new Fame(110, 120000)));
        assertEquals(2, GraphPanel.window(samples, 60000).size());
        assertEquals(110, GraphPanel.window(samples, 60000).get(0).getFame(), 0);
        SwingUtilities.invokeAndWait(() -> {
            GraphPanel graph = new GraphPanel(new ArrayList<>(Arrays.asList(new Fame(100, 0), new Fame(100, 60000))), false);
            render(graph, "statistics-flat-graph", 850, 420);
            graph.setScores(new ArrayList<>(Arrays.asList(new Fame(100, 5), new Fame(110, 5))));
            render(graph, "statistics-same-time", 850, 420);
        });
    }

    @Test public void lootViewsShareCountsButKeepFiltersIndependent() throws Exception {
        LootDashboard[] dashboards = new LootDashboard[2];
        JFrame[] frame = new JFrame[1];
        int[] mirrorChanges = new int[1];
        LootDashboard.Item item = new LootDashboard.Item(999991, "Sample item", false);
        try {
            SwingUtilities.invokeAndWait(() -> {
                dashboards[0] = new LootDashboard(); dashboards[1] = new LootDashboard(dashboards[0]);
                JPanel content = new JPanel(new GridLayout(1, 2));
                content.add(dashboards[0]); content.add(dashboards[1]);
                frame[0] = new JFrame("Shared loot views"); frame[0].setContentPane(content);
                frame[0].setSize(1280, 720); frame[0].setVisible(true); frame[0].validate();
                assertTrue(dashboards[0].isShowing()); assertTrue(dashboards[1].isShowing());
                dashboards[0].accept(new LootDashboard.Drop("White", "Lost Halls", "Boss", 1000, Arrays.asList(item, item)));
                dashboards[0].accept(new LootDashboard.Drop("Blue", "Sprite World", "Limon", 600000, Arrays.asList(item)));
            });
            // The next EDT turn runs after the coalesced acceptance callback. Select each table
            // before inspecting its rows, just as a user does when opening a deferred view.
            SwingUtilities.invokeAndWait(() -> {
                LootDashboard panel = dashboards[0], mirror = dashboards[1];
                named(panel, "loot-dungeon-filter", JComboBox.class).setSelectedItem("Lost Halls");
                assertEquals(2, named(panel, "loot-view-0", JTable.class).getValueAt(0, 2));
                assertEquals(3, named(mirror, "loot-view-0", JTable.class).getValueAt(0, 2));
                named(panel, "loot-bag-filter", JComboBox.class).setSelectedItem("Blue");
                view(panel, 3);
                assertEquals(0, named(panel, "loot-view-3", JTable.class).getRowCount());
                view(mirror, 4);
                named(mirror, "loot-recent-range", JComboBox.class).setSelectedIndex(1);
                assertEquals(1, named(mirror, "loot-view-4", JTable.class).getRowCount());
                view(mirror, 0);
                assertEquals(3, named(mirror, "loot-view-0", JTable.class).getValueAt(0, 2));

                mirror.setVisible(false); frame[0].validate(); assertFalse(mirror.isShowing());
                named(mirror, "loot-view-0", JTable.class).getModel().addTableModelListener(e -> mirrorChanges[0]++);
                view(panel, 0);
                panel.accept(new LootDashboard.Drop("Blue", "Lost Halls", "Boss", 700000, Arrays.asList(item)));
                assertArrayEquals(new int[]{3, 4}, mirror.sessionTotals());
            });
            SwingUtilities.invokeAndWait(() -> {
                LootDashboard panel = dashboards[0], mirror = dashboards[1];
                assertEquals(1, named(panel, "loot-view-0", JTable.class).getValueAt(0, 2));
                assertEquals("Hidden mirror receives no table rebuild", 0, mirrorChanges[0]);
                assertEquals("Hidden rows retain their previous rendering", 3, named(mirror, "loot-view-0", JTable.class).getValueAt(0, 2));
                mirror.setVisible(true); frame[0].validate();
            });
            SwingUtilities.invokeAndWait(() -> {
                LootDashboard panel = dashboards[0], mirror = dashboards[1];
                assertTrue(mirror.isShowing());
                assertEquals("Showing the mirror catches up once", 1, mirrorChanges[0]);
                assertEquals(4, named(mirror, "loot-view-0", JTable.class).getValueAt(0, 2));
                assertEquals("Lost Halls", named(panel, "loot-dungeon-filter", JComboBox.class).getSelectedItem());
                assertEquals("Blue", named(panel, "loot-bag-filter", JComboBox.class).getSelectedItem());
                assertEquals("All dungeons", named(mirror, "loot-dungeon-filter", JComboBox.class).getSelectedItem());
                assertEquals("All bags", named(mirror, "loot-bag-filter", JComboBox.class).getSelectedItem());
                assertEquals(1, named(mirror, "loot-recent-range", JComboBox.class).getSelectedIndex());
                view(mirror, 4);
                JTable recent = named(mirror, "loot-view-4", JTable.class);
                assertEquals(2, recent.getRowCount());
                recent.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
                assertEquals(600000L, recent.getValueAt(0, 0));
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (frame[0] != null) frame[0].dispose(); });
        }
    }

    private static void render(JComponent panel, String name, int width, int height) {
        JFrame frame = new JFrame("Fame graph · Preview sample"); frame.setContentPane(panel);
        try {
            frame.setSize(width, height); frame.setVisible(true); frame.validate();
            // A second layout pass accounts for wrapped filter bars after receiving their actual width.
            invalidateTree(panel); frame.validate(); invalidateTree(panel); frame.validate();
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics(); frame.printAll(g); g.dispose();
            File dir = new File("screenshots"); dir.mkdirs(); ImageIO.write(image, "png", new File(dir, name + ".png"));
            verifyControls(panel);
        } catch (Exception e) { throw new AssertionError(e); } finally { frame.dispose(); }
    }
    private static void verifyControls(Container root) {
        for (Component c : root.getComponents()) {
            if (!c.isShowing()) continue;
            if (c instanceof JTextField || c instanceof JComboBox || c instanceof JButton) {
                assertTrue("Clipped control " + c.getName() + " " + c.getClass().getSimpleName() + " " + c.getBounds() + " in " + root.getSize(), c.getX() >= 0 && c.getY() >= 0 && c.getX() + c.getWidth() <= root.getWidth() && c.getY() + c.getHeight() <= root.getHeight());
            }
            if (c instanceof JTable && ((JTable)c).getRowCount() > 0) {
                assertTrue("No room for data rows: " + c.getName(), root.getHeight() >= 60);
            }
            if (c instanceof Container) verifyControls((Container)c);
        }
    }
    private static void invalidateTree(Container root) {
        for (Component child : root.getComponents()) if (child instanceof Container) invalidateTree((Container)child);
        root.invalidate();
    }
    static <T> T named(Container root, String name, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && name.equals(c.getName())) return type.cast(c);
            if (c instanceof Container) { T result = named((Container)c, name, type); if (result != null) return result; }
        }
        return null;
    }
    /** The tabs became a view selector: chooses the live view at {@code index} (its persisted index) as a user does. */
    private static void view(LootDashboard panel, int index) {
        named(panel, "loot-views", JComboBox.class).setSelectedItem(LootExploreModel.liveView(index));
    }
}
