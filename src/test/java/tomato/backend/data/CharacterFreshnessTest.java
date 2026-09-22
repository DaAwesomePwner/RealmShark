package tomato.backend.data;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.data.*;
import packets.data.enums.StatType;
import tomato.realmshark.RealmCharacter;
import static org.junit.Assert.*;

public class CharacterFreshnessTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private CharacterJournal journal() { return new CharacterJournal(temp.getRoot().toPath().resolve("journal.json")); }
    private static StatData stat(StatType type, int value) { return ProgressionDataTest.stat(type, value); }
    private static StatData account(String name) { StatData s = stat(StatType.ACCOUNT_ID_STAT, 0); s.stringStatValue = name; return s; }
    private static ObjectStatusData status(StatData... fields) {
        ObjectStatusData s = new ObjectStatusData(); s.objectId = 1; s.stats = fields; s.pos = new WorldPosData(); return s;
    }
    @Test public void actualIngestionSeparatesLocalPresenceFromPerFieldCaptureTime() {
        AtomicLong clock = new AtomicLong(100);
        Entity player = new Entity(null, 1, 0, clock::get); player.captureObjectType(782);
        player.updateStats(status(account("A"), stat(StatType.INVENTORY_0_STAT, 123), stat(StatType.ATTACK_STAT, 90), stat(StatType.ATTACK_BOOST_STAT, 15)), 0);
        CharacterJournal journal = journal(); journal.observe(player, 7);
        clock.set(200); player.updateStats(status(stat(StatType.CURR_FAME_STAT, 0)), 0); journal.observe(player, 7);
        CharacterJournal.CharacterRecord r = journal.characters().get(0);
        assertEquals(200, r.lastObservedAlive); assertEquals(100, r.fields.get("equipment.0").at);
        assertEquals(200, r.fields.get("fame").at); assertEquals(Long.valueOf(0), r.fame);
        assertEquals(100, r.fields.get("stat.2").at);
        clock.set(300); journal.observe(player, 7); // An unrelated packet does not refresh the local player.
        assertEquals(200, journal.characters().get(0).lastObservedAlive);
        player.updateStats(status(stat(StatType.INVENTORY_0_STAT, 123)), 0); journal.observe(player, 7);
        assertEquals(300, journal.characters().get(0).fields.get("equipment.0").at);
        clock.set(400); player.updateStats(status(stat(StatType.ATTACK_STAT, 95)), 0); journal.observe(player, 7);
        r = journal.characters().get(0); assertEquals(Integer.valueOf(80), r.stats[2]);
        assertEquals("Derived freshness retains the older boost operand", 100, r.fields.get("stat.2").at);
    }
    @Test public void absentRosterMetadataPreservesKnownValuesAndExplicitZeroFalseReplaceThem() {
        CharacterJournal journal = journal(); String account = CharacterJournal.accountKey("A");
        journal.mergeRoster(account, RealmCharacter.getCharList("<Chars><Char id='7'><ObjectType>782</ObjectType><Level>20</Level><Texture>12</Texture><CurrentFame>99</CurrentFame><Seasonal>True</Seasonal><Equipment>123,-1</Equipment></Char></Chars>"));
        long equipmentTime = journal.characters().get(0).fields.get("equipment.0").at;
        journal.mergeRoster(account, RealmCharacter.getCharList("<Chars><Char id='7'><Attack>0</Attack></Char></Chars>"));
        CharacterJournal.CharacterRecord r = journal.characters().get(0);
        assertEquals(Integer.valueOf(20), r.level); assertEquals(Integer.valueOf(12), r.skin);
        assertEquals(Long.valueOf(99), r.fame); assertEquals(Boolean.TRUE, r.seasonal); assertEquals(782, r.classId);
        assertEquals(Integer.valueOf(0), r.stats[2]); assertEquals(equipmentTime, r.fields.get("equipment.0").at);
        journal.mergeRoster(account, RealmCharacter.getCharList("<Chars><Char id='7'><Texture>0</Texture><CurrentFame>0</CurrentFame><Seasonal>False</Seasonal></Char></Chars>"));
        r = journal.characters().get(0); assertEquals(Integer.valueOf(0), r.skin); assertEquals(Long.valueOf(0), r.fame); assertEquals(Boolean.FALSE, r.seasonal);
    }
    @Test public void manualDeathRetainsSnapshotUntilExplicitRestoreIncludingSameMillisecondObservation() {
        AtomicLong clock = new AtomicLong(100);
        Entity player = new Entity(null, 1, 0, clock::get); player.captureObjectType(782);
        player.updateStats(status(account("A"), stat(StatType.INVENTORY_0_STAT, 123)), 0);
        CharacterJournal journal = journal(); journal.observe(player, 7);
        String key = journal.characters().get(0).key; journal.markDead(key, true);
        journal.observe(player, 7); assertEquals(0, journal.characters().get(0).observedAgainAt);
        player.updateStats(status(stat(StatType.INVENTORY_0_STAT, -1)), 0); journal.observe(player, 7);
        CharacterJournal.CharacterRecord marked = journal.characters().get(0);
        assertTrue(marked.dead); assertEquals(100, marked.observedAgainAt); assertEquals(Integer.valueOf(123), marked.equipment[0]);
        journal.notes(key, "Keep this draft"); journal.markDead(key, false);
        CharacterJournal.CharacterRecord restored = journal.characters().get(0);
        assertFalse(restored.dead); assertEquals(Integer.valueOf(-1), restored.equipment[0]); assertEquals("Keep this draft", restored.notes);
        assertEquals(0, restored.observedAgainAt);
    }
    @Test public void accountIdChangeCannotCopyRetainedEquipmentToNewAccount() {
        Entity player = new Entity(null, 1, 0); player.captureObjectType(782);
        player.updateStats(status(account("A"), stat(StatType.INVENTORY_0_STAT, 123)), 0);
        CharacterJournal journal = journal(); journal.observe(player, 7);
        player.updateStats(status(account("B"), stat(StatType.CURR_FAME_STAT, 0)), 0); journal.observe(player, 7);
        CharacterJournal.CharacterRecord b = journal.characters().stream().filter(r -> r.account.equals(CharacterJournal.accountKey("B"))).findFirst().get();
        assertNull(b.equipment[0]); assertNull(b.fields.get("equipment.0")); assertEquals(Long.valueOf(0), b.fame);
    }
    @Test public void legacyJournalKeepsValuesWithoutInventingFieldTimesAndCopiesAreDetached() throws Exception {
        Path path = temp.getRoot().toPath().resolve("journal.json");
        CharacterJournal j = new CharacterJournal(path); j.observe(CharacterJournalTest.player("A", 782), 7); j.save();
        String old = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\"version\": 2", "\"version\": 1");
        com.google.gson.JsonObject document = new com.google.gson.JsonParser().parse(old).getAsJsonObject();
        com.google.gson.JsonObject row = document.getAsJsonArray("characters").get(0).getAsJsonObject();
        row.remove("fields"); row.remove("lastObservedAlive"); row.remove("rosterReceivedAt"); row.remove("observedAgainAt");
        row.addProperty("lastSeen", 1234); row.addProperty("seasonal", false);
        Files.write(path, document.toString().getBytes(StandardCharsets.UTF_8));
        j = new CharacterJournal(path); CharacterJournal.CharacterRecord record = j.characters().get(0);
        assertTrue(record.fields.isEmpty()); assertEquals(0, record.lastObservedAlive); assertEquals(1234, record.lastSeen);
        assertEquals(Boolean.FALSE, record.seasonal); assertTrue(record.source.contains("Legacy"));
        record.fields.put("level", new FieldCapture(999, "fake")); assertTrue(j.characters().get(0).fields.isEmpty());
        j.notes(record.key, "migrated"); j.save();
        assertTrue(new String(Files.readAllBytes(path), StandardCharsets.UTF_8).contains("\"version\": 2"));
    }
    @Test public void acceptedHttpMetadataRetainsPresenceAndPartialPetInputs() throws Exception {
        CharacterJournal journal = journal();
        TomatoData data = new TomatoData((token, endpoint) -> endpoint.equals("char/list")
            ? "<Chars><Char id='7'><ObjectType>782</ObjectType><Pet instanceId='42'><Abilities><Ability type='407' power='30'/></Abilities></Pet></Char></Chars>"
            : "<AccountPowerups/>") { @Override public CharacterJournal characterJournal() { return journal; } };
        data.updateToken("fixture"); assertTrue(data.awaitMetadataIdle(2000)); data.setUserId(1, 7, "AAAAAA==");
        data.player = new Entity(data, 1, 0); data.player.captureObjectType(782);
        data.player.stat.set(StatType.ACCOUNT_ID_STAT, account("A")); data.rememberCharacter();
        data.charListHttpRequest(); assertTrue(data.awaitMetadataIdle(2000)); data.rememberCharacter();
        CharacterJournal.CharacterRecord r = journal.characters().get(0);
        assertNull(r.level); assertNull(r.skin); assertNull(r.fame); assertNull(r.seasonal);
        ProgressionData.Pet pet = data.progression().snapshot().pets.get(0);
        assertEquals(Integer.valueOf(42), pet.value(StatType.PET_INSTANCE_ID_STAT));
        assertNull(pet.value(StatType.PET_FIRST_ABILITY_POINT_STAT)); assertNull(pet.value(StatType.PET_MAX_ABILITY_POWER_STAT));
        assertEquals(Integer.valueOf(30), pet.value(StatType.PET_FIRST_ABILITY_POWER_STAT));
        assertNull(data.pet); // An incomplete ability list remains unavailable to My Info's aggregate estimate.
    }
    @Test public void legacyPetParserUsesAttributeNamesAndPresenceRatherThanDefaultZeros() {
        RealmCharacter c = RealmCharacter.getCharList("<Chars><Char id='7'><Pet instanceId='0'><Abilities>"
            + "<Ability type='407' power='30'/><Ability points='0' type='408' power='1'/></Abilities></Pet></Char></Chars>").get(0);
        assertTrue(c.presence.containsKey("pet.81")); assertEquals(0, c.petInstanceId);
        assertFalse(c.presence.containsKey("pet.85")); assertFalse(c.presence.containsKey("pet.87"));
        assertTrue(c.presence.containsKey("pet.88")); assertEquals(407, c.petAbilitys[2]); assertEquals(408, c.petAbilitys[5]);
        assertEquals(30, c.petAbilitys[1]);
    }
}
