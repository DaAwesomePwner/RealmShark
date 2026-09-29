package tomato.gui.kit;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.dnd.DragSource;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.*;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.modern.Themes;
import static org.junit.Assert.*;

public class CustomizableTabsTest {
    @Test public void analystModeCannotHideTheLastSimpleTabAndSavedHiddenStateRecovers() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            mode.set(DisplayModeModel.Mode.ANALYST);
            CustomizableTabs tabs = tabs();
            assertTrue(tabs.hide("overview")); assertTrue(tabs.hide("gear"));
            assertFalse(tabs.hide("exalts"));
            mode.set(DisplayModeModel.Mode.SIMPLE);
            assertEquals(Collections.singletonList("exalts"), tabs.visibleIds());
            store.put("ui.tabs.character", "overview,gear,exalts,evidence|overview,gear,exalts");
            CustomizableTabs recovered = tabs();
            assertFalse(recovered.visibleIds().isEmpty());
            assertTrue("Recovery does not rewrite saved preferences", store.get("ui.tabs.character").endsWith("|overview,gear,exalts"));
        });
    }

    @Test public void aConditionalTabIsSkippedWithoutRewritingTheSavedOrder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            boolean[] dead = {false};
            store.put("ui.tabs.character", "death,overview,gear,exalts,evidence|");
            CustomizableTabs tabs = tabs().addWhen("death", "Death annotation", new JPanel(), () -> dead[0]);
            assertEquals(Arrays.asList("overview", "gear", "exalts"), tabs.visibleIds());
            assertEquals("Every known tab keeps its saved place", Arrays.asList("death", "overview", "gear", "exalts", "evidence"), tabs.order());
            dead[0] = true;
            assertEquals("Nothing changes until the conditions are re-checked", 3, tabs.component().getTabCount());
            tabs.refreshConditions();
            assertEquals(Arrays.asList("death", "overview", "gear", "exalts"), tabs.visibleIds());
            assertEquals("Death annotation", tabs.component().getTitleAt(0));
            tabs.select("death"); assertEquals("death", tabs.selectedId());
            dead[0] = false; tabs.refreshConditions();
            assertEquals(Arrays.asList("overview", "gear", "exalts"), tabs.visibleIds());
            assertNotEquals("death", tabs.selectedId());
            assertEquals("A condition never rewrites the saved order", "death,overview,gear,exalts,evidence|", store.get("ui.tabs.character"));
            assertTrue(tabs.hide("overview")); assertTrue(tabs.hide("gear"));
            dead[0] = true; tabs.refreshConditions();
            assertFalse("A tab that may disappear never counts as the view's last tab", tabs.hide("exalts"));
            assertTrue(tabs.hide("death"));
        });
    }

    private final Map<String, String> store = new HashMap<>();
    private final DisplayModeModel mode = new DisplayModeModel(store::get, store::put);

    private CustomizableTabs tabs() {
        return new CustomizableTabs("character", mode, store::get, store::put)
            .add("overview", "Overview", new JPanel())
            .add("gear", "Gear", new JPanel())
            .add("exalts", "Exalts", new JPanel())
            .addAnalyst("evidence", "Snapshot evidence", new JPanel());
    }

    @Test public void analystOnlyTabsAppearOnlyInAnalystMode() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CustomizableTabs tabs = tabs();
            assertEquals(Arrays.asList("overview", "gear", "exalts"), tabs.visibleIds());
            assertEquals(3, tabs.component().getTabCount());
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertEquals(Arrays.asList("overview", "gear", "exalts", "evidence"), tabs.visibleIds());
            assertEquals("Snapshot evidence", tabs.component().getTitleAt(3));
        });
    }

    @Test public void orderAndHiddenTabsPersistAndSelectionFollowsTheTab() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CustomizableTabs tabs = tabs();
            tabs.select("exalts");
            tabs.move("exalts", -2);
            assertEquals(Arrays.asList("exalts", "overview", "gear"), tabs.visibleIds());
            assertEquals("exalts", tabs.selectedId());
            assertTrue(tabs.hide("gear"));
            assertEquals("exalts,overview,gear,evidence|gear", store.get("ui.tabs.character"));
            CustomizableTabs reopened = tabs();
            assertEquals(Arrays.asList("exalts", "overview"), reopened.visibleIds());
            assertEquals(Collections.singleton("gear"), reopened.hiddenIds());
            reopened.show("gear");
            assertEquals("gear", reopened.selectedId());
            reopened.reset();
            assertEquals(Arrays.asList("overview", "gear", "exalts"), reopened.visibleIds());
        });
    }

    @Test public void theLastVisibleTabCannotBeHiddenAndNewTabsAppend() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            store.put("ui.tabs.character", "gear,overview|");
            CustomizableTabs tabs = tabs();
            assertEquals(Arrays.asList("gear", "overview", "exalts"), tabs.visibleIds());
            assertTrue(tabs.hide("gear"));
            assertTrue(tabs.hide("overview"));
            assertFalse(tabs.hide("exalts"));
            assertEquals(Collections.singletonList("exalts"), tabs.visibleIds());
        });
    }

    @Test public void selectionIsReportedOnceAfterHidingTheSelectedTab() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CustomizableTabs tabs = tabs();
            List<String> seen = new ArrayList<>();
            tabs.onSelect(seen::add);
            tabs.select("gear");
            tabs.hide("gear");
            assertEquals(Arrays.asList("gear", "overview"), seen);
        });
    }

    /** Closing and other hierarchy walks reach every tab's content: hidden, Analyst-only in Simple mode and conditional ones too. */
    @Test public void contentsListEveryTabInSavedOrderAndThePaneLeadsBackToTheTabs() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            store.put("ui.tabs.character", "exalts,overview|");
            JPanel overview = new JPanel(), gear = new JPanel(), exalts = new JPanel(), evidence = new JPanel(), death = new JPanel();
            CustomizableTabs tabs = new CustomizableTabs("character", mode, store::get, store::put)
                .add("overview", "Overview", overview).add("gear", "Gear", gear).add("exalts", "Exalts", exalts)
                .addAnalyst("evidence", "Snapshot evidence", evidence).addWhen("death", "Death annotation", death, () -> false);
            assertTrue(tabs.hide("gear"));
            assertEquals(Arrays.asList("exalts", "overview"), tabs.visibleIds());
            assertEquals(2, tabs.component().getTabCount());
            assertEquals("Hidden, Analyst-only (Simple mode) and conditional tabs are listed, in the saved order",
                Arrays.asList(exalts, overview, gear, evidence, death), tabs.contents());
            tabs.move("overview", -1);
            assertEquals(Arrays.asList(overview, exalts, gear, evidence, death), tabs.contents());
            try { tabs.contents().clear(); fail("A read-only view"); } catch (UnsupportedOperationException expected) { }
            assertSame(tabs, tabs.component().getClientProperty(CustomizableTabs.class));
        });
    }

    @Test public void invalidIdsAreRejected() {
        try { new CustomizableTabs("Bad Group", mode, store::get, store::put); fail(); } catch (IllegalArgumentException expected) { }
        try { tabs().add("gear", "Duplicate", new JPanel()); fail(); } catch (IllegalArgumentException expected) { }
    }

    // ---- P6b Task 4: the mouse drag, the keyboard and the menu (windowless panes under the dark theme, synthetic events) ----

    /** Every preference value written, in order: a drag saves once, on release. */
    private final List<String> writes = new ArrayList<>();

    /** A laid-out, windowless "drag" group whose writes are counted; {@code idTitle} alternates IDs and titles. */
    private CustomizableTabs counted(String... idTitle) {
        Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
        CustomizableTabs tabs = new CustomizableTabs("drag", mode, store::get, (key, value) -> { writes.add(value); store.put(key, value); });
        for (int i = 0; i < idTitle.length; i += 2) tabs.add(idTitle[i], idTitle[i + 1], new JPanel());
        layOut(tabs.component());
        return tabs;
    }

    private static void layOut(JTabbedPane pane) { pane.setSize(600, 200); pane.doLayout(); }

    private static Point at(JTabbedPane pane, int index, double fraction) {
        Rectangle bounds = pane.getBoundsAt(index);
        return new Point(bounds.x + (int) Math.round(bounds.width * fraction), bounds.y + bounds.height / 2);
    }

    private static void mouse(JComponent target, int id, Point point, int button, boolean popupTrigger) {
        int modifiers = id == MouseEvent.MOUSE_RELEASED ? 0 : InputEvent.getMaskForButton(button);
        target.dispatchEvent(new MouseEvent(target, id, System.currentTimeMillis(), modifiers, point.x, point.y, 1, popupTrigger, button));
    }
    private static void press(JComponent target, Point point) { mouse(target, MouseEvent.MOUSE_PRESSED, point, MouseEvent.BUTTON1, false); }
    private static void drag(JComponent target, Point point) { mouse(target, MouseEvent.MOUSE_DRAGGED, point, MouseEvent.BUTTON1, false); }
    private static void release(JComponent target, Point point) { mouse(target, MouseEvent.MOUSE_RELEASED, point, MouseEvent.BUTTON1, false); }

    /** Sends Escape to {@code target} as a key press; true when something consumed it. */
    private static boolean escape(JComponent target) {
        KeyEvent key = new KeyEvent(target, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED);
        target.dispatchEvent(key);
        return key.isConsumed();
    }

    private static void invoke(JComponent component, KeyStroke key) {
        Object binding = component.getInputMap(JComponent.WHEN_FOCUSED).get(key);
        assertNotNull("Missing binding " + key, binding);
        component.getActionMap().get(binding).actionPerformed(new ActionEvent(component, ActionEvent.ACTION_PERFORMED, binding.toString()));
    }

    private static JMenuItem item(JPopupMenu menu, String name) {
        for (Component child : menu.getComponents()) {
            if (child instanceof JMenuItem && name.equals(child.getName())) return (JMenuItem) child;
            if (child instanceof JMenu) for (Component nested : ((JMenu) child).getMenuComponents())
                if (nested instanceof JMenuItem && name.equals(nested.getName())) return (JMenuItem) nested;
        }
        fail("Missing menu item " + name);
        return null;
    }

    /** R3 case 1: live swapping follows the pointer across each neighbour's midpoint, and one save happens, on release. */
    @Test public void aDragAcrossTheNeighboursMidpointMovesTheTabAndSavesOnceOnRelease() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CustomizableTabs tabs = counted("alpha", "Alpha", "bravo", "Bravo", "charlie", "Charlie");
            JTabbedPane pane = tabs.component();
            Point alpha = at(pane, 0, 0.5), beforeBravoMid = at(pane, 1, 0.4), pastBravoMid = at(pane, 1, 0.6), pastCharlieMid = at(pane, 2, 0.75);
            press(pane, alpha);
            drag(pane, beforeBravoMid);
            assertEquals("The near half of a neighbour moves nothing", Arrays.asList("alpha", "bravo", "charlie"), tabs.visibleIds());
            drag(pane, pastBravoMid);
            assertEquals(Arrays.asList("bravo", "alpha", "charlie"), tabs.visibleIds());
            drag(pane, pastCharlieMid);
            assertEquals(Arrays.asList("bravo", "charlie", "alpha"), tabs.visibleIds());
            assertEquals("The dragged tab stays selected", "alpha", tabs.selectedId());
            assertEquals("Nothing is saved while dragging", Collections.emptyList(), writes);
            release(pane, pastCharlieMid);
            assertEquals("One save, on release", Collections.singletonList("bravo,charlie,alpha|"), writes);
            assertEquals("bravo,charlie,alpha|", store.get("ui.tabs.drag"));
            // A drag that ends where it began saves nothing.
            press(pane, at(pane, 0, 0.5));
            drag(pane, at(pane, 1, 0.75));
            assertEquals(Arrays.asList("charlie", "bravo", "alpha"), tabs.visibleIds());
            drag(pane, at(pane, 0, 0.25));
            assertEquals(Arrays.asList("bravo", "charlie", "alpha"), tabs.visibleIds());
            release(pane, at(pane, 0, 0.25));
            assertEquals(Collections.singletonList("bravo,charlie,alpha|"), writes);
        });
    }

    /**
     * R3 case 2 (the probe's regression): a narrow tab dragged onto a much wider neighbour moved on every drag event while the
     * pointer stayed inside the wide tab, flipping the order back and forth and writing the preference each time.
     */
    @Test public void aNarrowTabDraggedOntoAWideNeighbourDoesNotOscillate() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CustomizableTabs tabs = counted("a", "A", "wide", "A much much longer tab title", "c", "C");
            JTabbedPane pane = tabs.component();
            Rectangle narrow = pane.getBoundsAt(0), wide = pane.getBoundsAt(1);
            assertTrue("The fixture's neighbour is several times wider", wide.width > narrow.width * 3);
            int y = narrow.y + narrow.height / 2;
            List<List<String>> orders = new ArrayList<>();
            orders.add(tabs.visibleIds());
            Runnable record = () -> { if (!tabs.visibleIds().equals(orders.get(orders.size() - 1))) orders.add(tabs.visibleIds()); };
            press(pane, new Point(narrow.x + narrow.width / 2, y));
            // Held still just inside the wide tab's near edge (the probe's six events), then swept through it and held again.
            for (int i = 0; i < 6; i++) { drag(pane, new Point(wide.x + 10, y)); record.run(); }
            assertEquals("The near half of the wide tab moves nothing", 1, orders.size());
            int end = wide.x + wide.width - 4;
            for (int x = wide.x + 10; x <= end; x += 3) { drag(pane, new Point(x, y)); record.run(); }
            for (int i = 0; i < 6; i++) { drag(pane, new Point(end, y)); record.run(); }
            assertEquals("One move, never back and forth",
                Arrays.asList(Arrays.asList("a", "wide", "c"), Arrays.asList("wide", "a", "c")), orders);
            assertEquals("Nothing is saved while dragging", Collections.emptyList(), writes);
            release(pane, new Point(end, y));
            assertEquals(Collections.singletonList("wide,a,c|"), writes);
        });
    }

    /** R3 case 3: only the left button drags; a popup-trigger press opens the tab menu and moves nothing. */
    @Test public void onlyTheLeftButtonDragsAndThePopupTriggerOpensTheMenu() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CustomizableTabs tabs = counted("alpha", "Alpha", "bravo", "Bravo", "charlie", "Charlie");
            JTabbedPane pane = tabs.component();
            Point alpha = at(pane, 0, 0.5), charlie = at(pane, 2, 0.9);
            for (int button : new int[] {MouseEvent.BUTTON2, MouseEvent.BUTTON3}) {
                // BUTTON3 without the popup trigger is Windows' right press (its trigger comes on release).
                mouse(pane, MouseEvent.MOUSE_PRESSED, alpha, button, false);
                mouse(pane, MouseEvent.MOUSE_DRAGGED, at(pane, 1, 0.9), button, false);
                mouse(pane, MouseEvent.MOUSE_DRAGGED, charlie, button, false);
                mouse(pane, MouseEvent.MOUSE_RELEASED, charlie, button, false);
                assertEquals("Button " + button + " never drags", Arrays.asList("alpha", "bravo", "charlie"), tabs.visibleIds());
            }
            assertEquals(Collections.emptyList(), writes);
            JFrame frame = new JFrame("tab menu");
            try {
                frame.setContentPane(pane); frame.setSize(600, 200); frame.setVisible(true); frame.validate();
                mouse(pane, MouseEvent.MOUSE_PRESSED, at(pane, 0, 0.5), MouseEvent.BUTTON3, true);
                mouse(pane, MouseEvent.MOUSE_DRAGGED, at(pane, 2, 0.9), MouseEvent.BUTTON3, false);
                MenuElement[] path = MenuSelectionManager.defaultManager().getSelectedPath();
                assertTrue("The popup trigger opens the tab menu", path.length > 0 && "drag-tab-menu".equals(path[0].getComponent().getName()));
                assertEquals(Arrays.asList("alpha", "bravo", "charlie"), tabs.visibleIds());
                assertEquals(Collections.emptyList(), writes);
            } finally {
                MenuSelectionManager.defaultManager().clearSelectedPath();
                frame.dispose();
            }
        });
    }

    /** R3 case 4: jitter within the system drag threshold is a click, not a drag, even across a tab boundary. */
    @Test public void aPressThatMovesNoFartherThanTheDragThresholdIsNotADrag() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CustomizableTabs tabs = counted("alpha", "Alpha", "bravo", "Bravo", "charlie", "Charlie");
            JTabbedPane pane = tabs.component();
            int threshold = DragSource.getDragThreshold();
            Rectangle bravo = pane.getBoundsAt(1);
            Point edge = new Point(bravo.x - 1, bravo.y + bravo.height / 2), across = new Point(edge.x + threshold, edge.y);
            assertEquals("The press lands on Alpha", 0, pane.indexAtLocation(edge.x, edge.y));
            assertEquals("The jitter ends on Bravo", 1, pane.indexAtLocation(across.x, across.y));
            press(pane, edge);
            drag(pane, across);
            assertEquals(Arrays.asList("alpha", "bravo", "charlie"), tabs.visibleIds());
            assertFalse("No drag is in progress, so Escape is left to others", escape(pane));
            Point pastBravoMid = at(pane, 1, 0.75);
            drag(pane, pastBravoMid);
            assertEquals("Past the threshold the same press drags", Arrays.asList("bravo", "alpha", "charlie"), tabs.visibleIds());
            release(pane, pastBravoMid);
            assertEquals(Collections.singletonList("bravo,alpha,charlie|"), writes);
        });
    }

    /** R3 case 5: Escape mid-drag puts every tab back where it was, writes nothing, and ends the drag. */
    @Test public void escapeDuringADragRestoresTheOrderAndWritesNothing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CustomizableTabs tabs = counted("alpha", "Alpha", "bravo", "Bravo", "charlie", "Charlie");
            JTabbedPane pane = tabs.component();
            Point alpha = at(pane, 0, 0.5), bravo = at(pane, 1, 0.75), charlie = at(pane, 2, 0.75);
            press(pane, alpha);
            drag(pane, bravo);
            drag(pane, charlie);
            assertEquals(Arrays.asList("bravo", "charlie", "alpha"), tabs.visibleIds());
            assertTrue("Escape is consumed by the drag", escape(pane));
            assertEquals(Arrays.asList("alpha", "bravo", "charlie"), tabs.visibleIds());
            assertEquals(Arrays.asList("alpha", "bravo", "charlie"), tabs.order());
            assertEquals("alpha", tabs.selectedId());
            drag(pane, charlie);
            assertEquals("The cancelled drag stays over until the next press", Arrays.asList("alpha", "bravo", "charlie"), tabs.visibleIds());
            release(pane, charlie);
            assertEquals("Nothing was written", Collections.emptyList(), writes);
            assertNull(store.get("ui.tabs.drag"));
            assertFalse("After the drag, Escape is left to others", escape(pane));
        });
    }

    /** R3 case 6: a tab dragged past hidden and Analyst-only tabs leaves them in their saved places. */
    @Test public void hiddenAndAnalystOnlyTabsKeepTheirSavedPlaceWhenATabIsDraggedPastThem() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            store.put("ui.tabs.drag", "overview,gear,exalts,evidence|gear");
            CustomizableTabs tabs = counted("overview", "Overview", "gear", "Gear", "exalts", "Exalts");
            tabs.addAnalyst("evidence", "Snapshot evidence", new JPanel());
            JTabbedPane pane = tabs.component();
            layOut(pane);
            assertEquals(Arrays.asList("overview", "exalts"), tabs.visibleIds());
            press(pane, at(pane, 0, 0.5));
            drag(pane, at(pane, 1, 0.8));
            release(pane, at(pane, 1, 0.8));
            assertEquals(Arrays.asList("exalts", "overview"), tabs.visibleIds());
            assertEquals("Hidden Gear stays ahead of Exalts; Analyst-only evidence stays last",
                Arrays.asList("gear", "exalts", "overview", "evidence"), tabs.order());
            assertEquals(Collections.singletonList("gear,exalts,overview,evidence|gear"), writes);
            mode.set(DisplayModeModel.Mode.ANALYST);
            tabs.show("gear");
            assertEquals(Arrays.asList("gear", "exalts", "overview", "evidence"), tabs.visibleIds());
        });
    }

    /** R3 case 7: Ctrl+Shift+Left/Right move the selected tab one place and save, as the menu's Move items do. */
    @Test public void ctrlShiftLeftAndRightMoveTheSelectedTab() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CustomizableTabs tabs = counted("alpha", "Alpha", "bravo", "Bravo", "charlie", "Charlie");
            JTabbedPane pane = tabs.component();
            tabs.select("bravo");
            invoke(pane, KeyStroke.getKeyStroke("ctrl shift RIGHT"));
            assertEquals(Arrays.asList("alpha", "charlie", "bravo"), tabs.visibleIds());
            assertEquals("bravo", tabs.selectedId());
            invoke(pane, KeyStroke.getKeyStroke("ctrl shift RIGHT"));
            assertEquals("The last tab goes no farther", Arrays.asList("alpha", "charlie", "bravo"), tabs.visibleIds());
            invoke(pane, KeyStroke.getKeyStroke("ctrl shift LEFT"));
            invoke(pane, KeyStroke.getKeyStroke("ctrl shift LEFT"));
            assertEquals(Arrays.asList("bravo", "alpha", "charlie"), tabs.visibleIds());
            assertEquals(Arrays.asList("alpha,charlie,bravo|", "alpha,bravo,charlie|", "bravo,alpha,charlie|"), writes);
            assertNotNull("Shift+F10 opens the menu", pane.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("shift F10")));
        });
    }

    /** R3 case 8: the menu enables exactly what applies; Hide tab is off for the view's last steady tab, as hide() refuses it. */
    @Test public void theMenuEnablesExactlyWhatApplies() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
            CustomizableTabs tabs = tabs();
            JTabbedPane pane = tabs.component();
            layOut(pane);
            JPopupMenu first = tabs.menu(at(pane, 0, 0.5));
            assertEquals("character-tab-menu", first.getName());
            assertFalse(item(first, "character-tab-move-left").isEnabled());
            assertTrue(item(first, "character-tab-move-right").isEnabled());
            assertTrue(item(first, "character-tab-hide").isEnabled());
            JMenuItem hiddenMenu = item(first, "character-tab-show-hidden");
            assertEquals("Spec §4.4 wording; the submenu paints its own ▸", "Show hidden", hiddenMenu.getText());
            assertFalse("Nothing is hidden", hiddenMenu.isEnabled());
            assertEquals("Reset order", item(first, "character-tab-reset").getText());
            assertFalse("The order is the default and nothing is hidden", item(first, "character-tab-reset").isEnabled());
            assertEquals("Move left", item(first, "character-tab-move-left").getText());
            assertEquals("Hide tab", item(first, "character-tab-hide").getText());

            assertTrue(tabs.hide("gear"));
            layOut(pane);
            JPopupMenu last = tabs.menu(at(pane, 1, 0.5));
            assertTrue(item(last, "character-tab-move-left").isEnabled());
            assertFalse(item(last, "character-tab-move-right").isEnabled());
            assertTrue(item(last, "character-tab-show-hidden").isEnabled());
            assertTrue("Something is hidden", item(last, "character-tab-reset").isEnabled());
            JMenuItem gear = item(last, "character-tab-show-gear");
            assertEquals("Gear", gear.getText());
            gear.doClick();
            assertEquals(Arrays.asList("overview", "gear", "exalts"), tabs.visibleIds());
            assertEquals("gear", tabs.selectedId());

            mode.set(DisplayModeModel.Mode.ANALYST);
            assertTrue(tabs.hide("overview")); assertTrue(tabs.hide("gear"));
            layOut(pane);
            assertEquals(Arrays.asList("exalts", "evidence"), tabs.visibleIds());
            assertFalse(tabs.canHide("exalts"));
            JMenuItem hideExalts = item(tabs.menu(at(pane, 0, 0.5)), "character-tab-hide");
            assertFalse("Exalts is the last steady tab, so Hide tab is off", hideExalts.isEnabled());
            assertTrue(tabs.canHide("evidence"));
            JMenuItem hideEvidence = item(tabs.menu(at(pane, 1, 0.5)), "character-tab-hide");
            assertTrue("An Analyst-only tab may still be hidden", hideEvidence.isEnabled());
            item(tabs.menu(at(pane, 0, 0.5)), "character-tab-reset").doClick();
            assertEquals(Arrays.asList("overview", "gear", "exalts", "evidence"), tabs.visibleIds());
        });
    }

    /**
     * A WRAP strip with several runs: the midpoint rule is skipped for a tab on another run, and one cross-run move waits for the
     * pointer to come back to the dragged tab's run before another, so rotating runs cannot swap the tab back and forth.
     */
    @Test public void aDragOntoAnotherTabRunMovesOnceUntilThePointerReturnsToTheDraggedTabsRun() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CustomizableTabs tabs = counted("one", "First tab", "two", "Second tab", "three", "Third tab", "four", "Fourth tab",
                "five", "Fifth tab", "six", "Sixth tab");
            JTabbedPane pane = tabs.component();
            pane.setTabLayoutPolicy(JTabbedPane.WRAP_TAB_LAYOUT);
            pane.setSize(260, 300); pane.doLayout();
            tabs.select("six");
            pane.doLayout();
            Rectangle six = pane.getBoundsAt(5);
            int other = -1;
            for (int i = 0; i < 5 && other < 0; i++) if (pane.getBoundsAt(i).y != six.y) other = i;
            assertTrue("The fixture wraps into several runs", other >= 0);
            Point otherRun = at(pane, other, 0.5);
            String target = tabs.visibleIds().get(other);
            press(pane, at(pane, 5, 0.5));
            List<List<String>> orders = new ArrayList<>();
            orders.add(tabs.visibleIds());
            for (int i = 0; i < 6; i++) {
                drag(pane, new Point(otherRun.x + (i % 2), otherRun.y));
                if (!tabs.visibleIds().equals(orders.get(orders.size() - 1))) orders.add(tabs.visibleIds());
            }
            assertEquals("One move onto the other run, however its runs rotate: " + orders, 2, orders.size());
            List<String> moved = orders.get(1);
            assertEquals("Six takes the place of the tab under the pointer", moved.indexOf("six"), orders.get(0).indexOf(target));
            assertEquals(Collections.emptyList(), writes);
            release(pane, otherRun);
            assertEquals(Collections.singletonList(String.join(",", moved) + "|"), writes);
        });
    }
}
