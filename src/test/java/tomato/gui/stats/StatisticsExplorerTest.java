package tomato.gui.stats;

import com.google.gson.Gson;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.Test;
import tomato.backend.data.DungeonStatData;
import tomato.gui.modern.VioletTheme;
import tomato.gui.stats.data.MapFameData;
import static org.junit.Assert.*;

public class StatisticsExplorerTest {
    @Test public void equalFameCharacterSwitchesReachGraphAndPinnedSelectionStaysPinned() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FameTrackerGUI panel = new FameTrackerGUI();
            tomato.backend.data.FameTracker.trackFame(9101, 159929, 1000);
            tomato.backend.data.FameTracker.trackFame(9102, 159929, 2000);
            assertTrue(panel.getFameData().containsKey(9101)); assertTrue(panel.getFameData().containsKey(9102));
            assertEquals(1000, panel.getFameData().get(9101).get(0).getTime());
            panel.refreshNow();
            named(panel, "fame-graph-character", JComboBox.class).setSelectedItem("Character #9101");
            FameTrackerGUI.updateFame(9102, 150, System.currentTimeMillis() + 60000);
            panel.refreshNow();
            assertEquals("Character #9101", named(panel, "fame-graph-character", JComboBox.class).getSelectedItem());
            assertEquals(100, find(panel, GraphPanel.class).getScores().get(0).getFame(), 0);
        });
    }

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

    @Test public void fameMapsTrackOnlyActiveCharacterAndIncludeOpenAndZeroGainVisitsOnce() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FameTableBridge.getInstance().setFameTrackerGUI(null);
            FameTablePanel panel = new FameTablePanel(null);
            panel.onMapChange("Sprite World", 1000);
            panel.updateFame(1, 100, 1000, "Wizard");
            panel.updateFame(1, 120, 61000, "Wizard");
            assertEquals(1, panel.getMapFameData().get(1).size());
            assertEquals(20, panel.getMapFameData().get(1).get(0).getFameGained(), 0);
            panel.onMapChange("Lost Halls", 62000);
            panel.updateFame(1, 125, 63000, "Wizard");
            assertEquals(5, panel.getCurrentMapData().get(1).getFameGained(), 0);
            panel.updateFame(2, 500, 64000, "Priest");
            panel.updateFame(2, 510, 124000, "Priest");
            panel.onMapChange("Nexus", 125000);
            assertEquals(2, panel.getMapFameData().get(1).size());
            assertEquals(1, panel.getMapFameData().get(2).size());
            panel.updateFame(2, 510, 126000, "Priest");
            panel.onMapChange("Nexus", 127000);
            assertEquals(2, panel.getMapFameData().get(2).size());
            assertEquals(0, panel.getMapFameData().get(2).get(1).getFameGained(), 0);
            panel.refreshNow();
            JTable table = named(panel, "fame-characters", JTable.class);
            table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(3, SortOrder.DESCENDING)));
            assertTrue(table.getValueAt(0, 0).toString().contains("Priest"));
            named(panel, "fame-search", JTextField.class).setText("wizard");
            panel.refreshNow();
            assertEquals(1, table.getRowCount());
            assertEquals(25.0, (Double)table.getValueAt(0, 4), 0);
            assertEquals(2, named(panel, "fame-maps", JTable.class).getRowCount());
            named(panel, "fame-map-search", JTextField.class).setText("sprite");
            panel.refreshNow();
            assertEquals(1, named(panel, "fame-maps", JTable.class).getRowCount());
            named(panel, "fame-search", JTextField.class).setText("["); panel.refreshNow(); assertEquals(0, table.getRowCount());
        });
    }

    @Test public void dungeonFiltersPreserveLootOnlySourcesAndNumericSorting() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DungeonStats panel = new DungeonStats(); DungeonStatData data = dungeonSample();
            DungeonStats.update(data, null); panel.refreshData();
            JTable dungeons = named(panel, "dungeon-table", JTable.class);
            assertEquals(2, dungeons.getRowCount());
            dungeons.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(1, SortOrder.DESCENDING)));
            assertEquals("Lost Halls", dungeons.getValueAt(0, 0));
            dungeons.setRowSelectionInterval(0, 0);
            assertEquals(3, named(panel, "dungeon-enemies", JTable.class).getRowCount());
            assertEquals(3, named(panel, "dungeon-items", JTable.class).getRowCount());
            named(panel, "dungeon-search", JTextField.class).setText("halls"); assertEquals(1, dungeons.getRowCount());
            data.data.get("Lost Halls").addItems(77, 333);
            DungeonStats.update(data, "Lost Halls"); panel.refreshData(); assertEquals(1, dungeons.getRowCount());
            assertEquals(4, named(panel, "dungeon-items", JTable.class).getRowCount());
            named(panel, "dungeon-detail-search", JTextField.class).setText("333");
            assertEquals(1, named(panel, "dungeon-items", JTable.class).getRowCount());
            named(panel, "dungeon-search", JTextField.class).setText("["); assertEquals(0, dungeons.getRowCount());
            assertEquals(0, named(panel, "dungeon-items", JTable.class).getRowCount());
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
                find(panel, JTabbedPane.class).setSelectedIndex(3);
                assertEquals(0, named(panel, "loot-view-3", JTable.class).getRowCount());
                find(mirror, JTabbedPane.class).setSelectedIndex(4);
                named(mirror, "loot-recent-range", JComboBox.class).setSelectedIndex(1);
                assertEquals(1, named(mirror, "loot-view-4", JTable.class).getRowCount());
                find(mirror, JTabbedPane.class).setSelectedIndex(0);
                assertEquals(3, named(mirror, "loot-view-0", JTable.class).getValueAt(0, 2));

                mirror.setVisible(false); frame[0].validate(); assertFalse(mirror.isShowing());
                named(mirror, "loot-view-0", JTable.class).getModel().addTableModelListener(e -> mirrorChanges[0]++);
                find(panel, JTabbedPane.class).setSelectedIndex(0);
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
                find(mirror, JTabbedPane.class).setSelectedIndex(4);
                JTable recent = named(mirror, "loot-view-4", JTable.class);
                assertEquals(2, recent.getRowCount());
                recent.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
                assertEquals(600000L, recent.getValueAt(0, 0));
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (frame[0] != null) frame[0].dispose(); });
        }
    }

    @Test public void renderPopulatedStatisticsAtDesktopAndCompactWidths() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VioletTheme.install(); StatisticsGUI panel = new StatisticsGUI(null);
            long start = 1700000000000L;
            FameTablePanel table = FameTablePanel.getInstance();
            FameTableBridge.getInstance().setFameTrackerGUI(null); // Fixture creation must not autosave.
            table.onMapChange("Lost Halls", start);
            for (int i = 0; i < 21; i++) {
                long fame = 2500 + i * 13 + (i > 12 ? 90 : 0);
                FameTrackerGUI.updateFame(101, fame, start + i * 60000);
                table.updateFame(101, fame, start + i * 60000, "Wizard");
            }
            table.onMapChange("The Shatters", start + 1260000);
            table.updateFame(202, 6800, start + 1260000, "Priest");
            table.updateFame(202, 7120, start + 1800000, "Priest");
            DungeonStats.update(dungeonSample(), null);
            find(panel, DungeonStats.class).refreshData();
            LootDashboard loot = panel.getLootDashboard();
            loot.accept(new LootDashboard.Drop("White", "Lost Halls", "Marble Colossus", start, Arrays.asList(new LootDashboard.Item(999992, "Preview white item", false))));
            loot.accept(new LootDashboard.Drop("Blue", "Sprite World", "Limon", start + 60000, Arrays.asList(new LootDashboard.Item(999993, "Potion of Dexterity", true))));
            JTabbedPane tabs = named(panel, "statistics-tabs", JTabbedPane.class);
            for (int width : new int[]{1040, 680}) {
                for (int i = 0; i < tabs.getTabCount(); i++) {
                    tabs.setSelectedIndex(i); render(panel, "statistics-" + i + "-" + width, width, 710);
                }
                tabs.setSelectedIndex(1);
                find(table, JTabbedPane.class).setSelectedIndex(1); render(panel, "statistics-map-" + width, width, 710);
            }
            tabs.setSelectedIndex(3);
            named(panel, "dungeon-views", JTabbedPane.class).setSelectedIndex(2);
            render(panel, "statistics-dungeon-loot", 1040, 710);
            JComponent[] pages = new JComponent[tomato.gui.modern.WorkspaceShell.TITLES.length]; Arrays.setAll(pages, i -> new JPanel());
            pages[4] = panel; pages[8] = loot;
            tomato.gui.modern.WorkspaceShell shell = new tomato.gui.modern.WorkspaceShell(pages, () -> {}, true); shell.select(4);
            for (int i = 0; i < tabs.getTabCount(); i++) {
                tabs.setSelectedIndex(i); render(shell, "statistics-workspace-" + i, 1240, 800);
                render(shell, "statistics-workspace-compact-" + i, 760, 620);
            }
        });
    }

    private static DungeonStatData dungeonSample() {
        return new Gson().fromJson("{\"data\":{\"Lost Halls\":{\"name\":\"Lost Halls\",\"enteredDungeon\":100,\"totalTime\":9876000,\"entityDamaged\":{\"55\":1000},\"entityLoot\":{\"55\":{\"lootList\":{\"111\":8}},\"66\":{\"lootList\":{\"222\":2}},\"0\":{\"lootList\":{\"222\":1}}}},\"Sprite World\":{\"name\":\"Sprite World\",\"enteredDungeon\":9,\"totalTime\":320000,\"entityDamaged\":{},\"entityLoot\":{}}}}", DungeonStatData.class);
    }
    private static void render(JComponent panel, String name, int width, int height) {
        JFrame frame = new JFrame("Statistics · Preview sample"); frame.setContentPane(panel);
        try {
            frame.setSize(width, height); frame.setVisible(true); frame.validate();
            FameTablePanel fameTable = panel instanceof FameTablePanel ? (FameTablePanel)panel : find(panel, FameTablePanel.class);
            FameTrackerGUI fameGraph = panel instanceof FameTrackerGUI ? (FameTrackerGUI)panel : find(panel, FameTrackerGUI.class);
            if (fameTable != null) fameTable.refreshNow();
            if (fameGraph != null) fameGraph.refreshNow();
            if (panel instanceof tomato.gui.modern.WorkspaceShell) {
                panel.dispatchEvent(new java.awt.event.ComponentEvent(panel, java.awt.event.ComponentEvent.COMPONENT_RESIZED));
            }
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
    private static <T> T find(Container root, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) return type.cast(c);
            if (c instanceof Container) { T result = find((Container)c, type); if (result != null) return result; }
        }
        return null;
    }
}
