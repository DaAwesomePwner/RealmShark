package tomato.gui.stats;

import java.awt.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.*;
import tomato.realmshark.ControlledLootTransport;
import tomato.realmshark.LootDelivery;
import tomato.realmshark.SendLoot;
import tomato.realmshark.enums.LootBags;
import static org.junit.Assert.*;

/**
 * Loot capture off the EDT (P6a: {@link LootCapture}) and the legacy sharing status (Loot › ⋯ › Loot sharing status…). The Statistics
 * Live log's rendering, fonts, item tooltips and status bar went with the page.
 */
public class LootLogTest {
    /**
     * The capture thread never waits for a blocked EDT, and what it records is detached from the live entities it read: changing
     * the bag, item or map afterwards changes no recorded drop. (The Live log that rendered these drops went with Statistics.)
     */
    @Test public void producerDoesNotWaitForSwingAndRecordedDropsAreDetached() throws Exception {
        LootDashboard[] view = new LootDashboard[1];
        ExecutorService capture = Executors.newSingleThreadExecutor();
        SendLoot.Session sharing = new SendLoot.Session(new LootDelivery(
            () -> { throw new AssertionError("Opted-out capture must not connect"); }, 2, false, false));
        LootCapture loot = new LootCapture(new TomatoData(), sharing);
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    view[0] = new LootDashboard(loot.feed());
                    loot.lootSharing(true);
                    capture.submit(() -> {
                        loot.updateExaltStats();
                        Entity player = new Entity(null, 1, 0); player.objectType = 768;
                        Entity bag = new Entity(null, 2, 0); bag.objectType = LootBags.WHITE.getId();
                        StatData item = new StatData(); item.statValue = 999991;
                        bag.stat.set(StatType.INVENTORY_0_STAT, item);
                        MapInfoPacket map = new MapInfoPacket(); map.name = "The Shatters";
                        for (int i = 0; i < 1105; i++) loot.update(map, bag, null, player, i);
                        item.statValue = -1; map.name = "Changed after capture"; bag.objectType = 0;
                    }).get(10, TimeUnit.SECONDS);
                    assertEquals("Recorded while the EDT is blocked", 1105, loot.feed().revision());
                    assertArrayEquals(new int[]{1105, 1105}, view[0].sessionTotals());
                } catch (Exception e) { throw new AssertionError(e); }
            });
            java.util.List<LootFacts.Bag> bags = loot.feed().snapshot("live");
            assertEquals("The snapshot keeps the latest 1,000 bags", 1000, bags.size());
            for (LootFacts.Bag bag : bags) {
                assertEquals("White", bag.bag()); assertTrue(bag.white());
                assertEquals("The Shatters", bag.dungeon());
                assertEquals(1, bag.items().size()); assertEquals(999991, bag.items().get(0).id());
            }
        } finally {
            capture.shutdownNow();
            sharing.close();
        }
    }

    @Test public void matchingBagWithItsSoundTurnedOffIsRecordedAsAlertOff() throws Exception {
        SendLoot.Session sharing = new SendLoot.Session(new LootDelivery(
            () -> { throw new AssertionError("Opted-out log must not connect"); }, 2, false, false));
        boolean whiteEnabled = tomato.realmshark.Sound.whitebag.isEnabled();
        ExecutorService capture = Executors.newSingleThreadExecutor();
        try {
            // Bag sounds play from the non-UI capture with device sounds (P6a); no loot page is built.
            LootCapture loot = new LootCapture(new TomatoData(), sharing); loot.lootSharing(true);
            tomato.realmshark.Sound.whitebag.setEnabled(false);
            tomato.realmshark.AlertDecisions.INSTANCE.clear();
            capture.submit(() -> {
                loot.updateExaltStats(); // enables the producer, as capture does once exalt stats arrive
                Entity player = new Entity(null, 1, 0); player.objectType = 768;
                Entity bag = new Entity(null, 2, 0); bag.objectType = LootBags.WHITE.getId();
                MapInfoPacket map = new MapInfoPacket(); map.name = "The Shatters";
                loot.update(map, bag, null, player, 1);
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
        LootDashboard[] view = new LootDashboard[1];
        tomato.gui.loot.LootSharingStatus[] status = new tomato.gui.loot.LootSharingStatus[1];
        TomatoData data = new TomatoData();
        data.setPropList("itemPings", new java.util.ArrayList<>());
        LootCapture loot = new LootCapture(data, sharing);
        try {
            SwingUtilities.invokeAndWait(() -> view[0] = new LootDashboard(loot.feed()));
            Entity bag = new Entity(null, 2, 0); bag.objectType = LootBags.BROWN.getId();
            bag.pos = new packets.data.WorldPosData();
            StatData item = new StatData(); item.statValue = 999991; bag.stat.set(StatType.INVENTORY_0_STAT, item);
            Entity player = new Entity(null, 1, 0); player.objectType = 768;
            MapInfoPacket map = new MapInfoPacket(); map.name = "The Shatters";
            capture.submit(() -> { loot.updateExaltStats(); loot.update(map, bag, null, player, 1); }).get(2, TimeUnit.SECONDS);
            assertEquals("Composition rejected the fixture: " + sharing.snapshot().lastError, 1, sharing.snapshot().queued);
            assertTrue(transport.connectEntered.await(2, TimeUnit.SECONDS));
            // Capture can publish another bag while the sole sender is still in connect.
            capture.submit(() -> loot.update(map, bag, null, player, 2)).get(2, TimeUnit.SECONDS);
            CountDownLatch heartbeat = new CountDownLatch(1);
            SwingUtilities.invokeLater(() -> {
                loot.lootSharing(true);
                status[0] = new tomato.gui.loot.LootSharingStatus(sharing); // Loot › ⋯ › Loot sharing status… reads the session once
                heartbeat.countDown();
            });
            assertTrue("EDT does not acquire an I/O-held lock", heartbeat.await(2, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                JTextArea summary = findStatus(status[0]);
                assertNotNull(summary);
                assertTrue(summary.isVisible());
                assertTrue(summary.getText().contains("Opted out"));
                assertTrue(summary.getText().contains("Dropped: 2"));
                assertTrue(summary.getText().contains("Socket: 0 (unconfirmed)"));
                assertTrue(summary.getAccessibleContext().getAccessibleDescription().contains("not application confirmation"));
                assertArrayEquals(new int[]{2, 2}, view[0].sessionTotals());
                status[0].close();
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
                LootCapture loot = new LootCapture(data, sharing);
                Entity bag = new Entity(null, 2, 0);
                StatData item = new StatData(); item.statValue = 999991; bag.stat.set(StatType.INVENTORY_0_STAT, item);
                AtomicBoolean alerted = new AtomicBoolean();
                loot.lootSharing(false);
                loot.notifyItems(bag, decision -> alerted.set(true), () -> fail("Preview cannot share"));
                tomato.gui.loot.LootSharingStatus view = new tomato.gui.loot.LootSharingStatus(sharing);
                assertTrue(alerted.get());
                assertTrue(findStatus(view).getAccessibleContext().getAccessibleDescription().contains("Preview — sending disabled"));
                view.close();
            });
        } finally { sharing.close(); }
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
}
