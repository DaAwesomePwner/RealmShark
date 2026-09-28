package tomato.gui.maingui;

import java.awt.Component;
import java.lang.reflect.Field;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.dps.DpsGUI;
import static org.junit.Assert.*;

/** The Clear DPS Logs menu item says it clears only this app run's list, not saved combat history. */
public class ClearDpsLogsMenuTest {
    @Test public void clearDpsLogsCarriesTheSavedHistoryIsKeptTooltip() throws Exception {
        Field sniffer = TomatoMenuBar.class.getDeclaredField("sniffer"); sniffer.setAccessible(true);
        Object kept = sniffer.get(null);   // make() replaces the shared capture item; restored below
        JMenuItem[] found = new JMenuItem[1];
        try {
            SwingUtilities.invokeAndWait(() -> found[0] = find(new TomatoMenuBar().make(), "Clear DPS Logs"));
        } finally { sniffer.set(null, kept); }
        assertNotNull("The DPS Options menu has Clear DPS Logs", found[0]);
        assertEquals(DpsGUI.CLEAR_LOGS_HELP, found[0].getToolTipText());
    }

    private static JMenuItem find(MenuElement element, String text) {
        Component component = element.getComponent();
        if (component instanceof JMenuItem && !(component instanceof JMenu) && text.equals(((JMenuItem) component).getText())) return (JMenuItem) component;
        if (component instanceof JMenu) {
            for (Component child : ((JMenu) component).getMenuComponents())
                if (child instanceof MenuElement) { JMenuItem hit = find((MenuElement) child, text); if (hit != null) return hit; }
            return null;
        }
        for (MenuElement child : element.getSubElements()) { JMenuItem hit = find(child, text); if (hit != null) return hit; }
        return null;
    }
}
