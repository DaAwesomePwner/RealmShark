package tomato.gui.runs;

import java.awt.BorderLayout;
import java.awt.Component;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;

/**
 * Shell page 10, "Runs & DPS" (spec §6.3): customizable tabs Feed · Dungeons · Live meter · Recordings ({@link RunsTab}, strip
 * {@code runs-tabs}, order and hidden set in {@code ui.tabs.runs}; none is Analyst-only). The Feed is the {@link RunsPage}
 * unchanged, the Live meter is the app's single DPS meter, and Dungeons and Recordings are holders filled later
 * ({@link #setContent}).
 * - The page opens on its first visible tab: the selected tab is not persisted, and startup only selects, never showing a hidden
 *   tab. Only explicit navigation ({@link #bring}: a route, Back, a search entry, a card action) brings a hidden tab forward.
 * - Back: {@code ShellNavigator} captures one target per page (whichever last opened it, else the newest registered), so every
 *   page-10 target is this page's: the wrappers ({@link #routes}), {@link #tabTarget()} and {@link #liveMeterTarget}. Each
 *   captures the same {@link PageState}, the tab in front and the state of that tab's {@link #owner} (Feed: the {@code RUNS}
 *   {@link RunsRouteTarget}; Live meter: the meter's encounter target), and each restores it: the tab comes forward first, then
 *   its owner restores. This is {@link RunsPage#tableRoutes} one level up.
 * - Closing reaches every tab's content, hidden tabs included ({@link CustomizableTabs#contents()}).
 * EDT only.
 */
public final class RunsDpsPage extends JPanel implements AutoCloseable {
    /**
     * The detached Back state every page-10 target captures: the tab in front, and its owner's own state (null when that tab has
     * no owner). Restoring brings {@code tab} forward (showing it if it was hidden since), then restores the owner.
     */
    public record PageState(RunsTab tab, Object inner) {}

    private final CustomizableTabs tabs;
    private final RunsPage feed;
    /** The Dungeons and Recordings tabs: fixed holders whose content {@link #setContent} replaces, and that content. */
    private final Map<RunsTab, JPanel> holders = new EnumMap<>(RunsTab.class);
    private final Map<RunsTab, JComponent> held = new EnumMap<>(RunsTab.class);
    private final Map<RunsTab, RouteTarget> owners = new EnumMap<>(RunsTab.class);
    private Consumer<String> feedDungeon;
    private boolean closed;

