package tomato.gui.quest;

import com.github.weisj.darklaf.LafManager;
import com.github.weisj.darklaf.theme.HighContrastLightTheme;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.table.TableColumn;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import org.junit.*;
import packets.data.QuestData;
import tomato.gui.TomatoGUI;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.VioletTheme;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.notifications.NotificationsGUI;
import static org.junit.Assert.*;

/** Exact logical shell geometry plus realized-window, posted-AWT-key integration; no capture or native key injection. */
public class QuestConsistencyTest {
    private static final Dimension[] CLIENTS = {new Dimension(1240, 800), new Dimension(680, 520)};
    private static final int[] FONTS = {13, 16, 24};
    private static final String TOKEN = String.join("", Collections.nCopies(160, "W"));
    private static final String LONG_NAME = "00 Quest_" + TOKEN + "_東京";
    private static final String DESCRIPTION = "Bring these captured requirements.\n" + TOKEN + "\n最後の説明 e\u0301 ★";
    private static final String EXPIRATION = "Server supplied expiration: " + TOKEN + "\nFinal expiration line";
    private static final String CATEGORY = "Event category_" + TOKEN;
    private QuestGuiTest.MemoryPreferences preferences;
    private QuestGUI quest;
    private WorkspaceShell shell;
    private JFrame frame;
    private Font oldFont;
    private LookAndFeel oldLaf;

