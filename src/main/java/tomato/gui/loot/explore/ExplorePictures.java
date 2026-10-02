package tomato.gui.loot.explore;

import java.awt.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.*;
import javax.swing.*;
import tomato.gui.kit.*;
import tomato.gui.loot.haul.HaulModel;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.runs.RunFeedQuery;
import tomato.history.SessionStore;
import tomato.history.link.VisitRef;
import util.PropertiesManager;

/**
 * Explore's Pictures: Runs, Dungeons and Collection, with a breadcrumb and item history. Level and dungeon moves use
 * {@link #onNavigate}; items and drops use their own routes. Run-card picks stay local. State carries the dungeon, level,
 * run (or "Loot outside runs") and item so Back and Forward restore each place. EDT only.
 */
public final class ExplorePictures extends JPanel implements AutoCloseable {
    public static final String ENTRY_KEY = "ui.loot.explore.entry", RUNS_ENTRY = "runs", DUNGEONS_ENTRY = "dungeons", COLLECTION_ENTRY = "collection";

    public enum Level { RUNS, DUNGEONS, COLLECTION, ITEM }

    /** A level move; only Runs and Collection may carry a canonical dungeon filter. */
    public record Focus(Level level, String dungeon) {
        public Focus {
            Objects.requireNonNull(level, "level");
            if (level == Level.ITEM || (dungeon != null && level == Level.DUNGEONS))
                throw new IllegalArgumentException("Not a Pictures level focus");
        }
    }

    /** Where Pictures is: the level, the run or "Loot outside runs" of Runs, and the item with the level it was opened from. */
    public record State(Level level, VisitRef run, boolean unlinked, int itemId, Level itemFrom, String dungeon) {}

    private final RunsLevel runs;
    private final CollectionLevel collection;
    private final DungeonsLevel dungeons;
    private final ItemLevel item;
    private final IntFunction<String> names;
    private final BiConsumer<String, String> write;
    private final Breadcrumb path = new Breadcrumb("loot-explore-path");
    private final SegmentedControl entry = new SegmentedControl("loot-explore-entry", "Runs", "Dungeons", "Collection");
    private final CardLayout layout = new CardLayout();
    private final JPanel body = new JPanel(layout);
    private Level level = Level.RUNS, itemFrom = Level.RUNS;
    private int itemId;
    private String dungeon;
    private Consumer<Focus> navigate = this::go;
    private IntConsumer openItem = this::openItem;
    private Consumer<VisitRef> openRun = this::openRun;

    /** The production Pictures: one saved-loot catalog shared by the dungeon panel, Collection and item history. */
    public static ExplorePictures production(Supplier<SessionStore> store) {
        LootCatalog.Reader catalog = LootCatalog.over(store);
        return new ExplorePictures(RunsLevel.production(store, catalog), CollectionLevel.production(catalog), DungeonsLevel.production(store, catalog), ItemLevel.production(catalog),
            Sprites::name, PropertiesManager::getProperty, PropertiesManager::setProperties);
    }

