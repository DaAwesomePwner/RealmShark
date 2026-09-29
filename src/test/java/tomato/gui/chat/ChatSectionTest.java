package tomato.gui.chat;

import java.awt.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import tomato.gui.kit.SectionHeader;
import tomato.gui.maingui.TomatoMenuBar;
import tomato.gui.settings.ChatSection;
import tomato.gui.settings.SettingsPage;
import ui.VisualEvidence;
import util.PreferencesStore;
import util.PropertiesManager;
import static org.junit.Assert.*;

/**
 * P6a: Settings › Chat. Save chat is Chat › Save Chat (one preference, one effect, kept in sync); the filter editor is the Chat
 * filters… editor over the live chat's own rules. In the chat package so the rules can be in memory (no disk, no network).
 */
public class ChatSectionTest {
    @Rule public final VisualEvidence evidence = new VisualEvidence("redesign-p6a-settings");
    private static final String SAVE_CHAT = "saveChat";
    private final List<String> persisted = new ArrayList<>();
    private final List<String> openedSettings = new ArrayList<>();
    private String savedChat;
    private boolean savedEffect;
    private Object sniffer;
    private ChatFilters filters;

    @Before public void isolateSaveChat() throws Exception {
        PropertiesManager.flush().toCompletableFuture().get(3, TimeUnit.SECONDS);
        savedChat = PropertiesManager.getProperty(SAVE_CHAT);
        savedEffect = ChatGUI.save;
        preferences().remove(SAVE_CHAT);
        ChatGUI.save = false;
        sniffer = snifferField().get(null); // TomatoMenuBar.make() replaces the shared capture item; restored below
        filters = new ChatFilters(json -> { persisted.add(json); return CompletableFuture.completedFuture(PreferencesStore.SaveResult.saved(0)); });
    }

    @After public void restoreSaveChat() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
        snifferField().set(null, sniffer);
        PropertiesManager.flush().toCompletableFuture().get(3, TimeUnit.SECONDS);
        if (savedChat == null) preferences().remove(SAVE_CHAT); else PropertiesManager.setProperties(SAVE_CHAT, savedChat);
        ChatGUI.save = savedEffect;
        PropertiesManager.flush().toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    @Test public void saveChatIsTheMenusPreferenceAndEffectKeptInSyncBothWays() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ChatSection section = new ChatSection(JPanel::new);
            assertEquals("settings-chat", section.getName());
            JCheckBox save = named(section, "settings-chat-save", JCheckBox.class);
            assertEquals("Save chat", save.getText());
            assertFalse("An absent preference is off, as the menu reads it", save.isSelected());
            JCheckBoxMenuItem menu = (JCheckBoxMenuItem) find(new TomatoMenuBar().make(), "Save Chat");
            assertFalse(menu.isSelected());

