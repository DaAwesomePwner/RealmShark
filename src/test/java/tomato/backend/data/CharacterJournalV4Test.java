package tomato.backend.data;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.data.*;
import packets.data.enums.StatType;
import static org.junit.Assert.*;

/** Journal v4: account-wide live stats and live exaltation bonuses, read back as deep copies for Home. */
public class CharacterJournalV4Test {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String ACCOUNT = CharacterJournal.accountKey("v4-fixture");
    private Path file() { return temp.getRoot().toPath().resolve("Characters/journal.json"); }
    private static String read(Path path) throws Exception { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
    private static void write(Path path, String json) throws Exception { Files.createDirectories(path.getParent()); Files.write(path, json.getBytes(StandardCharsets.UTF_8)); }

    /** A journal exactly as version 3 wrote it: no live account fields. */
    static String v3Document(String account) {
        String nulls = String.join(",", Collections.nCopies(28, "null"));
        return "{\"version\":3,\"characters\":[{\"key\":\"" + account + ":7\",\"account\":\"" + account + "\",\"name\":\"Sample\","
            + "\"className\":\"Wizard\",\"characterId\":7,\"classId\":782,\"level\":20,\"fame\":1500,\"firstSeen\":100,\"lastSeen\":200,"
            + "\"diedAt\":0,\"lastObservedAlive\":200,\"rosterReceivedAt\":0,\"observedAgainAt\":0,\"fields\":{},\"dead\":false,"
            + "\"stats\":[670,385,75,25,50,75,40,60],\"equipment\":[" + nulls + "],\"notes\":\"\",\"source\":\"Captured character\"}],"
            + "\"accounts\":{\"" + account + "\":{\"key\":\"" + account + "\",\"name\":\"Sample\","
            + "\"exalts\":{\"782\":[5,15,30,50,75,77,1,0]},\"exaltSeen\":300}}}";
    }

    @Test public void versionThreeLoadsUnchangedAndSavesAsVersionFourWithLiveFields() throws Exception {
        Path path = file(); write(path, v3Document(ACCOUNT));
        CharacterJournal j = new CharacterJournal(path);
        assertFalse(j.storageStatus().contains("Original preserved"));
        CharacterJournal.CharacterRecord r = j.characters().get(0);
        assertEquals("Sample", r.name); assertEquals(782, r.classId); assertEquals(Integer.valueOf(20), r.level); assertEquals(200, r.lastSeen);
        assertEquals(Long.valueOf(1500), r.fame); assertEquals(Integer.valueOf(670), r.stats[0]); assertNull(r.equipment[0]);
        CharacterJournal.AccountRecord a = j.accountCopy(ACCOUNT);
        assertEquals(5, a.exalts.get(782)[0]); assertEquals(300, a.exaltSeen); assertEquals(0, a.accountStatsObservedAt);
        assertTrue(a.liveExaltBonus.isEmpty()); assertNull(a.accountFame); assertNull(a.gold); assertNull(a.rankStars);
        j.save();
        assertTrue("Nothing changed, so the v3 file is not rewritten", read(path).contains("\"version\":3"));
        j.accountLive(ACCOUNT, 782, 70, 1200L, 12345L, new int[]{50, 40, 3, 4, 5, 6, 7, 8}, 5000); j.save();
        String saved = read(path);
        assertTrue(saved.contains("\"version\": 4")); assertTrue(saved.contains("\"liveExaltBonus\"")); assertTrue(saved.contains("\"rankStars\": 70"));
        CharacterJournal reopened = new CharacterJournal(path);
        CharacterJournal.AccountRecord live = reopened.accountCopy(ACCOUNT);
        assertEquals(Integer.valueOf(70), live.rankStars); assertEquals(Long.valueOf(1200), live.gold);
        assertEquals(Long.valueOf(12345), live.accountFame); assertEquals(5000, live.accountStatsObservedAt);
        assertArrayEquals(new int[]{50, 40, 3, 4, 5, 6, 7, 8}, live.liveExaltBonus.get(782).bonus); assertEquals(5000, live.liveExaltBonus.get(782).observedAt);
        assertEquals("v3 values survive the upgrade", 5, live.exalts.get(782)[0]); assertEquals(Integer.valueOf(670), reopened.characters().get(0).stats[0]);
    }

    @Test public void nullLiveValuesKeepThePreviousOnesAndUnchangedObservationsDoNotDirtyTheJournal() {
        CharacterJournal j = new CharacterJournal(file());
        int[] bonus = {1, 2, 3, 4, 5, 6, 7, 8};
        j.accountLive(ACCOUNT, 782, 70, 1200L, 12345L, bonus, 1000);
        bonus[0] = 99; // The journal keeps its own copy.
        j.accountLive(ACCOUNT, 782, null, 1300L, null, null, 2000);
        CharacterJournal.AccountRecord a = j.accountCopy(ACCOUNT);
        assertEquals(Integer.valueOf(70), a.rankStars); assertEquals(Long.valueOf(1300), a.gold); assertEquals(Long.valueOf(12345), a.accountFame);
        assertEquals(2000, a.accountStatsObservedAt);
        assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7, 8}, a.liveExaltBonus.get(782).bonus); assertEquals(1000, a.liveExaltBonus.get(782).observedAt);
        long revision = j.revision();
        j.accountLive(ACCOUNT, 782, null, null, null, null, 3000);
        j.accountLive(ACCOUNT, 782, 70, 1300L, 12345L, new int[]{1, 2, 3, 4, 5, 6, 7, 8}, 1000);
        assertEquals("Nothing observed or nothing new", revision, j.revision());
        j.accountLive(ACCOUNT, 0, null, null, null, new int[]{9, 9, 9, 9, 9, 9, 9, 9}, 4000);
        j.accountLive(ACCOUNT, 784, null, null, null, new int[]{1, 2, 3}, 4000);
        j.accountLive(ACCOUNT, 784, null, null, null, new int[]{1, 2, 3, 4, 5, 6, 7, -1}, 4000);
        assertEquals("Unknown classes and malformed bonuses are ignored", revision, j.revision());
        assertEquals(1, j.accountCopy(ACCOUNT).liveExaltBonus.size());
        j.accountLive(ACCOUNT, 784, null, null, null, new int[8], 4000);
        assertEquals(revision + 1, j.revision()); assertEquals(4000, j.accountCopy(ACCOUNT).liveExaltBonus.get(784).observedAt);
        j.accountLive(null, 782, 1, 1L, 1L, null, 5000);
        assertEquals(revision + 1, j.revision());
    }

    @Test public void rememberCharacterFeedsAccountStatsAndCanonicalExaltBonuses() {
        CharacterJournal isolated = new CharacterJournal(file());
        TomatoData data = new TomatoData() { @Override public CharacterJournal characterJournal() { return isolated; } };
        Entity player = CharacterJournalTest.player("v4-fixture", 782);
        CharacterJournalTest.put(player, StatType.NUM_STARS_STAT, 70); CharacterJournalTest.put(player, StatType.CREDITS_STAT, 1200);
        CharacterJournalTest.put(player, StatType.FAME_STAT, 12345);
        // StatType id order 105..112 is atk, def, spd, vit, wis, dex, life, mana: not the canonical order.
        StatType[] byId = {StatType.EXALTED_ATK, StatType.EXALTED_DEF, StatType.EXALTED_SPD, StatType.EXALTED_VIT,
            StatType.EXALTED_WIS, StatType.EXALTED_DEX, StatType.EXALTED_HP, StatType.EXALTED_MP};
        int[] values = {3, 4, 5, 7, 8, 6, 50, 40}, canonical = {50, 40, 3, 4, 5, 6, 7, 8};
        for (int i = 0; i < 7; i++) CharacterJournalTest.put(player, byId[i], values[i]);
        assertNull("Seven of eight is not a complete bonus", CharacterJournal.exaltBonus(player));
        CharacterJournalTest.put(player, byId[7], values[7]);
        assertArrayEquals(canonical, CharacterJournal.exaltBonus(player)); assertNull(CharacterJournal.exaltBonus(null));
        data.player = player; data.charId = 42;
        try {
            data.rememberCharacter();
            CharacterJournal.AccountRecord a = isolated.accountCopy(ACCOUNT);
            assertEquals(Integer.valueOf(70), a.rankStars); assertEquals(Long.valueOf(1200), a.gold); assertEquals(Long.valueOf(12345), a.accountFame);
            assertTrue(player.observedAt() > 0); assertEquals(player.observedAt(), a.accountStatsObservedAt);
            assertArrayEquals(canonical, a.liveExaltBonus.get(782).bonus); assertEquals(player.observedAt(), a.liveExaltBonus.get(782).observedAt);
            long revision = isolated.revision();
            data.rememberCharacter();
            assertEquals("An unchanged observation does not dirty the journal", revision, isolated.revision());
        } finally { isolated.close(); }
    }

    @Test public void accountCopyAndMostRecentCharacterAreDetachedDeepCopies() {
        CharacterJournal j = new CharacterJournal(file());
        assertNull(j.mostRecentCharacter()); assertNull(j.accountCopy(ACCOUNT)); assertNull(j.accountCopy(null));
        AtomicLong clock = new AtomicLong(1000);
        j.observe(observed(clock, 1, 782), 1);
        clock.set(2000); j.observe(observed(clock, 2, 784), 2);
        CharacterJournal.CharacterRecord recent = j.mostRecentCharacter();
        assertEquals(2, recent.characterId); assertEquals(2000, recent.lastSeen);
        recent.stats[0] = 999; recent.notes = "changed";
        assertNull(j.mostRecentCharacter().stats[0]); assertEquals("", j.mostRecentCharacter().notes);
        j.markDead(recent.key, true);
        assertEquals("A dead character is never the most recent live one", 1, j.mostRecentCharacter().characterId);
        j.accountLive(ACCOUNT, 784, 70, null, null, new int[]{1, 2, 3, 4, 5, 6, 7, 8}, 2000);
        CharacterJournal.AccountRecord copy = j.accountCopy(ACCOUNT);
        copy.liveExaltBonus.get(784).bonus[0] = 99; copy.rankStars = 1; copy.exalts.put(1, new int[8]);
        CharacterJournal.AccountRecord again = j.accountCopy(ACCOUNT);
        assertEquals(1, again.liveExaltBonus.get(784).bonus[0]); assertEquals(Integer.valueOf(70), again.rankStars); assertFalse(again.exalts.containsKey(1));
        assertEquals("accounts() copies the live fields too", Integer.valueOf(70), j.accounts().get(0).rankStars);
    }

    @Test public void newerOrMalformedJournalsStayReadOnly() throws Exception {
        Path path = file(); String newer = v3Document(ACCOUNT).replace("\"version\":3", "\"version\":5");
        write(path, newer);
        CharacterJournal j = new CharacterJournal(path);
        assertTrue(j.storageStatus().contains("Original preserved")); assertTrue(j.characters().isEmpty());
        j.accountLive(ACCOUNT, 782, 70, null, null, null, 1000); j.save();
        assertEquals(newer, read(path));
        write(path, v3Document(ACCOUNT).replace("\"version\":3", "\"version\":4")
            .replace("\"exaltSeen\":300", "\"exaltSeen\":300,\"liveExaltBonus\":{\"782\":{\"bonus\":[1,2,3],\"observedAt\":5}}"));
        assertTrue(new CharacterJournal(path).storageStatus().contains("Original preserved"));
    }

    private static Entity observed(AtomicLong clock, int objectId, int classId) {
        Entity player = new Entity(null, objectId, 0, clock::get); player.captureObjectType(classId);
        StatData account = new StatData(); account.statType = StatType.ACCOUNT_ID_STAT; account.statTypeNum = account.statType.get();
        account.stringStatValue = "v4-fixture";
        ObjectStatusData status = new ObjectStatusData(); status.objectId = objectId; status.pos = new WorldPosData(); status.stats = new StatData[]{account};
        player.updateStats(status, 0);
        return player;
    }
}
