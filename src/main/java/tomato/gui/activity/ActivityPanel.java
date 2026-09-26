package tomato.gui.activity;

import packets.packetcapture.logger.ActivityJournal;
import packets.packetcapture.logger.DiscoveryLog;
import javax.swing.*;
import javax.swing.table.*;
import javax.swing.event.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.modern.CollectionControl;
import tomato.realmshark.ParseDungeon;
import tomato.gui.history.HistoryTables;
import tomato.gui.history.ViewState;
import tomato.gui.history.ViewStateStore;
import tomato.gui.roster.RosterViewState;
import tomato.history.SessionStore;

/** Product-facing history modules sharing the capture journal, independent of diagnostic tables. */
public final class ActivityPanel extends JPanel {
    /** EDT factory used by the shell; each mode has an independent saved workspace. */
    public static JComponent workspace(DiscoveryLog log,Mode mode) {
        ActivityPanel live=new ActivityPanel(log,mode);
        live.bindViewState(ViewStateStore.application());
        tomato.history.SessionStore store=tomato.history.AppHistory.store();
        return store==null?live:workspace(store,live,mode,Paths.get(System.getProperty("java.io.tmpdir"),"realmshark-activity-archive"),tomato.gui.history.ViewStateStore.application());
    }
    public static tomato.gui.history.ArchiveWorkspace<ActivityQueries.Row,ActivityQueries.Filters,ActivityQueries.Sort> workspace(
            tomato.history.SessionStore store,JComponent live,Mode mode,Path scratch,tomato.gui.history.ViewStateStore states) {
        if(live instanceof ActivityPanel)((ActivityPanel)live).bindViewState(states);
        String name=mode==Mode.RUNS?"runs":mode==Mode.TIMELINE?"timeline":"combat";
        return tomato.gui.history.SessionPanel.queried(store,name,live,new ActivityArchiveClient(mode,scratch),states);
    }
    public static tomato.gui.history.SessionPanel.Loaded runsHistory(tomato.history.SessionStore store,String scope,int page,String query)throws java.io.IOException {
        return savedHistory(store,scope,page,query,Mode.RUNS);
    }
    public static tomato.gui.history.SessionPanel.Loaded timelineHistory(tomato.history.SessionStore store,String scope,int page,String query)throws java.io.IOException {
        return savedHistory(store,scope,page,query,Mode.TIMELINE);
    }
    public static tomato.gui.history.SessionPanel.Loaded combatHistory(tomato.history.SessionStore store,String scope,int page,String query)throws java.io.IOException {
        return savedHistory(store,scope,page,query,Mode.COMBAT);
    }
    private static tomato.gui.history.SessionPanel.Loaded savedHistory(tomato.history.SessionStore store,String scope,int page,String query,Mode mode)throws java.io.IOException {
        ActivityJournal.State saved=new ActivityJournal.State();boolean more;String description;
        if(mode==Mode.TIMELINE){
            tomato.gui.history.HistoryPage<ActivityJournal.Entry> entries=tomato.gui.history.HistoryPage.read(store,scope,"timeline",ActivityJournal.Entry.class,page,query,
                    e->e.map+" "+e.kind+" "+e.detail+" "+tomato.history.SessionStore.JSON.toJson(e.values));
            saved.entries.addAll(entries.values);saved.entries.sort(Comparator.comparingLong(e->e.time));more=entries.more();description=entries.description();
            Set<String> visits=new HashSet<>();for(ActivityJournal.Entry entry:entries.values)visits.add(entry.visitId);
            store.read(scope,"runs",ActivityJournal.Visit.class,(session,visit)->{if(visits.contains(visit.id))saved.visits.add(visit.summary());});
            saved.visits.sort(Comparator.comparingLong(v->v.started));
        }else{
            tomato.gui.history.HistoryPage<ActivityJournal.Visit> visits=tomato.gui.history.HistoryPage.read(store,scope,"runs",ActivityJournal.Visit.class,page,100,query,
                    v->v.map,v->mode==Mode.COMBAT||ParseDungeon.isDungeon(v.map));
            saved.visits.addAll(visits.values);saved.visits.sort(Comparator.comparingLong(v->v.started));more=visits.more();description=visits.description();
        }
        return new tomato.gui.history.SessionPanel.Loaded(()->{
            ActivityPanel view=new ActivityPanel(DiscoveryLog.historyView(saved),mode);view.record.setVisible(false);return view;
        },more,description);
    }
    public enum Mode { RUNS, TIMELINE, COMBAT }
    private final DiscoveryLog log;
    private final Mode mode;
    private final JTextField search=new JTextField(18);
    private final JComboBox<VisitChoice> visitPicker=new JComboBox<>();
    private final JComboBox<String> kind=new JComboBox<>(new String[]{"All activities","Party","Exalt","Item / ability","Inventory","Equipment","Resources","Capture","Ownership"});
    private final JCheckBox freeze=new JCheckBox("Pause this view");
    private final CollectionControl record;
    private final JComboBox<RunDurationUnit> durationUnit=new JComboBox<>(RunDurationUnit.values());
    private final JLabel summary=new JLabel(" "), saved=new JLabel(" ");
    private final JTextArea detail=new JTextArea();
    private final List<Object[]> rows=new ArrayList<>();
    private final List<Object> items=new ArrayList<>();
    private final JTable table;
    private final AbstractTableModel model;
    private final TableRowSorter<TableModel> sorter;
    private final CombatTimelineChart chart=new CombatTimelineChart();
    private final JTabbedPane combatViews=new JTabbedPane();
    private ActivityJournal.State state=new ActivityJournal.State();
    private final javax.swing.Timer timer;
    private boolean refreshing;
    private boolean tableInitialized;
    private final SnapshotRefresh<ViewUpdate> snapshots=new SnapshotRefresh<>();
    private DiscoveryLog.ActivitySnapshot displayed;
    private volatile DiscoveryLog.ActivityRevision revision;
    private boolean displayedEnabled, exporting;
    private int visitCount, eventCount;
    private Locale presentationLocale;
    private ZoneId presentationZone;
    private ActivityQueries.Filters runFilters=new ActivityQueries.Filters();
    private final JButton outcomeFilter=new JButton(), evidenceFilter=new JButton();
    private final JComboBox<ActivityQueries.Presence> issuesFilter=new JComboBox<>(ActivityQueries.Presence.values()), gapsFilter=new JComboBox<>(ActivityQueries.Presence.values());
    private final JTextField minimumDuration=new JTextField(6), maximumDuration=new JTextField(6);
    private final JPanel stateHost=new JPanel(new BorderLayout());
    private RosterViewState liveState;
    private boolean restoringState, restorePending, selectionRequired;
    private String restoredVisit="", restoredRow="";
    private static final int RUN_DPS=11;

