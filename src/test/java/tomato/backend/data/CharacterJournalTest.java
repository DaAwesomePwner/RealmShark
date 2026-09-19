package tomato.backend.data;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.ExaltationUpdatePacket;
import tomato.realmshark.RealmCharacter;

public class CharacterJournalTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    public static void put(Entity e, StatType type, int value) { StatData s = new StatData(); s.statValue = value; e.stat.set(type, s); }
    public static Entity player(String account, int clazz) {
        Entity e = new Entity(null, 1, 0); e.objectType = clazz;
        StatData id = new StatData(); id.stringStatValue = account; e.stat.set(StatType.ACCOUNT_ID_STAT, id);
        StatData name = new StatData(); name.stringStatValue = "Sample"; e.stat.set(StatType.NAME_STAT, name);
        return e;
    }
    private Path file() { return temp.getRoot().toPath().resolve("Characters/journal.json"); }

    @Test public void savesOwnedIdentityAndPartialSnapshotsWithoutCredentials() throws Exception {
        Path file = file(); CharacterJournal j = new CharacterJournal(file);
        Entity e = player("account-private-id", 782);
        put(e, StatType.ATTACK_STAT, 90); // No boost value: cannot infer a base stat.
        put(e, StatType.INVENTORY_0_STAT, 12345);
        j.observe(e, 10);
        assertNull(j.characters().get(0).stats[2]);
        put(e, StatType.ATTACK_BOOST_STAT, 15);
        put(e, StatType.MAX_HP_STAT, 920); put(e, StatType.MAX_HP_BOOST_STAT, 250);
        j.observe(e, 10); j.save();
        CharacterJournal reopened = new CharacterJournal(file);
        assertEquals(1, reopened.characters().size());
        assertEquals(Integer.valueOf(75), reopened.characters().get(0).stats[2]);
        assertEquals(Integer.valueOf(670), reopened.characters().get(0).stats[0]);
        assertEquals(Integer.valueOf(12345), reopened.characters().get(0).equipment[0]);
        assertNull(reopened.characters().get(0).equipment[1]);
        String saved = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertFalse(saved.contains("account-private-id")); assertFalse(saved.contains("accessToken"));
        assertFalse(saved.contains("statUpdates"));
        e.stat.set(StatType.ACCOUNT_ID_STAT, null);
        assertNull(j.observe(e, 20)); assertEquals(1, j.characters().size());
    }

    @Test public void accountCollisionsDeathRestoreAndEquipmentChanges() {
        CharacterJournal j = new CharacterJournal(file()); Entity a = player("A", 782), b = player("B", 782);
        j.observe(a, 1); j.observe(b, 1); assertEquals(2, j.characters().size());
        String key = CharacterJournal.accountKey("A") + ":1";
        j.markDead(key, true); j.notes(key, "Lost in the Shatters");
        put(a, StatType.INVENTORY_0_STAT, 321); j.observe(a, 1);
        CharacterJournal.CharacterRecord dead = j.characters().stream().filter(r -> r.key.equals(key)).findFirst().get();
        assertTrue(dead.dead); assertNull(dead.equipment[0]); assertTrue(dead.diedAt > 0);
        j.save(); j = new CharacterJournal(file());
        assertEquals(1, j.characters().stream().filter(r -> r.dead).count());
        j.markDead(key, false); j.observe(a, 1);
        CharacterJournal.CharacterRecord alive = j.characters().stream().filter(r -> r.key.equals(key)).findFirst().get();
        assertFalse(alive.dead); assertEquals(0, alive.diedAt); assertEquals(Integer.valueOf(321), alive.equipment[0]);
        assertEquals("Lost in the Shatters", alive.notes);
        put(a, StatType.INVENTORY_0_STAT, -1); j.observe(a, 1);
        assertEquals(Integer.valueOf(-1), j.characters().stream().filter(r -> r.key.equals(key)).findFirst().get().equipment[0]);
    }

    @Test public void rosterMergeRetainsMissingCharactersAndUnknownStats() {
        CharacterJournal j = new CharacterJournal(file()); String account = j.observe(player("A", 782), 1);
        ArrayList<RealmCharacter> roster = RealmCharacter.getCharList("<Chars><Char id='2'><ObjectType>782</ObjectType><Attack>70</Attack><Equipment>123,-1</Equipment></Char></Chars>");
        j.mergeRoster(account, roster); assertEquals(2, j.characters().size());
        CharacterJournal.CharacterRecord r = j.characters().stream().filter(c -> c.characterId == 2).findFirst().get();
        assertEquals(Integer.valueOf(70), r.stats[2]); assertNull(r.stats[0]);
        j.mergeRoster(account, new ArrayList<>()); assertEquals(2, j.characters().size());
        assertFalse(j.characters().get(0).dead);
    }

    @Test public void exaltsPersistPerAccountAndClassWithoutCharacterDeathReset() {
        CharacterJournal j = new CharacterJournal(file()); String a = j.observe(player("A", 782), 1);
        String b = j.observe(player("B", 782), 1); Map<Integer,int[]> exalts = new HashMap<>();
        exalts.put(782, new int[]{5,15,30,50,75,77,1,0}); j.exalts(a, exalts);
        exalts.get(782)[0] = 999;
        j.markDead(a + ":1", true); j.save(); j = new CharacterJournal(file());
        assertEquals(5, j.accounts().stream().filter(r -> r.key.equals(a)).findFirst().get().exalts.get(782)[0]);
        assertTrue(j.accounts().stream().filter(r -> r.key.equals(b)).findFirst().get().exalts.isEmpty());
        assertEquals(0, CharacterJournal.exaltLevel(4)); assertEquals(1, CharacterJournal.exaltLevel(5));
        assertEquals(2, CharacterJournal.exaltLevel(15)); assertEquals(3, CharacterJournal.exaltLevel(30));
        assertEquals(4, CharacterJournal.exaltLevel(50)); assertEquals(5, CharacterJournal.exaltLevel(999));
    }

    @Test public void maxingIsUnknownUntilCompleteAndPotionDeficitsNeverNegative() {
        CharacterJournal.CharacterRecord r = new CharacterJournal.CharacterRecord();
        int[] caps = {670,385,75,25,50,75,40,60};
        assertEquals(-1, CharacterJournal.maxed(r, caps));
        r.stats = new Integer[]{670,380,75,25,49,75,40,60};
        assertEquals(6, CharacterJournal.maxed(r, caps));
        assertEquals(1, CharacterJournal.potions(380,385,1));
        r.stats[1] = 390; r.stats[4] = 50; assertEquals(8, CharacterJournal.maxed(r,caps));
        assertEquals(0, CharacterJournal.potions(390,385,1)); assertEquals(-1, CharacterJournal.maxed(r,null));
    }

    @Test public void corruptJournalIsPreservedAndWriteFailuresVisible() throws Exception {
        Path path = file(); Files.createDirectories(path.getParent()); Files.write(path, "{broken".getBytes(StandardCharsets.UTF_8));
        CharacterJournal j = new CharacterJournal(path); j.observe(player("A",782),1); j.save();
        assertTrue(j.storageStatus().contains("Original preserved")); assertEquals("{broken", new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        Path blocked = temp.getRoot().toPath().resolve("blocked"); Files.write(blocked, new byte[]{1});
        j = new CharacterJournal(blocked.resolve("journal.json")); j.observe(player("A",782),1); j.save();
        assertTrue(j.storageStatus().startsWith("Save failed"));
    }

    @Test public void absentOrRejectedAccountResponsesDoNotClaimExaltData() {
        assertNull(RealmCharacter.getCharList(null));
        assertFalse(RealmCharacter.checkExaltNew(null));
        assertFalse(RealmCharacter.checkExaltNew("<Error>Unavailable</Error>"));
    }

    @Test public void oneUpdateCopiesAllKnownStatsAndPreservesMissingOnes() {
        TomatoData data = new TomatoData(); data.charMap = new HashMap<>();
        RealmCharacter c = new RealmCharacter(); c.wis = 60; data.charMap.put(1,c);
        Entity e = new Entity(data,1,0);
        e.charStat(1,new int[]{700,400,60,25,50,75,40,-1});
        assertEquals(700,c.hp); assertEquals(400,c.mp); assertEquals(60,c.atk); assertEquals(25,c.def);
        assertEquals(50,c.spd); assertEquals(75,c.dex); assertEquals(40,c.vit); assertEquals(60,c.wis);
    }

    @Test public void captureIntegrationAcceptsPreviouslyUnseenExaltClass() {
        CharacterJournal isolated = new CharacterJournal(file());
        TomatoData data = new TomatoData() { @Override public CharacterJournal characterJournal() { return isolated; } };
        try {
            data.player = player("integration-account",782); data.charId = 42;
            data.rememberCharacter(); RealmCharacter.exalts.clear();
            ExaltationUpdatePacket p = new ExaltationUpdatePacket(); p.objType = 782; p.healthProgress = 15;
            data.exaltUpdate(p);
            assertEquals(15, data.characterJournal().accounts().stream().filter(a -> a.key.equals(CharacterJournal.accountKey("integration-account"))).findFirst().get().exalts.get(782)[7]);
            data.player.setUser(42); // Partial initial stats must not throw.
        } finally { data.characterJournal().close(); RealmCharacter.exalts.clear(); }
    }
}
