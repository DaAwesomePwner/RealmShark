package tomato.gui.settings;

import java.awt.*;
import java.awt.event.ComponentEvent;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsPageTest {
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

    static <T> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = named((Container) child, name, type); if (found != null) return found; }
        }
        return null;
    }
}
