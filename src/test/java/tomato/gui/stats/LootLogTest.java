package tomato.gui.stats;

import java.awt.*;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.*;
import tomato.gui.modern.ContentStyle;
import tomato.realmshark.enums.LootBags;
import static org.junit.Assert.*;

public class LootLogTest {
    @Test public void itemTooltipsDistinguishSlotsFromAppliedEnchantsAndSurviveMalformedData() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Entity bag = new Entity(null, 1, 0);
                for (int slot = 0; slot < 3; slot++) {
                    StatData stat = new StatData(); stat.statValue = 999991;
                    bag.stat.set(StatType.byOrdinal(StatType.INVENTORY_0_STAT.get() + slot), stat);
                }
                StatData unique = new StatData();
                unique.stringStatValue = LootEquipmentTest.encode(101, -1, -2) + ",bad,AAIE_f_9__3__f8=";
                bag.stat.set(StatType.UNIQUE_DATA_STRING, unique);
                java.lang.reflect.Method render = LootGUI.class.getDeclaredMethod("displayBagLootIcons", Entity.class, JPanel.class);
                render.setAccessible(true); JPanel row = new JPanel(); render.invoke(null, bag, row);
                assertTrue(hasTooltip(row, "Rare · 2 unlocked slots · 1 applied enchants"));
                assertTrue(hasTooltip(row, "Unknown enchant data"));
                assertTrue(hasTooltip(row, "Common / Unenchanted · 0 unlocked slots · 0 applied enchants"));
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    @Test public void producerDoesNotWaitForSwingAndRetainedRowsUseDetachedStats() throws Exception {
        LootGUI[] view = new LootGUI[1]; JPanel[] rows = new JPanel[1];
        AtomicBoolean offEdtMutation = new AtomicBoolean();
        ExecutorService capture = Executors.newSingleThreadExecutor();
        boolean whiteFilter = LootGUI.filterWhiteBag;
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    view[0] = new LootGUI(new TomatoData());
                    LootGUI.lootSharing(true); LootGUI.filterWhiteBag = true;
                    LootGUI.editFont(ContentStyle.body());
                    Field field = LootGUI.class.getDeclaredField("lootPanel"); field.setAccessible(true);
                    rows[0] = (JPanel)field.get(view[0]);
                    rows[0].addContainerListener(new ContainerAdapter() {
                        public void componentAdded(ContainerEvent e) { if (!SwingUtilities.isEventDispatchThread()) offEdtMutation.set(true); }
                        public void componentRemoved(ContainerEvent e) { if (!SwingUtilities.isEventDispatchThread()) offEdtMutation.set(true); }
                    });
                    capture.submit(() -> {
                        LootGUI.updateExaltStats();
                        Entity player = new Entity(null, 1, 0); player.objectType = 768;
                        Entity bag = new Entity(null, 2, 0); bag.objectType = LootBags.WHITE.getId();
                        StatData item = new StatData(); item.statValue = 999991;
                        bag.stat.set(StatType.INVENTORY_0_STAT, item);
                        MapInfoPacket map = new MapInfoPacket(); map.name = "The Shatters";
                        for (int i = 0; i < 1105; i++) LootGUI.update(map, bag, null, player, i);
                        item.statValue = -1; map.name = "Changed after capture"; bag.objectType = 0;
                    }).get(10, TimeUnit.SECONDS);
                    assertArrayEquals(new int[]{1105, 1105}, view[0].getDashboard().sessionTotals());
                    assertEquals("Blocked EDT has not rendered capture work", 1, rows[0].getComponentCount());
                } catch (Exception e) { throw new AssertionError(e); }
            });
            // The renderer yields between bounded batches. Poll the actual completion condition.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            AtomicBoolean done = new AtomicBoolean();
            while (!done.get() && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait(() -> done.set(rows[0].getComponentCount() == LootGUI.LOG_LIMIT &&
                    Integer.valueOf(1105).equals(((JPanel)rows[0].getComponent(0)).getClientProperty("dropNumber"))));
                if (!done.get()) Thread.sleep(10);
            }
            assertTrue("Retained rows finish rendering", done.get()); assertFalse(offEdtMutation.get());
            SwingUtilities.invokeAndWait(() -> {
                JPanel newest = (JPanel)rows[0].getComponent(0), oldest = (JPanel)rows[0].getComponent(999);
                assertEquals(106, oldest.getClientProperty("dropNumber"));
                Entity bag = (Entity)newest.getClientProperty("bagEntity");
                assertEquals(LootBags.WHITE.getId(), bag.objectType);
                assertEquals(999991, bag.stat.get(StatType.INVENTORY_0_STAT).statValue);
                assertTrue(hasTooltip(newest, "The Shatters"));
                assertTrue(newest.isVisible());
                LootGUI.filterWhiteBag = false; LootGUI.applyFilters(); assertFalse(newest.isVisible());
                LootGUI.filterWhiteBag = true; LootGUI.applyFilters(); assertTrue(newest.isVisible());
                Font font = ContentStyle.body().deriveFont(28f); LootGUI.editFont(font);
                JLabel count = (JLabel)newest.getComponent(1);
                assertEquals(font, count.getFont());
                assertTrue(newest.getMaximumSize().height >= count.getPreferredSize().height);
            });
        } finally {
            capture.shutdownNow();
            SwingUtilities.invokeAndWait(() -> { LootGUI.filterWhiteBag = whiteFilter; LootGUI.editFont(ContentStyle.body()); });
        }
    }

    private static boolean hasTooltip(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel && ((JLabel)child).getToolTipText() != null && ((JLabel)child).getToolTipText().contains(text)) return true;
            if (child instanceof Container && hasTooltip((Container)child, text)) return true;
        }
        return false;
    }
}
