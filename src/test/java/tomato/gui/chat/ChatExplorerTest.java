package tomato.gui.chat;
import ui.UiTestLayout;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import org.junit.Test;
import packets.incoming.TextPacket;
import tomato.gui.modern.VioletTheme;
import static org.junit.Assert.*;

public class ChatExplorerTest {
    private static TextPacket packet(String sender, String recipient, String text) {
        TextPacket packet = new TextPacket(); packet.name = sender; packet.recipient = recipient; packet.text = text; return packet;
    }
    private static ChatMessage message(String sender, String recipient, String text) { return ChatMessage.from(packet(sender, recipient, text), "Aster"); }

    @Test public void routingPreservesBothSidesOfPrivateConversationsAndCopiesPackets() {
        TextPacket packet = packet("Aster, metadata", "Wren", "Meet in Nexus");
        ChatMessage sent = ChatMessage.from(packet, "Aster");
        packet.name = "Changed"; packet.text = "Changed";
        assertEquals(ChatMessage.Channel.PM, sent.channel); assertEquals("To: Wren", sent.playerLabel());
        assertTrue(sent.matches("nexus", "ASTER")); assertTrue(sent.matches("", "wren"));
        assertTrue(sent.transcript().contains("Aster → Wren: Meet in Nexus"));
        assertEquals("From: Wren", message("Wren", "Aster", "hi").playerLabel());
        assertEquals(ChatMessage.Channel.GUILD, message("Wren", "*Guild*", "hi").channel);
        assertEquals(ChatMessage.Channel.PARTY, message("Wren", "*Party*", "hi").channel);
        assertEquals(ChatMessage.Channel.WORLD, message("Wren", "", "hi").channel);
        assertEquals(ChatMessage.Channel.SYSTEM, message("#Oryx", "", "hi").channel);
        assertEquals(ChatMessage.Channel.SYSTEM, message("", "", "hi").channel);
        assertFalse(sent.matches("[", "")); // Literal input, never a regex.
    }

