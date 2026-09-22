package tomato.gui.security;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.data.enums.StatType;
import packets.packetcapture.logger.*;
import tomato.backend.data.*;
import tomato.gui.activity.RunDurationUnit;
import tomato.gui.history.ViewStateStore;
import util.PreferencesStore;
import javax.swing.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static tomato.gui.activity.ActivityArchiveUiTest.*;
import static tomato.gui.activity.ActivityLiveStateTest.*;

public class InspectContainerStateTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void coldRestartRestoresRunsContainerAndExactRunWithoutMatchingNewCaptureByTimeOrMap()throws Exception {
        ActivityJournal.State history=fixture();String first=history.visits.get(0).id;
        Path directory=temp.newFolder().toPath(),settings=temp.getRoot().toPath().resolve("inspect-container.properties");PreferencesStore preferences=preferences(settings);
        try(DiscoveryLog log=retainedLog(directory,history)) {
            SecurityGUI panel=edt(()->{SecurityGUI p=new SecurityGUI(log);p.bindViewState(ViewStateStore.preferences(preferences));named(p,JTabbedPane.class,"inspect-tabs").setSelectedIndex(1);return p;});
            await(()->named(panel,JTable.class,"inspect-runs-table").getRowCount()==2);
            edt(()->{JTable runs=named(panel,JTable.class,"inspect-runs-table");runs.setRowSelectionInterval(1,1);
                named(panel,JTextField.class,"inspect-runs-search").setText("Lost Halls");named(panel,JComboBox.class,"inspect-run-duration-unit").setSelectedItem(RunDurationUnit.SECONDS);
                runs.getColumnModel().getColumn(1).setWidth(222);runs.moveColumn(3,0);return null;});
            await(()->rosterName(panel).startsWith("First"));
            edt(panel::saveViewState).toCompletableFuture().get();
        }finally{preferences.shutdown(5,TimeUnit.SECONDS,m->{});}
        PreferencesStore loaded=preferences(settings);
        try(DiscoveryLog log=new DiscoveryLog(directory)) {
            log.setSaving(false);SecurityGUI panel=edt(()->{SecurityGUI p=new SecurityGUI(log);p.bindViewState(ViewStateStore.preferences(loaded));return p;});
            await(()->rosterName(panel).startsWith("First"));
            edt(()->{
                assertEquals(1,named(panel,JTabbedPane.class,"inspect-tabs").getSelectedIndex());assertEquals("Lost Halls",named(panel,JTextField.class,"inspect-runs-search").getText());
                JTable table=named(panel,JTable.class,"inspect-runs-table");assertEquals(3,table.getColumnModel().getColumn(0).getModelIndex());assertEquals(222,table.getColumnModel().getColumn(table.convertColumnIndexToView(1)).getWidth());
                assertEquals(RunDurationUnit.SECONDS,named(panel,JComboBox.class,"inspect-run-duration-unit").getSelectedItem());
                assertEquals(first,tomato.gui.modern.FormattingTestSupport.field(named(panel,InspectRunsPanel.class,"inspect-runs"),"selectedId",String.class));return null;
            });
            edt(panel::saveViewState).toCompletableFuture().get();
        }finally{loaded.shutdown(5,TimeUnit.SECONDS,m->{});}
        PreferencesStore fresh=preferences(settings);ActivityJournal.State next=fixture();assertNotEquals(first,next.visits.get(0).id);
        try(DiscoveryLog log=retainedLog(temp.newFolder().toPath(),next)) {
            SecurityGUI panel=edt(()->{SecurityGUI p=new SecurityGUI(log);p.bindViewState(ViewStateStore.preferences(fresh));return p;});
            await(()->named(panel,JTable.class,"inspect-runs-table").getRowCount()==2);
            edt(()->{assertEquals(1,named(panel,JTabbedPane.class,"inspect-tabs").getSelectedIndex());assertEquals(-1,named(panel,JTable.class,"inspect-runs-table").getSelectedRow());
                assertEquals("",rosterName(panel));named(panel,JTable.class,"inspect-runs-table").setRowSelectionInterval(0,0);return null;});
            await(()->rosterName(panel).startsWith("Second"));
        }finally{fresh.shutdown(5,TimeUnit.SECONDS,m->{});edt(()->{ParsePanelGUI.clear();return null;});}
    }
    private static String rosterName(SecurityGUI panel){
        ParsePanelGUI roster=named(panel,ParsePanelGUI.class,null);if(roster==null)return "";JTable table=named(roster,JTable.class,null);
        return table.getRowCount()==0?"":Objects.toString(table.getValueAt(0,0));
    }
    private static ActivityJournal.State fixture(){
        ActivityJournal.State state=twoVisits();for(int i=0;i<2;i++){
            Entity player=RequirementResultTest.player(i+1).playerEntity;player.stat.get(StatType.NAME_STAT).stringStatValue=i==0?"First":"Second";
            InspectSnapshot snapshot=new InspectSnapshot(player,1234);state.visits.get(i).inspectedPlayers.put("p"+i,snapshot);state.visits.get(i).inspectedPlayerCount=1;
        }return state;
    }
}
