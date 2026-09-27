package tomato.gui.kit;

import java.awt.*;
import java.beans.PropertyChangeListener;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.table.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

/** Applies ColumnKind widths and renderers. Model values stay raw, so sorting and exports are unchanged. */
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

        private static Long epoch(Object value) {
            if (value instanceof Instant) return ((Instant) value).toEpochMilli();
            if (value instanceof Number) return ((Number) value).longValue();
            return null;
        }
    }
}
