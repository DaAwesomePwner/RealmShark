package tomato.gui.kit;

import java.awt.*;
import java.beans.PropertyChangeListener;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.List;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.table.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

/**
 * Applies ColumnKind widths and renderers, and the mode-aware time and Analyst-only columns. Model values stay raw, so sorting and
 * exports are unchanged.
 */
public final class KitTables {
    private KitTables() {}

    /** A sortable icon + text cell for dungeon, class and item columns. */
    public static final class IconText implements Comparable<IconText> {
        public final Icon icon;
        public final String text;
        public IconText(Icon icon, String text) { this.icon = icon; this.text = text == null ? "" : text; }
        @Override public int compareTo(IconText other) { return String.CASE_INSENSITIVE_ORDER.compare(text, other.text); }
        @Override public String toString() { return text; }
    }

    /** Applies kinds by view position. Call right after creating the table, before user reordering. */
    public static void apply(JTable table, ColumnKind... kinds) {
        TableColumnModel columns = table.getColumnModel();
        for (int i = 0; i < kinds.length && i < columns.getColumnCount(); i++) apply(table, columns.getColumn(i), kinds[i]);
    }

    /** Applies kinds by column identifier (HistoryTables columns carry stable IDs; plain models use header text). */
    public static void apply(JTable table, Map<String, ColumnKind> kinds) {
        for (TableColumn column : Collections.list(table.getColumnModel().getColumns())) {
            ColumnKind kind = kinds.get(String.valueOf(column.getIdentifier()));
            if (kind != null) apply(table, column, kind);
        }
    }

    private static void apply(JTable table, TableColumn column, ColumnKind kind) {
        fitKind(table, column, kind);
        column.setCellRenderer(renderer(kind));
    }

    /** Client properties on a fitted table: its fits by column identifier, and whether a refit is queued. */
    private static final String FITS = "kit.kindFits", REFIT_PENDING = "kit.kindRefitPending";

    /** One fitted column: its kind and the width fitKind last gave it. */
    private static final class Fit {
        final TableColumn column;
        final ColumnKind kind;
        int width;

        Fit(TableColumn column, ColumnKind kind, int width) { this.column = column; this.kind = kind; this.width = width; }
    }

    /**
     * Sizes a column for its kind: the kind's width at the table font, never narrower than the column's minimum or
     * its header text at the header font. The width then follows font changes of the table or its header, re-fitted
     * on a later EDT turn so both fonts have settled, for as long as the column's preferred width is still the one
     * applied here. A header drag (JTable copies the dragged width into the preferred width) or a restored layout
     * ({@code HistoryTables.applyColumns}, {@code RosterViewState.prepareTable}) sets another width, which is kept.
     */
    public static void fitKind(JTable table, TableColumn column, ColumnKind kind) {
        Objects.requireNonNull(kind, "kind");
        fits(table).put(column.getIdentifier(), new Fit(column, kind, size(table, column, kind)));
    }

    private static int size(JTable table, TableColumn column, ColumnKind kind) {
        TableCellRenderer header = column.getHeaderRenderer() != null ? column.getHeaderRenderer()
            : table.getTableHeader() == null ? null : table.getTableHeader().getDefaultRenderer();
        int title = header == null ? 0
            : header.getTableCellRendererComponent(table, column.getHeaderValue(), false, false, -1, 0).getPreferredSize().width;
        int width = Math.max(column.getMinWidth(), Math.max(title, kind.width(table.getFont())));
        column.setPreferredWidth(width);
        column.setWidth(width);
        return column.getPreferredWidth();
    }

    /** The table's fits; the first call installs the font listeners, once per table. */
    @SuppressWarnings("unchecked")
    private static Map<Object, Fit> fits(JTable table) {
        Object saved = table.getClientProperty(FITS);
        if (saved != null) return (Map<Object, Fit>) saved;
        Map<Object, Fit> fits = new LinkedHashMap<>();
        table.putClientProperty(FITS, fits);
        PropertyChangeListener refit = event -> refitLater(table);
        table.addPropertyChangeListener("font", refit);
        if (table.getTableHeader() != null) table.getTableHeader().addPropertyChangeListener("font", refit);
        return fits;
    }

