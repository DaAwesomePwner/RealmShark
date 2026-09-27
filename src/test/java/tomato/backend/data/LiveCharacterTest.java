package tomato.backend.data;

import java.io.IOException;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.gui.myinfo.BuildEstimates;
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
        live.clear(500, LiveCharacter.Boundary.STOPPED);
        assertEquals("Clearing with nothing in game is not a change", 0, live.revision()); assertEquals(0, live.lastSeenAt());
        assertNull("No character has stopped being current yet", live.lastBoundary());
        LiveCharacter.Snapshot first = snapshot(1000);
        // A new level, so a change (a snapshot differing only in observedAt is none; see the next test).
        LiveCharacter.Snapshot second = new LiveCharacter.Snapshot("account", 7, 782, "Sample", 912, 21, 1500L, TOTALS,
            new int[]{670, 385, 75, 25, 50, 75, 40, -1}, new int[]{2711, -1, -1, -1}, 12345, 1200, 70, BONUS, null, 2000);
        live.publish(first);
        assertSame(first, live.current()); assertSame(first, live.lastKnown()); assertEquals(1, live.revision()); assertEquals(0, live.lastSeenAt());
        live.publish(second);
        assertSame(second, live.current()); assertEquals(2, live.revision());
        live.clear(2500, LiveCharacter.Boundary.TRANSIENT);
        assertNull(live.current()); assertSame(second, live.lastKnown()); assertEquals(2500, live.lastSeenAt()); assertEquals(3, live.revision());
        assertEquals(LiveCharacter.Boundary.TRANSIENT, live.lastBoundary());
        live.clear(9000, LiveCharacter.Boundary.TRANSIENT);
        assertEquals(2500, live.lastSeenAt()); assertEquals(3, live.revision());
        live.clear(9500, LiveCharacter.Boundary.IDENTITY);
        assertEquals("A later identity change replaces a transient reason", LiveCharacter.Boundary.IDENTITY, live.lastBoundary());
        assertEquals("It is a change for Home", 4, live.revision()); assertEquals("The character still left at its first clear", 2500, live.lastSeenAt());
        live.clear(9900, LiveCharacter.Boundary.TRANSIENT); live.clear(9900, LiveCharacter.Boundary.STOPPED);
        assertEquals("Never back to transient; one lasting reason is enough", LiveCharacter.Boundary.IDENTITY, live.lastBoundary());
        assertEquals(4, live.revision());
        live.publish(first);
        assertSame(first, live.current()); assertEquals(2500, live.lastSeenAt()); assertEquals(5, live.revision());
        try { live.publish(null); fail("Use clear() when no character is in game"); } catch (IllegalArgumentException expected) {}
    }


    @Test public void captureStopRejectsLatePublicationUntilTheNextStartAndBoundaryClears() {
        TomatoData data = new TomatoData();
        LiveCharacter live = data.liveCharacter;
        LiveCharacter.Snapshot first = snapshot(1000), delayed = snapshot(2000);
        live.publish(first);
        data.captureBoundary();
        assertNull("Transport interruption invalidates the current character", live.current());
        live.publish(first);
        data.captureStopped();
        long revision = live.revision();
        // Simulates the producer finishing a detach that began before stopRequested.
        live.publish(delayed);
        assertNull("A late producer publication cannot resurrect a stopped capture", live.current());
        assertSame(first, live.lastKnown()); assertEquals(revision, live.revision());
        data.captureStarted();
        live.publish(delayed);
        assertSame(delayed, live.current());
    }

    /** Only a reset that may keep the same account and character is transient (Home keeps the character live briefly). */
    @Test public void everyIdentityResetSaysWhetherTheSameCharacterMayReturn() throws Exception {
        TomatoData data = new TomatoData((token, endpoint) -> { throw new IOException("offline fixture"); });
        LiveCharacter live = data.liveCharacter;
        data.updateToken("token-A"); assertTrue(data.awaitMetadataIdle(2000));
        live.publish(snapshot(1000));   // account "account", character 7
        data.clear();
        assertEquals("Map change", LiveCharacter.Boundary.TRANSIENT, live.lastBoundary());
        data.updateToken("token-A"); assertTrue(data.awaitMetadataIdle(2000));
        assertEquals("HELLO with the same credential", LiveCharacter.Boundary.TRANSIENT, live.lastBoundary());
        data.setUserId(1, 7, "AAAAAA==");
        assertEquals("CREATE for the same character", LiveCharacter.Boundary.TRANSIENT, live.lastBoundary());
        data.setUserId(1, 8, "AAAAAA==");
        assertEquals("CREATE for another character", LiveCharacter.Boundary.IDENTITY, live.lastBoundary());
        live.publish(snapshot(2000));
        data.captureBoundary();
        assertEquals("Transport reset: who follows is not known yet", LiveCharacter.Boundary.TRANSIENT, live.lastBoundary());
        data.updateToken("token-B"); assertTrue(data.awaitMetadataIdle(2000));
        assertEquals("HELLO with another credential is another account", LiveCharacter.Boundary.IDENTITY, live.lastBoundary());
        live.publish(snapshot(3000));
        data.captureStopped();
        assertEquals("Capture stop", LiveCharacter.Boundary.STOPPED, live.lastBoundary());
    }

    /** The identity My Info resets to (unknown parts null or negative) against the last published character. */
    @Test public void resetIsTransientUnlessAKnownAccountOrCharacterDiffers() {
        for (Object[] c : new Object[][] {{null, -1, LiveCharacter.Boundary.TRANSIENT}, {"account", 7, LiveCharacter.Boundary.TRANSIENT},
                {null, 7, LiveCharacter.Boundary.TRANSIENT}, {"account", -1, LiveCharacter.Boundary.TRANSIENT},
                {"other", 7, LiveCharacter.Boundary.IDENTITY}, {null, 8, LiveCharacter.Boundary.IDENTITY}}) {
            LiveCharacter live = new LiveCharacter();
            live.publish(snapshot(1000));   // account "account", character 7
            live.reset(2000, (String) c[0], (Integer) c[1]);
            assertNull(live.current()); assertEquals(c[0] + "/" + c[1], c[2], live.lastBoundary()); assertEquals(2000, live.lastSeenAt());
        }
        LiveCharacter unknownAccount = new LiveCharacter();
        unknownAccount.publish(new LiveCharacter.Snapshot(null, 7, 782, null, null, null, null, null, null, null, null, null, null, null, null, 1000));
        unknownAccount.reset(2000, "account", 7);
        assertEquals("An account that was not known is not a different one", LiveCharacter.Boundary.TRANSIENT, unknownAccount.lastBoundary());
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

    @Test public void theSameObservationIsDetachedOnceAndAnEqualSnapshotKeepsTheRevision() throws Exception {
        LiveCharacter live = new LiveCharacter();
        live.publish(snapshot(1000));
        long revision = live.revision();
        LiveCharacter.Snapshot again = snapshot(2000);
        live.publish(again);
        assertEquals("Only the publish time differs", revision, live.revision());
        assertSame("Readers still get the newest publication", again, live.current()); assertSame(again, live.lastKnown());
        live.publish(new LiveCharacter.Snapshot("account", 7, 782, "Sample", 912, 21, 1500L, TOTALS,
            new int[]{670, 385, 75, 25, 50, 75, 40, -1}, new int[]{2711, -1, -1, -1}, 12345, 1200, 70, BONUS, null, 3000));
        assertEquals("A new level is a change", revision + 1, live.revision());

        TomatoData data = new TomatoData(); data.setUserId(1, 7, "AAAAAA==");
        Entity player = new Entity(data, 1, 0); player.captureObjectType(782); data.player = player;
        text(player, StatType.ACCOUNT_ID_STAT, "dedupe-account");
        player.updateStats(status(StatType.LEVEL_STAT, 20), 0);
        data.publishMyInfoPlayer(player);
        LiveCharacter.Snapshot first = data.liveCharacter.current();
        long published = data.liveCharacter.revision();
        data.publishMyInfoPlayer(player);   // the tick publishes the same observation again
        assertSame("Nothing new was observed: no second detach", first, data.liveCharacter.current());
        assertEquals(published, data.liveCharacter.revision());
        player.updateStats(status(StatType.LEVEL_STAT, 21), 0);
        data.publishMyInfoPlayer(player);
        assertEquals(Integer.valueOf(21), data.liveCharacter.current().level()); assertTrue(data.liveCharacter.revision() > published);
        data.captureBoundary();   // clears the character without a new observation
        data.publishMyInfoPlayer(player);
        assertNotNull("After a clear the same observation is published again", data.liveCharacter.current());

        BuildEstimates.Inputs a = BuildEstimates.Inputs.detach(player, null, TomatoData.PetAvailability.ABSENT);
        assertTrue("Same observations", a.sameSource(BuildEstimates.Inputs.detach(player, null, TomatoData.PetAvailability.ABSENT)));
        assertFalse("Another pet state", a.sameSource(BuildEstimates.Inputs.detach(player, null, TomatoData.PetAvailability.UNKNOWN)));
        player.updateStats(status(StatType.LEVEL_STAT, 22), 0);
        assertFalse("A newer observation", a.sameSource(BuildEstimates.Inputs.detach(player, null, TomatoData.PetAvailability.ABSENT)));
        Entity untracked = new Entity(null, 2, 0);
        assertFalse("Stats set without an observation are never assumed equal",
            BuildEstimates.Inputs.detach(untracked, null, null).sameSource(BuildEstimates.Inputs.detach(untracked, null, null)));
    }

    private static packets.data.ObjectStatusData status(StatType type, int value) {
        StatData stat = new StatData(); stat.statType = type; stat.statTypeNum = type.get(); stat.statValue = value;
        packets.data.ObjectStatusData status = new packets.data.ObjectStatusData();
        status.objectId = 1; status.pos = new packets.data.WorldPosData(); status.stats = new StatData[]{stat};
        return status;
    }

    @Test public void journalKeyIsTheJournalsExactKeyOrNull() {
        String account = CharacterJournal.accountKey("sample-account");
        assertEquals(account + ":7", new LiveCharacter.Snapshot(account, 7, 782, null, null, null, null, TOTALS, null, null, null, null, null,
            null, null, 0).journalKey());
        assertNull("Not a hashed account key", snapshot(0).journalKey());
        assertNull("No character id", new LiveCharacter.Snapshot(account, -1, 782, null, null, null, null, TOTALS, null, null, null, null, null,
            null, null, 0).journalKey());
    }

    private static void put(Entity entity, StatType type, int value) { StatData stat = new StatData(); stat.statValue = value; entity.stat.set(type, stat); }
    private static void text(Entity entity, StatType type, String value) { StatData stat = new StatData(); stat.stringStatValue = value; entity.stat.set(type, stat); }
}
