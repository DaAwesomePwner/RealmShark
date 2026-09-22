package assets;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.myinfo.Equip;
import tomato.realmshark.ParseEquipment;
import tomato.realmshark.ParseEnchants;
import tomato.realmshark.enums.CharacterClass;
import util.PropertiesManager;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class AssetRecoveryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final Map<Field, Object> original = new LinkedHashMap<>();
    private Object scalingRules;

    @Before public void isolateCatalogs() throws Exception {
        keep(Equip.class, "weapons"); keep(ParseEquipment.class, "EQUIPMENT");
        for (String field : new String[]{"CHAR_CLASS_LIST", "CHARACTER_CLASS", "CLASS_NAME", "CLASS_MAX_STATS", "WEAPON_CLASSES", "CHARACTER_IDS"}) keep(CharacterClass.class, field);
        keep(AssetExtractor.class, "REALM_RES_PATH"); keep(AssetExtractor.class, "explicitPath");
        keep(AssetCache.class, "current"); keep(tomato.realmshark.ParseDungeon.class, "CATALOG");
        Field rules = tomato.backend.data.AbilityScalingManager.class.getDeclaredField("rules"); rules.setAccessible(true);
        scalingRules = rules.get(tomato.backend.data.AbilityScalingManager.getInstance());
        keep(IdToAsset.class, "objectID"); keep(IdToAsset.class, "tileID");
        for (String name : new String[]{"ENCHANTS", "ENCHANT_EFFECTS", "ENCHANT_REGEN", "ENCHANT_LOOT_BONUS"}) keep(ParseEnchants.class, name);
    }
    private void keep(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); original.put(field, field.get(null)); }
    @After public void restore() throws Exception {
        for (Map.Entry<Field,Object> entry : original.entrySet()) entry.getKey().set(null, entry.getValue());
        Field rules = tomato.backend.data.AbilityScalingManager.class.getDeclaredField("rules"); rules.setAccessible(true);
        rules.set(tomato.backend.data.AbilityScalingManager.getInstance(), scalingRules);
    }

    @Test public void missingAndMalformedDefinitionsDoNotPoisonLaterValidReloads() throws Exception {
        Path missing = temp.getRoot().toPath().resolve("missing.xml");
        assertFalse(Equip.reload(missing)); assertFalse(ParseEquipment.reload(missing)); assertFalse(CharacterClass.reload(missing));
        Path equipment = xml("<Objects><Object type='0x1234' id='Synthetic weapon'><SlotType>1</SlotType><feedPower>500</feedPower>"
            + "<Projectile id='0'><MinDamage>10</MinDamage><MaxDamage>20</MaxDamage></Projectile></Object></Objects>");
        assertTrue(Equip.reload(equipment)); assertTrue(ParseEquipment.reload(equipment));
        assertNotNull(Equip.get(0x1234));
        assertEquals(Integer.valueOf(500), ParseEquipment.feedPowerCatalog().get(0x1234));
        Path malformed = xml("<Objects><Object");
        assertFalse(Equip.reload(malformed)); assertFalse(ParseEquipment.reload(malformed));
        assertNotNull(Equip.get(0x1234)); // Failed replacement retains the last usable catalog.
        assertEquals(Integer.valueOf(500), ParseEquipment.feedPowerCatalog().get(0x1234));
        assertTrue(CharacterClass.reload(xml("<Objects><Object type='0x7ffe' id='Synthetic class'><Equipment>1,2,3</Equipment>"
            + "<MaxHitPoints max='100'>10</MaxHitPoints><MaxMagicPoints max='100'>10</MaxMagicPoints>"
            + "<Attack max='50'>10</Attack><Defense max='50'>10</Defense><Speed max='50'>10</Speed>"
            + "<Dexterity max='50'>10</Dexterity><HpRegen max='50'>10</HpRegen><MpRegen max='50'>10</MpRegen></Object></Objects>")));
        assertEquals("Synthetic class", CharacterClass.getName(0x7ffe));
        assertTrue(CharacterClass.hasStats(0x7ffe));
        assertFalse(CharacterClass.reload(malformed));
        assertEquals(100, CharacterClass.getLife(0x7ffe));
        tomato.backend.data.Entity partial = new tomato.backend.data.Entity(null, 1, 0);
        partial.objectType = 0x7ffe; partial.baseStats = new int[]{100, -1, -1, -1, -1, -1, -1, -1};
        tomato.gui.security.Player player = new tomato.gui.security.Player(partial);
        assertEquals(-1, player.statsMaxed()); assertEquals(0, player.statMissing()[0]);
        assertEquals(-1, player.statMissing()[1]);
    }

    @Test public void missingResourcePathHasActionableFailureAndCannotAdvanceSuccessMarker() throws Exception {
        AssetExtractor.setRealmResPath(temp.getRoot().toPath().resolve("missing.assets").toString());
        assertNull(AssetExtractor.assetFile());
        try { AssetExtractor.lastEdited("test"); fail("Missing assets must be reported"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("Choose resources.assets")); }
        String before = PropertiesManager.getProperty("lastModifiedTime");
        try { AssetExtractor.recover(null, "test", text -> {}); fail("No file was selected"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("readable")); }
        assertEquals(before, PropertiesManager.getProperty("lastModifiedTime"));
    }

    @Test public void recoveryPublishesGeneratedListsAndDefinitionsOnlyMarksSuccessAfterFailureIsRepaired() throws Exception {
        Path assets = Paths.get("assets");
        assertFalse("This test requires an isolated working directory without game assets", Files.exists(assets));
        String marker = PropertiesManager.getProperty("lastModifiedTime"), remembered = PropertiesManager.getProperty("realmResPath");
        Path source = temp.newFile("resources.assets").toPath();
        try {
            AssetExtractor.Extraction denied = (input, output) -> { throw new java.nio.file.AccessDeniedException("synthetic"); };
            try { AssetExtractor.recover(source.toFile(), "test", text -> {}, denied); fail("Extraction must fail"); }
            catch (java.nio.file.AccessDeniedException expected) { }
            assertEquals(marker, PropertiesManager.getProperty("lastModifiedTime"));
            AssetExtractor.recover(source.toFile(), "test", text -> {}, (input, output) -> {
                Path xml = output[2].toPath(); Files.createDirectories(xml);
                Files.write(xml.resolve("equip.xml"), ("<Objects><Object type='0x1234' id='Synthetic weapon'><Class>Equipment</Class>"
                    + "<SlotType>1</SlotType><feedPower>500</feedPower><Projectile id='0'><MinDamage>10</MinDamage>"
                    + "<MaxDamage>20</MaxDamage></Projectile></Object><Ground type='0x1' id='Synthetic tile'/></Objects>").getBytes(StandardCharsets.UTF_8));
                Files.write(xml.resolve("players.xml"), ("<Objects><Object type='0x7ffe' id='Synthetic class'><Equipment>1,2,3</Equipment>"
                    + "<MaxHitPoints max='100'>10</MaxHitPoints></Object></Objects>").getBytes(StandardCharsets.UTF_8));
                Files.write(xml.resolve("enchantments.xml"), "<Enchantments/>".getBytes(StandardCharsets.UTF_8));
            });
            assertEquals("Synthetic weapon", IdToAsset.objectName(0x1234));
            assertEquals("Synthetic tile", IdToAsset.tileName(1));
            assertNotNull(Equip.get(0x1234));
            assertEquals(Integer.valueOf(500), ParseEquipment.feedPowerCatalog().get(0x1234));
            assertTrue(AssetExtractor.hasUsableCache());
            assertFalse(AssetExtractor.needsExtraction("test"));
            assertEquals(source.toAbsolutePath().toString(), PropertiesManager.getProperty("realmResPath"));
            assertEquals(Files.getLastModifiedTime(source).toString() + "-test", PropertiesManager.getProperty("lastModifiedTime"));
        } finally {
            PropertiesManager.setProperties("lastModifiedTime", marker == null ? "" : marker);
            PropertiesManager.setProperties("realmResPath", remembered == null ? "" : remembered);
            if (Files.exists(assets)) try (java.util.stream.Stream<Path> paths = Files.walk(assets)) {
                for (Path path : (Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }

    @Test public void freshGenerationsIgnoreObsoleteXmlAndRefreshDungeonAndRemovedScalingRulesOnRepeatedRecovery() throws Exception {
        Path legacy = Paths.get("assets"); assertFalse(Files.exists(legacy));
        String marker = PropertiesManager.getProperty("lastModifiedTime"), remembered = PropertiesManager.getProperty("realmResPath");
        Path source = temp.newFile("resources.assets").toPath();
        try {
            Files.createDirectories(legacy.resolve("xml"));
            byte[] obsolete = "<obsolete-malformed".getBytes(StandardCharsets.UTF_8);
            Files.write(legacy.resolve("xml/obsolete.xml"), obsolete);
            Files.write(legacy.resolve("user-notes.txt"), "keep this unfamiliar file".getBytes(StandardCharsets.UTF_8));
            assertEquals(-1, tomato.realmshark.ParseDungeon.getPortalId("Setup Fixture Dungeon"));
            AssetExtractor.recover(source.toFile(), "first", text -> {}, bundle("First weapon", "2", "0x300", "0x400"));
            Path first = AssetCache.root();
            assertNotEquals(legacy, first);
            assertEquals("First weapon", IdToAsset.objectName(0x1234));
            assertTrue(tomato.realmshark.ParseDungeon.isDungeon("Setup Fixture Dungeon"));
            assertEquals(0x300, tomato.realmshark.ParseDungeon.getPortalId("Setup Fixture Dungeon"));
            assertArrayEquals(new int[]{0x400}, tomato.realmshark.ParseDungeon.getModIds("SETUP_FIXTURE"));
            tomato.backend.data.Entity player = new tomato.backend.data.Entity(null, 1, 0);
            packets.data.StatData wisdom = new packets.data.StatData(); wisdom.statValue = 75;
            player.stat.set(packets.data.enums.StatType.WISDOM_STAT, wisdom);
            tomato.backend.data.AbilityScalingManager scaling = tomato.backend.data.AbilityScalingManager.getInstance();
            assertEquals(50, scaling.calculateStatBonus(0x1234, player));
            byte[] committed = Files.readAllBytes(legacy.resolve("realmshark-cache.current"));
            Object weapon = Equip.get(0x1234);
            try {
                AssetExtractor.recover(source.toFile(), "failed", text -> {}, bundle("Must not publish", "invalid", "0x301", "0x401"));
                fail("Invalid scaling metadata must reject the whole generation before publication");
            } catch (java.io.IOException expected) { }
            assertEquals(first, AssetCache.root()); assertSame(weapon, Equip.get(0x1234));
            assertArrayEquals(committed, Files.readAllBytes(legacy.resolve("realmshark-cache.current")));
            assertEquals("First weapon", IdToAsset.objectName(0x1234));
            assertEquals(0x300, tomato.realmshark.ParseDungeon.getPortalId("Setup Fixture Dungeon"));
            assertEquals(50, scaling.calculateStatBonus(0x1234, player));
            for (int attempt = 0; attempt < 2; attempt++) {
                AssetExtractor.recover(source.toFile(), "replacement", text -> {}, bundle("Replacement weapon", null, "0x301", "0x401"));
                assertEquals(0, scaling.calculateStatBonus(0x1234, player));
                assertFalse(scaling.hasScaling(0x1234));
                assertEquals(0x301, tomato.realmshark.ParseDungeon.getPortalId("Setup Fixture Dungeon"));
                assertArrayEquals(new int[]{0x401}, tomato.realmshark.ParseDungeon.getModIds("SETUP_FIXTURE"));
                assertFalse(Files.exists(AssetCache.path("xml/obsolete.xml")));
                assertArrayEquals(obsolete, Files.readAllBytes(legacy.resolve("xml/obsolete.xml")));
                assertTrue(Files.exists(legacy.resolve("user-notes.txt")));
                assertTrue(Files.isRegularFile(first.resolve("ObjectID.list")));
            }
            // Reconstructing the process's cache selector reads the committed generation, not the legacy leftovers.
            java.lang.reflect.Method read = AssetCache.class.getDeclaredMethod("readCurrent"); read.setAccessible(true);
            Field current = AssetCache.class.getDeclaredField("current"); current.setAccessible(true); current.set(null, read.invoke(null));
            AssetExtractor.reloadAssetsOnRunningApp();
            assertEquals("Replacement weapon", IdToAsset.objectName(0x1234));
            assertEquals(0, scaling.calculateStatBonus(0x1234, player));
        } finally {
            PropertiesManager.setProperties("lastModifiedTime", marker == null ? "" : marker);
            PropertiesManager.setProperties("realmResPath", remembered == null ? "" : remembered);
            deleteFixtureAssets(legacy);
        }
    }

    @Test public void failedAtomicPointerPublicationPreservesThePreviousGeneration() throws Exception {
        Path legacy = Paths.get("assets"); assertFalse(Files.exists(legacy));
        try {
            Path first = AssetCache.createGeneration(); Files.write(first.resolve("sentinel"), new byte[]{1});
            AssetCache.publish(first, "first");
            byte[] before = Files.readAllBytes(legacy.resolve("realmshark-cache.current"));
            Path next = AssetCache.createGeneration();
            try {
                AssetCache.publish(next, "next", (temporary, target) -> { throw new AccessDeniedException("synthetic pointer denial"); });
                fail("Denied publication must fail");
            } catch (AccessDeniedException expected) { }
            assertEquals(first, AssetCache.root());
            assertArrayEquals(before, Files.readAllBytes(legacy.resolve("realmshark-cache.current")));
            assertArrayEquals(new byte[]{1}, Files.readAllBytes(first.resolve("sentinel")));
            AssetCache.discard(next); assertTrue(Files.exists(first));
        } finally { deleteFixtureAssets(legacy); }
    }

    private static AssetExtractor.Extraction bundle(String name, String scaling, String portal, String modifier) {
        return (input, output) -> {
            Path xml = output[2].toPath(); Files.createDirectories(xml);
            String activate = scaling == null ? "" : "<Activate scalingStat='WIS' statModDamage='" + scaling + "' statModScalingMin='50'>Ability</Activate>";
            Files.write(xml.resolve("equip.xml"), ("<Objects><Object type='0x1234' id='" + name + "'><Class>Equipment</Class><SlotType>1</SlotType>"
                + "<feedPower>500</feedPower><Projectile id='0'><MinDamage>10</MinDamage><MaxDamage>20</MaxDamage></Projectile>"
                + activate + "</Object><Ground type='1' id='Synthetic tile'/></Objects>").getBytes(StandardCharsets.UTF_8));
            Files.write(xml.resolve("players.xml"), ("<Objects><Object type='0x7ffe' id='Synthetic class'><Equipment>1,2,3</Equipment>"
                + "<MaxHitPoints max='100'>10</MaxHitPoints></Object></Objects>").getBytes(StandardCharsets.UTF_8));
            Files.write(xml.resolve("enchantments.xml"), "<Enchantments/>".getBytes(StandardCharsets.UTF_8));
            Files.write(xml.resolve("portals.xml"), ("<Objects><Object type='" + portal + "' id='Setup Fixture Portal'><Class>Portal</Class>"
                + "<DungeonName>Setup Fixture Dungeon</DungeonName><DungeonPortal/></Object></Objects>").getBytes(StandardCharsets.UTF_8));
            Files.write(xml.resolve("mods.xml"), ("<DungeonModifiers><DungeonModifier id='SETUP_FIXTURE' type='" + modifier + "'/></DungeonModifiers>").getBytes(StandardCharsets.UTF_8));
        };
    }
    private static void deleteFixtureAssets(Path assets) throws Exception {
        if (Files.exists(assets)) try (java.util.stream.Stream<Path> paths = Files.walk(assets)) {
            for (Path path : (Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
        }
    }
    private Path xml(String text) throws Exception { Path path = temp.newFile().toPath(); Files.write(path, text.getBytes(StandardCharsets.UTF_8)); return path; }
}
