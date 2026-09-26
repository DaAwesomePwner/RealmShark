package tomato.backend.data;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.PacketType;
import packets.data.*;
import packets.data.enums.StatType;
import packets.incoming.*;
import packets.packetcapture.logger.ActivityJournal;
import packets.packetcapture.logger.DiscoveryLog;
import packets.reader.BufferReader;
import tomato.history.SessionStore;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.Assert.*;

/** Producer-order fixtures for COMBAT-3: encounter identity is frozen at entry from the exact MAPINFO. */
public class EncounterIdentityTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @BeforeClass public static void initializeSwing() throws Exception { javax.swing.SwingUtilities.invokeAndWait(() -> {}); }

    private SessionStore store;
    private DiscoveryLog log;
    private TomatoData data;

    @Before public void setUp() throws Exception {
        store = new SessionStore(temp.newFolder("history").toPath(), false, "synthetic");
        log = new DiscoveryLog(temp.newFolder("discovery").toPath());
        log.setSaving(false);
        log.attachHistory(store);
        data = new TomatoData();
        data.visitSource(log::visitForMap);
    }

    @After public void tearDown() {
        log.close(); store.close();
    }

    @Test public void consecutiveSameNameVisitsReceiveDistinctExactReferences() {
        enter("Lost Halls", "decoded");
        enter("Lost Halls", "decoded");
        enter("Ice Citadel", "decoded");
        List<ActivityJournal.Visit> visits = log.activityHistory().visits;
        assertEquals(3, visits.size());
        assertEquals(2, data.dpsData.size());
        VisitRef first = data.dpsData.get(0).getEncounterContext().visit, second = data.dpsData.get(1).getEncounterContext().visit;
        assertEquals(new VisitRef(store.currentId(), visits.get(0).id), first);
        assertEquals(new VisitRef(store.currentId(), visits.get(1).id), second);
        assertNotEquals("Same dungeon name must never share or swap a visit", first, second);
        assertEquals("Session ID is the history session, not the journal UUID", store.currentId(), first.sessionId);
        assertFalse(first.visitId.startsWith(store.currentId()));
    }

    @Test public void outgoingEncounterKeepsItsEntryReferenceWhenTheIncomingMapReusesThePacketObject() {
        MapInfoPacket map = map("Lost Halls");
        log.observe(PacketType.MAPINFO.getIndex(), 30, map, "decoded", 0); data.setNewRealm(map);
        map.name = map.displayName = "Lost Halls";
        log.observe(PacketType.MAPINFO.getIndex(), 30, map, "decoded", 0); data.setNewRealm(map);
        enter("Nexus", "decoded");
        List<ActivityJournal.Visit> visits = log.activityHistory().visits;
        assertEquals(visits.get(0).id, data.dpsData.get(0).getEncounterContext().visit.visitId);
        assertEquals(visits.get(1).id, data.dpsData.get(1).getEncounterContext().visit.visitId);
    }

    @Test public void pausedCollectionAndConnectionBoundaryYieldNoLink() {
        log.setEnabled(false);
        enter("Lost Halls", "decoded");
        log.setEnabled(true);
        MapInfoPacket observed = map("Ice Citadel");
        log.observe(PacketType.MAPINFO.getIndex(), 30, observed, "decoded", 0);
        log.setEnabled(false); log.setEnabled(true);
        assertNull("A pause after the MAPINFO invalidates its provenance", log.visitForMap(observed));
        data.setNewRealm(observed);
        MapInfoPacket reconnect = map("Lost Halls");
        log.observe(PacketType.MAPINFO.getIndex(), 30, reconnect, "decoded", 0);
        log.boundary();
        data.setNewRealm(reconnect);
        enter("Nexus", "decoded");
        assertEquals(3, data.dpsData.size());
        for (DpsData encounter : data.dpsData) {
            assertNotNull("New recordings carry a context even when unlinked", encounter.getEncounterContext());
            assertNull(encounter.getEncounterContext().visit);
            assertFalse(encounter.getEncounterContext().linked());
        }
    }

    @Test public void failedTrailingAndUnobservedMapInfoYieldNoLink() {
        MapInfoPacket linked = enter("Lost Halls", "decoded");
        assertNotNull(log.visitForMap(linked));
        BufferReader reader = new BufferReader(ByteBuffer.wrap(new byte[]{1, 2, 3}));
        log.decodeFailure(PacketType.MAPINFO.getIndex(), 30, reader, new IllegalArgumentException("synthetic"));
        assertNull("A failed MAPINFO ends the visit and its provenance", log.visitForMap(linked));
        enter("Ice Citadel", "trailing-bytes");
        data.setNewRealm(map("Lost Halls")); // Never observed by the collector.
        enter("Nexus", "decoded");
        assertEquals(3, data.dpsData.size());
        assertEquals("The encounter entered before the failure keeps its entry-frozen link",
            log.activityHistory().visits.get(0).id, data.dpsData.get(0).getEncounterContext().visit.visitId);
        assertNull(data.dpsData.get(1).getEncounterContext().visit);
        assertNull(data.dpsData.get(2).getEncounterContext().visit);
    }

    @Test public void clearedCollectorOrMissingHistoryYieldNoLink() throws Exception {
        MapInfoPacket map = enter("Lost Halls", "decoded");
        log.clear();
        assertNull(log.visitForMap(map));
        try (DiscoveryLog detached = new DiscoveryLog(temp.newFolder("detached").toPath())) {
            detached.setSaving(false);
            MapInfoPacket other = map("Lost Halls");
            detached.observe(PacketType.MAPINFO.getIndex(), 30, other, "decoded", 0);
            assertFalse(detached.currentVisitId().isEmpty());
            assertNull("Without a history session there is no resolvable reference", detached.visitForMap(other));
        }
    }

    @Test public void localObjectIdIsCapturedBeforeResetAndOnlyWhenItAgreesWithTheCapturedPlayer() {
        enter("Lost Halls", "decoded");
        data.setUserId(21, 7, "AAAAAA=="); spawn(21);
        assertNotNull(data.player);
        enter("Ice Citadel", "decoded");
        assertEquals(Integer.valueOf(21), data.dpsData.get(0).getEncounterContext().localPlayerObjectId);

        data.setUserId(21, 7, "AAAAAA=="); spawn(21);
        data.setUserId(22, 7, "AAAAAA=="); spawn(22);
        enter("Lost Halls", "decoded");
        assertNull("Two local objects in one encounter are ambiguous", data.dpsData.get(1).getEncounterContext().localPlayerObjectId);

        data.setUserId(23, 7, "AAAAAA=="); // CREATE without a captured player object.
        enter("Nexus", "decoded");
        EncounterContext missing = data.dpsData.get(2).getEncounterContext();
        assertNull(missing.localPlayerObjectId);
        assertNotNull("Identity absence does not remove the verified visit", missing.visit);
    }

    private MapInfoPacket enter(String name, String outcome) {
        MapInfoPacket map = map(name);
        log.observe(PacketType.MAPINFO.getIndex(), 30, map, outcome, "decoded".equals(outcome) ? 0 : 4);
        data.setNewRealm(map);
        return map;
    }
    private static MapInfoPacket map(String name) {
        MapInfoPacket map = new MapInfoPacket(); map.name = map.displayName = name; return map;
    }
    private void spawn(int id) {
        UpdatePacket spawn = new UpdatePacket(); spawn.tiles = new GroundTileData[0]; spawn.drops = new int[0];
        ObjectData object = new ObjectData(); object.objectType = 782;
        ObjectStatusData status = new ObjectStatusData(); status.objectId = id; status.pos = new WorldPosData();
        StatData level = new StatData(); level.statType = StatType.LEVEL_STAT; level.statTypeNum = StatType.LEVEL_STAT.get(); level.statValue = 20;
        status.stats = new StatData[]{level}; object.status = status;
        spawn.newObjects = new ObjectData[]{object};
        data.update(spawn);
    }
}
