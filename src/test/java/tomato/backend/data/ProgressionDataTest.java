package tomato.backend.data;

import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import packets.data.*;
import packets.data.enums.StatType;
import static org.junit.Assert.*;

public class ProgressionDataTest {
    @Test public void oldAccountAndReconnectCannotReplaceLatestListAndArraysAreDetached() {
        ProgressionData data = new ProgressionData(); data.reset("account-A", "identified");
        ProgressionData.Scope a = data.scope();
        QuestData quest = quest("A");
        assertTrue(data.quests(a, new QuestData[]{quest}, 100));
        quest.requirements[0] = 99;
        assertEquals(42, data.snapshot().quests.rows()[0].requirements[0]);
        data.snapshot().quests.rows()[0].requirements[0] = 88;
        data.reset("account-B", "account switched");
        assertFalse(data.snapshot().currentQuests()); assertSame(a, data.snapshot().quests.scope);
        assertFalse(data.quests(a, new QuestData[]{quest("late-A")}, 200));
        assertTrue(data.quests(data.scope(), new QuestData[]{quest("B")}, 300));
        ProgressionData.Scope b = data.scope(); data.reset("account-B", "reconnected");
        assertFalse(data.quests(b, new QuestData[]{quest}, 400)); assertFalse(data.snapshot().currentQuests());
        assertEquals("B", data.snapshot().quests.rows()[0].id);
    }
    @Test public void stopInvalidatesQueuedPublicationAcrossThreadsAndStartRequiresFreshIdentity() throws Exception {
        ProgressionData data = new ProgressionData(); data.reset("A", "identified");
        ProgressionData.Scope old = data.scope();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            Future<Boolean> late = worker.submit(() -> { started.countDown(); release.await(); return data.quests(old, new QuestData[]{quest("A")}, 1); });
            assertTrue(started.await(2, TimeUnit.SECONDS)); data.captureStopped(); release.countDown();
            assertFalse(late.get(2, TimeUnit.SECONDS));
            data.reset("A", "late player callback"); assertFalse(data.scope().accepting);
            assertFalse(data.quests(data.scope(), new QuestData[0], 2));
            data.captureStarted(); assertNull(data.scope().account);
            assertTrue(data.quests(data.scope(), new QuestData[0], 3)); assertFalse(data.snapshot().currentQuests());
            data.reset("B", "identified"); assertFalse(data.snapshot().currentQuests());
            assertTrue(data.quests(data.scope(), new QuestData[0], 4)); assertTrue(data.snapshot().currentQuests());
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @Test public void unknownPetInstancesStaySeparateAndOnlyExplicitObjectPromotionMerges() {
        ProgressionData data = new ProgressionData(); data.reset("A", "identified");
        data.pet(data.scope(), 11, stats(stat(StatType.PET_FIRST_ABILITY_POINT_STAT, 0)), 10, "capture", Collections.emptyMap());
        data.pet(data.scope(), 12, stats(stat(StatType.PET_FIRST_ABILITY_POINT_STAT, 99)), 11, "capture", Collections.emptyMap());
        assertEquals(2, data.snapshot().pets.size());
        data.pet(data.scope(), 11, stats(stat(StatType.PET_INSTANCE_ID_STAT, 7)), 12, "capture", Collections.emptyMap());
        ProgressionData.Pet pet = data.snapshot().pets.stream().filter(p -> Integer.valueOf(7).equals(p.value(StatType.PET_INSTANCE_ID_STAT))).findFirst().get();
        assertEquals(Integer.valueOf(0), pet.value(StatType.PET_FIRST_ABILITY_POINT_STAT));
        data.pet(data.scope(), 11, stats(stat(StatType.PET_FIRST_ABILITY_POWER_STAT, 1)), 13, "capture", Collections.emptyMap());
        assertEquals("A NEWTICK without instance ID updates the explicitly associated object", 2, data.snapshot().pets.size());
        assertEquals(Integer.valueOf(1), data.snapshot().pets.stream().filter(p -> Integer.valueOf(7).equals(p.value(StatType.PET_INSTANCE_ID_STAT)))
            .findFirst().get().value(StatType.PET_FIRST_ABILITY_POWER_STAT));
        assertNull(pet.value(StatType.PET_MAX_ABILITY_POWER_STAT));
        pet.stats().get(StatType.PET_FIRST_ABILITY_POINT_STAT).statValue = 1000;
        assertEquals(Integer.valueOf(0), pet.value(StatType.PET_FIRST_ABILITY_POINT_STAT));
        data.reset("B", "switched"); assertTrue(data.snapshot().pets.isEmpty());
        data.pet(pet.scope, 11, pet.stats(), 20, "late", Collections.emptyMap()); assertTrue(data.snapshot().pets.isEmpty());
    }
    @Test public void pullMailboxAlwaysReadsNewestRevisionAfterCoalescedNotifications() {
        ProgressionData data = new ProgressionData(); data.reset("A", "identified");
        List<Runnable> queued = new ArrayList<>(); List<String> rendered = new ArrayList<>();
        data.addListener(() -> queued.add(() -> rendered.add(data.snapshot().quests.rows()[0].id)));
        data.quests(data.scope(), new QuestData[]{quest("first")}, 1);
        data.quests(data.scope(), new QuestData[]{quest("second")}, 2);
        Collections.reverse(queued); queued.forEach(Runnable::run);
        assertEquals(Arrays.asList("second", "second"), rendered);
    }
    @Test public void packetIngestionPublishesWithoutShellAndRedemptionDoesNotCompleteAnArbitraryRow() {
        TomatoData data = new TomatoData((token, endpoint) -> "<AccountPowerups/>");
        data.progression().reset("A", "identified");
        tomato.backend.TomatoPacketCapture capture = new tomato.backend.TomatoPacketCapture(data);
        packets.incoming.QuestFetchResponsePacket fetched = new packets.incoming.QuestFetchResponsePacket();
        fetched.quests = new QuestData[]{quest("turn-in")}; capture.packetCapture(fetched);
        assertTrue(data.progression().snapshot().currentQuests());
        packets.incoming.QuestRedeemResponsePacket redeemed = new packets.incoming.QuestRedeemResponsePacket(); redeemed.ok = true;
        capture.packetCapture(redeemed);
        assertFalse(data.progression().snapshot().quests.rows()[0].completed);
        assertEquals("turn-in", data.progression().snapshot().quests.rows()[0].id);
    }
    @Test public void delayedPetMetadataCannotRollBackNewerCapturedPoints() {
        ProgressionData data = new ProgressionData(); data.reset("A", "identified");
        data.pet(data.scope(), 11, stats(stat(StatType.PET_INSTANCE_ID_STAT, 7), stat(StatType.PET_FIRST_ABILITY_POINT_STAT, 100)), 200, "capture", Collections.emptyMap());
        data.pet(data.scope(), -1, stats(stat(StatType.PET_INSTANCE_ID_STAT, 7), stat(StatType.PET_FIRST_ABILITY_POINT_STAT, 0),
            stat(StatType.PET_MAX_ABILITY_POWER_STAT, 30)), 100, "metadata", Collections.emptyMap());
        ProgressionData.Pet result = data.snapshot().pets.get(0);
        assertEquals(Integer.valueOf(100), result.value(StatType.PET_FIRST_ABILITY_POINT_STAT));
        assertEquals(200, result.field(StatType.PET_FIRST_ABILITY_POINT_STAT).at);
        assertEquals(Integer.valueOf(30), result.value(StatType.PET_MAX_ABILITY_POWER_STAT));
        assertEquals(100, result.field(StatType.PET_MAX_ABILITY_POWER_STAT).at);
    }
    @Test public void promotionMergesAnonymousAndExistingInstanceAndIncomingByEachFieldTime() {
        ProgressionData data = new ProgressionData(); data.reset("A", "identified");
        Map<Integer, FieldCapture> anonymousFields = Collections.singletonMap(StatType.PET_FIRST_ABILITY_POWER_STAT.get(),
            new FieldCapture(80, "earlier yard level"));
        data.pet(data.scope(), 11, stats(stat(StatType.PET_FIRST_ABILITY_POINT_STAT, 200),
            stat(StatType.PET_FIRST_ABILITY_POWER_STAT, 25), stat(StatType.PET_SECOND_ABILITY_TYPE_STAT, 408)),
            200, "anonymous yard capture", anonymousFields);
        Map<Integer, FieldCapture> metadataFields = Collections.singletonMap(StatType.PET_FIRST_ABILITY_POWER_STAT.get(),
            new FieldCapture(250, "newer metadata level"));
        data.pet(data.scope(), -1, stats(stat(StatType.PET_INSTANCE_ID_STAT, 7), stat(StatType.PET_FIRST_ABILITY_POINT_STAT, 10),
            stat(StatType.PET_FIRST_ABILITY_POWER_STAT, 30), stat(StatType.PET_MAX_ABILITY_POWER_STAT, 70), stat(StatType.PET_RARITY_STAT, 2)),
            100, "character metadata", metadataFields);
        ProgressionData.Snapshot before = data.snapshot();
        assertEquals(2, before.pets.size());
        Map<Integer, FieldCapture> promotionFields = Collections.singletonMap(StatType.PET_FIRST_ABILITY_POINT_STAT.get(),
            new FieldCapture(150, "delayed promotion points"));
        data.pet(data.scope(), 11, stats(stat(StatType.PET_INSTANCE_ID_STAT, 7), stat(StatType.PET_FIRST_ABILITY_POINT_STAT, 5),
            stat(StatType.PET_MAX_ABILITY_POWER_STAT, 90)), 300, "promotion", promotionFields);
        assertEquals("The anonymous row is removed only after combining all its evidence", 1, data.snapshot().pets.size());
        ProgressionData.Pet promoted = data.snapshot().pets.get(0);
        assertPetField(promoted, StatType.PET_FIRST_ABILITY_POINT_STAT, 200, 200, "anonymous yard capture");
        assertPetField(promoted, StatType.PET_FIRST_ABILITY_POWER_STAT, 30, 250, "newer metadata level");
        assertPetField(promoted, StatType.PET_SECOND_ABILITY_TYPE_STAT, 408, 200, "anonymous yard capture");
        assertPetField(promoted, StatType.PET_RARITY_STAT, 2, 100, "character metadata");
        assertPetField(promoted, StatType.PET_MAX_ABILITY_POWER_STAT, 90, 300, "promotion");
        assertPetField(promoted, StatType.PET_INSTANCE_ID_STAT, 7, 300, "promotion");
        assertEquals(300, promoted.capturedAt); assertEquals(11, promoted.objectId);
        assertEquals(Integer.valueOf(10), before.pets.get(1).value(StatType.PET_FIRST_ABILITY_POINT_STAT));
        data.pet(data.scope(), 11, stats(stat(StatType.PET_FIRST_ABILITY_POINT_STAT, 250)), 400, "next tick", Collections.emptyMap());
        assertEquals(1, data.snapshot().pets.size());
        assertPetField(data.snapshot().pets.get(0), StatType.PET_FIRST_ABILITY_POINT_STAT, 250, 400, "next tick");
        assertEquals(Integer.valueOf(200), promoted.value(StatType.PET_FIRST_ABILITY_POINT_STAT));
    }
    @Test public void negativeMetadataSentinelsCannotDonateAnonymousFieldsToAnIdentifiedPet() {
        for (int sentinel : new int[]{-1, -2}) {
            ProgressionData data = new ProgressionData(); data.reset("A", "identified");
            data.pet(data.scope(), sentinel, stats(stat(StatType.PET_FIRST_ABILITY_POINT_STAT, 200)),
                200, "unidentified metadata", Collections.emptyMap());
            data.pet(data.scope(), sentinel, stats(stat(StatType.PET_INSTANCE_ID_STAT, 8), stat(StatType.PET_MAX_ABILITY_POWER_STAT, 30)),
                300, "identified metadata", Collections.emptyMap());
            ProgressionData.Pet identified = data.snapshot().pets.stream()
                .filter(p -> Integer.valueOf(8).equals(p.value(StatType.PET_INSTANCE_ID_STAT))).findFirst().get();
            assertNull("A negative sentinel is not evidence of identity", identified.value(StatType.PET_FIRST_ABILITY_POINT_STAT));
            assertNull(identified.field(StatType.PET_FIRST_ABILITY_POINT_STAT));
            assertPetField(identified, StatType.PET_MAX_ABILITY_POWER_STAT, 30, 300, "identified metadata");
            assertEquals(2, data.snapshot().pets.size()); assertFalse(data.hasPetObject(sentinel));
            ProgressionData.Pet unidentified = data.snapshot().pets.stream()
                .filter(p -> p.value(StatType.PET_INSTANCE_ID_STAT) == null).findFirst().get();
            assertPetField(unidentified, StatType.PET_FIRST_ABILITY_POINT_STAT, 200, 200, "unidentified metadata");
            data.pet(data.scope(), sentinel, stats(stat(StatType.PET_FIRST_ABILITY_POWER_STAT, 1)), 400, "new unidentified metadata", Collections.emptyMap());
            unidentified = data.snapshot().pets.stream().filter(p -> p.value(StatType.PET_INSTANCE_ID_STAT) == null).findFirst().get();
            assertNull(unidentified.value(StatType.PET_FIRST_ABILITY_POINT_STAT));
            assertNull(unidentified.value(StatType.PET_MAX_ABILITY_POWER_STAT));
            assertPetField(unidentified, StatType.PET_FIRST_ABILITY_POWER_STAT, 1, 400, "new unidentified metadata");
        }
    }
    private static void assertPetField(ProgressionData.Pet pet, StatType type, int value, long at, String source) {
        assertEquals(Integer.valueOf(value), pet.value(type));
        assertNotNull(pet.field(type)); assertEquals(at, pet.field(type).at); assertEquals(source, pet.field(type).source);
    }
    private static QuestData quest(String id) { QuestData q = new QuestData(); q.id = id; q.requirements = new int[]{42}; return q; }
    static Stat stats(StatData... values) { return new Stat(values); }
    static StatData stat(StatType type, int value) {
        StatData result = new StatData(); result.statType = type; result.statTypeNum = type.get(); result.statValue = value; return result;
    }
}
