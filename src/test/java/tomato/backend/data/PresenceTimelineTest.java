package tomato.backend.data;

import org.junit.Test;

import java.io.*;

import static org.junit.Assert.*;

public class PresenceTimelineTest {
    @Test public void seeingAPlayerTwiceAddsOneEntryAndLeavingAddsOneExit() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(5, "Ann", 768, 100, false);
        t.recordSeen(5, null, 0, 110, false);          // a repeated sighting changes nothing
        t.recordLeft(5, 350, 700, 200);
        t.recordLeft(5, 300, 700, 210);                // already gone
        PresenceTimeline.Player ann = t.players().get(5);
        assertEquals("Ann", ann.name); assertEquals(768, ann.classType);
        assertEquals(2, ann.changes.size());
        assertFalse(ann.present());
        assertEquals(350, ann.changes.get(1).hp);
        assertTrue("Sequence numbers order events", ann.changes.get(0).seq < ann.changes.get(1).seq);
        t.recordSeen(5, "Ann", 768, 300, false);
        assertTrue(ann.present()); assertEquals(3, ann.changes.size());
    }

    @Test public void aLeaveForSomeoneNeverSeenStillCounts() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordLeft(9, 10, 100, 50);
        assertFalse(t.players().get(9).present());
        assertNull(t.players().get(9).name);
    }

    @Test public void theEndPrefersVictoryThenDialogueThenTheLastBossRemoved() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordEnd(PresenceTimeline.END_BOSS, 100);
        t.recordEnd(PresenceTimeline.END_BOSS, 200);
        assertEquals(200, t.end().at);                 // the last boss removed wins among bosses
        t.recordEnd(PresenceTimeline.END_DIALOGUE, 300);
        t.recordEnd(PresenceTimeline.END_BOSS, 400);   // weaker: ignored
        assertEquals(PresenceTimeline.END_DIALOGUE, t.end().detail); assertEquals(300, t.end().at);
        t.recordEnd(PresenceTimeline.END_VICTORY, 500);
        t.recordEnd(PresenceTimeline.END_VICTORY, 600); // the first victory stays
        assertEquals(PresenceTimeline.END_VICTORY, t.end().detail); assertEquals(500, t.end().at);
        t.recordEnd("something else", 700);
        assertEquals(500, t.end().at);
    }

    @Test public void yourDeathAndNexusKeepTheFirstEvent() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(1, "Self", 768, 0, true);
        t.recordEscape(10); t.recordEscape(20);
        t.recordLocalDeath("Foe", 30); t.recordLocalDeath("Other", 40);
        assertEquals(Integer.valueOf(1), t.localObjectId());
        assertEquals(10, t.localEscape().at);
        assertEquals("Foe", t.localDeath().detail);
    }

    @Test public void aCopyIsIndependentOfLaterRecording() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(1, "Ann", 768, 0, false);
        PresenceTimeline copy = t.copy();
        t.recordLeft(1, 0, 700, 10); t.recordDeath("Ann", 0x0723, 10); t.recordEnd(PresenceTimeline.END_VICTORY, 20);
        assertTrue(copy.players().get(1).present());
        assertTrue(copy.deaths().isEmpty()); assertNull(copy.end());
        copy.recordSeen(2, "Bob", 775, 30, false);
        assertFalse("Sequence numbers continue in the copy", t.players().containsKey(2));
        assertTrue(copy.players().get(2).changes.get(0).seq > copy.players().get(1).changes.get(0).seq);
    }

    @Test public void itSurvivesJavaSerialization() throws Exception {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(1, "Ann", 768, 0, true); t.recordLeft(1, 5, 10, 1); t.recordDeath("Ann", 3, 2);
        t.recordEscape(3); t.recordLocalDeath("Foe", 4); t.recordEnd(PresenceTimeline.END_VICTORY, 5);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(t); }
        PresenceTimeline read;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { read = (PresenceTimeline) in.readObject(); }
        assertEquals("Ann", read.players().get(1).name);
        assertEquals(1, read.deaths().size()); assertEquals("Foe", read.localDeath().detail);
        assertEquals(PresenceTimeline.END_VICTORY, read.end().detail);
        read.recordSeen(2, "Bob", 775, 6, false);
        assertTrue(read.players().get(2).changes.get(0).seq > read.end().seq);
    }

    @Test public void copyHandlesNullCollectionsAndNullElements() throws Exception {
        // First, create a normal timeline with some data
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(1, "Ann", 768, 0, false);
        t.recordDeath("Ann", 0x0723, 10);

        // Test 1: null players field
        PresenceTimeline nullPlayers = new PresenceTimeline();
        java.lang.reflect.Field playersField = PresenceTimeline.class.getDeclaredField("players");
        playersField.setAccessible(true);
        playersField.set(nullPlayers, null);

        PresenceTimeline copiedFromNullPlayers = nullPlayers.copy();
        assertTrue("Copy of timeline with null players field has empty players",
                   copiedFromNullPlayers.players().isEmpty());

        // Test 2: null deaths field
        PresenceTimeline nullDeaths = new PresenceTimeline();
        java.lang.reflect.Field deathsField = PresenceTimeline.class.getDeclaredField("deaths");
        deathsField.setAccessible(true);
        deathsField.set(nullDeaths, null);

        PresenceTimeline copiedFromNullDeaths = nullDeaths.copy();
        assertTrue("Copy of timeline with null deaths field has empty deaths",
                   copiedFromNullDeaths.deaths().isEmpty());

        // Test 3: null changes field in a Player
        PresenceTimeline withNullChanges = t.copy();  // Start with a valid copy
        PresenceTimeline.Player player = withNullChanges.players().get(1);
        assertNotNull("Player should exist", player);

        java.lang.reflect.Field changesField = PresenceTimeline.Player.class.getDeclaredField("changes");
        changesField.setAccessible(true);
        changesField.set(player, null);  // Corrupt the player's changes

        PresenceTimeline copiedFromNullChanges = withNullChanges.copy();
        // Should successfully copy without throwing
        assertNotNull("Copy should exist", copiedFromNullChanges);
        assertTrue("Copy should have the player", copiedFromNullChanges.players().containsKey(1));
        PresenceTimeline.Player copiedPlayer = copiedFromNullChanges.players().get(1);
        assertNotNull("Copied player should exist", copiedPlayer);
        // The copied player's changes should be empty (not null) because copy() handled the null
        assertEquals("Copied player's changes should be empty after handling null",
                     0, copiedPlayer.changes.size());
    }
}
