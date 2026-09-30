package tomato.gui.dps;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import packets.incoming.NotificationPacket;
import tomato.backend.data.Entity;
import tomato.backend.data.PresenceTimeline;
import tomato.backend.data.Projectile;

import java.util.*;
import java.util.List;

import static org.junit.Assert.*;

public class DpsTextOutcomeTest {
    private int savedFilter;

    /** The rows honor the static player filter, whose default hides everyone: make the state explicit and restore it. */
    @Before public void showEveryPlayer() { savedFilter = Filter.filter; Filter.disable(); }
    @After public void restoreTheFilter() { Filter.filter = savedFilter; Filter.filterNames.clear(); }

    private static Entity enemy(Entity alice, Entity bob) {
        Entity e = new Entity(null, 11, 0);
        StatData hp = new StatData(); hp.statValue = 1000; e.stat.set(StatType.MAX_HP_STAT, hp);
        e.genericDamageHit(alice, new Projectile(200), 1000); e.genericDamageHit(bob, new Projectile(800), 2000);
        e.updateDamageTaken(1000); e.updateDamageTaken(3000);
        return e;
    }
    private static String lineFor(String text, String name) {
        for (String line : text.split("\n")) if (line.contains(" DMG: ") && line.contains(name)) return line;
        throw new AssertionError(name + " in\n" + text);
    }

    @Test public void thePartyBlockAndTheEnemyLinesShowDungeonOutcomes() {
        Entity alice = EncounterOutcomesTest.named(1, "Alice", 768), bob = EncounterOutcomesTest.named(2, "Bob", 775);
        PresenceTimeline presence = new PresenceTimeline();
        presence.recordSeen(1, "Alice", 768, 1_000, false); presence.recordSeen(2, "Bob", 775, 1_000, false);
        presence.recordLeft(2, 126, 700, 161_000); presence.recordEnd(PresenceTimeline.END_VICTORY, 300_000);
        List<Entity> enemies = Collections.singletonList(enemy(alice, bob));
        EncounterOutcomes outcomes = EncounterOutcomes.of(presence, 1_000, false, EncounterOutcomes.playersOf(enemies), List.of());
        MapInfoPacket map = new MapInfoPacket(); map.name = "Synthetic Dungeon";
        String text = DpsToString.stringDmgRealtime(map, enemies, new ArrayList<>(), null, 2123, outcomes);
        assertTrue(text, text.contains("Party outcome: 2 players · 1 completed · 1 nexused"));
        assertTrue(text, text.contains("Nexused 2:40 · 18% HP"));
        assertTrue(lineFor(text, "Bob").contains("Nexused 2:40"));
        assertFalse("Completers carry no tag", lineFor(text, "Alice").contains("Nexus"));
    }

    @Test public void thePartyBlockListsOnlyPlayersTheFilterShows() {
        Entity alice = EncounterOutcomesTest.named(1, "Alice", 768), bob = EncounterOutcomesTest.named(2, "Bob", 775);
        PresenceTimeline presence = new PresenceTimeline();   // Carol never hit anything, so no entity carries her filter data
        presence.recordSeen(1, "Alice", 768, 1_000, false); presence.recordSeen(2, "Bob", 775, 1_000, false);
        presence.recordSeen(3, "Carol", 782, 1_000, false);
        presence.recordLeft(2, 126, 700, 161_000); presence.recordEnd(PresenceTimeline.END_VICTORY, 300_000);
        List<Entity> enemies = Collections.singletonList(enemy(alice, bob));
        EncounterOutcomes outcomes = EncounterOutcomes.of(presence, 1_000, false, EncounterOutcomes.playersOf(enemies), List.of());
        MapInfoPacket map = new MapInfoPacket(); map.name = "Synthetic Dungeon";

        Filter.filter = 1; Filter.filterNames.add("alice");
        String filtered = DpsToString.stringDmgRealtime(map, enemies, new ArrayList<>(), null, 2123, outcomes);
        assertTrue(filtered, filtered.contains("Party outcome: 3 players · 2 completed · 1 nexused"));
        assertTrue(filtered, filtered.contains("Alice"));
        assertFalse("A hidden player is not named in the party block: " + filtered, filtered.contains("Bob"));
        assertFalse("A player the filter cannot match is hidden too: " + filtered, filtered.contains("Carol"));

        Filter.filter = 2;   // highlight shows everyone
        String highlighted = DpsToString.stringDmgRealtime(map, enemies, new ArrayList<>(), null, 2123, outcomes);
        assertTrue(highlighted, highlighted.contains("Bob") && highlighted.contains("Carol"));
        Filter.filter = 0;
        assertTrue(DpsToString.stringDmgRealtime(map, enemies, new ArrayList<>(), null, 2123, outcomes).contains("Carol"));
    }

    @Test public void anUntrackedAreaAddsNoPartyBlockAndNoTags() {
        Entity alice = EncounterOutcomesTest.named(1, "Alice", 768), bob = EncounterOutcomesTest.named(2, "Bob", 775);
        MapInfoPacket map = new MapInfoPacket(); map.name = "Realm of the Mad God";
        String text = DpsToString.stringDmgRealtime(map, Collections.singletonList(enemy(alice, bob)), new ArrayList<>(), null, 2123,
            EncounterOutcomes.untracked());
        assertFalse(text, text.contains("Party outcome"));
        assertFalse(lineFor(text, "Bob").contains("Nexus")); assertFalse(lineFor(text, "Bob").contains("Died"));
    }

    @Test public void theFiveArgumentFormKeepsDeathsFromNotices() {
        Entity alice = EncounterOutcomesTest.named(1, "Alice", 768), bob = EncounterOutcomesTest.named(2, "Bob", 775);
        ArrayList<NotificationPacket> notes = new ArrayList<>(List.of(EncounterOutcomesTest.death("Bob", 0x0723)));
        String text = DpsToString.stringDmgRealtime(null, Collections.singletonList(enemy(alice, bob)), notes, null, 2123);
        assertTrue(text, text.contains("Party outcome: Outcomes unavailable (recorded before this feature)"));
        assertTrue(lineFor(text, "Bob").contains("Died"));
        assertFalse("A player without a death notice is not called a nexus any more", lineFor(text, "Alice").contains("Nexus"));
    }
}
