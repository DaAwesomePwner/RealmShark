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
import tomato.gui.dps.CombatAutosave;
import tomato.gui.dps.CombatSummaries;
import tomato.history.SessionStore;
import tomato.history.encounter.CombatFacts;
import tomato.history.encounter.CombatRecord;
import tomato.history.encounter.CombatSettings;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    @Test public void liveSnapshotCarriesTheInProgressEncountersEntryFrozenContext() {
        assertNull("No encounter entered yet: identity unknown, not unlinked", DpsSnapshot.capture(data).context);
        MapInfoPacket map = enter("Lost Halls", "decoded");
        VisitRef entered = log.visitForMap(map);
        data.setUserId(21, 7, "AAAAAA=="); spawn(21);
        EncounterContext live = DpsSnapshot.capture(data).context;
        assertEquals(entered, live.visit); assertEquals(Integer.valueOf(21), live.localPlayerObjectId);
        log.boundary();
        assertEquals("A boundary after entry does not rewrite the live encounter's entry link", entered, DpsSnapshot.capture(data).context.visit);
        enter("Ice Citadel", "trailing-bytes");
        EncounterContext unlinked = DpsSnapshot.capture(data).context;
        assertNotNull(unlinked); assertNull(unlinked.visit); assertFalse(unlinked.linked());
        assertEquals("The saved recording keeps the live link", entered, data.dpsData.get(0).getEncounterContext().visit);
    }

    @Test public void aMapChangeSavesTheClosedFightWithItsExactVisitAndLocalId() throws Exception {
        CombatAutosave autosave = savingHistory();
        VisitRef entered = log.visitForMap(enter("Lost Halls", "decoded"));
        data.setUserId(21, 7, "AAAAAA=="); spawn(21);
        data.setTime(1_000); hit(21, 500, 300); hit(21, 501, 100);
        enter("Ice Citadel", "decoded");
        assertEquals("Handed off once clear() returned: the capture's hit list is already reset", List.of(0), handOffHits);
        assertTrue(autosave.flush(10_000));
        List<CombatRecord> saved = saved();
        assertEquals(1, saved.size());
        CombatRecord record = saved.get(0);
        assertEquals(data.dpsData.get(0).getRecordingId(), record.recordingId);
        assertEquals("The exact visit frozen at entry", entered, record.visit());
        assertEquals(Integer.valueOf(21), record.localObjectId);
        assertEquals(400, record.local().damage);
        assertEquals("Lost Halls", record.map);
        assertNotNull("Its detail is saved beside it", CombatFacts.detail(store, store.currentId(), record.recordingId));
    }

    @Test public void captureStopSavesTheOpenFightOnce() throws Exception {
        CombatAutosave autosave = savingHistory();
        VisitRef entered = log.visitForMap(enter("Lost Halls", "decoded"));
        data.setUserId(21, 7, "AAAAAA=="); spawn(21);
        data.setTime(1_000); hit(21, 500, 300);
        Entity player = data.player, enemy = data.entityList.get(500);
        TomatoData.MyInfoIdentity identity = data.myInfoIdentity();
        assertTrue(data.isCurrentMyInfoSnapshot(identity, player, null, TomatoData.PetAvailability.UNKNOWN));
        data.captureTerminated();
        assertEquals(1, data.dpsData.size());
        assertEquals(List.of(0), handOffHits);
        assertEquals("The live meter starts empty", 0, data.getEntityHitList().length);
        assertSame("The live world stays: the local player", player, data.player);
        assertSame("…the objects in view", enemy, data.entityList.get(500));
        assertSame(player, data.playerList.get(21));
        assertSame("My Info's source of the local player survives the stop", identity, data.myInfoIdentity());
        assertTrue(data.isCurrentMyInfoSnapshot(identity, player, null, TomatoData.PetAvailability.UNKNOWN));
        data.captureTerminated();   // another stop, or the start-failure path: nothing is open
        enter("Nexus", "decoded");   // moving on with nothing recorded since the stop adds nothing
        assertEquals("Closed once", 1, data.dpsData.size());
        assertTrue(autosave.flush(10_000));
        List<CombatRecord> saved = saved();
        assertEquals(1, saved.size());
        assertEquals(entered, saved.get(0).visit());
        assertEquals(Integer.valueOf(21), saved.get(0).localObjectId);
        assertEquals(300, saved.get(0).local().damage);
    }

    @Test public void aRestartInTheSameAreaGivesASecondUnlinkedRecording() throws Exception {
        CombatAutosave autosave = savingHistory();
        VisitRef entered = log.visitForMap(enter("Lost Halls", "decoded"));
        data.setUserId(21, 7, "AAAAAA=="); spawn(21);
        data.setTime(1_000); hit(21, 500, 300); incoming(21, 500, 40);
        data.captureTerminated();
        // Capture restarts in the same area: objects already in view are not announced again, and need not be.
        data.setTime(2_000); hit(21, 500, 200); incoming(21, 500, 15);
        assertEquals("The closed recording is detached: hits after the restart never change it", 300,
            CombatSummaries.build(data.dpsData.get(0)).record().totalDamage);
        assertNotSame(data.entityList.get(500), data.dpsData.get(0).hitList.get(500));
        enter("Nexus", "decoded");
        assertEquals(2, data.dpsData.size());
        assertTrue(autosave.flush(10_000));
        Map<String, CombatRecord> saved = new HashMap<>();
        for (CombatRecord record : saved()) saved.put(record.recordingId, record);
        CombatRecord first = saved.get(data.dpsData.get(0).getRecordingId()), second = saved.get(data.dpsData.get(1).getRecordingId());
        assertEquals(entered, first.visit());
        assertEquals("The first recording is frozen at the stop", 300, first.totalDamage);
        assertEquals(300, first.local().damage); assertEquals(Long.valueOf(40), first.local().taken);
        assertNull("The remainder is not linked to the visit", second.visit());
        assertEquals("A known player's hit on a known enemy is attributed without re-announcement", Integer.valueOf(21), second.localObjectId);
        assertEquals(200, second.totalDamage); assertEquals(0, second.unattributedDamage);
        assertEquals(200, second.local().damage);
        assertEquals("Incoming damage restarts with the remainder", Long.valueOf(15), second.local().taken);
        assertNotEquals(first.recordingId, second.recordingId);
    }

    private final List<Integer> handOffHits = new ArrayList<>();
    private final List<CombatAutosave> autosaves = new ArrayList<>();
    @After public void closeAutosaves() { autosaves.forEach(CombatAutosave::close); }

    /** Replaces the read-only history with a writable one whose closed recordings a combat autosave saves (summaries only). */
    private CombatAutosave savingHistory() throws Exception {
        log.close(); store.close();
        store = new SessionStore(temp.newFolder("saved").toPath(), true, "synthetic");
        log = new DiscoveryLog(temp.newFolder("saved-discovery").toPath());
        log.setSaving(false);
        log.attachHistory(store);
        data = new TomatoData();
        data.visitSource(log::visitForMap);
        CombatAutosave autosave = new CombatAutosave(store, () -> new CombatSettings.Values(false, 30, null), System::currentTimeMillis);
        autosaves.add(autosave);
        data.closedEncounters(closed -> { handOffHits.add(data.getEntityHitList().length); autosave.submit(closed); });
        return autosave;
    }
    private List<CombatRecord> saved() throws Exception {
        store.flush();
        List<CombatRecord> read = new ArrayList<>();
        CombatFacts.read(store, store.catalog(), store.currentId(), read::add);
        return read;
    }
    private void hit(int attacker, int enemy, int damage) {
        DamagePacket packet = new DamagePacket();
        packet.targetId = enemy; packet.objectId = attacker; packet.damageAmount = damage; packet.bulletId = 1;
        data.damage(packet);
    }
    /** An enemy's hit on a player, as a DAMAGE packet reports it (no player attacker). */
    private void incoming(int target, int enemy, int damage) {
        DamagePacket packet = new DamagePacket();
        packet.targetId = target; packet.objectId = enemy; packet.damageAmount = damage; packet.bulletId = 2;
        data.damage(packet);
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
