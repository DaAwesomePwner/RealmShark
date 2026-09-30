package tomato.gui.stats;

import java.awt.*;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import tomato.gui.kit.ItemIcon;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

/** Shared presentation for Loot › Explore's live loot view (and the saved fame graph's controls). */
final class StatsUi {
    private StatsUi() {}

    static JTextArea note(String text) {
        return ContentStyle.wrappingText(text, 1);
    }

    static JPanel stack(Component... children) {
        JPanel panel = new JPanel(new BorderLayout(0, 6)) {
            @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }
            @Override public void setBounds(int x, int y, int width, int height) {
                boolean changed = width != getWidth(); super.setBounds(x, y, width, height);
                if (changed) SwingUtilities.invokeLater(this::revalidate);
            }
        };
        // BorderLayout retains wrapped preferred heights when the page is narrower than a heading.
        JPanel row = panel;
        for (int i = 0; i < children.length; i++) {
            row.add(children[i], BorderLayout.NORTH);
            if (i + 1 < children.length) {
                JPanel next = new JPanel(new BorderLayout(0, 6)); row.add(next); row = next;
            }
        }
        return panel;
    }

    static JPanel controls() {
        return ContentStyle.controls();
    }

    /** A small useful data viewport, plus its header and scrollbar, independent of table row count. */
    static JScrollPane tableScroll(JTable table) {
        return new JScrollPane(table) {
            @Override public Dimension getMinimumSize() {
                return new Dimension(0, tableScrollHeight(this, table));
            }
        };
    }

    private static int tableScrollHeight(JScrollPane scroll, JTable table) {
        Insets insets = scroll.getInsets();
        int header = table.getTableHeader() == null ? 0 : table.getTableHeader().getPreferredSize().height;
        return table.getRowHeight() * 3 + header
            + scroll.getHorizontalScrollBar().getPreferredSize().height + insets.top + insets.bottom;
    }

    static JTextField search(String name, String placeholder, int columns) {
        JTextField field = new JTextField(columns); field.setName(name);
        field.putClientProperty("JTextField.placeholderText", placeholder);
        field.getAccessibleContext().setAccessibleName(placeholder);
        return field;
    }

    static void onSearch(JTextField field, Runnable action) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { action.run(); }
            public void removeUpdate(DocumentEvent e) { action.run(); }
            public void changedUpdate(DocumentEvent e) { action.run(); }
        });
    }

    static DefaultTableModel model(String[] names, Class<?>... types) {
        return new DefaultTableModel(names, 0) {
            public boolean isCellEditable(int row, int col) { return false; }
            public Class<?> getColumnClass(int col) { return types[col]; }
        };
    }

    static JTable table(DefaultTableModel model, String name) {
        JTable table = new JTable(model) {
            @Override public boolean getScrollableTracksViewportWidth() {
                return getParent() != null && getPreferredSize().width < getParent().getWidth();
            }
            @Override public String getToolTipText(java.awt.event.MouseEvent e) {
                int row = rowAtPoint(e.getPoint()), column = columnAtPoint(e.getPoint());
                if (row < 0 || column < 0) return null;
                Object value = getValueAt(row, column);
                // Item icons carry their own (already escaped) enchant tooltip, built here on hover.
                if (value instanceof ItemIcon) return ((ItemIcon) value).tooltip();
                if (value instanceof Icon) return null;
                Component cell = prepareRenderer(getCellRenderer(row, column), row, column);
                if (!(cell instanceof JLabel)) return null;
                JLabel label = (JLabel)cell;
                String text = label.getToolTipText() == null ? label.getText() : label.getToolTipText();
                if (text == null) return null;
                return "<html>" + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</html>";
            }
        }; table.setName(name);
        ContentStyle.table(table, ContentStyle.Density.COMFORTABLE);
        table.getAccessibleContext().setAccessibleName(name.replace('-', ' '));
        table.setAutoCreateRowSorter(true); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        for (int col = 0; col < model.getColumnCount(); col++) {
            boolean icon = model.getColumnClass(col) == Icon.class;
            table.getColumnModel().getColumn(col).setPreferredWidth(icon ? 36 : col == 0 || (col == 1 && model.getColumnClass(0) == Icon.class) ? 200 : 130);
            table.getColumnModel().getColumn(col).setMinWidth(icon ? 32 : 95);
        }
        DefaultTableCellRenderer text = new ContentStyle.Cell();
        text.putClientProperty("html.disable", true); table.setDefaultRenderer(String.class, text);
        // Integer values can be identifiers. Counts opt in to grouping at their call sites.
        DefaultTableCellRenderer integers = new ContentStyle.Cell() {
            protected void setValue(Object value) {
                setText(value == null ? DisplayFormat.UNAVAILABLE : value.toString());
            }
        };
        integers.setHorizontalAlignment(SwingConstants.RIGHT);
        table.setDefaultRenderer(Integer.class, integers); table.setDefaultRenderer(Long.class, integers);
        DefaultTableCellRenderer decimals = new ContentStyle.Cell() {
            protected void setValue(Object value) {
                setText(value == null ? "—" : Formatters.formatNumber(((Number)value).doubleValue(), 1));
            }
        };
        decimals.setHorizontalAlignment(SwingConstants.RIGHT); table.setDefaultRenderer(Double.class, decimals);
        table.setMinimumSize(new Dimension(0, 0));
        return table;
    }

    static void countColumns(JTable table, int... columns) {
        for (int column : columns) table.getColumnModel().getColumn(column).setCellRenderer(new ContentStyle.Cell() {
            protected void setValue(Object value) {
                setText(value == null ? DisplayFormat.UNAVAILABLE : DisplayFormat.formatInteger(((Number)value).longValue()));
            }
        });
    }

    /** Timestamp models retain epoch millis; text and zone labels are always rendered together. */
    static void timestampColumn(JTable table, int column) {
        table.getColumnModel().getColumn(column).setCellRenderer(new ContentStyle.Cell() {
            protected void setValue(Object value) {
                setText(value == null ? DisplayFormat.UNAVAILABLE : DisplayFormat.formatTimestamp(((Number)value).longValue()));
                setToolTipText(DisplayFormat.UNAVAILABLE.equals(getText()) ? null : getText() + " (" + DisplayFormat.timestampZoneLabel() + ")");
            }
        });
    }
}
