package tomato.gui.character;

import assets.IdToAsset;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.backend.data.CharacterJournal.AccountRecord;
import tomato.backend.data.RosterDefinitions;
import tomato.realmshark.enums.CharacterClass;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.stats.Formatters;
import tomato.gui.history.ViewStateStore;
import tomato.gui.roster.RosterViewState;
import tomato.gui.history.FilterChips;
import tomato.gui.history.HistoryTables;
import tomato.gui.kit.ColumnKind;
import tomato.gui.history.WrapRow;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.Banner;
import tomato.gui.kit.Tokens;
import util.PropertiesManager;

/** Searchable persistent roster with explicit unknowns. Enter or a double-click opens a character's sheet (CharacterRosterView). */
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
    private final JTextArea summary = ContentStyle.wrappingText(""), status = ContentStyle.wrappingText("");
    private final DefaultTableModel rosterModel = new DefaultTableModel(new String[]{"Character", "Account", "State", "Season", "Level", "Maxed", "Fame", "Last snapshot update", "Potions remaining"}, 0) {
        public boolean isCellEditable(int row, int col) { return false; }
        public Class<?> getColumnClass(int col) { return col == 4 || col == 5 ? Integer.class : col >= 6 ? Long.class : String.class; }
    };
    private final JTable roster = table(rosterModel);
    private final DefaultTableModel exaltModel = model("Account", "Class", "Stat", "Level", "Completions", "Next tier", "Observed");
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
    /**
     * Sheet tab ids by the saved "tab" index. Index i names the sheet tab that replaced the old detail pane's tab i
     * (stats, equipment, exalts, notes, evidence, goals, death), so views saved before the sheet keep their tab.
     */
    static final String[] SHEET_TABS = {"overview", "gear", "exalts", "notes", "evidence", "goals", "death"};
    private static final java.util.regex.Pattern TAB_ID = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9-]*");
    /** The sheet tab selected when the sheet opens without an explicit tab: restored at startup, then the last one chosen. */
    private String sheetTab;
    private java.util.function.Consumer<String> openSheet = key -> { };
    private final List<Runnable> rowsListeners = new ArrayList<>();
    private JScrollPane pageScroll;
    private String pendingSelectionKey;
    private boolean restoringState;
    private final FilterBar filterBar = new FilterBar("characters");
    private Runnable clearFilters = () -> {};
    /** Gallery | Table below the one filter row: both show visibleRows(), and either opens the sheet (spec §6.2). */
    private final RosterViews views;
    /** The live character's journal key for the gallery's "Playing now"; CharacterPanelGUI supplies it (exact key, never a name). */
    private java.util.function.Supplier<String> liveKey = () -> null;
    /** The saved view's status, shown only while it is a failure (the actions themselves are in the ⋯ menu). */
    private final Banner stateBanner = new Banner("character-view-state");
    /** The gallery's last-seen storage problem: save() runs on a background writer and never bumps revision, so this is
     * compared on every refresh() instead of only when a data change already calls filter(). */
    private String galleryProblem;

    public CharacterJournalGUI(CharacterJournal journal) {
        this(journal, System::currentTimeMillis);
        bindViewState(ViewStateStore.application());
    }

    CharacterJournalGUI(CharacterJournal journal, java.util.function.LongSupplier clock) {
        this(journal, clock, RosterDefinitions::current);
    }

    CharacterJournalGUI(CharacterJournal journal, java.util.function.LongSupplier clock, java.util.function.Supplier<RosterDefinitions> definitionsSource) {
        super(new BorderLayout(0, 8)); this.journal = journal; this.clock = Objects.requireNonNull(clock); this.definitionsSource = definitionsSource;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel top = new JPanel(new BorderLayout(0, 6));
        summary.setName("character-summary");
        ContentStyle.font(summary, ContentStyle.body()); top.add(summary, BorderLayout.NORTH);
        status.setFont(ContentStyle.metadata(ContentStyle.body()));
        JPanel filters = ContentStyle.controls();
        search.putClientProperty("JTextField.placeholderText", "Search class, account, ID, equipment…");
        search.setToolTipText("Search class, account name, character ID, item names or notes");
        search.setName("character-search"); search.getAccessibleContext().setAccessibleName("Search saved characters");
        life.getAccessibleContext().setAccessibleName("Character life state"); season.getAccessibleContext().setAccessibleName("Character season");
        life.setName("character-life"); season.setName("character-season");
        filters.add(life); filters.add(season);
        boundChoiceWidth(accountFilter, "Account name · 000000");
        boundChoiceWidth(classFilter, "Class name (#00000)");
        accountFilter.addItem(new Choice<>(null, "All accounts")); classFilter.addItem(new Choice<>(null, "All classes"));
        filters.add(accountFilter); filters.add(classFilter); filters.add(needsLife); filters.add(missing); filters.add(maxedFilter);
        filters.add(new JLabel("Maxed from")); filters.add(minMaxed); filters.add(new JLabel("to")); filters.add(maxMaxed);
        filters.add(ageFilter); filters.add(ageHours);
        // One filter row (search + reset); life, season and every roster facet live in the drawer.
        JButton reset = new JButton("Reset filters");
        WrapRow searchRow = new WrapRow(search, reset);
        filterBar.search(searchRow).drawer(filters); clearFilters = reset::doClick; top.add(filterBar);
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
        Map<String, ColumnKind> kinds = new HashMap<>();
        kinds.put("Character", ColumnKind.PLAYER); kinds.put("Account", ColumnKind.PLAYER); kinds.put("State", ColumnKind.STATUS); kinds.put("Season", ColumnKind.STATUS);
        kinds.put("Level", ColumnKind.COUNT); kinds.put("Maxed", ColumnKind.COUNT); kinds.put("Fame", ColumnKind.COUNT);
        kinds.put("Last snapshot update", ColumnKind.DATE_TIME); kinds.put("Potions remaining", ColumnKind.COUNT);
        HistoryTables.kinds(roster, kinds);
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
        roster.getAccessibleContext().setAccessibleDescription("Enter or double-click opens the character's sheet");
        roster.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open-character");
        roster.getActionMap().put("open-character", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { openRow(roster.getSelectedRow()); }
        });
        roster.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) openRow(roster.rowAtPoint(e.getPoint()));
            }
        });
        // A user sort reorders the visible rows; filter() reports its own rebuild once.
        roster.getRowSorter().addRowSorterListener(e -> { if (e.getType() == RowSorterEvent.Type.SORTED && !refreshing) rowsChanged(); });
        JPanel footer = new JPanel(new BorderLayout(0, 4)); footer.add(status, BorderLayout.NORTH);
        // Saved views live in the ⋯ menu (spec §3.2); a failure shows as a warn banner under the filter row, nothing else does.
        stateBanner.setTone(Tokens.Tone.WARN); stateBanner.setVisible(false); stateHost.add(stateBanner); stateHost.setVisible(false);
        top.add(stateHost, BorderLayout.SOUTH);
        // Gallery | Table below the one filter row (spec §6.2): both views show visibleRows(), and either opens the sheet.
        JComponent rosterArea = ContentStyle.tableScroll(roster, 3);
        views = new RosterViews(rosterArea, filterBar, searchRow, new RosterViews.Source() {
            @Override public List<CharacterRosterQuery.Row> rows() { return visibleRows(); }
            @Override public boolean saved() { return !records.isEmpty(); }
            @Override public String problem() { return journal.storageProblem(); }
            @Override public String liveKey() { return liveKey.get(); }
            @Override public String selectedKey() { return selectedKey; }
            @Override public void select(String key) { selectKey(key); }
            @Override public void open(String key) { openSheet.accept(key); }
        }, DisplayModeModel.application(), PropertiesManager::getProperty, PropertiesManager::setProperties);
        addRowsListener(views::refresh);
        JScrollPane page = ContentStyle.page(top, views.body(), footer); pageScroll = page;
        page.setName("character-page-scroll");
        page.getAccessibleContext().setAccessibleName("Characters; scroll for the roster and its actions at large text sizes");
        add(page, BorderLayout.CENTER);
        for (JComponent control : new JComponent[]{search, life, season, accountFilter, classFilter, needsLife, missing, maxedFilter, minMaxed, maxMaxed, ageFilter, ageHours, reset}) {
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
        timer = new javax.swing.Timer(1000, e -> { if (isShowing() || exalts.isShowing()) refresh(); });
        java.awt.event.HierarchyListener visibility = e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && e.getComponent().isShowing()) refresh();
        };
        addHierarchyListener(visibility); exalts.addHierarchyListener(visibility); refresh();
    }
    @Override public void addNotify() { super.addNotify(); timer.start(); refresh(); }
    @Override public void removeNotify() { if (viewState != null) viewState.save(); super.removeNotify(); if (!exalts.isDisplayable()) timer.stop(); }
    public JPanel exaltPanel() { return exalts; }
    /** Where the gallery reads the live character's journal key (null when no character is in game). EDT. */
    public void setLiveKey(java.util.function.Supplier<String> source) { liveKey = Objects.requireNonNull(source); views.refresh(); }
    RosterViews views() { return views; }
    /** Selects {@code key}'s row, as a card selection in the gallery does, so currentKey() and the saved selection follow it. EDT. */
    void selectKey(String key) {
        for (int i = 0; key != null && i < filtered.size(); i++) if (filtered.get(i).key.equals(key)) {
            int view = roster.convertRowIndexToView(i);
            if (view >= 0 && roster.getSelectedRow() != view) roster.setRowSelectionInterval(view, view);
            return;
        }
    }
    /**
     * Where Back from the sheet puts keyboard focus: the gallery's selected card while the gallery shows and has one (an
     * EmptyState instead of the card list is not in the tree, so it can never take focus: fall back to the search field), else
     * the table.
     */
    JComponent focusTarget() {
        if (!views.galleryShown()) return roster;
        JComponent target = views.gallery().focusTarget();
        // The card list stays a field of the gallery even while an EmptyState (no match / unavailable) shows instead of it, so
        // it is only really reachable when it still descends from the gallery.
        return SwingUtilities.isDescendingFrom(target, views.gallery()) ? target : search;
    }
    /** Enter or a double-click on a row passes that character's journal key here; the Roster tab opens its sheet. */
    public void onOpenSheet(java.util.function.Consumer<String> open) { openSheet = Objects.requireNonNull(open); }
    /** The rows the search and filters keep, in the table's current sort order (the gallery shows exactly these). EDT only. */
    public List<CharacterRosterQuery.Row> visibleRows() {
        List<CharacterRosterQuery.Row> rows = new ArrayList<>();
        for (int view = 0; view < roster.getRowCount(); view++) rows.add(projected.get(filtered.get(roster.convertRowIndexToModel(view)).key));
        return rows;
    }
    /** Runs after the visible rows or their order change: filters, search, a journal change or a new sort. EDT only. */
    public void addRowsListener(Runnable listener) { rowsListeners.add(Objects.requireNonNull(listener)); }
    private void rowsChanged() { for (Runnable listener : new ArrayList<>(rowsListeners)) listener.run(); }
    private void openRow(int view) { if (view >= 0 && view < roster.getRowCount()) openSheet.accept(filtered.get(roster.convertRowIndexToModel(view)).key); }
    /** The selected row's character, or null. */
    String selectedKey() { return selectedKey; }
    /** The sheet tab to select when the sheet opens without an explicit tab; null when none was saved or chosen. */
    String sheetTab() { return sheetTab; }
    /** The sheet's settled tab changed; remember it with the list's view state. */
    void sheetTabSelected(String id) { if (id != null && !id.equals(sheetTab)) { sheetTab = id; rememberViewState(); } }
    /** Back from the sheet: the selected card again (in view) while the gallery shows, else the roster table (spec §10). */
    void focusRoster() {
        if (views.galleryShown()) views.gallery().select(selectedKey);
        focusTarget().requestFocusInWindow();
    }
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
        // save() runs on a background writer and changes the storage problem without bumping revision, so this is checked on
        // every refresh() instead of only when filter() already ran from a data change; the gallery's banner would otherwise
        // go stale (never appearing, or not clearing after a later successful save).
        String problem = journal.storageProblem();
        if (!Objects.equals(problem, galleryProblem)) { galleryProblem = problem; views.refresh(); }
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
    /** Active roster facets as removable chips. */
    private void updateChips() {
        List<FilterBar.ActiveFilter> chips = new ArrayList<>();
        for (JComboBox<?> box : new JComboBox<?>[]{life, season, needsLife, missing})
            if (box.getSelectedIndex() > 0) chips.add(new FilterBar.ActiveFilter(String.valueOf(box.getSelectedItem()), () -> box.setSelectedIndex(0)));
        if (accountFilter.getSelectedIndex() > 0) chips.add(new FilterBar.ActiveFilter("Account: " + accountFilter.getSelectedItem(), () -> accountFilter.setSelectedIndex(0)));
        if (classFilter.getSelectedIndex() > 0) chips.add(new FilterBar.ActiveFilter("Class: " + classFilter.getSelectedItem(), () -> classFilter.setSelectedIndex(0)));
        if (maxedFilter.getSelectedIndex() == 1) chips.add(new FilterBar.ActiveFilter("Maxed " + minMaxed.getValue() + "–" + maxMaxed.getValue(), () -> maxedFilter.setSelectedIndex(0)));
        else if (maxedFilter.getSelectedIndex() == 2) chips.add(new FilterBar.ActiveFilter("Unknown maxed count", () -> maxedFilter.setSelectedIndex(0)));
        int age = ageFilter.getSelectedIndex();
        if (age == 1 || age == 2) chips.add(new FilterBar.ActiveFilter((age == 1 ? "Newer than " : "At least ") + ageHours.getValue() + " h", () -> ageFilter.setSelectedIndex(0)));
        else if (age == 3) chips.add(new FilterBar.ActiveFilter("Unknown snapshot age", () -> ageFilter.setSelectedIndex(0)));
        FilterChips.update(filterBar, chips, clearFilters, false);
    }
    private void filter() {
        if (refreshing) return;
        updateChips();
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
        rowsChanged();
    }
    private CharacterRecord selected() { int row = roster.getSelectedRow(); return row < 0 ? null : filtered.get(roster.convertRowIndexToModel(row)); }
    private void select(boolean explicitSelection) {
        CharacterRecord r = selected(); String newKey = r == null ? null : r.key;
        if (explicitSelection && r != null) pendingSelectionKey = null;
        selectedKey = newKey;
        if (Objects.equals(selectedKey, pendingSelectionKey)) pendingSelectionKey = null;
        if (r != null) rememberViewState();
    }
    public void bindViewState(ViewStateStore store) {
        if (viewState != null) return;
        viewState = new RosterViewState(store, "characters-live-roster", this::captureViewState, this::prepareViewState);
        // Saved views live in the ⋯ menu in both modes (spec §3.2); the page shows their status only when it is a failure.
        filterBar.overflow().add("Save view state", () -> viewState.save()).setName("character-save-view");
        filterBar.overflow().add("Reset saved view state", viewState::resetSaved).setName("character-reset-view");
        viewState.onStatus(this::viewStateChanged);
        viewStateChanged();
        RosterViewState.listenTable(roster, this::rememberViewState);
        pageScroll.getViewport().addChangeListener(e -> { if (isShowing()) rememberViewState(); });
    }
    public java.util.concurrent.CompletionStage<util.PreferencesStore.SaveResult> saveViewState() {
        if (viewState == null) throw new IllegalStateException("View state is not bound"); return viewState.save();
    }
    /** The saved view's status shows only while it is a failure: a save failed, or the saved state could not be read. */
    private void viewStateChanged() {
        boolean problem = viewState.statusProblem();
        stateBanner.setText(problem ? viewState.statusText() : "");
        stateBanner.setVisible(problem);
        stateHost.setVisible(problem);
        stateHost.revalidate();
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
        // "tab" keeps its pre-sheet meaning (an index) for the tabs that existed then; "sheetTab" names any tab, including later ones.
        int tab = Arrays.asList(SHEET_TABS).indexOf(sheetTab);
        if (tab >= 0) values.put("tab", Integer.toString(tab));
        values.put("sheetTab", Objects.toString(sheetTab, "")); values.put("pageY", Integer.toString(pageScroll.getViewport().getViewPosition().y));
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
        int hours = RosterViewState.number(values, "hours", (Integer)ageHours.getValue(), 0, 1000000), tab = RosterViewState.number(values, "tab", 0, 0, SHEET_TABS.length - 1);
        int y = RosterViewState.number(values, "pageY", 0, 0, Integer.MAX_VALUE);
        String account = values.getOrDefault("account", Objects.toString(choice(accountFilter), ""));
        if (!account.isEmpty() && !account.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid account reference");
        String classText = values.getOrDefault("class", Objects.toString(choice(classFilter), "")); Integer clazz = classText.isEmpty() ? null : Integer.valueOf(classText);
        String selected = values.getOrDefault("selected", Objects.toString(selectedKey, ""));
        if (!selected.isEmpty() && !selected.matches("[0-9a-f]{64}:[0-9]+")) throw new IllegalArgumentException("Invalid character reference");
        String savedSheetTab = values.getOrDefault("sheetTab", "");
        if (!savedSheetTab.isEmpty() && !TAB_ID.matcher(savedSheetTab).matches()) throw new IllegalArgumentException("Invalid sheet tab");
        Runnable tableState = RosterViewState.prepareTable(values, roster);
        return () -> {
            restoringState = refreshing = true;
            try {
                search.setText(values.getOrDefault("text", search.getText())); life.setSelectedIndex(savedLife); season.setSelectedIndex(savedSeason);
                selectChoice(accountFilter, account.isEmpty() ? null : account, account.isEmpty() ? "All accounts" : accountName(account));
                selectChoice(classFilter, clazz, clazz == null ? "All classes" : className(clazz));
                needsLife.setSelectedIndex(need.ordinal()); missing.setSelectedIndex(coverage.ordinal()); maxedFilter.setSelectedIndex(maxed.ordinal()); ageFilter.setSelectedIndex(age.ordinal());
                minMaxed.setValue(minimum); maxMaxed.setValue(maximum); ageHours.setValue(hours);
                // Remembered only: the sheet selects (never shows) it when it next opens without an explicit tab, so a hidden tab stays hidden.
                if (!savedSheetTab.isEmpty()) sheetTab = savedSheetTab; else if (values.containsKey("tab")) sheetTab = SHEET_TABS[tab];
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
    private static String next(int count) { for (int goal : new int[]{5,15,30,50,75}) if (count < goal) return (goal - count) + " to " + goal; return "Complete"; }
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