    /** Runtime font changes set the table font, then the header font; one refit after both covers the pair. */
    private static void refitLater(JTable table) {
        if (table.getClientProperty(REFIT_PENDING) != null) return;
        table.putClientProperty(REFIT_PENDING, Boolean.TRUE);
        SwingUtilities.invokeLater(() -> {
            table.putClientProperty(REFIT_PENDING, null);
            for (Fit fit : fits(table).values())
                if (fit.column.getPreferredWidth() == fit.width) fit.width = size(table, fit.column, fit.kind);
        });
    }

    public static TableCellRenderer renderer(ColumnKind kind) { return new KindRenderer(kind); }

    /** A tinted status badge whose tone depends on the cell value. */
    public static TableCellRenderer status(Function<Object, Tokens.Tone> tone) {
        return new ContentStyle.Badge() {
            @Override protected Color badgeColor(Object value) { return Tokens.tone(tone.apply(value)); }
        };
    }

    // ---- mode-aware columns (spec §3.2, §5.5): display only, so models, sorting, search, Copy and exports never see the mode ----

    /**
     * Table client property: the identifiers {@link #analystOnly} hides right now (every Analyst-only column while Simple, none in
     * Analyst), as an unmodifiable set. A layout saver reads it so a mode-hidden column is never saved as hidden by the user.
     */
    public static final String MODE_HIDDEN = "kit.modeHidden";
    /** Table client property: TRUE only while {@link #analystOnly} removes, re-adds, moves or re-sizes columns, so column-model listeners can ignore it. */
    public static final String MODE_CHANGING = "kit.modeChanging";
    private static final String ANALYST_ONLY = "kit.analystOnly", TIME_MODE = "kit.relativeTimeMode";
    static final int TICK_MILLIS = 60_000;
    /** Tables with a relative-time column and their mode. Weak keys; a mode never holds its table strongly. EDT only. */
    private static final Map<JTable, DisplayModeModel> TICKING = new WeakHashMap<>();
    /** The one shared, coalescing repaint timer; it never reads data. */
    private static Timer ticker;

