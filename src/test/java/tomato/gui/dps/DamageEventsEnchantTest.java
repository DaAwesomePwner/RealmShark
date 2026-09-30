package tomato.gui.dps;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.HashMap;
import org.junit.Test;
import tomato.realmshark.ParseEnchants;
import static org.junit.Assert.assertEquals;

/** Event detail names each slot's enchantments instead of printing raw encoded strings. */
public class DamageEventsEnchantTest {
    @Test public void namesEachSlotsEnchantsAndReadsARetainedEmptyAsNotRecorded() {
        HashMap<Short, ParseEnchants.Definition> saved = ParseEnchants.ENCHANT_DEFINITIONS;
        try {
            HashMap<Short, ParseEnchants.Definition> definitions = new HashMap<>();
            definitions.put((short) 42, new ParseEnchants.Definition("Attack Bonus I", "Increases Attack by 1.4"));
            ParseEnchants.ENCHANT_DEFINITIONS = definitions;
            assertEquals("  Weapon enchants: Rare · 2 enchant slots\n    Attack Bonus I — Increases Attack by 1.4\n    (empty slot)\n"
                    + "  Ability enchants: Enchants not recorded\n"
                    + "  Armor enchants: Enchant data unreadable\n"
                    + "  Ring enchants: Unenchanted\n",
                DamageEvents.enchantLines(new int[] {1, 2, 3, 4}, new String[] {encode(42, -1), "", "!!!", encode()}));
            assertEquals("  Weapon enchants: Rare · 2 enchant slots\n    Attack Bonus I — Increases Attack by 1.4\n    (empty slot)\n"
                    + "  Armor enchants: Enchant data unreadable\n"
                    + "  Ring enchants: Unenchanted\n",
                DamageEvents.enchantLines(new int[] {1, -1, 3, 4}, new String[] {encode(42, -1), "", "!!!", encode()}));
        } finally {
            ParseEnchants.ENCHANT_DEFINITIONS = saved;
        }
    }

    private static String encode(int... entries) {
        ByteBuffer buffer = ByteBuffer.allocate(3 + entries.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0);
        buffer.putShort((short) 1026);
        for (int entry : entries) buffer.putShort((short) entry);
        return Base64.getUrlEncoder().encodeToString(buffer.array());
    }
}
