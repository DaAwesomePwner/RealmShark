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
import tomato.backend.data.FieldCapture;
import tomato.backend.data.RosterDefinitions;
import tomato.realmshark.enums.CharacterClass;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.stats.Formatters;
import tomato.gui.history.ViewStateStore;
import tomato.gui.roster.RosterViewState;

/** Searchable persistent roster, with explicit unknowns and reversible life-state annotations. */
public final class CharacterJournalGUI extends JPanel {
    private final CharacterJournal journal;
    private final java.util.function.LongSupplier clock;
    private final java.util.function.Supplier<RosterDefinitions> definitionsSource;
    private RosterDefinitions definitions = RosterDefinitions.empty();
    private final Map<String, CharacterRosterQuery.Row> projected = new LinkedHashMap<>();
    private final JTextField search = new JTextField(18);
    private final JComboBox<String> life = new JComboBox<>(new String[]{"All characters", "Not marked dead", "Marked dead manually"});
    private final JComboBox<String> season = new JComboBox<>(new String[]{"All seasons", "Seasonal", "Regular", "Unknown season"});
    private final JComboBox<Choice<String>> accountFilter = new JComboBox<>();
    private final JComboBox<Choice<Integer>> classFilter = new JComboBox<>();
    private final JComboBox<String> needsLife = new JComboBox<>(new String[]{"Any Life need", "Needs Life", "Life maxed", "Life need unknown"});
    private final JComboBox<String> missing = new JComboBox<>(new String[]{"Any stat coverage", "Missing base stats", "All base stats captured", "Missing cap definitions"});
    private final JComboBox<String> maxedFilter = new JComboBox<>(new String[]{"Any maxed count", "Known maxed range", "Unknown maxed count"});
    private final JSpinner minMaxed = new JSpinner(new SpinnerNumberModel(0, 0, 8, 1)), maxMaxed = new JSpinner(new SpinnerNumberModel(8, 0, 8, 1));
    private final JComboBox<String> ageFilter = new JComboBox<>(new String[]{"Any snapshot age", "Newer than hours", "At least hours", "Unknown snapshot age"});
    private final JSpinner ageHours = new JSpinner(new SpinnerNumberModel(24, 0, 1000000, 1));
    private final JLabel heading = new JLabel("Select a character");
    private final JTextArea summary = ContentStyle.wrappingText(""), status = ContentStyle.wrappingText(""), seen = ContentStyle.wrappingText(" ");
    private final JButton death = new JButton("Mark dead"), saveNotes = new JButton("Save notes");
    private final JTextArea notes = new JTextArea(3, 30);
    private final DefaultTableModel rosterModel = new DefaultTableModel(new String[]{"Character", "Account", "State", "Season", "Level", "Maxed", "Fame", "Last snapshot update", "Potions remaining"}, 0) {
        public boolean isCellEditable(int row, int col) { return false; }
        public Class<?> getColumnClass(int col) { return col == 4 || col == 5 ? Integer.class : col >= 6 ? Long.class : String.class; }
    };
    private final JTable roster = table(rosterModel);
    private final DefaultTableModel statModel = model("Stat", "Base", "Cap", "Potions to max", "Field evidence");
    private final CharacterEquipmentPanel equipmentPanel = new CharacterEquipmentPanel();
    private final CharacterPlanningPanel planningPanel;
    private final CharacterDeathPanel deathPanel;
    private final DefaultTableModel metadataModel = model("Field", "Value", "Field evidence");
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
    private RosterViewState viewState;
    private final JPanel stateHost = new JPanel(new BorderLayout());
    private final JTabbedPane tabs = new JTabbedPane();
    private JScrollPane pageScroll;
    private String pendingSelectionKey;
    private boolean restoringState;

    public CharacterJournalGUI(CharacterJournal journal) {
        this(journal, System::currentTimeMillis);
        bindViewState(ViewStateStore.application());
    }

    CharacterJournalGUI(CharacterJournal journal, java.util.function.LongSupplier clock) {
        this(journal, clock, RosterDefinitions::current);
    }

