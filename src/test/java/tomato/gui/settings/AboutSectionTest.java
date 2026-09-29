package tomato.gui.settings;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import realmshark.branding.AppIdentity;
import tomato.gui.maingui.AboutPanel;
import tomato.gui.maingui.TomatoMenuBar;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static tomato.gui.settings.LootFiltersSectionTest.find;
import static tomato.gui.settings.LootFiltersSectionTest.snifferField;
import static tomato.gui.settings.SettingsPageTest.named;

/** P6a: Settings › About shows the About dialog's content, the Java version and Net traffic; the dialog itself is unchanged. */
public class AboutSectionTest {
    @Rule public final VisualEvidence evidence = new VisualEvidence("redesign-p6a-settings");
    private final List<String> opened = new ArrayList<>();
    private int javaVersions, netTraffic;
    private Object sniffer;

    @Before public void keepSniffer() throws Exception { sniffer = snifferField().get(null); }

    @After public void closeDialogsAndRestoreSniffer() throws Exception {
        SwingUtilities.invokeAndWait(() -> { for (Window window : Window.getWindows()) if (window instanceof JDialog) window.dispose(); });
        snifferField().set(null, sniffer);
    }

    private AboutSection section() { return new AboutSection(() -> javaVersions++, () -> netTraffic++); }

    @Test public void showsTheAboutContentAndVersionsWithoutAnyPath() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AboutSection section = section();
            assertEquals("settings-about", section.getName());
            AboutPanel about = VisualEvidence.find(section, AboutPanel.class, panel -> true);
            assertEquals(AppIdentity.NAME, named(about, "about-name", JLabel.class).getText());
            assertEquals(AppIdentity.version() + "  ·  Custom build", named(about, "about-version", JLabel.class).getText());
            String text = String.join("\n", texts(section));
            for (String fact : new String[] {"A read-only companion for Realm of the Mad God.", "Upstream credits",
                    "Original work by Anon and upstream contributors.", "Distributed under the MIT License.", "LICENSE.md"})
                assertTrue("About shows '" + fact + "': " + text, text.contains(fact));
            assertEquals("The menu's Java version text", "Java version: " + System.getProperty("java.version") + " ("
                + System.getProperty("sun.arch.data.model") + "-bit)", TomatoMenuBar.javaVersion());
            AbstractButton java = named(section, "settings-about-java-version", AbstractButton.class);
            assertEquals("Java version", java.getText());
            assertEquals(TomatoMenuBar.javaVersion(), java.getToolTipText());
            java.doClick();
            assertEquals("The menu's Java version action runs", 1, javaVersions);
            AbstractButton traffic = named(section, "settings-about-net-traffic", AbstractButton.class);
            assertEquals("Net traffic", traffic.getText());
            traffic.doClick();
            assertEquals("The menu's Net traffic action runs", 1, netTraffic);
            String everything = text + "\n" + java.getToolTipText() + "\n" + traffic.getToolTipText();
            for (String property : new String[] {"user.home", "user.dir", "java.home", "java.io.tmpdir"}) {
                String path = System.getProperty(property);
                assertFalse("No " + property + " path is shown", path != null && everything.contains(path));
            }
            String plain = everything.replaceAll("<[^>]+>", "");
            assertFalse("No path separators outside markup: " + plain, plain.contains("/") || plain.contains("\\"));
        });
    }

    @Test public void theAboutDialogStillBuildsWithTheSameContentAndTheMenuOpensItWithoutTheHook() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TomatoMenuBar bar = new TomatoMenuBar();
            JMenuBar menus = bar.make();
            JMenuItem about = find(menus, "About");
            about.doClick();
            JDialog dialog = aboutDialog();
            assertNotNull("No hook: Info › About opens the dialog", dialog);
            assertEquals("About " + AppIdentity.NAME, dialog.getTitle());
            assertFalse("Modeless, as before", dialog.isModal());
            AboutPanel content = VisualEvidence.find(dialog.getContentPane(), AboutPanel.class, panel -> true);
            assertEquals(AppIdentity.NAME, named(content, "about-name", JLabel.class).getText());
            assertNotNull(VisualEvidence.button(dialog.getContentPane(), "Close"));
            assertSame(VisualEvidence.button(dialog.getContentPane(), "Close"), dialog.getRootPane().getDefaultButton());
            VisualEvidence.button(dialog.getContentPane(), "Close").doClick();
            assertFalse(dialog.isDisplayable());

            bar.onOpenSettings(opened::add);
            about.doClick();
            assertEquals("With the hook, Info › About opens Settings › About", List.of(SettingsPage.ABOUT), opened);
            assertNull("and no dialog", aboutDialog());
            List<String> info = LootFiltersSectionTest.texts((JMenu) find(menus, "Info"));
            assertEquals("The Info menu keeps its items", List.of("About", "Java version", "Net traffic"), info);
            bar.onOpenSettings(null);
            about.doClick();
            assertNotNull("Clearing the hook opens the dialog again", aboutDialog());
        });
    }

    @Test public void fitsAt680By520WithFont18() throws Exception {
        SettingsPage[] page = new SettingsPage[1];
        SwingUtilities.invokeAndWait(() -> {
            page[0] = new SettingsPage(new JPanel(), () -> {}, new JPanel(), new JPanel(), new JPanel(), new JPanel(), section());
            page[0].showSection(SettingsPage.ABOUT);
            evidence.show(page[0], "Settings About", 680, 520, 18);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            AboutSection section = VisualEvidence.find(page[0], AboutSection.class, component -> true);
            JViewport viewport = VisualEvidence.find(section, JScrollPane.class, scroll -> true).getViewport();
            assertEquals("The section never scrolls sideways", viewport.getWidth(), viewport.getView().getWidth());
            VisualEvidence.reachable(named(section, "about-name", JLabel.class));
            VisualEvidence.completeButton(named(section, "settings-about-java-version", AbstractButton.class));
            VisualEvidence.completeButton(named(section, "settings-about-net-traffic", AbstractButton.class));
            for (JLabel label : labels(section)) {
                Rectangle placed = SwingUtilities.convertRectangle(label.getParent(), label.getBounds(), viewport.getView());
                assertTrue("'" + label.getText() + "' fits at 680 px, font 18: " + placed, placed.x >= 0 && placed.x + placed.width <= viewport.getWidth());
                assertTrue("'" + label.getText() + "' is whole", label.getWidth() >= label.getPreferredSize().width);
            }
            evidence.capture("settings-about-680-18");
        });
    }

    private static JDialog aboutDialog() {
        for (Window window : Window.getWindows())
            if (window instanceof JDialog && window.isDisplayable() && ("About " + AppIdentity.NAME).equals(((JDialog) window).getTitle())) return (JDialog) window;
        return null;
    }

    private static List<String> texts(Container root) {
        List<String> texts = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel) texts.add(((JLabel) child).getText());
            else if (child instanceof JTextArea) texts.add(((JTextArea) child).getText());
            else if (child instanceof AbstractButton) texts.add(((AbstractButton) child).getText());
            if (child instanceof JComponent && ((JComponent) child).getToolTipText() != null) texts.add(((JComponent) child).getToolTipText());
            if (child instanceof Container) texts.addAll(texts((Container) child));
        }
        return texts;
    }

    private static List<JLabel> labels(Container root) {
        List<JLabel> labels = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel && child.isVisible() && ((JLabel) child).getText() != null && !((JLabel) child).getText().isEmpty()) labels.add((JLabel) child);
            if (child instanceof Container) labels.addAll(labels((Container) child));
        }
        return labels;
    }
}
