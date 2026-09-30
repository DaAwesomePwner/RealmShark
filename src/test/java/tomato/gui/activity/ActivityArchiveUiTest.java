package tomato.gui.activity;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.history.*;
import tomato.gui.kit.CustomizableTabs;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import util.PreferencesStore;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;
import static tomato.gui.activity.ActivityQueries.*;

/** Model/component checks only: no Window, native focus, Robot, capture or personal history. */
public class ActivityArchiveUiTest {
    /** The saved Resources tabs' order and hidden set (CustomizableTabs "saved-resources"); tests select them by index in the default order. */
    private static final String SAVED_TABS="ui.tabs.saved-resources";
    private static final java.util.List<String> TAB_IDS=Arrays.asList("resources","uptime","coverage","window"),
            TAB_TITLES=Arrays.asList("Resources & buffs","Uptime summary","Coverage","Selected window");
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private String savedTabs;
    @Before public void isolateSavedResourceTabs(){savedTabs=util.PropertiesManager.getProperty(SAVED_TABS);util.PropertiesManager.setProperties(SAVED_TABS,"");}
    @After public void restoreSavedResourceTabs(){util.PropertiesManager.setProperties(SAVED_TABS,savedTabs==null?"":savedTabs);}
    @Test public void resourcesRememberSelectedVisitPageChartTabColumnsAndNamedQueryIndependently()throws Exception {
        Path scratch=temp.newFolder().toPath();
        PreferencesStore preferences=new PreferencesStore(temp.getRoot().toPath().resolve("views.properties"));preferences.preload();
        ViewStateStore states=ViewStateStore.preferences(preferences);
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic")) {
            for(int i=1;i<=145;i++)store.put("runs","v"+i,ActivityArchiveTest.visit("v"+i,i));store.flush();
            ArchiveWorkspace<Row,Filters,Sort> resources=edt(()->ActivityPanel.workspace(store,new JLabel("Independent damage encounter"),ActivityPanel.Mode.COMBAT,scratch,states));
            ArchiveWorkspace<Row,Filters,Sort> runs=edt(()->ActivityPanel.workspace(store,new JLabel("Live Runs"),ActivityPanel.Mode.RUNS,scratch,states));
            try {
                edt(()->{resources.showSaved();return null;});await(()->!resources.loading()&&resources.displayedPage()!=null);
                edt(()->{resources.selectPage(1);return null;});await(()->!resources.loading()&&resources.displayedPage().page==1);
                ArchiveRow.Ref selected=edt(()->resources.displayedPage().rows.get(5).ref);
                edt(()->{table(resources).setRowSelectionInterval(5,5);named(resources,JTabbedPane.class,"saved-resource-tabs").setSelectedIndex(1);return null;});
                await(()->named(resources,JTable.class,"saved-buff-uptime")!=null);
                assertEquals(50.0,edt(()->named(resources,JTable.class,"saved-buff-uptime").getValueAt(0,3)));
                edt(()->{
                    JTable table=table(resources);table.getColumnModel().getColumn(0).setWidth(203);
                    JViewport viewport=((JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,table)).getViewport();
                    viewport.setExtentSize(new Dimension(600,80));viewport.setViewPosition(new Point(0,3*table.getRowHeight()+4));return null;
                });
                edt(()->null); // Drain column-state/anchor callbacks before persistence.
                assertEquals(Collections.singletonList(selected),edt(()->resources.state().selected));
                assertEquals("uptime",edt(()->resources.state().tab));assertFalse(edt(()->runs.state().archive));
                assertEquals(203,edt(()->resources.state().tables.get("activity").columns.get(0).width).intValue());
                edt(()->resources.saveNamed("Resource review")).toCompletableFuture().get();preferences.flush().toCompletableFuture().get();
                ViewState<Filters,Sort> remembered=edt(resources::state);
                edt(()->{resources.close();return null;});
                ArchiveWorkspace<Row,Filters,Sort> reopened=edt(()->ActivityPanel.workspace(store,new JPanel(),ActivityPanel.Mode.COMBAT,scratch,states));
                try {
                    await(()->!reopened.loading()&&reopened.displayedPage()!=null&&named(reopened,JTable.class,"saved-buff-uptime")!=null);
                    assertEquals(1,edt(()->reopened.state().page).longValue());assertEquals(selected,edt(()->reopened.state().selected.get(0)));
                    assertEquals(1,edt(()->named(reopened,JTabbedPane.class,"saved-resource-tabs").getSelectedIndex()).intValue());
                    assertEquals(5,edt(()->table(reopened).getSelectedRow()).intValue());
                    assertEquals(remembered.query,edt(()->reopened.state().query));
                    assertEquals(remembered.anchor,edt(()->reopened.state().anchor));assertEquals(remembered.anchorOffset,edt(()->reopened.state().anchorOffset).intValue());
                    edt(()->{reopened.changeQuery(reopened.state().query.withText("no such area"));return null;});await(()->!reopened.loading()&&reopened.displayedPage().matches==0);
                    edt(()->{reopened.loadNamed("Resource review");return null;});await(()->!reopened.loading()&&reopened.displayedPage().matches==145);
                    assertEquals(Collections.singletonList(selected),edt(()->reopened.state().selected));
                }finally{edt(()->{reopened.close();return null;});}
            }finally{edt(()->{resources.close();runs.close();return null;});}
        }finally{preferences.shutdown(5,TimeUnit.SECONDS,m->{});}
    }
    @Test public void compactRunColumnsHaveVisibleOutcomeAndKeyboardSortChangesGlobalQuery()throws Exception {
        Path scratch=temp.newFolder().toPath();PreferencesStore preferences=new PreferencesStore(temp.getRoot().toPath().resolve("columns.properties"));preferences.preload();
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic")) {
            for(int i=1;i<=130;i++)store.put("runs","v"+i,ActivityArchiveTest.visit("v"+i,i));store.flush();
            ArchiveWorkspace<Row,Filters,Sort> workspace=edt(()->ActivityPanel.workspace(store,new JPanel(),ActivityPanel.Mode.RUNS,scratch,ViewStateStore.preferences(preferences)));
            try {
                edt(()->{workspace.showSaved();return null;});await(()->!workspace.loading()&&workspace.displayedPage()!=null);
                edt(()->{
                    JTable table=table(workspace);assertNull(table.getRowSorter());assertEquals(5,table.getColumnCount());assertEquals("Outcome",table.getColumnName(3));
                    assertTrue(table.getCellRect(0,3,true).x<500);assertEquals(Double.class,table.getColumnClass(2));
                    table.setColumnSelectionInterval(2,2);table.getActionMap().get("archive-sort-ascending").actionPerformed(null);return null;
                });
                await(()->!workspace.loading()&&workspace.displayedPage().rows.get(0).value.visitId.equals("v1"));
                assertEquals(Sort.DURATION,edt(()->workspace.state().query.order().get(0).field));
                edt(()->{table(workspace).setRowSelectionInterval(0,0);table(workspace).getActionMap().get("archive-details").actionPerformed(null);return null;});
                await(()->named(workspace,JTextArea.class,"activity-archive-detail").getText().contains("Buff coverage"));
                assertTrue(edt(()->HistoryTables.selectedText(table(workspace))).contains("Left · completion unconfirmed"));
                assertTrue(edt(()->named(workspace,JTextArea.class,"activity-archive-detail").getText()).contains(store.currentId()));
            }finally{edt(()->{workspace.close();return null;});}
        }finally{preferences.shutdown(5,TimeUnit.SECONDS,m->{});}
    }
    @Test public void resourceDetailsUseOldPinUntilRefreshAndClientExportIncludesTimeline()throws Exception {
        Path scratch=temp.newFolder().toPath(),output=temp.newFolder().toPath();PreferencesStore preferences=new PreferencesStore(temp.getRoot().toPath().resolve("pin.properties"));preferences.preload();
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic")) {
            ActivityJournal.Visit v=ActivityArchiveTest.visit("selected",1);store.put("runs","selected",v);store.append("timeline",ActivityArchiveTest.event("selected",1));store.flush();
            ArchiveWorkspace<Row,Filters,Sort> workspace=edt(()->ActivityPanel.workspace(store,new JPanel(),ActivityPanel.Mode.COMBAT,scratch,ViewStateStore.preferences(preferences)));
            try {
                edt(()->{workspace.showSaved();return null;});await(()->!workspace.loading()&&workspace.displayedPage()!=null);
                v.resourceTimeline.get(0).hp=999;store.put("runs","selected",v);store.append("timeline",ActivityArchiveTest.event("selected",2));store.flush();
                edt(()->{table(workspace).setRowSelectionInterval(0,0);return null;});await(()->named(workspace,JTable.class,"saved-buff-uptime")!=null);
                String old=edt(()->{CombatTimelineChart chart=named(workspace,CombatTimelineChart.class,"combat-timeline-chart");chart.getActionMap().get("first-sample").actionPerformed(null);return chart.getInspectionSummary();});assertTrue(old,old.contains("HP 700"));
                ArchivePage<Row> page=edt(workspace::displayedPage);
                try(ArchiveResult.Lease<Row> held=edt(page::lease)) {
                    ActivityArchiveClient client=new ActivityArchiveClient(ActivityPanel.Mode.COMBAT,scratch);
                    Path file=client.writeExport(held,ExportSelection.selected(Collections.singleton(page.rows.get(0).ref)),ArchiveExport.Format.JSON,output,"linked",new Cancellation());
                    assertEquals(2,ActivityArchiveTest.json(file).getAsJsonArray("rows").size());
                }
                edt(()->{workspace.refresh();return null;});await(()->!workspace.loading()&&!workspace.displayedPage().revision.equals(page.revision)&&named(workspace,JTable.class,"saved-buff-uptime")!=null);
                String fresh=edt(()->{CombatTimelineChart chart=named(workspace,CombatTimelineChart.class,"combat-timeline-chart");chart.getActionMap().get("first-sample").actionPerformed(null);return chart.getInspectionSummary();});assertTrue(fresh,fresh.contains("HP 999"));
            }finally{edt(()->{workspace.close();return null;});}
        }finally{preferences.shutdown(5,TimeUnit.SECONDS,m->{});}
    }
    @Test public void legacyFrozenExportDeclaresRevisionAndUnfilteredScopeWhileRetainingStateShape()throws Exception {
        Path output=temp.newFolder().toPath();
        try(packets.packetcapture.logger.DiscoveryLog log=new packets.packetcapture.logger.DiscoveryLog(null)) {
            packets.incoming.MapInfoPacket map=new packets.incoming.MapInfoPacket();map.name="Lost Halls";
            log.observe(packets.PacketType.MAPINFO.getIndex(),30,map,"decoded",0);
            ActivityPanel panel=edt(()->{ActivityPanel view=new ActivityPanel(log,ActivityPanel.Mode.RUNS);view.refresh();return view;});
            await(()->named(panel,JTable.class,"activity-table").getRowCount()==1);
            edt(()->{named(panel,JTextField.class,"activity-search").setText("no matching run");
                for(Component child:buttons(panel))if(child instanceof JCheckBox&&"Pause this view".equals(((JCheckBox)child).getText()))((JCheckBox)child).setSelected(true);return null;});
            log.clear();
            Path file=edt(()->panel.exportTo(output)).get(5,TimeUnit.SECONDS);
            com.google.gson.JsonObject document=ActivityArchiveTest.json(file),manifest=document.getAsJsonObject("manifest");
            assertEquals(1,document.getAsJsonArray("visits").size());assertTrue(manifest.get("displayFrozen").getAsBoolean());assertFalse(manifest.get("filtersApplied").getAsBoolean());
            assertTrue(manifest.has("revision"));assertEquals(1,manifest.get("visitCount").getAsInt());assertTrue(manifest.get("scopeNote").getAsString().contains("visit picker"));
        }
    }
    /**
     * The saved Resources tabs are customizable tabs under their existing name: in the default order an index selects the tab it
     * always did; in the user's order the saved tab is restored by its ID, and selecting a tab records its ID, not its index.
     */
    @Test public void savedResourceTabsRestoreByIdInTheUsersOrderAndIndexSelectionKeepsTheDefaultOrder()throws Exception {
        Path scratch=temp.newFolder().toPath();PreferencesStore preferences=new PreferencesStore(temp.getRoot().toPath().resolve("tabs.properties"));preferences.preload();
        ViewStateStore states=ViewStateStore.preferences(preferences);
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic")) {
            for(int i=1;i<=3;i++)store.put("runs","v"+i,ActivityArchiveTest.visit("v"+i,i));store.flush();
            ArchiveWorkspace<Row,Filters,Sort> first=edt(()->ActivityPanel.workspace(store,new JPanel(),ActivityPanel.Mode.COMBAT,scratch,states));
            try {
                edt(()->{first.showSaved();return null;});await(()->!first.loading()&&first.displayedPage()!=null);
                JTabbedPane pane=edt(()->named(first,JTabbedPane.class,"saved-resource-tabs"));
                CustomizableTabs kit=(CustomizableTabs)pane.getClientProperty(CustomizableTabs.class);
                assertNotNull("The saved Resources tabs are customizable (reorder, hide, reset)",kit);
                assertEquals(TAB_IDS,edt(kit::order));assertEquals(TAB_TITLES,edt(()->titles(pane)));
                for(int i=TAB_IDS.size()-1;i>=0;i--){
                    int index=i;edt(()->{pane.setSelectedIndex(index);return null;});
                    assertEquals("Index "+i+" selects the tab it always did",TAB_IDS.get(i),edt(()->first.state().tab));
                }
                edt(()->{pane.setSelectedIndex(1);return null;});assertEquals("uptime",edt(()->first.state().tab));
            }finally{edt(()->{first.close();return null;});}
            util.PropertiesManager.setProperties(SAVED_TABS,"window,coverage,uptime,resources|");
            ArchiveWorkspace<Row,Filters,Sort> reordered=edt(()->ActivityPanel.workspace(store,new JPanel(),ActivityPanel.Mode.COMBAT,scratch,states));
            try {
                await(()->!reordered.loading()&&reordered.displayedPage()!=null&&named(reordered,JTabbedPane.class,"saved-resource-tabs")!=null);
                JTabbedPane pane=edt(()->named(reordered,JTabbedPane.class,"saved-resource-tabs"));
                assertEquals(Arrays.asList("Selected window","Coverage","Uptime summary","Resources & buffs"),edt(()->titles(pane)));
                assertEquals("The remembered tab is restored by its ID, not its old index","Uptime summary",edt(()->pane.getTitleAt(pane.getSelectedIndex())));
                assertEquals("uptime",edt(()->reordered.state().tab));
                edt(()->{pane.setSelectedIndex(0);return null;});
                assertEquals("Selecting a tab records its ID","window",edt(()->reordered.state().tab));
                assertEquals("Restoring writes no tab preference","window,coverage,uptime,resources|",util.PropertiesManager.getProperty(SAVED_TABS));
            }finally{edt(()->{reordered.close();return null;});}
        }finally{preferences.shutdown(5,TimeUnit.SECONDS,m->{});}
    }
    /** A restored view whose tab the user hid shows the first visible tab; only the user's own "Show hidden" brings it back. */
    @Test public void restoringASavedResourceTabNeverUnhidesIt()throws Exception {
        Path scratch=temp.newFolder().toPath();PreferencesStore preferences=new PreferencesStore(temp.getRoot().toPath().resolve("hidden.properties"));preferences.preload();
        ViewStateStore states=ViewStateStore.preferences(preferences);
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic")) {
            for(int i=1;i<=3;i++)store.put("runs","v"+i,ActivityArchiveTest.visit("v"+i,i));store.flush();
            ArchiveWorkspace<Row,Filters,Sort> first=edt(()->ActivityPanel.workspace(store,new JPanel(),ActivityPanel.Mode.COMBAT,scratch,states));
            try {
                edt(()->{first.showSaved();return null;});await(()->!first.loading()&&first.displayedPage()!=null);
                edt(()->{table(first).setRowSelectionInterval(0,0);named(first,JTabbedPane.class,"saved-resource-tabs").setSelectedIndex(1);return null;});
                await(()->named(first,JTable.class,"saved-buff-uptime")!=null);
                assertEquals("uptime",edt(()->first.state().tab));
            }finally{edt(()->{first.close();return null;});}
            String hidden="resources,uptime,coverage,window|uptime";
            util.PropertiesManager.setProperties(SAVED_TABS,hidden);
            ArchiveWorkspace<Row,Filters,Sort> restored=edt(()->ActivityPanel.workspace(store,new JPanel(),ActivityPanel.Mode.COMBAT,scratch,states));
            try {
                await(()->!restored.loading()&&restored.displayedPage()!=null&&named(restored,JTextArea.class,"activity-archive-detail")!=null
                        &&named(restored,JTextArea.class,"activity-archive-detail").getText().contains("Buff coverage"));
                JTabbedPane pane=edt(()->named(restored,JTabbedPane.class,"saved-resource-tabs"));
                assertEquals("The hidden Uptime tab stays hidden",Arrays.asList("Resources & buffs","Coverage","Selected window"),edt(()->titles(pane)));
                assertEquals("The first visible tab shows",0,edt(pane::getSelectedIndex).intValue());
                assertNull("A hidden tab's content is not in the view",edt(()->named(restored,JTable.class,"saved-buff-uptime")));
                assertEquals("Restoring never rewrites the hidden set",hidden,util.PropertiesManager.getProperty(SAVED_TABS));
                CustomizableTabs kit=(CustomizableTabs)pane.getClientProperty(CustomizableTabs.class);
                edt(()->{kit.show("uptime");return null;});
                assertEquals("Shown again by the user, it is selected and remembered","uptime",edt(()->restored.state().tab));
                assertEquals("Uptime summary",edt(()->pane.getTitleAt(pane.getSelectedIndex())));
                assertEquals("resources,uptime,coverage,window|",util.PropertiesManager.getProperty(SAVED_TABS));
            }finally{edt(()->{restored.close();return null;});}
        }finally{preferences.shutdown(5,TimeUnit.SECONDS,m->{});}
    }
    /**
     * Each render makes its own tabs and its own relative-time column, and the display-mode model holds their listeners weakly: after
     * re-rendering, every replaced view's tabs are collectable and the model keeps only the shown view's listeners. The counts do not
     * depend on collection timing: the baseline and the shown view's own count are read once collections stop lowering the count, and
     * every replaced view is awaited, not only the first (a view that other objects awaiting finalization still reach goes a cycle later).
     */
    @Test public void savedResourceTabsMadePerRenderLeaveNoModeListenerBehind()throws Exception {
        Path scratch=temp.newFolder().toPath();PreferencesStore preferences=new PreferencesStore(temp.getRoot().toPath().resolve("leak.properties"));preferences.preload();
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic")) {
            for(int i=1;i<=145;i++)store.put("runs","v"+i,ActivityArchiveTest.visit("v"+i,i));store.flush();
            int before=settledListeners();   // listeners of earlier tests' views are gone
            ArchiveWorkspace<Row,Filters,Sort> workspace=edt(()->ActivityPanel.workspace(store,new JPanel(),ActivityPanel.Mode.COMBAT,scratch,ViewStateStore.preferences(preferences)));
            try {
                edt(()->{workspace.showSaved();return null;});await(()->!workspace.loading()&&workspace.displayedPage()!=null);
                int shown=settledListeners()-before;   // one shown view's: its tabs' and its time column's
                assertTrue("A shown view listens to the mode: "+shown,shown>=1);
                java.util.List<java.lang.ref.WeakReference<JTabbedPane>> replaced=new ArrayList<>();
                for(long page:new long[]{1,0,1,0}){
                    replaced.add(new java.lang.ref.WeakReference<>(edt(()->named(workspace,JTabbedPane.class,"saved-resource-tabs"))));
                    assertNotNull(replaced.get(replaced.size()-1).get());
                    edt(()->{workspace.selectPage(page);return null;});await(()->!workspace.loading()&&workspace.displayedPage().page==page);
                }
                JTabbedPane current=edt(()->named(workspace,JTabbedPane.class,"saved-resource-tabs"));
                for(java.lang.ref.WeakReference<JTabbedPane> old:replaced)assertNotSame("Each render makes its own tabs",current,old.get());
                for(java.lang.ref.WeakReference<JTabbedPane> old:replaced)assertTrue("Every replaced view's tabs are collectable",collect(old));
                int after=settledListeners();
                assertTrue("The model keeps only the shown view's listeners: "+after+" > "+before+" + "+shown,after<=before+shown);
            }finally{edt(()->{workspace.close();return null;});}
        }finally{preferences.shutdown(5,TimeUnit.SECONDS,m->{});}
    }
    /** The application mode's listener count once five collections in a row do not lower it (at most 10 s). */
    private static int settledListeners()throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);int lowest=Integer.MAX_VALUE,steady=0;
        while(System.nanoTime()<until&&steady<5){
            System.gc();Thread.sleep(20);
            int count=edt(()->tomato.gui.kit.DisplayModeModel.application().listenerCount());
            if(count<lowest){lowest=count;steady=0;}else steady++;
        }
        return lowest;
    }
    /** Collects garbage until {@code reference} is cleared (at most 10 s); true once it is. */
    private static boolean collect(java.lang.ref.WeakReference<?> reference)throws InterruptedException {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(reference.get()!=null&&System.nanoTime()<until){System.gc();Thread.sleep(20);}
        return reference.get()==null;
    }
    private static java.util.List<String> titles(JTabbedPane pane) {
        java.util.List<String> titles=new ArrayList<>();for(int i=0;i<pane.getTabCount();i++)titles.add(pane.getTitleAt(i));return titles;
    }
    private static java.util.List<Component> buttons(Container root) {
        java.util.List<Component> all=new ArrayList<>();for(Component c:root.getComponents()){all.add(c);if(c instanceof Container)all.addAll(buttons((Container)c));}return all;
    }
    public static JTable table(Container root){return named(root,JTable.class,"saved-activity-table");}
    public static <T> T named(Container root,Class<T> type,String name){
        for(Component c:root.getComponents()){if(type.isInstance(c)&&(name==null||name.equals(c.getName())))return type.cast(c);
            if(c instanceof Container){T found=named((Container)c,type,name);if(found!=null)return found;}}return null;
    }
    @FunctionalInterface public interface Checked<T>{T get()throws Exception;}
    public static <T> T edt(Checked<T> value)throws Exception{
        AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{try{result.set(value.get());}catch(Throwable failure){error.set(failure);}});
        if(error.get()!=null)throw new AssertionError(error.get());return result.get();
    }
    public static void await(BooleanSupplier condition)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<until){if(edt(condition::getAsBoolean))return;Thread.sleep(20);}fail("Timed out waiting for component/model state");
    }
}
