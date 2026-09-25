package tomato.gui.keypop;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.history.*;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import util.PreferencesStore;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.*;

public class KeyPopArchiveClientTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static final Instant BASE=Instant.parse("2026-09-01T12:00:00Z");
    private String populate(Path root)throws Exception{
        try(SessionStore source=new SessionStore(root,true,"synthetic")){
            source.flush();
            try(BufferedWriter out=Files.newBufferedWriter(root.resolve(source.currentId()).resolve("keypops.jsonl"),StandardCharsets.UTF_8)){
                for(int i=0;i<12005;i++){
                    String player=i<12000?"P"+i:new String[]{"Ann","Anna","ANN","Ann","Anna"}[i-12000];
                    KeyPopEvent event=new KeyPopEvent(BASE.plusSeconds(i),player,i<12000?"Halls":"Shatters",i%2==0?KeyPopEvent.Kind.KEY:KeyPopEvent.Kind.VIAL);
                    out.write(SessionStore.JSON.toJson(event));out.newLine();
                }
            }return source.currentId();
        }
    }
    @Test public void twelveThousandEventsFilterBeforePageAndGlobalSummariesHaveExactDenominatorsAndPinnedExports()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath(),output=temp.newFolder().toPath();String id=populate(root);
        try(SessionStore store=new SessionStore(root,false,"reader")){
            KeyPopArchiveClient client=new KeyPopArchiveClient(scratch);KeyPopArchiveClient.Facets f=new KeyPopArchiveClient.Facets();f.exactPlayer="Ann";
            f.items.add("Shatters");f.kinds.add("KEY");f.kinds.add("VIAL");
            ArchiveQuery<KeyPopArchiveClient.Facets,KeyPopArchiveClient.Sort> q=client.initialQuery().withScope(id).withFacets(f)
                .withBounds(new ArchiveQuery.Bounds(BASE.plusSeconds(12000).toEpochMilli(),BASE.plusSeconds(12005).toEpochMilli(),ZoneOffset.UTC,ArchiveQuery.TimeMode.ENTRY,false));
            try(ArchiveResult<KeyPopArchiveClient.Row> events=ArchiveResult.open(store,q,client.adapter(q),scratch,new Cancellation())){
                ArchivePage<KeyPopArchiveClient.Row> page=events.page(0,1000,new Cancellation());assertEquals(3,page.matches);
                assertEquals(BASE.plusSeconds(12003),page.rows.get(0).value.time);assertEquals(3,page.counts.get("events").value);
                for(ArchiveRow<KeyPopArchiveClient.Row> row:page.rows){assertTrue(row.value.player.equalsIgnoreCase("Ann"));assertEquals(3,row.value.matchingEvents);}
            }
            f=new KeyPopArchiveClient.Facets();f.mode=KeyPopArchiveClient.Mode.BY_PLAYER;
            q=client.initialQuery().withScope(id).withFacets(f).withOrder(Collections.singletonList(new ArchiveQuery.Order<>(KeyPopArchiveClient.Sort.POPS,ArchiveQuery.Direction.DESCENDING)));
            try(ArchiveResult<KeyPopArchiveClient.Row> result=ArchiveResult.open(store,q,client.adapter(q),scratch,new Cancellation())){
                assertEquals(12002,result.matches);ArchivePage<KeyPopArchiveClient.Row> first=result.page(0,1000,new Cancellation());
                assertTrue(first.rows.get(0).value.player.equalsIgnoreCase("Ann"));assertEquals(3,first.rows.get(0).value.pops);
                assertEquals(12005,first.rows.get(0).value.matchingEvents);assertEquals(3*100.0/12005,first.rows.get(0).value.share,.000001);
                assertEquals(2,result.page(12,1000,new Cancellation()).rows.size());
                try(ArchiveResult.Lease<KeyPopArchiveClient.Row> lease=result.lease()){
                    Path csv=ArchiveExport.write(lease,ExportSelection.page(0,1000),ArchiveExport.Format.CSV,output,"contributors",client.exportColumns(),new Cancellation());
                    String data=new String(Files.readAllBytes(csv),StandardCharsets.UTF_8);assertTrue(data.contains("contributor summaries"));assertTrue(data.contains("12005"));
                }
            }
            f=new KeyPopArchiveClient.Facets();f.mode=KeyPopArchiveClient.Mode.BY_ITEM;q=client.initialQuery().withScope(id).withFacets(f)
                .withOrder(Collections.singletonList(new ArchiveQuery.Order<>(KeyPopArchiveClient.Sort.POPS,ArchiveQuery.Direction.DESCENDING)));
            try(ArchiveResult<KeyPopArchiveClient.Row> result=ArchiveResult.open(store,q,client.adapter(q),scratch,new Cancellation())){
                ArchivePage<KeyPopArchiveClient.Row> page=result.page(0,1000,new Cancellation());assertEquals(2,page.matches);
                KeyPopArchiveClient.Row halls=page.rows.get(0).value,shatters=page.rows.get(1).value;
                assertEquals(12000,halls.pops);assertEquals(12000,halls.players);assertEquals(5,shatters.pops);assertEquals(2,shatters.players);
                Files.write(root.resolve(id).resolve("keypops.jsonl"),(SessionStore.JSON.toJson(new KeyPopEvent(BASE,"Later","Other",KeyPopEvent.Kind.KEY))+"\n").getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
                try(ArchiveResult.Lease<KeyPopArchiveClient.Row> lease=result.lease()){
                    Path json=ArchiveExport.write(lease,ExportSelection.all(),ArchiveExport.Format.JSON,output,"items",client.exportColumns(),new Cancellation());
                    JsonObject exported=JsonParser.parseString(new String(Files.readAllBytes(json),StandardCharsets.UTF_8)).getAsJsonObject();assertEquals(2,exported.getAsJsonArray("rows").size());
                    assertEquals(12005,exported.getAsJsonArray("rows").get(0).getAsJsonObject().getAsJsonObject("value").get("matchingEvents").getAsLong());
                }
                assertEquals(page.revision,result.page(0,1000,new Cancellation()).revision);
            }
        }
    }
    @Test public void queriedTabsUseGlobalPopulationAndCurrentSummaryExportSurvivesRestart()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath(),out=temp.newFolder().toPath();String id=populate(root);
        PreferencesStore preferences=new PreferencesStore(temp.getRoot().toPath().resolve("keypop-state.properties"));preferences.preload();ViewStateStore states=ViewStateStore.preferences(preferences);
        try(SessionStore store=new SessionStore(root,false,"reader")){
            KeyPopArchiveClient client=new KeyPopArchiveClient(scratch);
            ArchiveWorkspace<KeyPopArchiveClient.Row,KeyPopArchiveClient.Facets,KeyPopArchiveClient.Sort> ws=edt(()->{ArchiveWorkspace<KeyPopArchiveClient.Row,KeyPopArchiveClient.Facets,KeyPopArchiveClient.Sort> w=SessionPanel.queried(store,"keypops",new JPanel(),client,states);client.bind(w);return w;});
            try{
                KeyPopArchiveClient.Facets f=new KeyPopArchiveClient.Facets();f.items.add("Shatters");
                ArchiveQuery<KeyPopArchiveClient.Facets,KeyPopArchiveClient.Sort> q=client.initialQuery().withScope(id).withFacets(f);
                edt(()->{ws.changeQuery(q);return null;});await(()->!ws.loading()&&ws.displayedPage()!=null);
                edt(()->{named(ws,"keypop-archive-tabs",JTabbedPane.class).setSelectedIndex(1);return null;});await(()->!ws.loading()&&ws.displayedPage().matches==2);
                assertEquals(KeyPopArchiveClient.Mode.BY_PLAYER,edt(()->ws.state().query.facets().mode));
                edt(()->{named(ws,"keypop-archive-rows",JTable.class).setRowSelectionInterval(0,0);return null;});
                edt(()->ws.saveNamed("Shatters contributions")).toCompletableFuture().get();preferences.flush().toCompletableFuture().get();
                SwingWorker<Path,Void> export=edt(()->ws.exportTo(out,"summary",ExportSelection.all(),ArchiveExport.Format.JSON));Path path=export.get(15,TimeUnit.SECONDS);
                JsonObject json=JsonParser.parseString(new String(Files.readAllBytes(path),StandardCharsets.UTF_8)).getAsJsonObject();assertEquals(2,json.getAsJsonArray("rows").size());
                edt(()->{ws.close();return null;});
                ArchiveWorkspace<KeyPopArchiveClient.Row,KeyPopArchiveClient.Facets,KeyPopArchiveClient.Sort> restored=edt(()->SessionPanel.queried(store,"keypops",new JPanel(),new KeyPopArchiveClient(scratch),states));
                try{await(()->!restored.loading()&&restored.displayedPage()!=null);assertEquals(KeyPopArchiveClient.Mode.BY_PLAYER,edt(()->restored.state().query.facets().mode));assertEquals(2,edt(()->restored.displayedPage().matches).longValue());}
                finally{edt(()->{restored.close();return null;});}
            }finally{edt(()->{ws.close();return null;});}
        }finally{preferences.shutdown(5,TimeUnit.SECONDS,m->{});}
    }
    @Test public void largeGroupKeysCrossByteBoundWithoutDroppingOrTruncatingSummaries()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String id;
        char[] letters=new char[50000];Arrays.fill(letters,'a');String suffix=new String(letters);
        try(SessionStore source=new SessionStore(root,true,"large synthetic names")){
            source.flush();id=source.currentId();
            try(BufferedWriter out=Files.newBufferedWriter(root.resolve(id).resolve("keypops.jsonl"),StandardCharsets.UTF_8)){
                for(int i=0;i<60;i++){out.write(SessionStore.JSON.toJson(new KeyPopEvent(BASE.plusSeconds(i),"P"+i+suffix,"Halls",KeyPopEvent.Kind.KEY)));out.newLine();}
            }
        }
        try(SessionStore store=new SessionStore(root,false,"reader")){
            KeyPopArchiveClient client=new KeyPopArchiveClient(scratch);KeyPopArchiveClient.Facets facets=new KeyPopArchiveClient.Facets();facets.mode=KeyPopArchiveClient.Mode.BY_PLAYER;
            ArchiveQuery<KeyPopArchiveClient.Facets,KeyPopArchiveClient.Sort> query=client.initialQuery().withScope(id).withFacets(facets);
            try(ArchiveResult<KeyPopArchiveClient.Row> result=ArchiveResult.open(store,query,client.adapter(query),scratch,new Cancellation())){
                assertEquals(60,result.matches);ArchivePage<KeyPopArchiveClient.Row> page=result.page(0,100,new Cancellation());
                assertEquals(60,page.rows.size());for(ArchiveRow<KeyPopArchiveClient.Row> row:page.rows){assertTrue(row.value.player.endsWith(suffix));assertEquals(1,row.value.pops);assertEquals(60,row.value.matchingEvents);}
            }
        }
    }
}
