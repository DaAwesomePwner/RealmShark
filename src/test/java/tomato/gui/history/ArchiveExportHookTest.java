package tomato.gui.history;

import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.activity.*;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import util.PreferencesStore;
import javax.swing.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;
import static tomato.gui.activity.ActivityQueries.*;

/** Runs the toolbar's preview/confirm/write path with a headless confirmation callback. */
public class ArchiveExportHookTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void sharedWriterDispatchesExistingActivityHookAndDefaultAllMatchExport()throws Exception {
        Path scratch=temp.newFolder().toPath(),output=temp.newFolder().toPath();PreferencesStore preferences=preferences();
        try(SessionStore store=populated()) {
            ActivityArchiveClient client=new ActivityArchiveClient(ActivityPanel.Mode.RUNS,scratch);
            ArchiveWorkspace<Row,Filters,Sort> workspace=edt(()->SessionPanel.queried(store,"activity-hook",new JPanel(),client,ViewStateStore.preferences(preferences)));
            try {
                edt(()->{workspace.showSaved();return null;});await(()->!workspace.loading()&&workspace.displayedPage()!=null);
                ArchiveRow.Ref ref=edt(()->workspace.displayedPage().rows.get(0).ref);
                Path linked=edt(()->workspace.exportTo(output,"selected",ExportSelection.selected(Collections.singleton(ref)),ArchiveExport.Format.JSON)).get(5,TimeUnit.SECONDS);
                assertEquals(3,json(linked).getAsJsonArray("rows").size());assertEquals(2,json(linked).getAsJsonObject("manifest").get("linkedEventCount").getAsInt());
                awaitExportIdle(workspace);
                Path all=edt(()->workspace.exportTo(output,"all",ExportSelection.all(),ArchiveExport.Format.JSON)).get(5,TimeUnit.SECONDS);
                assertEquals(1,json(all).getAsJsonArray("rows").size());assertEquals("ALL_MATCHES",json(all).getAsJsonObject("manifest").get("exportScope").getAsString());
            }finally{edt(()->{workspace.close();return null;});}
        }finally{preferences.shutdown(5,TimeUnit.SECONDS,m->{});}
    }
    @Test public void linkedPreviewRunsOffEdtAndHandsExactlyItsOldLeaseToTheWriter()throws Exception {
        Path scratch=temp.newFolder().toPath(),output=temp.newFolder().toPath();PreferencesStore preferences=preferences();
        CountDownLatch counted=new CountDownLatch(1),release=new CountDownLatch(1),confirmed=new CountDownLatch(1);
        AtomicReference<ArchiveResult.Lease<Row>> previewLease=new AtomicReference<>();AtomicReference<SwingWorker<Path,Void>> writing=new AtomicReference<>();AtomicReference<Throwable> callbackFailure=new AtomicReference<>();
        try(SessionStore store=populated()) {
            ActivityArchiveClient activity=new ActivityArchiveClient(ActivityPanel.Mode.RUNS,scratch);
            ArchiveClient<Row,Filters,Sort> client=new ArchiveClient<Row,Filters,Sort>(){
                public ArchiveQuery<Filters,Sort> initialQuery(){return activity.initialQuery();}public Path scratchDirectory(){return scratch;}
                public ArchiveAdapter<Row,Filters,Sort> adapter(ArchiveQuery<Filters,Sort> q){return activity.adapter(q);}
                public JComponent render(ArchivePage<Row> page,ViewState<Filters,Sort> state,Binding<Filters,Sort> binding){return new JLabel(page.description());}
                public String previewExport(ArchiveResult.Lease<Row> lease,ExportSelection selection,Cancellation cancel)throws IOException{
                    assertFalse(SwingUtilities.isEventDispatchThread());previewLease.set(lease);
                    SelectedRunExport.Preview preview=SelectedRunExport.preview(lease,selection.refs.iterator().next(),cancel);counted.countDown();waitFor(release);return preview.description();
                }
                public Path writeExport(ArchiveResult.Lease<Row> lease,ExportSelection selection,ArchiveExport.Format format,Path directory,String base,Cancellation cancel)throws IOException{
                    assertFalse(SwingUtilities.isEventDispatchThread());assertSame(previewLease.get(),lease);return activity.writeExport(lease,selection,format,directory,base,cancel);
                }
            };
            ArchiveWorkspace<Row,Filters,Sort> workspace=edt(()->SessionPanel.queried(store,"preview-hook",new JPanel(),client,ViewStateStore.preferences(preferences)));
            try {
                edt(()->{workspace.showSaved();return null;});await(()->!workspace.loading()&&workspace.displayedPage()!=null);
                ArchivePage<Row> old=edt(workspace::displayedPage);ExportSelection selection=ExportSelection.selected(Collections.singleton(old.rows.get(0).ref));
                edt(()->workspace.prepareExport(selection,(held,text)->{
                    try{assertTrue(SwingUtilities.isEventDispatchThread());assertTrue(text,text.contains("1 selected visit + 2 linked Timeline events"));assertTrue(text.contains(old.revision));
                        writing.set(workspace.startExport(held,output,"confirmed",selection,ArchiveExport.Format.JSON));return true;
                    }catch(Throwable failure){callbackFailure.set(failure);return false;}finally{confirmed.countDown();}
                }));
                assertTrue(counted.await(5,TimeUnit.SECONDS));store.append("timeline",event("visit",3));store.flush();
                edt(()->{workspace.refresh();return null;});await(()->!workspace.loading()&&!old.revision.equals(workspace.displayedPage().revision));
                release.countDown();assertTrue(confirmed.await(5,TimeUnit.SECONDS));if(callbackFailure.get()!=null)throw new AssertionError(callbackFailure.get());
                JsonObject exported=json(writing.get().get(5,TimeUnit.SECONDS));assertEquals(3,exported.getAsJsonArray("rows").size());
                assertEquals(old.revision,exported.getAsJsonObject("manifest").get("revision").getAsString());
            }finally{release.countDown();edt(()->{workspace.close();return null;});}
        }finally{release.countDown();preferences.shutdown(5,TimeUnit.SECONDS,m->{});}
        awaitOffEdt(()->empty(scratch));
    }
    @Test public void cancelledPreviewDoesNotConfirmAndReleasesOnlyAfterReaderExit()throws Exception {
        Path scratch=temp.newFolder().toPath();PreferencesStore preferences=preferences();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger confirmations=new AtomicInteger();
        try(SessionStore store=populated()) {
            ArchiveClient<Row,Filters,Sort> client=new ArchiveClient<Row,Filters,Sort>(){
                public ArchiveQuery<Filters,Sort> initialQuery(){return initial();}public Path scratchDirectory(){return scratch;}
                public ArchiveAdapter<Row,Filters,Sort> adapter(ArchiveQuery<Filters,Sort> q){return ActivityQueries.adapter(ActivityPanel.Mode.RUNS);}
                public JComponent render(ArchivePage<Row> page,ViewState<Filters,Sort> state,Binding<Filters,Sort> binding){return new JLabel("Preview cancellation");}
                public String previewExport(ArchiveResult.Lease<Row> lease,ExportSelection selection,Cancellation cancel)throws IOException{
                    entered.countDown();waitFor(release);cancel.check();return ArchiveClient.super.previewExport(lease,selection,cancel);
                }
            };
            ArchiveWorkspace<Row,Filters,Sort> workspace=edt(()->SessionPanel.queried(store,"cancel-preview",new JPanel(),client,ViewStateStore.preferences(preferences)));
            try {
                edt(()->{workspace.showSaved();return null;});await(()->!workspace.loading()&&workspace.displayedPage()!=null);
                SwingWorker<String,Void> preview=edt(()->workspace.prepareExport(ExportSelection.all(),(held,text)->{confirmations.incrementAndGet();return false;}));
                assertTrue(entered.await(5,TimeUnit.SECONDS));preview.cancel(false);edt(()->{workspace.close();return null;});
                assertFalse("Reader still owns the pin",empty(scratch));release.countDown();awaitOffEdt(()->empty(scratch));assertEquals(0,confirmations.get());
            }finally{release.countDown();edt(()->{workspace.close();return null;});}
        }finally{release.countDown();preferences.shutdown(5,TimeUnit.SECONDS,m->{});}
    }
    private PreferencesStore preferences()throws Exception{PreferencesStore p=new PreferencesStore(temp.newFolder().toPath().resolve("views.properties"));p.preload();return p;}
    private SessionStore populated()throws Exception{SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic");ActivityJournal.Visit v=new ActivityJournal.Visit();v.id="visit";v.map="Lost Halls";v.started=1000;v.lastSeen=v.ended=2000;
        store.put("runs",v.id,v);store.append("timeline",event(v.id,1));store.append("timeline",event(v.id,2));store.append("timeline",event("other",9));store.flush();return store;}
    private static ActivityJournal.Entry event(String visit,int id){ActivityJournal.Entry e=new ActivityJournal.Entry();e.id="event-"+id;e.visitId=visit;e.map="Lost Halls";e.kind="Area entered";e.detail="Synthetic";e.time=1000+id;e.values=new LinkedHashMap<>();return e;}
    private static JsonObject json(Path path)throws Exception{return JsonParser.parseString(new String(Files.readAllBytes(path),StandardCharsets.UTF_8)).getAsJsonObject();}
    private static boolean empty(Path path){try(java.util.stream.Stream<Path> files=Files.list(path)){return !files.findAny().isPresent();}catch(IOException e){throw new RuntimeException(e);}}
    private static void waitFor(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("Test reader not released");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}
    @FunctionalInterface private interface Checked<T>{T get()throws Exception;}
    private static <T>T edt(Checked<T> work)throws Exception{AtomicReference<T> value=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();SwingUtilities.invokeAndWait(()->{try{value.set(work.get());}catch(Throwable e){failure.set(e);}});if(failure.get()!=null)throw new AssertionError(failure.get());return value.get();}
    private static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){if(edt(condition::getAsBoolean))return;Thread.sleep(20);}fail("EDT did not settle");}
    private static void awaitOffEdt(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(20);}fail("Cleanup did not settle");}
    private static void awaitExportIdle(ArchiveWorkspace<?,?,?> workspace)throws Exception{await(()->enabledExport(workspace));}
    private static boolean enabledExport(java.awt.Container root){for(java.awt.Component component:root.getComponents()){if(component instanceof JButton&&"Export all matches…".equals(((JButton)component).getText()))return component.isEnabled();if(component instanceof java.awt.Container&&enabledExport((java.awt.Container)component))return true;}return false;}
}
