package tomato.gui.myinfo;
import ui.UiTestLayout;

import org.junit.Test;
import static org.junit.Assert.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.*;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.gui.modern.VioletTheme;

public class MyInfoGuiTest {
    @Test public void hiddenBurstsCoalesceAndCopyBuildStatsBeforeEdtDelivery() throws Exception {
        MyInfoGUI[] panel = new MyInfoGUI[1]; JFrame[] frame = new JFrame[1];
        JPanel[] pages = new JPanel[1];
        java.util.concurrent.atomic.AtomicInteger changes = new java.util.concurrent.atomic.AtomicInteger();
        try {
            SwingUtilities.invokeAndWait(() -> {
                VioletTheme.install(); panel[0] = new MyInfoGUI(null);
                pages[0] = new JPanel(new CardLayout()); pages[0].add(panel[0], "build"); pages[0].add(new JPanel(), "other");
                frame[0] = new JFrame("Build refresh validation"); frame[0].setContentPane(pages[0]);
                frame[0].setSize(680, 520); frame[0].setVisible(true);
                ((CardLayout)pages[0].getLayout()).show(pages[0], "other");
                find(panel[0], JTable.class).getModel().addTableModelListener(e -> {
                    assertTrue(SwingUtilities.isEventDispatchThread()); changes.incrementAndGet();
                });
                Thread producer = new Thread(() -> {
                    Entity value = new Entity(null, 42, 0);
                    for (int i = 0; i < 200; i++) { put(value, StatType.HP_STAT, i); MyInfoGUI.updatePlayer(value); }
                    put(value, StatType.HP_STAT, 9999);
                });
                producer.start();
                try { producer.join(3000); } catch (InterruptedException e) { throw new AssertionError(e); }
                assertFalse("Capture must not wait for the EDT", producer.isAlive());
            });
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Hidden views should not rebuild", 0, changes.get());
                ((CardLayout)pages[0].getLayout()).show(pages[0], "build");
                UiTestLayout.settle(frame[0]);
                assertEquals(1, changes.get());
                find(panel[0], JTextField.class).setText("Character Health");
                JTable table = find(panel[0], JTable.class);
                assertEquals(1, table.getRowCount()); assertEquals(199d, (Double)table.getValueAt(0, 2), 0);
                assertTrue("Short windows retain a reachable data viewport", table.getParent().getHeight() >= 100);
            });
        } finally { SwingUtilities.invokeAndWait(() -> { if (frame[0] != null) frame[0].dispose(); }); }
    }

    private static void put(Entity e, StatType type, int value) {
        StatData stat = new StatData(); stat.statValue = value; e.stat.set(type, stat);
    }
    private static <T> T find(Container root, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) return type.cast(c);
            if (c instanceof Container) { T found = find((Container)c, type); if (found != null) return found; }
        }
        return null;
    }
    @Test public void partialCaptureSortSearchPetAndRendering() throws Exception {
        final MyInfoGUI[] panel = new MyInfoGUI[1];
        Entity player = new Entity(null, 1, 0);
        put(player, StatType.ATTACK_STAT, 100);
        put(player, StatType.DEXTERITY_STAT, 9);
        put(player, StatType.WISDOM_STAT, 75);
        put(player, StatType.MAX_HP_STAT, 900);
        put(player, StatType.HP_STAT, 820);
        put(player, StatType.MAX_MP_STAT, 385);
        put(player, StatType.MP_STAT, 310);
        SwingUtilities.invokeAndWait(() -> {
            VioletTheme.install();
            panel[0] = new MyInfoGUI(null);
            assertEquals(0, find(panel[0], JTable.class).getRowCount());
            MyInfoGUI.updatePlayer(player);
        });
        SwingUtilities.invokeAndWait(() -> {
            JTable table = find(panel[0], JTable.class);
            assertTrue(table.getRowCount() > 20);
            JTextField search = find(panel[0], JTextField.class);
            search.setText("Character");
            table.getRowSorter().toggleSortOrder(2);
            double last = -1;
            for (int i = 0; i < table.getRowCount(); i++) {
                Double value = (Double)table.getValueAt(i, 2);
                if (value != null) { assertTrue(value >= last); last = value; }
            }
            search.setText("[");
            assertEquals(0, table.getRowCount());
            search.setText("Damage Ability damage");
            assertEquals(1, table.getRowCount());
            assertNull(table.getValueAt(0, 2));
            search.setText("");
            Entity pet = new Entity(null, 2, 0);
            put(pet, StatType.PET_FIRST_ABILITY_TYPE_STAT, 408);
            put(pet, StatType.PET_FIRST_ABILITY_POWER_STAT, 100);
            MyInfoGUI.updatePet(pet);
        });
        SwingUtilities.invokeAndWait(() -> {
            JTextField search = find(panel[0], JTextField.class);
            JTable table = find(panel[0], JTable.class);
            search.setText("Magic heal");
            assertTrue(table.getRowCount() >= 1);
            find(panel[0], JComboBox.class).setSelectedItem("Pet");
            search.setText("Magic heal");
            assertEquals(1, table.getRowCount());
            assertEquals(45d, (Double)table.getValueAt(0, 2), .001);
            search.setText("");
            find(panel[0], JComboBox.class).setSelectedItem("All details");
            JFrame frame = new JFrame("My Info — sample capture");
            frame.setContentPane(panel[0]);
            try {
                for (int width : new int[] {1080, 680}) {
                    frame.setSize(width, 720); frame.setVisible(true); frame.validate();
                    UiTestLayout.settle(frame);
                    BufferedImage image = new BufferedImage(width, 720, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = image.createGraphics(); frame.paint(graphics); graphics.dispose();
                    File output = new File("my-info-" + width + ".png");
                    try { ImageIO.write(image, "png", output); } catch (Exception e) { throw new RuntimeException(e); }
                }
            } finally { frame.dispose(); }
            MyInfoGUI.updatePlayer(null);
        });
        SwingUtilities.invokeAndWait(() -> assertEquals(0, find(panel[0], JTable.class).getRowCount()));
    }
}
