package tomato.gui.loot.explore;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import tomato.gui.history.WrapRow;
import tomato.gui.kit.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.stats.LootFacts;
import tomato.history.archive.Cancellation;

/**
 * Explore's Collection, the trophy cabinet: every item ever looted, as sprites with drop counts on shelves (UTs, STs, Tiered,
 * Potions, Other), most-dropped first. A shelf shows {@value #SHELF_LIMIT} items, then "Show all N". The search keeps the items whose
 * name contains it. Enter, Space or a double-click on a tile opens that item ({@link #onOpenItem}). Bags are read off the EDT on
 * {@link #reload} (each time Collection is shown; closed sessions come from the catalog's cache); only the newest read applies.
 * EDT only, except the reads.
 */
public final class CollectionLevel extends JPanel implements AutoCloseable {
    static final int SHELF_LIMIT = 60;
    static final String LOADING = "Loading your collection…", EMPTY = "Items appear here once capture saves loot bags.",
        NO_MATCH = "No item name matches the search.";

    private final LootCatalog.Reader catalog;
    private final Executor worker;
    private final IntFunction<String> names;
    private final JTextField search = new JTextField(18);
    private final KitText summary = KitText.caption(" ");
    private final JTextArea status = ContentStyle.wrappingText(LOADING);
    private final JPanel shelves = new JPanel() {
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }   // the page scrolls the shelves, never squeezes them
    };
    private final Set<CollectionModel.Kind> expanded = EnumSet.noneOf(CollectionModel.Kind.class);
    private List<LootFacts.Bag> bags;
    private CollectionModel model;
    private IntConsumer openItem = id -> { };
    private long generation;
    private Cancellation cancel = new Cancellation();
    private boolean closed;

    /** The production cabinet over {@code catalog}, reading on its own daemon worker; names come from the game's assets. */
    public static CollectionLevel production(LootCatalog.Reader catalog) {
        return new CollectionLevel(catalog, daemon("RealmShark loot collection"), Sprites::name);
    }

    CollectionLevel(LootCatalog.Reader catalog, Executor worker, IntFunction<String> names) {
        super(new BorderLayout());
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build the Collection on the EDT");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.names = Objects.requireNonNull(names, "names");
        setName("loot-collection");
        setOpaque(false);
        search.setName("loot-collection-search");
        search.putClientProperty("JTextField.placeholderText", "Search items");
        search.getAccessibleContext().setAccessibleName("Search items");
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { render(); }
            public void removeUpdate(DocumentEvent e) { render(); }
            public void changedUpdate(DocumentEvent e) { render(); }
        });
        summary.setName("loot-collection-summary");
        status.setName("loot-collection-status");
        status.setFocusable(false);
        shelves.setName("loot-collection-shelves");
        shelves.setOpaque(false);
        shelves.setLayout(new BoxLayout(shelves, BoxLayout.Y_AXIS));
        JPanel controls = new WrapRow(search, summary);
        JScrollPane page = ContentStyle.page(KitLayouts.stack(Tokens.S, controls, status), shelves, null);
        page.setName("loot-collection-scroll");
        page.getVerticalScrollBar().setUnitIncrement(32);
        add(page, BorderLayout.CENTER);
        render();
    }

    /** What opening a tile runs, with its item ID. */
    public void onOpenItem(IntConsumer action) { openItem = Objects.requireNonNull(action, "action"); }
    /** The cabinet shown now (null before the first read). */
    public CollectionModel model() { return model; }
    JTextField search() { return search; }
    JTextArea status() { return status; }

    /** Reads every saved bag again, off the EDT; the newest read's bags replace the shown ones. EDT. */
    public void reload() {
        if (closed) return;
        long ticket = ++generation;
        cancel.cancel();
        Cancellation token = cancel = new Cancellation();
        if (bags == null) { status.setText(LOADING); status.setVisible(true); }
        try {
            worker.execute(() -> {
                List<LootFacts.Bag> read = null;
                String failure = null;
                try { read = catalog.bags(token); }
                catch (CancellationException cancelled) { return; }
                catch (Exception | Error failed) { failure = RunsLevelMessages.message(failed); }
                List<LootFacts.Bag> done = read;
                String why = failure;
                SwingUtilities.invokeLater(() -> apply(ticket, done, why));
            });
        } catch (RejectedExecutionException shutDown) { /* closed */ }
    }

    private void apply(long ticket, List<LootFacts.Bag> read, String failure) {
        if (closed || ticket != generation) return;
        if (failure != null) {
            status.setText("Your collection could not be read: " + failure);
            status.setVisible(true);
            return;
        }
        bags = read;
        render();
    }

    /** EDT: the shelves for the read bags and the search. */
    private void render() {
        if (bags == null) return;
        model = CollectionModel.of(bags, search.getText(), names);
        shelves.removeAll();
        for (CollectionModel.Shelf shelf : model.shelves()) {
            String id = shelf.kind().name().toLowerCase(Locale.ROOT);
            SectionHeader header = new SectionHeader(shelf.kind().label + " · " + shelf.entries().size());
            header.setName("loot-collection-header-" + id);
            TileList<CollectionModel.Entry> tiles = new TileList<>("loot-collection-shelf-" + id, new CollectionTileRenderer(),
                entry -> String.valueOf(entry.itemId()), CollectionTileRenderer::accessibleName);
            boolean all = expanded.contains(shelf.kind()) || shelf.entries().size() <= SHELF_LIMIT;
            tiles.setItems(all ? shelf.entries() : shelf.entries().subList(0, SHELF_LIMIT));
            tiles.onOpen(entry -> openItem.accept(entry.itemId()));
            tiles.getAccessibleContext().setAccessibleDescription("Enter or Space opens the item's drops");
            stack(header);
            stack(tiles);
            if (!all) {
                KitButton more = KitButton.ghost("Show all " + shelf.entries().size());
                more.setName("loot-collection-more-" + id);
                more.addActionListener(e -> { expanded.add(shelf.kind()); render(); });
                stack(more);
            }
        }
        summary.setText(model.kinds() + (model.kinds() == 1 ? " item · " : " items · ") + model.drops() + (model.drops() == 1 ? " drop" : " drops"));
        boolean none = model.shelves().isEmpty();
        status.setText(none ? (search.getText().isBlank() ? EMPTY : NO_MATCH) : " ");
        status.setVisible(none);
        shelves.revalidate();
        shelves.repaint();
    }

    private void stack(JComponent part) {
        part.setAlignmentX(LEFT_ALIGNMENT);
        shelves.add(part);
    }

    static ThreadPoolExecutor daemon(String name) {
        ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
        worker.allowCoreThreadTimeOut(true);
        return worker;
    }

    /** Stops the reads. EDT. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        generation++;
        cancel.cancel();
        if (worker instanceof ExecutorService service) service.shutdownNow();
    }
}
