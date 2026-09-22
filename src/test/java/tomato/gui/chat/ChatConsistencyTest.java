package tomato.gui.chat;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.*;
import java.awt.event.ComponentEvent;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.text.BadLocationException;
import org.junit.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.VioletTheme;
import tomato.gui.modern.WorkspaceShell;
import util.PropertiesManager;
import static org.junit.Assert.*;

/** Exact logical client geometry, offscreen; native focus is covered by WorkspaceShellNavigationTest. */
public class ChatConsistencyTest {
    private Font previousFont;
    private LookAndFeel previousLaf;

    @Before public void rememberTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> { previousFont = ContentStyle.body(); previousLaf = UIManager.getLookAndFeel(); });
    }
    @After public void restoreTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> { ContentStyle.setBodyFont(previousFont); setLaf(previousLaf); });
    }

    @Test public void shellColumnsHeadersAndSelectedDetailsFitAcrossFontThemeAndWidthTransitions() throws Exception {
        WorkspaceShell[] shell = new WorkspaceShell[1]; ChatExplorer[] chat = new ChatExplorer[1];
        ChatFilters filters = new ChatFilters();
        String sender = "LongPlayerName" + String.join("", Collections.nCopies(6, "東京"));
        String text = "Partial capture: remote identity unavailable.\n" + String.join("", Collections.nCopies(184, "W")) + ".wav\nこんにちは ★ e\u0301";
        SwingUtilities.invokeAndWait(() -> {
            theme(new VioletTheme(), 13);
            ChatFilters.Settings settings = filters.settings(); settings.ignoredPlayers.add(sender); filters.apply(settings, false);
            chat[0] = new ChatExplorer(() -> {}, filters, () -> "Remote whisper identities may be unavailable.");
            JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length];
            for (int i = 0; i < pages.length; i++) pages[i] = i == 0 ? chat[0] : new JPanel();
            shell[0] = new WorkspaceShell(pages, () -> {}, true);
            JTable table = find(chat[0], "chat-messages", JTable.class);
            // JTable normally mounts this in addNotify; the fixture intentionally has no native peer.
            ((JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table)).setColumnHeaderView(table.getTableHeader());
            for (ChatMessage.Channel channel : new ChatMessage.Channel[] {ChatMessage.Channel.WORLD, ChatMessage.Channel.PM,
                    ChatMessage.Channel.PARTY, ChatMessage.Channel.GUILD, ChatMessage.Channel.SYSTEM})
                chat[0].accept(new ChatMessage(LocalDateTime.of(2026, 9, 19, 23, 59, 59), channel, "Wren", "Aster", "Wren", text, ""));
            chat[0].accept(new ChatMessage(LocalDateTime.of(2026, 9, 19, 23, 59, 59), ChatMessage.Channel.PM, sender, "Aster", sender, text, "From"));
        });
        for (LookAndFeel laf : new LookAndFeel[] {new VioletTheme(), new FlatLightLaf(), new FlatDarkLaf()}) {
            for (int font : new int[] {13, 16, 24, 13}) for (Dimension geometry : new Dimension[] {new Dimension(1240, 800), new Dimension(680, 520)}) {
                SwingUtilities.invokeAndWait(() -> {
                    theme(laf, font); SwingUtilities.updateComponentTreeUI(shell[0]); ContentStyle.refreshFonts(shell[0]);
                    shell[0].setSize(geometry); shell[0].dispatchEvent(new ComponentEvent(shell[0], ComponentEvent.COMPONENT_RESIZED));
                    find(chat[0], "chat-channel-ALL", AbstractButton.class).doClick();
                    find(chat[0], "chat-messages", JTable.class).setRowSelectionInterval(0, 0);
                });
                settle(shell[0]);
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals(geometry, shell[0].getSize());
                    JTable table = find(chat[0], "chat-messages", JTable.class);
                    assertEquals(5, table.getRowCount());
                    for (int column = 0; column < table.getColumnCount(); column++) {
                        TableColumn model = table.getColumnModel().getColumn(column);
                        TableCellRenderer renderer = model.getHeaderRenderer();
                        if (renderer == null) renderer = table.getTableHeader().getDefaultRenderer();
                        Component heading = renderer.getTableCellRendererComponent(table, model.getHeaderValue(), false, false, -1, column);
                        assertTrue("Header width: " + model.getHeaderValue(), model.getWidth() >= heading.getPreferredSize().width);
                        assertTrue("Header height: " + model.getHeaderValue(), table.getTableHeader().getHeight() >= heading.getPreferredSize().height);
                    }
                    for (int row = 0; row < table.getRowCount(); row++) for (int column : new int[] {1, 2}) {
                        Component cell = table.prepareRenderer(table.getCellRenderer(row, column), row, column);
                        assertTrue("Complete semantic value " + table.getValueAt(row, column) + " at " + font + "pt",
                            table.getColumnModel().getColumn(column).getWidth() >= cell.getPreferredSize().width);
                    }
                    assertTrue("Player space", table.getColumnModel().getColumn(3).getWidth() >= table.getFontMetrics(table.getFont()).stringWidth("From: Wren"));
                    assertTrue("Message space", table.getColumnModel().getColumn(4).getWidth() >= table.getFontMetrics(table.getFont()).stringWidth("Message text"));
                    JViewport viewport = (JViewport) table.getParent();
                    assertTrue("At least three font-aware rows", viewport.getHeight() >= table.getRowHeight() * 3);
                    assertTrue("Table fills the viewport or can scroll horizontally", table.getWidth() >= viewport.getWidth());
                    if (table.getMinimumSize().width > viewport.getWidth())
                        assertTrue(((JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table)).getHorizontalScrollBar().isVisible());
                    for (int column : new int[] {1, 2}) {
                        TableColumn model = table.getColumnModel().getColumn(column);
                        int width = model.getWidth(); model.setWidth(width + 30);
                        assertEquals("Semantic columns remain manually resizable", width + 30, model.getWidth()); model.setWidth(width);
                    }
                    for (ChatMessage.Channel channel : ChatMessage.Channel.values()) assertButtonFits(find(chat[0], "chat-channel-" + channel.name(), AbstractButton.class));
                    assertButtonFits(find(chat[0], "chat-show-ignored-players", JCheckBox.class));
                    assertReachable(find(chat[0], "chat-show-ignored-players", JCheckBox.class));
                    assertTextFits(find(chat[0], "chat-detail-header", JTextArea.class));
                    assertDetailReachable(chat[0], text);
                    find(chat[0], "chat-channel-IGNORED", AbstractButton.class).doClick(); table.setRowSelectionInterval(0, 0);
                });
                settle(shell[0]);
                SwingUtilities.invokeAndWait(() -> {
                    assertTextFits(find(chat[0], "chat-detail-header", JTextArea.class));
                    JTextArea reason = find(chat[0], "chat-ignore-reason", JTextArea.class);
                    assertTrue(reason.getText().contains(sender)); assertTextFits(reason);
                    JTable table = find(chat[0], "chat-messages", JTable.class);
                    Component badge = table.prepareRenderer(table.getCellRenderer(0, 2), 0, 2);
                    assertTrue("Ignored channel label fits", table.getColumnModel().getColumn(2).getWidth() >= badge.getPreferredSize().width);
                    assertDetailReachable(chat[0], text);
                    JTextField search = find(chat[0], "chat-search", JTextField.class);
                    search.setText("no-such-message"); chat[0].refresh(false);
                    assertEquals(0, find(chat[0], "chat-messages", JTable.class).getRowCount());
                });
                settle(shell[0]);
                SwingUtilities.invokeAndWait(() -> {
                    assertReachable(find(chat[0], "chat-search", JTextField.class));
                    find(chat[0], "chat-search", JTextField.class).setText(""); chat[0].refresh(false);
                    System.out.println("Offscreen Chat shell=" + geometry + ", font=" + font + ", theme=" + laf.getName());
                });
            }
        }
        SwingUtilities.invokeAndWait(() -> { chat[0].clear(); assertEquals("", chat[0].filteredTranscript()); });
        settle(shell[0]);
        SwingUtilities.invokeAndWait(() -> assertReachable(find(chat[0], "chat-search", JTextField.class)));
    }

    @Test public void compactFilterPagesWrapAllDescriptionsKeepEditorsUsableAndPreserveSaveCancelSemantics() throws Exception {
        ChatFilterPanel[] panel = new ChatFilterPanel[1]; ChatFilters filters = new ChatFilters();
        AtomicInteger saves = new AtomicInteger(), cancels = new AtomicInteger();
        String previousSettings = PropertiesManager.getProperty("chat.filters");
        try {
            SwingUtilities.invokeAndWait(() -> {
                theme(new VioletTheme(), 13);
                panel[0] = new ChatFilterPanel(filters, "Partial: no full ignore list captured. Remote whisper identities may be unavailable.\nFailed to read "
                    + String.join("", Collections.nCopies(184, "W")) + ".txt", saves::incrementAndGet, cancels::incrementAndGet);
            });
            for (LookAndFeel laf : new LookAndFeel[] {new VioletTheme(), new FlatLightLaf()}) for (int font : new int[] {13, 16, 24, 13}) {
                for (int width : new int[] {660, 460, 660}) {
                    SwingUtilities.invokeAndWait(() -> {
                        theme(laf, font); SwingUtilities.updateComponentTreeUI(panel[0]); ContentStyle.refreshFonts(panel[0]); panel[0].setSize(width, 540);
                    });
                    settle(panel[0]);
                    SwingUtilities.invokeAndWait(() -> {
                        assertReadOnlyTextFits(panel[0]);
                        for (String option : new String[] {"advertisements", "links", "game-ignores", "inherited"}) {
                            AbstractButton box = find(panel[0], "chat-filter-" + option, AbstractButton.class);
                            assertButtonFits(box); assertReachable(box);
                            assertNotNull(box.getAccessibleContext().getAccessibleDescription());
                        }
                        assertReachable(find(panel[0], "chat-save-filters", JButton.class));
                        assertReachable(find(panel[0], "chat-cancel-filters", JButton.class));
                    });
                    for (int tab = 0; tab < 3; tab++) {
                        final int selected = tab;
                        SwingUtilities.invokeAndWait(() -> {
                            find(panel[0], "chat-filter-lists", JTabbedPane.class).setSelectedIndex(selected);
                            filterEditor(panel[0], selected).setText("One\nTwo\n" + String.join("", Collections.nCopies(100, "W")));
                        });
                        // Document changes resize the unwrapped text view on the following layout turn.
                        settle(panel[0]);
                        SwingUtilities.invokeAndWait(() -> {
                            JTextArea editor = filterEditor(panel[0], selected);
                            JViewport viewport = (JViewport) editor.getParent();
                            int line = editor.getFontMetrics(editor.getFont()).getHeight();
                            assertTrue("Usable list editor: " + width + "/" + font, viewport.getHeight() >= line * 4);
                            assertTrue(viewport.getWidth() >= editor.getFontMetrics(editor.getFont()).stringWidth("LongPlayerName"));
                            assertReachable(editor, end(editor));
                            assertEditorCharactersReachable(editor);
                            assertReadOnlyTextFits((Container) find(panel[0], "chat-filter-lists", JTabbedPane.class).getSelectedComponent());
                        });
                    }
                }
            }
            SwingUtilities.invokeAndWait(() -> {
                find(panel[0], "chat-ignored-players", JTextArea.class).setText(" Vendor \nVENDOR\n");
                find(panel[0], "chat-blocked-phrases", JTextArea.class).setText("literal.[sale]\n");
                find(panel[0], "chat-allowed-players", JTextArea.class).setText("Friend\n");
                find(panel[0], "chat-filter-links", JCheckBox.class).setSelected(true);
                find(panel[0], "chat-cancel-filters", JButton.class).doClick();
                assertEquals(1, cancels.get()); assertEquals(0, saves.get()); assertFalse(filters.settings().whisperLinks);
                assertEquals(previousSettings, PropertiesManager.getProperty("chat.filters"));
                find(panel[0], "chat-save-filters", JButton.class).doClick();
                assertEquals(1, saves.get()); ChatFilters.Settings saved = ChatFilters.load().settings();
                assertTrue(saved.whisperLinks); assertEquals(1, saved.ignoredPlayers.size());
                assertEquals(Collections.singletonList("literal.[sale]"), saved.phrases);
                assertTrue(ChatFilters.load().ignoresPlayer("vendor")); assertEquals(1, saved.allowedPlayers.size());
            });
        } finally { PropertiesManager.setProperties("chat.filters", previousSettings == null ? "{}" : previousSettings); }
    }

    @Test public void nativeFilterDialogAt460By360OuterMinimumKeepsEveryEditorAndActionReachable() throws Exception {
        JDialog[] dialog = new JDialog[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                theme(new VioletTheme(), 13);
                ChatExplorer explorer = new ChatExplorer(() -> {}, new ChatFilters(),
                    () -> "Partial: remote identity unavailable.\nCould not read " + String.join("", Collections.nCopies(184, "W")) + ".txt");
                dialog[0] = explorer.createFiltersDialog();
                assertEquals(new Dimension(460, 360), dialog[0].getMinimumSize());
                // Exercise the production content and native decorations without blocking the test in a modal loop.
                dialog[0].setModalityType(Dialog.ModalityType.MODELESS);
                dialog[0].setSize(dialog[0].getMinimumSize()); dialog[0].setVisible(true);
            });
            for (LookAndFeel laf : new LookAndFeel[] {new VioletTheme(), new FlatLightLaf(), new FlatDarkLaf()}) {
                for (int font : new int[] {13, 16, 24, 13}) {
                    SwingUtilities.invokeAndWait(() -> {
                        theme(laf, font); SwingUtilities.updateComponentTreeUI(dialog[0]); ContentStyle.refreshFonts(dialog[0]);
                        dialog[0].setSize(460, 360); dialog[0].validate();
                    });
                    settle(dialog[0]);
                    SwingUtilities.invokeAndWait(() -> {
                        assertEquals("Native outer minimum, including decorations", new Dimension(460, 360), dialog[0].getSize());
                        assertTrue("Native insets reduce the actual client height", dialog[0].getContentPane().getHeight() < 360);
                        assertReadOnlyTextFits(dialog[0].getContentPane());
                        for (String option : new String[] {"advertisements", "links", "game-ignores", "inherited"}) {
                            AbstractButton box = find(dialog[0], "chat-filter-" + option, AbstractButton.class);
                            assertButtonFits(box); assertReachable(box);
                        }
                    });
                    for (int tab = 0; tab < 3; tab++) {
                        final int selected = tab;
                        SwingUtilities.invokeAndWait(() -> {
                            find(dialog[0], "chat-filter-lists", JTabbedPane.class).setSelectedIndex(selected);
                            filterEditor(dialog[0], selected).setText("One\nTwo\n" + String.join("", Collections.nCopies(100, "W"))
                                + "\n日本語 e\u0301 אבג 😀\nLast entry");
                        });
                        settle(dialog[0]);
                        SwingUtilities.invokeAndWait(() -> {
                            JTextArea editor = filterEditor(dialog[0], selected);
                            JViewport viewport = (JViewport) editor.getParent();
                            int line = editor.getFontMetrics(editor.getFont()).getHeight();
                            assertTrue("Four full input rows are retained", viewport.getHeight() >= line * 4);
                            assertTrue("Usable editor width", viewport.getWidth() >= editor.getFontMetrics(editor.getFont()).stringWidth("LongPlayerName"));
                            assertReachable(viewport, new Rectangle(0, 0, viewport.getWidth(), line * 4));
                            assertEditorCharactersReachable(editor);
                            assertReadOnlyTextFits((Container) find(dialog[0], "chat-filter-lists", JTabbedPane.class).getSelectedComponent());
                            for (String action : new String[] {"chat-save-filters", "chat-cancel-filters"}) {
                                JButton button = find(dialog[0], action, JButton.class);
                                assertButtonFits(button); assertReachable(button);
                            }
                        });
                    }
                    SwingUtilities.invokeAndWait(() -> System.out.println("Native filter dialog outer=" + dialog[0].getSize()
                        + ", client=" + dialog[0].getContentPane().getSize() + ", font=" + font + ", theme=" + laf.getName()));
                }
            }
        } finally { SwingUtilities.invokeAndWait(() -> { if (dialog[0] != null) dialog[0].dispose(); }); }
    }

    private static JTextArea filterEditor(Container root, int selected) {
        return find(root, new String[] {"chat-ignored-players", "chat-blocked-phrases", "chat-allowed-players"}[selected], JTextArea.class);
    }

    private static void assertEditorCharactersReachable(JTextArea editor) {
        String text = editor.getText();
        try {
            for (int offset = 0; offset < text.length();) {
                int codePoint = text.codePointAt(offset), next = offset + Character.charCount(codePoint);
                Rectangle character = editor.modelToView(offset); assertNotNull(character);
                Rectangle after = editor.modelToView(next); assertNotNull(after);
                if (codePoint != '\n' && codePoint != '\r' && character.y == after.y) character = character.union(after);
                assertReachable(editor, character);
                offset = next;
            }
            assertReachable(editor, end(editor));
        } catch (BadLocationException e) { throw new AssertionError(e); }
    }

    private static void assertDetailReachable(ChatExplorer chat, String text) {
        JTextArea detail = find(chat, "chat-detail-message", JTextArea.class);
        assertEquals(text, detail.getText());
        assertTrue(((JViewport) detail.getParent()).getHeight() >= detail.getFontMetrics(detail.getFont()).getHeight() * 2);
        assertReachable(detail, end(detail));
        assertReachable(find(chat, "chat-ignore-player", JButton.class));
    }
    private static void assertReadOnlyTextFits(Container root) {
        for (Component child : root.getComponents()) {
            if (!child.isVisible()) continue;
            if (child instanceof JTextArea && !((JTextArea) child).isEditable()) assertTextFits((JTextArea) child);
            else if (child instanceof Container) assertReadOnlyTextFits((Container) child);
        }
    }
    private static void assertTextFits(JTextArea area) {
        assertTrue("Text view height for " + area.getName(), area.getHeight() >= area.getUI().getPreferredSize(area).height);
        assertReachable(area, end(area));
    }
    private static Rectangle end(JTextArea area) {
        try { Rectangle end = area.modelToView(area.getDocument().getLength()); assertNotNull(end); return end; }
        catch (BadLocationException e) { throw new AssertionError(e); }
    }
    private static void assertButtonFits(AbstractButton button) {
        Insets i = button.getInsets(); Rectangle icon = new Rectangle(), text = new Rectangle();
        Rectangle available = new Rectangle(i.left, i.top, button.getWidth() - i.left - i.right, button.getHeight() - i.top - i.bottom);
        String painted = SwingUtilities.layoutCompoundLabel(button, button.getFontMetrics(button.getFont()), button.getText(), button.getIcon(),
            button.getVerticalAlignment(), button.getHorizontalAlignment(), button.getVerticalTextPosition(), button.getHorizontalTextPosition(),
            available, icon, text, button.getIconTextGap());
        assertEquals("Complete control label " + button.getName(), button.getText(), painted);
        assertTrue("Control text height", available.contains(text));
    }
    private static void assertReachable(JComponent component) { assertReachable(component, new Rectangle(0, 0, component.getWidth(), component.getHeight())); }
    private static void assertReachable(JComponent component, Rectangle region) {
        for (Container parent = component.getParent(); parent != null; parent = parent.getParent()) if (parent instanceof JViewport) {
            JComponent view = (JComponent) ((JViewport) parent).getView();
            view.scrollRectToVisible(SwingUtilities.convertRectangle(component, region, view));
        }
        assertTrue("Scroll-reachable " + component.getName() + ": " + region + " in " + component.getVisibleRect(), component.getVisibleRect().contains(region));
    }
    private static void settle(Container root) throws Exception {
        for (int turn = 0; turn < 10; turn++) SwingUtilities.invokeAndWait(() -> layoutTree(root));
    }
    private static void layoutTree(Container root) {
        root.doLayout(); for (Component child : root.getComponents()) if (child instanceof Container && child.isVisible()) layoutTree((Container) child);
    }
    private static <T> T find(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T match = find((Container) child, name, type); if (match != null) return match; }
        }
        return null;
    }
    private static void theme(LookAndFeel laf, int size) {
        ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, size)); setLaf(laf); ContentStyle.applyFontDefaults();
    }
    private static void setLaf(LookAndFeel laf) {
        try { UIManager.setLookAndFeel(laf); } catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
    }
}
