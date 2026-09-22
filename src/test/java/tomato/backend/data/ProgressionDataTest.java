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
    private static QuestData quest(String id) { QuestData q = new QuestData(); q.id = id; q.requirements = new int[]{42}; return q; }
    static Stat stats(StatData... values) { return new Stat(values); }
    static StatData stat(StatType type, int value) {
        StatData result = new StatData(); result.statType = type; result.statTypeNum = type.get(); result.statValue = value; return result;
    }
}
