package tomato.gui.security;

import packets.packetcapture.logger.ActivityJournal;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.activity.SnapshotRefresh;
import tomato.gui.activity.RunDurationUnit;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.realmshark.ParseDungeon;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.table.TableModel;
import javax.swing.table.TableStringConverter;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

/** Select the same dungeon visits as Runs, then inspect their last observed player loadouts. */
final class InspectRunsPanel extends JPanel {
    private final DiscoveryLog log;
    private final ParsePanelGUI roster;
    private final JPanel rosterHost = new JPanel(new BorderLayout());
    private final JComboBox<RunDurationUnit> durationUnit = new JComboBox<>(RunDurationUnit.values());
    private final List<ActivityJournal.Visit> visits = new ArrayList<>();
    private final AbstractTableModel model = new AbstractTableModel() {
        private final String[] columns = {"Entered", "Dungeon", "Players", "Status", "Damage", "DPS", "Observed minutes"};
        public int getRowCount() { return visits.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int column) { return column == 6 ? unit().column() : columns[column]; }
        public Class<?> getColumnClass(int column) { return column == 0 ? Instant.class : column == 2 ? Integer.class : column == 4 ? Long.class : column >= 5 ? Double.class : String.class; }
        public Object getValueAt(int row, int column) {
            ActivityJournal.Visit visit = visits.get(row);
            switch (column) {
                case 0: return Instant.ofEpochMilli(visit.started);
                case 1: return visit.map;
                case 2: return visit.inspectedPlayerCount;
                case 3: return visit.runStatus();
                case 4: return visit.damageTracked ? visit.totalDamage : null;
                case 5: return visit.dps(visit.damageTracked ? visit.totalDamage : null);
                default: return unit().value(visit.observedMillis());
            }
        }
    };
    private final JTable table = new JTable(model);
    private final TableRowSorter<AbstractTableModel> sorter = new TableRowSorter<>(model);
    private final JTextField search = new JTextField(18);
    private final JCheckBox record = new JCheckBox("Record");
    private final JLabel summary = new JLabel("No dungeon runs recorded. Enable Record and start capture.");
    private final SnapshotRefresh<DiscoveryLog.ActivitySnapshot> refresh = new SnapshotRefresh<>();
    private final javax.swing.Timer timer;
    private DiscoveryLog.ActivityRevision revision;
    private String selectedId = "";
    private String loadedId = "";
    private boolean applying;

