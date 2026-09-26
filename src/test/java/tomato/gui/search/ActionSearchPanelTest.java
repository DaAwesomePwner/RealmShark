package tomato.gui.search;

import org.junit.Test;
import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ActionSearchPanelTest {
    @Test public void typingAndSelectionOnlyDiscoverWhileEnterOpensRealCallback()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            AtomicInteger opened=new AtomicInteger(),closed=new AtomicInteger();ActionRegistry registry=new ActionRegistry();
            registry.register(new ActionDescriptor("font","Font","typography","Appearance","Properties","Preview safe",()->true,"",opened::incrementAndGet));
            registry.register(new ActionDescriptor("capture","Capture options","network","Capture","Properties","Disabled",()->false,"Unavailable in preview",()->{throw new AssertionError("Disabled callback");}));
            ActionSearchPanel panel=new ActionSearchPanel(registry,closed::incrementAndGet);panel.setSize(680,520);layout(panel);
            JTextField query=find(panel,JTextField.class);JList<?> list=find(panel,JList.class);
            query.setText("font");assertEquals(1,list.getModel().getSize());assertEquals(0,opened.get());
            query.postActionEvent();assertEquals(1,opened.get());assertEquals(1,closed.get());
            query.setText("capture");query.postActionEvent();assertEquals(1,opened.get());assertEquals(1,closed.get());
            query.setText("no-such-control");assertEquals(0,list.getModel().getSize());
        });
    }
    static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container)layout((Container)child);}
    static <T>T find(Container c,Class<T> type){for(Component child:c.getComponents()){if(type.isInstance(child))return type.cast(child);if(child instanceof Container){T found=find((Container)child,type);if(found!=null)return found;}}return null;}
}
