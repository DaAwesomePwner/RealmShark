package tomato.gui.keypop;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

/** All three views and their metrics are derived from the same filtered history. */
final class KeyPopDashboard extends JPanel {
    private static final String ALL_ITEMS = "All dungeons / items";
    final JTextField search = new JTextField();
    final JComboBox<String> type = new JComboBox<>(new String[] {"All types", "Key", "Rune", "Vial", "Inc", "Other"});
    final JComboBox<String> period = new JComboBox<>(new String[] {"All retained", "Last 15 minutes", "Last hour", "Today"});
    final JComboBox<String> item = new JComboBox<>(new String[] {ALL_ITEMS});
    final JLabel[] metrics = new JLabel[4];
    private final JLabel[] metricCaptions = new JLabel[4];
    final JLabel status = new JLabel();
    final JLabel empty = new JLabel("Waiting for key pops", SwingConstants.CENTER);
    private final KeyPopHistory history;
    private final DefaultTableModel eventsModel = model(new String[] {"Time", "Player", "Type", "Dungeon / item"}, Instant.class, String.class, String.class, String.class);
    private final DefaultTableModel playersModel = model(new String[] {"Player", "Pops", "Keys", "Runes", "Vials", "Incs", "Share %", "Last pop"}, String.class, Integer.class, Integer.class, Integer.class, Integer.class, Integer.class, Double.class, Instant.class);
    private final DefaultTableModel itemsModel = model(new String[] {"Dungeon / item", "Pops", "Players", "Share %", "Last pop"}, String.class, Integer.class, Integer.class, Double.class, Instant.class);
    final JTable events = table(eventsModel, "keypop-events");
    final JTable players = table(playersModel, "keypop-players");
    final JTable items = table(itemsModel, "keypop-items");
    final JTabbedPane tabs = new JTabbedPane();
    private final javax.swing.Timer refreshTimer;
    private List<KeyPopEvent> filtered = Collections.emptyList();
    private long seenRevision = -1;
    private boolean updating;
    private final boolean historical;

