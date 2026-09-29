package tomato.gui.notifications;

import java.util.*;
import javax.swing.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;
import util.PropertiesManager;
import static org.junit.Assert.*;

/**
 * P6b Task 4: Settings › Notifications' sections are the customizable tab group {@code notifications} (spec §4.4). Routes and Back
 * are explicit navigation, so they bring a hidden section forward; the default order is the historical one.
 */
public class NotificationsTabsTest {
    private static final String KEY = CustomizableTabs.PREFIX + "notifications";
    private static final List<String> IDS = Arrays.asList("messages", "bags", "key-pops", "realm-events", "other-alerts", "decisions");
    private String saved;

    @Before public void isolate() { saved = PropertiesManager.getProperty(KEY); PropertiesManager.setProperties(KEY, ""); }
    @After public void restore() { PropertiesManager.setProperties(KEY, saved == null ? "" : saved); }

    private static CustomizableTabs group(NotificationsGUI page) {
        Object group = page.tabs.getClientProperty(CustomizableTabs.class);
        assertTrue("The sections are a CustomizableTabs group", group instanceof CustomizableTabs);
        return (CustomizableTabs) group;
    }

    private static String selectedTitle(NotificationsGUI page) { return page.tabs.getTitleAt(page.tabs.getSelectedIndex()); }

    /** The index and title loops of the older Notifications tests stay valid: same six titles, same order, same pane. */
    @Test public void theDefaultOrderMatchesTheOldTitles() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NotificationsGUI page = new NotificationsGUI();
            CustomizableTabs group = group(page);
            assertSame("The page keeps its tabs field as the group's pane", page.tabs, group.component());
            assertEquals(IDS, group.visibleIds());
            List<String> titles = new ArrayList<>();
            for (int i = 0; i < page.tabs.getTabCount(); i++) titles.add(page.tabs.getTitleAt(i));
            assertEquals(Arrays.asList("Messages", "Bags", NotificationsGUI.KEY_POPS, "Realm events", "Other alerts", NotificationsGUI.DECISIONS), titles);
            assertSame(page.decisions, page.tabs.getComponentAt(5));
            assertEquals(JTabbedPane.SCROLL_TAB_LAYOUT, page.tabs.getTabLayoutPolicy());
            assertEquals("notifications-tabs", page.tabs.getName());
            assertEquals("messages", group.selectedId());
            assertTrue("Building the page writes no tab preference", PropertiesManager.getProperty(KEY).isEmpty());
        });
    }

    @Test public void theOrderAndHiddenSectionsPersistUnderUiTabsNotifications() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NotificationsGUI page = new NotificationsGUI();
            CustomizableTabs group = group(page);
            group.move("decisions", -5);
            assertTrue(group.hide("bags"));
            assertEquals("decisions,messages,bags,key-pops,realm-events,other-alerts|bags", PropertiesManager.getProperty(KEY));
            NotificationsGUI reopened = new NotificationsGUI();
            assertEquals(Arrays.asList("decisions", "messages", "key-pops", "realm-events", "other-alerts"), group(reopened).visibleIds());
            assertEquals(NotificationsGUI.DECISIONS, reopened.tabs.getTitleAt(0));
            assertEquals(Collections.singleton("bags"), group(reopened).hiddenIds());
        });
    }

    /** A route is explicit navigation: it shows the section the user hid, then selects it, by ID or by the older titles. */
    @Test public void aRouteToAHiddenSectionRevealsAndSelectsIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NotificationsGUI page = new NotificationsGUI();
            CustomizableTabs group = group(page);
            RouteTarget target = AlertRouteTargets.notifications(page, () -> { });
            assertTrue(group.hide("realm-events"));
            target.open(Route.to(Destination.NOTIFICATIONS).withPayload(NotificationFocus.section("Realm events")));
            assertTrue(group.visibleIds().contains("realm-events"));
            assertEquals("realm-events", group.selectedId());
            assertEquals("Realm events", selectedTitle(page));

            assertTrue(group.hide("key-pops"));
            page.selectSection("Key pops"); // the sound group's own name
            assertEquals("key-pops", group.selectedId());
            assertTrue(group.hide("bags"));
            page.selectSection("bags"); // an ID
            assertEquals("bags", group.selectedId());
            assertTrue(group.hide("decisions"));
            page.focusDecision(42L);
            assertEquals("decisions", group.selectedId());

            assertTrue(group.hide("other-alerts"));
            page.selectSection(null);
            assertEquals("No section keeps the current one", "decisions", group.selectedId());
            page.selectSection("Not a section");
            assertEquals("decisions", group.selectedId());
            assertEquals("Only a route to it reveals a hidden section", Collections.singleton("other-alerts"), group.hiddenIds());
        });
    }

    /** Back stores the section's ID, so a reorder between the capture and Back cannot land on another section. */
    @Test public void backRestoresTheSectionByIdAfterAReorder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NotificationsGUI page = new NotificationsGUI();
            CustomizableTabs group = group(page);
            RouteTarget target = AlertRouteTargets.notifications(page, () -> { });
            group.select("key-pops");
            Object state = target.captureState();
            group.move("key-pops", 3);
            group.move("decisions", -4);
            group.select("messages");
            assertNotEquals("The reorder moved Key-pops' index", 2, group.visibleIds().indexOf("key-pops"));
            target.restoreState(state);
            assertEquals("key-pops", group.selectedId());
            assertEquals(NotificationsGUI.KEY_POPS, selectedTitle(page));

            group.select("messages");
            assertTrue(group.hide("key-pops"));
            target.restoreState(state);
            assertEquals("Back is explicit navigation: the hidden section comes forward", "key-pops", group.selectedId());
            assertTrue(group.hiddenIds().isEmpty());
        });
    }
}
