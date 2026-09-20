package tomato.gui.notifications;

import org.junit.Test;
import tomato.gui.keypop.KeypopGUI;
import tomato.gui.modern.VioletTheme;
import tomato.realmshark.Sound;
import util.PropertiesManager;
import ui.UiTestLayout;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import static org.junit.Assert.*;

public class NotificationsGuiTest {
    @Test public void dungeonSelectionPreservesHiddenChoicesAndExistingPreferences() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PropertiesManager.setProperties("keypopSound", "Lost Halls,The Shatters"); new KeypopGUI();
            NotificationsGUI ui = new NotificationsGUI();
            ui.dungeonSearch.setText("Lost Halls");
            assertEquals(1, ui.dungeonList.getComponentCount()); ((JCheckBox)ui.dungeonList.getComponent(0)).doClick();
            assertFalse(KeypopGUI.shouldNotify("Lost Halls", null)); assertTrue(KeypopGUI.shouldNotify("The Shatters", null));
            assertEquals("The Shatters", PropertiesManager.getProperty("keypopSound"));
            ui.dungeonSearch.setText("["); assertTrue(ui.dungeonList.getComponent(0) instanceof JLabel);
            ui.missing.doClick(); assertTrue(KeypopGUI.getSelectedDungeons().contains("missingDungeons"));
            assertFalse(KeypopGUI.shouldNotify("Lost Halls", null));
            KeypopGUI.setSelectedDungeons(new TreeSet<>());
        });
    }
    @Test public void savedSoundControlsAndEverySectionRenderAtDesktopAndCompactSizes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VioletTheme.install(); NotificationsGUI ui = new NotificationsGUI(); JFrame frame = new JFrame("Sound & Notifications"); frame.setContentPane(ui);
            try {
                ui.master.setValue(64); assertEquals(64, Sound.getMasterVolume());
                ui.mute.doClick(); assertEquals(ui.mute.isSelected(), Sound.isMuted());
                AbstractButton enabled = button(ui, "sound-pm-enabled"); boolean before = Sound.pm.isEnabled();
                enabled.doClick(); assertEquals(!before, Sound.pm.isEnabled()); enabled.doClick();
                File folder = new File("screenshots"); folder.mkdirs();
                for (int width : new int[]{960, 520}) {
                    frame.setSize(width, 650); frame.setVisible(true); frame.validate();
                    for (int tab = 0; tab < ui.tabs.getTabCount(); tab++) {
                        ui.tabs.setSelectedIndex(tab); frame.validate();
                        UiTestLayout.settle(frame);
                        assertEquals("The complete master control must be visible before capture", new Rectangle(0, 0, ui.master.getWidth(), ui.master.getHeight()),
                            ui.master.getVisibleRect());
                        System.out.println("Notification layout " + width + " tab " + tab + ": master=" + ui.master.getWidth() + ", tabs=" + ui.tabs.getHeight());
                        BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = image.createGraphics(); frame.printAll(g); g.dispose();
                        ImageIO.write(image, "png", new File(folder, "notifications-" + width + "-tab-" + tab + ".png"));
                        assertTrue("Master width: " + ui.master.getWidth(), ui.master.getWidth() > 150); assertTrue("Tabs height: " + ui.tabs.getHeight(), ui.tabs.getHeight() > 300);
                    }
                }
            } catch (Exception e) { throw new AssertionError(e); }
            finally { frame.dispose(); Sound.setMuted(false); Sound.setVolume(100); }
        });
    }
    private static AbstractButton button(Container root, String name) {
        for (Component c : root.getComponents()) {
            if (c instanceof AbstractButton && name.equals(c.getName())) return (AbstractButton)c;
            if (c instanceof Container) { AbstractButton found = button((Container)c, name); if (found != null) return found; }
        }
        return null;
    }
}
