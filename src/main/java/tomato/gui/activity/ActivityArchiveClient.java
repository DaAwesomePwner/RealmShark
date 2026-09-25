package tomato.gui.activity;

import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.history.*;
import tomato.gui.modern.ContentStyle;
import tomato.history.archive.*;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import static tomato.gui.activity.ActivityQueries.*;

/** Saved Runs, Timeline and independent Resources & buffs clients. No live producer ownership. */
public final class ActivityArchiveClient implements ArchiveClient<Row,Filters,Sort> {
    @FunctionalInterface public interface VisitRenderer {
        JComponent render(ActivityJournal.Visit visit,ArchiveRow.Ref origin);
    }
    private final ActivityPanel.Mode mode;
    private final Path scratch;
    private final VisitRenderer visitRenderer;
    private View currentView;
    public ActivityArchiveClient(ActivityPanel.Mode mode,Path scratch) { this(mode,scratch,null); }
    public ActivityArchiveClient(ActivityPanel.Mode mode,Path scratch,VisitRenderer visitRenderer) {
        this.mode=Objects.requireNonNull(mode);this.scratch=Objects.requireNonNull(scratch);this.visitRenderer=visitRenderer;
    }
    public ArchiveQuery<Filters,Sort> initialQuery() { return ActivityQueries.initial(); }
    public Path scratchDirectory() { return scratch; }
    public ArchiveAdapter<Row,Filters,Sort> adapter(ArchiveQuery<Filters,Sort> q) { return ActivityQueries.adapter(mode); }
    public int pageSize() { return mode==ActivityPanel.Mode.TIMELINE?1000:100; }
    public List<ArchiveExport.Column<Row>> exportColumns() {
        if(mode==ActivityPanel.Mode.TIMELINE)return Arrays.asList(
                new ArchiveExport.Column<>("Time (epoch ms)",r->r.time),new ArchiveExport.Column<>("Area",r->r.map),
                new ArchiveExport.Column<>("Event ID",r->r.recordId),new ArchiveExport.Column<>("Kind",r->r.kind),
                new ArchiveExport.Column<>("Assignment",r->r.assigned?"Assigned":"Unassigned"),new ArchiveExport.Column<>("Visit ID",r->r.visitId),
                new ArchiveExport.Column<>("Summary",r->r.summary),new ArchiveExport.Column<>("Detail",r->r.detail),
                new ArchiveExport.Column<>("Values JSON",r->tomato.history.SessionStore.JSON.toJson(r.values)));
        return Arrays.asList(new ArchiveExport.Column<>("Time (epoch ms)",r->r.time),new ArchiveExport.Column<>("Area",r->r.map),
                new ArchiveExport.Column<>("Visit ID",r->r.visitId),new ArchiveExport.Column<>("Summary",r->r.summary),
                new ArchiveExport.Column<>("Duration ms",r->r.durationMillis),new ArchiveExport.Column<>("Outcome",r->r.outcome),
                new ArchiveExport.Column<>("Evidence",r->r.completionEvidence),new ArchiveExport.Column<>("Capture issues",r->r.issues),
                new ArchiveExport.Column<>("Timing gaps",r->r.gaps));
    }
    @Override public String previewExport(ArchiveResult.Lease<Row> lease,ExportSelection selection,Cancellation cancel)throws IOException {
        if(mode!=ActivityPanel.Mode.TIMELINE&&selection.kind==ExportSelection.Kind.SELECTED) {
            if(selection.refs.size()!=1)throw new IllegalArgumentException("Select exactly one visit for linked evidence export");
            return SelectedRunExport.preview(lease,selection.refs.iterator().next(),cancel).description()
                    +"\nVisit query, bounds and ordering: "+lease.manifest().get("query")+"\nSource issues: "+lease.manifest().get("issues");
        }
        return ArchiveClient.super.previewExport(lease,selection,cancel);
    }
    /** Shared-toolbar export hook; caller owns the already captured lease. */
    @Override
    public Path writeExport(ArchiveResult.Lease<Row> lease,ExportSelection selection,ArchiveExport.Format format,
                            Path folder,String base,Cancellation cancel)throws IOException {
        if(mode!=ActivityPanel.Mode.TIMELINE&&selection.kind==ExportSelection.Kind.SELECTED) {
            if(selection.refs.size()!=1)throw new IllegalArgumentException("Select exactly one visit for linked evidence export");
            return SelectedRunExport.write(lease,selection.refs.iterator().next(),format,folder,base,cancel);
        }
        return ArchiveExport.write(lease,selection,format,folder,base,exportColumns(),cancel);
    }
    public JComponent render(ArchivePage<Row> page,ViewState<Filters,Sort> state,Binding<Filters,Sort> binding) {
        // Replaced non-displayable/headless views do not receive removeNotify.
        if(currentView!=null)currentView.retire();
        currentView=new View(page,state,binding);return currentView;
    }
    private final class View extends JPanel {
        private final ArchivePage<Row> page;
        private ViewState<Filters,Sort> state;
        private final Binding<Filters,Sort> binding;
        private final JTable table;
        private final JScrollPane scroll;
        private final JPanel details=new JPanel(new BorderLayout()) {
            @Override public Dimension getMinimumSize() {
                Dimension size=super.getMinimumSize();
                return new Dimension(size.width,Math.max(100,size.height));
            }
        };
        private final JTextArea message=ContentStyle.wrappingText("");
        private final JTabbedPane tabs=new JTabbedPane();
        private final CombatTimelineChart chart=new CombatTimelineChart();
        private final JPanel uptime=new JPanel(new BorderLayout());
        private final JButton linked=new JButton("Export selected visit + Timeline…");
        private final JButton openFolder=new JButton("Open linked export folder");
        private Path exportedFolder;
        private Cancellation detailCancel=new Cancellation(),exportCancel=new Cancellation();
        private ArchiveRow<Row> pending, selected;
        private boolean reading, restoring=true, removed, exporting;
        private long generation;
        View(ArchivePage<Row> page,ViewState<Filters,Sort> state,Binding<Filters,Sort> binding) {
            super(new BorderLayout(0,6));this.page=page;this.state=state;this.binding=binding;
            setName("activity-archive-"+mode.name().toLowerCase(Locale.ROOT));
            Map<String,Sort> sorts=new LinkedHashMap<>();
            List<HistoryTables.Column<Row,?>> columns=columns(sorts);
            table=HistoryTables.queried("saved-activity-table",columns,page,sorts,state.query,binding::queryChanged,this::select);
            ZoneId zone=ZoneId.of(state.query.bounds().zone);
            table.setDefaultRenderer(Instant.class,new ContentStyle.Cell(){
                protected void setValue(Object value){setText(value==null?"Unknown time":java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").format(((Instant)value).atZone(zone)));setToolTipText(zone.getId());}
            });
            scroll=ContentStyle.tableScroll(table,4);
            ViewState.Table defaults=defaults(columns);
            HistoryTables.applyColumns(table,state.tables.getOrDefault("activity",defaults));
            Map<String,List<String>> presets=new LinkedHashMap<>();
            presets.put("Compact",mode==ActivityPanel.Mode.TIMELINE?Arrays.asList("time","map","kind","summary","assignment")
                    :Arrays.asList("map","time","duration","outcome","coverage"));
            List<String> all=new ArrayList<>();for(HistoryTables.Column<Row,?> column:columns)all.add(column.id);presets.put("Evidence",all);
            JPanel top=new JPanel();top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));
            top.add(queryControls());
            top.add(HistoryTables.controls(table,defaults,presets,layout->{this.state=this.state.withTable("activity",layout);remember();}));
            JTextArea counts=ContentStyle.wrappingText(counts(page)+(mode==ActivityPanel.Mode.TIMELINE?"":
                    "\nVisit summary rows · Export selected visit + Timeline below includes full linked evidence."));counts.setName("activity-archive-counts");top.add(counts);
            add(top,BorderLayout.NORTH);
            message.setName("activity-archive-detail");message.getAccessibleContext().setAccessibleName("Saved activity details and origin");
            message.setRows(6);
            if(mode==ActivityPanel.Mode.COMBAT) {
                JPanel plot=new JPanel(new BorderLayout());JPanel tools=ContentStyle.controls();
                for(String action:new String[]{"zoom-in","zoom-out","reset-zoom","previous-sample","next-sample"})tools.add(new JButton(chart.getActionMap().get(action)));
                JTextArea inspection=ContentStyle.wrappingText(chart.getInspectionSummary());inspection.setName("saved-resource-sample");
                chart.addPropertyChangeListener("inspectionSummary",e->inspection.setText((String)e.getNewValue()));
                plot.add(tools,BorderLayout.NORTH);plot.add(new JScrollPane(chart));plot.add(inspection,BorderLayout.SOUTH);
                tabs.setName("saved-resource-tabs");tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
                tabs.addTab("Resources & buffs",plot);tabs.addTab("Uptime summary",uptime);tabs.addTab("Coverage",new JScrollPane(message));
                int index="uptime".equals(state.tab)?1:"coverage".equals(state.tab)?2:0;tabs.setSelectedIndex(index);
                tabs.addChangeListener(e->{if(!restoring){this.state=this.state.withPosition(tab(),this.state.selected,this.state.anchor,this.state.anchorOffset);remember();}});
                details.add(tabs);
            } else details.add(new JScrollPane(message));
            // Selected Inspect visits embed a roster with its own scrollable controls.
            // Let its minimum height propagate instead of squeezing it beneath the evidence header.
            details.setMinimumSize(null);
            JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,scroll,details);split.setResizeWeight(mode==ActivityPanel.Mode.COMBAT?.3:.55);
            scroll.setPreferredSize(new Dimension(650,mode==ActivityPanel.Mode.COMBAT?140:230));split.setBorder(null);add(split);
            JPanel bottom=ContentStyle.controls();
            if(mode!=ActivityPanel.Mode.TIMELINE) {
                linked.setEnabled(false);linked.addActionListener(e->chooseLinkedExport());bottom.add(linked);
                JButton stop=new JButton("Cancel linked export");stop.addActionListener(e->exportCancel.cancel());bottom.add(stop);
                openFolder.setEnabled(false);openFolder.addActionListener(e->{try{Desktop.getDesktop().open(exportedFolder.toFile());}catch(Exception failure){message.setText("Could not open export folder: "+failure.getMessage());}});bottom.add(openFolder);
            }
            add(bottom,BorderLayout.SOUTH);
            HistoryTables.restorePosition(table,scroll,page,state);
            table.getSelectionModel().addListSelectionListener(e->{if(!restoring&&!e.getValueIsAdjusting()){
                position();int row=table.getSelectedRow();select(row<0?null:page.rows.get(row));
            }});
            scroll.getViewport().addChangeListener(e->{if(!restoring&&!removed)position();});
            restoring=false;
            int row=table.getSelectedRow();select(row<0?null:page.rows.get(row));
        }
        private String tab() { return tabs.getSelectedIndex()==1?"uptime":tabs.getSelectedIndex()==2?"coverage":"resources"; }
        private void remember() { if(!restoring&&!removed)binding.viewChanged(state); }
        private void position() { state=HistoryTables.position(table,scroll,page,state);remember(); }
        private JPanel queryControls() {
            JPanel controls=ContentStyle.controls();Filters f=state.query.facets();
            if(mode==ActivityPanel.Mode.TIMELINE) {
                Set<String> types=new LinkedHashSet<>(Arrays.asList("Area entered","Equipment changed","Exalt change","Exalt snapshot","Exalt baseline",
                        "Resources","Item / ability request","Party roster","Party activity","Party request / response","Inventory request","Inventory result","Capture issue","Ownership check"));
                types.addAll(f.kinds);for(ArchiveRow<Row> row:page.rows)types.add(row.value.kind);
                controls.add(multiple("Types",types,f.kinds,values->{Filters next=this.state.query.facets();next.kinds=values;change(next);}));
                JComboBox<Assignment> assignment=new JComboBox<>(Assignment.values());assignment.setSelectedItem(f.assignment);
                assignment.getAccessibleContext().setAccessibleName("Assigned or unassigned events");assignment.addActionListener(e->{Filters next=this.state.query.facets();next.assignment=(Assignment)assignment.getSelectedItem();change(next);});controls.add(assignment);
            } else {
                controls.add(multiple("Outcome",Arrays.asList(Outcome.values()),f.outcomes,values->{Filters next=this.state.query.facets();next.outcomes=values;change(next);}));
                controls.add(multiple("Evidence",Arrays.asList(Evidence.values()),f.evidence,values->{Filters next=this.state.query.facets();next.evidence=values;change(next);}));
                controls.add(presence("Capture issues",f.captureIssues,value->{Filters next=this.state.query.facets();next.captureIssues=value;change(next);}));
                controls.add(presence("Timing gaps",f.timingGaps,value->{Filters next=this.state.query.facets();next.timingGaps=value;change(next);}));
                JTextField min=field("Minimum duration seconds",seconds(f.minimumDurationMillis),6),max=field("Maximum duration seconds",seconds(f.maximumDurationMillis),6);
                controls.add(new JLabel("Duration seconds"));controls.add(min);controls.add(new JLabel("to"));controls.add(max);
                JButton duration=new JButton("Apply duration");duration.addActionListener(e->{try{
                    Filters next=this.state.query.facets();next.minimumDurationMillis=millis(min.getText());next.maximumDurationMillis=millis(max.getText());
                    adapter(this.state.query).validate(this.state.query.withFacets(next));change(next);
                }catch(RuntimeException failure){message.setText("Duration not applied: "+failure.getMessage());}});controls.add(duration);
            }
            JButton dates=new JButton("Date bounds…");dates.addActionListener(e->dates());controls.add(dates);
            if(!f.visitId.isEmpty()) {
                JButton clear=new JButton("Clear exact visit link");clear.setToolTipText(f.visitSession+" / "+f.visitId);
                clear.addActionListener(e->{Filters next=this.state.query.facets();next.visitId=next.visitSession="";change(next);});controls.add(clear);
            }
            return controls;
        }
        private void change(Filters f) { binding.queryChanged(state.query.withFacets(f)); }
        private void dates() {
            ArchiveQuery.Bounds b=state.query.bounds();
            JTextField from=field("From inclusive ISO offset date-time",b.from==null?"":Instant.ofEpochMilli(b.from).atZone(ZoneId.of(b.zone)).toOffsetDateTime().toString(),28);
            JTextField until=field("Until exclusive ISO offset date-time",b.until==null?"":Instant.ofEpochMilli(b.until).atZone(ZoneId.of(b.zone)).toOffsetDateTime().toString(),28);
            JTextField zone=field("Display zone",b.zone,18);JComboBox<ArchiveQuery.TimeMode> timeMode=new JComboBox<>(ArchiveQuery.TimeMode.values());timeMode.setSelectedItem(b.mode);
            JCheckBox unknown=new JCheckBox("Include unknown times",b.includeUnknown);
            JPanel form=new JPanel(new GridLayout(0,1));form.add(new JLabel("ISO offset date-time (e.g. 2026-09-21T00:00:00Z); blank = unbounded"));
            form.add(new JLabel("From (inclusive)"));form.add(from);form.add(new JLabel("Until (exclusive)"));form.add(until);
            form.add(new JLabel("Zone for display"));form.add(zone);form.add(timeMode);form.add(unknown);
            form.add(new JLabel("ENTRY uses entry time; OVERLAP uses the observed interval. Events are points."));
            if(JOptionPane.showConfirmDialog(this,form,"Saved activity date bounds",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)try {
                binding.queryChanged(state.query.withBounds(new ArchiveQuery.Bounds(bound(from.getText()),bound(until.getText()),ZoneId.of(zone.getText().trim()),
                        (ArchiveQuery.TimeMode)timeMode.getSelectedItem(),unknown.isSelected())));
            }catch(RuntimeException failure){message.setText("Date bounds not applied: "+failure.getMessage());}
        }
        private void select(ArchiveRow<Row> row) {
            if(removed)return;generation++;detailCancel.cancel();selected=row;pending=row;
            linked.setEnabled(row!=null&&!exporting);
            if(mode==ActivityPanel.Mode.COMBAT){chart.setVisit(null);uptime.removeAll();uptime.revalidate();uptime.repaint();}
            if(visitRenderer!=null){details.removeAll();details.add(new JScrollPane(message));details.revalidate();details.repaint();}
            if(row==null){pending=null;message.setText(page.matches==0?"No saved matches in this query. Reset filters or change scope; recording coverage is unknown.":"Select a saved visit or event for full details.");return;}
            message.setText(origin(row)+"\n"+row.value.summary+"\n"+row.value.detail);
            if(mode==ActivityPanel.Mode.TIMELINE) {
                pending=null;message.append("\n"+(row.value.assigned?"Assigned by recorded visit ID: "+row.value.visitId:"Unassigned; no visit inferred")
                        +"\n\nRaw fields\n"+new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(row.value.values));return;
            }
            message.append("\nLoading full visit from displayed pin…");if(!reading)startDetail();
        }
        private String origin(ArchiveRow<Row> row) {
            return "Session "+row.ref.session+" · Visit "+Objects.toString(row.value.visitId,"Not recorded")+"\nRevision "+page.revision
                    +"\n"+Objects.toString(row.value.map,"Unknown area")+" · "+(row.value.time==null?"Time unknown":Instant.ofEpochMilli(row.value.time).atZone(ZoneId.of(state.query.bounds().zone)))
                    +"\nSource "+row.ref.module+" / "+row.ref.locator;
        }
        private void startDetail() {
            if(pending==null||removed)return;
            ArchiveRow<Row> target=pending;pending=null;long ticket=generation;Cancellation token=new Cancellation();detailCancel=token;
            final ArchiveResult.Lease<Row> lease;
            try{lease=page.lease();}catch(IOException failure){message.setText("Displayed pin is unavailable; Refresh to retry.");return;}
            reading=true;
            new SwingWorker<ActivityJournal.Visit,Void>() {
                protected ActivityJournal.Visit doInBackground()throws Exception {
                    try(ArchiveResult.Lease<Row> held=lease){return ActivityQueries.readVisit(held,target,token);}
                }
                protected void done() {
                    try { ActivityJournal.Visit visit=get();if(!removed&&ticket==generation)showVisit(target,visit); }
                    catch(Exception failure){if(!removed&&ticket==generation)message.setText("Could not read selected visit from this revision. Refresh to retry.");}
                    finally{lease.close();reading=false;if(pending!=null&&!removed)startDetail();}
                }
            }.execute();
        }
        private void showVisit(ArchiveRow<Row> row,ActivityJournal.Visit visit) {
            message.setText(origin(row)+"\n"+row.value.outcome+" · "+row.value.coverage()
                    +"\nCompletion evidence: "+(row.value.completionEvidence.isEmpty()?"Not observed":row.value.completionEvidence)
                    +"\nEvidence observed at: "+(visit.completionObservedAt==0?"Unknown":Instant.ofEpochMilli(visit.completionObservedAt))
                    +" (later counter confirmation is not a reconstructed completion time)"
                    +"\nVisit ended: "+Objects.toString(visit.endReason,"")+" · Stored status: "+Objects.toString(visit.status,"Unknown")
                    +"\nObserved duration: "+(row.value.durationMillis==null?"Unknown":row.value.durationMillis+" ms")
                    +"\nProgress increase: "+visit.exaltIncrease+" · Use requests: "+visit.useRequests
                    +"\nBuff coverage: "+visit.conditionObservedMillis+" ms; additional conditions: "+visit.extraConditionObservedMillis+" ms. Gaps are unknown, not zero uptime."
                    +"\nTimeline samples omitted by retention: "+visit.timelineOmitted+". Old aggregate-only visits cannot reconstruct charts."
                    +"\nPage/all-match exports contain lightweight visit summaries. Export selected visit + Timeline includes full saved details and exact linked events.");
            if(mode==ActivityPanel.Mode.COMBAT) {
                chart.setVisit(visit);List<Object[]> rows=new ArrayList<>();
                uptimes(rows,visit.conditions,visit.conditionObservedMillis,"");uptimes(rows,visit.extraConditions,visit.extraConditionObservedMillis," (extra)");
                JTable table=HistoryTables.table("saved-buff-uptime",new String[]{"Condition","Active ms","Observed ms","Uptime %"},
                        new Class<?>[]{String.class,Long.class,Long.class,Double.class},rows);
                uptime.removeAll();uptime.add(HistoryTables.page(table,"Local character only. Uptime denominator is observed coverage, not the entire visit."));uptime.revalidate();uptime.repaint();
            } else if(visitRenderer!=null) {
                JPanel host=new JPanel(new BorderLayout());message.append("\nRoster facets apply only to this selected visit.");
                JScrollPane evidence=new JScrollPane(message);evidence.setPreferredSize(new Dimension(600,90));
                evidence.setMinimumSize(new Dimension(0,90));host.add(evidence,BorderLayout.NORTH);
                host.add(visitRenderer.render(visit,row.ref));details.removeAll();details.add(host);details.revalidate();details.repaint();
            }
        }
        private void chooseLinkedExport() {
            if(selected==null||exporting)return;
            final ArchiveResult.Lease<Row> lease;
            try{lease=page.lease();}catch(IOException failure){message.setText("Displayed revision is closed. Refresh to retry.");return;}
            ArchiveRow.Ref ref=selected.ref;exportCancel=new Cancellation();Cancellation token=exportCancel;exporting=true;linked.setEnabled(false);
            new SwingWorker<SelectedRunExport.Preview,Void>() {
                protected SelectedRunExport.Preview doInBackground()throws Exception { return SelectedRunExport.preview(lease,ref,token); }
                protected void done() {
                    boolean handedOff=false;
                    try {
                        SelectedRunExport.Preview preview=get();if(removed||token.isCancelled())return;
                        JTextArea info=ContentStyle.wrappingText(preview.description()+"\nVisit query: "+lease.manifest().get("query"));
                        JScrollPane area=new JScrollPane(info);area.setPreferredSize(new Dimension(580,260));
                        Object format=JOptionPane.showInputDialog(View.this,area,"Export selected visit and linked evidence",JOptionPane.PLAIN_MESSAGE,null,ArchiveExport.Format.values(),ArchiveExport.Format.JSON);
                        if(!(format instanceof ArchiveExport.Format)||removed||token.isCancelled())return;
                        JFileChooser chooser=new JFileChooser();chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                        if(chooser.showSaveDialog(View.this)!=JFileChooser.APPROVE_OPTION||removed||token.isCancelled())return;
                        Path folder=chooser.getSelectedFile().toPath();
                        new SwingWorker<Path,Void>() {
                            protected Path doInBackground()throws Exception {
                                try(ArchiveResult.Lease<Row> held=lease){return SelectedRunExport.write(held,preview,(ArchiveExport.Format)format,folder,"selected-visit",token);}
                            }
                            protected void done(){try{Path path=get();exportedFolder=path.toAbsolutePath().getParent();openFolder.setEnabled(Desktop.isDesktopSupported());message.setText("Exported "+(1+preview.events)+" records · revision "+preview.revision+" · "+path.getFileName());}
                                catch(Exception failure){message.setText("Linked export failed or cancelled; no completed report: "+failure.getMessage());}
                                finally{lease.close();exporting=false;linked.setEnabled(selected!=null&&!removed);}}
                        }.execute();handedOff=true;
                    }catch(Exception failure){message.setText("Could not prepare linked export: "+failure.getMessage());}
                    finally{if(!handedOff){lease.close();exporting=false;linked.setEnabled(selected!=null&&!removed);}}
                }
            }.execute();
        }
        private void retire() { removed=true;generation++;pending=null;detailCancel.cancel();exportCancel.cancel(); }
        @Override public void removeNotify() { retire();super.removeNotify(); }
    }
    private List<HistoryTables.Column<Row,?>> columns(Map<String,Sort> sorts) {
        List<HistoryTables.Column<Row,?>> columns=new ArrayList<>();
        if(mode!=ActivityPanel.Mode.TIMELINE)columns.add(new HistoryTables.Column<>("map","Dungeon / area",String.class,r->r.map,null));
        columns.add(new HistoryTables.Column<>("time",mode==ActivityPanel.Mode.TIMELINE?"Time":"Entered",Instant.class,r->r.time==null?null:Instant.ofEpochMilli(r.time),null));
        if(mode==ActivityPanel.Mode.TIMELINE) {
            columns.add(new HistoryTables.Column<>("map","Area",String.class,r->r.map,null));
            columns.add(new HistoryTables.Column<>("kind","Activity",String.class,r->r.kind,null));
            columns.add(new HistoryTables.Column<>("summary","Summary",String.class,r->r.summary,null));
            columns.add(new HistoryTables.Column<>("assignment","Assignment",String.class,r->r.assigned?"Assigned":"Unassigned",null));
        } else {
            columns.add(new HistoryTables.Column<>("duration","Seconds",Double.class,r->r.durationMillis==null?null:r.durationMillis/1000.0,null));
            columns.add(new HistoryTables.Column<>("outcome","Outcome",String.class,r->r.outcome.toString(),null));
            columns.add(new HistoryTables.Column<>("coverage","Coverage",String.class,Row::coverage,null));
            columns.add(new HistoryTables.Column<>("evidence","Evidence source",String.class,r->r.evidence.toString(),null));
            columns.add(new HistoryTables.Column<>("issues","Capture issues",Long.class,r->r.issues,null));
            columns.add(new HistoryTables.Column<>("gaps","Timing gaps",Long.class,r->r.gaps,null));
            columns.add(new HistoryTables.Column<>("players","Players",Integer.class,r->r.players,null));
            columns.add(new HistoryTables.Column<>("damage","Damage",Long.class,r->r.damage,null));
        }
        for(Sort sort:Sort.values())sorts.put(sort.name().toLowerCase(Locale.ROOT),sort);
        return columns;
    }
    private ViewState.Table defaults(List<HistoryTables.Column<Row,?>> columns) {
        List<ViewState.Column> layout=new ArrayList<>();
        for(int i=0;i<columns.size();i++) {
            String id=columns.get(i).id;int width="map".equals(id)?145:"time".equals(id)?150:"duration".equals(id)?80:"summary".equals(id)?360:180;
            layout.add(new ViewState.Column(id,width,i<5));
        }
        return new ViewState.Table("Compact",layout);
    }
    private static String counts(ArchivePage<Row> page) {
        String text=page.description();
        for(Map.Entry<String,ArchiveAdapter.Count> entry:page.counts.entrySet())text+=" · "+entry.getValue().value+" matching "+entry.getKey()+" events";
        return text;
    }
    private static void uptimes(List<Object[]> rows,Map<String,Long> values,long coverage,String suffix) {
        values.forEach((name,ms)->rows.add(new Object[]{name+suffix,ms,coverage,coverage<=0?null:100.0*ms/coverage}));
    }
    private static JTextField field(String label,String value,int columns) {
        JTextField field=new JTextField(value,columns);field.getAccessibleContext().setAccessibleName(label);return field;
    }
    private static JComponent presence(String name,Presence selected,Consumer<Presence> changed) {
        JComboBox<Presence> choice=new JComboBox<>(Presence.values());choice.setSelectedItem(selected);choice.getAccessibleContext().setAccessibleName(name);
        choice.addActionListener(e->changed.accept((Presence)choice.getSelectedItem()));JPanel group=ContentStyle.controls();group.add(new JLabel(name));group.add(choice);return group;
    }
    private static <T> JButton multiple(String name,Collection<T> available,Set<T> selected,Consumer<Set<T>> changed) {
        JButton button=new JButton(name+": "+(selected.isEmpty()?"All":selected.size()+" selected"));button.setToolTipText(selected.toString());button.getAccessibleContext().setAccessibleName(name+" multi-select "+selected);
        button.addActionListener(e->{JPopupMenu menu=new JPopupMenu();Set<T> draft=new LinkedHashSet<>(selected);
            for(T value:available){JCheckBoxMenuItem item=new JCheckBoxMenuItem(value.toString(),draft.contains(value));item.addActionListener(change->{if(item.isSelected())draft.add(value);else draft.remove(value);changed.accept(draft);});menu.add(item);}
            JMenuItem clear=new JMenuItem("All / clear "+name);clear.addActionListener(change->changed.accept(new LinkedHashSet<>()));menu.add(clear);menu.show(button,0,button.getHeight());});return button;
    }
    private static String seconds(Long millis) { return millis==null?"":java.math.BigDecimal.valueOf(millis,3).stripTrailingZeros().toPlainString(); }
    private static Long millis(String seconds) { return seconds.trim().isEmpty()?null:new java.math.BigDecimal(seconds.trim()).movePointRight(3).longValueExact(); }
    private static Long bound(String text) { return text.trim().isEmpty()?null:OffsetDateTime.parse(text.trim()).toInstant().toEpochMilli(); }
}
