package tomato.gui.quest;

import java.awt.Component;
import java.awt.Container;
import java.util.Objects;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;

/**
 * Routes into the Quests page (shell page 5). The page stays mounted: its filters, selection, detail and plans are never touched here.
 * - {@link Destination#QUESTS} without a payload, or with {@link QuestsFocus#BOARD}, brings the Board forward, so Home's Quests card
 *   lands on the Board even when the Planner was the tab last shown (spec S3).
 * - {@link QuestsFocus#PLANNER} brings the Planner forward and moves keyboard focus to the tabs once the page shows (search's "Quest
 *   requirements and manual stock").
 * A route is explicit navigation, so it shows its tab even when a saved order hid it. The Back state is the Quests tab in front when
 * it was captured ({@link QuestsFocus}, null when neither is); Back brings that tab forward again in place, without moving keyboard
 * focus, or shows it first when it was hidden since (Back is explicit navigation too). Startup never comes here: the navigator has no
 * startup restore, and the saved tab order only selects. EDT only.
 */
public final class QuestsRouteTarget implements RouteTarget {
    /** The Board's tab page; the Planner's is the page's QuestPlanPanel. */
    private static final String BOARD_PAGE = "quest-page-scroll", TABS = "quests-tabs";
    private final QuestGUI quests;
    private final JTabbedPane tabs;

    public QuestsRouteTarget(QuestGUI quests) {
        this.quests = Objects.requireNonNull(quests, "quests");
        tabs = Objects.requireNonNull(find(quests), "The Quests page has no " + TABS);
    }

    @Override public Destination destination() { return Destination.QUESTS; }
    @Override public boolean accepts(Route route) {
        return route.destination == Destination.QUESTS && route.query == null && route.visit == null && route.record == null
            && route.recordingId == null && route.localObjectId == null && route.from == null && route.until == null
            && (route.payload == null || route.payload instanceof QuestsFocus);
    }
    @Override public Object captureState() { return front(); }
    @Override public void open(Route route) {
        if (!accepts(route)) throw new IllegalArgumentException("Unsupported Quests route: " + route);
        if (route.payload != QuestsFocus.PLANNER) { quests.openBoard(); return; }
        quests.openPlans();
        // ShellNavigator.open calls this before it selects page 5, so from another page openPlans' focus request finds the tabs
        // hidden. Ask again once the page shows (as CharactersRouteTarget does for the sheet), while the Planner is still in front.
        SwingUtilities.invokeLater(() -> { if (front() == QuestsFocus.PLANNER) tabs.requestFocusInWindow(); });
    }
    @Override public void restoreState(Object state) {
        if (state == null) return; // captured with no Quests tab in front: the page stays as it is
        if (!(state instanceof QuestsFocus)) throw new IllegalArgumentException("Not a Quests tab state");
        QuestsFocus tab = (QuestsFocus) state;
        int index = indexOf(tab);
        if (index >= 0) { tabs.setSelectedIndex(index); return; }
        // Hidden since the state was captured: only the page's own open methods show a tab. openPlans also asks for focus, which
        // fails harmlessly while Back from another page has not yet selected page 5.
        if (tab == QuestsFocus.BOARD) quests.openBoard(); else quests.openPlans();
    }

    /** The Quests tab in front, or null when neither is. */
    private QuestsFocus front() { return tabOf(tabs.getSelectedComponent()); }
    /** The tab's index in the strip, or -1 while a saved order hides it. */
    private int indexOf(QuestsFocus tab) {
        for (int i = 0; i < tabs.getTabCount(); i++) if (tabOf(tabs.getComponentAt(i)) == tab) return i;
        return -1;
    }
    private static QuestsFocus tabOf(Component page) {
        if (page instanceof QuestPlanPanel) return QuestsFocus.PLANNER;
        return page != null && BOARD_PAGE.equals(page.getName()) ? QuestsFocus.BOARD : null;
    }
    private static JTabbedPane find(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTabbedPane && TABS.equals(child.getName())) return (JTabbedPane) child;
            if (child instanceof Container) { JTabbedPane found = find((Container) child); if (found != null) return found; }
        }
        return null;
    }
}
