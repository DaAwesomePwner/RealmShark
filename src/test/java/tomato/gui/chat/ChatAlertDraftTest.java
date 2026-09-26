package tomato.gui.chat;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.After;
import org.junit.Test;
import packets.incoming.TextPacket;
import tomato.realmshark.AlertRules;
import tomato.realmshark.Sound;
import tomato.realmshark.SoundSeam;
import static org.junit.Assert.*;
import static tomato.gui.maingui.SocialRuleEditorTest.named;

/** CHAT-3 draft slice: contextual drafts carry a detached sample and returning restores the source row. */
public class ChatAlertDraftTest {
    private static ChatMessage message(String sender, String recipient, String text) {
        TextPacket packet = new TextPacket(); packet.name = sender; packet.recipient = recipient; packet.text = text;
        return ChatMessage.from(packet, "Aster");
    }
    @After public void clear() { SoundSeam.clear(); }

    @Test public void messageAndPlayerDraftsAreDetachedSilentAndReturnToTheSourceRow() throws Exception {
        AtomicInteger audio = new AtomicInteger(); SoundSeam.count(audio);
        boolean keywords = Sound.keywords.isEnabled();
        SwingUtilities.invokeAndWait(() -> {
            ChatExplorer explorer = new ChatExplorer(() -> {}, new ChatFilters(), () -> "", false);
            List<Object[]> opened = new ArrayList<>();
            explorer.useDraftOpener((draft, back) -> opened.add(new Object[]{draft, back}));
            ChatMessage source = message("Wren", "", "need a priest at the nexus");
            List<ChatMessage> batch = new ArrayList<>(Arrays.asList(message("Nova", "", "hello"), source, message("Aster", "", "my own words")));
            for (int i = 0; i < 40; i++) batch.add(message("Filler" + i, "", "filler " + i));
            for (ChatMessage each : batch) explorer.accept(each);
            JTable table = named(explorer, "chat-messages", JTable.class);
            int row = explorer.visibleMessages().indexOf(source); table.setRowSelectionInterval(row, row);
            JButton fromMessage = named(explorer, "chat-alert-message", JButton.class), player = named(explorer, "chat-alert-player", JButton.class);
            assertTrue(fromMessage.isEnabled()); assertTrue(player.isEnabled()); assertEquals("Alert on mentions of Wren…", player.getText());
            fromMessage.doClick();
            AlertRules.Draft draft = (AlertRules.Draft)opened.get(0)[0];
            assertEquals(AlertRules.Domain.CHAT, draft.domain); assertEquals(AlertRules.Mode.TEXT_CONTAINS, draft.mode);
            assertEquals("need a priest at the nexus", draft.value); assertEquals("need a priest at the nexus", draft.sampleText);
            assertTrue(draft.source.contains("World") && draft.source.contains("Wren"));
            player.doClick();
            AlertRules.Draft mentions = (AlertRules.Draft)opened.get(1)[0];
            assertEquals(AlertRules.Mode.SPACE_TOKEN, mentions.mode); assertEquals("Wren", mentions.value);
            assertTrue(mentions.proposedRule().supported());

            // The reader moves elsewhere while the modal editor is open; closing it returns to the same record.
            table.setRowSelectionInterval(40, 40);
            ((Runnable)opened.get(0)[1]).run();
            assertSame(source, explorer.selectedMessage());
            assertFalse(named(explorer, "chat-draft-status", JTextArea.class).isVisible());

            // Own messages cannot trigger chat alerts, so drafting from them is disabled.
            int own = explorer.visibleMessages().indexOf(batch.get(2)); table.setRowSelectionInterval(own, own);
            assertFalse(fromMessage.isEnabled());

            // When filters hide the source, return says so instead of selecting another row.
            JTextField search = named(explorer, "chat-search", JTextField.class); search.setText("filler"); explorer.refresh(false);
            assertFalse(explorer.returnToMessage(source));
            JTextArea status = named(explorer, "chat-draft-status", JTextArea.class);
            assertTrue(status.isVisible()); assertTrue(status.getText().contains("no longer in this view"));
        });
        assertEquals(0, audio.get()); assertEquals(keywords, Sound.keywords.isEnabled());
    }
}
