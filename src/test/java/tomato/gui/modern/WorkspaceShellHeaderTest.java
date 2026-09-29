package tomato.gui.modern;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;
import javax.swing.*;
import org.junit.Test;
import packets.packetcapture.CaptureState;
import tomato.gui.kit.Chip;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.KitButton;
import tomato.gui.kit.SegmentedControl;
import tomato.gui.kit.Tokens;
import static org.junit.Assert.*;
import static tomato.gui.modern.WorkspaceShellLayoutTest.named;
import static tomato.gui.modern.WorkspaceShellLayoutTest.resize;

public class WorkspaceShellHeaderTest {
    private final Map<String, String> store = new HashMap<>();
    private final DisplayModeModel mode = new DisplayModeModel(store::get, store::put);
    private final int[] browsed = {0};

    private WorkspaceShell shell(boolean preview) {
        Map<String, JComponent> pages = TestPages.placeholders();
        WorkspaceShell shell = new WorkspaceShell(pages, () -> {}, preview, () -> {}, () -> {}, () -> browsed[0]++,
            new NavLayout(store::get, store::put), mode);
        resize(shell, 1240, 800);
        return shell;
    }

    @Test public void headerShowsTheTitleWithItsDescriptionAsTooltipAndHistoryBesideTheModeSwitch() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell(false);
            shell.select("party");
            JLabel title = named(shell, "page-title", JLabel.class);
            assertEquals("Party", title.getText());
            assertEquals(NavEntry.forId("party").description(), title.getToolTipText());
            assertEquals(NavEntry.forId("party").description(), title.getAccessibleContext().getAccessibleDescription());
            assertNull("No subtitle line", visibleText(shell, NavEntry.forId("party").description()));
            JComponent header = named(shell, "workspace-header", JComponent.class);
            KitButton browse = named(shell, "browse-history", KitButton.class);
            assertEquals(KitButton.Variant.GHOST, browse.variant());
            assertTrue(SwingUtilities.isDescendingFrom(browse, header));
            browse.doClick();
            assertEquals(1, browsed[0]);
            assertTrue(SwingUtilities.isDescendingFrom(named(shell, "display-mode", SegmentedControl.class), header));
            assertEquals(KitButton.Variant.PRIMARY, named(shell, "capture-toggle", KitButton.class).variant());
        });
    }

    @Test public void modeSwitchAndCtrlShiftAToggleTheSharedDisplayMode() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell(false);
            SegmentedControl modes = named(shell, "display-mode", SegmentedControl.class);
            assertEquals(0, modes.selected());
            named(shell, "display-mode-1", AbstractButton.class).doClick();
            assertEquals(DisplayModeModel.Mode.ANALYST, mode.mode());
            assertEquals("analyst", store.get(DisplayModeModel.KEY));
            Object binding = shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
            assertEquals("toggle-display-mode", binding);
            shell.getActionMap().get(binding).actionPerformed(null);
            assertEquals(DisplayModeModel.Mode.SIMPLE, mode.mode());
            assertEquals("The switch follows the model", 0, modes.selected());
        });
    }

    @Test public void capturePillSummarizesTheCaptureState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell(false);
            Chip pill = named(shell, "capture-pill", Chip.class);
            assertTrue(pill.isVisible());
            assertEquals("Capture off", pill.getText());
            assertEquals(Tokens.Tone.NEUTRAL, pill.tone());
            shell.setCaptureState(true);
            assertEquals("Waiting for game", pill.getText());
            assertEquals(Tokens.Tone.WARN, pill.tone());
            shell.setCaptureReadiness(CaptureState.RECEIVING);
            assertEquals("Capturing", pill.getText());
            assertEquals(Tokens.Tone.GOOD, pill.tone());
            assertEquals("Capture status: Capturing", pill.getAccessibleContext().getAccessibleName());
            assertTrue(pill.getToolTipText(), pill.getToolTipText().contains("Receiving decoded game data"));
            shell.setCaptureFailure("Synthetic failure");
            assertEquals("Capture error", pill.getText());
            assertEquals(Tokens.Tone.BAD, pill.tone());
            assertEquals("Synthetic failure", named(shell, "capture-failure", JTextArea.class).getText());
            shell.setCaptureState(false);
            assertEquals("Capture off", pill.getText());
        });
    }

    @Test public void setupBannerAppearsOnlyWhenSetupNeedsAttention() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell(false);
            JComponent banner = named(shell, "setup-banner", JComponent.class);
            assertFalse("Ready assets need no banner", banner.isVisible());
            shell.setSetupState("Checking local assets… Saved history remains available.", false, true);
            assertTrue(banner.isVisible());
            assertFalse(named(shell, "retry-assets", JButton.class).isEnabled());
            assertFalse(named(shell, "capture-toggle", JButton.class).isEnabled());
            shell.setSetupState("Assets missing or outdated · Choose resources.assets.", false, false);
            assertTrue(banner.isVisible());
            assertTrue(named(shell, "choose-assets", JButton.class).isEnabled());
            assertTrue(named(shell, "retry-assets", JButton.class).isEnabled());
            assertEquals(KitButton.Variant.PRIMARY, named(shell, "choose-assets", KitButton.class).variant());
            assertTrue(named(shell, "browse-history", JButton.class).isEnabled());
            shell.setSetupState("Cached assets loaded · Source file unavailable; freshness unverified.", true, false);
            assertFalse(banner.isVisible());
            assertTrue(named(shell, "capture-toggle", JButton.class).isEnabled());
            assertTrue("A ready note moves to the pill tooltip",
                named(shell, "capture-pill", Chip.class).getToolTipText().contains("freshness unverified"));
        });
    }

    @Test public void previewShowsAChipInsteadOfTheBannerAndCaptureState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WorkspaceShell shell = shell(true);
            shell.setSetupState("Assets missing or outdated · Choose resources.assets.", false, false);
            assertFalse(named(shell, "setup-banner", JComponent.class).isVisible());
            Chip preview = named(shell, "preview-chip", Chip.class);
            assertTrue(preview.isVisible());
            assertEquals("Preview", preview.getText());
            assertFalse(named(shell, "capture-pill", Chip.class).isVisible());
            assertFalse(named(shell, "capture-toggle", JButton.class).isEnabled());
            assertFalse(named(shell, "choose-assets", JButton.class).isEnabled());
        });
    }

    @Test public void headerActionsWrapBelowTheTitleInsteadOfClipping() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Font previous = ContentStyle.body();
            try {
                ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 24));
                ContentStyle.applyFontDefaults();
                WorkspaceShell shell = shell(false);
                shell.setSetupState("Assets missing or outdated · Choose resources.assets.", false, false);
                ContentStyle.refreshFonts(shell);
                for (Dimension size : new Dimension[] {new Dimension(1240, 800), new Dimension(680, 520)}) {
                    resize(shell, size.width, size.height);
                    JComponent header = named(shell, "workspace-header", JComponent.class);
                    Rectangle bounds = new Rectangle(0, 0, header.getWidth(), header.getHeight());
                    for (String name : new String[] {"browse-history", "capture-pill", "display-mode", "capture-toggle", "page-title"}) {
                        JComponent part = named(shell, name, JComponent.class);
                        Rectangle placed = SwingUtilities.convertRectangle(part.getParent(), part.getBounds(), header);
                        assertTrue(name + " inside the header at " + size + ": " + placed + " in " + bounds, bounds.contains(placed));
                        if (!name.equals("page-title"))
                            assertTrue(name + " is not squeezed at " + size, part.getWidth() >= part.getPreferredSize().width);
                    }
                }
            } finally {
                ContentStyle.setBodyFont(previous);
                ContentStyle.applyFontDefaults();
            }
        });
    }

    private static Component visibleText(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child.isVisible() && child instanceof JLabel && text.equals(((JLabel) child).getText())) return child;
            if (child instanceof Container) { Component found = visibleText((Container) child, text); if (found != null) return found; }
        }
        return null;
    }
}
