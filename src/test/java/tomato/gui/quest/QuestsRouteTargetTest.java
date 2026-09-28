package tomato.gui.quest;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import tomato.gui.kit.TileList;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;
import tomato.gui.route.ShellNavigator;
import tomato.history.link.VisitRef;
import util.PropertiesManager;
import static org.junit.Assert.*;

/**
 * Routes into the Quests page: a plain Quests route and {@link QuestsFocus#BOARD} bring the Board forward (even after the Planner),
 * {@link QuestsFocus#PLANNER} the Planner; Back returns to the tab in front when its state was captured. Routes and Back are explicit
 * navigation and may show a hidden tab; startup only selects from the saved order.
 */
public class QuestsRouteTargetTest {
    private static final String ORDER = "ui.tabs.quests", VIEW = "ui.quests.view";
    private String savedOrder, savedView;

    @Before public void isolate() {
        savedOrder = PropertiesManager.getProperty(ORDER); savedView = PropertiesManager.getProperty(VIEW);
        PropertiesManager.setProperties(ORDER, ""); PropertiesManager.setProperties(VIEW, "");
    }
    @After public void restore() {
        PropertiesManager.setProperties(ORDER, savedOrder == null ? "" : savedOrder);
        PropertiesManager.setProperties(VIEW, savedView == null ? "" : savedView);
    }

