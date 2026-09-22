package tomato.gui.history;

import com.google.gson.*;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import util.PreferencesStore;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static tomato.history.archive.ArchiveFixtures.*;

public class ArchiveLifecycleRegressionTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void oldRendererCannotRestorePriorScopeWhileReplacementIsBlocked()throws Exception {
        try(Fixture f=new Fixture()) {
            String b=session(f.root,2);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean once=new AtomicBoolean();
            f.client.factory=q->ArchiveAdapter.records("chat",Event.class,"messages",r->r.time,(r,query)->{
                if(query.scope().equals(b)&&once.compareAndSet(false,true)){entered.countDown();waitFor(release);}return true;
            },s->Comparator.comparingInt(r->r.value));
            ArchiveClient.Binding<Facets,Sort> old=edt(()->f.client.binding);JComponent oldView=edt(()->f.client.view);
            try {
                edt(()->{f.workspace.selectSession(b);return null;});assertTrue(entered.await(5,TimeUnit.SECONDS));
                edt(()->{old.queryChanged(query(f.id).withText("stale"));return null;});
                assertEquals(b,edt(()->f.workspace.state().query.scope()));assertFalse(edt(oldView::isEnabled));
                release.countDown();await(()->!f.workspace.loading()&&f.workspace.displayedPage().matches==2);
                assertEquals(b,edt(()->f.workspace.state().query.scope()));
            }finally{release.countDown();}
        }
    }
    @Test public void oldRendererCallbacksAreInertAfterLiveSwitchAndRemoval()throws Exception {
        try(Fixture f=new Fixture()) {
            ArchiveClient.Binding<Facets,Sort> old=edt(()->f.client.binding);
            edt(()->{button(f.workspace,"Current live view").doClick();old.queryChanged(query(f.id).withText("stale live"));return null;});
            assertFalse(edt(()->f.workspace.state().archive));
            edt(()->{f.workspace.showSaved();return null;});await(()->!f.workspace.loading());
            ArchiveClient.Binding<Facets,Sort> removed=edt(()->f.client.binding);String before=edt(()->f.workspace.state().query.text());
            edt(()->{f.workspace.removeNotify();removed.queryChanged(query(f.id).withText("stale removed"));return null;});
            assertEquals(before,edt(()->f.workspace.state().query.text()));
        }
    }
    @Test public void cancelFalseKeepsExportBusyUntilWriterExitsWithoutClosingWorkspace()throws Exception {
        try(Fixture f=new Fixture()) {
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),notified=new CountDownLatch(1);f.client.columns=blockedColumns(entered,release);
            try {
                SwingWorker<Path,Void> worker=edt(()->f.workspace.exportTo(f.output,"cancel",ExportSelection.all(),ArchiveExport.Format.CSV));
                worker.addPropertyChangeListener(e->{if("state".equals(e.getPropertyName())&&e.getNewValue()==SwingWorker.StateValue.DONE)notified.countDown();});
                assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(worker.cancel(false));assertTrue(notified.await(5,TimeUnit.SECONDS));
                assertFalse("Export must remain busy while its writer is blocked",edt(()->button(f.workspace,"Export all matches…").isEnabled()));
                release.countDown();await(()->button(f.workspace,"Export all matches…").isEnabled());assertEquals(0,children(f.output));
            }finally{release.countDown();}
        }
    }
    @Test public void cancelFalseDoesNotPublishAfterBlockedColumnIsReleased()throws Exception {
        try(Fixture f=new Fixture()) {
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);f.client.columns=blockedColumns(entered,release);
            try {
                SwingWorker<Path,Void> worker=edt(()->f.workspace.exportTo(f.output,"cancel",ExportSelection.all(),ArchiveExport.Format.CSV));
                assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(worker.cancel(false));release.countDown();
                awaitOffEdt(()->{try(java.util.stream.Stream<Path> files=Files.list(f.output)){return files.noneMatch(p->p.toString().endsWith(".tmp"));}catch(Exception e){throw new RuntimeException(e);}});
                assertEquals("Cancellation must not publish a CSV",0,children(f.output));
                assertNotNull(edt(f.workspace::displayedPage));
            }finally{release.countDown();}
        }
    }
    @Test public void malformedNamedReferenceDoesNotReplaceOrPersistValidWorkspaceState()throws Exception {
        try(Fixture f=new Fixture()) {
            String b=session(f.root,2);JsonObject valid=edt(()->f.workspace.state().toJson());
            JsonObject doc=JsonParser.parseString(f.preferences.getProperty("ux.archive.regression")).getAsJsonObject();
            JsonObject invalid=valid.deepCopy();invalid.add("anchor",new JsonObject());invalid.add("query",query(b).toJson());doc.getAsJsonObject("named").add("Broken",invalid);
            f.preferences.setProperties("ux.archive.regression",doc.toString()).toCompletableFuture().get();
            edt(()->{f.workspace.loadNamed("Broken");return null;});
            assertEquals(valid,edt(()->f.workspace.state().toJson()));
            assertEquals(valid,JsonParser.parseString(f.preferences.getProperty("ux.archive.regression")).getAsJsonObject().get("last"));
        }
    }
    @Test public void allSavedReferencesAndNullEntriesAreValidatedBeforeRestoration()throws Exception {
        ViewState<Facets,Sort> defaults=ViewState.initial(query(ArchiveQuery.CURRENT));
        for(String malformed:Arrays.asList("{}","{\"session\":null,\"module\":\"chat\",\"locator\":\"x\",\"child\":\"\"}","{\"session\":1,\"module\":\"chat\",\"locator\":\"x\",\"child\":\"\"}")) {
            JsonObject json=defaults.toJson();json.add("anchor",JsonParser.parseString(malformed));
            try{defaults.restore(json);fail("Malformed anchor accepted: "+malformed);}catch(IllegalArgumentException expected){ }
        }
        JsonObject json=defaults.toJson();JsonArray selected=new JsonArray();selected.add(JsonNull.INSTANCE);json.add("selected",selected);
        try{defaults.restore(json);fail("Null selected reference accepted");}catch(IllegalArgumentException expected){ }
    }
    private List<ArchiveExport.Column<Event>> blockedColumns(CountDownLatch entered,CountDownLatch release){return Collections.singletonList(new ArchiveExport.Column<>("Value",r->{entered.countDown();waitFor(release);return r.value;}));}
    private final class Fixture implements AutoCloseable {
        final Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath(),output=temp.newFolder().toPath();final String id=session(root,1);
        final SessionStore store=new SessionStore(root,false,"test");final PreferencesStore preferences=new PreferencesStore(temp.newFolder().toPath().resolve("prefs.properties"));
        final Client client=new Client(id,scratch);final ArchiveWorkspace<Event,Facets,Sort> workspace;
        Fixture()throws Exception{preferences.preload();workspace=edt(()->SessionPanel.queried(store,"regression",new JLabel("live"),client,ViewStateStore.preferences(preferences)));edt(()->{workspace.showSaved();return null;});await(()->workspace.displayedPage()!=null&&!workspace.loading());}
        public void close()throws Exception{edt(()->{workspace.close();return null;});store.close();preferences.shutdown(5,TimeUnit.SECONDS,m->{});}
    }
    private static class Client implements ArchiveClient<Event,Facets,Sort> {
        final String scope;final Path scratch;Binding<Facets,Sort> binding;JComponent view;
        Function<ArchiveQuery<Facets,Sort>,ArchiveAdapter<Event,Facets,Sort>> factory=q->ArchiveFixtures.adapter();
        List<ArchiveExport.Column<Event>> columns=Collections.emptyList();
        Client(String scope,Path scratch){this.scope=scope;this.scratch=scratch;}
        public ArchiveQuery<Facets,Sort> initialQuery(){return query(scope);}public Path scratchDirectory(){return scratch;}
        public ArchiveAdapter<Event,Facets,Sort> adapter(ArchiveQuery<Facets,Sort> q){return factory.apply(q);}
        public List<ArchiveExport.Column<Event>> exportColumns(){return columns;}
        public JComponent render(ArchivePage<Event> page,ViewState<Facets,Sort> state,Binding<Facets,Sort> binding){this.binding=binding;view=new JButton("old renderer");return view;}
    }
    private static void waitFor(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("Blocked test was not released");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}
    @FunctionalInterface private interface Checked<T>{T get()throws Exception;}
    private static <T> T edt(Checked<T> read)throws Exception{AtomicReference<T> value=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();SwingUtilities.invokeAndWait(()->{try{value.set(read.get());}catch(Throwable e){error.set(e);}});if(error.get()!=null)throw new AssertionError(error.get());return value.get();}
    private static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){if(edt(condition::getAsBoolean))return;Thread.sleep(20);}fail("EDT state did not settle");}
    private static void awaitOffEdt(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(20);}fail("Worker did not settle");}
    private static JButton button(Container parent,String label){for(Component child:parent.getComponents()){if(child instanceof JButton&&label.equals(((JButton)child).getText()))return (JButton)child;if(child instanceof Container){JButton found=button((Container)child,label);if(found!=null)return found;}}return null;}
}
