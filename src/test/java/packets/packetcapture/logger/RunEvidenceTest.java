package packets.packetcapture.logger;

import org.junit.Test;
import packets.Packet;
import packets.PacketType;
import packets.data.ObjectStatusData;
import packets.data.StatData;
import packets.data.enums.NotificationEffectType;
import packets.data.enums.StatType;
import packets.incoming.*;
import tomato.backend.data.Entity;
import tomato.backend.data.InspectSnapshot;
import tomato.realmshark.enums.CharacterStatistics;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class RunEvidenceTest {
    private static void feed(ActivityJournal journal, Packet packet, long time) {
        journal.observe(packet, PacketType.byClass(packet), "decoded", time, Collections.emptyMap());
    }
    private static void map(ActivityJournal journal, String name, long time) {
        MapInfoPacket map = new MapInfoPacket(); map.name = name; feed(journal, map, time);
    }
    private static void create(ActivityJournal journal, int character, int count, long time) {
        CreateSuccessPacket create = new CreateSuccessPacket(); create.charId = character; create.objectId = 42;
        feed(journal, create, time);
        int[] counts = new int[CharacterStatistics.DUNGEON_NAMES.size()];
        counts[CharacterStatistics.getDungeonIndex("Ice Citadel")] = count;
        journal.completionStats(character, counts);
    }
    private static void identity(ActivityJournal journal, String account, long time) {
        NewTickPacket tick = new NewTickPacket(); tick.tickTime = 200;
        ObjectStatusData status = new ObjectStatusData(); status.objectId = 42;
        StatData value = new StatData(); value.statTypeNum = 38; value.stringStatValue = account;
        status.stats = new StatData[]{value}; tick.status = new ObjectStatusData[]{status}; feed(journal, tick, time);
    }
    private static TextPacket text(String name, String text) {
        TextPacket packet = new TextPacket(); packet.name = name; packet.text = text; return packet;
    }
    private static InspectSnapshot player(int id, String name) {
        Entity player = new Entity(null, id, 0); player.objectType = 782;
        StatData stat = new StatData(); stat.stringStatValue = name; player.stat.set(StatType.NAME_STAT, stat);
        return new InspectSnapshot(player);
    }

    @Test public void victoryAndFinalBossDialogueSurviveConnectionBoundariesAndRestore() throws Exception {
        ActivityJournal journal = new ActivityJournal(); map(journal, "The Shatters", 1000);
        feed(journal, text("#The Bridge Sentinel", "I tried to protect you... I have failed."), 1100);
        feed(journal, text("King Azamoth", "This fate is mine to bear... not hers."), 1200);
        assertEquals("In progress", journal.snapshot().visits.get(0).runStatus());
        feed(journal, text("#King Azamoth", "This fate is mine to bear... not hers."), 1300);
        ActivityJournal.ViewSnapshot completed = journal.viewSnapshot(ActivityJournal.View.RUNS, "", null);
        journal.boundary(1400, "Connection boundary; completion unknown");
        ActivityJournal.Visit visit = journal.snapshot().visits.get(0);
        assertEquals("Completed", visit.runStatus()); assertEquals("Completed", visit.status);
        assertTrue(visit.endReason.startsWith("Connection boundary"));
        assertTrue(visit.completionEvidence.contains("King Azamoth"));
        assertEquals(0, completed.data.visits.get(0).ended);
        map(journal, "Ocean Trench", 1500);
        feed(journal, text("#King Azamoth", "This fate is mine to bear... not hers."), 1600);
        assertEquals("In progress", journal.snapshot().visits.get(1).runStatus());
        NotificationPacket victory = new NotificationPacket(); victory.effect = NotificationEffectType.Victory;
        feed(journal, victory, 1700);
        Path directory = Files.createTempDirectory("run-completion");
        ActivityStore store = new ActivityStore(directory); store.offer(journal.snapshot()); store.close();
        ActivityStore reader = new ActivityStore(directory); ActivityJournal restored = new ActivityJournal(reader.load()); reader.close();
        assertEquals("Completed", restored.snapshot().visits.get(0).runStatus());
        assertEquals("Completed", restored.snapshot().visits.get(1).runStatus());
        map(journal, "Nexus", 1800); feed(journal, victory, 1900);
        assertTrue(journal.snapshot().visits.get(2).completionEvidence.isEmpty());
    }

    @Test public void nextEntryCounterRequiresMatchingIdentityCharacterAndContinuousAdjacentVisit() {
        for (String variant : new String[]{"valid", "account", "character", "gap", "pause", "delta", "missing", "unchanged"}) {
            ActivityJournal journal = new ActivityJournal(); map(journal, "Ice Citadel", 1000);
            create(journal, 7, 3, 1010); identity(journal, "ACCOUNT_A", 1020);
            journal.boundary(1500, variant.equals("pause") ? "Collection paused / resumed" : "Connection boundary; completion unknown");
            long next = variant.equals("gap") ? 60000 : 2000;
            map(journal, "Nexus", next);
            create(journal, variant.equals("character") ? 8 : 7, variant.equals("delta") ? 5 : variant.equals("unchanged") ? 3 : 4, next + 10);
            assertFalse("Wait for account evidence", journal.snapshot().visits.get(0).runStatus().equals("Completed"));
            if (variant.equals("missing")) journal.completionStats(7, null);
            identity(journal, variant.equals("account") ? "ACCOUNT_B" : "ACCOUNT_A", next + 20);
            assertEquals(variant, variant.equals("valid"), journal.snapshot().visits.get(0).runStatus().equals("Completed"));
            assertFalse(new com.google.gson.Gson().toJson(journal.snapshot()).contains("ACCOUNT_"));
        }
    }

    @Test public void partialVictoryDoesNotConfirmCompletionAndOldHistoryRemainsUnconfirmed() {
        ActivityJournal journal = new ActivityJournal(); map(journal, "Ice Citadel", 1000);
        NotificationPacket victory = new NotificationPacket(); victory.effect = NotificationEffectType.Victory;
        journal.observe(victory, PacketType.NOTIFICATION, "trailing-bytes", 1100, Collections.emptyMap());
        journal.boundary(1200, "Connection boundary; completion unknown");
        assertEquals("Left · completion unconfirmed", journal.snapshot().visits.get(0).runStatus());
        ActivityJournal.State legacy = new com.google.gson.Gson().fromJson("{\"visits\":[{\"id\":\"old\",\"map\":\"Ice Citadel\",\"started\":1000,\"lastSeen\":2000,\"ended\":2000}],\"entries\":[]}", ActivityJournal.State.class);
        assertEquals("Left · completion unconfirmed", new ActivityJournal(legacy).snapshot().visits.get(0).runStatus());
        tomato.realmshark.RealmCharacterStats incomplete = new tomato.realmshark.RealmCharacterStats();
        incomplete.decode(Base64.getEncoder().encodeToString(new byte[5]));
        assertNull("Trailing pcStats bytes cannot establish a completion baseline", incomplete.completionCounts());
    }

    @Test public void damageUsesSharedCombatWindowMigratesAnonymousPlayersAndSurvivesPersistence() throws Exception {
        ActivityJournal journal = new ActivityJournal(); map(journal, "Ice Citadel", 1000);
        InspectSnapshot anonymous = player(1, ""), alice = player(1, "Alice"), bob = player(2, "Bob");
        journal.inspectPlayer(anonymous);
        assertFalse(journal.inspectDamage(1, 500, 900));
        assertTrue(journal.inspectDamage(1, 500, 1100));
        assertNull(journal.snapshot().visits.get(0).dps(500L));
        journal.inspectPlayer(alice); journal.inspectPlayer(bob); journal.inspectDamage(2, 1500, 2100);
        ActivityJournal.ViewSnapshot frozen = journal.viewSnapshot(ActivityJournal.View.INSPECT, "", null);
        ActivityJournal.Visit first = frozen.data.visits.get(0);
        assertEquals(Long.valueOf(500), first.damage(alice.key())); assertEquals(Double.valueOf(500), first.dps(first.damage(alice.key())));
        assertEquals(Long.valueOf(1500), first.damage(bob.key())); assertEquals(Double.valueOf(1500), first.dps(first.damage(bob.key())));
        assertEquals(2000, first.totalDamage); assertFalse(first.playerDamage.containsKey(anonymous.key()));
        journal.inspectDamage(1, 1000, 3100);
        assertEquals(2000, frozen.fullHistory().visits.get(0).totalDamage);
        journal.boundary(3200, "Connection boundary; completion unknown");
        assertFalse(journal.inspectDamage(1, 999, 3300));
        map(journal, "Ice Citadel", 3400); journal.inspectPlayer(alice);
        assertEquals(Long.valueOf(0), journal.snapshot().visits.get(1).damage(alice.key()));
        Path directory = Files.createTempDirectory("run-damage");
        ActivityStore store = new ActivityStore(directory); store.offer(journal.snapshot()); store.close();
        ActivityStore reader = new ActivityStore(directory); ActivityJournal restored = new ActivityJournal(reader.load()); reader.close();
        ActivityJournal.Visit saved = restored.snapshot().visits.get(0);
        assertEquals(3000, saved.totalDamage); assertEquals(Double.valueOf(750), saved.dps(saved.damage(alice.key())));
        assertNull(new ActivityJournal.Visit().damage(alice.key()));
    }
    @Test public void durableArchiveKeepsRunsAfterTheLiveJournalEvictsThem()throws Exception{
        Path root=Files.createTempDirectory("all-session-runs");tomato.history.SessionStore store=new tomato.history.SessionStore(root,true,"test");
        try{
            ActivityJournal journal=new ActivityJournal();journal.archiveTo(visit->store.put("runs",visit.id,visit));
            for(int i=0;i<250;i++)map(journal,"Ice Citadel",1000L+i*1000);
            journal.boundary(252000,"Connection boundary; completion unknown");store.flush();
            assertEquals(ActivityJournal.RUN_LIMIT,journal.snapshot().visits.size());
            assertEquals(250,store.read(store.currentId(),"runs",ActivityJournal.Visit.class).size());
        }finally{store.close();}
    }
}
