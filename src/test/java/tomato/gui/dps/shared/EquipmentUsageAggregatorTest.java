package tomato.gui.dps.shared;

import org.junit.Test;
import tomato.backend.data.Damage;
import tomato.backend.data.Entity;
import tomato.backend.data.Equipment;
import tomato.realmshark.EnchantInfo;
import tomato.realmshark.ParseEnchants;
import static org.junit.Assert.*;

/** Usage is summed per item; the item is shown with the enchant variant that dealt the most damage. */
public class EquipmentUsageAggregatorTest {
    private static final String RARE = "AAIEAQD__w==", UNCOMMON = "AAIE_wU=";

    private static Damage hit(Entity owner, int damage, int weapon, String weaponEnchant) {
        Damage d = new Damage(owner, 0, damage);
        d.ownerInvntory = new int[] {weapon, 200, 300, 400};
        d.ownerEnchants = new String[] {weaponEnchant, null, "", ParseEnchants.UNENCHANTED_ENTRY};
        return d;
    }

    @Test public void theMostUsedItemShowsItsMostUsedEnchantVariant() {
        Entity mob = new Entity(null, 1, 0), owner = new Entity(null, 7, 0);
        mob.getDamageList().add(hit(owner, 10, 100, UNCOMMON)); // seen first
        mob.getDamageList().add(hit(owner, 50, 100, RARE));
        mob.getDamageList().add(hit(owner, 30, 101, RARE));
        EquipmentUsageAggregator usage = EquipmentUsageAggregator.of(mob);
        Equipment weapon = usage.getMostUsedItem(7, 0);
        assertEquals(100, weapon.id);
        assertEquals("The variant that dealt the most damage, not the first one seen", RARE, weapon.enchant);
        assertEquals("Usage is still summed per item across its variants", 60, weapon.dmg);
        assertEquals("The breakdown lists each item once", 2, usage.getSlotBreakdown(7, 0).size());
        assertEquals(90, usage.getSlotTotalDamage(7, 0));
    }

    @Test public void aTieKeepsTheFirstVariantSeen() {
        Entity mob = new Entity(null, 1, 0), owner = new Entity(null, 7, 0);
        mob.getDamageList().add(hit(owner, 20, 100, UNCOMMON));
        mob.getDamageList().add(hit(owner, 20, 100, RARE));
        assertEquals(UNCOMMON, EquipmentUsageAggregator.of(mob).getMostUsedItem(7, 0).enchant);
    }

    @Test public void oldHitsWithoutEnchantsReadAsNotRecordedNotAsTheTextNull() {
        Entity mob = new Entity(null, 1, 0), owner = new Entity(null, 7, 0);
        mob.getDamageList().add(hit(owner, 10, 100, UNCOMMON));
        Damage old = new Damage(owner, 0, 5);
        old.ownerInvntory = new int[] {100, 200, 300, 400};
        old.ownerEnchants = null; // a hit recorded without enchant strings
        mob.getDamageList().add(old);
        EquipmentUsageAggregator usage = EquipmentUsageAggregator.of(mob);
        assertNull(usage.getMostUsedItem(7, 1).enchant);
        assertSame(EnchantInfo.notRecorded(), EnchantInfo.ofRetained(usage.getMostUsedItem(7, 1).enchant));
        assertEquals("An older file's \"\" is kept as it was", "", usage.getMostUsedItem(7, 2).enchant);
        assertEquals(UNCOMMON, usage.getMostUsedItem(7, 0).enchant);
    }
}
