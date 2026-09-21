package tomato.gui.keypop;

import java.nio.file.Path;
import java.time.Instant;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.history.SessionStore;
import tomato.gui.history.SessionPanel;
import static org.junit.Assert.*;
import static tomato.gui.history.SessionPanelTest.named;

public class KeyPopHistoryPersistenceTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void savedPopsKeepTimestampsTypesAndSummaryCountsAcrossBuilds()throws Exception{
        Path root=temp.newFolder().toPath();SessionStore old=new SessionStore(root,true,"one");String id=old.currentId();
        Instant time=Instant.parse("2026-09-01T12:00:00Z");
        old.append("keypops",new KeyPopEvent(time,"Alice","Ice Citadel",KeyPopEvent.Kind.KEY));
        old.append("keypops",new KeyPopEvent(time.plusSeconds(1),"Bob","Sword Rune",KeyPopEvent.Kind.RUNE));old.flush();old.close();
        SessionStore now=new SessionStore(root,true,"two");
        try{
            SessionPanel.Loaded loaded=KeypopGUI.history(now,id,0,"");
            SwingUtilities.invokeAndWait(()->{
                JComponent panel=loaded.createView();JTable events=named(panel,"keypop-events",JTable.class);
                assertEquals(2,events.getRowCount());assertEquals(time.plusSeconds(1),events.getValueAt(0,0));
                assertEquals("Rune",events.getValueAt(0,2));
                assertEquals("2",named(panel,"keypop-metric-0",JLabel.class).getText());
                assertEquals("1",named(panel,"keypop-metric-1",JLabel.class).getText());
            });
            now.flush();assertEquals(0,now.read(now.currentId(),"keypops",KeyPopEvent.class).size());
        }finally{now.close();}
    }
}