    public ActivityPanel(DiscoveryLog log, Mode mode) {
        super(new BorderLayout(0,8)); this.log=log; this.mode=mode; setName("activity-"+mode.name().toLowerCase(Locale.ROOT));
        record=new CollectionControl(log,this::refresh);
        String[] columns=mode==Mode.RUNS ? new String[]{"Dungeon","Entered","Observed minutes","Outcome","Coverage","Progress increase","Use requests","Capture issues","Timing gaps","Evidence source","Damage","DPS"}
            : mode==Mode.TIMELINE ? new String[]{"Time","Area","Activity","Summary","Meaning"}
            : new String[]{"Condition","Active seconds","Observed seconds","Uptime %"};
        model=new AbstractTableModel() {
            public int getRowCount(){return rows.size();} public int getColumnCount(){return columns.length;}
            public String getColumnName(int c){return mode==Mode.RUNS&&c==2 ? unit().column() : columns[c];} public Object getValueAt(int r,int c){return rows.get(r)[c];}
            public Class<?> getColumnClass(int c){for(Object[] row:rows)if(row[c]!=null)return row[c].getClass();return Object.class;}
        };
        table=new JTable(model); table.setName("activity-table");
        ContentStyle.table(table,ContentStyle.Density.DENSE);table.getAccessibleContext().setAccessibleName("Retained "+mode.name().toLowerCase(Locale.ROOT)+" evidence");
        DefaultTableCellRenderer numbers=new ContentStyle.Cell(){
            {setHorizontalAlignment(SwingConstants.RIGHT);}
            protected void setValue(Object value){setText(DisplayFormat.formatExact((Number)value));}
        };
        table.setDefaultRenderer(Number.class,numbers);table.setDefaultRenderer(Double.class,numbers);
        if(mode==Mode.RUNS)for(int column:new int[]{2,RUN_DPS}){
            final int c=column;
            table.getColumnModel().getColumn(c).setCellRenderer(new ContentStyle.Cell(){
                protected void setValue(Object value){setText(displayValue(value,c));}
            });
        }
        if(mode==Mode.COMBAT)table.getColumnModel().getColumn(3).setCellRenderer(new ContentStyle.Cell(){
            {setHorizontalAlignment(SwingConstants.RIGHT);}
            protected void setValue(Object value){setText(displayValue(value,3));}
        });
        else table.getColumnModel().getColumn(mode==Mode.RUNS?1:0).setCellRenderer(new ContentStyle.Cell(){
            protected void setValue(Object value){
                setText(displayValue(value,mode==Mode.RUNS?1:0));
                setToolTipText(value==null?null:getText()+" ("+DisplayFormat.timestampZoneLabel()+")");
            }
        });
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        sorter=new TableRowSorter<>(model); table.setRowSorter(sorter);
        sorter.setStringConverter(new TableStringConverter(){
            public String toString(TableModel model,int row,int column){
                Object value=model.getValueAt(row,column);
                String shown=displayValue(value,column),raw=value==null?"":value.toString();
                return shown.equals(raw)?shown:shown+"\n"+raw;
            }
        });
        for(int c=0;c<columns.length;c++)table.getColumnModel().getColumn(c).setPreferredWidth(c==1?190:c==0?150:160);
        for(int c=0;c<columns.length;c++)table.getColumnModel().getColumn(c).setIdentifier("column-"+c);
        if(mode==Mode.RUNS){int[] widths={145,150,85,175,150};for(int c=0;c<widths.length;c++){
            table.getColumnModel().getColumn(c).setPreferredWidth(widths[c]);table.getColumnModel().getColumn(c).setWidth(widths[c]);}}
        if(mode==Mode.TIMELINE){table.getColumnModel().getColumn(3).setPreferredWidth(320);table.getColumnModel().getColumn(4).setPreferredWidth(370);}
        JPanel top=new JPanel(); top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));
        JPanel controls=ContentStyle.controls();controls.setAlignmentX(LEFT_ALIGNMENT);
        search.setName("activity-search");search.getAccessibleContext().setAccessibleName("Search "+mode.name().toLowerCase(Locale.ROOT));
        search.putClientProperty("JTextField.placeholderText","Search this view");
        search.setToolTipText("Search this module; text is matched literally"); controls.add(labeled("Search",search));
        displayedEnabled=record.isSelected();
        JButton export=new JButton("Export displayed history (unfiltered)"); export.addActionListener(e->export());
        export.setToolTipText("Export the displayed history revision (including while frozen); filters do not limit the export"+(mode==Mode.RUNS ? ". Dungeon runs and their events only." : "."));
        controls.add(record); controls.add(freeze); controls.add(export);
        if(mode==Mode.RUNS){
            durationUnit.setName("run-duration-unit");durationUnit.getAccessibleContext().setAccessibleName("Run duration units");
            controls.add(labeled("Time",durationUnit));
            durationUnit.addActionListener(e->{int view=table.convertColumnIndexToView(2);if(view>=0)table.getColumnModel().getColumn(view).setHeaderValue(unit().column());table.getTableHeader().repaint();if(!restoringState)fill(false);rememberLiveState();});
        }
        freeze.addItemListener(e->{
            snapshots.invalidate();
            if(freeze.isSelected()&&mode==Mode.COMBAT&&!state.visits.isEmpty()){
                // A pending selection is not yet the displayed value that Freeze latches.
                refreshing=true;selectVisit(state.visits.get(0).id);refreshing=false;
            }
            refresh();
        });
        top.add(controls);
        if(mode==Mode.RUNS)top.add(runFilterControls());
        if(mode!=Mode.RUNS){
            JPanel filters=ContentStyle.controls();filters.setAlignmentX(LEFT_ALIGNMENT);
            visitPicker.setName("activity-visit"); visitPicker.setPrototypeDisplayValue(new VisitChoice("","09-09 22:00 · Recorded visit"));
            visitPicker.getAccessibleContext().setAccessibleName("Recorded visit");kind.setName("activity-kind");kind.getAccessibleContext().setAccessibleName("Activity type");
            filters.add(labeled("Visit",visitPicker)); if(mode==Mode.TIMELINE)filters.add(kind); top.add(filters);
        }
        summary.setName("activity-summary");summary.setFont(ContentStyle.metadata(ContentStyle.body()));saved.setFont(ContentStyle.metadata(ContentStyle.body()));
        summary.setBorder(BorderFactory.createEmptyBorder(4,8,4,0));summary.setAlignmentX(LEFT_ALIGNMENT); top.add(summary); add(top,BorderLayout.NORTH);
        detail.setEditable(false); detail.setLineWrap(true); detail.setWrapStyleWord(true); detail.setMargin(new Insets(6,8,6,8));
        detail.setName("activity-detail"); detail.setFont(ContentStyle.body());detail.getAccessibleContext().setAccessibleName("Selected activity details");
        if(mode==Mode.COMBAT){
            JPanel plot=new JPanel(new BorderLayout(0,6)),tools=ContentStyle.controls();
            for(String action:new String[]{"zoom-in","zoom-out","reset-zoom","previous-sample","next-sample"})tools.add(new JButton(chart.getActionMap().get(action)));
            JTextArea inspection=new JTextArea(chart.getInspectionSummary());inspection.setName("combat-sample-summary");
            inspection.setEditable(false);inspection.setLineWrap(true);inspection.setWrapStyleWord(true);inspection.setOpaque(false);
            inspection.setFont(ContentStyle.metadata(ContentStyle.body()));inspection.setRows(2);
            inspection.getAccessibleContext().setAccessibleName("Inspected resource sample");
            chart.addPropertyChangeListener("inspectionSummary",e->inspection.setText((String)e.getNewValue()));
            JScrollPane inspectionScroll=new JScrollPane(inspection);inspectionScroll.setBorder(null);
            inspectionScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            plot.add(tools,BorderLayout.NORTH);plot.add(new JScrollPane(chart));plot.add(inspectionScroll,BorderLayout.SOUTH);
            combatViews.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
            combatViews.addTab("Buff timeline & resources",plot); combatViews.addTab("Uptime summary",ContentStyle.tableScroll(table,3));
            // Live visits are not yet saved-session references: window analysis works, Timeline handoffs explain why not.
            combatViews.addTab("Selected window",ResourceWindowPanel.scroll(new ResourceWindowPanel(chart,()->null)));
            combatViews.setName("activity-resource-tabs");
            combatViews.addChangeListener(e->{if(!refreshing&&!restoringState){fill(false);rememberLiveState();}});
            add(split(combatViews,new JScrollPane(detail),.78),BorderLayout.CENTER);
        }else add(split(ContentStyle.tableScroll(table,3),new JScrollPane(detail),.70),BorderLayout.CENTER);
        JTextArea scope=new JTextArea(mode==Mode.COMBAT
            ? "Local character · choose a recorded visit independently of the damage encounter. Party-wide uptime awaits verified roster matching."
            : mode==Mode.RUNS ? "Dungeon runs only · Completed requires a server victory, final-boss dialogue or verified completion counter. DPS uses captured damage over the shared first-to-last hit window."
            : "Party, progression and equipment history. Requests do not prove successful actions; progress between visits remains unassigned.");
        scope.setLineWrap(true); scope.setWrapStyleWord(true); scope.setOpaque(false); scope.setEditable(false); scope.setRows(2);
        scope.setFont(ContentStyle.metadata(ContentStyle.body()));
        JPanel bottom=new JPanel();bottom.setLayout(new BoxLayout(bottom,BoxLayout.Y_AXIS));bottom.add(scope);bottom.add(saved);bottom.add(stateHost);add(bottom,BorderLayout.SOUTH);
        search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){filter();} public void removeUpdate(DocumentEvent e){filter();} public void changedUpdate(DocumentEvent e){filter();}});
        kind.addActionListener(e->{if(!refreshing&&!restoringState){fill();rememberLiveState();}}); visitPicker.addActionListener(e->{if(!refreshing&&!restoringState){
            selectionRequired=false;restorePending=false;restoredVisit=restoredRow="";
            if(mode==Mode.COMBAT)table.clearSelection();
            if(mode==Mode.COMBAT){if(freeze.isSelected())refreshFrozenVisit();else refresh();}else fill();
            rememberLiveState();
        }});
        table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&!refreshing&&!restoringState){showDetail();rememberLiveState();}});
        timer=new javax.swing.Timer(1000,e->{if(isShowing())refresh();}); fill(false);
        addHierarchyListener(e->{if((e.getChangeFlags()&java.awt.event.HierarchyEvent.SHOWING_CHANGED)!=0)visibilityChanged();});
    }
    private JPanel runFilterControls(){
        JPanel controls=ContentStyle.controls();controls.setAlignmentX(LEFT_ALIGNMENT);
        outcomeFilter.setName("live-run-outcomes");evidenceFilter.setName("live-run-evidence");
        outcomeFilter.addActionListener(e->chooseFacets(outcomeFilter,ActivityQueries.Outcome.values(),runFilters.outcomes,values->{runFilters.outcomes=values;runFilterChanged();}));
        evidenceFilter.addActionListener(e->chooseFacets(evidenceFilter,ActivityQueries.Evidence.values(),runFilters.evidence,values->{runFilters.evidence=values;runFilterChanged();}));
        issuesFilter.setName("live-run-issues");gapsFilter.setName("live-run-gaps");
        issuesFilter.getAccessibleContext().setAccessibleName("Capture issues in retained runs");gapsFilter.getAccessibleContext().setAccessibleName("Timing gaps in retained runs");
        issuesFilter.addActionListener(e->{if(!restoringState){runFilters.captureIssues=(ActivityQueries.Presence)issuesFilter.getSelectedItem();runFilterChanged();}});
        gapsFilter.addActionListener(e->{if(!restoringState){runFilters.timingGaps=(ActivityQueries.Presence)gapsFilter.getSelectedItem();runFilterChanged();}});
        minimumDuration.setName("live-run-minimum-seconds");maximumDuration.setName("live-run-maximum-seconds");
        minimumDuration.getAccessibleContext().setAccessibleName("Minimum run duration seconds");maximumDuration.getAccessibleContext().setAccessibleName("Maximum run duration seconds");
        JButton duration=new JButton("Apply duration");duration.setName("live-run-apply-duration");
        duration.addActionListener(e->{try{
            ActivityQueries.Filters next=runFilters();next.minimumDurationMillis=durationMillis(minimumDuration.getText());next.maximumDurationMillis=durationMillis(maximumDuration.getText());setRunFilters(next);
        }catch(RuntimeException failure){saved.setText("Duration not applied: "+failure.getMessage());}});
        JButton reset=new JButton("Reset run filters");reset.addActionListener(e->setRunFilters(new ActivityQueries.Filters()));
        controls.add(outcomeFilter);controls.add(evidenceFilter);controls.add(labeled("Capture issues",issuesFilter));controls.add(labeled("Timing gaps",gapsFilter));
        controls.add(labeled("Duration seconds ≥",minimumDuration));controls.add(labeled("≤",maximumDuration));controls.add(duration);controls.add(reset);syncRunControls();return controls;
    }
    private static <T> void chooseFacets(JButton button,T[] available,Set<T> selected,java.util.function.Consumer<Set<T>> changed){
        JPopupMenu menu=new JPopupMenu();Set<T> draft=new LinkedHashSet<>(selected);
        for(T value:available){JCheckBoxMenuItem item=new JCheckBoxMenuItem(value.toString(),draft.contains(value));item.addActionListener(e->{if(item.isSelected())draft.add(value);else draft.remove(value);changed.accept(new LinkedHashSet<>(draft));});menu.add(item);}
        JMenuItem all=new JMenuItem("All");all.addActionListener(e->changed.accept(new LinkedHashSet<>()));menu.add(all);menu.show(button,0,button.getHeight());
    }
    /** Typed live filters operate on every retained visit in the displayed snapshot, including while frozen. */
    public void setRunFilters(ActivityQueries.Filters filters){
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Change live filters on the EDT");
        tomato.history.archive.ArchiveQuery<ActivityQueries.Filters,ActivityQueries.Sort> query=ActivityQueries.initial().withFacets(filters);
        ActivityQueries.adapter(Mode.RUNS).validate(query);runFilters=query.facets();syncRunControls();filter();
    }
    public ActivityQueries.Filters runFilters(){return ActivityQueries.initial().withFacets(runFilters).facets();}
    private void runFilterChanged(){syncRunControls();filter();}
    private void syncRunControls(){boolean previous=restoringState;restoringState=true;try{
        outcomeFilter.setText("Outcome: "+(runFilters.outcomes.isEmpty()?"All":runFilters.outcomes.size()==1?runFilters.outcomes.iterator().next():runFilters.outcomes.size()+" selected"));
        evidenceFilter.setText("Evidence: "+(runFilters.evidence.isEmpty()?"All":runFilters.evidence.size()==1?runFilters.evidence.iterator().next():runFilters.evidence.size()+" selected"));
        outcomeFilter.setToolTipText(runFilters.outcomes.toString());evidenceFilter.setToolTipText(runFilters.evidence.toString());
        issuesFilter.setSelectedItem(runFilters.captureIssues);gapsFilter.setSelectedItem(runFilters.timingGaps);
        minimumDuration.setText(durationSeconds(runFilters.minimumDurationMillis));maximumDuration.setText(durationSeconds(runFilters.maximumDurationMillis));
    }finally{restoringState=previous;}}
    private static Long durationMillis(String text){return text.trim().isEmpty()?null:new java.math.BigDecimal(text.trim()).movePointRight(3).longValueExact();}
    private static String durationSeconds(Long value){return value==null?"":java.math.BigDecimal.valueOf(value,3).stripTrailingZeros().toPlainString();}

    public void bindViewState(ViewStateStore store){
        if(liveState!=null||log.isHistorical())return;
        liveState=new RosterViewState(store,"activity-live-"+mode.name().toLowerCase(Locale.ROOT),this::captureLiveState,this::prepareLiveState);
        stateHost.add(liveState.controls());RosterViewState.listenTable(table,this::rememberLiveState);
    }
    public java.util.concurrent.CompletionStage<util.PreferencesStore.SaveResult> saveViewState(){
        if(liveState==null)throw new IllegalStateException("Live state is not bound");return liveState.save();
    }
    private void rememberLiveState(){if(liveState!=null&&!restoringState&&!refreshing)liveState.changed();}
    private Map<String,String> captureLiveState(){
        Map<String,String> values=new LinkedHashMap<>();values.put("activityVersion","1");values.put("search",search.getText());
        values.put("kind",Objects.toString(kind.getSelectedItem(),"All activities"));values.put("unit",unit().name());
        values.put("tab",mode==Mode.COMBAT&&combatViews.getSelectedIndex()==1?"uptime":"primary");
        values.put("visit",restorePending?restoredVisit:choice());values.put("row",restorePending?restoredRow:selectedKey());
        values.put("requireSelection",Boolean.toString(selectionRequired));values.put("runQuery",ActivityQueries.initial().withFacets(runFilters).toJson().toString());
        RosterViewState.captureTable(values,table);values.put("layout",SessionStore.JSON.toJson(HistoryTables.columnState(table,"Live")));return values;
    }
    private Runnable prepareLiveState(Map<String,String> values){
        RosterViewState.number(values,"activityVersion",1,1,1);
        String text=values.getOrDefault("search",""),visit=values.getOrDefault("visit",""),row=values.getOrDefault("row","");
        int selectedKind=-1;String type=values.getOrDefault("kind","All activities");for(int i=0;i<kind.getItemCount();i++)if(kind.getItemAt(i).equals(type))selectedKind=i;
        if(selectedKind<0)throw new IllegalArgumentException("Unknown activity type");final int kindIndex=selectedKind;
        RunDurationUnit units=RunDurationUnit.valueOf(values.getOrDefault("unit",RunDurationUnit.values()[0].name()));
        int tab=RosterViewState.option(values,"tab",0,"primary","uptime"),required=RosterViewState.option(values,"requireSelection",0,"false","true");
        tomato.history.archive.ArchiveQuery<ActivityQueries.Filters,ActivityQueries.Sort> query=ActivityQueries.initial();
        if(values.containsKey("runQuery"))query=query.restore(com.google.gson.JsonParser.parseString(values.get("runQuery")).getAsJsonObject());ActivityQueries.adapter(mode).validate(query);
        ActivityQueries.Filters filters=query.facets();Runnable columns=RosterViewState.prepareTable(values,table);
        ViewState.Table layout=values.containsKey("layout")?SessionStore.JSON.fromJson(values.get("layout"),ViewState.Table.class):null;
        if(layout!=null){layout=new ViewState.Table(layout.preset,layout.columns);Set<String> ids=new HashSet<>();for(ViewState.Column c:layout.columns)ids.add(c.id);
            for(int c=0;c<table.getModel().getColumnCount();c++)if(!ids.remove("column-"+c))throw new IllegalArgumentException("Missing live column");if(!ids.isEmpty())throw new IllegalArgumentException("Unknown live column");
            if(layout.columns.stream().noneMatch(c->c.visible))throw new IllegalArgumentException("Keep a visible column");}
        final ViewState.Table restoredLayout=layout;
        return ()->{restoringState=true;try{
            runFilters=filters;syncRunControls();search.setText(text);kind.setSelectedIndex(kindIndex);durationUnit.setSelectedItem(units);
            if(mode==Mode.COMBAT)combatViews.setSelectedIndex(tab);columns.run();if(restoredLayout!=null)HistoryTables.applyColumns(table,restoredLayout);
            restoredVisit=visit;restoredRow=row;restorePending=!visit.isEmpty()||!row.isEmpty();selectionRequired=required==1;
        }finally{restoringState=false;}filter();};
    }
    private static JPanel labeled(String text,JComponent component){JPanel group=new JPanel(new BorderLayout(6,0));JLabel label=new JLabel(text);label.setLabelFor(component);group.add(label,BorderLayout.WEST);group.add(component);return group;}
    private static JSplitPane split(JComponent top,JComponent bottom,double ratio){
        bottom.setMinimumSize(new Dimension(0,65)); bottom.setPreferredSize(new Dimension(600,ratio>.75?75:115));
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,top,bottom){
            private boolean positioned;
            @Override public void doLayout(){if(!positioned&&getHeight()>0){positioned=true;setDividerLocation(ratio);}super.doLayout();}
        }; split.setResizeWeight(ratio); split.setBorder(null); return split;
    }
    @Override public void addNotify(){super.addNotify();visibilityChanged();}
    @Override public void removeNotify(){if(liveState!=null)liveState.save();timer.stop();snapshots.invalidate();super.removeNotify();}
    private void visibilityChanged(){
        if(isShowing()){
            timer.start();
            if(freeze.isSelected()&&mode==Mode.COMBAT)refreshFrozenVisit();else refresh();
        }else{timer.stop();snapshots.invalidate();}
    }
    public void refresh(){
        if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::refresh);return;}
        record.refresh();updateSummary();
        refreshPresentation();
        if(freeze.isSelected() || (isDisplayable()&&!isShowing()))return;
        String selected=restorePending&&!restoredVisit.isEmpty()?restoredVisit:choice();
        snapshots.request(selected,()->{
            DiscoveryLog.ActivitySnapshot next=log.activityView(ActivityJournal.View.valueOf(mode.name()),selected,revision);
            return next==null ? null : new ViewUpdate(next,next.view.data,next.view.selectedVisit);
        },this::applySnapshot,error->saved.setText("Could not refresh history; retrying on the next refresh."));
    }
    private void refreshFrozenVisit(){
        refreshPresentation();
        if(displayed==null||(isDisplayable()&&!isShowing()))return;
        snapshots.invalidate();
        if(!state.visits.isEmpty()&&state.visits.get(0).id.equals(choice()))return;
        String id=choice();DiscoveryLog.ActivitySnapshot frozen=displayed;
        snapshots.request("frozen:"+id,()->{
            ActivityJournal.State data=new ActivityJournal.State();ActivityJournal.Visit visit=frozen.view.combatVisit(id);
            if(visit!=null)data.visits.add(visit);return new ViewUpdate(frozen,data,id);
        },this::applySnapshot,error->saved.setText("Could not load the frozen visit."));
    }
    private void applySnapshot(ViewUpdate update){
        refreshPresentation();
        boolean selectionChanged=mode==Mode.COMBAT && (state.visits.isEmpty() || !state.visits.get(0).id.equals(update.selected));
        displayed=update.snapshot;state=update.data;displayedEnabled=displayed.enabled;
        // Frozen navigation keeps the export pin, but its token still describes the original visit.
        // Only reuse that token when it also describes the payload actually on screen.
        revision=mode!=Mode.COMBAT || update.selected.equals(displayed.view.selectedVisit) ? displayed.revision : null;
        visitCount=displayed.view.visitCount;eventCount=displayed.view.eventCount;
        record.refresh(); refreshing=true;
        String selection=mode==Mode.COMBAT?update.selected:choice();String rowToRestore="";
        if(restorePending){
            if(mode!=Mode.RUNS&&!restoredVisit.isEmpty()){
                boolean found=false;for(ActivityJournal.VisitChoice visit:displayed.view.choices)if(visit.id.equals(restoredVisit)){found=true;break;}
                selection=found?restoredVisit:"";selectionRequired=!found;
                if(found)rowToRestore=restoredRow;
            }else rowToRestore=restoredRow;
            restorePending=false;restoredVisit=restoredRow="";
        }
        updateChoices(selection);
        if(selectionRequired&&mode==Mode.COMBAT){visitPicker.setSelectedIndex(-1);state=new ActivityJournal.State();revision=null;}
        refreshing=false; fill(selectionChanged); rememberPresentation();
        refreshing=true;try{restoreSelection(rowToRestore);}finally{refreshing=false;}showDetail();
    }
    private void updateChoices(String selected){
        List<VisitChoice> choices=new ArrayList<>();
        if(mode==Mode.TIMELINE)choices.add(new VisitChoice("","All visits (including unassigned events)"));
        if(mode!=Mode.RUNS)for(int i=displayed.view.choices.size()-1;i>=0;i--){ActivityJournal.VisitChoice v=displayed.view.choices.get(i);choices.add(new VisitChoice(v.id,time(v.started)+" · "+v.map));}
        boolean same=choices.size()==visitPicker.getItemCount();
        for(int i=0;same&&i<choices.size();i++)same=choices.get(i).id.equals(visitPicker.getItemAt(i).id)&&choices.get(i).label.equals(visitPicker.getItemAt(i).label);
        if(!same){visitPicker.removeAllItems();for(VisitChoice item:choices)visitPicker.addItem(item);}
        for(int i=0;i<visitPicker.getItemCount();i++)if(visitPicker.getItemAt(i).id.equals(selected)){visitPicker.setSelectedIndex(i);break;}
    }
    private void rememberPresentation(){
        presentationLocale=Locale.getDefault(Locale.Category.FORMAT);presentationZone=ZoneId.systemDefault();
    }
    /** Reformat the retained view, including while frozen, without asking the observer to copy history. */
    private void refreshPresentation(){
        if(displayed==null || (Locale.getDefault(Locale.Category.FORMAT).equals(presentationLocale)
            && ZoneId.systemDefault().equals(presentationZone)))return;
        String selected=selectedKey();refreshing=true;
        try{
            updateChoices(choice());
            boolean changed=false;
            if(mode==Mode.TIMELINE)for(int row=0;row<rows.size();row++){
                String text=eventText((ActivityJournal.Entry)items.get(row));
                if(!Objects.equals(rows.get(row)[3],text)){rows.get(row)[3]=text;changed=true;}
            }
            table.clearSelection();
            if(changed)model.fireTableDataChanged();
            filter();restoreSelection(selected);
            if(mode==Mode.COMBAT)chart.refreshPresentation();
            rememberPresentation();table.repaint();
        }finally{refreshing=false;}
        // During an in-flight combat selection the chart still owns the previously displayed visit.
        showDetail(mode==Mode.COMBAT&&!state.visits.isEmpty()?state.visits.get(0):null);
    }
    private static final class ViewUpdate {
        final DiscoveryLog.ActivitySnapshot snapshot;final ActivityJournal.State data;final String selected;
        ViewUpdate(DiscoveryLog.ActivitySnapshot snapshot,ActivityJournal.State data,String selected){this.snapshot=snapshot;this.data=data;this.selected=selected;}
    }
    public void selectVisit(String id){for(int i=0;i<visitPicker.getItemCount();i++)if(visitPicker.getItemAt(i).id.equals(id)){visitPicker.setSelectedIndex(i);return;}}
    private String choice(){Object v=visitPicker.getSelectedItem();return v instanceof VisitChoice?((VisitChoice)v).id:"";}
    private ActivityJournal.Visit selectedVisit(){String id=choice();for(ActivityJournal.Visit v:state.visits)if(v.id.equals(id))return v;return null;}
    private void fill(){fill(true);}
    private void fill(boolean explicit){
        String selected=mode==Mode.COMBAT&&explicit?"":selectedKey(); refreshing=true;
        List<Object[]> nextRows=new ArrayList<>();List<Object> nextItems=new ArrayList<>();
        boolean updateTable=mode!=Mode.COMBAT||explicit||!tableInitialized||combatViews.getSelectedIndex()==1;
        if(mode==Mode.RUNS){for(int i=state.visits.size()-1;i>=0;i--){ActivityJournal.Visit v=state.visits.get(i);
            if(!ParseDungeon.isDungeon(v.map))continue;
            ActivityQueries.Row projection=ActivityQueries.visit(v);
            add(nextRows,nextItems,v,v.map,projection.time==null?null:Instant.ofEpochMilli(v.started),projection.durationMillis==null?null:unit().value(projection.durationMillis),projection.outcome.toString(),projection.coverage(),v.exaltIncrease,v.useRequests,v.issues,v.timingGaps,projection.evidence.toString(),v.damageTracked?v.totalDamage:null,v.dps(v.damageTracked?v.totalDamage:null));}}
        else if(mode==Mode.TIMELINE){for(int i=state.entries.size()-1;i>=0;i--){ActivityJournal.Entry e=state.entries.get(i);
            if(!choice().isEmpty()&&!choice().equals(e.visitId))continue;
            if(kind.getSelectedIndex()>0&&!Objects.toString(e.kind,"").startsWith((String)kind.getSelectedItem()))continue;
            add(nextRows,nextItems,e,Instant.ofEpochMilli(e.time),e.map,e.kind,eventText(e),e.detail);}}
        else {
            ActivityJournal.Visit v=selectedVisit();
            if(explicit||combatViews.getSelectedIndex()!=1)chart.setVisit(v);
            if(updateTable&&v!=null){uptimes(nextRows,nextItems,v.conditions,v.conditionObservedMillis,"");uptimes(nextRows,nextItems,v.extraConditions,v.extraConditionObservedMillis," (extra)");}
        }
        if(updateTable){
            boolean same=rows.size()==nextRows.size();
            for(int i=0;same&&i<rows.size();i++)same=Arrays.deepEquals(rows.get(i),nextRows.get(i))&&key(items.get(i)).equals(key(nextItems.get(i)));
            if(!same)table.clearSelection();
            rows.clear();rows.addAll(nextRows);items.clear();items.addAll(nextItems);tableInitialized=true;
            if(!same){model.fireTableDataChanged();filter();
                if(!selected.isEmpty())for(int i=0;i<items.size();i++)if(key(items.get(i)).equals(selected)){int row=table.convertRowIndexToView(i);if(row>=0)table.setRowSelectionInterval(row,row);break;}}
        }
        refreshing=false;
        updateSummary();
        showDetail();
    }
    private void uptimes(List<Object[]> target,List<Object> objects,Map<String,Long> values,long coverage,String suffix){values.forEach((name,ms)->add(target,objects,name+suffix,name+suffix,ms/1000.0,coverage/1000.0,coverage==0?null:Math.round(ms*1000.0/coverage)/10.0));}
    private static void add(List<Object[]> target,List<Object> objects,Object item,Object... row){objects.add(item);target.add(row);}
    private String displayValue(Object value,int column){
        if(mode==Mode.TIMELINE&&column==0||mode==Mode.RUNS&&column==1)return DisplayFormat.formatTimestamp((Instant)value);
        if(mode==Mode.RUNS&&column==2)return value==null?DisplayFormat.UNAVAILABLE:unit().format(((Number)value).doubleValue());
        if(mode==Mode.RUNS&&column==RUN_DPS)return value==null?DisplayFormat.UNAVAILABLE:DisplayFormat.formatNumber(((Number)value).doubleValue(),0,1);
        if(mode==Mode.COMBAT&&column==3)return value==null?DisplayFormat.UNAVAILABLE:DisplayFormat.formatPercentage(((Number)value).doubleValue(),1);
        if((mode==Mode.RUNS&&((column>=5&&column<=8)||column==10))||(mode==Mode.COMBAT&&(column==1||column==2)))return number(value);
        return value==null?"":value.toString();
    }
    private void filter(){
        String text=search.getText().trim();RowFilter<TableModel,Integer> literal=text.isEmpty()?null:RowFilter.regexFilter("(?i)"+Pattern.quote(text));
        sorter.setRowFilter(new RowFilter<TableModel,Integer>(){public boolean include(Entry<? extends TableModel,? extends Integer> entry){
            boolean textMatches=literal==null||literal.include(entry)||mode==Mode.TIMELINE&&SessionStore.JSON.toJson(((ActivityJournal.Entry)items.get(entry.getIdentifier())).values).toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT));
            return textMatches&&(mode!=Mode.RUNS||ActivityQueries.matchesVisit(ActivityQueries.visit((ActivityJournal.Visit)items.get(entry.getIdentifier())),runFilters));
        }});chart.setFilter(text);updateSummary();if(!refreshing)showDetail();rememberLiveState();
    }
    private void updateSummary(){
        String counts=mode==Mode.RUNS ? number(table.getRowCount())+" of "+number(rows.size())+" dungeon runs · "+number(visitCount-rows.size())+" other area visits in Timeline"
            : number(visitCount)+" visits · "+number(eventCount)+" retained events";
        summary.setText("<html>"+CollectionControl.status(log,freeze.isSelected())+"<br>"+counts+(mode==Mode.RUNS?"<br>Filters and sorting cover the entire retained displayed snapshot; Browse saved for persisted history.":"")+"</html>");
    }
    private String selectedKey(){int row=table.getSelectedRow();return row<0?"":key(items.get(table.convertRowIndexToModel(row)));}
    private void restoreSelection(String selected){
        if(selected==null||selected.isEmpty())return;
        for(int i=0;i<items.size();i++)if(key(items.get(i)).equals(selected)){
            int row=table.convertRowIndexToView(i);if(row>=0)table.setRowSelectionInterval(row,row);break;
        }
    }
    private static String key(Object item){if(item instanceof ActivityJournal.Visit)return Objects.toString(((ActivityJournal.Visit)item).id,"");if(item instanceof ActivityJournal.Entry)return Objects.toString(((ActivityJournal.Entry)item).id,"");return item.toString();}
    private void showDetail(){
        showDetail(mode==Mode.COMBAT?selectedVisit():null);
    }
    private void showDetail(ActivityJournal.Visit combatVisit){
        int row=table.getSelectedRow(); Object item=row<0?null:items.get(table.convertRowIndexToModel(row));
        ActivityJournal.Visit v=mode==Mode.COMBAT?combatVisit:item instanceof ActivityJournal.Visit?(ActivityJournal.Visit)item:null;
        String text;
        if(v!=null)text=v.map+" · "+time(v.started)+" ("+DisplayFormat.timestampZoneLabel()+")\n"+v.runStatus()+" · Capture issues: "+number(v.issues)+" · Timing gaps: "+number(v.timingGaps)
            +"\nCompletion evidence: "+(v.completionEvidence.isEmpty()?"Not observed":v.completionEvidence)
            +"\nVisit ended: "+(v.endReason.isEmpty()?v.status:v.endReason)
            +"\nHP: "+range(v.hpMin,v.hpMax)+" · MP: "+range(v.mpMin,v.mpMax)+" · Use requests: "+number(v.useRequests)
            +"\nBuff coverage: "+seconds(v.conditionObservedMillis)+" s; additional conditions: "+seconds(v.extraConditionObservedMillis)+" s. Uptime uses observed coverage, not the entire visit."
            +"\nParty roster: "+(v.partyId==null?"not observed":"party "+v.partyId+", "+number(v.rosterSize)+" observed members; identity links unverified")
            +"\nProgress increase within this visit: "+number(v.exaltIncrease)+" · Realm score: "+number(v.realmStart)+" → "+number(v.realmLatest)
            +"\nTimeline records omitted by retention limits: "+number(v.timelineOmitted)+". Old visits without time samples retain their aggregate summaries.";
        else if(item instanceof ActivityJournal.Entry){ActivityJournal.Entry e=(ActivityJournal.Entry)item;text=time(e.time)+" ("+DisplayFormat.timestampZoneLabel()+") · "+e.map+"\n"+e.kind+" · "+eventText(e)+"\n"+e.detail+"\n\n"+new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(e.values);}
        else text=mode==Mode.COMBAT?"Select a recorded visit to see local HP/MP and buff lanes. New captures supply time samples; existing aggregate-only records cannot be reconstructed into a timeline. Blank intervals mean unknown coverage."
            : mode==Mode.RUNS ? (rows.isEmpty()?(log.isHistorical()?"No dungeon runs saved in this scope. Recording coverage is unknown.":"No dungeon runs recorded. Enable gameplay & diagnostics collection, start capture and enter a dungeon such as Ice Citadel or Ocean Trench.")
                : table.getRowCount()==0?"No dungeon runs match your search.":"Select a dungeon run for progression, party and capture details.")+" Hub, overworld and unresolved area visits are available in Timeline."
            : log.isHistorical()?"Saved activity evidence. No selected record; search or select a row for details. An empty history does not establish complete recording coverage."
                : "Start capture and enter a fresh area to record activity. Search or select a row for details. Saved history is shared across Runs, Timeline and DPS Logger.";
        if(!text.equals(detail.getText())){detail.setText(text);detail.setCaretPosition(0);}
    }
    public static String time(long millis){return DisplayFormat.formatTimestamp(millis);}
    private RunDurationUnit unit(){return (RunDurationUnit)durationUnit.getSelectedItem();}
    private static String range(Integer a,Integer b){return a==null&&b==null?DisplayFormat.UNAVAILABLE:number(a)+"–"+number(b);}
    private static String seconds(long millis){return DisplayFormat.formatDurationSeconds(millis,1);}
    private static String number(Object value){return value instanceof Number?DisplayFormat.formatExact((Number)value):DisplayFormat.UNAVAILABLE;}
    private static String identifier(Object value){return value instanceof Number?Long.toString(((Number)value).longValue()):DisplayFormat.UNAVAILABLE;}
    private static String item(Object value){if(!(value instanceof Number))return "Unknown item";int id=((Number)value).intValue();if(id==-1)return "Empty";String name=assets.IdToAsset.objectName(id);return name==null||name.isEmpty()?"Item "+id:name;}
    private static String eventText(ActivityJournal.Entry e){Map<String,Object> v=e.values;
        if(e.kind==null||v==null)return ActivitySummaries.event(e);
        if(e.kind.equals("Party roster"))return "Party "+identifier(v.get("partyId"))+" · "+number(v.get("memberCount"))+" observed members";
        if(e.kind.equals("Exalt change"))return "Class "+identifier(v.get("classId"))+" · "+v.get("stat")+": "+number(v.get("before"))+" → "+number(v.get("after"));
        if(e.kind.equals("Resources"))return "HP "+number(v.get("hp"))+" · MP "+number(v.get("mp"));
        if(e.kind.equals("Item / ability request"))return item(v.get("slotObject.objectType"))+" · "+v.getOrDefault("slotLabel","Unknown slot");
        if(e.kind.equals("Equipment changed"))return item(v.get("before"))+" → "+item(v.get("after"));
        return ActivitySummaries.event(e);
    }
    static ActivityJournal.State exportHistory(ActivityJournal.State state,Mode mode){
        if(mode!=Mode.RUNS)return state;
        ActivityJournal.State report=new ActivityJournal.State();report.schemaVersion=state.schemaVersion;
        report.captureRunId=state.captureRunId;report.checkpointTime=state.checkpointTime;
        Set<String> ids=new HashSet<>();
        for(ActivityJournal.Visit visit:state.visits)if(ParseDungeon.isDungeon(visit.map)){report.visits.add(visit);ids.add(visit.id);}
        for(ActivityJournal.Entry entry:state.entries)if(ids.contains(entry.visitId))report.entries.add(entry);
        return report;
    }
    private void export(){exportTo(Paths.get("logs","discovery","reports"));}
    SwingWorker<Path,Void> exportTo(Path dir){
        if(exporting||displayed==null)return null;
        final DiscoveryLog.ActivitySnapshot source=displayed;final boolean frozen=freeze.isSelected();exporting=true;saved.setText("Saving displayed history revision; view filters not applied…");
        SwingWorker<Path,Void> worker=new SwingWorker<Path,Void>(){
            protected Path doInBackground()throws Exception{
                ActivityJournal.State report=exportHistory(source.fullHistory(),mode);
                com.google.gson.Gson json=new com.google.gson.GsonBuilder().setPrettyPrinting().create();
                com.google.gson.JsonObject document=json.toJsonTree(report).getAsJsonObject(),manifest=new com.google.gson.JsonObject();
                manifest.addProperty("exportScope",mode==Mode.RUNS?"DISPLAYED_DUNGEON_HISTORY_AND_LINKED_EVENTS":"DISPLAYED_HISTORY");
                manifest.addProperty("displayFrozen",frozen);manifest.addProperty("filtersApplied",false);
                manifest.addProperty("scopeNote","Legacy live/frozen history export; search, visit picker and table filters do not limit this report");
                manifest.add("revision",json.toJsonTree(source.revision));manifest.addProperty("displayCapturedAt",report.checkpointTime);
                manifest.addProperty("visitCount",report.visits.size());manifest.addProperty("eventCount",report.entries.size());document.add("manifest",manifest);
                Files.createDirectories(dir);Path path=Files.createTempFile(dir,"activity-",".json");Files.write(path,json.toJson(document).getBytes(StandardCharsets.UTF_8));return path;
            }
            protected void done(){try{Path path=get();saved.setText("Saved "+path.getFileName());saved.setToolTipText(path.toAbsolutePath().toString());}catch(Exception e){saved.setText("Could not save history. Check folder permissions and free space.");}finally{exporting=false;}}
        };worker.execute();return worker;
    }
    private static final class VisitChoice {final String id,label;VisitChoice(String id,String label){this.id=id;this.label=label;}public String toString(){return label;}}
}
