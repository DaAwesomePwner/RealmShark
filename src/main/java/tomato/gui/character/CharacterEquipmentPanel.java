package tomato.gui.character;

import assets.IdToAsset;
import assets.ImageBuffer;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.backend.data.FieldCapture;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.modern.ContentStyle;
import tomato.gui.stats.Formatters;

/** Detached historical slot projection. Never resolves equipment from a current live entity. */
public final class CharacterEquipmentPanel extends JPanel {
    public static final class Slot {
        public final int index; public final Integer item;
        public final String group, name, state, evidence, detail;
        Slot(int index, Integer item, String group, String name, String state, String evidence, String detail) {
            this.index = index; this.item = item; this.group = group; this.name = name; this.state = state; this.evidence = evidence; this.detail = detail;
        }
    }
    private final JComboBox<String> group = new JComboBox<>(new String[]{"All groups", "Equipped", "Inventory", "Backpack"});
    private final JComboBox<String> state = new JComboBox<>(new String[]{"All slot states", "Occupied", "Empty", "Not captured"});
    private final DefaultTableModel model = new DefaultTableModel(new String[]{"Group", "Slot", "State", "Item", "Item ID", "Field evidence"}, 0) { public boolean isCellEditable(int r, int c) { return false; } };
    private final JTable table = new JTable(model);
    private final JTextArea status = ContentStyle.wrappingText("Select a saved character");
    private List<Slot> slots = Collections.emptyList(), shown = new ArrayList<>();
    public CharacterEquipmentPanel() {
        super(new BorderLayout(0, 6)); JPanel controls = ContentStyle.controls(); controls.add(group); controls.add(state);
        JButton full = new JButton("Full slot details"); controls.add(full); JButton reset = new JButton("Reset equipment filters"); controls.add(reset); add(controls, BorderLayout.NORTH);
        ContentStyle.table(table, ContentStyle.Density.DENSE); ContentStyle.tableFont(table, ContentStyle.body(), 32);
        table.setAutoCreateRowSorter(true); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths = {90, 105, 100, 180, 90, 290}; for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getColumnModel().getColumn(3).setCellRenderer(new ContentStyle.Cell() {
            public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int row, int col) {
                super.getTableCellRendererComponent(t, v, s, f, row, col); Slot slot = shown.get(t.convertRowIndexToModel(row));
                setIcon(slot.item == null || slot.item < 0 ? null : ImageBuffer.getOutlinedIcon(slot.item, 24)); return this;
            }
        });
        add(ContentStyle.tableScroll(table, 3)); add(status, BorderLayout.SOUTH);
        group.getAccessibleContext().setAccessibleName("Equipment group"); state.getAccessibleContext().setAccessibleName("Equipment capture state"); table.getAccessibleContext().setAccessibleName("Historical equipment and inventory slots");
        table.setName("character-equipment"); group.setName("equipment-group"); state.setName("equipment-state");
        group.addActionListener(e -> render()); state.addActionListener(e -> render()); reset.addActionListener(e -> { group.setSelectedIndex(0); state.setSelectedIndex(0); });
        full.addActionListener(e -> details()); table.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "slot-details");
        table.getActionMap().put("slot-details", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { details(); } });
        CharacterFocusSupport.install(this);
    }
    public void showRecord(CharacterRecord record, RosterDefinitions definitions) { slots = record == null ? Collections.emptyList() : project(record, definitions); render(); }
    public static List<Slot> project(CharacterRecord record, RosterDefinitions definitions) {
        List<Slot> result = new ArrayList<>(); String[] equipped = {"Weapon", "Ability", "Armor", "Ring"};
        for (int i = 0; i < 28; i++) {
            Integer item = record.equipment != null && i < record.equipment.length ? record.equipment[i] : null;
            String group = i < 4 ? "Equipped" : i < 12 ? "Inventory" : "Backpack", name = i < 4 ? equipped[i] : group + " " + (i < 12 ? i - 3 : i - 11);
            String state = item == null ? "Not captured" : item < 0 ? "Empty" : "Occupied";
            FieldCapture capture = record.fields.get("equipment." + i);
            String evidence = item == null ? "Not captured" : capture == null ? "Legacy / provenance unknown" : capture.source + " · " + Formatters.formatTimestamp(capture.at) + (capture.at < record.lastSeen ? " · Retained from earlier observation" : "");
            RosterDefinitions.Item definition = item == null || item < 0 ? null : definitions.item(item);
            String detail = group + " / " + name + "\nSlot index: " + i + "\nState: " + state + "\nItem: " + itemName(item)
                + "\nItem ID: " + (item == null ? "Not captured" : item + " (0x" + Integer.toHexString(item) + ")")
                + "\nField evidence: " + evidence + "\nSnapshot updated: " + (record.lastSeen <= 0 ? "Unknown" : Formatters.formatTimestamp(record.lastSeen))
                + "\nLife state: " + (record.dead ? "Marked dead manually; frozen loadout" : "Saved observation")
                + "\nCurrent local item definition (not capture-time data):\nTier: " + (definition == null || definition.tier == null ? "Unknown" : definition.tier)
                + "\nLabels: " + (definition == null || definition.labels == null ? "Unknown" : definition.labels)
                + "\nSlot type: " + (definition == null || definition.slotType == null ? "Unknown" : definition.slotType)
                + "\nEnchantment effects: Not recorded in this character snapshot";
            result.add(new Slot(i, item, group, name, state, evidence, detail));
        }
        return Collections.unmodifiableList(result);
    }
    private void render() {
        model.setRowCount(0); shown = new ArrayList<>(); int occupied = 0, empty = 0, unknown = 0;
        for (Slot slot : slots) {
            if (slot.item == null) unknown++; else if (slot.item < 0) empty++; else occupied++;
            if (group.getSelectedIndex() != 0 && !slot.group.equals(group.getSelectedItem())) continue;
            if (state.getSelectedIndex() != 0 && !slot.state.equals(state.getSelectedItem())) continue;
            shown.add(slot);
            model.addRow(new Object[]{slot.group, slot.name, slot.state, itemName(slot.item), slot.item, slot.evidence});
        }
        status.setText(slots.isEmpty() ? "Select a saved character" : shown.size() + " / 28 slots shown · " + occupied + " occupied · " + empty + " captured empty · " + unknown + " not captured. Enter opens complete copyable details.");
    }
    private void details() {
        int row = table.getSelectedRow(); if (row < 0) return; Slot slot = shown.get(table.convertRowIndexToModel(row));
        JTextArea full = new JTextArea(slot.detail, 16, 50); full.setEditable(false); full.setLineWrap(true); full.setWrapStyleWord(true); full.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(full), "Historical slot details · select text to copy", JOptionPane.PLAIN_MESSAGE);
    }
    private static String itemName(Integer id) { return id == null ? "Not captured" : id < 0 ? "Empty" : Objects.toString(IdToAsset.objectName(id), "Unrecognized item #" + id); }
}