    @Test public void acceptsPlainAndFocusedQuestsRoutesOnly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestsRouteTarget target = new QuestsRouteTarget(panel());
            assertEquals(Destination.QUESTS, target.destination());
            assertTrue(target.accepts(Route.to(Destination.QUESTS)));
            assertTrue(target.accepts(Route.to(Destination.QUESTS).withPayload(QuestsFocus.BOARD)));
            assertTrue(target.accepts(Route.to(Destination.QUESTS).withPayload(QuestsFocus.PLANNER)));
            assertFalse("Another payload is rejected, not approximated", target.accepts(Route.to(Destination.QUESTS).withPayload("plans")));
            assertFalse(target.accepts(Route.to(Destination.QUESTS).withVisit(new VisitRef("session", "visit"))));
            assertFalse(target.accepts(Route.to(Destination.QUESTS).withBounds(0L, 1L)));
            assertFalse(target.accepts(Route.to(Destination.CHARACTERS)));
            try { target.open(Route.to(Destination.QUESTS).withPayload("plans")); fail("An unsupported route must not open"); }
            catch (IllegalArgumentException expected) { /* rejected */ }
        });
    }

    /** Home's Quests card opens a plain route: it lands on the Board even when the Planner was the tab last shown. */
    @Test public void aPlainRouteOrBoardFocusOpensTheBoardEvenAfterThePlanner() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = panel();
            JTabbedPane tabs = tabs(ui);
            QuestsRouteTarget target = new QuestsRouteTarget(ui);
            select(tabs, "Planner");
            target.open(Route.to(Destination.QUESTS));
            assertEquals("A plain Quests route shows the Board", "Board", front(tabs));
            select(tabs, "Planner");
            target.open(Route.to(Destination.QUESTS).withPayload(QuestsFocus.BOARD));
            assertEquals("Board", front(tabs));
        });
    }

    @Test public void plannerFocusOpensThePlanner() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = panel();
            JTabbedPane tabs = tabs(ui);
            assertEquals("Board", front(tabs));
            new QuestsRouteTarget(ui).open(Route.to(Destination.QUESTS).withPayload(QuestsFocus.PLANNER));
            assertEquals("Planner", front(tabs));
        });
    }

    /** Back state is the tab in front; restoring it brings that tab forward, and a state that is not a Quests tab is refused. */
    @Test public void captureRecordsTheTabInFrontAndRestoreBringsItBack() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = panel();
            JTabbedPane tabs = tabs(ui);
            QuestsRouteTarget target = new QuestsRouteTarget(ui);
            assertEquals(QuestsFocus.BOARD, target.captureState());
            select(tabs, "Planner");
            assertEquals(QuestsFocus.PLANNER, target.captureState());
            target.restoreState(QuestsFocus.BOARD);
            assertEquals("Board", front(tabs));
            target.restoreState(QuestsFocus.PLANNER);
            assertEquals("Planner", front(tabs));
            target.restoreState(null);
            assertEquals("An unknown state leaves the page as it is", "Planner", front(tabs));
            try { target.restoreState("captured"); fail("Only a Quests tab state restores"); }
            catch (IllegalArgumentException expected) { /* refused */ }
        });
    }

    /**
     * Through the shell's navigator: the search's Planner route from the Board is one Back entry that returns to the Board on the
     * same page; Home's plain route with the Planner last shown lands on the Board, and Back returns to Home.
     */
    @Test public void backThroughTheNavigatorReturnsToTheTabARouteLeft() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = panel();
            JTabbedPane tabs = tabs(ui);
            int[] page = {WorkspaceShell.pageOf(Destination.QUESTS)};
            ShellNavigator navigator = new ShellNavigator(() -> page[0], next -> page[0] = next, WorkspaceShell::pageOf, 20);
            navigator.register(new RetainedHome());
            navigator.register(new QuestsRouteTarget(ui));
            assertTrue(navigator.open(Route.to(Destination.QUESTS).withPayload(QuestsFocus.PLANNER)));
            assertEquals("Planner", front(tabs));
            assertTrue(navigator.back());
            assertEquals(5, page[0]); assertEquals("Back returns to the Board the Planner route left", "Board", front(tabs));
            select(tabs, "Planner");
            page[0] = WorkspaceShell.pageOf(Destination.HOME);
            assertTrue(navigator.open(Route.to(Destination.QUESTS)));
            assertEquals(5, page[0]); assertEquals("Board", front(tabs));
            assertTrue(navigator.back());
            assertEquals("Back returns Home", 14, page[0]);
        });
    }

    /** A saved order that hid the Board keeps it hidden at startup; the explicit route shows it by id and selects it. */
    @Test public void anExplicitRouteShowsAHiddenBoard() throws Exception {
        PropertiesManager.setProperties(ORDER, "plans,captured|captured");
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = panel();
            JTabbedPane tabs = tabs(ui);
            QuestsRouteTarget target = new QuestsRouteTarget(ui);
            assertEquals(List.of("Planner"), titles(tabs));
            target.open(Route.to(Destination.QUESTS));
            assertEquals(List.of("Planner", "Board"), titles(tabs));
            assertEquals("Board", front(tabs));
            assertEquals("Showing the Board is saved like the tab menu's Show", "plans,captured|", PropertiesManager.getProperty(ORDER));
        });
    }

    /**
     * Startup never goes through the target (the navigator has no startup restore): the saved order only selects, so building the
     * page and its target leaves a hidden tab hidden and the saved order untouched. Back is explicit navigation: restoring a tab that
     * was hidden after its state was captured shows it again.
     */
    @Test public void startupOnlySelectsAndBackShowsATabHiddenSince() throws Exception {
        PropertiesManager.setProperties(ORDER, "plans,captured|captured");
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = panel();
            JTabbedPane tabs = tabs(ui);
            QuestsRouteTarget target = new QuestsRouteTarget(ui);
            assertEquals("Startup keeps the hidden Board hidden", List.of("Planner"), titles(tabs));
            assertEquals("Planner", front(tabs));
            assertEquals(QuestsFocus.PLANNER, target.captureState());
            assertEquals("plans,captured|captured", PropertiesManager.getProperty(ORDER));
        });
        PropertiesManager.setProperties(ORDER, "captured,plans|plans");
        SwingUtilities.invokeAndWait(() -> {
            QuestGUI ui = panel();
            JTabbedPane tabs = tabs(ui);
            QuestsRouteTarget target = new QuestsRouteTarget(ui);
            assertEquals(List.of("Board"), titles(tabs));
            target.restoreState(QuestsFocus.PLANNER);
            assertEquals("Back shows the Planner hidden since", List.of("Board", "Planner"), titles(tabs));
            assertEquals("Planner", front(tabs));
        });
    }

    /**
     * What the Board's Cards view shows, for the S3 shell test in another package (the card models are package-private and the
     * painted text is not in the component tree): per card, in the order shown, its group's list name, the quest's name, its pin and
     * the reward ids its painted slots show ({@link QuestCardRenderer#lines}). Empty while the Table view shows. EDT only.
     */
    public record ShownCard(String list, String name, boolean pinned, List<Integer> rewards) {}

    public static List<ShownCard> shownCards(QuestGUI quests) {
        List<ShownCard> shown = new ArrayList<>();
        if (!quests.cardsShown()) return shown;
        for (QuestBoardModel.Group group : quests.board().groups()) {
            TileList<QuestCardModel> list = quests.board().list(group.key());
            for (QuestCardModel card : list.items()) {
                List<Integer> rewards = new ArrayList<>();
                for (QuestCardRenderer.Slot slot : QuestCardRenderer.lines(card).rewards()) rewards.add(slot.id());
                shown.add(new ShownCard(list.getName(), card.name(), card.pinned(), List.copyOf(rewards)));
            }
        }
        return shown;
    }

    private static QuestGUI panel() { return new QuestGUI(QuestFixtures.ITEM_NAMES, id -> null, new QuestGuiTest.MemoryPreferences()); }
    private static JTabbedPane tabs(QuestGUI ui) { return find(ui, JTabbedPane.class, "quests-tabs"); }
    private static String front(JTabbedPane tabs) { return tabs.getTitleAt(tabs.getSelectedIndex()); }
    /** A user's click on a visible tab. */
    private static void select(JTabbedPane tabs, String title) { tabs.setSelectedIndex(tabs.indexOfTab(title)); }
    private static List<String> titles(JTabbedPane tabs) {
        String[] titles = new String[tabs.getTabCount()];
        for (int i = 0; i < titles.length; i++) titles[i] = tabs.getTitleAt(i);
        return Arrays.asList(titles);
    }

    /** Home as the shell registers it: a retained page that keeps its own state. */
    private static final class RetainedHome implements RouteTarget {
        @Override public Destination destination() { return Destination.HOME; }
        @Override public Object captureState() { return null; }
        @Override public void open(Route route) { }
        @Override public void restoreState(Object state) { }
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, name); if (found != null) return found; }
        }
        return null;
    }
}