    CharacterJournalGUI(CharacterJournal journal, java.util.function.LongSupplier clock, java.util.function.Supplier<RosterDefinitions> definitionsSource) {
        this(journal, clock, definitionsSource, tomato.planning.PlanningStore.shared());
    }
    CharacterJournalGUI(CharacterJournal journal, java.util.function.LongSupplier clock, java.util.function.Supplier<RosterDefinitions> definitionsSource, tomato.planning.PlanningStore plans) {
        super(new BorderLayout(0, 8)); this.journal = journal; this.clock = Objects.requireNonNull(clock); this.definitionsSource = definitionsSource;
        planningPanel = new CharacterPlanningPanel(plans);
        deathPanel = new CharacterDeathPanel(journal);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel top = new JPanel(new BorderLayout(0, 6));
        summary.setName("character-summary");
        seen.setName("character-snapshot-evidence");
        ContentStyle.font(summary, ContentStyle.body()); top.add(summary, BorderLayout.NORTH);
        seen.setFont(ContentStyle.metadata(ContentStyle.body())); status.setFont(ContentStyle.metadata(ContentStyle.body()));
        JPanel filters = ContentStyle.controls();
        search.putClientProperty("JTextField.placeholderText", "Search class, account, ID, equipment…");
        search.setToolTipText("Search class, account name, character ID, item names or notes");
        search.setName("character-search"); search.getAccessibleContext().setAccessibleName("Search saved characters");
        life.getAccessibleContext().setAccessibleName("Character life state"); season.getAccessibleContext().setAccessibleName("Character season");
        filters.add(search); filters.add(life); filters.add(season);
        boundChoiceWidth(accountFilter, "Account name · 000000");
        boundChoiceWidth(classFilter, "Class name (#00000)");
        accountFilter.addItem(new Choice<>(null, "All accounts")); classFilter.addItem(new Choice<>(null, "All classes"));
        filters.add(accountFilter); filters.add(classFilter); filters.add(needsLife); filters.add(missing); filters.add(maxedFilter);
        filters.add(new JLabel("Maxed from")); filters.add(minMaxed); filters.add(new JLabel("to")); filters.add(maxMaxed);
        filters.add(ageFilter); filters.add(ageHours);
        JButton reset = new JButton("Reset filters"); filters.add(reset); top.add(filters);
        String[] facetNames = {"Account", "Class", "Life need", "Stat coverage", "Maxed count", "Minimum maxed stats", "Maximum maxed stats", "Snapshot update age", "Age in hours"};
        JComponent[] facets = {accountFilter, classFilter, needsLife, missing, maxedFilter, minMaxed, maxMaxed, ageFilter, ageHours};
        for (int i = 0; i < facets.length; i++) { facets[i].setName("character-facet-" + i); facets[i].getAccessibleContext().setAccessibleName(facetNames[i]); }
        reset.addActionListener(e -> {
            refreshing = true;
            search.setText(""); for (JComboBox<?> box : new JComboBox<?>[]{life, season, accountFilter, classFilter, needsLife, missing, maxedFilter, ageFilter}) box.setSelectedIndex(0);
            minMaxed.setValue(0); maxMaxed.setValue(8); ageHours.setValue(24); refreshing = false; filter();
        });
        roster.setName("character-roster");
        roster.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); roster.setAutoCreateRowSorter(true);
        roster.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths = {150, 130, 165, 85, 55, 70, 80, 150, 140};
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
        roster.getColumnModel().getColumn(5).setCellRenderer(new ContentStyle.Cell() {
            @Override protected void setValue(Object value) { setText(value == null ? "Unknown" : value + "/8"); }
        });
        roster.getColumnModel().getColumn(8).setCellRenderer(new ContentStyle.Cell() {
            @Override protected void setValue(Object value) { setText(value == null ? "Unknown" : DisplayFormat.formatInteger(((Number)value).longValue())); }
        });
        roster.getTableHeader().setToolTipText("Potions remaining: complete known base stats and caps; +5 Life/Mana and +1 other stats. Unknown totals are not zero.");
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
        tabs.setName("character-detail-tabs"); tabs.setTabLayoutPolicy(JTabbedPane.WRAP_TAB_LAYOUT);
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
        tabs.addTab("Equipment & inventory", equipmentPanel);
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
        tabs.addTab("Snapshot evidence", ContentStyle.tableScroll(table(metadataModel), 3));
        tabs.addTab("Goals", planningPanel);
        tabs.addTab("Death annotation", deathPanel);
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
        JPanel footer = new JPanel(new BorderLayout(0, 4)); footer.add(status, BorderLayout.NORTH); footer.add(stateHost, BorderLayout.CENTER); stateHost.setVisible(false);
        JScrollPane page = ContentStyle.page(top, split, footer); pageScroll = page;
        page.setName("character-page-scroll");
        page.getAccessibleContext().setAccessibleName("Characters; scroll for roster, details and actions at large text sizes");
        add(page, BorderLayout.CENTER);
        for (JComponent control : new JComponent[]{search, life, season, death, saveNotes, accountFilter, classFilter, needsLife, missing, maxedFilter, minMaxed, maxMaxed, ageFilter, ageHours, reset}) {
            JComponent focus = control instanceof JSpinner ? ((JSpinner.DefaultEditor)((JSpinner)control).getEditor()).getTextField() : control;
            focus.addFocusListener(new java.awt.event.FocusAdapter() {
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
        for (JComboBox<?> facet : new JComboBox<?>[]{accountFilter, classFilter, needsLife, missing, maxedFilter, ageFilter}) facet.addActionListener(e -> filter());
        minMaxed.addChangeListener(e -> filter()); maxMaxed.addChangeListener(e -> filter()); ageHours.addChangeListener(e -> filter());
        roster.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !refreshing) select(!restoringState); });
        death.addActionListener(e -> { CharacterRecord r = selected(); if (r != null) { journal.markDead(r.key, !r.dead); refresh(); } });
        saveNotes.addActionListener(e -> { if (selectedKey != null) { journal.notes(selectedKey, notes.getText()); refresh(); } });
        timer = new javax.swing.Timer(1000, e -> { if (isShowing() || exalts.isShowing()) refresh(); });
        java.awt.event.HierarchyListener visibility = e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && e.getComponent().isShowing()) refresh();
        };
        addHierarchyListener(visibility); exalts.addHierarchyListener(visibility); refresh();
    }
    @Override public void addNotify() { super.addNotify(); timer.start(); refresh(); }
    @Override public void removeNotify() { if (viewState != null) viewState.save(); super.removeNotify(); if (!exalts.isDisplayable()) timer.stop(); }
    public JPanel exaltPanel() { return exalts; }
    public void bindNavigator(tomato.gui.route.Navigator navigator) { deathPanel.bindNavigator(navigator); }
    public void refresh() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::refresh); return; }
        boolean project = false;
        RosterDefinitions nextDefinitions = definitionsSource.get();
        if (nextDefinitions != definitions) { definitions = nextDefinitions; project = true; rosterDirty = true; }
        synchronized (journal) {
            if (revision != journal.revision()) {
                records = journal.characters(); accounts = journal.accounts(); revision = journal.revision();
                rosterDirty = exaltsDirty = true;
                project = true;
            }
        }
        if (project) {
            projected.clear(); for (CharacterRecord record : records) projected.put(record.key, new CharacterRosterQuery.Row(record, definitions));
            rebuildFacets();
        }
        // The Exalts page can be mounted independently of the roster in the workspace.
        boolean detached = !isDisplayable() && !exalts.isDisplayable();
        if (exaltsDirty && (exalts.isShowing() || detached)) refreshExalts();
        if (rosterDirty && (isShowing() || detached)) filter();
        else if ((isShowing() || detached) && ageFilter.getSelectedIndex() != 0 && !matchingKeys().equals(filteredKeys())) filter();
        if (isShowing() || detached) { refreshTimeEvidence(selected()); deathPanel.showRecord(selected()); }
        if (isShowing() || detached) planningPanel.refresh(records, accounts, definitions);
        String storageStatus = journal.storageStatus();
        if (records.isEmpty() && storageStatus.startsWith("Saved"))
            storageStatus = "Start capture and enter the game on a character. Account identity is required before saving.";
        else if (!records.isEmpty() && filtered.isEmpty()) storageStatus = "No matching characters. Reset filters to show the retained roster. · " + storageStatus;
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
        if (refreshing) return;
        rosterDirty = false;
        refreshing = true;
        String oldKey = pendingSelectionKey != null ? pendingSelectionKey : selectedKey;
        filtered = new ArrayList<>(); rosterModel.setRowCount(0);
        CharacterRosterQuery query = query(); long now = clock.getAsLong();
        int alive = 0, deadCount = 0, maxed = 0;
        for (CharacterRecord r : records) {
            CharacterRosterQuery.Row row = projected.get(r.key);
            if (r.dead) deadCount++; else { alive++; if (Integer.valueOf(8).equals(row.maxed)) maxed++; }
            if (!query.matches(row, now, accountName(r.account), CharacterJournalGUI::itemName, CharacterJournalGUI::className)) continue;
            filtered.add(r);
            rosterModel.addRow(new Object[]{className(r.classId) + " #" + r.characterId, accountName(r.account), lifeLabel(r),
                r.seasonal == null ? "Unknown" : r.seasonal ? "Seasonal" : "Regular", r.level,
                row.maxed, r.fame, r.lastSeen, row.potions});
        }
        summary.setText(records.isEmpty() ? "Your saved characters will appear here" : DisplayFormat.formatInteger(alive) + " not marked dead  •  "
                + DisplayFormat.formatInteger(deadCount) + " marked dead manually  •  " + DisplayFormat.formatInteger(maxed) + " at 8/8  •  "
                + DisplayFormat.formatInteger(filtered.size()) + " shown");
        if (records.isEmpty() && journal.storageStatus().startsWith("Saved")) status.setText("Start capture and enter the game on a character. Account identity is required before saving.");
        else if (filtered.isEmpty()) status.setText("No matching characters. Reset filters to show the retained roster.");
        for (int i = 0; i < filtered.size(); i++) if (filtered.get(i).key.equals(oldKey)) {
            int view = roster.convertRowIndexToView(i); roster.setRowSelectionInterval(view, view); break;
        }
        if (roster.getSelectedRow() < 0 && !filtered.isEmpty() && pendingSelectionKey == null) roster.setRowSelectionInterval(0, 0);
        refreshing = false; select(false);
        rememberViewState();
    }
    private CharacterRecord selected() { int row = roster.getSelectedRow(); return row < 0 ? null : filtered.get(roster.convertRowIndexToModel(row)); }
    private void select(boolean explicitSelection) {
        CharacterRecord r = selected(); String newKey = r == null ? null : r.key;
        if (explicitSelection && r != null) pendingSelectionKey = null;
        boolean changed = !Objects.equals(selectedKey, newKey);
        if (changed && selectedKey != null) {
            for (CharacterRecord previous : records) if (previous.key.equals(selectedKey) && !Objects.equals(previous.notes, notes.getText())) {
                journal.notes(selectedKey, notes.getText()); previous.notes = notes.getText();
            }
        }
        selectedKey = newKey;
        if (Objects.equals(selectedKey, pendingSelectionKey)) pendingSelectionKey = null;
        statModel.setRowCount(0); charExaltModel.setRowCount(0); metadataModel.setRowCount(0);
        equipmentPanel.showRecord(r, definitions); deathPanel.showRecord(r);
        death.setEnabled(r != null); saveNotes.setEnabled(r != null); notes.setEnabled(r != null);
        if (r == null) { heading.setText("Select a character"); heading.setIcon(null); seen.setText(" "); seen.setToolTipText(null); notes.setText(""); return; }
        heading.setText(className(r.classId) + " #" + r.characterId + (r.dead ? " • Marked dead manually" : ""));
        heading.setIcon(ImageBuffer.getOutlinedIcon(r.skin == null || r.skin == 0 ? r.classId : r.skin, 28));
        death.setText(r.dead ? r.observedAgainAt > 0 ? "Observed again—restore?" : "Restore alive" : "Mark dead");
        refreshTimeEvidence(r);
        seen.setToolTipText(r.source);
        String[] fields = {"class", "level", "skin", "fame", "seasonal", "created"};
        Object[] values = {r.className, r.level, r.skin, r.fame, r.seasonal == null ? null : r.seasonal ? "Seasonal" : "Regular", r.created};
        for (int i = 0; i < fields.length; i++) metadataModel.addRow(new Object[]{fields[i], unknown(values[i]), evidence(r, fields[i], values[i] != null)});
        if (changed) notes.setText(r.notes);
        for (int i = 0; i < 8; i++) {
            Integer cap = definitions.cap(r.classId, i);
            statModel.addRow(new Object[]{CharacterJournal.STATS[i], unknown(r.stats[i]), unknown(cap),
                cap == null || r.stats[i] == null ? "Unknown" : CharacterJournal.potions(r.stats[i], cap, i), evidence(r, "stat." + i, r.stats[i] != null)});
        }
        int[] exalt = null;
        for (AccountRecord a : accounts) if (a.key.equals(r.account)) exalt = a.exalts.get(r.classId);
        for (int i = 0; i < 8; i++) {
            Integer count = exalt == null ? null : exalt[CharacterJournal.EXALT_ORDER[i]];
            charExaltModel.addRow(new Object[]{CharacterJournal.STATS[i], count == null ? "Unknown" : CharacterJournal.exaltLevel(count) + "/5", unknown(count), count == null ? "Unknown" : next(count)});
        }
        rememberViewState();
    }
    public void bindViewState(ViewStateStore store) {
        if (viewState != null) return;
        viewState = new RosterViewState(store, "characters-live-roster", this::captureViewState, this::prepareViewState);
        stateHost.add(viewState.controls()); stateHost.setVisible(true);
        RosterViewState.listenTable(roster, this::rememberViewState);
        tabs.addChangeListener(e -> rememberViewState());
        pageScroll.getViewport().addChangeListener(e -> { if (isShowing()) rememberViewState(); });
    }
    public java.util.concurrent.CompletionStage<util.PreferencesStore.SaveResult> saveViewState() {
        if (viewState == null) throw new IllegalStateException("View state is not bound"); return viewState.save();
    }
    private void rememberViewState() { if (viewState != null && !refreshing && !restoringState) viewState.changed(); }
    private Map<String, String> captureViewState() {
        Map<String, String> values = new LinkedHashMap<>(); CharacterRosterQuery q = query();
        values.put("text", search.getText()); values.put("account", Objects.toString(q.account, "")); values.put("class", Objects.toString(q.classId, ""));
        values.put("life", new String[]{"ANY", "NOT_MARKED", "MANUAL_DEAD"}[life.getSelectedIndex()]);
        values.put("season", new String[]{"ANY", "SEASONAL", "REGULAR", "UNKNOWN"}[season.getSelectedIndex()]);
        values.put("needsLife", q.life.name()); values.put("missing", q.missing.name()); values.put("maxed", q.maxed.name()); values.put("age", q.age.name());
        values.put("minimum", minMaxed.getValue().toString()); values.put("maximum", maxMaxed.getValue().toString()); values.put("hours", ageHours.getValue().toString());
        values.put("selected", Objects.toString(pendingSelectionKey != null ? pendingSelectionKey : selectedKey, ""));
        values.put("tab", Integer.toString(tabs.getSelectedIndex())); values.put("pageY", Integer.toString(pageScroll.getViewport().getViewPosition().y));
        RosterViewState.captureTable(values, roster); return values;
    }
    private Runnable prepareViewState(Map<String, String> values) {
        int savedLife = RosterViewState.option(values, "life", life.getSelectedIndex(), "ANY", "NOT_MARKED", "MANUAL_DEAD");
        int savedSeason = RosterViewState.option(values, "season", season.getSelectedIndex(), "ANY", "SEASONAL", "REGULAR", "UNKNOWN");
        CharacterRosterQuery.NeedLife need = CharacterRosterQuery.NeedLife.valueOf(values.getOrDefault("needsLife", query().life.name()));
        CharacterRosterQuery.Missing coverage = CharacterRosterQuery.Missing.valueOf(values.getOrDefault("missing", query().missing.name()));
        CharacterRosterQuery.Maxed maxed = CharacterRosterQuery.Maxed.valueOf(values.getOrDefault("maxed", query().maxed.name()));
        CharacterRosterQuery.Age age = CharacterRosterQuery.Age.valueOf(values.getOrDefault("age", query().age.name()));
        int minimum = RosterViewState.number(values, "minimum", (Integer)minMaxed.getValue(), 0, 8), maximum = RosterViewState.number(values, "maximum", (Integer)maxMaxed.getValue(), 0, 8);
        int hours = RosterViewState.number(values, "hours", (Integer)ageHours.getValue(), 0, 1000000), tab = RosterViewState.number(values, "tab", tabs.getSelectedIndex(), 0, tabs.getTabCount() - 1);
        int y = RosterViewState.number(values, "pageY", 0, 0, Integer.MAX_VALUE);
        String account = values.getOrDefault("account", Objects.toString(choice(accountFilter), ""));
        if (!account.isEmpty() && !account.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid account reference");
        String classText = values.getOrDefault("class", Objects.toString(choice(classFilter), "")); Integer clazz = classText.isEmpty() ? null : Integer.valueOf(classText);
        String selected = values.getOrDefault("selected", Objects.toString(selectedKey, ""));
        if (!selected.isEmpty() && !selected.matches("[0-9a-f]{64}:[0-9]+")) throw new IllegalArgumentException("Invalid character reference");
        Runnable tableState = RosterViewState.prepareTable(values, roster);
        return () -> {
            restoringState = refreshing = true;
            try {
                search.setText(values.getOrDefault("text", search.getText())); life.setSelectedIndex(savedLife); season.setSelectedIndex(savedSeason);
                selectChoice(accountFilter, account.isEmpty() ? null : account, account.isEmpty() ? "All accounts" : accountName(account));
                selectChoice(classFilter, clazz, clazz == null ? "All classes" : className(clazz));
                needsLife.setSelectedIndex(need.ordinal()); missing.setSelectedIndex(coverage.ordinal()); maxedFilter.setSelectedIndex(maxed.ordinal()); ageFilter.setSelectedIndex(age.ordinal());
                minMaxed.setValue(minimum); maxMaxed.setValue(maximum); ageHours.setValue(hours); tabs.setSelectedIndex(tab);
                pendingSelectionKey = selected.isEmpty() ? null : selected;
                refreshing = false; filter(); tableState.run();
                SwingUtilities.invokeLater(() -> pageScroll.getViewport().setViewPosition(new Point(0, Math.min(y,
                    Math.max(0, pageScroll.getViewport().getViewSize().height - pageScroll.getViewport().getExtentSize().height)))));
            } finally { refreshing = restoringState = false; }
        };
    }
    private static <T> void selectChoice(JComboBox<Choice<T>> box, T value, String label) {
        for (int i = 0; i < box.getItemCount(); i++) if (Objects.equals(box.getItemAt(i).value, value)) { box.setSelectedIndex(i); return; }
        Choice<T> choice = new Choice<>(value, label); box.addItem(choice); box.setSelectedItem(choice);
    }
    /** Time advances even after capture stops; refresh just this text, not selection or editable drafts. */
    private void refreshTimeEvidence(CharacterRecord r) {
        if (r == null) return;
        long age = r.lastSeen <= 0 ? -1 : Math.max(0, (clock.getAsLong() - r.lastSeen) / 1000);
        String text = "Last observed alive " + date(r.lastObservedAlive) + "  •  Roster received " + date(r.rosterReceivedAt)
            + "\nSnapshot update age: " + (age < 0 ? "Unknown" : age + "s") + " · "
            + Arrays.stream(r.stats).filter(Objects::nonNull).count() + "/8 known stats · "
            + Arrays.stream(r.equipment).filter(Objects::nonNull).count() + "/28 known slots (may be retained)"
            + (r.dead ? "\nMarked dead manually " + date(r.diedAt) + "; preserved snapshot."
                + (r.observedAgainAt > 0 ? " Reported again " + date(r.observedAgainAt) + ". Restore explicitly to accept updates." : "") : "");
        if (!seen.getText().equals(text)) seen.setText(text);
    }
    private CharacterRosterQuery query() {
        return new CharacterRosterQuery(search.getText(), choice(accountFilter), choice(classFilter), life.getSelectedIndex() == 0 ? null : life.getSelectedIndex() == 2,
            season.getSelectedIndex() == 1 ? Boolean.TRUE : season.getSelectedIndex() == 2 ? Boolean.FALSE : null, season.getSelectedIndex() == 3,
            CharacterRosterQuery.NeedLife.values()[needsLife.getSelectedIndex()], CharacterRosterQuery.Missing.values()[missing.getSelectedIndex()],
            CharacterRosterQuery.Maxed.values()[maxedFilter.getSelectedIndex()], (Integer)minMaxed.getValue(), (Integer)maxMaxed.getValue(),
            CharacterRosterQuery.Age.values()[ageFilter.getSelectedIndex()], ((Number)ageHours.getValue()).longValue() * 3600000L);
    }
    private List<String> matchingKeys() {
        List<String> keys = new ArrayList<>(); CharacterRosterQuery q = query(); long now = clock.getAsLong();
        for (CharacterRosterQuery.Row row : projected.values()) if (q.matches(row, now, accountName(row.record.account), CharacterJournalGUI::itemName, CharacterJournalGUI::className)) keys.add(row.record.key);
        return keys;
    }
    private List<String> filteredKeys() { List<String> keys = new ArrayList<>(); for (CharacterRecord r : filtered) keys.add(r.key); return keys; }
    private void rebuildFacets() {
        refreshing = true;
        String account = choice(accountFilter); Integer clazz = choice(classFilter);
        accountFilter.removeAllItems(); classFilter.removeAllItems();
        accountFilter.addItem(new Choice<>(null, "All accounts")); classFilter.addItem(new Choice<>(null, "All classes"));
        Set<String> seenAccounts = new LinkedHashSet<>(); Set<Integer> classes = new TreeSet<>();
        for (CharacterRecord r : records) { seenAccounts.add(r.account); classes.add(r.classId); }
        if (account != null) seenAccounts.add(account); if (clazz != null) classes.add(clazz);
        for (String key : seenAccounts) { Choice<String> c = new Choice<>(key, accountName(key)); accountFilter.addItem(c); if (key.equals(account)) accountFilter.setSelectedItem(c); }
        for (Integer id : classes) { Choice<Integer> c = new Choice<>(id, className(id) + " (#" + id + ")"); classFilter.addItem(c); if (id.equals(clazz)) classFilter.setSelectedItem(c); }
        refreshing = false;
    }
    private static <T> void boundChoiceWidth(JComboBox<Choice<T>> box, String prototype) {
        // Names from captured records must not widen a FlowLayout row beyond the page.
        // The model and accessible selected value keep the complete label.
        box.setPrototypeDisplayValue(new Choice<>(null, prototype));
        box.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                    boolean selected, boolean focused) {
                putClientProperty("html.disable", true);
                super.getListCellRendererComponent(list, value, index, selected, focused);
                // JToolTip has its own HTML parser; a fixed plain-text prefix keeps
                // captured labels beginning with <html> literal in the popup too.
                setToolTipText("Full label: " + Objects.toString(value, ""));
                return this;
            }
        });
        box.addActionListener(e -> {
            String label = Objects.toString(box.getSelectedItem(), "");
            box.setToolTipText("Full label: " + label); box.getAccessibleContext().setAccessibleDescription(label);
        });
    }
    private static <T> T choice(JComboBox<Choice<T>> box) { Choice<T> c = (Choice<T>)box.getSelectedItem(); return c == null ? null : c.value; }
    private static final class Choice<T> { final T value; final String label; Choice(T value, String label) { this.value = value; this.label = label; } public String toString() { return label; } }
    private String accountName(String key) { for (AccountRecord a : accounts) if (a.key.equals(key)) return (a.name == null ? "Account" : a.name) + " · " + key.substring(0, Math.min(6, key.length())); return "Account · " + key.substring(0, Math.min(6, key.length())); }
    private static String lifeLabel(CharacterRecord r) {
        return r.dead ? "Marked dead manually" : r.lastObservedAlive > 0 ? "Last observed alive" : r.rosterReceivedAt > 0 ? "Reported in roster" : "Legacy life state";
    }
    private static String evidence(CharacterRecord r, String key, boolean known) {
        if (!known) return "Not captured";
        FieldCapture field = r.fields.get(key);
        if (field == null) return "Legacy / provenance unknown";
        return field.source + " · " + date(field.at) + (field.at > 0 && field.at < r.lastSeen ? " · Retained from earlier observation" : "");
    }
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
        ContentStyle.reveal(control, region);
    }
    private static JTextArea note(String text) {
        return ContentStyle.wrappingText(text);
    }
}
