package tomato.gui.activity;

import java.awt.*;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.history.*;
import tomato.gui.kit.FilterBar;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveQuery;
import static org.junit.Assert.*;

/** Saved Runs facets live in the workspace Filters drawer and read back as removable chips. */
public class ActivityArchiveFiltersTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();

    @Test public void runFacetsMoveToTheDrawerAndBecomeRemovableChips()throws Exception{
        Path scratch=temp.newFolder().toPath();ArchiveNativeSupport.Memory memory=new ArchiveNativeSupport.Memory();
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic")){
            for(int i=0;i<3;i++){ActivityJournal.Visit v=new ActivityJournal.Visit();v.id="visit-"+i;v.map="Lost Halls";v.started=1000+i*100_000L;v.lastSeen=v.ended=v.started+60_000;store.put("runs",v.id,v);}
            store.flush();
            ArchiveWorkspace<ActivityQueries.Row,ActivityQueries.Filters,ActivityQueries.Sort> workspace=edt(()->ActivityPanel.workspace(store,new JLabel("Live runs"),ActivityPanel.Mode.RUNS,scratch,memory.states));
            try{
                edt(()->{workspace.showSaved();return null;});await(()->ArchiveNativeSupport.ready(workspace));
                edt(()->{
                    FilterBar bar=workspace.filterBar();JComponent drawer=bar.drawerContent();Container view=(Container)named(workspace,"activity-archive-runs");
                    assertNotNull("Run facets live in the Filters drawer",drawer);assertFalse(bar.drawerOpen());
                    assertNotNull(find(drawer,AbstractButton.class,b->b.getText()!=null&&b.getText().startsWith("Outcome: ")));
                    assertNotNull(find(drawer,AbstractButton.class,b->"Date bounds…".equals(b.getText())));
                    assertNull("The view no longer repeats the facet row",find(view,AbstractButton.class,b->"Date bounds…".equals(b.getText())));
                    assertEquals(0,bar.activeCount());
                    ActivityQueries.Filters f=new ActivityQueries.Filters();f.outcomes.add(ActivityQueries.Outcome.COMPLETED);f.timingGaps=ActivityQueries.Presence.PRESENT;
                    f.minimumDurationMillis=30_000L;f.visitSession=store.currentId();f.visitId="visit-1";
                    workspace.changeQuery(workspace.state().query.withFacets(f).withBounds(new ArchiveQuery.Bounds(0L,10_000_000L,ZoneId.of("UTC"),ArchiveQuery.TimeMode.ENTRY,false)));return null;});
                await(()->ArchiveNativeSupport.ready(workspace));
                edt(()->{
                    assertEquals(Arrays.asList("Outcome: Completed","With timing gaps","Duration ≥ 30 s","Exact visit","1970-01-01 00:00 – 1970-01-01 02:46"),
                        ArchiveNativeSupport.chipLabels(workspace.filterBar()));
                    Container view=(Container)named(workspace,"activity-archive-runs");
                    assertNotNull("The exact-link action stays beside the view",find(view,AbstractButton.class,b->"Clear exact visit link".equals(b.getText())));
                    ArchiveNativeSupport.removeChip(workspace.filterBar(),"Exact visit");return null;});
                await(()->ArchiveNativeSupport.ready(workspace)&&workspace.state().query.facets().visitId.isEmpty());
                edt(()->{
                    assertEquals(Collections.singleton(ActivityQueries.Outcome.COMPLETED),workspace.state().query.facets().outcomes);
                    assertEquals("",workspace.state().query.facets().visitSession);
                    ArchiveNativeSupport.removeChip(workspace.filterBar(),"1970-01-01 00:00 – 1970-01-01 02:46");return null;});
                await(()->ArchiveNativeSupport.ready(workspace)&&workspace.state().query.bounds().from==null);
                edt(()->{ArchiveQuery.Bounds b=workspace.state().query.bounds();assertNull(b.until);assertEquals("UTC",b.zone);assertFalse(b.includeUnknown);return null;});
            }finally{edt(()->{workspace.close();return null;});}
        }
    }

    @FunctionalInterface private interface Checked<T>{T get()throws Exception;}
    private static <T> T edt(Checked<T> value)throws Exception{
        AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{try{result.set(value.get());}catch(Throwable t){failure.set(t);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());return result.get();
    }
    private static void await(BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){if(edt(condition::getAsBoolean))return;Thread.sleep(20);}fail("Timed out waiting for EDT state");
    }
    private static Component named(Container root,String name){return find(root,Component.class,c->name.equals(c.getName()));}
    private static <T extends Component> T find(Container root,Class<T> type,Predicate<T> test){
        for(Component child:root.getComponents()){
            if(type.isInstance(child)&&test.test(type.cast(child)))return type.cast(child);
            if(child instanceof Container){T found=find((Container)child,type,test);if(found!=null)return found;}
        }return null;
    }
}
