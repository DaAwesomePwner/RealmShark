package tomato.gui.settings;

import javax.swing.*;
import org.junit.Test;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.route.*;
import static org.junit.Assert.*;

public class SettingsRouteTargetsTest {
    @Test public void settingsAndNotificationsRestoreTheSectionAndNestedStateAcrossBack() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SettingsPage settings = new SettingsPage(new JPanel(), () -> {}, new JPanel(), new JPanel());
            String[] selected = {"runs"};
            Object[] originState = {"feed-selection"}, notificationState = {"rules"};
            ShellNavigator navigator = new ShellNavigator(() -> selected[0], page -> selected[0] = page, WorkspaceShell::pageOf, 20);
            navigator.register(new RouteTarget() {
                public Destination destination() { return Destination.RUNS; }
                public Object captureState() { return originState[0]; }
                public void open(Route route) { originState[0] = "changed"; }
                public void restoreState(Object state) { originState[0] = state; }
            });
            RouteTarget notifications = new RouteTarget() {
                public Destination destination() { return Destination.NOTIFICATIONS; }
                public Object captureState() { return notificationState[0]; }
                public void open(Route route) { settings.showSection(SettingsPage.NOTIFICATIONS); notificationState[0] = "history"; }
                public void restoreState(Object state) { settings.showSection(SettingsPage.NOTIFICATIONS); notificationState[0] = state; }
            };
            for (RouteTarget target : SettingsRouteTargets.of(settings, notifications)) navigator.register(target);

            assertTrue(navigator.open(Route.to(Destination.SETTINGS).withPayload(new SettingsFocus(SettingsPage.APPEARANCE))));
            assertEquals("settings", selected[0]);
            assertEquals(SettingsPage.APPEARANCE, settings.currentSection());
            originState[0] = "background change";
            assertTrue(navigator.back());
            assertEquals("runs", selected[0]);
            assertEquals("feed-selection", originState[0]);

            assertTrue(navigator.open(Route.to(Destination.SETTINGS).withPayload(new SettingsFocus(SettingsPage.GENERAL))));
            assertTrue(navigator.open(Route.to(Destination.NOTIFICATIONS)));
            assertEquals(SettingsPage.NOTIFICATIONS, settings.currentSection());
            assertEquals("history", notificationState[0]);
            assertTrue(navigator.back());
            assertEquals(SettingsPage.GENERAL, settings.currentSection());
            assertEquals("rules", notificationState[0]);
            assertTrue(navigator.open(Route.to(Destination.SETTINGS)));
            assertEquals("A plain route retains the section", SettingsPage.GENERAL, settings.currentSection());
            assertTrue(navigator.open(Route.to(Destination.SETTINGS).withPayload(new SettingsFocus("unknown"))));
            assertEquals("An unknown section retains the section", SettingsPage.GENERAL, settings.currentSection());
            int depth = navigator.depth();
            assertFalse(navigator.open(Route.to(Destination.SETTINGS).withPayload("appearance")));
            assertFalse(navigator.open(Route.to(Destination.SETTINGS).withRecording("recording", null)));
            assertEquals(depth, navigator.depth());

            assertTrue(navigator.open(Route.to(Destination.NOTIFICATIONS)));
            assertTrue(navigator.open(Route.to(Destination.SETTINGS).withPayload(new SettingsFocus(SettingsPage.APPEARANCE))));
            notificationState[0] = "changed";
            assertTrue(navigator.back());
            assertEquals(SettingsPage.NOTIFICATIONS, settings.currentSection());
            assertEquals("history", notificationState[0]);
        });
    }
}
