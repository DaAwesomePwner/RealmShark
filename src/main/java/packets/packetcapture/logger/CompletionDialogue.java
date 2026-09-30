package packets.packetcapture.logger;

import packets.incoming.TextPacket;

/**
 * Final-boss lines that prove a dungeon was cleared where the server sends no victory notification. Shared by the activity
 * journal (run completion) and the DPS presence timeline (the end of the dungeon).
 */
public final class CompletionDialogue {
    private CompletionDialogue() { }

    /**
     * The evidence text (for example "Final boss dialogue: Void Entity") when {@code p} is a recognised final-boss line in
     * {@code map} (a canonical map name, {@code ParseDungeon.canonicalMapName}), else null. Only lines from an NPC (a name
     * starting with '#') count.
     */
    public static String evidence(String map, TextPacket p) {
        if (map == null || p == null || p.name == null || !p.name.startsWith("#") || p.text == null) return null;
        if ("Moonlight Village".equals(map) && (
                ("#Kitsune Umi".equals(p.name) && "This fully concludes the Moonlight Festival!".equals(p.text)) ||
                ("#Dancer Miko".equals(p.name) && "Thank you all for coming tonight.".equals(p.text)) ||
                ("#Umi, Goddess of Revelry".equals(p.name) && "This fully concludes the Moonlight Festival.".equals(p.text))))
            return "Final boss dialogue: " + p.name.substring(1);
        if ("The Void".equals(map) && "#Void Entity".equals(p.name)
                && "You fools... You can never truly defeat me! I am in all of you! I AM all of you!".equals(p.text))
            return "Final boss dialogue: Void Entity";
        if ("The Shatters".equals(map) && (
                ("#The Accursed King".equals(p.name) && "...do you truly think your end will be any different?".equals(p.text)) ||
                ("#King Azamoth".equals(p.name) && "This fate is mine to bear... not hers.".equals(p.text))))
            return "Final boss dialogue: " + p.name.substring(1);
        return null;
    }
}
