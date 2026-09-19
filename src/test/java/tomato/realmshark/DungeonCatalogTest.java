package tomato.realmshark;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.incoming.MapInfoPacket;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class DungeonCatalogTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path directory() { return temp.getRoot().toPath(); }
    private void write(String name, String xml) throws Exception {
        Path path = directory().resolve(name);
        Files.createDirectories(path.getParent());
        Files.write(path, xml.getBytes(StandardCharsets.UTF_8));
    }
    private String portal(String id, String objectId, String name, String display, String flags) {
        return "<Object type='" + id + "' id='" + objectId + "'><Class>Portal</Class>"
                + "<DungeonName>" + name + "</DungeonName><DisplayId>" + display + "</DisplayId>"
                + flags + "</Object>";
    }
    private MapInfoPacket map(String name, String display) {
        MapInfoPacket map = new MapInfoPacket();
        map.name = name;
        map.displayName = display;
        return map;
    }
    private void baseFiles() throws Exception {
        write("mods.xml", "<DungeonModifiers><DungeonModifier id='FEEBLEMINIONS_1' type='0x62'/></DungeonModifiers>");
        write("portals.xml", "<Objects><Object type='0x1234'><DungeonName>The Shatters</DungeonName></Object></Objects>");
    }
    @Test public void absentMods2PreservesModifiersAndPortalIcons() throws Exception {
        baseFiles();
        DungeonCatalog catalog = DungeonCatalog.load(directory());
        assertArrayEquals(new int[]{98,-15}, catalog.getModIds("FEEBLEMINIONS_1;|D"));
        assertEquals(0x1234,catalog.getPortalId("The Shatters"));
    }
    @Test public void additionalModifierFileStillLoadsWhenPresent() throws Exception {
        baseFiles(); write("mods2.xml", "<DungeonModifiers><DungeonModifier id='EXTRA' type='0x8001'/></DungeonModifiers>");
        assertArrayEquals(new int[]{98,32769},DungeonCatalog.load(directory()).getModIds("FEEBLEMINIONS_1;EXTRA"));
    }
    @Test public void missingAssetsAndUnknownNamesHaveSafeFallbacks() {
        DungeonCatalog catalog = DungeonCatalog.load(directory());
        assertArrayEquals(new int[]{-11},catalog.getModIds("UNKNOWN;|S"));
        assertArrayEquals(new int[0],catalog.getModIds(null));
        assertEquals(-1,catalog.getPortalId("Unknown"));
        assertEquals(1796,catalog.getPortalId("Realm of the Mad God"));
    }
    @Test public void malformedOptionalFileCannotDiscardValidFiles() throws Exception {
        baseFiles(); write("mods2.xml", "<broken>");
        DungeonCatalog catalog = DungeonCatalog.load(directory());
        assertArrayEquals(new int[]{98},catalog.getModIds("FEEBLEMINIONS_1"));
        assertEquals(0x1234,catalog.getPortalId("The Shatters"));
    }
    @Test public void invalidRowDoesNotPreventLaterValidModifiers() throws Exception {
        baseFiles();
        write("mods2.xml", "<DungeonModifiers><DungeonModifier id='BAD' type='bad'/><DungeonModifier id='GOOD' type='0x123'/></DungeonModifiers>");
        assertArrayEquals(new int[]{291},DungeonCatalog.load(directory()).getModIds("BAD;GOOD"));
    }

    @Test public void bundledDungeonsWorkWithNoAssetDirectory() {
        DungeonCatalog catalog = DungeonCatalog.load(directory().resolve("absent-assets"));
        String[] dungeons = {"Ice Citadel", "Ocean Trench", "Ice Cave", "Lost Halls", "The Void",
                "Cultist Hideout", "Fungal Cavern", "Crystal Cavern", "The Nest", "Plagued Nest",
                "Kogbold Steamworks", "Advanced Kogbold Steamworks", "Oryx's Sanctuary",
                "Moonlight Village", "The Shatters", "Battle for the Nexus", "The Tavern",
                "Spectral Penitentiary", "Heroic Undead Lair", "Infernal Abyss of Demons"};
        for (String name : dungeons) {
            assertTrue(name, catalog.isDungeon(name));
            assertEquals(name, catalog.canonicalName(name));
            assertTrue(name, catalog.getPortalId(name) > 0);
        }
        assertEquals(0x9cfd, catalog.getPortalId("Ice Citadel"));
        assertEquals(0x748b, catalog.getPortalId("Ice Cave"));
        assertEquals(0x0730, catalog.getPortalId("Ocean Trench"));
        assertEquals(0xb024, catalog.getPortalId("Lost Halls"));
        assertEquals(0xb063, catalog.getPortalId("Cultist Hideout"));
        assertEquals(0x273a, catalog.getPortalId("Crystal Cavern"));
    }

    @Test public void knownHubsTutorialsAndTestMapsAreNotDungeons() {
        DungeonCatalog catalog = DungeonCatalog.load(directory());
        String[] excluded = {"Realm of the Mad God", "Random Realm", "Nexus", "Vault", "Guild Hall",
                "Guild Hall 2", "Guild Hall 3", "Guild Hall 4", "Pet Yard", "Daily Quest Room",
                "Daily Login Room", "Interregnum Daily Quest Room", "Grand Bazaar", "Court of Oryx",
                "Tutorial", "Kitchen", "Exalted Kitchen", "Nexus Explanation", "Vault Explanation",
                "Guild Explanation", "Admin Arena", "LOD Rock Dragon", "LH Test", "LH Boss Test",
                "DPS Test", "RRCubeGodTest", "KSW Encounter", "GC encounter", "WhiteSnakeTest",
                "Easter Treasure Rooms Test Maps", "Chess"};
        for (String name : excluded) {
            assertFalse(name, catalog.isDungeon(name));
            assertEquals(name, catalog.canonicalName(name));
        }
        assertTrue(catalog.isDungeon("Battle for the Nexus"));
        assertFalse(catalog.isDungeon(null));
        assertFalse(catalog.isDungeon("Unrecognized area"));
    }

    @Test public void reviewedAliasesResolveWithoutExtractedAssets() {
        DungeonCatalog catalog = DungeonCatalog.load(directory());
        String[][] aliases = {{"mgm2 Dungeon", "The Trials of Cronus"},
                {"Oryx Pandemonium Decaract", "Mad God Mayhem"},
                {"Santa Workshop", "Santa's Workshop"},
                {"Cave of A Thousand Treasures", "Cave of a Thousand Treasures"}};
        for (String[] pair : aliases) {
            assertEquals(pair[1], catalog.canonicalName(pair[0]));
            assertEquals(catalog.getPortalId(pair[1]), catalog.getPortalId(pair[0]));
            assertTrue(catalog.isDungeon(pair[0]));
        }
        assertEquals("Grand Bazaar", catalog.canonicalName("Cloth Bazaar"));
        assertEquals("Grand Bazaar", catalog.canonicalName("Bazaar"));
        assertFalse(catalog.isDungeon("Cloth Bazaar"));
        assertFalse(catalog.isDungeon("Rock Dragon Test"));
        assertEquals("Unrecognized area", catalog.canonicalName("Lost Halls Portal"));
        assertEquals("Unrecognized area", catalog.canonicalName("lost halls"));
        assertEquals("Unrecognized area", catalog.canonicalName(" Lost Halls "));
    }

    @Test public void exactMapNameWinsAndOnlyCataloguedDisplayNameIsAFallback() {
        DungeonCatalog catalog = DungeonCatalog.load(directory());
        assertEquals("Ice Citadel", catalog.canonicalMapName(map("new-internal-name", "Ice Citadel")));
        assertEquals("Ocean Trench", catalog.canonicalMapName(map(null, "Ocean Trench")));
        assertEquals("The Trials of Cronus", catalog.canonicalMapName(map("mgm2 Dungeon", "Vault")));
        assertEquals("Vault", catalog.canonicalMapName(map("Vault", "Lost Halls")));
        assertEquals("Grand Bazaar", catalog.canonicalMapName(map("Cloth Bazaar", "Ocean Trench")));
        assertEquals("LOD Rock Dragon", catalog.canonicalMapName(map("LOD Rock Dragon", "Lair of Draconis")));
        assertEquals("Realm of the Mad God", catalog.canonicalMapName(map("Realm of the Mad God", "The Void")));
        MapInfoPacket unknown = map("player-controlled\ntext", "<html>unknown display</html>");
        unknown.realmName = "Lost Halls";
        assertEquals("Unrecognized area", catalog.canonicalMapName(unknown));
        assertEquals("player-controlled\ntext", unknown.name);
        assertEquals("<html>unknown display</html>", unknown.displayName);
        assertEquals("Unrecognized area", catalog.canonicalMapName(null));
        assertEquals("Unrecognized area", catalog.canonicalName(null));
        assertEquals("Unrecognized area", catalog.canonicalName(""));
    }

    @Test public void recursiveOptionalDefinitionsProvideNewContentAndExactDisplayAliases() throws Exception {
        write("nested/newContent.xml", "<Objects>"
                + portal("0x301", "New Content Portal", "New Content", "New Content Display", "<DungeonPortal/>")
                + portal("0x302", "New Lobby Portal", "New Lobby", "Lobby Display", "<SafeZonePortal/>")
                + "<Object type='0x303'><Class>Character</Class><DungeonName>Not a map</DungeonName>"
                + "<DisplayId>Enemy name</DisplayId><DungeonPortal/></Object></Objects>");
        DungeonCatalog catalog = DungeonCatalog.load(directory());
        assertTrue(catalog.isDungeon("New Content"));
        assertTrue(catalog.isDungeon("New Content Display"));
        assertEquals(0x301, catalog.getPortalId("New Content Display"));
        assertEquals("New Content", catalog.canonicalName("New Content Display"));
        assertEquals("New Content", catalog.canonicalMapName(map("unfamiliar", "New Content Display")));
        assertEquals("New Lobby", catalog.canonicalMapName(map("New Lobby", "New Content Display")));
        assertFalse(catalog.isDungeon("New Lobby"));
        assertEquals("Unrecognized area", catalog.canonicalName("Enemy name"));
        assertFalse(catalog.isDungeon("Not a map"));
    }

    @Test public void templatesSupportPortalClassificationWithoutPromotingEveryNamedMap() throws Exception {
        write("dungeonTemplates.xml", "<DungeonTemplates><DungeonModTemplate id='Template Dungeon'/>"
                + "<DungeonModTemplate id='Template Only'/><DungeonModTemplate id='Vault'/>"
                + "<DungeonModTemplate id='Safe Lobby'/></DungeonTemplates>");
        write("extra.xml", "<Objects>"
                + portal("0x310", "Template Portal", "Template Dungeon", "Template Dungeon", "")
                + portal("0x311", "Plain Portal", "Plain Map", "Plain Map", "")
                + portal("0x312", "Safe Portal", "Safe Lobby", "Safe Lobby", "<DungeonPortal/><SafeZonePortal/>")
                + "</Objects>");
        DungeonCatalog catalog = DungeonCatalog.load(directory());
        assertTrue(catalog.isDungeon("Template Dungeon"));
        assertFalse(catalog.isDungeon("Plain Map"));
        assertEquals("Plain Map", catalog.canonicalName("Plain Map"));
        assertFalse(catalog.isDungeon("Template Only"));
        assertFalse(catalog.isDungeon("Vault"));
        assertFalse(catalog.isDungeon("Safe Lobby"));
    }

    @Test public void optionalDungeonFlagsCannotOverrideReviewedNonContent() throws Exception {
        write("extra.xml", "<Objects>"
                + portal("0x321", "Court Portal", "Court of Oryx", "Court of Oryx", "<DungeonPortal/>")
                + portal("0x322", "Test Portal", "LOD Rock Dragon", "Rock Dragon Test", "<DungeonPortal/>")
                + portal("0x323", "Arena Portal", "Admin Arena", "Admin Arena", "<DungeonPortal/>")
                + "</Objects>");
        DungeonCatalog catalog = DungeonCatalog.load(directory());
        assertFalse(catalog.isDungeon("Court of Oryx"));
        assertFalse(catalog.isDungeon("LOD Rock Dragon"));
        assertFalse(catalog.isDungeon("Rock Dragon Test"));
        assertFalse(catalog.isDungeon("Admin Arena"));
        assertEquals(0x321, catalog.getPortalId("Court of Oryx"));
    }

    @Test public void ambiguousDisplaysDoNotInventAliasesOrOverwriteKnownNames() throws Exception {
        write("extra.xml", "<Objects>"
                + portal("0x331", "One Portal", "Area One", "Shared Display", "<DungeonPortal/>")
                + portal("0x332", "Two Portal", "Area Two", "Shared Display", "<DungeonPortal/>")
                + portal("0x333", "Test Portal", "Admin Arena", "The Shatters", "<DungeonPortal/>")
                + "</Objects>");
        DungeonCatalog catalog = DungeonCatalog.load(directory());
        assertEquals("Unrecognized area", catalog.canonicalName("Shared Display"));
        assertFalse(catalog.isDungeon("Shared Display"));
        assertEquals("The Shatters", catalog.canonicalName("The Shatters"));
        assertEquals(0x727e, catalog.getPortalId("The Shatters"));
        assertEquals("Admin Arena", catalog.canonicalMapName(map("Admin Arena", "The Shatters")));
        assertEquals("Unrecognized area", catalog.canonicalMapName(map("new internal name", "The Shatters")));
        assertEquals("Unrecognized area", catalog.canonicalMapName(map("new internal name", "Shared Display")));
    }

    @Test public void bundledSharedDisplayNamesRequireAnExactInternalName() {
        DungeonCatalog catalog = DungeonCatalog.load(directory());
        for (String display : new String[]{"The Tavern", "The Shatters", "Oryx's Sanctuary"}) {
            assertEquals(display, catalog.canonicalMapName(map(display, display)));
            assertEquals("Unrecognized area", catalog.canonicalMapName(map("unfamiliar map", display)));
        }
        assertEquals("Beer Encounter Arena", catalog.canonicalMapName(map("Beer Encounter Arena", "The Tavern")));
        assertFalse(catalog.isDungeon("Beer Encounter Arena"));
    }

    @Test public void newCollisionWithCanonicalLabelIsNotADisplayOnlyMatch() throws Exception {
        write("extra.xml", "<Objects>"
                + portal("0x351", "Dungeon Portal", "Fresh Dungeon", "Fresh Dungeon", "<DungeonPortal/>")
                + portal("0x352", "Lobby Portal", "Fresh Lobby", "Fresh Dungeon", "<SafeZonePortal/>")
                + "</Objects>");
        DungeonCatalog catalog = DungeonCatalog.load(directory());
        assertEquals("Fresh Dungeon", catalog.canonicalMapName(map("Fresh Dungeon", "Fresh Dungeon")));
        assertEquals("Fresh Lobby", catalog.canonicalMapName(map("Fresh Lobby", "Fresh Dungeon")));
        assertEquals("Unrecognized area", catalog.canonicalMapName(map("unfamiliar map", "Fresh Dungeon")));
    }

    @Test public void partialAssetsCannotPromoteKnownAmbiguousDisplayNames() throws Exception {
        write("partial.xml", "<Objects>"
                + portal("0x65db", "Crab Arena Portal", "Crab Arena", "Mysterious Arena", "<DungeonPortal/>")
                + portal("0x02fb", "Treasure Portal", "Oryxmania Treasure Room", "Treasure Room", "<DungeonPortal/>")
                + "<Object>");
        DungeonCatalog catalog = DungeonCatalog.load(directory());
        assertEquals("Unrecognized area", catalog.canonicalMapName(map("unfamiliar map", "Mysterious Arena")));
        assertEquals("Unrecognized area", catalog.canonicalMapName(map("unfamiliar map", "Treasure Room")));
        assertEquals("Crab Arena", catalog.canonicalMapName(map("Crab Arena", "Mysterious Arena")));
        assertEquals("Oryxmania Treasure Room", catalog.canonicalMapName(map("Oryxmania Treasure Room", "Treasure Room")));
    }

    @Test public void normalPortalWinsOverLockedEventSoloAndTemporaryVariants() throws Exception {
        String variants = portal("0x401", "Choice Portal", "Choice", "Choice", "<LockedPortal/><DungeonPortal/>")
                + portal("0x402", "Choice Event Portal", "Choice", "Choice", "<DungeonPortal/>")
                + portal("0x403", "Choice Solo Portal", "Choice", "Choice", "<DungeonPortal/>")
                + portal("0x404", "Choice Portal Temp", "Choice", "Choice", "<DungeonPortal/>");
        String normal = portal("0x499", "Choice Portal", "Choice", "Choice", "<DungeonPortal/>");
        write("extra.xml", "<Objects>" + variants + normal + "</Objects>");
        assertEquals(0x499, DungeonCatalog.load(directory()).getPortalId("Choice"));
        write("extra.xml", "<Objects>" + normal + variants + "</Objects>");
        assertEquals(0x499, DungeonCatalog.load(directory()).getPortalId("Choice"));
    }

    @Test public void existingPortalsFileWinsOverNewDefinitionsAndTiesAreDeterministic() throws Exception {
        write("portals.xml", "<Objects>"
                + portal("0x599", "One Portal", "Choice", "Choice", "<DungeonPortal/>")
                + portal("0x598", "Another Portal", "Choice", "Choice", "<DungeonPortal/>")
                + "</Objects>");
        write("aaa.xml", "<Objects>"
                + portal("0x501", "Other Portal", "Choice", "Choice", "<DungeonPortal/>") + "</Objects>");
        assertEquals(0x598, DungeonCatalog.load(directory()).getPortalId("Choice"));
    }

    @Test public void duplicateMetadataAddsClassificationWithoutChangingPreferredIcon() throws Exception {
        write("portals.xml", "<Objects>"
                + portal("0x590", "Content Portal", "New Dungeon", "New Dungeon", "")
                + portal("0x591", "Lobby Portal", "New Hub", "New Hub", "<DungeonPortal/>")
                + "</Objects>");
        write("extra.xml", "<Objects>"
                + portal("0x592", "Content Event Portal", "New Dungeon", "New Dungeon", "<DungeonPortal/>")
                + portal("0x593", "Lobby Portal", "New Hub", "New Hub", "<SafeZonePortal/>")
                + "</Objects>");
        DungeonCatalog catalog = DungeonCatalog.load(directory());
        assertTrue(catalog.isDungeon("New Dungeon"));
        assertEquals(0x590, catalog.getPortalId("New Dungeon"));
        assertFalse(catalog.isDungeon("New Hub"));
        assertEquals("New Hub", catalog.canonicalMapName(map("New Hub", "New Dungeon")));
    }

    @Test public void malformedExtraXmlKeepsCompleteEntriesAndOtherFiles() throws Exception {
        baseFiles();
        write("aaa-broken.xml", "<Objects>"
                + portal("0x601", "First Portal", "First Dungeon", "First Dungeon", "<DungeonPortal/>")
                + "<Object type='0x602'><Class>Portal</Class><DungeonName>Incomplete</DungeonName>");
        write("zzz-valid.xml", "<Objects>"
                + portal("bad", "Bad Portal", "Bad Entry", "Bad Entry", "<DungeonPortal/>")
                + portal("0x603", "Last Portal", "Last Dungeon", "Last Dungeon", "<DungeonPortal/>")
                + "</Objects>");
        DungeonCatalog catalog = DungeonCatalog.load(directory());
        assertTrue(catalog.isDungeon("First Dungeon"));
        assertTrue(catalog.isDungeon("Last Dungeon"));
        assertEquals(-1, catalog.getPortalId("Incomplete"));
        assertEquals(-1, catalog.getPortalId("Bad Entry"));
        assertEquals(0x1234, catalog.getPortalId("The Shatters"));
        assertEquals(0x9cfd, catalog.getPortalId("Ice Citadel"));
        assertArrayEquals(new int[]{98, -15}, catalog.getModIds("FEEBLEMINIONS_1;|D"));
    }

    @Test public void externalEntityDefinitionsAreNotLoaded() throws Exception {
        write("external.xml", "<!DOCTYPE Objects [<!ENTITY injected SYSTEM 'file:///not-a-real-catalog-file'>]>"
                + "<Objects>" + portal("0x700", "External Portal", "&injected;", "External", "<DungeonPortal/>")
                + "</Objects>");
        DungeonCatalog catalog = DungeonCatalog.load(directory());
        assertEquals("Unrecognized area", catalog.canonicalName("External"));
        assertTrue(catalog.isDungeon("Ocean Trench"));
    }

    @Test public void modifierOrderDuplicatesAndGradeIdsRemainCompatible() throws Exception {
        baseFiles();
        write("mods2.xml", "<DungeonModifiers><DungeonModifier id='EXTRA' type='0x8001'/></DungeonModifiers>");
        DungeonCatalog catalog = DungeonCatalog.load(directory());
        assertArrayEquals(new int[]{98, 32769, 98, -11, -12, -13, -14, -15},
                catalog.getModIds("FEEBLEMINIONS_1;UNKNOWN;EXTRA;FEEBLEMINIONS_1;|S;|A;|B;|C;|D"));
        assertArrayEquals(new int[0], catalog.getModIds(""));
        assertArrayEquals(new int[0], catalog.getModIds("UNKNOWN"));
    }
}
