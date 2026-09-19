package packets.packetcapture.logger;

import com.google.gson.Gson;
import org.junit.Test;
import packets.*;
import packets.data.*;
import packets.incoming.*;
import packets.outgoing.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    @Test public void revisionedViewsCopyOnlyTheirRowsAndEqualRevisionsCopyNothing() {
        ActivityJournal j=start();feed(j,tick(42,1,600,29,0x20000),1100);feed(j,tick(42),1300);feed(j,exalt(10),1400);
        MapInfoPacket map=new MapInfoPacket();map.name="Ice Citadel";feed(j,map,2000);
        ActivityJournal.SnapshotStats before=j.snapshotStats();
        ActivityJournal.ViewSnapshot runs=j.viewSnapshot(ActivityJournal.View.RUNS,"",null);
        assertEquals(2,runs.data.visits.size());assertTrue(runs.data.entries.isEmpty());
        for(ActivityJournal.Visit v:runs.data.visits){assertTrue(v.resourceTimeline.isEmpty());assertTrue(v.conditionTimeline.isEmpty());}
        ActivityJournal.ViewSnapshot timeline=j.viewSnapshot(ActivityJournal.View.TIMELINE,"",null);
        assertTrue(timeline.data.visits.isEmpty());assertEquals(2,timeline.choices.size());assertFalse(timeline.data.entries.isEmpty());
        assertEquals(before.resourcePoints,j.snapshotStats().resourcePoints);assertEquals(before.conditionSlices,j.snapshotStats().conditionSlices);
        String first=runs.data.visits.get(0).id;
        ActivityJournal.ViewSnapshot combat=j.viewSnapshot(ActivityJournal.View.COMBAT,first,null);
        assertEquals(1,combat.data.visits.size());assertEquals(first,combat.selectedVisit);assertEquals(2,combat.choices.size());assertTrue(combat.data.entries.isEmpty());
        assertEquals(1,j.snapshotStats().resourcePoints-before.resourcePoints);assertEquals(1,j.snapshotStats().conditionSlices-before.conditionSlices);
        ActivityJournal.SnapshotStats copied=j.snapshotStats();
        for(int i=0;i<100;i++){
            assertNull(j.viewSnapshot(ActivityJournal.View.RUNS,"",runs.revision));
            assertNull(j.viewSnapshot(ActivityJournal.View.TIMELINE,"",timeline.revision));
            assertNull(j.viewSnapshot(ActivityJournal.View.COMBAT,first,combat.revision));
        }
        assertEquals(new Gson().toJson(copied),new Gson().toJson(j.snapshotStats()));
        RealmScoreUpdatePacket score=new RealmScoreUpdatePacket();score.score=10;feed(j,score,2100);
        assertNotNull(j.viewSnapshot(ActivityJournal.View.RUNS,"",runs.revision));
        assertNull(j.viewSnapshot(ActivityJournal.View.TIMELINE,"",timeline.revision));
        assertNull(j.viewSnapshot(ActivityJournal.View.COMBAT,first,combat.revision));
        assertNotNull(j.viewSnapshot(ActivityJournal.View.COMBAT,runs.data.visits.get(1).id,combat.revision));
    }
    @Test public void viewsAndPinnedExportsAreDeeplyDetachedAndPreserveTheDisplayedRevision() {
        ActivityJournal j=start();feed(j,tick(42,1,600,29,0x20000),1100);feed(j,tick(42),1300);feed(j,exalt(10),1400);
        ActivityJournal.ViewSnapshot view=j.viewSnapshot(ActivityJournal.View.COMBAT,"",null);
        assertFalse("revision tokens must not serialize the private identity baseline",new Gson().toJson(view).contains("PRIVATE_ACCOUNT"));
        String original=new Gson().toJson(view.fullHistory());
        view.data.visits.get(0).resourceTimeline.get(0).hp=-99;
        view.data.visits.get(0).conditionTimeline.get(0).end=-1;
        view.data.visits.get(0).conditions.clear();view.data.visits.clear();
        ActivityJournal.State export=view.fullHistory();
        ((int[])export.entries.stream().filter(e->e.values.containsKey("progress")).findFirst().get().values.get("progress"))[0]=999;
        export.visits.get(0).equipment.put(0,123);export.entries.clear();
        assertEquals(original,new Gson().toJson(view.fullHistory()));
        feed(j,tick(42,1,400,29,0),2300);j.boundary(2400,"Paused");j.clear();
        assertTrue(j.snapshot().visits.isEmpty());assertEquals(original,new Gson().toJson(view.fullHistory()));
        ActivityJournal.Visit selected=view.combatVisit(view.selectedVisit);selected.resourceTimeline.clear();
        assertFalse(view.combatVisit(view.selectedVisit).resourceTimeline.isEmpty());
    }
    @Test public void resourcesFailuresBoundariesAndClearsInvalidateTheRelevantView() {
        ActivityJournal j=start();ActivityJournal.ViewSnapshot view=j.viewSnapshot(ActivityJournal.View.COMBAT,"",null);
        feed(j,tick(42,1,600,29,0x20000),1100);
        view=assertChanged(j,view);feed(j,tick(42),1300);view=assertChanged(j,view);
        assertFalse(view.data.visits.get(0).conditionTimeline.isEmpty());
        j.observe(null,PacketType.NEWTICK,"decode-error",1400,Collections.emptyMap());view=assertChanged(j,view);
        assertNull(view.data.visits.get(0).resourceTimeline.get(1).hp);
        j.boundary(1500,"Paused");view=assertChanged(j,view);assertEquals(1500,view.data.visits.get(0).ended);
        j.boundary(1600,"Still paused");assertNull(j.viewSnapshot(ActivityJournal.View.COMBAT,view.selectedVisit,view.revision));
        j.clear();view=assertChanged(j,view);assertTrue(view.data.visits.isEmpty());assertTrue(view.choices.isEmpty());
    }
    private ActivityJournal.ViewSnapshot assertChanged(ActivityJournal j,ActivityJournal.ViewSnapshot before){
        ActivityJournal.ViewSnapshot after=j.viewSnapshot(ActivityJournal.View.COMBAT,before.selectedVisit,before.revision);assertNotNull(after);return after;
    }
    @Test public void retentionInvalidatesAnOldSelectedVisitWithoutMutatingItsPinnedExport() {
        ActivityJournal j=new ActivityJournal();long now=1000;
        for(int run=0;run<12;run++){
            MapInfoPacket map=new MapInfoPacket();map.name="Ice Citadel";feed(j,map,now++);
            CreateSuccessPacket create=new CreateSuccessPacket();create.objectId=42;feed(j,create,now++);
            // Chart samples continue at constant HP, but Resources events require an actual change.
            // Exercise the event budget as well as both global chart budgets.
            for(int n=0;n<1001;n++){now+=1000;feed(j,tick(42,1,600-n%2,29,n%2==0?0x20000:0),now);}
        }
        ActivityJournal.State full=j.snapshot();
        assertEquals(12000,full.visits.stream().mapToInt(v->v.resourceTimeline.size()).sum());
        assertEquals(12000,full.visits.stream().mapToInt(v->v.conditionTimeline.size()).sum());
        assertEquals(ActivityJournal.EVENT_LIMIT,full.entries.size());
        String first=full.visits.get(0).id;
        MapInfoPacket map=new MapInfoPacket();map.name="Ocean Trench";feed(j,map,++now);
        CreateSuccessPacket create=new CreateSuccessPacket();create.objectId=42;feed(j,create,++now);
        ActivityJournal.ViewSnapshot old=j.viewSnapshot(ActivityJournal.View.COMBAT,first,null);
        long omitted=old.data.visits.get(0).timelineOmitted;
        now+=1000;feed(j,tick(42,1,500,29,0),now);
        ActivityJournal.ViewSnapshot trimmed=assertChanged(j,old);
        assertEquals(999,trimmed.data.visits.get(0).resourceTimeline.size());assertEquals(omitted+1,trimmed.data.visits.get(0).timelineOmitted);
        assertEquals(1000,old.fullHistory().visits.get(0).resourceTimeline.size());
        for(int i=0;i<ActivityJournal.RUN_LIMIT;i++)feed(j,map,++now);
        ActivityJournal.ViewSnapshot removed=assertChanged(j,trimmed);
        assertEquals(ActivityJournal.RUN_LIMIT,removed.visitCount);assertNotEquals(first,removed.selectedVisit);
        assertEquals(first,old.fullHistory().visits.get(0).id);
    }
    @Test public void failedCheckpointDoesNotAcknowledgeOrDiscardANewerPendingCheckpoint() throws Exception {
        Path directory=Files.createTempDirectory("activity-pending-write-recovery");
        Path barrier=directory.resolve("activity-history.json.tmp");Files.createDirectory(barrier);
        ActivityStore store=new ActivityStore(directory);
        CountDownLatch firstRead=new CountDownLatch(1),releaseFirst=new CountDownLatch(1),secondRead=new CountDownLatch(1),releaseSecond=new CountDownLatch(1);
        AtomicBoolean oldSaved=new AtomicBoolean(),newSaved=new AtomicBoolean();
        ActivityJournal.State oldState=new ActivityJournal.State(),newState=new ActivityJournal.State();
        oldState.captureRunId="older";newState.captureRunId="newer";
        try {
            store.offer(()->{firstRead.countDown();awaitRelease(releaseFirst);return oldState;},()->oldSaved.set(true));
            assertTrue(firstRead.await(5,TimeUnit.SECONDS));
            store.offer(()->{secondRead.countDown();awaitRelease(releaseSecond);return newState;},()->newSaved.set(true));
            releaseFirst.countDown();assertTrue(secondRead.await(5,TimeUnit.SECONDS));
            assertFalse(store.error().isEmpty());assertFalse(oldSaved.get());assertFalse(newSaved.get());
            Files.delete(barrier);releaseSecond.countDown();store.close();
            assertFalse(oldSaved.get());assertTrue(newSaved.get());assertEquals("",store.error());
            ActivityJournal.State saved=new Gson().fromJson(new String(Files.readAllBytes(directory.resolve("activity-history.json")),java.nio.charset.StandardCharsets.UTF_8),ActivityJournal.State.class);
            assertEquals("newer",saved.captureRunId);
        } finally {releaseFirst.countDown();releaseSecond.countDown();store.close();}
    }
    private static void awaitRelease(CountDownLatch latch) {
        try {if(!latch.await(5,TimeUnit.SECONDS))throw new IllegalStateException("checkpoint read was not released");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    }
}
