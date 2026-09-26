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
import tomato.realmshark.ControlledLootTransport;
import tomato.realmshark.LootDelivery;
import tomato.realmshark.SendLoot;
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
        SendLoot.Session sharing = new SendLoot.Session(new LootDelivery(
            () -> { throw new AssertionError("Opted-out log must not connect"); }, 2, false, false));
        boolean whiteFilter = LootGUI.filterWhiteBag;
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    view[0] = new LootGUI(new TomatoData(), sharing);
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
            sharing.close();
            SwingUtilities.invokeAndWait(() -> { LootGUI.filterWhiteBag = whiteFilter; LootGUI.editFont(ContentStyle.body()); });
        }
    }

    @Test public void matchingBagWithItsSoundTurnedOffIsRecordedAsAlertOff() throws Exception {
        SendLoot.Session sharing = new SendLoot.Session(new LootDelivery(
            () -> { throw new AssertionError("Opted-out log must not connect"); }, 2, false, false));
        boolean whiteEnabled = tomato.realmshark.Sound.whitebag.isEnabled();
        ExecutorService capture = Executors.newSingleThreadExecutor();
        try {
            SwingUtilities.invokeAndWait(() -> { new LootGUI(new TomatoData(), sharing); LootGUI.lootSharing(true); });
            tomato.realmshark.Sound.whitebag.setEnabled(false);
            tomato.realmshark.AlertDecisions.INSTANCE.clear();
            capture.submit(() -> {
                LootGUI.updateExaltStats(); // enables the producer, as capture does once exalt stats arrive
                Entity player = new Entity(null, 1, 0); player.objectType = 768;
                Entity bag = new Entity(null, 2, 0); bag.objectType = LootBags.WHITE.getId();
                MapInfoPacket map = new MapInfoPacket(); map.name = "The Shatters";
                LootGUI.update(map, bag, null, player, 1);
            }).get(10, TimeUnit.SECONDS);
            boolean recorded = false;
            for (tomato.realmshark.AlertDecisions.Decision d : tomato.realmshark.AlertDecisions.INSTANCE.snapshot(true))
                recorded |= d.result == tomato.realmshark.AlertDecisions.Result.SOUND_OFF && tomato.realmshark.Sound.whitebag.label.equals(d.soundLabel);
            assertTrue("A matching bag whose sound is off still records its decision", recorded);
        } finally {
            capture.shutdownNow(); sharing.close(); tomato.realmshark.Sound.whitebag.setEnabled(whiteEnabled);
        }
    }

    @Test public void blockedConnectionDoesNotBlockCaptureEdtOrOptOutStatus() throws Exception {
        ControlledLootTransport transport = new ControlledLootTransport(true, false);
        transport.ignoreConnectInterrupt = true;
        SendLoot.Session sharing = new SendLoot.Session(new LootDelivery(() -> transport, 2, true, false));
        ExecutorService capture = Executors.newSingleThreadExecutor();
        LootGUI[] view = new LootGUI[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                TomatoData data = new TomatoData();
                data.setPropList("itemPings", new java.util.ArrayList<>());
                view[0] = new LootGUI(data, sharing);
            });
            Entity bag = new Entity(null, 2, 0); bag.objectType = LootBags.BROWN.getId();
            bag.pos = new packets.data.WorldPosData();
            StatData item = new StatData(); item.statValue = 999991; bag.stat.set(StatType.INVENTORY_0_STAT, item);
            Entity player = new Entity(null, 1, 0); player.objectType = 768;
            MapInfoPacket map = new MapInfoPacket(); map.name = "The Shatters";
            capture.submit(() -> { LootGUI.updateExaltStats(); LootGUI.update(map, bag, null, player, 1); }).get(2, TimeUnit.SECONDS);
            assertEquals("Composition rejected the fixture: " + sharing.snapshot().lastError, 1, sharing.snapshot().queued);
            assertTrue(transport.connectEntered.await(2, TimeUnit.SECONDS));
            // Capture can publish another bag while the sole sender is still in connect.
            capture.submit(() -> LootGUI.update(map, bag, null, player, 2)).get(2, TimeUnit.SECONDS);
            CountDownLatch heartbeat = new CountDownLatch(1);
            SwingUtilities.invokeLater(() -> {
                LootGUI.lootSharing(true);
                view[0].refreshDeliveryStatus();
                heartbeat.countDown();
            });
            assertTrue("EDT does not acquire an I/O-held lock", heartbeat.await(2, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                JTextArea status = findStatus(view[0]);
                assertNotNull(status);
                assertTrue(status.isVisible());
                assertTrue(status.getText().contains("Opted out"));
                assertTrue(status.getText().contains("Dropped: 2"));
                assertTrue(status.getText().contains("Socket: 0 (unconfirmed)"));
                assertTrue(status.getAccessibleContext().getAccessibleDescription().contains("not application confirmation"));
                assertArrayEquals(new int[]{2, 2}, view[0].getDashboard().sessionTotals());
            });
            transport.release();
            assertTrue(transport.closed.await(2, TimeUnit.SECONDS));
            assertTrue(transport.payloads.isEmpty());
            assertFalse(transport.ioOnEdt);
        } finally {
            transport.release(); sharing.close(); capture.shutdownNow();
            assertTrue(sharing.awaitStopped(2000));
        }
    }

    @Test public void previewKeepsLocalAlertsButDoesNotInvokeSharingCallback() throws Exception {
        SendLoot.Session sharing = new SendLoot.Session(new LootDelivery(
            () -> { throw new AssertionError("Preview must not connect"); }, 2, true, true));
        try {
            SwingUtilities.invokeAndWait(() -> {
                TomatoData data = new TomatoData();
                data.setPropList("itemPings", new java.util.ArrayList<>(java.util.Collections.singletonList("999991")));
                LootGUI view = new LootGUI(data, sharing);
                Entity bag = new Entity(null, 2, 0);
                StatData item = new StatData(); item.statValue = 999991; bag.stat.set(StatType.INVENTORY_0_STAT, item);
                AtomicBoolean alerted = new AtomicBoolean();
                LootGUI.lootSharing(false);
                view.notifyItems(bag, decision -> alerted.set(true), () -> fail("Preview cannot share"));
                view.refreshDeliveryStatus();
                assertTrue(alerted.get());
                assertTrue(findStatus(view).getAccessibleContext().getAccessibleDescription().contains("Preview — sending disabled"));
            });
        } finally { sharing.close(); }
    }

    @Test public void compactDeliverySummaryLeavesDataRowsVisibleEvenWithLongErrors() throws Exception {
        LootDelivery delivery = new LootDelivery(() -> { throw new AssertionError("Layout test must not connect"); }, 2, false, false);
        SendLoot.Session sharing = new SendLoot.Session(delivery);
        LootGUI[] view = new LootGUI[1]; JFrame[] frame = new JFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                view[0] = new LootGUI(new TomatoData(), sharing);
                frame[0] = new JFrame("Loot layout regression");
                // Match StatisticsGUI: short windows scroll the page rather than shrink data rows.
                frame[0].setContentPane(StatsUi.page(view[0], 570));
                frame[0].setSize(680, 510); frame[0].setVisible(true); frame[0].validate();
                view[0].getDashboard().accept(new LootDashboard.Drop("White", "Test dungeon", "Boss", 1,
                    java.util.Collections.singletonList(new LootDashboard.Item(999991, "Test item", false))));
            });
            SwingUtilities.invokeAndWait(() -> {
                JTextArea status = findStatus(view[0]);
                int originalStatusHeight = status.getParent().getHeight();
                char[] message = new char[2000]; java.util.Arrays.fill(message, 'x');
                delivery.recordDrop(new String(message));
                view[0].refreshDeliveryStatus();
                frame[0].validate();
                assertEquals("Errors do not expand the summary", originalStatusHeight, status.getParent().getHeight());
                assertTrue(status.getText().contains("Dropped: 1"));
                assertTrue(status.getText().contains("Error: See Details"));
                assertTrue(status.getAccessibleContext().getAccessibleDescription().contains(new String(message)));
                JTable table = StatisticsExplorerTest.named(view[0], "loot-view-0", JTable.class);
                assertNotNull(table); assertTrue(table.getRowCount() > 0);
                assertTrue("Inspect the selected, showing loot table", table.isShowing());
                assertTrue("Compact loot rows remain usable: viewport=" + table.getParent().getSize(),
                    table.getParent().getHeight() >= 60);
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (frame[0] != null) frame[0].dispose(); });
            sharing.close();
        }
    }

    private static JTextArea findStatus(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextArea && "Legacy loot delivery status".equals(
                    ((JTextArea)child).getAccessibleContext().getAccessibleName())) return (JTextArea)child;
            if (child instanceof Container) {
                JTextArea found = findStatus((Container)child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasTooltip(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel && ((JLabel)child).getToolTipText() != null && ((JLabel)child).getToolTipText().contains(text)) return true;
            if (child instanceof Container && hasTooltip((Container)child, text)) return true;
        }
        return false;
    }
}
