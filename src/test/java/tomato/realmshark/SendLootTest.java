package tomato.realmshark;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.Test;
import packets.data.StatData;
import packets.data.WorldPosData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.Entity;
import tomato.version.Version;
import static org.junit.Assert.*;

public class SendLootTest {
    @Test public void absentPreferenceIsExplicitlyEnabledAndMatchesLiteralMenuOptOut() {
        assertTrue(SendLoot.defaultEnabled(null));
        assertTrue(SendLoot.defaultEnabled(""));
        assertTrue(SendLoot.defaultEnabled("false"));
        assertTrue(SendLoot.defaultEnabled("TRUE"));
        assertFalse(SendLoot.defaultEnabled("true"));
    }

    @Test public void goldenPayloadPreservesWireFieldsNumericMobAndLegacySlCounts() {
        Entity bag = bag(1287, 6);
        StatData unique = new StatData();
        unique.stringStatValue = String.join(",", encode(777, 888), encode(-1, -2, 777),
            encode(777, -2), "!!!", "AAIE_f_9__3__f8=", encode(777, 888, 999, 123).replace("=", ""));
        bag.stat.set(StatType.UNIQUE_DATA_STRING, unique);
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            JsonObject actual = SendLoot.compose(null, map("Test Dungeon"), bag, null, null, 0);
            JsonObject expected = JsonParser.parseString("{\"bag\":1287,\"pos\":\"1.250000,2.500000\","
                + "\"dung\":\"Test Dungeon\",\"mods\":[],\"mob\":-1,\"share\":-1,"
                + "\"items\":[{\"id\":1000,\"sl\":2},{\"id\":1001,\"sl\":1},{\"id\":1002,\"sl\":2},"
                + "{\"id\":1003,\"sl\":0},{\"id\":1004,\"sl\":0},{\"id\":1005,\"sl\":4}],"
                + "\"exalt\":-1,\"ld\":false,\"seas\":false,\"cruc\":0,\"lben\":0.0}").getAsJsonObject();
            expected.addProperty("ver", Version.VERSION);
            assertEquals(expected, actual);
            assertTrue(actual.getAsJsonPrimitive("mob").isNumber());
            // The result must not retain the incoming bag/stat/position objects.
            bag.pos.x = 99; bag.stat.get(StatType.INVENTORY_0_STAT).statValue = -1;
            unique.stringStatValue = "changed";
            assertEquals(expected, actual);
        } finally { Locale.setDefault(original); }
    }

    @Test public void malformedAndMissingEnchantFieldsKeepEveryRealInventorySlot() {
        Entity bag = bag(1287, 0);
        for (int slot : new int[]{0, 2, 7}) item(bag, slot, 1000 + slot);
        StatData unique = new StatData(); unique.stringStatValue = "!!!,,AAIEAQ==";
        bag.stat.set(StatType.UNIQUE_DATA_STRING, unique);
        JsonArray items = SendLoot.compose(null, null, bag, null, null, 0).getAsJsonArray("items");
        assertEquals(3, items.size());
        for (int i = 0; i < 3; i++) assertEquals(0, items.get(i).getAsJsonObject().get("sl").getAsInt());
        assertEquals(1007, items.get(2).getAsJsonObject().get("id").getAsInt());
    }

    @Test public void unavailableClassAssetsKeepTheBagWithUnknownExaltBonus() {
        Entity player = new Entity(null, 1, 0); player.objectType = Integer.MAX_VALUE;
        JsonObject payload = SendLoot.compose(null, map("The Shatters"), bag(1287, 1), null, player, 0);
        assertEquals(-1, payload.get("exalt").getAsInt());
        assertEquals(1000, payload.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsInt());
        assertFalse(payload.get("seas").getAsBoolean());
    }

    @Test public void eitherFullBagMergesInTickKeepingFirstMetadataAndDetachedItems() throws Exception {
        for (boolean fullFirst : new boolean[]{true, false}) {
            ControlledLootTransport transport = new ControlledLootTransport(false, false);
            SendLoot.Session session = session(transport);
            try {
                session.beginLootTick(50);
                Entity first = bag(1287, fullFirst ? 8 : 1);
                Entity second = bag(1288, fullFirst ? 1 : 8); second.pos.x = 500;
                MapInfoPacket map = map("Test Dungeon");
                JsonObject expected = SendLoot.compose(null, map, first, null, null, 0);
                expected.getAsJsonArray("items").addAll(SendLoot.compose(null, map, second, null, null, 0).getAsJsonArray("items"));
                session.sendLoot(null, map, first, null, null, 0);
                assertEquals(1, session.pendingBags());
                assertEquals(0, session.snapshot().queued);
                first.pos.x = 900; first.stat.get(StatType.INVENTORY_0_STAT).statValue = -1;
                session.sendLoot(null, map, second, null, null, 0);
                second.stat.get(StatType.INVENTORY_0_STAT).statValue = -1; map.name = "Mutated";
                LootDeliveryTest.await(() -> session.snapshot().sentToSocket == 1);
                assertEquals(0, session.pendingBags());
                assertEquals(expected, payload(transport, 0));
                assertEquals(9, payload(transport, 0).getAsJsonArray("items").size());
            } finally { stop(session, transport); }
        }
    }

    @Test public void nonFullBagsFlushInOrderAndEveryBeginTickFlushesEvenForSameSeed() throws Exception {
        ControlledLootTransport transport = new ControlledLootTransport(false, false);
        SendLoot.Session session = session(transport);
        try {
            session.beginLootTick(50);
            session.sendLoot(null, map("Test Dungeon"), bag(1287, 1), null, null, 0);
            session.sendLoot(null, map("Test Dungeon"), bag(1288, 1), null, null, 0);
            session.beginLootTick(50); // seed is a map seed, NOT a unique tick id
            session.sendLoot(null, map("Test Dungeon"), bag(1289, 8), null, null, 0);
            session.beginLootTick(51);
            LootDeliveryTest.await(() -> session.snapshot().sentToSocket == 3);
            assertEquals(1287, payload(transport, 0).get("bag").getAsInt());
            assertEquals(1288, payload(transport, 1).get("bag").getAsInt());
            assertEquals(1289, payload(transport, 2).get("bag").getAsInt());
            assertEquals(8, payload(transport, 2).getAsJsonArray("items").size());
        } finally { stop(session, transport); }
    }

    @Test public void specialDungeonsAndStringOverridesNeverMerge() throws Exception {
        ControlledLootTransport transport = new ControlledLootTransport(false, false);
        SendLoot.Session session = session(transport);
        try {
            for (String name : new String[]{"The Shatters", "Oryx's Sanctuary", "Moonlight Village"}) {
                session.sendLoot(null, map(name), bag(1287, 8), null, null, 0);
                assertEquals(0, session.pendingBags());
            }
            Entity mob = new Entity(null, 5, 0); mob.objectType = 20493; mob.lootMobIdOverride = "20493HM";
            session.sendLoot(null, map("Test Dungeon"), bag(1288, 8), mob, null, 0);
            LootDeliveryTest.await(() -> session.snapshot().sentToSocket == 4);
            assertEquals(0, session.pendingBags());
            assertEquals("20493HM", payload(transport, 3).get("mob").getAsString());
            assertTrue(payload(transport, 3).getAsJsonPrimitive("mob").isString());
            assertEquals(0, payload(transport, 3).get("share").getAsInt());
        } finally { stop(session, transport); }
    }

    @Test public void optOutClearsPendingAndBeginTickCannotResurrectIt() throws Exception {
        ControlledLootTransport transport = new ControlledLootTransport(false, false);
        SendLoot.Session session = session(transport);
        try {
            session.sendLoot(null, map("Test Dungeon"), bag(1287, 8), null, null, 0);
            assertEquals(1, session.pendingBags());
            session.setEnabled(false);
            assertEquals(1, session.snapshot().dropped);
            assertEquals(0, session.pendingBags());
            session.beginLootTick(2); session.flushPendingOverflow();
            session.sendLoot(null, map("The Shatters"), bag(1288, 1), null, null, 0);
            session.setEnabled(true); session.beginLootTick(3);
            assertEquals(0, session.snapshot().queued);
            assertTrue(transport.ioThreads.isEmpty());
            session.sendLoot(null, map("The Shatters"), bag(1289, 1), null, null, 0);
            LootDeliveryTest.await(() -> session.snapshot().sentToSocket == 1);
            assertEquals(1289, payload(transport, 0).get("bag").getAsInt());
        } finally { stop(session, transport); }
    }

    @Test public void pendingLockDoesNotCoverCompositionAndOldGenerationCannotPublishAfterOptOut() throws Exception {
        ControlledLootTransport transport = new ControlledLootTransport(false, false);
        SendLoot.Session session = session(transport);
        ExecutorService capture = Executors.newSingleThreadExecutor();
        CountDownLatch composing = new CountDownLatch(1), release = new CountDownLatch(1);
        Entity slowDropper = new Entity(null, 5, 0) {
            @Override public int playersRemainAtKill() {
                composing.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { throw new AssertionError(ex); }
                return 3;
            }
        };
        try {
            Future<?> submission = capture.submit(() -> session.sendLoot(null, map("Test Dungeon"), bag(1287, 8), slowDropper, null, 0));
            assertTrue(composing.await(2, TimeUnit.SECONDS));
            CountDownLatch heartbeat = new CountDownLatch(1);
            SwingUtilities.invokeLater(() -> {
                session.setEnabled(false);
                session.beginLootTick(2);
                session.setEnabled(true);
                assertEquals(0, session.pendingBags());
                heartbeat.countDown();
            });
            assertTrue("Composition must not hold the pending-state monitor", heartbeat.await(2, TimeUnit.SECONDS));
            release.countDown(); submission.get(2, TimeUnit.SECONDS);
            assertEquals(1, session.snapshot().dropped);
            assertEquals(0, session.snapshot().queued);
            assertEquals(0, session.pendingBags());
            assertTrue(transport.ioThreads.isEmpty());
        } finally { release.countDown(); capture.shutdownNow(); stop(session, transport); }
    }

    @Test public void malformedBagCompositionIsCountedAndDoesNotPreventLaterBags() throws Exception {
        ControlledLootTransport transport = new ControlledLootTransport(false, false);
        SendLoot.Session session = session(transport);
        try {
            session.sendLoot(null, map("The Shatters"), new Entity(null, 1, 0), null, null, 0); // Missing position.
            assertEquals(1, session.snapshot().dropped);
            assertTrue(session.snapshot().lastError.contains("composition failed"));
            assertTrue(transport.ioThreads.isEmpty());
            session.sendLoot(null, map("The Shatters"), bag(1287, 1), null, null, 0);
            LootDeliveryTest.await(() -> session.snapshot().sentToSocket == 1);
            assertEquals(1, transport.payloads.size());
        } finally { stop(session, transport); }
    }

    private static SendLoot.Session session(ControlledLootTransport transport) {
        return new SendLoot.Session(new LootDelivery(() -> transport, 8, true, false));
    }
    private static void stop(SendLoot.Session session, ControlledLootTransport transport) throws Exception {
        transport.release(); session.close(); assertTrue(session.awaitStopped(2000));
    }
    private static JsonObject payload(ControlledLootTransport transport, int index) {
        return JsonParser.parseString(new String(transport.payloads.get(index), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private static MapInfoPacket map(String name) { MapInfoPacket map = new MapInfoPacket(); map.name = name; return map; }
    private static Entity bag(int id, int count) {
        Entity bag = new Entity(null, id, 0); bag.objectType = id;
        bag.pos = new WorldPosData(); bag.pos.x = 1.25f; bag.pos.y = 2.5f;
        for (int slot = 0; slot < count; slot++) item(bag, slot, 1000 + slot);
        return bag;
    }
    private static void item(Entity bag, int slot, int id) {
        StatData stat = new StatData(); stat.statValue = id;
        bag.stat.set(StatType.byOrdinal(StatType.INVENTORY_0_STAT.get() + slot), stat);
    }
    private static String encode(int... ids) {
        ByteBuffer buffer = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte)0).putShort((short)1026);
        for (int i = 0; i < 4; i++) buffer.putShort((short)(i < ids.length ? ids[i] : -3));
        return Base64.getUrlEncoder().encodeToString(buffer.array());
    }
}
