package tomato.backend.data;

import org.junit.*;
import packets.data.*;
import packets.data.enums.NotificationEffectType;
import packets.data.enums.StatType;
import packets.incoming.*;
import packets.outgoing.EscapePacket;
import tomato.backend.TomatoPacketCapture;
import tomato.gui.dps.EncounterOutcomes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Capture fills a presence timeline per logged area and closes it with the recording. */
public class PresenceCaptureTest {
    @BeforeClass public static void initializeSwing() throws Exception { javax.swing.SwingUtilities.invokeAndWait(() -> {}); }

    /** The asset catalog is not part of the checkout, so register object type 782 as a player class ourselves. */
    @BeforeClass public static void registerPlayerClass() throws Exception {
        java.nio.file.Path xml = java.nio.file.Files.createTempFile("players", ".xml");
        java.nio.file.Files.write(xml, ("<Objects><Object type='0x30e' id='Synthetic class'><Equipment>1,2,3</Equipment>"
            + "<MaxHitPoints max='100'>10</MaxHitPoints><MaxMagicPoints max='100'>10</MaxMagicPoints>"
            + "<Attack max='50'>10</Attack><Defense max='50'>10</Defense><Speed max='50'>10</Speed>"
            + "<Dexterity max='50'>10</Dexterity><HpRegen max='50'>10</HpRegen><MpRegen max='50'>10</MpRegen></Object></Objects>")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try { assertTrue(tomato.realmshark.enums.CharacterClass.reload(xml)); }
        finally { java.nio.file.Files.deleteIfExists(xml); }
    }

    @AfterClass public static void restorePlayerClasses() {
        tomato.realmshark.enums.CharacterClass.reload();   // the real catalog when the checkout has one
    }

    private TomatoData data;
    private final List<DpsData> handedOff = new ArrayList<>();

    @Before public void setUp() {
        data = new TomatoData();
        data.visitSource(null);
        data.closedEncounters(handedOff::add);
        enter("Lost Halls");
        data.setTime(1);
    }

    @Test public void aRecordingKeepsWhoLeftDiedAndStayedUntilTheVictory() {
        data.setUserId(21, 7, "AAAAAA==");
        spawn(21, "Self", 700, 700); spawn(22, "Walker", 700, 700); spawn(23, "Quitter", 126, 700); spawn(24, "Faller", 700, 700);
        drop(22); spawn(22, "Walker", 700, 700);   // out of view and back
        drop(23);                                  // gone for good
        data.notification(death("Faller", 0x0723)); drop(24);
        data.notification(notice(NotificationEffectType.Victory));
        enter("{s.nexus}");
        DpsData recording = data.dpsData.get(0);
        PresenceTimeline presence = recording.getPresence();
        assertNotNull(presence);
        assertEquals(Integer.valueOf(21), presence.localObjectId());
        assertEquals(PresenceTimeline.END_VICTORY, presence.end().detail);
        EncounterOutcomes outcomes = EncounterOutcomes.of(presence, recording.dungeonStartTime, false, List.of(), List.of());
        assertEquals(EncounterOutcomes.Kind.COMPLETED, outcomes.outcome(21).kind);
        assertEquals(EncounterOutcomes.Kind.COMPLETED, outcomes.outcome(22).kind);
        assertEquals(EncounterOutcomes.Kind.NEXUSED, outcomes.outcome(23).kind);
        assertEquals(Integer.valueOf(18), outcomes.outcome(23).hpPercent);
        assertEquals(EncounterOutcomes.Kind.DIED, outcomes.outcome(24).kind);
        assertEquals("4 players · 2 completed · 1 nexused · 1 died", outcomes.summary());
    }

    @Test public void yourNexusAndYourDeathArriveThroughCapture() {
        TomatoPacketCapture capture = new TomatoPacketCapture(data);
        data.setUserId(21, 7, "AAAAAA=="); spawn(21, "Self", 700, 700);
        capture.packetCapture(new EscapePacket());
        enter("{s.nexus}");
        EncounterOutcomes.Outcome mine = outcomes(data.dpsData.get(0)).outcome(21);
        assertEquals(EncounterOutcomes.Kind.NEXUSED, mine.kind); assertTrue(mine.confirmed);

        enter("Lost Halls"); data.setTime(1);
        data.setUserId(31, 8, "AAAAAA=="); spawn(31, "Self", 700, 700);
        DeathPacket death = new DeathPacket(); death.killedBy = "Synthetic foe";
        capture.packetCapture(death);
        enter("{s.nexus}");
        mine = outcomes(data.dpsData.get(1)).outcome(31);
        assertEquals(EncounterOutcomes.Kind.DIED, mine.kind); assertEquals("Synthetic foe", mine.killedBy);
    }

    @Test public void theFinalBossLineEndsTheDungeon() {
        enter("The Void"); data.setTime(1);
        spawn(41, "Stayer", 700, 700);
        TextPacket line = new TextPacket(); line.name = "#Void Entity";
        line.text = "You fools... You can never truly defeat me! I am in all of you! I AM all of you!";
        data.text(line);
        enter("{s.nexus}");
        assertEquals(PresenceTimeline.END_DIALOGUE, data.dpsData.get(1).getPresence().end().detail);
    }

    @Test public void theSnapshotAndTheSaveFileCarryDetachedCopies() {
        spawn(51, "Copied", 700, 700);
        DpsSnapshot snapshot = DpsSnapshot.capture(data);
        assertTrue(snapshot.startedAt > 0);
        drop(51);
        assertTrue("The published copy is detached from capture", snapshot.presence.players().get(51).present());
        enter("{s.nexus}");
        DpsData recording = data.dpsData.get(0), saved = recording.getSaveFile(false);
        assertNotSame(recording.getPresence(), saved.getPresence());
        assertFalse(saved.getPresence().players().get(51).present());
        assertTrue("The next area starts empty", DpsSnapshot.capture(data).presence.players().isEmpty());
    }

    @Test public void aCaptureStopClosesTheTimelineAndTheRemainderKnowsWhoIsStillInView() {
        data.setUserId(21, 7, "AAAAAA==");
        spawn(21, "Self", 700, 700); spawn(61, "Before", 700, 700); spawn(62, "Gone", 700, 700);
        drop(62);
        data.captureTerminated();
        PresenceTimeline closed = data.dpsData.get(0).getPresence();
        assertTrue(closed.players().containsKey(61));
        assertFalse("The closed recording keeps its own history", closed.players().get(62).present());
        PresenceTimeline remainder = DpsSnapshot.capture(data).presence;
        assertTrue("Everyone still in view is registered again, not 'Not seen entering'", remainder.players().get(61).present());
        assertEquals("Before", remainder.players().get(61).name);
        assertEquals("The local player is known", Integer.valueOf(21), remainder.localObjectId());
        assertNull("Someone already out of view is not registered as present", remainder.players().get(62));
        assertNull("The remainder has no end yet", remainder.end());
        assertEquals("Present, so in progress rather than not seen", EncounterOutcomes.Kind.PENDING,
            EncounterOutcomes.of(remainder, 1, true, List.of(), List.of()).outcome(61).kind);
    }

    private EncounterOutcomes outcomes(DpsData recording) {
        return EncounterOutcomes.of(recording.getPresence(), recording.dungeonStartTime, false, List.of(), List.of());
    }
    private void enter(String name) {
        MapInfoPacket map = new MapInfoPacket(); map.name = map.displayName = name; data.setNewRealm(map);
    }
    private void spawn(int id, String name, int hp, int maxHp) {
        UpdatePacket update = new UpdatePacket(); update.tiles = new GroundTileData[0]; update.drops = new int[0];
        ObjectData object = new ObjectData(); object.objectType = 782;
        ObjectStatusData status = new ObjectStatusData(); status.objectId = id; status.pos = new WorldPosData();
        status.stats = new StatData[]{stat(StatType.NAME_STAT, 0, name), stat(StatType.HP_STAT, hp, null), stat(StatType.MAX_HP_STAT, maxHp, null)};
        object.status = status; update.newObjects = new ObjectData[]{object};
        data.update(update);
    }
    private void drop(int id) {
        UpdatePacket update = new UpdatePacket(); update.tiles = new GroundTileData[0];
        update.newObjects = new ObjectData[0]; update.drops = new int[]{id};
        data.update(update);
    }
    private static StatData stat(StatType type, int value, String text) {
        StatData stat = new StatData(); stat.statType = type; stat.statTypeNum = type.get(); stat.statValue = value; stat.stringStatValue = text;
        return stat;
    }
    private static NotificationPacket notice(NotificationEffectType effect) {
        NotificationPacket n = new NotificationPacket(); n.effect = effect; return n;
    }
    private static NotificationPacket death(String name, int grave) {
        NotificationPacket n = notice(NotificationEffectType.PlayerDeath); n.pictureType = grave;
        n.message = "{\"k\":\"s.death\",\"t\":{\"player\":\"" + name + "\",\"level\":\"20\"}}";
        return n;
    }
}
