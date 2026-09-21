package tomato.gui.stats;

import tomato.history.*;
import tomato.gui.history.*;
import tomato.gui.stats.session.*;
import tomato.gui.modern.ContentStyle;
import tomato.realmshark.ParseDungeon;
import packets.packetcapture.logger.ActivityJournal;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.List;

/** Worker-built projections of durable sessions. Rates retain zero-loot runs in their denominators. */
public final class HistoricalStatistics {
    private final LootDashboard.Archive loot = new LootDashboard.Archive();
    private final Map<String, Profile> dungeons = new TreeMap<>(), profiles = new LinkedHashMap<>();
    private final Map<String, SessionStore.Session> sessions = new LinkedHashMap<>();
    private final Map<String, String> runs = new HashMap<>();
    private final Map<String, FameSession> fame = new HashMap<>();
    private final Map<String, Map<Integer, CharacterFame>> characters = new HashMap<>();
    private final List<tomato.backend.data.DungeonStatData.Snapshot> dungeonTotals = new ArrayList<>();
    private final String query;
    private String currentId;

    private HistoricalStatistics(String query){this.query=query.trim().toLowerCase(Locale.ROOT);}
    public static SessionPanel.Loaded loot(SessionStore store,String scope,int page,String query)throws IOException{
        HistoricalStatistics data=load(store,scope,query,false);
        return new SessionPanel.Loaded(()->data.view(false),false,"Saved totals · rates use observed runs and captured hours; zero-loot runs count");
    }
    public static SessionPanel.Loaded statistics(SessionStore store,String scope,int page,String query)throws IOException{
        HistoricalStatistics data=load(store,scope,query,true);
        return new SessionPanel.Loaded(()->data.view(true),false,data.sessions.size()+" sessions · compare by session or dungeon");
    }
    private static HistoricalStatistics load(SessionStore store,String scope,String query,boolean withFame)throws IOException{
        HistoricalStatistics data=new HistoricalStatistics(query);
        data.currentId=store.currentId();
        for(SessionStore.Session session:store.sessions())if(scope.equals(SessionStore.ALL)||scope.equals(session.id)){
            data.sessions.put(session.id,session);data.profiles.put(session.id,new Profile());
        }
        store.read(scope,"runs",ActivityJournal.Visit.class,(session,visit)->{
            if(!ParseDungeon.isDungeon(visit.map))return;
            Profile sessionProfile=data.profiles.computeIfAbsent(session.id,k->new Profile());sessionProfile.runs++;
            sessionProfile.millis+=visit.observedMillis();if("Completed".equals(visit.runStatus()))sessionProfile.completed++;
            sessionProfile.damage+=visit.totalDamage;
            sessionProfile.damageKnown &= visit.damageTracked;
            if("Imported".equals(session.version))return; // Legacy run-only records have no measured loot coverage.
            Profile dungeon=data.dungeons.computeIfAbsent(visit.map,k->new Profile());dungeon.runs++;dungeon.millis+=visit.observedMillis();
            if("Completed".equals(visit.runStatus()))dungeon.completed++;
            data.runs.put(session.id+"/"+visit.id,visit.map);
        });
        store.read(scope,"loot",LootDashboard.Drop.class,(session,drop)->{
            data.loot.accept(drop);
            String map=ParseDungeon.canonicalName(drop.dungeon);if(map==null)map=drop.dungeon;
            Profile dungeon=data.dungeons.computeIfAbsent(map,k->new Profile());Profile sessionProfile=data.profiles.computeIfAbsent(session.id,k->new Profile());
            boolean linked=drop.visitId!=null&&map.equals(data.runs.get(session.id+"/"+drop.visitId));
            dungeon.add(drop,linked);sessionProfile.add(drop,linked);
        });
        if(withFame){
            store.read(scope,"dungeon-totals",com.google.gson.JsonArray.class,(session,array)->{
                for(com.google.gson.JsonElement element:array)data.dungeonTotals.add(SessionStore.JSON.fromJson(element,tomato.backend.data.DungeonStatData.Snapshot.class));
            });
            store.read(scope,"fame",AppHistory.FameSample.class,(session,sample)->data.sample(session,sample,true));
            store.read(scope,"fame-latest",AppHistory.FameSample.class,(session,sample)->data.sample(session,sample,false));
            store.read(scope,"fame-snapshots",FameSession.class,(session,saved)->{
                data.fame.put(session.id,saved);
                if(saved.getCharacterFameData()!=null)saved.getCharacterFameData().forEach((id,samples)->{
                    for(Fame point:samples){CharacterFame row=data.characters.computeIfAbsent(session.id,k->new TreeMap<>()).computeIfAbsent(id,k->new CharacterFame());row.add(point.getTime(),point.getFame());}
                });
            });
        }
        return data;
    }
    private void sample(SessionStore.Session session,AppHistory.FameSample sample,boolean graph){
        characters.computeIfAbsent(session.id,k->new TreeMap<>()).computeIfAbsent(sample.character,k->new CharacterFame()).add(sample.time,sample.fame);
        FameSession saved=fame.computeIfAbsent(session.id,k->new FameSession(session.toString()));
        saved.getCharacterClassNames().put(sample.character,sample.className);
        List<Fame> samples=saved.getCharacterFameData().computeIfAbsent(sample.character,k->new ArrayList<>());
        if(graph||samples.isEmpty()||samples.get(samples.size()-1).getTime()<sample.time)samples.add(new Fame(sample.fame,sample.time));
    }
    private JComponent view(boolean withFame){
        JTabbedPane tabs=new JTabbedPane();tabs.setName("historical-statistics-tabs");tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        if(withFame)tabs.addTab("Session comparison",sessionComparison(true));
        LootDashboard items=loot.view();items.searchHistory(query);tabs.addTab("Loot explorer",items);
        tabs.addTab("Dungeon loot profile",dungeonComparison());
        if(!withFame)tabs.addTab("Session comparison",sessionComparison(false));
        if(withFame){tabs.addTab("Character fame",characterComparison());tabs.addTab("Dungeon statistics",DungeonStats.history(combineDungeons()));}
        return tabs;
    }
    private List<tomato.backend.data.DungeonStatData.Snapshot> combineDungeons(){
        Map<String,List<tomato.backend.data.DungeonStatData.Snapshot>> groups=new TreeMap<>();
        for(tomato.backend.data.DungeonStatData.Snapshot row:dungeonTotals)groups.computeIfAbsent(row.name,k->new ArrayList<>()).add(row);
        List<tomato.backend.data.DungeonStatData.Snapshot> result=new ArrayList<>();
        groups.forEach((name,rows)->{
            if(!name.toLowerCase(Locale.ROOT).contains(query))return;
            int visits=0;long time=0;for(tomato.backend.data.DungeonStatData.Snapshot row:rows){visits+=row.visits;time+=row.time;}
            tomato.backend.data.DungeonStatData.Snapshot combined=new tomato.backend.data.DungeonStatData.Snapshot(name,visits,time);
            for(tomato.backend.data.DungeonStatData.Snapshot row:rows){row.hits.forEach((id,count)->combined.hits.merge(id,count,Integer::sum));
                row.loot.forEach((id,items)->items.forEach((item,count)->combined.loot.computeIfAbsent(id,k->new TreeMap<>()).merge(item,count,Integer::sum)));}
            result.add(combined);
        });return result;
    }
    private JComponent dungeonComparison(){
        List<Object[]> rows=new ArrayList<>();
        dungeons.forEach((name,p)->{if(name.toLowerCase(Locale.ROOT).contains(query))rows.add(p.row(name));});
        JTable table=HistoryTables.table("history-dungeon-loot",Profile.COLUMNS,Profile.TYPES,rows);
        table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(4,SortOrder.DESCENDING)));
        return HistoryTables.page(table,"Observed drops, not pickups. Rates include recorded zero-loot runs. Missing durations or unassigned drops show —. Legacy run-only imports are excluded from loot-rate denominators.");
    }
    private JComponent sessionComparison(boolean withFame){
        List<Object[]> rows=new ArrayList<>();List<String> ids=new ArrayList<>();
        sessions.forEach((id,session)->{
            if(!session.toString().toLowerCase(Locale.ROOT).contains(query)&&!query.isEmpty())return;
            Profile p=profiles.get(id);double gain=0;
            for(CharacterFame row:characters.getOrDefault(id,Collections.emptyMap()).values())gain+=row.last-row.first;
            rows.add(new Object[]{(id.equals(currentId)?"Current · ":"")+session,Instant.ofEpochMilli(session.started),p.runs,p.completed,p.millis/60000.0,characters.containsKey(id)?gain:null,p.items,p.whites,p.uts,p.sts,p.potions,p.damageKnown&&p.runs>0?p.damage:null,session.version});ids.add(id);
        });
        JTable table=HistoryTables.table("history-session-comparison",new String[]{"Session","Started","Dungeon visits","Completed","Captured minutes","Fame change","Items","White bags","UT gear","ST gear","Stat potions","Damage","Build"},
                new Class<?>[]{String.class,Instant.class,Long.class,Long.class,Double.class,Double.class,Long.class,Long.class,Long.class,Long.class,Long.class,Long.class,String.class},rows);
        JPanel panel=new JPanel(new BorderLayout(0,6));panel.add(HistoryTables.page(table,"Sessions are app launches. Captured time is the sum of recorded dungeon visit intervals; missing traffic cannot be reconstructed."));
        if(withFame){JButton graph=new JButton("Open selected session's fame graph");graph.addActionListener(e->{int selected=table.getSelectedRow();if(selected>=0){FameSession saved=fame.get(ids.get(table.convertRowIndexToModel(selected)));if(saved!=null)new FameSessionViewer(saved);else JOptionPane.showMessageDialog(panel,"No fame samples were captured for this session.");}});panel.add(graph,BorderLayout.SOUTH);}
        return panel;
    }
    private JComponent characterComparison(){
        List<Object[]> rows=new ArrayList<>();
        characters.forEach((session,values)->values.forEach((id,row)->{
            FameSession saved=fame.get(session);String name=saved==null?"Unknown":saved.getCharacterClassNames().getOrDefault(id,"Unknown");
            String label=sessions.get(session).toString();
            if((label+" "+name+" "+id).toLowerCase(Locale.ROOT).contains(query))rows.add(new Object[]{label,id,name,row.first,row.last,row.last-row.first});
        }));
        return HistoryTables.page(HistoryTables.table("history-character-fame",new String[]{"Session","Character ID","Class","First fame","Last fame","Change"},
                new Class<?>[]{String.class,Integer.class,String.class,Double.class,Double.class,Double.class},rows),"Fame changes use each character's first and last captured values within its app session.");
    }
    static final class Profile {
        static final String[] COLUMNS={"Dungeon","Observed runs","Items / run","Items / hour","UT / hour","Captured minutes","Items","White bags","UT gear","ST gear","Stat potions","Completed","Whites / run","UT / run","ST / run","Potions / run"};
        static final Class<?>[] TYPES={String.class,Long.class,Double.class,Double.class,Double.class,Double.class,Long.class,Long.class,Long.class,Long.class,Long.class,Long.class,Double.class,Double.class,Double.class,Double.class};
        long runs,completed,millis,items,whites,uts,sts,potions,damage;boolean unassigned;
        boolean damageKnown=true;
        void add(LootDashboard.Drop drop,boolean linked){
            unassigned|=!linked;items+=drop.items.size();if(drop.bag.equals("White")||drop.bag.equals("B.White"))whites++;
            for(LootDashboard.Item item:drop.items){if(item.ut)uts++;if(item.st)sts++;if(item.potion)potions++;}
        }
        Double perRun(long count){return unassigned||runs==0?null:count/(double)runs;}
        Double perHour(long count){return unassigned||millis<=0?null:count*3600000.0/millis;}
        Object[] row(String name){return new Object[]{name,runs,perRun(items),perHour(items),perHour(uts),millis/60000.0,items,whites,uts,sts,potions,completed,perRun(whites),perRun(uts),perRun(sts),perRun(potions)};}
    }
    private static final class CharacterFame {
        long firstTime=Long.MAX_VALUE,lastTime=Long.MIN_VALUE;double first,last;
        void add(long time,double value){if(time<firstTime){firstTime=time;first=value;}if(time>=lastTime){lastTime=time;last=value;}}
    }
}
