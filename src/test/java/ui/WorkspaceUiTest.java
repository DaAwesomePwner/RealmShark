package ui;

import org.junit.*;
import tomato.Tomato;
import tomato.gui.TomatoGUI;
import tomato.gui.chat.ChatGUI;
import tomato.gui.modern.WorkspaceShell;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import static org.junit.Assert.*;

/** Exercises the actual Swing application in preview mode, with no packet capture. */
public class WorkspaceUiTest {
    private static JFrame frame;
    private static WorkspaceShell shell;

    @BeforeClass public static void openApplication() throws Exception {
        Tomato.main(new String[] {"--preview"});
        SwingUtilities.invokeAndWait(() -> {
            frame = TomatoGUI.getFrame();
            assertNotNull("The actual application must open", frame);
            shell = (WorkspaceShell) frame.getContentPane();
        });
    }

    @AfterClass public static void closeApplication() throws Exception {
        SwingUtilities.invokeAndWait(() -> { for (Window w : Window.getWindows()) w.dispose(); });
    }

    @Test public void allOriginalSectionsRemainReachableAtBothSizes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (int width : new int[] {1240, 760, 680}) {
                frame.setSize(width, width == 680 ? 520 : 800); frame.validate();
                // Deliver the same layout event used when the user resizes the window.
                shell.dispatchEvent(new java.awt.event.ComponentEvent(shell, java.awt.event.ComponentEvent.COMPONENT_RESIZED));
                frame.validate();
                assertEquals(width < 1000, shell.isCompact());
                for (int i = 0; i < 8; i++) {
                    AbstractButton button = findButton(shell, "nav-" + i);
                    assertTrue(button.isShowing()); button.doClick();
                    assertEquals(i, shell.getSelectedPage());
                    assertTrue(button.isSelected());
                    assertTrue(button.getWidth() >= 40);
                    assertTrue(button.getHeight() >= 40);
                }
            }
        });
    }

    @Test public void originalMenusAndPreviewCaptureGuardRemain() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<String> labels = new ArrayList<>(); collectMenu(frame.getJMenuBar(), labels);
            for (String required : new String[] {"Start Sniffer", "Opt-out Loot Sharing", "Save Chat", "Clear Chat",
                    "Chat Message Pings", "Entity ID Pings", "Item Drop Pings", "Enchant Pings", "DPS Options", "Filter Loot",
                    "RealmShark Violet", "Darcula Theme", "High Contrast Light Theme", "Solarized Dark Theme", "Font", "Net traffic"}) {
                assertTrue("Missing original menu: " + required, labels.contains(required));
            }
            assertFalse(findButton(shell, "capture-toggle").isEnabled());
            assertTrue(Tomato.isPreview());
            TomatoGUI.setStateOfSniffer(true);
            assertEquals("Stop capture", findButton(shell, "capture-toggle").getText());
            TomatoGUI.setStateOfSniffer(false);
        });
    }

    @Test public void captureFailureRemainsVisibleAtCompactWidths() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            frame.setSize(680, 620); frame.validate();
            shell.dispatchEvent(new java.awt.event.ComponentEvent(shell, java.awt.event.ComponentEvent.COMPONENT_RESIZED));
            String reason = "Capture stopped: FileNotFoundException in dungeon metadata. See logs/capture-health.log.";
            TomatoGUI.setCaptureFailure(reason); frame.validate();
            JTextArea area = findFailure(shell);
            assertNotNull(area); assertTrue(area.isShowing());
            assertEquals(reason,area.getText()); assertTrue(area.getLineWrap());
            assertTrue(area.getHeight() > 30);
            assertEquals("Start capture",findButton(shell,"capture-toggle").getText());
            TomatoGUI.setStateOfSniffer(true); assertFalse(area.isVisible());
            TomatoGUI.setStateOfSniffer(false);
        });
    }

    private static JTextArea findFailure(Container container) {
        for (Component c : container.getComponents()) {
            if (c instanceof JTextArea && "capture-failure".equals(c.getName())) return (JTextArea)c;
            if (c instanceof Container) { JTextArea area = findFailure((Container)c); if (area != null) return area; }
        }
        return null;
    }

    @Test public void lootDungeonRenderingSurvivesUnavailableOptionalAssets() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                java.lang.reflect.Method render = tomato.gui.stats.LootGUI.class.getDeclaredMethod(
                        "displayDungeonIcon", packets.incoming.MapInfoPacket.class, JPanel.class);
                render.setAccessible(true);
                packets.incoming.MapInfoPacket map = new packets.incoming.MapInfoPacket();
                map.name = "The Shatters"; map.dungeonModifiers = "UNKNOWN_MOD";
                for (int i = 0; i < 3; i++) {
                    JPanel row = new JPanel(); render.invoke(null, map, row);
                    assertEquals(1,row.getComponentCount());
                    assertTrue(((JLabel)row.getComponent(0)).getToolTipText().contains("The Shatters"));
                }
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
    }

    @Test public void legacyThemeCanSwitchBackWithoutLosingTheWorkspace() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            shell.select(7);
            menuItem(frame.getJMenuBar(), "High Contrast Light Theme").doClick();
            assertFalse(UIManager.getLookAndFeel() instanceof tomato.gui.modern.VioletTheme);
            menuItem(frame.getJMenuBar(), "RealmShark Violet").doClick();
            assertTrue(UIManager.getLookAndFeel() instanceof tomato.gui.modern.VioletTheme);
            assertEquals(7, shell.getSelectedPage());
            shell.select(0);
        });
    }

    @Test public void renderActualPagesForVisualReview() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            frame.setSize(1240, 800); frame.validate();
            shell.dispatchEvent(new java.awt.event.ComponentEvent(shell, java.awt.event.ComponentEvent.COMPONENT_RESIZED));
            frame.validate();
            for (int i = 0; i < 8; i++) {
                shell.select(i); frame.validate(); snapshot("page-" + i + ".png");
                renderSubtabs(shell, "page-" + i);
            }
            shell.select(0);
            ChatGUI.appendTextAreaChat("[Preview sample] 12:41 [Guild] Aster: Anyone up for a Shatters run?\n\n"
                + "[Preview sample] 12:42 [Guild] Wren: Ready in Nexus. Bringing a key.\n\n"
                + "[Preview sample] 12:42 [Party] Nova: Let's meet by the portal.\n");
            frame.validate(); snapshot("chat-sample.png");
            ChatGUI.clearTextAreaChat();
            frame.setSize(760, 620); frame.validate();
            shell.dispatchEvent(new java.awt.event.ComponentEvent(shell, java.awt.event.ComponentEvent.COMPONENT_RESIZED));
            frame.validate(); snapshot("compact.png");
        });
    }

    private static void snapshot(String name) {
        try {
            BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); frame.printAll(graphics); graphics.dispose();
            File folder = new File("screenshots"); folder.mkdirs(); ImageIO.write(image, "png", new File(folder, name));
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static void renderSubtabs(Container root, String prefix) {
        for (Component c : root.getComponents()) {
            if (!c.isShowing()) continue;
            if (c instanceof JTabbedPane) {
                JTabbedPane tabs = (JTabbedPane)c; int selected = tabs.getSelectedIndex();
                for (int i = 0; i < tabs.getTabCount(); i++) {
                    tabs.setSelectedIndex(i); frame.validate(); snapshot(prefix + "-tab-" + i + ".png");
                }
                tabs.setSelectedIndex(selected);
            } else if (c instanceof Container) renderSubtabs((Container)c, prefix);
        }
    }
    private static AbstractButton findButton(Container root, String name) {
        for (Component c : root.getComponents()) {
            if (c instanceof AbstractButton && name.equals(c.getName())) return (AbstractButton) c;
            if (c instanceof Container) { AbstractButton found = findButton((Container)c, name); if (found != null) return found; }
        }
        return null;
    }
    private static void collectMenu(Component c, List<String> labels) {
        if (c instanceof JMenuItem) labels.add(((JMenuItem)c).getText());
        if (c instanceof JMenu) for (Component child : ((JMenu)c).getMenuComponents()) collectMenu(child, labels);
        else if (c instanceof Container) for (Component child : ((Container)c).getComponents()) collectMenu(child, labels);
    }
    private static JMenuItem menuItem(Component c, String text) {
        if (c instanceof JMenuItem && text.equals(((JMenuItem)c).getText())) return (JMenuItem)c;
        Component[] children = c instanceof JMenu ? ((JMenu)c).getMenuComponents() : c instanceof Container ? ((Container)c).getComponents() : new Component[0];
        for (Component child : children) { JMenuItem found = menuItem(child, text); if (found != null) return found; }
        return null;
    }
}
