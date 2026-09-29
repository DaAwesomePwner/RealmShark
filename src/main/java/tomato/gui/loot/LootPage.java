package tomato.gui.loot;

import java.awt.BorderLayout;
import java.awt.Component;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;
import tomato.gui.stats.LootQuery;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveQuery;

/**
 * Loot, the shell's {@code loot} page (spec §6.4, P6a): customizable tabs Highlights · Explore ({@link LootTab}, strip
 * {@code loot-tabs}, order and hidden set in {@code ui.tabs.loot}; neither is Analyst-only). Highlights is {@link LootHighlights};
 * Explore is the Loot workspace (the live dashboard and saved loot behind one view selector), or the live dashboard alone
 * without saved history. The composition follows {@code RunsDpsPage} (a shared base is P6b cleanup).
 * - The page opens on its first visible tab: the selected tab is not persisted, and startup only selects, never showing a hidden
 *   tab. Only explicit navigation ({@link #bring}: a route, Back, a search entry, Home's tile) brings a hidden tab forward.
 * - Back: {@code ShellNavigator} captures one target per page (whichever last opened it, else the newest registered), so every
 *   Loot target is this page's: the wrappers ({@link #routes}) and {@link #tabTarget()}. Each captures the same {@link PageState},
 *   the tab in front and the state of that tab's {@link #owner} (Explore: the Loot archive target; Highlights has none, its
 *   window choice is its own preference), and each restores it: the tab comes forward first, then its owner restores.
 * - Closing reaches every tab's content, hidden tabs included ({@link CustomizableTabs#contents()}): Loot highlights stops its
 *   reader and the workspace releases its saved results.
 * EDT only.
 */
public final class LootPage extends JPanel implements AutoCloseable {
    /**
     * The detached Back state every Loot target captures: the tab in front, and its owner's own state (null when that tab has no
     * owner). Restoring brings {@code tab} forward (showing it if it was hidden since), then restores the owner.
     */
    public record PageState(LootTab tab, Object inner) {}

    private final CustomizableTabs tabs;
    private final JComponent highlights;
    private final Map<LootTab, RouteTarget> owners = new EnumMap<>(LootTab.class);
    private boolean closed;