    /**
     * A time column that follows the display mode. Analyst: the column's existing renderer, untouched (its units, zone and "Unknown"
     * wording). Simple: that renderer's own component with its text replaced by {@link KitFormat#relative} and its tooltip set to the
     * absolute text plus {@code zoneLabel} ("2026-09-29 12:04:11 (UTC)"); a value {@code epoch} cannot read keeps the renderer's own
     * wording, dimmed. Only the renderer changes: model values, sorting, search converters, Copy and exports stay absolute, and the
     * width is never refitted on a switch, so a saved layout never records a mode-specific width. A shared minute timer repaints the
     * showing Simple tables, and a mode switch repaints the table. Call on the EDT once the column's own renderer is installed (a
     * column without one renders through the table's default for its class); installing again re-wraps that original renderer.
     */
    public static void relativeTime(JTable table, Object columnId, DisplayModeModel mode, Function<Object, Long> epoch, String zoneLabel) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(epoch, "epoch");
        TableColumn column = column(table, columnId);
        if (column == null) throw new IllegalArgumentException("No column " + columnId);
        TableCellRenderer base = column.getCellRenderer();
        if (base instanceof ModeTimeRenderer) base = ((ModeTimeRenderer) base).base;
        column.setCellRenderer(new ModeTimeRenderer(base, mode, epoch, zoneLabel));
        if (table.getClientProperty(TIME_MODE) != mode) {
            table.putClientProperty(TIME_MODE, mode);
            mode.bind(table, value -> { if (table.getClientProperty(TIME_MODE) == mode) table.repaint(); });
        }
        TICKING.put(table, mode);
        if (ticker == null) ticker = new Timer(TICK_MILLIS, event -> tick());
        if (!ticker.isRunning()) ticker.start();
    }

    /**
     * Epoch milliseconds from an {@link Instant}, a {@link Number} (epoch ms) or an ISO-8601 date-time with an offset or zone
     * ("2026-09-29T12:04:11.5Z", "…+02:00", "…+02:00[Europe/Berlin]"); null for anything else, including a date-time without a
     * zone (never guessed) and a non-finite number.
     */
    public static Long epoch(Object value) {
        if (value instanceof Instant) return ((Instant) value).toEpochMilli();
        if (value instanceof Number) return Double.isFinite(((Number) value).doubleValue()) ? ((Number) value).longValue() : null;
        if (value instanceof String) return iso(((String) value).trim());
        return null;
    }

    private static Long iso(String text) {
        // Renderers call this for every painted cell; a shape check keeps clock times and names away from the parsers' exceptions.
        if (text.length() < 16 || text.charAt(4) != '-' || text.charAt(7) != '-' || Character.toUpperCase(text.charAt(10)) != 'T') return null;
        try { return Instant.parse(text).toEpochMilli(); } catch (DateTimeException notAnInstant) { /* an offset or zone follows */ }
        try { return ZonedDateTime.parse(text).toInstant().toEpochMilli(); } catch (DateTimeException unreadable) { return null; }
    }

    /**
     * Hides these columns (TableColumn identifiers) in Simple and puts them back in Analyst at the view index and width they had.
     * While they are hidden the table's {@link #MODE_HIDDEN} names them, and {@link #MODE_CHANGING} is TRUE only while columns move,
     * so a layout saver can ignore the change. A RowSorter's keys on a hidden column are dropped. A column already removed, a user's
     * own choice, is left alone and not brought back by Analyst. Model indices and exports are unchanged. Call on the EDT; calling
     * again adds identifiers without binding twice.
     */
    public static void analystOnly(JTable table, DisplayModeModel mode, Object... columnIds) {
        Objects.requireNonNull(mode, "mode");
        Object saved = table.getClientProperty(ANALYST_ONLY);
        AnalystOnly state = saved instanceof AnalystOnly ? (AnalystOnly) saved : new AnalystOnly();
        table.putClientProperty(ANALYST_ONLY, state);
        state.ids.addAll(Arrays.asList(columnIds));
        if (state.mode == mode) { state.apply(table, mode.analyst()); return; }
        state.mode = mode;
        mode.bind(table, value -> { if (state.mode == mode) state.apply(table, value == DisplayModeModel.Mode.ANALYST); });
    }

    /** The identifiers {@link #analystOnly} hides right now: every Analyst-only column while Simple, none in Analyst. */
    @SuppressWarnings("unchecked")
    public static Set<Object> modeHidden(JTable table) {
        Object hidden = table.getClientProperty(MODE_HIDDEN);
        return hidden instanceof Set ? (Set<Object>) hidden : Collections.emptySet();
    }

    /** One repaint pass: registered tables showing in Simple repaint what is visible; the timer stops once none is left. EDT. */
    static int tick() {
        int repainted = 0;
        for (Map.Entry<JTable, DisplayModeModel> entry : new ArrayList<>(TICKING.entrySet())) {
            JTable table = entry.getKey();
            if (table == null || !table.isShowing() || entry.getValue().analyst()) continue;
            table.repaint(table.getVisibleRect());
            repainted++;
        }
        if (TICKING.isEmpty() && ticker != null) ticker.stop();
        return repainted;
    }

    static boolean tickerRunning() { return ticker != null && ticker.isRunning(); }

    /** A column by identifier: shown, hidden by {@link #analystOnly}, or kept by HistoryTables while the user hides it. */
    private static TableColumn column(JTable table, Object id) {
        for (TableColumn column : Collections.list(table.getColumnModel().getColumns())) if (Objects.equals(id, column.getIdentifier())) return column;
        Object state = table.getClientProperty(ANALYST_ONLY);
        Hidden hidden = state instanceof AnalystOnly ? ((AnalystOnly) state).hidden.get(id) : null;
        if (hidden != null) return hidden.column;
        Object retained = table.getClientProperty("archive.columns");   // HistoryTables' list of every column
        if (retained instanceof Collection)
            for (Object column : (Collection<?>) retained)
                if (column instanceof TableColumn && Objects.equals(id, ((TableColumn) column).getIdentifier())) return (TableColumn) column;
        return null;
    }

    /**
     * Wraps a time column's renderer (see {@link #relativeTime}). Its tooltip and ink changes to the base renderer's shared stamp are
     * undone before that stamp renders again through this column, and once more after the paint pass, so neither Analyst nor another
     * column sharing the stamp (a table's default renderer) ever shows them.
     */
    private static final class ModeTimeRenderer implements TableCellRenderer {
        final TableCellRenderer base;
        private final DisplayModeModel mode;
        private final Function<Object, Long> epoch;
        private final String zone;
        private JComponent touched;
        private String touchedTip;
        private Color touchedInk;
        private boolean undoQueued;

        ModeTimeRenderer(TableCellRenderer base, DisplayModeModel mode, Function<Object, Long> epoch, String zone) {
            this.base = base; this.mode = mode; this.epoch = epoch; this.zone = zone;
        }

        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                 boolean focus, int row, int column) {
            undo();
            TableCellRenderer renderer = base != null ? base : table.getDefaultRenderer(table.getColumnClass(column));
            Component shown = renderer.getTableCellRendererComponent(table, value, selected, focus, row, column);
            if (mode.analyst() || !(shown instanceof JLabel)) return shown;
            JLabel label = (JLabel) shown;
            Long at = millis(value);
            touched = label;
            touchedTip = label.getToolTipText();
            touchedInk = null;
            if (at != null) {
                String absolute = label.getText();
                label.setText(KitFormat.relative(at));
                label.setToolTipText(absolute == null || absolute.isEmpty() ? null : zone == null || zone.isEmpty() ? absolute : absolute + " (" + zone + ")");
            } else if (!selected) {
                touchedInk = label.getForeground();
                label.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
            }
            if (!undoQueued) {
                undoQueued = true;
                SwingUtilities.invokeLater(() -> { undoQueued = false; undo(); });
            }
            return label;
        }

        /** An unreadable value is unknown: the base renderer's wording stays, dimmed. */
        private Long millis(Object value) {
            if (value == null) return null;
            try { return epoch.apply(value); } catch (RuntimeException unreadable) { return null; }
        }

        private void undo() {
            if (touched == null) return;
            touched.setToolTipText(touchedTip);
            if (touchedInk != null) touched.setForeground(touchedInk);
            touched = null;
        }
    }

    /** One table's Analyst-only columns, and where each column the mode hid stood. */
    private static final class AnalystOnly {
        final Set<Object> ids = new LinkedHashSet<>();
        /** Columns the mode removed, by identifier, with the view index and widths to restore. */
        final Map<Object, Hidden> hidden = new LinkedHashMap<>();
        DisplayModeModel mode;

        void apply(JTable table, boolean analyst) {
            TableColumnModel columns = table.getColumnModel();
            List<TableColumn> shown = Collections.list(columns.getColumns());
            List<Hidden> moves = new ArrayList<>();
            if (analyst) {
                for (Hidden column : hidden.values()) if (!shown.contains(column.column)) moves.add(column);
                moves.sort(Comparator.comparingInt(column -> column.index));   // ascending, so every earlier index is filled first
            } else {
                // Indexes are read before anything is removed: they are the positions to restore.
                for (int i = 0; i < shown.size(); i++) if (ids.contains(shown.get(i).getIdentifier())) moves.add(new Hidden(shown.get(i), i));
            }
            table.putClientProperty(MODE_HIDDEN, analyst ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(ids)));
            if (moves.isEmpty()) { if (analyst) hidden.clear(); return; }
            table.putClientProperty(MODE_CHANGING, Boolean.TRUE);
            try {
                if (analyst) {
                    for (Hidden column : moves) {
                        columns.addColumn(column.column);
                        columns.moveColumn(columns.getColumnCount() - 1, Math.min(column.index, columns.getColumnCount() - 1));
                        column.column.setPreferredWidth(column.preferred);
                        column.column.setWidth(column.width);
                    }
                    hidden.clear();
                } else {
                    Set<Integer> models = new HashSet<>();
                    for (Hidden column : moves) {
                        hidden.put(column.column.getIdentifier(), column);
                        models.add(column.column.getModelIndex());
                        columns.removeColumn(column.column);
                    }
                    RowSorter<? extends TableModel> sorter = table.getRowSorter();
                    if (sorter != null) {
                        List<RowSorter.SortKey> kept = new ArrayList<>();
                        for (RowSorter.SortKey key : sorter.getSortKeys()) if (!models.contains(key.getColumn())) kept.add(key);
                        if (kept.size() != sorter.getSortKeys().size()) sorter.setSortKeys(kept);
                    }
                }
            } finally {
                table.putClientProperty(MODE_CHANGING, null);
            }
        }
    }

    private static final class Hidden {
        final TableColumn column;
        final int index, preferred, width;

        Hidden(TableColumn column, int index) {
            this.column = column; this.index = index; preferred = column.getPreferredWidth(); width = column.getWidth();
        }
    }

    private static final class KindRenderer extends ContentStyle.Cell {
        private final ColumnKind kind;

        KindRenderer(ColumnKind kind) { this.kind = kind; }

        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                 boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            setHorizontalAlignment(kind.alignment);
            setToolTipText(null);
            if (kind == ColumnKind.ID) setFont(ContentStyle.report(table.getFont()));
            if (value instanceof DisplayValue) {
                DisplayValue shown = (DisplayValue) value;
                setText(shown.display());
                setToolTipText(shown.tooltip());
                if (!selected && shown.dimmed()) setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
            } else if (value instanceof IconText) {
                setIcon(((IconText) value).icon);
                setIconTextGap(6);
                setText(((IconText) value).text);
            } else if (value == null) {
                setText(DisplayFormat.UNAVAILABLE);
                if (!selected) setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
            } else {
                setText(format(value));
                setToolTipText(tooltip(value));
            }
            return this;
        }

        private String format(Object value) {
            Number number = value instanceof Number ? (Number) value : null;
            switch (kind) {
                case TIME_RELATIVE: return epoch(value) == null ? String.valueOf(value) : KitFormat.relative(epoch(value));
                case DATE_TIME: return epoch(value) == null ? String.valueOf(value) : DisplayFormat.formatTimestamp(Instant.ofEpochMilli(epoch(value)));
                case DURATION: return number == null ? String.valueOf(value) : KitFormat.duration(number.longValue());
                case COUNT: return number == null ? String.valueOf(value) : DisplayFormat.formatInteger(number.longValue());
                case NUMBER: return number == null ? String.valueOf(value) : KitFormat.compact(number.doubleValue());
                case PERCENT: return number == null ? String.valueOf(value) : DisplayFormat.formatPercentage(number.doubleValue(), 1);
                default: return String.valueOf(value);
            }
        }

        private String tooltip(Object value) {
            if (kind == ColumnKind.TIME_RELATIVE && epoch(value) != null) return DisplayFormat.formatTimestamp(Instant.ofEpochMilli(epoch(value)));
            if (kind == ColumnKind.NUMBER && value instanceof Number) return DisplayFormat.formatNumber(((Number) value).doubleValue(), 0, 2);
            return null;
        }
    }
}
