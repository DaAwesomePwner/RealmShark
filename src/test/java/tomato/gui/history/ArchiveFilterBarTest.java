package tomato.gui.history;

// Explicit AWT imports: java.awt.* would make ArchiveFixtures.Event ambiguous with java.awt.Event.
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.KitButton;
import tomato.gui.kit.OverflowMenu;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import static org.junit.Assert.*;
import static tomato.history.archive.ArchiveFixtures.*;

/** The archive toolbar as one filter row, a ⋯ menu and a footer; synthetic history, no native window. */
public class ArchiveFilterBarTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();

    @Test public void toolbarMovesIntoTheFilterRowOverflowAndFooter()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String id=session(root,120);ArchiveNativeSupport.Memory memory=new ArchiveNativeSupport.Memory();
        try(SessionStore store=new SessionStore(root,false,"test")){
            Faceted client=new Faceted(id,scratch);
            ArchiveWorkspace<Event,Facets,Sort> workspace=edt(()->SessionPanel.queried(store,"facet-bar",new JLabel("Live"),client,memory.states));
            try{
                edt(()->{
                    assertEquals("facet-bar-filter-bar",workspace.filterBar().getName());
                    assertFalse("Saved-history search is hidden in the live view",named(workspace,"facet-bar-history-search").isVisible());
                    assertFalse("Paging and status belong to saved history",named(workspace,"facet-bar-archive-footer").isVisible());
                    assertNotNull(button(workspace,"Browse saved"));assertNotNull(named(workspace,"facet-bar-session-picker"));
                    KitButton refresh=find(workspace,KitButton.class,b->"Refresh".equals(b.getAccessibleContext().getAccessibleName()));
                    assertEquals(KitButton.Variant.ICON,refresh.variant());
                    OverflowMenu more=ArchiveNativeSupport.more(workspace);
                    for(String label:new String[]{"History library…","Saved views","Save current view…","Delete view…","Reset saved state",
                            "Export selected…","Export page…","Export all matches…","Open export folder"})assertNotNull(label,more.item(label));
                    assertFalse(more.item("Export all matches…").isEnabled());assertFalse(more.item("Open export folder").isEnabled());
                    assertFalse("Saved views follow saved-history mode",more.item("Saved views").isEnabled());
                    workspace.showSaved();return null;});
                await(()->ArchiveNativeSupport.ready(workspace));
                edt(()->{
                    OverflowMenu more=ArchiveNativeSupport.more(workspace);FilterBar bar=workspace.filterBar();
                    assertTrue(named(workspace,"facet-bar-archive-footer").isVisible());assertTrue(named(workspace,"facet-bar-history-search").isVisible());
                    assertNotNull(button(workspace,"Current live view"));
                    assertTrue(more.item("Export all matches…").isEnabled());assertTrue(more.item("Export page…").isEnabled());
                    assertFalse("Nothing selected yet",more.item("Export selected…").isEnabled());assertTrue(more.item("Saved views").isEnabled());
                    assertFalse(button(workspace,"Cancel read").isVisible());assertFalse(button(workspace,"Cancel export").isVisible());
                    assertFalse("Filters start collapsed",bar.drawerOpen());assertSame(client.drawer,bar.drawerContent());
                    assertSame("filters() receives render()'s binding",client.renderBinding,client.filtersBinding);
                    assertEquals(0,bar.activeCount());
                    client.minimum.setValue(100);
                    assertFalse("The drawer is disabled while its query loads",client.minimum.isEnabled());return null;});
                await(()->ArchiveNativeSupport.ready(workspace)&&workspace.displayedPage().matches==20);
                edt(()->{
                    assertTrue("The replacement drawer is enabled",client.minimum.isEnabled());
                    assertEquals(Collections.singletonList("Minimum 100"),ArchiveNativeSupport.chipLabels(workspace.filterBar()));
                    assertEquals("Filters · 1",((AbstractButton)named(workspace,"facet-bar-filters")).getText());
                    ((AbstractButton)named(workspace,"facet-bar-clear-filters")).doClick();return null;});
                await(()->ArchiveNativeSupport.ready(workspace)&&workspace.displayedPage().matches==120);
                edt(()->{assertEquals(0,workspace.filterBar().activeCount());assertEquals("Clear keeps the scope",id,workspace.state().query.scope());
                    client.minimum.setValue(110);return null;});
                await(()->ArchiveNativeSupport.ready(workspace)&&workspace.displayedPage().matches==10);
                edt(()->{ArchiveNativeSupport.removeChip(workspace.filterBar(),"Minimum 110");return null;});
                await(()->ArchiveNativeSupport.ready(workspace)&&workspace.displayedPage().matches==120);
                edt(()->workspace.saveNamed("Everything")).toCompletableFuture().get(5,TimeUnit.SECONDS);
                edt(()->{assertTrue(ArchiveNativeSupport.action(workspace,"Delete view…").isEnabled());
                    workspace.changeQuery(workspace.state().query.withText("Message 1"));return null;});
                await(()->ArchiveNativeSupport.ready(workspace)&&workspace.displayedPage().matches==31);
                edt(()->{ArchiveNativeSupport.action(workspace,"Load: Everything").doClick();return null;});
                await(()->ArchiveNativeSupport.ready(workspace)&&workspace.displayedPage().matches==120);
            }finally{edt(()->{workspace.close();return null;});}
        }
    }

    private static final class Faceted implements ArchiveClient<Event,Facets,Sort> {
        final String scope;final Path scratch;JSpinner minimum;JPanel drawer;Binding<Facets,Sort> renderBinding,filtersBinding;
        Faceted(String scope,Path scratch){this.scope=scope;this.scratch=scratch;}
        public ArchiveQuery<Facets,Sort> initialQuery(){return query(scope);}
        public Path scratchDirectory(){return scratch;}
        public ArchiveAdapter<Event,Facets,Sort> adapter(ArchiveQuery<Facets,Sort> query){return ArchiveFixtures.adapter();}
        static List<HistoryTables.Column<Event,?>> columns(){return Arrays.asList(new HistoryTables.Column<>("value","Value",Integer.class,e->e.value,null));}
        public JComponent render(ArchivePage<Event> page,ViewState<Facets,Sort> state,Binding<Facets,Sort> binding){
            renderBinding=binding;
            return new JScrollPane(HistoryTables.queried("facet-bar-rows",columns(),page,Collections.singletonMap("value",Sort.VALUE),state.query,binding::queryChanged,row->{}));
        }
        @Override public ArchiveFilters filters(ArchivePage<Event> page,ViewState<Facets,Sort> state,Binding<Facets,Sort> binding){
            filtersBinding=binding;int value=state.query.facets().minimum;
            minimum=new JSpinner(new SpinnerNumberModel(value,0,20000,1));minimum.addChangeListener(e->binding.queryChanged(state.query.withFacets(new Facets((Integer)minimum.getValue()))));
            drawer=new JPanel();drawer.add(minimum);List<FilterBar.ActiveFilter> chips=new ArrayList<>();
            if(value>0)chips.add(new FilterBar.ActiveFilter("Minimum "+value,()->binding.queryChanged(state.query.withFacets(new Facets(0)))));
            return new ArchiveFilters(drawer,chips);
        }
    }
    @FunctionalInterface private interface Checked<T>{T get()throws Exception;}
    private static <T> T edt(Checked<T> value)throws Exception{
        AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{try{result.set(value.get());}catch(Throwable t){failure.set(t);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());return result.get();
    }
    private static void await(java.util.function.BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){if(edt(condition::getAsBoolean))return;Thread.sleep(20);}fail("Timed out waiting for EDT state");
    }
    private static Component named(Container root,String name){return find(root,Component.class,c->name.equals(c.getName()));}
    private static AbstractButton button(Container root,String text){return find(root,AbstractButton.class,b->text.equals(b.getText()));}
    private static <T extends Component> T find(Container root,Class<T> type,Predicate<T> test){
        for(Component child:root.getComponents()){
            if(type.isInstance(child)&&test.test(type.cast(child)))return type.cast(child);
            if(child instanceof Container){T found=find((Container)child,type,test);if(found!=null)return found;}
        }return null;
    }
}
