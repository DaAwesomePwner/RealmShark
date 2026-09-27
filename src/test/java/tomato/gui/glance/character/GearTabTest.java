package tomato.gui.glance.character;

import java.util.List;
import javax.swing.*;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.gui.kit.Collapsible;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.ItemSlot;
import tomato.gui.myinfo.BuildEstimates;
import static org.junit.Assert.*;
import static tomato.gui.glance.character.SheetFixtures.*;

/** Gear: unknown vs empty vs no backpack, tier labels, and enchant rarity dots only for the live character. */
public class GearTabTest {
    private final DisplayModeModel mode = new DisplayModeModel(key -> null, (key, value) -> {});
    private static ItemSlot slot(JComponent tab, int index) { return named(tab, "character-gear-slot-" + index, ItemSlot.class); }

    @Test public void emptyAndNotCapturedSlotsStayDistinctAndEquippedItemsShowTiers() throws Exception {
        SheetModel model = model(record(), account(), null);
        SwingUtilities.invokeAndWait(() -> {
            GearTab tab = new GearTab(mode, id -> id == 2_001 ? "UT" : id == 2_004 ? "T6" : "");
            tab.apply(model.gear());
            assertEquals(ItemSlot.State.ITEM, slot(tab, 0).state());
            assertEquals(ItemSlot.State.EMPTY, slot(tab, 1).state());
            assertEquals(ItemSlot.State.UNKNOWN, slot(tab, 2).state());
            assertEquals("Empty slot", slot(tab, 1).getToolTipText());
            assertEquals("Slot not captured", slot(tab, 2).getToolTipText());
            assertEquals("UT", named(tab, "character-gear-tier-0", JLabel.class).getText());
            assertEquals("T6", named(tab, "character-gear-tier-3", JLabel.class).getText());
            assertEquals("Armor", named(tab, "character-gear-tier-2", JLabel.class).getText());
            assertEquals(ItemSlot.State.ITEM, slot(tab, 4).state());
            assertEquals(ItemSlot.State.EMPTY, slot(tab, 5).state());
        });
    }

    @Test public void backpackIsAbsentUnknownOrShownAsCaptured() throws Exception {
        CharacterJournal.CharacterRecord none = record(); none.hasBackpack = Boolean.FALSE;
        CharacterJournal.CharacterRecord partial = record(); partial.equipment[12] = 3_000;
        CharacterJournal.CharacterRecord owned = record(); owned.hasBackpack = Boolean.TRUE;
        for (int i = 12; i < 28; i++) owned.equipment[i] = -1;
        SwingUtilities.invokeAndWait(() -> {
            GearTab tab = new GearTab(mode, id -> "");
            tab.apply(model(none, account(), null).gear());
            assertTrue(named(tab, "character-gear-no-backpack", JLabel.class).isVisible());
            assertFalse(named(tab, "character-gear-backpack", JPanel.class).isVisible());
            tab.apply(model(record(), account(), null).gear());
            assertFalse(named(tab, "character-gear-no-backpack", JLabel.class).isVisible());
            assertTrue("Unknown and nothing captured", named(tab, "character-gear-backpack-unknown", JLabel.class).isVisible());
            for (int i = 12; i < 28; i++) assertEquals(ItemSlot.State.UNKNOWN, slot(tab, i).state());
            tab.apply(model(partial, account(), null).gear());
            assertFalse("Captured slots show as captured", named(tab, "character-gear-backpack-unknown", JLabel.class).isVisible());
            assertEquals(ItemSlot.State.ITEM, slot(tab, 12).state());
            assertEquals(ItemSlot.State.UNKNOWN, slot(tab, 13).state());
            tab.apply(model(owned, account(), null).gear());
            assertEquals(ItemSlot.State.EMPTY, slot(tab, 20).state());
        });
    }

    @Test public void enchantDotsShowOnlyForTheLiveCharacter() throws Exception {
        Entity player = new Entity(null, 1, 0);
        StatData enchants = new StatData();
        enchants.stringStatValue = "AAIE_wU,AAIE,,!!!"; // weapon 1 unlocked slot, ability 0, armor 0, ring malformed
        player.stat.set(StatType.UNIQUE_DATA_STRING, enchants);
        BuildEstimates.Inputs inputs = BuildEstimates.Inputs.detach(player, null, TomatoData.PetAvailability.UNKNOWN);
        SheetModel playing = model(record(), account(), live(ACCOUNT, 7, "Sharkbait", inputs));
        SheetModel other = model(record(), account(), live(ACCOUNT, 8, "Ann", inputs));
        assertEquals(List.of(1, 0, 0, -1), playing.gear().enchants());
        assertNull("Another character's enchants never describe this one", other.gear().enchants());
        SwingUtilities.invokeAndWait(() -> {
            GearTab tab = new GearTab(mode, id -> "");
            tab.apply(playing.gear());
            EnchantDots weapon = named(tab, "character-gear-enchant-0", EnchantDots.class);
            assertTrue(weapon.isVisible());
            assertEquals(1, weapon.slots());
            assertEquals("Uncommon · 1 enchant slot", weapon.getToolTipText());
            assertFalse("Empty slot: no dots", named(tab, "character-gear-enchant-1", EnchantDots.class).isVisible());
            assertEquals("Common · 0 enchant slots", named(tab, "character-gear-enchant-2", EnchantDots.class).getToolTipText());
            assertFalse("Malformed data: unknown, no dots", named(tab, "character-gear-enchant-3", EnchantDots.class).isVisible());
            tab.apply(other.gear());
            for (int i = 0; i < 4; i++) assertFalse(named(tab, "character-gear-enchant-" + i, EnchantDots.class).isVisible());
        });
    }

    @Test public void theSlotTableIsAnalystOnly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            GearTab tab = new GearTab(mode, id -> "");
            tab.analyst(record(), defs());
            Collapsible table = named(tab, "character-gear-slot-table", Collapsible.class);
            assertFalse(table.isVisible());
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertTrue(table.isVisible());
            assertEquals(28, named(tab, "character-equipment", JTable.class).getRowCount());
        });
    }
}
