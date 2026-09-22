package tomato.gui.security;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import tomato.backend.data.*;
import tomato.gui.activity.ActivityArchiveClient;
import tomato.gui.activity.ActivityQueries;
import tomato.gui.history.*;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import util.PreferencesStore;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static tomato.gui.activity.ActivityArchiveUiTest.*;

public class InspectArchiveClientTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void sameVisitIdsAcrossSessionsLoadExactRostersAndKeepLiveOwnerAndLocalFacets()throws Exception {
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String first;
        try(SessionStore old=new SessionStore(root,true,"synthetic-old")){first=old.currentId();old.put("runs","same",visit("same",1));old.flush();}
        PreferencesStore preferences=new PreferencesStore(temp.getRoot().toPath().resolve("inspect.properties"));preferences.preload();
        try(SessionStore store=new SessionStore(root,true,"synthetic-new")) {
            for(int i=0;i<130;i++)store.put("runs","new"+i,visit(i==129?"same":"new"+i,2));store.flush();
            RosterDefinitions definitions=RequirementResultTest.definitions();
            ParsePanelGUI live=edt(()->new ParsePanelGUI(true,()->definitions));
            ArchiveWorkspace<ActivityQueries.Row,ActivityQueries.Filters,ActivityQueries.Sort> workspace=edt(()->SecurityGUI.workspace(store,new JLabel("Live Inspect"),scratch,ViewStateStore.preferences(preferences)));
            try {
                ActivityQueries.Filters exact=new ActivityQueries.Filters();exact.visitId="same";exact.visitSession=first;
                edt(()->{workspace.changeQuery(ActivityQueries.initial().withScope(SessionStore.ALL).withFacets(exact));return null;});
                await(()->!workspace.loading()&&workspace.displayedPage()!=null);assertEquals(1,edt(()->workspace.displayedPage().matches).longValue());
                edt(()->{table(workspace).setRowSelectionInterval(0,0);return null;});await(()->named(workspace,ParsePanelGUI.class,null)!=null);
                ParsePanelGUI saved=edt(()->named(workspace,ParsePanelGUI.class,null));
                edt(()->{
                    JTable roster=named(saved,JTable.class,null);assertEquals(1,roster.getRowCount());roster.setRowSelectionInterval(0,0);
                    ParsePanelGUI.addPlayer(20,RequirementResultTest.player(20).playerEntity);live.refreshRoster();assertEquals(1,named(live,JTable.class,null).getRowCount());
                    assertEquals(1,roster.getRowCount());return null;
                });
                ActivityQueries.Filters other=new ActivityQueries.Filters();other.visitId="same";other.visitSession=store.currentId();
                edt(()->{workspace.changeQuery(workspace.state().query.withFacets(other));return null;});await(()->!workspace.loading()&&workspace.displayedPage().rows.get(0).ref.session.equals(store.currentId()));
                edt(()->{table(workspace).setRowSelectionInterval(0,0);return null;});await(()->named(workspace,ParsePanelGUI.class,null)!=null);
                edt(()->{assertSame(saved,named(workspace,ParsePanelGUI.class,null));assertEquals(-1,named(saved,JTable.class,null).getSelectedRow());
                    assertTrue(named(workspace,JTextArea.class,null)!=null);assertEquals(1,named(saved,JTable.class,null).getRowCount());return null;});
                assertEquals(store.currentId(),edt(()->workspace.state().selected.get(0).session));
            }finally{edt(()->{workspace.close();ParsePanelGUI.clear();return null;});}
        }finally{preferences.shutdown(5,TimeUnit.SECONDS,m->{});}
    }
    @Test public void cachedProductionRosterRetainsEnabledStatesAcrossRefreshQueryPagingAndLiveReturn()throws Exception {
        Path scratch=temp.newFolder().toPath();PreferencesStore preferences=new PreferencesStore(temp.getRoot().toPath().resolve("reuse.properties"));preferences.preload();
        try(SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"synthetic-reuse")) {
            for(int i=0;i<130;i++)store.put("runs","visit-"+i,visit("visit-"+i,1));store.flush();
            ArchiveWorkspace<ActivityQueries.Row,ActivityQueries.Filters,ActivityQueries.Sort> workspace=edt(()->SecurityGUI.workspace(store,new JLabel("Live Inspect"),scratch,ViewStateStore.preferences(preferences)));
            try {
                edt(()->{workspace.showSaved();return null;});await(()->!workspace.loading()&&workspace.displayedPage()!=null);
                edt(()->{table(workspace).setRowSelectionInterval(0,0);return null;});await(()->named(workspace,ParsePanelGUI.class,null)!=null);
                ParsePanelGUI cached=edt(()->named(workspace,ParsePanelGUI.class,null));
                JButton preDisabled=edt(()->button(cached,"Copy all (JSON)"));
                edt(()->{preDisabled.getAction().setEnabled(false);assertRosterEnabled(cached,preDisabled);return null;});

                JTable retired=edt(()->table(workspace));String revision=edt(()->workspace.displayedPage().revision);
                edt(()->{workspace.refresh();workspace.refresh();assertFalse(named(cached,JTable.class,null).isEnabled());return null;});
                await(()->!workspace.loading()&&!revision.equals(workspace.displayedPage().revision)&&named(workspace,ParsePanelGUI.class,null)==cached);
                edt(()->{assertRosterEnabled(cached,preDisabled);assertFalse("Retired controller stays disabled",retired.isEnabled());return null;});

                JTable beforeQuery=edt(()->table(workspace));
                edt(()->{workspace.changeQuery(workspace.state().query.withText("Lost Halls"));return null;});
                await(()->!workspace.loading()&&table(workspace)!=beforeQuery&&named(workspace,ParsePanelGUI.class,null)==cached);
                edt(()->{assertRosterEnabled(cached,preDisabled);return null;});

                edt(()->{workspace.selectPage(1);return null;});await(()->!workspace.loading()&&workspace.displayedPage().page==1);
                edt(()->{table(workspace).setRowSelectionInterval(0,0);return null;});await(()->named(workspace,ParsePanelGUI.class,null)==cached);
                edt(()->{assertRosterEnabled(cached,preDisabled);return null;});

                JTable beforeLive=edt(()->table(workspace));
                edt(()->{button(workspace,"Current live view").doClick();assertFalse(workspace.state().archive);assertFalse(named(cached,JTable.class,null).isEnabled());workspace.showSaved();return null;});
                await(()->!workspace.loading()&&table(workspace)!=beforeLive&&named(workspace,ParsePanelGUI.class,null)==cached);
                edt(()->{assertRosterEnabled(cached,preDisabled);return null;});

                JTable beforeRemoval=edt(()->table(workspace));
                edt(()->{workspace.removeNotify();assertFalse(named(cached,JTable.class,null).isEnabled());workspace.showSaved();return null;});
                await(()->!workspace.loading()&&table(workspace)!=beforeRemoval&&named(workspace,ParsePanelGUI.class,null)==cached);
                edt(()->{
                    assertRosterEnabled(cached,preDisabled);
                    JTextField search=named(cached,JTextField.class,"inspect-roster-search");search.setText("no-such-synthetic-player");cached.refreshRoster();
                    assertEquals(0,named(cached,JTable.class,null).getRowCount());button(cached,"Reset display filters").doClick();cached.refreshRoster();
                    assertEquals("Restored action actually clears the filter",1,named(cached,JTable.class,null).getRowCount());
                    workspace.close();new JPanel().add(cached);
                    assertFalse("Disposal must not reactivate detached reusable controls",named(cached,JTable.class,null).isEnabled());
                    assertFalse(preDisabled.isEnabled());return null;
                });
            }finally{edt(()->{workspace.close();ParsePanelGUI.clear();return null;});}
        }finally{preferences.shutdown(5,TimeUnit.SECONDS,m->{});}
    }
    private static void assertRosterEnabled(ParsePanelGUI roster,JButton preDisabled) {
        assertTrue("Reused roster table",named(roster,JTable.class,null).isEnabled());
        assertTrue("Reused roster search",named(roster,JTextField.class,"inspect-roster-search").isEnabled());
        JComboBox<?> filter=named(roster,JComboBox.class,"inspect-facet-0");
        assertTrue("Reused class filter",filter.isEnabled());
        for(Component child:filter.getComponents())if(child instanceof AbstractButton)assertTrue("Reused filter dropdown",child.isEnabled());
        assertTrue("Reused copy action",button(roster,"Copy names").isEnabled());
        assertTrue("Reused action menu",button(roster,"Actions…").isEnabled());
        assertTrue("Reused reset action",button(roster,"Reset display filters").isEnabled());
        assertFalse("Intentionally disabled action",preDisabled.isEnabled());
        assertFalse(preDisabled.getAction().isEnabled());
    }
    private static JButton button(Container root,String text) {
        for(Component component:root.getComponents()) {
            if(component instanceof JButton&&text.equals(((JButton)component).getText()))return (JButton)component;
            if(component instanceof Container){JButton found=button((Container)component,text);if(found!=null)return found;}
        }
        return null;
    }
    private static ActivityJournal.Visit visit(String id,int player) {
        ActivityJournal.Visit v=new ActivityJournal.Visit();v.id=id;v.map="Lost Halls";v.started=1000;v.lastSeen=v.ended=2000;
        InspectSnapshot snapshot=new InspectSnapshot(RequirementResultTest.player(player).playerEntity,1234);
        v.inspectedPlayers.put("p"+player,snapshot);v.inspectedPlayerCount=1;return v;
    }
}
