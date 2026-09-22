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
    static ArchiveQuery<Facets,Sort> query(String scope,View view){Facets f=new Facets();f.view=view;return LootQuery.initial(false).withScope(scope).withFacets(f);}
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
                Path json=ArchiveExport.write(lease,ExportSelection.all(),ArchiveExport.Format.JSON,output,"loot",new LootArchiveClient(output,false).exportColumns(),new Cancellation());
                JsonObject document=JsonParser.parseString(new String(Files.readAllBytes(json),StandardCharsets.UTF_8)).getAsJsonObject();
                assertEquals(2,document.getAsJsonArray("rows").size());assertEquals(result.revision,document.getAsJsonObject("manifest").get("revision").getAsString());assertEquals(2,document.getAsJsonObject("manifest").get("exportCount").getAsInt());
                Path csv=ArchiveExport.write(lease,ExportSelection.all(),ArchiveExport.Format.CSV,output,"loot",new LootArchiveClient(output,false).exportColumns(),new Cancellation());
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
                Path json=ArchiveExport.write(lease,ExportSelection.all(),ArchiveExport.Format.JSON,output,"fame",new LootArchiveClient(output,true).exportColumns(),new Cancellation());assertTrue(new String(Files.readAllBytes(json),StandardCharsets.UTF_8).contains("Wizard"));
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
}