    @Test public void whisperAlertsRequireIncomingDirectionWithNormalizedPlayerNames() {
        ChatMessage sent = message("aster, metadata", "Wren", "outgoing");
        assertTrue(sent.isFrom("Aster"));
        assertEquals("To: Wren", sent.playerLabel());
        assertFalse(sent.isIncomingWhisper());
        assertFalse(message("Aster", "Wren", "outgoing").isIncomingWhisper());
        assertFalse(message("Aster, metadata", "Aster", "self whisper").isIncomingWhisper());

        ChatMessage received = message("Wren, metadata", " aStEr, recipient metadata", "incoming");
        assertFalse(received.isFrom("Aster"));
        assertEquals("From: Wren", received.playerLabel());
        assertTrue(received.isIncomingWhisper());
        assertTrue(message("Wren", "Aster", "incoming").isIncomingWhisper());

        assertFalse(message("Wren", "AnotherPlayer", "unrelated").isIncomingWhisper());
        assertFalse(ChatMessage.from(packet("Aster, metadata", "Wren", "unknown identity"), null).isIncomingWhisper());
        assertFalse(ChatMessage.from(packet("Wren", "Aster", "unknown identity"), "").isIncomingWhisper());
        assertFalse(message("Wren", "*Guild*", "guild").isIncomingWhisper());
        assertFalse(message("Wren", "*Party*", "party").isIncomingWhisper());
        assertFalse(message("Wren", "", "public").isIncomingWhisper());
    }
    @Test public void combinedFiltersStarsCopyAndClearOperateOnOneHistory() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatExplorer ui = new ChatExplorer(() -> {}); JTable table = find(ui, JTable.class, "chat-messages");
            ui.accept(message("Wren", "*Guild*", "Shatters key ready"));
            ui.accept(message("Nova", "*Party*", "Meet at the portal"));
            ui.accept(message("Aster", "Wren", "Bring your Shatters key"));
            ui.accept(message("Wren", "Aster", "On my way"));
            assertEquals(4, table.getRowCount());
            search(ui).setText("SHATTERS"); ui.refresh(false); assertEquals(2, table.getRowCount());
            button(ui, "chat-channel-GUILD").doClick(); assertEquals(1, table.getRowCount());
            assertTrue(ui.filteredTranscript().contains("Shatters key ready"));
            assertFalse(ui.filteredTranscript().contains("Bring your"));
            table.setRowSelectionInterval(0, 0); button(ui, "Star").doClick();
            assertEquals("★", table.getValueAt(0, 0));
            assertEquals(1, find(ui, JTextArea.class, "chat-detail-message").getHighlighter().getHighlights().length);
            button(ui, "Reset").doClick(); button(ui, "Starred").doClick(); assertEquals(1, table.getRowCount());
            button(ui, "Reset").doClick(); button(ui, "chat-channel-PM").doClick();
            find(ui, JTextField.class, "chat-player").setText("wren"); ui.refresh(false); assertEquals(2, table.getRowCount());
            table.setRowSelectionInterval(0, 1);
            assertTrue(ui.selectedTranscript().contains("Aster → Wren")); assertTrue(ui.selectedTranscript().contains("Wren → Aster"));
            table.setRowSelectionInterval(0, 0); button(ui, "This player").doClick();
            assertEquals("Wren", find(ui, JTextField.class, "chat-player").getText());
            search(ui).setText("["); ui.refresh(false); assertEquals(0, table.getRowCount());
            ui.clear(); button(ui, "Reset").doClick(); assertEquals(0, table.getRowCount());
            button(ui, "chat-channel-GUILD").doClick(); assertEquals(0, table.getRowCount());
            button(ui, "Starred").doClick(); assertEquals("", ui.filteredTranscript());
        });
    }

    @Test public void burstsAreBoundedAndDeliveredOnEdtAndClearDropsQueuedMessages() throws Exception {
        final ChatExplorer[] ui = new ChatExplorer[1];
        SwingUtilities.invokeAndWait(() -> {
            ui[0] = new ChatExplorer(() -> {});
            JTable table = find(ui[0], JTable.class, "chat-messages");
            ui[0].accept(message("Wren", "", "old starred message")); table.setRowSelectionInterval(0, 0); button(ui[0], "Star").doClick();
            table.getModel().addTableModelListener(e -> assertTrue(SwingUtilities.isEventDispatchThread()));
            Thread capture = new Thread(() -> {
                for (int i = 0; i < ChatExplorer.HISTORY_LIMIT + 50; i++) ui[0].accept(message("Wren", "", "Message " + i));
            });
            capture.start(); join(capture);
        });
        SwingUtilities.invokeAndWait(() -> {
            JTable table = find(ui[0], JTable.class, "chat-messages");
            assertEquals(ChatExplorer.HISTORY_LIMIT, table.getRowCount());
            assertEquals("Message 50", table.getValueAt(0, 4));
            button(ui[0], "Starred").doClick(); assertEquals(0, table.getRowCount());
            Thread capture = new Thread(() -> ui[0].accept(message("Wren", "*Party*", "pending")));
            capture.start(); join(capture); ui[0].clear();
        });
        SwingUtilities.invokeAndWait(() -> { button(ui[0], "Reset").doClick(); assertEquals("", ui[0].filteredTranscript()); });
    }

    @Test public void packetMarkupIsLiteralAndFullMultilineUnicodeMessagesSurvive() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatExplorer ui = new ChatExplorer(() -> {});
            String content = "<html><img src='https://invalid.example/x'>\nこんにちは ★ [literal]";
            ui.accept(message("<html>Wren", "", content));
            JTable table = find(ui, JTable.class, "chat-messages");
            table.setRowSelectionInterval(0, 0);
            assertEquals(content, find(ui, JTextArea.class, "chat-detail-message").getText());
            TableCellRenderer renderer = table.getCellRenderer(0, 4);
            JComponent rendered = (JComponent)renderer.getTableCellRendererComponent(table, table.getValueAt(0, 4), false, false, 0, 4);
            assertEquals(Boolean.TRUE, rendered.getClientProperty("html.disable"));
            assertNull(rendered.getClientProperty("html"));
            assertTrue(ui.filteredTranscript().contains(content));
            search(ui).setText("[literal]"); ui.refresh(false); assertEquals(1, table.getRowCount());
        });
    }

    @Test public void selectionAndReadingPositionSurviveArrivalsAndFollowCanResume() throws Exception {
        final JFrame[] frame = new JFrame[1]; final ChatExplorer[] ui = new ChatExplorer[1];
        SwingUtilities.invokeAndWait(() -> {
            ui[0] = new ChatExplorer(() -> {}); frame[0] = new JFrame(); frame[0].setContentPane(ui[0]);
            frame[0].setSize(850, 620); frame[0].setVisible(true);
            for (int i = 0; i < 80; i++) ui[0].accept(message("Wren", "*Guild*", "History " + i));
            frame[0].validate();
        });
        try {
            SwingUtilities.invokeAndWait(() -> {
                ChatExplorer panel = ui[0]; JTable table = find(panel, JTable.class, "chat-messages");
                assertTrue("Auto-follow reaches the latest row after layout", table.getVisibleRect().intersects(table.getCellRect(79, 0, true)));
                button(panel, "Follow latest").doClick();
                JScrollPane scroll = (JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
                table.setRowSelectionInterval(20, 20);
                scroll.getViewport().setViewPosition(new Point(0, table.getRowHeight() * 18));
                int before = scroll.getViewport().getViewPosition().y;
                panel.accept(message("Nova", "*Guild*", "A new message")); frame[0].validate();
                assertEquals("History 20", table.getValueAt(table.getSelectedRow(), 4));
                assertEquals(before, scroll.getViewport().getViewPosition().y);
                button(panel, "Follow latest").doClick();
                assertTrue(scroll.getViewport().getViewPosition().y > before);
            });
        } finally { SwingUtilities.invokeAndWait(() -> frame[0].dispose()); }
    }

    @Test public void actualChatEntryPointsRetainUmiHintsAndClearEveryChannel() throws Exception {
        tomato.Tomato.main(new String[]{"--preview"});
        try {
            SwingUtilities.invokeAndWait(() -> {
                tomato.backend.data.TomatoData data = new tomato.backend.data.TomatoData();
                ChatGUI chat = new ChatGUI(data);
                ChatGUI.updateChat(packet("Wren", "*Guild*", "Guild sample"));
                ChatGUI.updateChat(packet("Nova", "*Party*", "Party sample"));
                ChatGUI.updateChat(packet("Wren", "Aster", "Private sample"));
                ChatGUI.updateChat(packet("#Village Girl Umi", "", "I've been intrigued by folktales from foreign lands recently."));
                ChatGUI.appendTextAreaChat("Capture notice");
                JTable table = find(chat, JTable.class, "chat-messages");
                assertEquals(6, table.getRowCount());
                assertEquals("The Happy Prince", table.getValueAt(4, 4));
                button(chat, "chat-channel-SYSTEM").doClick(); assertEquals(3, table.getRowCount());
                ChatGUI.clearTextAreaChat();
                for (ChatMessage.Channel channel : ChatMessage.Channel.values()) {
                    button(chat, "chat-channel-" + channel.name()).doClick(); assertEquals(0, table.getRowCount());
                }
            });
        } finally { SwingUtilities.invokeAndWait(() -> { for (Window window : Window.getWindows()) window.dispose(); }); }
    }

    @Test public void renderDesktopCompactFilteredAndEmptyStates() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VioletTheme.install(); ChatExplorer ui = new ChatExplorer(() -> {}); JFrame frame = new JFrame("Chat · sample data");
            frame.setContentPane(ui);
            sample(ui);
            JTable table = find(ui, JTable.class, "chat-messages"); table.setRowSelectionInterval(2, 2); button(ui, "Star").doClick();
            try {
                for (int width : new int[]{1060, 500}) {
                    frame.setSize(width, width == 500 ? 540 : 740); frame.setVisible(true); frame.validate();
                    UiTestLayout.settle(frame);
                    assertTrue(search(ui).getWidth() > 150);
                    assertTrue(find(ui, JTextField.class, "chat-player").getWidth() > 100);
                    for (ChatMessage.Channel channel : ChatMessage.Channel.values()) {
                        AbstractButton tab = button(ui, "chat-channel-" + channel.name());
                        assertTrue(tab.getWidth() >= tab.getPreferredSize().width);
                    }
                    snapshot(frame, "chat-" + width);
                    assertTrue("Message viewport at " + width + ": " + table.getVisibleRect(), table.getVisibleRect().getHeight() > 100);
                }
                frame.setSize(1060, 740); search(ui).setText("shatters"); ui.refresh(false); table.setRowSelectionInterval(0, 0); frame.validate(); snapshot(frame, "chat-search");
                search(ui).setText("no-such-message"); ui.refresh(false); frame.validate(); snapshot(frame, "chat-no-results");
                ui.clear(); button(ui, "Reset").doClick(); frame.validate(); snapshot(frame, "chat-empty");
            } finally { frame.dispose(); }
        });
    }

    private static void sample(ChatExplorer ui) {
        String[][] rows = {
            {"Wren", "*Guild*", "Anyone up for a Shatters run? I have a key ready."},
            {"Nova", "*Party*", "Meet in Nexus. We'll head out once everyone's here."},
            {"Aster", "Wren", "I'm in! Bringing a priest for the group."},
            {"Wren", "Aster", "Perfect. Meet us by the portal — no rush."},
            {"Lumen", "", "Lost Halls opening in Nexus, come join!"},
            {"#Village Girl Umi", "", "I've been intrigued by folktales from foreign lands recently."},
            {"Wren", "*Guild*", "Quick Shatters plan: stay together through the village, regroup at the bridge, and call out before entering the next area.\nWe'll wait for everyone before the boss."},
            {"Nova", "*Party*", "Ready when you are. Let's go!"}
        };
        int minute = 41;
        for (String[] row : rows) {
            ChatMessage value = message(row[0], row[1], row[2]);
            ui.accept(new ChatMessage(LocalDateTime.of(2026, 9, 8, 12, minute++, 0), value.channel, value.sender, value.recipient, value.player, value.text, value.direction));
        }
    }
    private static void snapshot(JFrame frame, String name) {
        BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); frame.printAll(graphics); graphics.dispose();
        try { File folder = new File("screenshots"); folder.mkdirs(); ImageIO.write(image, "png", new File(folder, name + ".png")); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static void join(Thread thread) { try { thread.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); } }
    private static JTextField search(ChatExplorer ui) { return find(ui, JTextField.class, "chat-search"); }
    private static AbstractButton button(Container root, String name) {
        for (Component component : root.getComponents()) {
            if (component instanceof AbstractButton && (name.equals(component.getName()) || name.equals(((AbstractButton)component).getText()))) return (AbstractButton)component;
            if (component instanceof Container) { AbstractButton result = button((Container)component, name); if (result != null) return result; }
        }
        return null;
    }
    private static <T> T find(Container root, Class<T> type, String name) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && (name == null || name.equals(component.getName()))) return type.cast(component);
            if (component instanceof Container) { T result = find((Container)component, type, name); if (result != null) return result; }
        }
        return null;
    }
}
