package tomato.gui.dps;

import java.lang.reflect.Field;
import java.util.*;
import javax.swing.SwingUtilities;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.*;
import tomato.realmshark.enums.CharacterClass;
import static org.junit.Assert.*;

/** Home's meter summary: the live meter's damage ranking over all enemies, computed off the EDT from a DPS snapshot. */
public class MeterSummaryTest {
    private static Entity player(int id, int classId, String name) {
        Entity player = new Entity(null, id, 0); player.objectType = classId; player.markPlayerIdentity();
        StatData stat = new StatData(); stat.stringStatValue = name; player.stat.set(StatType.NAME_STAT, stat);
        return player;
    }
    /** An enemy with a 2-second first-to-last-hit window (1000–3000 ms). */
    private static Entity enemy(int id) { Entity enemy = new Entity(null, id, 0); enemy.updateDamageTaken(1000); enemy.updateDamageTaken(3000); return enemy; }
    private static void hit(Entity enemy, Entity owner, int damage) { enemy.genericDamageHit(owner, new Projectile(damage), 2000); }

    @Test public void ranksByDamageLimitsRowsAndFindsTheLocalRank() {
        Entity self = player(1, 782, "Self"), alice = player(2, 797, "Alice"), bob = player(3, 784, "Bob"), cara = player(4, 782, "Cara");
        self.setUser(7);
        Entity boss = enemy(10), minion = enemy(11);
        hit(boss, alice, 600); hit(minion, alice, 300); hit(boss, self, 500); hit(boss, bob, 300); hit(minion, cara, 100);
        MeterSummary summary = MeterSummary.of(Arrays.asList(boss, minion, self), self, "Lost Halls", 2);
        assertEquals(4, summary.players()); assertEquals(2, summary.localRank()); assertEquals("Lost Halls", summary.map());
        assertEquals(2, summary.top().size());
        MeterSummary.Row first = summary.top().get(0), second = summary.top().get(1);
        assertEquals("Alice", first.name()); assertEquals(797, first.classId()); assertEquals(900, first.damage());
        assertEquals(450d, first.dps(), 1e-9); assertFalse(first.local());
        assertEquals("Self", second.name()); assertEquals(500, second.damage()); assertEquals(250d, second.dps(), 1e-9); assertTrue(second.local());
        String wizard = CharacterClass.getName(782);
        assertEquals(wizard == null ? "Unknown" : wizard, second.className());
        try { summary.top().clear(); fail("Rows are read-only"); } catch (UnsupportedOperationException expected) {}
    }

    @Test public void aLocalPlayerWithoutDamageIsNotOnTheMeterAndZeroWindowsHaveUnknownDps() throws Exception {
        Entity self = player(1, 782, "Self"), alice = player(2, 797, "Alice");
        Entity boss = new Entity(null, 10, 0); boss.updateDamageTaken(1000); // A single instant: no window.
        hit(boss, alice, 600);
        MeterSummary summary = MeterSummary.of(Collections.singletonList(boss), self, null, 3);
        assertEquals(0, summary.localRank()); assertEquals(1, summary.players()); assertNull(summary.map());
        assertTrue("Unknown DPS is NaN, never 0", Double.isNaN(summary.top().get(0).dps()));
        assertSame(MeterSummary.EMPTY, MeterSummary.of(null, 3));
        assertTrue(MeterSummary.EMPTY.top().isEmpty()); assertEquals(0, MeterSummary.EMPTY.localRank());
        assertEquals(0, MeterSummary.EMPTY.players()); assertNull(MeterSummary.EMPTY.map());
        java.awt.Color[] color = new java.awt.Color[1];
        SwingUtilities.invokeAndWait(() -> color[0] = MeterDpsGUI.classColor(782)); // it reads UIManager: EDT only
        assertNotNull("Home colors classes the way the meter does", color[0]);
    }

    @Test public void latestSnapshotFeedsTheSummaryFromTheDpsPage() throws Exception {
        TomatoData data = new TomatoData();
        Entity self = player(1, 782, "Self"); self.setUser(7); data.player = self;
        Entity boss = enemy(10); hit(boss, self, 400);
        Field hitList = TomatoData.class.getDeclaredField("entityHitList"); hitList.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Integer, Entity> targets = (Map<Integer, Entity>) hitList.get(data);
        targets.put(boss.id, boss);
        DpsGUI[] view = new DpsGUI[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new DpsGUI(data));
        DpsSnapshot constructed = DpsGUI.latestSnapshot();
        assertNotNull(constructed);
        String recordings = DpsGUI.recordingsRevision();
        assertFalse(recordings.isEmpty());
        assertEquals("Stable while no recording is added, imported or cleared", recordings, DpsGUI.recordingsRevision());
        DpsGUI.updateMapPacket(data);
        DpsSnapshot published = DpsGUI.latestSnapshot();
        assertNotSame("Each publication is a new immutable snapshot", constructed, published);
        MeterSummary summary = MeterSummary.of(published, 3);
        assertEquals(1, summary.players()); assertEquals(1, summary.localRank());
        MeterSummary.Row row = summary.top().get(0);
        assertEquals("Self", row.name()); assertTrue(row.local()); assertEquals(400, row.damage()); assertEquals(200d, row.dps(), 1e-9);
    }
}
