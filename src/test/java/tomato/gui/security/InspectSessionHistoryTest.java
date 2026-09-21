package tomato.gui.security;

import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.data.StatData;
import packets.data.enums.StatType;
import packets.packetcapture.logger.ActivityJournal;
import tomato.backend.data.*;
import tomato.history.SessionStore;
import tomato.gui.history.SessionPanel;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;
import static tomato.gui.history.SessionPanelTest.named;

public class InspectSessionHistoryTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void archivedRosterWorksWithoutAssetsAndCannotStealTheLiveCaptureOwner()throws Exception{
        Path root=temp.newFolder().toPath();SessionStore store=new SessionStore(root,true,"test");
        Entity before=player(1,"Alice,old"),after=player(2,"ALICE,new");
        ActivityJournal.Visit visit=new ActivityJournal.Visit();visit.id="old-run";visit.map="Ice Citadel";visit.started=1000;visit.lastSeen=visit.ended=3000;
        visit.inspectedPlayers.put("player:alice,old:99999",new InspectSnapshot(before));
        visit.inspectedPlayers.put("player:alice,new:99999",new InspectSnapshot(after));
        visit.damageTracked=true;visit.firstDamageAt=1000;visit.lastDamageAt=3000;
        visit.playerDamage.put("player:alice,old:99999",100L);visit.playerDamage.put("player:alice,new:99999",300L);
        store.put("runs",visit.id,visit);store.flush();
        SessionPanel.Loaded saved=SecurityGUI.history(store,store.currentId(),0,"");JFrame[] frames=new JFrame[2];ParsePanelGUI[] live=new ParsePanelGUI[1];
        try{
            SwingUtilities.invokeAndWait(()->{
                live[0]=new ParsePanelGUI();frames[0]=show(live[0]);
                JComponent historical=saved.createView();frames[1]=show(historical);
                ParsePanelGUI.addPlayer(8,player(8,"Live"));
            });
            await(()->find(live[0],JTable.class).getRowCount()==1&&named(frames[1],"inspect-runs-table",JTable.class).getRowCount()==1);
            await(()->find(find(frames[1],ParsePanelGUI.class),JTable.class).getRowCount()==1);
            SwingUtilities.invokeAndWait(()->{
                assertEquals("Live [20]",find(live[0],JTable.class).getValueAt(0,0));
                JTable roster=find(find(frames[1],ParsePanelGUI.class),JTable.class);
                assertEquals("ALICE [20]",roster.getValueAt(0,0));assertNull(roster.getValueAt(0,7));
                assertEquals(400L,roster.getValueAt(0,9));assertEquals(200.0,roster.getValueAt(0,10));
            });
        }finally{SwingUtilities.invokeAndWait(()->{for(JFrame frame:frames)if(frame!=null)frame.dispose();});store.close();}
    }
    private static Entity player(int id,String name){Entity entity=new Entity(null,id,0);entity.objectType=99999;entity.baseStats=new int[]{500,200,50,25,50,60,40,40};
        StatData stat=new StatData();stat.stringStatValue=name;entity.stat.set(StatType.NAME_STAT,stat);stat=new StatData();stat.statValue=20;entity.stat.set(StatType.LEVEL_STAT,stat);return entity;}
    private static JFrame show(JComponent panel){JFrame frame=new JFrame();frame.setContentPane(panel);frame.setSize(950,700);frame.setVisible(true);return frame;}
    private static <T> T find(Container root,Class<T> type){for(Component c:root.getComponents()){if(type.isInstance(c))return type.cast(c);if(c instanceof Container){T child=find((Container)c,type);if(child!=null)return child;}}return null;}
}
