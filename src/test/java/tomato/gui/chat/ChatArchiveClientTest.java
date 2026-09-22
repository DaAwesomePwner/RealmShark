package tomato.gui.chat;

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

public class ChatArchiveClientTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final LocalDateTime BASE = LocalDateTime.of(2026,9,1,12,0);
    private ChatMessage marked;
    private String populate(Path root) throws Exception {
        try (SessionStore source = new SessionStore(root,true,"synthetic")) {
            source.flush();
            try (BufferedWriter out = Files.newBufferedWriter(root.resolve(source.currentId()).resolve("chat.jsonl"),StandardCharsets.UTF_8)) {
                for (int i=0;i<12005;i++) {
                    ChatMessage m = new ChatMessage(BASE.plusSeconds(i),i>=12000||i==3?ChatMessage.Channel.GUILD:ChatMessage.Channel.WORLD,
                        i>=12000||i==3?"Ann":"Anna","","Ann","needle "+i,"");
                    if(i==3)marked=m;out.write(SessionStore.JSON.toJson(m));out.newLine();
                }
            }return source.currentId();
        }
    }
    @Test public void globalFacetsStarsAndSortPrecedePagingAndExportsKeepFrozenReasonsAndStars() throws Exception {
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath(),output=temp.newFolder().toPath();String session=populate(root);
        try(SessionStore store=new SessionStore(root,true,"later annotations")){
            store.put("chat-stars",marked.id,new ChatExplorer.Bookmark(marked.id,true,100));store.flush();
            ChatFilters filters=new ChatFilters();ChatFilters.Settings s=filters.settings();s.ignoredPlayers.add("Ann");filters.apply(s,false);
            ChatArchiveClient.Facets f=new ChatArchiveClient.Facets();f.channel="GUILD";f.player="Ann";f.starredOnly=true;f.showIgnoredPlayers=true;
            ArchiveQuery<ChatArchiveClient.Facets,ChatArchiveClient.Sort> q=ChatArchiveClient.query().withScope(session).withText("needle").withFacets(f)
                .withBounds(new ArchiveQuery.Bounds(BASE.plusSeconds(3).toInstant(ZoneOffset.UTC).toEpochMilli(),BASE.plusSeconds(4).toInstant(ZoneOffset.UTC).toEpochMilli(),ZoneOffset.UTC,ArchiveQuery.TimeMode.ENTRY,false));
            ChatArchiveClient.Adapter adapter=new ChatArchiveClient.Adapter(filters.snapshot());
            s.ignoredPlayers.clear();filters.apply(s,false);
            try(ArchiveResult<ChatArchiveClient.Row> result=ArchiveResult.open(store,q,adapter,scratch,new Cancellation())){
                ArchivePage<ChatArchiveClient.Row> page=result.page(0,1000,new Cancellation());assertEquals(1,page.matches);
                assertEquals(marked.id,page.rows.get(0).value.message.id);assertEquals("Ignored player: Ann",page.rows.get(0).value.reason);
                store.put("chat-stars",marked.id,new ChatExplorer.Bookmark(marked.id,false,200));store.flush();
                try(ArchiveResult.Lease<ChatArchiveClient.Row> lease=result.lease()){
                    Path json=ArchiveExport.write(lease,ExportSelection.all(),ArchiveExport.Format.JSON,output,"chat",Collections.emptyList(),new Cancellation());
                    JsonObject exported=JsonParser.parseString(new String(Files.readAllBytes(json),StandardCharsets.UTF_8)).getAsJsonObject();
                    assertEquals(1,exported.getAsJsonArray("rows").size());JsonObject value=exported.getAsJsonArray("rows").get(0).getAsJsonObject().getAsJsonObject("value");
                    assertTrue(value.get("starred").getAsBoolean());assertEquals("Ignored player: Ann",value.get("reason").getAsString());
                    Path csv=ArchiveExport.write(lease,ExportSelection.all(),ArchiveExport.Format.CSV,output,"chat-csv",new ChatArchiveClient(store,filters,null,scratch).exportColumns(),new Cancellation());
                    assertTrue(new String(Files.readAllBytes(csv),StandardCharsets.UTF_8).contains("Ignored player: Ann"));
                }
                assertEquals(page.revision,result.page(0,1000,new Cancellation()).revision);
            }
            try(ArchiveResult<ChatArchiveClient.Row> next=ArchiveResult.open(store,q,new ChatArchiveClient.Adapter(filters.snapshot()),scratch,new Cancellation())){assertEquals(0,next.matches);}
            ArchiveQuery<ChatArchiveClient.Facets,ChatArchiveClient.Sort> all=ChatArchiveClient.query().withScope(session);
            try(ArchiveResult<ChatArchiveClient.Row> result=ArchiveResult.open(store,all,new ChatArchiveClient.Adapter(filters.snapshot()),scratch,new Cancellation())){
                assertEquals(12005,result.matches);ArchivePage<ChatArchiveClient.Row> first=result.page(0,1000,new Cancellation());
                assertEquals("needle 12004",first.rows.get(0).value.message.text);assertEquals("needle 0",result.page(12,1000,new Cancellation()).rows.get(4).value.message.text);
                assertEquals(marked.id,result.page(12,1000,new Cancellation()).rows.get(1).value.message.id);
                assertEquals(12,result.pageOf(result.page(12,1000,new Cancellation()).rows.get(4).ref,1000,new Cancellation()));
            }
        }
    }
    @Test public void realWorkspaceRestoresNamedStateAndRejectsOldRendererCallbacks() throws Exception {
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String session=populate(root);
        PreferencesStore preferences=new PreferencesStore(temp.getRoot().toPath().resolve("views.properties"));preferences.preload();ViewStateStore states=ViewStateStore.preferences(preferences);
        try(SessionStore store=new SessionStore(root,false,"test")){
            ChatFilters policy=new ChatFilters();ChatArchiveClient client=new ChatArchiveClient(store,policy,null,scratch);
            ArchiveWorkspace<ChatArchiveClient.Row,ChatArchiveClient.Facets,ChatArchiveClient.Sort> ws=edt(()->SessionPanel.queried(store,"chat",new JPanel(),client,states));
            ArchiveWorkspace<tomato.gui.keypop.KeyPopArchiveClient.Row,tomato.gui.keypop.KeyPopArchiveClient.Facets,tomato.gui.keypop.KeyPopArchiveClient.Sort> other=
                edt(()->SessionPanel.queried(store,"keypops",new JPanel(),new tomato.gui.keypop.KeyPopArchiveClient(scratch),states));
            try{
                edt(()->{ws.changeQuery(ChatArchiveClient.query().withScope(session));return null;});await(()->ws.displayedPage()!=null&&!ws.loading());
                JTextField old=edt(()->named(ws,"chat-archive-player",JTextField.class));
                edt(()->{JComboBox<?> channel=named(ws,"chat-archive-channel",JComboBox.class);channel.setSelectedItem(ChatMessage.Channel.GUILD);return null;});
                await(()->!ws.loading()&&ws.displayedPage().matches==6);
                edt(()->{old.setText("stale");old.postActionEvent();return null;});assertEquals("",edt(()->ws.state().query.facets().player));
                edt(()->{JTable table=named(ws,"chat-archive-messages",JTable.class);table.setRowSelectionInterval(1,1);table.getColumnModel().getColumn(3).setWidth(260);return null;});
                edt(()->ws.saveNamed("Guild review")).toCompletableFuture().get(5,TimeUnit.SECONDS);preferences.flush().toCompletableFuture().get();
                ViewState<ChatArchiveClient.Facets,ChatArchiveClient.Sort> saved=edt(ws::state);assertFalse(edt(()->other.state().archive));assertEquals(ArchiveQuery.CURRENT,edt(()->other.state().query.scope()));
                edt(()->{ws.close();return null;});
                ArchiveWorkspace<ChatArchiveClient.Row,ChatArchiveClient.Facets,ChatArchiveClient.Sort> restored=edt(()->SessionPanel.queried(store,"chat",new JPanel(),new ChatArchiveClient(store,policy,null,scratch),states));
                try{await(()->!restored.loading()&&restored.displayedPage()!=null);assertEquals(saved.query,edt(()->restored.state().query));assertEquals(saved.selected,edt(()->restored.state().selected));
                    assertEquals(260,edt(()->named(restored,"chat-archive-messages",JTable.class).getColumnModel().getColumn(3).getWidth()).intValue());
                    edt(()->{restored.changeQuery(ChatArchiveClient.query().withScope(SessionStore.ALL));return null;});await(()->!restored.loading());
                    edt(()->{restored.loadNamed("Guild review");return null;});await(()->!restored.loading()&&restored.displayedPage().matches==6);
                }finally{edt(()->{restored.close();return null;});}
            }finally{edt(()->{ws.close();other.close();return null;});}
        }finally{preferences.shutdown(5,TimeUnit.SECONDS,m->{});}
    }
    @Test public void legacyClockAssumptionAndExplicitHalfOpenBoundsAreNotFabricatedUtc() {
        ZoneId zone=ZoneId.of("America/New_York");ChatFilters policy=new ChatFilters();
        ChatMessage overlap=new ChatMessage(LocalDateTime.of(2026,11,1,1,30),ChatMessage.Channel.WORLD,"Ann","","Ann","hi","");
        ChatArchiveClient.Row row=new ChatArchiveClient.Row(overlap,false,0,policy.snapshot(),zone);
        assertEquals(Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(),row.assumedTime.longValue());assertTrue(row.timeInterpretation.contains("Not captured UTC"));
        assertFalse(new ArchiveQuery.Bounds(null,row.assumedTime,zone,ArchiveQuery.TimeMode.ENTRY,false).contains(row.assumedTime,null));
        try{SocialQueryControls.parseDate("2026-11-01T01:30:00",zone);fail();}catch(IllegalArgumentException expected){}
        assertEquals(Long.valueOf(Instant.parse("2026-11-01T06:30:00Z").toEpochMilli()),SocialQueryControls.parseDate("2026-11-01T01:30:00-05:00",zone));
        ArchiveQuery.Bounds all=new ArchiveQuery.Bounds(null,null,zone,ArchiveQuery.TimeMode.ENTRY,false);
        assertTrue(all.contains(null,null));assertTrue(SocialQueryControls.boundsLabel(all,true).contains("unknown times included"));
    }
    @Test public void displayedPolicyChangesCreateNewPinsWhileAnOldExportLeaseKeepsItsReasons() throws Exception {
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String session=populate(root);
        PreferencesStore preferences=new PreferencesStore(temp.getRoot().toPath().resolve("policy-state.properties"));preferences.preload();
        try(SessionStore store=new SessionStore(root,false,"reader")){
            ChatFilters filters=new ChatFilters();JPanel host=edt(()->{JPanel p=new JPanel();p.setVisible(false);p.addNotify();return p;});
            ArchiveWorkspace<ChatArchiveClient.Row,ChatArchiveClient.Facets,ChatArchiveClient.Sort> ws=edt(()->{
                ArchiveWorkspace<ChatArchiveClient.Row,ChatArchiveClient.Facets,ChatArchiveClient.Sort> w=SessionPanel.queried(store,"chat",new JPanel(),new ChatArchiveClient(store,filters,null,scratch),ViewStateStore.preferences(preferences));host.add(w);return w;});
            try{
                ChatArchiveClient.Facets facets=new ChatArchiveClient.Facets();facets.channel="GUILD";
                edt(()->{ws.changeQuery(ChatArchiveClient.query().withScope(session).withFacets(facets));return null;});await(()->!ws.loading()&&ws.displayedPage()!=null&&ws.displayedPage().matches==6);
                String revision=edt(()->ws.displayedPage().revision);
                try(ArchiveResult.Lease<ChatArchiveClient.Row> held=edt(()->ws.displayedPage().lease())){
                    edt(()->{ChatFilters.Settings settings=filters.settings();settings.ignoredPlayers.add("Ann");filters.apply(settings,false);return null;});
                    await(()->!ws.loading()&&ws.displayedPage().matches==0&&!revision.equals(ws.displayedPage().revision));
                    List<ChatArchiveClient.Row> rows=new ArrayList<>();held.stream(ExportSelection.all(),row->rows.add(row.value),new Cancellation());
                    assertEquals(6,rows.size());for(ChatArchiveClient.Row row:rows)assertEquals("",row.reason);
                    edt(()->{ChatFilters.Settings settings=filters.settings();settings.ignoredPlayers.clear();filters.apply(settings,false);return null;});await(()->!ws.loading()&&ws.displayedPage().matches==6);
                }
            }finally{edt(()->{ws.close();host.removeNotify();return null;});}
        }finally{preferences.shutdown(5,TimeUnit.SECONDS,m->{});}
    }
    @Test public void missingLegacyIdsCannotInheritStarsAndEqualTimeBookmarksUseStableSourceTieBreak() throws Exception {
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String first,second;
        ChatMessage message=new ChatMessage(BASE,ChatMessage.Channel.WORLD,"Ann","","Ann","saved","");
        try(SessionStore s=new SessionStore(root,true,"one")){s.append("chat",message);s.put("chat-stars",message.id,new ChatExplorer.Bookmark(message.id,true,10));s.flush();first=s.currentId();}
        try(SessionStore s=new SessionStore(root,true,"two")){s.put("chat-stars",message.id,new ChatExplorer.Bookmark(message.id,false,10));s.flush();second=s.currentId();}
        JsonObject legacy=SessionStore.JSON.toJsonTree(message).getAsJsonObject();legacy.remove("id");legacy.addProperty("searchable","obsolete cache");
        Files.write(root.resolve(first).resolve("chat.jsonl"),(legacy.toString()+"\n").getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
        try(SessionStore store=new SessionStore(root,false,"reader");ArchiveResult<ChatArchiveClient.Row> result=ArchiveResult.open(store,ChatArchiveClient.query().withScope(first).withText("saved"),new ChatArchiveClient.Adapter(new ChatFilters().snapshot()),scratch,new Cancellation())){
            ArchivePage<ChatArchiveClient.Row> page=result.page(0,1000,new Cancellation());assertEquals(2,page.matches);
            for(ArchiveRow<ChatArchiveClient.Row> row:page.rows){if(row.value.message.id==null)assertFalse(row.value.starred);else assertEquals(first.compareTo(second)>0,row.value.starred);}
        }
    }
}
