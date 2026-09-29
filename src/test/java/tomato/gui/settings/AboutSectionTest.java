package tomato.gui.settings;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import realmshark.branding.AppIdentity;
import tomato.gui.maingui.AboutPanel;
import tomato.gui.maingui.TomatoMenuBar;
import tomato.gui.modern.Themes;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static tomato.gui.settings.LootFiltersSectionTest.find;
import static tomato.gui.settings.LootFiltersSectionTest.snifferField;
import static tomato.gui.settings.SettingsPageTest.named;

/** P6a: Settings › About shows the About dialog's content, the Java version and Net traffic; the dialog itself is unchanged. */
public class AboutSectionTest {
    @Rule public final VisualEvidence evidence = new VisualEvidence("redesign-p6a-settings");
    @Rule public final ErrorCollector errors = new ErrorCollector();
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

    /**
     * Polish A: the About content starts at the Diagnostics header's left edge, as every Settings section's header and body share
     * one edge (GeneralSection, AppearanceSection); at both reference sizes and in both themes.
     */
    @Test public void theAboutContentSharesTheDiagnosticsHeadersLeftEdge() throws Exception {
        SettingsPage[] page = new SettingsPage[1];
        SwingUtilities.invokeAndWait(() -> {
            page[0] = new SettingsPage(new JPanel(), () -> {}, new JPanel(), new JPanel(), new JPanel(), new JPanel(), section());
            page[0].showSection(SettingsPage.ABOUT);
            evidence.show(page[0], "Settings About 1240", 1240, 800, 13);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            captureAndCheck(page[0], "1240x800 font 13", "settings-about-edge-1240-13-dark");
            evidence.show(page[0], "Settings About 680", 680, 520, 18);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            captureAndCheck(page[0], "680x520 font 18", "settings-about-edge-680-18-dark");
            Themes.install(new Themes.Choice(Themes.Variant.LIGHT, false));
            SwingUtilities.updateComponentTreeUI(SwingUtilities.getWindowAncestor(page[0]));
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            captureAndCheck(page[0], "680x520 font 18, light", "settings-about-edge-680-18-light");
            evidence.show(page[0], "Settings About 1240 light", 1240, 800, 13);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> captureAndCheck(page[0], "1240x800 font 13, light", "settings-about-edge-1240-13-light"));
    }

    /** Captures first, then checks; the collector reports every size's failure at the end, so each size is captured and checked. */
    private void captureAndCheck(SettingsPage page, String size, String capture) {
        evidence.capture(capture);
        errors.checkSucceeds(() -> { assertOneLeftEdge(page, size); return null; });
    }

    /** Polish A: the dialog keeps its own 12 px border and AboutPanel adds no inset of its own there. */
    @Test public void theAboutDialogKeepsItsOwnBorder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            find(new TomatoMenuBar().make(), "About").doClick();
            JDialog dialog = aboutDialog();
            assertNotNull(dialog);
            Container content = dialog.getContentPane();
            assertEquals("The dialog's own border", new Insets(12, 12, 8, 12), ((JComponent) content).getInsets());
            AboutPanel about = VisualEvidence.find(content, AboutPanel.class, panel -> true);
            assertEquals("No inset inside the dialog's panel", new Insets(0, 0, 0, 0), about.getInsets());
            assertEquals("The logo starts at the dialog's border", 12, left(logo(about), content));
            evidence.capture(dialog, "about-dialog-13-dark");
        });
    }

    /** The logo and the description, credits and license lines start where the Diagnostics header's title starts (within 1 px). */
    private static void assertOneLeftEdge(SettingsPage page, String size) {
        AboutSection section = VisualEvidence.find(page, AboutSection.class, component -> true);
        Component view = VisualEvidence.find(section, JScrollPane.class, scroll -> true).getViewport().getView();
        int edge = left(VisualEvidence.find(section, JLabel.class, label -> "Diagnostics".equals(label.getText())), view);
        AboutPanel about = VisualEvidence.find(section, AboutPanel.class, panel -> true);
        List<JLabel> starts = new ArrayList<>();
        starts.add(logo(about));
        for (JLabel label : labels(about)) if (label.getName() == null) starts.add(label); // not about-name / about-version
        assertEquals("The logo and four text blocks", 5, starts.size());
        System.out.println(size + ": Diagnostics edge " + edge + ", buttons " + left(named(section, "settings-about-java-version", JComponent.class), view)
            + ", note " + left(named(section, "settings-about-diagnostics-help", JComponent.class), view));
        for (JLabel label : starts)
            assertEquals(size + ": '" + (label.getText() == null ? "logo" : label.getText()) + "' starts at the Diagnostics header's edge",
                edge, left(label, view), 1);
        int logoRight = left(logo(about), view) + logo(about).getWidth();
        assertTrue(size + ": the name sits beside the logo", left(named(about, "about-name", JLabel.class), view) > logoRight);
    }

    private static JLabel logo(AboutPanel about) { return VisualEvidence.find(about, JLabel.class, label -> label.getIcon() != null); }

    /** Where a component's content starts (its bounds plus its insets: border and margin), in {@code root}'s coordinates. */
    private static int left(JComponent component, Component root) {
        return SwingUtilities.convertPoint(component.getParent(), component.getX() + component.getInsets().left, 0, root).x;
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
