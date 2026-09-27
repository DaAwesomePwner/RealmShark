package packets.packetcapture.logger;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.PacketType;
import packets.incoming.MapInfoPacket;
import tomato.history.SessionStore;
import static org.junit.Assert.*;

/** Home's "Now" elapsed time starts at the exact visit start the Runs journal records. */
public class CurrentVisitStartTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void currentVisitCarriesTheJournalsStartTime() throws Exception {
        SessionStore store = new SessionStore(temp.newFolder("history").toPath(), true, "synthetic");
        DiscoveryLog log = new DiscoveryLog(temp.newFolder("discovery").toPath());
        try {
            log.setSaving(false); log.attachHistory(store);
            assertNull("No visit before the first map", log.currentVisit());
            long before = System.currentTimeMillis();
            MapInfoPacket map = new MapInfoPacket(); map.name = map.displayName = "Lost Halls";
            log.observe(PacketType.MAPINFO.getIndex(), 30, map, "decoded", 0);
            long after = System.currentTimeMillis();
            DiscoveryLog.CurrentVisit visit = log.currentVisit();
            assertNotNull(visit);
            assertTrue(visit.started >= before && visit.started <= after);
            ActivityJournal.Visit recorded = log.activityHistory().visits.stream()
                .filter(v -> v.id.equals(visit.visit.visitId)).findFirst().get();
            assertEquals("The same start the Runs page records", recorded.started, visit.started);
            assertEquals("Stable while the visit lasts", visit.started, log.currentVisit().started);
        } finally { log.close(); store.close(); }
    }
}
