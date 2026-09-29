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
import tomato.gui.kit.ColumnKind;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.KitTables;
import tomato.gui.kit.ViewSelector;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.route.*;
import tomato.gui.stats.LootQuery.*;
import tomato.gui.stats.session.*;
import tomato.history.*;
import tomato.history.archive.*;

/** One independently persisted workspace; every inner archive filter sends query intent upstream. */
public final class LootArchiveClient implements ArchiveClient<Row,Facets,Sort> {
    private final Path scratch;
    /** The views the view selector offers (a routed or restored view outside them is still shown, as its "Current view"). */
    private final Set<View> views;private final ArchiveQuery<Facets,Sort> initial;
    /**
     * Loot › Explore's one view and selector (the live dashboard's, in the filter row: the saved view builds no view row), its
     * display mode for the saved presentation, and the ⋯ that takes the drill-downs; null elsewhere.
     */
    private final LootExploreModel explore;
    /**
     * A client whose view selector offers only {@code views} (in the views' usual order), opening on {@code initial}: Loot ›
     * Explore's saved half, Dungeons › Analysis (rates, sessions, counters, enemies, sources and cohorts) and Characters › Fame
     * history (fame). Queries, rows, drill-downs and exports are the same for every view set.
     *
     * @throws IllegalArgumentException when {@code views} is empty or does not offer {@code initial}'s view
     */
    public LootArchiveClient(Path scratch,Set<View> views,ArchiveQuery<Facets,Sort> initial){this(scratch,views,initial,null);}
    /**
     * Loot › Explore's saved half: {@code views}, chosen with {@code explore}'s one selector (Simple and Analyst lists; each choice
     * carried to the live dashboard). In Simple its saved view is plainer (Polish B4). Null {@code explore}: the view-subset client above.
     */
    LootArchiveClient(Path scratch,Set<View> views,ArchiveQuery<Facets,Sort> initial,LootExploreModel explore){
        this.scratch=scratch;this.initial=Objects.requireNonNull(initial,"initial");this.explore=explore;
        if(views==null||views.isEmpty())throw new IllegalArgumentException("A workspace offers at least one view");
        if(!views.contains(initial.facets().view))throw new IllegalArgumentException("The initial view "+initial.facets().view.name()+" is not offered");
        this.views=Collections.unmodifiableSet(EnumSet.copyOf(views));
    }
    /** The views the view selector offers. */
    public Set<View> views(){return views;}
    /** A view's tooltip in the view row's selector (not built on Loot › Explore): the item views' definitions. */
    private String tooltip(View view){return LootExploreModel.live(view)?LootExploreModel.tooltip(view):null;}
    public ArchiveQuery<Facets,Sort> initialQuery(){return initial;}
    public Path scratchDirectory(){return scratch;}
    public int pageSize(){return 100;}
    public ArchiveAdapter<Row,Facets,Sort> adapter(ArchiveQuery<Facets,Sort> q){View view=q.facets().view;return view.loot()?new LootArchiveAdapter(q):view==View.COHORTS?new CohortArchiveAdapter(q):new StatisticsArchiveAdapter(q);}
    private Render rendered;
    public JComponent render(ArchivePage<Row> page,ViewState<Facets,Sort> state,Binding<Facets,Sort> binding){rendered=new Render(page,state,binding);return rendered;}
    /** The rendered view's facet and date controls for the drawer; drill-down actions and cohort inputs stay in the view. */
    @Override public ArchiveFilters filters(ArchivePage<Row> page,ViewState<Facets,Sort> state,Binding<Facets,Sort> binding){return rendered!=null&&rendered.binding==binding?rendered.filters():null;}
    public List<ArchiveExport.Column<Row>> exportColumns(){
        List<ArchiveExport.Column<Row>> result=new ArrayList<>();for(HistoryTables.Column<Row,?> column:columns())result.add(new ArchiveExport.Column<>(column.label,column.value));
        result.add(new ArchiveExport.Column<>("Exact enchantment evidence",r->r.enchantEvidence));
        result.add(new ArchiveExport.Column<>("Captured drop context",r->r.dropContext));return result;
    }
    private static <V> HistoryTables.Column<Row,V> col(String id,String label,Class<V> type,java.util.function.Function<Row,V> value,ColumnKind kind){return new HistoryTables.Column<>(id,label,type,value,null,kind);}
    static List<HistoryTables.Column<Row,?>> columns(){return Arrays.asList(
        col("type","Row unit/type",String.class,r->r.type,ColumnKind.STATUS),new HistoryTables.Column<>("time","Timestamp (epoch ms)",Long.class,r->r.time,timeRenderer(),ColumnKind.DATE_TIME),col("name","Name",String.class,r->r.name,ColumnKind.ITEM),
        col("session","Source session",String.class,r->r.session,ColumnKind.ID),col("visit","Recorded visit ID (within session)",String.class,r->r.visitId,ColumnKind.ID),new HistoryTables.Column<>("dungeon","Dungeon",String.class,r->r.dungeon,areaRenderer(),ColumnKind.DUNGEON),new HistoryTables.Column<>("bag","Bag",String.class,r->r.bag,blankRenderer(ColumnKind.STATUS),ColumnKind.STATUS),col("dropper","Dropper",String.class,r->r.dropper,ColumnKind.TEXT),
        col("item","Item ID",Integer.class,r->r.itemId,ColumnKind.ID),col("tier","Tier",String.class,r->r.tier,ColumnKind.STATUS),col("rarity","Rarity",String.class,r->r.rarity,ColumnKind.STATUS),col("slots","Slots",Integer.class,r->r.slots,ColumnKind.COUNT),col("applied","Applied enchants",Integer.class,r->r.applied,ColumnKind.COUNT),
        col("count","Occurrences / sample observations / count",Long.class,r->r.count,ColumnKind.COUNT),col("bags","Bags",Long.class,r->r.bags,ColumnKind.COUNT),col("items","Items",Long.class,r->r.items,ColumnKind.COUNT),col("runs","Visits / activity-recorded exits",Long.class,r->r.runs,ColumnKind.COUNT),new HistoryTables.Column<>("millis","Observed / finalized milliseconds",Long.class,r->r.millis,durationRenderer(),ColumnKind.DURATION),new HistoryTables.Column<>("average","Average finalized milliseconds / exit",Long.class,r->r.averageMillis,durationRenderer(),ColumnKind.DURATION),
        col("whites","White bags",Long.class,r->r.whites,ColumnKind.COUNT),col("uts","UT gear",Long.class,r->r.uts,ColumnKind.COUNT),col("sts","ST gear",Long.class,r->r.sts,ColumnKind.COUNT),col("potions","Stat potions",Long.class,r->r.potions,ColumnKind.COUNT),col("completed","Completed",Long.class,r->r.completed,ColumnKind.COUNT),col("unknown","Excluded unknown visits",Long.class,r->r.unknownRuns,ColumnKind.COUNT),col("imports","Excluded imported visits",Long.class,r->r.importedRuns,ColumnKind.COUNT),
        col("rate","Items / hour",Double.class,r->r.perHour,ColumnKind.NUMBER),col("perRun","Items / run",Double.class,r->r.perRun,ColumnKind.NUMBER),col("utHour","UT / hour",Double.class,r->r.utPerHour,ColumnKind.NUMBER),col("whiteRun","Whites / run",Double.class,r->r.whitesPerRun,ColumnKind.NUMBER),col("utRun","UT / run",Double.class,r->r.utPerRun,ColumnKind.NUMBER),col("stRun","ST / run",Double.class,r->r.stPerRun,ColumnKind.NUMBER),col("potionRun","Potions / run",Double.class,r->r.potionsPerRun,ColumnKind.NUMBER),
        col("character","Character ID (within session)",Integer.class,r->r.character,ColumnKind.ID),col("class","Class",String.class,r->r.className,ColumnKind.CLASS),col("first","First fame",Double.class,r->r.firstFame,ColumnKind.NUMBER),col("last","Last fame",Double.class,r->r.lastFame,ColumnKind.NUMBER),col("gain","Fame change",Double.class,r->r.gain,ColumnKind.NUMBER),col("enemy","Enemy ID",Integer.class,r->r.enemyId,ColumnKind.ID),col("hits","Hit events",Long.class,r->r.hits,ColumnKind.COUNT),col("damage","Damage",Long.class,r->r.damage,ColumnKind.NUMBER),col("build","Build",String.class,r->r.build,ColumnKind.TEXT),col("ongoing","Ongoing contribution at counter snapshot",String.class,r->"COUNTERS".equals(r.type)?r.ongoingActivity==null?"Not captured":r.ongoingActivity?"Included; time not finalized":"None at snapshot":null,ColumnKind.STATUS),col("runLink","Recorded run link",String.class,r->r.runLinked==null?null:r.runLinked?"Verified":"Unavailable",ColumnKind.STATUS),col("zeroLoot","Eligible runs with no linked bags",Long.class,r->r.zeroLootRuns,ColumnKind.COUNT),col("unassigned","Unassigned bags",Long.class,r->r.unassignedBags,ColumnKind.COUNT),
        col("minRun","Minimum items / run",Long.class,r->r.minPerRun,ColumnKind.COUNT),col("median","Median items / run",Double.class,r->r.medianPerRun,ColumnKind.NUMBER),col("maxRun","Maximum items / run",Long.class,r->r.maxPerRun,ColumnKind.COUNT),col("runChange","Items / run change (% of baseline)",Double.class,r->r.perRunChange,ColumnKind.PERCENT),col("hourChange","Items / hour change (% of baseline)",Double.class,r->r.perHourChange,ColumnKind.PERCENT),
        col("evidence","Calculation / coverage",String.class,r->r.evidence,ColumnKind.TEXT));}
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
    /**
     * Display only (Polish B3): a blank saved value (a bag saved without a name) reads "—", as a missing one does; the model,
     * sorting, Copy and CSV keep "".
     */
    static javax.swing.table.TableCellRenderer blankRenderer(ColumnKind kind){javax.swing.table.TableCellRenderer base=KitTables.renderer(kind);
        return (table,value,selected,focus,row,column)->base.getTableCellRendererComponent(table,value==null||value.toString().trim().isEmpty()?null:value,selected,focus,row,column);}
    /**
     * Display only (Polish B2): an area capture could not name ("Unknown", "Unrecognized area") reads {@value LootFacts#UNKNOWN_AREA};
     * a blank one (a summary of many areas, such as a bag type) reads "—". Keys, facets, drills and exports keep the saved name.
     */
    static javax.swing.table.TableCellRenderer areaRenderer(){javax.swing.table.TableCellRenderer base=KitTables.renderer(ColumnKind.DUNGEON);
        return (table,value,selected,focus,row,column)->base.getTableCellRendererComponent(table,value==null||value.toString().trim().isEmpty()?null:LootFacts.areaLabel(value.toString()),selected,focus,row,column);}
    /** Millisecond durations render as h:mm:ss; unknown stays distinct from a recorded zero. */
    static javax.swing.table.TableCellRenderer durationRenderer(){ContentStyle.Cell cell=new ContentStyle.Cell(){protected void setValue(Object value){
        setText(value==null?DisplayFormat.UNAVAILABLE:DisplayFormat.formatDurationHMS(((Number)value).longValue()));}};cell.setHorizontalAlignment(SwingConstants.RIGHT);return cell;}
    /** Short headers with full-label tooltips; every column is at least as wide as its header and sized to this page's values (capped). */
    static void sizeColumns(JTable table){
        javax.swing.table.TableCellRenderer base=table.getTableHeader().getDefaultRenderer();Map<String,String> labels=new HashMap<>();Map<String,ColumnKind> kinds=new HashMap<>();
        for(HistoryTables.Column<Row,?> c:columns()){labels.put(c.id,c.label);kinds.put(c.id,c.kind);}
        for(javax.swing.table.TableColumn column:Collections.list(table.getColumnModel().getColumns())){
            String id=column.getIdentifier().toString(),full=labels.getOrDefault(id,String.valueOf(column.getHeaderValue()));
            column.setHeaderValue(HEADERS.getOrDefault(id,full));
            column.setHeaderRenderer((t,value,selected,focus,row,index)->{Component c=base.getTableCellRendererComponent(t,value,selected,focus,row,index);if(c instanceof JComponent)((JComponent)c).setToolTipText(full);return c;});
            int header=headerWidth(table,column),content=0,model=column.getModelIndex();
            for(int row=0;row<table.getRowCount();row++)content=Math.max(content,table.prepareRenderer(table.getCellRenderer(row,table.convertColumnIndexToView(model)),row,table.convertColumnIndexToView(model)).getPreferredSize().width);
            // The column kind is the floor; Loot still grows a column to its page's values (capped at 320 px) so no value is cut.
            ColumnKind kind=kinds.get(id);int width=Math.max(header,Math.max(kind==null?0:kind.width(table.getFont()),Math.min(content+table.getIntercellSpacing().width+2,320)));
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
        private final HistoryTables.ColumnTools tools;
        private boolean restoring=true;
        private final JTextArea linkStatus=ContentStyle.wrappingText(" "),rateStatus=ContentStyle.wrappingText(" ");
        private JTextArea countText;
        /** After a cohort input error the count line keeps its warning until a valid Compare re-renders. */
        private boolean staleCounts;
        /** The drill-downs: ⋯ items on Loot › Explore (Polish B4), a button row under the table elsewhere (the rate drill on Dungeons › Analysis). */
        private final AbstractButton showOccurrences=drillAction("Occurrences of selected variant"),showVisit=drillAction("Loot from selected run"),
            openRun=drillAction("Open recorded run"),rateDetails=drillAction("Dungeon rate calculation");
        /** The view row's selector: only without Explore, whose one selector leads the workspace's filter row. */
        private ViewSelector<View> selector;
        /** Fame history's Name column: the layout's own width (-1: not fitted), the width fitted to the viewport (-1: none), and the fit's guards. */
        private int nameWidth=-1,fitted=-1;private boolean fitting,refitting;
        Render(ArchivePage<Row> page,ViewState<Facets,Sort> state,Binding<Facets,Sort> binding){
            super(new BorderLayout(0,5));this.page=page;this.current=state;this.binding=binding;View view=state.query.facets().view;
            // One view selector replaces the tabs (P6a): the offered views, and a routed or restored view outside them as its "Current view".
            // On Loot › Explore that selector is the live dashboard's (P6b), in the filter row; elsewhere it heads the saved view.
            if(explore==null)add(head(view),BorderLayout.NORTH);
            JPanel body=new JPanel(new BorderLayout(0,4));add(body,BorderLayout.CENTER);
            JPanel top=new JPanel(new BorderLayout(0,4));
            // Cohort inputs define the comparison itself, so they stay in the view; every other facet lives in the Filters drawer.
            if(view==View.COHORTS)top.add(analyticalFilters(view),BorderLayout.NORTH);
            countText=ContentStyle.wrappingText("");countText.setName("loot-archive-counts");top.add(countText,BorderLayout.SOUTH);body.add(top,BorderLayout.NORTH);
            // Explore's count line follows the mode (Simple: one plain line); the mode never changes the query.
            if(explore!=null)explore.mode().bind(this,mode->counts());else counts();
            table=HistoryTables.queried("loot-archive-table",columns(),page,sorts(),state.query,this::query,this::detail);sizeColumns(table);
            ViewState.Table defaults=HistoryTables.columnState(table,"All columns");ViewState.Table compact=compact(defaults,view);
            HistoryTables.applyColumns(table,current.tables.getOrDefault(view.name(),compact));
            // Polish B4: an Items column that reads "—" on every row says nothing in Simple, so Explore's Simple leaves it out of every
            // layout; Analyst shows it as the layout says. Display only: saved layouts keep the user's own choice for it.
            if(explore!=null&&page.rows.stream().allMatch(row->row.value.items==null))KitTables.analystOnly(table,explore.mode(),"items");
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
            // The column tools go to the workspace ⋯ (filters()); moves and resizes are still remembered with the view state.
            java.util.function.Consumer<ViewState.Table> saveLayout=layout->{current=current.withTable(view.name(),layoutWidths(layout));savePosition();};
            HistoryTables.rememberLayout(table,saveLayout);tools=HistoryTables.columnTools(table,defaults,presets,saveLayout);
            if(view.loot()||view==View.RATES)actions.add(drillActions(view),BorderLayout.NORTH);
            if(view==View.SESSIONS||view==View.FAME){JButton graph=new JButton("Open selected session's full fame graph");graph.setName("archive-open-fame");graph.addActionListener(e->openFame(graph));actions.add(graph,BorderLayout.SOUTH);}
            body.add(actions,BorderLayout.SOUTH);
            table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&!restoring){savePosition();int r=table.getSelectedRow();if(r>=0)detail(page.rows.get(r));updateDrill(selected());}});
            scroll.getViewport().addChangeListener(e->{if(!restoring)savePosition();});HistoryTables.restorePosition(table,scroll,page,current);
            if(view==View.FAME)fillName();
            restoring=false;
            ArchiveRow<Row> restored=selected();if(restored!=null)detail(restored);updateDrill(restored);
            if(explore!=null){
                // Explore's drill-downs are ⋯ items: they follow this view's enablement (the workspace disables a stale view), and the
                // selector shows the view rendered (a choice, restore, route or drill).
                addPropertyChangeListener("enabled",e->updateDrill(selected()));
                explore.drills(view.loot()?Arrays.asList(showOccurrences,showVisit,openRun,rateDetails):view==View.RATES?Collections.singletonList(rateDetails):Collections.<Component>emptyList());
                explore.rendered(current);
            }
        }
        /** A drill-down action: an item of the workspace ⋯ on Loot › Explore, a button in the view elsewhere. */
        private AbstractButton drillAction(String text){return explore!=null?new JMenuItem(text):new JButton(text);}
        /**
         * The count line. Analyst (and every workspace without Explore): the view's description and every count with its unit and
         * population. Explore in Simple (Polish B4): one plain line such as "9 bags · 15 item variants · 22 items", with the full text
         * in its tooltip; the accessible description is always the full text.
         */
        private void counts(){
            if(staleCounts)return;
            View view=current.query.facets().view;String full=description(view)+"\n"+countDescription(page);
            boolean plain=explore!=null&&!explore.mode().analyst();
            countText.setText(plain?plainCounts(view,page):full);countText.setToolTipText(plain?countTooltip(view,page):null);
            countText.getAccessibleContext().setAccessibleDescription(full);
        }
        /**
         * The view row: the selector over the offered views, in the views' usual order, never changing the query. A workspace that
         * offers one view (Characters › Fame history) has nothing to choose, so the row is hidden while that view is shown; a routed or
         * restored view outside it keeps the row, whose selector leads back to the offered view. Not built on Loot › Explore.
         */
        private JComponent head(View view){
            selector=new ViewSelector<>("loot-archive-view",View::toString,LootArchiveClient.this::tooltip);
            JPanel row=ContentStyle.controls();row.setName("loot-archive-view-row");row.add(selector.component());
            row.setVisible(views.size()>1||!views.contains(view));
            List<View> offered=new ArrayList<>();for(View candidate:View.values())if(views.contains(candidate))offered.add(candidate);
            selector.setItems(offered,Collections.<View>emptyList(),view);selector.select(view);
            selector.onChange(this::choose);
            return row;
        }
        /** A user's choice in the view row: the tabs' logic (the view, its own position, the query). */
        private void choose(View selected){
            if(restoring)return;Facets f=current.query.facets();f.view=selected;current=current.withPosition(selected.name(),Collections.emptyList(),null,0);binding.viewChanged(current);query(current.query.withFacets(f));
        }
        /**
         * Polish B7 (Fame history): while the columns fit, Name takes the table's spare width instead of the table ending at about
         * 60 %. Display only: {@link #nameWidth} is the layout's own Name width (the applied layout, a preset or Reset, or the user's
         * drag); the fitted width is never saved as a layout change, and a layout saved for another reason records the layout's own
         * Name width ({@link #layoutWidths}).
         */
        private void fillName(){
            javax.swing.table.TableColumn name=nameColumn();if(name!=null)nameWidth=name.getWidth();
            scroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter(){@Override public void componentResized(java.awt.event.ComponentEvent e){fitName();}});
            table.getColumnModel().addColumnModelListener(new javax.swing.event.TableColumnModelListener(){
                // A column shown or hidden, or another width, changes what is spare: refit after the layout the change triggers.
                public void columnAdded(javax.swing.event.TableColumnModelEvent e){refit();}
                public void columnRemoved(javax.swing.event.TableColumnModelEvent e){refit();}
                public void columnMoved(javax.swing.event.TableColumnModelEvent e){}
                public void columnMarginChanged(javax.swing.event.ChangeEvent e){
                    if(fitting)return;javax.swing.table.TableColumn shown=nameColumn();
                    // Name changed by anything but the fit (a drag, a preset, Reset, a restored layout): that is the layout's width now.
                    if(shown!=null&&nameWidth>=0&&shown.getWidth()!=(fitted<0?nameWidth:fitted)){nameWidth=shown.getWidth();fitted=-1;}
                    refit();
                }
                public void columnSelectionChanged(javax.swing.event.ListSelectionEvent e){}
            });
        }
        private void refit(){if(refitting)return;refitting=true;SwingUtilities.invokeLater(()->{refitting=false;fitName();});}
        private void fitName(){
            javax.swing.table.TableColumn name=nameColumn();int viewport=scroll.getViewport().getWidth();
            if(name==null||viewport<=0||table.getTableHeader().getResizingColumn()!=null)return;
            if(nameWidth<0)nameWidth=name.getWidth();   // Name was hidden when the view was built
            int others=0;for(javax.swing.table.TableColumn column:Collections.list(table.getColumnModel().getColumns()))if(column!=name)others+=column.getWidth();
            int width=Math.max(nameWidth,viewport-others);
            if(width!=name.getWidth()){
                // Marked as HistoryTables' own change, so the layout listener does not save the fitted width.
                Object before=table.getClientProperty(HistoryTables.RESTORING_COLUMNS);table.putClientProperty(HistoryTables.RESTORING_COLUMNS,true);fitting=true;
                try{name.setPreferredWidth(width);name.setWidth(width);}finally{fitting=false;table.putClientProperty(HistoryTables.RESTORING_COLUMNS,before);}
            }
            fitted=width==nameWidth?-1:width;
        }
        /** A layout to save: Fame history's Name keeps the layout's own width, never the width fitted to the viewport. */
        private ViewState.Table layoutWidths(ViewState.Table layout){
            if(nameWidth<0)return layout;List<ViewState.Column> columns=new ArrayList<>();
            for(ViewState.Column column:layout.columns)columns.add("name".equals(column.id)?new ViewState.Column(column.id,nameWidth,column.visible):column);
            return new ViewState.Table(layout.preset,columns);
        }
        private javax.swing.table.TableColumn nameColumn(){
            for(javax.swing.table.TableColumn column:Collections.list(table.getColumnModel().getColumns()))if("name".equals(column.getIdentifier()))return column;
            return null;
        }
        /** After a cohort input error the shown comparison no longer matches the inputs: clear it until a valid Compare runs. */
        private void cohortInputInvalid(){
            restoring=true;try{table.clearSelection();((javax.swing.table.DefaultTableModel)table.getModel()).setRowCount(0);}finally{restoring=false;}
            staleCounts=true;countText.setToolTipText(null);countText.setText(STALE_COHORT);countText.setForeground(ContentStyle.color("rose"));
            details.setText("No comparison shown: the previous results were cleared because the cohort inputs are not valid. Export still uses the last applied comparison, not these invalid inputs.");details.setCaretPosition(0);
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
            // Explore puts them in the workspace ⋯ (the constructor hands them over); the drill summary, Clear and the reasons stay here.
            if(explore==null){if(view.loot()){buttons.add(showOccurrences);buttons.add(showVisit);buttons.add(openRun);}buttons.add(rateDetails);}
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
            // ⋯ items are outside this view's tree, so they also follow its enablement (a stale view is disabled until replaced).
            boolean usable=explore==null||isEnabled();
            showOccurrences.setEnabled(usable&&r!=null&&"variant".equals(r.type)&&r.variantKey()!=null);
            showVisit.setEnabled(usable&&ref!=null);rateDetails.setEnabled(usable&&r!=null&&r.dungeon!=null&&!r.dungeon.isEmpty()&&!"rate".equals(r.type));
            boolean navigable=ref!=null&&Navigator.current().canOpen(runRoute(ref));openRun.setEnabled(usable&&navigable);
            linkStatus.setText(runLinkStatus(r,ref,navigable));linkStatus.getAccessibleContext().setAccessibleName(linkStatus.getText());
            rateStatus.setText(rateStatus(r));rateStatus.getAccessibleContext().setAccessibleName(rateStatus.getText());
            // The reasons also travel with the actions, which may be far from these lines (in ⋯).
            showVisit.setToolTipText(linkStatus.getText());openRun.setToolTipText(linkStatus.getText());rateDetails.setToolTipText(rateStatus.getText());
        }
        private void query(ArchiveQuery<Facets,Sort> q){binding.queryChanged(q);}
        ArchiveFilters filters(){
            View view=current.query.facets().view;JPanel drawer=new JPanel(new BorderLayout(0,4));
            if(view.loot())drawer.add(new LootFacetControls(current.query.facets(),choices("facet.bag."),choices("facet.dungeon."),f->query(current.query.withFacets(f))),BorderLayout.NORTH);
            else if(view!=View.COHORTS)drawer.add(analyticalFilters(view),BorderLayout.NORTH);
            drawer.add(dateControls(),BorderLayout.CENTER);
            // Polish B7: loot facets and dungeon selection do not filter fame, so Character fame shows only its Character ID chip.
            List<FilterBar.ActiveFilter> chips=view==View.FAME?characterChip():LootFacetChips.chips(current.query::facets,f->query(current.query.withFacets(f)));
            ArchiveFilters.dates(chips,current.query,this::query);
            return new ArchiveFilters(drawer,chips,tools);
        }
        private List<FilterBar.ActiveFilter> characterChip(){
            List<FilterBar.ActiveFilter> chips=new ArrayList<>();String character=current.query.facets().character;
            if(!character.isEmpty())chips.add(new FilterBar.ActiveFilter("Character ID "+character,()->{Facets next=current.query.facets();next.character="";query(current.query.withFacets(next));}));
            return chips;
        }

        private void savePosition(){if(restoring)return;current=HistoryTables.position(table,scroll,page,current);current=current.withPosition(current.query.facets().view.name(),current.selected,current.anchor,current.anchorOffset);binding.viewChanged(current);}
        private Set<String> choices(String prefix){Set<String> values=new TreeSet<>();for(String key:page.counts.keySet())if(key.startsWith(prefix))values.add(key.substring(prefix.length()));return values;}
        private void detail(ArchiveRow<Row> row){StringBuilder text=new StringBuilder("rate".equals(row.value.type)?RateCalculation.describe(row.value)+"\n\n":"");
            if("occurrence".equals(row.value.type))text.append("Exact enchantment evidence: ").append(row.value.enchantEvidence==null?"Not recorded":row.value.enchantEvidence).append("\nDrop context: ").append(row.value.dropContext==null?"Not recorded":row.value.dropContext).append('\n');
            text.append("Origin: ").append(row.ref).append('\n');for(HistoryTables.Column<Row,?> column:columns()){Object value=column.value.apply(row.value);if(value!=null&&!value.toString().isEmpty())text.append(column.label).append(": ").append(value).append(readable(column.id,value)).append('\n');}
            details.setText(text.toString());details.setCaretPosition(0);}
        private JComponent analyticalFilters(View view){
            if(view==View.COHORTS){Map<String,String> sessions=new TreeMap<>();page.counts.forEach((key,value)->{if(key.startsWith("facet.session."))sessions.put(key.substring(14),value.population);});
                return new CohortControls(current.query.facets(),sessions,ZoneId.of(current.query.bounds().zone),f->query(current.query.withFacets(f)),this::cohortInputInvalid);}
            JPanel p=ContentStyle.controls();Facets f=current.query.facets();JTextField dungeon=new JTextField(String.join(";",f.dungeons),16),identity=new JTextField(view==View.FAME?f.character:f.enemy,8);
            dungeon.getAccessibleContext().setAccessibleName("Exact dungeons separated by semicolons");identity.getAccessibleContext().setAccessibleName(view==View.FAME?"Exact character ID":"Exact enemy ID");
            // Fame has no map association, so no dungeon field (Polish B7): Character ID is its one analytical filter.
            if(view!=View.FAME){p.add(new JLabel("Dungeons (semicolon-separated)"));p.add(dungeon);}if(view==View.FAME||view==View.ENEMIES||view==View.SOURCES){p.add(new JLabel(view==View.FAME?"Character ID":"Enemy ID"));p.add(identity);}
            JButton apply=new JButton("Apply analytical filters");p.add(apply);apply.addActionListener(e->{Facets next=current.query.facets();
                if(view!=View.FAME){next.dungeons=new LinkedHashSet<>();for(String name:dungeon.getText().split(";"))if(!name.trim().isEmpty())next.dungeons.add(name.trim());}
                if(view==View.FAME)next.character=identity.getText().trim();else next.enemy=identity.getText().trim();query(current.query.withFacets(next));});return p;
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
    static final String STALE_COHORT="Previous comparison cleared. Export still uses the last applied comparison, not these invalid inputs. Correct the cohort input shown above, then choose Compare cohorts to see current results.";
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
    /**
     * Simple's count line (Polish B4): the loot views' matching bags, item variants and items ("9 bags · 15 item variants · 22
     * items"), the other views' counts by name; a count the page lacks reads "—", never 0.
     */
    static String plainCounts(View view,ArchivePage<Row> page){
        if(view.loot())return plain(page,"matching bags","bag","bags")+" · "+plain(page,"matching variants","item variant","item variants")+" · "+plain(page,"matching occurrences","item","items");
        StringJoiner s=new StringJoiner(" · ");page.counts.forEach((key,value)->{if(!key.startsWith("facet."))s.add(key+": "+DisplayFormat.formatInteger(value.value));});return s.toString();
    }
    private static String plain(ArchivePage<Row> page,String key,String one,String many){
        ArchiveAdapter.Count count=page.counts.get(key);return count==null?DisplayFormat.UNAVAILABLE+" "+many:DisplayFormat.formatInteger(count.value)+" "+(count.value==1?one:many);
    }
    /** The plain line's tooltip: the view's description, then each count with its unit and population, one per line. */
    private static String countTooltip(View view,ArchivePage<Row> page){
        StringBuilder html=new StringBuilder("<html><body style='width:420px'>").append(escape(description(view)));
        page.counts.forEach((key,value)->{if(!key.startsWith("facet."))html.append("<br>").append(escape(key+": "+value.value+" "+value.unit+" ["+value.population+"]"));});
        return html.append("</body></html>").toString();
    }
    private static String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
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
