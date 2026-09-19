package tomato.realmshark;

import org.junit.Test;
import packets.incoming.MapInfoPacket;
import static org.junit.Assert.*;

/** Public catalog API checks also run from Gradle's asset-free build/ui-test working directory. */
public class ParseDungeonCatalogTest {
    @Test public void publicApiRecognizesContentAndReviewedAliases() {
        assertTrue(ParseDungeon.isDungeon("Ice Citadel"));
        assertTrue(ParseDungeon.isDungeon("Ocean Trench"));
        assertTrue(ParseDungeon.isDungeon("Lost Halls"));
        assertTrue(ParseDungeon.isDungeon("mgm2 Dungeon"));
        assertEquals("The Trials of Cronus", ParseDungeon.canonicalName("mgm2 Dungeon"));
        assertEquals(0x9cfd, ParseDungeon.getPortalId("Ice Citadel"));
        assertEquals(0x0730, ParseDungeon.getPortalId("Ocean Trench"));
        assertFalse(ParseDungeon.isDungeon("Vault"));
        assertFalse(ParseDungeon.isDungeon("Cloth Bazaar"));
        assertFalse(ParseDungeon.isDungeon("LOD Rock Dragon"));
        assertFalse(ParseDungeon.isDungeon("Unrecognized area"));
    }

    @Test public void publicMapResolverUsesExactNameThenKnownDisplayAndNeverRealmName() {
        MapInfoPacket map = new MapInfoPacket();
        map.name = "unfamiliar internal name";
        map.displayName = "Ocean Trench";
        assertEquals("Ocean Trench", ParseDungeon.canonicalMapName(map));
        map.name = "Nexus";
        assertEquals("Nexus", ParseDungeon.canonicalMapName(map));
        map.name = null;
        map.displayName = "unrecognized display";
        map.realmName = "Ice Citadel";
        assertEquals("Unrecognized area", ParseDungeon.canonicalMapName(map));
        assertEquals("Unrecognized area", ParseDungeon.canonicalMapName(null));
    }

    @Test public void modifierStringStillCombinesFourFieldsAndGradeInOrder() {
        MapInfoPacket map = new MapInfoPacket();
        map.dungeonModifiers = "BONUSCONSUMABLES";
        map.dungeonModifiers2 = "ENERGIZEDMINIONS_1";
        map.dungeonModifiers3 = "THIRD";
        map.dungeonModifiers4 = "FOURTH";
        map.dungeonGrade = "D";
        assertEquals("BONUSCONSUMABLES;ENERGIZEDMINIONS_1;THIRD;FOURTH;|D", ParseDungeon.getModifiersString(map));
        map.dungeonModifiers2 = "";
        map.dungeonModifiers3 = null;
        assertEquals("BONUSCONSUMABLES;FOURTH;|D", ParseDungeon.getModifiersString(map));
        assertEquals("", ParseDungeon.getModifiersString(null));
        assertEquals("", ParseDungeon.getModifiersString(new MapInfoPacket()));
        assertArrayEquals(new int[]{-15}, ParseDungeon.getModIds("UNKNOWN;|D"));
    }
}
