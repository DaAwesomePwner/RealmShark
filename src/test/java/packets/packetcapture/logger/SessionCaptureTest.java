package packets.packetcapture.logger;

import java.nio.file.*;
import org.junit.Test;
import packets.PacketType;
import packets.data.StatData;
import packets.data.enums.*;
import packets.incoming.*;
import tomato.backend.data.Entity;
import tomato.history.SessionStore;
import static org.junit.Assert.*;

public class SessionCaptureTest {
    @Test public void activeCollectorSkipsUnchangedVisitsAndWritesChangedAndFinalVisits() throws Exception {
        Path directory=Files.createTempDirectory("run-gating"); SessionStore store=new SessionStore(directory,true,"test");
        java.util.concurrent.atomic.AtomicLong clock=new java.util.concurrent.atomic.AtomicLong(1000);
        DiscoveryLog log=new DiscoveryLog(null); log.activeRunClock=clock::get; log.attachHistory(store);
        try {
            MapInfoPacket map=new MapInfoPacket(); map.name="Vault";
            log.observe(PacketType.MAPINFO.getIndex(),30,map,"decoded",0); store.flush();
            String id=log.currentVisitId();
            Path file=directory.resolve(store.currentId()).resolve("runs").resolve(SessionStore.checkpointName(id)+".json");
            java.nio.file.attribute.FileTime sentinel=java.nio.file.attribute.FileTime.fromMillis(1000);
            Files.setLastModifiedTime(file,sentinel); clock.addAndGet(DiscoveryLog.ACTIVE_RUN_INTERVAL_MILLIS); store.flush();
            assertEquals("No checkpoint replacement for an unchanged revision",sentinel,Files.getLastModifiedTime(file));
            CreateSuccessPacket create=new CreateSuccessPacket(); create.objectId=42; create.charId=3;
            log.observe(PacketType.CREATE_SUCCESS.getIndex(),12,create,"decoded",0); store.flush();
            assertNotEquals(sentinel,Files.getLastModifiedTime(file));
            assertEquals(2,store.readCheckpoint(store.currentId(),"runs",id,ActivityJournal.Visit.class).get().frames);
            log.close(); store.flush();
            assertTrue(store.readCheckpoint(store.currentId(),"runs",id,ActivityJournal.Visit.class).get().ended>0);
        } finally { log.close(); store.close(); }
    }
    @Test public void activeCollectorThrottlesChangingRevisionsButNewVisitsAndFinalWritesAreImmediate() throws Exception {
        Path directory=Files.createTempDirectory("run-interval"); SessionStore store=new SessionStore(directory,true,"test");
        java.util.concurrent.atomic.AtomicLong clock=new java.util.concurrent.atomic.AtomicLong(1000);
        DiscoveryLog log=new DiscoveryLog(null); log.activeRunClock=clock::get; log.attachHistory(store);
        try {
            MapInfoPacket map=new MapInfoPacket(); map.name="Lost Halls";
            log.observe(PacketType.MAPINFO.getIndex(),30,map,"decoded",0); store.flush();
            String first=log.currentVisitId();
            Path file=directory.resolve(store.currentId()).resolve("runs").resolve(SessionStore.checkpointName(first)+".json");
            java.nio.file.attribute.FileTime sentinel=java.nio.file.attribute.FileTime.fromMillis(1000);
            Files.setLastModifiedTime(file,sentinel);
            CreateSuccessPacket create=new CreateSuccessPacket(); create.objectId=42; create.charId=3;
            for (long elapsed : new long[] {2000,4000,6000,8000,9999}) {
                synchronized (log) { clock.set(1000+elapsed); log.observe(PacketType.CREATE_SUCCESS.getIndex(),12,create,"decoded",0); }
                store.flush();
                assertEquals("Changing revisions do not bypass the interval",sentinel,Files.getLastModifiedTime(file));
                assertEquals(1,store.readCheckpoint(store.currentId(),"runs",first,ActivityJournal.Visit.class).get().frames);
            }
            synchronized (log) {
                clock.set(1000+DiscoveryLog.ACTIVE_RUN_INTERVAL_MILLIS);
                log.observe(PacketType.CREATE_SUCCESS.getIndex(),12,create,"decoded",0);
            }
            store.flush();
            assertNotEquals(sentinel,Files.getLastModifiedTime(file));
            assertEquals(7,store.readCheckpoint(store.currentId(),"runs",first,ActivityJournal.Visit.class).get().frames);
            Files.setLastModifiedTime(file,sentinel);
            clock.incrementAndGet(); log.observe(PacketType.CREATE_SUCCESS.getIndex(),12,create,"decoded",0); store.flush();
            assertEquals("The interval restarts after each write",sentinel,Files.getLastModifiedTime(file));
            clock.incrementAndGet(); map=new MapInfoPacket(); map.name="Ice Citadel";
            log.observe(PacketType.MAPINFO.getIndex(),30,map,"decoded",0); store.flush();
            String second=log.currentVisitId(); assertNotEquals(first,second);
            assertEquals("Ice Citadel",store.readCheckpoint(store.currentId(),"runs",second,ActivityJournal.Visit.class).get().map);
            ActivityJournal.Visit finished=store.readCheckpoint(store.currentId(),"runs",first,ActivityJournal.Visit.class).get();
            assertTrue("Final writes bypass the interval",finished.ended>0); assertEquals(8,finished.frames);
            NotificationPacket victory=new NotificationPacket(); victory.effect=NotificationEffectType.Victory;
            log.observe(PacketType.NOTIFICATION.getIndex(),2,victory,"decoded",0); log.close(); store.flush();
            ActivityJournal.Visit completed=store.readCheckpoint(store.currentId(),"runs",second,ActivityJournal.Visit.class).get();
            assertEquals("Completed",completed.runStatus()); assertTrue(completed.ended>0);
        } finally { log.close(); store.close(); }
    }
    @Test public void activeAndClosedRunCheckpointsUseTheAppSessionAndKeepFinalDamage()throws Exception{
        Path directory=Files.createTempDirectory("session-capture");SessionStore store=new SessionStore(directory,true,"first-build");String session=store.currentId();
        DiscoveryLog log=new DiscoveryLog(null);log.attachHistory(store);
        MapInfoPacket map=new MapInfoPacket();map.name="Ice Citadel";log.observe(PacketType.MAPINFO.getIndex(),30,map,"decoded",0);
        Entity player=new Entity(null,7,0);player.objectType=99999;StatData name=new StatData();name.stringStatValue="Alice,metadata";player.stat.set(StatType.NAME_STAT,name);
        log.inspectPlayer(player);long started=log.activityHistory().visits.get(0).started;
        log.inspectDamage(player,100,started+1000);store.flush();
        assertEquals(100,store.read(session,"runs",ActivityJournal.Visit.class).get(0).totalDamage);
        ActivityJournal.State active=new ActivityJournal.State();active.visits.addAll(store.read(session,"runs",ActivityJournal.Visit.class));
        assertEquals("In progress",DiscoveryLog.historyView(active).activityHistory().visits.get(0).runStatus());
        assertEquals("Area entered",store.read(session,"timeline",ActivityJournal.Entry.class).get(0).kind);
        log.inspectDamage(player,900,started+2000);
        NotificationPacket victory=new NotificationPacket();victory.effect=NotificationEffectType.Victory;
        log.observe(PacketType.NOTIFICATION.getIndex(),2,victory,"decoded",0);
        log.close();store.close();
        SessionStore reopened=new SessionStore(directory,true,"second-build");
        try{
            ActivityJournal.Visit saved=reopened.read(session,"runs",ActivityJournal.Visit.class).get(0);
            assertEquals(1000,saved.totalDamage);assertEquals("Completed",saved.runStatus());assertTrue(saved.ended>0);
            assertEquals(1,reopened.read(session,"runs",ActivityJournal.Visit.class).size());
            assertTrue(reopened.read(reopened.currentId(),"runs",ActivityJournal.Visit.class).isEmpty());
        }finally{reopened.close();}
    }
}
