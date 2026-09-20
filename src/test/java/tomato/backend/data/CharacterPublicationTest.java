package tomato.backend.data;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.CreateSuccessPacket;
import packets.incoming.VaultContentPacket;
import tomato.backend.TomatoPacketCapture;
import tomato.gui.character.CharacterPetsGUI;
import tomato.gui.keypop.KeypopGUI;
import tomato.gui.stats.FameTablePanel;
import tomato.gui.stats.LootGUI;
import tomato.realmshark.LootDelivery;
import tomato.realmshark.RealmCharacter;
import tomato.realmshark.RealmCharacterStats;
import tomato.realmshark.SendLoot;
import tomato.realmshark.enums.CharacterClass;
import tomato.realmshark.enums.CharacterStatistics;
import tomato.realmshark.enums.LootBags;
import static org.junit.Assert.*;

/** Exercises the retained tracking paths without constructing any retired character view. */
public class CharacterPublicationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final Map<Field, Object> savedStatics = new LinkedHashMap<>();
    private TreeMap<Integer, int[]> savedExalts;
    private Map<Integer, String> classNames, savedClassNames;
    private CharacterJournal journal;
    private TomatoData data;
    private String roster;
    private CharacterPetsGUI pets;
    private FameTablePanel fame;
    private LootGUI loot;
    private SendLoot.Session sharing;

    @Before public void isolateModelsAndViews() throws Exception {
        onEdt(() -> {
            for (Class<?> type : new Class<?>[]{CharacterPetsGUI.class, FameTablePanel.class, LootGUI.class})
                replaceStatic(type, "INSTANCE", null);
            replaceStatic(LootGUI.class, "data", null);
        });
        savedExalts = RealmCharacter.exalts;
        RealmCharacter.exalts = new TreeMap<>();
        classNames = names(); savedClassNames = new TreeMap<>(classNames);
        classNames.put(782, "Fixture Wizard");
        journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"));
        roster = roster(character(7, false, 500, "-1,-1,-1,-1,2793,9064", true),
            character(8, true, 900, "-1,-1,-1,-1,5472,9071", false));
        data = new TomatoData((token, endpoint) -> "char/list".equals(endpoint) ? roster : "<AccountPowerups/>") {
            @Override public CharacterJournal characterJournal() { return journal; }
        };
    }

    @After public void restoreModelsAndViews() throws Exception {
        try {
            if (data != null) assertTrue(data.awaitMetadataIdle(2000));
        } finally {
            // Drain publications and their queued row renders before restoring singleton owners.
            onEdt(() -> {});
            onEdt(() -> {
                for (Map.Entry<Field, Object> entry : savedStatics.entrySet()) entry.getKey().set(null, entry.getValue());
            });
            if (sharing != null) sharing.close();
            if (journal != null) journal.close();
            if (classNames != null) { classNames.clear(); classNames.putAll(savedClassNames); }
            if (savedExalts != null) RealmCharacter.exalts = savedExalts;
        }
    }

    @Test public void acceptedRosterPublishesEquippedPetAndFameBaselinesOnEdtWithoutSamples() throws Exception {
        identify();
        onEdt(() -> { pets = new CharacterPetsGUI(data); fame = new FameTablePanel(data); });
        data.charListHttpRequest();
        assertTrue(data.awaitMetadataIdle(2000));
        assertNull("HTTP completion alone cannot publish a roster", data.chars);

        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            data.rememberCharacter(); // Accept the worker response through the real capture-side publication path.
            assertNotNull(data.pet);
            assertTrue("Pets publication must stay queued on the EDT", petCache().isEmpty());
            assertEquals("Char", fame.getClassNameForCharacterId(8));
        } finally { release.countDown(); }

        onEdt(() -> {
            assertFalse(pets.isShowing()); assertFalse(fame.isShowing());
            assertEquals(2, data.chars.size()); assertTrue(data.characterDataRecieved);
            assertEquals(2, journal.characters().size());
            Stat cached = petCache().get(42);
            assertNotNull("Accepted equipped-pet metadata reaches the mounted Pets cache", cached);
            assertEquals(70, cached.get(StatType.PET_MAX_ABILITY_POWER_STAT).statValue);
            assertEquals(407, cached.get(StatType.PET_FIRST_ABILITY_TYPE_STAT).statValue);
            assertNotSame(data.pet.stat, cached);
            data.pet.stat.get(StatType.PET_FIRST_ABILITY_POWER_STAT).statValue = 1;
            assertEquals(50, cached.get(StatType.PET_FIRST_ABILITY_POWER_STAT).statValue);
            assertEquals("Fixture Wizard", fame.getClassNameForCharacterId(7));
            assertEquals("Fixture Wizard", fame.getClassNameForCharacterId(8));
            assertNull("Roster baselines are not captured fame samples", fame.getCurrentFame(7));
            assertNull(fame.getCurrentFame(8));
            assertTrue(fame.getMapFameData().isEmpty());
            JTable table = namedTable(fame, "fame-characters");
            assertNotNull(table); assertEquals("Hidden tracking does not render rows", 0, table.getRowCount());
            Method refresh = FameTablePanel.class.getDeclaredMethod("refreshNow"); refresh.setAccessible(true); refresh.invoke(fame);
            assertEquals(2, table.getRowCount());
            for (int row = 0; row < 2; row++) {
                int id = table.getValueAt(row, 0).toString().contains("#7") ? 7 : 8;
                double baseline = id == 7 ? 500 : 900;
                assertEquals("Not observed", table.getValueAt(row, 1));
                assertEquals(baseline, (Double)table.getValueAt(row, 2), 0);
                assertEquals(baseline, (Double)table.getValueAt(row, 3), 0);
                assertEquals(0d, (Double)table.getValueAt(row, 4), 0);
                assertEquals(0L, table.getValueAt(row, 5));
                assertNull(table.getValueAt(row, 6));
            }
        });
    }

    @Test public void acceptedRostersRebuildSeasonalInventoryCachesWithoutDoubleCounting() throws Exception {
        identify();
        for (int i = 0; i < 2; i++) {
            acceptRoster();
            assertArrayEquals(new int[]{1,0,2,0,0,0,0,0}, inventory(data.regularVault));
            assertArrayEquals(new int[]{0,3,0,0,0,0,0,0}, inventory(data.seasonalVault));
        }
        roster = roster(character(7, false, 500, null, false), character(8, true, 900, "-1,-1,-1,-1,2613", false));
        acceptRoster();
        assertArrayEquals(new int[8], inventory(data.regularVault));
        assertArrayEquals(new int[]{0,0,0,0,0,0,0,1}, inventory(data.seasonalVault));
        assertNotNull(data.charMap.get(7)); assertNull(data.charMap.get(7).equipment);
    }

    @Test public void multipartVaultPacketsRouteBySeasonAndReplaceCompletedContents() throws Exception {
        identify();
        TomatoPacketCapture capture = new TomatoPacketCapture(data);
        seasonal(false);
        capture.packetCapture(vault(false, new int[]{2793,9064,-1}, new int[]{5466}, new int[]{2794}));
        assertTrue(data.vaultDataRecievedRegular); assertFalse(data.vaultDataRecievedSeasonal);
        assertVault(data.regularVault, new int[8], new int[8], new int[8]);
        seasonal(true);
        capture.packetCapture(vault(false, new int[]{2613}, new int[0], new int[]{9066}));
        assertTrue(data.vaultDataRecievedSeasonal);
        seasonal(false);
        capture.packetCapture(vault(true, new int[]{9070}, new int[]{2592}, new int[]{9071}));
        assertVault(data.regularVault, new int[]{3,0,2,0,0,0,0,0}, new int[]{0,0,0,2,0,0,0,0}, new int[]{0,3,0,0,0,0,0,0});
        assertVault(data.seasonalVault, new int[8], new int[8], new int[8]);
        seasonal(true);
        capture.packetCapture(vault(true, new int[]{5469}, new int[]{9069}, new int[]{2593}));
        assertVault(data.seasonalVault, new int[]{0,0,0,0,0,0,0,2}, new int[]{0,0,0,0,0,2,0,0}, new int[]{0,0,0,0,3,0,0,0});
        seasonal(false);
        capture.packetCapture(vault(true, new int[]{2591}, new int[0], new int[0]));
        assertVault(data.regularVault, new int[]{0,0,1,0,0,0,0,0}, new int[8], new int[8]);
        assertVault(data.seasonalVault, new int[]{0,0,0,0,0,0,0,2}, new int[]{0,0,0,0,0,2,0,0}, new int[]{0,0,0,0,3,0,0,0});
    }

    @Test public void createSuccessCompletionsUpdateCachedCharacterAndMissingKeypopDecisions() throws Exception {
        replaceStatic(KeypopGUI.class, "selectedDungeons", Collections.singleton("missingDungeons"));
        RealmCharacter character = new RealmCharacter(); character.charId = 7;
        character.charStats = new RealmCharacterStats(); character.charStats.decode(completions(0));
        data.charMap = new HashMap<>(); data.charMap.put(7, character);
        TomatoPacketCapture capture = new TomatoPacketCapture(data);
        capture.packetCapture(create(completions(0)));
        RealmCharacterStats first = data.getCurrentDungeonStats();
        assertEquals(0, first.getDungeonInfoByName("Pirate Cave"));
        assertNotNull("A complete server payload can establish a run-completion baseline", first.completionCounts());
        assertTrue(KeypopGUI.shouldNotify("Pirate Cave", first));
        assertFalse(KeypopGUI.shouldNotify("Not a dungeon", first));
        assertFalse(KeypopGUI.shouldNotify("Pirate Cave", null));
        capture.packetCapture(create(completions(1)));
        RealmCharacterStats updated = data.getCurrentDungeonStats();
        assertNotSame(first, updated); assertSame(updated, character.charStats);
        assertEquals(1, updated.getDungeonInfoByName("Pirate Cave"));
        int dungeon = tomato.realmshark.enums.CharacterStatistics.getDungeonIndex("Pirate Cave");
        assertEquals(1, updated.completionCounts()[dungeon]);
        int[] detached = updated.completionCounts(); detached[dungeon] = 999;
        assertEquals(1, updated.completionCounts()[dungeon]);
        assertFalse(KeypopGUI.shouldNotify("Pirate Cave", updated));
        capture.packetCapture(create(completions(1)));
        assertSame("Unchanged completion payload retains the cached decode", updated, data.getCurrentDungeonStats());
        replaceStatic(KeypopGUI.class, "selectedDungeons", Collections.singleton("Pirate Cave"));
        assertTrue("Explicit selections still notify completed dungeons", KeypopGUI.shouldNotify("Pirate Cave", updated));
    }

    @Test public void acceptedExaltMetadataStillEnablesLootProcessing() throws Exception {
        identify(); openLoot();
        observeLoot(); assertArrayEquals(new int[]{0,0}, lootTotals());
        roster = roster(character(7, false, 500, "-1,-1,-1,-1", false),
            "<PowerUpStats><ClassStats class='782'>0,0,0,0,0,0,0,15</ClassStats></PowerUpStats>");
        acceptRoster(); onEdt(() -> {});
        assertEquals(15, RealmCharacter.exalts.get(782)[7]);
        assertEquals(15, journal.accounts().get(0).exalts.get(782)[7]);
        observeLoot(); assertArrayEquals(new int[]{1,1}, lootTotals());
    }

    @Test public void accountMetadataResetStillEnablesLootProcessing() throws Exception {
        identify();
        onEdt(() -> pets = new CharacterPetsGUI(data));
        acceptRoster();
        onEdt(() -> assertTrue(petCache().containsKey(42)));
        assertArrayEquals(new int[]{1,0,2,0,0,0,0,0}, inventory(data.regularVault));
        openLoot();
        observeLoot(); assertArrayEquals(new int[]{0,0}, lootTotals());
        data.updateToken("different-fixture-token");
        assertTrue(data.awaitMetadataIdle(2000)); onEdt(() -> {});
        assertNull(data.chars); assertNull(data.charMap); assertNull(data.pet);
        assertFalse(data.characterDataRecieved);
        assertArrayEquals(new int[8], inventory(data.regularVault));
        assertArrayEquals(new int[8], inventory(data.seasonalVault));
        onEdt(() -> assertTrue(petCache().isEmpty()));
        assertEquals("The saved journal survives clearing live account metadata", 2, journal.characters().size());
        observeLoot(); assertArrayEquals(new int[]{1,1}, lootTotals());
    }

    private void identify() throws Exception {
        data.updateToken("fixture-token"); assertTrue(data.awaitMetadataIdle(2000));
        data.setUserId(1, 7, completions(0));
        data.player = new Entity(data, 1, 0); data.player.objectType = 782;
        StatData identity = new StatData(); identity.stringStatValue = "fixture-account";
        data.player.stat.set(StatType.ACCOUNT_ID_STAT, identity);
        data.rememberCharacter(); onEdt(() -> {});
    }

    private void acceptRoster() throws Exception {
        data.charListHttpRequest(); assertTrue(data.awaitMetadataIdle(2000)); data.rememberCharacter();
    }

    private void openLoot() throws Exception {
        sharing = new SendLoot.Session(new LootDelivery(() -> { throw new AssertionError("Tracking tests must not connect"); }, 2, false, false));
        data.setPropList("itemPings", new ArrayList<>());
        onEdt(() -> {
            Constructor<LootGUI> constructor = LootGUI.class.getDeclaredConstructor(TomatoData.class, SendLoot.Session.class);
            constructor.setAccessible(true); loot = constructor.newInstance(data, sharing);
        });
    }

    private void observeLoot() {
        Entity bag = new Entity(null, 2, 0); bag.objectType = LootBags.BROWN.getId();
        StatData item = new StatData(); item.statValue = 999991; bag.stat.set(StatType.INVENTORY_0_STAT, item);
        LootGUI.update(null, bag, null, data.player, 1000);
    }

    private int[] lootTotals() throws Exception {
        Method totals = loot.getDashboard().getClass().getDeclaredMethod("sessionTotals"); totals.setAccessible(true);
        return (int[])totals.invoke(loot.getDashboard());
    }

    private void seasonal(boolean value) { StatData stat = new StatData(); stat.statValue = value ? 1 : 0; data.player.stat.set(StatType.SEASONAL, stat); }
    private static int[] inventory(VaultData vault) { int[] values = new int[8]; vault.getPlayerInvPots(values); return values; }
    private static void assertVault(VaultData vault, int[] chest, int[] gifts, int[] storage) {
        int[] actual = new int[8]; vault.getVaultChestPots(actual); assertArrayEquals(chest, actual);
        actual = new int[8]; vault.getGiftChestPots(actual); assertArrayEquals(gifts, actual);
        actual = new int[8]; vault.getPotStoragePots(actual); assertArrayEquals(storage, actual);
    }
    private static VaultContentPacket vault(boolean last, int[] chest, int[] gifts, int[] storage) {
        VaultContentPacket packet = new VaultContentPacket(); packet.lastVaultPacket = last;
        packet.vaultContents = chest; packet.giftContents = gifts; packet.potionContents = storage; return packet;
    }
    private static CreateSuccessPacket create(String stats) {
        CreateSuccessPacket packet = new CreateSuccessPacket(); packet.objectId = 1; packet.charId = 7; packet.str = stats; return packet;
    }
    private static String completions(int count) {
        // PCStats: little-endian nonzero flag, 128 presence bits, then one small compressed count.
        byte[] bytes = new byte[21]; bytes[0] = 1;
        int bit = CharacterStatistics.PIRATE_CAVE.getPcStatId();
        bytes[4 + bit / 8] = (byte)(1 << (bit % 8)); bytes[20] = (byte)count;
        return Base64.getUrlEncoder().encodeToString(bytes);
    }
    private static String roster(String... entries) {
        return "<Chars><Account><AccountId>fixture-account</AccountId></Account>" + String.join("", entries) + "</Chars>";
    }
    private static String character(int id, boolean seasonal, int fame, String equipment, boolean pet) {
        return "<Char id='" + id + "'><ObjectType>782</ObjectType><Level>20</Level><CurrentFame>" + fame
            + "</CurrentFame><Seasonal>" + (seasonal ? "True" : "False") + "</Seasonal>"
            + (equipment == null ? "" : "<Equipment>" + equipment + "</Equipment>")
            + (pet ? "<Pet instanceId='42' maxAbilityPower='70' skin='100'><Abilities>"
                + "<Ability type='407' power='50' points='1000'/><Ability type='408' power='40' points='800'/>"
                + "<Ability type='406' power='30' points='600'/></Abilities></Pet>" : "<Pet/>") + "</Char>";
    }
    @SuppressWarnings("unchecked") private Map<Integer, Stat> petCache() throws Exception { return (Map<Integer, Stat>)field(CharacterPetsGUI.class, "pets").get(pets); }
    @SuppressWarnings("unchecked") private static Map<Integer, String> names() throws Exception { return (Map<Integer, String>)field(CharacterClass.class, "CLASS_NAME").get(null); }
    private void replaceStatic(Class<?> type, String name, Object value) throws Exception {
        Field field = field(type, name);
        if (!savedStatics.containsKey(field)) savedStatics.put(field, field.get(null));
        field.set(null, value);
    }
    private static Field field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static JTable namedTable(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTable && name.equals(child.getName())) return (JTable)child;
            if (child instanceof Container) { JTable found = namedTable((Container)child, name); if (found != null) return found; }
        }
        return null;
    }
    private interface EdtAction { void run() throws Exception; }
    private static void onEdt(EdtAction action) throws Exception {
        SwingUtilities.invokeAndWait(() -> { try { action.run(); } catch (Exception e) { throw new AssertionError(e); } });
    }
}
