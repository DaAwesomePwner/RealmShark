package tomato.gui.stats;

import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import tomato.gui.stats.LootQuery.*;
import tomato.history.*;
import tomato.history.archive.*;
import tomato.realmshark.ParseEnchants;
import packets.packetcapture.logger.ActivityJournal;
import static org.junit.Assert.*;

public class LootArchiveQueryTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    static LootDashboard.Item item(int id,String name,String labels,String encoded){return new LootDashboard.Item(id,name,labels,ParseEnchants.summarize(encoded));}
    static LootDashboard.Drop drop(long time,String map,String bag,String visit,LootDashboard.Item...items){return new LootDashboard.Drop(bag,map,"Synthetic boss",time,Arrays.asList(items),visit);}
    static ArchiveQuery<Facets,Sort> query(String scope,View view){Facets f=new Facets();f.view=view;return LootQuery.initial(View.OCCURRENCES,ArchiveQuery.CURRENT).withScope(scope).withFacets(f);}
    private ArchiveResult<Row> open(SessionStore store,ArchiveQuery<Facets,Sort> q)throws Exception{return ArchiveResult.open(store,q,q.facets().view.loot()?new LootArchiveAdapter(q):new StatisticsArchiveAdapter(q),temp.newFolder().toPath(),new Cancellation());}
    private static List<ArchiveRow<Row>> rows(ArchiveResult<Row> result)throws Exception{List<ArchiveRow<Row>> rows=new ArrayList<>();result.stream(ExportSelection.all(),rows::add,new Cancellation());return rows;}
    @Test public void globalRareSearchBeyondTwoThousandBagsHasStableDuplicateOriginsAndPinnedExports()throws Exception{
        Path root=temp.newFolder().toPath(),output=temp.newFolder().toPath();String oldId;
        LootDashboard.Item normal=item(1,"Plain sword","WEAPON,T13",""),rare=item(42,"Rare unused UT","WEAPON,UT",LootEquipmentTest.encode(-1,-1));
        try(SessionStore old=new SessionStore(root,true,"old")){oldId=old.currentId();for(int i=0;i<1105;i++)old.append("loot",i==3?drop(1003,"Lost Halls","White","same",rare,rare):drop(1000+i,"Ice Citadel","Blue","same",normal));old.flush();}
        try(SessionStore current=new SessionStore(root,true,"new")){
            for(int i=0;i<1105;i++)current.append("loot",drop(10000+i,"Ice Citadel","Blue","same",normal));current.flush();
            try(ArchiveResult<Row> all=open(current,query(SessionStore.ALL,View.OCCURRENCES))){
                assertEquals(2211,all.matches);assertEquals(11104L,all.page(0,100,new Cancellation()).rows.get(0).value.time.longValue());
                assertEquals(11,all.page(22,100,new Cancellation()).rows.size());assertTrue(all.maximumChunkRows<=ArchiveResult.CHUNK_ROWS);
            }
            ArchiveQuery<Facets,Sort> q=query(SessionStore.ALL,View.OCCURRENCES);Facets f=q.facets();f.kind=Kind.UT_EQUIPMENT;f.slots.min=2;f.slots.unknown=Unknown.EXCLUDE;f.applied.min=f.applied.max=0;f.applied.unknown=Unknown.EXCLUDE;f.bags.addAll(Arrays.asList("White","Orange"));f.dungeons.addAll(Arrays.asList("Lost Halls","The Void"));q=q.withFacets(f);
            try(ArchiveResult<Row> result=open(current,q);ArchiveResult.Lease<Row> lease=result.lease()){
                List<ArchiveRow<Row>> matches=rows(result);assertEquals(2,matches.size());assertEquals(oldId,matches.get(0).ref.session);assertNotEquals(matches.get(0).ref,matches.get(1).ref);
                assertEquals(matches.get(0).ref.locator,matches.get(1).ref.locator);assertTrue(matches.get(0).ref.child.contains("item-"));assertEquals("same",matches.get(0).value.visitId);
                ArchivePage<Row> page=result.page(0,1,new Cancellation());assertEquals(2,page.counts.get("matching occurrences").value);assertEquals(1,page.counts.get("matching bags").value);assertEquals(1,page.counts.get("matching variants").value);assertEquals(2210,page.counts.get("scope bags").value);
                assertEquals(1,result.pageOf(matches.get(1).ref,1,new Cancellation()));
                current.append("loot",drop(99999,"Lost Halls","White","same",rare));current.flush();
                Path json=ArchiveExport.write(lease,ExportSelection.all(),ArchiveExport.Format.JSON,output,"loot",new LootArchiveClient(output,LootExploreModel.views(),LootExploreModel.initialQuery()).exportColumns(),new Cancellation());
                JsonObject document=JsonParser.parseString(new String(Files.readAllBytes(json),StandardCharsets.UTF_8)).getAsJsonObject();
                assertEquals(2,document.getAsJsonArray("rows").size());assertEquals(result.revision,document.getAsJsonObject("manifest").get("revision").getAsString());assertEquals(2,document.getAsJsonObject("manifest").get("exportCount").getAsInt());
                Path csv=ArchiveExport.write(lease,ExportSelection.all(),ArchiveExport.Format.CSV,output,"loot",new LootArchiveClient(output,LootExploreModel.views(),LootExploreModel.initialQuery()).exportColumns(),new Cancellation());
                List<String> lines=Files.readAllLines(csv,StandardCharsets.UTF_8);assertEquals(4,lines.size());assertTrue(lines.get(1).contains("Item ID"));assertTrue(lines.get(2).contains("Rare unused UT"));
                try(ArchiveResult<Row> again=open(current,q)){assertEquals(matches.get(0).ref,again.page(1,1,new Cancellation()).rows.get(0).ref);assertEquals(matches.get(1).ref,again.page(2,1,new Cancellation()).rows.get(0).ref);}
            }
            f.view=View.ITEMS;try(ArchiveResult<Row> grouped=open(current,q.withFacets(f))){assertEquals(1,grouped.matches);assertEquals(3L,rows(grouped).get(0).value.count.longValue());assertEquals(2L,rows(grouped).get(0).value.bags.longValue());}
            try(ArchiveResult<Row> recent=open(current,query(SessionStore.ALL,View.RECENT))){assertEquals(2211,recent.matches);assertEquals("bags",recent.unit);}
        }
    }
    @Test public void unknownCountsAreNeverZeroAndUtMeansWearableEquipment()throws Exception{
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"fixture")){
            store.append("loot",drop(1000,"Ice Citadel","White","",item(1,"Unknown","WEAPON,UT",null),item(2,"Zero","WEAPON,UT",""),item(3,"Rune","CONSUMABLE,UT",""),item(4,"Slotted empty","ARMOR,UT",LootEquipmentTest.encode(-1,-1))));store.flush();
            ArchiveQuery<Facets,Sort> q=query(store.currentId(),View.OCCURRENCES);Facets f=q.facets();f.kind=Kind.UT_EQUIPMENT;f.applied.min=f.applied.max=0;f.applied.unknown=Unknown.EXCLUDE;
            try(ArchiveResult<Row> result=open(store,q.withFacets(f))){assertEquals(2,result.matches);assertEquals(new HashSet<>(Arrays.asList(2,4)),ids(rows(result)));}
            f.applied.unknown=Unknown.ONLY;try(ArchiveResult<Row> result=open(store,q.withFacets(f))){assertEquals(1,result.matches);assertNull(rows(result).get(0).value.applied);}
            f.applied.unknown=Unknown.INCLUDE;try(ArchiveResult<Row> result=open(store,q.withFacets(f))){assertEquals(3,result.matches);}
            f.rarities.add("Rare");f.slots.min=2;f.slots.unknown=Unknown.EXCLUDE;try(ArchiveResult<Row> result=open(store,q.withFacets(f))){assertEquals(Collections.singleton(4),ids(rows(result)));}
        }
    }
    private static Set<Integer> ids(List<ArchiveRow<Row>> rows){Set<Integer> ids=new HashSet<>();for(ArchiveRow<Row> row:rows)ids.add(row.value.itemId);return ids;}
    @Test public void groupingUsesAllMatchesAndCountsBagsOnceBeforePaging()throws Exception{
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"fixture")){
            LootDashboard.Item a=item(1,"Needle","WEAPON,UT",""),b=item(2,"Other","WEAPON,T13","");
            for(int i=0;i<2201;i++)store.append("loot",drop(1000+i,i%2==0?"Lost Halls":"Ice Citadel",i%2==0?"White":"Blue","",a,a,b));store.flush();
            ArchiveQuery<Facets,Sort> q=query(store.currentId(),View.BAGS).withText("Needle").withOrder(Collections.singletonList(new ArchiveQuery.Order<>(Sort.ITEMS,ArchiveQuery.Direction.DESCENDING)));
            try(ArchiveResult<Row> result=open(store,q)){
                assertEquals(2,result.matches);assertEquals(4402,result.page(0,1,new Cancellation()).counts.get("matching occurrences").value);
                assertEquals(2201,result.page(1,1,new Cancellation()).counts.get("matching bags").value);
                assertEquals(4402,rows(result).stream().mapToLong(r->r.value.items).sum());assertEquals(2201,rows(result).stream().mapToLong(r->r.value.bags).sum());
                assertEquals(2202L,result.page(0,1,new Cancellation()).rows.get(0).value.items.longValue());assertEquals(2200L,result.page(1,1,new Cancellation()).rows.get(0).value.items.longValue());
            }
        }
    }
    @Test public void halfOpenDatesAndUnknownPolicyUseExactResolvedZone()throws Exception{
        ZoneId zone=ZoneId.of("America/New_York");long start=LootQuery.resolveTime("2026-09-22T00:00:00",zone),end=LootQuery.resolveTime("2026-09-23T00:00:00",zone);
        assertEquals(Instant.parse("2026-09-22T04:00:00Z").toEpochMilli(),start);
        try{LootQuery.resolveTime("2026-11-01T01:30:00",zone);fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("explicit UTC offset"));}
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"fixture")){
            for(long time:new long[]{0,start-1,start,end-1,end})store.append("loot",drop(time,"Ice Citadel","Blue","",item(1,"A","WEAPON,T13","")));store.flush();
            ArchiveQuery<Facets,Sort> q=query(store.currentId(),View.OCCURRENCES).withBounds(new ArchiveQuery.Bounds(start,end,zone,ArchiveQuery.TimeMode.ENTRY,false));
            try(ArchiveResult<Row> result=open(store,q)){assertEquals(2,result.matches);assertEquals(end-1,rows(result).get(0).value.time.longValue());}
            try(ArchiveResult<Row> result=open(store,q.withBounds(new ArchiveQuery.Bounds(start,end,zone,ArchiveQuery.TimeMode.ENTRY,true)))){assertEquals(3,result.matches);}
            assertEquals(q,q.restore(q.toJson()));
        }
    }
    @Test public void excessGroupingCardinalityFailsRatherThanTruncates()throws Exception{
        Path scratch=temp.newFolder().toPath();try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"fixture")){
            for(int i=0;i<4;i++)store.append("loot",drop(1000+i,"Ice Citadel","White","",item(10+i,"Unique "+i,"WEAPON,UT","")));store.flush();ArchiveQuery<Facets,Sort> q=query(store.currentId(),View.ITEMS);
            try(ArchiveResult<Row> ignored=ArchiveResult.open(store,q,new LootArchiveAdapter(q,3),scratch,new Cancellation())){fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("nothing was truncated"));}
            try(java.util.stream.Stream<Path> children=Files.list(scratch)){assertEquals(0,children.count());}
        }
    }
    static ActivityJournal.Visit visit(String id,String map,long start,long duration){ActivityJournal.Visit v=new ActivityJournal.Visit();v.id=id;v.map=map;v.started=start;v.ended=v.lastSeen=start+duration;return v;}
    @Test public void rateCohortsKeepUnknownSessionsOutAndZeroLootVisitsInDespiteItemFacets()throws Exception{
        Path root=temp.newFolder().toPath();try(SessionStore unknown=new SessionStore(root,true,"unknown")){for(int i=0;i<99;i++)unknown.put("runs","u"+i,visit("u"+i,"Ice Citadel",1000,60000));unknown.flush();}
        try(SessionStore known=new SessionStore(root,true,"known")){
            known.put("runs","one",visit("one","Ice Citadel",1000,60000));known.put("runs","zero",visit("zero","Ice Citadel",61000,60000));known.append("loot",drop(2000,"Ice Citadel","White","one",item(1,"A","WEAPON,UT","")));known.flush();
            ArchiveQuery<Facets,Sort> q=query(SessionStore.ALL,View.RATES);Facets f=q.facets();f.bags.add("No such bag");f.kind=Kind.ST;q=q.withFacets(f);
            try(ArchiveResult<Row> result=open(known,q)){
                Row row=rows(result).get(0).value;assertEquals(2L,row.runs.longValue());assertEquals(.5,row.perRun,0);assertEquals(30,row.perHour,0);assertEquals(99L,row.unknownRuns.longValue());assertTrue(row.evidence.contains("Item/bag/enchant facets do not filter"));
            }
            // Visit entry bounds include the full visit's loot even if its drop arrives after until.
            try(ArchiveResult<Row> result=open(known,q.withBounds(new ArchiveQuery.Bounds(1000L,1500L,ZoneId.of("UTC"),ArchiveQuery.TimeMode.ENTRY,false)))){Row row=rows(result).get(0).value;assertEquals(1L,row.runs.longValue());assertEquals(1.0,row.perRun,0);assertEquals(60.0,row.perHour,0);}
            known.importSnapshot("synthetic-import","Run-only",1000,"runs","old",visit("old","Ice Citadel",1000,60000));
            try(ArchiveResult<Row> result=open(known,q)){Row row=rows(result).get(0).value;assertEquals(1L,row.importedRuns.longValue());assertEquals(2L,row.runs.longValue());assertEquals(30.0,row.perHour,0);}
            known.put("runs","gap",visit("gap","Ice Citadel",1000,0));known.flush();
            try(ArchiveResult<Row> result=open(known,q)){Row row=rows(result).get(0).value;assertEquals(3L,row.runs.longValue());assertNull(row.perHour);assertEquals(1/3.0,row.perRun,0);}
            known.append("loot",drop(3000,"Ice Citadel","White","missing",item(2,"Unlinked","WEAPON,UT","")));known.flush();
            try(ArchiveResult<Row> result=open(known,q)){assertNull(rows(result).get(0).value.perRun);assertNull(rows(result).get(0).value.perHour);}
        }
    }
    @Test public void fameAndUndatedCountersHaveTabSpecificSearchAndPinnedGraphInputs()throws Exception{
        Path output=temp.newFolder().toPath();try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"fixture")){
            store.append("fame",new AppHistory.FameSample(7,100,1000,"Wizard"));store.append("fame",new AppHistory.FameSample(7,150,2000,"Wizard"));
            tomato.backend.data.DungeonStatData.Snapshot counter=new tomato.backend.data.DungeonStatData.Snapshot("Ice Citadel",2,120000);counter.ongoingActivity=true;counter.hits.put(42,7);counter.loot.put(42,Collections.singletonMap(55,3));store.put("dungeon-totals","summary",Collections.singletonList(counter));store.flush();
            try(ArchiveResult<Row> fame=open(store,query(store.currentId(),View.FAME).withText("Wizard"));ArchiveResult.Lease<Row> lease=fame.lease()){
                assertEquals(1,fame.matches);assertEquals(50.0,rows(fame).get(0).value.gain,0);
                store.append("fame",new AppHistory.FameSample(7,999,3000,"Wizard"));store.flush();
                assertEquals(2,LootArchiveClient.readFame(lease,store.currentId(),new Cancellation()).getCharacterFameData().get(7).size());
                Path json=ArchiveExport.write(lease,ExportSelection.all(),ArchiveExport.Format.JSON,output,"fame",CharacterFameHistory.client(output).exportColumns(),new Cancellation());assertTrue(new String(Files.readAllBytes(json),StandardCharsets.UTF_8).contains("Wizard"));
            }
            try(ArchiveResult<Row> enemies=open(store,query(store.currentId(),View.ENEMIES).withText("42"))){assertEquals(7L,rows(enemies).get(0).value.hits.longValue());}
            try(ArchiveResult<Row> counters=open(store,query(store.currentId(),View.COUNTERS))){assertEquals(Boolean.TRUE,rows(counters).get(0).value.ongoingActivity);assertEquals(60000L,rows(counters).get(0).value.averageMillis.longValue());}
            try(ArchiveResult<Row> items=open(store,query(store.currentId(),View.SOURCES).withText("55"))){assertEquals(3L,rows(items).get(0).value.items.longValue());assertNull(rows(items).get(0).value.hits);assertNull(rows(items).get(0).value.runs);}
            try(ArchiveResult<Row> ignored=open(store,query(store.currentId(),View.COUNTERS).withBounds(new ArchiveQuery.Bounds(1000L,2000L,ZoneId.of("UTC"),ArchiveQuery.TimeMode.ENTRY,false)))){fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("undated"));}
        }
    }
    @Test public void legacyAndJournalFameRemainAvailableTogetherAndEmptyBagMeansZeroOnlyInItsSession()throws Exception{
        Path root=temp.newFolder().toPath();try(SessionStore old=new SessionStore(root,true,"unknown")){old.put("runs","v",visit("v","Ice Citadel",1000,60000));old.flush();}
        try(SessionStore known=new SessionStore(root,true,"known")){
            known.put("runs","v",visit("v","Ice Citadel",1000,60000));known.append("loot",drop(2000,"Ice Citadel","Blue","v"));
            tomato.gui.stats.session.FameSession legacy=new tomato.gui.stats.session.FameSession("Legacy");legacy.addCharacterData(7,"Wizard",Arrays.asList(new Fame(100,1000),new Fame(150,2000)));known.put("fame-snapshots","legacy",legacy);
            known.append("fame",new AppHistory.FameSample(8,20,1000,"Priest"));known.append("fame",new AppHistory.FameSample(8,50,2000,"Priest"));known.put("fame-latest","7",new AppHistory.FameSample(7,101,1000,"Wizard"));known.flush();
            try(ArchiveResult<Row> result=open(known,query(SessionStore.ALL,View.RATES))){Row row=rows(result).get(0).value;assertEquals(0L,row.items.longValue());assertEquals(0.0,row.perRun,0);assertEquals(1L,row.runs.longValue());assertEquals(1L,row.unknownRuns.longValue());}
            try(ArchiveResult<Row> fame=open(known,query(known.currentId(),View.FAME));ArchiveResult.Lease<Row> lease=fame.lease()){
                assertEquals(2,fame.matches);Map<Integer,Double> gains=new HashMap<>();for(ArchiveRow<Row> row:rows(fame))gains.put(row.value.character,row.value.gain);assertEquals(49.0,gains.get(7),0);assertEquals(30.0,gains.get(8),0);
                tomato.gui.stats.session.FameSession graph=LootArchiveClient.readFame(lease,known.currentId(),new Cancellation());assertEquals(2,graph.getCharacterFameData().size());assertEquals(101.0,graph.getCharacterFameData().get(7).get(0).getFame(),0);
            }
        }
    }
    @Test public void oversizedGroupLabelsFailBeforeBeingRetained()throws Exception{
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"fixture")){
            char[] chars=new char[513];Arrays.fill(chars,'x');store.append("loot",drop(1000,"Ice Citadel","Blue","",item(1,new String(chars),"WEAPON,UT","")));store.flush();
            try(ArchiveResult<Row> ignored=open(store,query(store.currentId(),View.ITEMS))){fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("512 characters"));}
        }
    }
    @Test public void normalizationCannotSilentlySpanPublishedAssetGenerations()throws Exception{
        java.util.concurrent.atomic.AtomicReference<Path> generation=new java.util.concurrent.atomic.AtomicReference<>(Paths.get("generation-a"));ArchiveDefinitions definitions=new ArchiveDefinitions(generation::get);
        assertEquals("Ice Citadel",definitions.canonical("Ice Citadel"));assertEquals("generation-a",definitions.description());
        generation.set(Paths.get("generation-b"));
        try{definitions.canonical("Ice Citadel");fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("Asset definitions changed"));}
    }
    @Test public void groupedUnknownTimesStayNullAndCohortDamageIsNotInventedZero()throws Exception{
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"fixture")){
            store.append("loot",drop(0,"Ice Citadel","White","v",item(1,"A","WEAPON,UT","")));ActivityJournal.Visit visit=visit("v","Ice Citadel",1000,60000);visit.damageTracked=true;visit.totalDamage=456;store.put("runs","v",visit);store.flush();
            for(View view:Arrays.asList(View.BAGS,View.DUNGEONS))try(ArchiveResult<Row> grouped=open(store,query(store.currentId(),view))){assertEquals(1,grouped.matches);assertNull(rows(grouped).get(0).value.time);}
            try(ArchiveResult<Row> rates=open(store,query(store.currentId(),View.RATES))){assertEquals(456L,rows(rates).get(0).value.damage.longValue());}
            visit.damageTracked=false;store.put("runs","v",visit);store.flush();try(ArchiveResult<Row> rates=open(store,query(store.currentId(),View.RATES))){assertNull(rows(rates).get(0).value.damage);}
        }
    }
    /**
     * Polish B1: a legacy saved bag without a bag name (null, or blank as LootFacts) failed By Bag (a null group key) and listed a
     * "null" bag facet. By Bag groups such bags under one "Unknown bag (name not saved)" row, never merged into a named bag; the bag
     * facet lists named bags only; search never matches the text "null"; every loot view reads; a nameless bag is never white.
     */
    @Test public void savedBagsWithoutANameGroupUnderUnknownBagAndNeverListANullFacet()throws Exception{
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"nameless")){
            store.append("loot",new LootDashboard.Drop(null,"Lost Halls",null,1000,Arrays.asList(item(1,"Blade","WEAPON,UT",""),item(2,"Robe","ARMOR,ST","")),"v1"));
            store.append("loot",drop(1000,"Lost Halls"," ","v2",item(3,"Band","RING","")));   // blank: no name either
            store.append("loot",drop(1000,"Lost Halls","White","v3",item(4,"Sword","WEAPON,T12","")));
            store.append("loot",drop(1000,"Lost Halls","Orange","v4",item(5,"Staff","WEAPON,T12","")));
            store.flush();
            String scope=store.currentId();
            for(View view:View.values())if(view.loot())try(ArchiveResult<Row> result=open(store,query(scope,view))){rows(result);}
            // One observation time for every bag, so the default newest-first order ties and the rows' own order shows.
            try(ArchiveResult<Row> result=open(store,query(scope,View.BAGS))){
                List<String> names=new ArrayList<>();for(ArchiveRow<Row> row:rows(result))names.add(row.value.name);
                assertEquals("Named bags by name, then the one Unknown bag row",Arrays.asList("Orange","White","Unknown bag (name not saved)"),names);
                Row unknown=rows(result).get(2).value;
                assertEquals((Long)2L,unknown.bags);assertEquals((Long)3L,unknown.items);assertEquals((Long)3L,unknown.count);
                assertEquals("Its Bag cell is empty: the name was not saved","",unknown.bag);assertEquals("bag-type",unknown.type);
                assertTrue(unknown.evidence,unknown.evidence.contains("never counted as white bags"));
                Row white=rows(result).get(1).value;assertEquals((Long)1L,white.bags);assertEquals((Long)1L,white.items);
                Set<String> facets=new TreeSet<>();for(String key:result.page(0,10,new Cancellation()).counts.keySet())if(key.startsWith("facet.bag."))facets.add(key);
                assertEquals("Named bags only; never \"null\" or a blank name",new TreeSet<>(Arrays.asList("facet.bag.Orange","facet.bag.White")),facets);
            }
            try(ArchiveResult<Row> result=open(store,query(scope,View.WHITES))){
                List<String> names=new ArrayList<>();for(ArchiveRow<Row> row:rows(result))names.add(row.value.name);
                assertEquals("A bag without a name is never a white bag",Collections.singletonList("Sword"),names);
            }
            try(ArchiveResult<Row> result=open(store,query(scope,View.ITEMS).withText("null"))){assertEquals("Search never matches a missing name as \"null\"",0,rows(result).size());}
            try(ArchiveResult<Row> result=open(store,query(scope,View.ITEMS).withText("unknown bag"))){
                Set<String> names=new TreeSet<>();for(ArchiveRow<Row> row:rows(result))names.add(row.value.name);
                assertEquals("Search finds nameless bags by their By Bag label",new TreeSet<>(Arrays.asList("Blade","Robe","Band")),names);
            }
            ArchiveQuery<Facets,Sort> whiteOnly=query(scope,View.BAGS);Facets f=whiteOnly.facets();f.bags.add("White");
            try(ArchiveResult<Row> result=open(store,whiteOnly.withFacets(f))){
                assertEquals("A bag facet selects named bags only",1,rows(result).size());assertEquals("White",rows(result).get(0).value.name);
            }
        }
    }
    /**
     * P6b Task 9 (R3 B3a): By Bag's Unknown bag row is last under every explicit sort. Its empty Bag cell sorted first under Bag
     * ascending and its label alphabetically under Name, and the DESCENDING reversal flipped any comparator; ArchiveAdapter.sortsLast
     * is the first sort key, outside the reversal. The newest and largest group here, so no other key puts it last by accident.
     */
    @Test public void theUnknownBagRowIsLastUnderBagAndNameInBothDirections()throws Exception{
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"unknown-last")){
            store.append("loot",drop(1000,"Lost Halls","Orange","v1",item(1,"Staff","WEAPON,T12","")));
            store.append("loot",drop(2000,"Lost Halls","Blue","v2",item(2,"Band","RING",""),item(3,"Charm","RING","")));
            store.append("loot",drop(3000,"Lost Halls","White","v3",item(4,"Sword","WEAPON,T12",""),item(5,"Bow","WEAPON,T12",""),item(6,"Wand","WEAPON,T12","")));
            store.append("loot",new LootDashboard.Drop(null,"Lost Halls",null,4000,Arrays.asList(item(7,"Blade","WEAPON,UT",""),item(8,"Robe","ARMOR,ST",""),item(9,"Ring","RING",""),item(10,"Helm","ARMOR","")),"v4"));
            store.flush();
            String scope=store.currentId(),unknown="Unknown bag (name not saved)";
            Map<String,List<String>> expected=new LinkedHashMap<>();
            expected.put("BAG ASCENDING",Arrays.asList("Blue","Orange","White",unknown));
            expected.put("BAG DESCENDING",Arrays.asList("White","Orange","Blue",unknown));
            expected.put("NAME ASCENDING",Arrays.asList("Blue","Orange","White",unknown));
            expected.put("NAME DESCENDING",Arrays.asList("White","Orange","Blue",unknown));
            expected.put("TIME DESCENDING",Arrays.asList("White","Blue","Orange",unknown));
            expected.put("ITEMS DESCENDING",Arrays.asList("White","Blue","Orange",unknown));
            expected.put("ITEMS ASCENDING",Arrays.asList("Orange","Blue","White",unknown));
            for(Map.Entry<String,List<String>> order:expected.entrySet()){
                String[] parts=order.getKey().split(" ");
                ArchiveQuery<Facets,Sort> q=query(scope,View.BAGS).withOrder(Collections.singletonList(new ArchiveQuery.Order<>(Sort.valueOf(parts[0]),ArchiveQuery.Direction.valueOf(parts[1]))));
                try(ArchiveResult<Row> result=open(store,q)){
                    List<String> names=new ArrayList<>();for(ArchiveRow<Row> row:rows(result))names.add(row.value.name);
                    assertEquals(order.getKey(),order.getValue(),names);
                    List<String> paged=new ArrayList<>();for(ArchiveRow<Row> row:result.page(0,2,new Cancellation()).rows)paged.add(row.value.name);
                    assertEquals(order.getKey()+": pages follow the same order",order.getValue().subList(0,2),paged);
                }
            }
            try(ArchiveResult<Row> result=open(store,query(scope,View.BAGS))){
                Row last=rows(result).get(3).value;assertEquals(unknown,last.name);assertEquals("The saved row keeps its empty Bag value","",last.bag);
            }
            try(ArchiveResult<Row> result=open(store,query(scope,View.OCCURRENCES).withOrder(Collections.singletonList(new ArchiveQuery.Order<>(Sort.BAG,ArchiveQuery.Direction.ASCENDING))))){
                List<Integer> ids=new ArrayList<>();for(ArchiveRow<Row> row:rows(result))ids.add(row.value.itemId);
                assertEquals("Only the By Bag row sorts last: other views keep the query's order (a missing bag name sorts first by value)",Arrays.asList(7,8,9,10,2,3,1,4,5,6),ids);
            }
        }
    }
    /**
     * P6b Task 9 (R3 B2): a bag saved without a map ("Unknown", capture's word) reads "Unknown area" as the Dungeon loot profile row's
     * name; the row's dungeon, the drill key, and the dungeon facet stay "Unknown", so saved facets and drills still select it.
     */
    @Test public void unknownAreaIsADisplayLabelAndKeysStayUnknown()throws Exception{
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"unknown-area")){
            store.append("loot",drop(2000,null,"White","v1",item(1,"Blade","WEAPON,UT","")));
            store.append("loot",drop(3000,"Ice Citadel","Orange","v2",item(2,"Staff","WEAPON,T12","")));
            store.flush();
            String scope=store.currentId();
            try(ArchiveResult<Row> result=open(store,query(scope,View.RATES))){
                Map<String,Row> byKey=new TreeMap<>();for(ArchiveRow<Row> row:rows(result))byKey.put(row.value.dungeon,row.value);
                assertEquals("The drill keys are the saved names",new TreeSet<>(Arrays.asList("Ice Citadel","Unknown")),byKey.keySet());
                assertEquals("Unknown area",byKey.get("Unknown").name);
                assertEquals("A known area keeps its name","Ice Citadel",byKey.get("Ice Citadel").name);
            }
            try(ArchiveResult<Row> result=open(store,query(scope,View.ITEMS))){
                Set<String> facets=new TreeSet<>();for(String key:result.page(0,10,new Cancellation()).counts.keySet())if(key.startsWith("facet.dungeon."))facets.add(key);
                assertEquals("The facet keys stay the saved names",new TreeSet<>(Arrays.asList("facet.dungeon.Ice Citadel","facet.dungeon.Unknown")),facets);
            }
            ArchiveQuery<Facets,Sort> drill=query(scope,View.ITEMS);Facets f=drill.facets();f.dungeons.add("Unknown");
            try(ArchiveResult<Row> result=open(store,drill.withFacets(f))){
                assertEquals("A saved \"Unknown\" facet (a drill from the row) still selects the bag",1,rows(result).size());
                assertEquals("Blade",rows(result).get(0).value.name);assertEquals("Unknown",rows(result).get(0).value.dungeon);
            }
            ArchiveQuery<Facets,Sort> rates=query(scope,View.RATES);Facets r=rates.facets();r.dungeons.add("Unknown");
            try(ArchiveResult<Row> result=open(store,rates.withFacets(r))){
                assertEquals(1,rows(result).size());assertEquals("Unknown area",rows(result).get(0).value.name);
            }
            try(ArchiveResult<Row> result=open(store,query(scope,View.RATES).withText("unknown area"))){
                assertEquals("Rate search finds the row by the name it shows",1,rows(result).size());assertEquals("Unknown",rows(result).get(0).value.dungeon);
            }
            try(ArchiveResult<Row> result=open(store,query(scope,View.RATES).withText("Unknown"))){assertEquals("…and by its saved name",1,rows(result).size());}
        }
    }
}
