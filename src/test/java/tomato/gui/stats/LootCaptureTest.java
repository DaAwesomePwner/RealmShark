package tomato.gui.stats;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.swing.JFrame;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import packets.data.StatData;
import packets.data.WorldPosData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.realmshark.LootDelivery;
import tomato.realmshark.SendLoot;
import tomato.realmshark.Sound;
import tomato.realmshark.enums.LootBags;
import static org.junit.Assert.*;

/** P6a: loot capture with no loot page built. {@link LootCapture} is the one live loot pipeline. */
public class LootCaptureTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Field storeField;
    private Object previousStore;
    private SessionStore store;
    private TomatoData data;
    private SendLoot.Session sharing;
    private ScheduledThreadPoolExecutor flames;
    private LootCapture capture;
    private ExecutorService producer;
    private final List<Sound> bagSounds = new CopyOnWriteArrayList<>();
    private final List<Long> itemAlerts = new CopyOnWriteArrayList<>();

    @Before public void isolate() throws Exception {
        storeField = AppHistory.class.getDeclaredField("store"); storeField.setAccessible(true);
        previousStore = storeField.get(null);
        store = new SessionStore(temp.newFolder("history").toPath(), true, "synthetic");
        storeField.set(null, store);
        data = new TomatoData(); data.setPropList("itemPings", new ArrayList<>());
        sharing = new SendLoot.Session(new LootDelivery(() -> { throw new AssertionError("Capture tests must not connect"); }, 2, true, false));
        flames = new ScheduledThreadPoolExecutor(1);
        // A long injected delay keeps the reset queued so each assertion is deterministic; tests run the queued reset themselves.
        capture = new LootCapture(data, sharing, new LootCapture.Sounds() {
            @Override public void bag(Sound sound) { bagSounds.add(sound); }
            @Override public void item(long decision) { itemAlerts.add(decision); }
        }, flames, 60_000);
        capture.lootSharing(true);   // opted out unless a test opts in
        producer = Executors.newSingleThreadExecutor();
    }

    @After public void restore() throws Exception {
        producer.shutdownNow(); flames.shutdownNow();
        sharing.close();
        storeField.set(null, previousStore);
        store.close();
    }

    /** (a) and (b): saved once however many dashboards attach; the capture thread never waits for a blocked EDT. */
    @Test public void oneBagIsSavedOnceAndReachesEveryAttachedDashboardWithoutWaitingForSwing() throws Exception {
        LootDashboard[] views = new LootDashboard[2]; JFrame[] frame = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            views[0] = new LootDashboard(capture.feed()); views[1] = new LootDashboard(capture.feed());
            frame[0] = new JFrame("Loot capture"); frame[0].setContentPane(views[0]); frame[0].setSize(900, 600); frame[0].setVisible(true);
        });
        try {
            capture.updateExaltStats();
            CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
            SwingUtilities.invokeLater(() -> {
                blocked.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            try {
                assertTrue(blocked.await(5, TimeUnit.SECONDS));
                produce(() -> capture.update(map("The Shatters"), bag(LootBags.WHITE.getId(), 999991), null, player(), 1000L));
                assertEquals("Recorded while the EDT is blocked", 1, capture.feed().revision());
            } finally { release.countDown(); }
            SwingUtilities.invokeAndWait(() -> { });
            SwingUtilities.invokeAndWait(() -> {
                for (LootDashboard view : views) assertArrayEquals(new int[]{1, 1}, view.sessionTotals());
                JTable table = StatisticsExplorerTest.named(views[0], "loot-view-0", JTable.class);
                assertEquals("The showing dashboard refreshed on the EDT", 1, table.getRowCount());
            });
            store.flush();
            List<LootDashboard.Drop> saved = store.read(store.currentId(), "loot", LootDashboard.Drop.class);
            assertEquals("One append per drop, however many dashboards are attached", 1, saved.size());
            assertEquals("White", saved.get(0).bag);
            assertEquals(999991, saved.get(0).items.get(0).id);
        } finally {
            SwingUtilities.invokeAndWait(() -> frame[0].dispose());
        }
    }

    /** (c): the bag-sound decision follows the bag kind, boosted variants included; bags without a sound play none. */
    @Test public void bagSoundDecisionFollowsTheBagKind() throws Exception {
        capture.updateExaltStats();
        produce(() -> {
            for (LootBags kind : new LootBags[]{LootBags.WHITE, LootBags.BOOSTED_BLUE, LootBags.BROWN, LootBags.BOOSTED_EGG,
                    LootBags.ORANGE, LootBags.BOOSTED_RED, LootBags.GOLD, LootBags.SOULBOUND, LootBags.PINK})
                capture.update(map("Sprite World"), bag(kind.getId()), null, player(), 1000L);
        });
        assertEquals(Arrays.asList(Sound.whitebag, Sound.bluebag, Sound.eggbag, Sound.orangebag, Sound.redbag, Sound.goldbag), bagSounds);
    }

    /** (d): legacy loot sharing keeps its opt-out semantics exactly (no composition while opted out; opting out cancels unsent). */
    @Test public void legacySharingRespectsTheOptOut() throws Exception {
        capture.updateExaltStats();
        assertSame(sharing, capture.sharing());
        assertFalse(sharing.isEnabled());
        produce(() -> capture.update(map("Sprite World"), bag(LootBags.BROWN.getId(), 999991), null, player(), 1000L));
        assertEquals("Opted out: nothing is composed", 0, sharing.pendingBags());
        assertEquals(0, sharing.snapshot().queued);
        capture.lootSharing(false);
        assertTrue(sharing.isEnabled());
        produce(() -> capture.update(map("Sprite World"), bag(LootBags.BROWN.getId(), 999991), null, player(), 2000L));
        assertEquals("Opted in: the bag waits to merge within its loot tick", 1, sharing.pendingBags());
        capture.lootSharing(true);
        assertFalse(sharing.isEnabled());
        assertEquals("Opting out cancels the unsent bag", 0, sharing.pendingBags());
        assertEquals("Nothing reached the transport", 0, sharing.snapshot().queued);
    }

    /** (e): each Moonlight Village drop schedules one flame reset after the injected delay (production: 5 s). */
    @Test public void moonlightFlamesResetOnceAfterTheDelayForEachDrop() throws Exception {
        assertEquals("Each LootEntry's timer waited 5 s", 5000, LootCapture.FLAME_RESET_MILLIS);
        setFlames(3);
        capture.updateExaltStats();
        produce(() -> capture.update(map("Sprite World"), bag(LootBags.BROWN.getId()), null, player(), 1000L));
        assertEquals("Only Moonlight Village drops schedule a reset", 0, flames.getQueue().size());
        produce(() -> {
            capture.update(map("Moonlight Village"), bag(LootBags.BROWN.getId()), null, player(), 2000L);
            capture.update(map("Moonlight Village"), bag(LootBags.BROWN.getId()), null, player(), 2001L);
        });
        assertEquals("One reset per drop", 2, flames.getQueue().size());
        assertEquals("Nothing resets before the delay", 3, data.getMoonlightFlameCount());
        for (Runnable queued : flames.getQueue()) {
            long delay = ((RunnableScheduledFuture<?>)queued).getDelay(TimeUnit.MILLISECONDS);
            assertTrue("Scheduled after the injected delay: " + delay, delay > 50_000 && delay <= 60_000);
        }
        ((Runnable)flames.getQueue().peek()).run();   // poll() would only return an expired task
        assertEquals("The reset clears the counter", 0, data.getMoonlightFlameCount());
    }

    /** A short delay end to end: the reset runs on the capture's own daemon scheduler, never on the EDT. */
    @Test public void flameResetRunsOffTheEdtAfterAShortDelay() throws Exception {
        ScheduledThreadPoolExecutor quick = new ScheduledThreadPoolExecutor(1);
        LootCapture fast = new LootCapture(data, sharing, new LootCapture.Sounds() {
            @Override public void bag(Sound sound) { }
            @Override public void item(long decision) { }
        }, quick, 50);
        try {
            setFlames(4);
            fast.updateExaltStats();
            produce(() -> fast.update(map("Moonlight Village"), bag(LootBags.BROWN.getId()), null, player(), 1000L));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (data.getMoonlightFlameCount() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(0, data.getMoonlightFlameCount());
        } finally { quick.shutdownNow(); }
    }

    /** (f): the update gate: nothing is recorded, played, pinged, shared or scheduled before updateExaltStats(), or without a player. */
    @Test public void nothingHappensBeforeExaltStatsOpenTheGate() throws Exception {
        setFlames(2);
        data.setPropList("itemPings", new ArrayList<>(Collections.singletonList("999991")));
        capture.lootSharing(false);
        produce(() -> capture.update(map("Sprite World"), bag(LootBags.WHITE.getId(), 999991), null, player(), 1000L));
        produce(() -> capture.update(map("Moonlight Village"), bag(LootBags.WHITE.getId(), 999991), null, player(), 1001L));
        assertEquals(0, capture.feed().revision());
        assertTrue(bagSounds.isEmpty()); assertTrue(itemAlerts.isEmpty());
        assertEquals(0, sharing.pendingBags()); assertEquals(0, flames.getQueue().size());
        store.flush();
        assertTrue(store.read(store.currentId(), "loot", LootDashboard.Drop.class).isEmpty());

        capture.updateExaltStats();
        produce(() -> capture.update(map("Sprite World"), bag(LootBags.WHITE.getId(), 999991), null, null, 1002L));
        assertEquals("No player: still ignored", 0, capture.feed().revision());
        // A mergeable bag waits in the session (Moonlight Village bags are never merged and would go to the transport).
        produce(() -> capture.update(map("Sprite World"), bag(LootBags.WHITE.getId(), 999991), null, player(), 1003L));
        assertEquals(1, capture.feed().revision());
        assertEquals(Collections.singletonList(Sound.whitebag), bagSounds);
        assertEquals("The matching item pinged once", 1, itemAlerts.size());
        assertEquals("Shared (opted in)", 1, sharing.pendingBags());
        capture.lootSharing(true);
        produce(() -> capture.update(map("Moonlight Village"), bag(LootBags.BROWN.getId()), null, player(), 1004L));
        assertEquals(2, capture.feed().revision());
        assertEquals("The Moonlight drop schedules its flame reset", 1, flames.getQueue().size());
        store.flush();
        assertEquals(2, store.read(store.currentId(), "loot", LootDashboard.Drop.class).size());
    }

    /**
     * TomatoData and File › Opt-out Loot Sharing call the app's capture directly (P6a Task 12: the Statistics Live log and its static
     * API are gone), with no loot page built.
     */
    @Test public void theAppsCaptureIsCalledDirectlyWithNoLootPageBuilt() throws Exception {
        LootCapture previous = LootCapture.install(capture);
        try {
            assertSame(capture, LootCapture.get());
            LootCapture.get().update(map("Sprite World"), bag(LootBags.BROWN.getId(), 999991), null, player(), 1000L);
            assertEquals("Gate closed: ignored, and no exception", 0, capture.feed().revision());
            LootCapture.get().updateExaltStats();
            LootCapture.get().update(map("Sprite World"), bag(LootBags.BROWN.getId(), 999991), null, player(), 2000L);
            assertEquals(1, capture.feed().revision());
            LootCapture.get().lootSharing(false); assertTrue(sharing.isEnabled());
            LootCapture.get().lootSharing(true); assertFalse(sharing.isEnabled());
        } finally {
            LootCapture.install(previous);
        }
    }

    /** Feed.revision() counts received bags; snapshot() is a detached, capped copy projected like saved loot. */
    @Test public void feedRevisionCountsBagsAndSnapshotsAreDetachedAndCapped() throws Exception {
        LootDashboard.Feed feed = new LootDashboard.Feed();
        assertEquals(0, feed.revision()); assertTrue(feed.snapshot("live").isEmpty()); assertFalse(feed.capped());
        feed.accept(drop(1000, "White"));
        feed.acceptAll(Arrays.asList(drop(3000, "Blue"), drop(2000, "B.Brown")));
        assertEquals("Bumped per received bag", 3, feed.revision());
        List<LootFacts.Bag> first = feed.snapshot("live");
        assertEquals(3, first.size());
        assertEquals("Oldest first, as a saved session reads", Arrays.asList(1000L, 2000L, 3000L),
            Arrays.asList(first.get(0).time(), first.get(1).time(), first.get(2).time()));
        assertEquals("live", first.get(0).session());
        assertTrue(first.get(0).white()); assertEquals("B.Brown", first.get(1).bag());
        assertEquals(999990, first.get(0).items().get(0).id());
        feed.accept(drop(4000, "Red"));
        assertEquals("A snapshot is a detached copy", 3, first.size());
        try { first.clear(); fail("Snapshots are read-only"); } catch (UnsupportedOperationException expected) { }
        List<LootDashboard.Drop> many = new ArrayList<>();
        for (int i = 0; i < LootDashboard.RECENT_LIMIT + 1; i++) many.add(drop(10_000 + i, "Brown"));
        feed.acceptAll(many);
        assertEquals(4 + LootDashboard.RECENT_LIMIT + 1, feed.revision());
        assertTrue("More bags than the live list keeps", feed.capped());
        List<LootFacts.Bag> capped = feed.snapshot("live");
        assertEquals(LootDashboard.RECENT_LIMIT, capped.size());
        assertEquals("The latest bags are kept", 10_000L + LootDashboard.RECENT_LIMIT, capped.get(capped.size() - 1).time());
    }

    private void produce(Runnable work) throws Exception { producer.submit(work).get(10, TimeUnit.SECONDS); }

    private void setFlames(int count) throws Exception {
        Field field = TomatoData.class.getDeclaredField("moonlightFlames"); field.setAccessible(true); field.setInt(data, count);
    }

    private static LootDashboard.Drop drop(long time, String bag) {
        return new LootDashboard.Drop(bag, "Sprite World", "Limon", time,
            Collections.singletonList(new LootDashboard.Item(999990, "Synthetic item", false)));
    }

    private static MapInfoPacket map(String name) { MapInfoPacket map = new MapInfoPacket(); map.name = name; return map; }

    private static Entity player() { Entity player = new Entity(null, 1, 0); player.objectType = 768; return player; }

    private static Entity bag(int objectType, int... items) {
        Entity bag = new Entity(null, 2, 0); bag.objectType = objectType; bag.pos = new WorldPosData();
        for (int slot = 0; slot < items.length; slot++) {
            StatData stat = new StatData(); stat.statValue = items[slot];
            bag.stat.set(StatType.byOrdinal(StatType.INVENTORY_0_STAT.get() + slot), stat);
        }
        return bag;
    }
}
