package tomato.backend.data;

import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.realmshark.EnchantInfo;
import static org.junit.Assert.*;

/** New DPS hits keep a missing enchant stat apart from a known-unenchanted slot (older hits saved "" for both). */
public class DamageEnchantRetentionTest {
    private static Entity player(String enchants) {
        Entity p = new Entity(null, 1, 0);
        StatType[] slots = {StatType.INVENTORY_0_STAT, StatType.INVENTORY_1_STAT, StatType.INVENTORY_2_STAT, StatType.INVENTORY_3_STAT};
        for (int i = 0; i < 4; i++) { StatData s = new StatData(); s.statValue = 100 + i; p.stat.set(slots[i], s); }
        if (enchants != null) { StatData s = new StatData(); s.stringStatValue = enchants; p.stat.set(StatType.UNIQUE_DATA_STRING, s); }
        return p;
    }

    @Test public void aHitKeepsAMissingStatAsNotRecordedAndAnEmptyEntryAsUnenchanted() {
        assertArrayEquals(new String[] {null, null, null, null}, new Damage(player(null)).ownerEnchants);
        Damage hit = new Damage(player("AAIE_wU,,"));
        assertArrayEquals(new String[] {"AAIE_wU=", "AAIE", "AAIE", null}, hit.ownerEnchants);
        assertEquals(EnchantInfo.Rarity.UNENCHANTED, EnchantInfo.ofRetained(hit.ownerEnchants[1]).rarity());
        assertSame(EnchantInfo.notRecorded(), EnchantInfo.ofRetained(hit.ownerEnchants[3]));
        assertSame("Older hits' \"\" still reads as not recorded", EnchantInfo.notRecorded(), EnchantInfo.ofRetained(""));
    }
}
