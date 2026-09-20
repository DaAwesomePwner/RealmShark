package tomato.gui.character;

import assets.IdToAsset;
import assets.ImageBuffer;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.backend.data.CharacterJournal.AccountRecord;
import tomato.realmshark.enums.CharacterClass;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.stats.Formatters;

/** Searchable persistent roster, with explicit unknowns and reversible life-state annotations. */
public final class CharacterJournalGUI extends JPanel {
    private final CharacterJournal journal;
    private final JTextField search = new JTextField(18);
    private final JComboBox<String> life = new JComboBox<>(new String[]{"All characters", "Alive", "Dead"});
    private final JComboBox<String> season = new JComboBox<>(new String[]{"All seasons", "Seasonal", "Regular"});
    private final JLabel heading = new JLabel("Select a character");
    private final JTextArea summary = ContentStyle.wrappingText(""), status = ContentStyle.wrappingText(""), seen = ContentStyle.wrappingText(" ");
    private final JButton death = new JButton("Mark dead"), saveNotes = new JButton("Save notes");
    private final JTextArea notes = new JTextArea(3, 30);
    private final DefaultTableModel rosterModel = model("Character", "Account", "State", "Season", "Level", "Maxed", "Fame", "Last seen");
    private final JTable roster = table(rosterModel);
    private final DefaultTableModel statModel = model("Stat", "Base", "Cap", "Potions to max");
    private final DefaultTableModel gearModel = model("Slot", "Item", "Item ID");
    private final DefaultTableModel exaltModel = model("Account", "Class", "Stat", "Level", "Completions", "Next tier", "Observed");
    private final DefaultTableModel charExaltModel = model("Stat", "Level", "Completions", "Next tier");
    private final JPanel exalts = new JPanel(new BorderLayout(0, 8)) {
        @Override public void addNotify() { super.addNotify(); timer.start(); refresh(); }
        @Override public void removeNotify() { super.removeNotify(); if (!CharacterJournalGUI.this.isDisplayable()) timer.stop(); }
    };
    private List<CharacterRecord> records = new ArrayList<>(), filtered = new ArrayList<>();
    private List<AccountRecord> accounts = new ArrayList<>();
    private String selectedKey;
    private long revision = -1;
    private boolean refreshing;
    private boolean rosterDirty = true, exaltsDirty = true;
    private final javax.swing.Timer timer;

