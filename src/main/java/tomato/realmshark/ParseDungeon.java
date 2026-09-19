package tomato.realmshark;

import java.nio.file.Paths;
import packets.incoming.MapInfoPacket;

/** Dungeon lookups tolerate optional or unavailable extracted asset files. */
public class ParseDungeon {
    private static final DungeonCatalog CATALOG = DungeonCatalog.load(Paths.get("assets", "xml"));

    public static int[] getModIds(String dungeonString) {
        return CATALOG.getModIds(dungeonString);
    }

    public static int getPortalId(String name) {
        return CATALOG.getPortalId(name);
    }
    /** Return only an exact locally catalogued map label, never arbitrary server text. */
    public static String canonicalName(String name) {
        return CATALOG.canonicalName(name);
    }

    /** Affirmative content classification; a portal ID alone does not identify a dungeon. */
    public static boolean isDungeon(String name) {
        return CATALOG.isDungeon(name);
    }

    /** Exact map name wins (including hubs); only a locally known display label is a fallback. */
    public static String canonicalMapName(MapInfoPacket map) {
        return CATALOG.canonicalMapName(map);
    }
    /**
     * Build the canonical modifiers string from a MapInfoPacket by combining up to four modifier fields
     * and the optional dungeon grade. Example output: "BONUSCONSUMABLES;ENERGIZEDMINIONS_1;|D".
     */
    public static String getModifiersString(MapInfoPacket map) {
        if (map == null) return "";
        StringBuilder sb = new StringBuilder();
        // Append non-empty modifier fields in order
        if (map.dungeonModifiers != null && !map.dungeonModifiers.isEmpty()) {
            sb.append(map.dungeonModifiers);
        }
        if (map.dungeonModifiers2 != null && !map.dungeonModifiers2.isEmpty()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(map.dungeonModifiers2);
        }
        if (map.dungeonModifiers3 != null && !map.dungeonModifiers3.isEmpty()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(map.dungeonModifiers3);
        }
        if (map.dungeonModifiers4 != null && !map.dungeonModifiers4.isEmpty()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(map.dungeonModifiers4);
        }
        // Append grade as separate token like "|D"
        if (map.dungeonGrade != null && !map.dungeonGrade.isEmpty()) {
            if (sb.length() > 0) sb.append(';');
            sb.append('|').append(map.dungeonGrade);
        }
        return sb.toString();
    }
}
