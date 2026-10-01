package tomato.gui.history;

import java.awt.*;

/** Shared component lookup retained for archive tests; the legacy panel UI is gone. */
public abstract class SessionPanelTest {
    public static <T> T named(Container root,String name,Class<T> type){
        for(Component c:root.getComponents()){if(type.isInstance(c)&&name.equals(c.getName()))return type.cast(c);if(c instanceof Container){T found=named((Container)c,name,type);if(found!=null)return found;}}return null;
    }
}
