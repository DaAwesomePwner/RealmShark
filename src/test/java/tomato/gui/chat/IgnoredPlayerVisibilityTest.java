package tomato.gui.chat;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.*;
import java.nio.file.Path;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.incoming.TextPacket;
import tomato.gui.history.SessionPanel;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.VioletTheme;
import tomato.history.SessionStore;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.history.SessionPanelTest.named;

public class IgnoredPlayerVisibilityTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String previousVisibility, previousFilters;
    private LookAndFeel previousTheme;

    @Before public void isolatePreferences() throws Exception {
        previousVisibility = PropertiesManager.getProperty(ChatExplorer.SHOW_IGNORED_PLAYERS);
        previousFilters = PropertiesManager.getProperty("chat.filters");
        PropertiesManager.setProperties(ChatExplorer.SHOW_IGNORED_PLAYERS, "");
        PropertiesManager.setProperties("chat.filters", "{}");
        SwingUtilities.invokeAndWait(() -> previousTheme = UIManager.getLookAndFeel());
    }

    @After public void restorePreferences() throws Exception {
        PropertiesManager.setProperties(ChatExplorer.SHOW_IGNORED_PLAYERS, previousVisibility == null ? "" : previousVisibility);
        PropertiesManager.setProperties("chat.filters", previousFilters == null ? "{}" : previousFilters);
        SwingUtilities.invokeAndWait(() -> {
            try { UIManager.setLookAndFeel(previousTheme); }
            catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
        });
    }

    @Test public void defaultOffToggleRestoresCapturedPlayersInTheirChannelsWithoutExposingSpamOnlyMatches() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatFilters filters = new ChatFilters(); ChatFilters.Settings settings = filters.settings();
            settings.ignoredPlayers.add("Vendor"); filters.apply(settings, false);
            ChatExplorer view = new ChatExplorer(() -> {}, filters, () -> "");
            JCheckBox toggle = named(view, "chat-show-ignored-players", JCheckBox.class);
            JTable table = named(view, "chat-messages", JTable.class);
            assertFalse(toggle.isSelected());
            view.accept(message("Vendor", "Aster", "Local ignored message"));
            view.accept(message("GameIgnored", "", "Observed ignore message").withGameIgnored(true));
            view.accept(message("Other", "", "Buy cheap items at shop.example"));
            view.accept(message("Friend", "*Party*", "Ordinary message"));
            assertEquals(1, table.getRowCount());
            assertFalse(view.filteredTranscript().contains("ignored message"));

            toggle.doClick(); assertEquals(3, table.getRowCount());
            assertTrue(view.filteredTranscript().contains("[Ignored: Ignored player: Vendor]"));
            assertTrue(view.filteredTranscript().contains("[Ignored: In-game ignore observed at receipt]"));
            assertFalse(view.filteredTranscript().contains("Buy cheap"));
            assertEquals("true", PropertiesManager.getProperty(ChatExplorer.SHOW_IGNORED_PLAYERS));
            assertTrue(named(view, "chat-channel-ALL", JToggleButton.class).getToolTipText().contains("3 retained"));
            assertTrue(named(view, "chat-channel-IGNORED", JToggleButton.class).getToolTipText().contains("3 retained"));

            named(view, "chat-channel-PM", JToggleButton.class).doClick();
            assertEquals(1, table.getRowCount()); assertEquals("PM · Ignored", table.getValueAt(0, 2));
            table.setRowSelectionInterval(0, 0);
            assertTrue(named(view, "chat-ignore-reason", JTextArea.class).getText().contains("Vendor"));
            table.getActionMap().get("star-message").actionPerformed(null);
            toggle.doClick(); assertEquals(0, table.getRowCount());
            assertEquals("", named(view, "chat-detail-message", JTextArea.class).getText());
            assertEquals("", view.selectedTranscript());
            toggle.doClick(); assertEquals("★", table.getValueAt(0, 0));
            named(view, "chat-channel-WORLD", JToggleButton.class).doClick();
            assertEquals(1, table.getRowCount()); assertEquals("World · Ignored", table.getValueAt(0, 2));
            named(view, "chat-search", JTextField.class).setText("no-match"); view.refresh(false);
            assertEquals(0, table.getRowCount());
            named(view, "chat-search", JTextField.class).setText(""); view.refresh(false);
            named(view, "chat-channel-IGNORED", JToggleButton.class).doClick();
            assertEquals(3, table.getRowCount());

            ChatExplorer reopened = new ChatExplorer(() -> {}, filters, () -> "");
            assertTrue(named(reopened, "chat-show-ignored-players", JCheckBox.class).isSelected());
            named(reopened, "chat-show-ignored-players", JCheckBox.class).doClick();
            assertFalse(named(new ChatExplorer(() -> {}, filters, () -> ""), "chat-show-ignored-players", JCheckBox.class).isSelected());
            assertTrue(filters.ignoresPlayer("Vendor"));
        });
    }

    @Test public void ignoredRowsKeepAnExplicitLabelAndThemeAwareRedWithoutLosingSelectionContrastOrLiteralText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatExplorer view = new ChatExplorer(() -> {});
            view.accept(message("Ignored", "", "<html>Literal ignored text").withGameIgnored(true));
            view.accept(message("Friend", "", "Ordinary message"));
            named(view, "chat-show-ignored-players", JCheckBox.class).doClick();
            JTable table = named(view, "chat-messages", JTable.class);
            table.setRowSelectionInterval(0, 0);
            for (LookAndFeel theme : new LookAndFeel[]{new VioletTheme(), new FlatLightLaf(), new FlatDarkLaf()}) {
                try { UIManager.setLookAndFeel(theme); }
                catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
                SwingUtilities.updateComponentTreeUI(view);
                for (int column : new int[]{2, 3, 4}) {
                    JComponent cell = (JComponent)table.getCellRenderer(0, column)
                            .getTableCellRendererComponent(table, table.getValueAt(0, column), false, true, 0, column);
                    assertEquals(ContentStyle.color("rose"), cell.getForeground());
                    assertEquals(Boolean.TRUE, cell.getClientProperty("html.disable"));
                    assertNull(cell.getClientProperty("html"));
                    Component selected = table.getCellRenderer(0, column)
                            .getTableCellRendererComponent(table, table.getValueAt(0, column), true, true, 0, column);
                    assertEquals(table.getSelectionForeground(), selected.getForeground());
                }
                assertEquals("World · Ignored", table.getValueAt(0, 2));
                assertEquals(ContentStyle.color("rose"), named(view, "chat-ignore-reason", JTextArea.class).getForeground());
                assertEquals(ContentStyle.color("rose"), named(view, "chat-detail-header", JTextArea.class).getForeground());
                assertEquals("<html>Literal ignored text", named(view, "chat-detail-message", JTextArea.class).getText());
                Component normal = table.getCellRenderer(1, 4)
                        .getTableCellRendererComponent(table, table.getValueAt(1, 4), false, false, 1, 4);
                assertEquals(table.getForeground(), normal.getForeground());
            }
        });
    }

    @Test public void savedChatRetainsTheIgnoreMarkerWhileHiddenAndCanBeRevealedAfterRestart() throws Exception {
        Path root = temp.newFolder().toPath(); String session;
        try (SessionStore source = new SessionStore(root, true, "old-build")) {
            session = source.currentId();
            source.append("chat", message("Ignored", "", "Retained while hidden").withGameIgnored(true));
            source.flush();
        }
        try (SessionStore history = new SessionStore(root, true, "new-build")) {
            SessionPanel.Loaded loaded = ChatGUI.history(history, session, 0, "");
            SwingUtilities.invokeAndWait(() -> {
                ChatExplorer view = (ChatExplorer)loaded.createView();
                JTable table = named(view, "chat-messages", JTable.class);
                assertEquals(0, table.getRowCount());
                named(view, "chat-show-ignored-players", JCheckBox.class).doClick();
                assertEquals(1, table.getRowCount());
                assertTrue(view.filteredTranscript().contains("Retained while hidden"));
                assertTrue(view.filteredTranscript().contains("[Ignored: In-game ignore observed at receipt]"));
                named(view, "chat-show-ignored-players", JCheckBox.class).doClick();
                assertEquals(0, table.getRowCount());
            });
            assertTrue(history.read(history.currentId(), "chat", ChatMessage.class).isEmpty());
            assertEquals(1, history.read(session, "chat", ChatMessage.class).size());
        }
    }

    private static ChatMessage message(String sender, String recipient, String text) {
        TextPacket packet = new TextPacket(); packet.name = sender; packet.recipient = recipient; packet.text = text;
        return ChatMessage.from(packet, "Aster");
    }
}
