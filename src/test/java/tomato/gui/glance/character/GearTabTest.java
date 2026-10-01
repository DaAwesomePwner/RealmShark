package tomato.gui.glance.character;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.Entity;
import tomato.backend.data.FieldCapture;
import tomato.backend.data.RosterDefinitions;
import tomato.backend.data.TomatoData;
import tomato.gui.kit.Collapsible;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.ItemSlot;
import tomato.gui.myinfo.BuildEstimates;
import tomato.realmshark.EnchantInfo;
import static org.junit.Assert.*;
import static tomato.gui.glance.character.SheetFixtures.*;

/** Gear: unknown vs empty vs no backpack, tier labels, and enchant rarity gems from the live character or, when not in game, the enchants last observed live. */
public class GearTabTest {
    private final DisplayModeModel mode = new DisplayModeModel(key -> null, (key, value) -> {});
    private static ItemSlot slot(JComponent tab, int index) { return named(tab, "character-gear-slot-" + index, ItemSlot.class); }

    @Test public void aCharacterNotInGameShowsTheEnchantsLastObservedLive() throws Exception {
        CharacterJournal.CharacterRecord r = record();
        r.equipmentEnchants = new String[] {"AAIE_wU", "", "", ""};
        SheetModel.Gear gear = model(r, account(), null).gear();
        SwingUtilities.invokeAndWait(() -> {
            GearTab tab = new GearTab(mode);
            tab.apply(gear);
            assertEquals(EnchantInfo.Rarity.UNCOMMON, slot(tab, 0).enchant().rarity());
        });
    }

