package tomato.history;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.PacketType;
import packets.incoming.MapInfoPacket;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.history.link.VisitRef;

import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.List;

import static org.junit.Assert.*;

/** STAT-2 producer hook: fame samples carry only the exact visit active when they were produced. */
public class FameVisitHookTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void samplesRecordTheExactActiveVisitAndLegacyJsonReadsAsNotRecorded() throws Exception {
        Field storeField = AppHistory.class.getDeclaredField("store"); storeField.setAccessible(true);
        Object previous = storeField.get(null);
        java.util.function.Supplier<DiscoveryLog.CurrentVisit> previousVisit = AppHistory.currentVisit;
        SessionStore store = new SessionStore(temp.newFolder("history").toPath(), true, "synthetic");
        DiscoveryLog log = new DiscoveryLog(temp.newFolder("discovery").toPath());
        try {
            log.setSaving(false); log.attachHistory(store);
            storeField.set(null, store); AppHistory.currentVisit = log::currentVisit;
            AppHistory.fame(7001, 10, 1000, "Wizard"); // No visit yet.
            MapInfoPacket map = new MapInfoPacket(); map.name = map.displayName = "Lost Halls";
            log.observe(PacketType.MAPINFO.getIndex(), 30, map, "decoded", 0);
            String visitId = log.currentVisitId();
            AppHistory.fame(7001, 20, 2000, "Wizard");
            log.setEnabled(false);
            AppHistory.fame(7001, 30, 3000, "Wizard"); // Paused collection: not recorded.
            log.setEnabled(true);
            AppHistory.fame(7001, 40, 4000, "Wizard"); // Pause ended the visit; no new MAPINFO yet.
            store.flush();
            List<AppHistory.FameSample> samples = store.read(store.currentId(), "fame", AppHistory.FameSample.class);
            assertEquals(4, samples.size());
            assertNull(samples.get(0).visit()); assertNull(samples.get(0).map);
            assertEquals(new VisitRef(store.currentId(), visitId), samples.get(1).visit());
            assertEquals("Lost Halls", samples.get(1).map);
            assertNull(samples.get(2).visit()); assertNull(samples.get(3).visit());

            Path raw = store.directory().resolve(store.currentId()).resolve("fame.jsonl");
            String first = new String(Files.readAllBytes(raw), java.nio.charset.StandardCharsets.UTF_8).split("\n")[0];
            assertFalse("Unlinked samples keep the legacy JSON shape", first.contains("visit") || first.contains("map"));
            AppHistory.FameSample legacy = SessionStore.JSON.fromJson("{\"character\":7,\"fame\":5,\"time\":9,\"className\":\"Priest\"}", AppHistory.FameSample.class);
            assertNull(legacy.visit()); assertNull(legacy.map); assertEquals(5, legacy.fame);

            AppHistory.FameSample foreign = new AppHistory.FameSample(7, 1, 1, "Priest", new VisitRef("other", "x:1"), "Lost Halls");
            assertEquals("other", foreign.visit().sessionId);
            assertNull("A map is never kept without its exact visit", new AppHistory.FameSample(7, 1, 1, "Priest", null, "Lost Halls").map);
        } finally {
            AppHistory.currentVisit = previousVisit; storeField.set(null, previous);
            log.close(); store.close();
        }
    }
}
