package tomato.gui.stats;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import tomato.backend.data.DungeonStatData;
import tomato.backend.data.Entity;
import tomato.gui.stats.LootQuery.*;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import tomato.realmshark.ParseEnchants;
import javax.swing.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Synthetic projections only: no windows, desktop, assets or capture required. */
public class ReportingStatisticsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static LootDashboard.Drop drop(long time, String source) {
        return new LootDashboard.Drop("White", "Ice Citadel", source, time,
            Collections.singletonList(new LootDashboard.Item(42, "Synthetic item", false)), "run");
    }
    // LootDashboard.Archive fed only the removed historical Statistics view (P6a Task 12). The same Recent Drops rules hold on the
    // live dashboard, whose one session key leaves the arrival ordinal to break equal timestamps.
    @Test public void globalNewestThousandSurviveOutOfOrderRecords() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            LootDashboard view=new LootDashboard();
            for(int i=1099;i>=0;i--)view.accept(drop(10000+i,"new"));
            for(int i=0;i<1100;i++)view.accept(drop(i,"old"));
            List<LootDashboard.Drop> recent=view.recentDrops();
            assertEquals(1000,recent.size());assertEquals(11099,recent.get(0).time);assertEquals(10100,recent.get(999).time);
            assertArrayEquals(new int[]{2200,2200},view.sessionTotals());
        });
    }
    @Test public void equalTimestampsUseTheArrivalOrdinalWithoutCollapsingDuplicates() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            LootDashboard first=new LootDashboard(),second=new LootDashboard();
            for(LootDashboard view:Arrays.asList(first,second)){
                for(int i=0;i<600;i++)view.accept(drop(1000,"z"+i));
                for(int i=0;i<600;i++)view.accept(drop(1000,"a"+i));
            }
            List<LootDashboard.Drop> a=first.recentDrops(),b=second.recentDrops();
            assertEquals(1000,a.size());for(int i=0;i<a.size();i++)assertEquals(a.get(i).dropper,b.get(i).dropper);
            assertEquals("a599",a.get(0).dropper);assertEquals("z200",a.get(999).dropper);
        });
    }
    private static ActivityJournal.Visit visit(String id,String map,long duration){
        ActivityJournal.Visit v=new ActivityJournal.Visit();v.id=id;v.map=map;v.started=1000;v.lastSeen=v.ended=1000+duration;return v;
    }
    // The dead Statistics view (HistoricalStatistics.loot/statistics) was removed; the same rate, exclusion and coverage
    // rules are asserted on the live StatisticsArchiveAdapter that Dungeons › Analysis and Loot's saved views read:
    // Dungeon loot profile = View.RATES, Session comparison = View.SESSIONS, and the coverage label is in Row.evidence.
    private static ArchiveQuery<Facets,Sort> query(String scope,View view){return LootQuery.initial(view,scope);}
    private List<Row> rows(SessionStore store,ArchiveQuery<Facets,Sort> q)throws Exception{
        try(ArchiveResult<Row> result=ArchiveResult.open(store,q,new StatisticsArchiveAdapter(q),temp.newFolder().toPath(),new Cancellation())){
            List<Row> rows=new ArrayList<>();result.stream(ExportSelection.all(),row->rows.add(row.value),new Cancellation());return rows;
        }
    }
    private static Row only(List<Row> rows){assertEquals(1,rows.size());return rows.get(0);}
    private Row rate(SessionStore store,String scope)throws Exception{return only(rows(store,query(scope,View.RATES)));}
    private Row session(SessionStore store,String scope)throws Exception{return only(rows(store,query(scope,View.SESSIONS)));}
    @Test public void ninetyNineUnknownVisitsCannotDiluteOneEvidencedVisit() throws Exception {
        Path root=temp.newFolder().toPath();String unknownId;
        try(SessionStore unknown=new SessionStore(root,true,"synthetic A")){
            unknownId=unknown.currentId();
            for(int i=0;i<99;i++){
                ActivityJournal.Visit v=visit("unknown-"+i,"Ice Citadel",60000);
                v.started+=60000L*i;v.lastSeen+=60000L*i;v.ended=v.lastSeen;
                unknown.put("runs",v.id,v);
            }
            unknown.flush();assertFalse(Files.exists(root.resolve(unknownId).resolve("loot.jsonl")));
        }
        try(SessionStore known=new SessionStore(root,true,"synthetic B")){
            known.put("runs","run",visit("run","Ice Citadel",60000));known.append("loot",drop(2000,"Boss"));known.flush();
            Row rate=rate(known,SessionStore.ALL);
            assertEquals((Long)1L,rate.runs);assertEquals((Long)60000L,rate.millis);
            assertEquals((Long)1L,rate.items);assertEquals((Double)1.0,rate.perRun);assertEquals((Double)60.0,rate.perHour);
            assertEquals((Long)99L,rate.unknownRuns);assertEquals((Long)0L,rate.importedRuns);assertEquals((Long)0L,rate.zeroLootRuns);
            assertTrue(rate.evidence.contains("0 with no linked bags"));assertTrue(rate.evidence.contains("99 unknown-coverage visits (5940000 observed milliseconds)"));
            List<Row> sessions=rows(known,query(SessionStore.ALL,View.SESSIONS));assertEquals(2,sessions.size());
            for(Row row:sessions){
                boolean unknownSession=Long.valueOf(99).equals(row.runs);
                if(unknownSession){assertEquals(unknownId,row.session);assertNull(row.items);assertTrue(row.evidence.contains("No saved loot · coverage unknown"));}
                else assertEquals((Long)1L,row.items);
            }
            known.importSnapshot("synthetic run-only import","Imported fixture",1000,"runs","imported",visit("imported","Ice Citadel",60000));
            Row withImport=rate(known,SessionStore.ALL);
            assertEquals((Long)1L,withImport.runs);assertEquals((Long)1L,withImport.importedRuns);assertEquals((Long)99L,withImport.unknownRuns);
            assertEquals((Double)1.0,withImport.perRun);assertEquals((Double)60.0,withImport.perHour);
        }
    }
    @Test public void evidencedSessionKeepsItsVisitWithoutLootInBothDenominators() throws Exception {
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic")){
            store.put("runs","run",visit("run","Ice Citadel",60000));store.put("runs","empty",visit("empty","Ice Citadel",60000));
            store.append("loot",drop(2000,"Boss"));store.flush();
            Row rate=rate(store,store.currentId());
            assertEquals((Long)2L,rate.runs);assertEquals((Long)120000L,rate.millis);
            assertEquals((Double)0.5,rate.perRun);assertEquals((Double)30.0,rate.perHour);assertEquals((Long)0L,rate.unknownRuns);
            assertEquals((Long)1L,rate.zeroLootRuns);assertTrue(rate.evidence.contains("1 with no linked bags"));
        }
    }
    @Test public void emptyBagEvidenceIsSessionScopedAndSurvivesDungeonFiltering() throws Exception {
        Path root=temp.newFolder().toPath();String knownId;
        try(SessionStore known=new SessionStore(root,true,"synthetic known")){
            knownId=known.currentId();
            known.put("runs","run",visit("run","Ice Citadel",60000));known.put("runs","empty",visit("empty","Lost Halls",60000));
            known.append("loot",new LootDashboard.Drop("Blue","Ice Citadel","Boss",2000,Collections.emptyList(),"run"));known.flush();
        }
        try(SessionStore unknown=new SessionStore(root,true,"synthetic unknown")){
            unknown.put("runs","empty",visit("empty","Lost Halls",60000));unknown.flush();
            // The dungeon filter is the rate text search or the dungeon facet; neither narrows the session's loot evidence.
            for(String scope:Arrays.asList(knownId,SessionStore.ALL))for(ArchiveQuery<Facets,Sort> q:lostHalls(scope)){
                Row row=only(rows(unknown,q));
                assertEquals("Lost Halls",row.dungeon);assertEquals((Long)1L,row.runs);
                assertEquals((Long)0L,row.items);assertEquals((Double)0.0,row.perRun);assertEquals((Double)0.0,row.perHour);
                assertTrue(row.evidence.contains("Partial · session loot evidence"));
                assertEquals(scope.equals(SessionStore.ALL)?(Long)1L:(Long)0L,row.unknownRuns);
            }
            for(ArchiveQuery<Facets,Sort> q:lostHalls(unknown.currentId())){
                Row alone=only(rows(unknown,q));
                assertEquals((Long)0L,alone.runs);assertEquals((Long)1L,alone.unknownRuns);
                assertNull(alone.items);assertNull(alone.perRun);assertNull(alone.perHour);
                assertTrue(alone.evidence.contains("No saved loot · coverage unknown"));
            }
        }
    }
    private static List<ArchiveQuery<Facets,Sort>> lostHalls(String scope){
        ArchiveQuery<Facets,Sort> text=query(scope,View.RATES).withText("Lost Halls"),facet=query(scope,View.RATES);
        Facets f=facet.facets();f.dungeons.add("Lost Halls");return Arrays.asList(text,facet.withFacets(f));
    }
    @Test public void onlyEligibleSessionDurationsCanInvalidateTheHourlyRate() throws Exception {
        for(boolean knownMissingDuration:new boolean[]{false,true}){
            Path root=temp.newFolder().toPath();
            try(SessionStore unknown=new SessionStore(root,true,"synthetic unknown")){
                unknown.put("runs","gap",visit("gap","Ice Citadel",0));unknown.flush();
            }
            try(SessionStore known=new SessionStore(root,true,"synthetic known")){
                known.put("runs","run",visit("run","Ice Citadel",60000));known.append("loot",drop(2000,"Boss"));
                if(knownMissingDuration)known.put("runs","gap",visit("gap","Ice Citadel",0));known.flush();
                Row rate=rate(known,SessionStore.ALL);
                assertEquals((Long)1L,rate.unknownRuns);assertEquals(knownMissingDuration?(Long)2L:(Long)1L,rate.runs);
                assertEquals(knownMissingDuration?(Double)0.5:(Double)1.0,rate.perRun);
                if(knownMissingDuration){assertNull(rate.perHour);assertTrue(rate.evidence.contains("at least one visit has no positive observed duration"));}
                else assertEquals((Double)60.0,rate.perHour);
            }
        }
    }
    @Test public void unassignedBagEstablishesSessionEvidenceButCannotCreateARate() throws Exception {
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic")){
            store.put("runs","a",visit("a","Ice Citadel",60000));store.put("runs","b",visit("b","Ice Citadel",60000));
            store.append("loot",drop(2000,"Boss"));store.flush(); // Bag refers to absent visit "run".
            Row rate=rate(store,store.currentId());
            assertEquals((Long)2L,rate.runs);assertEquals((Long)0L,rate.unknownRuns);assertEquals((Long)1L,rate.items);
            assertNull(rate.perRun);assertNull(rate.perHour);
            assertEquals((Long)1L,rate.unassignedBags);assertTrue(rate.evidence.contains("Unassigned bags: 1"));
        }
    }
    @Test public void unknownAndImportedOnlyCohortsDoNotBecomeRecordedZero() throws Exception {
        Path root=temp.newFolder().toPath();
        try(SessionStore imported=new SessionStore(root,true,"Imported")){
            imported.put("runs","run",visit("run","Ice Citadel",60000));imported.flush();
        }
        try(SessionStore unknown=new SessionStore(root,true,"synthetic")){
            unknown.put("runs","run",visit("run","Ice Citadel",60000));unknown.flush();
            Row rate=rate(unknown,SessionStore.ALL);
            assertEquals((Long)0L,rate.runs);assertEquals((Long)1L,rate.importedRuns);assertEquals((Long)1L,rate.unknownRuns);
            assertNull(rate.items);assertNull(rate.perRun);assertNull(rate.perHour);
            assertTrue(rate.evidence.contains("No saved loot · coverage unknown"));
        }
    }
    @Test public void runOnlyImportsExposeUnavailableLootInBothReports() throws Exception {
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"Imported")){
            store.put("runs","run",visit("run","Ice Citadel",60000));store.flush();
            Row session=session(store,store.currentId());
            assertEquals((Long)1L,session.runs);assertNull(session.items);assertTrue(session.evidence.contains("Not captured · run-only import"));
            Row dungeon=rate(store,store.currentId());
            assertEquals((Long)0L,dungeon.runs);assertEquals((Long)1L,dungeon.importedRuns);assertNull(dungeon.perRun);
            assertTrue(dungeon.evidence.contains("run-only imported visits"));
        }
    }
    @Test public void missingLootJournalIsUnknownButAnObservedEmptyBagEstablishesZero() throws Exception {
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic")){
            store.put("runs","run",visit("run","Ice Citadel",60000));store.flush();
            Row unknown=session(store,store.currentId());
            assertNull(unknown.items);assertTrue(unknown.evidence.contains("No saved loot · coverage unknown"));
            store.append("loot",new LootDashboard.Drop("White","Ice Citadel","Boss",2000,Collections.emptyList(),"run"));store.flush();
            Row zero=rate(store,store.currentId());
            assertEquals((Long)0L,zero.items);assertEquals((Double)0.0,zero.perRun);
            assertEquals((Long)1L,zero.whites);
        }
    }
    @Test public void aliasJoinIncludesZeroLootVisitsAndRateDetailsAndRejectsMissingDurations() throws Exception {
        String alias="Cave of A Thousand Treasures",canonical="Cave of a Thousand Treasures";
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic")){
            store.put("runs","run",visit("run",alias,60000));store.put("runs","empty",visit("empty",canonical,60000));
            List<LootDashboard.Item> items=Arrays.asList(new LootDashboard.Item(1,"A",false),new LootDashboard.Item(2,"B",false),new LootDashboard.Item(3,"C",false));
            store.append("loot",new LootDashboard.Drop("White",canonical,"Boss",2000,items,"run"));store.flush();
            Row rate=rate(store,store.currentId());
            assertEquals(canonical,rate.dungeon);
            assertEquals((Double)1.5,rate.perRun);assertEquals((Double)90.0,rate.perHour);
            assertTrue(rate.evidence.contains("1 with no linked bags"));assertTrue(rate.evidence.contains("120000 observed milliseconds"));
            store.put("runs","gap",visit("gap",canonical,0));store.flush();
            Row gap=rate(store,store.currentId());
            assertEquals((Double)1.0,gap.perRun);assertNull(gap.perHour);
        }
    }
    @Test public void profilesIncludeZeroLootRunsAndCompareSessionsWithoutMixingCharacterIds() throws Exception {
        // Moved from the deleted HistoricalStatisticsTest (same fixture), asserted on the live RATES, SESSIONS and FAME views.
        Path root=temp.newFolder().toPath();String oldId;
        try(SessionStore old=new SessionStore(root,true,"one")){
            oldId=old.currentId();
            ActivityJournal.Visit one=visit("run-one","Ice Citadel",60000),empty=visit("run-empty","Ice Citadel",60000);
            empty.started+=60000;empty.lastSeen+=60000;empty.ended=empty.lastSeen;
            old.put("runs",one.id,one);old.put("runs",empty.id,empty);
            old.append("loot",new LootDashboard.Drop("White","Ice Citadel","Boss",2000,Arrays.asList(
                new LootDashboard.Item(1,"UT blade","EQUIPMENT,WEAPON,UT",ParseEnchants.summarize("")),
                new LootDashboard.Item(2,"ST robe","EQUIPMENT,ARMOR,ST",ParseEnchants.summarize("")),
                new LootDashboard.Item(3,"Potion","EQUIPMENT,CONSUMABLE,STATPOTION",ParseEnchants.summarize(""))),"run-one"));
            old.append("fame",new AppHistory.FameSample(7,100,1000,"Wizard"));old.append("fame",new AppHistory.FameSample(7,150,61000,"Wizard"));
            old.flush();
        }
        try(SessionStore now=new SessionStore(root,true,"two")){
            now.append("fame",new AppHistory.FameSample(7,300,1000,"Wizard"));now.append("fame",new AppHistory.FameSample(7,320,61000,"Wizard"));now.flush();
            Row rate=rate(now,SessionStore.ALL);
            assertEquals((Long)2L,rate.runs);assertEquals((Long)120000L,rate.millis);
            assertEquals((Long)3L,rate.items);assertEquals((Double)1.5,rate.perRun);assertEquals((Double)90.0,rate.perHour);
            assertEquals((Double)30.0,rate.utPerHour);assertEquals((Double)0.5,rate.whitesPerRun);
            assertEquals((Long)1L,rate.uts);assertEquals((Long)1L,rate.sts);assertEquals((Long)1L,rate.potions);
            Map<String,Double> gains=new HashMap<>();for(Row row:rows(now,query(SessionStore.ALL,View.SESSIONS)))gains.put(row.session,row.gain);
            assertEquals(2,gains.size());assertEquals((Double)50.0,gains.get(oldId));assertEquals((Double)20.0,gains.get(now.currentId()));
            List<Row> characters=rows(now,query(SessionStore.ALL,View.FAME));assertEquals(2,characters.size());
            Set<String> characterSessions=new HashSet<>();for(Row row:characters){assertEquals((Integer)7,row.character);characterSessions.add(row.session);}
            assertEquals(new HashSet<>(Arrays.asList(oldId,now.currentId())),characterSessions);
            now.append("loot",new LootDashboard.Drop("White","Ice Citadel","Unknown",2000,Collections.emptyList(),"missing-run"));now.flush();
            assertNull(rate(now,SessionStore.ALL).perRun);
            now.delete(oldId);assertTrue(now.read(oldId,"loot",LootDashboard.Drop.class).isEmpty());
        }
    }
    // Polish B1: a legacy saved bag without a name (no "bag" field) made every profile read fail ("drop.bag" is null). It is still a
    // bag and its items count as before, but it is never a white bag, and the explanation says how many such bags there were.
    @Test public void aSavedBagWithoutANameReadsInRatesAndSessionsAndIsNeverAWhiteBag() throws Exception {
        Path root=temp.newFolder().toPath();
        try(SessionStore store=new SessionStore(root,true,"synthetic")){
            store.put("runs","run",visit("run","Ice Citadel",60000));
            store.append("loot",new LootDashboard.Drop(null,"Ice Citadel","Boss",2000,Arrays.asList(
                new LootDashboard.Item(1,"UT blade","EQUIPMENT,WEAPON,UT",ParseEnchants.summarize("")),
                new LootDashboard.Item(2,"ST robe","EQUIPMENT,ARMOR,ST",ParseEnchants.summarize("")),
                new LootDashboard.Item(3,"Potion","EQUIPMENT,CONSUMABLE,STATPOTION",ParseEnchants.summarize(""))),"run"));
            store.append("loot",drop(3000,"Boss"));   // a named white bag in the same visit
            store.flush();
            List<String> saved=Files.readAllLines(root.resolve(store.currentId()).resolve("loot.jsonl"));
            assertEquals(2,saved.size());assertFalse("The first saved bag has no bag field, as a legacy save",saved.get(0).contains("\"bag\""));
            Row rate=rate(store,store.currentId());
            assertEquals((Long)2L,rate.bags);assertEquals((Long)4L,rate.items);
            assertEquals("Only the named white bag is a white bag",(Long)1L,rate.whites);
            assertEquals((Long)1L,rate.uts);assertEquals((Long)1L,rate.sts);assertEquals((Long)1L,rate.potions);
            assertEquals((Double)4.0,rate.perRun);assertEquals((Double)1.0,rate.whitesPerRun);
            assertTrue(rate.evidence,rate.evidence.contains("4 items, 1 white bags, 1 UT gear, 1 ST gear, 1 stat potions (observed, not owned). 1 bag without a saved bag name is not counted as a white bag."));
            Row session=session(store,store.currentId());
            assertEquals((Long)2L,session.bags);assertEquals((Long)4L,session.items);assertEquals((Long)1L,session.whites);assertEquals((Long)1L,session.runs);
            assertTrue(session.evidence,session.evidence.contains("Unassigned bags: 0. 1 bag without a saved bag name is not counted as a white bag."));
        }
    }
    @Test public void theProfileCountsBagsWithoutASavedNameAndNamesThemInItsExplanation() {
        LootProfile profile=new LootProfile();profile.runs=1;profile.millis=60000;
        profile.add(new LootDashboard.Drop(null,"Ice Citadel","Boss",2000,Collections.emptyList(),"run"),true);
        profile.add(new LootDashboard.Drop(" ","Ice Citadel","Boss",2000,Collections.emptyList(),"run"),true);   // blank: no name either (as Highlights)
        profile.add(new LootDashboard.Drop("B.White","Ice Citadel","Boss",2000,Collections.emptyList(),"run"),true);
        assertEquals(3,profile.bags);assertEquals(2,profile.unnamedBags);assertEquals(1,profile.whites);
        assertTrue(profile.explanation(),profile.explanation().contains("0 items, 1 white bags, 0 UT gear, 0 ST gear, 0 stat potions (observed, not owned). 2 bags without a saved bag name are not counted as white bags."));
        LootProfile named=new LootProfile();named.add(drop(2000,"Boss"),true);
        assertFalse("No note when every bag has a name",named.explanation().contains("without a saved bag name"));
    }
    @Test public void unassignedBagMakesRateUnavailableEvenWithAnEligibleVisit() {
        LootProfile profile=new LootProfile();profile.runs=2;profile.millis=120000;
        profile.add(drop(2000,"test"),false);
        assertNull(profile.perRun(profile.items));assertNull(profile.perHour(profile.items));
        assertTrue(profile.explanation().contains("Unassigned bags: 1"));
    }
    // The table row (columns and types) of the removed historical view is gone (P6a Task 12); its values are asserted directly.
    @Test public void lootProfileWithEvidenceAndNoItemsIsZeroPerRunAndPartial() {
        LootProfile profile=new LootProfile();profile.runs=2;profile.millis=120000;profile.lootEvidence=true;
        assertEquals((Double)0.0,profile.perRun(profile.items));assertEquals(Long.valueOf(0),profile.lootValue(profile.items));
        assertTrue(profile.coverage().startsWith("Partial"));
    }
    @Test public void ongoingActivityIsSeparateFromFinalizedExitsAndOldMetadataStaysUnknown() {
        DungeonStatData data=new DungeonStatData(temp.getRoot().toPath().resolve("synthetic.stats"));
        data.updateDungeon("Ice Citadel",1000);assertTrue(data.sessionSnapshot().isEmpty());
        Entity enemy=new Entity(null,1,42);enemy.objectType=42;
        data.updateEntityDamage("Ice Citadel",enemy);
        DungeonStatData.Snapshot ongoing=data.sessionSnapshot().get(0);
        assertEquals(0,ongoing.visits);assertEquals(0,ongoing.time);assertEquals(1,ongoing.hitCount());assertEquals(Boolean.TRUE,ongoing.ongoingActivity);
        data.updateDungeon("Ice Citadel",1000);DungeonStatData.Snapshot ended=data.sessionSnapshot().get(0);
        assertEquals(1,ended.visits);assertEquals(1000,ended.time);assertEquals(Boolean.FALSE,ended.ongoingActivity);
        DungeonStatData.Snapshot legacy=SessionStore.JSON.fromJson("{\"name\":\"Ice Citadel\",\"visits\":1,\"time\":1000}",DungeonStatData.Snapshot.class);
        assertNull(legacy.ongoingActivity);
        assertTrue(data.flush()); // Drain the background save before the isolated fixture is removed.
    }
    @Test public void canonicalCountersMergeVerifiedAliasesButPreserveUnrecognizedAreas() {
        DungeonStatData.Snapshot a=new DungeonStatData.Snapshot("Cave of A Thousand Treasures",1,1000);
        DungeonStatData.Snapshot b=new DungeonStatData.Snapshot("Cave of a Thousand Treasures",2,2000);
        a.hits.put(42,1);b.hits.put(42,2);b.ongoingActivity=true;
        List<DungeonStatData.Snapshot> rows=DungeonStatData.Snapshot.canonicalize(Arrays.asList(a,b));
        assertEquals(1,rows.size());assertEquals(3,rows.get(0).visits);assertEquals(3,rows.get(0).hitCount());assertEquals(Boolean.TRUE,rows.get(0).ongoingActivity);
        assertEquals("Cave of A Thousand Treasures",a.name);assertEquals(1,a.visits);
        assertEquals(2,DungeonStatData.Snapshot.canonicalize(Arrays.asList(new DungeonStatData.Snapshot("Unknown A",0,0),new DungeonStatData.Snapshot("Unknown B",0,0))).size());
    }
}
