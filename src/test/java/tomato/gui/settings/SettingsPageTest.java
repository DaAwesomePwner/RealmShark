package tomato.gui.settings;

import java.awt.*;
import java.awt.event.ComponentEvent;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import ui.VisualEvidence;
import static org.junit.Assert.*;

public class SettingsPageTest {
    @Rule public final VisualEvidence evidence = new VisualEvidence("redesign-p6a-settings");
    private static final java.util.List<String> SIX = java.util.List.of("settings-section-notifications", "settings-section-general",
        "settings-section-appearance", "settings-section-loot-filters", "settings-section-chat", "settings-section-about");

    @Test public void sectionsSwitchAndTheListMovesAboveTheContentWhenNarrow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JLabel notifications = new JLabel("Synthetic notifications"), appearance = new JLabel("Synthetic appearance");
            JLabel general = new JLabel("Synthetic general");
            SettingsPage page = new SettingsPage(notifications, () -> {}, general, appearance);
            assertEquals("settings-page", page.getName());
            assertEquals(SettingsPage.NOTIFICATIONS, page.currentSection());
            assertTrue(notifications.isVisible());
            assertFalse(appearance.isVisible());
            assertFalse(general.isVisible());
            java.util.List<String> order = new java.util.ArrayList<>();
            for (Component button : named(page, "settings-sections", JComponent.class).getComponents()) order.add(button.getName());
            assertEquals("Spec §6.7 order: Notifications, General, Appearance", java.util.List.of("settings-section-notifications",
                "settings-section-general", "settings-section-appearance"), order);
            assertEquals("general", SettingsPage.GENERAL);
            named(page, "settings-section-general", AbstractButton.class).doClick();
            assertEquals(SettingsPage.GENERAL, page.currentSection());
            assertTrue(general.isVisible());
            assertFalse(notifications.isVisible());
            assertEquals("General", named(page, "settings-section-general", AbstractButton.class).getText());
            named(page, "settings-section-appearance", AbstractButton.class).doClick();
            assertEquals(SettingsPage.APPEARANCE, page.currentSection());
            assertTrue(appearance.isVisible());
            assertFalse(notifications.isVisible());
            assertTrue(named(page, "settings-section-appearance", AbstractButton.class).isSelected());
            page.showSection("no-such-section");
            assertEquals("Unknown sections are ignored", SettingsPage.APPEARANCE, page.currentSection());
            page.showSection(SettingsPage.GENERAL);
            assertEquals(SettingsPage.GENERAL, page.currentSection());
            assertTrue(named(page, "settings-section-general", AbstractButton.class).isSelected());
            page.showSection(SettingsPage.APPEARANCE);
            JComponent sections = named(page, "settings-sections", JComponent.class);
            BorderLayout layout = (BorderLayout) page.getLayout();
            page.setSize(1000, 600);
            page.dispatchEvent(new ComponentEvent(page, ComponentEvent.COMPONENT_RESIZED));
            assertEquals(BorderLayout.WEST, layout.getConstraints(sections.getParent()));
            page.setSize(600, 600);
            page.dispatchEvent(new ComponentEvent(page, ComponentEvent.COMPONENT_RESIZED));
            assertEquals(BorderLayout.NORTH, layout.getConstraints(sections.getParent()));
        });
    }

    @Test public void openingSettingsRefreshesNotificationsOnlyWhenThatSectionIsShown() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            int[] refreshed = {0};
            SettingsPage page = new SettingsPage(new JPanel(), () -> refreshed[0]++, new JPanel(), new JPanel());
            page.dispatchEvent(new ComponentEvent(page, ComponentEvent.COMPONENT_SHOWN));
            assertEquals(1, refreshed[0]);
            page.showSection(SettingsPage.APPEARANCE);
            page.dispatchEvent(new ComponentEvent(page, ComponentEvent.COMPONENT_SHOWN));
            assertEquals(1, refreshed[0]);
            page.showSection(SettingsPage.GENERAL);
            page.dispatchEvent(new ComponentEvent(page, ComponentEvent.COMPONENT_SHOWN));
            assertEquals("Opening on General does not refresh Notifications", 1, refreshed[0]);
        });
    }

    /** P6a: the seven-argument page adds Loot filters, Chat and About after Appearance (spec §6.7 order). */
    @Test public void sevenArgumentPageAddsLootFiltersChatAndAboutInSpecOrder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JLabel notifications = new JLabel("Synthetic notifications"), general = new JLabel("Synthetic general");
            JLabel appearance = new JLabel("Synthetic appearance"), loot = new JLabel("Synthetic loot filters");
            JLabel chat = new JLabel("Synthetic chat"), about = new JLabel("Synthetic about");
            SettingsPage page = new SettingsPage(notifications, () -> {}, general, appearance, loot, chat, about);
            assertEquals("loot-filters", SettingsPage.LOOT_FILTERS);
            assertEquals("chat", SettingsPage.CHAT);
            assertEquals("about", SettingsPage.ABOUT);
            assertEquals("Opens on Notifications, as before", SettingsPage.NOTIFICATIONS, page.currentSection());
            assertEquals("Spec §6.7 order: Notifications, General, Appearance, Loot filters, Chat, About", SIX, buttons(page));
            assertEquals("Loot filters", named(page, "settings-section-loot-filters", AbstractButton.class).getText());
            assertEquals("Chat", named(page, "settings-section-chat", AbstractButton.class).getText());
            assertEquals("About", named(page, "settings-section-about", AbstractButton.class).getText());
            JLabel[] contents = {loot, chat, about};
            String[] ids = {SettingsPage.LOOT_FILTERS, SettingsPage.CHAT, SettingsPage.ABOUT};
            for (int i = 0; i < ids.length; i++) {
                named(page, "settings-section-" + ids[i], AbstractButton.class).doClick();
                assertEquals(ids[i], page.currentSection());
                for (int j = 0; j < contents.length; j++) assertEquals(ids[i] + " shows only its content", i == j, contents[j].isVisible());
                assertFalse(notifications.isVisible());
            }
            page.showSection(SettingsPage.CHAT);
            assertTrue("showSection reaches the new sections", chat.isVisible());
            assertTrue(named(page, "settings-section-chat", AbstractButton.class).isSelected());
            assertThrows("Every new section is required", NullPointerException.class,
                () -> new SettingsPage(new JPanel(), () -> {}, new JPanel(), new JPanel(), null, new JPanel(), new JPanel()));
        });
    }

    @Test public void fourArgumentPageKeepsTodaysThreeSections() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SettingsPage page = new SettingsPage(new JPanel(), () -> {}, new JPanel(), new JPanel());
            assertEquals(SIX.subList(0, 3), buttons(page));
            page.showSection(SettingsPage.ABOUT);
            assertEquals("A section the page does not have is ignored", SettingsPage.NOTIFICATIONS, page.currentSection());
        });
    }

    /** Narrow pages list the sections above the content; the six buttons wrap instead of running off the row. */
    @Test public void narrowLayoutKeepsEverySectionReachable() throws Exception {
        SettingsPage[] page = new SettingsPage[1];
        JLabel[] contents = new JLabel[6];
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < contents.length; i++) contents[i] = new JLabel("Synthetic section " + i);
            page[0] = new SettingsPage(contents[0], () -> {}, contents[1], contents[2], contents[3], contents[4], contents[5]);
            evidence.show(page[0], "Settings narrow", 360, 520, 18);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            JComponent sections = named(page[0], "settings-sections", JComponent.class);
            assertEquals(BorderLayout.NORTH, ((BorderLayout) page[0].getLayout()).getConstraints(sections.getParent()));
            JTextField filter = named(page[0], "settings-search", JTextField.class);
            assertTrue("Filter stays above the wrapping row", SwingUtilities.convertPoint(filter, 0, filter.getHeight(), page[0]).y
                <= SwingUtilities.convertPoint(sections, 0, 0, page[0]).y);
            assertTrue("Filter fits the narrow page", filter.getWidth() > 0 && filter.getWidth() <= page[0].getWidth());
            for (int i = 0; i < SIX.size(); i++) {
                AbstractButton button = named(page[0], SIX.get(i), AbstractButton.class);
                Rectangle bounds = button.getBounds();
                assertTrue(SIX.get(i) + " lies inside the section list at 360 px, font 18: " + bounds + " in " + sections.getSize(),
                    bounds.x >= 0 && bounds.y >= 0 && bounds.x + bounds.width <= sections.getWidth() && bounds.y + bounds.height <= sections.getHeight());
                VisualEvidence.completeButton(button);
                button.doClick();
                assertTrue(SIX.get(i) + " opens its section", contents[i].isVisible());
            }
            evidence.capture("settings-sections-narrow-360-18");
        });
        SwingUtilities.invokeAndWait(() -> evidence.show(page[0], "Settings wide", 1240, 800, 13));
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            JComponent sections = named(page[0], "settings-sections", JComponent.class);
            assertEquals("Wide again: the list returns beside the content", BorderLayout.WEST,
                ((BorderLayout) page[0].getLayout()).getConstraints(sections.getParent()));
            for (String name : SIX) VisualEvidence.completeButton(named(page[0], name, AbstractButton.class));
        });
    }

    private static java.util.List<String> buttons(SettingsPage page) {
        java.util.List<String> order = new java.util.ArrayList<>();
        for (Component button : named(page, "settings-sections", JComponent.class).getComponents()) order.add(button.getName());
        return order;
    }

    @Test public void filterMatchesAllWordsAndClearsForEscapeAndExternalNavigation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SettingsPage page = new SettingsPage(new JPanel(), () -> {}, new JPanel(), new JPanel(),
                new JPanel(), new JPanel(), new JPanel());
            JTextField search = named(page, "settings-search", JTextField.class);
            assertTrue(search.getClientProperty("JTextField.leadingIcon") instanceof com.formdev.flatlaf.icons.FlatSearchIcon);
            assertEquals(true, search.getClientProperty("JTextField.showClearButton"));
            search.setText("  FONT size ");
            assertEquals(java.util.List.of("settings-section-appearance"), buttons(page));
            assertEquals(SettingsPage.APPEARANCE, page.currentSection());
            search.setText("sound version");
            assertTrue(buttons(page).isEmpty());
            assertTrue(named(page, "settings-no-matches", JLabel.class).isVisible());
            assertEquals(SettingsPage.APPEARANCE, page.currentSection());
            search.getActionMap().get("clear-filter").actionPerformed(null);
            assertEquals("", search.getText());
            assertEquals(SIX, buttons(page));
            assertFalse(named(page, "settings-no-matches", JLabel.class).isVisible());
            search.setText("combat history");
            assertEquals(SettingsPage.GENERAL, page.currentSection());
            page.showSection(SettingsPage.CHAT);
            assertEquals("", search.getText());
            assertEquals(SIX, buttons(page));
            assertEquals(SettingsPage.CHAT, page.currentSection());
            search.setText("filter");
            named(page, "settings-section-loot-filters", AbstractButton.class).doClick();
            assertEquals("filter", search.getText());
            assertEquals(SettingsPage.LOOT_FILTERS, page.currentSection());
        });
    }

    static <T> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = named((Container) child, name, type); if (found != null) return found; }
        }
        return null;
    }
}