            save.doClick();
            assertEquals("The menu's key and value", "true", PropertiesManager.getProperty(SAVE_CHAT));
            assertTrue("The menu's effect: chat is logged", ChatGUI.save);
            assertTrue("Chat › Save Chat follows the section", menu.isSelected());
            menu.doClick();
            assertEquals("false", PropertiesManager.getProperty(SAVE_CHAT));
            assertFalse(ChatGUI.save);
            assertFalse("The section follows Chat › Save Chat", save.isSelected());
            menu.doClick();
            assertTrue(save.isSelected());
            assertTrue("A section built later reads the saved value", named(new ChatSection(JPanel::new), "settings-chat-save", JCheckBox.class).isSelected());
            assertNotNull(named(section, "settings-chat-save-help", JTextArea.class));
            for (String title : new String[] {"Saving", "Chat filters"})
                assertNotNull(title, VisualEvidence.find(section, SectionHeader.class, header -> title.equals(header.title())));
        });
    }

    @Test public void theEmbeddedEditorSavesThroughTheLiveRulesAndCancelDiscardsTheDraft() throws Exception {
        ChatGUI[] chat = new ChatGUI[1];
        ChatSection[] section = new ChatSection[1];
        SwingUtilities.invokeAndWait(() -> {
            chat[0] = new ChatGUI(null, filters, false);
            section[0] = new ChatSection(chat[0]::filtersEditor);
            JComponent editor = named(section[0], "chat-filters-editor", JComponent.class);
            assertNotNull("The existing editor is embedded", named(editor, "chat-save-filters", AbstractButton.class));
            named(section[0], "chat-ignored-players", JTextArea.class).setText("ExampleVendor");
            named(section[0], "chat-blocked-phrases", JTextArea.class).setText("sale.example");
            named(section[0], "chat-save-filters", AbstractButton.class).doClick();
            assertTrue("Saved into the live chat's own rules", filters.ignoresPlayer("ExampleVendor"));
            assertFalse(filters.reason(ChatMessage.from(packet("Vendor", "", "cheap at sale.example"), "Aster")).isEmpty());
            assertEquals("Persisted once, like the dialog", 1, persisted.size());
            assertTrue(persisted.get(0), persisted.get(0).contains("ExampleVendor"));
            assertEquals("DraftSaveStatus reports the submission", "Applied now; saving to disk…",
                named(section[0], "chat-filter-save-status", JTextArea.class).getText());
        });
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("DraftSaveStatus reports the completed save", "Submitted changes saved.",
                named(section[0], "chat-filter-save-status", JTextArea.class).getText());
            named(section[0], "chat-ignored-players", JTextArea.class).setText("ExampleVendor\nDraftOnly");
            named(section[0], "chat-filter-advertisements", JCheckBox.class).doClick();
            JTextArea draft = named(section[0], "chat-ignored-players", JTextArea.class);
            named(section[0], "chat-cancel-filters", AbstractButton.class).doClick();
            JTextArea rebuilt = named(section[0], "chat-ignored-players", JTextArea.class);
            assertNotSame("Cancel rebuilds the editor", draft, rebuilt);
            assertEquals("The editor shows the saved rules again", "ExampleVendor", rebuilt.getText());
            assertTrue("The draft's checkbox change is gone", named(section[0], "chat-filter-advertisements", JCheckBox.class).isSelected());
            assertFalse("Nothing was applied", filters.ignoresPlayer("DraftOnly"));
            assertEquals(1, persisted.size());
            assertEquals("The rebuilt editor starts a new draft", "Draft changes apply only when you save.",
                named(section[0], "chat-filter-save-status", JTextArea.class).getText());
        });
    }

    @Test public void theEditorRebuildsWhenShownAfterTheRulesChangedElsewhereAndFitsAtFont18() throws Exception {
        SettingsPage[] page = new SettingsPage[1];
        SwingUtilities.invokeAndWait(() -> {
            ChatGUI chat = new ChatGUI(null, filters, false);
            page[0] = new SettingsPage(new JPanel(), () -> {}, new JPanel(), new JPanel(), new JPanel(), new ChatSection(chat::filtersEditor), new JPanel());
            page[0].showSection(SettingsPage.CHAT);
            evidence.show(page[0], "Settings Chat", 680, 520, 18);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            ChatSection section = VisualEvidence.find(page[0], ChatSection.class, component -> true);
            JViewport viewport = VisualEvidence.find(section, JScrollPane.class, scroll -> true).getViewport();
            assertEquals("The section never scrolls sideways", viewport.getWidth(), viewport.getView().getWidth());
            VisualEvidence.completeButton(named(section, "settings-chat-save", JCheckBox.class));
            VisualEvidence.completeText(named(section, "settings-chat-save-help", JTextArea.class));
            VisualEvidence.completeText(named(section, "settings-chat-filters-help", JTextArea.class));
            evidence.capture("settings-chat-top-680-18");
            VisualEvidence.completeButton(named(section, "chat-save-filters", AbstractButton.class));
            VisualEvidence.completeButton(named(section, "chat-cancel-filters", AbstractButton.class));
            VisualEvidence.reachable(named(section, "chat-ignored-players", JTextArea.class));
            evidence.capture("settings-chat-680-18");
            page[0].showSection(SettingsPage.ABOUT);
            filters.togglePlayer("ElsewhereVendor"); // the Chat page's Ignore player, while Settings › Chat is hidden
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> page[0].showSection(SettingsPage.CHAT));
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("Shown again, the editor offers the current rules", "ElsewhereVendor",
                named(page[0], "chat-ignored-players", JTextArea.class).getText());
        });
    }

    /** With the settings hook, the Chat menu ends with a link to this section; without it the menu is exactly as before. */
    @Test public void chatMenuLinksToTheSectionOnlyWhileTheHookIsSet() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TomatoMenuBar bar = new TomatoMenuBar();
            JMenu menu = (JMenu) find(bar.make(), "Chat");
            assertEquals("No hook: today's Chat menu", List.of("Chat Message Pings", "Save Chat", "—", "Clear Chat"), texts(menu));
            bar.onOpenSettings(openedSettings::add);
            assertEquals(List.of("Chat Message Pings", "Save Chat", "—", "Clear Chat", "—", "Chat settings…"), texts(menu));
            ((JMenuItem) menu.getMenuComponent(menu.getMenuComponentCount() - 1)).doClick();
            assertEquals(List.of(SettingsPage.CHAT), openedSettings);
            bar.onOpenSettings(null);
            assertEquals(List.of("Chat Message Pings", "Save Chat", "—", "Clear Chat"), texts(menu));
        });
    }

    private static packets.incoming.TextPacket packet(String sender, String recipient, String text) {
        packets.incoming.TextPacket p = new packets.incoming.TextPacket(); p.name = sender; p.recipient = recipient; p.text = text; p.objectId = 42; return p;
    }

    private static List<String> texts(JMenu menu) {
        List<String> texts = new ArrayList<>();
        for (Component item : menu.getMenuComponents()) texts.add(item instanceof JMenuItem ? ((JMenuItem) item).getText() : "—");
        return texts;
    }

    private static JMenuItem find(MenuElement root, String text) {
        for (MenuElement child : root.getSubElements()) {
            if (child instanceof JMenuItem && text.equals(((JMenuItem) child).getText())) return (JMenuItem) child;
            JMenuItem found = find(child, text);
            if (found != null) return found;
        }
        return null;
    }

    private static <T extends Component> T named(Container root, String name, Class<T> type) { return VisualEvidence.named(root, name, type); }

    private static Field snifferField() throws Exception {
        Field field = TomatoMenuBar.class.getDeclaredField("sniffer");
        field.setAccessible(true);
        return field;
    }

    private static Properties preferences() throws Exception {
        Field field = PropertiesManager.class.getDeclaredField("properties"); field.setAccessible(true);
        return (Properties) field.get(null);
    }
}
