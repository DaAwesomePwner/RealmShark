package tomato.gui.stats;

import com.google.gson.JsonObject;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import tomato.gui.history.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.route.*;
import tomato.gui.stats.LootQuery.*;
import tomato.gui.stats.session.*;
import tomato.history.*;
import tomato.history.archive.*;

/** One independently persisted workspace; every inner archive filter sends query intent upstream. */
public final class LootArchiveClient implements ArchiveClient<Row,Facets,Sort> {
    private final Path scratch;private final boolean statistics;
    public LootArchiveClient(Path scratch,boolean statistics){this.scratch=scratch;this.statistics=statistics;}
    public ArchiveQuery<Facets,Sort> initialQuery(){return LootQuery.initial(statistics);}
    public Path scratchDirectory(){return scratch;}
    public int pageSize(){return 100;}
    public ArchiveAdapter<Row,Facets,Sort> adapter(ArchiveQuery<Facets,Sort> q){View view=q.facets().view;return view.loot()?new LootArchiveAdapter(q):view==View.COHORTS?new CohortArchiveAdapter(q):new StatisticsArchiveAdapter(q);}
    public JComponent render(ArchivePage<Row> page,ViewState<Facets,Sort> state,Binding<Facets,Sort> binding){return new Render(page,state,binding);}
    public List<ArchiveExport.Column<Row>> exportColumns(){
        List<ArchiveExport.Column<Row>> result=new ArrayList<>();for(HistoryTables.Column<Row,?> column:columns())result.add(new ArchiveExport.Column<>(column.label,column.value));
        result.add(new ArchiveExport.Column<>("Exact enchantment evidence",r->r.enchantEvidence));
        result.add(new ArchiveExport.Column<>("Captured drop context",r->r.dropContext));return result;
    }
    private static <V> HistoryTables.Column<Row,V> col(String id,String label,Class<V> type,java.util.function.Function<Row,V> value){return new HistoryTables.Column<>(id,label,type,value,null);}
    static List<HistoryTables.Column<Row,?>> columns(){return Arrays.asList(
        col("type","Row unit/type",String.class,r->r.type),new HistoryTables.Column<>("time","Timestamp (epoch ms)",Long.class,r->r.time,timeRenderer()),col("name","Name",String.class,r->r.name),
        col("session","Source session",String.class,r->r.session),col("visit","Recorded visit ID (within session)",String.class,r->r.visitId),col("dungeon","Dungeon",String.class,r->r.dungeon),col("bag","Bag",String.class,r->r.bag),col("dropper","Dropper",String.class,r->r.dropper),
        col("item","Item ID",Integer.class,r->r.itemId),col("tier","Tier",String.class,r->r.tier),col("rarity","Rarity",String.class,r->r.rarity),col("slots","Slots",Integer.class,r->r.slots),col("applied","Applied enchants",Integer.class,r->r.applied),
        col("count","Occurrences / sample observations / count",Long.class,r->r.count),col("bags","Bags",Long.class,r->r.bags),col("items","Items",Long.class,r->r.items),col("runs","Visits / activity-recorded exits",Long.class,r->r.runs),new HistoryTables.Column<>("millis","Observed / finalized milliseconds",Long.class,r->r.millis,durationRenderer()),new HistoryTables.Column<>("average","Average finalized milliseconds / exit",Long.class,r->r.averageMillis,durationRenderer()),
        col("whites","White bags",Long.class,r->r.whites),col("uts","UT gear",Long.class,r->r.uts),col("sts","ST gear",Long.class,r->r.sts),col("potions","Stat potions",Long.class,r->r.potions),col("completed","Completed",Long.class,r->r.completed),col("unknown","Excluded unknown visits",Long.class,r->r.unknownRuns),col("imports","Excluded imported visits",Long.class,r->r.importedRuns),
        col("rate","Items / hour",Double.class,r->r.perHour),col("perRun","Items / run",Double.class,r->r.perRun),col("utHour","UT / hour",Double.class,r->r.utPerHour),col("whiteRun","Whites / run",Double.class,r->r.whitesPerRun),col("utRun","UT / run",Double.class,r->r.utPerRun),col("stRun","ST / run",Double.class,r->r.stPerRun),col("potionRun","Potions / run",Double.class,r->r.potionsPerRun),
        col("character","Character ID (within session)",Integer.class,r->r.character),col("class","Class",String.class,r->r.className),col("first","First fame",Double.class,r->r.firstFame),col("last","Last fame",Double.class,r->r.lastFame),col("gain","Fame change",Double.class,r->r.gain),col("enemy","Enemy ID",Integer.class,r->r.enemyId),col("hits","Hit events",Long.class,r->r.hits),col("damage","Damage",Long.class,r->r.damage),col("build","Build",String.class,r->r.build),col("ongoing","Ongoing contribution at counter snapshot",String.class,r->"COUNTERS".equals(r.type)?r.ongoingActivity==null?"Not captured":r.ongoingActivity?"Included; time not finalized":"None at snapshot":null),col("runLink","Recorded run link",String.class,r->r.runLinked==null?null:r.runLinked?"Verified":"Unavailable"),col("zeroLoot","Eligible runs with no linked bags",Long.class,r->r.zeroLootRuns),col("unassigned","Unassigned bags",Long.class,r->r.unassignedBags),
        col("minRun","Minimum items / run",Long.class,r->r.minPerRun),col("median","Median items / run",Double.class,r->r.medianPerRun),col("maxRun","Maximum items / run",Long.class,r->r.maxPerRun),col("runChange","Items / run change (% of baseline)",Double.class,r->r.perRunChange),col("hourChange","Items / hour change (% of baseline)",Double.class,r->r.perHourChange),
        col("evidence","Calculation / coverage",String.class,r->r.evidence));}
    /** Compact on-screen headers; the full analytical label stays in the header tooltip, details and CSV export. */
    static final Map<String,String> HEADERS;static{Map<String,String> h=new HashMap<>();String[] pairs={
        "type","Row type","time","Time","session","Session","visit","Visit ID","item","Item ID","applied","Applied","count","Count",
        "runs","Visits","millis","Observed (h:mm:ss)","average","Avg / exit (h:mm:ss)","unknown","Excluded unknown","imports","Excluded imports",
        "character","Character ID","ongoing","Ongoing at snapshot","runLink","Run link","zeroLoot","Zero-loot runs","unassigned","Unassigned bags",
        "minRun","Min items / run","median","Median items / run","maxRun","Max items / run","runChange","Items / run change","hourChange","Items / hour change",
        "evidence","Calculation / coverage"};for(int i=0;i<pairs.length;i+=2)h.put(pairs[i],pairs[i+1]);HEADERS=Collections.unmodifiableMap(h);}
    /** Epoch-millisecond values render as local date/time; the model (sort, copy, export) keeps the exact value. Zero/negative means undated. */
    static javax.swing.table.TableCellRenderer timeRenderer(){return new ContentStyle.Cell(){protected void setValue(Object value){
        setText(value==null?DisplayFormat.UNAVAILABLE:((Number)value).longValue()<=0?"Undated":DisplayFormat.formatTimestamp(((Number)value).longValue()));}};}
    /** Millisecond durations render as h:mm:ss; unknown stays distinct from a recorded zero. */
    static javax.swing.table.TableCellRenderer durationRenderer(){ContentStyle.Cell cell=new ContentStyle.Cell(){protected void setValue(Object value){
        setText(value==null?DisplayFormat.UNAVAILABLE:DisplayFormat.formatDurationHMS(((Number)value).longValue()));}};cell.setHorizontalAlignment(SwingConstants.RIGHT);return cell;}
    /** Short headers with full-label tooltips; every column is at least as wide as its header and sized to this page's values (capped). */
    static void sizeColumns(JTable table){
        javax.swing.table.TableCellRenderer base=table.getTableHeader().getDefaultRenderer();Map<String,String> labels=new HashMap<>();for(HistoryTables.Column<Row,?> c:columns())labels.put(c.id,c.label);
        for(javax.swing.table.TableColumn column:Collections.list(table.getColumnModel().getColumns())){
            String id=column.getIdentifier().toString(),full=labels.getOrDefault(id,String.valueOf(column.getHeaderValue()));
            column.setHeaderValue(HEADERS.getOrDefault(id,full));
            column.setHeaderRenderer((t,value,selected,focus,row,index)->{Component c=base.getTableCellRendererComponent(t,value,selected,focus,row,index);if(c instanceof JComponent)((JComponent)c).setToolTipText(full);return c;});
            int header=headerWidth(table,column),content=0,model=column.getModelIndex();
            for(int row=0;row<table.getRowCount();row++)content=Math.max(content,table.prepareRenderer(table.getCellRenderer(row,table.convertColumnIndexToView(model)),row,table.convertColumnIndexToView(model)).getPreferredSize().width);
            int width=Math.max(header,Math.min(content+table.getIntercellSpacing().width+2,320));
            column.setMinWidth(header);column.setPreferredWidth(width);column.setWidth(width);
        }
        // A later font refresh (theme, scaling or evidence fonts) must not truncate headers again.
        table.getTableHeader().addPropertyChangeListener("font",e->ensureHeaderWidths(table));
    }
    static void ensureHeaderWidths(JTable table){
        Set<javax.swing.table.TableColumn> all=new LinkedHashSet<>(Collections.list(table.getColumnModel().getColumns()));
        Object retained=table.getClientProperty("archive.columns");if(retained instanceof Collection)for(Object c:(Collection<?>)retained)if(c instanceof javax.swing.table.TableColumn)all.add((javax.swing.table.TableColumn)c);
        for(javax.swing.table.TableColumn column:all){int header=headerWidth(table,column);column.setMinWidth(header);if(column.getPreferredWidth()<header)column.setPreferredWidth(header);if(column.getWidth()<header)column.setWidth(header);}
    }
    /** The realized header renderer's preferred width, plus the column margin. */
    static int headerWidth(JTable table,javax.swing.table.TableColumn column){
        javax.swing.table.TableCellRenderer renderer=column.getHeaderRenderer()!=null?column.getHeaderRenderer():table.getTableHeader().getDefaultRenderer();
        Component header=renderer.getTableCellRendererComponent(table,column.getHeaderValue(),false,false,-1,0);
        // Fractional display scales (150%) can paint text a few pixels wider than its logical metrics; keep one em of slack.
        return header.getPreferredSize().width+table.getIntercellSpacing().width+header.getFontMetrics(header.getFont()).charWidth('m');
    }
    private static Map<String,Sort> sorts(){Map<String,Sort> m=new HashMap<>();String[] ids={"time","name","dungeon","bag","item","slots","applied","count","bags","items","runs","millis","rate","gain","hits","first","last","perRun","utHour","whiteRun","utRun","stRun","potionRun","whites","uts","sts","potions","completed","unknown","imports","damage","character","enemy","tier","rarity","average"};Sort[] values=Sort.values();for(int i=0;i<ids.length;i++)m.put(ids[i],values[i]);return m;}
    private final class Render extends JPanel {
        private ViewState<Facets,Sort> current;
        private final Binding<Facets,Sort> binding;
        private final ArchivePage<Row> page;
        private final JTextArea details=ContentStyle.wrappingText("Select a row for complete values and evidence.");
        private final JTable table;
        private final JScrollPane scroll;
        private boolean restoring=true;
        private final JTextArea linkStatus=ContentStyle.wrappingText(" "),rateStatus=ContentStyle.wrappingText(" ");
        private JTextArea countText;
        private final JButton showOccurrences=new JButton("Occurrences of selected variant"),showVisit=new JButton("Loot from selected run"),
            openRun=new JButton("Open recorded run"),rateDetails=new JButton("Dungeon rate calculation");
        Render(ArchivePage<Row> page,ViewState<Facets,Sort> state,Binding<Facets,Sort> binding){
            super(new BorderLayout(0,5));this.page=page;this.current=state;this.binding=binding;View view=state.query.facets().view;
            JTabbedPane tabs=new JTabbedPane();tabs.setName("loot-archive-tabs");tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
            List<View> views=new ArrayList<>();for(View candidate:View.values())if(statistics||candidate!=View.FAME&&!candidate.counters()&&candidate!=View.COHORTS)views.add(candidate);
            if(!views.contains(view))views.add(view);for(View v:views)tabs.addTab(v.toString(),new JPanel());tabs.setSelectedIndex(views.indexOf(view));
            JPanel body=new JPanel(new BorderLayout(0,4));tabs.setComponentAt(tabs.getSelectedIndex(),body);add(tabs);
            JPanel top=new JPanel(new BorderLayout(0,4));
            if(view.loot())top.add(new LootFacetControls(state.query.facets(),choices("facet.bag."),choices("facet.dungeon."),f->query(current.query.withFacets(f))),BorderLayout.NORTH);
            else top.add(analyticalFilters(view),BorderLayout.NORTH);
            top.add(dateControls(),BorderLayout.CENTER);countText=ContentStyle.wrappingText(description(view)+"\n"+countDescription(page));countText.setName("loot-archive-counts");top.add(countText,BorderLayout.SOUTH);body.add(top,BorderLayout.NORTH);
            table=HistoryTables.queried("loot-archive-table",columns(),page,sorts(),state.query,this::query,this::detail);sizeColumns(table);
            ViewState.Table defaults=HistoryTables.columnState(table,"All columns");ViewState.Table compact=compact(defaults,view);
            HistoryTables.applyColumns(table,current.tables.getOrDefault(view.name(),compact));
            scroll=ContentStyle.tableScroll(table,3);details.setName("loot-archive-details");details.getAccessibleContext().setAccessibleName("Selected archive record evidence");
            JScrollPane detailScroll=new JScrollPane(details) {
                @Override public Dimension getMinimumSize() {
                    Insets border=getInsets(),text=details.getInsets();
                    return new Dimension(0,details.getFontMetrics(details.getFont()).getHeight()*3
                            +border.top+border.bottom+text.top+text.bottom);
                }
            };
            detailScroll.setPreferredSize(new Dimension(300,130));JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,scroll,detailScroll);split.setResizeWeight(.75);body.add(split);
            JPanel actions=new JPanel(new BorderLayout());Map<String,List<String>> presets=new LinkedHashMap<>();presets.put("Compact",visible(compact));presets.put("All analytical columns",visible(defaults));
            actions.add(HistoryTables.controls(table,defaults,presets,layout->{current=current.withTable(view.name(),layout);savePosition();}),BorderLayout.CENTER);
            if(view.loot()||view==View.RATES)actions.add(drillActions(view),BorderLayout.NORTH);
            if(view==View.SESSIONS||view==View.FAME){JButton graph=new JButton("Open selected session's full fame graph");graph.setName("archive-open-fame");graph.addActionListener(e->openFame(graph));actions.add(graph,BorderLayout.SOUTH);}
            body.add(actions,BorderLayout.SOUTH);
            table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&!restoring){savePosition();int r=table.getSelectedRow();if(r>=0)detail(page.rows.get(r));updateDrill(selected());}});
            scroll.getViewport().addChangeListener(e->{if(!restoring)savePosition();});HistoryTables.restorePosition(table,scroll,page,current);
            tabs.addChangeListener(e->{if(restoring)return;View selected=views.get(tabs.getSelectedIndex());Facets f=current.query.facets();f.view=selected;current=current.withPosition(selected.name(),Collections.emptyList(),null,0);binding.viewChanged(current);query(current.query.withFacets(f));});
            restoring=false;
            ArchiveRow<Row> restored=selected();if(restored!=null)detail(restored);updateDrill(restored);
        }
        /** After a cohort input error the shown comparison no longer matches the inputs: clear it until a valid Compare runs. */
        private void cohortInputInvalid(){
            restoring=true;try{table.clearSelection();((javax.swing.table.DefaultTableModel)table.getModel()).setRowCount(0);}finally{restoring=false;}
            countText.setText(STALE_COHORT);countText.setForeground(ContentStyle.color("rose"));
            details.setText("No comparison shown: the previous results were cleared because the cohort inputs are not valid.");details.setCaretPosition(0);
            revalidate();repaint();
        }
        private ArchiveRow<Row> selected(){int r=table.getSelectedRow();return r<0||r>=page.rows.size()?null:page.rows.get(r);}
        /** Exact drill-downs change query intent (applied before paging); none reads the visible rows as a population. */
        private JComponent drillActions(View view){
            JPanel panel=new JPanel(new BorderLayout(0,2));JPanel buttons=ContentStyle.controls();Facets f=current.query.facets();
            showOccurrences.setName("loot-drill-occurrences");showVisit.setName("loot-drill-visit");openRun.setName("loot-open-run");rateDetails.setName("loot-drill-rate");
            showOccurrences.getAccessibleContext().setAccessibleDescription("Show every saved occurrence of the selected exact item variant");
            showVisit.getAccessibleContext().setAccessibleDescription("Show loot from the selected row's verified recorded run");
            openRun.getAccessibleContext().setAccessibleDescription("Open the selected row's verified run in the Runs workspace");
            rateDetails.getAccessibleContext().setAccessibleDescription("Show the dungeon's eligible-run rate calculation");
            showOccurrences.addActionListener(e->{ArchiveRow<Row> row=selected();if(row==null||row.value.variantKey()==null)return;Facets next=current.query.facets();next.variant=row.value.variantKey();drill(next,View.OCCURRENCES);});
            showVisit.addActionListener(e->{ArchiveRow<Row> row=selected();tomato.history.link.VisitRef ref=row==null?null:row.value.visitRef();if(ref==null)return;Facets next=current.query.facets();next.visitSession=ref.sessionId;next.visitId=ref.visitId;drill(next,View.OCCURRENCES);});
            rateDetails.addActionListener(e->{ArchiveRow<Row> row=selected();if(row==null||row.value.dungeon==null||row.value.dungeon.isEmpty())return;Facets next=current.query.facets();next.variant=next.visitSession=next.visitId=null;next.dungeons=new LinkedHashSet<>(Collections.singleton(row.value.dungeon));drill(next,View.RATES);});
            openRun.addActionListener(e->{ArchiveRow<Row> row=selected();tomato.history.link.VisitRef ref=row==null?null:row.value.visitRef();if(ref==null)return;
                if(!Navigator.current().open(runRoute(ref)))linkStatus.setText("The Runs workspace did not accept run "+ref+"; nothing was opened.");});
            if(view.loot()){buttons.add(showOccurrences);buttons.add(showVisit);buttons.add(openRun);}buttons.add(rateDetails);
            JPanel lines=new JPanel();lines.setLayout(new BoxLayout(lines,BoxLayout.Y_AXIS));
            if(f.drilled()){JTextArea active=ContentStyle.wrappingText(drillSummary(f)+(f.visitSession!=null&&page.matches==0?VISIT_UNAVAILABLE:""));active.setName("loot-drill-summary");active.getAccessibleContext().setAccessibleName(active.getText());lines.add(active);
                JButton clear=new JButton("Clear drill-down");clear.setName("loot-clear-drill");clear.addActionListener(e->{Facets next=current.query.facets();next.variant=next.visitSession=next.visitId=null;query(current.query.withFacets(next));});buttons.add(clear);}
            // Run-link reasons apply only to the loot views that offer run actions; the rate button has its own reason.
            linkStatus.setName("loot-run-link-status");linkStatus.setVisible(view.loot());rateStatus.setName("loot-rate-status");
            for(JTextArea line:Arrays.asList(linkStatus,rateStatus)){line.setAlignmentX(0f);lines.add(line);}
            panel.add(buttons,BorderLayout.CENTER);panel.add(lines,BorderLayout.SOUTH);return panel;
        }
        private void drill(Facets next,View target){next.view=target;current=current.withPosition(target.name(),Collections.emptyList(),null,0);binding.viewChanged(current);query(current.query.withFacets(next));}
        private void updateDrill(ArchiveRow<Row> row){
            Row r=row==null?null:row.value;tomato.history.link.VisitRef ref=r==null?null:r.visitRef();
            showOccurrences.setEnabled(r!=null&&"variant".equals(r.type)&&r.variantKey()!=null);
            showVisit.setEnabled(ref!=null);rateDetails.setEnabled(r!=null&&r.dungeon!=null&&!r.dungeon.isEmpty()&&!"rate".equals(r.type));
            boolean navigable=ref!=null&&Navigator.current().canOpen(runRoute(ref));openRun.setEnabled(navigable);
            linkStatus.setText(runLinkStatus(r,ref,navigable));linkStatus.getAccessibleContext().setAccessibleName(linkStatus.getText());
            rateStatus.setText(rateStatus(r));rateStatus.getAccessibleContext().setAccessibleName(rateStatus.getText());
        }
        private void query(ArchiveQuery<Facets,Sort> q){binding.queryChanged(q);}
        private void savePosition(){if(restoring)return;current=HistoryTables.position(table,scroll,page,current);current=current.withPosition(current.query.facets().view.name(),current.selected,current.anchor,current.anchorOffset);binding.viewChanged(current);}
        private Set<String> choices(String prefix){Set<String> values=new TreeSet<>();for(String key:page.counts.keySet())if(key.startsWith(prefix))values.add(key.substring(prefix.length()));return values;}
        private void detail(ArchiveRow<Row> row){StringBuilder text=new StringBuilder("rate".equals(row.value.type)?RateCalculation.describe(row.value)+"\n\n":"").append("Origin: ").append(row.ref).append('\n');for(HistoryTables.Column<Row,?> column:columns()){Object value=column.value.apply(row.value);if(value!=null&&!value.toString().isEmpty())text.append(column.label).append(": ").append(value).append(readable(column.id,value)).append('\n');}
            if("occurrence".equals(row.value.type))text.append("Exact enchantment evidence: ").append(row.value.enchantEvidence==null?"Not recorded":row.value.enchantEvidence).append("\nDrop context: ").append(row.value.dropContext==null?"Not recorded":row.value.dropContext).append('\n');
            details.setText(text.toString());details.setCaretPosition(0);}
        private JComponent analyticalFilters(View view){
            if(view==View.COHORTS){Map<String,String> sessions=new TreeMap<>();page.counts.forEach((key,value)->{if(key.startsWith("facet.session."))sessions.put(key.substring(14),value.population);});
                return new CohortControls(current.query.facets(),sessions,ZoneId.of(current.query.bounds().zone),f->query(current.query.withFacets(f)),this::cohortInputInvalid);}
            JPanel p=ContentStyle.controls();Facets f=current.query.facets();JTextField dungeon=new JTextField(String.join(";",f.dungeons),16),identity=new JTextField(view==View.FAME?f.character:f.enemy,8);
            dungeon.getAccessibleContext().setAccessibleName("Exact dungeons separated by semicolons");identity.getAccessibleContext().setAccessibleName(view==View.FAME?"Exact character ID":"Exact enemy ID");
            p.add(new JLabel("Dungeons (semicolon-separated)"));p.add(dungeon);if(view==View.FAME||view==View.ENEMIES||view==View.SOURCES){p.add(new JLabel(view==View.FAME?"Character ID":"Enemy ID"));p.add(identity);}
            JButton apply=new JButton("Apply analytical filters");p.add(apply);apply.addActionListener(e->{Facets next=current.query.facets();next.dungeons=new LinkedHashSet<>();for(String name:dungeon.getText().split(";"))if(!name.trim().isEmpty())next.dungeons.add(name.trim());if(view==View.FAME)next.character=identity.getText().trim();else next.enemy=identity.getText().trim();query(current.query.withFacets(next));});return p;
        }
        private JComponent dateControls(){
            JPanel p=ContentStyle.controls();ArchiveQuery.Bounds b=current.query.bounds();JTextField from=new JTextField(b.from==null?"":Instant.ofEpochMilli(b.from).toString(),16),until=new JTextField(b.until==null?"":Instant.ofEpochMilli(b.until).toString(),16),zone=new JTextField(b.zone,10);
            from.setName("loot-date-from");until.setName("loot-date-until");zone.setName("loot-date-zone");from.getAccessibleContext().setAccessibleName("From inclusive ISO timestamp");until.getAccessibleContext().setAccessibleName("Until exclusive ISO timestamp");zone.getAccessibleContext().setAccessibleName("Resolved time zone");
            JCheckBox unknown=new JCheckBox("Include unknown times",b.includeUnknown);JComboBox<ArchiveQuery.TimeMode> mode=new JComboBox<>(ArchiveQuery.TimeMode.values());mode.setSelectedItem(b.mode);mode.getAccessibleContext().setAccessibleName("Visit time inclusion");
            p.add(new JLabel("From ["));p.add(from);p.add(new JLabel("Until )"));p.add(until);p.add(zone);p.add(unknown);p.add(mode);JButton apply=new JButton("Apply dates"),clear=new JButton("All time");p.add(apply);p.add(clear);JLabel error=new JLabel();p.add(error);
            apply.addActionListener(e->{try{ZoneId z=ZoneId.of(zone.getText().trim());query(current.query.withBounds(new ArchiveQuery.Bounds(LootQuery.resolveTime(from.getText(),z),LootQuery.resolveTime(until.getText(),z),z,(ArchiveQuery.TimeMode)mode.getSelectedItem(),unknown.isSelected())));}catch(RuntimeException failure){error.setText(failure.getMessage());}});
            clear.addActionListener(e->query(current.query.withBounds(ArchiveQuery.Bounds.all())));return p;
        }
        private void openFame(JButton button){
            int selected=table.getSelectedRow();if(selected<0){details.setText("Select a session or character first.");return;}String session=page.rows.get(selected).value.session;
            try{ArchiveResult.Lease<Row> lease=page.lease();button.setEnabled(false);new SwingWorker<FameSession,Void>(){
                protected FameSession doInBackground()throws Exception{try(ArchiveResult.Lease<Row> held=lease){return readFame(held,session,new Cancellation());}}
                protected void done(){button.setEnabled(true);try{FameSession fame=get();if(!Render.this.isShowing())return;if(fame.getCharacterFameData().isEmpty())details.setText("No fame samples captured for this pinned session.");else new FameSessionViewer(fame);}catch(Exception failure){details.setText("Fame graph unavailable: "+failure.getMessage());}}
            }.execute();}catch(IOException failure){details.setText(failure.getMessage());}
        }
    }
    /** Details keep the exact stored value and add the on-screen form for timestamps and durations. */
    static String readable(String id,Object value){
        if(!(value instanceof Number))return "";long v=((Number)value).longValue();
        if("time".equals(id))return v<=0?" (undated)":" ("+DisplayFormat.formatTimestamp(v)+")";
        if("millis".equals(id)||"average".equals(id))return " ("+DisplayFormat.formatDurationHMS(v)+")";
        return "";
    }
    static Route runRoute(tomato.history.link.VisitRef ref){return Route.to(Destination.RUNS).withVisit(ref);}
    static final String STALE_COHORT="Previous comparison cleared. Correct the cohort input shown above, then choose Compare cohorts to see current results.";
    static final String VISIT_UNAVAILABLE=" · Linked run unavailable here: no saved loot for this exact run (imported, deleted or unsaved session). No other run is substituted.";
    static String drillSummary(Facets f){StringJoiner parts=new StringJoiner(" · ");if(f.variant!=null)parts.add("exact variant "+f.variant+" (item ID/slots/applied)");if(f.visitSession!=null)parts.add("exact run "+f.visitSession+"/"+f.visitId);return "Drill-down: "+parts;}
    /** Explains exactly why a selected row can or cannot open its recorded run. */
    static String runLinkStatus(Row r,tomato.history.link.VisitRef ref,boolean navigable){
        if(r==null)return "Select an occurrence or bag to follow its recorded run; select a variant for its occurrences.";
        if(r.runLinked==null)return "variant".equals(r.type)?"Variants combine many runs; open their occurrences to reach one exact run.":"This row is not a single drop; no single run applies.";
        if(ref==null)return r.visitId==null||r.visitId.isEmpty()?"Run unavailable: no recorded visit ID (legacy or unlinked record).":"Run unavailable: visit "+r.visitId+" has no agreeing saved run in this session.";
        return navigable?"Verified run "+ref+" can be opened.":"Verified run "+ref+"; Runs view unavailable in this window, so it cannot be opened here.";
    }
    /** Why "Dungeon rate calculation" is or is not available for the selected row. */
    static String rateStatus(Row r){
        if(r==null)return "Dungeon rate calculation: select a row that has a dungeon.";
        if("rate".equals(r.type))return "Dungeon rate calculation: this row already is the "+(r.dungeon==null||r.dungeon.isEmpty()?r.name:r.dungeon)+" rate; its calculation is in the details above.";
        if(r.dungeon==null||r.dungeon.isEmpty())return "Dungeon rate calculation: unavailable because this row has no recorded dungeon.";
        return "Dungeon rate calculation: shows eligible-run rates for "+r.dungeon+".";
    }
    static String description(View view){return view.loot()?"All saved occurrences are queried before grouping and paging. Recent Drops is globally paged, not the live 1,000-bag window. Unknown enchant values are not zero. Text: item ID/name, bag, dungeon, dropper, tier, rarity."
        :view.counters()?"Undated counters: custom periods unsupported. Text searches dungeon / enemy / item labels for this tab. Item facets are not applied."
        :view==View.COHORTS?"Controlled A/B comparison. Both cohorts share the dungeon, outcome, date-bound and loot-coverage predicates; each adds its own sessions and visit-entry bounds. Eligibility and rates follow Dungeon loot profile: imports and sessions without loot evidence are excluded, zero-loot runs count, unassigned bags make rates unavailable. Totals depend on cohort size; compare per-run/per-hour rates and distributions. A zero baseline has no percentage change."
        :view==View.FAME?"Text searches session, character ID and class; bounds select fame samples. Undated observations are counted separately, never ordered as epoch zero; incomplete chronology has no gain/elapsed interval. Loot facets and dungeon selection do not filter fame (map association not captured). Open graph uses the whole pinned session."
        :"Whole-visit analytical cohort: scope, dungeon and visit entry/overlap bounds. Item/bag/enchant facets are not applied. Text searches "+(view==View.SESSIONS?"session label/build/ID":"dungeon names")+". Saved bag evidence establishes eligibility per session; unknown sessions remain excluded.";}
    private static String countDescription(ArchivePage<Row> page){StringJoiner s=new StringJoiner(" · ");page.counts.forEach((key,value)->{if(!key.startsWith("facet."))s.add(key+": "+value.value+" "+value.unit+" ["+value.population+"]");});return s.toString();}
    private static List<String> visible(ViewState.Table layout){List<String> ids=new ArrayList<>();for(ViewState.Column c:layout.columns)if(c.visible)ids.add(c.id);return ids;}
    private static ViewState.Table compact(ViewState.Table defaults,View view){
        Set<String> ids=new LinkedHashSet<>(view==View.OCCURRENCES?Arrays.asList("time","name","dungeon","bag","slots","applied","runLink"):view==View.RECENT?Arrays.asList("time","name","bag","dungeon","items"):view==View.RATES?Arrays.asList("name","items","runs","zeroLoot","millis","perRun","rate","unknown","imports","unassigned"):view==View.SESSIONS?Arrays.asList("name","runs","millis","items","gain"):view==View.FAME?Arrays.asList("name","first","last","gain"):view==View.COUNTERS?Arrays.asList("name","runs","millis","average","hits","items","ongoing"):view==View.ENEMIES?Arrays.asList("name","dungeon","hits","items"):view==View.SOURCES?Arrays.asList("name","dungeon","dropper","item","count"):view==View.COHORTS?Arrays.asList("name","runs","items","millis","perRun","rate","median","runChange","hourChange","unknown","imports"):Arrays.asList("name","count","bags","items","slots","applied"));
        List<ViewState.Column> columns=new ArrayList<>();for(ViewState.Column c:defaults.columns)columns.add(new ViewState.Column(c.id,c.width,ids.contains(c.id)));return new ViewState.Table("Compact",columns);
    }
    /** Whole-session graph from the same pin; bounded points, never a fresh live file read. */
    static FameSession readFame(ArchiveResult.Lease<Row> lease,String session,Cancellation cancel)throws IOException{
        Map<Integer,TreeMap<Long,FameSession.SampleVisit>> associations=new TreeMap<>();
        Map<Integer,TreeMap<Long,Fame>> dated=new TreeMap<>();Map<Integer,List<Fame>> undated=new TreeMap<>();Map<Integer,String> names=new HashMap<>();
        List<HashMap<Integer,List<tomato.gui.stats.data.MapFameData>>> maps=new ArrayList<>();maps.add(new HashMap<>());
        FameSession[] single={null};long[] sourceRecords={0},storedDates={0,0};int[] count={0};
        lease.readSource(session,"fame-snapshots",JsonObject.class,row->{
            FameSession old=SessionStore.JSON.fromJson(row.value,FameSession.class);sourceRecords[0]++;
            if(sourceRecords[0]==1){single[0]=old;storedDates[0]=storedTime(row.value,"createdTimestamp");storedDates[1]=storedTime(row.value,"lastModifiedTimestamp");}
            if(old.getCharacterClassNames()!=null)names.putAll(old.getCharacterClassNames());LootArchiveAdapter.bounded(names.size(),100000,"Graph character labels");
            if(old.getCharacterMapFameData()!=null)maps.set(0,old.getCharacterMapFameData());
            if(old.getCharacterFameData()!=null)for(Map.Entry<Integer,List<Fame>> e:old.getCharacterFameData().entrySet())for(Fame f:e.getValue()){cancel.check();graphPoint(dated,undated,e.getKey(),f,count);}
        },cancel);
        for(String module:Arrays.asList("fame","fame-latest"))lease.readSource(session,module,AppHistory.FameSample.class,row->{
            sourceRecords[0]++;AppHistory.FameSample f=row.value;names.put(f.character,f.className);graphPoint(dated,undated,f.character,new Fame(f.fame,f.time),count);
            tomato.history.link.VisitRef visit=f.visit();if(visit!=null){LootArchiveAdapter.label(f.map);LootArchiveAdapter.label(f.visitId);associations.computeIfAbsent(f.character,k->new TreeMap<>()).put(f.time,new FameSession.SampleVisit(f.time,visit,f.map));}
        },cancel);
        String revision=lease.manifest().get("revision").getAsString();
        if(sourceRecords[0]==1&&single[0]!=null)return FameSession.pinnedSnapshot(single[0],revision,session,storedDates[0],storedDates[1]);
        FameSession result=FameSession.archiveProjection("Pinned saved session · "+session,revision,session,sourceRecords[0]);
        result.getCharacterClassNames().putAll(names);result.getCharacterMapFameData().putAll(maps.get(0));
        dated.forEach((id,points)->result.getCharacterFameData().put(id,new ArrayList<>(points.values())));
        undated.forEach((id,points)->result.getCharacterFameData().computeIfAbsent(id,key->new ArrayList<>()).addAll(points));
        // Only journal samples that carried an exact visit are associated; equal-time ties keep the journal/checkpoint that won the point.
        associations.forEach((id,visits)->visits.forEach((time,visit)->{TreeMap<Long,Fame> points=dated.get(id);if(time<=0||points!=null&&points.containsKey(time))result.addSampleVisit(id,visit);}));
        return result;
    }
    private static long storedTime(JsonObject snapshot,String field){return snapshot.has(field)&&!snapshot.get(field).isJsonNull()?snapshot.get(field).getAsLong():0;}
    private static void graphPoint(Map<Integer,TreeMap<Long,Fame>> dated,Map<Integer,List<Fame>> undated,int character,Fame sample,int[] count)throws IOException{
        if(sample.getTime()<=0){if(++count[0]>100000)throw new IOException("Graph exceeds 100000 dated/undated observations; no samples were truncated.");undated.computeIfAbsent(character,key->new ArrayList<>()).add(sample);return;}
        TreeMap<Long,Fame> points=dated.computeIfAbsent(character,k->new TreeMap<>());if(points.put(sample.getTime(),sample)==null&&++count[0]>100000)throw new IOException("Graph exceeds 100000 dated/undated observations; use the paged fame summaries. No samples were truncated.");
    }
}
