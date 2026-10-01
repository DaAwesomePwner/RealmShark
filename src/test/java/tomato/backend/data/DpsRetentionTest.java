package tomato.backend.data;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import packets.Packet;
import packets.data.ObjectData;
import packets.data.ObjectStatusData;
import packets.incoming.CreateSuccessPacket;
import packets.incoming.MapInfoPacket;
import packets.incoming.UpdatePacket;
import packets.outgoing.EnemyHitPacket;
import packets.outgoing.PlayerShootPacket;
import static org.junit.Assert.*;

public class DpsRetentionTest {
    /** A synthetic close through the same two producer paths as map changes and capture stop. */
    public static DpsData close(TomatoData data, int number, boolean stop) {
        return close(data, number, stop, List.of(new MapInfoPacket()));
    }

    private static DpsData close(TomatoData data, int number, boolean stop, List<Packet> packets) {
        data.clear(); // reset any stopped world's remainder without creating another recording
        MapInfoPacket map = new MapInfoPacket(); map.name = map.displayName = "Synthetic fight " + number;
        data.map = map;
        packets.forEach(data::logPacket);
        if (stop) data.captureTerminated(); else data.clear();
        data.map = null;
        DpsData[] records = data.closedDpsSnapshot();
        return records[records.length - 1];
    }

    @Test public void missingSpawnVerdictSurvivesPacketRetentionForBothClosePaths() {
        for (boolean stop : new boolean[]{false, true}) for (boolean spawned : new boolean[]{false, true}) {
            TomatoData data = new TomatoData(); data.closedEncounters(closed -> { });
            DpsData first = close(data, 0, stop, localShots(spawned));
            assertEquals(!spawned, first.missingLocalSpawn());
            for (int i = 1; i < TomatoData.DEBUG_DPS_KEPT; i++) close(data, i, stop);
            assertNotNull(first.debugPackets);
            close(data, TomatoData.DEBUG_DPS_KEPT, stop);
            assertNull(first.debugPackets);
            assertEquals("Releasing packets must preserve the damage-completeness warning", !spawned, first.missingLocalSpawn());
            close(data, TomatoData.DEBUG_DPS_KEPT + 1, stop);
            assertEquals("Repeated retention must not erase the verdict", !spawned, first.missingLocalSpawn());
        }
    }

    @Test public void retainedVerdictIsNotSerializedAndPacketlessRecordingsKeepExistingBehavior() throws Exception {
        TomatoData data = new TomatoData();
        DpsData first = close(data, 0, false, localShots(false));
        for (int i = 1; i <= TomatoData.DEBUG_DPS_KEPT; i++) close(data, i, false);
        assertNull(first.debugPackets); assertTrue(first.missingLocalSpawn());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(first); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            DpsData loaded = (DpsData) input.readObject();
            assertNull(loaded.debugPackets); assertFalse(loaded.missingLocalSpawn());
        }
        assertFalse(first.getSaveFile(false).missingLocalSpawn());
    }

    private static List<Packet> localShots(boolean spawned) {
        CreateSuccessPacket create = new CreateSuccessPacket(); create.objectId = 3166;
        List<Packet> packets = new ArrayList<>(); packets.add(create);
        if (spawned) {
            ObjectData player = new ObjectData(); player.status = new ObjectStatusData(); player.status.objectId = create.objectId;
            UpdatePacket update = new UpdatePacket(); update.newObjects = new ObjectData[]{player}; packets.add(update);
        }
        packets.add(new PlayerShootPacket());
        EnemyHitPacket hit = new EnemyHitPacket(); hit.shooterID = create.objectId; packets.add(hit);
        return packets;
    }

    @Test public void bothClosePathsKeepTwentyFightsAndOnlyThreePacketLogs() {
        for (boolean stop : new boolean[]{false, true}) {
            TomatoData data = new TomatoData(); data.closedEncounters(closed -> { });
            List<DpsData> closed = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                closed.add(close(data, i, stop));
                assertEquals(Math.min(i + 1, TomatoData.CLOSED_DPS_KEPT), data.dpsData.size());
                for (int j = 0; j <= i; j++) {
                    if (j <= i - TomatoData.DEBUG_DPS_KEPT) assertNull(closed.get(j).debugPackets);
                    else assertEquals(1, closed.get(j).debugPackets.size());
                }
            }
            assertSame(closed.get(5), data.dpsData.get(0));
            assertFalse(data.showDpsEncounter(closed.get(0)));
        }
    }

    @Test public void shownFightSurvivesWhileItsPacketsExpireAndClearReleasesIt() {
        TomatoData data = new TomatoData(); data.closedEncounters(closed -> { });
        DpsData shown = close(data, 0, false);
        assertTrue(data.showDpsEncounter(shown));
        for (int i = 1; i <= 30; i++) close(data, i, false);
        assertEquals(TomatoData.CLOSED_DPS_KEPT, data.dpsData.size());
        assertSame(shown, data.dpsData.get(0));
        assertNull(shown.debugPackets);
        assertNull(shown.getSaveFile(true).debugPackets);
        assertTrue(data.showDpsEncounter(null));
        close(data, 31, false);
        assertFalse(data.dpsData.contains(shown));
        assertTrue(data.showDpsEncounter(data.dpsData.get(0)));
        data.clearDpsHistory();
        assertEquals(0, data.closedDpsSnapshot().length);
        assertFalse(data.showDpsEncounter(shown));
    }
}
