package tomato.gui.chat;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.Test;
import packets.incoming.*;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.TomatoPacketCapture;
import tomato.backend.data.*;
import tomato.gui.modern.VioletTheme;
import ui.UiTestLayout;
import util.PropertiesManager;
import static org.junit.Assert.*;

public class ChatFiltersTest {
    private static TextPacket packet(String sender, String recipient, String text) {
        TextPacket p = new TextPacket(); p.name = sender; p.recipient = recipient; p.text = text; p.objectId = 42; return p;
    }
    private static ChatMessage message(String sender, String recipient, String text) { return ChatMessage.from(packet(sender, recipient, text), "Aster"); }
    private static ChatMessage pm(String text) { return message("Vendor", "Aster", text); }

    @Test public void advertisementsNeedBothSalesWordingAndLinkAndPreserveOrdinaryConversations() {
        ChatFilters filters = new ChatFilters();
        for (String text : Arrays.asList("BUY cheap items at shop.example", "Fast delivery! HTTPS://SHOP.EXAMPLE",
                "discount at shop [dot] com", "CHEAP shop . com", "Cheap shop. com",
                "Buy at sh\u200bop.com", "Buy at \uff33\uff28\uff2f\uff30\uff0e\uff23\uff2f\uff2d", "Items $5 at store.example")) {
            assertFalse(text, filters.reason(pm(text)).isEmpty());
        }
        for (String text : Arrays.asList("Check this guide https://realmeye.com", "Anyone selling a shield?",
                "Cheap gear. Meet at nexus", "Anyone selling? We can meet.", "Cheap potion, 1.25 each")) {
            assertEquals(text, "", filters.reason(pm(text)));
        }
        assertEquals("", filters.reason(message("Vendor", "*Guild*", "Buy at shop.example")));
        assertEquals("", filters.reason(message("Vendor", "*Party*", "Buy at shop.example")));
        assertEquals("", filters.reason(message("Aster", "Vendor", "Buy at shop.example")));
        assertEquals("", filters.reason(message("#Oryx", "", "Buy at shop.example")));
        assertFalse(filters.reason(message("Vendor", "", "Buy at shop.example")).isEmpty());
        ChatFilters.Settings settings = filters.settings(); settings.whisperLinks = true; filters.apply(settings, false);
        assertEquals("Link in whisper", filters.reason(pm("Guide https://realmeye.com")));
        assertEquals("", filters.reason(message("Vendor", "", "Guide https://realmeye.com")));
    }

    @Test public void exactSenderIgnoresLiteralPhrasesAllowListsAndInheritedRulesHaveExplicitPrecedence() {
        ChatFilters filters = new ChatFilters(); ChatFilters.Settings s = filters.settings();
        s.ignoredPlayers = Arrays.asList("  VeNdOr  ", "", "vendor");
        s.phrases = Arrays.asList(" [SALE] ", "", "\u200b");
        s.allowedPlayers = Arrays.asList("Friend", "Vendor"); filters.apply(s, false);
        assertTrue(filters.reason(pm("hello")).startsWith("Ignored player"));
        assertEquals("", filters.reason(message("VendorTwo", "Aster", "hello")));
        assertEquals("", filters.reason(message("Aster", "Vendor", "[SALE]"))); // Ignore the sender, never the recipient.
        assertEquals("", filters.reason(ChatMessage.notice("[SALE]")));
        assertTrue(filters.reason(message("Other", "*Guild*", "[sale] today")).startsWith("Blocked phrase"));
        assertEquals("", filters.reason(message("Other", "", "s a l e")));
        assertEquals("", filters.reason(message("Friend", "Aster", "[sale] Buy at shop.example")));
        assertTrue(filters.reason(message("Friend", "Aster", "hi").withGameIgnored(true)).startsWith("In-game"));
        filters.inherited(Arrays.asList("LEGACY.EXAMPLE", "", "\u200b"));
        assertEquals(1, filters.inheritedCount());
        assertTrue(filters.reason(message("Other", "Aster", "legacy.example")).startsWith("Existing spam"));
        s = filters.settings(); s.inheritedRules = false; filters.apply(s, false);
        assertEquals("", filters.reason(message("Other", "Aster", "legacy.example")));
    }

    @Test(timeout = 3000) public void longUntrustedMessagesDoNotCauseUnboundedLinkScanning() {
        char[] text = new char[65000]; Arrays.fill(text, 'a');
        assertEquals("", new ChatFilters().reason(pm("cheap " + new String(text))));
    }