    InspectRunsPanel(DiscoveryLog log, ParsePanelGUI roster) {
        super(new BorderLayout(0, 8));
        this.log = log; this.roster = roster;
        setName("inspect-runs");
        ContentStyle.table(table, ContentStyle.Density.DENSE);
        table.setName("inspect-runs-table");
        table.getAccessibleContext().setAccessibleName("Runs to inspect");
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowSorter(sorter);
        sorter.setStringConverter(new TableStringConverter() {
            @Override public String toString(TableModel model, int row, int column) {
                Object value = model.getValueAt(row, column);
                return value instanceof Instant ? DisplayFormat.formatTimestamp((Instant) value) + "\n" + value : Objects.toString(value, "");
            }
        });
        sorter.setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.DESCENDING)));
        table.getColumnModel().getColumn(0).setCellRenderer(new ContentStyle.Cell() {
            @Override protected void setValue(Object value) { setText(DisplayFormat.formatTimestamp((Instant) value)); }
        });
        for (int column : new int[]{4, 5, 6}) {
            final int c = column;
            table.getColumnModel().getColumn(c).setCellRenderer(new ContentStyle.Cell() {
                @Override protected void setValue(Object value) {
                    setText(value == null ? DisplayFormat.UNAVAILABLE : c == 4 ? DisplayFormat.formatExact((Number)value)
                            : c == 6 ? unit().format(((Number)value).doubleValue()) : DisplayFormat.formatNumber(((Number)value).doubleValue(), 0, 1));
                }
            });
        }
        int[] widths = {160, 190, 70, 220, 110, 100, 130};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        JPanel controls = ContentStyle.controls();
        JLabel label = new JLabel("Search runs"); label.setLabelFor(search);
        search.setName("inspect-runs-search"); search.getAccessibleContext().setAccessibleName("Search dungeon runs");
        controls.add(label); controls.add(search);
        record.setSelected(log.isEnabled());
        record.setToolTipText("Record shared run history and player snapshots while capture is running.");
        record.addActionListener(e -> { log.setEnabled(record.isSelected()); requestRefresh(); });
        controls.add(record);
        durationUnit.setName("inspect-run-duration-unit");durationUnit.getAccessibleContext().setAccessibleName("Run duration units");
        controls.add(durationUnit);
        durationUnit.addActionListener(e -> {
            table.getColumnModel().getColumn(table.convertColumnIndexToView(6)).setHeaderValue(unit().column());table.getTableHeader().repaint();
            applying = true;
            try { model.fireTableDataChanged(); restoreSelection(); }
            finally { applying = false; }
            showSelection();
        });
        JPanel runs = new JPanel(new BorderLayout(0, 6));
        runs.add(controls, BorderLayout.NORTH);
        JScrollPane runScroll = ContentStyle.tableScroll(table, 3);
        runScroll.setPreferredSize(new Dimension(650, 135));
        runs.add(runScroll);
        summary.setName("inspect-runs-summary");
        summary.setFont(ContentStyle.metadata(ContentStyle.body()));
        runs.add(summary, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, runs, rosterHost);
        split.setBorder(null); split.setResizeWeight(0);
        runs.setMinimumSize(new Dimension(0, 100)); rosterHost.setMinimumSize(new Dimension(0, 150));
        add(split);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!applying && !e.getValueIsAdjusting()) selectionChanged();
        });
        timer = new javax.swing.Timer(1000, e -> requestRefresh());
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
            if (isShowing()) { timer.start(); requestRefresh(); }
            else { timer.stop(); refresh.invalidate(); }
        });
    }

    void showRoster() {
        showSelection();
        rosterHost.add(roster);
        rosterHost.revalidate();
        requestRefresh();
    }
    void readOnly() { record.setVisible(false); }

    private void filter() {
        String text = search.getText().trim();
        sorter.setRowFilter(text.isEmpty() ? null : RowFilter.regexFilter("(?i)" + Pattern.quote(text)));
        if (!applying) selectionChanged();
    }

    private ActivityJournal.Visit selectedVisit() {
        int row = table.getSelectedRow();
        return row < 0 ? null : visits.get(table.convertRowIndexToModel(row));
    }

    private void selectionChanged() {
        ActivityJournal.Visit visit = selectedVisit();
        if (visit != null) selectedId = visit.id;
        showSelection();
        if (visit != null) requestRefresh();
    }

    private void showSelection() {
        ActivityJournal.Visit visit = selectedVisit();
        boolean loaded = visit != null && visit.id.equals(loadedId);
        if (loaded) roster.showRun(visit);
        else roster.showRun(visit == null ? "" : visit.id, Collections.emptyList());
        String text = visit == null ? (visits.isEmpty() ? "No dungeon runs recorded. Enable Record and start capture."
                : "Select a dungeon run to inspect its players.")
                : !loaded ? "Loading player snapshots…"
                : visit.inspectedPlayers.isEmpty() ? "No player snapshots saved for this run. New captures record player loadouts."
                : visit.map + " · " + visit.inspectedPlayers.size() + (visit.inspectedPlayers.size() == 1 ? " player" : " players")
                    + " · Last captured gear and base stats";
        summary.setText(text);
        summary.setToolTipText(visit == null ? text : visit.runStatus() + " · "
                + (visit.completionEvidence.isEmpty() ? "Completion not observed" : visit.completionEvidence)
                + " · " + (visit.endReason.isEmpty() ? visit.status : visit.endReason));
    }

    private void requestRefresh() {
        if (!isShowing()) return;
        String id = selectedId;
        DiscoveryLog.ActivityRevision known = revision;
        refresh.request(id, () -> log.activityView(ActivityJournal.View.INSPECT, id, known), this::apply,
                error -> summary.setText("Could not load run history; retrying on the next refresh."));
    }

    private void apply(DiscoveryLog.ActivitySnapshot snapshot) {
        applying = true;
        try {
            revision = snapshot.revision;
            loadedId = snapshot.view.selectedVisit;
            selectedId = loadedId;
            record.setSelected(snapshot.enabled);
            table.clearSelection();
            visits.clear();
            for (int i = snapshot.view.data.visits.size() - 1; i >= 0; i--) {
                ActivityJournal.Visit visit = snapshot.view.data.visits.get(i);
                if (ParseDungeon.isDungeon(visit.map)) visits.add(visit);
            }
            model.fireTableDataChanged();
            restoreSelection();
            showSelection();
        } finally { applying = false; }
    }
    private void restoreSelection() {
        for (int i = 0; i < visits.size(); i++) if (visits.get(i).id.equals(selectedId)) {
            int view = table.convertRowIndexToView(i);
            if (view >= 0) table.setRowSelectionInterval(view, view);
            break;
        }
    }
    private RunDurationUnit unit() { return (RunDurationUnit)durationUnit.getSelectedItem(); }
}
