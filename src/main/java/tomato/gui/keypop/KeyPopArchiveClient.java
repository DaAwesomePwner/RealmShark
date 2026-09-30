package tomato.gui.keypop;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.List;
import javax.swing.*;
import tomato.gui.chat.SocialQueryControls;
import tomato.gui.history.*;
import tomato.gui.kit.ColumnKind;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.KitTables;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.history.SessionStore;
import tomato.history.archive.*;

/** Events and whole-query contribution summaries share one predicate and pinned export pipeline. */
public final class KeyPopArchiveClient implements ArchiveClient<KeyPopArchiveClient.Row,KeyPopArchiveClient.Facets,KeyPopArchiveClient.Sort> {
    public enum Mode { EVENTS, BY_PLAYER, BY_ITEM }
    public enum Sort { TIME, PLAYER, ITEM, KIND, POPS, KEYS, RUNES, VIALS, INCS, PLAYERS, SHARE }
    public static class Facets {
        public Set<String> kinds = new LinkedHashSet<>(), items = new LinkedHashSet<>();
        public String exactPlayer = "";
        public Mode mode = Mode.EVENTS;
    }
    public static final class Row {
        public final Mode mode;
        public final Instant time;
        public final String player, item, kind;
        public final long pops, keys, runes, vials, incs, players, matchingEvents;
        public final double share;
        Row(Mode mode, Instant time, String player, String item, String kind, long pops, long keys, long runes,
                long vials, long incs, long players, long denominator) {
            this.mode=mode;this.time=time;this.player=player;this.item=item;this.kind=kind;this.pops=pops;this.keys=keys;
            this.runes=runes;this.vials=vials;this.incs=incs;this.players=players;matchingEvents=denominator;
            share=denominator==0?0:pops*100.0/denominator;
        }
        static Row event(KeyPopEvent e,long denominator) {
            return new Row(Mode.EVENTS,e.time,e.player,e.item,e.kind==null?"Unknown":e.kind.label,1,
                e.kind==KeyPopEvent.Kind.KEY?1:0,e.kind==KeyPopEvent.Kind.RUNE?1:0,e.kind==KeyPopEvent.Kind.VIAL?1:0,e.kind==KeyPopEvent.Kind.INC?1:0,1,denominator);
        }
    }
    /** The saved modes' tab IDs in Mode order (the customizable tab group {@code keypops-saved}); a query stores the mode, never a tab. */
    static final String[] MODE_TABS={"events","by-player","by-item"};
    private static final String[] MODE_TITLES={"Events","By player","By dungeon / item"};
    private final Path scratch;
    private final DisplayModeModel mode;
    private long generation;
    /** Kept for callers that bind their workspace; exports are the workspace ⋯'s since the view's duplicate export went. */
    public void bind(ArchiveWorkspace<Row,Facets,Sort> workspace){ }
    public KeyPopArchiveClient(Path scratch) { this(scratch,DisplayModeModel.application()); }
    /** {@code mode} decides how the time column reads: relative in Simple, absolute in Analyst (display only). */
    KeyPopArchiveClient(Path scratch,DisplayModeModel mode) { this.scratch=scratch;this.mode=Objects.requireNonNull(mode,"mode"); }
    public static ArchiveQuery<Facets,Sort> query() { return ArchiveQuery.of(ArchiveQuery.CURRENT,new Facets(),Facets.class,Sort.TIME)
        .withOrder(Collections.singletonList(new ArchiveQuery.Order<>(Sort.TIME,ArchiveQuery.Direction.DESCENDING))); }
    public ArchiveQuery<Facets,Sort> initialQuery(){return query();}
    public Path scratchDirectory(){return scratch;}
    public ArchiveAdapter<Row,Facets,Sort> adapter(ArchiveQuery<Facets,Sort> q){generation++;return new Adapter(q.facets().mode);}
    static boolean matches(KeyPopEvent e, Facets f, String query) {
        if ((!f.kinds.isEmpty() && !f.kinds.contains(e.kind==null?"UNKNOWN":e.kind.name()))
                || (!f.items.isEmpty() && !f.items.contains(e.item))
                || (!f.exactPlayer.isEmpty() && !f.exactPlayer.equalsIgnoreCase(e.player))) return false;
        String text=(Objects.toString(e.player,"")+" "+Objects.toString(e.item,"")+" "+(e.kind==null?"Unknown":e.kind.label)).toLowerCase(Locale.ROOT);
        for(String word:query.trim().toLowerCase(Locale.ROOT).split("\\s+"))if(!text.contains(word))return false;
        return true;
    }
    static class Adapter implements ArchiveAdapter<Row,Facets,Sort> {
        private final Mode mode;
        private long matching, keys;
        Adapter(Mode mode){this.mode=Objects.requireNonNull(mode);}
        public Class<Row> rowType(){return Row.class;}
        public String unit(){return mode==Mode.EVENTS?"pop events":mode==Mode.BY_PLAYER?"contributor summaries":"dungeon/item summaries";}
        public List<ReadSnapshot.Source> sources(SessionStore store,ArchiveQuery<Facets,Sort> q){return Collections.singletonList(new ReadSnapshot.Source(q.resolvedScope(store),"keypops"));}
        public void validate(ArchiveQuery<Facets,Sort> q){Facets f=q.facets();Objects.requireNonNull(f.exactPlayer);Objects.requireNonNull(f.items);for(String kind:f.kinds)if(!kind.equals("UNKNOWN"))KeyPopEvent.Kind.valueOf(kind);}
        public Long time(ArchiveRow<Row> row){return row.value.time==null?null:row.value.time.toEpochMilli();}
        public boolean inBounds(ArchiveRow<Row> row,ArchiveQuery<Facets,Sort> q){return true;}
        public boolean matches(ArchiveRow<Row> row,ArchiveQuery<Facets,Sort> q){return true;}
        private void events(ReadSnapshot pin,ArchiveQuery<Facets,Sort> q,Sink<KeyPopEvent> sink,Cancellation cancel)throws IOException{
            Facets f=q.facets();pin.read("keypops",KeyPopEvent.class,source->{
                KeyPopEvent e=source.value;Long time=e.time==null?null:e.time.toEpochMilli();
                if(q.bounds().contains(time,time)&&KeyPopArchiveClient.matches(e,f,q.text()))sink.accept(source);
            },cancel);
        }
        public void scan(ReadSnapshot pin,ArchiveQuery<Facets,Sort> q,Sink<Row> sink,Cancellation cancel)throws IOException{
            events(pin,q,source->{matching++;if(source.value.kind==KeyPopEvent.Kind.KEY)keys++;},cancel);
            if(mode==Mode.EVENTS){events(pin,q,source->sink.accept(source.project(Row.event(source.value,matching))),cancel);return;}
            // Bounded grouping by repeated scans of the same immutable pin. No all-event lists,
            // arbitrary truncation or second storage/query architecture; high cardinality costs scans.
            GroupKey after=null;Accumulator combined=null;String current=null;
            for(;;){
                final GroupKey floor=after;TreeSet<GroupKey> batch=new TreeSet<>();long[] keyBytes={0};
                events(pin,q,source->{GroupKey key=key(source.value);if(floor!=null&&key.compareTo(floor)<=0)return;
                    if(batch.add(key))keyBytes[0]+=key.bytes();
                    while(batch.size()>512||(keyBytes[0]>4L*1024*1024&&batch.size()>1))keyBytes[0]-=batch.pollLast().bytes();},cancel);
                if(batch.isEmpty())break;
                Map<GroupKey,Accumulator> values=new TreeMap<>();for(GroupKey key:batch)values.put(key,new Accumulator());
                events(pin,q,source->{Accumulator a=values.get(key(source.value));if(a!=null)a.add(source.value);},cancel);
                for(Map.Entry<GroupKey,Accumulator> entry:values.entrySet()){
                    cancel.check();GroupKey key=entry.getKey();Accumulator a=entry.getValue();
                    if(mode==Mode.BY_PLAYER){emit(sink,key.group,a,1);continue;}
                    if(!key.group.equals(current)){if(combined!=null)emit(sink,current,combined,combined.players);current=key.group;combined=new Accumulator();}
                    combined.merge(a);combined.players++;
                }
                after=batch.last();
            }
            if(combined!=null)emit(sink,current,combined,combined.players);
        }
        private GroupKey key(KeyPopEvent e){return mode==Mode.BY_PLAYER?new GroupKey(Objects.toString(e.player,"").toLowerCase(Locale.ROOT),"")
            :new GroupKey(Objects.toString(e.item,""),Objects.toString(e.player,"").toLowerCase(Locale.ROOT));}
        private void emit(Sink<Row> sink,String key,Accumulator a,long players)throws IOException{
            Row row=new Row(mode,a.last,mode==Mode.BY_PLAYER?a.player:"",mode==Mode.BY_ITEM?key:"","",a.pops,a.keys,a.runes,a.vials,a.incs,players,matching);
            sink.accept(new ArchiveRow<>(new ArchiveRow.Ref("@query","keypops","summary:"+mode.name(),key),row));
        }
        public Comparator<Row> comparator(Sort field){
            switch(field){
                case TIME:return Comparator.comparing(r->r.time,Comparator.nullsLast(Comparator.naturalOrder()));
                case PLAYER:return Comparator.comparing(r->r.player,Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
                case ITEM:return Comparator.comparing(r->r.item,Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
                case KIND:return Comparator.comparing(r->r.kind);
                case POPS:return Comparator.comparingLong(r->r.pops);case KEYS:return Comparator.comparingLong(r->r.keys);
                case RUNES:return Comparator.comparingLong(r->r.runes);case VIALS:return Comparator.comparingLong(r->r.vials);
                case INCS:return Comparator.comparingLong(r->r.incs);case PLAYERS:return Comparator.comparingLong(r->r.players);
                default:return Comparator.comparingDouble(r->r.share);
            }
        }
        public Map<String,String> dependencies(){return Collections.singletonMap("population","Observed pop events only; portal callouts excluded. Summary origins @query are aggregate keys, not session identities.");}
        public Map<String,Count> counts(){Map<String,Count> result=new LinkedHashMap<>();result.put("events",new Count(matching,"pop events","whole matching query; share denominator; callouts excluded"));result.put("keys",new Count(keys,"key-pops","whole matching query"));return result;}
        private static final class GroupKey implements Comparable<GroupKey>{final String group,player;GroupKey(String g,String p){group=g;player=p;}long bytes(){return 64L+2L*group.length()+2L*player.length();}public int compareTo(GroupKey k){int c=group.compareTo(k.group);return c==0?player.compareTo(k.player):c;}}
        private static final class Accumulator{
            long pops,keys,runes,vials,incs,players;Instant last;String player="";
            void add(KeyPopEvent e){pops++;if(e.kind==KeyPopEvent.Kind.KEY)keys++;if(e.kind==KeyPopEvent.Kind.RUNE)runes++;if(e.kind==KeyPopEvent.Kind.VIAL)vials++;if(e.kind==KeyPopEvent.Kind.INC)incs++;
                if(e.time!=null&&(last==null||e.time.isAfter(last)))last=e.time;
                String name=Objects.toString(e.player,"");if(player.isEmpty())player=name;}
            void merge(Accumulator a){pops+=a.pops;keys+=a.keys;runes+=a.runes;vials+=a.vials;incs+=a.incs;if(a.last!=null&&(last==null||a.last.isAfter(last)))last=a.last;}
        }
    }
    public List<ArchiveExport.Column<Row>> exportColumns(){return Arrays.asList(
        new ArchiveExport.Column<>("View",r->r.mode),new ArchiveExport.Column<>("Timestamp UTC / last pop",r->r.time),
        new ArchiveExport.Column<>("Player",r->r.player),new ArchiveExport.Column<>("Dungeon / item",r->r.item),new ArchiveExport.Column<>("Type",r->r.kind),
        new ArchiveExport.Column<>("Pops",r->r.pops),new ArchiveExport.Column<>("Keys",r->r.keys),new ArchiveExport.Column<>("Runes",r->r.runes),new ArchiveExport.Column<>("Vials",r->r.vials),
        new ArchiveExport.Column<>("Incs",r->r.incs),new ArchiveExport.Column<>("Players",r->r.players),new ArchiveExport.Column<>("Matching pop-event denominator (no callouts)",r->r.matchingEvents),new ArchiveExport.Column<>("Share %",r->r.share));}
    private SocialQueryControls.State<Row,Facets,Sort> rendered;private Binding<Facets,Sort> renderedBinding;
    public JComponent render(ArchivePage<Row> page,ViewState<Facets,Sort> initial,Binding<Facets,Sort> binding){
        long ticket=++generation;SocialQueryControls.State<Row,Facets,Sort> state=new SocialQueryControls.State<>(initial,binding,()->generation==ticket);
        rendered=state;renderedBinding=binding;Facets f=initial.query.facets();
        // The modes are customizable tabs (spec §4.4), built per render over the shared keypops-saved order; the pane keeps its name.
        // The query's mode is the content shown, so its tab is revealed even when hidden; choosing another tab queries that mode.
        CustomizableTabs modes=new CustomizableTabs("keypops-saved");JTabbedPane tabs=modes.component();tabs.setName("keypop-archive-tabs");tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        Map<Mode,JPanel> holders=new EnumMap<>(Mode.class);
        for(Mode m:Mode.values()){JPanel holder=new JPanel(new BorderLayout());holders.put(m,holder);modes.add(MODE_TABS[m.ordinal()],MODE_TITLES[m.ordinal()],holder);}
        modes.show(MODE_TABS[f.mode.ordinal()]);modes.select(MODE_TABS[f.mode.ordinal()]);
        JTextArea detail=ContentStyle.wrappingText("Select a row for its full values.");detail.setName("keypop-archive-detail");
        List<HistoryTables.Column<Row,?>> columns=new ArrayList<>();Map<String,Sort> sorts=new LinkedHashMap<>();
        if(f.mode==Mode.EVENTS){
            // Live Events' order and Type badges (Time, Player, Type, Dungeon / item). Saved layouts name columns by ID, so a layout saved
            // in the earlier order (Player, Dungeon / item, Time, Type) is restored as saved; only a table without one takes this order.
            columns.add(new HistoryTables.Column<>("time","Time",Instant.class,r->r.time,null,ColumnKind.DATE_TIME));sorts.put("time",Sort.TIME);
            columns.add(new HistoryTables.Column<>("player","Player",String.class,r->r.player,null,ColumnKind.PLAYER));sorts.put("player",Sort.PLAYER);
            columns.add(new HistoryTables.Column<>("kind","Type",String.class,r->r.kind,KeyPopDashboard.typeBadge(),ColumnKind.STATUS));sorts.put("kind",Sort.KIND);
            columns.add(new HistoryTables.Column<>("item","Dungeon / item",String.class,r->r.item,null,ColumnKind.DUNGEON));sorts.put("item",Sort.ITEM);
        }else{
            if(f.mode!=Mode.BY_ITEM){columns.add(new HistoryTables.Column<>("player","Player",String.class,r->r.player,null,ColumnKind.PLAYER));sorts.put("player",Sort.PLAYER);}
            if(f.mode!=Mode.BY_PLAYER){columns.add(new HistoryTables.Column<>("item","Dungeon / item",String.class,r->r.item,null,ColumnKind.DUNGEON));sorts.put("item",Sort.ITEM);}
            columns.add(new HistoryTables.Column<>("time","Last pop",Instant.class,r->r.time,null,ColumnKind.DATE_TIME));sorts.put("time",Sort.TIME);
            columns.add(new HistoryTables.Column<>("pops","Pops",Long.class,r->r.pops,null,ColumnKind.COUNT));sorts.put("pops",Sort.POPS);
            columns.add(new HistoryTables.Column<>("keys","Keys",Long.class,r->r.keys,null,ColumnKind.COUNT));sorts.put("keys",Sort.KEYS);
            columns.add(new HistoryTables.Column<>("runes","Runes",Long.class,r->r.runes,null,ColumnKind.COUNT));sorts.put("runes",Sort.RUNES);
            columns.add(new HistoryTables.Column<>("vials","Vials",Long.class,r->r.vials,null,ColumnKind.COUNT));sorts.put("vials",Sort.VIALS);
            columns.add(new HistoryTables.Column<>("incs","Incs",Long.class,r->r.incs,null,ColumnKind.COUNT));sorts.put("incs",Sort.INCS);
            if(f.mode==Mode.BY_ITEM){columns.add(new HistoryTables.Column<>("players","Players",Long.class,r->r.players,null,ColumnKind.COUNT));sorts.put("players",Sort.PLAYERS);}
            columns.add(new HistoryTables.Column<>("share","Share %",Double.class,r->r.share,null,ColumnKind.PERCENT));sorts.put("share",Sort.SHARE);
        }
        JTable table=HistoryTables.queried("keypop-archive-rows",columns,page,sorts,initial.query,state::query,row->{Row r=row.value;detail.setText((r.mode==Mode.EVENTS?"Event":"Whole-query summary")+" · "+r.player+" "+r.item+"\n"+r.pops+" / "+r.matchingEvents+" matching observed pop events = "+r.share+"%. Callouts excluded.\n"+"Last/event timestamp: "+r.time+" · "+r.kind);});
        KitTables.relativeTime(table,"time",mode,KitTables::epoch,DisplayFormat.timestampZoneLabel());   // "12 min ago" in Simple, absolute in Analyst
        table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&table.getSelectedRow()>=0)table.getActionMap().get("archive-details").actionPerformed(null);});
        JScrollPane scroll=ContentStyle.tableScroll(table,3);JPanel body=new JPanel(new BorderLayout(0,4));body.add(scroll);
        Map<String,List<String>> presets=new LinkedHashMap<>();presets.put("All columns",new ArrayList<>(sorts.keySet()));presets.put("Compact",f.mode==Mode.EVENTS?Arrays.asList("time","player","kind","item"):f.mode==Mode.BY_PLAYER?Arrays.asList("player","pops","share"):Arrays.asList("item","pops","players","share"));
        state.tableTools(table,scroll,page,f.mode.name(),presets);holders.get(f.mode).add(body);   // tools go to the workspace ⋯ via filters()
        modes.onSelect(id->{int index=Arrays.asList(MODE_TABS).indexOf(id);if(index<0||index==f.mode.ordinal())return;Mode chosen=Mode.values()[index];
            state.tab(chosen.name());Facets next=state.value.query.facets();next.mode=chosen;
            state.query(state.value.query.withFacets(next).withOrder(Collections.singletonList(new ArchiveQuery.Order<>(chosen==Mode.EVENTS?Sort.TIME:Sort.POPS,ArchiveQuery.Direction.DESCENDING))));});
        JPanel actions=ContentStyle.controls();JButton drill=new JButton(f.mode==Mode.BY_ITEM?"Show this item's events":"Show this player's events");drill.setEnabled(f.mode!=Mode.EVENTS);
        drill.addActionListener(e->{int index=table.getSelectedRow();if(index<0)return;Row row=page.rows.get(index).value;Facets next=state.value.query.facets();
            if(f.mode==Mode.BY_PLAYER)next.exactPlayer=row.player;else next.items=new LinkedHashSet<>(Collections.singleton(row.item));next.mode=Mode.EVENTS;state.tab(Mode.EVENTS.name());state.query(state.value.query.withFacets(next));});actions.add(drill);
        String export=f.mode==Mode.EVENTS?"Export events":"Export this summary";
        long denominator=page.counts.containsKey("events")?page.counts.get("events").value:0;
        // The count line names the pinned revision in Analyst only (spec §3.2: Simple hides IDs and revisions); it follows the mode.
        JTextArea note=ContentStyle.wrappingText("");note.setName("keypop-archive-population");
        mode.bind(note,shown->note.setText((shown==DisplayModeModel.Mode.ANALYST?page.description():plainDescription(page))+"\n"+denominator+" matching observed pop events across the whole query; callouts excluded from totals and share denominator.\n"+export+": ⋯ Export selected / page / all matches, as CSV or JSON. The export follows this tab's pinned rows."));
        JPanel footer=new JPanel(new BorderLayout(0,4));footer.add(actions,BorderLayout.NORTH);footer.add(detail);footer.add(note,BorderLayout.SOUTH);
        JComponent view = ContentStyle.page(null,tabs,footer); state.owner(view); return view;
    }
    /** Contribution facets and dates of the view just rendered, for the workspace drawer; they share its State. */
    @Override public ArchiveFilters filters(ArchivePage<Row> page,ViewState<Facets,Sort> initial,Binding<Facets,Sort> binding){
        if(rendered==null||renderedBinding!=binding)return null;SocialQueryControls.State<Row,Facets,Sort> state=rendered;Facets f=initial.query.facets();
        JPanel drawer=new JPanel(new BorderLayout(0,6)),filters=ContentStyle.responsiveGrid(2,220,8);
        JTextField player=new JTextField(f.exactPlayer);JTextArea items=new JTextArea(String.join("\n",f.items),3,18);
        filters.add(SocialQueryControls.labeled("Player equals (case-insensitive)",player,"keypop-archive-player"));filters.add(SocialQueryControls.labeled("Exact dungeons/items — one per line; empty = all",new JScrollPane(items),"keypop-archive-items"));
        JPanel types=ContentStyle.controls();Map<String,JCheckBox> choices=new LinkedHashMap<>();
        for(String kind:Arrays.asList("KEY","RUNE","VIAL","INC","OTHER","UNKNOWN")){JCheckBox box=new JCheckBox(kind,f.kinds.contains(kind));choices.put(kind,box);types.add(box);}
        JButton apply=new JButton("Apply contribution filters"),clearPlayer=new JButton("Clear exact player");types.add(apply);types.add(clearPlayer);
        clearPlayer.setName("keypop-archive-exact-player"); clearPlayer.setVisible(!f.exactPlayer.isEmpty());
        clearPlayer.setText("Player equals " + f.exactPlayer + " · Clear");
        Runnable change=()->{Facets next=state.value.query.facets();next.exactPlayer=player.getText().trim();next.items=SocialQueryControls.lines(items.getText());next.kinds.clear();choices.forEach((kind,box)->{if(box.isSelected())next.kinds.add(kind);});state.query(state.value.query.withFacets(next));};
        apply.addActionListener(e->change.run());player.addActionListener(e->change.run());clearPlayer.addActionListener(e->{player.setText("");change.run();});
        JPanel facets=new JPanel(new BorderLayout());facets.add(filters);facets.add(types,BorderLayout.SOUTH);drawer.add(facets,BorderLayout.NORTH);
        drawer.add(SocialQueryControls.dates(initial.query.bounds(),false,b->state.query(state.value.query.withBounds(b))));
        List<FilterBar.ActiveFilter> chips=new ArrayList<>();
        if(!f.exactPlayer.isEmpty())chips.add(chip(state,"Player: "+f.exactPlayer,next->next.exactPlayer=""));
        if(!f.kinds.isEmpty())chips.add(chip(state,"Types: "+kinds(f.kinds),next->next.kinds=new LinkedHashSet<>()));
        if(!f.items.isEmpty())chips.add(chip(state,"Dungeons/items: "+ArchiveFilters.summary(f.items),next->next.items=new LinkedHashSet<>()));
        ArchiveFilters.dates(chips,initial.query,state::query);
        return new ArchiveFilters(drawer,chips,state.tools());
    }
    private static FilterBar.ActiveFilter chip(SocialQueryControls.State<Row,Facets,Sort> state,String label,java.util.function.Consumer<Facets> reset){
        return new FilterBar.ActiveFilter(label,()->{Facets next=state.value.query.facets();reset.accept(next);state.query(state.value.query.withFacets(next));});
    }
    /** {@link ArchivePage#description()} without the pinned revision: "18 displayed / 18 matching pop events · page 1" (a partial read still says so). */
    static String plainDescription(ArchivePage<?> page){
        return page.rows.size()+" displayed / "+page.matches+" matching "+page.unit+" · page "+(page.page+1)+(page.issues.isEmpty()?"":" · partial: "+page.issues.size()+" source issue(s)");
    }
    /** Kind codes as the labels players see; unrecorded kinds stay "Unknown". */
    static String kinds(Set<String> kinds){List<String> labels=new ArrayList<>();for(String kind:kinds)labels.add("UNKNOWN".equals(kind)?"Unknown":KeyPopEvent.Kind.valueOf(kind).label);return ArchiveFilters.summary(labels);}
}
