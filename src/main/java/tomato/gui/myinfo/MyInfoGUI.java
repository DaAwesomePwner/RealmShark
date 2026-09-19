package tomato.gui.myinfo;

import assets.IdToAsset;
import assets.ImageBuffer;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.realmshark.ParseEnchants;
import tomato.gui.modern.ContentStyle;

/** Captured character details and explicitly scoped build estimates. */
public class MyInfoGUI extends JPanel {
    private static volatile MyInfoGUI INSTANCE;
    private Entity player, pet;
    private final Object pendingLock = new Object();
    private Entity pendingPlayer, pendingPet;
    private boolean playerPending, petPending, refreshScheduled, dirty;
    private final JLabel status = new JLabel("Enter the game during capture to see your build.");
    private final JLabel[] summary = new JLabel[4];
    private final JLabel[] icons = new JLabel[4];
    private final JLabel[] equipmentNames = new JLabel[4];
    private final JCheckBox outOfCombatCheck = new JCheckBox("Out of combat");
    private final JTextField search = new JTextField(18);
    private final JComboBox<String> category = new JComboBox<>(new String[] {
        "All details", "Character", "Equipment", "Damage", "Recovery", "Pet", "Dust"
    });
    private List<Row> rows = new ArrayList<>();
    private final DetailModel model = new DetailModel();
    private final JTable table = new JTable(model);
    private final TableRowSorter<DetailModel> sorter = new TableRowSorter<>(model);
    private final JTextArea detail = new JTextArea(3, 30);
    private final JLabel count = new JLabel("No captured character");

    private static final String[] SLOT_NAMES = {"Weapon", "Ability", "Armor", "Ring"};
    private static final float[] petRegenTimeMpHp = {
        10.00f,
        9.35f,
        8.69f,
        8.04f,
        7.39f,
        7.13f,
        6.88f,
        6.62f,
        6.37f,
        6.11f,
        5.94f,
        5.76f,
        5.59f,
        5.42f,
        5.25f,
        5.13f,
        5.01f,
        4.90f,
        4.78f,
        4.67f,
        4.58f,
        4.49f,
        4.40f,
        4.31f,
        4.22f,
        4.14f,
        4.06f,
        3.98f,
        3.90f,
        3.82f,
        3.75f,
        3.69f,
        3.62f,
        3.55f,
        3.48f,
        3.43f,
        3.39f,
        3.34f,
        3.29f,
        3.24f,
        3.19f,
        3.13f,
        3.08f,
        3.03f,
        2.97f,
        2.91f,
        2.85f,
        2.78f,
        2.72f,
        2.66f,
        2.61f,
        2.56f,
        2.51f,
        2.46f,
        2.41f,
        2.38f,
        2.34f,
        2.30f,
        2.26f,
        2.22f,
        2.19f,
        2.15f,
        2.12f,
        2.08f,
        2.05f,
        2.03f,
        2.01f,
        1.99f,
        1.98f,
        1.96f,
        1.93f,
        1.91f,
        1.88f,
        1.85f,
        1.83f,
        1.79f,
        1.74f,
        1.70f,
        1.65f,
        1.61f,
        1.57f,
        1.53f,
        1.50f,
        1.46f,
        1.42f,
        1.42f,
        1.42f,
        1.42f,
        1.42f,
        1.42f,
        1.38f,
        1.34f,
        1.29f,
        1.25f,
        1.21f,
        1.17f,
        1.13f,
        1.08f,
        1.04f,
        1.00f,
    };
    private static final int[] petManaPerLevel = {
        1,
        1,
        1,
        1,
        1,
        1,
        1,
        1,
        1,
        1,
        1,
        1,
        1,
        1,
        1,
        1,
        1,
        1,
        1,
        2,
        2,
        2,
        2,
        2,
        2,
        2,
        2,
        3,
        3,
        3,
        3,
        3,
        3,
        4,
        4,
        4,
        4,
        4,
        5,
        5,
        5,
        5,
        6,
        6,
        6,
        7,
        7,
        7,
        8,
        8,
        8,
        9,
        9,
        9,
        10,
        10,
        11,
        11,
        11,
        12,
        12,
        13,
        13,
        14,
        14,
        15,
        16,
        16,
        17,
        17,
        18,
        19,
        19,
        20,
        21,
        21,
        22,
        23,
        24,
        24,
        25,
        26,
        27,
        28,
        28,
        29,
        30,
        31,
        32,
        33,
        34,
        35,
        36,
        38,
        39,
        40,
        41,
        42,
        44,
        45,
    };
    private static final int[] petHpPerLevel = {
        10,
        10,
        10,
        10,
        10,
        10,
        10,
        10,
        10,
        10,
        10,
        10,
        10,
        10,
        11,
        11,
        11,
        11,
        11,
        11,
        12,
        12,
        12,
        12,
        12,
        13,
        13,
        13,
        14,
        14,
        14,
        14,
        15,
        15,
        16,
        16,
        16,
        17,
        17,
        18,
        18,
        19,
        19,
        20,
        20,
        21,
        21,
        22,
        22,
        23,
        24,
        24,
        25,
        26,
        26,
        27,
        28,
        29,
        29,
        30,
        31,
        32,
        33,
        34,
        35,
        36,
        37,
        38,
        39,
        40,
        41,
        42,
        43,
        45,
        46,
        47,
        48,
        50,
        51,
        53,
        54,
        55,
        57,
        59,
        60,
        62,
        64,
        65,
        67,
        69,
        71,
        73,
        75,
        77,
        79,
        81,
        83,
        85,
        88,
        90,
    };

