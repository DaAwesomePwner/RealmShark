package tomato.backend.data;

import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.gui.myinfo.WeaponFixture;
import static org.junit.Assert.*;

/** Home's live character: published beside My Info on the capture thread, cleared with every identity reset. */
public class LiveCharacterTest {
    private static final int[] TOTALS = {920, 385, 75, 25, 50, 75, 40, 60}, BONUS = {50, 40, 3, 4, 5, 6, 7, 8};
    private static LiveCharacter.Snapshot snapshot(long at) {
        return new LiveCharacter.Snapshot("account", 7, 782, "Sample", 912, 20, 1500L, TOTALS, new int[]{670, 385, 75, 25, 50, 75, 40, -1},
            new int[]{2711, -1, -1, -1}, 12345, 1200, 70, BONUS, null, at);
    }
    private static LiveCharacter.Snapshot arrays(int[] totals, int[] base, int[] equipment, int[] bonus) {
        return new LiveCharacter.Snapshot("a", 7, 782, null, null, null, null, totals, base, equipment, null, null, null, bonus, null, 0);
    }

    @Test public void publishClearAndLastKnownFollowTheCharacterInGame() {
        LiveCharacter live = new LiveCharacter();
        assertNull(live.current()); assertNull(live.lastKnown()); assertEquals(0, live.lastSeenAt()); assertEquals(0, live.revision());
        live.clear(500);
        assertEquals("Clearing with nothing in game is not a change", 0, live.revision()); assertEquals(0, live.lastSeenAt());
        LiveCharacter.Snapshot first = snapshot(1000), second = snapshot(2000);
        live.publish(first);
        assertSame(first, live.current()); assertSame(first, live.lastKnown()); assertEquals(1, live.revision()); assertEquals(0, live.lastSeenAt());
        live.publish(second);
        assertSame(second, live.current()); assertEquals(2, live.revision());
        live.clear(2500);
        assertNull(live.current()); assertSame(second, live.lastKnown()); assertEquals(2500, live.lastSeenAt()); assertEquals(3, live.revision());
        live.clear(9000);
        assertEquals(2500, live.lastSeenAt()); assertEquals(3, live.revision());
        live.publish(first);
        assertSame(first, live.current()); assertEquals(2500, live.lastSeenAt()); assertEquals(4, live.revision());
        try { live.publish(null); fail("Use clear() when no character is in game"); } catch (IllegalArgumentException expected) {}
    }

    @Test public void snapshotsCopyArraysInAndOutAndRejectWrongShapes() {
        int[] totals = TOTALS.clone(), base = TOTALS.clone(), equipment = {2711, -1, -1, -1}, bonus = BONUS.clone();
        LiveCharacter.Snapshot s = arrays(totals, base, equipment, bonus);
        totals[0] = 1; base[0] = 1; equipment[0] = 1; bonus[0] = 1;
        assertEquals(920, s.totals()[0]); assertEquals(920, s.base()[0]); assertEquals(2711, s.equipment()[0]); assertEquals(50, s.exaltBonus()[0]);
        s.totals()[0] = 2; s.base()[0] = 2; s.equipment()[0] = 2; s.exaltBonus()[0] = 2;
        assertEquals(920, s.totals()[0]); assertEquals(920, s.base()[0]); assertEquals(2711, s.equipment()[0]); assertEquals(50, s.exaltBonus()[0]);
        LiveCharacter.Snapshot unknown = arrays(null, null, null, null);
        assertNull(unknown.totals()); assertNull(unknown.base()); assertNull(unknown.equipment()); assertNull(unknown.exaltBonus());
        try { arrays(new int[7], null, null, null); fail("Stat arrays have 8 entries"); } catch (IllegalArgumentException expected) {}
        try { arrays(null, null, new int[8], null); fail("Equipment has 4 slots"); } catch (IllegalArgumentException expected) {}
    }