    KeyPopDashboard(KeyPopHistory history) {
        this(history,false);
    }
    KeyPopDashboard(KeyPopHistory history, boolean historical) {
        super(new BorderLayout(0, 8)); this.history = history;this.historical=historical;
        refreshTimer = new javax.swing.Timer(1000, e -> {
            if (isShowing() && (history.revision() != seenRevision || period.getSelectedIndex() != 0)) refresh();
        });
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) refresh();
        });
        setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints(); constraints.gridx = 0; constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL; constraints.insets = new Insets(0, 0, 8, 0);
        JLabel note = new JLabel("Observed key, rune, vial and inc pops. Statistics follow your filters; callouts do not count.") {
            @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }
        };
        ContentStyle.font(note, ContentStyle.metadata(ContentStyle.body()));
        note.setToolTipText(note.getText());
        note.setForeground(UIManager.getColor("Label.disabledForeground"));
        constraints.gridy = 0; top.add(note, constraints);
        JPanel cards = ContentStyle.responsiveGrid(4, 118, 8);
        String[] captions = {"Observed pops", "Keys", "Players", "Dungeons/items"};
        for (int i = 0; i < captions.length; i++) {
            JPanel card = new JPanel(new BorderLayout(0, 3));
            card.setBackground(UIManager.getColor("Table.background"));
            card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground")), BorderFactory.createEmptyBorder(6, 8, 6, 8)));
            metrics[i] = new JLabel("0"); metrics[i].setName("keypop-metric-" + i);
            ContentStyle.font(metrics[i], ContentStyle.emphasis(ContentStyle.body()).deriveFont(ContentStyle.body().getSize2D() * 18f / ContentStyle.FONT_SIZE));
            metrics[i].setForeground(ContentStyle.color(new String[]{"violet", "amber", "mint", "blue"}[i]));
            card.add(metrics[i], BorderLayout.CENTER);
            JLabel caption = new JLabel(captions[i]); ContentStyle.font(caption, ContentStyle.metadata(ContentStyle.body()));
            metricCaptions[i] = caption;
            caption.setForeground(ContentStyle.color("muted"));
            card.add(caption, BorderLayout.SOUTH); cards.add(card);
        }
        constraints.gridy++; top.add(cards, constraints);
        JPanel searchRow = new JPanel(new BorderLayout(8, 0));
        search.setName("keypop-search"); search.putClientProperty("JTextField.placeholderText", "Search player, dungeon or item…");
        search.getAccessibleContext().setAccessibleName("Search key pops");
        search.setToolTipText("Case-insensitive search; every word must match the event.");
        searchRow.add(search); searchRow.add(button("Reset filters", this::resetFilters), BorderLayout.EAST);
        constraints.gridy++; top.add(searchRow, constraints);
        JPanel filters = ContentStyle.responsiveGrid(3, 140, 8);
        filters.add(labeled("Event type", type, "keypop-type")); filters.add(labeled("Time range", period, "keypop-period"));
        item.setPrototypeDisplayValue(ALL_ITEMS); filters.add(labeled("Dungeon / item", item, "keypop-item"));
        constraints.gridy++; constraints.insets = new Insets(0, 0, 0, 0); top.add(filters, constraints);
        events.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.DESCENDING)));
        players.getRowSorter().setSortKeys(Arrays.asList(new RowSorter.SortKey(1, SortOrder.DESCENDING), new RowSorter.SortKey(0, SortOrder.ASCENDING)));
        items.getRowSorter().setSortKeys(Arrays.asList(new RowSorter.SortKey(1, SortOrder.DESCENDING), new RowSorter.SortKey(0, SortOrder.ASCENDING)));
        tabs.addTab("Events", ContentStyle.tableScroll(events, 3)); tabs.addTab("By player", ContentStyle.tableScroll(players, 3)); tabs.addTab("By dungeon / item", ContentStyle.tableScroll(items, 3));
        JPanel body = new JPanel(new BorderLayout(0, 4)) {
            public Dimension getMinimumSize() {
                return new Dimension(0, tabs.getMinimumSize().height + (empty.isVisible() ? empty.getPreferredSize().height + 4 : 0));
            }
        };
        empty.setName("keypop-empty"); empty.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        body.add(empty, BorderLayout.NORTH); body.add(tabs);
        installDrilldown(players, true); installDrilldown(items, false);
        status.setName("keypop-status"); ContentStyle.font(status, ContentStyle.metadata(ContentStyle.body()));
        status.setForeground(ContentStyle.color("muted"));
        JScrollPane page = ContentStyle.page(top, body, status); page.setName("keypop-page-scroll"); add(page);
        onChange(search, this::refresh);
        type.addActionListener(e -> { if (!updating) refresh(); }); period.addActionListener(e -> { if (!updating) refresh(); });
        item.addActionListener(e -> { if (!updating) refresh(); }); refresh();
    }

    @Override public void updateUI() {
        super.updateUI();
        if (metrics == null) return;
        String[] colors = {"violet", "amber", "mint", "blue"};
        for (int i = 0; i < metrics.length; i++) {
            if (metrics[i] != null) metrics[i].setForeground(ContentStyle.color(colors[i]));
            if (metricCaptions[i] != null) metricCaptions[i].setForeground(ContentStyle.color("muted"));
        }
        status.setForeground(ContentStyle.color("muted"));
    }

    @Override public void addNotify() { super.addNotify(); refreshTimer.start(); }
    @Override public void removeNotify() { refreshTimer.stop(); super.removeNotify(); }

    void resetFilters() {
        updating = true; search.setText(""); type.setSelectedIndex(0); period.setSelectedIndex(0); item.setSelectedIndex(0);
        updating = false; refresh();
    }

    void refresh() {
        if (updating) return;
        KeyPopHistory.Snapshot snapshot = history.snapshot(); seenRevision = snapshot.revision;
        TreeSet<String> choices = new TreeSet<>(); for (KeyPopEvent event : snapshot.events) choices.add(event.item);
        String selected = (String)item.getSelectedItem();
        if (selected != null && !selected.equals(ALL_ITEMS)) choices.add(selected);
        List<String> existing = new ArrayList<>(); for (int i = 1; i < item.getItemCount(); i++) existing.add(item.getItemAt(i));
        if (!existing.equals(new ArrayList<>(choices))) {
            updating = true; item.removeAllItems(); item.addItem(ALL_ITEMS);
            for (String choice : choices) item.addItem(choice);
            item.setSelectedItem(selected == null ? ALL_ITEMS : selected); updating = false;
        }
        Instant now = historical ? snapshot.events.stream().map(event->event.time).max(Instant::compareTo).orElse(Instant.now()) : Instant.now();
        Instant since = period.getSelectedIndex() == 1 ? now.minusSeconds(900) : period.getSelectedIndex() == 2 ? now.minusSeconds(3600)
            : period.getSelectedIndex() == 3 ? now.atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant() : null;
        filtered = new ArrayList<>();
        for (KeyPopEvent event : snapshot.events) if (event.matches(search.getText(), (String)type.getSelectedItem(), (String)item.getSelectedItem(), since)) filtered.add(event);
        Map<String, List<KeyPopEvent>> byPlayer = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, List<KeyPopEvent>> byItem = new TreeMap<>();
        List<Object[]> eventRows = new ArrayList<>(); int keys = 0;
        for (KeyPopEvent event : filtered) {
            eventRows.add(new Object[] {event.time, event.player, event.kind.label, event.item});
            byPlayer.computeIfAbsent(event.player, k -> new ArrayList<>()).add(event);
            byItem.computeIfAbsent(event.item, k -> new ArrayList<>()).add(event);
            if (event.kind == KeyPopEvent.Kind.KEY) keys++;
        }
        replaceRows(events, eventRows);
        metrics[0].setText(DisplayFormat.formatInteger(filtered.size())); metrics[1].setText(DisplayFormat.formatInteger(keys));
        metrics[2].setText(DisplayFormat.formatInteger(byPlayer.size())); metrics[3].setText(DisplayFormat.formatInteger(byItem.size()));
        List<Object[]> playerRows = new ArrayList<>(), itemRows = new ArrayList<>();
        byPlayer.forEach((name, pops) -> {
            int[] counts = new int[KeyPopEvent.Kind.values().length]; Instant last = Instant.MIN;
            for (KeyPopEvent pop : pops) { counts[pop.kind.ordinal()]++; if (pop.time.isAfter(last)) last = pop.time; }
            playerRows.add(new Object[] {name, pops.size(), counts[0], counts[1], counts[2], counts[3], pops.size() * 100.0 / filtered.size(), last});
        });
        byItem.forEach((name, pops) -> {
            Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER); Instant last = Instant.MIN;
            for (KeyPopEvent pop : pops) { names.add(pop.player); if (pop.time.isAfter(last)) last = pop.time; }
            itemRows.add(new Object[] {name, pops.size(), names.size(), pops.size() * 100.0 / filtered.size(), last});
        });
        replaceRows(players, playerRows); replaceRows(items, itemRows);
        empty.setVisible(filtered.isEmpty());
        empty.setText(snapshot.events.isEmpty() ? (historical ? "No saved pops in this page." : "Waiting for pops · Start capture and join a fresh game connection.") : "No pops match these filters. Try Reset filters.");
        empty.setToolTipText(empty.getText());
        status.setText(DisplayFormat.formatInteger(filtered.size()) + " shown / " + DisplayFormat.formatInteger(snapshot.events.size()) + " retained · " + (historical ? "Historical page; time filters follow its latest pop" : "This app session")
            + (snapshot.discarded > 0 ? " · " + DisplayFormat.formatInteger(snapshot.discarded) + " older pops dropped" : ""));
        status.setToolTipText("Live view retains the latest " + DisplayFormat.formatInteger(KeyPopHistory.CAPACITY) + " events. Saved session history retains every pop; use Browse saved for older pages. CSV exports filtered events.");
    }

    private void installDrilldown(JTable table, boolean player) {
        table.setToolTipText("Double-click or press Enter to filter the event history.");
        Runnable drilldown = () -> {
            int row = table.getSelectedRow(); if (row < 0) return;
            String value = (String)table.getValueAt(row, 0);
            if (player) search.setText(value); else item.setSelectedItem(value);
            tabs.setSelectedIndex(0);
        };
        table.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "show-events");
        table.getActionMap().put("show-events", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { drilldown.run(); }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int row = table.rowAtPoint(e.getPoint()); if (row < 0) return;
                table.setRowSelectionInterval(row, row); drilldown.run();
            }
        });
    }

    void clearHistory() { history.clear(); refresh(); }

    void editFont(Font font) {
        for (JTable table : new JTable[] {events, players, items}) ContentStyle.tableFont(table, font, 0);
    }

    void exportCsv() {
        refresh();
        if (filtered.isEmpty()) { JOptionPane.showMessageDialog(this, "No matching events to export."); return; }
        List<KeyPopEvent> export = new ArrayList<>(filtered);
        JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File("keypops-" + LocalDate.now() + ".csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        if (Files.exists(path) && JOptionPane.showConfirmDialog(this, "Replace " + path.getFileName() + "?", "Export CSV", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        status.setText("Exporting " + DisplayFormat.formatInteger(export.size()) + " events…");
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws IOException { writeCsv(path, export); return null; }
            protected void done() {
                try { get(); status.setText("Exported " + DisplayFormat.formatInteger(export.size()) + " events to " + path.getFileName()); }
                catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    JOptionPane.showMessageDialog(KeyPopDashboard.this, "Could not export: " + cause.getMessage(), "Export failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    static void writeCsv(Path path, List<KeyPopEvent> events) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("Timestamp (UTC),Player,Type,Dungeon / item\r\n");
            for (KeyPopEvent event : events) writer.write(event.csvLine());
        }
    }

    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text); button.addActionListener(e -> action.run()); return button;
    }
    private static JPanel labeled(String text, JComponent control, String name) {
        control.setName(name); control.getAccessibleContext().setAccessibleName(text);
        JPanel panel = new JPanel(new BorderLayout(0, 4)); JLabel label = new JLabel(text); label.setLabelFor(control);
        ContentStyle.font(label, ContentStyle.metadata(ContentStyle.body()));
        panel.add(label, BorderLayout.NORTH); panel.add(control); return panel;
    }
    static void onChange(JTextField field, Runnable action) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { action.run(); }
            public void removeUpdate(DocumentEvent e) { action.run(); }
            public void changedUpdate(DocumentEvent e) { action.run(); }
        });
    }
    private static DefaultTableModel model(String[] names, Class<?>... types) {
        return new DefaultTableModel(names, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
            @Override public Class<?> getColumnClass(int column) { return types[column]; }
        };
    }
    private static void replaceRows(JTable table, List<Object[]> rows) {
        DefaultTableModel model = (DefaultTableModel)table.getModel();
        Object[] selected = null;
        if (table.getSelectedRow() >= 0) {
            int row = table.convertRowIndexToModel(table.getSelectedRow());
            int columns = model.getColumnClass(0) == Instant.class ? model.getColumnCount() : 1;
            selected = new Object[columns];
            for (int col = 0; col < columns; col++) selected[col] = model.getValueAt(row, col);
        }
        // One change event per refresh avoids sorting after every captured row.
        model.getDataVector().clear();
        for (Object[] row : rows) model.getDataVector().add(new Vector<>(Arrays.asList(row)));
        model.fireTableDataChanged();
        if (selected != null) {
            for (int row = 0; row < rows.size(); row++) {
                if (Arrays.equals(selected, Arrays.copyOf(rows.get(row), selected.length))) {
                    int view = table.convertRowIndexToView(row); table.setRowSelectionInterval(view, view); break;
                }
            }
        }
    }
    private static JTable table(DefaultTableModel model, String name) {
        JTable table = new JTable(model) {
            @Override public boolean getScrollableTracksViewportWidth() { return getParent() != null && getPreferredSize().width < getParent().getWidth(); }
        };
        table.setName(name); ContentStyle.table(table);
        table.setAutoCreateRowSorter(true); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        for (int i = 0; i < model.getColumnCount(); i++) {
            int width = model.getColumnClass(i) == Instant.class ? 175 : model.getColumnClass(i) == String.class ? (model.getColumnName(i).equals("Dungeon / item") ? 270 : model.getColumnName(i).equals("Type") ? 86 : 160) : 86;
            table.getColumnModel().getColumn(i).setPreferredWidth(width);
        }
        table.setDefaultRenderer(String.class, new ContentStyle.Cell() {
            @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                if ("Player".equals(t.getColumnName(column))) setFont(ContentStyle.emphasis(t.getFont()));
                return this;
            }
        });
        for (int i = 0; i < model.getColumnCount(); i++) if ("Type".equals(model.getColumnName(i))) {
            table.getColumnModel().getColumn(i).setCellRenderer(new ContentStyle.Badge() {
                protected Color badgeColor(Object value) {
                    switch (String.valueOf(value)) {
                        case "Key": return ContentStyle.color("amber");
                        case "Rune": return ContentStyle.color("violet");
                        case "Vial": return ContentStyle.color("mint");
                        case "Inc": return ContentStyle.color("rose");
                        default: return ContentStyle.color("muted");
                    }
                }
            });
        }
        table.setDefaultRenderer(Instant.class, new ContentStyle.Cell() {
            @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                setFont(ContentStyle.metadata(t.getFont()));
                if (!selected) setForeground(ContentStyle.color("muted"));
                return this;
            }
            @Override protected void setValue(Object value) {
                setText(DisplayFormat.formatTimestamp((Instant)value));
                setToolTipText(value == null ? null : getText() + " (" + DisplayFormat.timestampZoneLabel() + ")");
            }
        });
        table.setDefaultRenderer(Integer.class, new ContentStyle.Cell() {
            { setHorizontalAlignment(SwingConstants.RIGHT); }
            @Override protected void setValue(Object value) {
                setText(value == null ? DisplayFormat.UNAVAILABLE : DisplayFormat.formatInteger(((Number)value).longValue()));
            }
        });
        table.setDefaultRenderer(Double.class, new ContentStyle.Cell() {
            private double percent;
            { setHorizontalAlignment(SwingConstants.RIGHT); setOpaque(false); }
            @Override protected void setValue(Object value) {
                percent = value == null ? 0 : ((Number)value).doubleValue();
                setText(value == null ? DisplayFormat.UNAVAILABLE : DisplayFormat.formatPercentage(percent, 1));
                if (!Double.isFinite(percent)) percent = 0;
            }
            @Override protected void paintComponent(Graphics g) {
                g.setColor(getBackground()); g.fillRect(0, 0, getWidth(), getHeight());
                Color accent = UIManager.getColor("Component.accentColor");
                if (accent == null) accent = UIManager.getColor("Table.selectionBackground");
                g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 65));
                g.fillRoundRect(3, 5, (int)((getWidth() - 6) * Math.min(100, percent) / 100), getHeight() - 10, 6, 6);
                super.paintComponent(g);
            }
        });
        return table;
    }
}
