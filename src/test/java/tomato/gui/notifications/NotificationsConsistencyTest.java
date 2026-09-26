package tomato.gui.notifications;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.github.weisj.darklaf.LafManager;
import com.github.weisj.darklaf.theme.HighContrastLightTheme;
import java.awt.*;
import java.awt.event.ComponentEvent;
import java.util.Collections;
import javax.swing.*;
import javax.swing.text.BadLocationException;
import org.junit.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.VioletTheme;
import tomato.gui.modern.WorkspaceShell;
import static org.junit.Assert.*;

/** Exact offscreen client sizes; no claim about native window size on a scaled/clamped desktop. */
public class NotificationsConsistencyTest {
    private Font previousFont;
    private LookAndFeel previousLaf;

    @Before public void rememberTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> { previousFont = ContentStyle.body(); previousLaf = UIManager.getLookAndFeel(); });
    }
    @After public void restoreTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> { ContentStyle.setBodyFont(previousFont); setLaf(previousLaf); });
    }

    @Test public void longFilenameSavedPartialAndErrorStatusesFitWithoutSqueezingOutSettings() throws Exception {
        WorkspaceShell[] shell = new WorkspaceShell[1]; NotificationsGUI[] ui = new NotificationsGUI[1];
        String filename = String.join("", Collections.nCopies(180, "W")) + ".wav";
        String[] states = {"", "Saved short.wav. Use Test to hear it.", "Saved " + filename + ". Use Test to hear it.",
            "Sound unchanged: could not open " + filename + "\nCheck the selected folder.\nこんにちは ★ e\u0301 😀",
            "Partial: the saved sound is unavailable.\n" + filename + "\nOther alert settings are retained."};
        // Populate the app-global decision history so the Recent decisions table (and its scroll bar) is
        // exercised deterministically, independent of which tests ran earlier in this JVM.
        tomato.realmshark.AlertDecisions.INSTANCE.clear();
        for (int i = 0; i < 40; i++) tomato.realmshark.AlertDecisions.INSTANCE.record(
            new tomato.realmshark.AlertDecisions.Entry(tomato.realmshark.AlertDecisions.Source.CHAT).subject("Synthetic message " + i).explain("Matched"));
        SwingUtilities.invokeAndWait(() -> {
            theme(new VioletTheme(), 13); ui[0] = new NotificationsGUI();
            JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length];
            for (int i = 0; i < pages.length; i++) pages[i] = i == 13 ? ui[0] : new JPanel();
            shell[0] = new WorkspaceShell(pages, () -> {}, true); shell[0].select(13);
        });
        for (LookAndFeel laf : new LookAndFeel[] {new VioletTheme(), new FlatLightLaf(), new FlatDarkLaf()}) {
            for (int font : new int[] {13, 16, 24, 13}) for (Dimension geometry : new Dimension[] {new Dimension(1240, 800), new Dimension(680, 520)}) {
                SwingUtilities.invokeAndWait(() -> {
                    theme(laf, font); SwingUtilities.updateComponentTreeUI(shell[0]); ContentStyle.refreshFonts(shell[0]);
                    shell[0].setSize(geometry); shell[0].dispatchEvent(new ComponentEvent(shell[0], ComponentEvent.COMPONENT_RESIZED));
                });
                for (String state : states) {
                    SwingUtilities.invokeAndWait(() -> status(ui[0]).setText(state));
                    settle(shell[0]);
                    SwingUtilities.invokeAndWait(() -> {
                        assertEquals("Exact offscreen client dimensions", geometry, shell[0].getSize());
                        JTextArea status = status(ui[0]);
                        assertEquals(font * 12f / 13, status.getFont().getSize2D(), .01f);
                        assertEquals(state, status.getText()); assertWrappingFits(status);
                        assertTrue("Settings retain usable height while a long status scrolls", ui[0].tabs.getHeight() >= ui[0].tabs.getFontMetrics(ui[0].tabs.getFont()).getHeight() * 6);
                        assertReachable(ui[0].master, new Rectangle(0, 0, ui[0].master.getWidth(), ui[0].master.getHeight()));
                        assertReachable(ui[0].mute, new Rectangle(0, 0, ui[0].mute.getWidth(), ui[0].mute.getHeight()));
                    });
                }
                // Inspect the actual page headers and every enabled action in each selected section.
                for (int tab = 0; tab < ui[0].tabs.getTabCount(); tab++) {
                    final int selected = tab;
                    SwingUtilities.invokeAndWait(() -> ui[0].tabs.setSelectedIndex(selected));
                    settle(shell[0]);
                    SwingUtilities.invokeAndWait(() -> {
                        JScrollPane section = section(ui[0]);
                        assertTrue("A section viewport remains usable", section.getViewport().getHeight() >= ui[0].tabs.getFontMetrics(ui[0].tabs.getFont()).getHeight() * 4);
                        assertContentReachable((Container) section.getViewport().getView());
                        assertWrappingFits(status(ui[0]));
                    });
                }
                SwingUtilities.invokeAndWait(() -> System.out.println("Offscreen Notifications shell=" + geometry + ", theme=" + laf.getName() + ", font=" + font));
            }
        }
    }

    @Test public void filenameStatusUsesActualSwingHeightAtTheReviewed590PixelWidth() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            theme(new VioletTheme(), 13); NotificationsGUI ui = new NotificationsGUI();
            JTextArea status = status(ui);
            String filename = String.join("", Collections.nCopies(180, "W")) + ".wav";
            for (int font : new int[] {13, 16, 24, 13}) {
                ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, font)); ContentStyle.refreshFonts(ui);
                status.setSize(590, 1); status.setText("Saved " + filename + ". Use Test to hear it.");
                JTextArea reference = new JTextArea(status.getText()); reference.setFont(status.getFont()); reference.setBorder(status.getBorder());
                reference.setLineWrap(true); reference.setWrapStyleWord(true); reference.setSize(590, 10000);
                int required = reference.getUI().getPreferredSize(reference).height;
                assertEquals("Actual Swing view, not word count: font=" + font, required, status.getPreferredSize().height);
                int longHeight = status.getPreferredSize().height;
                status.setText("Saved short.wav. Use Test to hear it.");
                assertTrue("Long token must allocate additional lines", longHeight > status.getPreferredSize().height);
            }
        });
    }

    @Test public void displayableNestedStacksSurviveNativeResizeAndLegacyThemeTransitions() throws Exception {
        JFrame[] frame = new JFrame[1]; NotificationsGUI[] ui = new NotificationsGUI[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                theme(new VioletTheme(), 13); ui[0] = new NotificationsGUI();
                frame[0] = new JFrame(); frame[0].setContentPane(ui[0]); frame[0].setSize(820, 620); frame[0].setVisible(true);
            });
            for (int step = 0; step < 3; step++) {
                final boolean legacy = step == 1;
                SwingUtilities.invokeAndWait(() -> {
                    if (legacy) LafManager.install(new HighContrastLightTheme());
                    else setLaf(new VioletTheme());
                    ContentStyle.applyFontDefaults(); SwingUtilities.updateComponentTreeUI(frame[0]); ContentStyle.refreshFonts(frame[0]);
                });
                for (int width : new int[] {820, 500, 680, 820}) for (int tab = 0; tab < ui[0].tabs.getTabCount(); tab++) {
                    final int selected = tab;
                    SwingUtilities.invokeAndWait(() -> {
                        ui[0].tabs.setSelectedIndex(selected); frame[0].setSize(width, 620);
                        // Displayable validation exercises ancestor invalidation during BoxLayout's size calculation.
                        frame[0].validate();
                    });
                    for (int turn = 0; turn < 10; turn++) SwingUtilities.invokeAndWait(() -> frame[0].validate());
                    SwingUtilities.invokeAndWait(() -> {
                        JScrollPane section = section(ui[0]);
                        assertTrue(section.getViewport().getHeight() >= ui[0].tabs.getFontMetrics(ui[0].tabs.getFont()).getHeight() * 4);
                        assertContentReachable((Container) section.getViewport().getView());
                        assertWrappingFits(status(ui[0]));
                    });
                }
            }
        } finally { SwingUtilities.invokeAndWait(() -> { if (frame[0] != null) frame[0].dispose(); }); }
    }

    @Test public void unfocusedSoundStatusUpdatesDoNotScrollAwayFromNotificationSettings() throws Exception {
        JFrame[] frame = new JFrame[1]; NotificationsGUI[] ui = new NotificationsGUI[1];
        String saved = "Saved " + String.join("", Collections.nCopies(184, "W")) + ".wav. Use Test to hear it.";
        try {
            SwingUtilities.invokeAndWait(() -> {
                theme(new VioletTheme(), 24); ui[0] = new NotificationsGUI();
                frame[0] = new JFrame(); frame[0].setContentPane(ui[0]); frame[0].setSize(520, 450); frame[0].setVisible(true);
            });
            for (LookAndFeel laf : new LookAndFeel[] {new VioletTheme(), new FlatLightLaf(), new VioletTheme()}) {
                SwingUtilities.invokeAndWait(() -> {
                    theme(laf, 24); SwingUtilities.updateComponentTreeUI(frame[0]); ContentStyle.refreshFonts(frame[0]);
                });
                for (int position : new int[] {0, 40}) {
                    SwingUtilities.invokeAndWait(() -> status(ui[0]).setText("Ready. Settings are retained."));
                    for (int turn = 0; turn < 12; turn++) SwingUtilities.invokeAndWait(() -> frame[0].validate());
                    SwingUtilities.invokeAndWait(() -> {
                        JScrollPane page = (JScrollPane) ui[0].getComponent(0);
                        assertEquals("notifications-page", page.getName());
                        page.getViewport().setViewPosition(new Point(0, position));
                        assertFalse(status(ui[0]).isFocusOwner());
                        status(ui[0]).setText(saved);
                    });
                    for (int turn = 0; turn < 12; turn++) SwingUtilities.invokeAndWait(() -> frame[0].validate());
                    SwingUtilities.invokeAndWait(() -> {
                        JScrollPane page = (JScrollPane) ui[0].getComponent(0);
                        assertEquals("Unfocused status publication preserves the settings viewport", new Point(0, position), page.getViewport().getViewPosition());
                        assertEquals(saved, status(ui[0]).getText());
                        assertTrue("The complete status is still allocated", status(ui[0]).getHeight() >= status(ui[0]).getUI().getPreferredSize(status(ui[0])).height);
                        if (position == 0) assertEquals("The master-volume control stays at the top", ui[0].master.getHeight(), ui[0].master.getVisibleRect().height);
                    });
                }
            }
        } finally { SwingUtilities.invokeAndWait(() -> { if (frame[0] != null) frame[0].dispose(); }); }
    }

    private static void assertContentReachable(Container root) {
        for (Component child : root.getComponents()) {
            if (!child.isVisible()) continue;
            // Scroll bars are viewport chrome, not page actions; FlatLaf sizes their hidden arrow buttons to 0 height.
            if (child instanceof JScrollBar) continue;
            if (child instanceof JTextArea && !((JTextArea) child).isEditable()) assertWrappingFits((JTextArea) child);
            else if (child instanceof AbstractButton && child.isEnabled()) {
                AbstractButton button = (AbstractButton) child;
                if (button.getText() != null && !button.getText().isEmpty()) {
                    Insets i = button.getInsets(); Rectangle icon = new Rectangle(), text = new Rectangle();
                    Rectangle available = new Rectangle(i.left, i.top, button.getWidth() - i.left - i.right, button.getHeight() - i.top - i.bottom);
                    String painted = SwingUtilities.layoutCompoundLabel(button, button.getFontMetrics(button.getFont()), button.getText(), button.getIcon(),
                        button.getVerticalAlignment(), button.getHorizontalAlignment(), button.getVerticalTextPosition(), button.getHorizontalTextPosition(),
                        available, icon, text, button.getIconTextGap());
                    assertEquals("Complete action label", button.getText(), painted);
                }
                assertReachable(button, new Rectangle(0, 0, button.getWidth(), button.getHeight()));
            } else if (child instanceof Container) assertContentReachable((Container) child);
        }
    }
    private static void assertWrappingFits(JTextArea area) {
        assertTrue("Complete text view for " + area.getName() + ": " + area.getSize(),
            area.getHeight() >= area.getUI().getPreferredSize(area).height);
        try {
            Rectangle end = area.modelToView(area.getDocument().getLength()); assertNotNull(end);
            assertReachable(area, end);
        } catch (BadLocationException e) { throw new AssertionError(e); }
    }
    private static void assertReachable(JComponent component, Rectangle region) {
        for (Container parent = component.getParent(); parent != null; parent = parent.getParent()) if (parent instanceof JViewport) {
            JComponent view = (JComponent) ((JViewport) parent).getView();
            view.scrollRectToVisible(SwingUtilities.convertRectangle(component, region, view));
        }
        assertTrue("Scroll-reachable " + component.getClass().getSimpleName() + " " + component.getName() + ": " + region + " in " + component.getVisibleRect(), component.getVisibleRect().contains(region));
    }
    private static void settle(Container root) throws Exception {
        for (int turn = 0; turn < 10; turn++) SwingUtilities.invokeAndWait(() -> layoutTree(root));
    }
    private static void layoutTree(Container root) {
        root.doLayout(); for (Component child : root.getComponents()) if (child instanceof Container && child.isVisible()) layoutTree((Container) child);
    }
    private static JTextArea status(Container root) {
        for (Component child : root.getComponents()) {
            if ("sound-status".equals(child.getName())) return (JTextArea) child;
            if (child instanceof Container) { JTextArea status = status((Container) child); if (status != null) return status; }
        }
        return null;
    }
    private static void theme(LookAndFeel laf, int size) {
        ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, size)); setLaf(laf); ContentStyle.applyFontDefaults();
    }
    private static void setLaf(LookAndFeel laf) {
        try { UIManager.setLookAndFeel(laf); } catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
    }

    /** The selected section's scrolling page; Recent decisions hosts its own page so its table can reveal rows itself. */
    private static JScrollPane section(NotificationsGUI ui) {
        java.awt.Component selected = ui.tabs.getSelectedComponent();
        return selected instanceof RecentDecisionsPanel ? ((RecentDecisionsPanel) selected).page : (JScrollPane) selected;
    }
}
