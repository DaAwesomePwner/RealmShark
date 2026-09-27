package tomato.gui.kit;

import java.awt.*;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
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
        int width = kind.width(table.getFont());
        column.setPreferredWidth(width);
        column.setWidth(width);
        column.setCellRenderer(renderer(kind));
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