    public CharacterJournalGUI(CharacterJournal journal) {
        super(new BorderLayout(0, 8)); this.journal = journal;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel top = new JPanel(new BorderLayout(0, 6));
        summary.setName("character-summary");
        ContentStyle.font(summary, ContentStyle.body()); top.add(summary, BorderLayout.NORTH);
        seen.setFont(ContentStyle.metadata(ContentStyle.body())); status.setFont(ContentStyle.metadata(ContentStyle.body()));
        JPanel filters = ContentStyle.controls();
        search.putClientProperty("JTextField.placeholderText", "Search class, account, ID, equipment…");
        search.setToolTipText("Search class, account name, character ID, item names or notes");
        search.setName("character-search"); search.getAccessibleContext().setAccessibleName("Search saved characters");
        life.getAccessibleContext().setAccessibleName("Character life state"); season.getAccessibleContext().setAccessibleName("Character season");
        filters.add(search); filters.add(life); filters.add(season); top.add(filters);
        roster.setName("character-roster");
        roster.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); roster.setAutoCreateRowSorter(true);
        roster.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths = {150, 130, 65, 85, 55, 70, 80, 150};
        for (int i = 0; i < widths.length; i++) roster.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        roster.getColumnModel().getColumn(1).setCellRenderer(new ContentStyle.Cell() {
            @Override protected void setValue(Object value) { super.setValue(value); setToolTipText(getText()); }
        });
        roster.getColumnModel().getColumn(6).setCellRenderer(new ContentStyle.Cell() {
            @Override protected void setValue(Object value) {
                setText(value instanceof Number ? DisplayFormat.formatInteger(((Number)value).longValue()) : "Unknown");
            }
        });
        roster.getColumnModel().getColumn(7).setCellRenderer(dateRenderer());
        JPanel detail = new JPanel(new BorderLayout(0, 8)) {
            @Override public Dimension getMinimumSize() {
                BorderLayout layout = (BorderLayout) getLayout();
                Component title = layout.getLayoutComponent(BorderLayout.NORTH);
                Component content = layout.getLayoutComponent(BorderLayout.CENTER);
                Component hint = layout.getLayoutComponent(BorderLayout.SOUTH);
                // Tab chrome AND usable rows must fit, including after font/width changes.
                return new Dimension(0, title.getPreferredSize().height + content.getMinimumSize().height
                        + hint.getPreferredSize().height + layout.getVgap() * 2);
            }
        };
        detail.setName("character-detail");
        JPanel title = new JPanel(new BorderLayout(8, 3)); heading.setFont(ContentStyle.emphasis(ContentStyle.body()));
        JPanel titleActions = ContentStyle.controls(); titleActions.add(heading); titleActions.add(death);
        title.add(titleActions); title.add(seen, BorderLayout.SOUTH); detail.add(title, BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane(); tabs.setName("character-detail-tabs"); tabs.setTabLayoutPolicy(JTabbedPane.WRAP_TAB_LAYOUT);
        JTable stats = table(statModel);
        stats.getColumnModel().getColumn(3).setCellRenderer(new ContentStyle.Cell() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int row, int col) {
                super.getTableCellRendererComponent(t, v, s, f, row, col);
                setHorizontalAlignment(RIGHT);
                if (!s) setForeground(t.getForeground());
                if (v instanceof Integer) {
                    if ((Integer)v == 0) { setText("Maxed"); if (!s) setForeground(ContentStyle.color("mint")); }
                    else if (!s) setForeground(ContentStyle.color("violet"));
                }
                return this;
            }
        });
        tabs.addTab("Stat maxing", ContentStyle.tableScroll(stats, 3));
        JTable gear = table(gearModel); gear.getColumnModel().getColumn(1).setCellRenderer(new ContentStyle.Cell() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int row, int col) {
                super.getTableCellRendererComponent(t, v, s, f, row, col); setIcon(null);
                Object id = gearModel.getValueAt(t.convertRowIndexToModel(row), 2);
                if (id instanceof Integer && (Integer)id >= 0) setIcon(ImageBuffer.getOutlinedIcon((Integer)id, 24));
                return this;
            }
        });
        ContentStyle.tableFont(gear, ContentStyle.body(), 32); // Room for 24px equipment icons.
        tabs.addTab("Equipment & inventory", ContentStyle.tableScroll(gear, 3));
        tabs.addTab("Class exalts", ContentStyle.tableScroll(table(charExaltModel), 3));
        JPanel notePanel = new JPanel(new BorderLayout(8, 8)); notes.setLineWrap(true); notes.setWrapStyleWord(true);
        notes.setName("character-notes"); notes.setFont(ContentStyle.body()); notes.getAccessibleContext().setAccessibleName("Character notes");
        JScrollPane noteScroll = new JScrollPane(notes) {
            @Override public Dimension getMinimumSize() {
                Insets insets = getInsets();
                return new Dimension(0, notes.getFontMetrics(notes.getFont()).getHeight() * 3 + insets.top + insets.bottom);
            }
        };
        notePanel.add(noteScroll, BorderLayout.CENTER); notePanel.add(saveNotes, BorderLayout.SOUTH); tabs.addTab("Notes", notePanel);
        detail.add(tabs, BorderLayout.CENTER);
        JTextArea hint = note("Base stats exclude captured boosts. Caps use local game assets; missing values stay unknown.");
        hint.setToolTipText("Potion estimates use +5 Life/Mana and +1 other stats. Exalts are account/class progress shared across characters.");
        detail.add(hint, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, ContentStyle.tableScroll(roster, 3), detail) {
            @Override public void doLayout() {
                super.doLayout();
                // Swing permits programmatic divider positions to ignore child minimums.
                // Reconcile the saved position after font changes or a narrower page layout.
                int current = getUI().getDividerLocation(this);
                int usable = Math.max(getMinimumDividerLocation(), Math.min(current, getMaximumDividerLocation()));
                if (usable != current) { setDividerLocation(usable); super.doLayout(); }
            }
        };
        split.setName("character-roster-detail-split");
        split.setResizeWeight(.48); split.setDividerLocation(235); split.setBorder(null);
        JScrollPane page = ContentStyle.page(top, split, status);
        page.setName("character-page-scroll");
        page.getAccessibleContext().setAccessibleName("Characters; scroll for roster, details and actions at large text sizes");
        add(page, BorderLayout.CENTER);
        for (JComponent control : new JComponent[]{search, life, season, death, saveNotes}) {
            control.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusGained(java.awt.event.FocusEvent event) {
                    // JTextField.scrollRectToVisible scrolls its text horizontally, not the page.
                    reveal(control, new Rectangle(0, 0, control.getWidth(), control.getHeight()));
                }
            });
        }
        exalts.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JTextArea exaltHint = note("Saved account/class progress • Sort columns to review progress • Only observed classes are listed");
        JTable exaltTable = table(exaltModel); exaltTable.setAutoCreateRowSorter(true);
        exaltTable.getColumnModel().getColumn(6).setCellRenderer(dateRenderer());
        exalts.add(ContentStyle.page(exaltHint, ContentStyle.tableScroll(exaltTable, 3),
                note("Tier thresholds: 5 / 15 / 30 / 50 / 75 completions. Character death does not reset exalts.")), BorderLayout.CENTER);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); } public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        life.addActionListener(e -> filter()); season.addActionListener(e -> filter());
        roster.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !refreshing) select(); });
        death.addActionListener(e -> { CharacterRecord r = selected(); if (r != null) { journal.markDead(r.key, !r.dead); refresh(); } });
        saveNotes.addActionListener(e -> { if (selectedKey != null) { journal.notes(selectedKey, notes.getText()); refresh(); } });
        timer = new javax.swing.Timer(1000, e -> { if (isShowing() || exalts.isShowing()) refresh(); });
        java.awt.event.HierarchyListener visibility = e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && e.getComponent().isShowing()) refresh();
        };
        addHierarchyListener(visibility); exalts.addHierarchyListener(visibility); refresh();
    }
    @Override public void addNotify() { super.addNotify(); timer.start(); refresh(); }
    @Override public void removeNotify() { super.removeNotify(); if (!exalts.isDisplayable()) timer.stop(); }
    public JPanel exaltPanel() { return exalts; }
    public void refresh() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::refresh); return; }
        synchronized (journal) {
            if (revision != journal.revision()) {
                records = journal.characters(); accounts = journal.accounts(); revision = journal.revision();
                rosterDirty = exaltsDirty = true;
            }
        }
        // The Exalts page can be mounted independently of the roster in the workspace.
        boolean detached = !isDisplayable() && !exalts.isDisplayable();
        if (exaltsDirty && (exalts.isShowing() || detached)) refreshExalts();
        if (rosterDirty && (isShowing() || detached)) filter();
        String storageStatus = journal.storageStatus();
        if (records.isEmpty() && storageStatus.startsWith("Saved"))
            storageStatus = "Start capture and enter the game on a character. Account identity is required before saving.";
        if (!status.getText().equals(storageStatus)) status.setText(storageStatus);
    }
    private void refreshExalts() {
        exaltsDirty = false;
        exaltModel.setRowCount(0);
        for (AccountRecord a : accounts) for (Map.Entry<Integer, int[]> entry : a.exalts.entrySet()) {
            for (int i = 0; i < 8; i++) {
                int count = entry.getValue()[CharacterJournal.EXALT_ORDER[i]];
                exaltModel.addRow(new Object[]{accountName(a.key), className(entry.getKey()), CharacterJournal.STATS[i],
                    CharacterJournal.exaltLevel(count) + "/5", count, next(count), a.exaltSeen});
            }
        }
    }
    private void filter() {
        rosterDirty = false;
        refreshing = true;
        String oldKey = selectedKey;
        filtered = new ArrayList<>(); rosterModel.setRowCount(0);
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        int alive = 0, deadCount = 0, maxed = 0;
        for (CharacterRecord r : records) {
            if (r.dead) deadCount++; else { alive++; if (CharacterJournal.maxed(r, CharacterClass.getStats(r.classId)) == 8) maxed++; }
            if (life.getSelectedIndex() == 1 && r.dead || life.getSelectedIndex() == 2 && !r.dead) continue;
            if (season.getSelectedIndex() == 1 && !Boolean.TRUE.equals(r.seasonal)
                || season.getSelectedIndex() == 2 && !Boolean.FALSE.equals(r.seasonal)) continue;
            StringBuilder haystack = new StringBuilder(className(r.classId) + " " + r.characterId + " " + accountName(r.account) + " " + r.notes);
            for (Integer item : r.equipment) if (item != null && item >= 0) haystack.append(' ').append(itemName(item));
            if (!haystack.toString().toLowerCase(Locale.ROOT).contains(query)) continue;
            filtered.add(r);
            int count = CharacterJournal.maxed(r, CharacterClass.getStats(r.classId));
            rosterModel.addRow(new Object[]{className(r.classId) + " #" + r.characterId, accountName(r.account), r.dead ? "Dead" : "Alive",
                r.seasonal == null ? "Unknown" : r.seasonal ? "Seasonal" : "Regular", r.level,
                count < 0 ? "Unknown" : count + "/8", r.fame, r.lastSeen});
        }
        summary.setText(records.isEmpty() ? "Your saved characters will appear here" : DisplayFormat.formatInteger(alive) + " alive  •  "
                + DisplayFormat.formatInteger(deadCount) + " dead  •  " + DisplayFormat.formatInteger(maxed) + " at 8/8  •  "
                + DisplayFormat.formatInteger(filtered.size()) + " shown");
        if (records.isEmpty() && journal.storageStatus().startsWith("Saved")) status.setText("Start capture and enter the game on a character. Account identity is required before saving.");
        for (int i = 0; i < filtered.size(); i++) if (filtered.get(i).key.equals(oldKey)) {
            int view = roster.convertRowIndexToView(i); roster.setRowSelectionInterval(view, view); break;
        }
        if (roster.getSelectedRow() < 0 && !filtered.isEmpty()) roster.setRowSelectionInterval(0, 0);
        refreshing = false; select();
    }
    private CharacterRecord selected() { int row = roster.getSelectedRow(); return row < 0 ? null : filtered.get(roster.convertRowIndexToModel(row)); }
    private void select() {
        CharacterRecord r = selected(); String newKey = r == null ? null : r.key;
        boolean changed = !Objects.equals(selectedKey, newKey);
        if (changed && selectedKey != null) {
            journal.notes(selectedKey, notes.getText());
            for (CharacterRecord previous : records) if (previous.key.equals(selectedKey)) previous.notes = notes.getText();
        }
        selectedKey = newKey;
        statModel.setRowCount(0); gearModel.setRowCount(0); charExaltModel.setRowCount(0);
        death.setEnabled(r != null); saveNotes.setEnabled(r != null); notes.setEnabled(r != null);
        if (r == null) { heading.setText("Select a character"); heading.setIcon(null); seen.setText(" "); seen.setToolTipText(null); notes.setText(""); return; }
        heading.setText(className(r.classId) + " #" + r.characterId + (r.dead ? " • Dead" : ""));
        heading.setIcon(ImageBuffer.getOutlinedIcon(r.skin == null || r.skin == 0 ? r.classId : r.skin, 28));
        death.setText(r.dead ? "Restore alive" : "Mark dead");
        seen.setText("First seen " + date(r.firstSeen) + "  •  Last seen " + date(r.lastSeen));
        seen.setToolTipText(r.source + (r.created == null ? "" : " • Created " + r.created) + (r.dead ? " • Marked dead " + date(r.diedAt) : ""));
        if (changed) notes.setText(r.notes);
        int[] caps = CharacterClass.getStats(r.classId);
        for (int i = 0; i < 8; i++) statModel.addRow(new Object[]{CharacterJournal.STATS[i], unknown(r.stats[i]), caps == null ? "Unknown" : caps[i],
            caps == null || r.stats[i] == null ? "Unknown" : CharacterJournal.potions(r.stats[i], caps[i], i)});
        String[] slots = {"Weapon", "Ability", "Armor", "Ring"};
        for (int i = 0; i < r.equipment.length; i++) gearModel.addRow(new Object[]{i < 4 ? slots[i] : i < 12 ? "Inventory " + (i - 3) : "Backpack " + (i - 11), itemName(r.equipment[i]), r.equipment[i]});
        int[] exalt = null;
        for (AccountRecord a : accounts) if (a.key.equals(r.account)) exalt = a.exalts.get(r.classId);
        for (int i = 0; i < 8; i++) {
            Integer count = exalt == null ? null : exalt[CharacterJournal.EXALT_ORDER[i]];
            charExaltModel.addRow(new Object[]{CharacterJournal.STATS[i], count == null ? "Unknown" : CharacterJournal.exaltLevel(count) + "/5", unknown(count), count == null ? "Unknown" : next(count)});
        }
    }
    private String accountName(String key) { for (AccountRecord a : accounts) if (a.key.equals(key)) return (a.name == null ? "Account" : a.name) + " · " + key.substring(0, 6); return "Account · " + key.substring(0, 6); }
    private static String next(int count) { for (int goal : new int[]{5,15,30,50,75}) if (count < goal) return (goal - count) + " to " + goal; return "Complete"; }
    private static Object unknown(Object value) { return value == null ? "Unknown" : value; }
    private static String className(int id) { String name = CharacterClass.getName(id); return name == null ? "Class " + id : name; }
    private static String itemName(Integer id) { if (id == null) return "Not captured"; if (id < 0) return "Empty"; String name = IdToAsset.objectName(id); return name == null ? "Item #" + id : name; }
    private static String date(long time) { return time <= 0 ? "Unknown" : Formatters.formatTimestamp(time); }
    private static DefaultTableCellRenderer dateRenderer() { return new ContentStyle.Cell() {
        @Override protected void setValue(Object v) { setText(v instanceof Long ? date((Long)v) : "Unknown"); setToolTipText(getText()); }
    }; }
    private static DefaultTableModel model(String... columns) { return new DefaultTableModel(columns, 0) { @Override public boolean isCellEditable(int row, int col) { return false; }
        @Override public Class<?> getColumnClass(int col) { for (int i = 0; i < getRowCount(); i++) { Object v = getValueAt(i, col); if (v != null) return v instanceof Number ? v.getClass() : String.class; } return String.class; }
    }; }
    private static JTable table(DefaultTableModel model) {
        JTable t = new JTable(model); ContentStyle.table(t, ContentStyle.Density.DENSE);
        t.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) {
                int row = Math.max(0, t.getSelectedRow()), column = Math.max(0, t.getSelectedColumn());
                reveal(t, t.getCellRect(row, column, true));
            }
        });
        t.getTableHeader().setReorderingAllowed(false); return t;
    }
    private static void reveal(JComponent control, Rectangle region) {
        for (Container parent = control.getParent(); parent != null; parent = parent.getParent()) {
            if (!(parent instanceof JViewport)) continue;
            JComponent view = (JComponent)((JViewport)parent).getView();
            view.scrollRectToVisible(SwingUtilities.convertRectangle(control, region, view));
        }
    }
    private static JTextArea note(String text) {
        return ContentStyle.wrappingText(text);
    }
}
