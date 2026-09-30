package tomato.gui.glance.character;

import assets.IdToAsset;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.swing.*;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.character.CharacterEquipmentPanel;
import tomato.gui.kit.*;
import tomato.realmshark.EnchantInfo;
import static tomato.gui.glance.character.SheetViews.*;

/**
 * Sheet › Gear (spec §6.2): four large equipped slots with tier labels and, for the character in game only, an enchant rarity gem and enchant tooltip;
 * then the inventory (8) and backpack (16) as a sprite grid. Unknown and empty slots stay distinct; each slot's tooltip names its
 * item. Tier labels come from the model (computed off the EDT with the build's definitions). The 28-row slot table with field
 * evidence is an Analyst-only Collapsible. EDT only.
 */
final class GearTab extends JPanel {
    private static final Object NOT_SHOWN = new Object();
    private final ItemSlot[] slots = new ItemSlot[28];
    private final KitText[] tiers = new KitText[4];
    private final JPanel backpack = named(row(), "character-gear-backpack");
    private final KitText noBackpack = named(KitText.caption("No backpack"), "character-gear-no-backpack");
    private final KitText backpackUnknown = named(KitText.caption("Backpack contents not captured yet"), "character-gear-backpack-unknown");
    private final CharacterEquipmentPanel table = new CharacterEquipmentPanel();
    private final Collapsible slotTable;
    private SheetModel.Gear shown;
    /** What the Analyst slot table last rendered ({@link SlotRows}); NOT_SHOWN until the first {@link #analyst}. */
    private Object analystShown = NOT_SHOWN;

    /**
     * What the Analyst slot table shows for one record copy: the character, each slot's state, item, item name and field evidence
     * (CharacterEquipmentPanel's own projection) and the definitions whose details each slot carries. While playing, every
     * capture hands the sheet a new copy whose times moved but whose slots did not; such a copy re-renders nothing, so the user's
     * selection and scroll stay. (A slot's full-details text also names the record's last update; it refreshes with the next
     * real change.)
     */
    private record SlotRows(String key, boolean dead, List<List<Object>> rows, RosterDefinitions definitions) {
        static SlotRows of(CharacterJournal.CharacterRecord record, RosterDefinitions definitions) {
            if (record == null) return null;
            List<List<Object>> rows = new ArrayList<>(28);
            for (CharacterEquipmentPanel.Slot slot : CharacterEquipmentPanel.project(record, definitions))
                rows.add(Arrays.asList(slot.state, slot.item, slot.item == null || slot.item < 0 ? null : IdToAsset.objectName(slot.item), slot.evidence));
            return new SlotRows(record.key, record.dead, List.copyOf(rows), definitions);
        }
    }

    GearTab(DisplayModeModel mode) {
        super(new BorderLayout());
        setOpaque(false);
        setName("character-gear");
        JPanel equipped = clear(new FlowLayout(FlowLayout.LEADING, Tokens.M, 0));
        for (int i = 0; i < 4; i++) {
            slots[i] = named(new ItemSlot(48), "character-gear-slot-" + i);
            tiers[i] = named(KitText.caption(SLOTS[i]), "character-gear-tier-" + i);
            equipped.add(beside(slots[i], clear(new FlowLayout(FlowLayout.CENTER, Tokens.XS, 0), tiers[i]), BorderLayout.SOUTH, 2));
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

    /** EDT. A gear section equal to the shown one is skipped; its tier labels are part of it, so labels arriving repaint it. */
    void apply(SheetModel.Gear gear) {
        if (gear != null && gear.equals(shown)) return;
        boolean backpackCaptured = false;
        for (int i = 0; i < 28; i++) {
            int id = gear == null ? -1 : gear.slots().get(i);
            String tier = id > 0 ? gear.tier(i) : "";
            // Enchantments are decoded from the character in game only; saved records show no gem.
            EnchantInfo enchant = i < 4 && gear != null && gear.enchants() != null ? gear.enchants().get(i) : null;
            if (id > 0) slots[i].setItem(id, tier, enchant); else if (id == 0) slots[i].setEmpty(); else slots[i].setUnknown();
            if (i >= 12 && id >= 0) backpackCaptured = true;
            if (i < 4) tiers[i].setText(tier.isEmpty() ? SLOTS[i] : tier);
        }
        Boolean has = gear == null ? null : gear.hasBackpack();
        noBackpack.setVisible(Boolean.FALSE.equals(has));
        backpack.setVisible(!Boolean.FALSE.equals(has));
        backpackUnknown.setVisible(has == null && !backpackCaptured);
        shown = gear; // recorded last, so an apply that failed midway is redone in full
        revalidate();
        repaint();
    }

    /**
     * EDT: the Analyst slot table (per-slot field evidence) for the sheet's detached record copy. It re-renders only when the
     * slots it shows, the character or the definitions changed ({@link SlotRows}), never for a copy that only moved its times.
     */
    void analyst(CharacterJournal.CharacterRecord record, RosterDefinitions definitions) {
        SlotRows rows = SlotRows.of(record, definitions);
        if (Objects.equals(rows, analystShown)) return;
        table.showRecord(record, definitions);
        analystShown = rows;
    }
}
