package tomato.gui.roster;

import java.awt.BorderLayout;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.table.TableColumn;
import tomato.gui.history.*;
import tomato.gui.kit.KitTables;
import tomato.gui.modern.ContentStyle;
import tomato.history.archive.ArchiveQuery;
import util.PreferencesStore;

/** Module-owned live-state adapter using the foundation's versioned, asynchronous preference store. */
public final class RosterViewState {
    public enum Order { NONE }
    public static final class Fields { public int version = 1; public Map<String, String> values = new LinkedHashMap<>(); }
    private final ViewStateStore store;
    private final String key;
    private final Supplier<Map<String, String>> capture;
    private final Function<Map<String, String>, Runnable> prepare;
    private final BooleanSupplier ownsLiveState;
    private final JTextArea status = ContentStyle.wrappingText("");
    private final JPanel controls = new JPanel(new BorderLayout(0, 4));
    private final JButton retry = new JButton("Save view state"), reset = new JButton("Reset saved view state");
    private ViewState<Fields, Order> state;
    private boolean restoring, blocked, queued;
    private long saveGeneration;
    private long changeGeneration;
    private final List<Runnable> statusListeners = new ArrayList<>();
    /** The latest status is a failure: a save failed, or the saved state could not be read. */
    private boolean problem;

    public RosterViewState(ViewStateStore store, String key, Supplier<Map<String, String>> capture,
                           Function<Map<String, String>, Runnable> prepare) {
        this(store, key, capture, prepare, () -> true);
    }
    public RosterViewState(ViewStateStore store, String key, Supplier<Map<String, String>> capture,
                           Function<Map<String, String>, Runnable> prepare, BooleanSupplier ownsLiveState) {
        this.store = store; this.key = key; this.capture = capture; this.prepare = prepare;
        this.ownsLiveState = Objects.requireNonNull(ownsLiveState);
        state = ViewState.initial(ArchiveQuery.of(ArchiveQuery.CURRENT, new Fields(), Fields.class, Order.NONE));
        status.setName(key + "-state-status"); status.getAccessibleContext().setAccessibleName("Live workspace state persistence");
        retry.setName(key + "-save-state"); reset.setName(key + "-reset-state");
        retry.addActionListener(e -> save()); reset.addActionListener(e -> reset());
        JPanel actions = ContentStyle.controls(); actions.add(retry); actions.add(reset);
        controls.add(status, BorderLayout.NORTH); controls.add(actions, BorderLayout.SOUTH);
        try {
            ViewState<Fields, Order> saved = store.load(key, state); Fields fields = saved.query.facets();
            if (fields.version != 1 || fields.values == null || fields.values.containsValue(null)) throw new IllegalArgumentException("Unsupported live state");
            if (ownsLiveState.getAsBoolean()) {
                Runnable apply = prepare.apply(Collections.unmodifiableMap(fields.values));
                restoring = true;
                try { apply.run(); } finally { restoring = false; }
            }
            state = saved;
        } catch (RuntimeException invalid) {
            blocked = true; status("Saved view state unavailable. Current controls remain usable; Reset saved view state to replace it.", true);
        }
        ownershipChanged();
    }
    public JComponent controls() { return controls; }
    public boolean restoring() { return restoring; }
    /** Invalidate pending UI intent at a scope boundary, including a live -> recorded -> live round trip. */
    public void ownershipChanged() {
        cancelQueued();
        boolean active = ownsLiveState.getAsBoolean();
        controls.setVisible(active); retry.setEnabled(active); reset.setEnabled(active);
    }
    private void cancelQueued() { queued = false; changeGeneration++; }
    public void changed() {
        if (restoring || blocked || queued || !ownsLiveState.getAsBoolean()) return;
        queued = true;
        long ticket = ++changeGeneration;
        SwingUtilities.invokeLater(() -> { if (queued && ticket == changeGeneration) { queued = false; save(); } });
    }
    public CompletionStage<PreferencesStore.SaveResult> save() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Capture view state on the EDT");
        cancelQueued();
        if (!ownsLiveState.getAsBoolean()) return CompletableFuture.completedFuture(PreferencesStore.SaveResult.failed(0, new IllegalStateException("Live view state is not active")));
        if (blocked) return CompletableFuture.completedFuture(PreferencesStore.SaveResult.failed(0, new IllegalStateException("Reset unsupported saved state first")));
        Fields fields = new Fields(); fields.values.putAll(state.query.facets().values); fields.values.putAll(capture.get()); state = state.withQuery(state.query.withFacets(fields));
        try { return watch(store.save(key, state)); }
        catch (RuntimeException failure) { return watch(CompletableFuture.completedFuture(PreferencesStore.SaveResult.failed(0, failure))); }
    }
    public void restoreLast() {
        if (!ownsLiveState.getAsBoolean()) return;
        cancelQueued();
        Runnable apply = prepare.apply(state.query.facets().values); restoring = true;
        try { apply.run(); } finally { restoring = false; }
    }
    private void reset() {
        cancelQueued();
        if (!ownsLiveState.getAsBoolean()) return;
        blocked = false;
        state = ViewState.initial(ArchiveQuery.of(ArchiveQuery.CURRENT, new Fields(), Fields.class, Order.NONE));
        watch(store.reset(key));
        status("Saved state reset. Current controls and notes are retained; Save view state remembers them.", false);
    }
    private CompletionStage<PreferencesStore.SaveResult> watch(CompletionStage<PreferencesStore.SaveResult> save) {
        long request = ++saveGeneration;
        save.whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
            if (request != saveGeneration) return;
            boolean saved = failure == null && result != null && result.isSuccess();
            status(saved ? "View state saved." : "View state save failed; current controls remain active. Save view state retries.", !saved);
        }));
        return save;
    }
    private void status(String text, boolean failed) {
        problem = failed;
        status.setText(text);
        for (Runnable listener : new ArrayList<>(statusListeners)) listener.run();
    }
    /** The latest status line: saved, save failed, reset, or saved state unavailable ("" before any). */
    public String statusText() { return status.getText(); }
    /** True while the latest status is a failure: the last save failed or the saved state could not be read. */
    public boolean statusProblem() { return problem; }
    /** Runs on the EDT after every status change, for hosts that offer the actions in a menu and show only failures. */
    public void onStatus(Runnable listener) { statusListeners.add(Objects.requireNonNull(listener)); }
    /** What the Reset saved view state button does, for hosts that offer it in a menu. */
    public void resetSaved() { reset(); }

    public static int number(Map<String, String> values, String key, int fallback, int minimum, int maximum) {
        if (!values.containsKey(key)) return fallback;
        int value = Integer.parseInt(values.get(key));
        if (value < minimum || value > maximum) throw new IllegalArgumentException("Invalid " + key);
        return value;
    }
    public static int option(Map<String, String> values, String key, int fallback, String... ids) {
        String value = values.get(key); if (value == null) return fallback;
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(value)) return i;
        throw new IllegalArgumentException("Unknown " + key);
    }
    /**
     * Sort keys and the widths of the table's columns by model index. A column {@code KitTables.analystOnly} hides in Simple keeps
     * the width it had (or the width a restore gave it while hidden), so a Simple capture never drops it; nothing records it as
     * hidden. The table must reach {@link #listenTable}, this or {@link #prepareTable} before the mode first hides a column.
     */
    public static void captureTable(Map<String, String> values, JTable table) {
        ModeWidths mode = modeWidths(table);
        StringJoiner sort = new StringJoiner(",");
        if (table.getRowSorter() != null) for (RowSorter.SortKey key : table.getRowSorter().getSortKeys()) sort.add(key.getColumn() + ":" + key.getSortOrder().name());
        values.put("sort", sort.toString());
        Set<Integer> shown = new HashSet<>();
        for (Enumeration<TableColumn> columns = table.getColumnModel().getColumns(); columns.hasMoreElements();) {
            TableColumn column = columns.nextElement(); shown.add(column.getModelIndex());
            values.put("width." + column.getModelIndex(), Integer.toString(Math.min(10000, Math.max(16, column.getWidth()))));
        }
        if (!KitTables.modeHidden(table).isEmpty())
            for (Map.Entry<Integer, Integer> width : mode.widths.entrySet())
                if (!shown.contains(width.getKey()) && width.getKey() < table.getModel().getColumnCount())
                    values.put("width." + width.getKey(), Integer.toString(Math.min(10000, Math.max(16, width.getValue()))));
    }
    /**
     * Validate everything before changing any controls, so a malformed document cannot apply half a state. A saved width for a
     * column the mode hides right now is kept and applied when Analyst shows the column again; no column is hidden or dropped.
     */
    public static Runnable prepareTable(Map<String, String> values, JTable table) {
        ModeWidths mode = modeWidths(table);
        List<RowSorter.SortKey> sort = new ArrayList<>();
        String raw = values.get("sort");
        if (raw != null && !raw.isEmpty()) for (String entry : raw.split(",")) {
            String[] pieces = entry.split(":", -1);
            if (pieces.length != 2) throw new IllegalArgumentException("Invalid sort");
            int column = Integer.parseInt(pieces[0]);
            if (column < 0 || column >= table.getModel().getColumnCount()) throw new IllegalArgumentException("Invalid sort column");
            sort.add(new RowSorter.SortKey(column, SortOrder.valueOf(pieces[1])));
        }
        Map<Integer, Integer> widths = new HashMap<>();
        for (int column = 0; column < table.getModel().getColumnCount(); column++)
            if (values.containsKey("width." + column)) widths.put(column, number(values, "width." + column, 75, 16, 10000));
        return () -> {
            if (raw != null && table.getRowSorter() != null) table.getRowSorter().setSortKeys(sort);
            Set<Integer> shown = new HashSet<>();
            for (Enumeration<TableColumn> columns = table.getColumnModel().getColumns(); columns.hasMoreElements();) {
                TableColumn column = columns.nextElement(); Integer width = widths.get(column.getModelIndex()); shown.add(column.getModelIndex());
                if (width != null) { column.setPreferredWidth(width); column.setWidth(width); }
            }
            if (!KitTables.modeHidden(table).isEmpty())
                for (Map.Entry<Integer, Integer> width : widths.entrySet())
                    if (!shown.contains(width.getKey())) { mode.widths.put(width.getKey(), width.getValue()); mode.pending.add(width.getKey()); }
        };
    }
    /**
     * Calls {@code changed} after the user sorts, moves or resizes. Changes {@code KitTables.analystOnly} makes on a mode switch,
     * layouts HistoryTables applies and the widths restored here are not the user's; the mode's flag is read in each callback.
     */
    public static void listenTable(JTable table, Runnable changed) {
        ModeWidths mode = modeWidths(table);
        java.util.function.BooleanSupplier ignored = () -> mode.applying || Boolean.TRUE.equals(table.getClientProperty(KitTables.MODE_CHANGING))
            || Boolean.TRUE.equals(table.getClientProperty(HistoryTables.RESTORING_COLUMNS));
        if (table.getRowSorter() != null) table.getRowSorter().addRowSorterListener(e -> {
            if (e.getType() == javax.swing.event.RowSorterEvent.Type.SORT_ORDER_CHANGED && !ignored.getAsBoolean()) changed.run();
        });
        table.getColumnModel().addColumnModelListener(new javax.swing.event.TableColumnModelListener() {
            public void columnAdded(javax.swing.event.TableColumnModelEvent e) { }
            public void columnRemoved(javax.swing.event.TableColumnModelEvent e) { }
            public void columnMoved(javax.swing.event.TableColumnModelEvent e) { if (e.getFromIndex() != e.getToIndex() && !ignored.getAsBoolean()) changed.run(); }
            public void columnMarginChanged(javax.swing.event.ChangeEvent e) { if (!ignored.getAsBoolean()) changed.run(); }
            public void columnSelectionChanged(javax.swing.event.ListSelectionEvent e) { }
        });
    }

    /** Table client property: the table's {@link ModeWidths}. */
    private static final String MODE_WIDTHS = "roster.modeWidths";
    private static ModeWidths modeWidths(JTable table) {
        Object saved = table.getClientProperty(MODE_WIDTHS);
        if (saved instanceof ModeWidths) return (ModeWidths) saved;
        ModeWidths mode = new ModeWidths(table); table.putClientProperty(MODE_WIDTHS, mode);
        table.addPropertyChangeListener(KitTables.MODE_CHANGING, mode);
        return mode;
    }
    /**
     * One table's column widths across mode switches, by model index. {@code KitTables.analystOnly} flags each change with
     * {@code KitTables.MODE_CHANGING}: as a change begins the shown columns' widths are remembered (the hidden ones keep theirs),
     * and a column shown again takes a width restored while it was hidden. EDT.
     */
    private static final class ModeWidths implements java.beans.PropertyChangeListener {
        private final JTable table;
        final Map<Integer, Integer> widths = new HashMap<>();
        /** Hidden columns whose width a restore replaced; applied when they are shown again. */
        final Set<Integer> pending = new HashSet<>();
        boolean applying;
        private List<TableColumn> before;
        ModeWidths(JTable table) { this.table = table; }
        @Override public void propertyChange(java.beans.PropertyChangeEvent event) {
            if (Boolean.TRUE.equals(event.getNewValue())) {
                before = Collections.list(table.getColumnModel().getColumns());
                for (TableColumn column : before) widths.put(column.getModelIndex(), column.getWidth());
                return;
            }
            List<TableColumn> was = before; before = null;
            if (was == null) return;
            applying = true;
            try {
                for (TableColumn column : Collections.list(table.getColumnModel().getColumns())) {
                    if (was.contains(column) || !pending.remove(column.getModelIndex())) continue;
                    Integer width = widths.get(column.getModelIndex());
                    if (width != null) { column.setPreferredWidth(width); column.setWidth(width); }
                }
            } finally { applying = false; }
        }
    }
}
