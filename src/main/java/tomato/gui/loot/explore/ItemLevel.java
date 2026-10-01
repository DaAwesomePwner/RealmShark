package tomato.gui.loot.explore;

import java.awt.*;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.swing.*;
import tomato.gui.kit.*;
import tomato.gui.loot.HighlightsModel;
import tomato.gui.loot.NotableDropRenderer;
import tomato.gui.loot.haul.HaulView;
import tomato.gui.modern.ContentStyle;
import tomato.gui.stats.LootFacts;
import tomato.history.archive.Cancellation;
import tomato.history.link.VisitRef;
import tomato.realmshark.EnchantInfo;

/**
 * Explore's item history: one item's every saved drop, newest first, as Highlights' drop cards (sprite in its bag's well, type
 * and rarity chips, time · area, "Not linked to a run"). The header shows the item's best drop, its name and "12 drops · 3 Rare".
 * At most {@value #DROP_LIMIT} cards show, with a note when there are more. Enter, Space or a double-click on a card with a run opens
 * that run ({@link #onOpenRun}). Reads off the EDT; only the newest {@link #open} applies. EDT only, except the reads.
 */
public final class ItemLevel extends JPanel implements AutoCloseable {
    static final int DROP_LIMIT = 200;
    static final String LOADING = "Loading this item's drops…", NONE = "No saved drop of this item.";

    private final LootCatalog.Reader catalog;
    private final Executor worker;
    private final ZoneId zone;
    private final ItemSlot slot = new ItemSlot(48);
    private final KitText name = KitText.emphasis(" "), summary = KitText.caption(" "), note = KitText.caption(" ");
    private final JTextArea status = ContentStyle.wrappingText(LOADING);
    private final TileList<HighlightsModel.Notable> drops;
    private ItemHistory model;
    private long capturedAt = System.currentTimeMillis();
    private int itemId;
    private Consumer<VisitRef> openRun = ref -> { };
    private long generation;
    private Cancellation cancel = new Cancellation();
    private boolean closed;

    public static ItemLevel production(LootCatalog.Reader catalog) {
        return new ItemLevel(catalog, CollectionLevel.daemon("RealmShark loot item"), ZoneId.systemDefault());
    }

    ItemLevel(LootCatalog.Reader catalog, Executor worker, ZoneId zone) {
        super(new BorderLayout());
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build the item history on the EDT");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.zone = Objects.requireNonNull(zone, "zone");
        setName("loot-item");
        setOpaque(false);
        slot.setName("loot-item-slot");
        name.setName("loot-item-name");
        summary.setName("loot-item-summary");
        note.setName("loot-item-note");
        note.setVisible(false);
        status.setName("loot-item-status");
        status.setFocusable(false);
        drops = new TileList<>("loot-item-drops", new NotableDropRenderer(zone, () -> capturedAt,
            "Enter opens the run", "Enter or a double-click opens the run"), HighlightsModel.Notable::key,
            drop -> NotableDropRenderer.accessibleName(drop, zone, capturedAt, "Enter opens the run"));
        drops.getAccessibleContext().setAccessibleDescription("Enter or Space opens the drop's run");
        drops.onOpen(drop -> { if (drop.visit() != null) openRun.accept(drop.visit()); });
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEADING, Tokens.M, 0));
        header.setOpaque(false);
        header.add(slot);
        header.add(KitLayouts.stack(0, name, summary));
        JScrollPane page = ContentStyle.page(KitLayouts.stack(Tokens.S, header, note, status), drops, null);
        page.setName("loot-item-scroll");
        page.getVerticalScrollBar().setUnitIncrement(32);
        add(page, BorderLayout.CENTER);
    }

    public int itemId() { return itemId; }
    /** The shown history (null before the first read applies). */
    public ItemHistory model() { return model; }
    /** What opening a drop that recorded a run runs, with that exact run. */
    public void onOpenRun(Consumer<VisitRef> action) { openRun = Objects.requireNonNull(action, "action"); }
    JTextArea status() { return status; }

    /** Shows {@code id}'s drops: its name at once, the drops once read. EDT. */
    public void open(int id) {
        if (closed) return;
        int previous = itemId;
        itemId = id;
        name.setText(Sprites.name(id));
        long ticket = ++generation;
        cancel.cancel();
        Cancellation token = cancel = new Cancellation();
        if (model == null || previous != id || model.itemId() != id) {
            status.setText(LOADING);
            status.setVisible(true);
            drops.setItems(List.of());
            summary.setText(" ");
            note.setVisible(false);
            slot.setItem(id, "");
        }
        try {
            worker.execute(() -> {
                ItemHistory read = null;
                String failure = null;
                try { read = ItemHistory.of(id, catalog.bags(token)); }
                catch (CancellationException cancelled) { return; }
                catch (Exception | Error failed) { failure = RunsLevelMessages.message(failed); }
                ItemHistory done = read;
                String why = failure;
                SwingUtilities.invokeLater(() -> apply(ticket, done, why));
            });
        } catch (RejectedExecutionException shutDown) { /* closed */ }
    }

    private void apply(long ticket, ItemHistory read, String failure) {
        if (closed || ticket != generation) return;
        if (failure != null) {
            status.setText("This item's drops could not be read: " + failure);
            status.setVisible(true);
            return;
        }
        model = read;
        capturedAt = System.currentTimeMillis();
        LootFacts.Item front = read.front();
        if (front != null) {
            EnchantInfo enchant = front.enchant().state() == EnchantInfo.State.NOT_RECORDED ? null : front.enchant();
            slot.setItem(read.itemId(), HaulView.tierLabel(front), enchant);
        }
        summary.setText(read.summary());
        List<HighlightsModel.Notable> shown = read.count() > DROP_LIMIT ? read.drops().subList(0, DROP_LIMIT) : read.drops();
        drops.setItems(shown);
        note.setText("Showing the newest " + DROP_LIMIT + " of " + read.count() + " drops");
        note.setVisible(read.count() > DROP_LIMIT);
        status.setText(read.count() == 0 ? NONE : " ");
        status.setVisible(read.count() == 0);
        revalidate();
        repaint();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        generation++;
        cancel.cancel();
        if (worker instanceof ExecutorService service) service.shutdownNow();
    }
}