    @Test public void publishMyInfoPlayerPublishesTheLocalCharacterAndResetsClearIt() throws Exception {
        TomatoData data = new TomatoData(); data.setUserId(1, 7, "AAAAAA==");
        Entity player = new Entity(data, 1, 0); player.captureObjectType(782); data.player = player;
        text(player, StatType.ACCOUNT_ID_STAT, "live-character-account"); text(player, StatType.NAME_STAT, "Sample,metadata");
        put(player, StatType.LEVEL_STAT, 20); put(player, StatType.SKIN_ID, 912); put(player, StatType.CURR_FAME_STAT, 1500);
        StatType[] totals = {StatType.MAX_HP_STAT, StatType.MAX_MP_STAT, StatType.ATTACK_STAT, StatType.DEFENSE_STAT,
            StatType.SPEED_STAT, StatType.DEXTERITY_STAT, StatType.VITALITY_STAT, StatType.WISDOM_STAT};
        StatType[] boosts = {StatType.MAX_HP_BOOST_STAT, StatType.MAX_MP_BOOST_STAT, StatType.ATTACK_BOOST_STAT, StatType.DEFENSE_BOOST_STAT,
            StatType.SPEED_BOOST_STAT, StatType.DEXTERITY_BOOST_STAT, StatType.VITALITY_BOOST_STAT}; // No wisdom boost captured.
        StatType[] exalted = {StatType.EXALTED_HP, StatType.EXALTED_MP, StatType.EXALTED_ATK, StatType.EXALTED_DEF,
            StatType.EXALTED_SPD, StatType.EXALTED_DEX, StatType.EXALTED_VIT, StatType.EXALTED_WIS};
        for (int i = 0; i < 8; i++) { put(player, totals[i], TOTALS[i]); put(player, exalted[i], BONUS[i]); if (i < 7) put(player, boosts[i], i == 0 ? 250 : 0); }
        put(player, StatType.INVENTORY_0_STAT, 999_101); put(player, StatType.INVENTORY_1_STAT, -1);
        put(player, StatType.INVENTORY_2_STAT, -1); put(player, StatType.INVENTORY_3_STAT, -1); put(player, StatType.EXALTATION_BONUS_DAMAGE, 1000);
        put(player, StatType.NUM_STARS_STAT, 70); put(player, StatType.CREDITS_STAT, 1200); put(player, StatType.FAME_STAT, 12345);
        long before = System.currentTimeMillis(), revision = data.liveCharacter.revision();
        LiveCharacter.Snapshot s;
        try (AutoCloseable weapon = WeaponFixture.install(999_101, 100, 200, 2, 1.5f)) {
            data.publishMyInfoPlayer(player);
            s = data.liveCharacter.current();
            assertNotNull(s);
            assertEquals("Build's in-combat weapon estimate, from the snapshot's own copies", 7200d, s.build().estimate().weaponDps(), 1e-9);
            assertNull("Pet metadata unknown: mana estimate unavailable", s.build().estimate().mpPerSecond());
            put(player, StatType.ATTACK_STAT, 1); // the capture thread keeps changing the live entity
            assertEquals("The copies are detached from the live entity", 7200d, s.build().estimate().weaponDps(), 1e-9);
            put(player, StatType.ATTACK_STAT, TOTALS[2]);
        }
        assertTrue(data.liveCharacter.revision() > revision); assertSame(s, data.liveCharacter.lastKnown());
        assertEquals(CharacterJournal.accountKey("live-character-account"), s.account());
        assertEquals(7, s.characterId()); assertEquals(782, s.classId()); assertEquals("Sample", s.name());
        assertEquals(Integer.valueOf(912), s.skin()); assertEquals(Integer.valueOf(20), s.level()); assertEquals(Long.valueOf(1500), s.characterFame());
        assertArrayEquals(TOTALS, s.totals());
        assertArrayEquals("Base is total minus boost; an uncaptured boost is unknown", new int[]{670, 385, 75, 25, 50, 75, 40, -1}, s.base());
        assertArrayEquals(new int[]{999_101, -1, -1, -1}, s.equipment()); assertArrayEquals(BONUS, s.exaltBonus());
        assertEquals(Integer.valueOf(12345), s.accountFame()); assertEquals(Integer.valueOf(1200), s.gold()); assertEquals(Integer.valueOf(70), s.rankStars());
        assertTrue(s.observedAt() >= before);
        data.clear(); // Map change: identity reset.
        assertNull(data.liveCharacter.current()); assertSame(s, data.liveCharacter.lastKnown()); assertTrue(data.liveCharacter.lastSeenAt() >= s.observedAt());
        data.setUserId(1, 7, "AAAAAA=="); data.player = player; data.publishMyInfoPlayer(player);
        assertNotNull(data.liveCharacter.current());
        data.captureStopped();
        assertNull("Stopping capture means nothing is known to be in game", data.liveCharacter.current());
    }

    private static void put(Entity entity, StatType type, int value) { StatData stat = new StatData(); stat.statValue = value; entity.stat.set(type, stat); }
    private static void text(Entity entity, StatType type, String value) { StatData stat = new StatData(); stat.stringStatValue = value; entity.stat.set(type, stat); }
}
