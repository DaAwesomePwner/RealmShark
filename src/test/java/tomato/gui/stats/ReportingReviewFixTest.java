package tomato.gui.stats;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.stats.LootQuery.*;
import tomato.gui.stats.session.*;
import tomato.history.*;
import tomato.history.archive.*;
import static org.junit.Assert.*;
import static tomato.gui.history.SessionPanelTest.named;
import static tomato.gui.stats.LootArchiveQueryTest.*;

/** Review blockers exercised through real pinned projections, with no native viewer or capture. */
public class ReportingReviewFixTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private ArchiveResult<Row> open(SessionStore store,ArchiveQuery<Facets,Sort> query)throws Exception{
        return ArchiveResult.open(store,query,query.facets().view.loot()?new LootArchiveAdapter(query):new StatisticsArchiveAdapter(query),temp.newFolder().toPath(),new Cancellation());
    }
    private static List<ArchiveRow<Row>> rows(ArchiveResult<Row> result)throws Exception{List<ArchiveRow<Row>> rows=new ArrayList<>();result.stream(ExportSelection.all(),rows::add,new Cancellation());return rows;}
    @Test public void literalBracketAndRawDropperSearchAgreeAcrossLiveBagsItemsAndArchive()throws Exception{
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"fixture")){
            LootDashboard.Item plain=item(42,"PlainSword","WEAPON,UT","");
            LootDashboard.Drop first=new LootDashboard.Drop("White","Ice Citadel","Limon",1000,Collections.singletonList(plain));
            LootDashboard live=edt(()->{LootDashboard d=new LootDashboard();d.accept(first);named(d,"loot-search",JTextField.class).setText("[");assertArrayEquals(new int[]{0,0,0},d.matchingTotals());return d;});
            store.append("loot",first);store.flush();try(ArchiveResult<Row> result=open(store,query(store.currentId(),View.OCCURRENCES).withText("["))){assertEquals(0,result.matches);}
            LootDashboard.Item bracket=item(42,"Relic [test]","WEAPON,UT","");
            LootDashboard.Drop second=new LootDashboard.Drop("White","Ice Citadel","Other",2000,Arrays.asList(bracket,bracket));
            LootDashboard.Drop third=new LootDashboard.Drop("White","Ice Citadel","Boss [test]",3000,Collections.singletonList(plain));
            edt(()->{live.acceptAll(Arrays.asList(second,third));return null;});store.append("loot",second);store.append("loot",third);store.flush();
            String[] searches={"[","Relic","Boss [","42","Common / Unenchanted","0 slots"};int[][] expected={{2,3},{1,2},{1,1},{3,4},{3,4},{0,0}};
            for(int i=0;i<searches.length;i++){
                String search=searches[i];int[] actual=edt(()->{named(live,"loot-search",JTextField.class).setText(search);return live.matchingTotals();});
                assertEquals(search,expected[i][0],actual[0]);assertEquals(search,expected[i][1],actual[1]);
                try(ArchiveResult<Row> result=open(store,query(store.currentId(),View.OCCURRENCES).withText(search))){
                    assertEquals(search,actual[1],result.matches);assertEquals(search,actual[0],result.page(0,100,new Cancellation()).counts.get("matching bags").value);
                }
            }
        }
    }
    @Test public void undatedHundredAndDatedOneFiftyNeverProduceEpochEndpointsOrFiftyGain()throws Exception{
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"fixture")){
            store.append("fame",new AppHistory.FameSample(7,100,0,"Wizard"));store.append("fame",new AppHistory.FameSample(7,150,1000,"Wizard"));store.flush();
            try(ArchiveResult<Row> result=open(store,query(store.currentId(),View.FAME));ArchiveResult.Lease<Row> lease=result.lease()){
                Row row=rows(result).get(0).value;assertEquals(1000L,row.time.longValue());assertEquals(150.0,row.firstFame,0);assertEquals(150.0,row.lastFame,0);
                assertNull(row.gain);assertNull(row.millis);assertEquals(2L,row.count.longValue());assertTrue(row.evidence.contains("1 undated observations"));
                assertEquals(1,result.page(0,100,new Cancellation()).counts.get("undated fame observations").value);
                Path output=temp.newFolder().toPath();Path json=ArchiveExport.write(lease,ExportSelection.all(),ArchiveExport.Format.JSON,output,"fame",new LootArchiveClient(output,true).exportColumns(),new Cancellation());
                JsonObject document=JsonParser.parseString(new String(Files.readAllBytes(json),StandardCharsets.UTF_8)).getAsJsonObject();JsonObject value=document.getAsJsonArray("rows").get(0).getAsJsonObject().getAsJsonObject("value");
                assertTrue(!value.has("gain")||value.get("gain").isJsonNull());assertTrue(!value.has("millis")||value.get("millis").isJsonNull());assertEquals(1000,value.get("time").getAsLong());
                Path csv=ArchiveExport.write(lease,ExportSelection.all(),ArchiveExport.Format.CSV,output,"fame",new LootArchiveClient(output,true).exportColumns(),new Cancellation());
                List<String> lines=Files.readAllLines(csv,StandardCharsets.UTF_8);List<String> header=csvLine(lines.get(1)),cells=csvLine(lines.get(2));
                assertEquals("",cells.get(header.indexOf("Fame change")));assertEquals("",cells.get(header.indexOf("Observed / finalized milliseconds")));assertEquals("1000",cells.get(header.indexOf("Timestamp (epoch ms)")));
                FameSession graph=LootArchiveClient.readFame(lease,store.currentId(),new Cancellation());assertEquals(2,graph.getCharacterData(7).size());assertEquals(Collections.singletonList(new Fame(150,1000)),graph.datedSamples(7));
                assertNull(graph.chronology(7).gain());assertNull(graph.chronology(7).elapsed());assertEquals(1,graph.chronology(7).undatedCount());
            }
            try(ArchiveResult<Row> sessions=open(store,query(store.currentId(),View.SESSIONS))){Row session=rows(sessions).get(0).value;assertNull(session.gain);assertTrue(session.evidence.contains("1 undated fame observations"));}
        }
    }
    @Test public void undatedOnlyFlatDatedAndSingleDatedObservationsRemainDistinct()throws Exception{
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"fixture")){
            store.append("fame",new AppHistory.FameSample(8,40,0,"Priest"));store.append("fame",new AppHistory.FameSample(8,90,-5,"Priest"));
            store.append("fame",new AppHistory.FameSample(9,100,1000,"Wizard"));store.append("fame",new AppHistory.FameSample(9,100,2000,"Wizard"));
            store.append("fame",new AppHistory.FameSample(10,123,1000,"Knight"));store.flush();
            try(ArchiveResult<Row> result=open(store,query(store.currentId(),View.FAME));ArchiveResult.Lease<Row> lease=result.lease()){
                Map<Integer,Row> byId=new HashMap<>();for(ArchiveRow<Row> row:rows(result))byId.put(row.value.character,row.value);
                Row unknown=byId.get(8);assertNull(unknown.time);assertNull(unknown.firstFame);assertNull(unknown.lastFame);assertNull(unknown.gain);assertNull(unknown.millis);assertEquals(2L,unknown.count.longValue());
                assertEquals(0.0,byId.get(9).gain,0);assertEquals(1000L,byId.get(9).millis.longValue());
                assertEquals(0.0,byId.get(10).gain,0);assertNull(byId.get(10).millis);assertTrue(byId.get(10).evidence.contains("same-sample delta"));
                FameSession graph=LootArchiveClient.readFame(lease,store.currentId(),new Cancellation());assertTrue(graph.datedSamples(8).isEmpty());assertEquals(2,graph.chronology(8).undatedCount());assertEquals(2,graph.getCharacterData(8).size());assertNull(graph.chronology(8).gain());
            }
            try(ArchiveResult<Row> sessions=open(store,query(store.currentId(),View.SESSIONS))){assertNull(rows(sessions).get(0).value.gain);}
        }
    }
    @Test public void finiteBoundsCanExcludeUndatedSamplesWithoutInventingTheirOrder()throws Exception{
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"fixture")){
            store.append("fame",new AppHistory.FameSample(7,100,0,"Wizard"));store.append("fame",new AppHistory.FameSample(7,150,1000,"Wizard"));store.append("fame",new AppHistory.FameSample(7,170,2000,"Wizard"));store.flush();
            for(View view:Arrays.asList(View.FAME,View.SESSIONS))for(boolean includeUnknown:new boolean[]{false,true}){
                ArchiveQuery<Facets,Sort> q=query(store.currentId(),view).withBounds(new ArchiveQuery.Bounds(500L,2500L,ZoneId.of("UTC"),ArchiveQuery.TimeMode.ENTRY,includeUnknown));
                try(ArchiveResult<Row> result=open(store,q)){Row row=rows(result).get(0).value;if(includeUnknown)assertNull(row.gain);else assertEquals(20.0,row.gain,0);if(view==View.FAME){assertEquals(150.0,row.firstFame,0);assertEquals(170.0,row.lastFame,0);if(includeUnknown)assertNull(row.millis);else assertEquals(1000L,row.millis.longValue());}}
            }
        }
    }
    @Test public void singleImportedSnapshotRetainsMetadataAndUndatedEvidenceFromItsPin()throws Exception{
        Path root=temp.newFolder().toPath();String original="{\"sessionName\":\"Imported custom\",\"createdTimestamp\":123456789,\"lastModifiedTimestamp\":234567890,\"description\":\"Recorded description\",\"readOnly\":false,\"characterClassNames\":{\"7\":\"Wizard\"},\"characterFameData\":{\"7\":[{\"fame\":100,\"time\":0},{\"fame\":150,\"time\":1000}]},\"characterMapFameData\":{}}";
        try(SessionStore store=new SessionStore(root,true,"fixture")){
            store.importSnapshot("legacy-single","Legacy fixture",999,"fame-snapshots","one",JsonParser.parseString(original));store.flush();
            String id=store.sessions().stream().filter(s->s.version.equals("Imported")).findFirst().get().id;
            try(ArchiveResult<Row> result=open(store,query(id,View.FAME));ArchiveResult.Lease<Row> lease=result.lease()){
                Path file;try(java.util.stream.Stream<Path> files=Files.list(root.resolve(id).resolve("fame-snapshots"))){file=files.findFirst().get();}
                Files.write(file,original.replace("Recorded description","Replacement").replace("123456789","987654321").getBytes(StandardCharsets.UTF_8));
                FameSession graph=LootArchiveClient.readFame(lease,id,new Cancellation());assertEquals("Imported custom",graph.getSessionName());assertEquals(123456789,graph.getCreatedTimestamp());assertEquals(234567890,graph.getLastModifiedTimestamp());assertEquals("Recorded description",graph.getDescription());assertFalse(graph.isReadOnly());
                assertEquals("PINNED_SNAPSHOT",graph.getArchiveProvenance().kind);assertEquals(result.revision,graph.getArchiveProvenance().revision);assertEquals(id,graph.getArchiveProvenance().sourceSession);
                assertEquals(2,graph.getCharacterData(7).size());assertEquals(Collections.singletonList(new Fame(150,1000)),graph.datedSamples(7));assertNull(graph.chronology(7).gain());
                String text=FameSessionViewer.metadataText(graph);assertTrue(text.contains("Created: "+Formatters.formatTimestamp(123456789)));assertTrue(text.contains("Last Modified: "+Formatters.formatTimestamp(234567890)));assertTrue(text.contains("Projection generated:"));
            }
        }
    }
    @Test public void synthesizedGraphHasGenerationProvenanceAndNoInventedHistoricalDates()throws Exception{
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"fixture")){
            FameSession snapshot=SessionStore.JSON.fromJson("{\"sessionName\":\"Original\",\"createdTimestamp\":123,\"lastModifiedTimestamp\":456,\"description\":\"Original description\",\"characterFameData\":{\"7\":[{\"fame\":100,\"time\":1000}]}}",FameSession.class);
            store.put("fame-snapshots","one",snapshot);store.append("fame",new AppHistory.FameSample(7,150,2000,"Wizard"));store.flush();
            try(ArchiveResult<Row> result=open(store,query(store.currentId(),View.FAME));ArchiveResult.Lease<Row> lease=result.lease()){
                long before=System.currentTimeMillis();FameSession graph=LootArchiveClient.readFame(lease,store.currentId(),new Cancellation());
                assertEquals(0,graph.getCreatedTimestamp());assertEquals(0,graph.getLastModifiedTimestamp());assertTrue(graph.getDescription().contains("Synthesized"));assertTrue(graph.isReadOnly());
                FameSession.ArchiveProvenance source=graph.getArchiveProvenance();assertEquals("SYNTHESIZED",source.kind);assertEquals(2,source.sourceRecords);assertEquals(result.revision,source.revision);assertTrue(source.generatedTimestamp>=before&&source.generatedTimestamp<=System.currentTimeMillis());
                String text=FameSessionViewer.metadataText(graph);assertTrue(text.contains("Historical Created / Last Modified: Not captured"));assertTrue(text.contains("Projection generated:"));assertFalse(text.contains("\nCreated:"));assertEquals(50.0,graph.chronology(7).gain(),0);
            }
        }
    }
    @Test public void absentSnapshotDatesAreUnknownAndOrdinaryManualSessionsKeepTheirMetadataContract()throws Exception{
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"fixture")){
            store.put("fame-snapshots","one",JsonParser.parseString("{\"sessionName\":\"No dates\",\"description\":\"Kept\",\"characterFameData\":{\"7\":[{\"fame\":123,\"time\":1000}]}}"));store.flush();
            try(ArchiveResult<Row> result=open(store,query(store.currentId(),View.FAME));ArchiveResult.Lease<Row> lease=result.lease()){
                FameSession graph=LootArchiveClient.readFame(lease,store.currentId(),new Cancellation());assertEquals(0,graph.getCreatedTimestamp());assertEquals(0,graph.getLastModifiedTimestamp());assertTrue(FameSessionViewer.metadataText(graph).contains("Created: Not captured"));assertEquals("Kept",graph.getDescription());assertEquals(0.0,graph.chronology(7).gain(),0);assertNull(graph.chronology(7).elapsed());
            }
        }
        FameSession manual=new FameSession("Manual");manual.setDescription("My note");manual.addCharacterData(7,Arrays.asList(new Fame(100,1000),new Fame(100,2000),new Fame(95,2000)));
        assertNull(manual.getArchiveProvenance());assertTrue(manual.getCreatedTimestamp()>0);assertTrue(FameSessionViewer.metadataText(manual).startsWith("Created: "));assertEquals(manual.getCharacterData(7),manual.datedSamples(7));assertEquals(-5.0,manual.chronology(7).gain(),0);
    }
    private static List<String> csvLine(String line){List<String> values=new ArrayList<>();StringBuilder value=new StringBuilder();boolean quote=false;for(int i=0;i<line.length();i++){char c=line.charAt(i);if(c=='"'){if(quote&&i+1<line.length()&&line.charAt(i+1)=='"'){value.append('"');i++;}else quote=!quote;}else if(c==','&&!quote){values.add(value.toString());value.setLength(0);}else value.append(c);}values.add(value.toString());return values;}
    private interface Checked<T>{T get()throws Exception;}
    private static <T>T edt(Checked<T> task)throws Exception{AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();SwingUtilities.invokeAndWait(()->{try{result.set(task.get());}catch(Throwable failure){error.set(failure);}});if(error.get()!=null)throw new AssertionError(error.get());return result.get();}
}
