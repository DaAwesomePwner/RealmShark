package tomato.gui.runs;

import java.awt.BorderLayout;
import java.awt.Component;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import tomato.gui.modern.TabbedRoutePage;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;

/**
 * Runs & DPS: Feed, Dungeons, Live meter, Resources & buffs and Recordings ({@link RunsTab}), with strip {@code runs-tabs}
 * and preferences {@code ui.tabs.runs}. Feed's Back state belongs to its RUNS target, Live meter's to the encounter target,
 * and Resources' to the resources target; Dungeons and Recordings restore their tab only. See {@link TabbedRoutePage} for
 * shared Back, rejected-open rollback and close semantics.
 */
public final class RunsDpsPage extends TabbedRoutePage<RunsTab, RunsDpsPage.PageState> {
    /** Detached Back state: the selected Runs tab and its owner's state, or null when it has no owner. */
    public record PageState(RunsTab tab, Object inner) implements TabState<RunsTab> {}

    private final RunsPage feed;
    /** The Dungeons, Resources and Recordings tabs: fixed holders whose content {@link #setContent} replaces, and that content. */
    private final Map<RunsTab, JPanel> holders = new EnumMap<>(RunsTab.class);
    private final Map<RunsTab, JComponent> held = new EnumMap<>(RunsTab.class);
    private Consumer<String> feedDungeon;

    /** The page around {@code feed} (the Feed tab, unchanged) and {@code liveMeter} (the Live meter tab). */
    public RunsDpsPage(RunsPage feed, JComponent liveMeter) {
        super("runs", "runs-dps-page", "Runs & DPS", RunsTab.class, RunsTab::id, RunsTab::of, PageState.class, PageState::new);
        this.feed = Objects.requireNonNull(feed, "feed");
        Objects.requireNonNull(liveMeter, "liveMeter");
        tabs().initializeOrder(RunsDpsPage::withResources);
        populate(RunsTab.values(), RunsTab::title, tab -> tab == RunsTab.FEED ? feed : tab == RunsTab.LIVE_METER ? liveMeter : holder(tab));
    }

    /** Older layouts gain Resources beside Live meter, or last when they never named Live meter; reads never save. */
    private static List<String> withResources(List<String> saved) {
        String resources = RunsTab.RESOURCES.id();
        if (saved.isEmpty() || saved.contains(resources)) return saved;
        List<String> order = new ArrayList<>(saved);
        int meter = order.indexOf(RunsTab.LIVE_METER.id());
        if (meter >= 0) order.add(meter + 1, resources);
        else {
            for (RunsTab tab : RunsTab.values()) if (tab != RunsTab.RESOURCES && !order.contains(tab.id())) order.add(tab.id());
            order.add(resources);
        }
        return order;
    }

    private JPanel holder(RunsTab tab) {
        JPanel holder = new JPanel(new BorderLayout());
        holder.setName("runs-" + tab.id() + "-slot");
        holder.setOpaque(false);
        holders.put(tab, holder);
        return holder;
    }

    public RunsPage feed() { return feed; }
    /**
     * Puts {@code content} in the Dungeons, Resources or Recordings tab's holder in place of what it held (null empties it); the tab itself
     * is never re-added and does not come forward. The replaced content is the caller's to close. The Feed and the Live meter
     * are fixed when the page is built.
     */
    public void setContent(RunsTab tab, JComponent content) {
        JPanel holder = holders.get(Objects.requireNonNull(tab, "tab"));
        if (holder == null) throw new IllegalArgumentException("The " + tab.title() + " tab's content is fixed when the page is built");
        if (held.get(tab) == content) return;
        holder.removeAll();
        if (content == null) held.remove(tab);
        else { held.put(tab, content); holder.add(content, BorderLayout.CENTER); }
        holder.revalidate();
        holder.repaint();
    }

    /**
     * {@code RUNS} routes with a {@link RunsFocus} payload and no other reference (search entries, the meter's library button, a
     * card action): opening brings that tab forward. A Feed focus with a dungeon also shows the feed (not a recap left open)
     * and hands the dungeon to the {@link #onFeedDungeon} hook; while no hook is set such a route is rejected, not approximated.
     */
    public RouteTarget tabTarget() {
        return new PageTarget() {
            @Override public Destination destination() { return Destination.RUNS; }
            @Override public boolean accepts(Route route) {
                if (route.destination != Destination.RUNS || referenced(route) || !(route.payload instanceof RunsFocus)) return false;
                return ((RunsFocus) route.payload).dungeon() == null || feedDungeon != null;
            }
            @Override public void open(Route route) {
                if (!accepts(route)) throw new IllegalArgumentException("Unsupported Runs & DPS tab route: " + route);
                RunsFocus focus = (RunsFocus) route.payload;
                Consumer<String> filter = feedDungeon;
                boolean recap = feed.recapShown();
                openOn(focus.tab(), () -> {
                    if (focus.dungeon() == null) return;
                    feed.showFeed();
                    try { filter.accept(focus.dungeon()); }
                    catch (RuntimeException failed) { if (recap) feed.showRecap(); throw failed; }
                });
            }
        };
    }

    /**
     * Plain {@code ENCOUNTER} routes (no recording, object, visit, query, record, bounds or payload: Home's Now card, Alt+8):
     * opening brings the Live meter forward without changing the meter's selection, then runs {@code focus} once the navigator
     * has shown the page (it selects the page after {@code open}), while the Live meter is still in front. Exact recordings
     * stay the meter's own target.
     */
    public RouteTarget liveMeterTarget(Runnable focus) {
        Objects.requireNonNull(focus, "focus");
        return new PageTarget() {
            @Override public Destination destination() { return Destination.ENCOUNTER; }
            @Override public boolean accepts(Route route) {
                return route.destination == Destination.ENCOUNTER && !referenced(route) && route.payload == null;
            }
            @Override public void open(Route route) {
                if (!accepts(route)) throw new IllegalArgumentException("Unsupported Live meter route: " + route);
                openOn(RunsTab.LIVE_METER, () -> { });
                SwingUtilities.invokeLater(() -> { if (!closed() && selectedTab() == RunsTab.LIVE_METER) focus.run(); });
            }
        };
    }

    /** Receives the canonical dungeon of a Feed {@link RunsFocus} (the feed's dungeon filter); null removes it. */
    public void onFeedDungeon(Consumer<String> hook) { feedDungeon = hook; }

    /** A holder's content, else the tab's component itself. */
    @Override protected Component contentOf(Component tab) {
        for (Map.Entry<RunsTab, JPanel> holder : holders.entrySet()) if (holder.getValue() == tab) return held.get(holder.getKey());
        return tab;
    }
}
