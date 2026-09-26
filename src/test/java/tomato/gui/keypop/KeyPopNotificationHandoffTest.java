package tomato.gui.keypop;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.After;
import org.junit.Test;
import tomato.gui.notifications.AlertRouteTargets;
import tomato.gui.notifications.NotificationsGUI;
import tomato.gui.route.*;
import static org.junit.Assert.*;
import static tomato.gui.maingui.SocialRuleEditorTest.named;

/** KEY-3: the handoff focuses an exact known dungeon, changes no choice, and Back restores the contributor report. */
public class KeyPopNotificationHandoffTest {
    private static final Instant NOW = Instant.parse("2026-09-08T19:00:00Z");
    @After public void uninstall() { Navigator.install(Navigator.NONE); }

    /** Minimal stand-in for the shell navigator: one target, one origin state per open. */
    private static final class FakeNavigator implements Navigator {
        final RouteTarget target; final Deque<Object> origins = new ArrayDeque<>(); final AtomicInteger opened = new AtomicInteger();
        final Runnable showOrigin;
        FakeNavigator(RouteTarget target, Runnable showOrigin) { this.target = target; this.showOrigin = showOrigin; }
        public boolean open(Route route) { if (!canOpen(route)) return false; origins.push(target.captureState()); opened.incrementAndGet(); target.open(route); return true; }
        public boolean back() { if (origins.isEmpty()) return false; target.restoreState(origins.pop()); showOrigin.run(); return true; }
        public boolean canGoBack() { return !origins.isEmpty(); }
        public boolean canOpen(Route route) { return target.accepts(route); }
    }

    @Test public void exactDungeonIsFocusedWithoutChangingChoicesAndBackRestoresReport() throws Exception {
        Set<String> previous = KeypopGUI.getSelectedDungeons();
        try { SwingUtilities.invokeAndWait(() -> {
            KeypopGUI.setSelectedDungeons(new TreeSet<>(Arrays.asList("The Shatters", "missingDungeons")));
            Set<String> before = KeypopGUI.getSelectedDungeons();
            KeyPopHistory history = new KeyPopHistory();
            history.add(new KeyPopEvent(NOW, "Aster", "Lost Halls", KeyPopEvent.Kind.KEY));
            history.add(new KeyPopEvent(NOW.plusSeconds(5), "Wren", "Shield Rune", KeyPopEvent.Kind.RUNE));
            KeyPopDashboard report = new KeyPopDashboard(history); report.refresh();
            report.selectPlayer("Aster"); report.tabs.setSelectedIndex(2);
            report.items.setRowSelectionInterval(0, 0);
            assertEquals("Lost Halls", report.selectedDungeon());

            NotificationsGUI page = new NotificationsGUI();
            page.selectSection("Messages");
            JTextField search = named(page, "sound-dungeon-search", JTextField.class); search.setText("Shat");
            boolean[] originShown = {false};
            FakeNavigator navigator = new FakeNavigator(AlertRouteTargets.notifications(page, () -> {}), () -> originShown[0] = true);
            Navigator.install(navigator);

            String status = KeypopGUI.handoff(report.selectedDungeon(), report);
            assertTrue(status, status.startsWith("Showing Lost Halls"));
            assertEquals(1, navigator.opened.get());
            assertEquals("Key-pops", page.getComponentCount() > 0 ? selectedTab(page) : null);
            assertEquals("Lost Halls", search.getText());
            JTextArea focus = named(page, "sound-dungeon-focus", JTextArea.class);
            assertTrue(focus.getText().contains("Lost Halls is currently not selected"));
            assertEquals("no notification choice changes before an explicit action", before, KeypopGUI.getSelectedDungeons());

            named(page, "sound-dungeon-focus-back", JButton.class).doClick();
            assertTrue(originShown[0]);
            assertEquals("Shat", search.getText()); assertEquals("Messages", selectedTab(page));
            assertEquals(before, KeypopGUI.getSelectedDungeons());
            // The contributor report is untouched: same tab, exact-player filter and selected dungeon row.
            assertEquals(2, report.tabs.getSelectedIndex()); assertTrue(report.playerChip.isVisible());
            assertEquals("Lost Halls", report.selectedDungeon());

            assertNull("near-miss spellings are not guessed", tomato.gui.notifications.NotificationFocus.resolveDungeon("lost halls"));
            report.selectPlayer(null); report.tabs.setSelectedIndex(0);
            int rune = -1; for (int i = 0; i < report.events.getRowCount(); i++) if ("Shield Rune".equals(report.events.getValueAt(i, 3))) rune = i;
            report.events.setRowSelectionInterval(rune, rune);
            String unresolved = KeypopGUI.handoff(report.selectedDungeon(), report);
            assertTrue(unresolved, unresolved.contains("“Shield Rune” is not a known notification dungeon"));
            assertEquals("unresolved names never open Notifications", 1, navigator.opened.get());
            assertEquals("Select a key-pop or dungeon row first.", KeypopGUI.handoff(null, report));
            assertFalse(page.focusDungeon("Not A Dungeon", null));
            assertTrue(focus.getText().contains("not a known notification dungeon"));
            assertEquals(before, KeypopGUI.getSelectedDungeons());
        }); } finally { KeypopGUI.setSelectedDungeons(previous); }
    }
    private static String selectedTab(NotificationsGUI page) {
        JTabbedPane tabs = null;
        for (java.awt.Component c : allComponents(page)) if (c instanceof JTabbedPane) { tabs = (JTabbedPane)c; break; }
        return tabs.getTitleAt(tabs.getSelectedIndex());
    }
    private static List<java.awt.Component> allComponents(java.awt.Container root) {
        List<java.awt.Component> result = new ArrayList<>();
        for (java.awt.Component c : root.getComponents()) { result.add(c); if (c instanceof java.awt.Container) result.addAll(allComponents((java.awt.Container)c)); }
        return result;
    }
}
