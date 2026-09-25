package tomato.gui.roster;

import java.awt.BorderLayout;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.table.TableColumn;
import tomato.gui.history.*;
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
            blocked = true; status.setText("Saved view state unavailable. Current controls remain usable; Reset saved view state to replace it.");
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
        status.setText("Saved state reset. Current controls and notes are retained; Save view state remembers them.");
    }
    private CompletionStage<PreferencesStore.SaveResult> watch(CompletionStage<PreferencesStore.SaveResult> save) {
        long request = ++saveGeneration;
        save.whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
            if (request == saveGeneration) status.setText(failure == null && result != null && result.isSuccess()
                ? "View state saved." : "View state save failed; current controls remain active. Save view state retries.");
        }));
        return save;
    }
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
    public static void captureTable(Map<String, String> values, JTable table) {
        StringJoiner sort = new StringJoiner(",");
        if (table.getRowSorter() != null) for (RowSorter.SortKey key : table.getRowSorter().getSortKeys()) sort.add(key.getColumn() + ":" + key.getSortOrder().name());
        values.put("sort", sort.toString());
        for (Enumeration<TableColumn> columns = table.getColumnModel().getColumns(); columns.hasMoreElements();) {
            TableColumn column = columns.nextElement(); values.put("width." + column.getModelIndex(), Integer.toString(Math.min(10000, Math.max(16, column.getWidth()))));
        }
    }
    /** Validate everything before changing any controls, so a malformed document cannot apply half a state. */
    public static Runnable prepareTable(Map<String, String> values, JTable table) {
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
            for (Enumeration<TableColumn> columns = table.getColumnModel().getColumns(); columns.hasMoreElements();) {
                TableColumn column = columns.nextElement(); Integer width = widths.get(column.getModelIndex());
                if (width != null) { column.setPreferredWidth(width); column.setWidth(width); }
            }
        };
    }
    public static void listenTable(JTable table, Runnable changed) {
        if (table.getRowSorter() != null) table.getRowSorter().addRowSorterListener(e -> {
            if (e.getType() == javax.swing.event.RowSorterEvent.Type.SORT_ORDER_CHANGED) changed.run();
        });
        table.getColumnModel().addColumnModelListener(new javax.swing.event.TableColumnModelListener() {
            public void columnAdded(javax.swing.event.TableColumnModelEvent e) { }
            public void columnRemoved(javax.swing.event.TableColumnModelEvent e) { }
            public void columnMoved(javax.swing.event.TableColumnModelEvent e) { if (e.getFromIndex() != e.getToIndex()) changed.run(); }
            public void columnMarginChanged(javax.swing.event.ChangeEvent e) { changed.run(); }
            public void columnSelectionChanged(javax.swing.event.ListSelectionEvent e) { }
        });
    }
}
