package tomato.gui.logging;

import com.google.gson.Gson;
import org.junit.Test;
import packets.PacketType;
import packets.data.*;
import packets.incoming.*;
import packets.packetcapture.logger.*;
import java.util.*;
import static org.junit.Assert.*;

public class LoggingQueryTest {
    @Test public void semanticNestedStatSearchAndCorrelatedFacetsUseOnlyRetainedDeltas() {
        try (DiscoveryLog log = fixture()) {
            DiscoveryLog.Event initial=log.snapshot().events.get(0), changed=log.snapshot().events.get(1);
            LoggingQuery q=new LoggingQuery(); q.text="HP_STAT";
            assertTrue(q.matches(changed,"")); // Name exists only in the nested stat projection.
            q.text="777"; assertTrue(q.matches(changed,"")); // Object ID, not a displayed event column.
            q.text="900"; assertTrue(q.matches(changed,"")); // Prior HP value.
            q.text="not a known stat"; assertFalse(q.matches(changed,""));
            q.text="["; assertTrue(q.matches(changed,"")); // Literal bracket in the stat ID, never regex.
            q.text=".*"; assertFalse(q.matches(changed,""));
            q.text=""; q.stat=1; q.object=888; assertFalse(q.matches(changed,""));
            q.object=777; q.changed=true;
            assertTrue(q.matches(changed,"")); assertFalse(q.matches(initial,""));
            q.area=changed.area+1; assertFalse(q.matches(changed,""));
            q.area=changed.area; q.packet="UPDATE"; assertFalse(q.matches(changed,""));
            q.packet="NEWTICK"; q.outcome="decode-error"; assertFalse(q.matches(changed,""));
            q.outcome="decoded"; assertTrue(q.matches(changed,""));
            // Secondary-only changes qualify, but initial values with missing baselines do not.
            DiscoveryLog.Delta delta=new Gson().fromJson("{objectId:777,statId:1,value:900,previousValue:900,secondary:2,previousSecondary:1}", DiscoveryLog.Delta.class);
            assertTrue(q.matchesDelta(delta));
        }
    }

    @Test public void packetFieldKeysAndNestedPathsAreVerifiedAgainstTheDecoderCatalog() {
        try (DiscoveryLog log = fixture()) {
            List<DiscoveryCatalog.SchemaField> catalog=DiscoveryCatalog.fields();
            List<String> paths=LoggingQuery.fieldPaths(log.snapshot().events.get(1),catalog);
            assertTrue(paths.contains("status[].stats[].statTypeNum"));
            assertTrue(paths.contains("status[].stats[].statValue"));
            assertTrue(paths.contains("status[].stats[].statValueTwo"));
            UpdatePacket update=new UpdatePacket(); ObjectData object=new ObjectData();
            object.status=status(777,1,700); update.newObjects=new ObjectData[]{object};
            log.observe(PacketType.UPDATE.getIndex(),30,update,"decoded",0);
            paths=LoggingQuery.fieldPaths(log.snapshot().events.get(2),catalog);
            assertTrue(paths.contains("newObjects[].status.stats[].statValue"));
            Set<String> keys=new HashSet<>();
            for (DiscoveryCatalog.SchemaField f:catalog) assertTrue("Duplicate catalog key: " + LoggingQuery.fieldKey(f),keys.add(LoggingQuery.fieldKey(f)));
            DiscoveryLog.Event unknown=new Gson().fromJson("{packet:'UNKNOWN', values:{arbitrary:1},statChanges:[]}",DiscoveryLog.Event.class);
            assertTrue(LoggingQuery.fieldPaths(unknown,catalog).isEmpty());
        }
    }

    static DiscoveryLog fixture() {
        DiscoveryLog log=new DiscoveryLog(null); log.setSaving(false); log.setSampleMillis(0);
        tick(log,900,100); tick(log,800,90); return log;
    }
    static void tick(DiscoveryLog log,int hp,int mp) {
        NewTickPacket tick=new NewTickPacket(); tick.status=new ObjectStatusData[]{status(777,1,hp),status(888,4,mp)};
        log.observe(PacketType.NEWTICK.getIndex(),30,tick,"decoded",0);
    }
    static ObjectStatusData status(int object,int id,int value) {
        ObjectStatusData status=new ObjectStatusData(); status.objectId=object;
        StatData stat=new StatData(); stat.statTypeNum=id; stat.statValue=value; status.stats=new StatData[]{stat}; return status;
    }
}
