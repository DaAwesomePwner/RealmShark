package tomato.gui.dps;

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

    @Test public void theFiveArgumentFormKeepsDeathsFromNotices() {
        Entity alice = EncounterOutcomesTest.named(1, "Alice", 768), bob = EncounterOutcomesTest.named(2, "Bob", 775);
        ArrayList<NotificationPacket> notes = new ArrayList<>(List.of(EncounterOutcomesTest.death("Bob", 0x0723)));
        String text = DpsToString.stringDmgRealtime(null, Collections.singletonList(enemy(alice, bob)), notes, null, 2123);
        assertTrue(text, text.contains("Party outcome: Outcomes unavailable (recorded before this feature)"));
        assertTrue(lineFor(text, "Bob").contains("Died"));
        assertFalse("A player without a death notice is not called a nexus any more", lineFor(text, "Alice").contains("Nexus"));
    }
}