    /** The page around {@code feed} (the Feed tab, unchanged) and {@code liveMeter} (the Live meter tab). */
    public RunsDpsPage(RunsPage feed, JComponent liveMeter) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build the Runs & DPS page on the EDT");
        this.feed = Objects.requireNonNull(feed, "feed");
        Objects.requireNonNull(liveMeter, "liveMeter");
        tabs = new CustomizableTabs("runs");
        setName("runs-dps-page");
        setLayout(new BorderLayout());
        setOpaque(false);
        JTabbedPane pane = tabs.component();
        // One row of tabs at any width: the Live meter nests its own strip, so a wrapped second row would cost the meter height.
        pane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        for (RunsTab tab : RunsTab.values()) {
            JComponent content = tab == RunsTab.FEED ? feed : tab == RunsTab.LIVE_METER ? liveMeter : holder(tab);
            tabs.add(tab.id(), tab.title(), content);
        }
        // Adding keeps whichever tab was selected first; the page opens on its first visible tab in the saved order instead.
        List<String> visible = tabs.visibleIds();
        if (!visible.isEmpty()) tabs.select(visible.get(0));
        add(pane, BorderLayout.CENTER);
    }

    private JPanel holder(RunsTab tab) {
        JPanel holder = new JPanel(new BorderLayout());
        holder.setName("runs-" + tab.id() + "-slot");
        holder.setOpaque(false);
        holders.put(tab, holder);
        return holder;
    }

    public RunsPage feed() { return feed; }
    public CustomizableTabs tabs() { return tabs; }
    /** The tab in front, or null when none is. */
    public RunsTab selectedTab() { return RunsTab.of(tabs.selectedId()); }

    /** Explicit navigation to {@code tab}: shows it when hidden (the saved hidden set changes, as the tab menu's Show does), then selects it. */
    public void bring(RunsTab tab) {
        String id = Objects.requireNonNull(tab, "tab").id();
        tabs.show(id);
        tabs.select(id);
    }

    /**
     * Puts {@code content} in the Dungeons or Recordings tab's holder in place of what it held (null empties it); the tab itself
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
     * The target whose state is captured while {@code tab} is in front, and restored when Back returns to it: the tab's own,
     * unwrapped target (null: none, the tab alone comes back). One of this page's targets is refused, since its capture would
     * capture the page again.
     */
    public void owner(RunsTab tab, RouteTarget owner) {
        Objects.requireNonNull(tab, "tab");
        if (owner instanceof PageTarget) throw new IllegalArgumentException("An owner is the tab's own target, not a Runs & DPS page target");
        if (owner == null) owners.remove(tab); else owners.put(tab, owner);
    }

    /**
     * {@code inner} (a target whose view is in {@code tab}) as a page-10 target: its destination, {@code accepts} and
     * {@code redirect} are the inner target's; opening brings {@code tab} forward first, then opens {@code inner}, and a rejected
     * open brings back the tab that was in front (hiding {@code tab} again if it was hidden) before the rejection reaches the
     * navigator. Its Back state is the page's {@link PageState}, not the inner target's own.
     */
    public RouteTarget routes(RunsTab tab, RouteTarget inner) {
        Objects.requireNonNull(tab, "tab");
        Objects.requireNonNull(inner, "inner");
        return new PageTarget() {
            @Override public Destination destination() { return inner.destination(); }
            @Override public boolean accepts(Route route) { return inner.accepts(route); }
            @Override public Route redirect(Route route) { return inner.redirect(route); }
            @Override public void open(Route route) { openOn(tab, () -> inner.open(route)); }
        };
    }

    /**
     * {@code RUNS} routes with a {@link RunsFocus} payload and no other reference (search entries, the DPS Logger pointer, a
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
                SwingUtilities.invokeLater(() -> { if (!closed && selectedTab() == RunsTab.LIVE_METER) focus.run(); });
            }
        };
    }

    /** Receives the canonical dungeon of a Feed {@link RunsFocus} (the feed's dungeon filter); null removes it. */
    public void onFeedDungeon(Consumer<String> hook) { feedDungeon = hook; }

    /** Brings {@code tab} forward and runs {@code open}; a failure brings back the tab in front before (hiding {@code tab} again) and rethrows. */
    private void openOn(RunsTab tab, Runnable open) {
        RunsTab before = selectedTab();
        boolean hidden = tabs.hiddenIds().contains(tab.id());
        bring(tab);
        try { open.run(); }
        catch (RuntimeException failed) {   // a rejected route changes nothing: the navigator keeps the origin page
            if (before != null) tabs.select(before.id());
            if (hidden) tabs.hide(tab.id());
            throw failed;
        }
    }

    /** The page's Back state: the tab in front and its owner's state. */
    private PageState capture() {
        RunsTab tab = selectedTab();
        RouteTarget owner = tab == null ? null : owners.get(tab);
        return new PageState(tab, owner == null ? null : owner.captureState());
    }

    /** Back is explicit navigation: the tab comes forward first (hiding a showing workspace cancels its read), then its owner restores. */
    private void restore(Object state) {
        if (!(state instanceof PageState)) throw new IllegalArgumentException("Not a Runs & DPS page state");
        PageState saved = (PageState) state;
        if (saved.tab() == null) return;
        bring(saved.tab());
        RouteTarget owner = owners.get(saved.tab());
        if (owner != null && saved.inner() != null) owner.restoreState(saved.inner());
    }

    /** Whether the route carries any reference besides its payload. */
    private static boolean referenced(Route route) {
        return route.query != null || route.visit != null || route.record != null || route.recordingId != null
            || route.localObjectId != null || route.from != null || route.until != null;
    }

    /** A page-10 target: whichever of them the navigator captures or restores, the state is the page's {@link PageState}. */
    private abstract class PageTarget implements RouteTarget {
        @Override public final Object captureState() { return capture(); }
        @Override public final void restoreState(Object state) { restore(state); }
    }

    /**
     * Closes every tab's content that has a lifecycle (an {@code AutoCloseable}, such as the feed's {@link RunsPage} or an
     * {@code ArchiveWorkspace}), the contents of hidden tabs and holders included. The feed's workspace is closed with the other
     * archive workspaces. Every content is closed even when one fails; the first failure is rethrown afterwards. Idempotent.
     */
    @Override public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (Component tab : tabs.contents()) {
            Component content = contentOf(tab);
            if (!(content instanceof AutoCloseable)) continue;
            try { ((AutoCloseable) content).close(); }
            catch (Exception failed) {
                if (failure == null) failure = failed instanceof RuntimeException ? (RuntimeException) failed : new IllegalStateException(failed);
                else failure.addSuppressed(failed);
            }
        }
        if (failure != null) throw failure;
    }

    /** A holder's content, else the tab's component itself. */
    private Component contentOf(Component tab) {
        for (Map.Entry<RunsTab, JPanel> holder : holders.entrySet()) if (holder.getValue() == tab) return held.get(holder.getKey());
        return tab;
    }
}