    /** The page around {@code highlights} (the Highlights tab) and {@code explore} (the Explore tab), both fixed. */
    public LootPage(JComponent highlights, JComponent explore) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build the Loot page on the EDT");
        Objects.requireNonNull(highlights, "highlights");
        Objects.requireNonNull(explore, "explore");
        this.highlights = highlights;
        tabs = new CustomizableTabs("loot");
        setName("loot-page");
        setLayout(new BorderLayout());
        setOpaque(false);
        JTabbedPane pane = tabs.component();
        // One row of tabs at any width, as on Runs & DPS: Explore's filter row sits right under it.
        pane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        for (LootTab tab : LootTab.values()) tabs.add(tab.id(), tab.title(), tab == LootTab.HIGHLIGHTS ? highlights : explore);
        // Adding keeps whichever tab was selected first; the page opens on its first visible tab in the saved order instead.
        List<String> visible = tabs.visibleIds();
        if (!visible.isEmpty()) tabs.select(visible.get(0));
        add(pane, BorderLayout.CENTER);
    }

    public CustomizableTabs tabs() { return tabs; }
    /** The tab in front, or null when none is. */
    public LootTab selectedTab() { return LootTab.of(tabs.selectedId()); }

    /** Explicit navigation to {@code tab}: shows it when hidden (the saved hidden set changes, as the tab menu's Show does), then selects it. */
    public void bring(LootTab tab) {
        String id = Objects.requireNonNull(tab, "tab").id();
        tabs.show(id);
        tabs.select(id);
    }

    /**
     * The target whose state is captured while {@code tab} is in front, and restored when Back returns to it: the tab's own,
     * unwrapped target (null: none, the tab alone comes back). One of this page's targets is refused, since its capture would
     * capture the page again.
     */
    public void owner(LootTab tab, RouteTarget owner) {
        Objects.requireNonNull(tab, "tab");
        if (owner instanceof PageTarget) throw new IllegalArgumentException("An owner is the tab's own target, not a Loot page target");
        if (owner == null) owners.remove(tab); else owners.put(tab, owner);
    }

    /**
     * {@code inner} (a target whose view is in {@code tab}) as a Loot page target: its destination, {@code accepts} and
     * {@code redirect} are the inner target's; opening brings {@code tab} forward first, then opens {@code inner}, and a rejected
     * open brings back the tab that was in front (hiding {@code tab} again if it was hidden) before the rejection reaches the
     * navigator. Its Back state is the page's {@link PageState}, not the inner target's own.
     */
    public RouteTarget routes(LootTab tab, RouteTarget inner) {
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
     * {@code LOOT} routes with a {@link LootFocus} payload and no other reference (search entries, Home's Notable loot tile,
     * Highlights' Unknown area cell): opening brings that tab forward and changes nothing inside it, except that a focus with a
     * window (Home's tile) applies it to Highlights as a click on its window choice does ({@link LootHighlights#showWindow}).
     * Highlights' window is its own preference, so Back restores the tab only. Visit, query and plain Loot routes stay the
     * workspace's targets ({@link #routes}).
     */
    public RouteTarget tabTarget() {
        return new PageTarget() {
            @Override public Destination destination() { return Destination.LOOT; }
            @Override public boolean accepts(Route route) {
                return route.destination == Destination.LOOT && !referenced(route) && route.payload instanceof LootFocus;
            }
            @Override public void open(Route route) {
                if (!accepts(route)) throw new IllegalArgumentException("Unsupported Loot tab route: " + route);
                LootFocus focus = (LootFocus) route.payload;
                openOn(focus.tab(), () -> {
                    if (focus.window() != null && highlights instanceof LootHighlights) ((LootHighlights) highlights).showWindow(focus.window());
                });
            }
        };
    }

    /**
     * The route a Highlights by-dungeon cell opens (P6a): Explore on saved loot, All Items (Explore's first view) filtered to
     * {@code dungeon} over the same period Highlights counted, so the rows are the cell's bags: This session is the current session;
     * Today is every saved session kept to the local day of {@code now} in {@code zone} (undated drops are outside it, as in
     * Highlights). Unknown area ({@code dungeon} null: no area recorded, so no dungeon filter can select those bags) opens Explore as
     * it is. A query route has no target without saved history; the caller then opens Explore alone.
     */
    public static Route dungeonRoute(String dungeon, HighlightsModel.Window window, ZoneId zone, long now) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(zone, "zone");
        if (dungeon == null) return Route.to(Destination.LOOT).withPayload(new LootFocus(LootTab.EXPLORE));
        boolean today = window == HighlightsModel.Window.TODAY;
        ArchiveQuery<LootQuery.Facets, LootQuery.Sort> query = LootQuery.initial(LootQuery.View.ITEMS, today ? SessionStore.ALL : ArchiveQuery.CURRENT);
        LootQuery.Facets facets = query.facets();
        facets.dungeons.add(dungeon);
        query = query.withFacets(facets);
        if (today) {
            LocalDate day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
            query = query.withBounds(new ArchiveQuery.Bounds(day.atStartOfDay(zone).toInstant().toEpochMilli(),
                day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), zone, ArchiveQuery.TimeMode.ENTRY, false));
        }
        return Route.to(Destination.LOOT).withQuery(query);
    }

    /** Brings {@code tab} forward and runs {@code open}; a failure brings back the tab in front before (hiding {@code tab} again) and rethrows. */
    private void openOn(LootTab tab, Runnable open) {
        LootTab before = selectedTab();
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
        LootTab tab = selectedTab();
        RouteTarget owner = tab == null ? null : owners.get(tab);
        return new PageState(tab, owner == null ? null : owner.captureState());
    }

    /** Back is explicit navigation: the tab comes forward first, then its owner restores. */
    private void restore(Object state) {
        if (!(state instanceof PageState)) throw new IllegalArgumentException("Not a Loot page state");
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

    /** A Loot page target: whichever of them the navigator captures or restores, the state is the page's {@link PageState}. */
    private abstract class PageTarget implements RouteTarget {
        @Override public final Object captureState() { return capture(); }
        @Override public final void restoreState(Object state) { restore(state); }
    }

    /**
     * Closes every tab's content that has a lifecycle (Loot highlights, the Loot workspace), the contents of hidden tabs included.
     * Every content is closed even when one fails; the first failure is rethrown afterwards. Idempotent.
     */
    @Override public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (Component content : tabs.contents()) {
            if (!(content instanceof AutoCloseable)) continue;
            try { ((AutoCloseable) content).close(); }
            catch (Exception failed) {
                if (failure == null) failure = failed instanceof RuntimeException ? (RuntimeException) failed : new IllegalStateException(failed);
                else failure.addSuppressed(failed);
            }
        }
        if (failure != null) throw failure;
    }
}