    public MyInfoGUI(TomatoData data) {
        INSTANCE = this;
        setLayout(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel header = new JPanel(new BorderLayout(0, 8));
        header.add(status, BorderLayout.NORTH);
        JPanel cards = ContentStyle.responsiveGrid(4, 155, 8);
        String[] labels = {"Health", "Mana", "Weapon DPS (est.)", "MP/sec (est.)"};
        for (int i = 0; i < 4; i++) {
            JPanel card = new JPanel(new BorderLayout(0, 4)) {
                @Override public void updateUI() { super.updateUI(); setBackground(ContentStyle.color("surface")); }
            };
            card.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            JLabel caption = new JLabel(labels[i]); caption.setFont(ContentStyle.metadata(ContentStyle.body()));
            card.add(caption, BorderLayout.NORTH);
            summary[i] = new JLabel("—");
            summary[i].setFont(ContentStyle.emphasis(ContentStyle.body()).deriveFont(ContentStyle.body().getSize2D() * 18f / ContentStyle.FONT_SIZE));
            card.add(summary[i], BorderLayout.CENTER);
            cards.add(card);
        }
        header.add(cards, BorderLayout.CENTER);
        JPanel equipment = ContentStyle.responsiveGrid(4, 155, 8);
        for (int i = 0; i < 4; i++) {
            JPanel slot = new JPanel(new BorderLayout(8, 3));
            icons[i] = new JLabel();
            slot.add(icons[i], BorderLayout.WEST);
            JLabel caption = new JLabel(SLOT_NAMES[i]); caption.setFont(ContentStyle.metadata(ContentStyle.body()));
            slot.add(caption, BorderLayout.NORTH);
            equipmentNames[i] = new JLabel("Awaiting capture");
            slot.add(equipmentNames[i], BorderLayout.CENTER);
            equipment.add(slot);
        }
        header.add(equipment, BorderLayout.SOUTH);

        JPanel toolbar = ContentStyle.controls();
        JScrollPane tableScroll = ContentStyle.tableScroll(table, 3);
        JPanel center = new JPanel(new BorderLayout(0, 8)) {
            public Dimension getMinimumSize() {
                return new Dimension(0, toolbar.getPreferredSize().height + tableScroll.getMinimumSize().height + 8);
            }
        };
        search.putClientProperty("JTextField.placeholderText", "Search build details…");
        search.getAccessibleContext().setAccessibleName("Search build details");
        category.getAccessibleContext().setAccessibleName("Detail category");
        toolbar.add(search); toolbar.add(category); toolbar.add(outOfCombatCheck);
        outOfCombatCheck.setToolTipText("Apply the existing out-of-combat enchant regeneration bonuses.");
        outOfCombatCheck.addActionListener(e -> updateMe());
        category.addActionListener(e -> filter());
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        center.add(toolbar, BorderLayout.NORTH);
        table.setRowSorter(sorter);
        ContentStyle.table(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths = {95, 170, 85, 85, 420};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.setDefaultRenderer(Double.class, new ContentStyle.Cell() {
            { setHorizontalAlignment(SwingConstants.RIGHT); }
            protected void setValue(Object value) { setText(value == null ? "—" : format((Double) value)); }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            int index = table.getSelectedRow();
            if (index < 0) { detail.setText("Select a row for its full details. Click column headers to sort."); return; }
            Row row = rows.get(table.convertRowIndexToModel(index));
            detail.setText(row.group + " / " + row.name + "\n" + row.notes);
            detail.setCaretPosition(0);
        });
        center.add(tableScroll, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(0, 6)) {
            public Dimension getMinimumSize() {
                return new Dimension(0, detail.getFontMetrics(detail.getFont()).getHeight() * 2
                    + count.getPreferredSize().height + 24);
            }
        };
        detail.setEditable(false); detail.setLineWrap(true); detail.setWrapStyleWord(true);
        detail.setFont(ContentStyle.body()); detail.setMargin(new Insets(6, 8, 6, 8));
        detail.getAccessibleContext().setAccessibleName("Selected build detail");
        detail.setText("Select a row for its full details. Click column headers to sort.");
        footer.add(new JScrollPane(detail), BorderLayout.CENTER);
        count.setFont(ContentStyle.metadata(ContentStyle.body()));
        footer.add(count, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, center, footer);
        split.setResizeWeight(.8); split.setBorder(null); split.setContinuousLayout(true);
        split.setOneTouchExpandable(true);
        JScrollPane pageScroll = ContentStyle.page(header, split, null);
        pageScroll.setBorder(null); pageScroll.setName("myinfo-page-scroll");
        pageScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(pageScroll, BorderLayout.CENTER);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing() && dirty) {
                dirty = false; updateMe();
            }
        });
    }

    private void filter() {
        final String query = search.getText().trim().toLowerCase(Locale.ROOT);
        final String group = (String) category.getSelectedItem();
        sorter.setRowFilter(new RowFilter<DetailModel, Integer>() {
            public boolean include(Entry<? extends DetailModel, ? extends Integer> entry) {
                Row row = rows.get(entry.getIdentifier());
                return ("All details".equals(group) || row.group.equals(group))
                    && (row.group + " " + row.name + " " + row.value + " " + row.unit + " " + row.notes)
                        .toLowerCase(Locale.ROOT).contains(query);
            }
        });
        count.setText(player == null ? "No captured character" :
            table.getRowCount() + " of " + rows.size() + " details • Captured values and local estimates");
    }

    public static void updatePlayer(Entity value) {
        MyInfoGUI view = INSTANCE;
        if (view != null) view.enqueue(value, true);
    }

    public static void updatePet(Entity value) {
        MyInfoGUI view = INSTANCE;
        if (view != null) view.enqueue(value, false);
    }

    private void enqueue(Entity value, boolean character) {
        Entity copy = copyStats(value);
        synchronized (pendingLock) {
            if (character) { pendingPlayer = copy; playerPending = true; }
            else { pendingPet = copy; petPending = true; }
            if (refreshScheduled) return;
            refreshScheduled = true;
        }
        SwingUtilities.invokeLater(this::drainUpdates);
    }

    private void drainUpdates() {
        synchronized (pendingLock) {
            if (playerPending) player = pendingPlayer;
            if (petPending) pet = pendingPet;
            pendingPlayer = pendingPet = null;
            playerPending = petPending = refreshScheduled = false;
        }
        dirty = true;
        if (isShowing() || !isDisplayable()) { dirty = false; updateMe(); }
    }

    /** Copy only build statistics on the producer; combat histories are not needed by this view. */
    private static Entity copyStats(Entity source) {
        if (source == null) return null;
        Entity copy = new Entity(null, source.id, 0); copy.objectType = source.objectType;
        for (StatType type : StatType.values()) {
            StatData value = source.stat.get(type);
            if (value == null) continue;
            StatData stat = new StatData();
            stat.statType = value.statType; stat.statTypeNum = value.statTypeNum;
            stat.statValue = value.statValue; stat.statValueTwo = value.statValueTwo;
            stat.stringStatValue = value.stringStatValue; copy.stat.set(type, stat);
        }
        return copy;
    }

    private Double stat(Entity entity, StatType type) {
        StatData value = entity == null ? null : entity.stat.get(type);
        return value == null ? null : (double) value.statValue;
    }

    private void add(String group, String name, Double value, String unit, String notes) {
        rows.add(new Row(group, name, value, unit, notes));
    }

    private void updateMe() {
        String selected = null;
        if (table.getSelectedRow() >= 0) {
            Row row = rows.get(table.convertRowIndexToModel(table.getSelectedRow()));
            selected = row.group + "/" + row.name;
        }
        rows = new ArrayList<>();
        for (JLabel label : summary) label.setText("—");
        for (int i = 0; i < 4; i++) { icons[i].setIcon(null); equipmentNames[i].setText("Awaiting capture"); }
        if (player != null) {
            StatData name = player.stat.get(StatType.NAME_STAT);
            status.setText(name == null || name.stringStatValue == null ? "Captured character" : name.stringStatValue + " • Captured build");
            summary[0].setText(pair(StatType.HP_STAT, StatType.MAX_HP_STAT));
            summary[1].setText(pair(StatType.MP_STAT, StatType.MAX_MP_STAT));
            StatType[] stats = {StatType.HP_STAT, StatType.MAX_HP_STAT, StatType.MP_STAT, StatType.MAX_MP_STAT,
                StatType.ATTACK_STAT, StatType.DEFENSE_STAT, StatType.SPEED_STAT, StatType.DEXTERITY_STAT,
                StatType.VITALITY_STAT, StatType.WISDOM_STAT, StatType.EXALTATION_BONUS_DAMAGE};
            String[] names = {"Health", "Maximum health", "Mana", "Maximum mana", "Attack", "Defense",
                "Speed", "Dexterity", "Vitality", "Wisdom", "Exaltation damage multiplier"};
            for (int i = 0; i < stats.length; i++) {
                Double value = stat(player, stats[i]);
                if (i == 10 && value != null) value /= 1000;
                add("Character", names[i], value, i == 10 ? "×" : "points", value == null ? "Not captured yet." : "Captured character stat.");
            }
            String[] enchants;
            try { enchants = ParseEnchants.extractEnchants(player); }
            catch (RuntimeException e) { enchants = new String[] {"Unavailable", "Unavailable", "Unavailable", "Unavailable"}; }
            StatType[] slots = {StatType.INVENTORY_0_STAT, StatType.INVENTORY_1_STAT, StatType.INVENTORY_2_STAT, StatType.INVENTORY_3_STAT};
            Weapon weapon = null;
            for (int i = 0; i < 4; i++) {
                Double id = stat(player, slots[i]);
                String item = id == null ? "Not captured" : id < 0 ? "Empty slot" : itemName(id.intValue());
                equipmentNames[i].setText(item); equipmentNames[i].setToolTipText(item);
                if (id != null && id >= 0) displayImg(icons[i], id.intValue());
                add("Equipment", SLOT_NAMES[i], id, "item ID", item + (enchants[i].isEmpty() ? "" : "\n" + enchants[i]));
                if (i == 0 && id != null && id >= 0) weapon = Equip.get(id.intValue());
            }
            damage(weapon);
            recovery();
            dust();
        } else status.setText("Enter the game during capture to see your build.");
        model.fireTableDataChanged();
        filter();
        if (selected != null) for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (selected.equals(row.group + "/" + row.name)) {
                int view = table.convertRowIndexToView(i);
                if (view >= 0) table.setRowSelectionInterval(view, view);
                break;
            }
        }
    }

    private void damage(Weapon weapon) {
        Double atk = stat(player, StatType.ATTACK_STAT), dex = stat(player, StatType.DEXTERITY_STAT),
            exalt = stat(player, StatType.EXALTATION_BONUS_DAMAGE);
        Double total = null;
        String assumptions = "Local estimate against 0 defense, all projectiles hitting continuously. "
            + "Does not model ability damage, condition effects, weapon enchants or practical uptime.";
        if (weapon != null && !weapon.bullets.isEmpty() && atk != null && dex != null && exalt != null) {
            total = 0d;
            int index = 1;
            for (Bullet bullet : weapon.bullets) {
                double dps = (bullet.min + bullet.max) / 2d * (exalt / 1000d) * (0.5 + atk / 50d)
                    * bullet.numProj * (1.5 + 6.5 * dex / 75d) * bullet.rof;
                add("Damage", "Projectile group " + index++, dps, "dmg/sec",
                    bullet.numProj + " projectiles • " + bullet.min + "–" + bullet.max + " base damage • "
                        + bullet.rof + "× rate of fire. " + assumptions);
                total += dps;
            }
        }
        add("Damage", "Weapon total", total, "dmg/sec", total == null ? "Waiting for weapon metadata and attack, dexterity and exaltation stats." : assumptions);
        summary[2].setText(total == null ? "—" : format(total));
        double petDps = 0;
        int[] types = {406, 402, 404, 405};
        String[] names = {"Electric", "Attack close", "Attack mid", "Attack far"};
        double[] scale = {4.8, 6.77, 4.83, 2.91}, exponent = {.04138, .0339, .0344, .0354};
        for (int i = 0; i < types.length; i++) {
            int level = getPetStat(types[i]);
            if (level < 1) continue;
            int hit = (int) (scale[i] * Math.exp(exponent[i] * level));
            double interval = i == 0 ? 1.02 * Math.exp(-.0163 * level) : 5.17 * Math.exp(-.0325 * level);
            double dps = hit / interval;
            petDps += dps;
            add("Pet", names[i], dps, "dmg/sec", "Level " + level + " • " + hit + " damage every "
                + format(interval) + " sec. Estimate assumes continuous hits.");
        }
        add("Damage", "Weapon + observed pet attacks", total == null ? null : total + petDps, "dmg/sec",
            assumptions + (pet == null ? " No pet captured; pet damage excluded." : " Includes captured pet attack abilities."));
        add("Damage", "Ability damage", null, "dmg/sec", "Not implemented; excluded from damage estimates.");
        if (pet == null) add("Pet", "Pet capture", null, "", "No pet captured yet.");
    }

    private void recovery() {
        Double wis = stat(player, StatType.WISDOM_STAT), maxMp = stat(player, StatType.MAX_MP_STAT),
            maxHp = stat(player, StatType.MAX_HP_STAT);
        Double manaEnchant = null, hpEnchant = null;
        String mode = outOfCombatCheck.isSelected() ? "Out of combat" : "In combat";
        try {
            String[] raw = ParseEnchants.getEnchantStrings(player);
            if (maxMp != null) manaEnchant = (double) ParseEnchants.getManaRegenPerSecondFromEnchants(raw, maxMp.intValue(), outOfCombatCheck.isSelected());
            if (maxHp != null) hpEnchant = (double) ParseEnchants.getLifeRegenPerSecondFromEnchants(raw, maxHp.intValue(), outOfCombatCheck.isSelected());
        } catch (RuntimeException ignored) { /* Missing or malformed enchant data stays unavailable. */ }
        Double base = wis == null ? null : wis * .12;
        add("Recovery", "Wisdom mana recovery", base, "mana/sec", "Existing estimate: Wisdom × 0.12.");
        add("Recovery", "Enchant mana recovery", manaEnchant, "mana/sec", mode + " • Supported enchant effects; requires maximum mana.");
        int level = getPetStat(408);
        double petMana = level < 1 ? 0 : petManaPerLevel[level - 1] / (double) petRegenTimeMpHp[level - 1];
        if (level > 0) add("Pet", "Magic heal", petMana, "mana/sec", "Level " + level + " • "
            + petManaPerLevel[level - 1] + " mana every " + petRegenTimeMpHp[level - 1] + " sec.");
        Double total = base == null || manaEnchant == null ? null : base + manaEnchant + petMana;
        add("Recovery", "Estimated mana recovery", total, "mana/sec", mode + " • Wisdom + supported enchants + observed pet Magic Heal. Does not model pet suppression.");
        summary[3].setText(total == null ? "—" : format(total));
        add("Recovery", "Enchant health recovery", hpEnchant, "hp/sec", mode + " • Partial estimate only. Base Vitality recovery and pet Heal are not included.");
    }

    private void dust() {
        StatData amounts = player.stat.get(StatType.DUST_STAT), caps = player.stat.get(StatType.DUST_AMOUNT_STAT);
        String[] names = {"Green dust", "Red dust", "Purple dust"};
        int[] indexes = {0, 4, 3};
        for (int i = 0; i < names.length; i++) {
            Double amount = dustValue(amounts, indexes[i]), cap = dustValue(caps, indexes[i]);
            add("Dust", names[i], amount, "dust", "Capacity: " + (cap == null ? "not captured" : format(cap)));
        }
    }

    private Double dustValue(StatData data, int index) {
        try { return Double.valueOf(data.stringStatValue.split(",")[index].split(":")[1]); }
        catch (RuntimeException e) { return null; }
    }

    private int getPetStat(int type) {
        StatType[] types = {StatType.PET_FIRST_ABILITY_TYPE_STAT, StatType.PET_SECOND_ABILITY_TYPE_STAT, StatType.PET_THIRD_ABILITY_TYPE_STAT};
        StatType[] powers = {StatType.PET_FIRST_ABILITY_POWER_STAT, StatType.PET_SECOND_ABILITY_POWER_STAT, StatType.PET_THIRD_ABILITY_POWER_STAT};
        for (int i = 0; i < types.length; i++) {
            Double ability = stat(pet, types[i]), level = stat(pet, powers[i]);
            if (ability != null && ability == type && level != null && level >= 1 && level <= 100) return level.intValue();
        }
        return -1;
    }

    private String pair(StatType current, StatType max) {
        Double a = stat(player, current), b = stat(player, max);
        return (a == null ? "—" : format(a)) + " / " + (b == null ? "—" : format(b));
    }

    private String itemName(int id) {
        try { String name = IdToAsset.objectName(id); if (name != null && !name.isEmpty()) return name; }
        catch (RuntimeException ignored) {}
        return "Item " + id;
    }

    public void displayImg(JLabel label, int id) {
        try { label.setIcon(ImageBuffer.getOutlinedIcon(id, 28)); }
        catch (RuntimeException e) { label.setIcon(null); }
    }

    private static String format(double value) {
        return java.text.NumberFormat.getNumberInstance().format(Math.round(value * 100d) / 100d);
    }

    static final class Row {
        final String group, name, unit, notes;
        final Double value;
        Row(String group, String name, Double value, String unit, String notes) {
            this.group = group; this.name = name; this.value = value; this.unit = unit; this.notes = notes;
        }
    }

    private final class DetailModel extends AbstractTableModel {
        private final String[] columns = {"Category", "Detail", "Value", "Unit", "Notes"};
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int col) { return columns[col]; }
        public Class<?> getColumnClass(int col) { return col == 2 ? Double.class : String.class; }
        public Object getValueAt(int row, int col) {
            Row r = rows.get(row);
            switch (col) { case 0: return r.group; case 1: return r.name; case 2: return r.value; case 3: return r.unit; default: return r.notes; }
        }
    }
}
