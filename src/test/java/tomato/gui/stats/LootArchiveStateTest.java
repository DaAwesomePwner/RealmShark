package tomato.gui.stats;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.history.*;
import tomato.gui.stats.LootQuery.*;
import tomato.history.*;
import tomato.history.archive.*;
import util.PreferencesStore;
import static org.junit.Assert.*;
import static tomato.gui.history.SessionPanelTest.named;
import static tomato.gui.stats.LootArchiveQueryTest.*;

/** Component-model actions only: no windows, focus, clipboard, audio or capture. */
public class LootArchiveStateTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void queriedWorkspacePreservesNamedTabQuerySortSelectionColumnsAndIndependentScopes()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();PreferencesStore prefs=new PreferencesStore(temp.getRoot().toPath().resolve("views.properties"));prefs.preload();ViewStateStore states=ViewStateStore.preferences(prefs);
        try(SessionStore store=new SessionStore(root,true,"fixture")){
            for(int i=0;i<1103;i++)store.append("loot",drop(1000+i,"Ice Citadel","White","visit",item(42,"Item "+i,"WEAPON,UT","")));store.flush();
            ArchiveWorkspace<Row,Facets,Sort> workspace=edt(()->HistoricalStatistics.lootWorkspace(store,new LootDashboard(),scratch,states));
            ArchiveWorkspace<Row,Facets,Sort> other=edt(()->SessionPanel.queried(store,"statistics",new JLabel("Live stats"),new LootArchiveClient(scratch,true),states));
            try{
                edt(()->{workspace.showSaved();return null;});await(()->workspace.displayedPage()!=null&&!workspace.loading());
                assertEquals(1103,edt(()->workspace.displayedPage().matches).longValue());
                edt(()->{JTable table=named(workspace,"loot-archive-table",JTable.class);assertNull(table.getRowSorter());table.setColumnSelectionInterval(0,0);table.getActionMap().get("archive-sort-ascending").actionPerformed(null);return null;});
                await(()->!workspace.loading()&&workspace.displayedPage().rows.get(0).value.time==1000L);
                edt(()->{workspace.selectPage(1);return null;});await(()->!workspace.loading()&&workspace.displayedPage().page==1);
                edt(()->{JTable table=named(workspace,"loot-archive-table",JTable.class);table.setRowSelectionInterval(7,7);table.getColumnModel().getColumn(1).setWidth(277);return null;});
                await(()->workspace.state().tables.containsKey(View.OCCURRENCES.name()));
                ArchiveRow.Ref selected=edt(()->workspace.displayedPage().rows.get(7).ref);assertEquals(Collections.singletonList(selected),edt(()->workspace.state().selected));
                edt(()->workspace.saveNamed("Exact saved occurrence")).toCompletableFuture().get(5,TimeUnit.SECONDS);prefs.flush().toCompletableFuture().get();
                assertFalse(edt(()->other.state().archive));assertEquals(View.SESSIONS,edt(()->other.state().query.facets().view));
                edt(()->{JTabbedPane tabs=named(workspace,"loot-archive-tabs",JTabbedPane.class);tabs.setSelectedIndex(tabs.indexOfTab("By Bag"));return null;});await(()->!workspace.loading()&&workspace.state().query.facets().view==View.BAGS);
                assertEquals("bag-type summaries",edt(()->workspace.displayedPage().unit));
                edt(()->{workspace.loadNamed("Exact saved occurrence");return null;});await(()->!workspace.loading()&&workspace.state().query.facets().view==View.OCCURRENCES);
                assertEquals(1,edt(()->workspace.state().page).longValue());assertEquals(Collections.singletonList(selected),edt(()->workspace.state().selected));assertEquals(7,edt(()->named(workspace,"loot-archive-table",JTable.class).getSelectedRow()).intValue());
                edt(()->{workspace.close();return null;});
                ArchiveWorkspace<Row,Facets,Sort> reopened=edt(()->HistoricalStatistics.lootWorkspace(store,new LootDashboard(),scratch,states));
                try{await(()->!reopened.loading()&&reopened.displayedPage()!=null);assertEquals(View.OCCURRENCES,edt(()->reopened.state().query.facets().view));assertEquals(Collections.singletonList(selected),edt(()->reopened.state().selected));assertEquals(ArchiveQuery.Direction.ASCENDING,edt(()->reopened.state().query.order().get(0).direction));}
                finally{edt(()->{reopened.close();return null;});}
            }finally{edt(()->{workspace.close();other.close();return null;});}
        }finally{prefs.shutdown(5,TimeUnit.SECONDS,message->{});}
    }
    @Test public void liveMultiFacetsFilterAllAggregatedItemsAndRestoreWithoutAWindow()throws Exception{
        PreferencesStore prefs=new PreferencesStore(temp.getRoot().toPath().resolve("live.properties"));prefs.preload();ViewStateStore states=ViewStateStore.preferences(prefs);
        try{
            LootDashboard[] live=new LootDashboard[1];Facets f=new Facets();f.kind=Kind.UT_EQUIPMENT;f.bags.addAll(Arrays.asList("White","Orange"));f.dungeons.addAll(Arrays.asList("Lost Halls","Ice Citadel"));f.slots.min=2;f.slots.unknown=Unknown.EXCLUDE;f.applied.max=0;f.applied.unknown=Unknown.EXCLUDE;
            edt(()->{live[0]=new LootDashboard();live[0].bindViewState(states,"test-live");for(int i=0;i<2201;i++)live[0].accept(drop(1000+i,"Lost Halls","White","",item(42,"Rare unused","WEAPON,UT",LootEquipmentTest.encode(-1,-1)),item(43,"Unknown","WEAPON,UT",null),item(44,"Rune","CONSUMABLE,UT",LootEquipmentTest.encode(-1,-1))));live[0].applyFacets(f);assertArrayEquals(new int[]{2201,2201,0},live[0].matchingTotals());return null;});
            prefs.flush().toCompletableFuture().get();
            edt(()->{LootDashboard restored=new LootDashboard(live[0]);restored.bindViewState(states,"test-live");assertArrayEquals(new int[]{2201,2201,0},restored.matchingTotals());
                Facets unknown=new Facets();unknown.slots.unknown=Unknown.ONLY;restored.applyFacets(unknown);assertEquals(2201,restored.matchingTotals()[1]);assertEquals(2201,live[0].matchingTotals()[1]);
                unknown.slots.unknown=Unknown.EXCLUDE;unknown.slots.max=0;restored.applyFacets(unknown);assertEquals(0,restored.matchingTotals()[1]);return null;});
        }finally{prefs.shutdown(5,TimeUnit.SECONDS,message->{});}
    }
    @Test public void liveBagCountersCountEachMatchingCompositionOnceAndFailExplicitlyAtTheBound()throws Exception{
        edt(()->{
            LootDashboard live=new LootDashboard();LootDashboard.Item rare=item(42,"Rare","WEAPON,UT",LootEquipmentTest.encode(-1,-1)),rune=item(43,"Rune","CONSUMABLE,UT",LootEquipmentTest.encode(-1,-1));
            live.accept(drop(1000,"Ice Citadel","White","",rare,rare));live.accept(drop(2000,"Lost Halls","Orange","",rare));live.accept(drop(3000,"Ice Citadel","White","",rune));
            Facets f=new Facets();f.kind=Kind.UT_EQUIPMENT;f.slots.min=2;f.slots.unknown=Unknown.EXCLUDE;f.applied.max=0;f.applied.unknown=Unknown.EXCLUDE;live.applyFacets(f);
            assertArrayEquals(new int[]{2,3,0},live.matchingTotals());
            LootDashboard limited=new LootDashboard();List<LootDashboard.Drop> drops=new ArrayList<>();for(int i=0;i<=LootArchiveAdapter.MAX_KEYS;i++)drops.add(drop(1000+i,"Ice Citadel","White","",item(900000+i,"UT "+i,"WEAPON,UT","")));limited.acceptAll(drops);
            f=new Facets();f.kind=Kind.UT_EQUIPMENT;limited.applyFacets(f);assertEquals(-1,limited.matchingTotals()[0]);assertEquals(LootArchiveAdapter.MAX_KEYS+1,limited.matchingTotals()[1]);
            limited.applyFacets(new Facets());assertEquals(LootArchiveAdapter.MAX_KEYS+1,limited.matchingTotals()[0]);return null;
        });
    }
    @Test public void liveFameRangeMeasureAndDelayedCharacterChoiceRestore()throws Exception{
        PreferencesStore prefs=new PreferencesStore(temp.getRoot().toPath().resolve("fame-state.properties"));prefs.preload();ViewStateStore states=ViewStateStore.preferences(prefs);
        try{
            edt(()->{FameTrackerGUI fame=new FameTrackerGUI((session,done)->done.accept(true));fame.bindViewState(states);fame.trackCapturedFame(7,160000,1000);fame.trackCapturedFame(8,170000,2000);fame.refreshNow();
                named(fame,"fame-graph-character",JComboBox.class).setSelectedItem("Character #7");named(fame,"fame-graph-range",JComboBox.class).setSelectedItem("5 min");named(fame,"fame-graph-measure",JComboBox.class).setSelectedItem("Gain in range");return null;});
            prefs.flush().toCompletableFuture().get();
            edt(()->{FameTrackerGUI restored=new FameTrackerGUI((session,done)->done.accept(true));restored.bindViewState(states);restored.trackCapturedFame(7,160000,1000);restored.trackCapturedFame(8,170000,2000);restored.refreshNow();
                assertEquals("Character #7",named(restored,"fame-graph-character",JComboBox.class).getSelectedItem());assertEquals("5 min",named(restored,"fame-graph-range",JComboBox.class).getSelectedItem());assertEquals("Gain in range",named(restored,"fame-graph-measure",JComboBox.class).getSelectedItem());return null;});
        }finally{prefs.shutdown(5,TimeUnit.SECONDS,message->{});}
    }
    @Test public void liveStateSaveFailureRetainsActiveFacetsAndCanRetryWithoutChangingThem()throws Exception{
        Path file=temp.getRoot().toPath().resolve("blocked.properties");PreferencesStore prefs=new PreferencesStore(file);prefs.preload();ViewStateStore states=ViewStateStore.preferences(prefs);
        Files.createDirectory(file);Files.write(file.resolve("keep"),new byte[]{1});
        try{
            LootDashboard live=edt(()->{LootDashboard view=new LootDashboard();view.bindViewState(states,"failure-live");view.accept(drop(1000,"Ice Citadel","White","",item(1,"UT","WEAPON,UT",""),item(2,"ST","ARMOR,ST","")));Facets f=new Facets();f.kind=Kind.UT_EQUIPMENT;view.applyFacets(f);return view;});
            await(()->named(live,"failure-live-state-status",JTextArea.class).getText().contains("save failed"));assertEquals(1,edt(()->live.matchingTotals()[1]).intValue());
            Files.delete(file.resolve("keep"));Files.delete(file);
            edt(()->{named(live,"failure-live-retry-state",JButton.class).doClick();return null;});await(()->named(live,"failure-live-state-status",JTextArea.class).getText().contains("state saved"));
            edt(()->{LootDashboard restored=new LootDashboard(live);restored.bindViewState(states,"failure-live");assertEquals(1,restored.matchingTotals()[1]);return null;});
        }finally{prefs.shutdown(5,TimeUnit.SECONDS,message->{});}
    }
    @Test public void arrivingFacetChoicesPreserveTheUnappliedLiveDraft()throws Exception{
        edt(()->{AtomicReference<Facets> applied=new AtomicReference<>();LootFacetControls controls=new LootFacetControls(new Facets(),Collections.singleton("White"),Collections.singleton("Ice Citadel"),applied::set);
            named(controls,"loot-bags-multi",JList.class).setSelectedValue("White",false);named(controls,"loot-slots-min",JTextField.class).setText("2");named(controls,"loot-kind",JComboBox.class).setSelectedItem(Kind.UT_EQUIPMENT);
            controls.updateChoices(Arrays.asList("Orange","White"),Arrays.asList("Ice Citadel","Lost Halls"));assertNull(applied.get());
            assertEquals(Collections.singleton("White"),controls.value().bags);assertEquals(Integer.valueOf(2),controls.value().slots.min);assertEquals(Kind.UT_EQUIPMENT,controls.value().kind);
            named(controls,"loot-apply-facets",JButton.class).doClick();assertEquals(Collections.singleton("White"),applied.get().bags);return null;});
    }
    private interface Checked<T>{T get()throws Exception;}
    private static <T>T edt(Checked<T> action)throws Exception{AtomicReference<T> value=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();SwingUtilities.invokeAndWait(()->{try{value.set(action.get());}catch(Throwable t){failure.set(t);}});if(failure.get()!=null)throw new AssertionError(failure.get());return value.get();}
    private static void await(java.util.function.BooleanSupplier ready)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){if(edt(ready::getAsBoolean))return;Thread.sleep(20);}fail("Archive state did not settle");}
}
