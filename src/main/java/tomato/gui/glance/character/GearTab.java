package tomato.gui.glance.character;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Objects;
import java.util.function.IntFunction;
import javax.swing.*;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.character.CharacterEquipmentPanel;
import tomato.gui.kit.*;
import static tomato.gui.glance.character.SheetViews.*;

/**
 * Sheet › Gear (spec §6.2): four large equipped slots with tier labels and, for the character in game only, enchant rarity dots;
 * then the inventory (8) and backpack (16) as a sprite grid. Unknown and empty slots stay distinct; each slot's tooltip names its
 * item. The 28-row slot table with field evidence is an Analyst-only Collapsible. EDT only.
 */
final class GearTab extends JPanel {
    private final ItemSlot[] slots = new ItemSlot[28];
    private final KitText[] tiers = new KitText[4];
    private final EnchantDots[] dots = new EnchantDots[4];
    private final JPanel backpack = named(row(), "character-gear-backpack");
    private final KitText noBackpack = named(KitText.caption("No backpack"), "character-gear-no-backpack");
    private final KitText backpackUnknown = named(KitText.caption("Backpack contents not captured yet"), "character-gear-backpack-unknown");
    private final CharacterEquipmentPanel table = new CharacterEquipmentPanel();
    private final Collapsible slotTable;
    private final IntFunction<String> tierOf;
    private SheetModel.Gear shown;

    GearTab(DisplayModeModel mode) { this(mode, ItemTiers::label); }

    GearTab(DisplayModeModel mode, IntFunction<String> tierOf) {
        super(new BorderLayout());
        this.tierOf = tierOf;
        setOpaque(false);
        setName("character-gear");
        JPanel equipped = clear(new FlowLayout(FlowLayout.LEADING, Tokens.M, 0));
        for (int i = 0; i < 4; i++) {
            slots[i] = named(new ItemSlot(48), "character-gear-slot-" + i);
            tiers[i] = named(KitText.caption(SLOTS[i]), "character-gear-tier-" + i);
            dots[i] = named(new EnchantDots(), "character-gear-enchant-" + i);
            equipped.add(beside(slots[i], clear(new FlowLayout(FlowLayout.CENTER, Tokens.XS, 0), tiers[i], dots[i]), BorderLayout.SOUTH, 2));
        }
        JPanel inventory = row();
        for (int i = 4; i < 12; i++) inventory.add(slots[i] = named(new ItemSlot(32), "character-gear-slot-" + i));
        for (int i = 12; i < 28; i++) backpack.add(slots[i] = named(new ItemSlot(32), "character-gear-slot-" + i));
        slotTable = named(new Collapsible("character-equipment-table", "Slot table", table, false), "character-gear-slot-table");
        add(KitLayouts.stack(Tokens.M, card(mode, "Equipped", equipped, "character-gear-equipped"),
            card(mode, "Inventory", inventory, "character-gear-inventory"),
            card(mode, "Backpack", KitLayouts.stack(Tokens.XS, noBackpack, backpackUnknown, backpack), "character-gear-backpack-card"), slotTable),
            BorderLayout.NORTH);
        mode.bind(this, value -> slotTable.setVisible(value == DisplayModeModel.Mode.ANALYST));
        apply(null);
    }

    /** EDT. A gear section equal to the shown one is skipped. */
    void apply(SheetModel.Gear gear) {
        if (gear != null && gear.equals(shown)) return;
        shown = gear;
        boolean backpackCaptured = false;
        for (int i = 0; i < 28; i++) {
            int id = gear == null ? -1 : gear.slots().get(i);
            String tier = id > 0 ? Objects.toString(tierOf.apply(id), "") : "";
            if (id > 0) slots[i].setItem(id, tier); else if (id == 0) slots[i].setEmpty(); else slots[i].setUnknown();
            if (i >= 12 && id >= 0) backpackCaptured = true;
            if (i < 4) {
                tiers[i].setText(tier.isEmpty() ? SLOTS[i] : tier);
                // Enchant rarity is decoded from the character in game only; saved records never show dots.
                dots[i].set(gear == null || gear.enchants() == null || id <= 0 ? -1 : gear.enchants().get(i));
            }
        }
        Boolean has = gear == null ? null : gear.hasBackpack();
        noBackpack.setVisible(Boolean.FALSE.equals(has));
        backpack.setVisible(!Boolean.FALSE.equals(has));
        backpackUnknown.setVisible(has == null && !backpackCaptured);
        revalidate();
        repaint();
    }

    /** EDT: the Analyst slot table (per-slot field evidence) for the sheet's detached record copy. */
    void analyst(CharacterJournal.CharacterRecord record, RosterDefinitions definitions) { table.showRecord(record, definitions); }
}
