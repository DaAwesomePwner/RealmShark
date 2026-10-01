package tomato.gui.loot;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import javax.swing.JComponent;
import tomato.gui.modern.TabbedRoutePage;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;
import tomato.gui.stats.LootQuery;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveQuery;

/**
 * Loot: Highlights and Explore ({@link LootTab}), with strip {@code loot-tabs} and preferences {@code ui.tabs.loot}.
 * Explore's archive target owns its Back state; Highlights restores its tab only, keeping its own window preference.
 * See {@link TabbedRoutePage} for shared Back, rejected-open rollback and close semantics.
 */
public final class LootPage extends TabbedRoutePage<LootTab, LootPage.PageState> {
    /** Detached Back state: the selected Loot tab and its owner's state, or null when it has no owner. */
    public record PageState(LootTab tab, Object inner) implements TabState<LootTab> {}

    private final JComponent highlights;

    /** The page around {@code highlights} (the Highlights tab) and {@code explore} (the Explore tab), both fixed. */
    public LootPage(JComponent highlights, JComponent explore) {
        super("loot", "loot-page", "Loot", LootTab.class, LootTab::id, LootTab::of, PageState.class, PageState::new);
        this.highlights = Objects.requireNonNull(highlights, "highlights");
        Objects.requireNonNull(explore, "explore");
        populate(LootTab.values(), LootTab::title, tab -> tab == LootTab.HIGHLIGHTS ? highlights : explore);
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

}
