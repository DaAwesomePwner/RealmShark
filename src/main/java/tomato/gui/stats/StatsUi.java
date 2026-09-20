package tomato.gui.stats;

import java.awt.*;
import java.util.Locale;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

/** Shared presentation for the statistics workspace. */
final class StatsUi {
    private StatsUi() {}

    /** Keeps short application windows usable without squeezing charts or tables to zero height. */
    static JScrollPane page(JComponent content, int minimumHeight) {
        reserveTableSpace(content);
        class Page extends JPanel implements Scrollable {
            Page() { super(new BorderLayout()); add(content); }
            private int contentHeight() { return Math.max(minimumHeight, content.getMinimumSize().height); }
            @Override public Dimension getPreferredSize() { return new Dimension(800, contentHeight()); }
            public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
            public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) { return 24; }
            public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) { return Math.max(24, r.height - 24); }
            public boolean getScrollableTracksViewportWidth() { return true; }
            public boolean getScrollableTracksViewportHeight() { return getParent() != null && getParent().getHeight() >= contentHeight(); }
        }
        JScrollPane scroll = new JScrollPane(new Page()); scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(24); return scroll;
    }

    private static void reserveTableSpace(Component component) {
        if (component instanceof JScrollPane) {
            JScrollPane scroll = (JScrollPane)component;
            Component view = scroll.getViewport().getView();
            if (view instanceof JTable) {
                JTable table = (JTable)view; Dimension minimum = scroll.getMinimumSize();
                Runnable resize = () -> scroll.setMinimumSize(new Dimension(minimum.width,
                    Math.max(minimum.height, tableScrollHeight(scroll, table))));
                resize.run();
                table.addPropertyChangeListener("rowHeight", e -> resize.run());
                if (table.getTableHeader() != null) table.getTableHeader().addPropertyChangeListener("preferredSize", e -> resize.run());
                return;
            }
        }
        if (component instanceof Container) for (Component child : ((Container)component).getComponents()) reserveTableSpace(child);
    }

    static JPanel heading(String title, String description) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        JLabel label = new JLabel(title);
        label.setFont(ContentStyle.emphasis(ContentStyle.body()).deriveFont(ContentStyle.body().getSize2D() * 16f / ContentStyle.FONT_SIZE));
        panel.add(label, BorderLayout.NORTH);
        panel.add(note(description), BorderLayout.CENTER);
        return panel;
    }

    static JTextArea note(String text) {
        return ContentStyle.wrappingText(text, 1);
    }

    static JPanel metrics(JLabel[] values, String... names) {
        // Two responsive pairs keep four summaries balanced at 4, 2 or 1 columns.
        JPanel row = ContentStyle.responsiveGrid(names.length == 4 ? 2 : names.length, names.length == 4 ? 358 : 175, 8);
        JPanel cells = row;
        for (int i = 0; i < names.length; i++) {
            if (names.length == 4 && i % 2 == 0) { cells = ContentStyle.responsiveGrid(2, 175, 8); row.add(cells); }
            JPanel card = new JPanel(new BorderLayout(0, 4)) {
                @Override public void updateUI() { super.updateUI(); setBackground(ContentStyle.color("surface")); }
            };
            card.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            values[i] = new JLabel("—") {
                @Override public void updateUI() { super.updateUI(); setForeground(ContentStyle.color("violet")); }
            };
            values[i].setFont(ContentStyle.emphasis(ContentStyle.body()).deriveFont(ContentStyle.body().getSize2D() * 18f / ContentStyle.FONT_SIZE));
            values[i].getAccessibleContext().setAccessibleDescription(names[i]);
            card.add(values[i], BorderLayout.CENTER);
            JLabel caption = new JLabel(names[i]); caption.setFont(ContentStyle.metadata(ContentStyle.body()));
            card.add(caption, BorderLayout.SOUTH); cells.add(card);
        }
        return row;
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

    static boolean matches(String text, String query) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
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
                if (getValueAt(row, column) instanceof Icon) return null;
                Component cell = prepareRenderer(getCellRenderer(row, column), row, column);
                if (!(cell instanceof JLabel)) return null;
                JLabel label = (JLabel)cell;
                String value = label.getToolTipText() == null ? label.getText() : label.getToolTipText();
                if (value == null) return null;
                return "<html>" + value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</html>";
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

    static void exactColumns(JTable table, int... columns) {
        for (int column : columns) table.getColumnModel().getColumn(column).setCellRenderer(new ContentStyle.Cell() {
            protected void setValue(Object value) { setText(DisplayFormat.formatExact((Number)value)); }
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

    static void durationColumn(JTable table, int column) {
        table.getColumnModel().getColumn(column).setCellRenderer(new ContentStyle.Cell() {
            protected void setValue(Object value) {
                setText(value == null ? "—" : Formatters.formatDurationHMS(((Number)value).longValue()));
            }
        });
    }
}
