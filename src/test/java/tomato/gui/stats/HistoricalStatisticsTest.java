package tomato.gui.stats;

import java.nio.file.Path;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import tomato.history.*;
import tomato.realmshark.ParseEnchants;
import tomato.gui.history.SessionPanel;
import static org.junit.Assert.*;
import static tomato.gui.history.SessionPanelTest.named;

public class HistoricalStatisticsTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void profilesIncludeZeroLootRunsAndCompareSessionsWithoutMixingCharacterIds()throws Exception{
        Path root=temp.newFolder().toPath();SessionStore old=new SessionStore(root,true,"one");String oldId=old.currentId();
        old.put("runs","run-one",visit("run-one",1000));old.put("runs","run-empty",visit("run-empty",61000));
        old.append("loot",new LootDashboard.Drop("White","Ice Citadel","Boss",2000,Arrays.asList(
                new LootDashboard.Item(1,"UT blade","EQUIPMENT,WEAPON,UT",ParseEnchants.summarize("")),
                new LootDashboard.Item(2,"ST robe","EQUIPMENT,ARMOR,ST",ParseEnchants.summarize("")),
                new LootDashboard.Item(3,"Potion","EQUIPMENT,CONSUMABLE,STATPOTION",ParseEnchants.summarize(""))),"run-one"));
        old.append("fame",new AppHistory.FameSample(7,100,1000,"Wizard"));old.append("fame",new AppHistory.FameSample(7,150,61000,"Wizard"));
        old.flush();old.close();
        SessionStore now=new SessionStore(root,true,"two");
        try{
            now.append("fame",new AppHistory.FameSample(7,300,1000,"Wizard"));now.append("fame",new AppHistory.FameSample(7,320,61000,"Wizard"));now.flush();
            SessionPanel.Loaded lootView=HistoricalStatistics.loot(now,SessionStore.ALL,0,"");
            SwingUtilities.invokeAndWait(()->{
                JComponent panel=lootView.createView();JTable table=named(panel,"history-dungeon-loot",JTable.class);
                assertEquals(1,table.getRowCount());assertEquals(2L,value(table,"Observed runs"));assertEquals(2.0,value(table,"Captured minutes"));
                assertEquals(3L,value(table,"Items"));assertEquals(1.5,value(table,"Items / run"));assertEquals(90.0,value(table,"Items / hour"));
                assertEquals(30.0,value(table,"UT / hour"));assertEquals(.5,value(table,"Whites / run"));
                render(panel,"Dungeon loot profile","historical-loot-profile");
            });
            SessionPanel.Loaded statistics=HistoricalStatistics.statistics(now,SessionStore.ALL,0,"");
            SwingUtilities.invokeAndWait(()->{
                JComponent panel=statistics.createView();JTable table=named(panel,"history-session-comparison",JTable.class);
                assertEquals(2,table.getRowCount());Set<Double> gains=new HashSet<>();for(int i=0;i<2;i++)gains.add((Double)table.getValueAt(i,5));
                assertEquals(new HashSet<>(Arrays.asList(20.0,50.0)),gains);
                JTable characters=named(panel,"history-character-fame",JTable.class);assertEquals(2,characters.getRowCount());
                render(panel,"Session comparison","historical-session-comparison");
            });
            now.append("loot",new LootDashboard.Drop("White","Ice Citadel","Unknown",2000,Collections.emptyList(),"missing-run"));now.flush();
            SessionPanel.Loaded unassigned=HistoricalStatistics.loot(now,SessionStore.ALL,0,"");
            SwingUtilities.invokeAndWait(()->assertNull(value(named(unassigned.createView(),"history-dungeon-loot",JTable.class),"Items / run")));
            now.delete(oldId);assertTrue(now.read(oldId,"loot",LootDashboard.Drop.class).isEmpty());
        }finally{now.close();}
    }
    private static ActivityJournal.Visit visit(String id,long start){ActivityJournal.Visit visit=new ActivityJournal.Visit();visit.id=id;visit.map="Ice Citadel";visit.started=start;visit.lastSeen=visit.ended=start+60000;return visit;}
    private static Object value(JTable table,String column){return table.getValueAt(0,table.getColumnModel().getColumnIndex(column));}
    private static void render(JComponent panel,String tab,String name){
        LookAndFeel old=UIManager.getLookAndFeel();JFrame frame=new JFrame("Saved history · synthetic validation data");
        try{
            tomato.gui.modern.VioletTheme.install();SwingUtilities.updateComponentTreeUI(panel);
            JTabbedPane tabs=(JTabbedPane)panel;tabs.setSelectedIndex(tabs.indexOfTab(tab));
            frame.setContentPane(panel);frame.setSize(1050,700);frame.setVisible(true);frame.validate();
            java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(frame.getWidth(),frame.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D graphics=image.createGraphics();frame.printAll(graphics);graphics.dispose();
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get("screenshots"));javax.imageio.ImageIO.write(image,"png",new java.io.File("screenshots/"+name+".png"));
        }catch(Exception e){throw new AssertionError(e);}finally{frame.dispose();try{UIManager.setLookAndFeel(old);}catch(UnsupportedLookAndFeelException e){throw new AssertionError(e);}}
    }
}
