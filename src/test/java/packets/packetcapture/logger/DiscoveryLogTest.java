package packets.packetcapture.logger;

import com.google.gson.Gson;
import org.junit.Test;
import packets.*;
import packets.data.*;
import packets.data.enums.PartyActionResultType;
import packets.data.enums.PartyActionType;
import packets.incoming.*;
import packets.outgoing.ForReconnectPacket;
import packets.outgoing.HelloPacket;
import packets.outgoing.PartyActionPacket;
import packets.packetcapture.PacketProcessor;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

public class DiscoveryLogTest {
    private static NewTickPacket tick(int object, int id, int value, int secondary) {
        StatData stat = new StatData(); stat.statTypeNum=id; stat.statValue=value; stat.statValueTwo=secondary;
        ObjectStatusData status=new ObjectStatusData(); status.objectId=object; status.stats=new StatData[] {stat};
        NewTickPacket tick=new NewTickPacket(); tick.status=new ObjectStatusData[] {status}; tick.tickTime=200; return tick;
    }
    private static void observe(DiscoveryLog log, Packet packet) {
        log.observe(PacketType.byClass(packet).getIndex(), 30, packet, "decoded", 0);
    }
    @Test public void sameObjectDeltasDoNotMixPlayersAndRetainSecondaryValues() {
        DiscoveryLog log=new DiscoveryLog(null); log.setSampleMillis(0);
        observe(log,tick(12,1,500,0)); observe(log,tick(13,1,200,0)); observe(log,tick(12,1,400,2));
        DiscoveryLog.Snapshot snapshot=log.snapshot();
        assertEquals(1,snapshot.stats.get(0).changes); assertEquals(Integer.valueOf(200),snapshot.stats.get(0).min);
        DiscoveryLog.Delta delta=snapshot.events.get(2).statChanges.get(0);
        assertEquals(Integer.valueOf(500),delta.previousValue); assertEquals(400,delta.value); assertEquals(2,delta.secondary);
    }
    @Test public void pauseAndAreaBoundariesDoNotInventChanges() {
        DiscoveryLog log=new DiscoveryLog(null); log.setSampleMillis(0);
        observe(log,tick(1,1,20,0)); log.boundary(); observe(log,tick(1,1,100,0));
        log.setEnabled(false); observe(log,tick(1,1,10,0)); log.setEnabled(true); observe(log,tick(1,1,30,0));
        assertEquals(3,log.snapshot().total); assertEquals(0,log.snapshot().stats.get(0).changes);
        String run=log.snapshot().runId; log.clear(); assertNotEquals(run,log.snapshot().runId); assertEquals(0,log.snapshot().total);
    }
    @Test public void despawnRemovesTheOldObjectBaseline() {
        DiscoveryLog log=new DiscoveryLog(null); log.setSampleMillis(0);
        observe(log,tick(1,1,20,0)); UpdatePacket drop=new UpdatePacket(); drop.drops=new int[] {1}; observe(log,drop);
        observe(log,tick(1,1,100,0)); assertEquals(0,log.snapshot().stats.get(0).changes);
    }
    @Test public void stringsCredentialsUnknownValuesAndPayloadsNeverReachSnapshots() {
        DiscoveryLog log=new DiscoveryLog(null); log.setSampleMillis(0);
        HelloPacket hello=new HelloPacket(); hello.accessToken="SECRET_TOKEN"; hello.setData("SECRET_PAYLOAD".getBytes(StandardCharsets.UTF_8)); observe(log,hello);
        TextPacket chat=new TextPacket(); chat.text="SECRET_CHAT"; observe(log,chat);
        NewTickPacket named=tick(1,31,0,0); named.status[0].stats[0].stringStatValue="SECRET_PLAYER"; observe(log,named);
        observe(log,tick(1,250,987654321,0));
        NewTickPacket unexpectedString=tick(1,1,123456789,0); unexpectedString.status[0].stats[0].stringStatValue="SECRET_UNEXPECTED"; observe(log,unexpectedString);
        String json=new Gson().toJson(log.snapshot());
        assertFalse(json.contains("SECRET")); assertFalse(json.contains("987654321")); assertFalse(json.contains("123456789"));
        assertEquals(3,log.snapshot().stats.stream().mapToLong(r -> r.withheld).sum());
    }
    @Test public void failedAndPartialPacketsDoNotBecomeFieldEvidence() {
        DiscoveryLog log=new DiscoveryLog(null);
        RealmScoreUpdatePacket score=new RealmScoreUpdatePacket(); score.score=999;
        log.observe(169,20,score,"trailing-bytes",2); log.observe(169,20,null,"decode-error",10);
        DiscoveryLog.PacketRow row=log.snapshot().packets.get(0);
        assertEquals(1,row.failures); assertEquals(1,row.trailing); assertTrue(row.latest.isEmpty());
    }
    @Test public void aggregateCountsSurviveSamplingAndHistoryIsBounded() {
        DiscoveryLog log=new DiscoveryLog(null); RealmScoreUpdatePacket score=new RealmScoreUpdatePacket();
        for(int i=0;i<500;i++) observe(log,score);
        assertEquals(500,log.snapshot().total); assertTrue(log.snapshot().sampledOut>0);
        log.setSampleMillis(0);
        for(int i=0;i<2000;i++) observe(log,score);
        assertEquals(DiscoveryLog.EVENT_LIMIT,log.snapshot().events.size());
        assertEquals(2500,log.snapshot().packets.get(0).count);
    }
    @Test public void cacheAndPerEventDeltasStayBounded() {
        DiscoveryLog log=new DiscoveryLog(null); log.setSampleMillis(0);
        NewTickPacket tick=tick(1,1,10,0); tick.status=new ObjectStatusData[DiscoveryLog.CACHE_LIMIT+1];
        for(int i=0;i<tick.status.length;i++) tick.status[i]=tick(i,1,10,0).status[0];
        observe(log,tick);
        assertEquals(1,log.snapshot().cacheEvictions);
        assertEquals(DiscoveryLog.DELTA_LIMIT,log.snapshot().events.get(0).statChanges.size());
        assertEquals(DiscoveryLog.CACHE_LIMIT+1-DiscoveryLog.DELTA_LIMIT,log.snapshot().deltaOmitted);
    }
    @Test public void catalogIncludesUnusedPacketsAndNestedFieldsWithoutReadingValues() {
        List<DiscoveryCatalog.SchemaField> fields=DiscoveryCatalog.fields();
        assertTrue(fields.stream().anyMatch(f -> f.packet.equals("HELLO") && f.path.equals("accessToken") && f.retention.contains("withheld")));
        assertTrue(fields.stream().anyMatch(f -> f.packet.equals("USEITEM") && f.path.equals("slotObject.objectType") && f.retention.startsWith("Selected")));
        assertTrue(fields.stream().anyMatch(f -> f.path.equals("newObjects[].status.stats[].statTypeNum")));
    }
    @Test public void combatAndInventorySamplesKeepGameplayIdsWithoutAccountIds() {
        DiscoveryLog log=new DiscoveryLog(null); log.setSampleMillis(0);
        CreateSuccessPacket created=new CreateSuccessPacket(); created.objectId=42; created.charId=987654321; created.str="SECRET_ID";
        observe(log,created);
        packets.outgoing.EnemyHitPacket hit=new packets.outgoing.EnemyHitPacket(); hit.bulletId=17; hit.targetId=90; hit.shooterID=42;
        observe(log,hit);
        packets.outgoing.InvSwapPacket swap=new packets.outgoing.InvSwapPacket(); swap.slotFrom=new SlotObjectData(); swap.slotFrom.objectType=123;
        observe(log,swap);
        assertEquals(42,log.snapshot().events.get(0).values.get("objectId"));
        assertEquals(90,log.snapshot().events.get(1).values.get("targetId"));
        assertEquals(123,log.snapshot().events.get(2).values.get("slotFrom.objectType"));
        String json=new Gson().toJson(log.snapshot()); assertFalse(json.contains("987654321")); assertFalse(json.contains("SECRET_ID"));
    }
    @Test public void reentryTraceKeepsOnlyDerivedReconnectEvidenceAndCurrentPartySemantics() {
        DiscoveryLog log=new DiscoveryLog(null); log.setSampleMillis(0);
        ForReconnectPacket reconnect=new ForReconnectPacket(); reconnect.reconnectInfo=":USSouth:EUWest"; observe(log,reconnect);
        PartyActionPacket action=new PartyActionPacket(); action.playerId=7; action.actionId=PartyActionType.TeleportTo; observe(log,action);
        DiscoveryLog.Snapshot snapshot=log.snapshot();
        assertEquals(2,snapshot.events.get(0).values.get("serverNameCount"));
        assertEquals("TeleportTo",snapshot.events.get(1).values.get("actionId"));
        String json=new Gson().toJson(snapshot);
        assertFalse(json.contains("USSouth")); assertFalse(json.contains("EUWest"));
        assertEquals("Server → client",DiscoveryCatalog.direction(PacketType.PARTY_ACTION));
        assertEquals("Client → server",DiscoveryCatalog.direction(PacketType.PARTY_ACTION_RESULT));
        assertEquals("Client → server",DiscoveryCatalog.direction(PacketType.PARTY_JOIN_REQUEST));
        assertEquals(PartyActionResultType.Failed,PartyActionResultType.byOrdinal((byte)1));
    }
    @Test public void processorReportsUnknownDecodeFailureAndCleanUnsubscribedPackets() {
        DiscoveryLog log=DiscoveryLog.INSTANCE; log.clear(); log.setEnabled(true); log.setSaving(false);
        try {
            PacketProcessor processor=new PacketProcessor();
            processor.processPackets(255,5,ByteBuffer.allocate(5).position(5));
            processor.processPackets(169,5,ByteBuffer.allocate(5).position(5));
            ByteBuffer score=ByteBuffer.allocate(9); score.position(5); score.putInt(321); score.position(5);
            processor.processPackets(169,9,score);
            assertEquals(3,log.snapshot().total);
            assertTrue(log.snapshot().events.stream().anyMatch(e -> e.outcome.equals("unknown-id")));
            assertTrue(log.snapshot().events.stream().anyMatch(e -> e.outcome.equals("decode-error")));
            assertTrue(log.snapshot().events.stream().anyMatch(e -> Integer.valueOf(321).equals(e.values.get("score"))));
        } finally { log.clear(); log.setSaving(true); }
    }
    @Test public void writerRotatesAndFlushesSanitizedRecords() throws Exception {
        Path directory=Files.createTempDirectory("discovery-writer-test");
        DiscoveryWriter writer=new DiscoveryWriter(directory,200);
        for(int i=0;i<20;i++) writer.offer(Collections.singletonMap("counter",i));
        writer.close(); assertEquals("",writer.error());
        assertTrue(Files.exists(directory.resolve("discovery.1.jsonl")));
        assertTrue(Files.size(directory.resolve("discovery.jsonl"))<=200);
        assertTrue(new String(Files.readAllBytes(directory.resolve("discovery.jsonl")),StandardCharsets.UTF_8).contains("19"));
    }
    @Test public void diskErrorsAreVisibleWithoutStoppingCollection() throws Exception {
        Path file=Files.createTempFile("discovery-unwritable-directory", ".tmp");
        DiscoveryWriter writer=new DiscoveryWriter(file,200); writer.offer(Collections.singletonMap("counter",1)); writer.close();
        assertFalse(writer.error().isEmpty()); assertTrue(writer.dropped.get()>0);
    }
    @Test public void diagnosticsNeverCopyActivityAndNoChangeDoesNotFabricateUpdates() {
        DiscoveryLog log=new DiscoveryLog(null);
        try {
            MapInfoPacket map=new MapInfoPacket();map.name="Ice Citadel";observe(log,map);
            DiscoveryLog.DiagnosticsSnapshot view=log.diagnosticsSnapshot(null);
            assertNull(view.data.activity);assertEquals(0,log.activitySnapshotStats().full);assertEquals(0,log.activitySnapshotStats().views);
            long copies=log.diagnosticsSnapshotCopies();
            for(int i=0;i<100;i++)assertNull(log.diagnosticsSnapshot(view.revision));
            log.setEnabled(true);log.setSaving(true);log.setSampleMillis(1000);log.observe(256,1,null,"decoded",0);
            assertNull(log.diagnosticsSnapshot(view.revision));assertEquals(copies,log.diagnosticsSnapshotCopies());
            log.boundary();view=diagnosticsChanged(log,view);
            log.setSampleMillis(0);view=diagnosticsChanged(log,view);
            log.setSaving(false);view=diagnosticsChanged(log,view);
            log.setEnabled(false);view=diagnosticsChanged(log,view);
            observe(log,map);assertNull(log.diagnosticsSnapshot(view.revision));
            log.setEnabled(true);view=diagnosticsChanged(log,view);
            log.observe(PacketType.NEWTICK.getIndex(),9,null,"decode-error",4);view=diagnosticsChanged(log,view);
            assertEquals(1,view.data.packets.stream().mapToLong(p->p.failures).sum());
            log.clearDiagnostics();view=diagnosticsChanged(log,view);assertEquals(0,view.data.total);
            log.clearDiagnostics();assertNull(log.diagnosticsSnapshot(view.revision));
            log.clear();assertNotNull(log.diagnosticsSnapshot(view.revision));
        } finally {log.close();}
    }
    private DiscoveryLog.DiagnosticsSnapshot diagnosticsChanged(DiscoveryLog log,DiscoveryLog.DiagnosticsSnapshot before){
        DiscoveryLog.DiagnosticsSnapshot after=log.diagnosticsSnapshot(before.revision);assertNotNull(after);return after;
    }
    @SuppressWarnings("unchecked")
    @Test public void nestedDiagnosticRowsEventsAndActivityViewsCannotCorruptTheModelOrEachOther() {
        DiscoveryLog log=new DiscoveryLog(null);
        try {
            MapInfoPacket map=new MapInfoPacket();map.name="Ice Citadel";observe(log,map);
            IncomingPartyMemberInfoPacket roster=new IncomingPartyMemberInfoPacket();roster.description="PRIVATE_DESCRIPTION";
            PartyPlayerData member=new PartyPlayerData();member.id=12;member.name="PRIVATE_NAME";roster.partyPlayers=new PartyPlayerData[]{member};observe(log,roster);
            DiscoveryLog.DiagnosticsSnapshot view=log.diagnosticsSnapshot(null);
            DiscoveryLog.PacketRow row=view.data.packets.stream().filter(p->p.latest.containsKey("members")).findFirst().get();
            ((Map<String,Object>)((List<?>)row.latest.get("members")).get(0)).put("memberId",999);
            DiscoveryLog.Event event=view.data.events.get(1);
            ((List<?>)event.values.get("members")).clear();
            DiscoveryLog.Snapshot full=log.snapshot();
            assertEquals(12,((Map<?,?>)((List<?>)full.events.get(1).values.get("members")).get(0)).get("memberId"));
            assertFalse(new Gson().toJson(full).contains("PRIVATE_"));
            assertNull(log.diagnosticsSnapshot(view.revision));
            assertEquals(1,((List<?>)log.diagnosticsSnapshot(null).data.events.get(1).values.get("members")).size());
            DiscoveryLog.ActivitySnapshot activity=log.activityView(ActivityJournal.View.TIMELINE,"",null);
            String frozen=new Gson().toJson(activity.fullHistory());
            activity.view.data.entries.get(1).values.clear();
            log.clear();assertEquals(frozen,new Gson().toJson(activity.fullHistory()));
            assertFalse(frozen.contains("PRIVATE_"));
        } finally {log.close();}
    }
    @Test public void pauseRefreshesActivityMetadataAndCheckpointCopyRunsOnTheStoreWorker() throws Exception {
        Path directory=Files.createTempDirectory("activity-checkpoint-worker");DiscoveryLog log=new DiscoveryLog(directory);
        try {
            synchronized(log){
                MapInfoPacket map=new MapInfoPacket();map.name="Ice Citadel";observe(log,map);
                assertEquals("capture must enqueue a request, not copy history",0,log.activitySnapshotStats().full);
                DiscoveryLog.ActivitySnapshot view=log.activityView(ActivityJournal.View.RUNS,"",null);
                log.setEnabled(false);
                DiscoveryLog.ActivitySnapshot paused=log.activityView(ActivityJournal.View.RUNS,"",view.revision);
                assertNotNull(paused);assertFalse(paused.enabled);assertTrue(paused.view.data.visits.get(0).ended>0);
                assertNull(log.activityView(ActivityJournal.View.RUNS,"",paused.revision));
                assertEquals(0,log.activitySnapshotStats().full);
            }
        } finally {log.close();}
        assertTrue(log.activitySnapshotStats().full>0);assertTrue(Files.exists(directory.resolve("activity-history.json")));
    }
    @Test public void failedPausedCheckpointIsRetriedOnCloseWithoutAnyNewFrames() throws Exception {
        Path directory=Files.createTempDirectory("activity-pause-write-recovery");
        Path file=directory.resolve("activity-history.json"),barrier=directory.resolve("activity-history.json.tmp");
        DiscoveryLog log=new DiscoveryLog(directory);
        try {
            MapInfoPacket map=new MapInfoPacket();map.name="Ice Citadel";observe(log,map);
            awaitCheckpoint(()->Files.exists(file));
            byte[] before=Files.readAllBytes(file);
            ActivityJournal.State initial=new Gson().fromJson(new String(before,StandardCharsets.UTF_8),ActivityJournal.State.class);
            assertEquals(0,initial.visits.get(0).ended);
            // A directory at the temporary-file path deterministically rejects the real write,
            // while leaving the previous atomic checkpoint readable on both Windows and Unix.
            Files.createDirectory(barrier);
            log.setEnabled(false);
            ActivityJournal.State paused=log.activityHistory();
            assertTrue(paused.visits.get(0).ended>0);
            awaitCheckpoint(()->!log.diagnosticsSnapshot(null).data.activityWriterError.isEmpty());
            assertArrayEquals("the failed final write must preserve the older checkpoint",before,Files.readAllBytes(file));
            Files.delete(barrier);
            log.close(); // No packets or further visit boundaries after the failed pause checkpoint.
            ActivityJournal.State saved=new Gson().fromJson(new String(Files.readAllBytes(file),StandardCharsets.UTF_8),ActivityJournal.State.class);
            assertEquals(new Gson().toJson(paused.visits),new Gson().toJson(saved.visits));
            assertEquals(paused.captureRunId,saved.captureRunId);assertEquals(paused.packetCounts,saved.packetCounts);
            assertEquals("Collection paused / resumed",saved.visits.get(0).status);
            assertEquals("",log.diagnosticsSnapshot(null).data.activityWriterError);
        } finally {
            if(Files.isDirectory(barrier))Files.delete(barrier);
            log.close();
        }
    }
    private static void awaitCheckpoint(BooleanSupplier condition) throws InterruptedException {
        long deadline=System.nanoTime()+5_000_000_000L;
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(10);
        assertTrue("checkpoint worker did not reach the expected state",condition.getAsBoolean());
    }
}
