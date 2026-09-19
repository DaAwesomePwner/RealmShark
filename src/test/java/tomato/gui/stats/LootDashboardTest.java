package tomato.gui.stats;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import static org.junit.Assert.*;

public class LootDashboardTest {
    @Test public void aggregatesDuplicatesAndBoostedBagsAndFiltersLive() throws Exception {
        LootDashboard[] panel = new LootDashboard[1]; JFrame[] frame = new JFrame[1];
        LootDashboard.Item potion = new LootDashboard.Item(2591, "Potion of Attack", true);
        LootDashboard.Item white = new LootDashboard.Item(99999, "Example white item", false);
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel[0] = new LootDashboard();
                panel[0].acceptAll(Arrays.asList(
                    new LootDashboard.Drop("B.White", "The Shatters", "Boss", 1000, Arrays.asList(potion, potion, white)),
                    new LootDashboard.Drop("Blue", "Sprite World", "Limon", 2000, Arrays.asList(potion))));
                frame[0] = show(panel[0]);
                assertEquals(2, table(panel[0], 0).getRowCount());
                assertEquals(3, table(panel[0], 1).getValueAt(0, 2));
                assertEquals("Sprite World", table(panel[0], 1).getValueAt(0, 3));
                assertEquals(2, table(panel[0], 2).getRowCount());
                assertEquals(2, table(panel[0], 3).getRowCount());
                assertEquals("Blue", table(panel[0], 4).getValueAt(0, 1));
                find(panel[0], JComboBox.class).setSelectedItem("B.White");
                assertEquals(2, table(panel[0], 1).getValueAt(0, 2));
                assertEquals(1, table(panel[0], 4).getRowCount());
                find(panel[0], JTextField.class).setText("Attack");
                assertEquals(1, table(panel[0], 0).getRowCount());
                panel[0].accept(new LootDashboard.Drop("B.White", "Lost Halls", "Boss", 3000, Arrays.asList(potion)));
            });
            // Rendering is coalesced; logical counters are already available before this EDT turn.
            assertArrayEquals(new int[]{3, 5}, panel[0].sessionTotals());
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(3, table(panel[0], 1).getValueAt(0, 2));
                assertEquals("Lost Halls", table(panel[0], 1).getValueAt(0, 3));
                JTextField search = find(panel[0], JTextField.class);
                search.setText("["); assertEquals(0, table(panel[0], 0).getRowCount());
                search.setText(""); find(panel[0], JComboBox.class).setSelectedItem("All bags");
                try {
                    for (int i = 0; i < 6; i++) {
                        table(panel[0], i); frame[0].validate();
                        BufferedImage image = new BufferedImage(frame[0].getWidth(), frame[0].getHeight(), BufferedImage.TYPE_INT_RGB);
                        Graphics2D graphics = image.createGraphics(); frame[0].printAll(graphics); graphics.dispose();
                        File dir = new File("screenshots"); dir.mkdirs();
                        ImageIO.write(image, "png", new File(dir, "loot-sample-" + i + ".png"));
                    }
                } catch (Exception e) { throw new AssertionError(e); }
            });
        } finally { SwingUtilities.invokeAndWait(() -> { if (frame[0] != null) frame[0].dispose(); }); }
    }

    @Test public void captureThreadCopiesMutableBagBeforeEdtUpdate() throws Exception {
        LootDashboard[] panel = new LootDashboard[1]; JFrame[] frame = new JFrame[1];
        Entity bag = new Entity(null, 1, 0); bag.objectType = 1292;
        StatData stat = new StatData(); stat.statValue = 123456;
        bag.stat.set(StatType.INVENTORY_0_STAT, stat);
        try {
            SwingUtilities.invokeAndWait(() -> panel[0] = new LootDashboard());
            panel[0].receive(null, bag, null, 1234); stat.statValue = -1;
            assertArrayEquals(new int[]{1, 1}, panel[0].sessionTotals());
            assertEquals(123456, panel[0].recentDrops().get(0).items.get(0).id);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Hidden views remain unbuilt", 0, rawTable(panel[0], 0).getRowCount());
                frame[0] = show(panel[0]);
                assertEquals(1, table(panel[0], 0).getRowCount());
                assertEquals(1, table(panel[0], 2).getRowCount());
                assertEquals(1, table(panel[0], 0).getValueAt(0, 2));
            });
        } finally { SwingUtilities.invokeAndWait(() -> { if (frame[0] != null) frame[0].dispose(); }); }
    }

    @Test public void burstUpdatesOnceRetainsRecentHistoryAndDefersHiddenSharedViews() throws Exception {
        LootDashboard[] panels = new LootDashboard[2]; JFrame[] frames = new JFrame[2];
        AtomicInteger visibleEvents = new AtomicInteger(), hiddenEvents = new AtomicInteger(), inactiveEvents = new AtomicInteger();
        LootDashboard.Item item = new LootDashboard.Item(999991, "Sample item", false);
        ExecutorService producer = Executors.newSingleThreadExecutor();
        try {
            SwingUtilities.invokeAndWait(() -> {
                panels[0] = new LootDashboard(); panels[1] = new LootDashboard(panels[0]);
                frames[0] = show(panels[0]);
                rawTable(panels[0], 0).getModel().addTableModelListener(e -> { assertTrue(SwingUtilities.isEventDispatchThread()); visibleEvents.incrementAndGet(); });
                rawTable(panels[0], 4).getModel().addTableModelListener(e -> inactiveEvents.incrementAndGet());
                rawTable(panels[1], 0).getModel().addTableModelListener(e -> hiddenEvents.incrementAndGet());
                try {
                    producer.submit(() -> {
                        for (int i = 0; i < 1105; i++) panels[0].accept(new LootDashboard.Drop("White", "Lost Halls", "Boss", i, Arrays.asList(item)));
                    }).get(5, TimeUnit.SECONDS);
                } catch (Exception e) { throw new AssertionError(e); }
                assertArrayEquals(new int[]{1105, 1105}, panels[1].sessionTotals());
                assertEquals(0, visibleEvents.get());
            });
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(1, visibleEvents.get()); assertEquals(0, hiddenEvents.get()); assertEquals(0, inactiveEvents.get());
                assertEquals(1105, table(panels[0], 0).getValueAt(0, 2));
                assertEquals(1000, panels[0].recentDrops().size());
                assertEquals(1104, panels[0].recentDrops().get(0).time);
                assertEquals(105, panels[0].recentDrops().get(999).time);
                panels[0].recentDrops().clear(); assertEquals(1000, panels[1].recentDrops().size());
                JTable recent = table(panels[0], 4);
                assertEquals(1, inactiveEvents.get()); assertEquals(1000, recent.getRowCount());
                recent.getRowSorter().toggleSortOrder(0);
                assertEquals(105L, recent.getValueAt(0, 0));
                panels[0].accept(new LootDashboard.Drop("Blue", "Sprite World", "Limon", 600000, Arrays.asList(item)));
            });
            SwingUtilities.invokeAndWait(() -> {
                frames[1] = show(panels[1]);
                assertEquals(1106, table(panels[1], 0).getValueAt(0, 2));
                find(panels[1], JComboBox.class).setSelectedItem("Blue");
                assertEquals(1, table(panels[1], 0).getValueAt(0, 2));
                assertEquals(1106, table(panels[0], 0).getValueAt(0, 2));
                find(panels[0], JTextField.class).setText("missing");
                assertEquals(0, table(panels[0], 0).getRowCount());
                assertEquals(1, table(panels[1], 0).getRowCount());
            });
        } finally {
            producer.shutdownNow();
            SwingUtilities.invokeAndWait(() -> { for (JFrame frame : frames) if (frame != null) frame.dispose(); });
        }
    }

    private static JFrame show(LootDashboard panel) {
        JFrame frame = new JFrame("Loot preview sample"); frame.setContentPane(panel);
        frame.setSize(1050, 650); frame.setVisible(true); frame.validate(); return frame;
    }
    private static JTable table(Container panel, int view) {
        find(panel, JTabbedPane.class).setSelectedIndex(view);
        return rawTable(panel, view);
    }
    private static JTable rawTable(Container panel, int view) {
        return find((Container)find(panel, JTabbedPane.class).getComponentAt(view), JTable.class);
    }
    private static <T> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container) { T result = find((Container)child, type); if (result != null) return result; }
        }
        return null;
    }
}
