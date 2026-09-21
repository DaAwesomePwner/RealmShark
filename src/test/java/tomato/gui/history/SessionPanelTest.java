package tomato.gui.history;

import tomato.history.SessionStore;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;

public class SessionPanelTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void modulesDefaultToLiveAndBrowseIndependentlyWhileCaptureContinues()throws Exception{
        Path root=temp.newFolder().toPath();SessionStore old=new SessionStore(root,true,"old-build");String oldId=old.currentId();
        old.append("chat","old message");old.flush();old.close();
        SessionStore live=new SessionStore(root,true,"new-build");SessionPanel[] panels=new SessionPanel[2];JFrame[] frame=new JFrame[1];
        try{
            SwingUtilities.invokeAndWait(()->{
                JPanel both=new JPanel(new GridLayout(2,1));
                for(int i=0;i<2;i++){
                    JLabel current=new JLabel("Live "+i);current.setName("live-content");
                    panels[i]=new SessionPanel(live,"module"+i,current,(store,scope,page,query)->{
                        java.util.List<String> records=store.read(scope,"chat",String.class);
                        return new SessionPanel.Loaded(()->{JLabel result=new JLabel(String.join("|",records));result.setName("history-content");return result;},false,"Saved");
                    });both.add(panels[i]);
                }
                frame[0]=new JFrame();frame[0].setContentPane(both);frame[0].setSize(1000,800);frame[0].setVisible(true);
                assertTrue(named(panels[0],"live-content",JLabel.class).isShowing());
            });
            await(()->named(panels[0],"module0-session-picker",JComboBox.class).getItemCount()==3);
            SwingUtilities.invokeAndWait(()->panels[0].selectSession(oldId));
            await(()->named(panels[0],"history-content",JLabel.class)!=null);
            live.append("chat","new message");live.flush();
            SwingUtilities.invokeAndWait(()->{
                assertEquals("old message",named(panels[0],"history-content",JLabel.class).getText());
                assertTrue(named(panels[1],"live-content",JLabel.class).isShowing());
                panels[1].selectSession(SessionStore.ALL);
            });
            await(()->named(panels[1],"history-content",JLabel.class)!=null);
            SwingUtilities.invokeAndWait(()->{
                String all=named(panels[1],"history-content",JLabel.class).getText();assertTrue(all.contains("old message"));assertTrue(all.contains("new message"));
                panels[0].selectSession(live.currentId());assertTrue(named(panels[0],"live-content",JLabel.class).isShowing());
            });
        }finally{SwingUtilities.invokeAndWait(()->{if(frame[0]!=null)frame[0].dispose();});live.close();}
    }
    public static <T> T named(Container root,String name,Class<T> type){
        for(Component c:root.getComponents()){if(type.isInstance(c)&&name.equals(c.getName()))return type.cast(c);if(c instanceof Container){T found=named((Container)c,name,type);if(found!=null)return found;}}return null;
    }
}
