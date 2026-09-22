package tomato.history.archive;

import com.google.gson.*;
import tomato.history.SessionStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static tomato.history.archive.ArchiveFixtures.*;

public class ArchivePipelineTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void twelveThousandRecordsFilterAndSortBeforePagingWithExactExportCounts()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath(),output=temp.newFolder().toPath();String id=session(root,12005);
        try(SessionStore store=new SessionStore(root,false,"test")){
            ArchiveQuery<Facets,Sort> query=query(id).withFacets(new Facets(11000)).withOrder(Collections.singletonList(new ArchiveQuery.Order<>(Sort.VALUE,ArchiveQuery.Direction.DESCENDING)));
            try(ArchiveResult<Event> result=ArchiveResult.open(store,query,adapter(),scratch,new Cancellation())){
                assertEquals(12005,result.scanned);assertEquals(1005,result.matches);
                ArchivePage<Event> first=result.page(0,1000,new Cancellation()),second=result.page(1,1000,new Cancellation());
                assertEquals(12004,first.rows.get(0).value.value);assertEquals(11005,first.rows.get(999).value.value);
                assertEquals(5,second.rows.size());assertEquals(11000,second.rows.get(4).value.value);assertTrue(first.more());assertFalse(second.more());
                assertEquals(id,first.rows.get(0).ref.session);assertEquals(result.revision,second.revision);
                try(ArchiveResult.Lease<Event> lease=result.lease()){
                    Path file=ArchiveExport.write(lease,ExportSelection.all(),ArchiveExport.Format.JSON,output,"matches",Collections.emptyList(),new Cancellation());
                    JsonObject json=JsonParser.parseString(new String(Files.readAllBytes(file),StandardCharsets.UTF_8)).getAsJsonObject();
                    assertEquals(1005,json.getAsJsonArray("rows").size());assertEquals(1005,json.getAsJsonObject("manifest").get("exportCount").getAsLong());
                    assertEquals(result.revision,json.getAsJsonObject("manifest").get("revision").getAsString());
                    assertFalse(json.toString().contains(scratch.toString().replace("\\","\\\\")));
                }
            }
            assertEquals(0,children(scratch));
        }
    }
    @Test public void externalMergeIsBoundedAndTiesHaveStableOriginAcrossSessions()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();session(root,12005);session(root,12005);
        try(SessionStore store=new SessionStore(root,false,"test");ArchiveResult<Event> result=ArchiveResult.open(store,query(SessionStore.ALL),adapter(),scratch,new Cancellation())){
            assertEquals(24010,result.matches);assertTrue(result.maximumChunkRows<=ArchiveResult.CHUNK_ROWS);
            assertEquals(ArchiveResult.MERGE_FAN_IN,result.maximumMergeReaders);
            List<ArchiveRow.Ref> first=new ArrayList<>();int[] last={-1};ArchiveRow.Ref[] previous={null};
            result.stream(ExportSelection.all(),row->{assertTrue(row.value.value>=last[0]);
                if(row.value.value==last[0])assertTrue(previous[0].compareTo(row.ref)<0);
                if(row.value.value<2)first.add(row.ref);last[0]=row.value.value;previous[0]=row.ref;},new Cancellation());
            assertEquals(4,first.size());assertEquals(4,new HashSet<>(first).size());
            assertEquals(result.page(0,2,new Cancellation()).rows.get(1).ref,result.page(0,2,new Cancellation()).rows.get(1).ref);
        }
        assertEquals(0,children(scratch));
    }
    @Test public void pinKeepsCheckpointJournalAndCrossScopeAnnotationsWhileSourcesChange()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String old=session(root,2);
        try(SessionStore store=new SessionStore(root,true,"test")){
            store.put("runs","visit",new Event(10,7,"one","before"));store.put("chat-stars","star",new Event(10,1,"star","before"));store.flush();
            List<ReadSnapshot.Source> sources=Arrays.asList(new ReadSnapshot.Source(old,"chat"),new ReadSnapshot.Source(store.currentId(),"runs"),new ReadSnapshot.Source(SessionStore.ALL,"chat-stars"));
            try(ReadSnapshot pin=store.capture(sources,scratch,new Cancellation())){
                store.put("runs","visit",new Event(20,99,"two","after"));store.put("chat-stars","star",new Event(20,0,"unstar","after"));store.flush();
                Files.write(root.resolve(old).resolve("chat.jsonl"),(SessionStore.JSON.toJson(new Event(3,3,"odd","later"))+"\n").getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
                List<ArchiveRow<Event>> visits=new ArrayList<>(),stars=new ArrayList<>(),messages=new ArrayList<>();
                pin.read("runs",Event.class,visits::add,new Cancellation());pin.read("chat-stars",Event.class,stars::add,new Cancellation());pin.read("chat",Event.class,messages::add,new Cancellation());
                assertEquals(7,visits.get(0).value.value);assertEquals(1,stars.get(0).value.value);assertEquals(2,messages.size());
                List<ArchiveRow<Event>> again=new ArrayList<>();pin.read("runs",Event.class,again::add,new Cancellation());assertEquals(visits.get(0).ref,again.get(0).ref);
                assertEquals(99,store.read(store.currentId(),"runs",Event.class).get(0).value);
            }
        }
    }
    @Test public void exportLeaseSurvivesOwnerCloseAndCheckpointReplacementDuringStreaming()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath(),output=temp.newFolder().toPath();
        try(SessionStore store=new SessionStore(root,true,"test")){
            store.put("runs","a",new Event(1,1,"a","before"));store.put("runs","b",new Event(2,2,"b","before"));store.flush();
            ArchiveAdapter<Event,Facets,Sort> runs=ArchiveAdapter.records("runs",Event.class,"visits",e->e.time,(e,q)->true,s->Comparator.comparingInt(e->e.value));
            ArchiveResult<Event> result=ArchiveResult.open(store,query(store.currentId()),runs,scratch,new Cancellation());
            try(ArchiveResult.Lease<Event> lease=result.lease()){
                AtomicInteger written=new AtomicInteger();List<ArchiveExport.Column<Event>> columns=Collections.singletonList(new ArchiveExport.Column<>("Value",event->{
                    if(written.getAndIncrement()==0){result.close();store.put("runs","a",new Event(9,99,"a","after"));try{store.flush();}catch(Exception failure){throw new AssertionError(failure);}}
                    return event.value;
                }));
                Path file=ArchiveExport.write(lease,ExportSelection.all(),ArchiveExport.Format.CSV,output,"run",columns,new Cancellation());
                String csv=new String(Files.readAllBytes(file),StandardCharsets.UTF_8);assertTrue(csv.contains(",\"1\"\r\n"));assertTrue(csv.contains(",\"2\"\r\n"));assertEquals(2,written.get());
                assertTrue(children(scratch)>0);
            }
            assertEquals(0,children(scratch));
        }
    }
    @Test public void selectedPageAndAllExportsSurviveFilenameCollisionsAndRejectForeignSelection()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath(),output=temp.newFolder().toPath();String id=session(root,30);
        try(SessionStore store=new SessionStore(root,false,"test");ArchiveResult<Event> result=ArchiveResult.open(store,query(id),adapter(),scratch,new Cancellation());ArchiveResult.Lease<Event> lease=result.lease()){
            ArchiveRow.Ref selected=result.page(2,10,new Cancellation()).rows.get(3).ref;
            Path first=ArchiveExport.write(lease,ExportSelection.selected(Collections.singleton(selected)),ArchiveExport.Format.JSON,output,"same",Collections.emptyList(),new Cancellation());
            Path second=ArchiveExport.write(lease,ExportSelection.page(2,10),ArchiveExport.Format.JSON,output,"same",Collections.emptyList(),new Cancellation());
            assertNotEquals(first,second);assertEquals(1,JsonParser.parseString(new String(Files.readAllBytes(first),StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("rows").size());
            assertEquals(10,JsonParser.parseString(new String(Files.readAllBytes(second),StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("rows").size());
            try{ArchiveExport.write(lease,ExportSelection.selected(Collections.singleton(new ArchiveRow.Ref(id,"chat","missing",""))),ArchiveExport.Format.JSON,output,"missing",Collections.emptyList(),new Cancellation());fail();}
            catch(java.io.IOException expected){assertEquals(2,children(output));}
        }
    }
    @Test public void cancellationAndFailedCsvOutputLeaveNoPartialFiles()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath(),output=temp.newFolder().toPath();String id=session(root,2500);
        Cancellation cancelled=new Cancellation();AtomicInteger seen=new AtomicInteger();
        ArchiveAdapter<Event,Facets,Sort> stopping=ArchiveAdapter.records("chat",Event.class,"messages",e->e.time,(e,q)->{if(seen.incrementAndGet()==1500)cancelled.cancel();return true;},s->Comparator.comparingInt(e->e.value));
        try(SessionStore store=new SessionStore(root,false,"test")){
            try{ArchiveResult.open(store,query(id),stopping,scratch,cancelled);fail();}catch(CancellationException expected){assertEquals(0,children(scratch));}
            try(ArchiveResult<Event> result=ArchiveResult.open(store,query(id),adapter(),scratch,new Cancellation());ArchiveResult.Lease<Event> lease=result.lease()){
                Path blocked=output.resolve("not-a-directory");Files.write(blocked,new byte[]{7});
                try{ArchiveExport.write(lease,ExportSelection.all(),ArchiveExport.Format.JSON,blocked,"blocked",Collections.emptyList(),new Cancellation());fail();}
                catch(java.io.IOException expected){assertArrayEquals(new byte[]{7},Files.readAllBytes(blocked));}Files.delete(blocked);
                List<ArchiveExport.Column<Event>> failed=Collections.singletonList(new ArchiveExport.Column<>("Value",event->{if(event.value==11)throw new IllegalStateException("synthetic writer failure");return event.value;}));
                try{ArchiveExport.write(lease,ExportSelection.all(),ArchiveExport.Format.CSV,output,"failed",failed,new Cancellation());fail();}
                catch(IllegalStateException expected){assertEquals(0,children(output));}
                Cancellation stop=new Cancellation();List<ArchiveExport.Column<Event>> cancelling=Collections.singletonList(new ArchiveExport.Column<>("Value",event->{if(event.value==20)stop.cancel();return event.value;}));
                try{ArchiveExport.write(lease,ExportSelection.all(),ArchiveExport.Format.CSV,output,"cancel",cancelling,stop);fail();}
                catch(CancellationException expected){assertEquals(0,children(output));}
            }
        }
        assertEquals(0,children(scratch));
    }
    @Test public void halfOpenBoundsUnknownAndOverlapAreExplicitAndFacetsAreDetached()throws Exception{
        Facets facets=new Facets(2,"even");ArchiveQuery<Facets,Sort> query=ArchiveQuery.of(SessionStore.ALL,facets,Facets.class,Sort.VALUE);
        facets.groups.clear();query.facets().groups.clear();assertEquals(Collections.singleton("even"),query.facets().groups);
        ArchiveQuery.Bounds entry=new ArchiveQuery.Bounds(10L,20L,ZoneId.of("UTC"),ArchiveQuery.TimeMode.ENTRY,false);
        assertTrue(entry.contains(10L,10L));assertFalse(entry.contains(20L,20L));assertFalse(entry.contains(null,null));
        ArchiveQuery.Bounds overlap=new ArchiveQuery.Bounds(10L,20L,ZoneId.of("UTC"),ArchiveQuery.TimeMode.OVERLAP,true);
        assertTrue(overlap.contains(5L,11L));assertFalse(overlap.contains(5L,10L));assertTrue(overlap.contains(null,null));
        assertEquals(query,query.restore(query.toJson()));JsonObject unknown=query.toJson();unknown.getAsJsonObject("facets").addProperty("futureFacet",1);
        try{query.restore(unknown);fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("migration"));}
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String id=session(root,30);
        try(SessionStore store=new SessionStore(root,false,"test");ArchiveResult<Event> result=ArchiveResult.open(store,query(id).withBounds(entry),adapter(),scratch,new Cancellation())){
            assertEquals(10,result.matches);assertEquals(10,result.page(0,100,new Cancellation()).rows.get(0).value.value);
        }
    }
    @Test public void corruptRecordFailsTheRevisionAndCrashTailIsDeclared()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String id=session(root,3);Path journal=root.resolve(id).resolve("chat.jsonl");
        Files.write(journal,"{unfinished".getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
        try(SessionStore store=new SessionStore(root,false,"test")){
            try(ArchiveResult<Event> result=ArchiveResult.open(store,query(id),adapter(),scratch,new Cancellation())){assertEquals(3,result.matches);assertEquals(1,result.page(0,100,new Cancellation()).issues.size());}
            Files.write(journal,"\n{}\n".getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
            try{ArchiveResult.open(store,query(id),adapter(),scratch,new Cancellation());fail();}catch(java.io.IOException expected){assertEquals(0,children(scratch));}
        }
    }
    @Test public void heavyProjectionPagesFailExplicitlyInsteadOfGrowingWithoutBound()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String id=session(root,0);
        char[] characters=new char[450*1024];Arrays.fill(characters,'x');String text=new String(characters);
        try(java.io.BufferedWriter writer=Files.newBufferedWriter(root.resolve(id).resolve("chat.jsonl"),StandardCharsets.UTF_8)){
            for(int i=0;i<24;i++){writer.write(SessionStore.JSON.toJson(new Event(i,i,"large",text)));writer.newLine();}
        }
        try(SessionStore store=new SessionStore(root,false,"test");ArchiveResult<Event> result=ArchiveResult.open(store,query(id),adapter(),scratch,new Cancellation())){
            assertEquals(24,result.matches);assertTrue(result.maximumChunkRows<ArchiveResult.CHUNK_ROWS);
            try{result.page(0,24,new Cancellation());fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("8 MiB"));}
            assertEquals(10,result.page(0,10,new Cancellation()).rows.size());AtomicInteger streamed=new AtomicInteger();
            result.stream(ExportSelection.all(),row->streamed.incrementAndGet(),new Cancellation());assertEquals(24,streamed.get());
        }
    }
    @Test public void projectionChildrenKeepDistinctOriginsAndResolveOutsideFirstPage()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String id=session(root,600);
        ArchiveAdapter<Event,Facets,Sort> projected=new ArchiveAdapter<Event,Facets,Sort>(){
            public Class<Event> rowType(){return Event.class;}public String unit(){return "occurrences";}
            public List<ReadSnapshot.Source> sources(SessionStore store,ArchiveQuery<Facets,Sort> q){return Collections.singletonList(new ReadSnapshot.Source(q.resolvedScope(store),"chat"));}
            public void scan(ReadSnapshot pin,ArchiveQuery<Facets,Sort> q,Sink<Event> rows,Cancellation token)throws java.io.IOException{
                pin.read("chat",Event.class,row->{for(int i=0;i<2;i++)rows.accept(row.child("item-"+i,row.value));},token);
            }
            public boolean matches(ArchiveRow<Event> row,ArchiveQuery<Facets,Sort> q){return true;}public Long time(ArchiveRow<Event> row){return row.value.time;}
            public Comparator<Event> comparator(Sort field){return Comparator.comparingInt(e->e.value);}
        };
        try(SessionStore store=new SessionStore(root,false,"test");ArchiveResult<Event> result=ArchiveResult.open(store,query(id),projected,scratch,new Cancellation())){
            assertEquals(1200,result.matches);ArchivePage<Event> page=result.page(1,1000,new Cancellation());
            assertNotEquals(page.rows.get(0).ref,page.rows.get(1).ref);assertEquals(1,result.pageOf(page.rows.get(99).ref,1000,new Cancellation()));
            assertEquals(-1,result.pageOf(new ArchiveRow.Ref(id,"chat","missing",""),1000,new Cancellation()));
        }
    }
    @Test public void groupedCountersAndDetailsUseTheSamePinAsTheRenderedRows()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String id=session(root,100);
        ArchiveAdapter<Event,Facets,Sort> grouped=new ArchiveAdapter<Event,Facets,Sort>(){
            long matched;
            public Class<Event> rowType(){return Event.class;}public String unit(){return "groups";}
            public List<ReadSnapshot.Source> sources(SessionStore store,ArchiveQuery<Facets,Sort> q){return Collections.singletonList(new ReadSnapshot.Source(q.resolvedScope(store),"chat"));}
            public void scan(ReadSnapshot pin,ArchiveQuery<Facets,Sort> q,Sink<Event> rows,Cancellation token)throws java.io.IOException{
                Map<String,Integer> groups=new TreeMap<>();pin.read("chat",Event.class,row->{if(q.bounds().contains(row.value.time,row.value.time)){matched++;groups.merge(row.value.group,1,Integer::sum);}},token);
                for(Map.Entry<String,Integer> group:groups.entrySet())rows.accept(new ArchiveRow<>(new ArchiveRow.Ref(id,"chat","group",group.getKey()),new Event(0,group.getValue(),group.getKey(),"summary")));
            }
            public boolean matches(ArchiveRow<Event> row,ArchiveQuery<Facets,Sort> q){return true;}
            public boolean inBounds(ArchiveRow<Event> row,ArchiveQuery<Facets,Sort> q){return true;}
            public Long time(ArchiveRow<Event> row){return null;}public Comparator<Event> comparator(Sort field){return Comparator.comparing(e->e.group);}
            public Map<String,Count> counts(){return Collections.singletonMap("events",new Count(matched,"messages","matching source events"));}
        };
        ArchiveQuery<Facets,Sort> q=query(id).withBounds(new ArchiveQuery.Bounds(10L,30L,ZoneId.of("UTC"),ArchiveQuery.TimeMode.ENTRY,false));
        try(SessionStore store=new SessionStore(root,false,"test");ArchiveResult<Event> result=ArchiveResult.open(store,q,grouped,scratch,new Cancellation())){
            ArchivePage<Event> page=result.page(0,100,new Cancellation());assertEquals(2,page.matches);assertEquals(20,page.counts.get("events").value);
            Files.delete(root.resolve(id).resolve("chat.jsonl"));
            try(ArchiveResult.Lease<Event> lease=page.lease()){
                AtomicInteger source=new AtomicInteger();lease.readSource(id,"chat",Event.class,row->source.incrementAndGet(),new Cancellation());assertEquals(100,source.get());
                try{lease.readSource(id,"loot",Event.class,row->fail("Undeclared source"),new Cancellation());fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("not included"));}
                assertEquals(20,lease.manifest().getAsJsonObject("counts").getAsJsonObject("events").get("value").getAsLong());
            }
        }
    }
    @Test public void selectedExportAgainstAnEmptyResultCannotPublishAnIncorrectCount()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath(),output=temp.newFolder().toPath();String id=session(root,0);
        try(SessionStore store=new SessionStore(root,false,"test");ArchiveResult<Event> result=ArchiveResult.open(store,query(id),adapter(),scratch,new Cancellation());ArchiveResult.Lease<Event> lease=result.lease()){
            try{ArchiveExport.write(lease,ExportSelection.selected(Collections.singleton(new ArchiveRow.Ref(id,"chat","missing",""))),ArchiveExport.Format.JSON,output,"empty",Collections.emptyList(),new Cancellation());fail();}
            catch(java.io.IOException expected){assertEquals(0,children(output));}
        }
        ArchiveRow.Ref rootRef=new ArchiveRow.Ref("session","module","locator","");
        assertNotEquals(rootRef.child("a/b"),rootRef.child("a").child("b"));
    }
}