    @Test public void productionCaptureSubscribesToAccountLists() throws Exception {
        java.lang.reflect.Method register = tomato.Tomato.class.getDeclaredMethod("packetRegister", TomatoPacketCapture.class);
        register.setAccessible(true);
        packets.packetcapture.register.Register registry = packets.packetcapture.register.Register.INSTANCE;
        int before = registry.directListenerCount(packets.PacketType.ACCOUNTLIST);
        register.invoke(null, new TomatoPacketCapture(new TomatoData()));
        assertEquals(before + 1, registry.directListenerCount(packets.PacketType.ACCOUNTLIST));
    }

    @Test public void savedSettingsSurviveReloadAndCanBeRemovedWithoutAliasing() {
        String old = PropertiesManager.getProperty("chat.filters");
        try {
            ChatFilters filters = new ChatFilters(); ChatFilters.Settings s = filters.settings();
            s.ignoredPlayers.add("Vendor"); s.phrases.add("CUSTOM"); s.whisperLinks = true;
            filters.apply(s, true); s.ignoredPlayers.clear();
            ChatFilters loaded = ChatFilters.load();
            assertTrue(loaded.ignoresPlayer("vendor, metadata")); assertTrue(loaded.settings().whisperLinks);
            assertTrue(loaded.reason(message("Other", "Aster", "custom text")).startsWith("Blocked phrase"));
            loaded.togglePlayer("VENDOR");
            assertFalse(ChatFilters.load().ignoresPlayer("vendor"));
        } finally { PropertiesManager.setProperties("chat.filters", old == null ? "{}" : old); }
    }

    private static AccountListPacket accountList(int list, int action, String... ids) {
        AccountListPacket p = new AccountListPacket(); p.accountListId = list; p.lockAction = action; p.accountIds = ids; return p;
    }
    private static Entity player(TomatoData data, int id, String name, String account) {
        Entity e = new Entity(data, id, 0);
        StatData n = new StatData(); n.stringStatValue = name; e.stat.set(StatType.NAME_STAT, n);
        StatData a = new StatData(); a.stringStatValue = account; e.stat.set(StatType.ACCOUNT_ID_STAT, a);
        data.entityList.put(id, e); return e;
    }
    @Test public void observedIgnoresHandleSnapshotsDeltasUnknownActionsAndConnectionReset() {
        TomatoData data = new TomatoData(); player(data, 42, "Vendor", "account-a");
        ObservedIgnores ignores = new ObservedIgnores(); TextPacket p = packet("Vendor", "Aster", "hello");
        ignores.accept(accountList(0, -1, "account-a")); assertFalse(ignores.matches(p, data));
        ignores.accept(accountList(1, -1, "account-a")); assertTrue(ignores.matches(p, data));
        ignores.accept(accountList(1, 1, "account-b")); assertTrue(ignores.matches(p, data));
        ignores.accept(accountList(1, 17, "account-a")); assertTrue(ignores.matches(p, data));
        ignores.accept(accountList(1, 0, "account-a")); assertFalse(ignores.matches(p, data));
        ignores.accept(accountList(1, 1, "account-a")); data.entityList.clear();
        assertTrue(ignores.matches(p, data)); // Previously correlated name still works across a map change.
        ignores.accept(accountList(1, -1, "account-b")); assertFalse(ignores.matches(p, data));
        ignores.accept(accountList(1, -1, "account-a")); ignores.reset();
        assertFalse(ignores.matches(p, data)); // Account and identity state never leaks to the next connection.
        ignores.accept(accountList(1, -1, "account-a")); assertFalse(ignores.matches(p, data));
        player(data, 42, "DifferentPlayer", "account-a");
        assertFalse(ignores.matches(p, data)); // Reused object ID must not identify a different sender.
        assertTrue(ignores.status().contains("Remote whisper identities may be unavailable"));
    }