    @Test public void emptyAndNotCapturedSlotsStayDistinctAndEquippedItemsShowTiers() throws Exception {
        SheetModel model = model(record(), account(), null);
        SwingUtilities.invokeAndWait(() -> {
            GearTab tab = new GearTab(mode);
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
            GearTab tab = new GearTab(mode);
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

    @Test public void liveEnchantGemsShowAndARecordWithoutSavedEnchantsShowsNone() throws Exception {
        Entity player = new Entity(null, 1, 0);
        StatData enchants = new StatData();
        enchants.stringStatValue = "AAIE_wU,AAIE,,!!!"; // weapon 1 unlocked slot, ability 0, armor 0, ring malformed
        player.stat.set(StatType.UNIQUE_DATA_STRING, enchants);
        BuildEstimates.Inputs inputs = BuildEstimates.Inputs.detach(player, null, TomatoData.PetAvailability.UNKNOWN);
        SheetModel playing = model(record(), account(), live(ACCOUNT, 7, "Sharkbait", inputs));
        SheetModel other = model(record(), account(), live(ACCOUNT, 8, "Ann", inputs));
        List<EnchantInfo> equipped = playing.gear().enchants();
        assertEquals(EnchantInfo.Rarity.UNCOMMON, equipped.get(0).rarity());
        assertEquals(EnchantInfo.Rarity.UNENCHANTED, equipped.get(1).rarity());
        assertEquals(EnchantInfo.Rarity.UNENCHANTED, equipped.get(2).rarity());
        assertSame(EnchantInfo.unreadable(), equipped.get(3));
        assertNull("Another character's enchants never describe this one", other.gear().enchants());
        SwingUtilities.invokeAndWait(() -> {
            GearTab tab = new GearTab(mode);
            tab.apply(playing.gear());
            // The live equipped four are 2001 (UT), empty, 2003 and 2004 (SheetFixtures.live).
            assertEquals(equipped.get(0), slot(tab, 0).enchant());
            String weapon = slot(tab, 0).getAccessibleContext().getAccessibleName();
            assertTrue(weapon, weapon.endsWith(" · UT · Uncommon · 1 enchant slot"));
            assertTrue(slot(tab, 0).getToolTipText().contains("Uncommon · 1 enchant slot"));
            assertNull("Empty slot: no enchant", slot(tab, 1).enchant());
            assertTrue(slot(tab, 2).getAccessibleContext().getAccessibleName().endsWith("Unenchanted"));
            assertTrue(slot(tab, 3).getAccessibleContext().getAccessibleName().endsWith("Enchant data unreadable"));
            tab.apply(other.gear());
            for (int i = 0; i < 4; i++) assertNull(slot(tab, i).enchant());
        });
    }

    /**
     * Labels come from the model (computed with the definitions the build used), so the tab never reads the global
     * RosterDefinitions.current() on the EDT, and a gear section whose slots did not change still repaints when the labels
     * arrive with the definitions.
     */
    @Test public void tierLabelsFollowTheModel() throws Exception {
        SheetModel.Gear loading = SheetModelBuilder.build(record(), account(), null, RosterDefinitions.empty(), null, NOW).gear();
        SheetModel.Gear loaded = model(record(), account(), null).gear();
        SwingUtilities.invokeAndWait(() -> {
            GearTab tab = new GearTab(mode);
            tab.apply(loading);
            assertEquals("No label while the definitions load: the slot name shows", "Weapon", named(tab, "character-gear-tier-0", JLabel.class).getText());
            assertEquals("Ring", named(tab, "character-gear-tier-3", JLabel.class).getText());
            tab.apply(loaded); // the same slots; only the labels arrived
            assertEquals("UT", named(tab, "character-gear-tier-0", JLabel.class).getText());
            assertEquals("T6", named(tab, "character-gear-tier-3", JLabel.class).getText());
            assertTrue("Inventory slots carry their label too", slot(tab, 4).getToolTipText().endsWith(" · T12"));
            assertEquals("Armor", named(tab, "character-gear-tier-2", JLabel.class).getText());
            tab.apply(loading);
            assertEquals("Labels leave with the model that had them", "Weapon", named(tab, "character-gear-tier-0", JLabel.class).getText());
        });
    }

    /**
     * While playing, every capture moves the record's times and hands the tab a new copy. The Analyst slot table re-renders only
     * when what it shows changed (a slot's item, state or field evidence, the character, or the definitions), so the user's
     * selection survives, and a real change still shows at once.
     */
    @Test public void theAnalystSlotTableIsNotRerenderedForAnUnchangedRecord() throws Exception {
        CharacterJournal.CharacterRecord first = record(), later = record(), changed = record();
        for (CharacterJournal.CharacterRecord r : List.of(first, later, changed)) r.fields.put("equipment.0", new FieldCapture(NOW - 3 * HOUR, "Captured"));
        later.lastSeen = first.lastSeen + 60_000; later.lastObservedAlive = later.lastSeen; // a later observation of the same character
        changed.lastSeen = later.lastSeen; changed.equipment[5] = 3_000; // …that picked up an item
        RosterDefinitions defs = defs();
        SwingUtilities.invokeAndWait(() -> {
            GearTab tab = new GearTab(mode);
            mode.set(DisplayModeModel.Mode.ANALYST);
            tab.analyst(first, defs);
            JTable table = named(tab, "character-equipment", JTable.class);
            table.setRowSelectionInterval(3, 3);
            AtomicInteger events = new AtomicInteger();
            table.getModel().addTableModelListener(e -> events.incrementAndGet());
            tab.analyst(later, defs);
            assertEquals("A copy showing the same slots re-renders nothing", 0, events.get());
            assertEquals("…so the selected row stays selected", 3, table.getSelectedRow());
            tab.analyst(changed, defs);
            assertTrue("A slot change re-renders at once", events.get() > 0);
            assertEquals(3_000, table.getModel().getValueAt(5, 4));
            events.set(0);
            tab.analyst(changed, defs());
            assertTrue("New definitions re-render (their details are part of each slot)", events.get() > 0);
            events.set(0);
            CharacterJournal.CharacterRecord other = record(); other.key = ACCOUNT + ":8"; other.characterId = 8;
            other.fields.putAll(changed.fields); other.lastSeen = changed.lastSeen; other.equipment[5] = 3_000;
            tab.analyst(other, defs);
            assertTrue("Another character re-renders even with identical slots", events.get() > 0);
        });
    }

    @Test public void theSlotTableIsAnalystOnly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            GearTab tab = new GearTab(mode);
            tab.analyst(record(), defs());
            Collapsible table = named(tab, "character-gear-slot-table", Collapsible.class);
            assertFalse(table.isVisible());
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertTrue(table.isVisible());
            assertEquals(28, named(tab, "character-equipment", JTable.class).getRowCount());
        });
    }
}
