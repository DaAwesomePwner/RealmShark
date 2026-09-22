package tomato.gui.stats;

import tomato.history.*;
import tomato.gui.history.*;
import tomato.gui.stats.session.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.Evidence;
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
        return new SessionPanel.Loaded(()->data.view(false),false,"Saved totals · select a dungeon for rate inputs, exclusions and coverage");
    }
    public static SessionPanel.Loaded statistics(SessionStore store,String scope,int page,String query)throws IOException{
        HistoricalStatistics data=load(store,scope,query,true);
        return new SessionPanel.Loaded(()->data.view(true),false,data.sessions.size()+" sessions · compare by session or dungeon");
    }
    private static HistoricalStatistics load(SessionStore store,String scope,String query,boolean withFame)throws IOException{
        HistoricalStatistics data=new HistoricalStatistics(query);
        data.currentId=store.currentId();
        for(SessionStore.Session session:store.sessions())if(scope.equals(SessionStore.ALL)||scope.equals(session.id)){
            data.sessions.put(session.id,session);Profile profile=new Profile();profile.imported="Imported".equals(session.version);data.profiles.put(session.id,profile);
        }
        Map<String,Map<String,Profile>> visitCohorts=new LinkedHashMap<>();
        store.read(scope,"runs",ActivityJournal.Visit.class,(session,visit)->{
            if(!ParseDungeon.isDungeon(visit.map))return;
            Profile sessionProfile=data.profiles.computeIfAbsent(session.id,k->new Profile());sessionProfile.runs++;
            sessionProfile.millis+=visit.observedMillis();if("Completed".equals(visit.runStatus()))sessionProfile.completed++;
            sessionProfile.damage+=visit.totalDamage;
            sessionProfile.damageKnown &= visit.damageTracked;
            String map=canonical(visit.map);
            Profile dungeon=data.dungeons.computeIfAbsent(map,k->new Profile());
            if("Imported".equals(session.version)){dungeon.excludedRuns++;return;}
            Profile cohort=visitCohorts.computeIfAbsent(session.id,k->new TreeMap<>()).computeIfAbsent(map,k->new Profile());
            cohort.runs++;cohort.millis+=visit.observedMillis();
            cohort.missingDuration|=visit.observedMillis()<=0;
            if(visit.ended==0)cohort.ongoing++;
            if("Completed".equals(visit.runStatus()))cohort.completed++;
            data.runs.put(session.id+"/"+visit.id,map);
        });
        store.read(scope,"loot",LootDashboard.Drop.class,(session,drop)->{
            data.loot.accept(session.id,drop);
            String map=canonical(drop.dungeon);
            Profile dungeon=data.dungeons.computeIfAbsent(map,k->new Profile());Profile sessionProfile=data.profiles.computeIfAbsent(session.id,k->new Profile());
            boolean linked=drop.visitId!=null&&map.equals(data.runs.get(session.id+"/"+drop.visitId));
            dungeon.add(drop,linked);sessionProfile.add(drop,linked);
            if(linked)dungeon.lootRuns.add(session.id+"/"+drop.visitId);
        });
        // Read each loot journal once before admitting any session's visits. Older
        // sessions have no module-availability metadata; another session's bags
        // cannot turn their unknown coverage into recorded zero-loot visits.
        visitCohorts.forEach((session,cohorts)->cohorts.forEach((map,cohort)->{
            Profile dungeon=data.dungeons.get(map);
            if(data.profiles.get(session).bags>0){
                dungeon.lootEvidence=true;dungeon.runs+=cohort.runs;dungeon.millis+=cohort.millis;
                dungeon.completed+=cohort.completed;dungeon.ongoing+=cohort.ongoing;dungeon.missingDuration|=cohort.missingDuration;
            }else{
                dungeon.unknownRuns+=cohort.runs;dungeon.unknownMillis+=cohort.millis;
            }
        }));
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
    private static String canonical(String name){return tomato.backend.data.DungeonStatData.Snapshot.canonicalName(name);}
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
        List<tomato.backend.data.DungeonStatData.Snapshot> result=tomato.backend.data.DungeonStatData.Snapshot.canonicalize(dungeonTotals);
        result.removeIf(row->!row.name.toLowerCase(Locale.ROOT).contains(query));return result;
    }
    private JComponent dungeonComparison(){
        List<Object[]> rows=new ArrayList<>();List<Profile> profiles=new ArrayList<>();
        dungeons.forEach((name,p)->{if(name.toLowerCase(Locale.ROOT).contains(query)){rows.add(p.row(name));profiles.add(p);}});
        JTable table=HistoryTables.table("history-dungeon-loot",Profile.COLUMNS,Profile.TYPES,rows);
        table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(4,SortOrder.DESCENDING)));
        JTextArea details=StatsUi.note("Select a dungeon to inspect rate calculations and exclusions.");details.setName("history-loot-rate-details");
        details.getAccessibleContext().setAccessibleName("Selected dungeon loot rate evidence");
        table.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting()){int row=table.getSelectedRow();details.setText(row<0?"Select a dungeon to inspect rate calculations and exclusions.":profiles.get(table.convertRowIndexToModel(row)).explanation());details.setCaretPosition(0);}});
        JPanel panel=new JPanel(new BorderLayout(0,6));panel.add(HistoryTables.page(table,"Observed drops, not pickups. Rates exclude run-only imports and sessions without saved loot evidence. Select a row for eligible numerator, denominator and coverage; sample rates are not drop probabilities."));
        JScrollPane scroll=new JScrollPane(details);scroll.setPreferredSize(new Dimension(400,145));panel.add(scroll,BorderLayout.SOUTH);
        if(table.getRowCount()>0)table.setRowSelectionInterval(0,0);
        return panel;
    }
    private JComponent sessionComparison(boolean withFame){
        List<Object[]> rows=new ArrayList<>();List<String> ids=new ArrayList<>();
        sessions.forEach((id,session)->{
            if(!session.toString().toLowerCase(Locale.ROOT).contains(query)&&!query.isEmpty())return;
            Profile p=profiles.get(id);double gain=0;
            for(CharacterFame row:characters.getOrDefault(id,Collections.emptyMap()).values())gain+=row.last-row.first;
            rows.add(new Object[]{(id.equals(currentId)?"Current · ":"")+session,Instant.ofEpochMilli(session.started),p.runs,p.completed,p.millis/60000.0,characters.containsKey(id)?gain:null,p.lootValue(p.items),p.lootValue(p.whites),p.lootValue(p.uts),p.lootValue(p.sts),p.lootValue(p.potions),p.damageKnown&&p.runs>0?p.damage:null,session.version,p.coverage()});ids.add(id);
        });
        JTable table=HistoryTables.table("history-session-comparison",new String[]{"Session","Started","Dungeon visits","Completed","Captured minutes","Fame change","Items","White bags","UT gear","ST gear","Stat potions","Damage","Build","Loot coverage"},
                new Class<?>[]{String.class,Instant.class,Long.class,Long.class,Double.class,Double.class,Long.class,Long.class,Long.class,Long.class,Long.class,Long.class,String.class,String.class},rows);
        JPanel panel=new JPanel(new BorderLayout(0,6));panel.add(HistoryTables.page(table,"Visits include ongoing and zero-activity visits, unlike activity-recorded exits in Dungeon statistics. Captured time sums observed visit spans. Run-only imports: loot not captured; no saved loot in older sessions: coverage unknown, not zero."));
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
        static final String[] COLUMNS={"Dungeon","Observed runs","Items / run","Items / hour","UT / hour","Captured minutes","Items","White bags","UT gear","ST gear","Stat potions","Completed","Whites / run","UT / run","ST / run","Potions / run","Excluded imported runs","Excluded unknown-coverage runs","Loot coverage"};
        static final Class<?>[] TYPES={String.class,Long.class,Double.class,Double.class,Double.class,Double.class,Long.class,Long.class,Long.class,Long.class,Long.class,Long.class,Double.class,Double.class,Double.class,Double.class,Long.class,Long.class,String.class};
        long runs,completed,millis,items,whites,uts,sts,potions,damage;boolean unassigned;
        long bags,unassignedBags,excludedRuns,unknownRuns,unknownMillis,ongoing;
        boolean imported,missingDuration,lootEvidence;
        final Set<String> lootRuns=new HashSet<>();
        boolean damageKnown=true;
        void add(LootDashboard.Drop drop,boolean linked){
            lootEvidence=true;bags++;if(!linked)unassignedBags++;unassigned|=!linked;items+=drop.items.size();if(drop.bag.equals("White")||drop.bag.equals("B.White"))whites++;
            for(LootDashboard.Item item:drop.items){if(item.ut)uts++;if(item.st)sts++;if(item.potion)potions++;}
        }
        Long lootValue(long count){return lootEvidence?count:null;}
        String coverage(){return lootEvidence?Evidence.Coverage.PARTIAL+" · session loot evidence; gaps unknown":imported||excludedRuns>0&&unknownRuns==0&&runs==0?Evidence.Coverage.NOT_CAPTURED+" · run-only import":"No saved loot · coverage unknown";}
        Double perRun(long count){return !lootEvidence||unassigned||runs==0?null:count/(double)runs;}
        Double perHour(long count){return !lootEvidence||unassigned||missingDuration||millis<=0?null:count*3600000.0/millis;}
        String explanation(){return coverage()+"\nNumerators: "+(!lootEvidence?"unavailable — no saved loot evidence.":items+" items, "+whites+" white bags, "+uts+" UT gear, "+sts+" ST gear, "+potions+" stat potions (observed, not owned).")
            +"\nPer run = numerator / "+runs+" eligible observed visits; "+(runs-lootRuns.size())+" with no linked bags; "+ongoing+" ongoing visits included."
            +"\nPer hour = numerator × 3,600,000 / "+millis+" observed milliseconds (first-to-last observation for each visit; includes gaps)."
            +"\nExcluded: "+excludedRuns+" run-only imported visits; "+unknownRuns+" unknown-coverage visits ("+unknownMillis+" observed milliseconds) from sessions without saved loot evidence. Unassigned bags: "+unassignedBags+" (session + visit + canonical dungeon must agree)."
            +"\nEligibility requires at least one saved bag in the same session; even an empty or unassigned bag establishes partial module evidence, not complete recording."
            +"\n"+(!lootEvidence?"Rates unavailable: no saved loot evidence; absence does not establish zero.":unassigned?"Rates unavailable: unassigned drops would mix numerator and denominator scopes.":runs==0?"Rates unavailable: no eligible observed visits.":"Zero-loot visits within evidenced sessions remain in the denominator; unknown-coverage sessions do not. No claim of complete recording or true drop probability.")
            +(missingDuration?" Hourly rates unavailable: at least one visit has no positive observed duration.":"");}
        Object[] row(String name){return new Object[]{name,runs,perRun(items),perHour(items),perHour(uts),millis/60000.0,lootValue(items),lootValue(whites),lootValue(uts),lootValue(sts),lootValue(potions),completed,perRun(whites),perRun(uts),perRun(sts),perRun(potions),excludedRuns,unknownRuns,coverage()};}
    }
    private static final class CharacterFame {
        long firstTime=Long.MAX_VALUE,lastTime=Long.MIN_VALUE;double first,last;
        void add(long time,double value){if(time<firstTime){firstTime=time;first=value;}if(time>=lastTime){lastTime=time;last=value;}}
    }
}
