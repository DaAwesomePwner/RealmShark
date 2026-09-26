package tomato.gui.stats;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import packets.packetcapture.logger.ActivityJournal;
import tomato.backend.data.DungeonStatData;
import tomato.gui.stats.LootQuery.*;
import tomato.gui.stats.session.FameSession;
import tomato.history.*;
import tomato.history.archive.*;

/** Compact bounded cohort/character/counter reducers over one pinned source vector. */
public final class StatisticsArchiveAdapter implements ArchiveAdapter<Row,Facets,Sort> {
    private final View view;
    private String summaryScope;
    private ArchiveDefinitions definitions;
    private final Map<String,Count> counts=new LinkedHashMap<>();
    private static final int LIMIT=LootArchiveAdapter.MAX_KEYS, VISITS=100000;
    public StatisticsArchiveAdapter(ArchiveQuery<Facets,Sort> q){view=q.facets().view;}
    public Class<Row> rowType(){return Row.class;}
    public String unit(){return view==View.SESSIONS?"sessions":view==View.FAME?"session-qualified characters":view==View.ENEMIES?"enemy summaries":view==View.SOURCES?"item/source summaries":"dungeons";}
    public List<ReadSnapshot.Source> sources(SessionStore store,ArchiveQuery<Facets,Sort> q){
        List<ReadSnapshot.Source> sources=new ArrayList<>();
        String[] modules=view.counters()?new String[]{"dungeon-totals"}:view==View.RATES?new String[]{"runs","loot"}:view==View.FAME?new String[]{"fame","fame-latest","fame-snapshots"}:new String[]{"runs","loot","fame","fame-latest","fame-snapshots"};
        for(String module:modules)sources.add(new ReadSnapshot.Source(q.resolvedScope(store),module));return sources;
    }
    public void validate(ArchiveQuery<Facets,Sort> q){q.facets().validate();if(view.counters()&&(q.bounds().from!=null||q.bounds().until!=null))throw new IllegalArgumentException("Custom periods are unsupported for undated dungeon counters. Clear date bounds; no timestamp will be invented.");}
    public boolean matches(ArchiveRow<Row> row,ArchiveQuery<Facets,Sort> q){return true;}
    public boolean inBounds(ArchiveRow<Row> row,ArchiveQuery<Facets,Sort> q){return true;}
    public Long time(ArchiveRow<Row> row){return row.value.time;}
    public Comparator<Row> comparator(Sort sort){return LootQuery.comparator(sort);}
    public Map<String,Count> counts(){return counts;}
    public Map<String,String> dependencies(){Map<String,String> values=new LinkedHashMap<>();values.put("statistics-cohort","v3: session bag evidence; exact session/visit/dungeon joins; visit-entry/overlap bounds; whole-visit loot; item facets not applied to analytical cohorts; fame timestamp ties prefer latest checkpoint, then journal, then legacy snapshot; undated fame excluded from endpoints and invalidates gain/span; one dated timestamp is same-sample zero, not an elapsed interval");if(definitions!=null)values.put("asset-generation",definitions.description());return values;}
    private static <T> T group(Map<String,T> map,String key,java.util.function.Supplier<T> create)throws IOException{
        T value=map.get(key);if(value==null){value=create.get();map.put(key,value);LootArchiveAdapter.bounded(map.size(),LIMIT,"Statistics groups");}return value;
    }
    private ArchiveRow<Row> summary(ArchiveQuery<Facets,Sort> q,String key,Row row){return new ArchiveRow<>(new ArchiveRow.Ref(row.session.isEmpty()?summaryScope:row.session,"statistics","summary:"+row.type+":"+key,""),row);}
    public void scan(ReadSnapshot pin,ArchiveQuery<Facets,Sort> q,Sink<Row> out,Cancellation cancel)throws IOException{
        definitions=view==View.FAME?null:new ArchiveDefinitions();scanPinned(pin,q,out,cancel);if(definitions!=null)definitions.check();
    }
    private void scanPinned(ReadSnapshot pin,ArchiveQuery<Facets,Sort> q,Sink<Row> out,Cancellation cancel)throws IOException{
        counts.clear();summaryScope=pin.sessionIds().size()==1?pin.sessionIds().iterator().next():SessionStore.ALL;if(view.counters()){counters(pin,q,out,cancel);return;}
        Map<String,FameRange> fame=(view==View.FAME||view==View.SESSIONS)?fame(pin,q,cancel):Collections.emptyMap();
        if(view==View.FAME){long undated=0;for(Map.Entry<String,FameRange> entry:fame.entrySet()){
            FameRange f=entry.getValue();Row row=f.row();SessionStore.Session session=pin.session(f.session);
            if(!LootQuery.contains(session+" "+f.character+" "+f.className,q.text())||!q.facets().character.isEmpty()&&!Integer.toString(f.character).equals(q.facets().character))continue;
            undated+=f.chronology.undatedCount();row.name=session+" · "+row.name;out.accept(summary(q,entry.getKey(),row));
        }counts.put("undated fame observations",new Count(undated,"sample observations","matching characters and date/unknown-time policy; excluded from chronological endpoints"));return;}
        cohorts(pin,q,fame,out,cancel);
    }
    private static final class Visit {
        String map;boolean eligible;
        Visit(String map,boolean eligible){this.map=map;this.eligible=eligible;}
    }
    private void cohorts(ReadSnapshot pin,ArchiveQuery<Facets,Sort> q,Map<String,FameRange> fame,Sink<Row> out,Cancellation cancel)throws IOException{
        Facets facets=q.facets();Set<String> evidence=new HashSet<>();
        // Evidence is read across each entire pinned session, before any period or item filtering.
        pin.read("loot",LootDashboard.Drop.class,row->{LootArchiveAdapter.checkDrop(row.value);evidence.add(row.ref.session);LootArchiveAdapter.bounded(evidence.size(),LIMIT,"Sessions with loot evidence");},cancel);
        Map<String,HistoricalStatistics.Profile> dungeons=new TreeMap<>(),sessions=new TreeMap<>();Map<String,Visit> visits=new HashMap<>();
        for(String id:pin.sessionIds()){SessionStore.Session meta=pin.session(id);LootArchiveAdapter.label(meta.label);LootArchiveAdapter.label(meta.version);HistoricalStatistics.Profile p=group(sessions,id,HistoricalStatistics.Profile::new);p.imported="Imported".equals(meta.version);p.lootEvidence=evidence.contains(id);}
        pin.read("runs",ActivityJournal.Visit.class,source->{
            ActivityJournal.Visit visit=source.value;LootArchiveAdapter.label(visit.map);LootArchiveAdapter.label(visit.id);if(!definitions.dungeon(visit.map))return;
            String map=definitions.canonical(visit.map),key=source.ref.session+"/"+visit.id;
            boolean selected=facets.dungeon(map)&&q.bounds().contains(LootQuery.time(visit.started),LootQuery.time(visit.lastSeen));
            visits.put(key,new Visit(map,selected));LootArchiveAdapter.bounded(visits.size(),VISITS,"Qualified visit joins");
            if(!selected)return;
            HistoricalStatistics.Profile session=sessions.get(source.ref.session),dungeon=group(dungeons,map,HistoricalStatistics.Profile::new);
            addVisit(session,visit);
            if(session.imported){dungeon.excludedRuns++;return;}
            if(!evidence.contains(source.ref.session)){dungeon.unknownRuns++;dungeon.unknownMillis+=visit.observedMillis();return;}
            dungeon.lootEvidence=true;addVisit(dungeon,visit);
        },cancel);
        pin.read("loot",LootDashboard.Drop.class,source->{
            LootDashboard.Drop drop=source.value;if(drop.items==null)throw new IOException("Loot bag contents not captured");
            String map=definitions.canonical(drop.dungeon),key=source.ref.session+"/"+drop.visitId;
            Visit visit=drop.visitId==null||drop.visitId.isEmpty()?null:visits.get(key);
            boolean agreement=visit!=null&&map.equals(visit.map);
            if(!facets.dungeon(map)||agreement&&!visit.eligible||!agreement&&!q.bounds().contains(LootQuery.time(drop.time),LootQuery.time(drop.time)))return;
            HistoricalStatistics.Profile session=sessions.get(source.ref.session),dungeon=group(dungeons,map,HistoricalStatistics.Profile::new);
            boolean linked=agreement&&!session.imported;
            dungeon.add(drop,linked);session.add(drop,linked);
            if(linked){dungeon.lootRuns.add(key);LootArchiveAdapter.bounded(dungeon.lootRuns.size(),VISITS,"Linked loot visits");}
        },cancel);
        Map<String,Double> gains=new HashMap<>();Set<String> incompleteFame=new HashSet<>();Map<String,Long> undatedFame=new HashMap<>();
        for(FameRange f:fame.values()){
            Double gain=f.chronology.gain();if(gain==null)incompleteFame.add(f.session);else gains.merge(f.session,gain,Double::sum);
            undatedFame.merge(f.session,f.chronology.undatedCount(),Long::sum);
        }
        long shownUndated=0;
        if(view==View.SESSIONS){for(Map.Entry<String,HistoricalStatistics.Profile> entry:sessions.entrySet()){
            String id=entry.getKey();SessionStore.Session session=pin.session(id);if(!LootQuery.contains(session+" "+session.version+" "+id,q.text()))continue;
            Row row=profile(entry.getValue());row.type="session";row.session=id;row.name=session.toString();row.build=session.version;row.time=LootQuery.time(session.started);row.gain=incompleteFame.contains(id)?null:gains.get(id);
            long undated=undatedFame.getOrDefault(id,0L);shownUndated+=undated;
            // Session comparisons display observed visits, but do not promise dungeon loot rates.
            row.perRun=row.perHour=row.utPerHour=row.whitesPerRun=row.utPerRun=row.stPerRun=row.potionsPerRun=null;
            HistoricalStatistics.Profile p=entry.getValue();
            row.evidence="Session text searches session label/build/ID. Visits follow dungeon and visit bounds; loot is whole-visit evidence. Item/bag/enchant facets do not filter analytical cohorts.\n"
                +"Observed visits (including unknown-coverage visits): "+p.runs+"; observed milliseconds: "+p.millis+". Loot: "+p.coverage()+". Unassigned bags: "+p.unassignedBags+".\nRates are available only in Dungeon loot profile, with its explicit eligible cohort. Fame change uses dated endpoints inside date bounds; dungeon filters do not filter fame (map association not captured).\n"
                +undated+" undated fame observations. "+(incompleteFame.contains(id)?"Combined fame change unavailable: at least one included character has incomplete chronology.":"Single-timestamp characters contribute a same-sample zero delta, not measured session growth.");
            out.accept(summary(q,id,row));
        }}else for(Map.Entry<String,HistoricalStatistics.Profile> entry:dungeons.entrySet()){
            if(!LootQuery.contains(entry.getKey(),q.text()))continue;Row row=profile(entry.getValue());row.type="rate";row.name=row.dungeon=entry.getKey();
            row.evidence="Rate text searches dungeon names. All loot from visits selected by entry/overlap bounds; unlinked bags use their own timestamps. Item/bag/enchant facets do not filter this cohort.\n"+row.evidence;
            out.accept(summary(q,entry.getKey(),row));
        }
        counts.put("evidenced sessions",new Count(evidence.size(),"sessions","whole pinned scope, before period/dungeon query"));
        long eligible=dungeons.values().stream().mapToLong(p->p.runs).sum(),unknown=dungeons.values().stream().mapToLong(p->p.unknownRuns).sum();
        counts.put("eligible visits",new Count(eligible,"visits","dungeon and visit bounds, before tab-specific text; zero-loot visits included"));
        counts.put("excluded unknown visits",new Count(unknown,"visits","same visit cohort; session loot availability unknown"));
        if(view==View.SESSIONS)counts.put("undated fame observations",new Count(shownUndated,"sample observations","shown session summaries and date/unknown-time policy; combined gain unavailable when chronology is incomplete"));
    }
    static void addVisit(HistoricalStatistics.Profile p,ActivityJournal.Visit v){p.runs++;p.millis+=v.observedMillis();p.missingDuration|=v.observedMillis()<=0;p.damage+=v.totalDamage;p.damageKnown&=v.damageTracked;if(v.ended==0)p.ongoing++;if("Completed".equals(v.runStatus()))p.completed++;}
    static Row profile(HistoricalStatistics.Profile p){
        Row r=new Row();r.items=p.lootValue(p.items);r.bags=p.lootValue(p.bags);r.runs=p.runs;r.millis=p.millis;r.whites=p.lootValue(p.whites);r.uts=p.lootValue(p.uts);r.sts=p.lootValue(p.sts);r.potions=p.lootValue(p.potions);r.completed=p.completed;r.unknownRuns=p.unknownRuns;r.importedRuns=p.excludedRuns;
        r.perRun=p.perRun(p.items);r.perHour=p.perHour(p.items);r.utPerHour=p.perHour(p.uts);r.whitesPerRun=p.perRun(p.whites);r.utPerRun=p.perRun(p.uts);r.stPerRun=p.perRun(p.sts);r.potionsPerRun=p.perRun(p.potions);r.damage=p.damageKnown&&p.runs>0?p.damage:null;r.evidence=p.explanation();
        r.zeroLootRuns=p.lootEvidence?(long)(p.runs-p.lootRuns.size()):null;r.unassignedBags=p.lootEvidence?p.unassignedBags:null;return r;
    }
    private static final class FameRange {
        String session,className="Unknown";int character;
        final FameSession.Chronology chronology=new FameSession.Chronology();
        Row row(){Row r=new Row();r.type="fame";r.session=session;r.character=character;r.name=className+" #"+character;r.className=className;r.time=chronology.firstTime();r.firstFame=chronology.firstFame();r.lastFame=chronology.lastFame();r.gain=chronology.gain();r.millis=chronology.elapsed();r.count=chronology.datedCount()+chronology.undatedCount();r.evidence=chronology.explanation()+" "+association();return r;}
        long associated,unassociated;final Map<String,Long> maps=new TreeMap<>();
        void associate(AppHistory.FameSample sample)throws IOException{
            if(sample.visit()==null){unassociated++;return;}associated++;LootArchiveAdapter.label(sample.map);
            String map=sample.map==null||sample.map.isEmpty()?"map not captured":sample.map;if(maps.containsKey(map)||maps.size()<10)maps.merge(map,1L,Long::sum);
        }
        /** Recorded per-sample visit associations; legacy samples stay Not recorded and nothing is inferred from names or times. */
        String association(){
            if(associated==0)return "Map association: Not recorded (no sample carries a recorded visit; none is inferred).";
            StringJoiner list=new StringJoiner(", ");maps.forEach((map,count)->list.add(map+" ×"+count));
            return "Map association: "+associated+" sample(s) with a recorded visit ("+list+(maps.size()>=10?", …":"")+"); "+unassociated+" sample(s) Not recorded.";
        }
    }
    private static Map<String,FameRange> fame(ReadSnapshot pin,ArchiveQuery<Facets,Sort> q,Cancellation cancel)throws IOException{
        Map<String,FameRange> result=new TreeMap<>();
        pin.read("fame-snapshots",FameSession.class,row->{if(row.value.getCharacterFameData()!=null)for(Map.Entry<Integer,List<Fame>> character:row.value.getCharacterFameData().entrySet())for(Fame sample:character.getValue()){
            cancel.check();FameRange legacy=sample(result,q,row.ref.session,character.getKey(),row.value.getCharacterClassNames()==null?"Unknown":row.value.getCharacterClassNames().getOrDefault(character.getKey(),"Unknown"),sample.getTime(),sample.getFame());if(legacy!=null)legacy.unassociated++;
        }},cancel);
        for(String module:Arrays.asList("fame","fame-latest"))pin.read(module,AppHistory.FameSample.class,row->{FameRange range=sample(result,q,row.ref.session,row.value.character,row.value.className,row.value.time,row.value.fame);if(range!=null)range.associate(row.value);},cancel);
        return result;
    }
    private static FameRange sample(Map<String,FameRange> groups,ArchiveQuery<Facets,Sort> q,String session,int character,String name,long time,double fame)throws IOException{
        if(!q.bounds().contains(LootQuery.time(time),LootQuery.time(time)))return null;
        LootArchiveAdapter.label(name);FameRange range=group(groups,session+"/"+character,FameRange::new);range.session=session;range.character=character;
        Long last=range.chronology.lastTime();if(time>0&&(last==null||time>=last)||last==null&&range.className.equals("Unknown"))range.className=name==null?"Unknown":name;
        range.chronology.add(time,fame);return range;
    }
    private void counters(ReadSnapshot pin,ArchiveQuery<Facets,Sort> q,Sink<Row> sink,Cancellation cancel)throws IOException{
        Map<String,Row> groups=new TreeMap<>();Facets facets=q.facets();
        pin.read("dungeon-totals",JsonArray.class,source->{for(JsonElement element:source.value){
            cancel.check();DungeonStatData.Snapshot d=SessionStore.JSON.fromJson(element,DungeonStatData.Snapshot.class);LootArchiveAdapter.label(d.name);String map=definitions.canonical(d.name);if(!facets.dungeon(map))continue;
            if(view==View.COUNTERS){if(!LootQuery.contains(map,q.text()))continue;Row r=counter(groups,map,map);r.runs+=d.visits;r.millis+=d.time;r.hits+=d.hitCount();r.items+=d.itemCount();
                r.ongoingActivity=r.sourceSummaries==0?d.ongoingActivity:Boolean.TRUE.equals(r.ongoingActivity)||Boolean.TRUE.equals(d.ongoingActivity)?Boolean.TRUE:r.ongoingActivity==null||d.ongoingActivity==null?null:Boolean.FALSE;r.sourceSummaries++;}
            else {Set<Integer> enemies=new TreeSet<>(d.hits.keySet());enemies.addAll(d.loot.keySet());for(Integer enemy:enemies){
                if(!facets.enemy.isEmpty()&&!facets.enemy.equals(enemy.toString()))continue;
                String enemyName=objectName(enemy);Map<Integer,Integer> items=d.loot.getOrDefault(enemy,Collections.emptyMap());
                if(view==View.ENEMIES){if(!LootQuery.contains(map+" "+enemy+" "+enemyName,q.text()))continue;Row r=counter(groups,map+"/"+enemy,map);r.name=enemyName;r.enemyId=enemy;r.hits+=d.hits.getOrDefault(enemy,0);r.items+=items.values().stream().mapToLong(Integer::longValue).sum();}
                else for(Map.Entry<Integer,Integer> item:items.entrySet()){
                    String name=objectName(item.getKey());if(!LootQuery.contains(map+" "+enemyName+" "+enemy+" "+item.getKey()+" "+name,q.text()))continue;
                    Row r=counter(groups,map+"/"+enemy+"/"+item.getKey(),map);r.name=name;r.enemyId=enemy;r.dropper=enemyName;r.itemId=item.getKey();r.items+=item.getValue();r.count=r.items;
                }
            }}
        }},cancel);
        for(Map.Entry<String,Row> entry:groups.entrySet()){Row row=entry.getValue();if(view==View.COUNTERS&&row.runs>0)row.averageMillis=row.millis/row.runs;sink.accept(summary(q,entry.getKey(),row));}
    }
    private Row counter(Map<String,Row> groups,String key,String map)throws IOException{
        return group(groups,key,()->{Row r=new Row();r.type=view.name();r.name=r.dungeon=map;r.items=0L;if(view!=View.SOURCES)r.hits=0L;if(view==View.COUNTERS)r.runs=r.millis=0L;r.evidence="Undated activity counters: custom periods unsupported. Exits/finalized time differ from observed Runs; hit events are not kills. Item/bag/enchant facets do not filter counters. Text searches this tab's dungeon/enemy/item labels and IDs.";return r;});
    }
    private String objectName(int id)throws IOException{return definitions.objectName(id);}
}
