package tomato.gui.security;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.data.enums.StatType;
import packets.packetcapture.logger.*;
import tomato.backend.data.*;
import tomato.gui.activity.RunDurationUnit;
import tomato.gui.history.ViewState;
import tomato.gui.roster.RosterViewState;
import tomato.gui.roster.RosterStateTestSupport.Memory;
import tomato.history.archive.ArchiveQuery;
import javax.swing.*;
import java.util.*;
import static org.junit.Assert.*;
import static tomato.gui.activity.ActivityArchiveUiTest.*;
import static tomato.gui.activity.ActivityLiveStateTest.*;
import static tomato.gui.modern.FormattingTestSupport.field;

/** Shared-roster ownership checks with no native peer, focus or real capture. */
public class InspectRosterOwnershipTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @After public void clearLivePlayers()throws Exception{edt(()->{ParsePanelGUI.clear();return null;});}

    @Test public void freshAndRestoredCurrentAreaRetainThirtyPlayersThroughBindingAndRunTimeChanges()throws Exception {
        for(boolean restored:new boolean[]{false,true})for(boolean populateBeforeBind:new boolean[]{false,true}) {
            Memory memory=new Memory();
            if(restored) {
                saveFields(memory,"inspect-live-runs","unit","SECONDS","visit","missing-retained-visit","sort","6:DESCENDING");
                saveFields(memory,"inspect-live-container","tab","area");
            }
            try(DiscoveryLog log=new DiscoveryLog(null)) {
                edt(()->{
                    SecurityGUI panel=new SecurityGUI(log);ParsePanelGUI roster=named(panel,ParsePanelGUI.class,null);
                    if(populateBeforeBind)populate(roster);
                    panel.bindViewState(memory.store);
                    if(!populateBeforeBind)populate(roster);
                    assertCurrent(panel,roster);
                    JComboBox<?> units=named(panel,JComboBox.class,"inspect-run-duration-unit");
                    assertEquals(restored?RunDurationUnit.SECONDS:RunDurationUnit.MINUTES,units.getSelectedItem());
                    units.setSelectedItem(restored?RunDurationUnit.MINUTES:RunDurationUnit.SECONDS);
                    assertCurrent(panel,roster);
                    named(panel,JTextField.class,"inspect-runs-search").setText("Hidden run filter");
                    assertCurrent(panel,roster);return null;
                });
            }
        }
    }

    @Test public void onlyActiveRunsCanReplaceTheSharedRosterAndReactivationStillLoadsSelection()throws Exception {
        Memory memory=new Memory();
        try(DiscoveryLog log=retainedLog(temp.newFolder().toPath(),history())) {
            SecurityGUI panel=edt(()->{SecurityGUI p=new SecurityGUI(log);p.bindViewState(memory.store);populate(named(p,ParsePanelGUI.class,null));tabs(p).setSelectedIndex(1);return p;});
            await(()->rosterName(panel).startsWith("Archived2"));
            ParsePanelGUI roster=edt(()->named(panel,ParsePanelGUI.class,null));
            edt(()->{
                assertRecorded(roster,"Archived2");runsTable(panel).setRowSelectionInterval(1,1);return null;
            });
            await(()->rosterName(panel).startsWith("Archived1"));
            edt(()->{
                tabs(panel).setSelectedIndex(0);assertCurrent(panel,roster);
                named(panel,JComboBox.class,"inspect-run-duration-unit").setSelectedItem(RunDurationUnit.SECONDS);assertCurrent(panel,roster);
                named(panel,JTextField.class,"inspect-runs-search").setText("No run matches");assertCurrent(panel,roster);
                named(panel,JTextField.class,"inspect-runs-search").setText("");runsTable(panel).setRowSelectionInterval(0,0);assertCurrent(panel,roster);
                named(panel,InspectRunsPanel.class,"inspect-runs").refresh();assertCurrent(panel,roster);
                tabs(panel).setSelectedIndex(1);return null;
            });
            await(()->rosterName(panel).startsWith("Archived2"));
            edt(()->{
                assertRecorded(roster,"Archived2");Object displayed=field(roster,"historicalPlayers",Object.class);
                tabs(panel).setSelectedIndex(2);
                named(panel,JTextField.class,"inspect-runs-search").setText("No run matches");
                named(panel,JComboBox.class,"inspect-run-duration-unit").setSelectedItem(RunDurationUnit.MINUTES);
                assertSame("Inactive Runs must not republish even while its roster remains attached",displayed,field(roster,"historicalPlayers",Object.class));
                tabs(panel).setSelectedIndex(0);assertCurrent(panel,roster);return null;
            });
        }
    }

    @Test public void runReadCompletingAfterReturnToCurrentAreaCannotPublishHistoricalRows()throws Exception {
        Memory memory=new Memory();
        try(DiscoveryLog log=retainedLog(temp.newFolder().toPath(),history())) {
            SecurityGUI panel=edt(()->{SecurityGUI p=new SecurityGUI(log);p.bindViewState(memory.store);populate(named(p,ParsePanelGUI.class,null));return p;});
            ParsePanelGUI roster=edt(()->named(panel,ParsePanelGUI.class,null));
            synchronized(log) {
                edt(()->{tabs(panel).setSelectedIndex(1);return null;});
                await(()->Thread.getAllStackTraces().entrySet().stream().anyMatch(thread->thread.getKey().getState()==Thread.State.BLOCKED
                        &&Arrays.stream(thread.getValue()).anyMatch(frame->frame.getClassName().equals(DiscoveryLog.class.getName())&&frame.getMethodName().equals("activityView"))));
                edt(()->{tabs(panel).setSelectedIndex(0);assertCurrent(panel,roster);return null;});
            }
            await(()->!field(field(named(panel,InspectRunsPanel.class,"inspect-runs"),"refresh",Object.class),"running",Boolean.class));
            edt(()->{assertCurrent(panel,roster);tabs(panel).setSelectedIndex(1);return null;});
            await(()->rosterName(panel).startsWith("Archived2"));
            edt(()->{assertRecorded(roster,"Archived2");return null;});
        }
    }

    private static void populate(ParsePanelGUI roster) {
        ParsePanelGUI.clear();for(int i=1;i<=30;i++)ParsePanelGUI.addPlayer(i,RequirementResultTest.player(i).playerEntity);roster.refreshRoster();
    }
    private static void assertCurrent(SecurityGUI panel,ParsePanelGUI roster) {
        assertEquals(0,tabs(panel).getSelectedIndex());assertSame(tabs(panel).getComponentAt(0),roster.getParent());
        assertNull("Current Area owns live row mode",field(roster,"historicalPlayers",Object.class));
        JTable table=named(roster,JTable.class,null);assertEquals("All current players remain visible",30,table.getRowCount());
        assertEquals(-1,table.convertColumnIndexToView(9));assertEquals(-1,table.convertColumnIndexToView(10));
        assertEquals(30,roster.getFilteredPlayers().size());
    }
    private static void assertRecorded(ParsePanelGUI roster,String name) {
        assertNotNull(field(roster,"historicalPlayers",Object.class));JTable table=named(roster,JTable.class,null);
        assertEquals(1,table.getRowCount());assertTrue(table.getValueAt(0,0).toString().startsWith(name));
        assertTrue(table.convertColumnIndexToView(9)>=0);assertTrue(table.convertColumnIndexToView(10)>=0);
    }
    private static JTabbedPane tabs(SecurityGUI panel){return named(panel,JTabbedPane.class,"inspect-tabs");}
    private static JTable runsTable(SecurityGUI panel){return named(panel,JTable.class,"inspect-runs-table");}
    private static String rosterName(SecurityGUI panel) {
        ParsePanelGUI roster=named(panel,ParsePanelGUI.class,null);if(roster==null)return "";JTable table=named(roster,JTable.class,null);
        return table.getRowCount()==0?"":table.getValueAt(0,0).toString();
    }
    private static ActivityJournal.State history() {
        ActivityJournal.State state=twoVisits();for(int i=0;i<2;i++) {
            Entity player=RequirementResultTest.player(101+i).playerEntity;player.stat.get(StatType.NAME_STAT).stringStatValue="Archived"+(i+1);
            state.visits.get(i).inspectedPlayers.put("p"+i,new InspectSnapshot(player,1234));state.visits.get(i).inspectedPlayerCount=1;
        }return state;
    }
    private static void saveFields(Memory memory,String key,String... entries)throws Exception {
        RosterViewState.Fields fields=new RosterViewState.Fields();for(int i=0;i<entries.length;i+=2)fields.values.put(entries[i],entries[i+1]);
        memory.store.save(key,ViewState.initial(ArchiveQuery.of(ArchiveQuery.CURRENT,fields,RosterViewState.Fields.class,RosterViewState.Order.NONE))).toCompletableFuture().get();
    }
}