    @Test public void ignoredMessagesAreRetainedButNeverReachAnyChatAlertAndOrdinaryChatStillDoes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String oldVisibility = PropertiesManager.getProperty(ChatExplorer.SHOW_IGNORED_PLAYERS);
            PropertiesManager.setProperties(ChatExplorer.SHOW_IGNORED_PLAYERS, "false");
            ChatFilters filters = new ChatFilters(); ChatFilters.Settings s = filters.settings();
            s.ignoredPlayers.add("Blocked"); s.phrases.add("CUSTOM-SPAM"); filters.apply(s, false);
            TomatoData data = new TomatoData();
            data.player = new Entity(data, 1, 0) { @Override public String name() { return "Aster"; } };
            AtomicInteger alerts = new AtomicInteger();
            ChatGUI ui = new ChatGUI(data, filters, false) {
                @Override void alert(TextPacket p, ChatMessage message) { alerts.incrementAndGet(); }
            };
            boolean oldSave = ChatGUI.save;
            ChatGUI.save = true;
            try {
            ChatGUI.updateChat(packet("Vendor", "Aster", "Buy at shop.example"));
            ChatGUI.updateChat(packet("Blocked", "*Guild*", "ignored-player-hidden"));
            ChatGUI.updateChat(packet("Other", "*Party*", "custom-spam"));
            player(data, 42, "GameIgnored", "account-a");
            new TomatoPacketCapture(data).packetCapture(accountList(1, -1, "account-a"));
            ChatGUI.updateChat(packet("GameIgnored", "Aster", "hi"));
            assertEquals(0, alerts.get()); assertEquals(0, find(ui, JTable.class, "chat-messages").getRowCount());
            button(ui, "chat-show-ignored-players").doClick();
            assertEquals(2, find(ui, JTable.class, "chat-messages").getRowCount());
            ChatGUI.updateChat(packet("Blocked", "*Guild*", "ignored-player-visible"));
            assertEquals(0, alerts.get()); assertEquals(3, find(ui, JTable.class, "chat-messages").getRowCount());
            button(ui, "chat-show-ignored-players").doClick();
            assertEquals(0, find(ui, JTable.class, "chat-messages").getRowCount());
            ChatGUI.updateChat(packet("Friend", "Aster", "Meet at nexus"));
            assertEquals(1, alerts.get()); assertEquals(1, find(ui, JTable.class, "chat-messages").getRowCount());
            button(ui, "chat-channel-IGNORED").doClick();
            assertEquals(5, find(ui, JTable.class, "chat-messages").getRowCount());
            StringBuilder log = new StringBuilder();
            File[] logs = new File("chat").listFiles((dir, name) -> name.endsWith(".data"));
            assertNotNull(logs);
            try {
                for (File file : logs) log.append(new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.Charset.defaultCharset()));
            } catch (java.io.IOException e) { throw new AssertionError(e); }
            assertTrue(log.toString().contains("Buy at shop.example"));
            assertTrue(log.toString().contains("[Ignored:"));
            assertTrue(log.toString().contains("ignored-player-hidden"));
            assertTrue(log.toString().contains("ignored-player-visible"));
            assertTrue(log.toString().contains("Meet at nexus"));
            } finally {
                ChatGUI.save = oldSave;
                PropertiesManager.setProperties(ChatExplorer.SHOW_IGNORED_PLAYERS, oldVisibility == null ? "" : oldVisibility);
            }
        });
    }

    @Test public void ignoreActionsRefilterHistoryPreserveStarsAndExportReasonsAndResetDoesNotDisableRules() throws Exception {
        String old = PropertiesManager.getProperty("chat.filters");
        try { SwingUtilities.invokeAndWait(() -> {
            ChatFilters filters = new ChatFilters(); ChatExplorer ui = new ChatExplorer(() -> {}, filters, () -> "");
            ui.accept(message("Wren", "Aster", "hi")); ui.accept(message("Aster", "Wren", "hello"));
            JTable table = find(ui, JTable.class, "chat-messages");
            table.setRowSelectionInterval(0, 0); button(ui, "Star").doClick(); button(ui, "chat-ignore-player").doClick();
            assertEquals(1, table.getRowCount()); assertTrue(ui.filteredTranscript().contains("hello"));
            button(ui, "chat-channel-IGNORED").doClick(); assertEquals(1, table.getRowCount());
            assertTrue(ui.filteredTranscript().contains("[PM]")); assertTrue(ui.filteredTranscript().contains("[Ignored: Ignored player: Wren]"));
            table.setRowSelectionInterval(0, 0); assertEquals("\u2605", table.getValueAt(0, 0));
            button(ui, "Reset").doClick(); assertEquals(1, table.getRowCount());
            button(ui, "chat-channel-IGNORED").doClick(); table.setRowSelectionInterval(0, 0);
            button(ui, "chat-ignore-player").doClick(); assertEquals(0, table.getRowCount());
            button(ui, "chat-channel-ALL").doClick(); assertEquals(2, table.getRowCount());
            ChatFilters.Settings s = filters.settings(); s.phrases.add("hello"); filters.apply(s, false); ui.refresh(false);
            assertEquals(2, table.getRowCount()); // Own outgoing message is exempt.
            s.phrases.add("hi"); filters.apply(s, false); ui.refresh(false); assertEquals(1, table.getRowCount());
            button(ui, "chat-channel-IGNORED").doClick(); find(ui, JTextField.class, "chat-search").setText("WREN"); ui.refresh(false);
            assertEquals(1, table.getRowCount());
            ui.clear(); assertEquals(0, table.getRowCount()); assertTrue(filters.reason(pm("hi")).startsWith("Blocked phrase"));
        }); } finally { PropertiesManager.setProperties("chat.filters", old == null ? "{}" : old); }
    }

    @Test public void editorSavesRulesAndRendersAlongsideIgnoredDesktopAndCompactViews() throws Exception {
        String old = PropertiesManager.getProperty("chat.filters");
        try { SwingUtilities.invokeAndWait(() -> {
            VioletTheme.install(); ChatFilters filters = new ChatFilters(); AtomicInteger saved = new AtomicInteger();
            ChatFilterPanel editor = new ChatFilterPanel(filters, "No full ignore list captured; remote identities may be unavailable.", saved::incrementAndGet, () -> {});
            find(editor, JTextArea.class, "chat-ignored-players").setText("ExampleVendor\n\nEXAMPLEVENDOR");
            find(editor, JTextArea.class, "chat-blocked-phrases").setText("sale.example\n[cheap]");
            button(editor, "chat-save-filters").doClick();
            assertEquals(1, saved.get()); assertTrue(ChatFilters.load().ignoresPlayer("ExampleVendor"));
            assertEquals(1, filters.settings().ignoredPlayers.size());
            JFrame frame = new JFrame("Chat filters - synthetic data");
            try {
                frame.setContentPane(editor); frame.setSize(660, 600); frame.setVisible(true); frame.validate();
                UiTestLayout.settle(frame);
                snapshot(frame, "chat-filter-settings");
                assertTrue("Editor rectangle: " + find(editor, JTextArea.class, "chat-ignored-players").getVisibleRect(),
                        find(editor, JTextArea.class, "chat-ignored-players").getVisibleRect().height > 90);
                ChatExplorer ui = new ChatExplorer(() -> {}, filters, () -> "");
                ui.accept(pm("Buy at shop.example - fast delivery!"));
                ui.accept(message("Friend", "Aster", "Meet at nexus"));
                ui.accept(message("ExampleVendor", "Aster", "Another offer"));
                frame.setContentPane(ui); button(ui, "chat-channel-IGNORED").doClick();
                JTable table = find(ui, JTable.class, "chat-messages"); table.setRowSelectionInterval(0, 0);
                for (int width : new int[]{1060, 500}) {
                    frame.setSize(width, width == 500 ? 600 : 740); frame.validate();
                    UiTestLayout.settle(frame);
                    assertTrue(table.getVisibleRect().height > 100);
                    assertTrue(button(ui, "chat-channel-IGNORED").getWidth() >= 60);
                    AbstractButton toggle = button(ui, "chat-show-ignored-players");
                    assertTrue(toggle.isShowing());
                    assertEquals(new Rectangle(0, 0, toggle.getWidth(), toggle.getHeight()), toggle.getVisibleRect());
                    snapshot(frame, "chat-ignored-" + width);
                }
            } finally { frame.dispose(); }
        }); } finally { PropertiesManager.setProperties("chat.filters", old == null ? "{}" : old); }
    }
    private static void snapshot(JFrame frame, String name) {
        BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); frame.printAll(g); g.dispose();
        try { File folder = new File("screenshots"); folder.mkdirs(); ImageIO.write(image, "png", new File(folder, name + ".png")); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static AbstractButton button(Container root, String name) {
        for (Component c : root.getComponents()) {
            if (c instanceof AbstractButton && (name.equals(c.getName()) || name.equals(((AbstractButton)c).getText()))) return (AbstractButton)c;
            if (c instanceof Container) { AbstractButton found = button((Container)c, name); if (found != null) return found; }
        } return null;
    }
    private static <T> T find(Container root, Class<T> type, String name) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && name.equals(c.getName())) return type.cast(c);
            if (c instanceof Container) { T found = find((Container)c, type, name); if (found != null) return found; }
        } return null;
    }
}
