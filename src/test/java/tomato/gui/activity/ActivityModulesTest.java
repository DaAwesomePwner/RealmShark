package tomato.gui.activity;

import org.junit.Test;
import packets.*;
import packets.data.*;
import packets.incoming.*;
import packets.packetcapture.logger.*;
import tomato.gui.dps.DpsGUI;
import tomato.gui.modern.*;
import tomato.backend.data.TomatoData;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import static org.junit.Assert.*;

public class ActivityModulesTest {
    private void feed(ActivityJournal j,Packet p,long time){j.observe(p,PacketType.byClass(p),"decoded",time,Collections.emptyMap());}
    private DiscoveryLog fixture() throws Exception {
        ActivityJournal j=new ActivityJournal();long base=System.currentTimeMillis()-60000;
        MapInfoPacket map=new MapInfoPacket();map.name="Ice Citadel";feed(j,map,base);
        CreateSuccessPacket create=new CreateSuccessPacket();create.objectId=42;feed(j,create,base+1);
        for(int n=0;n<=150;n++){
            NewTickPacket tick=new NewTickPacket();tick.tickTime=200;ObjectStatusData s=new ObjectStatusData();s.objectId=42;
            s.stats=new StatData[3];int[] ids={1,4,29};int[] values={700-(n%30)*6,180+(n%20)*4,n<40||n>90?0x20000:0x80000};
            for(int k=0;k<3;k++){s.stats[k]=new StatData();s.stats[k].statTypeNum=ids[k];s.stats[k].statValue=values[k];}
            tick.status=new ObjectStatusData[]{s};feed(j,tick,base+200+n*200);
        }
        IncomingPartyMemberInfoPacket roster=new IncomingPartyMemberInfoPacket();roster.partyId=7;roster.partyPlayers=new PartyPlayerData[0];feed(j,roster,base+31000);
        map.name="Ocean Trench";feed(j,map,base+32000);
        return savedLog(j);
    }
    private DiscoveryLog savedLog(ActivityJournal j) throws Exception {
        Path directory=Files.createTempDirectory("activity-ui");Files.write(directory.resolve("activity-history.json"),new com.google.gson.Gson().toJson(j.snapshot()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        DiscoveryLog log=new DiscoveryLog(directory);log.setSaving(false);return log;
    }
    @Test public void runsFilterSavedAndLiveVisitsWhileTimelineKeepsOtherAreas() throws Exception {
        ActivityJournal journal=new ActivityJournal();
        String[] maps={"Nexus","Ice Citadel","Vault","Ocean Trench","Realm of the Mad God","unknown map",
            "Guild Hall","Pet Yard","Cloth Bazaar","Daily Quest Room","Court of Oryx","Admin Arena","Battle for the Nexus","Lost Halls"};
        for(int i=0;i<maps.length;i++){MapInfoPacket map=new MapInfoPacket();map.name=maps[i];feed(journal,map,1000L+i*1000);}
        DiscoveryLog log=savedLog(journal);
        try {
            SwingUtilities.invokeAndWait(()->{
                ActivityPanel runs=new ActivityPanel(log,ActivityPanel.Mode.RUNS);
                JTable table=find(runs,JTable.class,"activity-table");JTextField search=find(runs,JTextField.class,"activity-search");
                JLabel summary=find(runs,JLabel.class,"activity-summary");
                assertEquals(4,table.getRowCount());assertEquals("Dungeon",table.getColumnName(1));
                assertEquals("Lost Halls",table.getValueAt(0,1));assertEquals("Battle for the Nexus",table.getValueAt(1,1));
                assertEquals("Ocean Trench",table.getValueAt(2,1));assertEquals("Ice Citadel",table.getValueAt(3,1));
                assertTrue(summary.getText().contains("4 of 4 dungeon runs"));
                table.getRowSorter().toggleSortOrder(1);table.setRowSelectionInterval(0,0);runs.refresh();
                assertEquals("Battle for the Nexus",table.getValueAt(table.getSelectedRow(),1));
                search.setText("Vault");assertEquals(0,table.getRowCount());
                assertTrue(find(runs,JTextArea.class,"activity-detail").getText().contains("No dungeon runs match"));
                search.setText("Ice Citadel");assertEquals(1,table.getRowCount());assertTrue(summary.getText().contains("1 of 4 dungeon runs"));
                search.setText("");
                MapInfoPacket hub=new MapInfoPacket();hub.name="Nexus";log.observe(PacketType.MAPINFO.getIndex(),30,hub,"decoded",0);runs.refresh();
                assertEquals(4,table.getRowCount());
                MapInfoPacket dungeon=new MapInfoPacket();dungeon.name="unfamiliar internal";dungeon.displayName="Moonlight Village";
                log.observe(PacketType.MAPINFO.getIndex(),30,dungeon,"decoded",0);runs.refresh();assertEquals(5,table.getRowCount());
                ActivityPanel timeline=new ActivityPanel(log,ActivityPanel.Mode.TIMELINE);
                find(timeline,JTextField.class,"activity-search").setText("Vault");assertEquals(1,find(timeline,JTable.class,null).getRowCount());
                JComboBox<?> picker=find(timeline,JComboBox.class,"activity-visit");assertEquals(maps.length+3,picker.getItemCount());
                ActivityJournal.State history=log.activityHistory(),report=ActivityPanel.exportHistory(history,ActivityPanel.Mode.RUNS);
                assertEquals(5,report.visits.size());assertEquals(5,report.entries.size());assertEquals(maps.length+2,history.visits.size());
                assertSame(history,ActivityPanel.exportHistory(history,ActivityPanel.Mode.TIMELINE));
                for(ActivityJournal.Entry entry:report.entries)assertTrue(report.visits.stream().anyMatch(v->v.id.equals(entry.visitId)));
            });
        } finally {log.close();}
    }
    @Test public void hubAndUnresolvedOnlyHistoryShowsDungeonEmptyState() throws Exception {
        ActivityJournal journal=new ActivityJournal();
        for(String name:new String[]{"Vault","Nexus","Unrecognized area"}){MapInfoPacket map=new MapInfoPacket();map.name=name;feed(journal,map,1000);}
        DiscoveryLog log=savedLog(journal);
        try {
            SwingUtilities.invokeAndWait(()->{
                ActivityPanel runs=new ActivityPanel(log,ActivityPanel.Mode.RUNS);
                assertEquals(0,find(runs,JTable.class,null).getRowCount());
                assertTrue(find(runs,JLabel.class,"activity-summary").getText().contains("0 of 0 dungeon runs"));
                assertTrue(find(runs,JTextArea.class,"activity-detail").getText().contains("No dungeon runs recorded"));
                assertEquals(3,log.activityHistory().visits.size());
                assertTrue(ActivityPanel.exportHistory(log.activityHistory(),ActivityPanel.Mode.RUNS).visits.isEmpty());
            });
        } finally {log.close();}
    }
    @Test public void standaloneNavigationFiltersSelectionsDpsTabsAndPopulatedLayouts() throws Exception {
        DiscoveryLog log=fixture();
        SwingUtilities.invokeAndWait(()->{
            VioletTheme.install();
            ActivityPanel runs=new ActivityPanel(log,ActivityPanel.Mode.RUNS),timeline=new ActivityPanel(log,ActivityPanel.Mode.TIMELINE);
            DpsGUI dps=new DpsGUI(new TomatoData(),log);
            JComponent[] pages=new JComponent[WorkspaceShell.TITLES.length];Arrays.setAll(pages,i->new JPanel());pages[7]=dps;pages[10]=runs;pages[11]=timeline;
            WorkspaceShell shell=new WorkspaceShell(pages,()->{},true);JFrame frame=new JFrame("Activity modules · synthetic validation sample");frame.setContentPane(shell);frame.setSize(1240,800);frame.setVisible(true);
            try{
                assertEquals("Runs",WorkspaceShell.TITLES[10]);assertEquals("Timeline",WorkspaceShell.TITLES[11]);
                assertNotNull(shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("alt R")));
                assertNotNull(shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("alt T")));
                shell.select(10);JTable table=find(runs,JTable.class,null);assertEquals(2,table.getRowCount());
                table.setRowSelectionInterval(1,1);runs.refresh();assertEquals(1,table.getSelectedRow());
                shell.select(11);JTextField search=find(timeline,JTextField.class,null);search.setText("Party roster");assertEquals(1,find(timeline,JTable.class,null).getRowCount());search.setText("[");assertEquals(0,find(timeline,JTable.class,null).getRowCount());search.setText("");
                shell.select(7);JTabbedPane tabs=find(dps,JTabbedPane.class,"dps-tabs");assertEquals("Resources & buffs",tabs.getTitleAt(1));tabs.setSelectedIndex(1);
                ActivityPanel combat=find(dps,ActivityPanel.class,"activity-combat");combat.selectVisit(log.activityHistory().visits.get(0).id);
                assertEquals(2,find(combat,JTable.class,null).getRowCount());
                assertTrue(find(combat,JTextArea.class,"activity-detail").getText().contains("Buff coverage"));
                for(int width:new int[]{1240,760}){
                    frame.setSize(width,width==1240?800:680);frame.validate();shell.dispatchEvent(new ComponentEvent(shell,ComponentEvent.COMPONENT_RESIZED));frame.validate();
                    for(int page:new int[]{10,11,7}){shell.select(page);frame.validate();capture(frame,"activity-"+width+"-"+page+".png");}
                    CombatTimelineChart chart=find(combat,CombatTimelineChart.class,null);assertTrue(chart.getWidth()>300);
                    assertNotNull(chart.getToolTipText(new MouseEvent(chart,MouseEvent.MOUSE_MOVED,0,0,200,60,0,false)));
                    JTabbedPane views=find(combat,JTabbedPane.class,null);views.setSelectedIndex(1);frame.validate();capture(frame,"uptime-"+width+".png");views.setSelectedIndex(0);
                }
            }finally{frame.dispose();log.close();}
        });
    }
    private static void capture(JFrame f,String name){try{BufferedImage img=new BufferedImage(f.getWidth(),f.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=img.createGraphics();f.paint(g);g.dispose();Path dir=Paths.get("screenshots");Files.createDirectories(dir);ImageIO.write(img,"png",dir.resolve(name).toFile());}catch(Exception e){throw new AssertionError(e);}}
    private static <T>T find(Container root,Class<T> type,String name){for(Component c:root.getComponents()){if(type.isInstance(c)&&(name==null||name.equals(c.getName())))return type.cast(c);if(c instanceof Container){T value=find((Container)c,type,name);if(value!=null)return value;}}return null;}
}
