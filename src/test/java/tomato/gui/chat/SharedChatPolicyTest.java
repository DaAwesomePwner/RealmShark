package tomato.gui.chat;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.incoming.TextPacket;
import tomato.backend.data.TomatoData;
import tomato.gui.history.SessionPanel;
import tomato.history.SessionStore;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.maingui.SocialRuleEditorTest.*;

public class SharedChatPolicyTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void historicalIgnorePublishesToLiveAndOtherPagesWithoutReplayingAlerts() throws Exception {
        String old = PropertiesManager.getProperty("chat.filters");
        String oldVisibility = PropertiesManager.getProperty(ChatExplorer.SHOW_IGNORED_PLAYERS);
        Path root = temp.newFolder().toPath(); SessionStore store = new SessionStore(root, true, "synthetic");
        ChatMessage message = new ChatMessage(LocalDateTime.of(2026, 9, 1, 12, 0), ChatMessage.Channel.WORLD, "Ann", "", "Ann", "hello", "");
        store.append("chat", message); store.flush();
        ChatGUI[] live = new ChatGUI[1]; ChatExplorer[] pages = new ChatExplorer[2]; AtomicInteger alerts = new AtomicInteger(); ChatFilters filters = new ChatFilters();
        JPanel[] host = new JPanel[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                PropertiesManager.setProperties(ChatExplorer.SHOW_IGNORED_PLAYERS, "false");
                live[0] = new ChatGUI(new TomatoData(), filters, false) { @Override void alert(TextPacket packet, ChatMessage msg) { alerts.incrementAndGet(); } };
                host[0] = new JPanel(); host[0].setVisible(false); host[0].add(live[0]); host[0].addNotify();
            });
            SessionPanel.Loaded loaded = live[0].historyWithPolicy(store, store.currentId(), 0, "");
            SwingUtilities.invokeAndWait(() -> {
                for (int i = 0; i < 2; i++) { pages[i] = (ChatExplorer)loaded.createView(); host[0].add(pages[i]); }
                JTable table = named(pages[0], "chat-messages", JTable.class); table.setRowSelectionInterval(0, 0);
                button(pages[0], "Ignore player").doClick();
                TextPacket packet = new TextPacket(); packet.name = "Ann"; packet.recipient = ""; packet.text = "hello";
                live[0].deliver(packet, message); assertEquals(0, alerts.get());
            });
            drain();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(0, named(pages[1], "chat-messages", JTable.class).getRowCount());
                assertEquals(0, named(live[0], "chat-messages", JTable.class).getRowCount());
                named(pages[0], "chat-channel-IGNORED", JToggleButton.class).doClick();
                JTable table = named(pages[0], "chat-messages", JTable.class); table.setRowSelectionInterval(0, 0); button(pages[0], "Unignore player").doClick();
            });
            drain();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(1, named(pages[1], "chat-messages", JTable.class).getRowCount());
                TextPacket packet = new TextPacket(); packet.text = "hello"; live[0].deliver(packet, message); assertEquals(1, alerts.get());
                filters.inherited(Arrays.asList("hello"));
            });
            drain();
            SwingUtilities.invokeAndWait(() -> assertEquals(0, named(pages[1], "chat-messages", JTable.class).getRowCount()));
            assertEquals(1, store.read(store.currentId(), "chat", ChatMessage.class).size());
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (host[0] != null) host[0].removeNotify();
                PropertiesManager.setProperties("chat.filters", old == null ? "{}" : old);
                PropertiesManager.setProperties(ChatExplorer.SHOW_IGNORED_PLAYERS, oldVisibility == null ? "" : oldVisibility);
            }); store.close();
        }
    }
    @Test public void receiptEvidenceSurvivesLocalUnignoreAndStaleDraftCannotClobberNewPolicy() throws Exception {
        ChatFilters filters = new ChatFilters(); ChatFilters.Settings first = filters.settings(); long revision = filters.edits();
        first.ignoredPlayers.add("Ann"); filters.apply(first, false);
        ChatMessage receipt = new ChatMessage(LocalDateTime.now(), ChatMessage.Channel.WORLD, "Ann", "", "Ann", "hello", "").withGameIgnored(true);
        assertTrue(filters.reason(receipt).startsWith("Ignored player"));
        ChatFilters.Settings next = filters.settings(); next.ignoredPlayers.clear(); filters.apply(next, false);
        assertEquals("In-game ignore observed at receipt", filters.reason(receipt));
        try { filters.apply(first, false, revision); fail(); } catch (IllegalStateException expected) { }
        next.gameIgnores = false; filters.apply(next, false); assertEquals("", filters.reason(receipt));
        SwingUtilities.invokeAndWait(() -> {
            ChatExplorer page = new ChatExplorer(() -> {}, filters, () -> "", false); page.accept(receipt);
            JTable table = named(page, "chat-messages", JTable.class); table.setRowSelectionInterval(0, 0);
            assertTrue(named(page, "chat-detail-header", JTextArea.class).getText().contains("observed at receipt"));
        });
    }
    @Test public void subscribersDetachAndFreshSubscriptionSeesLatestPolicy() {
        ChatFilters filters = new ChatFilters(); AtomicInteger calls = new AtomicInteger(); Runnable listener = calls::incrementAndGet;
        filters.addListener(listener); filters.inherited(Arrays.asList("one")); assertEquals(1, calls.get());
        filters.removeListener(listener); filters.inherited(Arrays.asList("two")); assertEquals(1, calls.get());
        assertEquals(2, filters.revision());
    }

    @Test public void failedFilterSaveRetainsInputAndActivePolicyWhileClassificationSnapshotsStayPinned() throws Exception {
        java.util.concurrent.CompletableFuture<util.PreferencesStore.SaveResult> failed = new java.util.concurrent.CompletableFuture<>();
        ChatFilters filters = new ChatFilters(value -> failed); ChatFilterPanel[] panel = new ChatFilterPanel[1];
        ChatMessage message = new ChatMessage(LocalDateTime.now(), ChatMessage.Channel.WORLD, "Ann", "", "Ann", "hello", "");
        ChatFilters.Classification previous = filters.snapshot();
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new ChatFilterPanel(filters, "Synthetic receipt state", () -> {}, () -> {});
            named(panel[0], "chat-ignored-players", JTextArea.class).setText("Ann");
            button(panel[0], "Save filters").doClick();
            assertTrue(filters.ignoresPlayer("Ann")); assertEquals("", previous.reason(message));
            named(panel[0], "chat-ignored-players", JTextArea.class).setText("Ann\nBea");
        });
        failed.complete(util.PreferencesStore.SaveResult.failed(1, new java.io.IOException("denied"))); drain();
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("Ann\nBea", named(panel[0], "chat-ignored-players", JTextArea.class).getText());
            assertTrue(named(panel[0], "chat-filter-save-status", JTextArea.class).getText().contains("Newer draft"));
            assertTrue(filters.ignoresPlayer("Ann")); assertFalse(filters.ignoresPlayer("Bea"));
            assertTrue(filters.saveStatus().contains("failed"));
        });
    }
}
