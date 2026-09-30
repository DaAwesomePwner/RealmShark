package tomato.gui.chat;

import java.awt.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
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
            ChatSection section = new ChatSection(ChatSectionTest::placeholderEditor);
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
            assertTrue("A section built later reads the saved value", named(new ChatSection(ChatSectionTest::placeholderEditor), "settings-chat-save", JCheckBox.class).isSelected());
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
            assertNotNull("The existing editor is embedded", named(editor, "chat-ignored-players", JTextArea.class));
            assertNotNull("…with its Save in the pinned footer (P6b)", named(named(section[0], "chat-filters-footer", JComponent.class), "chat-save-filters", AbstractButton.class));
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

    /**
     * Polish B2 (P6a evidence finding 9), kept by P6b's one scroll with the pinned footer: the editor's footer (Save
     * filters, Cancel and the save status) is whole in view as the section opens, with nothing scrolled, at the Settings page's
     * size in the real shell at 1240×800 font 13 (1016×690) and at 680×520 font 18 (592×328). The Save in view applies through the
     * live chat's rules, as before.
     */
    @Test public void theEditorsSaveAndCancelAreInViewWithoutScrollingAtBothReferenceSizes() throws Exception {
        SettingsPage[] page = new SettingsPage[1];
        JPanel padded = new JPanel(new BorderLayout());
        SwingUtilities.invokeAndWait(() -> {
            ChatGUI chat = new ChatGUI(null, filters, false);
            page[0] = new SettingsPage(new JPanel(), () -> {}, new JPanel(), new JPanel(), new JPanel(), new ChatSection(chat::filtersEditor), new JPanel());
            page[0].showSection(SettingsPage.CHAT);
            padded.setBorder(new EmptyBorder(24, 6, 6, 6));   // clear of the harness's painted title band, as LootEvidenceTest
            padded.add(page[0], BorderLayout.CENTER);
        });
        for (int[] size : new int[][] {{1016, 690, 13}, {592, 328, 18}}) {
            String at = size[0] + "x" + size[1] + " font " + size[2];
            SwingUtilities.invokeAndWait(() -> evidence.show(padded, "Settings chat " + at, size[0] + 12, size[1] + 30, size[2]));
            evidence.settle();
            SwingUtilities.invokeAndWait(() -> {
                evidence.capture("settings-chat-footer-" + size[0] + "x" + size[1] + "-font" + size[2]);
                assertEquals(at + ": the Settings page's size in the real shell", new Dimension(size[0], size[1]), page[0].getSize());
                for (String name : new String[] {"chat-save-filters", "chat-cancel-filters", "chat-filter-save-status"}) {
                    JComponent part = named(page[0], name, JComponent.class);
                    assertTrue(at + ": " + name + " is showing", part.isShowing() && part.getWidth() > 0 && part.getHeight() > 0);
                    assertEquals(at + ": " + name + " is whole in view without scrolling", new Rectangle(part.getSize()), part.getVisibleRect());
                }
                // P6b: one page holds Saving and the editor's body; the footer is pinned below it.
                JViewport scroll = named(page[0], "settings-chat-page", JScrollPane.class).getViewport();
                System.out.println(at + ": the page " + scroll.getHeight() + " px of " + scroll.getView().getHeight() + ", the footer "
                    + named(page[0], "chat-filters-footer", JComponent.class).getHeight() + " px");
                // The top has no scroll of its own any more: at font 13 it is whole in view as the section opens (the page below it may scroll).
                if (size[2] == 13) for (String name : new String[] {"settings-chat-save", "settings-chat-save-help", "settings-chat-filters-help"}) {
                    JComponent part = named(page[0], name, JComponent.class);
                    assertEquals(at + ": " + name + " is whole in view without scrolling", new Rectangle(part.getSize()), part.getVisibleRect());
                }
                VisualEvidence.completeButton(named(page[0], "chat-save-filters", AbstractButton.class));
                VisualEvidence.completeButton(named(page[0], "chat-cancel-filters", AbstractButton.class));
                VisualEvidence.completeButton(named(page[0], "settings-chat-save", JCheckBox.class));   // the top scrolls to it if short
            });
        }
        SwingUtilities.invokeAndWait(() -> {
            named(page[0], "chat-ignored-players", JTextArea.class).setText("ExampleVendor");
            named(page[0], "chat-save-filters", AbstractButton.class).doClick();
            assertTrue("The Save in view applies to the live chat's own rules", filters.ignoresPlayer("ExampleVendor"));
            assertEquals("Persisted once, like the dialog", 1, persisted.size());
        });
    }

    /**
     * P6b Task 12 (R3 B6): Settings › Chat is one scroll area, Saving and the whole editor in one page, with the editor's footer (Save
     * filters, Cancel, the save status) pinned below it, whole in view at 680×520 font 18. The embedded editor adds no inset of its
     * own: its first line starts at the section's edge, as the section's headings do.
     */
    @Test public void oneScrollAreaWithTheEditorsFooterPinnedBelowItAndNoExtraInset() throws Exception {
        SettingsPage[] page = new SettingsPage[1];
        SwingUtilities.invokeAndWait(() -> {
            ChatGUI chat = new ChatGUI(null, filters, false);
            page[0] = new SettingsPage(new JPanel(), () -> {}, new JPanel(), new JPanel(), new JPanel(), new ChatSection(chat::filtersEditor), new JPanel());
            page[0].showSection(SettingsPage.CHAT);
            evidence.show(page[0], "Settings Chat one scroll", 680, 520, 18);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            evidence.capture("p6b-settings-chat-680-18");
            ChatSection section = VisualEvidence.find(page[0], ChatSection.class, component -> true);
            List<JScrollPane> pages = pageScrolls(section);
            assertEquals("One scroll area (the filter lists' own editors aside): " + pages, 1, pages.size());
            JScrollPane scroll = pages.get(0);
            assertEquals("settings-chat-page", scroll.getName());
            assertEquals("The one page never scrolls sideways", scroll.getViewport().getWidth(), scroll.getViewport().getView().getWidth());
            assertNull("The embedded editor has no page scroll of its own", SocialArchiveTestSupport.named(section, "chat-filter-page", JScrollPane.class));
            JComponent footer = named(section, "chat-filters-footer", JComponent.class);
            assertNotNull("The editor's footer holder", footer);
            assertFalse("The footer is pinned below the page, not inside it", SwingUtilities.isDescendingFrom(footer, scroll));
            assertTrue("The footer is below the page", SwingUtilities.convertPoint(footer, 0, 0, section).y >= scroll.getY() + scroll.getHeight());
            for (String name : new String[] {"chat-save-filters", "chat-cancel-filters", "chat-filter-save-status"}) {
                JComponent part = named(section, name, JComponent.class);
                assertTrue(name + " is in the footer", SwingUtilities.isDescendingFrom(part, footer));
                assertTrue(name + " is showing", part.isShowing() && part.getWidth() > 0 && part.getHeight() > 0);
                assertEquals(name + " is whole in view", new Rectangle(part.getSize()), part.getVisibleRect());
            }
            VisualEvidence.completeButton(named(section, "chat-save-filters", AbstractButton.class));
            VisualEvidence.completeButton(named(section, "chat-cancel-filters", AbstractButton.class));
            JTextArea introduction = named(section, "chat-filter-introduction", JTextArea.class);
            SectionHeader heading = VisualEvidence.find(section, SectionHeader.class, header -> "Chat filters".equals(header.title()));
            assertEquals("No extra inset: the editor starts at the headings' edge", SwingUtilities.convertPoint(heading, 0, 0, section).x,
                SwingUtilities.convertPoint(introduction, 0, 0, section).x);
            assertEquals("No border of its own", new Insets(0, 0, 0, 0), VisualEvidence.find(section, ChatFilterPanel.class, panel -> true).getInsets());
            VisualEvidence.completeButton(named(section, "settings-chat-save", JCheckBox.class));
            VisualEvidence.completeText(introduction);
            VisualEvidence.reachable(named(section, "chat-ignored-players", JTextArea.class));
            VisualEvidence.completeText(named(section, "chat-filter-observed-status", JTextArea.class));
            evidence.capture("p6b-settings-chat-680-18-scrolled");
        });
    }

    /** P6b Task 12: every rebuild (Cancel, and the rules changed while hidden) refills the pinned footer, never adding a second one. */
    @Test public void theFooterSurvivesCancelAndARevisionRebuild() throws Exception {
        SettingsPage[] page = new SettingsPage[1];
        AbstractButton[] saves = new AbstractButton[2];
        SwingUtilities.invokeAndWait(() -> {
            ChatGUI chat = new ChatGUI(null, filters, false);
            page[0] = new SettingsPage(new JPanel(), () -> {}, new JPanel(), new JPanel(), new JPanel(), new ChatSection(chat::filtersEditor), new JPanel());
            page[0].showSection(SettingsPage.CHAT);
            evidence.show(page[0], "Settings Chat footer rebuilds", 680, 520, 18);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            saves[0] = named(page[0], "chat-save-filters", AbstractButton.class);
            named(page[0], "chat-ignored-players", JTextArea.class).setText("DraftOnly");
            named(page[0], "chat-cancel-filters", AbstractButton.class).doClick();
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            assertFooter(page[0], "after Cancel");
            saves[1] = named(page[0], "chat-save-filters", AbstractButton.class);
            assertNotSame("Cancel rebuilt the footer's Save", saves[0], saves[1]);
            assertEquals("", named(page[0], "chat-ignored-players", JTextArea.class).getText());
            page[0].showSection(SettingsPage.ABOUT);
            filters.togglePlayer("ElsewhereVendor");   // the Chat page's Ignore player, while Settings › Chat is hidden
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> page[0].showSection(SettingsPage.CHAT));
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            assertFooter(page[0], "after a revision rebuild");
            assertNotSame("The revision rebuilt the footer's Save", saves[1], named(page[0], "chat-save-filters", AbstractButton.class));
            assertEquals("ElsewhereVendor", named(page[0], "chat-ignored-players", JTextArea.class).getText());
            assertEquals("The rebuilt footer starts a new draft", "Draft changes apply only when you save.",
                named(page[0], "chat-filter-save-status", JTextArea.class).getText());
            named(page[0], "chat-ignored-players", JTextArea.class).setText("ElsewhereVendor\nExampleVendor");
            named(page[0], "chat-save-filters", AbstractButton.class).doClick();
            assertTrue("The rebuilt footer's Save applies to the live rules", filters.ignoresPlayer("ExampleVendor"));
        });
    }

    /** One footer holder below the one page scroll, holding exactly one Save, Cancel and status, each whole in view. */
    private static void assertFooter(SettingsPage page, String when) {
        ChatSection section = VisualEvidence.find(page, ChatSection.class, component -> true);
        assertEquals(when + ": one scroll area", 1, pageScrolls(section).size());
        JComponent footer = named(section, "chat-filters-footer", JComponent.class);
        assertNotNull(when + ": the footer holder", footer);
        for (String name : new String[] {"chat-save-filters", "chat-cancel-filters", "chat-filter-save-status"}) {
            assertEquals(when + ": one " + name, 1, count(section, name));
            JComponent part = named(section, name, JComponent.class);
            assertTrue(when + ": " + name + " is in the footer", SwingUtilities.isDescendingFrom(part, footer));
            assertTrue(when + ": " + name + " is showing", part.isShowing() && part.getWidth() > 0 && part.getHeight() > 0);
            assertEquals(when + ": " + name + " is whole in view", new Rectangle(part.getSize()), part.getVisibleRect());
        }
        assertEquals(when + ": one editor", 1, count(section, ChatFilterPanel.class));
    }

    /** The section's scroll panes other than the filter lists' own text editors. */
    private static List<JScrollPane> pageScrolls(Container root) {
        List<JScrollPane> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof JScrollPane && !(((JScrollPane) child).getViewport().getView() instanceof JTextArea)) found.add((JScrollPane) child);
            if (child instanceof Container) found.addAll(pageScrolls((Container) child));
        }
        return found;
    }

    private static int count(Container root, String name) {
        int count = 0;
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) count++;
            if (child instanceof Container) count += count((Container) child, name);
        }
        return count;
    }

    private static int count(Container root, Class<?> type) {
        int count = 0;
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) count++;
            if (child instanceof Container) count += count((Container) child, type);
        }
        return count;
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

    /** An empty editor for the Save chat checks (no rules involved). */
    private static ChatGUI.FiltersEditor placeholderEditor() { return new ChatGUI.FiltersEditor(new JPanel(), new JPanel()); }

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
