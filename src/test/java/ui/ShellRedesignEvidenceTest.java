package ui;

import java.awt.*;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.ErrorCollector;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.modern.NavLayout;
import tomato.gui.modern.Themes;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.notifications.NotificationsGUI;
import tomato.gui.settings.AppearanceSection;
import tomato.gui.settings.SettingsPage;
import static org.junit.Assert.*;
import static ui.VisualEvidence.completeButton;
import static ui.VisualEvidence.completeText;
import static ui.VisualEvidence.named;

/** The regrouped sidebar, header, setup banner and Settings in both variants, wide and compact, fonts 13 and 18. */
public class ShellRedesignEvidenceTest {
    @Rule public final VisualEvidence evidence = new VisualEvidence("redesign-shell");
    @Rule public final ErrorCollector errors = new ErrorCollector();

    @After public void restoreTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
    }

    @Test public void sidebarHeaderBannerAndSettingsRenderInBothVariants() throws Exception {
        for (Themes.Variant variant : Themes.Variant.values())
            for (int[] size : new int[][] {{1240, 800}, {680, 520}})
                for (int font : new int[] {13, 18}) {
                    String suffix = variant.name().toLowerCase(Locale.ROOT) + "-" + size[0] + "-" + font;
                    WorkspaceShell[] shell = new WorkspaceShell[1];
                    SettingsPage[] settings = new SettingsPage[1];
                    SwingUtilities.invokeAndWait(() -> {
                        Themes.install(new Themes.Choice(variant, false));
                        settings[0] = new SettingsPage(new NotificationsGUI(), () -> {}, new AppearanceSection(() -> {}));
                        shell[0] = fixture(settings[0]);
                        evidence.show(shell[0], "Redesigned shell", size[0], size[1], font);
                    });
                    evidence.settle();
                    SwingUtilities.invokeAndWait(() -> {
                        evidence.capture("shell-" + suffix);
                        errors.checkSucceeds(() -> { assertShellIsWhole(shell[0], size[0] >= 1000); return null; });
                        shell[0].select(13);
                    });
                    evidence.settle();
                    SwingUtilities.invokeAndWait(() -> {
                        evidence.capture("settings-notifications-" + suffix);
                        settings[0].showSection(SettingsPage.APPEARANCE);
                    });
                    evidence.settle();
                    SwingUtilities.invokeAndWait(() -> {
                        evidence.capture("settings-appearance-" + suffix);
                        errors.checkSucceeds(() -> { assertSettingsAreWhole(shell[0]); return null; });
                    });
                    if (size[0] == 680 && font == 18) {
                        SwingUtilities.invokeAndWait(() -> shell[0].setSetupState("Assets ready", true, false));
                        evidence.settle();
                        SwingUtilities.invokeAndWait(() -> {
                            evidence.capture("settings-appearance-ready-" + suffix);
                            AbstractButton analyst = named(shell[0], "settings-display-mode-1", AbstractButton.class);
                            analyst.scrollRectToVisible(new Rectangle(analyst.getSize()));
                        });
                        evidence.settle();
                        SwingUtilities.invokeAndWait(() -> {
                            evidence.capture("settings-appearance-lower-" + suffix);
                            AbstractButton analyst = named(shell[0], "settings-display-mode-1", AbstractButton.class);
                            assertEquals("Lower Appearance controls are reachable through scrolling",
                                    analyst.getHeight(), analyst.getVisibleRect().height);
                            settings[0].showSection(SettingsPage.NOTIFICATIONS);
                        });
                        evidence.settle();
                        SwingUtilities.invokeAndWait(() -> evidence.capture("settings-notifications-ready-" + suffix));
                    }
                }
    }

    /** Advanced open, Timeline pinned, Chat hidden, assets missing: every new shell state in one frame. */
    private static WorkspaceShell fixture(SettingsPage settings) {
        Map<String, String> store = new HashMap<>();
        store.put(NavLayout.ADVANCED_KEY, "true");
        store.put(NavLayout.PINNED_KEY, "timeline");
        store.put(NavLayout.HIDDEN_KEY, "chat");
        JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length];
        for (int i = 0; i < pages.length; i++) {
            JPanel page = new JPanel(new BorderLayout());
            page.add(new JLabel("Synthetic " + WorkspaceShell.TITLES[i] + " page"), BorderLayout.NORTH);
            pages[i] = page;
        }
        pages[13] = settings;
        WorkspaceShell shell = new WorkspaceShell(pages, () -> fail("Evidence must not start capture"), false,
            () -> {}, () -> {}, () -> {}, new NavLayout(store::get, store::put), new DisplayModeModel(store::get, store::put));
        shell.selectLanding();
        shell.setSetupState("Assets missing or outdated · Choose resources.assets, or Retry assets if the game is installed. Browse saved history without capture.", false, false);
        return shell;
    }

    private static void assertShellIsWhole(WorkspaceShell shell, boolean wide) {
        if (wide) {
            for (int page = 0; page < WorkspaceShell.TITLES.length; page++) {
                AbstractButton row = named(shell, "nav-" + page, AbstractButton.class);
                if (row.isVisible()) completeButton(row);
            }
            completeButton(named(shell, "nav-advanced", AbstractButton.class));
        }
        for (String name : new String[] {"browse-history", "display-mode-0", "display-mode-1", "capture-toggle", "choose-assets", "retry-assets"})
            completeButton(named(shell, name, AbstractButton.class));
        completeText(named(shell, "capture-setup-message", JTextArea.class));
        for (String name : new String[] {"capture-pill", "page-title"}) {
            JLabel label = named(shell, name, JLabel.class);
            assertTrue(name + " is whole: " + label.getBounds(), label.isShowing() && label.getWidth() >= label.getPreferredSize().width);
        }
    }

    private static void assertSettingsAreWhole(WorkspaceShell shell) {
        for (String name : new String[] {"settings-section-notifications", "settings-section-appearance", "settings-theme-0", "settings-theme-1",
                "settings-increase-contrast", "settings-reduce-motion", "settings-display-mode-0", "settings-display-mode-1"})
            completeButton(named(shell, name, AbstractButton.class));
    }
}
