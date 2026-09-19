package packets.packetcapture.logger;

import com.google.gson.Gson;
import org.junit.Test;
import packets.*;
import packets.data.*;
import packets.incoming.*;
import packets.outgoing.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class ActivityJournalTest {
    private void feed(ActivityJournal journal, Packet p, long time) {
        journal.observe(p, PacketType.byClass(p), "decoded", time, Collections.emptyMap());
    }
    private ActivityJournal start() {
        ActivityJournal journal = new ActivityJournal();
        MapInfoPacket map = new MapInfoPacket(); map.name = "Vault"; feed(journal,map,1000);
        CreateSuccessPacket create = new CreateSuccessPacket(); create.objectId = 42; create.charId = 3; feed(journal,create,1001);
        identity(journal, 42, "PRIVATE_ACCOUNT", 1002);
        return journal;
    }
    private void identity(ActivityJournal j, int objectId, String account, long time) {
        NewTickPacket p = tick(objectId, 38, 0); p.status[0].stats[0].stringStatValue = account; feed(j,p,time);
    }
    private NewTickPacket tick(int id, int... stats) {
        NewTickPacket p = new NewTickPacket(); p.tickTime = 200;
        ObjectStatusData s = new ObjectStatusData(); s.objectId = id; s.stats = new StatData[stats.length / 2];
        for (int i = 0; i < s.stats.length; i++) { s.stats[i] = new StatData(); s.stats[i].statTypeNum = stats[i*2]; s.stats[i].statValue = stats[i*2+1]; }
        p.status = new ObjectStatusData[] {s}; return p;
    }
    private ExaltationUpdatePacket exalt(int progress) {
        ExaltationUpdatePacket p = new ExaltationUpdatePacket(); p.objType = 782; p.healthProgress = progress; return p;
    }
    @Test public void mapRecognitionIsSharedByHistoryAndDiagnosticsAndPreservesTransitions() {
        ActivityJournal journal=new ActivityJournal();
        String[] names={"Lost Halls","Vault","PRIVATE_MAP","PRIVATE_MAP","mgm2 Dungeon","Nexus"};
        String[] displays={"PRIVATE_DISPLAY","Ocean Trench","Ocean Trench","PRIVATE_DISPLAY","PRIVATE_DISPLAY","Ice Citadel"};
        String[] expected={"Lost Halls","Vault","Ocean Trench","Unrecognized area","The Trials of Cronus","Nexus"};
        for(int i=0;i<names.length;i++){
            MapInfoPacket map=new MapInfoPacket();map.name=names[i];map.displayName=displays[i];map.realmName="PRIVATE_REALM";
            feed(journal,map,1000L+i*2000);
            assertEquals(expected[i],DiscoveryCatalog.values(map,PacketType.MAPINFO).get("canonicalMap"));
        }
        ActivityJournal.State history=journal.snapshot();assertEquals(expected.length,history.visits.size());
        for(int i=0;i<expected.length;i++){
            ActivityJournal.Visit visit=history.visits.get(i);assertEquals(expected[i],visit.map);
            assertEquals(expected[i],history.entries.get(i).map);
            if(i<expected.length-1){assertEquals(visit.started+2000,visit.ended);assertTrue(visit.status.contains("completion unknown"));}
        }
        assertFalse(new Gson().toJson(history).contains("PRIVATE_"));
    }
    @Test public void exaltSnapshotsAreBaselinesAndGapsNeverBecomeAwards() {
        ActivityJournal j = start(); feed(j,exalt(10),1100); feed(j,exalt(10),1200); feed(j,exalt(11),1300);
        assertEquals(1,j.snapshot().visits.get(0).exaltIncrease);
        assertEquals(1,j.snapshot().entries.stream().filter(e -> e.kind.equals("Exalt change")).count());
        j.boundary(1400,"Capture gap");
        MapInfoPacket map = new MapInfoPacket(); map.name = "Vault"; feed(j,map,1500);
        CreateSuccessPacket create = new CreateSuccessPacket(); create.objectId = 43; create.charId = 3; feed(j,create,1501);
        feed(j,exalt(15),1600);
        identity(j,43,"DIFFERENT_PRIVATE_ACCOUNT",1601);
        assertEquals(0,j.snapshot().visits.get(1).exaltIncrease);
    }
    @Test public void crossAreaProgressIsUnassignedAndDecreasesResetBaseline() {
        ActivityJournal j = start(); feed(j,exalt(10),1100);
        MapInfoPacket map = new MapInfoPacket(); map.name = "Vault"; feed(j,map,1200);
        CreateSuccessPacket create = new CreateSuccessPacket(); create.objectId=43; create.charId=3; feed(j,create,1201);
        identity(j,43,"PRIVATE_ACCOUNT",1202);
        feed(j,exalt(12),1300); feed(j,exalt(2),1400); feed(j,exalt(3),1500);
        assertEquals(1,j.snapshot().visits.get(1).exaltIncrease);
        assertTrue(j.snapshot().entries.stream().anyMatch(e -> e.kind.equals("Exalt change") && e.visitId.isEmpty()));
    }
    @Test public void sameAccountAcrossConnectionCanCompareProgressWithoutAssigningAClear() {
        ActivityJournal j=start(); feed(j,exalt(10),1100); j.boundary(1200,"Connection");
        MapInfoPacket map=new MapInfoPacket(); map.name="Vault"; feed(j,map,1300);
        CreateSuccessPacket create=new CreateSuccessPacket(); create.objectId=43; create.charId=3; feed(j,create,1301);
        feed(j,exalt(11),1400); identity(j,43,"PRIVATE_ACCOUNT",1500);
        assertTrue(j.snapshot().entries.stream().anyMatch(e -> e.kind.equals("Exalt change") && e.visitId.isEmpty()));
        assertEquals(0,j.snapshot().visits.get(1).exaltIncrease);
        assertFalse(new Gson().toJson(j.snapshot()).contains("PRIVATE_ACCOUNT"));
    }
    @Test public void resourcesBelongToLocalObjectAndConditionIntervalsExcludeGapsAndFailures() {
        ActivityJournal j=start();
        feed(j,tick(42,1,500,4,200,29,0x20000),1100);
        feed(j,tick(99,1,1,4,1),1300);
        feed(j,tick(42,1,450,29,0),1500);
        ActivityJournal.Visit v=j.snapshot().visits.get(0);
        assertEquals(Integer.valueOf(450),v.hpMin); assertEquals(Integer.valueOf(200),v.mpMin);
        assertEquals(Long.valueOf(400),v.conditions.get("HEALING"));
        assertEquals(400,v.conditionObservedMillis);
        feed(j,tick(42),5000); feed(j,tick(42),5200);
        assertEquals(400,j.snapshot().visits.get(0).conditionObservedMillis);
        j.observe(null,PacketType.NEWTICK,"decode-error",5300,Collections.emptyMap());
        feed(j,tick(42,1,700),5400);
        assertEquals(0,j.snapshot().visits.get(0).hpRises); // Never bridge an unknown update.
        assertEquals(1,j.snapshot().visits.get(0).issues);
    }
    @Test public void rosterValuesExcludeNamesAndDescriptionsAndImportantEventsAreUnsampled() {
        DiscoveryLog log=new DiscoveryLog(null);
        IncomingPartyMemberInfoPacket p=new IncomingPartyMemberInfoPacket(); p.partyId=7; p.description="SECRET_DESCRIPTION";
        PartyPlayerData member=new PartyPlayerData(); member.id=12; member.objectId=55; member.name="SECRET_NAME";
        p.partyPlayers=new PartyPlayerData[]{member};
        for(int i=0;i<4;i++) log.observe(PacketType.INCOMING_PARTY_MEMBER_INFO.getIndex(),30,p,"decoded",0);
        assertEquals(4,log.snapshot().events.size());
        String json=new Gson().toJson(log.snapshot()); assertFalse(json.contains("SECRET")); assertTrue(json.contains("memberId"));
        log.close();
    }
    @Test public void conditionUpdateBetweenTicksUsesItsObservedTime() {
        ActivityJournal j=start(); feed(j,tick(42,29,0x20000),1100);
        UpdatePacket update=new UpdatePacket(); ObjectData object=new ObjectData(); object.status=tick(42,29,0).status[0];
        update.newObjects=new ObjectData[]{object}; feed(j,update,1200); feed(j,tick(42),1300);
        assertEquals(Long.valueOf(100),j.snapshot().visits.get(0).conditions.get("HEALING"));
        assertEquals(200,j.snapshot().visits.get(0).conditionObservedMillis);
        assertEquals(2,j.snapshot().visits.get(0).conditionTimeline.size());
        assertEquals(1100,j.snapshot().visits.get(0).conditionTimeline.get(0).start);
        assertEquals(1200,j.snapshot().visits.get(0).conditionTimeline.get(0).end);
    }
    @Test public void chartIntervalsDoNotBridgeUnknownTicksAndCopiesAreDetached() {
        ActivityJournal j=start(); feed(j,tick(42,29,0x20000,1,600),1100); feed(j,tick(42),1300);
        feed(j,tick(42),5000); feed(j,tick(42,29,0x20000,1,550),5200); feed(j,tick(42),5400);
        ActivityJournal.Visit v=j.snapshot().visits.get(0);
        assertEquals(2,v.conditionTimeline.size()); assertEquals(1300,v.conditionTimeline.get(0).end); assertEquals(5200,v.conditionTimeline.get(1).start);
        v.conditionTimeline.get(0).end=999999; v.resourceTimeline.get(0).hp=99999;
        assertEquals(1300,j.snapshot().visits.get(0).conditionTimeline.get(0).end);
        assertEquals(Integer.valueOf(600),j.snapshot().visits.get(0).resourceTimeline.get(0).hp);
    }
    @Test public void shortDecodeFailureBreaksResourceLinesAndRequiresFreshValues() {
        ActivityJournal j=start(); feed(j,tick(42,1,600,4,200),1100);
        j.observe(null,PacketType.NEWTICK,"decode-error",1200,Collections.emptyMap());
        feed(j,tick(42),1300); feed(j,tick(42,1,550),2100);
        ActivityJournal.Visit v=j.snapshot().visits.get(0);
        assertEquals(3,v.resourceTimeline.size());
        assertNull(v.resourceTimeline.get(1).hp); assertNull(v.resourceTimeline.get(1).mp);
        assertEquals(Integer.valueOf(550),v.resourceTimeline.get(2).hp); assertNull(v.resourceTimeline.get(2).mp);
        assertEquals(0,v.hpFalls);
    }
    @Test public void diagnosticResetPreservesProductHistoryAndLegacyFilesGetEmptyChartLists() throws Exception {
        DiscoveryLog log=new DiscoveryLog(null); MapInfoPacket map=new MapInfoPacket();map.name="Vault";
        log.observe(PacketType.MAPINFO.getIndex(),30,map,"decoded",0);log.clearDiagnostics();
        assertEquals(0,log.snapshot().total);assertEquals(1,log.activityHistory().visits.size());log.close();
        com.google.gson.JsonObject old=new Gson().toJsonTree(start().snapshot()).getAsJsonObject();
        com.google.gson.JsonObject visit=old.getAsJsonArray("visits").get(0).getAsJsonObject();visit.remove("resourceTimeline");visit.remove("conditionTimeline");
        Path directory=Files.createTempDirectory("legacy-activity");Files.write(directory.resolve("activity-history.json"),old.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ActivityStore store=new ActivityStore(directory);ActivityJournal.State restored=store.load();store.close();
        assertNotNull(restored);assertTrue(restored.visits.get(0).resourceTimeline.isEmpty());assertTrue(restored.visits.get(0).conditionTimeline.isEmpty());
    }
    @Test public void corruptHistoryIsPreservedAndReadOnlyPreviewCannotOverwriteHistory() throws Exception {
        Path directory=Files.createTempDirectory("activity-corrupt"); Path file=directory.resolve("activity-history.json");
        Files.write(file,"broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ActivityStore store=new ActivityStore(directory); assertNull(store.load()); assertFalse(store.error().isEmpty());
        store.offer(start().snapshot()); store.close();
        try(java.util.stream.Stream<Path> files=Files.list(directory)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("activity-history-unreadable-")));
        }
        byte[] before=Files.readAllBytes(file);
        DiscoveryLog preview=new DiscoveryLog(directory); preview.setEnabled(false); preview.close();
        assertArrayEquals(before,Files.readAllBytes(file));
    }
    @Test public void effectDefaultsAreNotPresentedAsTransmittedFields() {
        ShowEffectPacket effect=new ShowEffectPacket(); effect.presenceMask=32; effect.duration=2;
        Map<String,Object> values=DiscoveryCatalog.values(effect,PacketType.SHOWEFFECT);
        assertEquals(2f,values.get("duration")); assertFalse(values.containsKey("targetObjectId")); assertFalse(values.containsKey("color"));
    }
    @Test public void ownershipChecksDetectAgreementsAndConflictsWithoutChangingDps() {
        ActivityJournal j=start(); feed(j,tick(100,114,42),1100);
        ServerPlayerShootPacket shot=new ServerPlayerShootPacket(); shot.ownerId=100; shot.summonerId=42; feed(j,shot,1200); feed(j,shot,1300);
        assertEquals(1,j.snapshot().visits.get(0).ownerMatches);
        feed(j,tick(100,114,99),1400); assertEquals(1,j.snapshot().visits.get(0).ownerConflicts);
    }
    @Test public void historyPersistsAndRestoredActiveVisitsDoNotContinueOrRestoreBaselines() throws Exception {
        Path directory=Files.createTempDirectory("activity-store"); ActivityJournal j=start(); feed(j,exalt(10),1200);
        ActivityStore store=new ActivityStore(directory); store.offer(j.snapshot()); store.close(); assertEquals("",store.error());
        ActivityStore reload=new ActivityStore(directory);
        ActivityJournal restored=new ActivityJournal(reload.load()); reload.close();
        assertTrue(restored.snapshot().visits.get(0).ended>0);
        assertTrue(restored.snapshot().visits.get(0).status.contains("App ended"));
        feed(restored,exalt(20),2000);
        assertEquals(0,restored.snapshot().visits.get(0).exaltIncrease);
    }
    @Test public void snapshotIsDetachedAndHistoryRemainsBounded() {
        ActivityJournal j=start(); ActivityJournal.State copy=j.snapshot();
        copy.visits.get(0).conditions.put("FAKE",500L);
        assertFalse(j.snapshot().visits.get(0).conditions.containsKey("FAKE"));
        UseItemPacket use=new UseItemPacket(); use.slotObject=new SlotObjectData(); use.slotObject.slotId=1000000;
        for(int i=0;i<1200;i++) feed(j,use,2000+i);
        assertEquals(ActivityJournal.EVENT_LIMIT,j.snapshot().entries.size());
        assertEquals(1200,j.snapshot().visits.get(0).useRequests);
        assertTrue(new Gson().toJson(j.snapshot()).contains("Potion storage"));
    }
}
