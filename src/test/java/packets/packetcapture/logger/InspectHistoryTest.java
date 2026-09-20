package packets.packetcapture.logger;

import org.junit.Test;
import packets.PacketType;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import tomato.backend.data.Entity;
import tomato.backend.data.InspectSnapshot;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class InspectHistoryTest {
    private static void map(ActivityJournal journal, String name, long time) {
        MapInfoPacket map = new MapInfoPacket(); map.name = name;
        journal.observe(map, PacketType.MAPINFO, "decoded", time, Collections.emptyMap());
    }
    private static Entity player(int id, String name, int weapon) {
        Entity player = new Entity(null, id, 0); player.objectType = 782;
        player.baseStats = new int[]{500, 300, 75, 25, 50, 70, 40, 60};
        stat(player, StatType.NAME_STAT, 0, name);
        stat(player, StatType.INVENTORY_0_STAT, weapon, null);
        stat(player, StatType.UNIQUE_DATA_STRING, 0, "captured-enchants");
        stat(player, StatType.ACCOUNT_ID_STAT, 0, "ACCOUNT_MUST_NOT_BE_SAVED");
        return player;
    }
    private static void stat(Entity entity, StatType type, int value, String text) {
        StatData stat = new StatData(); stat.statType = type; stat.statTypeNum = type.get(); stat.statValue = value; stat.stringStatValue = text;
        entity.stat.set(type, stat);
    }
    private static Entity onlyPlayer(ActivityJournal.Visit visit) { return visit.inspectedPlayers.values().iterator().next().toEntity(); }

    @Test public void loadoutsAreDetachedLastObservationsScopedToEachVisitAndPinnedRevision() {
        ActivityJournal journal = new ActivityJournal();
        Entity player = player(7, "Player", 10);
        assertFalse(journal.inspectPlayer(new InspectSnapshot(player)));
        map(journal, "Nexus", 1000); assertFalse(journal.inspectPlayer(new InspectSnapshot(player)));
        map(journal, "Ice Citadel", 2000); assertTrue(journal.inspectPlayer(new InspectSnapshot(player)));
        ActivityJournal.ViewSnapshot pinned = journal.viewSnapshot(ActivityJournal.View.INSPECT, "", null);
        String firstId = pinned.selectedVisit;
        stat(player, StatType.INVENTORY_0_STAT, 99, null); player.baseStats[0] = 600;
        assertTrue(journal.inspectPlayer(new InspectSnapshot(player)));
        assertFalse(journal.inspectPlayer(new InspectSnapshot(player)));
        assertEquals(10, onlyPlayer(pinned.fullHistory().visits.get(1)).stat.get(StatType.INVENTORY_0_STAT).statValue);
        map(journal, "Ice Citadel", 3000);
        stat(player, StatType.INVENTORY_0_STAT, 123, null); journal.inspectPlayer(new InspectSnapshot(player));
        ActivityJournal.ViewSnapshot old = journal.viewSnapshot(ActivityJournal.View.INSPECT, firstId, null);
        Entity saved = onlyPlayer(old.data.visits.get(1));
        assertEquals(99, saved.stat.get(StatType.INVENTORY_0_STAT).statValue); assertEquals(600, saved.baseStats[0]);
        assertTrue("Only the selected roster is copied into the view", old.data.visits.get(2).inspectedPlayers.isEmpty());
        assertEquals(1, old.data.visits.get(2).inspectedPlayerCount);
        saved.baseStats[0] = 0;
        assertEquals(600, onlyPlayer(journal.snapshot().visits.get(1)).baseStats[0]);
        assertFalse(new com.google.gson.Gson().toJson(journal.snapshot()).contains("ACCOUNT_MUST_NOT_BE_SAVED"));
        journal.boundary(4000, "Capture stopped");
        assertFalse(journal.inspectPlayer(new InspectSnapshot(player)));
        assertEquals(123, onlyPlayer(journal.snapshot().visits.get(2)).stat.get(StatType.INVENTORY_0_STAT).statValue);
    }

    @Test public void reappearingPlayersKeepTheirLastLoadoutAndAnonymousRowsAreResolved() {
        ActivityJournal journal = new ActivityJournal(); map(journal, "Ocean Trench", 1000);
        Entity player = player(1, "", 10); journal.inspectPlayer(new InspectSnapshot(player));
        stat(player, StatType.NAME_STAT, 0, "Named"); journal.inspectPlayer(new InspectSnapshot(player));
        Entity returned = player(2, "Named", 20); journal.inspectPlayer(new InspectSnapshot(returned));
        assertEquals(1, journal.snapshot().visits.get(0).inspectedPlayers.size());
        assertEquals(20, onlyPlayer(journal.snapshot().visits.get(0)).stat.get(StatType.INVENTORY_0_STAT).statValue);
    }

    @Test public void runLoadoutsSurviveCheckpointReloadAndLegacyRunsRemainEmpty() throws Exception {
        Path dir = Files.createTempDirectory("inspect-history");
        ActivityJournal journal = new ActivityJournal(); map(journal, "Ocean Trench", 1000);
        journal.inspectPlayer(new InspectSnapshot(player(1, "Saved", 100)));
        ActivityStore store = new ActivityStore(dir); store.offer(journal.snapshot()); store.close(); assertEquals("", store.error());
        ActivityStore reload = new ActivityStore(dir);
        ActivityJournal restored = new ActivityJournal(reload.load()); reload.close();
        Entity saved = onlyPlayer(restored.snapshot().visits.get(0));
        assertEquals("Saved", saved.getStatName()); assertEquals(100, saved.stat.get(StatType.INVENTORY_0_STAT).statValue);
        assertEquals("captured-enchants", saved.stat.get(StatType.UNIQUE_DATA_STRING).stringStatValue);
        assertNull(saved.stat.get(StatType.INVENTORY_1_STAT));
        assertTrue(restored.snapshot().visits.get(0).ended > 0);
        com.google.gson.JsonObject legacy = new com.google.gson.Gson().toJsonTree(journal.snapshot()).getAsJsonObject();
        legacy.getAsJsonArray("visits").get(0).getAsJsonObject().remove("inspectedPlayers");
        legacy.getAsJsonArray("visits").get(0).getAsJsonObject().remove("inspectedPlayerCount");
        Files.write(dir.resolve("activity-history.json"), legacy.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ActivityStore old = new ActivityStore(dir);
        assertTrue(old.load().visits.get(0).inspectedPlayers.isEmpty()); old.close();
    }

    @Test public void captureUpdatesKeepLastLoadoutAfterPlayerDropsAndMapChanges() {
        DiscoveryLog log = DiscoveryLog.INSTANCE;
        boolean enabled = log.isEnabled(), saving = log.isSaving();
        log.setSaving(false); log.clear(); log.setEnabled(true);
        try {
            MapInfoPacket map = new MapInfoPacket(); map.name = map.displayName = "Ocean Trench";
            log.observe(PacketType.MAPINFO.getIndex(), 30, map, "decoded", 0);
            tomato.backend.data.TomatoData data = new tomato.backend.data.TomatoData();
            data.setNewRealm(map); data.setUserId(7, 1, "AAAAAA==");
            Entity source = player(7, "Captured", 10);
            stat(source, StatType.MAX_HP_STAT, 500, null); stat(source, StatType.MAX_HP_BOOST_STAT, 0, null);
            packets.data.ObjectData object = new packets.data.ObjectData(); object.objectType = 782;
            object.status = status(source);
            packets.incoming.UpdatePacket update = new packets.incoming.UpdatePacket();
            update.tiles = new packets.data.GroundTileData[0]; update.drops = new int[0]; update.newObjects = new packets.data.ObjectData[]{object};
            data.update(update);
            stat(source, StatType.INVENTORY_0_STAT, 20, null); stat(source, StatType.MAX_HP_STAT, 600, null);
            packets.incoming.NewTickPacket tick = new packets.incoming.NewTickPacket(); tick.status = new packets.data.ObjectStatusData[]{status(source)};
            data.updateNewTick(tick);
            packets.incoming.DamagePacket outgoing = new packets.incoming.DamagePacket();
            outgoing.targetId = 999; outgoing.objectId = 7; outgoing.damageAmount = 600;
            data.timePc = System.currentTimeMillis(); data.damage(outgoing);
            data.timePc += 1000; outgoing.damageAmount = 400; data.damage(outgoing);
            packets.incoming.DamagePacket incoming = new packets.incoming.DamagePacket();
            incoming.targetId = 7; incoming.objectId = 7; incoming.damageAmount = 900; data.damage(incoming);
            update.newObjects = new packets.data.ObjectData[0]; update.drops = new int[]{7}; data.update(update);
            stat(source, StatType.INVENTORY_0_STAT, 99, null); tick.status = new packets.data.ObjectStatusData[]{status(source)}; data.updateNewTick(tick);
            map.name = map.displayName = "Nexus"; log.observe(PacketType.MAPINFO.getIndex(), 30, map, "decoded", 0); data.setNewRealm(map);
            Entity saved = onlyPlayer(log.activityHistory().visits.get(0));
            assertEquals(20, saved.stat.get(StatType.INVENTORY_0_STAT).statValue);
            assertEquals(600, saved.baseStats[0]);
            assertEquals("Captured", saved.getStatName());
            assertEquals("Incoming player damage must not inflate outgoing totals", 1000, log.activityHistory().visits.get(0).totalDamage);
            assertEquals(Double.valueOf(1000), log.activityHistory().visits.get(0).dps(1000L));
            assertTrue(log.activityHistory().visits.get(1).inspectedPlayers.isEmpty());
        } finally { log.clear(); log.setEnabled(enabled); log.setSaving(saving); }
    }

    private static packets.data.ObjectStatusData status(Entity entity) {
        packets.data.ObjectStatusData status = new packets.data.ObjectStatusData(); status.objectId = entity.id;
        status.pos = new packets.data.WorldPosData();
        status.stats = Arrays.stream(StatType.values()).map(entity.stat::get).filter(Objects::nonNull).toArray(StatData[]::new);
        return status;
    }
}
