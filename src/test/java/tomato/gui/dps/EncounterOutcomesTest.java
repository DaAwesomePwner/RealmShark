package tomato.gui.dps;

import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.NotificationEffectType;
import packets.data.enums.StatType;
import packets.incoming.NotificationPacket;
import tomato.backend.data.Entity;
import tomato.backend.data.PresenceTimeline;
import tomato.backend.data.Projectile;
import tomato.gui.dps.EncounterOutcomes.Kind;
import tomato.gui.dps.EncounterOutcomes.Outcome;
import tomato.gui.dps.EncounterOutcomes.State;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class EncounterOutcomesTest {
    private static final long START = 1_000;

    private static EncounterOutcomes saved(PresenceTimeline t) { return EncounterOutcomes.of(t, START, false, List.of(), List.of()); }

    static Entity named(int id, String name, int type) {
        Entity player = new Entity(null, id, 0); player.objectType = type;
        StatData stat = new StatData(); stat.statType = StatType.NAME_STAT; stat.statTypeNum = StatType.NAME_STAT.get(); stat.stringStatValue = name;
        player.stat.set(StatType.NAME_STAT, stat);
        return player;
    }

    static NotificationPacket death(String name, int grave) {
        NotificationPacket n = new NotificationPacket(); n.effect = NotificationEffectType.PlayerDeath; n.pictureType = grave;
        n.message = "{\"k\":\"s.death\",\"t\":{\"player\":\"" + name + "\",\"level\":\"20\"}}";
        return n;
    }

    @Test public void presentAtTheVictoryCompletesAndWalkingOutOfViewAndBackIsNotANexus() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(1, "Stayer", 768, 1_000, true);
        t.recordSeen(2, "Wanderer", 775, 1_000, false);
        t.recordLeft(2, 700, 700, 20_000); t.recordSeen(2, "Wanderer", 775, 30_000, false);
        t.recordEnd(PresenceTimeline.END_VICTORY, 300_000);
        EncounterOutcomes o = saved(t);
        assertEquals(State.AVAILABLE, o.state());
        assertEquals(Kind.COMPLETED, o.outcome(1).kind);
        assertTrue("Your own completion is observed directly", o.outcome(1).confirmed);
        assertEquals(Kind.COMPLETED, o.outcome(2).kind);
        assertEquals("In the dungeon when it ended (server victory).", o.outcome(2).reason);
        assertEquals("Completed", o.outcome(2).label());
        assertEquals("2 players · 2 completed", o.summary());
    }

    @Test public void leavingBeforeTheEndWithoutReturningIsAnInferredNexusWithTimeAndHp() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(2, "Quitter", 775, 1_000, false);
        t.recordLeft(2, 126, 700, 161_000);
        t.recordEnd(PresenceTimeline.END_VICTORY, 300_000);
        Outcome q = saved(t).outcome(2);
        assertEquals(Kind.NEXUSED, q.kind); assertFalse(q.confirmed);
        assertEquals(Long.valueOf(160_000), q.atMs); assertEquals(Integer.valueOf(18), q.hpPercent);
        assertEquals("Nexused 2:40 · 18% HP", q.label());
        assertTrue(q.reason, q.reason.contains("a nexus, a disconnect, or out of view when it ended"));
        assertTrue(q.didNotComplete());
    }

    @Test public void leavingOrDyingAfterTheEndStillCompletes() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(2, "Leaver", 775, 1_000, false); t.recordSeen(3, "Late", 768, 1_000, false);
        t.recordEnd(PresenceTimeline.END_BOSS, 100_000);
        t.recordLeft(2, 700, 700, 120_000); t.recordDeath("Late", 0x0723, 130_000);
        EncounterOutcomes o = saved(t);
        assertEquals(Kind.COMPLETED, o.outcome(2).kind); assertEquals(Kind.COMPLETED, o.outcome(3).kind);
        assertEquals("In the dungeon when it ended (last boss removed).", o.outcome(2).reason);
        assertEquals("2 players · 2 completed", o.summary());
    }

    @Test public void aDeathNoticeBeforeTheEndIsADeathWithItsTimeAndGravestone() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(3, "Faller", 768, 1_000, false);
        t.recordDeath("Faller", 0x0723, 61_000); t.recordLeft(3, 0, 700, 61_000);
        t.recordEnd(PresenceTimeline.END_VICTORY, 300_000);
        Outcome f = saved(t).outcome(3);
        assertEquals(Kind.DIED, f.kind); assertEquals(Long.valueOf(60_000), f.atMs);
        assertEquals(0x0723, f.graveIcon); assertEquals("Died 1:00", f.label()); assertTrue(f.didNotComplete());
        assertEquals("1 player · 0 completed · 1 died", saved(t).summary());
    }

    @Test public void withoutAnEndALiveEncounterIsPendingAndASavedOneIsUnseen() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(1, "Self", 768, 1_000, true); t.recordSeen(2, "Away", 775, 1_000, false);
        t.recordLeft(2, 350, 700, 50_000);
        EncounterOutcomes live = EncounterOutcomes.of(t, START, true, List.of(), List.of());
        assertEquals(State.PENDING, live.state());
        assertEquals(Kind.PENDING, live.outcome(1).kind); assertEquals("In progress", live.outcome(1).label());
        assertEquals("Left view at 0:49; decided when the dungeon ends.", live.outcome(2).reason);
        assertEquals("2 players · outcome pending", live.summary());
        EncounterOutcomes saved = saved(t);
        assertEquals(State.UNSEEN, saved.state());
        assertEquals(Kind.UNKNOWN, saved.outcome(2).kind); assertFalse(saved.outcome(2).didNotComplete());
        assertEquals("2 players · end of dungeon not seen", saved.summary());
    }

    @Test public void yourOwnNexusIsConfirmedEvenThoughTheEndWasNeverSeen() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(1, "Self", 768, 1_000, true); t.recordEscape(90_000);
        Outcome mine = saved(t).outcome(1);
        assertEquals(Kind.NEXUSED, mine.kind); assertTrue(mine.confirmed);
        assertEquals("Nexused 1:29", mine.label()); assertEquals("You pressed nexus at 1:29.", mine.reason);
        assertEquals("1 player · 1 nexused · end of dungeon not seen", saved(t).summary());
    }

    @Test public void yourDeathPacketNamesTheKiller() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(1, "Self", 768, 1_000, true);
        t.recordDeath("Self", 0x0723, 40_000); t.recordLocalDeath("Synthetic foe", 40_000);
        Outcome mine = saved(t).outcome(1);
        assertEquals(Kind.DIED, mine.kind); assertTrue(mine.confirmed);
        assertEquals("Synthetic foe", mine.killedBy); assertEquals(0x0723, mine.graveIcon);
        assertEquals("You died at 0:39, killed by Synthetic foe.", mine.reason);
    }

    @Test public void aReconnectUnderANewObjectIdIsTheSamePlayer() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(5, "Flaky", 768, 1_000, false); t.recordLeft(5, 700, 700, 40_000);
        t.recordSeen(6, "Flaky", 768, 45_000, false);
        t.recordEnd(PresenceTimeline.END_VICTORY, 300_000);
        EncounterOutcomes o = saved(t);
        assertEquals(Kind.COMPLETED, o.outcome(5).kind); assertSame(o.outcome(5), o.outcome(6));
        assertEquals(1, o.lines().size()); assertEquals("1 player · 1 completed", o.summary());
    }

    @Test public void aDeathNoticeForSomeoneNeverSeenStillCountsAsADeath() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordSeen(1, "Seen", 768, 1_000, false); t.recordDeath("Stranger", 0x0723, 20_000);
        t.recordEnd(PresenceTimeline.END_VICTORY, 300_000);
        EncounterOutcomes o = saved(t);
        assertEquals(1, o.lines().size()); assertEquals("2 players · 1 completed · 1 died", o.summary());
    }

    @Test public void someoneFirstSeenAfterTheEndOrNeverSeenIsUnknown() {
        PresenceTimeline t = new PresenceTimeline();
        t.recordEnd(PresenceTimeline.END_VICTORY, 100_000);
        t.recordSeen(7, "Late", 768, 120_000, false);
        EncounterOutcomes o = saved(t);
        assertEquals(Kind.UNKNOWN, o.outcome(7).kind); assertEquals("First seen after the dungeon ended.", o.outcome(7).reason);
        assertEquals(Kind.UNKNOWN, o.outcome(99).kind);
        assertEquals("1 player · 0 completed · 1 unknown", o.summary());
    }

    @Test public void aRecordingWithoutATimelineShowsOnlyDeathsMatchedByAUniqueName() {
        Entity alice = named(1, "Alice", 768), bob = named(2, "Bob", 775), twin = named(3, "Twin", 768), other = named(4, "Twin", 782);
        EncounterOutcomes o = EncounterOutcomes.of(null, -1, false, Arrays.asList(alice, bob, twin, other),
            Arrays.asList(death("Bob", 0x0723), death("Twin", 0x0723)));
        assertEquals(State.UNAVAILABLE, o.state());
        assertEquals(Kind.UNKNOWN, o.outcome(alice).kind);
        assertEquals(Kind.DIED, o.outcome(bob).kind); assertEquals("Died", o.outcome(bob).label());
        assertEquals("A shared name is never matched", Kind.UNKNOWN, o.outcome(twin).kind);
        assertEquals("Outcomes unavailable (recorded before this feature)", o.summary());
        assertEquals(Kind.UNKNOWN, EncounterOutcomes.none().outcome(99).kind);
        assertEquals(Kind.UNKNOWN, EncounterOutcomes.none().outcome((Entity) null).kind);
    }

    @Test public void playersOfListsEveryDamagingPlayerOnce() {
        Entity alice = named(1, "Alice", 768), bob = named(2, "Bob", 775);
        Entity enemy = new Entity(null, 11, 0), other = new Entity(null, 12, 0);
        enemy.genericDamageHit(alice, new Projectile(100), 1000); enemy.genericDamageHit(bob, new Projectile(50), 1100);
        other.genericDamageHit(alice, new Projectile(10), 1200);
        List<Entity> players = EncounterOutcomes.playersOf(Arrays.asList(enemy, other, null));
        assertEquals(2, players.size());
        assertTrue(players.contains(alice)); assertTrue(players.contains(bob));
        assertTrue(EncounterOutcomes.playersOf(Collections.emptyList()).isEmpty());
    }
}
