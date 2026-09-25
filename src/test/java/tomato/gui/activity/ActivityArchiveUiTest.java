package tomato.gui.activity;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.history.*;
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
    @Rule public TemporaryFolder temp=new TemporaryFolder();
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