    @Before public void rememberPresentation() throws Exception {
        preferences = new QuestGuiTest.MemoryPreferences();
        SwingUtilities.invokeAndWait(() -> {
            oldFont = ContentStyle.body(); oldLaf = UIManager.getLookAndFeel();
            ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 13));
            VioletTheme.install(); ContentStyle.applyFontDefaults();
        });
    }

    @After public void restorePresentation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MenuSelectionManager.defaultManager().clearSelectedPath();
            if (frame != null) {
                for (Window owned : frame.getOwnedWindows()) owned.dispose();
                frame.dispose();
            }
            ContentStyle.setBodyFont(oldFont);
            try { UIManager.setLookAndFeel(oldLaf); }
            catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
            ContentStyle.applyFontDefaults();
        });
    }

    private void createShell(boolean mixedPages) {
        quest = new QuestGUI(id -> {
            if (id == 998) throw new IllegalStateException("Unavailable item definitions");
            if (id == 999) return null;
            if (id == 10) return "Standard Quest Chest";
            if (id == 11) return "Mighty Quest Chest";
            return "Item " + id + " " + TOKEN;
        }, id -> {
            if (id == 998) throw new IllegalStateException("Unavailable icon");
            return null;
        }, preferences);
        JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length];
        Arrays.setAll(pages, i -> new JPanel());
        pages[5] = quest;
        if (mixedPages) pages[13] = new NotificationsGUI();
        shell = new WorkspaceShell(pages, () -> fail("Preview must never invoke capture"), true);
        shell.select(5); ContentStyle.refreshFonts(shell);
        // Offscreen JTable has no addNotify to install its header in the enclosing scroll pane.
        scroll("quest-list-scroll").setColumnHeaderView(table().getTableHeader());
    }

    private static QuestData record(String id, String name, int category) {
        QuestData value = new QuestData();
        value.id = id; value.name = name; value.category = category;
        value.requirements = new int[]{1, 1, 2}; value.rewards = new int[]{10};
        value.description = "Captured requirement and reward data.";
        return value;
    }

    private QuestData[] records() {
        QuestData[] values = new QuestData[24];
        for (int i = 0; i < values.length; i++) values[i] = record("quest-" + i, String.format(java.util.Locale.ROOT, "%02d Quest", i), i % 3);
        return values;
    }

    private QuestData longRecord() {
        QuestData value = record("long-quest", LONG_NAME, 77);
        value.description = DESCRIPTION; value.expiration = EXPIRATION;
        value.requirements = new int[]{1, 1, 2, 3, 4, 5, 6, 998, 999};
        value.rewards = new int[]{10, 11, 998, 999};
        value.itemOfChoice = true; value.repeatable = true; value.completed = true;
        return value;
    }

    @Test public void exactShellMatrixKeepsAllControlsRowsAndDividerEndpointsReachable() throws Exception {
        SwingUtilities.invokeAndWait(() -> createShell(false));
        // Reuse the shell through width/font round trips, including shrinking back from 24pt.
        for (int state = 0; state < 4; state++) {
            final int selectedState = state;
            for (int font : new int[]{13, 16, 24, 13}) for (Dimension client : new Dimension[]{CLIENTS[0], CLIENTS[1], CLIENTS[0]}) {
                SwingUtilities.invokeAndWait(() -> {
                    if (selectedState != 0) {
                        button("quest-reset").doClick();
                        quest.update(selectedState == 1 ? new QuestData[0] : records());
                        if (selectedState == 3) search().setText("no-such-quest");
                    }
                    setGeometry(font, client);
                });
                settle(shell);
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals(client, shell.getSize());
                    assertNull("Exact-size fixture must not depend on desktop size", SwingUtilities.getWindowAncestor(shell));
                    assertEquals(selectedState == 2 ? 24 : 0, table().getRowCount());
                    assertEquals(selectedState == 2, button("quest-pin").isEnabled());
                    assertUsableViewports(); assertControlsReachable(); assertWrappedText(quest, true);
                    if (selectedState == 2) {
                        assertTableGeometry();
                        reachableCell(0, 0); reachableCell(table().getRowCount() - 1, table().getColumnCount() - 1);
                    } else {
                        assertNull(find(quest, "quest-detail-title", JTextArea.class));
                        assertTrue(text(quest).contains(selectedState == 0 ? "Plan your next turn-in" : "No matching quests"));
                    }
                    JSplitPane split = named(quest, "quest-list-detail-split", JSplitPane.class);
                    int original = split.getDividerLocation();
                    assertTrue(split.getMaximumDividerLocation() >= split.getMinimumDividerLocation());
                    for (int position : new int[]{0, split.getHeight()}) {
                        split.setDividerLocation(position); layout(shell); assertUsableViewports();
                    }
                    split.setDividerLocation(original); layout(shell);
                });
            }
        }
    }

    @Test public void longDetailsAndNullOrFailingLookupsAllocateEveryFinalLineAcrossMatrix() throws Exception {
        preferences.put("category.77", CATEGORY);
        SwingUtilities.invokeAndWait(() -> createShell(false));
        for (int font : FONTS) for (Dimension client : CLIENTS) {
            SwingUtilities.invokeAndWait(() -> quest.update(new QuestData[]{longRecord()}));
            geometry(font, client);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(LONG_NAME, table().getValueAt(0, 1));
                JPanel detail = named(quest, "quest-details", JPanel.class);
                String content = text(detail);
                for (String required : new String[]{LONG_NAME, CATEGORY, DESCRIPTION, EXPIRATION,
                        "BRING • all required items", "CHOOSE ONE • reward options", "2 × Item 1 " + TOKEN,
                        "Unknown item #998", "Unknown item #999", "Server category: 77"}) assertTrue(required, content.contains(required));
                assertUsableViewports(); assertControlsReachable(); assertWrappedText(detail, true);
                assertEquals(CATEGORY, named(quest, "quest-type", JComboBox.class).getItemAt(1));
                JLabel reward = (JLabel)table().prepareRenderer(table().getCellRenderer(0, 3), 0, 3);
                assertEquals(table().getValueAt(0, 3), reward.getToolTipText());
                assertTableGeometry();
                // Same server ID, but absent optional strings/items: no previous description or choices survive.
                QuestData empty = record("long-quest", "Empty server fields", 77);
                empty.requirements = null; empty.rewards = null; empty.description = null; empty.expiration = null;
                quest.update(new QuestData[]{null, empty});
            });
            settle(shell);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(1, table().getRowCount()); assertNull(table().getValueAt(0, 4));
                assertTrue(text(quest).contains("Not captured; requirements/rewards unknown."));
                assertFalse(text(quest).contains(DESCRIPTION)); assertFalse(text(quest).contains(EXPIRATION));
                assertFalse(text(quest).contains("CHOOSE ONE"));
                assertWrappedText(quest, true); assertUsableViewports();
            });
        }
    }

    @Test public void nativeCompactRequestRevealsKeyboardTargetsAcrossFontsAndThemes() throws Exception {
        nativeMatrix(new Dimension(680, 520));
    }

    @Test public void nativeDesktopRequestRevealsKeyboardTargetsAcrossFontsAndThemes() throws Exception {
        nativeMatrix(new Dimension(1240, 800));
    }

    private void nativeMatrix(Dimension requested) throws Exception {
        openFrame(requested, false);
        for (int theme = 0; theme < 3; theme++) {
            final int themeIndex = theme;
            final boolean legacy = theme == 1;
            SwingUtilities.invokeAndWait(() -> {
                if (legacy) LafManager.install(new HighContrastLightTheme()); else VioletTheme.install();
                SwingUtilities.updateComponentTreeUI(frame);
            });
            for (int font : FONTS) {
                SwingUtilities.invokeAndWait(() -> {
                    font(font); quest.update(records()); table().setRowSelectionInterval(0, 0);
                    requestClient(requested);
                });
                settle(frame);
                SwingUtilities.invokeAndWait(() -> {
                    nativeGeometry(requested);
                    assertUsableViewports(); assertControlsReachable(); assertWrappedText(quest, true);
                });
                // Put the page elsewhere, then rely solely on production focus handling to reveal controls.
                awaitFocus(table());
                SwingUtilities.invokeAndWait(() -> scroll("quest-page-scroll").getVerticalScrollBar().setValue(Integer.MAX_VALUE));
                awaitFocus(search());
                SwingUtilities.invokeAndWait(() -> fullyVisible(search()));
                SwingUtilities.invokeAndWait(() -> {
                    scroll("quest-page-scroll").getVerticalScrollBar().setValue(Integer.MAX_VALUE);
                    if (requested.width == 680 && font == 24)
                        assertTrue("Exercise backward traversal with the summary offscreen", named(quest, "quest-summary", JTextArea.class).getVisibleRect().isEmpty());
                });
                postKey(search(), KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK);
                settle(frame);
                SwingUtilities.invokeAndWait(() -> assertFocusMovedPastStaticLabels(search()));
                awaitFocus(search());
                postKey(search(), KeyEvent.VK_TAB, 0);
                SwingUtilities.invokeAndWait(() -> {
                    JComboBox<?> type = named(quest, "quest-type", JComboBox.class);
                    assertSame("Posted Tab must traverse into the first filter", type, KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
                    fullyVisible(type);
                });
                awaitFocus(table()); postKey(table(), KeyEvent.VK_DOWN, 0);
                settle(frame);
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals(1, table().getSelectedRow());
                    assertEquals(table().getValueAt(1, 1), named(quest, "quest-detail-title", JTextArea.class).getText());
                    scroll("quest-page-scroll").getVerticalScrollBar().setValue(0);
                });
                awaitFocus(button("quest-pin"));
                SwingUtilities.invokeAndWait(() -> fullyVisible(button("quest-pin")));
                SwingUtilities.invokeAndWait(() -> {
                    scroll("quest-page-scroll").getVerticalScrollBar().setValue(0);
                    if (requested.width == 680 && font == 24)
                        assertTrue("Exercise backward traversal with the footer offscreen", named(quest, "quest-count", JTextArea.class).getVisibleRect().isEmpty());
                });
                postKey(button("quest-pin"), KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK);
                settle(frame);
                SwingUtilities.invokeAndWait(() -> assertFocusMovedPastStaticLabels(button("quest-pin")));
                awaitFocus(button("quest-pin"));
                postKey(button("quest-pin"), KeyEvent.VK_SPACE, 0);
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals("Unpin quest", button("quest-pin").getText());
                    assertEquals("Yes", table().getValueAt(table().getSelectedRow(), 0));
                });
                postKey(button("quest-pin"), KeyEvent.VK_SPACE, 0);
                SwingUtilities.invokeAndWait(() -> assertEquals("Pin quest", button("quest-pin").getText()));
                SwingUtilities.invokeAndWait(() -> quest.update(new QuestData[]{longRecord()}));
                settle(frame);
                JTextArea description = textArea(named(quest, "quest-details", JPanel.class), DESCRIPTION);
                assertNotNull(description); awaitFocus(description);
                postKey(description, KeyEvent.VK_END, InputEvent.CTRL_DOWN_MASK);
                settle(frame);
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals(description.getDocument().getLength(), description.getCaretPosition());
                    assertTrue("Keyboard caret navigation must reveal the last description line", description.getVisibleRect().contains(end(description)));
                    if (!legacy && font == 24) screenshot("quests-native-" + requested.width + "-" + themeIndex + ".png");
                    search().setText("no-match");
                });
                awaitFocus(button("quest-reset")); postKey(button("quest-reset"), KeyEvent.VK_SPACE, 0);
                settle(frame);
                SwingUtilities.invokeAndWait(() -> { assertEquals(1, table().getRowCount()); assertTrue(button("quest-pin").isEnabled()); });
            }
        }
    }

    @Test public void nameTypesDialogScrollsEditorsAndKeyboardCancelThenOkPreserveMeaning() throws Exception {
        openFrame(new Dimension(680, 520), false);
        for (int font : FONTS) {
            SwingUtilities.invokeAndWait(() -> {
                font(font);
                QuestData[] values = records();
                for (int i = 0; i < values.length; i++) {
                    values[i].category = i; values[i].name += " " + TOKEN;
                    preferences.put("category." + i, "Original " + i + (i == 23 ? " " + TOKEN : ""));
                }
                // Use a newly built panel so the fixture's initial labels are read once, as in production.
                frame.dispose(); createShell(false); quest.update(values);
                frame = new JFrame("Quest category validation"); frame.setContentPane(shell); requestClient(new Dimension(680, 520)); frame.setVisible(true);
            });
            settle(frame);
            JDialog cancelled = openTypesDialog();
            exerciseCategoryEditors(cancelled);
            JTextField last = editor(cancelled, 23);
            SwingUtilities.invokeAndWait(() -> last.setText("Must not save"));
            JButton cancel = named(cancelled, "quest-types-cancel", JButton.class);
            awaitFocus(cancel); postKey(cancel, KeyEvent.VK_SPACE, 0);
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(cancelled.isDisplayable()); assertEquals("Original 23 " + TOKEN, preferences.get("category.23", null));
            });
            JDialog accepted = openTypesDialog();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Original 23 " + TOKEN, editor(accepted, 23).getText());
                editor(accepted, 23).setText("  Event label " + TOKEN + "  ");
            });
            JButton ok = named(accepted, "quest-types-ok", JButton.class);
            awaitFocus(ok); postKey(ok, KeyEvent.VK_SPACE, 0);
            settle(frame);
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(accepted.isDisplayable());
                assertEquals("Event label " + TOKEN, preferences.get("category.23", null));
                JComboBox<?> type = named(quest, "quest-type", JComboBox.class);
                type.setSelectedItem("Event label " + TOKEN);
                assertEquals(1, table().getRowCount()); assertTrue(table().getValueAt(0, 1).toString().startsWith("23 Quest"));
                assertTrue(text(named(quest, "quest-details", JPanel.class)).contains("Event label " + TOKEN));
            });
        }
    }

    @Test public void nameTypesBeforeCaptureOpensExistingInformationPromptAndCanCloseByKeyboard() throws Exception {
        openFrame(new Dimension(680, 520), false);
        SwingUtilities.invokeAndWait(() -> font(24));
        JDialog dialog = openOwnedDialog(button("quest-name-types"), null);
        SwingUtilities.invokeAndWait(() -> assertTrue(text(dialog).contains("Enter the Daily Quest Room during capture first.")));
        JButton ok = findButton(dialog, "OK"); assertNotNull(ok);
        awaitFocus(ok); postKey(ok, KeyEvent.VK_SPACE, 0);
        SwingUtilities.invokeAndWait(() -> { assertFalse(dialog.isShowing()); dialog.dispose(); assertFalse(button("quest-pin").isEnabled()); });
    }

    @Test public void applicationQuestPublicationWhileHiddenSurvivesMixedPageFontAndThemeChanges() throws Exception {
        openFrame(new Dimension(680, 520), true);
        Field entry = TomatoGUI.class.getDeclaredField("questPanel"); entry.setAccessible(true);
        AtomicReference<Object> previous = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { previous.set(entry.get(null)); entry.set(null, quest); }
            catch (IllegalAccessException e) { throw new AssertionError(e); }
        });
        try {
            QuestData value = record("wiring", "Selected before navigation", 2);
            TomatoGUI.updateQuests(new QuestData[]{value});
            settle(frame);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Selected before navigation", table().getValueAt(0, 1));
                search().setText("selected"); shell.select(13);
                assertFalse(quest.isShowing()); assertTrue(named(shell, "sound-master", JSlider.class).isShowing());
                LafManager.install(new HighContrastLightTheme()); SwingUtilities.updateComponentTreeUI(frame); font(24);
                shell.refreshTheme();
            });
            QuestData replacement = record("wiring", "Selected after hidden publication", 2);
            replacement.description = "Latest detached quest payload";
            SwingUtilities.invokeAndWait(() -> {
                Thread producer = new Thread(() -> TomatoGUI.updateQuests(new QuestData[]{replacement}), "quest-wiring-producer");
                producer.start();
                try { producer.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                assertFalse("Publication must not wait for EDT", producer.isAlive());
                replacement.name = "Unpublished mutation"; replacement.description = "Unpublished description";
                replacement.requirements[0] = 123456;
            });
            settle(frame);
            SwingUtilities.invokeAndWait(() -> {
                VioletTheme.install(); SwingUtilities.updateComponentTreeUI(frame); font(24);
                shell.refreshTheme(); shell.select(5);
            });
            settle(frame);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(5, shell.getSelectedPage()); assertEquals("selected", search().getText());
                assertEquals("Selected after hidden publication", table().getValueAt(table().getSelectedRow(), 1));
                String content = text(named(quest, "quest-details", JPanel.class));
                assertTrue(content.contains("Latest detached quest payload")); assertFalse(content.contains("Unpublished"));
                assertFalse(content.contains("123456"));
                assertTrue(button("quest-pin").isEnabled()); assertUsableViewports(); assertControlsReachable();
                assertFalse(named(shell, "capture-toggle", AbstractButton.class).isEnabled());
                Action capture = shell.getActionMap().get("capture");
                capture.actionPerformed(new ActionEvent(shell, ActionEvent.ACTION_PERFORMED, "capture"));
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                try { entry.set(null, previous.get()); }
                catch (IllegalAccessException e) { throw new AssertionError(e); }
            });
        }
    }

    private void exerciseCategoryEditors(JDialog dialog) throws Exception {
        settle(dialog);
        SwingUtilities.invokeAndWait(() -> {
            System.out.println("Quest types native outer=" + dialog.getSize() + ", client=" + dialog.getContentPane().getSize()
                + ", font=" + ContentStyle.body().getSize() + ", transform=" + dialog.getGraphicsConfiguration().getDefaultTransform());
            assertTrue(named(dialog, "quest-types-scroll", JScrollPane.class).getVerticalScrollBar().isVisible());
            assertWrappedText(dialog, true);
        });
        for (int category = 0; category < 24; category++) {
            JTextField field = editor(dialog, category);
            awaitFocus(field);
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(field.getHeight() >= field.getPreferredSize().height); fullyVisible(field);
                assertNotNull(field.getAccessibleContext().getAccessibleName());
            });
        }
        JTextField last = editor(dialog, 23);
        postKey(last, KeyEvent.VK_END, InputEvent.CTRL_DOWN_MASK);
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(last.getDocument().getLength(), last.getCaretPosition());
            assertTrue("The complete long category label is keyboard-readable", last.getVisibleRect().contains(end(last)));
        });
        postKey(last, KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK);
        settle(dialog);
        SwingUtilities.invokeAndWait(() -> {
            assertSame("Shift+Tab skips category captions and reaches the previous editor", editor(dialog, 22),
                KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
            fullyVisible(editor(dialog, 22));
        });
        SwingUtilities.invokeAndWait(() -> {
            fullyVisible(named(dialog, "quest-types-ok", JButton.class));
            fullyVisible(named(dialog, "quest-types-cancel", JButton.class));
        });
    }

    private static JTextField editor(JDialog dialog, int category) {
        return (JTextField)named(dialog, "quest-category-" + category, JComboBox.class).getEditor().getEditorComponent();
    }

    private JDialog openTypesDialog() throws Exception { return openOwnedDialog(button("quest-name-types"), "quest-types-dialog"); }

    private JDialog openOwnedDialog(JButton opener, String name) throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        AtomicReference<JDialog> result = new AtomicReference<>();
        AWTEventListener windows = event -> {
            if (event.getID() != WindowEvent.WINDOW_OPENED || !(event.getSource() instanceof JDialog)) return;
            JDialog dialog = (JDialog)event.getSource();
            if (dialog.getOwner() == frame && (name == null || name.equals(dialog.getName()))) { result.set(dialog); opened.countDown(); }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(windows, AWTEvent.WINDOW_EVENT_MASK);
        try {
            awaitFocus(opener); postKey(opener, KeyEvent.VK_SPACE, 0);
            assertTrue("Keyboard action must open the real owned dialog", opened.await(5, TimeUnit.SECONDS));
            return result.get();
        } finally { Toolkit.getDefaultToolkit().removeAWTEventListener(windows); }
    }

    private void openFrame(Dimension requested, boolean mixed) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            createShell(mixed);
            frame = new JFrame("Quest shell validation"); frame.setContentPane(shell);
            requestClient(requested); frame.setVisible(true);
        });
        settle(frame);
    }

    private void requestClient(Dimension requested) {
        frame.pack(); Insets insets = frame.getInsets();
        frame.setSize(requested.width + insets.left + insets.right, requested.height + insets.top + insets.bottom);
        shell.dispatchEvent(new ComponentEvent(shell, ComponentEvent.COMPONENT_RESIZED));
    }

    private void nativeGeometry(Dimension requested) {
        Dimension actual = shell.getSize();
        System.out.println("Quest native: requested client=" + requested + ", realized client=" + actual
            + ", font=" + ContentStyle.body().getSize() + ", LAF=" + UIManager.getLookAndFeel().getName()
            + ", transform=" + frame.getGraphicsConfiguration().getDefaultTransform()
            + (requested.equals(actual) ? "; request realized" : "; HOST CLAMP — not an exact-size pass"));
        assertTrue(actual.width > 0 && actual.height > 0);
    }

    private void geometry(int font, Dimension client) throws Exception {
        SwingUtilities.invokeAndWait(() -> setGeometry(font, client)); settle(shell);
    }

    private void setGeometry(int font, Dimension client) {
        font(font); shell.setSize(client);
        shell.dispatchEvent(new ComponentEvent(shell, ComponentEvent.COMPONENT_RESIZED));
    }

    private void font(int size) {
        ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, size));
        ContentStyle.applyFontDefaults(); ContentStyle.refreshFonts(frame != null ? frame : shell);
    }

    private static void settle(Container root) throws Exception {
        // Separate EDT turns drain the shared deferred width measurement; never enlarge children to force a pass.
        for (int turn = 0; turn < 12; turn++) SwingUtilities.invokeAndWait(() -> {
            if (root instanceof Window) root.validate(); else layout(root);
        });
    }

    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container) layout((Container)child);
    }

    private void assertUsableViewports() {
        assertTrue("Three allocated roster rows", scroll("quest-list-scroll").getViewport().getExtentSize().height >= table().getRowHeight() * 3);
        JViewport detail = scroll("quest-detail-scroll").getViewport();
        assertTrue("Three allocated detail text lines", detail.getExtentSize().height >= quest.getFontMetrics(ContentStyle.body()).getHeight() * 3);
        assertTrue(detail.getExtentSize().width > 0);
    }

    private void assertControlsReachable() {
        for (String name : new String[]{"quest-search", "quest-type", "quest-reward", "quest-sort", "quest-pinned-only",
                "quest-completed", "quest-name-types", "quest-reset", "quest-pin"}) {
            JComponent control = named(quest, name, JComponent.class);
            assertTrue(name + " width", control.getWidth() > 0);
            assertTrue(name + " font-aware height", control.getHeight() >= control.getPreferredSize().height);
            if (!(control instanceof JTextField)) assertTrue(name + " full control width", control.getWidth() >= control.getPreferredSize().width);
            reveal(control, new Rectangle(0, 0, control.getWidth(), control.getHeight())); fullyVisible(control);
        }
    }

    private void assertTableGeometry() {
        JTable table = table();
        for (int c = 0; c < table.getColumnCount(); c++) {
            TableColumn column = table.getColumnModel().getColumn(c);
            Component header = table.getTableHeader().getDefaultRenderer().getTableCellRendererComponent(table, column.getHeaderValue(), false, false, -1, c);
            assertTrue("Header width " + column.getHeaderValue(), column.getWidth() >= header.getPreferredSize().width);
            assertTrue("Header height " + column.getHeaderValue(), table.getTableHeader().getHeight() >= header.getPreferredSize().height);
        }
        for (int c : new int[]{0, 4, 5}) for (int r = 0; r < table.getRowCount(); r++) {
            Component cell = table.prepareRenderer(table.getCellRenderer(r, c), r, c);
            assertTrue("Semantic cell fits: " + table.getValueAt(r, c), table.getColumnModel().getColumn(c).getWidth() >= cell.getPreferredSize().width);
        }
        assertEquals(Integer.class, table.getColumnClass(4));
        if (table.getWidth() > scroll("quest-list-scroll").getViewport().getWidth()) assertTrue(scroll("quest-list-scroll").getHorizontalScrollBar().isVisible());
    }

    private void reachableCell(int row, int column) {
        Rectangle cell = table().getCellRect(row, column, true); reveal(table(), cell);
        assertTrue("Whole cell reachable: " + cell + " / " + table().getVisibleRect(), table().getVisibleRect().contains(cell));
    }

    private static void assertWrappedText(Container root, boolean endpointsReachable) {
        for (Component child : root.getComponents()) {
            if (!child.isVisible()) continue;
            if (child instanceof JTextArea && !((JTextArea)child).isEditable()) {
                JTextArea area = (JTextArea)child;
                if (staticLabel(area)) assertFalse("Static wrapping labels must not become focus stops: " + area.getText(), area.isFocusable());
                Rectangle end = end(area);
                assertTrue("Complete final line allocated: " + area.getText(), end.y + end.height <= area.getHeight());
                assertTrue("Final glyph within wrapped width: " + area.getText(), end.x + end.width <= area.getWidth());
                if (endpointsReachable) {
                    reveal(area, end); assertTrue("Final line scroll reachable: " + area.getText(), area.getVisibleRect().contains(end));
                }
            } else if (child instanceof Container) assertWrappedText((Container)child, endpointsReachable);
        }
    }

    private static Rectangle end(JTextComponent area) {
        try { Rectangle end = area.modelToView(area.getDocument().getLength()); assertNotNull("Laid-out text endpoint", end); return end; }
        catch (BadLocationException e) { throw new AssertionError(e); }
    }

    private static void fullyVisible(JComponent component) {
        assertEquals("Full control visible after scrolling/focus: " + component.getName(),
            new Rectangle(0, 0, component.getWidth(), component.getHeight()), component.getVisibleRect());
    }

    private static boolean staticLabel(Component component) {
        return "quest-static-label".equals(component.getName()) || "quest-summary".equals(component.getName())
            || "quest-count".equals(component.getName());
    }

    private static void assertFocusMovedPastStaticLabels(JComponent previous) {
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        assertNotNull("Posted Shift+Tab must retain a real focus owner", owner);
        assertNotSame("Backward traversal must move focus", previous, owner);
        assertFalse("Offscreen static labels must not intercept backward traversal", staticLabel(owner));
    }

    private static void reveal(JComponent component, Rectangle region) {
        int viewports = 0;
        // Independent test-side exercise of actual scroll routes, not the production focus callback.
        for (Container parent = component.getParent(); parent != null; parent = parent.getParent()) if (parent instanceof JViewport) {
            JComponent view = (JComponent)((JViewport)parent).getView();
            view.scrollRectToVisible(SwingUtilities.convertRectangle(component, region, view)); viewports++;
        }
        assertTrue("Must exercise real viewport scrolling", viewports > 0);
    }

    private static void awaitFocus(JComponent component) throws Exception {
        KeyboardFocusManager focus = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        Window window = SwingUtilities.getWindowAncestor(component);
        assertNotNull("Native focus needs a realized window", window);
        CountDownLatch windowFocused = new CountDownLatch(1);
        CountDownLatch focused = new CountDownLatch(1);
        FocusAdapter listener = new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) {
                if (focus.getFocusedWindow() == window && focus.getFocusOwner() == component) focused.countDown();
            }
        };
        WindowAdapter activation = new WindowAdapter() {
            @Override public void windowGainedFocus(WindowEvent event) {
                if (focus.getFocusedWindow() == window) windowFocused.countDown();
            }
        };
        try {
            SwingUtilities.invokeAndWait(() -> {
                window.addWindowFocusListener(activation);
                if (focus.getFocusedWindow() == window) windowFocused.countDown();
                else window.toFront();
            });
            assertTrue("Owning window must gain focus before its child: " + component.getName(), windowFocused.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertSame(window, focus.getFocusedWindow());
                // A Window.requestFocus() queued alongside this request can later steal focus back
                // from the child. Activation completes first; only then observe/request child focus.
                component.addFocusListener(listener);
                if (focus.getFocusOwner() == component) focused.countDown();
                else component.requestFocusInWindow();
            });
            assertTrue("Focus on " + component.getName(), focused.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertSame(component, focus.getFocusOwner());
                assertSame(window, focus.getFocusedWindow());
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> { component.removeFocusListener(listener); window.removeWindowFocusListener(activation); });
        }
    }

    private static void postKey(JComponent target, int code, int modifiers) throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<String> wrongFocus = new AtomicReference<>();
        KeyboardFocusManager focus = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        KeyEventDispatcher observer = event -> {
            if (event.getID() == KeyEvent.KEY_PRESSED && event.getKeyCode() == code) {
                if (event.getComponent() != target || focus.getFocusOwner() != target) wrongFocus.set("Key reached a different focus owner");
                delivered.countDown();
            }
            return false;
        };
        focus.addKeyEventDispatcher(observer);
        try {
            EventQueue queue = Toolkit.getDefaultToolkit().getSystemEventQueue(); long when = System.currentTimeMillis();
            queue.postEvent(new KeyEvent(target, KeyEvent.KEY_PRESSED, when, modifiers, code, KeyEvent.CHAR_UNDEFINED));
            queue.postEvent(new KeyEvent(target, KeyEvent.KEY_RELEASED, when, modifiers, code, KeyEvent.CHAR_UNDEFINED));
            assertTrue("Posted key must pass through KeyboardFocusManager", delivered.await(5, TimeUnit.SECONDS));
            assertNull(wrongFocus.get());
            SwingUtilities.invokeAndWait(() -> {});
        } finally { focus.removeKeyEventDispatcher(observer); }
    }

    private void screenshot(String name) {
        try {
            File directory = new File("screenshots");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); frame.printAll(graphics); graphics.dispose();
            assertTrue(ImageIO.write(image, "png", new File(directory, name)));
        } catch (java.io.IOException e) { throw new AssertionError(e); }
    }

    private JTable table() { return named(quest, "quest-table", JTable.class); }
    private JTextField search() { return named(quest, "quest-search", JTextField.class); }
    private JButton button(String name) { return named(quest, name, JButton.class); }
    private JScrollPane scroll(String name) { return named(quest, name, JScrollPane.class); }

    private static <T> T named(Container root, String name, Class<T> type) {
        T component = find(root, name, type); assertNotNull("Missing " + name, component); return component;
    }

    private static <T> T find(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName()) && type.isInstance(child)) return type.cast(child);
            if (child instanceof Container) { T found = find((Container)child, name, type); if (found != null) return found; }
        }
        return null;
    }

    private static JTextArea textArea(Container root, String value) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextArea && value.equals(((JTextArea)child).getText())) return (JTextArea)child;
            if (child instanceof Container) { JTextArea found = textArea((Container)child, value); if (found != null) return found; }
        }
        return null;
    }

    private static JButton findButton(Container root, String value) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton && value.equals(((JButton)child).getText())) return (JButton)child;
            if (child instanceof Container) { JButton found = findButton((Container)child, value); if (found != null) return found; }
        }
        return null;
    }

    private static String text(Container root) {
        StringBuilder text = new StringBuilder();
        for (Component child : root.getComponents()) {
            if (child instanceof JTextArea) text.append(((JTextArea)child).getText()).append('\n');
            else if (child instanceof JLabel) text.append(((JLabel)child).getText()).append('\n');
            if (child instanceof Container) text.append(text((Container)child));
        }
        return text.toString();
    }
}
