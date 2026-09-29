package tomato.gui.security;

import packets.packetcapture.logger.ActivityJournal;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.activity.SnapshotRefresh;
import tomato.gui.activity.RunDurationUnit;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.modern.CollectionControl;
import tomato.realmshark.ParseDungeon;
import tomato.gui.history.HistoryTables;
import tomato.gui.history.WrapRow;
import tomato.gui.kit.Banner;
import tomato.gui.kit.ColumnKind;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.KitTables;
import tomato.gui.kit.Tokens;
import tomato.gui.history.ViewState;
import tomato.gui.history.ViewStateStore;
import tomato.gui.roster.RosterViewState;
import tomato.history.SessionStore;

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
    /** Archive visit filters are global; the roster owns only selected-run display facets. */
    static tomato.gui.activity.ActivityArchiveClient archiveClient(java.nio.file.Path scratch) {
        return new tomato.gui.activity.ActivityArchiveClient(tomato.gui.activity.ActivityPanel.Mode.RUNS,scratch,
                new tomato.gui.activity.ActivityArchiveClient.VisitRenderer() {
                    private ParsePanelGUI savedRoster;
                    private String session="";
                    public JComponent render(ActivityJournal.Visit visit,tomato.history.archive.ArchiveRow.Ref origin) {
                        if(savedRoster==null)savedRoster=new ParsePanelGUI(false);
                        // ParsePanel's local row keys use visit ID. Clear them when the source session changes.
                        if(!session.equals(origin.session))savedRoster.showRun("",Collections.emptyList());
                        // Provenance carries the exact saved session + visit (INS-2); names/times are never used.
                        session=origin.session;savedRoster.showRun(tomato.gui.activity.ActivityRoutes.reference(origin.session,visit.id),visit);return savedRoster;
                    }
                });
    }
    private final DiscoveryLog log;
    private final ParsePanelGUI roster;
    private final JPanel rosterHost = new JPanel(new BorderLayout());
    /** The Observed column's unit: a "Duration unit ▸" radio group in the row's ⋯ (display only). */
    private RunDurationUnit unit = RunDurationUnit.values()[0];
    private final Map<RunDurationUnit, JRadioButtonMenuItem> unitItems = new EnumMap<>(RunDurationUnit.class);
    private final FilterBar filterBar = new FilterBar("inspect-runs");
    private final JPanel statusLine = ContentStyle.controls();
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
    private final CollectionControl record;
    private final JLabel summary = new JLabel("No dungeon runs recorded.");
    private final SnapshotRefresh<DiscoveryLog.ActivitySnapshot> refresh = new SnapshotRefresh<>();
    private final javax.swing.Timer timer;
    private DiscoveryLog.ActivityRevision revision;
    private String selectedId = "";
    private String loadedId = "";
    private boolean applying;
    private RosterViewState liveState;
    private final JPanel stateHost=new JPanel(new BorderLayout());
    private final Banner stateBanner=new Banner("inspect-live-runs-view-state");
    private boolean restoringState, restorePending, selectionRequired;
    private boolean rosterActive;

    InspectRunsPanel(DiscoveryLog log, ParsePanelGUI roster) {
        super(new BorderLayout(0, 8));
        this.log = log; this.roster = roster;
        record = new CollectionControl(log, this::requestRefresh);
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
        ColumnKind[] kinds = {ColumnKind.DATE_TIME, ColumnKind.DUNGEON, ColumnKind.COUNT, ColumnKind.TEXT, ColumnKind.NUMBER, ColumnKind.NUMBER, ColumnKind.DURATION};
        Map<String, ColumnKind> byId = new HashMap<>();
        for (int i = 0; i < kinds.length; i++){table.getColumnModel().getColumn(i).setIdentifier("column-"+i);byId.put("column-"+i,kinds[i]);}
        HistoryTables.kinds(table, byId);
        // Simple reads "12 min ago" (the absolute time in the tooltip); Analyst, the model, sorting, search and Copy stay absolute.
        KitTables.relativeTime(table, "column-0", DisplayModeModel.application(), KitTables::epoch, java.time.ZoneId.systemDefault().getId());
        // One filter row: the search, and the duration unit in ⋯ (P6b); Party lends this row the Scope chip on the Runs tab.
        JLabel label = new JLabel("Search runs"); label.setLabelFor(search);
        search.setName("inspect-runs-search"); search.getAccessibleContext().setAccessibleName("Search dungeon runs");
        filterBar.search(new WrapRow(label, search));
        JMenu units = filterBar.overflow().submenu("Duration unit");
        units.setName("inspect-run-duration-unit"); units.getAccessibleContext().setAccessibleName("Run duration units");
        ButtonGroup unitGroup = new ButtonGroup();
        for (RunDurationUnit choice : RunDurationUnit.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(choice.toString(), choice == unit);
            item.setName("inspect-run-duration-unit-" + choice.name().toLowerCase(Locale.ROOT));
            item.addActionListener(e -> selectUnit(choice));
            unitGroup.add(item); units.add(item); unitItems.put(choice, item);
        }
        // Collection is a status line under the filter row, as on Timeline; saved (read-only) runs have none.
        statusLine.setName("inspect-runs-status-line"); statusLine.add(record); statusLine.setVisible(record.isVisible());
        JPanel top = new JPanel(); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        filterBar.setAlignmentX(LEFT_ALIGNMENT); statusLine.setAlignmentX(LEFT_ALIGNMENT);
        top.add(filterBar); top.add(statusLine);
        JPanel runs = new JPanel(new BorderLayout(0, 6));
        runs.add(top, BorderLayout.NORTH);
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
        // Its saved view state is in the page's ⋯ (SecurityGUI); this line shows its status only while it is a failure.
        stateBanner.setTone(Tokens.Tone.WARN);stateBanner.setVisible(false);stateHost.add(stateBanner);stateHost.setVisible(false);
        add(stateHost,BorderLayout.SOUTH);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!applying && !restoringState && !e.getValueIsAdjusting()) selectionChanged();
        });
        timer = new javax.swing.Timer(1000, e -> requestRefresh());
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
            if (isShowing()) { timer.start(); requestRefresh(); }
            else { timer.stop(); refresh.invalidate(); }
        });
    }

    void showRoster() {
        rosterActive = true;
        rosterHost.add(roster);
        showSelection();
        rosterHost.revalidate();
        requestRefresh();
    }
    /** Release before another tab restores or reparents the shared roster. */
    void releaseRoster() {
        rosterActive = false;
        timer.stop();
        refresh.invalidate();
    }
    void readOnly() { record.setVisible(false); statusLine.setVisible(false); }
    /** The runs table's filter row (the Scope chip's host on Party's Runs tab). */
    FilterBar filterBar() { return filterBar; }
    void bindViewState(ViewStateStore store){
        if(liveState!=null||log.isHistorical())return;
        liveState=new RosterViewState(store,"inspect-live-runs",this::captureLiveState,this::prepareLiveState);
        liveState.onStatus(this::viewStateChanged);viewStateChanged();RosterViewState.listenTable(table,this::rememberLiveState);
    }
    boolean viewStateBound(){return liveState!=null;}
    void resetViewState(){if(liveState!=null)liveState.resetSaved();}
    /** The saved view's status as a warning line while it is a failure. */
    private void viewStateChanged(){
        boolean problem=liveState.statusProblem();stateBanner.setText(problem?liveState.statusText():"");stateBanner.setVisible(problem);
        if(stateHost.isVisible()!=problem){stateHost.setVisible(problem);revalidate();}
    }
    java.util.concurrent.CompletionStage<util.PreferencesStore.SaveResult> saveViewState(){
        if(liveState==null)throw new IllegalStateException("Inspect Runs live state is not bound");return liveState.save();
    }
    private void rememberLiveState(){if(liveState!=null&&!applying&&!restoringState)liveState.changed();}
    private Map<String,String> captureLiveState(){
        Map<String,String> values=new LinkedHashMap<>();values.put("runsVersion","1");values.put("search",search.getText());
        values.put("unit",unit().name());values.put("visit",selectedId);values.put("requireSelection",Boolean.toString(selectionRequired));
        RosterViewState.captureTable(values,table);values.put("layout",SessionStore.JSON.toJson(HistoryTables.columnState(table,"Live")));return values;
    }
    private Runnable prepareLiveState(Map<String,String> values){
        RosterViewState.number(values,"runsVersion",1,1,1);String text=values.getOrDefault("search",""),id=values.getOrDefault("visit","");
        RunDurationUnit unit=RunDurationUnit.valueOf(values.getOrDefault("unit",RunDurationUnit.values()[0].name()));
        int required=RosterViewState.option(values,"requireSelection",0,"false","true");Runnable columns=RosterViewState.prepareTable(values,table);
        ViewState.Table layout=values.containsKey("layout")?SessionStore.JSON.fromJson(values.get("layout"),ViewState.Table.class):null;
        if(layout!=null){layout=new ViewState.Table(layout.preset,layout.columns);Set<String> ids=new HashSet<>();for(ViewState.Column c:layout.columns)ids.add(c.id);
            for(int c=0;c<model.getColumnCount();c++)if(!ids.remove("column-"+c))throw new IllegalArgumentException("Missing run column");if(!ids.isEmpty())throw new IllegalArgumentException("Unknown run column");
            if(layout.columns.stream().noneMatch(c->c.visible))throw new IllegalArgumentException("Keep a visible column");}
        final ViewState.Table restoredLayout=layout;
        return ()->{restoringState=true;applying=true;try{
            search.setText(text);selectUnit(unit);columns.run();if(restoredLayout!=null)HistoryTables.applyColumns(table,restoredLayout);
            selectedId=id;restorePending=!id.isEmpty();selectionRequired=required==1;
        }finally{applying=false;restoringState=false;}};
    }
    @Override public void removeNotify(){if(liveState!=null)liveState.save();timer.stop();refresh.invalidate();super.removeNotify();}
    /** Same model refresh used on showing; usable without a native peer in bounded EDT checks. */
    void refresh(){requestRefresh();}

    private void filter() {
        String text = search.getText().trim();
        sorter.setRowFilter(text.isEmpty() ? null : RowFilter.regexFilter("(?i)" + Pattern.quote(text)));
        if (!applying&&!restoringState) selectionChanged();
        rememberLiveState();
    }

    private ActivityJournal.Visit selectedVisit() {
        int row = table.getSelectedRow();
        return row < 0 ? null : visits.get(table.convertRowIndexToModel(row));
    }

    private void selectionChanged() {
        ActivityJournal.Visit visit = selectedVisit();
        if (visit != null) {selectedId = visit.id;selectionRequired=false;restorePending=false;}
        showSelection();
        if (visit != null) requestRefresh();
        rememberLiveState();
    }

    private void showSelection() {
        if (restoringState || !rosterActive || roster.getParent() != rosterHost) return;
        ActivityJournal.Visit visit = selectedVisit();
        boolean loaded = visit != null && visit.id.equals(loadedId);
        if (loaded) roster.showRun(visit);
        else roster.showRun(visit == null ? "" : visit.id, Collections.emptyList());
        String text = visit == null ? (visits.isEmpty() ? (log.isHistorical() ? "No dungeon runs saved in this scope; recording coverage unknown." : "No dungeon runs recorded. Enable gameplay & diagnostics collection and start capture.")
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
        if (restoringState || !rosterActive || (isDisplayable()&&!isShowing())) return;
        record.refresh();
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
            record.refresh();
            table.clearSelection();
            visits.clear();
            for (int i = snapshot.view.data.visits.size() - 1; i >= 0; i--) {
                ActivityJournal.Visit visit = snapshot.view.data.visits.get(i);
                if (ParseDungeon.isDungeon(visit.map)) visits.add(visit);
            }
            if(restorePending){
                boolean found=false;for(ActivityJournal.Visit visit:visits)if(visit.id.equals(selectedId)){found=true;break;}
                selectionRequired=!found;if(!found)selectedId="";restorePending=false;
            }else if(!selectionRequired)selectedId=loadedId;
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
    private RunDurationUnit unit() { return unit; }
    RunDurationUnit durationUnit() { return unit; }
    /** Shows the Observed column in {@code next}: the radio, the header and the values; selection and the saved state follow. */
    private void selectUnit(RunDurationUnit next) {
        unitItems.get(next).setSelected(true);
        if (next == unit) return;
        unit = next;
        int view=table.convertColumnIndexToView(6);if(view>=0)table.getColumnModel().getColumn(view).setHeaderValue(unit.column());table.getTableHeader().repaint();
        boolean wasApplying = applying;
        applying = true;
        try { model.fireTableDataChanged(); restoreSelection(); }
        finally { applying = wasApplying; }
        showSelection();
        rememberLiveState();
    }
}
