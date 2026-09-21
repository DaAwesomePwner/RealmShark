package tomato.gui.chat;

import java.awt.*;
import java.nio.file.Path;
import java.time.LocalDateTime;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.history.SessionStore;
import tomato.gui.history.SessionPanel;
import static org.junit.Assert.*;
import static tomato.gui.history.SessionPanelTest.named;

public class ChatHistoryTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void oldChatReplaysLiterallyAndStarsPersistWithoutRearchivingOrReplacingLiveOwner()throws Exception{
        Path root=temp.newFolder().toPath();SessionStore old=new SessionStore(root,true,"one");String id=old.currentId();
        ChatMessage message=new ChatMessage(LocalDateTime.of(2026,9,1,12,0),ChatMessage.Channel.PM,"Alice","Me","Alice","<html>Literal\n雪 🦈","From");
        old.append("chat",message);old.flush();old.close();
        SessionStore now=new SessionStore(root,true,"two");JFrame[] frame=new JFrame[1];
        try{
            SessionPanel.Loaded loaded=ChatGUI.history(now,id,0,"");
            SwingUtilities.invokeAndWait(()->{
                JComponent panel=loaded.createView();frame[0]=new JFrame();frame[0].setContentPane(panel);frame[0].setSize(950,700);frame[0].setVisible(true);
                JTable table=named(panel,"chat-messages",JTable.class);assertEquals(1,table.getRowCount());table.setRowSelectionInterval(0,0);
                assertEquals(message.text,named(panel,"chat-detail-message",JTextArea.class).getText());
                findButton(panel,"Star").doClick();
            });
            now.flush();assertTrue(now.read(now.currentId(),"chat",ChatMessage.class).isEmpty());
            SessionPanel.Loaded again=ChatGUI.history(now,id,0,"");
            SwingUtilities.invokeAndWait(()->{
                JComponent panel=again.createView();frame[0].setContentPane(panel);frame[0].validate();findButton(panel,"Starred").doClick();
                assertEquals(1,named(panel,"chat-messages",JTable.class).getRowCount());
            });
        }finally{SwingUtilities.invokeAndWait(()->{if(frame[0]!=null)frame[0].dispose();});now.close();}
    }
    private static AbstractButton findButton(Container root,String text){for(Component c:root.getComponents()){
        if(c instanceof AbstractButton&&text.equals(((AbstractButton)c).getText()))return (AbstractButton)c;
        if(c instanceof Container){AbstractButton found=findButton((Container)c,text);if(found!=null)return found;}}return null;}
}
