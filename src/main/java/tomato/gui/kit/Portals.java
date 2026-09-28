package tomato.gui.kit;

import tomato.realmshark.ParseDungeon;
import tomato.realmshark.enums.CharacterStatistics;

/** Portal sprites for areas (Home's cards, the run feed and the run recap); pass the id to {@link Sprites#sprite}. */
public final class Portals {
    private Portals() {}

    /**
     * The portal sprite's object id for a map name: the catalog portal, else the dungeon-statistics sprite, else 0 (the kit's
     * placeholder glyph). Constant time; safe on the EDT.
     */
    public static int spriteId(String map) {
        if (map == null || map.isEmpty()) return 0;
        int portal = ParseDungeon.getPortalId(map);
        if (portal > 0) return portal;
        CharacterStatistics stat = CharacterStatistics.statByName(map);
        return stat == null || stat.getSpriteId() <= 0 ? 0 : stat.getSpriteId();
    }
}
