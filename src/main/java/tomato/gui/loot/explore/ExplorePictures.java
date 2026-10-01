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
import tomato.history.SessionStore;
import tomato.history.link.VisitRef;
import util.PropertiesManager;

/**
 * Explore's Pictures: a breadcrumb ({@code Runs › Lost Halls · 21:40 › Synthetic Seal}) and the way in (Runs | Collection,
 * remembered in {@link #ENTRY_KEY}) above three levels: Runs ({@link RunsLevel}), Collection ({@link CollectionLevel}) and one
 * item's drops ({@link ItemLevel}). An item opens from a haul or a Collection tile and a run from a drop card; in production those
 * go through the navigator ({@link #onOpenItem}, {@link #onOpenRun}), so Back walks back through levels. {@link #state} and
 * {@link #restore} carry the level, the run (or "Loot outside runs") and the item. EDT only.
 */
public final class ExplorePictures extends JPanel implements AutoCloseable {
    public static final String ENTRY_KEY = "ui.loot.explore.entry", RUNS_ENTRY = "runs", COLLECTION_ENTRY = "collection";

    public enum Level { RUNS, COLLECTION, ITEM }

    /** Where Pictures is: the level, the run or "Loot outside runs" of Runs, and the item with the level it was opened from. */
    public record State(Level level, VisitRef run, boolean unlinked, int itemId, Level itemFrom) {}

    private final RunsLevel runs;
    private final CollectionLevel collection;
    private final ItemLevel item;
    private final IntFunction<String> names;
    private final BiConsumer<String, String> write;
    private final Breadcrumb path = new Breadcrumb("loot-explore-path");
    private final SegmentedControl entry = new SegmentedControl("loot-explore-entry", "Runs", "Collection");
    private final CardLayout layout = new CardLayout();
    private final JPanel body = new JPanel(layout);
    private Level level = Level.RUNS, itemFrom = Level.RUNS;
    private int itemId;
    private IntConsumer openItem = this::openItem;
    private Consumer<VisitRef> openRun = this::openRun;

    /** The production Pictures over saved history from {@code store}: one catalog shared by Collection and the item history. */
    public static ExplorePictures production(Supplier<SessionStore> store) {
        LootCatalog.Reader catalog = LootCatalog.over(store);
        return new ExplorePictures(RunsLevel.production(store), CollectionLevel.production(catalog), ItemLevel.production(catalog),
            Sprites::name, PropertiesManager::getProperty, PropertiesManager::setProperties);
    }

    /** As {@link #production} with built levels, the item names the breadcrumb shows, and the preference store (tests). */
    ExplorePictures(RunsLevel runs, CollectionLevel collection, ItemLevel item, IntFunction<String> names, Function<String, String> read,
                    BiConsumer<String, String> write) {
        super(new BorderLayout(0, Tokens.S));
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build Explore's Pictures on the EDT");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.collection = Objects.requireNonNull(collection, "collection");
        this.item = Objects.requireNonNull(item, "item");
        this.names = Objects.requireNonNull(names, "names");
        Objects.requireNonNull(read, "read");
        this.write = Objects.requireNonNull(write, "write");
        setName("loot-explore-pictures");
        setOpaque(false);
        body.setOpaque(false);
        body.add(runs, Level.RUNS.name());
        body.add(collection, Level.COLLECTION.name());
        body.add(item, Level.ITEM.name());
        entry.getAccessibleContext().setAccessibleName("Explore by");
        entry.onChange(index -> {
            write.accept(ENTRY_KEY, index == 1 ? COLLECTION_ENTRY : RUNS_ENTRY);
            if (index == 1) showCollection(); else showRuns();
        });
        JPanel header = new JPanel(new BorderLayout(Tokens.M, 0));
        header.setOpaque(false);
        header.add(path, BorderLayout.CENTER);
        header.add(entry, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        runs.onShown(this::refreshPath);
        runs.onOpenItem(key -> openItem.accept(itemOf(key)));
        collection.onOpenItem(id -> openItem.accept(id));
        item.onOpenRun(ref -> openRun.accept(ref));
        if (COLLECTION_ENTRY.equals(read.apply(ENTRY_KEY))) showCollection(); else show(Level.RUNS);
    }

    public Level level() { return level; }
    public int itemId() { return itemId; }
    public VisitRef selectedRun() { return runs.selectedRun(); }
    public boolean showingUnlinked() { return runs.showingUnlinked(); }
    public RunsLevel runs() { return runs; }
    public CollectionLevel collection() { return collection; }
    public ItemLevel item() { return item; }
    /** The breadcrumb's labels. */
    public List<String> path() { return path.labels(); }

    /** What opening an item runs, with its ID (default: {@link #openItem} here; production routes it, so Back returns). */
    public void onOpenItem(IntConsumer action) { openItem = Objects.requireNonNull(action, "action"); }
    /** What opening a drop's run runs (default: {@link #openRun} here; production routes it). */
    public void onOpenRun(Consumer<VisitRef> action) { openRun = Objects.requireNonNull(action, "action"); }
    /** What a haul's "Open run" runs (the run's recap in production). */
    public void onOpenRecap(Consumer<VisitRef> action) { runs.onOpenRun(action); }

    public void showRuns() { show(Level.RUNS); }

    /** Shows Collection, reading saved bags again (closed sessions come from the cache). */
    public void showCollection() {
        show(Level.COLLECTION);
        collection.reload();
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
        show(Level.RUNS);
        runs.openRun(ref);
    }

    /** Shows Runs on "Loot outside runs". */
    public void openUnlinked() {
        show(Level.RUNS);
        runs.openUnlinked();
    }

    public State state() { return new State(level, runs.selectedRun(), runs.showingUnlinked(), itemId, itemFrom); }

    /** Goes back to {@code state}: Runs' run or "Loot outside runs" first, then the level (an item reads its drops again). */
    public void restore(State state) {
        Objects.requireNonNull(state, "state");
        if (state.unlinked()) runs.openUnlinked(); else if (state.run() != null) runs.openRun(state.run());
        switch (state.level()) {
            case RUNS -> show(Level.RUNS);
            case COLLECTION -> showCollection();
            case ITEM -> {
                itemFrom = state.itemFrom();
                itemId = state.itemId();
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
        Level root = next == Level.ITEM ? itemFrom : next;
        entry.setSelected(root == Level.COLLECTION ? 1 : 0);
        refreshPath();
    }

    /** The breadcrumb for what shows: the way in, Runs' run or "Loot outside runs", and the item. */
    private void refreshPath() {
        List<Breadcrumb.Crumb> crumbs = new ArrayList<>();
        Level root = level == Level.ITEM ? itemFrom : level;
        if (root == Level.COLLECTION) crumbs.add(new Breadcrumb.Crumb("Collection", this::showCollection));
        else {
            crumbs.add(new Breadcrumb.Crumb("Runs", this::showRuns));
            String run = runLabel();
            if (run != null) crumbs.add(new Breadcrumb.Crumb(run, this::showRuns));
        }
        if (level == Level.ITEM) crumbs.add(new Breadcrumb.Crumb(names.apply(itemId), null));
        path.setPath(crumbs);
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
        item.close();
    }
}
