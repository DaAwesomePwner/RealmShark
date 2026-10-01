package tomato.gui.stats;

import org.junit.Test;
import tomato.realmshark.EnchantInfo;
import static org.junit.Assert.assertEquals;

/** The archive detail lists enchant lines indented once under their rarity line. */
public class LootArchiveDetailTest {
    @Test public void enchantLinesAreIndentedOnceUnderTheRarity() {
        assertEquals("\nEnchantments: Rare · 2 enchant slots\n  Enchant names not available", LootArchiveClient.enchantmentsLine(EnchantInfo.ofSlotCount(2).text()));
        assertEquals("\nEnchantments: Not recorded", LootArchiveClient.enchantmentsLine(null));
    }
}
