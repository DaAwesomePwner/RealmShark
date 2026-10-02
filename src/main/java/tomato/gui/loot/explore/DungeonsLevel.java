package tomato.gui.loot.explore;

import java.awt.BorderLayout;
import java.io.IOException;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import tomato.gui.history.WrapRow;
import tomato.gui.kit.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.runs.*;
import tomato.gui.stats.LootFacts;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;

/** Explore's dungeon wall, with saved runs and loot read on one worker and only the newest result applied on the EDT. */
public final class DungeonsLevel extends JPanel implements AutoCloseable {
    static final String LOADING = "Loading dungeons…", EMPTY = "Dungeons appear here once capture saves a run or a loot bag.",
        NO_MATCH = "No dungeon name matches the search.";

    @FunctionalInterface public interface Cards { List<DungeonCardModel> cards(Cancellation cancel) throws IOException; }

    private final Cards source;
    private final LootCatalog.Reader catalog;
    private final Executor worker;
    private final JTextField search = new JTextField(18);
    private final SegmentedControl sorter = new SegmentedControl("loot-dungeons-sort",
        AtlasModel.Sort.RUNS.label, AtlasModel.Sort.WHITES.label, AtlasModel.Sort.UTS.label, AtlasModel.Sort.RECENT.label);
    private final KitText summary = KitText.caption(" ");
    private final JTextArea status = ContentStyle.wrappingText(LOADING);
    private final TileList<AtlasModel.Tile> tiles = new TileList<>("loot-dungeons-tiles", new DungeonTileRenderer(),
        AtlasModel.Tile::dungeon, DungeonTileRenderer::accessibleName);
    private List<DungeonCardModel> cards;
    private List<LootFacts.Bag> bags;
    private AtlasModel model;
    private Consumer<String> openDungeon = dungeon -> {};
    private Cancellation cancel = new Cancellation();
    private long generation;
    private boolean closed;

    public static DungeonsLevel production(Supplier<SessionStore> store, LootCatalog.Reader catalog) {
        Cards cards = new Cards() {
            private SessionStore current;
            private DungeonsSource source;
            @Override public List<DungeonCardModel> cards(Cancellation cancel) throws IOException {
                SessionStore open = store.get();
                if (open == null) throw new IOException(RunHauls.NOT_OPEN);
                if (open != current) {
                    current = open;
                    source = new DungeonsSource(open, ZoneId.systemDefault(), System::currentTimeMillis);
                }
                return source.read(DungeonsQuery.all(), cancel).cards();
            }
        };
        return new DungeonsLevel(cards, catalog, CollectionLevel.daemon("RealmShark loot dungeons"));
    }

    DungeonsLevel(Cards source, LootCatalog.Reader catalog, Executor worker) {
        super(new BorderLayout());
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build Dungeons on the EDT");
        this.source = Objects.requireNonNull(source);
        this.catalog = Objects.requireNonNull(catalog);
        this.worker = Objects.requireNonNull(worker);
        setName("loot-dungeons");
        setOpaque(false);
        search.setName("loot-dungeons-search");
        search.putClientProperty("JTextField.placeholderText", "Search dungeons");
        search.getAccessibleContext().setAccessibleName("Search dungeons");
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { render(); }
            public void removeUpdate(DocumentEvent e) { render(); }
            public void changedUpdate(DocumentEvent e) { render(); }
        });
        sorter.onChange(index -> render());
        summary.setName("loot-dungeons-summary");
        status.setName("loot-dungeons-status");
        status.setFocusable(false);
        tiles.onOpen(tile -> openDungeon.accept(tile.dungeon()));
        tiles.getAccessibleContext().setAccessibleDescription("Enter or Space opens this dungeon's runs");
        JScrollPane page = ContentStyle.page(KitLayouts.stack(Tokens.S, new WrapRow(search, sorter, summary), status), tiles, null);
        page.getVerticalScrollBar().setUnitIncrement(32);
        add(page, BorderLayout.CENTER);
    }

    public void onOpenDungeon(Consumer<String> action) { openDungeon = Objects.requireNonNull(action); }
    public AtlasModel model() { return model; }
    public AtlasModel.Sort sort() { return AtlasModel.Sort.values()[sorter.selected()]; }
    JTextField search() { return search; }
    JTextArea status() { return status; }
    SegmentedControl sorter() { return sorter; }

    public void reload() {
        if (closed) return;
        long ticket = ++generation;
        cancel.cancel();
        Cancellation token = cancel = new Cancellation();
        status.setText(LOADING);
        status.setVisible(true);
        try {
            worker.execute(() -> {
                List<DungeonCardModel> readCards = null;
                List<LootFacts.Bag> readBags = null;
                String failure = null;
                try {
                    readCards = source.cards(token);
                    readBags = catalog.bags(token);
                } catch (CancellationException cancelled) { return; }
                catch (Exception | Error failed) { failure = RunsLevelMessages.message(failed); }
                List<DungeonCardModel> doneCards = readCards;
                List<LootFacts.Bag> doneBags = readBags;
                String why = failure;
                SwingUtilities.invokeLater(() -> apply(ticket, doneCards, doneBags, why));
            });
        } catch (RejectedExecutionException shutDown) { /* closed */ }
    }

    private void apply(long ticket, List<DungeonCardModel> readCards, List<LootFacts.Bag> readBags, String failure) {
        if (closed || generation != ticket) return;
        if (failure != null) {
            status.setText("Dungeons could not be read: " + failure);
            status.setVisible(true);
            return;
        }
        cards = readCards;
        bags = readBags;
        render();
    }

    private void render() {
        if (cards == null) return;
        model = AtlasModel.of(cards, bags, sort(), search.getText());
        tiles.setItems(model.tiles());
        summary.setText(DungeonTileRenderer.count(model.tiles().size(), "dungeon", "dungeons"));
        status.setText(model.tiles().isEmpty() ? (search.getText().isBlank() ? EMPTY : NO_MATCH) : " ");
        status.setVisible(model.tiles().isEmpty());
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        generation++;
        cancel.cancel();
        if (worker instanceof ExecutorService service) service.shutdownNow();
    }
}
