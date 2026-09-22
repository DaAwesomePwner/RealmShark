package tomato.gui.history;

import tomato.history.SessionStore;
import tomato.history.archive.*;
import tomato.gui.activity.SnapshotRefresh;
import util.PreferencesStore;
import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static tomato.history.archive.ArchiveFixtures.*;

/** Synthetic reference client: real pin/query/paging/state/export path, no native windows or focus. */
public class ArchiveWorkspaceTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void innerFacetsAndSortReachBeyondFirstPageThenRestoreIndependentNamedState()throws Exception{
        assertTrue(GraphicsEnvironment.isHeadless());
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath(),output=temp.newFolder().toPath();String id=session(root,12005);
        PreferencesStore preferences=new PreferencesStore(temp.getRoot().toPath().resolve("state.properties"));preferences.preload();ViewStateStore states=ViewStateStore.preferences(preferences);
        SessionStore store=new SessionStore(root,false,"test");Reference client=new Reference(id,scratch);
        ArchiveWorkspace<Event,Facets,Sort> workspace=edt(()->SessionPanel.queried(store,"reference",new JLabel("Live producer"),client,states));
        ArchiveWorkspace<Event,Facets,Sort> other=edt(()->SessionPanel.queried(store,"other",new JLabel("Other live producer"),new Reference(id,scratch),states));
        try{
            edt(()->{workspace.showSaved();return null;});await(()->workspace.displayedPage()!=null&&!workspace.loading());
            assertEquals(12005,edt(()->workspace.displayedPage().matches).longValue());
            edt(()->{client.minimum.setValue(11000);return null;});await(()->!workspace.loading()&&workspace.displayedPage().matches==1005);
            edt(()->{client.table.setColumnSelectionInterval(0,0);client.table.getActionMap().get("archive-sort-descending").actionPerformed(null);return null;});
            await(()->!workspace.loading()&&workspace.displayedPage().rows.get(0).value.value==12004);
            assertNull(edt(()->client.table.getRowSorter()));
            edt(()->{workspace.selectPage(1);return null;});await(()->!workspace.loading()&&workspace.displayedPage().page==1);
            assertEquals(5,edt(()->workspace.displayedPage().rows.size()).intValue());
            ArchiveRow.Ref ref=edt(()->workspace.displayedPage().rows.get(2).ref);
            edt(()->{client.table.setRowSelectionInterval(2,2);client.binding.viewChanged(workspace.state().withPosition("events",Collections.singletonList(ref),ref,4));return null;});
            edt(()->workspace.saveNamed("Older exact matches")).toCompletableFuture().get();preferences.flush().toCompletableFuture().get();
            assertFalse(edt(()->other.state().archive));assertEquals(0,edt(()->other.state().query.facets().minimum).intValue());
            SwingWorker<Path,Void> export=edt(()->workspace.exportTo(output,"selected",ExportSelection.selected(Collections.singleton(ref)),ArchiveExport.Format.JSON));
            assertTrue(Files.exists(export.get(10,TimeUnit.SECONDS)));
            ViewState<Facets,Sort> remembered=edt(workspace::state);edt(()->{workspace.close();return null;});
            Reference reopenedClient=new Reference(id,scratch);ArchiveWorkspace<Event,Facets,Sort> reopened=edt(()->SessionPanel.queried(store,"reference",new JLabel("Live again"),reopenedClient,states));
            try{
                await(()->!reopened.loading()&&reopened.displayedPage()!=null);
                assertEquals(remembered.toJson(),edt(reopened::state).toJson());assertEquals(2,edt(()->reopenedClient.table.getSelectedRow()).intValue());
                edt(()->{reopened.changeQuery(query(id));return null;});await(()->!reopened.loading()&&reopened.displayedPage().matches==12005);
                edt(()->{reopened.loadNamed("Older exact matches");return null;});await(()->!reopened.loading()&&reopened.displayedPage().matches==1005);
                assertEquals(1,edt(()->reopened.displayedPage().page).longValue());
            }finally{edt(()->{reopened.close();return null;});}
        }finally{edt(()->{workspace.close();other.close();return null;});store.close();preferences.shutdown(5,TimeUnit.SECONDS,message->{});}
        awaitOffEdt(()->{try{return children(scratch)==0;}catch(Exception failure){throw new RuntimeException(failure);}});
    }
    @Test public void typedColumnsPresetsNamesCopyAndKeyboardDetailsAreUsableHeadlessly()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String id=session(root,10);
        try(SessionStore store=new SessionStore(root,false,"test");ArchiveResult<Event> result=ArchiveResult.open(store,query(id),adapter(),scratch,new Cancellation())){
            ArchivePage<Event> page=result.page(0,10,new Cancellation());AtomicReference<ArchiveQuery<Facets,Sort>> changed=new AtomicReference<>();AtomicReference<ArchiveRow<Event>> details=new AtomicReference<>();
            edt(()->{
                JTable table=HistoryTables.queried("synthetic-evidence",Reference.columns(),page,Collections.singletonMap("value",Sort.VALUE),query(id),changed::set,details::set);
                assertEquals("synthetic evidence",table.getAccessibleContext().getAccessibleName());assertEquals(Integer.class,table.getColumnClass(0));
                table.setRowSelectionInterval(4,4);table.getActionMap().get("archive-details").actionPerformed(null);assertEquals(4,details.get().value.value);
                assertTrue(HistoryTables.selectedText(table).contains("Message 4"));
                ViewState.Table defaults=HistoryTables.columnState(table,"Default");HistoryTables.applyColumns(table,new ViewState.Table("Compact",Arrays.asList(new ViewState.Column("value",110,true),new ViewState.Column("text",180,false))));
                assertEquals(1,table.getColumnCount());HistoryTables.applyColumns(table,defaults);assertEquals(2,table.getColumnCount());
                table.getActionMap().get("archive-sort-descending").actionPerformed(null);assertEquals(ArchiveQuery.Direction.DESCENDING,changed.get().order().get(0).direction);
                assertEquals(0,table.getValueAt(0,0));return null;
            });
        }
    }
    @Test public void libraryShowsBadEntryAndRetainsSelectionAcrossRefresh()throws Exception{
        Path root=temp.newFolder().toPath();String id=session(root,1),bad=session(root,1);Files.write(root.resolve(bad).resolve("session.json"),new byte[]{'{'});
        try(SessionStore store=new SessionStore(root,false,"test")){
            AtomicReference<String> opened=new AtomicReference<>();HistoryLibrary library=edt(()->new HistoryLibrary(store,opened::set));
            try{
                await(()->library.entries().size()==3);edt(()->{library.select(id);library.reload();return null;});
                await(()->id.equals(library.selectedId()));assertEquals(1,edt(()->library.entries().stream().filter(e->!e.readable()).count()).longValue());
                edt(()->{JTable table=find(library,JTable.class);table.getActionMap().get("open-session").actionPerformed(null);return null;});assertEquals(id,opened.get());
                String remembered=edt(library::rememberedSelection);HistoryLibrary reopened=edt(()->new HistoryLibrary(store,opened::set,remembered));
                try{await(()->id.equals(reopened.selectedId()));}finally{edt(()->{reopened.close();return null;});}
            }finally{edt(()->{library.close();return null;});}
        }
    }
    @Test public void invalidatedReadDisposesItsResultAndKeepsOnlyTheLatestUi()throws Exception{
        SnapshotRefresh<String> refresh=new SnapshotRefresh<>();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);AtomicInteger disposed=new AtomicInteger();List<String> applied=new ArrayList<>();
        edt(()->{refresh.request("old",()->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}return "old";},applied::add,e->{throw new AssertionError(e);},value->disposed.incrementAndGet());return null;});
        assertTrue(entered.await(3,TimeUnit.SECONDS));
        edt(()->{refresh.invalidate();refresh.request("new",()->"new",value->{applied.add(value);done.countDown();},e->{throw new AssertionError(e);},value->disposed.incrementAndGet());return null;});
        release.countDown();assertTrue(done.await(3,TimeUnit.SECONDS));assertEquals(1,disposed.get());assertEquals(Collections.singletonList("new"),applied);
    }
    @Test public void cancellingExportFutureDoesNotReleaseFilesWhileItsWriterStillUsesThem()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath(),output=temp.newFolder().toPath();String id=session(root,20);
        PreferencesStore preferences=new PreferencesStore(temp.getRoot().toPath().resolve("cancel.properties"));preferences.preload();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Reference client=new Reference(id,scratch){public List<ArchiveExport.Column<Event>> exportColumns(){return Collections.singletonList(new ArchiveExport.Column<>("Value",event->{
            entered.countDown();try{release.await();}catch(InterruptedException failure){Thread.currentThread().interrupt();}return event.value;
        }));}};
        try(SessionStore store=new SessionStore(root,false,"test")){
            ArchiveWorkspace<Event,Facets,Sort> workspace=edt(()->SessionPanel.queried(store,"cancel",new JLabel("live"),client,ViewStateStore.preferences(preferences)));
            try{
                edt(()->{workspace.showSaved();return null;});await(()->workspace.displayedPage()!=null&&!workspace.loading());
                SwingWorker<Path,Void> worker=edt(()->workspace.exportTo(output,"cancelled",ExportSelection.all(),ArchiveExport.Format.CSV));
                assertTrue(entered.await(3,TimeUnit.SECONDS));assertTrue(worker.cancel(false));
                edt(()->{workspace.close();return null;});assertTrue("running writer retains its lease",children(scratch)>0);
                release.countDown();awaitOffEdt(()->{try{return children(scratch)==0&&children(output)==0;}catch(Exception e){throw new RuntimeException(e);}});
            }finally{release.countDown();edt(()->{workspace.close();return null;});}
        }finally{preferences.shutdown(5,TimeUnit.SECONDS,message->{});}
    }
    @Test public void originalConstructorAndFourArgumentLoaderStillLoadOffEdt()throws Exception{
        Path root=temp.newFolder().toPath();session(root,3);
        try(SessionStore store=new SessionStore(root,false,"test")){
            AtomicBoolean onWorker=new AtomicBoolean();SessionPanel panel=edt(()->new SessionPanel(store,"legacy",new JLabel("live"),(history,scope,page,text)->{
                onWorker.set(!SwingUtilities.isEventDispatchThread());List<Event> values=history.read(scope,"chat",Event.class);
                return new SessionPanel.Loaded(()->{JLabel label=new JLabel("loaded "+values.size());label.setName("legacy-loaded");return label;},false,"legacy");
            }));
            edt(()->{panel.selectSession(SessionStore.ALL);return null;});
            await(()->named(panel,"legacy-loaded")!=null);assertTrue(onWorker.get());
            assertEquals("loaded 3",edt(()->((JLabel)named(panel,"legacy-loaded")).getText()));
        }
    }
    private static class Reference implements ArchiveClient<Event,Facets,Sort> {
        final String scope;final Path scratch;JSpinner minimum;JTable table;Binding<Facets,Sort> binding;
        Reference(String scope,Path scratch){this.scope=scope;this.scratch=scratch;}
        public ArchiveQuery<Facets,Sort> initialQuery(){return query(scope);}
        public Path scratchDirectory(){return scratch;}
        public ArchiveAdapter<Event,Facets,Sort> adapter(ArchiveQuery<Facets,Sort> query){return ArchiveFixtures.adapter();}
        static List<HistoryTables.Column<Event,?>> columns(){return Arrays.asList(new HistoryTables.Column<>("value","Value",Integer.class,e->e.value,null),new HistoryTables.Column<>("text","Message",String.class,e->e.text,null));}
        public JComponent render(ArchivePage<Event> page,ViewState<Facets,Sort> state,Binding<Facets,Sort> binding){
            assertTrue(SwingUtilities.isEventDispatchThread());this.binding=binding;JPanel panel=new JPanel(new BorderLayout());
            minimum=new JSpinner(new SpinnerNumberModel(state.query.facets().minimum,0,20000,1));minimum.getAccessibleContext().setAccessibleName("Minimum value across archive");
            minimum.addChangeListener(e->binding.queryChanged(state.query.withFacets(new Facets((Integer)minimum.getValue()))));
            table=HistoryTables.queried("reference-messages",columns(),page,Collections.singletonMap("value",Sort.VALUE),state.query,binding::queryChanged,row->{});
            JScrollPane scroll=new JScrollPane(table);panel.add(minimum,BorderLayout.NORTH);panel.add(scroll);HistoryTables.restorePosition(table,scroll,page,state);
            return panel;
        }
    }
    @FunctionalInterface private interface Checked<T>{T get()throws Exception;}
    private static <T> T edt(Checked<T> value)throws Exception{
        AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{try{result.set(value.get());}catch(Throwable t){failure.set(t);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());return result.get();
    }
    private static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){if(edt(condition::getAsBoolean))return;Thread.sleep(20);}fail("Timed out waiting for EDT state");}
    private static void awaitOffEdt(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(20);}fail("Timed out waiting for cleanup");}
    private static <T> T find(Container root,Class<T> type){for(Component child:root.getComponents()){if(type.isInstance(child))return type.cast(child);if(child instanceof Container){T found=find((Container)child,type);if(found!=null)return found;}}return null;}
    private static Component named(Container root,String name){for(Component child:root.getComponents()){if(name.equals(child.getName()))return child;if(child instanceof Container){Component found=named((Container)child,name);if(found!=null)return found;}}return null;}
}
