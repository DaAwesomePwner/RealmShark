package tomato.gui.history;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import util.PreferencesStore;
import javax.swing.*;
import javax.swing.plaf.basic.BasicComboBoxUI;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;
import static tomato.history.archive.ArchiveFixtures.*;

/** Real JDK compound-control hierarchy events, with asynchronous cached-detail adoption and no window. */
public class ArchiveCompoundControlTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();

    @Test public void disabledComboArrowSurvivesChildFirstAdoptionAcrossRefreshPagingAndHide()throws Exception {
        try(Fixture f=new Fixture()) {
            edt(()->{f.client.assertOriginalState();return null;});
            int before=edt(()->f.client.adoptions);JPanel retired=edt(()->f.client.view);
            edt(()->{f.workspace.refresh();f.workspace.refresh();assertFalse(f.client.combo.isEnabled());return null;});
            f.awaitAdoption(before);
            edt(()->{f.client.assertOriginalState();assertFalse(retired.isEnabled());return null;});

            before=edt(()->f.client.adoptions);
            edt(()->{f.workspace.selectPage(1);return null;});f.awaitAdoption(before);
            assertEquals(1,edt(()->f.workspace.displayedPage().page).longValue());
            edt(()->{f.client.assertOriginalState();return null;});

            before=edt(()->f.client.adoptions);ArchiveClient.Binding<Facets,Sort> stale=edt(()->f.client.binding);
            edt(()->{
                button(f.workspace,"Current live view").doClick();f.workspace.refresh();
                f.client.host.remove(f.client.cached);f.client.host.add(f.client.cached);
                assertFalse(f.client.combo.isEnabled());assertFalse(f.client.arrow.isEnabled());
                stale.queryChanged(query(f.id).withText("obsolete"));assertFalse(f.workspace.state().archive);
                f.workspace.showSaved();return null;
            });f.awaitAdoption(before);
            edt(()->{f.client.assertOriginalState();f.workspace.close();new JPanel().add(f.client.cached);
                assertFalse(f.client.combo.isEnabled());assertFalse(f.client.arrow.isEnabled());return null;});
        }
    }

    @Test public void compoundSnapshotsRemainPendingDuringBlockedReadsAndAreNotRestoredAfterDisposal()throws Exception {
        try(Fixture f=new Fixture()) {
            Gate gate=new Gate();f.client.nextRead.set(gate);int before=edt(()->f.client.adoptions);
            ArchiveClient.Binding<Facets,Sort> stale=edt(()->f.client.binding);
            try {
                edt(()->{f.workspace.refresh();return null;});assertTrue(gate.entered.await(5,TimeUnit.SECONDS));
                edt(()->{
                    f.client.host.remove(f.client.cached);f.client.host.add(f.client.cached);
                    assertTrue(f.workspace.loading());assertFalse(f.client.combo.isEnabled());assertFalse(f.client.arrow.isEnabled());
                    stale.queryChanged(query(f.id).withText("obsolete"));assertEquals("",f.workspace.state().query.text());
                    f.workspace.refresh();return null;
                });gate.release.countDown();f.awaitAdoption(before);
                edt(()->{f.client.assertOriginalState();return null;});
            }finally{gate.release.countDown();}

            Gate closing=new Gate();f.client.nextRead.set(closing);
            try {
                edt(()->{f.workspace.refresh();return null;});assertTrue(closing.entered.await(5,TimeUnit.SECONDS));
                edt(()->{f.workspace.close();SwingUtilities.invokeLater(()->new JPanel().add(f.client.cached));return null;});
                closing.release.countDown();
                edt(()->{assertFalse(f.client.combo.isEnabled());assertFalse(f.client.arrow.isEnabled());return null;});
            }finally{closing.release.countDown();}
        }
    }

    private final class Fixture implements AutoCloseable {
        final Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();
        final String id=session(root,4);
        final SessionStore store=new SessionStore(root,false,"compound-fixture");
        final PreferencesStore preferences=new PreferencesStore(temp.newFolder().toPath().resolve("views.properties"));
        final CachedClient client;
        final ArchiveWorkspace<Event,Facets,Sort> workspace;
        Fixture()throws Exception {
            preferences.preload();client=edt(()->new CachedClient(id,scratch));
            workspace=edt(()->SessionPanel.queried(store,"compound",new JLabel("Live"),client,ViewStateStore.preferences(preferences)));
            edt(()->{workspace.showSaved();return null;});awaitAdoption(0);
        }
        void awaitAdoption(int before)throws Exception{await(()->{
            if(client.adoptionFailure!=null)throw new AssertionError("Asynchronous hierarchy adoption failed",client.adoptionFailure);
            return !workspace.loading()&&client.adoptions>before&&SwingUtilities.isDescendingFrom(client.cached,client.view);
        });}
        public void close()throws Exception {
            edt(()->{workspace.close();return null;});store.close();preferences.shutdown(5,TimeUnit.SECONDS,message->{});
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(children(scratch)!=0&&System.nanoTime()<end)Thread.sleep(20);
            assertEquals("Disposed readers release their scratch files",0,children(scratch));
        }
    }
    private static final class CachedClient implements ArchiveClient<Event,Facets,Sort> {
        final String scope;final Path scratch;
        final JPanel cached=new JPanel();
        final JComboBox<String> combo=new JComboBox<>(new String[]{"First","Second"});
        final JButton arrow;
        final List<String> hierarchyOrder=new ArrayList<>();
        final AtomicReference<Gate> nextRead=new AtomicReference<>();
        JPanel view,host;int adoptions;Throwable adoptionFailure;
        Binding<Facets,Sort> binding;
        CachedClient(String scope,Path scratch) {
            this.scope=scope;this.scratch=scratch;combo.setUI(new BasicComboBoxUI());
            arrow=Arrays.stream(combo.getComponents()).filter(c->c instanceof JButton).map(c->(JButton)c).findFirst().orElseThrow(AssertionError::new);
            arrow.setEnabled(false);cached.add(combo);
            HierarchyListener observe=e->{if((e.getChangeFlags()&HierarchyEvent.PARENT_CHANGED)!=0&&host!=null&&SwingUtilities.isDescendingFrom(cached,host))
                hierarchyOrder.add(e.getComponent()==arrow?"arrow":"combo");};
            arrow.addHierarchyListener(observe);combo.addHierarchyListener(observe);
        }
        public ArchiveQuery<Facets,Sort> initialQuery(){return query(scope);}
        public Path scratchDirectory(){return scratch;}
        public int pageSize(){return 2;}
        public ArchiveAdapter<Event,Facets,Sort> adapter(ArchiveQuery<Facets,Sort> query) {
            return ArchiveAdapter.records("chat",Event.class,"messages",r->r.time,(r,q)->{
                Gate gate=nextRead.getAndSet(null);if(gate!=null){gate.entered.countDown();gate.waitForRelease();}return true;
            },s->Comparator.comparingInt(r->r.value));
        }
        public JComponent render(ArchivePage<Event> page,ViewState<Facets,Sort> state,Binding<Facets,Sort> binding) {
            this.binding=binding;view=new JPanel();host=new JPanel();view.add(host);JPanel adoptedHost=host;
            // Mirrors a detail reader publishing its reused component after the workspace applies the page.
            SwingUtilities.invokeLater(()->{
                try{hierarchyOrder.clear();adoptedHost.add(cached);adoptions++;}
                catch(Throwable failure){adoptionFailure=failure;}
            });return view;
        }
        void assertOriginalState() {
            assertTrue("Cached parent remains enabled",cached.isEnabled());assertTrue("Combo remains enabled",combo.isEnabled());
            assertTrue("Fixture exercised child-first JDK hierarchy delivery: "+hierarchyOrder,
                    hierarchyOrder.indexOf("arrow")>=0&&hierarchyOrder.indexOf("combo")>hierarchyOrder.indexOf("arrow"));
            assertFalse("An enabled combo must not override its intentionally disabled arrow",arrow.isEnabled());
        }
    }
    private static final class Gate {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        void waitForRelease(){try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Blocked reader was not released");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}
    }
    @FunctionalInterface private interface Checked<T>{T get()throws Exception;}
    private static <T>T edt(Checked<T> work)throws Exception{AtomicReference<T> value=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{try{value.set(work.get());}catch(Throwable failure){error.set(failure);}});if(error.get()!=null)throw new AssertionError(error.get());return value.get();}
    private static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){if(edt(condition::getAsBoolean))return;Thread.sleep(20);}fail("Archive view did not settle");}
    private static JButton button(Container root,String text){for(Component child:root.getComponents()){
        if(child instanceof JButton&&text.equals(((JButton)child).getText()))return (JButton)child;
        if(child instanceof Container){JButton found=button((Container)child,text);if(found!=null)return found;}
    }return null;}
}
