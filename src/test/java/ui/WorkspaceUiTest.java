package ui;

import org.junit.*;
import realmshark.branding.AppIdentity;
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
    @Test public void appearanceSettingsAndTheThemeMenuStayInStep() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            shell.select(13);
            tomato.gui.settings.SettingsPage settings = findType(shell, tomato.gui.settings.SettingsPage.class);
            assertNotNull("Settings is shell page 13", settings);
            settings.showSection(tomato.gui.settings.SettingsPage.APPEARANCE);
            try {
                findButton(shell, "settings-theme-1").doClick();
                assertTrue(UIManager.getLookAndFeel() instanceof tomato.gui.modern.VioletLightTheme);
                JMenu themes = (JMenu) menuItem(frame.getJMenuBar(), "Theme");
                for (javax.swing.event.MenuListener listener : themes.getMenuListeners())
                    listener.menuSelected(new javax.swing.event.MenuEvent(themes));
                assertTrue("The menu shows the choice made in Settings", menuItem(frame.getJMenuBar(), "Violet Light").isSelected());
                menuItem(frame.getJMenuBar(), "Violet Dark").doClick();
                assertTrue(UIManager.getLookAndFeel() instanceof tomato.gui.modern.VioletTheme);
                assertTrue("Settings follows the menu", findButton(shell, "settings-theme-0").isSelected());
            } finally {
                if (!(UIManager.getLookAndFeel() instanceof tomato.gui.modern.VioletTheme)) menuItem(frame.getJMenuBar(), "Violet Dark").doClick();
                settings.showSection(tomato.gui.settings.SettingsPage.NOTIFICATIONS);
                shell.select(0);
            }
        });
    }
    private static <T> T findType(Container root, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) return type.cast(c);
            if (c instanceof Container) { T found = findType((Container) c, type); if (found != null) return found; }
        }
        return null;
    }
    @Test public void applicationOpensOnTheFirstVisibleCoreDestination() {
        assertEquals(new tomato.gui.modern.NavLayout().landing().page(), openedOn);
    }
    private static JFrame frame;
    private static WorkspaceShell shell;
    private static int openedOn = -1;

    @BeforeClass public static void openApplication() throws Exception {
        Tomato.main(new String[] {"--preview"});
        SwingUtilities.invokeAndWait(() -> {
            frame = TomatoGUI.getFrame();
            assertNotNull("The actual application must open", frame);
            shell = (WorkspaceShell) frame.getContentPane();
            openedOn = shell.getSelectedPage();
        });
    }

    @AfterClass public static void closeApplication() throws Exception {
        SwingUtilities.invokeAndWait(() -> { for (Window w : Window.getWindows()) w.dispose(); });
    }

    @Test public void previewTitleUsesProductVersionAndFinIcons() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("RealmShark " + realmshark.version.Version.VERSION + "  |  Preview", frame.getTitle());
            assertFalse(frame.getTitle().contains("Tomato"));
            assertFalse("The application must provide window icons", frame.getIconImages().isEmpty());
            assertEquals(AppIdentity.icons(), frame.getIconImages());
        });
    }

    @Test public void aboutMenuShowsOwnedBrandedDialogWithOriginalCredits() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JMenuItem about = menuItem(frame.getJMenuBar(), "About");
            assertNotNull("The original About menu must remain reachable", about);
            about.doClick();
            JDialog dialog = null;
            for (Window window : frame.getOwnedWindows()) {
                if (window instanceof JDialog && window.isShowing()
                        && "About RealmShark".equals(((JDialog)window).getTitle())) dialog = (JDialog)window;
            }
            assertNotNull("About must be owned by the main window", dialog);
            try {
                assertFalse("About should remain readable while using the app", dialog.isModal());
                assertEquals(AppIdentity.icons(), dialog.getIconImages());
                String text = visibleText(dialog);
                assertTrue(text.contains("RealmShark"));
                assertTrue(text.contains(realmshark.version.Version.VERSION));
                assertTrue(text.contains("Realm of the Mad God"));
                assertTrue(text.contains("Anon"));
                assertTrue(text.contains("MIT License"));
                assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains("tomato"));
                JLabel logo = findLogo(dialog);
                assertNotNull("About must display the product logo", logo);
                assertEquals(80, logo.getIcon().getIconWidth());
                assertEquals(80, logo.getIcon().getIconHeight());
                assertEquals("RealmShark logo", logo.getAccessibleContext().getAccessibleName());
                snapshot(dialog, "about.png");
                assertNotNull(dialog.getRootPane().getDefaultButton());
                assertEquals("Close", dialog.getRootPane().getDefaultButton().getText());
                dialog.getRootPane().getDefaultButton().doClick();
                assertFalse("Close should dispose the dialog", dialog.isDisplayable());
            } finally { dialog.dispose(); }
        });
    }

    @Test public void allOriginalSectionsRemainReachableAtRealizedNativeSizes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String advanced = expandAdvanced();
            try {
                for (int width : new int[] {1240, 760, 680}) {
                    frame.setSize(width, width == 680 ? 520 : 800); frame.validate();
                    // Deliver the same layout event used when the user resizes the window.
                    shell.dispatchEvent(new java.awt.event.ComponentEvent(shell, java.awt.event.ComponentEvent.COMPONENT_RESIZED));
                    frame.validate();
                    System.out.println("Native workspace requested=" + width + ", frame=" + frame.getSize()
                        + ", client=" + shell.getSize() + ", screen=" + frame.getGraphicsConfiguration().getBounds()
                        + ", transform=" + frame.getGraphicsConfiguration().getDefaultTransform());
                    // The native peer may clamp the requested outer size at high display scaling.
                    assertEquals("Native compact mode follows the realized client", shell.getWidth() < 1000, shell.isCompact());
                    for (int i = 0; i < WorkspaceShell.TITLES.length; i++) {
                        AbstractButton button = findButton(shell, "nav-" + i);
                        if (i == 6) { assertFalse("Build is reached by route, search and Alt+7, not the sidebar", button.isShowing()); continue; }
                        assertTrue(button.isShowing()); button.doClick();
                        assertEquals(i, shell.getSelectedPage());
                        assertTrue(button.isSelected());
                        assertTrue(button.getWidth() >= 32);
                        assertTrue(button.getHeight() >= 32);
                        button.scrollRectToVisible(new Rectangle(0, 0, button.getWidth(), button.getHeight()));
                        assertEquals("Native destination must be reachable", button.getHeight(), button.getVisibleRect().height);
                    }
                }
            } finally { restoreAdvanced(advanced); }
        });
    }
    @Test public void allOriginalSectionsRemainReachableAtExactClientSizes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String advanced = expandAdvanced();
            // Use the actual application's panels, detached from the native size-constrained peer.
            frame.setContentPane(new JPanel());
            try {
                for (int width : new int[] {1240, 1000, 999, 760, 680}) {
                    Dimension size = new Dimension(width, width == 680 ? 520 : 800);
                    shell.setSize(size);
                    shell.dispatchEvent(new java.awt.event.ComponentEvent(shell, java.awt.event.ComponentEvent.COMPONENT_RESIZED));
                    layoutTree(shell);
                    assertEquals("Exact offscreen client size", size, shell.getSize());
                    assertEquals("Exact compact breakpoint", width < 1000, shell.isCompact());
                    for (int i = 0; i < WorkspaceShell.TITLES.length; i++) {
                        AbstractButton button = findButton(shell, "nav-" + i);
                        if (i == 6) { assertFalse("Build has no sidebar row", button.isVisible()); continue; }
                        button.doClick(); layoutTree(shell);
                        assertEquals(i, shell.getSelectedPage());
                        assertTrue(button.isVisible()); assertTrue(button.isSelected());
                        assertTrue(button.getWidth() >= 32); assertTrue(button.getHeight() >= 32);
                        assertEquals(width < 1000 ? "" : WorkspaceShell.TITLES[i], button.getText());
                    }
                    System.out.println("Exact application client=" + size + ", compact=" + shell.isCompact());
                }
            } finally {
                restoreAdvanced(advanced);
                frame.setContentPane(shell); frame.validate();
                shell.dispatchEvent(new java.awt.event.ComponentEvent(shell, java.awt.event.ComponentEvent.COMPONENT_RESIZED));
                frame.validate();
            }
        });
    }

    /** Advanced pages start collapsed; reachability opens the group the way a user would. Returns the saved value. */
    private static String expandAdvanced() {
        String saved = util.PropertiesManager.getProperty(tomato.gui.modern.NavLayout.ADVANCED_KEY);
        AbstractButton toggle = findButton(shell, "nav-advanced");
        if ("Collapsed".equals(toggle.getAccessibleContext().getAccessibleDescription())) toggle.doClick();
        return saved;
    }

    /** Puts the group and the saved preference back as they were before the test. */
    private static void restoreAdvanced(String saved) {
        AbstractButton toggle = findButton(shell, "nav-advanced");
        boolean open = "Expanded".equals(toggle.getAccessibleContext().getAccessibleDescription());
        if (open != "true".equals(saved)) toggle.doClick();
        util.PropertiesManager.setProperties(tomato.gui.modern.NavLayout.ADVANCED_KEY, saved == null ? "" : saved);
    }

    private static void layoutTree(Container root) {
        root.doLayout();
        for (Component child : root.getComponents())
            if (child instanceof Container && child.isVisible()) layoutTree((Container) child);
    }

    @Test public void originalMenusAndPreviewCaptureGuardRemain() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<String> labels = new ArrayList<>(); collectMenu(frame.getJMenuBar(), labels);
            for (String required : new String[] {"Start capture connection", "Opt-out Loot Sharing", "Save Chat", "Clear Chat",
                    "Chat Message Pings", "Entity ID Pings", "Item Drop Pings", "Enchant Pings", "DPS Options", "Filter Loot",
                    "Violet Dark", "Violet Light", "Increase contrast", "Font", "Net traffic"}) {
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

    @Test public void lightThemeAndContrastSwitchBackWithoutLosingTheWorkspace() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JMenuItem contrast = menuItem(frame.getJMenuBar(), "Increase contrast");
            // Preferences persist in the test working directory; start from a known state even after a failed run.
            if (contrast.isSelected()) contrast.doClick();
            try {
                shell.select(7);
                menuItem(frame.getJMenuBar(), "Violet Light").doClick();
                assertTrue(UIManager.getLookAndFeel() instanceof tomato.gui.modern.VioletLightTheme);
                contrast.doClick();
                assertTrue(tomato.gui.modern.Themes.increaseContrast());
                contrast.doClick();
                menuItem(frame.getJMenuBar(), "Violet Dark").doClick();
                assertTrue(UIManager.getLookAndFeel() instanceof tomato.gui.modern.VioletTheme);
                assertFalse(tomato.gui.modern.Themes.increaseContrast());
                assertEquals(7, shell.getSelectedPage());
            } finally {
                if (contrast.isSelected()) contrast.doClick();
                menuItem(frame.getJMenuBar(), "Violet Dark").doClick();
                shell.select(0);
            }
        });
    }

    @Test public void renderActualPagesForVisualReview() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            frame.setSize(1240, 800); frame.validate();
            shell.dispatchEvent(new java.awt.event.ComponentEvent(shell, java.awt.event.ComponentEvent.COMPONENT_RESIZED));
            frame.validate();
            for (int i = 0; i < WorkspaceShell.TITLES.length; i++) {
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

    @Test public void supportedAppearanceChoicesRepaintAndKeepTheSelectedPage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JMenuItem contrast = menuItem(frame.getJMenuBar(), "Increase contrast");
            try {
                shell.select(0);
                ChatGUI.appendTextAreaChat("[Synthetic] Aster: Ready for the next run.\n");
                for (String variant : new String[] {"Violet Dark", "Violet Light"}) {
                    menuItem(frame.getJMenuBar(), variant).doClick();
                    for (boolean high : new boolean[] {false, true}) {
                        if (contrast.isSelected() != high) contrast.doClick();
                        assertEquals(high, tomato.gui.modern.Themes.saved().increaseContrast);
                        assertEquals(variant.equals("Violet Light"),
                            tomato.gui.modern.Themes.saved().variant == tomato.gui.modern.Themes.Variant.LIGHT);
                        assertEquals(0, shell.getSelectedPage());
                        for (int width : new int[] {1240, 680}) {
                            frame.setSize(width, width == 680 ? 520 : 800); frame.validate();
                            shell.dispatchEvent(new java.awt.event.ComponentEvent(shell, java.awt.event.ComponentEvent.COMPONENT_RESIZED));
                            frame.validate();
                            snapshot("p0-" + variant.replace(' ', '-') + "-contrast-" + high + "-" + width + ".png");
                        }
                    }
                }
            } finally {
                ChatGUI.clearTextAreaChat();
                if (contrast.isSelected()) contrast.doClick();
                menuItem(frame.getJMenuBar(), "Violet Dark").doClick();
                frame.setSize(1240, 800);
            }
        });
    }

    private static void snapshot(String name) {
        snapshot(frame, name);
    }
    private static void snapshot(Window window, String name) {
        try {
            BufferedImage image = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); window.printAll(graphics); graphics.dispose();
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
    @Test public void buildIsUnlistedButOpensByRouteUnderItsNewTitle() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            int before = shell.getSelectedPage();
            try {
                // Build lands on page 6 only while no character exists: pin an empty journal, then put the app's own back.
                tomato.backend.data.TomatoData app = appData();
                tomato.backend.data.CharacterJournal previous = app.characterJournal();
                tomato.gui.glance.character.SheetFixtures.inject(app, emptyJournal());
                try {
                    assertTrue(tomato.gui.route.Navigator.current().open(tomato.gui.route.Route.to(tomato.gui.route.Destination.MY_INFO)));
                    assertEquals(6, shell.getSelectedPage());
                    assertEquals("Build", pageTitle(shell).getText());
                } finally { tomato.gui.glance.character.SheetFixtures.inject(app, previous); }
                assertFalse("Build stays out of the sidebar while it is current", findButton(shell, "nav-6").isVisible());
                assertTrue(tomato.gui.route.Navigator.current().back());
                assertEquals(before, shell.getSelectedPage());
            } finally { shell.select(before); }
        });
    }
    private static JLabel pageTitle(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof JLabel && "page-title".equals(c.getName())) return (JLabel) c;
            if (c instanceof Container) { JLabel found = pageTitle((Container) c); if (found != null) return found; }
        }
        return null;
    }
    private static tomato.backend.data.TomatoData appData() {
        try {
            java.lang.reflect.Field field = TomatoGUI.class.getDeclaredField("data"); field.setAccessible(true);
            return (tomato.backend.data.TomatoData) field.get(null);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    /** A journal with no character, in a new temporary folder. */
    private static tomato.backend.data.CharacterJournal emptyJournal() {
        try { return new tomato.backend.data.CharacterJournal(java.nio.file.Files.createTempDirectory("workspace-build-").resolve("journal.json")); }
        catch (java.io.IOException e) { throw new AssertionError(e); }
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

    private static String visibleText(Component component) {
        StringBuilder text = new StringBuilder();
        if (component instanceof JLabel) text.append(((JLabel)component).getText()).append('\n');
        if (component instanceof AbstractButton) text.append(((AbstractButton)component).getText()).append('\n');
        if (component instanceof javax.swing.text.JTextComponent)
            text.append(((javax.swing.text.JTextComponent)component).getText()).append('\n');
        if (component instanceof Container)
            for (Component child : ((Container)component).getComponents()) text.append(visibleText(child));
        return text.toString();
    }

    private static JLabel findLogo(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel && ((JLabel)child).getIcon() != null
                    && "RealmShark logo".equals(child.getAccessibleContext().getAccessibleName())) return (JLabel)child;
            if (child instanceof Container) {
                JLabel found = findLogo((Container)child);
                if (found != null) return found;
            }
        }
        return null;
    }
}