    /** As {@link #production} with built levels, the item names the breadcrumb shows, and the preference store (tests). */
    ExplorePictures(RunsLevel runs, CollectionLevel collection, DungeonsLevel dungeons, ItemLevel item, IntFunction<String> names, Function<String, String> read,
                    BiConsumer<String, String> write) {
        super(new BorderLayout(0, Tokens.S));
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build Explore's Pictures on the EDT");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.collection = Objects.requireNonNull(collection, "collection");
        this.dungeons = Objects.requireNonNull(dungeons, "dungeons");
        this.item = Objects.requireNonNull(item, "item");
        this.names = Objects.requireNonNull(names, "names");
        Objects.requireNonNull(read, "read");
        this.write = Objects.requireNonNull(write, "write");
        setName("loot-explore-pictures");
        setOpaque(false);
        body.setOpaque(false);
        body.add(runs, Level.RUNS.name());
        body.add(collection, Level.COLLECTION.name());
        body.add(dungeons, Level.DUNGEONS.name());
        body.add(item, Level.ITEM.name());
        entry.getAccessibleContext().setAccessibleName("Explore by");
        entry.onChange(index -> {
            write.accept(ENTRY_KEY, index == 2 ? COLLECTION_ENTRY : index == 1 ? DUNGEONS_ENTRY : RUNS_ENTRY);
            navigate.accept(new Focus(index == 2 ? Level.COLLECTION : index == 1 ? Level.DUNGEONS : Level.RUNS, null));
            highlight();
        });
        JPanel header = new JPanel(new BorderLayout(Tokens.M, 0));
        header.setOpaque(false);
        header.add(path, BorderLayout.CENTER);
        header.add(entry, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        runs.onShown(() -> { syncDungeon(); refreshPath(); highlight(); });
        runs.onOpenItem(key -> openItem.accept(itemOf(key)));
        runs.dungeon().onOpenItem(id -> openItem.accept(id));
        runs.dungeon().onOpenCollection(name -> navigate.accept(new Focus(Level.COLLECTION, name)));
        dungeons.onOpenDungeon(name -> navigate.accept(new Focus(Level.RUNS, name)));
        collection.onFilterCleared(() -> { dungeon = null; refreshPath(); highlight(); });
        collection.onOpenItem(id -> openItem.accept(id));
        item.onOpenRun(ref -> openRun.accept(ref));
        String preferred = read.apply(ENTRY_KEY);
        if (COLLECTION_ENTRY.equals(preferred)) showCollection();
        else if (DUNGEONS_ENTRY.equals(preferred)) showDungeons();
        else show(Level.RUNS);
    }

    public Level level() { return level; }
    public int itemId() { return itemId; }
    public VisitRef selectedRun() { return runs.selectedRun(); }
    public boolean showingUnlinked() { return runs.showingUnlinked(); }
    public RunsLevel runs() { return runs; }
    public CollectionLevel collection() { return collection; }
    public DungeonsLevel dungeons() { return dungeons; }
    public String dungeon() { syncDungeon(); return dungeon; }
    public ItemLevel item() { return item; }
    /** The breadcrumb's labels. */
    public List<String> path() { return path.labels(); }

    /** What opening an item runs, with its ID (default: {@link #openItem} here; production routes it, so Back returns). */
    public void onOpenItem(IntConsumer action) { openItem = Objects.requireNonNull(action, "action"); }
    /** What opening a drop's run runs (default: {@link #openRun} here; production routes it). */
    public void onOpenRun(Consumer<VisitRef> action) { openRun = Objects.requireNonNull(action, "action"); }
    /** What a haul's "Open run" runs (the run's recap in production). */
    public void onOpenRecap(Consumer<VisitRef> action) { runs.onOpenRun(action); }

    public void onNavigate(Consumer<Focus> action) { navigate = Objects.requireNonNull(action, "action"); }

    /** Applies a routed move without issuing another route. */
    public void go(Focus focus) {
        switch (focus.level()) {
            case RUNS -> { if (focus.dungeon() == null) showRuns(); else openDungeon(focus.dungeon()); }
            case DUNGEONS -> showDungeons();
            case COLLECTION -> { if (focus.dungeon() == null) showCollection(); else openDungeonCollection(focus.dungeon()); }
            case ITEM -> throw new IllegalArgumentException("Items use an item route");
        }
    }

    public void showRuns() {
        dungeon = null;
        runs.feed().showDungeon(null);
        show(Level.RUNS);
    }

    public void showDungeons() {
        dungeon = null;
        show(Level.DUNGEONS);
        dungeons.reload();
    }

    public void openDungeon(String canonical) {
        boolean same = Objects.equals(runs.feed().query().map(), canonical);
        runs.clearRun();
        runs.feed().showDungeon(canonical);
        if (same) runs.feed().refresh(); // showDungeon keeps an equal query; a fresh page must pick its newest run
        dungeon = canonical;
        show(Level.RUNS);
    }

    public void openDungeonCollection(String canonical) {
        dungeon = canonical;
        collection.filterDungeon(canonical);
        show(Level.COLLECTION);
        collection.reload();
    }

    /** Shows Collection, reading saved bags again (closed sessions come from the cache). */
    public void showCollection() {
        openDungeonCollection(null);
    }

    /** Shows item {@code id}'s drops; the breadcrumb leads back to the level it was opened from. */
    public void openItem(int id) {
        if (level != Level.ITEM) itemFrom = level;
        itemId = id;
        item.open(id);
        show(Level.ITEM);
    }

    /** Shows Runs on {@code ref}'s haul. */
    public void openRun(VisitRef ref) {
        showRuns();
        runs.openRun(ref);
    }

    /** Shows Runs on "Loot outside runs". */
    public void openUnlinked() {
        showRuns();
        runs.openUnlinked();
    }

    public State state() { return new State(level, runs.selectedRun(), runs.showingUnlinked(), itemId, itemFrom, dungeon()); }

    /** Goes back to {@code state}: Runs' run or "Loot outside runs" first, then the level (an item reads its drops again). */
    public void restore(State state) {
        Objects.requireNonNull(state, "state");
        Level root = state.level() == Level.ITEM ? state.itemFrom() : state.level();
        String filter = root == Level.RUNS ? state.dungeon() : null;
        RunFeedQuery before = runs.feed().query();
        boolean keep = Objects.equals(before.map(), filter) && (state.unlinked() ? runs.showingUnlinked()
            : state.run() != null && state.run().equals(runs.shownRun()));
        if (!keep) runs.clearRun();
        runs.feed().showDungeon(filter);
        if (!keep) {
            if (state.unlinked()) runs.openUnlinked();
            else if (state.run() != null) runs.openRun(state.run());
            else if (filter != null && before.equals(runs.feed().query())) runs.feed().refresh();
        }
        dungeon = state.dungeon();
        itemFrom = state.itemFrom();
        itemId = state.itemId();
        collection.filterDungeon(root == Level.COLLECTION ? dungeon : null);
        switch (state.level()) {
            case RUNS -> show(Level.RUNS);
            case DUNGEONS -> showDungeons();
            case COLLECTION -> { show(Level.COLLECTION); collection.reload(); }
            case ITEM -> {
                item.open(itemId);
                show(Level.ITEM);
            }
        }
    }

    /** The item ID of a haul's exact variant key ("id/slots/applied"). */
    static int itemOf(String variantKey) {
        int slash = variantKey.indexOf('/');
        return Integer.parseInt(slash < 0 ? variantKey : variantKey.substring(0, slash));
    }

    private void show(Level next) {
        level = next;
        layout.show(body, next.name());
        highlight();
        refreshPath();
    }

    private void highlight() {
        Level root = level == Level.ITEM ? itemFrom : level;
        entry.setSelected(dungeon != null || root == Level.DUNGEONS ? 1 : root == Level.COLLECTION ? 2 : 0);
    }

    private void syncDungeon() {
        if (level == Level.RUNS) dungeon = runs.feed().query().map();
    }

    /** The breadcrumb for what shows: the way in, Runs' run or "Loot outside runs", and the item. */
    private void refreshPath() {
        List<Breadcrumb.Crumb> crumbs = new ArrayList<>();
        Level root = level == Level.ITEM ? itemFrom : level;
        String currentDungeon = dungeon;
        if (root == Level.DUNGEONS || currentDungeon != null) {
            crumbs.add(crumb("Dungeons", Level.DUNGEONS, null));
            if (currentDungeon != null) crumbs.add(crumb(currentDungeon, Level.RUNS, currentDungeon));
        } else if (root == Level.COLLECTION) crumbs.add(crumb("Collection", Level.COLLECTION, null));
        else crumbs.add(crumb("Runs", Level.RUNS, null));
        if (root == Level.COLLECTION && currentDungeon != null) crumbs.add(crumb("All items", Level.COLLECTION, currentDungeon));
        if (root == Level.RUNS) {
            String run = runLabel();
            if (run != null) crumbs.add(crumb(run, Level.RUNS, currentDungeon));
        }
        if (level == Level.ITEM) crumbs.add(new Breadcrumb.Crumb(names.apply(itemId), null));
        path.setPath(crumbs);
    }

    private Breadcrumb.Crumb crumb(String label, Level target, String filter) {
        return new Breadcrumb.Crumb(label, () -> navigate.accept(new Focus(target, filter)));
    }

    /** "Lost Halls · 21:40" for the drawn run, "Loot outside runs", "Run" while one loads, or null when none is chosen. */
    private String runLabel() {
        if (runs.showingUnlinked()) return "Loot outside runs";
        VisitRef selected = runs.selectedRun();
        if (selected == null) return null;
        HaulModel.Header header = selected.equals(runs.shownRun()) && runs.haul().model() != null ? runs.haul().model().header() : null;
        if (header == null) return "Run";
        return header.entered() == null ? header.mapName()
            : header.mapName() + " · " + DisplayFormat.formatTimestamp(Instant.ofEpochMilli(header.entered()), DisplayFormat.TimestampMode.TIME);
    }

    /** Releases every level's reads. EDT. */
    @Override public void close() {
        runs.close();
        collection.close();
        dungeons.close();
        item.close();
    }
}
