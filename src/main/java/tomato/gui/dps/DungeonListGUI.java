package tomato.gui.dps;

import tomato.backend.data.DpsData;
import tomato.backend.data.TomatoData;
import tomato.gui.history.FilterChips;
import tomato.gui.history.ViewStateStore;
import tomato.gui.history.WrapRow;
import tomato.gui.kit.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.roster.RosterViewState;
import tomato.gui.runs.RunRecapModel;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.encounter.CombatFacts;
import tomato.history.encounter.CombatRetention;
import tomato.history.encounter.CombatSettings;
import tomato.history.link.VisitRef;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Runs & DPS › Recordings (spec §6.3; the encounter library): every combat recording of this app run's memory, saved history
 * and imports, one row per recording ({@link RecordingsSource}: in memory, saved summary, kept full detail and imports merged
 * by recording ID; imported copies are rows of their own that say which row holds the same recording).
 * - Table {@code saved-encounters}: the library's columns (Export check, Entry, Dungeon, Recorded start, Elapsed, Contributors,
 *   Damage, Source file, Local context) plus Run (the recording's own link: "Linked · dungeon · entry time", "Unlinked",
 *   "Legacy · unlinked") and Saved (Summary saved, No saved summary (yet), Full detail · size, Full detail pruned (kept N days),
 *   Imported file). The model's column indices never change; the view orders them Export, Dungeon, Recorded start, Run, Saved,
 *   Elapsed, Damage, Contributors, Source file, Local context, Entry ({@link #VIEW_ORDER}), and Simple hides Entry (a library
 *   hash), Elapsed, Contributors, Source file and Local context ({@link #SIMPLE_HIDDEN}) so its six columns fit the Runs &amp; DPS
 *   table at 1240×800 font 13 without sideways scroll; Analyst shows every column and scrolls them sideways when narrow. A mode
 *   change re-applies the view. The live meter's row is the first row under every sort ({@link PinnedSorter}) and is selected
 *   whenever no listed recording is chosen, so Open reads "Open live meter". Unknown values are "—" or a word with a reason,
 *   never 0.
 * - One filter row, FilterBar {@code encounter-library}: search ({@code encounter-search}), a drawer with source
 *   ({@code encounter-source}: All, This app run, Saved summary, Full detail, Imported), run link ({@code encounter-link}) and
 *   local context ({@code encounter-context}), the scope Last 30 days | All sessions ({@code encounter-scope}), Clear (the old
 *   "Reset filters") and ⋯ Refresh, Save view state and Reset saved view state (their status shows, as a warning line, only when
 *   it is a failure). Filters apply at once to the rows read; the scope reads again. Below it, Open beside the count line; below
 *   the table, the details and the status line with the file actions beside it (Load, Save checked, and View imported encounter
 *   after an import), so at 680×520 font 18 at least three rows show before the page scrolls.
 * - An empty state ({@code encounter-empty}) replaces the table while there is nothing to list: reading for the first time,
 *   the first read failed (Try again), nothing recorded, saved or imported ("No recordings yet" and why), or no recording matches
 *   the filters (Clear filters). The live row stays selected meanwhile, so Open live meter stays one click away.
 * - Opening is explicit: Open ({@code encounter-open}, named for what it does), Enter or a double-click. Selecting a row only
 *   shows its details. A recording in memory with a unique recording ID opens through {@link #onOpenEncounter} (the shell's
 *   ENCOUNTER route), one without an ID or with a duplicated ID through {@link #onShowEntry}; the live row through
 *   {@link #onOpenLive}. Saved full detail is confirmed with its size ({@link #confirmLoad}), read on the recording reader thread
 *   ({@link EncounterImport#readSaved}) and admitted with {@link EncounterCatalog#addSaved} (the recording shown in the meter is
 *   never evicted), then opened; a copy already in memory opens instead and nothing is read. A summary-only recording linked to
 *   its run opens its run recap ({@link #onOpenRecap}); an unlinked or legacy one (or any, without a recap target) shows its
 *   read-only {@link RecordingSummaryPanel} under the table. Pruned full detail is never offered.
 * - Reads (S8): one daemon worker ("RealmShark recordings"), never the EDT, with a newest-result generation guard. The tab reads
 *   on first show; afterwards showing it, and a check every {@value #POLL_MILLIS} ms while it shows, compare the store's stamp
 *   ({@link #stamp}) and read only when it changed; the catalog's revision is compared every {@value #REVISION_MILLIS} ms while
 *   it shows; the scope and ⋯ Refresh always read.
 * - Export checks belong to library entries in memory (entry IDs), the selection to row references; both restore through the
 *   view state {@code encounter-library-live}, which is written only when it differs from what was loaded or last saved.
 * - File names are shown, never folders or paths. EDT only, except the reads.
 */
public class DungeonListGUI extends JPanel implements AutoCloseable {
    /** How often the showing tab compares the store's stamp (records are saved when a fight closes). */
    static final int POLL_MILLIS = 30_000;
    /** How often the showing tab compares the catalog's revision (a fight closed, an import, Clear). */
    static final int REVISION_MILLIS = 500;
    static final String LIVE = "Live", NOT_LOADED = "Not loaded", SAVED_HISTORY = "Saved history";
    /** The live row's Run cell. */
    static final String LIVE_HINT = "The fight in progress · Open live meter";
    static final String NO_SUMMARY = "No saved summary (yet)";
    static final String NO_SUMMARY_TIP = "No saved summary of this recording was read: nothing is saved in preview mode or outside a logged dungeon,"
        + " a save may still be in progress (saved history is read again when it changes), and a failed save is logged.";
    static final String PRUNED_SINCE = "Full detail was pruned or removed since this list was read, so it cannot be loaded.";
    private static final String[] LINKS = {"ANY", "LINKED", "UNLINKED", "LEGACY"};
    /**
     * The view's columns left to right, as model indices: Export, Dungeon, Recorded start, Run, Saved, Elapsed, Damage,
     * Contributors, Source file, Local context, Entry.
     */
    static final int[] VIEW_ORDER = {0, 2, 3, 9, 10, 4, 6, 5, 7, 8, 1};
    /** The model columns Simple hides (spec §3.2, provenance and diagnostics): Entry, Elapsed, Contributors, Source file, Local context. */
    static final Set<Integer> SIMPLE_HIDDEN = Set.of(1, 4, 5, 7, 8);
    /** The column layout the view state's widths belong to ({@code columns}); widths saved by the older layout are not applied. */
    private static final String LAYOUT = "2";
    /** Files of a session folder the list depends on: its metadata, its records and its full-detail files. */
    private static final Set<String> STAMPED = Set.of("session.json", CombatFacts.RECORDS, CombatFacts.RECORDS + ".jsonl", CombatRetention.FULL_DETAIL);

    private final DpsGUI dps;
    private final EncounterCatalog catalog;
    private final Supplier<SessionStore> stores;
    private final LongSupplier clock;
    private final ThreadPoolExecutor worker;
    private final EncounterModel model = new EncounterModel();
    private final JTable table = new JTable(model);
    private final TableRowSorter<EncounterModel> sorter = new PinnedSorter(model);
    /** Every column by model index, shown or hidden (the view shows them in {@link #VIEW_ORDER}). */
    private final TableColumn[] columns;
    private final DisplayModeModel mode;
    private final JScrollPane tableScroll;
    private final FilterBar filterBar = new FilterBar("encounter-library");
    private final KitButton open = KitButton.primary("Open");
    private final JButton load = new JButton("Load"), save = new JButton("Save checked"), viewImported = new JButton("View imported encounter");
    /** The empty state's slot above the (then hidden) table, its key (title and body) and its failure before any read. */
    private final JPanel emptyHolder = new JPanel(new BorderLayout());
    private EmptyState empty;
    private String emptyKey, readFailure;
    /** The saved view state's status, shown only while it is a failure (its actions are in the ⋯ menu). */
    private final Banner stateBanner = new Banner("encounter-view-state");
    private final JTextField search = new JTextField(18);
    private final JComboBox<String> source = new JComboBox<>(new String[]{"All sources", "This app run", "Saved summary", "Full detail", "Imported"});
    private final JComboBox<String> link = new JComboBox<>(new String[]{"Any run link", "Linked", "Unlinked", "Legacy"});
    private final JComboBox<String> context = new JComboBox<>(new String[]{"Any local context", "Available", "Partial", "Unavailable"});
    private final SegmentedControl scope = new SegmentedControl("encounter-scope", "Last 30 days", "All sessions");
    private final JTextArea status = ContentStyle.wrappingText("Select a recording, then Open it; checks choose what Save checked writes."), details = ContentStyle.wrappingText("");
    private final JTextArea count = ContentStyle.wrappingText("");
    private final Banner issues = new Banner("encounter-issues");
    private final RecordingSummaryPanel summaryPanel;
    private final javax.swing.Timer revisionTimer, poll;
    private final Set<String> rememberedChecks = new LinkedHashSet<>();
    /** The shell's open actions ({@link #onOpenEncounter} and the others); until it sets them they switch {@link #dps}. */
    private Consumer<String> openEncounter, showEntry;
    private BiConsumer<VisitRef, String> openRecap;
    private Runnable openLive;
    private Predicate<String> confirm = message -> JOptionPane.showConfirmDialog(this, message, "Load full detail", JOptionPane.OK_CANCEL_OPTION,
        JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
    private boolean rebuilding, busy, restoringState, updating, loading, loadedOnce, closed;
    /** The selected row's reference ({@link #reference(RecordingItem, EncounterCatalog.Entry)}; "" = the live row). */
    private String selectedKey = "", importedId;
    private RosterViewState viewState;
    private Map<String, String> lastSaved;
    private long rememberedGeneration, generation, summaryGeneration;
    private Cancellation cancel = new Cancellation(), summaryCancel = new Cancellation();
    /** The last read: its stamp, the catalog revision, scope and store it read. */
    private Object loadedStamp;
    private long loadedRevision = -1;
    private RecordingsQuery.Scope loadedScope;
    private SessionStore loadedStore, sourceStore;
    private RecordingsSource recordings;
    /** The catalog entries of the worker's current read (the source's memory). */
    private volatile List<EncounterCatalog.Entry> memory = List.of();
    private int reads, checks;

    /** The app's library over its history ({@link AppHistory#store}, read on each request) and its view state. */
    public DungeonListGUI(DpsGUI dps, TomatoData data) {
        this(dps, data, ViewStateStore.application(), AppHistory::store, System::currentTimeMillis);
    }
    /** A library of this app run's memory and imports only (no saved history). */
    DungeonListGUI(DpsGUI dps, TomatoData data, ViewStateStore states) {
        this(dps, data, states, () -> null, System::currentTimeMillis);
    }
    /** {@code store} is read on each request (null: no saved history); {@code clock} dates the 30-day scope. */
    DungeonListGUI(DpsGUI dps, TomatoData data, ViewStateStore states, Supplier<SessionStore> store, LongSupplier clock) {
        this(dps, data, states, store, clock, DisplayModeModel.application());
    }
    /** As above; {@code mode} chooses Simple's or Analyst's columns (tests pass their own). */
    DungeonListGUI(DpsGUI dps, TomatoData data, ViewStateStore states, Supplier<SessionStore> store, LongSupplier clock, DisplayModeModel mode) {
        super(new BorderLayout(0, 8)); this.dps = dps; catalog = dps.encounters();
        this.stores = Objects.requireNonNull(store, "store"); this.clock = Objects.requireNonNull(clock, "clock");
        openEncounter = id -> { EncounterCatalog.Entry entry = unique(id); if (entry != null) dps.showEncounter(entry.id); };
        showEntry = dps::showEncounter; openLive = () -> dps.setIndex(-1);
        selectedKey = reference(catalog.find(dps.currentEncounterId()));
        rememberedGeneration = catalog.generation();
        worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "RealmShark recordings"); thread.setDaemon(true); return thread;
        });
        worker.allowCoreThreadTimeOut(true);
        this.mode = Objects.requireNonNull(mode, "mode");
        summaryPanel = new RecordingSummaryPanel(mode, () -> { details.setVisible(true); status.setText("Closed the summary."); });

        // The filter row: search, the drawer's facets, the scope, Clear and ⋯ Refresh.
        search.setName("encounter-search"); search.getAccessibleContext().setAccessibleName("Search recordings");
        search.putClientProperty("JTextField.placeholderText", "Search recordings");
        search.setToolTipText("Dungeon, recording ID, file name, entry ID or date");
        source.setName("encounter-source"); link.setName("encounter-link"); context.setName("encounter-context");
        source.getAccessibleContext().setAccessibleName("Recording source"); link.getAccessibleContext().setAccessibleName("Run link");
        context.getAccessibleContext().setAccessibleName("Local context availability");
        source.setToolTipText("This app run's captures, recordings with a saved summary, with saved full detail, or your imports");
        link.setToolTipText("Each recording's own link to its run, verified when it began; never a match by name or time");
        context.setToolTipText("Known only for recordings in memory; a saved summary does not keep it");
        JPanel drawer = ContentStyle.controls();
        for (Object[] facet : new Object[][]{{"Source", source}, {"Run link", link}, {"Local context", context}}) {
            JLabel label = new JLabel((String) facet[0]); label.setLabelFor((JComponent) facet[1]); drawer.add(label); drawer.add((JComponent) facet[1]);
        }
        scope.setToolTipText("Saved recordings entered in the last 30 days, or in every saved session; this app run's recordings and imports are always listed");
        filterBar.search(new WrapRow(search)).drawer(drawer).scope(scope);
        filterBar.overflow().add("Refresh", this::refreshEncounters).setName("encounter-library-refresh");
        open.setName("encounter-open");
        open.addActionListener(e -> open()); load.addActionListener(e -> loadButton()); save.addActionListener(e -> saveButton());
        viewImported.addActionListener(e -> {
            EncounterCatalog.Entry entry = catalog.find(importedId); if (entry == null) return;
            selectedKey = reference(entry); restoreSelection(); rememberViewState(); openEntry(entry);
            status.setText("Opened imported encounter. Display filters are unchanged.");
        });
        count.setName("encounter-summary"); count.setFocusable(false);
        issues.setVisible(false);
        stateBanner.setTone(Tokens.Tone.WARN); stateBanner.setVisible(false);
        // Open beside the count line: one row at desktop width, the count wrapping beside it when compact (P5b finding 6).
        JPanel actions = new JPanel(new BorderLayout(Tokens.M, 0)); actions.setOpaque(false);
        actions.add(open, BorderLayout.WEST); actions.add(count, BorderLayout.CENTER);
        JPanel header = KitLayouts.stack(Tokens.S, filterBar, actions, issues, stateBanner);

        ContentStyle.table(table); table.setName("saved-encounters"); table.getAccessibleContext().setAccessibleName("Recordings and export checks");
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); table.setRowSorter(sorter);
        table.addPropertyChangeListener("font", e -> ContentStyle.tableDensity(table, ContentStyle.Density.COMFORTABLE));
        table.getTableHeader().setReorderingAllowed(false);
        table.setAutoCreateColumnsFromModel(false);   // the columns below are the view's for good; Simple and Analyst re-arrange them
        columns = new TableColumn[model.getColumnCount()];
        for (int i = 0; i < columns.length; i++) columns[i] = table.getColumnModel().getColumn(i);
        // Widths are set, not only preferred, so laying the table out changes nothing (and writes no view state). Simple's six
        // columns sum to 980 px: inside the 1,012 px table of Runs & DPS at 1240×800 font 13, with room for a vertical scroll bar.
        int[] widths = {56, 100, 140, 154, 90, 100, 90, 170, 120, 294, 246};
        for (int i = 0; i < widths.length; i++) { columns[i].setPreferredWidth(widths[i]); columns[i].setWidth(widths[i]); }
        RecordingCell cell = new RecordingCell();
        for (int i = 1; i < widths.length; i++) columns[i].setCellRenderer(cell);
        TableCellRenderer checkRenderer = table.getDefaultRenderer(Boolean.class);
        ContentStyle.Cell blank = new ContentStyle.Cell();
        columns[0].setCellRenderer((t, value, selected, focus, row, column) -> {
            if (value != null) return checkRenderer.getTableCellRendererComponent(t, value, selected, focus, row, column);
            Component none = blank.getTableCellRendererComponent(t, "", selected, focus, row, column);
            blank.setToolTipText(model.rows.get(t.convertRowIndexToModel(row)).item == null ? null : "Only recordings in memory can be saved as .dps files");
            return none;
        });
        table.getTableHeader().setToolTipText("Recorded start is the first captured tick, not a guaranteed map-entry timestamp. Elapsed is the retained encounter duration, not the DPS hit window."
            + " Contributors and Damage use the meter's definitions. Run is the recording's own link; Saved is what saved history keeps of it.");
        sorter.setSortKeys(Collections.singletonList(new RowSorter.SortKey(3, SortOrder.DESCENDING)));
        table.getSelectionModel().addListSelectionListener(e -> {
            if (rebuilding || e.getValueIsAdjusting() || table.getSelectedRow() < 0) return;
            Row row = selected(); selectedKey = row.reference;
            if (summaryPanel.key() != null && !summaryPanel.key().equals(row.reference)) closeSummary();
            updateButtons(); showDetails();
            rememberViewState();
        });
        table.getInputMap().put(KeyStroke.getKeyStroke("SPACE"), "toggle-export");
        table.getActionMap().put("toggle-export", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                int view = table.getSelectedRow(); if (view < 0) return;
                int row = table.convertRowIndexToModel(view);
                if (model.isCellEditable(row, 0)) model.setValueAt(!Boolean.TRUE.equals(model.getValueAt(row, 0)), row, 0);
            }
        });
        for (InputMap keys : new InputMap[]{table.getInputMap(JComponent.WHEN_FOCUSED), table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)})
            keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open-recording");
        table.getActionMap().put("open-recording", new AbstractAction() { public void actionPerformed(ActionEvent event) { open(); } });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) return;
                int row = table.rowAtPoint(e.getPoint()), column = table.columnAtPoint(e.getPoint());
                if (row < 0 || column < 0 || table.convertColumnIndexToModel(column) == 0) return;   // a double-click on a check only toggles it
                table.setRowSelectionInterval(row, row); open();
            }
        });

        // Below the table: the details, then the status line with the file actions beside it (below it when they would squeeze it).
        JPanel files = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0)); files.setOpaque(false);
        files.add(load); files.add(save); files.add(viewImported);
        JPanel footer = new JPanel(new BorderLayout(0, 4)); footer.add(KitLayouts.stack(Tokens.S, summaryPanel, details), BorderLayout.NORTH);
        footer.add(new StatusRow(status, files), BorderLayout.SOUTH);
        details.setName("encounter-details"); status.setName("encounter-status");
        // The table, or an empty state in its place while there is nothing to list.
        tableScroll = ContentStyle.tableScroll(table, 3);
        emptyHolder.setOpaque(false); emptyHolder.setVisible(false); emptyHolder.setBorder(BorderFactory.createEmptyBorder(Tokens.XL, 0, Tokens.XL, 0));
        JPanel body = new JPanel(new BorderLayout()); body.setOpaque(false);
        body.add(emptyHolder, BorderLayout.NORTH); body.add(tableScroll, BorderLayout.CENTER);
        add(ContentStyle.page(header, body, footer), BorderLayout.CENTER);
        for (JComponent control : new JComponent[]{open, load, save, viewImported, search, source, link, context})
            control.addFocusListener(new FocusAdapter() { public void focusGained(FocusEvent e) { ContentStyle.reveal(control, new Rectangle(0, 0, control.getWidth(), control.getHeight())); } });
        table.addFocusListener(new FocusAdapter() { public void focusGained(FocusEvent e) { ContentStyle.reveal(table, table.getCellRect(Math.max(0, table.getSelectedRow()), Math.max(0, table.getSelectedColumn()), true)); } });
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filter(); }
        });
        source.addActionListener(e -> filter()); link.addActionListener(e -> filter()); context.addActionListener(e -> filter());
        scope.onChange(index -> { rememberViewState(); request(true); });
        revisionTimer = new javax.swing.Timer(REVISION_MILLIS, e -> { if (!loading && catalog.revision() != loadedRevision) request(false); });
        poll = new javax.swing.Timer(POLL_MILLIS, e -> check());
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
            if (isShowing()) { revisionTimer.start(); poll.start(); check(); } else { revisionTimer.stop(); poll.stop(); }
        });
        mode.bind(this, value -> applyColumns(value == DisplayModeModel.Mode.ANALYST));
        if (states != null) bindViewState(states);
        filter(); updateButtons();
    }

    // ---- callbacks (the shell wires them; the defaults switch this library's meter) ----

    /** A recording in memory with a unique recording ID was opened: its ID (the shell routes it to ENCOUNTER). EDT. */
    public void onOpenEncounter(Consumer<String> open) { openEncounter = Objects.requireNonNull(open, "open"); }
    /** A recording in memory without a recording ID, or with one another entry shares, was opened: its library entry ID. EDT. */
    public void onShowEntry(Consumer<String> open) { showEntry = Objects.requireNonNull(open, "open"); }
    /** A summary-only recording linked to its run was opened: the run and the recording's ID; null shows its summary here. EDT. */
    public void onOpenRecap(BiConsumer<VisitRef, String> open) { openRecap = open; updateButtons(); }
    /** The live row was opened (the meter follows the live fight). EDT. */
    public void onOpenLive(Runnable open) { openLive = Objects.requireNonNull(open, "open"); }
    /** Asks before saved full detail is read (its message names the size); true reads it. Tests replace the dialog. */
    void confirmLoad(Predicate<String> ask) { confirm = Objects.requireNonNull(ask, "ask"); }

    // ---- reads ----

    /** Reads now (imports, ⋯ Refresh, and a library that is never shown). EDT. */
    void refreshEncounters() { request(true); }
    /** Completed reads (tests: S8). */
    int reads() { return reads; }
    /** Completed requests, reads or unchanged stamps (tests). */
    int checks() { return checks; }
    /** A request is in flight. */
    boolean loading() { return loading; }
    /** The shown empty state, or null (tests). */
    EmptyState emptyState() { return emptyHolder.isVisible() ? empty : null; }

    /** Shown, or the poll ticked: reads only when nothing was read yet or the store's stamp changed. */
    private void check() { if (!closed && !loading) request(false); }

    /** Starts a read on the worker; a newer request makes older results inert. {@code always} skips the stamp comparison. EDT. */
    private void request(boolean always) {
        if (closed) return;
        long revision = catalog.revision();
        SessionStore store = stores.get();
        RecordingsQuery.Scope wanted = scopeValue();
        Object known = !always && loadedOnce && revision == loadedRevision && wanted == loadedScope && store == loadedStore ? loadedStamp : null;
        long ticket = ++generation;
        cancel.cancel();
        Cancellation token = cancel = new Cancellation();
        RecordingsSource reader = source(store);
        int days = CombatSettings.current().fullDetailDays();
        loading = true;
        updateCount(); showEmpty();
        try {
            worker.execute(() -> {
                Loaded done;
                try { done = read(reader, store, wanted, revision, known, days, token); }
                catch (CancellationException cancelled) { return; }   // a newer request replaced this one
                catch (Exception | Error failed) { done = new Loaded(null, revision, wanted, store, null, null, false, failed); }
                Loaded result = done;
                SwingUtilities.invokeLater(() -> apply(ticket, result));
            });
        } catch (RejectedExecutionException shutDown) { loading = false; showEmpty(); }
    }

    /** On the worker: the stamp, then (unless it is {@code known}) the recordings and their rows. */
    private Loaded read(RecordingsSource reader, SessionStore store, RecordingsQuery.Scope wanted, long revision, Object known, int days,
                        Cancellation token) throws IOException {
        Object stamp = stamp(store, days, token);
        if (known != null && known.equals(stamp)) return new Loaded(stamp, revision, wanted, store, null, null, true, null);
        List<EncounterCatalog.Entry> entries = catalog.entries();
        memory = entries;
        RecordingsSource.Result result = reader.read(new RecordingsQuery("", wanted, Set.of(), null), token);
        return new Loaded(stamp, revision, wanted, store, result, rows(result, entries, days), false, null);
    }

    /** EDT: applies the newest request's result. */
    private void apply(long ticket, Loaded done) {
        if (closed || ticket != generation) return;
        loading = false; checks++;
        if (done.failure() != null) {
            loadedRevision = done.revision(); loadedScope = done.scope(); loadedStore = done.store(); loadedStamp = null;
            readFailure = safe(done.failure());
            status.setText("Could not read recordings: " + readFailure); updateCount(); showEmpty(); return;
        }
        readFailure = null;
        if (done.unchanged()) { updateCount(); showEmpty(); return; }
        reads++;
        loadedOnce = true; loadedStamp = done.stamp(); loadedRevision = done.revision(); loadedScope = done.scope(); loadedStore = done.store();
        applyRememberedReferences();
        rebuilding = true;
        try { model.rows = done.rows(); model.fireTableDataChanged(); restoreSelection(); }
        finally { rebuilding = false; }
        issues(done.result()); updateButtons(); showDetails(); showEmpty();
    }

    /** One {@link RecordingsSource} per store (its cache of closed sessions lives as long as the store). EDT. */
    private RecordingsSource source(SessionStore store) {
        if (recordings == null || sourceStore != store) { sourceStore = store; recordings = new RecordingsSource(() -> store, () -> memory, clock); }
        return recordings;
    }

    /**
     * What a read depends on besides the catalog: the retention days in the pruned label, every session folder's name and, of its
     * metadata, records and full-detail files, each one's name, size and modification time (a folder's time changes when a file
     * is saved into it or removed), with the current session's folders' entry counts. Off the EDT.
     */
    static List<String> stamp(SessionStore store, int days, Cancellation cancel) throws IOException {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Stamp saved history off the EDT");
        List<String> stamp = new ArrayList<>();
        stamp.add("days:" + days);
        if (store == null) return stamp;
        Path root = store.directory();
        if (!Files.isDirectory(root)) return stamp;
        String current = store.currentId();
        try (DirectoryStream<Path> sessions = Files.newDirectoryStream(root)) {
            for (Path session : sessions) {
                cancel.check();
                if (!Files.isDirectory(session, LinkOption.NOFOLLOW_LINKS)) continue;
                String id = session.getFileName().toString();
                stamp.add(id);
                try (DirectoryStream<Path> files = Files.newDirectoryStream(session)) {
                    for (Path file : files) {
                        String name = file.getFileName().toString();
                        if (!STAMPED.contains(name)) continue;
                        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        stamp.add(id + "/" + name + ":" + attributes.size() + ":" + attributes.lastModifiedTime().toMillis()
                            + (id.equals(current) && attributes.isDirectory() ? ":" + entries(file) : ""));
                    }
                } catch (NoSuchFileException | NotDirectoryException gone) { stamp.add(id + ":gone"); }
            }
        }
        Collections.sort(stamp);
        return stamp;
    }

    private static int entries(Path folder) throws IOException {
        int count = 0;
        try (DirectoryStream<Path> listing = Files.newDirectoryStream(folder)) { for (Path ignored : listing) count++; }
        return count;
    }

    // ---- rows ----

    /** The rows of one read, off the EDT: the live row, then one per recording with its copy in memory and its labels. */
    private static List<Row> rows(RecordingsSource.Result result, List<EncounterCatalog.Entry> entries, int days) {
        Map<String, EncounterCatalog.Entry> byId = new HashMap<>();
        for (EncounterCatalog.Entry entry : entries) byId.put(entry.id, entry);
        Map<String, RecordingItem> byKey = new HashMap<>();
        for (RecordingItem item : result.items()) byKey.put(item.key(), item);
        List<Row> rows = new ArrayList<>(result.items().size() + 1);
        rows.add(Row.LIVE);
        for (RecordingItem item : result.items()) {
            EncounterCatalog.Entry entry = item.memoryEntryId() == null ? null : byId.get(item.memoryEntryId());
            String dungeon = dungeon(item), source = source(item, byId);
            RecordingItem same = item.sameRecordingAs() == null ? null : byKey.get(item.sameRecordingAs());
            String sameAs = same == null ? null : source(same, byId) + " · " + dungeon(same);
            rows.add(new Row(item, entry, entry == null ? null : entry.summary(), reference(item, entry), dungeon, source, run(item), runTip(item),
                saved(item, sameAs, days), savedTip(item, sameAs, days), EncounterQuery.haystack(item, entry == null ? null : entry.id, dungeon, source)));
        }
        return rows;
    }

    static String dungeon(RecordingItem item) {
        if (item.mapName() != null && !item.mapName().isBlank()) return item.mapName();
        return item.map() != null && !item.map().isBlank() ? item.map() : "Unknown encounter";
    }

    /** The Source file column: an entry's source ("Captured", "Saved full detail", an import's file name), else saved history. */
    private static String source(RecordingItem item, Map<String, EncounterCatalog.Entry> byId) {
        EncounterCatalog.Entry entry = item.memoryEntryId() == null ? null : byId.get(item.memoryEntryId());
        return entry != null ? entry.source() : item.fileName() != null ? item.fileName() : SAVED_HISTORY;
    }

    private static boolean imported(RecordingItem item) { return item.kind() == RecordingItem.Kind.IMPORTED || item.kind() == RecordingItem.Kind.LEGACY_IMPORT; }

    /** The Run column: the recording's own link, with its run's dungeon and entry time when linked (they are the run's). */
    static String run(RecordingItem item) {
        switch (item.link()) {
            case LINKED: return (imported(item) ? "Linked (imported; may not resolve here)" : "Linked") + " · " + dungeon(item) + " · "
                + (item.enteredAt() == null ? "entry time unknown" : DisplayFormat.formatTimestamp(item.enteredAt()));
            case UNLINKED: return "Unlinked";
            default: return "Legacy · unlinked";
        }
    }

    private static String runTip(RecordingItem item) {
        switch (item.link()) {
            case LINKED: return "Linked to visit " + item.visit().visitId + " (session " + item.visit().sessionId + "), verified when this encounter began."
                + (imported(item) ? " Imported file: its session may be absent on this machine; the recap then says so instead of substituting another run." : "");
            case UNLINKED: return "Unlinked: the visit could not be verified when this encounter began (collection paused, capture boundary, failed or partial map data,"
                + " or no saved history). No visit is matched by name or time.";
            default: return "Legacy recording without encounter identity; no visit is matched by dungeon name or time.";
        }
    }

    /** The Saved column: what saved history keeps of the recording. */
    static String saved(RecordingItem item, String sameAs, int days) {
        if (imported(item)) return sameAs == null ? "Imported file" : "Imported file · same recording as " + sameAs;
        if (!item.summarySaved()) return NO_SUMMARY;
        switch (item.fullDetail()) {
            case PRESENT: return item.kind() == RecordingItem.Kind.SAVED && item.inMemory() ? "Full detail · loaded" : "Full detail · " + size(item.fullDetailBytes());
            case PRUNED: return "Full detail pruned (kept " + days + " days)";
            default: return "Summary saved";
        }
    }

    private static String savedTip(RecordingItem item, String sameAs, int days) {
        if (imported(item)) return "Imports are not saved to history; the file stays in memory for this app run."
            + (sameAs == null ? "" : " Another row holds the same recording ID (a copy or a variant of it).");
        if (!item.summarySaved()) return NO_SUMMARY_TIP;
        switch (item.fullDetail()) {
            case PRESENT: return "Its summary and every hit are saved. Open loads the full detail into memory; at most " + EncounterCatalog.SAVED_KEPT
                + " loaded recordings are kept, never unloading the one the Live meter shows.";
            case PRUNED: return "Its summary is saved; its full detail was deleted by retention (Settings › General keeps full detail " + days
                + " days), so only the summary opens.";
            default: return "Only its summary is saved: hit detail was not kept (Settings › General › Keep full combat detail).";
        }
    }

    /** "12.3 MB", "420 KB"; "size unknown" without one. */
    static String size(Long bytes) {
        if (bytes == null) return "size unknown";
        return bytes >= 1_000_000 ? DisplayFormat.formatNumber(bytes / 1e6, 1) + " MB" : DisplayFormat.formatNumber(Math.max(1, Math.round(bytes / 1e3)), 0) + " KB";
    }

    /** A row's reference: an import's exact bytes, else its recording ID, else its entry ("" for none: the live row). */
    static String reference(RecordingItem item, EncounterCatalog.Entry entry) {
        if (entry != null) return reference(entry);
        return item.recordingId() != null ? "native:" + item.recordingId() : item.key();
    }
    static String reference(EncounterCatalog.Entry entry) {
        if (entry == null) return "";
        return entry.imported() || entry.data.getRecordingId() == null ? EncounterCatalog.reference(entry) : "native:" + entry.data.getRecordingId();
    }

    // ---- columns ----

    /**
     * Shows the columns in {@link #VIEW_ORDER}, without {@link #SIMPLE_HIDDEN} in Simple; the model, its indices, the widths and the
     * sort are untouched, so nothing saved changes. Runs when the mode is bound and on every mode change. EDT.
     */
    private void applyColumns(boolean analyst) {
        List<TableColumn> wanted = new ArrayList<>();
        for (int index : VIEW_ORDER) if (analyst || !SIMPLE_HIDDEN.contains(index)) wanted.add(columns[index]);
        TableColumnModel shown = table.getColumnModel();
        List<TableColumn> current = Collections.list(shown.getColumns());
        if (current.equals(wanted)) return;
        for (TableColumn column : current) shown.removeColumn(column);
        for (TableColumn column : wanted) shown.addColumn(column);
    }

    // ---- filters ----

    private EncounterQuery query() {
        return new EncounterQuery(search.getText(), EncounterQuery.Source.values()[source.getSelectedIndex()],
            context.getSelectedIndex() == 0 ? null : (String) context.getSelectedItem(),
            link.getSelectedIndex() == 0 ? null : RecordingItem.Link.values()[link.getSelectedIndex() - 1]);
    }
    private RecordingsQuery.Scope scopeValue() { return scope.selected() == 1 ? RecordingsQuery.Scope.ALL : RecordingsQuery.Scope.LAST_30_DAYS; }

    private void filter() {
        if (updating) return;
        EncounterQuery query = query();
        rebuilding = true;
        try {
            sorter.setRowFilter(new RowFilter<EncounterModel, Integer>() {
                public boolean include(Entry<? extends EncounterModel, ? extends Integer> value) {
                    Row row = model.rows.get(value.getIdentifier()); return row.item == null || query.matches(row.item, row.localContext(), row.haystack);
                }
            });
            restoreSelection();
        } finally { rebuilding = false; }
        chips(); updateButtons(); showDetails(); showEmpty();
        rememberViewState();
    }

    /** Chips for the search and each facet; Clear resets them all (the scope is not a filter). */
    private void chips() {
        List<FilterBar.ActiveFilter> active = new ArrayList<>();
        if (!search.getText().trim().isEmpty()) active.add(new FilterBar.ActiveFilter("Search active", () -> search.setText("")));
        if (source.getSelectedIndex() > 0) active.add(new FilterBar.ActiveFilter("Source: " + source.getSelectedItem(), () -> source.setSelectedIndex(0)));
        if (link.getSelectedIndex() > 0) active.add(new FilterBar.ActiveFilter("Run link: " + link.getSelectedItem(), () -> link.setSelectedIndex(0)));
        if (context.getSelectedIndex() > 0) active.add(new FilterBar.ActiveFilter("Local context: " + context.getSelectedItem(), () -> context.setSelectedIndex(0)));
        FilterChips.update(filterBar, active, this::clearFilters, false);
    }

    private void clearFilters() {
        updating = true;
        try { search.setText(""); source.setSelectedIndex(0); link.setSelectedIndex(0); context.setSelectedIndex(0); }
        finally { updating = false; }
        filter();
    }

    private Row selected() { int row = table.getSelectedRow(); return row < 0 ? null : model.rows.get(table.convertRowIndexToModel(row)); }
    /**
     * Selects the chosen row ({@link #selectedKey}) when it is listed, else the live row (model row 0, never filtered out) without
     * forgetting the choice: the chosen recording is selected again once a filter or read lists it (P5b finding 4).
     */
    private void restoreSelection() {
        table.clearSelection();
        int view = -1;
        for (int i = 0; i < model.rows.size(); i++) if (model.rows.get(i).reference.equals(selectedKey)) { view = table.convertRowIndexToView(i); break; }
        if (view < 0) view = table.convertRowIndexToView(0);
        if (view >= 0) table.setRowSelectionInterval(view, view);
    }

    private void showDetails() {
        Row row = selected();
        details.setText(row == null ? "Selected encounter is not loaded or is outside these display filters."
            : row.item == null ? "Live capture: Open shows the live meter. It is not an exportable saved encounter."
                + (selectedKey.isEmpty() ? "" : "\nThe recording you chose is not listed with these filters or this scope; it is selected again once it is.")
            : details(row));
    }

    /**
     * One empty state in the table's place, or none (the table): reading for the first time, the first read failed, nothing
     * recorded, saved or imported, or no recording matching the filters. Its title names the situation, its body says why or what
     * to do, and its action is the next step; Open live meter stays in the header meanwhile (the live row stays selected). EDT.
     */
    private void showEmpty() {
        String title = null, body = null;
        KitButton action = null;
        int recordings = model.rows.size() - 1, listed = table.getRowCount() - 1;
        if (!loadedOnce && loading) {
            title = "Reading recordings";
            body = "This app run's recordings, your imports and saved history are being read.";
        } else if (!loadedOnce && readFailure != null) {
            title = "Recordings could not be read";
            body = (readFailure.endsWith(".") ? readFailure.substring(0, readFailure.length() - 1) : readFailure) + "; try again.";
            action = emptyAction("Try again", this::refreshEncounters);
        } else if (loadedOnce && recordings == 0) {
            title = "No recordings yet";
            String opens = "A recording appears here when a fight closes during capture or Load opens a .dps file";
            body = loadedStore == null ? opens + ". Saved history is not open in this app run, so saved recordings are not listed."
                : loadedScope == RecordingsQuery.Scope.ALL ? opens + "; no saved session holds one."
                : opens + "; older saved recordings are under All sessions.";
        } else if (loadedOnce && listed == 0) {
            title = "No recordings match";
            body = "Change the search or clear the filters to see every recording of this scope.";
            action = emptyAction("Clear filters", this::clearFilters);
        }
        boolean shown = title != null;
        if (shown) {
            String key = title + "\n" + body;
            if (!key.equals(emptyKey)) {
                emptyHolder.removeAll();
                empty = new EmptyState(title, body, action);
                empty.setName("encounter-empty");
                emptyHolder.add(empty, BorderLayout.CENTER);
                emptyKey = key;
            }
        } else emptyKey = null;
        if (emptyHolder.isVisible() == shown && tableScroll.isVisible() == !shown) return;
        emptyHolder.setVisible(shown); tableScroll.setVisible(!shown);
        revalidate(); repaint();
    }

    private static KitButton emptyAction(String text, Runnable action) {
        KitButton button = KitButton.secondary(text);
        button.setName("encounter-empty-action");
        button.addActionListener(e -> action.run());
        return button;
    }

    private String details(Row row) {
        RecordingItem item = row.item;
        StringBuilder text = new StringBuilder(row.dungeon).append(" · ").append(kind(item));
        if (row.entry != null) text.append(" · Entry ").append(row.entry.id);
        text.append("\nRecording ID: ").append(Objects.toString(item.recordingId(), "Not recorded (legacy)"));
        text.append("\nRun: ").append(row.run).append(". ").append(row.runTip);
        text.append("\nSaved: ").append(row.saved).append(". ").append(row.savedTip);
        text.append("\nLocal context: ").append(row.summary == null ? "known once the full recording is in memory"
            : row.summary.localContext + " · " + row.summary.contextDescription);
        if (row.entry != null && row.entry.imported()) text.append("\nImported file: ").append(row.entry.origin.fileName).append(" · SHA-256 ").append(row.entry.origin.fingerprint);
        else if (row.entry != null) text.append(row.entry.kind() == EncounterCatalog.Kind.SAVED ? "\nSaved full detail loaded from saved history" : "\nCaptured in this application");
        return text.toString();
    }

    private static String kind(RecordingItem item) {
        switch (item.kind()) {
            case THIS_RUN: return "This app run";
            case SAVED: return "Saved";
            case IMPORTED: return "Imported";
            default: return "Legacy import";
        }
    }

    // ---- open ----

    private enum Action { LIVE, MEMORY, LOAD, RECAP, SUMMARY, NONE }

    /** What Open does for {@code row} now. */
    private Action action(Row row) {
        if (row.item == null) return Action.LIVE;
        if (copy(row) != null) return Action.MEMORY;
        RecordingItem item = row.item;
        if (!imported(item) && item.fullDetail() == RecordingItem.FullDetail.PRESENT && item.session() != null) return Action.LOAD;
        if (!item.summarySaved()) return Action.NONE;   // not in memory any more (Clear) and never saved
        return item.link() == RecordingItem.Link.LINKED && item.visit() != null && openRecap != null ? Action.RECAP : Action.SUMMARY;
    }

    /** The row's copy in memory: its entry while the catalog holds it, else (not for imports) any copy of its recording loaded since. */
    private EncounterCatalog.Entry copy(Row row) {
        if (row.entry != null && catalog.find(row.entry.id) != null) return row.entry;
        return imported(row.item) || row.item.recordingId() == null ? null : inMemory(row.item.recordingId());
    }

    /** The captured or loaded copy of {@code recordingId} ({@link EncounterCatalog#addSaved}'s rule: imports do not count). */
    private EncounterCatalog.Entry inMemory(String recordingId) {
        for (EncounterCatalog.Entry entry : catalog.entries()) if (!entry.imported() && recordingId.equals(entry.data.getRecordingId())) return entry;
        return null;
    }

    /** The only entry with {@code recordingId}, or null when none or several claim it. */
    private EncounterCatalog.Entry unique(String recordingId) {
        EncounterCatalog.Entry match = null;
        if (recordingId != null) for (EncounterCatalog.Entry entry : catalog.entries()) if (recordingId.equals(entry.data.getRecordingId())) {
            if (match != null) return null;
            match = entry;
        }
        return match;
    }

    private static String openLabel(Row row, Action action) {
        switch (action) {
            case LIVE: return "Open live meter";
            case MEMORY: return "Open in Live meter";
            case LOAD: return "Load full detail (" + size(row.item.fullDetailBytes()) + ")…";
            case RECAP: return "Open run recap";
            case SUMMARY: return "Show summary";
            default: return "Open";
        }
    }

    /** Opens the selected row (Open, Enter, a double-click). EDT. */
    void open() {
        Row row = selected();
        if (row == null || busy || closed) return;
        switch (action(row)) {
            case LIVE: openLive.run(); status.setText("Opened the live meter."); break;
            case MEMORY: openEntry(copy(row)); status.setText("Opened " + row.dungeon + " in the Live meter."); break;
            case LOAD: loadFullDetail(row); break;
            case RECAP: openRecap.accept(row.item.visit(), row.item.recordingId()); status.setText("Opened the run recap of " + row.dungeon + "."); break;
            case SUMMARY: showSummary(row); break;
            default: status.setText("This recording is no longer in memory and was never saved, so it cannot be opened."); break;
        }
    }

    /** Opens one entry in memory: by recording ID when exactly one entry claims it (routes need that), else by entry ID. */
    private void openEntry(EncounterCatalog.Entry entry) {
        String id = entry.data.getRecordingId();
        if (unique(id) != null) openEncounter.accept(id); else showEntry.accept(entry.id);
    }

    /** Confirms, reads on the reader thread, admits (never evicting the shown recording) and opens saved full detail. EDT. */
    private void loadFullDetail(Row row) {
        RecordingItem item = row.item;
        SessionStore store = stores.get();
        if (store == null) { status.setText("Saved history is not open in this app run, so this full detail cannot be loaded."); return; }
        String size = size(item.fullDetailBytes());
        if (!confirm.test(row.dungeon + " · " + size + " · loads into memory. At most " + EncounterCatalog.SAVED_KEPT
            + " loaded recordings are kept; the one the Live meter shows is never unloaded.")) return;
        EncounterCatalog.Entry already = inMemory(item.recordingId());   // loaded or captured meanwhile
        if (already != null) { openEntry(already); return; }
        Path file = CombatAutosave.fullDetailFile(store.directory().resolve(item.session()), item.recordingId());
        long expected = catalog.generation();
        setBusy(true, "Loading the full detail of " + row.dungeon + " · " + size + "…");
        new SwingWorker<EncounterImport, Void>() {
            protected EncounterImport doInBackground() throws IOException { return EncounterImport.readSaved(file); }
            protected void done() {
                try {
                    EncounterImport loaded = get();
                    EncounterCatalog.Entry entry = catalog.addSaved(loaded, shown -> shown.id.equals(dps.currentEncounterId()), expected);
                    if (entry == null) { setBusy(false, "The library was cleared while loading; open the recording again."); return; }
                    setBusy(false, "Loaded the full detail of " + row.dungeon + ".");
                    if (!closed) { refreshEncounters(); DpsGUI.updateLabel(); openEntry(entry); }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); setBusy(false, "Loading full detail was interrupted."); }
                catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    setBusy(false, cause instanceof NoSuchFileException || cause instanceof FileNotFoundException ? PRUNED_SINCE : "Full detail could not be read: " + safe(cause));
                }
            }
        }.execute();
    }

    /** Shows a summary-only recording's saved summary under the table, read off the EDT. */
    private void showSummary(Row row) {
        RecordingItem item = row.item;
        Long time = item.enteredAt() != null ? item.enteredAt() : item.startedAt();
        String note = item.fullDetail() == RecordingItem.FullDetail.PRUNED ? RecordingSummaryPanel.pruned(CombatSettings.current().fullDetailDays()) : RecordingSummaryPanel.NOT_KEPT;
        summaryPanel.showLoading(row.reference, row.dungeon + " · " + (time == null ? "time unknown" : DisplayFormat.formatTimestamp(time)), note);
        details.setVisible(false);
        status.setText("Showing the saved summary of " + row.dungeon + " below.");
        long ticket = ++summaryGeneration;
        summaryCancel.cancel();
        Cancellation token = summaryCancel = new Cancellation();
        SessionStore store = stores.get();
        String key = row.reference;
        try {
            worker.execute(() -> {
                RunRecapModel.Damage section = null; String failure = null;
                try { section = RecordingSummaryPanel.read(store, item, token); if (section == null) failure = RecordingSummaryPanel.GONE; }
                catch (CancellationException cancelled) { return; }
                catch (Exception | Error unreadable) { failure = "The saved summary could not be read: " + safe(unreadable); }
                RunRecapModel.Damage shown = section; String why = failure;
                SwingUtilities.invokeLater(() -> {
                    if (closed || ticket != summaryGeneration || !key.equals(summaryPanel.key())) return;
                    if (why != null) summaryPanel.showFailure(why); else summaryPanel.show(shown);
                    SwingUtilities.invokeLater(() -> ContentStyle.reveal(summaryPanel, new Rectangle(0, 0, summaryPanel.getWidth(), Math.min(summaryPanel.getHeight(), 200))));
                });
            });
        } catch (RejectedExecutionException shutDown) { summaryPanel.showFailure(RecordingSummaryPanel.GONE); }
    }

    private void closeSummary() {
        summaryGeneration++; summaryCancel.cancel(); summaryPanel.hideSummary(); details.setVisible(true);
    }

    /** A failure in words without a path: its message unless it names a file path, else its kind. */
    static String safe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && (cause.getMessage() == null || cause instanceof ExecutionException)) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() || message.contains("/") || message.contains("\\") ? cause.getClass().getSimpleName() : message;
    }

    // ---- files ----

    private void loadButton() {
        JFileChooser chooser = new JFileChooser(new File(".")); chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("DPS encounters (*.dps)", "dps"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) importFile(chooser.getSelectedFile());
    }
    SwingWorker<DpsData, Void> importFile(File file) {
        requireIdleEdt(); setBusy(true, "Loading " + file.getName() + "…"); long generation = catalog.generation();
        SwingWorker<DpsData, Void> worker = new SwingWorker<DpsData, Void>() {
            private EncounterImport imported;
            protected DpsData doInBackground() throws IOException { imported = EncounterImport.read(file.toPath()); return imported.data; }
            protected void done() {
                try {
                    get();
                    EncounterCatalog.Admission admission = catalog.add(imported, generation);
                    if (admission == null) { setBusy(false, "Library cleared during import; load again to add this encounter."); return; }
                    importedId = admission.entry.id;
                    refreshEncounters(); DpsGUI.updateLabel();
                    setBusy(false, admission.duplicate ? "Already loaded identical file bytes; View imported encounter opens the existing entry."
                        : admission.sameRecordingId ? "Loaded a separate file variant with the same recording ID; existing entries preserved." : "Loaded " + file.getName());
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); failed(e); }
                catch (ExecutionException e) { failed(e.getCause()); }
            }
        };
        worker.execute(); return worker;
    }
    private void saveButton() {
        JFileChooser chooser = new JFileChooser(new File(".")); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        CheckBoxAccessory accessory = new CheckBoxAccessory(); chooser.setAccessory(accessory);
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) exportFiles(chooser.getSelectedFile(), accessory.isBoxSelected());
    }
    SwingWorker<Integer, Void> exportFiles(File folder, boolean debug) {
        requireIdleEdt(); List<DpsData> exports = new ArrayList<>(); List<String> names = new ArrayList<>();
        for (EncounterCatalog.Entry entry : catalog.checkedEntries()) { exports.add(entry.data.getSaveFile(debug)); names.add(name(entry.data)); }
        setBusy(true, "Saving " + exports.size() + " checked encounters (including hidden checks)…");
        SwingWorker<Integer, Void> worker = new SwingWorker<Integer, Void>() {
            protected Integer doInBackground() throws IOException {
                SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd-HH.mm.ss");
                for (int i = 0; i < exports.size(); i++) {
                    DpsData saved = exports.get(i);
                    String base = names.get(i).replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_") + " " + (saved.dungeonStartTime > 0 ? date.format(new Date(saved.dungeonStartTime)) : "unknown-start");
                    DpsExport.write(folder.toPath(), base, saved);
                }
                return exports.size();
            }
            protected void done() {
                try { setBusy(false, "Saved " + get() + " encounters."); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); failed(e); }
                catch (ExecutionException e) { failed(e.getCause()); }
            }
        };
        worker.execute(); return worker;
    }
    private static String name(DpsData data) { return data.map == null ? "Unknown encounter" : Objects.toString(data.map.name, "Unknown encounter"); }
    private void requireIdleEdt() { if (!SwingUtilities.isEventDispatchThread() || busy) throw new IllegalStateException("Start one file operation at a time on the EDT"); }
    private void setBusy(boolean value, String message) { busy = value; status.setText(message); updateButtons(); }
    private void failed(Throwable error) { setBusy(false, "File operation failed: " + safe(error)); }

    private void updateButtons() {
        load.setEnabled(!busy); save.setEnabled(!busy && !catalog.checkedEntries().isEmpty());
        boolean imported = catalog.find(importedId) != null;   // shown only once Load imported a file in this app run
        viewImported.setEnabled(imported);
        if (viewImported.isVisible() != imported) { viewImported.setVisible(imported); viewImported.revalidate(); }
        Row row = selected(); Action action = row == null ? Action.NONE : action(row);
        open.setText(row == null ? "Open" : openLabel(row, action));
        open.setEnabled(!busy && action != Action.NONE);
        open.setToolTipText(row == null ? "Select a recording to open it" : action == Action.NONE ? "Not in memory and never saved" : null);
        updateCount();
    }

    private void updateCount() {
        List<EncounterCatalog.Entry> checked = catalog.checkedEntries();
        int visible = 0, hiddenChecks = checked.size();
        for (int i = 0; i < table.getRowCount(); i++) {
            Row row = model.rows.get(table.convertRowIndexToModel(i));
            if (row.item == null) continue;
            visible++;
            if (row.entry != null && catalog.checked(row.entry.id)) hiddenChecks--;
        }
        String shown = !loadedOnce ? loading ? "Reading recordings…" : "Recordings are read when this tab first shows"
            : visible + " of " + (model.rows.size() - 1) + " recordings shown";
        count.setText(shown + " · " + (scopeValue() == RecordingsQuery.Scope.ALL ? "all sessions" : "last 30 days") + " · " + checked.size()
            + " checked for export" + (hiddenChecks > 0 ? " · " + hiddenChecks + " checked outside filters" : "") + (loading && loadedOnce ? " · reading…" : ""));
    }

    /** Why the list may be partial; saved history being unavailable is information, anything else a warning. */
    private void issues(RecordingsSource.Result result) {
        List<String> list = result.issues();
        if (list.isEmpty()) { issues.setVisible(false); return; }
        int skipped = result.sessionsSkipped();
        String text = String.join(" · ", list.subList(0, Math.min(3, list.size()))) + (list.size() > 3 ? " · …" : "");
        issues.setText(skipped > 0 ? "Records of " + skipped + (skipped == 1 ? " session" : " sessions") + " could not be read; the list may be partial. " + text : text);
        issues.setTone(list.size() == 1 && RecordingsSource.NO_HISTORY.equals(list.get(0)) ? Tokens.Tone.INFO : Tokens.Tone.WARN);
        issues.setToolTipText(String.join("\n", list));
        issues.setVisible(true);
    }

    // ---- view state ----

    public void bindViewState(ViewStateStore states) {
        if (viewState != null) return;
        viewState = new RosterViewState(states, "encounter-library-live", () -> { Map<String, String> values = captureViewState(); lastSaved = values; return values; }, this::prepareViewState);
        // Saved view state lives in the ⋯ menu (spec §3.2, as on Characters); the tab shows its status only when it is a failure.
        filterBar.overflow().add("Save view state", () -> viewState.save()).setName("encounter-library-save-view");
        filterBar.overflow().add("Reset saved view state", viewState::resetSaved).setName("encounter-library-reset-view");
        viewState.onStatus(this::viewStateChanged); viewStateChanged();
        RosterViewState.listenTable(table, this::rememberViewState);
        lastSaved = captureViewState();   // what it loaded: only a change is written
    }
    /** The saved view's status as a warning line while it is a failure (a save failed, or the saved state could not be read). */
    private void viewStateChanged() {
        boolean problem = viewState.statusProblem();
        stateBanner.setText(problem ? viewState.statusText() : "");
        if (stateBanner.isVisible() != problem) { stateBanner.setVisible(problem); revalidate(); }
    }
    public java.util.concurrent.CompletionStage<util.PreferencesStore.SaveResult> saveViewState() {
        if (viewState == null) throw new IllegalStateException("View state is not bound"); return viewState.save();
    }
    /** Closing writes the view state only when it differs from what was loaded or last saved. */
    @Override public void removeNotify() { if (viewState != null && !captureViewState().equals(lastSaved)) viewState.save(); super.removeNotify(); }
    private void rememberViewState() {
        if (viewState != null && !rebuilding && !restoringState && lastSaved != null && !captureViewState().equals(lastSaved)) viewState.changed();
    }
    private Map<String, String> captureViewState() {
        applyRememberedReferences();
        Map<String, String> values = new LinkedHashMap<>(); values.put("text", search.getText());
        values.put("source", EncounterQuery.Source.values()[source.getSelectedIndex()].name());
        values.put("context", context.getSelectedIndex() == 0 ? "ANY" : context.getSelectedItem().toString().toUpperCase(Locale.ROOT));
        values.put("link", LINKS[link.getSelectedIndex()]);
        values.put("scope", scopeValue().name());
        values.put("selected", valid(selectedKey) ? selectedKey : "");
        Set<String> checks = new LinkedHashSet<>(rememberedChecks); for (EncounterCatalog.Entry entry : catalog.checkedEntries()) checks.add(EncounterCatalog.reference(entry));
        values.put("checked", String.join("\n", checks)); values.put("catalog", catalog.lifetimeId()); values.put("generation", Long.toString(catalog.generation()));
        RosterViewState.captureTable(values, table);
        // Every column's width, shown or hidden, so switching modes changes nothing that is saved; widths of this layout only.
        for (int i = 0; i < columns.length; i++) values.put("width." + i, Integer.toString(Math.min(10000, Math.max(16, columns[i].getWidth()))));
        values.put("columns", LAYOUT);
        return values;
    }
    private Runnable prepareViewState(Map<String, String> values) {
        int selectedSource = RosterViewState.option(values, "source", source.getSelectedIndex(), "ANY", "CAPTURED", "SAVED", "FULL_DETAIL", "IMPORTED");
        int selectedContext = RosterViewState.option(values, "context", context.getSelectedIndex(), "ANY", "AVAILABLE", "PARTIAL", "UNAVAILABLE");
        int selectedLink = RosterViewState.option(values, "link", link.getSelectedIndex(), LINKS);
        int selectedScope = RosterViewState.option(values, "scope", scope.selected(), "LAST_30_DAYS", "ALL");
        String selection = dps.hasSelectionIntent() ? reference(catalog.find(dps.currentEncounterId())) : values.getOrDefault("selected", selectedKey);
        validateReference(selection);
        Set<String> checks = new LinkedHashSet<>();
        if (values.containsKey("checked")) for (String ref : values.get("checked").split("\n")) { validateReference(ref); if (!ref.isEmpty()) checks.add(ref); }
        else for (EncounterCatalog.Entry entry : catalog.checkedEntries()) checks.add(EncounterCatalog.reference(entry));
        long generation = values.containsKey("generation") ? Long.parseLong(values.get("generation")) : catalog.generation();
        if (generation < 0) throw new IllegalArgumentException("Invalid catalog generation");
        boolean cleared = catalog.lifetimeId().equals(values.get("catalog")) && generation != catalog.generation();
        // The sort through the table helper; the widths here, for hidden columns too, and only when saved by this column layout
        // (the older layout's widths summed past the desktop table, P5b finding 2).
        Map<String, String> sortOnly = new LinkedHashMap<>(values);
        sortOnly.keySet().removeIf(key -> key.startsWith("width."));
        Runnable sort = RosterViewState.prepareTable(sortOnly, table);
        Map<Integer, Integer> widths = new HashMap<>();
        if (LAYOUT.equals(values.get("columns"))) for (int i = 0; i < this.columns.length; i++)
            if (values.containsKey("width." + i)) widths.put(i, RosterViewState.number(values, "width." + i, 75, 16, 10000));
        Runnable columns = () -> {
            sort.run();
            for (Map.Entry<Integer, Integer> width : widths.entrySet()) {
                TableColumn column = this.columns[width.getKey()]; column.setPreferredWidth(width.getValue()); column.setWidth(width.getValue());
            }
        };
        return () -> {
            restoringState = true;
            try {
                rememberedChecks.clear(); if (!cleared) rememberedChecks.addAll(checks);
                selectedKey = cleared ? "" : selection; rememberedGeneration = catalog.generation();
                updating = true;
                try { search.setText(values.getOrDefault("text", search.getText())); source.setSelectedIndex(selectedSource); context.setSelectedIndex(selectedContext); link.setSelectedIndex(selectedLink); }
                finally { updating = false; }
                boolean rescoped = scope.selected() != selectedScope; scope.setSelected(selectedScope);
                columns.run(); applyRememberedReferences(); filter();
                if (rescoped && loadedOnce) request(true);
            } finally { restoringState = false; }
        };
    }
    private static boolean valid(String reference) {
        return reference.isEmpty() || reference.matches("file:[0-9a-f]{64}|(?:native|entry):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }
    private static void validateReference(String reference) {
        if (!valid(reference)) throw new IllegalArgumentException("Invalid encounter reference");
    }
    private void applyRememberedReferences() {
        if (rememberedGeneration != catalog.generation()) {   // Clear DPS Logs: remembered checks and the selection no longer apply
            rememberedGeneration = catalog.generation(); rememberedChecks.clear(); selectedKey = ""; rememberViewState();
        }
        for (Iterator<String> pending = rememberedChecks.iterator(); pending.hasNext();) {
            EncounterCatalog.Entry entry = catalog.resolve(pending.next());
            if (entry != null) { catalog.check(entry.id, true); pending.remove(); }
        }
    }

    /** Stops reading: the worker, the timers and any read in flight. Idempotent. */
    @Override public void close() {
        if (closed) return;
        closed = true; generation++; summaryGeneration++; cancel.cancel(); summaryCancel.cancel();
        revisionTimer.stop(); poll.stop(); worker.shutdownNow();
    }

    /** One read's result: the stamp read first, the revision, scope and store it read, and its rows, "unchanged", or a failure. */
    private record Loaded(Object stamp, long revision, RecordingsQuery.Scope scope, SessionStore store, RecordingsSource.Result result,
                          List<Row> rows, boolean unchanged, Throwable failure) {}

    /** One table row: the live row (no item) or one recording with its copy in memory (else null) and labels built off the EDT. */
    private static final class Row {
        static final Row LIVE = new Row(null, null, null, "", DungeonListGUI.LIVE, null, null, null, null, null, "");
        final RecordingItem item; final EncounterCatalog.Entry entry; final EncounterSummary summary;
        final String reference, dungeon, source, run, runTip, saved, savedTip, haystack;
        Row(RecordingItem item, EncounterCatalog.Entry entry, EncounterSummary summary, String reference, String dungeon, String source,
            String run, String runTip, String saved, String savedTip, String haystack) {
            this.item = item; this.entry = entry; this.summary = summary; this.reference = reference; this.dungeon = dungeon; this.source = source;
            this.run = run; this.runTip = runTip; this.saved = saved; this.savedTip = savedTip; this.haystack = haystack;
        }
        String localContext() { return summary == null ? null : summary.localContext; }
    }

    /**
     * The table's sorter: the live row stays the first row under every sort, in either direction (P5b finding 4). The sorter reads the
     * live row as {@link #PINNED}, which each column's order puts first whichever way that column is sorted, and a recording's
     * missing value as {@link #NONE}, which sorts as the sorter sorts a null (before any value, ascending). Everything else is the
     * column's own order.
     */
    private static final class PinnedSorter extends TableRowSorter<EncounterModel> {
        private static final Object PINNED = new Object(), NONE = new Object();

        PinnedSorter(EncounterModel model) {
            super(model);
            ModelWrapper<EncounterModel, Integer> plain = getModelWrapper();
            setModelWrapper(new ModelWrapper<EncounterModel, Integer>() {
                @Override public EncounterModel getModel() { return plain.getModel(); }
                @Override public int getColumnCount() { return plain.getColumnCount(); }
                @Override public int getRowCount() { return plain.getRowCount(); }
                @Override public Object getValueAt(int row, int column) {
                    if (model.rows.get(row).item == null) return PINNED;
                    Object value = plain.getValueAt(row, column);
                    return value == null ? NONE : value;
                }
                @Override public String getStringValueAt(int row, int column) { return plain.getStringValueAt(row, column); }
                @Override public Integer getIdentifier(int row) { return plain.getIdentifier(row); }
            });
            for (int column = 0; column < model.getColumnCount(); column++) {
                @SuppressWarnings("unchecked") Comparator<Object> order = (Comparator<Object>) getComparator(column);
                int index = column;
                setComparator(column, (a, b) -> {
                    if (a == PINNED || b == PINNED) return a == b ? 0 : (a == PINNED ? -1 : 1) * (descending(index) ? -1 : 1);
                    if (a == NONE || b == NONE) return a == b ? 0 : a == NONE ? -1 : 1;
                    return order.compare(a, b);
                });
            }
        }

        /** The sorter negates a descending key's order; the live row's comparison is negated first, so it stays first. */
        private boolean descending(int column) {
            for (SortKey key : getSortKeys()) if (key.getColumn() == column) return key.getSortOrder() == SortOrder.DESCENDING;
            return false;
        }
    }

    /**
     * The status line with the file actions at its end, on one line while the status keeps at least half of it; otherwise (a narrow
     * page, a large font, or View imported encounter shown) the actions go on their own line below the status, at its end.
     */
    private static final class StatusRow extends JPanel {
        private static final int GAP = Tokens.S;
        private final JComponent text;
        private final JPanel actions;
        private int laidOutWidth = -1;

        StatusRow(JComponent text, JPanel actions) {
            super(null);
            this.text = text; this.actions = actions;
            setOpaque(false);
            add(text); add(actions);
        }

        private boolean beside(int width) { return width <= 0 || actions.getPreferredSize().width <= width / 2; }
        private int width() { return getWidth() > 0 ? getWidth() : getParent() == null ? 0 : getParent().getWidth(); }

        /** The wrapping status's height at {@code width}: sized first, as a wrapping text area measures at its own width. */
        private int textHeight(int width) {
            if (width > 0 && text.getWidth() != width) text.setSize(width, Math.max(1, text.getHeight()));
            return text.getPreferredSize().height;
        }

        @Override public Dimension getPreferredSize() {
            Insets insets = getInsets();
            int width = width() - insets.left - insets.right;
            Dimension buttons = actions.getPreferredSize();
            if (beside(width)) {
                int textWidth = width <= 0 ? text.getPreferredSize().width : width - buttons.width - GAP;
                return new Dimension(textWidth + GAP + buttons.width + insets.left + insets.right,
                    Math.max(textHeight(textWidth), buttons.height) + insets.top + insets.bottom);
            }
            return new Dimension(width + insets.left + insets.right, textHeight(width) + GAP + buttons.height + insets.top + insets.bottom);
        }
        @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }

        @Override public void doLayout() {
            Insets insets = getInsets();
            int width = getWidth() - insets.left - insets.right, x = insets.left, y = insets.top;
            Dimension buttons = actions.getPreferredSize();
            if (beside(width)) {
                int textWidth = Math.max(0, width - buttons.width - GAP), height = Math.max(textHeight(textWidth), buttons.height);
                text.setBounds(x, y + (height - textHeight(textWidth)) / 2, textWidth, textHeight(textWidth));
                actions.setBounds(x + width - buttons.width, y + (height - buttons.height) / 2, buttons.width, buttons.height);
            } else {
                int textHeight = textHeight(width);
                text.setBounds(x, y, width, textHeight);
                actions.setBounds(x + Math.max(0, width - buttons.width), y + textHeight + GAP, Math.min(width, buttons.width), buttons.height);
            }
        }

        /** A new width can move the actions beside or below the status: lay the page out again once it is known. */
        @Override public void setBounds(int x, int y, int width, int height) {
            super.setBounds(x, y, width, height);
            if (width != laidOutWidth) {
                laidOutWidth = width;
                if (getPreferredSize().height != height) SwingUtilities.invokeLater(this::revalidate);
            }
        }
    }

    /** Renders the recordings' columns: words for unknowns, blank cells on the live row, reasons as tooltips. */
    private final class RecordingCell extends ContentStyle.Cell {
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            Row data = model.rows.get(table.convertRowIndexToModel(row));
            int index = table.convertColumnIndexToModel(column);
            String text = getText(), tip = null;
            if (data.item == null) {   // the live row: what it is, not blank cells
                text = index == 2 ? LIVE : index == 9 ? LIVE_HINT : "";
                tip = index == 2 || index == 9 ? "The live meter; Open shows it" : null;
            }
            else switch (index) {
                case 1: tip = data.entry == null ? "Not in memory; Open loads its full detail or shows its summary when it has one" : "Library entry " + data.entry.id; break;
                case 3: text = value == null ? "Not captured" : DisplayFormat.formatTimestamp((Long) value); break;
                case 4: text = value == null ? "Unavailable" : DisplayFormat.formatNumber(((Long) value) / 1000d, 0, 1); break;
                case 6: text = value == null ? "—" : DisplayFormat.formatInteger((Long) value); break;
                case 8: if (value == null) { text = "—"; tip = "Local context is read from the full recording; it is known once the recording is in memory"; } break;
                case 9: tip = data.run + ". " + data.runTip; break;   // the whole label too, should the column be narrower than it
                case 10: tip = data.saved + ". " + data.savedTip; break;
                default: break;
            }
            setText(text); setToolTipText(tip);
            return this;
        }
    }

    private final class EncounterModel extends AbstractTableModel {
        private final String[] columns = {"Export", "Entry", "Dungeon", "Recorded start", "Elapsed (s)", "Contributors", "Damage", "Source file", "Local context", "Run", "Saved"};
        private List<Row> rows = List.of(Row.LIVE);   // the live row until the first read
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int column) { return columns[column]; }
        public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : column == 3 || column == 4 || column == 6 ? Long.class : column == 5 ? Integer.class : String.class; }
        /** Only a recording in memory can be checked for export. */
        public boolean isCellEditable(int row, int column) { EncounterCatalog.Entry entry = rows.get(row).entry; return column == 0 && entry != null && catalog.find(entry.id) != null; }
        public Object getValueAt(int row, int column) {
            Row value = rows.get(row); RecordingItem item = value.item;
            if (item == null) return column == 2 ? LIVE : null;
            switch (column) {
                case 0: return value.entry == null ? null : catalog.checked(value.entry.id);
                case 1: return value.entry == null ? NOT_LOADED : value.entry.id.substring(0, 8);
                case 2: return value.dungeon; case 3: return item.startedAt(); case 4: return item.elapsedMs(); case 5: return item.contributors();
                case 6: return item.totalDamage(); case 7: return value.source; case 8: return value.localContext();
                case 9: return value.run; default: return value.saved;
            }
        }
        public void setValueAt(Object value, int row, int column) {
            if (!isCellEditable(row, column)) return;
            catalog.check(rows.get(row).entry.id, Boolean.TRUE.equals(value)); fireTableCellUpdated(row, column); updateButtons();
            String reference = EncounterCatalog.reference(rows.get(row).entry);
            rememberedChecks.remove(reference); // Loaded checks are authoritative in the catalog; only unresolved references wait here.
            rememberViewState();
        }
    }
    public class CheckBoxAccessory extends JPanel {
        private final JCheckBox checkBox = new JCheckBox("Save Debug Data");
        public CheckBoxAccessory() { super(new BorderLayout()); setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8)); add(checkBox, BorderLayout.NORTH); }
        public boolean isBoxSelected() { return checkBox.isSelected(); }
    }
}
