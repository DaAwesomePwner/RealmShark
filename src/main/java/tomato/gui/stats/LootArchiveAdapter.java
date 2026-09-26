package tomato.gui.stats;

import java.io.IOException;
import java.util.*;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import tomato.gui.stats.LootQuery.*;

/** Streaming occurrences; bounded associative reducers, followed by foundation external sorting. */
public final class LootArchiveAdapter implements ArchiveAdapter<Row,Facets,Sort> {
    public static final int MAX_KEYS=25000;
    private final int limit;
    private final Map<String,Count> counts=new LinkedHashMap<>();
    private View view;
    private ArchiveDefinitions definitions;
    public LootArchiveAdapter(ArchiveQuery<Facets,Sort> q){this(q,MAX_KEYS);}
    LootArchiveAdapter(ArchiveQuery<Facets,Sort> q,int limit){view=q.facets().view;this.limit=limit;}
    public Class<Row> rowType(){return Row.class;}
    public String unit(){return view==View.OCCURRENCES?"item occurrences":view==View.RECENT?"bags":view==View.BAGS?"bag-type summaries":view==View.DUNGEONS?"dungeon summaries":"item variants";}
    public static final int MAX_VISITS=100000;
    /** Occurrence and bag rows carry a visit; their run links are verified against the same pin's saved runs. */
    static boolean linksRuns(View view){return view==View.OCCURRENCES||view==View.RECENT;}
    public List<ReadSnapshot.Source> sources(SessionStore store,ArchiveQuery<Facets,Sort> q){
        String scope=q.resolvedScope(store);if(!linksRuns(q.facets().view))return Collections.singletonList(new ReadSnapshot.Source(scope,"loot"));
        return Arrays.asList(new ReadSnapshot.Source(scope,"loot"),new ReadSnapshot.Source(scope,"runs"));
    }
    public void validate(ArchiveQuery<Facets,Sort> q){q.facets().validate();if(!q.facets().view.loot())throw new IllegalArgumentException("Not a loot occurrence view");}
    public boolean matches(ArchiveRow<Row> row,ArchiveQuery<Facets,Sort> q){return true;}
    public boolean inBounds(ArchiveRow<Row> row,ArchiveQuery<Facets,Sort> q){return true;}
    public Long time(ArchiveRow<Row> row){return row.value.time;}
    public Comparator<Row> comparator(Sort field){return LootQuery.comparator(field);}
    public Map<String,Count> counts(){return counts;}
    public Map<String,String> dependencies(){Map<String,String> values=new LinkedHashMap<>();values.put("loot-projection","v2; saved item classifications; exact variant/visit facets before paging; run links require the same session, visit ID and canonical dungeon; summaries limited to 25000 distinct keys; observed, not owned");if(definitions!=null)values.put("asset-generation",definitions.description());return values;}
    static void bounded(int size,int limit,String population)throws IOException{if(size>limit)throw new IOException(population+" exceeds "+limit+" distinct keys. Narrow the session scope or date/facets; nothing was truncated.");}
    static void label(String value)throws IOException{if(value!=null&&value.length()>512)throw new IOException("Archive label exceeds 512 characters; bounded summaries cannot retain it. No data was truncated.");}
    static void checkDrop(LootDashboard.Drop drop)throws IOException{
        label(drop.dungeon);label(drop.bag);label(drop.dropper);label(drop.visitId);
        if(drop.items==null)throw new IOException("Loot bag has no item-list field; cannot infer empty contents");
        if(drop.items.size()>1024)throw new IOException("Bag exceeds 1024 item positions; no data was truncated");
        for(LootDashboard.Item item:drop.items){if(item==null)throw new IOException("Null item occurrence");label(item.name);label(item.tier);}
    }
    public void scan(ReadSnapshot pin,ArchiveQuery<Facets,Sort> q,Sink<Row> sink,Cancellation cancel)throws IOException{
        counts.clear();definitions=new ArchiveDefinitions();Facets f=q.facets();Map<String,Row> groups=new TreeMap<>();Set<String> variants=new HashSet<>();
        Map<String,Long> choices=new TreeMap<>();long[] totals=new long[4];Map<String,String> runs=new HashMap<>();
        if(linksRuns(view))pin.read("runs",packets.packetcapture.logger.ActivityJournal.Visit.class,source->{
            packets.packetcapture.logger.ActivityJournal.Visit visit=source.value;label(visit.id);label(visit.map);if(visit.id==null||visit.id.isEmpty())return;
            runs.put(source.ref.session+"/"+visit.id,definitions.canonical(visit.map));bounded(runs.size(),MAX_VISITS,"Saved run links");
        },cancel);
        long[] linked={0,0};
        pin.read("loot",LootDashboard.Drop.class,source->{
            LootDashboard.Drop d=source.value;checkDrop(d);
            totals[0]++;totals[1]+=d.items.size();String dungeon=definitions.canonical(d.dungeon);
            choices.merge("facet.bag."+d.bag,1L,Long::sum);choices.merge("facet.dungeon."+dungeon,1L,Long::sum);bounded(choices.size(),limit,"Facet catalog");
            if(!q.bounds().contains(LootQuery.time(d.time),LootQuery.time(d.time))||!f.location(d.bag,dungeon)||f.view==View.WHITES&&!white(d.bag)||!f.visit(source.ref.session,d.visitId))return;
            Boolean runLinked=linksRuns(view)?d.visitId!=null&&!d.visitId.isEmpty()&&dungeon.equals(runs.get(source.ref.session+"/"+d.visitId)):null;
            StringJoiner names=new StringJoiner(", ");long matching=0;Set<String> bagVariants=new HashSet<>();
            for(int ordinal=0;ordinal<d.items.size();ordinal++){
                cancel.check();LootDashboard.Item i=d.items.get(ordinal);if(i==null)throw new IOException("Null item occurrence");
                if(!f.item(i)||!LootQuery.contains(i.id+" "+i.name+" "+dungeon+" "+d.bag+" "+d.dropper+" "+LootQuery.tier(i)+" "+LootQuery.rarity(i),q.text()))continue;
                matching++;names.add(i.name+" (#"+i.id+")");String key=LootQuery.variant(i);variants.add(key);bounded(variants.size(),limit,"Matching item variants");
                Row row=Row.item(source.ref.session,d,i,dungeon);row.runLinked=runLinked;
                if(view==View.OCCURRENCES){row.evidence=runEvidence(d.visitId,runLinked);if(runLinked)linked[0]++;else linked[1]++;sink.accept(source.child("item-"+ordinal,row));}
                else if(view!=View.RECENT&&view!=View.BAGS&&view!=View.DUNGEONS){
                    Row group=groups.get(key);if(group==null){group=row;group.type="variant";group.session="";group.visitId="";group.bag="";group.dropper="";group.evidence="Variant = item ID + slots + applied count. Time/dungeon describe the latest matching observation; bag counts can overlap between variants. No single session/visit link is implied.";group.count=0L;group.bags=0L;groups.put(key,group);bounded(groups.size(),limit,"Variant groups");}
                    group.count++;if(bagVariants.add(key))group.bags++;
                    if(group.time==null||row.time!=null&&row.time>group.time){group.time=row.time;group.dungeon=row.dungeon;}
                }
            }
            boolean empty=d.items.isEmpty()&&!f.itemRestricted()&&LootQuery.contains(dungeon+" "+d.bag+" "+d.dropper,q.text());
            if(matching==0&&!empty)return;totals[2]++;totals[3]+=matching;
            if(view==View.RECENT){Row row=new Row();row.type="bag";row.session=source.ref.session;row.visitId=Objects.toString(d.visitId,"");row.time=LootQuery.time(d.time);row.bag=d.bag;row.dungeon=dungeon;row.dropper=d.dropper;row.name=empty?"No visible items":names.toString();row.items=matching;row.bags=1L;row.runLinked=runLinked;row.evidence=runEvidence(d.visitId,runLinked);sink.accept(source.project(row));}
            if(view==View.BAGS||view==View.DUNGEONS){String key=view==View.BAGS?d.bag:dungeon;Row group=groups.get(key);if(group==null){group=new Row();group.type=view==View.BAGS?"bag-type":"dungeon";group.name=key;group.bag=view==View.BAGS?key:"";group.dungeon=view==View.DUNGEONS?key:"";group.items=0L;group.bags=0L;groups.put(key,group);bounded(groups.size(),limit,"Loot groups");}group.bags++;group.items+=matching;group.count=group.items;Long time=LootQuery.time(d.time);if(time!=null&&(group.time==null||time>group.time))group.time=time;}
        },cancel);
        String scope=pin.sessionIds().size()==1?pin.sessionIds().iterator().next():SessionStore.ALL;
        for(Map.Entry<String,Row> group:groups.entrySet()){cancel.check();sink.accept(new ArchiveRow<>(new ArchiveRow.Ref(scope,"loot","group:"+view+":"+group.getKey(),""),group.getValue()));}
        counts.put("scope bags",new Count(totals[0],"bags","whole saved session scope, before query"));
        counts.put("scope items",new Count(totals[1],"item occurrences","whole saved session scope, before query"));
        counts.put("matching bags",new Count(totals[2],"bags","whole query; each qualifying bag once"));
        counts.put("matching occurrences",new Count(totals[3],"item occurrences","whole query"));
        counts.put("matching variants",new Count(variants.size(),"item variants","whole query; ID + slots + applied count"));
        if(view==View.OCCURRENCES){
            counts.put("run-linked occurrences",new Count(linked[0],"item occurrences","whole query; same session + visit ID + canonical dungeon in saved runs"));
            counts.put("unlinked occurrences",new Count(linked[1],"item occurrences","whole query; no recorded visit ID, or no agreeing saved run"));
        }
        choices.forEach((key,value)->counts.put(key,new Count(value,"bags","whole scope facet catalog, before query")));
        definitions.check();
    }
    static String runEvidence(String visitId,Boolean linked){
        if(linked==null)return "";
        if(visitId==null||visitId.isEmpty())return "Run link unavailable: this bag has no recorded visit ID (legacy or unlinked record). No run is inferred from names or times.";
        return linked?"Run link verified: this session's saved runs contain visit "+visitId+" in the same canonical dungeon."
            :"Run link unavailable: visit "+visitId+" is not among this session's saved runs, or its dungeon differs. No nearest-run guess is made.";
    }
    static boolean white(String bag){return "White".equals(bag)||"B.White".equals(bag);}
}
