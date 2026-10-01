package tomato.gui.loot.explore;

import java.awt.*;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.*;
import tomato.gui.kit.SegmentedControl;
import tomato.gui.kit.Tokens;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;
import tomato.history.SessionStore;
import tomato.history.link.VisitRef;
import util.PropertiesManager;

/**
 * Loot › Explore with saved history: Pictures (Runs | Collection with item history, {@link ExplorePictures}) and Table (the Loot workspace, unchanged: its
 * views, filters, saved views and exports), behind a Pictures | Table switch above both, remembered in {@link #VIEW_KEY}
 * (Pictures by default; restoring the preference only selects). Routes reach the view they need through {@link #routes}: an
 * exact run opens Pictures on that run, a query opens Table, and an {@link ExploreItem} route opens an item's drops through
 * {@link #itemTarget}. Back restores the view, Pictures' level, the run or "Loot outside runs", and the workspace's state.
 * Without saved history Explore stays the live dashboard alone (the coordinator does not build this page). EDT only.
 */
public final class LootExplorePage extends JPanel implements AutoCloseable {
    public static final String VIEW_KEY = "ui.loot.explore.view", PICTURES = "pictures", TABLE = "table";

    private final JComponent table;
    private final ExplorePictures pictures;
    private final BiConsumer<String, String> write;
    private final CardLayout layout = new CardLayout();
    private final JPanel views = new JPanel(layout);
    private final SegmentedControl viewSwitch = new SegmentedControl("loot-explore-view", "Pictures", "Table");
    private boolean tableShown, closed;

    /** The production page around {@code table} (the Loot workspace), with Pictures over saved history from {@code store}. */
    public LootExplorePage(JComponent table, Supplier<SessionStore> store) {
        this(table, ExplorePictures.production(store), PropertiesManager::getProperty, PropertiesManager::setProperties);
    }

    /** As above with the built Pictures and the preference store (tests). */
    LootExplorePage(JComponent table, ExplorePictures pictures, Function<String, String> read, BiConsumer<String, String> write) {
        super(new BorderLayout(0, Tokens.S));
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build Loot › Explore on the EDT");
        this.table = Objects.requireNonNull(table, "table");
        this.pictures = Objects.requireNonNull(pictures, "pictures");
        Objects.requireNonNull(read, "read");
        this.write = Objects.requireNonNull(write, "write");
        setName("loot-explore");
        setOpaque(false);
        views.setOpaque(false);
        views.add(pictures, PICTURES);
        views.add(table, TABLE);
        viewSwitch.getAccessibleContext().setAccessibleName("Explore view");
        viewSwitch.setToolTipText("Pictures shows your runs' loot as the game draws it; Table holds every loot view and filter");
        viewSwitch.onChange(index -> show(index == 1, true));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        row.setName("loot-explore-view-row");
        row.setOpaque(false);
        row.add(viewSwitch);
        add(row, BorderLayout.NORTH);
        add(views, BorderLayout.CENTER);
        show(TABLE.equals(read.apply(VIEW_KEY)), false);
    }

    public boolean tableShown() { return tableShown; }
    /** Explicit navigation to Pictures: brings it forward and remembers it. */
    public void showPictures() { show(false, true); }
    /** Explicit navigation to Table: brings it forward and remembers it. */
    public void showTable() { show(true, true); }
    public ExplorePictures pictures() { return pictures; }
    public JComponent table() { return table; }
    /** What a haul's "Open run" runs (the run's recap in production). */
    public void onOpenRun(Consumer<VisitRef> action) { pictures.onOpenRecap(action); }
    /** What opening an item runs (an {@link ExploreItem} route in production, so Back returns). */
    public void onOpenItem(java.util.function.IntConsumer action) { pictures.onOpenItem(action); }
    /** What opening a drop's run runs (an exact-run Loot route in production). */
    public void onOpenDrop(Consumer<VisitRef> action) { pictures.onOpenRun(action); }

    /**
     * {@code target} (a Loot workspace target) with the view each route needs: an exact run (a visit and no query) opens Pictures on
     * that run and leaves the workspace as it is; a query brings Table forward first; any other route keeps the view. Its captured
     * state also holds the view and the run or "Loot outside runs", and Back restores them with the workspace's state.
     * A rejected route changes nothing.
     */
    public RouteTarget routes(RouteTarget target) {
        Objects.requireNonNull(target, "target");
        return new RouteTarget() {
            @Override public Destination destination() { return target.destination(); }
            @Override public boolean accepts(Route route) { return target.accepts(route); }
            @Override public Route redirect(Route route) { return target.redirect(route); }
            @Override public Object captureState() { return new RouteState(tableShown, pictures.state(), target.captureState()); }
            @Override public void open(Route route) {
                if (route.visit != null && route.query == null) {
                    showPictures();
                    pictures.openRun(route.visit);
                    return;
                }
                boolean table = tableShown;
                if (route.query != null) showTable();
                try { target.open(route); }
                catch (RuntimeException failed) {
                    if (!table) showPictures();
                    throw failed;
                }
            }
            @Override public void restoreState(Object state) { restore(state, target); }
        };
    }

    /** A Loot route payload: open Explore's Pictures on this item's drops. */
    public record ExploreItem(int itemId) {}

    /** LOOT routes carrying {@link ExploreItem}: Pictures on that item; Back restores the view and Pictures' level. */
    public RouteTarget itemTarget() {
        return new RouteTarget() {
            @Override public Destination destination() { return Destination.LOOT; }
            @Override public boolean accepts(Route route) { return route.destination == Destination.LOOT && route.payload instanceof ExploreItem; }
            @Override public Object captureState() { return new RouteState(tableShown, pictures.state(), null); }
            @Override public void open(Route route) {
                if (!accepts(route)) throw new IllegalArgumentException("Unsupported Explore item route: " + route);
                showPictures();
                pictures.openItem(((ExploreItem) route.payload).itemId());
            }
            @Override public void restoreState(Object state) { restore(state, null); }
        };
    }

    /** Shows the saved view, puts Pictures back, then restores {@code target}'s own state when it has one. */
    private void restore(Object state, RouteTarget target) {
        if (!(state instanceof RouteState saved)) throw new IllegalArgumentException("Not a Loot › Explore state");
        if (saved.table()) showTable(); else showPictures();
        pictures.restore(saved.pictures());
        if (target != null && saved.inner() != null) target.restoreState(saved.inner());
    }

    /** Which view showed, Pictures' level and run or "Loot outside runs", and the workspace target's own state. */
    record RouteState(boolean table, ExplorePictures.State pictures, Object inner) {}

    private void show(boolean table, boolean remember) {
        tableShown = table;
        if (remember) write.accept(VIEW_KEY, table ? TABLE : PICTURES);
        layout.show(views, table ? TABLE : PICTURES);
        viewSwitch.setSelected(table ? 1 : 0);
        views.revalidate();
        views.repaint();
    }

    /** Releases Pictures' reads and the workspace (the Loot page closes this page as its Explore content). EDT. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        pictures.close();
        if (table instanceof AutoCloseable closeable) {
            try { closeable.close(); }
            catch (RuntimeException failed) { throw failed; }
            catch (Exception failed) { throw new IllegalStateException(failed); }
        }
    }
}
