package tomato.gui.kit;

import org.junit.Test;
import tomato.realmshark.ParseDungeon;
import tomato.realmshark.enums.CharacterStatistics;
import static org.junit.Assert.*;

/** Portal sprites: the catalog portal, else the dungeon-statistics sprite, else 0 (the placeholder glyph). */
public class PortalsTest {
    @Test public void theCatalogPortalWinsOverTheStatisticsSprite() {
        int portal = ParseDungeon.getPortalId("Ice Citadel");
        assertTrue("Bundled catalog portal", portal > 0);
        assertEquals(portal, Portals.spriteId("Ice Citadel"));
        assertNotEquals("The statistics sprite differs here, so the order shows", CharacterStatistics.statByName("Ice Citadel").getSpriteId(),
            Portals.spriteId("Ice Citadel"));
    }

    @Test public void aMapWithoutACatalogPortalFallsBackToItsStatisticsSprite() {
        assertTrue("Fixture: the bundled catalog has no portal for it", ParseDungeon.getPortalId("Santas Workshop") <= 0);
        assertEquals(CharacterStatistics.statByName("Santas Workshop").getSpriteId(), Portals.spriteId("Santas Workshop"));
        assertTrue(Portals.spriteId("Santas Workshop") > 0);
    }

    @Test public void unknownEmptyAndSpriteLessNamesAreZero() {
        assertEquals(0, Portals.spriteId(null));
        assertEquals(0, Portals.spriteId(""));
        assertEquals(0, Portals.spriteId("Synthetic Unknown Area"));
        assertEquals("A statistic without a sprite (−1) is the placeholder too", 0, Portals.spriteId("Kills"));
        assertTrue(Sprites.isPlaceholder(Sprites.sprite(Portals.spriteId("Synthetic Unknown Area"), 24)));
    }
}
