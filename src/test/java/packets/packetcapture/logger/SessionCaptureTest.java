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
