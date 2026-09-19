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
}
