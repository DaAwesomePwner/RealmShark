package tomato.gui.chat;

import java.awt.*;
import java.lang.reflect.*;
import java.time.LocalDateTime;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.data.QuestData;
import packets.incoming.QuestFetchResponsePacket;
import tomato.Tomato;
import tomato.backend.TomatoPacketCapture;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.history.SessionPanel;
import tomato.gui.modern.WorkspaceShell;
import tomato.history.*;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;

/** Actual shell wiring with synthetic history; no frame, focus, remote rules, or capture. */
public class ShellHookIntegrationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final Map<Field,Object> original = new LinkedHashMap<>();
    private WorkspaceShell shell;
    private SessionStore store;
    private TomatoData data;
    private String filters, ignoredVisibility;

    @Before public void open() throws Exception {
        filters = PropertiesManager.getProperty("chat.filters");
        ignoredVisibility = PropertiesManager.getProperty("chat.showIgnoredPlayers");
        PropertiesManager.setProperties("chat.filters", "{}");
        store = new SessionStore(temp.newFolder().toPath(), true, "synthetic");
        remember(AppHistory.class, "store", store); remember(Tomato.class, "preview", true);
        for (Class<?> type : new Class<?>[]{TomatoGUI.class, ChatGUI.class})
            for (Field field : type.getDeclaredFields()) if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                field.setAccessible(true); original.put(field, field.get(null));
            }
        data = new TomatoData();
        SwingUtilities.invokeAndWait(() -> shell = (WorkspaceShell)new TomatoGUI(data).createWorkspace());
    }
    private void remember(Class<?> type, String name, Object next) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); original.put(field, field.get(null)); field.set(null, next);
    }
    @After public void close() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (shell != null) shell.removeNotify();
            try { for (Map.Entry<Field,Object> entry : original.entrySet()) entry.getKey().set(null, entry.getValue()); }
            catch (IllegalAccessException e) { throw new AssertionError(e); }
            PropertiesManager.setProperties("chat.filters", filters == null ? "{}" : filters);
            PropertiesManager.setProperties("chat.showIgnoredPlayers", ignoredVisibility == null ? "false" : ignoredVisibility);
        });
        if (store != null) store.close();
    }

    @Test public void shellQuestsReceiveScopedPacketsAndRejectTheLegacyUnscopedBypass() throws Exception {
        data.progression().reset("synthetic-account", "fixture identified");
        QuestData quest = new QuestData(); quest.id = "scoped"; quest.name = "Scoped fixture quest"; quest.description = "Synthetic";
        quest.requirements = new int[0]; quest.rewards = new int[0];
        QuestFetchResponsePacket packet = new QuestFetchResponsePacket(); packet.quests = new QuestData[]{quest};
        new TomatoPacketCapture(data).packetCapture(packet);
        SwingUtilities.invokeAndWait(() -> {
            JTable table = named(shell, "quest-table", JTable.class);
            assertEquals(1, table.getRowCount()); assertEquals("Scoped fixture quest", table.getValueAt(0, 1));
            TomatoGUI.updateQuests(new QuestData[0]);
        });
        SwingUtilities.invokeAndWait(() -> assertEquals(1, named(shell, "quest-table", JTable.class).getRowCount()));
        data.captureStopped();
        SwingUtilities.invokeAndWait(() -> assertTrue(named(shell, "quest-capture-context", JTextArea.class).getText().contains("Stale / unverified")));
    }

    @Test public void shellHistoryLoaderSharesTheLiveChatPolicy() throws Exception {
        ChatMessage message = new ChatMessage(LocalDateTime.of(2026, 9, 1, 12, 0), ChatMessage.Channel.WORLD,
            "IntegrationAnn", "", "IntegrationAnn", "synthetic conversation", "");
        store.append("chat", message); store.flush();
        SessionPanel panel = named(shell, "chat-session-view", SessionPanel.class);
        SwingUtilities.invokeAndWait(() -> panel.selectSession(SessionStore.ALL));
        await(() -> archivedChat(panel) != null && named(archivedChat(panel), "chat-messages", JTable.class).getRowCount() == 1);
        Field field = ChatGUI.class.getDeclaredField("filters"); field.setAccessible(true);
        ChatFilters livePolicy = (ChatFilters)field.get(find(panel, ChatGUI.class));
        SwingUtilities.invokeAndWait(() -> {
            ChatExplorer saved = archivedChat(panel);
            JTable table = named(saved, "chat-messages", JTable.class); table.setRowSelectionInterval(0, 0);
            button(saved, "Ignore player").doClick();
            assertTrue(livePolicy.ignoresPlayer("IntegrationAnn"));
        });
        assertEquals(1, store.read(store.currentId(), "chat", ChatMessage.class).size());
    }

    private static ChatExplorer archivedChat(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof ChatGUI) continue;
            if (c instanceof ChatExplorer) return (ChatExplorer)c;
            if (c instanceof Container) { ChatExplorer found = archivedChat((Container)c); if (found != null) return found; }
        }
        return null;
    }
    private static AbstractButton button(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof AbstractButton && text.equals(((AbstractButton)c).getText())) return (AbstractButton)c;
            if (c instanceof Container) { AbstractButton found = button((Container)c, text); if (found != null) return found; }
        }
        return null;
    }
    private static <T> T find(Container root, Class<T> type) { return named(root, null, type); }
    private static <T> T named(Container root, String name, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && (name == null || name.equals(c.getName()))) return type.cast(c);
            if (c instanceof Container) { T found = named((Container)c, name, type); if (found != null) return found; }
        }
        return null;
    }
}
