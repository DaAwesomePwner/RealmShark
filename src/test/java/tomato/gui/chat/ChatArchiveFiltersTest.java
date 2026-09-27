package tomato.gui.chat;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.history.*;
import tomato.history.SessionStore;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.*;

/** Saved Chat facets and dates live in the drawer; the message table and actions stay in the view. */
public class ChatArchiveFiltersTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String ignored;
    @Before public void defaultIgnoredPlayers() { ignored = PropertiesManager.getProperty(ChatExplorer.SHOW_IGNORED_PLAYERS); PropertiesManager.setProperties(ChatExplorer.SHOW_IGNORED_PLAYERS, "false"); }
    @After public void restoreIgnoredPlayers() { PropertiesManager.setProperties(ChatExplorer.SHOW_IGNORED_PLAYERS, ignored == null ? "" : ignored); }

    @Test public void chatFacetsMoveToTheDrawerAndBecomeChips() throws Exception {
        Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath(); ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory(); String session;
        try (SessionStore source = new SessionStore(root, true, "chat-filters")) {
            session = source.currentId();
            source.append("chat", new ChatMessage(LocalDateTime.of(2026, 9, 22, 12, 0), ChatMessage.Channel.GUILD, "Ann", "", "Ann", "guild hello", ""));
            source.append("chat", new ChatMessage(LocalDateTime.of(2026, 9, 22, 12, 1), ChatMessage.Channel.WORLD, "Bo", "", "Bo", "world hello", ""));
            source.flush();
        }
        try (SessionStore store = new SessionStore(root, false, "reader")) {
            ChatArchiveClient client = new ChatArchiveClient(store, new ChatFilters(), null, scratch);
            ArchiveWorkspace<ChatArchiveClient.Row, ChatArchiveClient.Facets, ChatArchiveClient.Sort> ws = edt(() -> SessionPanel.queried(store, "chat", new JPanel(), client, memory.states));
            try {
                edt(() -> { ws.changeQuery(ChatArchiveClient.query().withScope(session)); return null; });
                await(() -> ArchiveNativeSupport.ready(ws) && ws.displayedPage().matches == 2);
                edt(() -> {
                    JComponent drawer = ws.filterBar().drawerContent();
                    assertTrue(SwingUtilities.isDescendingFrom(named(ws, "chat-archive-channel", JComboBox.class), drawer));
                    assertTrue(SwingUtilities.isDescendingFrom(named(ws, "social-date-from", JTextField.class), drawer));
                    assertFalse("Message actions stay in the view", SwingUtilities.isDescendingFrom(button(ws, "Toggle star"), drawer));
                    named(ws, "chat-archive-channel", JComboBox.class).setSelectedItem(ChatMessage.Channel.GUILD); return null; });
                await(() -> ArchiveNativeSupport.ready(ws) && ws.displayedPage().matches == 1);
                edt(() -> { assertEquals(Collections.singletonList("Channel: Guild"), ArchiveNativeSupport.chipLabels(ws.filterBar()));
                    ArchiveNativeSupport.removeChip(ws.filterBar(), "Channel: Guild"); return null; });
                await(() -> ArchiveNativeSupport.ready(ws) && ws.displayedPage().matches == 2);
                edt(() -> { assertEquals("ALL", ws.state().query.facets().channel); assertEquals(0, ws.filterBar().activeCount()); return null; });
            } finally { edt(() -> { ws.close(); return null; }); }
        }
    }
}
