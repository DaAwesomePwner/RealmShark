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
                    ScopeChip scope=ArchiveNativeSupport.scope(workspace);
                    assertEquals("Scope: Live",scope.getText());assertNotNull(ArchiveNativeSupport.scopeItem(workspace,"current"));
                    assertNull("The scope ⟳ icon is gone",find(workspace,KitButton.class,b->"Refresh".equals(b.getAccessibleContext().getAccessibleName())));
                    OverflowMenu more=ArchiveNativeSupport.more(workspace);
                    assertFalse("⋯ Refresh belongs to saved history",more.item("Refresh").isVisible());
                    assertNull("History library… moved into the Scope menu",more.item("History library…"));
                    assertEquals("History library…",ArchiveNativeSupport.scopeItem(workspace,"library").getText());
                    for(String label:new String[]{"Saved views","Save current view…","Delete view…","Reset saved state",
                            "Export selected…","Export page…","Export all matches…","Open export folder"})assertNotNull(label,more.item(label));
                    assertFalse(more.item("Export all matches…").isEnabled());assertFalse(more.item("Open export folder").isEnabled());
                    assertFalse("Saved views follow saved-history mode",more.item("Saved views").isEnabled());
                    workspace.showSaved();return null;});
                await(()->ArchiveNativeSupport.ready(workspace));
                edt(()->{
                    OverflowMenu more=ArchiveNativeSupport.more(workspace);FilterBar bar=workspace.filterBar();
                    assertTrue(named(workspace,"facet-bar-archive-footer").isVisible());assertTrue(named(workspace,"facet-bar-history-search").isVisible());
                    String scope=ArchiveNativeSupport.scope(workspace).getText();
                    assertTrue("The chip reads saved history of the fixture's session: "+scope,scope.startsWith("Scope: Saved · "));
                    assertTrue("⋯ Refresh shows in saved history",more.item("Refresh").isVisible());
                    assertEquals("facet-bar-refresh",more.item("Refresh").getName());
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
                edt(()->{
                    assertTrue("Text-only queries expose Clear",named(workspace,"facet-bar-clear-filters").isVisible());
                    ArchiveNativeSupport.removeChip(workspace.filterBar(),"Search active");return null;});
                await(()->ArchiveNativeSupport.ready(workspace)&&workspace.displayedPage().matches==120);
                edt(()->{workspace.changeQuery(workspace.state().query.withText("no matching synthetic message"));return null;});
                await(()->ArchiveNativeSupport.ready(workspace)&&workspace.displayedPage().matches==0);
                edt(()->{((AbstractButton)named(workspace,"facet-bar-clear-filters")).doClick();return null;});
                await(()->ArchiveNativeSupport.ready(workspace)&&workspace.displayedPage().matches==120);
                edt(()->{ArchiveNativeSupport.action(workspace,"Load: Everything").doClick();return null;});
                await(()->ArchiveNativeSupport.ready(workspace)&&workspace.displayedPage().matches==120);
            }finally{edt(()->{workspace.close();return null;});}
        }
    }

    // ---- The Scope chip moves between the live panel's bar and the workspace's (P6b) ----

    @Test public void theLiveHostsBarCarriesTheChipAndSavedHistoryTakesItBack()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String id=session(root,120);ArchiveNativeSupport.Memory memory=new ArchiveNativeSupport.Memory();
        try(SessionStore store=new SessionStore(root,false,"test")){
            FakeHost host=edt(FakeHost::new);List<Object> flips=new ArrayList<>();JLabel lead=edt(()->new JLabel("Lead"));
            ArchiveWorkspace<Event,Facets,Sort> workspace=edt(()->{
                ArchiveWorkspace<Event,Facets,Sort> value=SessionPanel.queried(store,"host-bar",host,new Faceted(id,scratch),memory.states);
                value.addPropertyChangeListener(ArchiveWorkspace.ARCHIVE,e->flips.add(e.getNewValue()));return value;});
            JFrame frame=edt(()->frame(workspace,1240));
            try{
                await(()->workspace.isShowing()&&host.bar.getWidth()>0);
                edt(()->{
                    ScopeChip chip=ArchiveNativeSupport.scope(workspace);
                    assertSame(host.bar,workspace.liveFilterBar());
                    assertTrue("Live: the chip is in the host bar",SwingUtilities.isDescendingFrom(chip,host.bar));
                    assertNotSame("…in its scope slot, not its search slot",host.bar.searchSlot(),chip.getParent());
                    assertFalse("The workspace bar is not showing while live",workspace.filterBar().isShowing());
                    assertTrue(chip.isShowing());tomato.gui.kit.FilterBarAssert.assertChipInVisibleBar(workspace);
                    tomato.gui.kit.FilterBarAssert.assertOneRow(host.bar);
                    workspace.lead(lead);
                    assertSame("lead() sits first in the live bar",lead,host.bar.searchSlot().getComponent(0));
                    workspace.showSaved();return null;});
                await(()->ArchiveNativeSupport.ready(workspace));
                edt(()->{
                    ScopeChip chip=ArchiveNativeSupport.scope(workspace);
                    assertEquals(Collections.singletonList(true),flips);
                    assertTrue("Saved: the chip is in the workspace bar",SwingUtilities.isDescendingFrom(chip,workspace.filterBar()));
                    assertFalse(SwingUtilities.isDescendingFrom(chip,host.bar));
                    assertSame("lead() sits first in the saved bar",lead,workspace.filterBar().searchSlot().getComponent(0));
                    workspace.getRootPane().validate();
                    assertTrue(workspace.filterBar().isShowing());assertTrue(chip.isShowing());tomato.gui.kit.FilterBarAssert.assertChipInVisibleBar(workspace);
                    tomato.gui.kit.FilterBarAssert.assertOneRow(workspace.filterBar());
                    workspace.showSaved();
                    assertEquals("No flip, no event",1,flips.size());
                    ArchiveNativeSupport.scopeItem(workspace,"live").doClick();
                    assertFalse(workspace.state().archive);assertEquals(Arrays.asList(true,false),flips);
                    assertTrue("Live again: the chip is back in the host bar",SwingUtilities.isDescendingFrom(chip,host.bar));
                    assertSame(lead,host.bar.searchSlot().getComponent(0));
                    workspace.showSaved(ArchiveQuery.CURRENT);
                    assertTrue("showSaved(CURRENT) is saved-current",workspace.state().archive);
                    assertEquals(ArchiveQuery.CURRENT,workspace.state().query.scope());
                    assertEquals("Scope: Saved · this session",chip.getText());
                    workspace.selectSession(store.currentId());
                    assertFalse("selectSession(CURRENT) still goes live",workspace.state().archive);
                    assertEquals(ArchiveQuery.CURRENT,workspace.state().query.scope());
                    assertEquals(Arrays.asList(true,false,true,false),flips);
                    host.swap(null);
                    assertNull(workspace.liveFilterBar());
                    assertTrue("A tab without a bar: the workspace row shows the chip",SwingUtilities.isDescendingFrom(chip,workspace.filterBar()));
                    assertTrue(workspace.filterBar().isVisible());
                    FilterBar next=FakeHost.bar("fake-next");host.swap(next);
                    assertTrue("The host's new bar takes the chip",SwingUtilities.isDescendingFrom(chip,next));
                    assertFalse(workspace.filterBar().isVisible());
                    workspace.lead(null);assertNull("lead(null) clears it",lead.getParent());
                    tomato.gui.kit.FilterBarAssert.assertChipInVisibleBar(workspace);return null;});
            }finally{edt(()->{frame.dispose();workspace.close();return null;});}
        }
    }

    @Test public void narrowBarsTakeTheChipIntoTheirSearchSlot()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String id=session(root,20);ArchiveNativeSupport.Memory memory=new ArchiveNativeSupport.Memory();
        try(SessionStore store=new SessionStore(root,false,"test")){
            FakeHost host=edt(FakeHost::new);
            ArchiveWorkspace<Event,Facets,Sort> workspace=edt(()->SessionPanel.queried(store,"narrow-bar",host,new Faceted(id,scratch),memory.states));
            JFrame frame=edt(()->frame(workspace,420));
            try{
                await(()->ArchiveNativeSupport.scope(workspace).getParent()==host.bar.searchSlot());
                edt(()->{tomato.gui.kit.FilterBarAssert.assertChipInVisibleBar(workspace);workspace.showSaved();return null;});
                await(()->ArchiveNativeSupport.ready(workspace)&&ArchiveNativeSupport.scope(workspace).getParent()==workspace.filterBar().searchSlot());
                edt(()->{tomato.gui.kit.FilterBarAssert.assertChipInVisibleBar(workspace);frame.setSize(1240,800);frame.validate();return null;});
                await(()->{Container parent=ArchiveNativeSupport.scope(workspace).getParent();
                    return parent!=workspace.filterBar().searchSlot()&&SwingUtilities.isDescendingFrom(parent,workspace.filterBar());});
            }finally{edt(()->{frame.dispose();workspace.close();return null;});}
        }
    }

    @Test public void restoresWhileHiddenPlaceTheChipOnceShown()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String id=session(root,20);ArchiveNativeSupport.Memory memory=new ArchiveNativeSupport.Memory();
        try(SessionStore store=new SessionStore(root,false,"test")){
            FakeHost host=edt(FakeHost::new);
            ArchiveWorkspace<Event,Facets,Sort> workspace=edt(()->SessionPanel.queried(store,"hidden-bar",host,new Faceted(id,scratch),memory.states));
            JFrame frame=edt(()->{JFrame value=new JFrame("Hidden");value.setContentPane(workspace);value.setSize(1240,800);return value;});
            try{
                edt(()->{
                    assertFalse(workspace.isShowing());
                    workspace.restore(workspace.state().withArchive(true));
                    tomato.gui.kit.FilterBarAssert.assertChipInVisibleBar(workspace);
                    workspace.restore(workspace.state().withArchive(false));
                    assertTrue("Placed while hidden",SwingUtilities.isDescendingFrom(ArchiveNativeSupport.scope(workspace),host.bar));
                    frame.setVisible(true);return null;});
                await(()->workspace.isShowing()&&host.bar.getWidth()>0);
                edt(()->{
                    assertTrue("…and shown where it was placed",ArchiveNativeSupport.scope(workspace).isShowing());
                    assertFalse(workspace.filterBar().isShowing());tomato.gui.kit.FilterBarAssert.assertChipInVisibleBar(workspace);
                    frame.setVisible(false);workspace.restore(workspace.state().withArchive(true));return null;});
                await(()->ArchiveNativeSupport.ready(workspace));
                edt(()->{tomato.gui.kit.FilterBarAssert.assertChipInVisibleBar(workspace);frame.setVisible(true);return null;});
                await(()->workspace.isShowing()&&ArchiveNativeSupport.scope(workspace).isShowing());
                edt(()->{assertTrue(workspace.filterBar().isShowing());tomato.gui.kit.FilterBarAssert.assertChipInVisibleBar(workspace);return null;});
            }finally{edt(()->{frame.dispose();workspace.close();return null;});}
        }
    }

    @Test public void aKeyboardPickKeepsFocusOnTheChipInItsNewBar()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String id=session(root,20);ArchiveNativeSupport.Memory memory=new ArchiveNativeSupport.Memory();
        try(SessionStore store=new SessionStore(root,false,"test")){
            FakeHost host=edt(FakeHost::new);
            ArchiveWorkspace<Event,Facets,Sort> workspace=edt(()->SessionPanel.queried(store,"focus-bar",host,new Faceted(id,scratch),memory.states));
            JFrame frame=edt(()->frame(workspace,1240));
            try{
                await(()->workspace.isShowing()&&host.bar.getWidth()>0);
                ScopeChip chip=edt(()->ArchiveNativeSupport.scope(workspace));
                edt(()->{frame.toFront();frame.requestFocus();return null;});
                await(()->java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow()==frame);
                edt(chip::requestFocusInWindow);
                await(chip::isFocusOwner);
                post(chip,java.awt.event.KeyEvent.VK_ENTER);
                await(()->chip.menu().isVisible()&&selectedItem()==chip.item("live"));
                post(chip,java.awt.event.KeyEvent.VK_DOWN);
                await(()->selectedItem()==chip.item("current"));
                post(chip,java.awt.event.KeyEvent.VK_ENTER);
                await(()->workspace.state().archive&&SwingUtilities.isDescendingFrom(chip,workspace.filterBar()));
                edt(()->{assertEquals("This session is saved-current",ArchiveQuery.CURRENT,workspace.state().query.scope());return null;});
                await(chip::isFocusOwner);
            }finally{edt(()->{MenuSelectionManager.defaultManager().clearSelectedPath();frame.dispose();workspace.close();return null;});}
        }
    }
    private static MenuElement selectedItem(){MenuElement[] path=MenuSelectionManager.defaultManager().getSelectedPath();return path.length==0?null:path[path.length-1];}
    /** A posted key press and release, delivered to the focus owner as a real key is. */
    private static void post(Component source,int code)throws Exception{
        long when=System.currentTimeMillis();char typed=code==java.awt.event.KeyEvent.VK_ENTER?'\n':java.awt.event.KeyEvent.CHAR_UNDEFINED;
        java.awt.EventQueue queue=java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue();
        queue.postEvent(new java.awt.event.KeyEvent(source,java.awt.event.KeyEvent.KEY_PRESSED,when,0,code,typed));
        queue.postEvent(new java.awt.event.KeyEvent(source,java.awt.event.KeyEvent.KEY_RELEASED,when,0,code,typed));
    }

    /** A live panel with its own filter row (a WrapRow search slot), as the archive pages' live views host the chip. */
    private static final class FakeHost extends JPanel implements LiveFilterHost {
        FilterBar bar=bar("fake-live");
        FakeHost(){super(new java.awt.BorderLayout());add(bar,java.awt.BorderLayout.NORTH);add(new JLabel("Live rows"),java.awt.BorderLayout.CENTER);}
        static FilterBar bar(String name){return new FilterBar(name).search(new WrapRow(new JTextField(12)));}
        public FilterBar liveFilterBar(){return bar;}
        void swap(FilterBar next){
            FilterBar old=bar;if(old!=null)remove(old);bar=next;if(next!=null)add(next,java.awt.BorderLayout.NORTH);revalidate();firePropertyChange(BAR,old,next);
        }
    }
    private static JFrame frame(JComponent content,int width){
        JFrame frame=new JFrame("Scope");frame.setContentPane(content);frame.setSize(width,800);frame.setVisible(true);return frame;
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
