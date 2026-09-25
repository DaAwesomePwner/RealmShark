package tomato.gui.activity;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.PacketType;
import packets.incoming.MapInfoPacket;
import packets.packetcapture.logger.*;
import tomato.gui.history.ViewStateStore;
import util.PreferencesStore;
import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static tomato.gui.activity.ActivityArchiveUiTest.edt;
import static tomato.gui.activity.ActivityArchiveUiTest.await;
import static tomato.gui.activity.ActivityArchiveUiTest.named;

public class ActivityLiveStateTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void liveRunsApplyTypedFacetsToAllRetainedVisitsAndPrioritizeCompactOutcome()throws Exception {
        ActivityJournal.State history=new ActivityJournal.State();
        for(int i=1;i<=140;i++){
            ActivityJournal.Visit v=ActivityArchiveTest.visit("synthetic-"+i,i);v.lastSeen=v.ended=v.started+i*10000L;
            v.timingGaps=i%3==0?1:0;v.issues=i%5==0?1:0;history.visits.add(v);
        }
        try(DiscoveryLog log=retainedLog(temp.newFolder().toPath(),history)) {
            ActivityPanel panel=edt(()->{ActivityPanel p=new ActivityPanel(log,ActivityPanel.Mode.RUNS);p.refresh();return p;});
            await(()->table(panel).getRowCount()==140);
            edt(()->{
                JTable table=table(panel);
                assertEquals(Arrays.asList("Dungeon","Entered","Observed minutes","Outcome","Coverage"),
                        Arrays.asList(table.getColumnName(0),table.getColumnName(1),table.getColumnName(2),table.getColumnName(3),table.getColumnName(4)));
                assertTrue("Outcome fits at compact width",table.getCellRect(0,3,true).getMaxX()<680);
                assertEquals(Double.class,table.getColumnClass(2));assertEquals(java.time.Instant.class,table.getColumnClass(1));
                ActivityQueries.Filters filters=new ActivityQueries.Filters();filters.outcomes.add(ActivityQueries.Outcome.COMPLETED);
                filters.evidence.add(ActivityQueries.Evidence.VICTORY);filters.minimumDurationMillis=300001L;filters.timingGaps=ActivityQueries.Presence.PRESENT;
                panel.setRunFilters(filters);assertEquals(18,table.getRowCount());
                named(panel,JComboBox.class,"live-run-issues").setSelectedItem(ActivityQueries.Presence.PRESENT);assertEquals(3,table.getRowCount());
                for(int row=0;row<table.getRowCount();row++){assertEquals("Completed",table.getValueAt(row,3));assertTrue(((Number)table.getValueAt(row,2)).doubleValue()>5);}
                assertTrue(named(panel,JLabel.class,"activity-summary").getText().contains("entire retained displayed snapshot"));
                named(panel,JTextField.class,"live-run-minimum-seconds").setText("1200.001");named(panel,JButton.class,"live-run-apply-duration").doClick();assertEquals(0,table.getRowCount());
                named(panel,JTextField.class,"live-run-minimum-seconds").setText("300.001");named(panel,JButton.class,"live-run-apply-duration").doClick();assertEquals(3,table.getRowCount());
                JCheckBox freeze=checkbox(panel,"Pause this view");freeze.setSelected(true);return null;
            });
            log.clear();edt(()->{panel.refresh();assertEquals(3,table(panel).getRowCount());panel.setRunFilters(new ActivityQueries.Filters());assertEquals(140,table(panel).getRowCount());return null;});
        }
    }
    @Test public void coldRestartRestoresResourceTabFilterLayoutSortAndOnlyTheExactRetainedVisit()throws Exception {
        ActivityJournal.State history=twoVisits();String first=history.visits.get(0).id;
        Path historyDir=temp.newFolder().toPath(),settings=temp.getRoot().toPath().resolve("live.properties");
        PreferencesStore preferences=preferences(settings);
        try(DiscoveryLog log=retainedLog(historyDir,history)) {
            ActivityPanel panel=edt(()->{ActivityPanel p=new ActivityPanel(log,ActivityPanel.Mode.COMBAT);p.bindViewState(ViewStateStore.preferences(preferences));p.refresh();return p;});
            await(()->named(panel,JComboBox.class,"activity-visit").getItemCount()==2);
            edt(()->{panel.selectVisit(first);return null;});
            await(()->tomato.gui.modern.FormattingTestSupport.field(panel,"state",ActivityJournal.State.class).visits.stream().anyMatch(v->first.equals(v.id))&&table(panel).getRowCount()==2);
            edt(()->{
                named(panel,JTabbedPane.class,"activity-resource-tabs").setSelectedIndex(1);named(panel,JTextField.class,"activity-search").setText("Damaging");
                JTable table=table(panel);table.setRowSelectionInterval(0,0);table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(3,SortOrder.DESCENDING)));
                table.getColumnModel().getColumn(1).setWidth(211);table.moveColumn(3,0);return null;
            });
            edt(panel::saveViewState).toCompletableFuture().get();preferences.flush().toCompletableFuture().get();
        }finally{preferences.shutdown(5,TimeUnit.SECONDS,m->{});}
        PreferencesStore reopenedPreferences=preferences(settings);
        try(DiscoveryLog log=new DiscoveryLog(historyDir)) {
            log.setSaving(false);ViewStateStore states=ViewStateStore.preferences(reopenedPreferences);
            ActivityPanel panel=edt(()->{ActivityPanel p=new ActivityPanel(log,ActivityPanel.Mode.COMBAT);p.bindViewState(states);p.refresh();return p;});
            await(()->table(panel).getRowCount()==1);
            edt(()->{
                assertEquals("Damaging",named(panel,JTextField.class,"activity-search").getText());assertEquals(1,named(panel,JTabbedPane.class,"activity-resource-tabs").getSelectedIndex());
                assertEquals(0,table(panel).getSelectedRow());assertEquals(3,table(panel).getColumnModel().getColumn(0).getModelIndex());
                assertEquals(211,table(panel).getColumnModel().getColumn(table(panel).convertColumnIndexToView(1)).getWidth());
                assertEquals(SortOrder.DESCENDING,table(panel).getRowSorter().getSortKeys().get(0).getSortOrder());
                CombatTimelineChart chart=named(panel,CombatTimelineChart.class,"combat-timeline-chart");chart.getActionMap().get("first-sample").actionPerformed(null);
                assertTrue(chart.getInspectionSummary(),chart.getInspectionSummary().contains("HP 111"));
                ActivityPanel other=new ActivityPanel(log,ActivityPanel.Mode.RUNS);other.bindViewState(states);assertEquals("",named(other,JTextField.class,"activity-search").getText());return null;
            });
            edt(panel::saveViewState).toCompletableFuture().get();
        }finally{reopenedPreferences.shutdown(5,TimeUnit.SECONDS,m->{});}
        PreferencesStore freshPreferences=preferences(settings);
        ActivityJournal.State newCapture=twoVisits();assertNotEquals(first,newCapture.visits.get(0).id);
        try(DiscoveryLog log=retainedLog(temp.newFolder().toPath(),newCapture)) {
            ActivityPanel panel=edt(()->{ActivityPanel p=new ActivityPanel(log,ActivityPanel.Mode.COMBAT);p.bindViewState(ViewStateStore.preferences(freshPreferences));p.refresh();return p;});
            await(()->named(panel,JComboBox.class,"activity-visit").getItemCount()==2);
            edt(()->{assertEquals(-1,named(panel,JComboBox.class,"activity-visit").getSelectedIndex());assertEquals(0,table(panel).getRowCount());assertEquals(-1,table(panel).getSelectedRow());
                assertEquals(1,named(panel,JTabbedPane.class,"activity-resource-tabs").getSelectedIndex());panel.selectVisit(newCapture.visits.get(1).id);return null;});
            await(()->table(panel).getRowCount()==1);
            edt(()->{CombatTimelineChart chart=named(panel,CombatTimelineChart.class,"combat-timeline-chart");chart.getActionMap().get("first-sample").actionPerformed(null);assertTrue(chart.getInspectionSummary().contains("HP 222"));return null;});
        }finally{freshPreferences.shutdown(5,TimeUnit.SECONDS,m->{});}
    }
    @Test public void runQueryAndExactRowSurviveColdRestartIndependentlyOfResources()throws Exception {
        ActivityJournal.State history=twoVisits();history.visits.get(0).completionEvidence="Server victory notification";history.visits.get(0).timingGaps=2;
        Path directory=temp.newFolder().toPath(),settings=temp.getRoot().toPath().resolve("runs.properties");PreferencesStore preferences=preferences(settings);
        try(DiscoveryLog log=retainedLog(directory,history)) {
            ActivityPanel panel=edt(()->{ActivityPanel p=new ActivityPanel(log,ActivityPanel.Mode.RUNS);p.bindViewState(ViewStateStore.preferences(preferences));p.refresh();return p;});
            await(()->table(panel).getRowCount()==2);
            edt(()->{ActivityQueries.Filters f=new ActivityQueries.Filters();f.outcomes.add(ActivityQueries.Outcome.COMPLETED);f.timingGaps=ActivityQueries.Presence.PRESENT;preset(panel,f);
                named(panel,JComboBox.class,"run-duration-unit").setSelectedItem(RunDurationUnit.SECONDS);table(panel).setRowSelectionInterval(0,0);return null;});
            edt(panel::saveViewState).toCompletableFuture().get();
        }finally{preferences.shutdown(5,TimeUnit.SECONDS,m->{});}
        PreferencesStore loaded=preferences(settings);
        try(DiscoveryLog log=new DiscoveryLog(directory)) {
            log.setSaving(false);ActivityPanel panel=edt(()->{ActivityPanel p=new ActivityPanel(log,ActivityPanel.Mode.RUNS);p.bindViewState(ViewStateStore.preferences(loaded));p.refresh();return p;});
            await(()->table(panel).getRowCount()==1);
            edt(()->{assertEquals(0,table(panel).getSelectedRow());assertEquals("Completed",table(panel).getValueAt(0,3));assertEquals("Observed seconds",table(panel).getColumnName(2));
                assertEquals(ActivityQueries.Presence.PRESENT,panel.runFilters().timingGaps);return null;});
        }finally{loaded.shutdown(5,TimeUnit.SECONDS,m->{});}
    }
    @Test public void timelineLiveRawSearchTypeAndExactEventSelectionSurviveColdRestart()throws Exception {
        ActivityJournal.State history=twoVisits();ActivityJournal.Entry event=history.entries.get(0);
        event.kind="Party activity";event.detail="Independent notification";event.values=new LinkedHashMap<>();event.values.put("partyId",4321);
        Path directory=temp.newFolder().toPath(),settings=temp.getRoot().toPath().resolve("timeline.properties");PreferencesStore preferences=preferences(settings);
        try(DiscoveryLog log=retainedLog(directory,history)) {
            ActivityPanel panel=edt(()->{ActivityPanel p=new ActivityPanel(log,ActivityPanel.Mode.TIMELINE);p.bindViewState(ViewStateStore.preferences(preferences));p.refresh();return p;});
            await(()->table(panel).getRowCount()==2);
            edt(()->{panel.selectVisit(event.visitId);named(panel,JComboBox.class,"activity-kind").setSelectedItem("Party");named(panel,JTextField.class,"activity-search").setText("4321");
                assertEquals(1,table(panel).getRowCount());assertFalse(table(panel).getValueAt(0,3).toString().contains("4321"));table(panel).setRowSelectionInterval(0,0);return null;});
            edt(panel::saveViewState).toCompletableFuture().get();
        }finally{preferences.shutdown(5,TimeUnit.SECONDS,m->{});}
        PreferencesStore loaded=preferences(settings);
        try(DiscoveryLog log=new DiscoveryLog(directory)) {
            log.setSaving(false);ActivityPanel panel=edt(()->{ActivityPanel p=new ActivityPanel(log,ActivityPanel.Mode.TIMELINE);p.bindViewState(ViewStateStore.preferences(loaded));p.refresh();return p;});
            await(()->table(panel).getRowCount()==1);
            edt(()->{assertEquals(0,table(panel).getSelectedRow());assertEquals("Party",named(panel,JComboBox.class,"activity-kind").getSelectedItem());
                assertEquals("4321",named(panel,JTextField.class,"activity-search").getText());assertTrue(named(panel,JTextArea.class,"activity-detail").getText().contains("4321"));
                named(panel,JTextField.class,"activity-search").setText("partyId");assertEquals(1,table(panel).getRowCount());return null;});
        }finally{loaded.shutdown(5,TimeUnit.SECONDS,m->{});}
    }
    private static void preset(ActivityPanel panel,ActivityQueries.Filters f){panel.setRunFilters(f);assertEquals(1,table(panel).getRowCount());}
    public static ActivityJournal.State twoVisits(){
        ActivityJournal journal=new ActivityJournal();for(int i=0;i<2;i++){MapInfoPacket map=new MapInfoPacket();map.name="Lost Halls";journal.observe(map,PacketType.MAPINFO,"decoded",1000,Collections.emptyMap());}
        ActivityJournal.State state=journal.snapshot();
        for(int i=0;i<2;i++){ActivityJournal.Visit v=state.visits.get(i);v.lastSeen=v.ended=601000;v.conditionObservedMillis=600000;v.conditions.put("Damaging",300000L);v.conditions.put("Quiet",10000L);
            ActivityJournal.ResourcePoint point=new ActivityJournal.ResourcePoint();point.time=1000;point.hp=(i+1)*111;v.resourceTimeline.add(point);}
        return state;
    }
    public static DiscoveryLog retainedLog(Path directory,ActivityJournal.State history)throws Exception{
        Files.write(directory.resolve("activity-history.json"),new com.google.gson.Gson().toJson(history).getBytes(StandardCharsets.UTF_8));DiscoveryLog log=new DiscoveryLog(directory);log.setSaving(false);return log;
    }
    public static PreferencesStore preferences(Path file)throws Exception{PreferencesStore store=new PreferencesStore(file);store.preload();return store;}
    private static JTable table(Container panel){return named(panel,JTable.class,"activity-table");}
    private static JCheckBox checkbox(Container root,String text){for(Component c:root.getComponents()){if(c instanceof JCheckBox&&text.equals(((JCheckBox)c).getText()))return(JCheckBox)c;if(c instanceof Container){JCheckBox found=checkbox((Container)c,text);if(found!=null)return found;}}return null;}
}
