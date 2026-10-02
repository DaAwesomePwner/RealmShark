package tomato.gui.loot.explore;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.IntConsumer;
import java.util.function.Consumer;
import javax.swing.*;
import tomato.gui.kit.*;
import tomato.gui.modern.ContentStyle;
import tomato.history.archive.Cancellation;

/** A dungeon's saved loot, read on its own worker; only the newest request may apply on the EDT. */
public final class DungeonPanel extends JPanel implements AutoCloseable {
    static final String LOADING = "Loading this dungeon's loot…", EMPTY = "No saved bags in this dungeon.";
    private final LootCatalog.Reader catalog;
    private final Executor worker;
    private final JTextArea status = ContentStyle.wrappingText("");
    private IntConsumer openItem = id -> {};
    private Consumer<String> openCollection = dungeon -> {};
    private final KitButton collection = KitButton.ghost("");
    private Cancellation cancel = new Cancellation();
    private long generation;
    private boolean closed;
    private String dungeon;
    private DungeonStats model;

    public static DungeonPanel production(LootCatalog.Reader catalog) {
        return new DungeonPanel(catalog, CollectionLevel.daemon("RealmShark loot dungeon"));
    }

    DungeonPanel(LootCatalog.Reader catalog, Executor worker) {
        super(new BorderLayout(0, Tokens.S));
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build the dungeon panel on the EDT");
        this.catalog = Objects.requireNonNull(catalog);
        this.worker = Objects.requireNonNull(worker);
        setName("loot-dungeon-panel");
        setOpaque(false);
        status.setName("loot-dungeon-status");
        status.setFocusable(false);
        collection.setName("loot-dungeon-collection");
        collection.setVisible(false);
        collection.addActionListener(e -> openCollection.accept(dungeon));
        setVisible(false);
    }

    public void onOpenItem(IntConsumer action) { openItem = Objects.requireNonNull(action); }
    public void onOpenCollection(Consumer<String> action) { openCollection = Objects.requireNonNull(action); }
    public String dungeon() { return dungeon; }
    public DungeonStats model() { return model; }
    JTextArea status() { return status; }

    /** Null/unknown hides the panel. A same-dungeon refresh keeps the last successful content while it reads. */
    public void showDungeon(String canonical) {
        if (closed) return;
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Show the dungeon on the EDT");
        String next = DungeonStats.of(List.of(), canonical).dungeon();
        boolean keep = model != null && Objects.equals(dungeon, next);
        dungeon = next;
        collection.setVisible(false);
        long ticket = ++generation;
        cancel.cancel();
        Cancellation token = cancel = new Cancellation();
        setVisible(dungeon != null);
        if (!keep) {
            model = null;
            removeAll();
            if (dungeon == null) { revalidate(); repaint(); return; }
            KitText title = KitText.emphasis("This dungeon so far");
            title.setName("loot-dungeon-title");
            add(title, BorderLayout.NORTH);
            status.setText(LOADING);
            add(status, BorderLayout.CENTER);
        }
        if (collection.getParent() != this) add(collection, BorderLayout.SOUTH);
        revalidate(); repaint();
        String wanted = dungeon;
        try {
            worker.execute(() -> {
                DungeonStats read = null;
                String failure = null;
                try { read = DungeonStats.of(catalog.bags(token), wanted); }
                catch (CancellationException cancelled) { return; }
                catch (Exception | Error failed) { failure = RunsLevelMessages.message(failed); }
                DungeonStats done = read;
                String why = failure;
                SwingUtilities.invokeLater(() -> apply(ticket, done, why));
            });
        } catch (RejectedExecutionException rejected) {
            apply(ticket, null, RunsLevelMessages.message(rejected));
        }
    }

    private void apply(long ticket, DungeonStats read, String failure) {
        if (closed || ticket != generation) return;
        if (failure != null) {
            collection.setVisible(false);
            model = null;
            clearBody();
            status.setText("This dungeon's loot could not be read: " + failure);
            add(status, BorderLayout.CENTER);
        } else {
            collection.setText("All items from " + read.dungeon());
            collection.setVisible(read.bags() > 0);
            if (read.equals(model)) return; // keep focused Most-dropped buttons when no displayed facts changed
            model = read;
            clearBody();
            JPanel content = new JPanel();
            content.setOpaque(false);
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            JTextArea summary = ContentStyle.wrappingText(read.dungeon() + " · " + count(read.runs(), "run", "runs") + " with loot");
            summary.setName("loot-dungeon-summary");
            summary.setFocusable(false);
            content.add(summary);
            JTextArea counts = ContentStyle.wrappingText(count(read.whites(), "white bag", "white bags") + " · " + count(read.uts(), "UT", "UTs") + " · "
                + count(read.sts(), "ST", "STs") + " · " + count(read.potions(), "stat potion", "stat potions"));
            counts.setName("loot-dungeon-counts");
            counts.setFocusable(false);
            content.add(counts);
            if (read.bags() == 0) {
                status.setText(EMPTY);
                content.add(status);
            } else if (!read.mostDropped().isEmpty()) {
                content.add(KitText.emphasis("Most dropped"));
                JPanel items = new JPanel(new GridLayout(0, 3, Tokens.XS, Tokens.XS));
                items.setName("loot-dungeon-items");
                items.setOpaque(false);
                for (DungeonStats.Item item : read.mostDropped()) {
                    JButton button = new JButton("×" + item.count(), Sprites.sprite(item.id(), 32));
                    button.setName("loot-dungeon-item-" + item.id());
                    button.setVerticalTextPosition(SwingConstants.BOTTOM);
                    button.setHorizontalTextPosition(SwingConstants.CENTER);
                    button.setContentAreaFilled(false);
                    button.getAccessibleContext().setAccessibleName(Sprites.name(item.id()) + " ×" + item.count() + "; Open item history");
                    button.setToolTipText(button.getAccessibleContext().getAccessibleName());
                    button.addActionListener(e -> openItem.accept(item.id()));
                    for (String key : new String[] {"ENTER", "SPACE"}) button.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), "loot-dungeon-open");
                    button.getActionMap().put("loot-dungeon-open", new AbstractAction() {
                        @Override public void actionPerformed(ActionEvent e) { openItem.accept(item.id()); }
                    });
                    items.add(button);
                }
                content.add(items);
            }
            for (Component part : content.getComponents()) if (part instanceof JComponent component) component.setAlignmentX(LEFT_ALIGNMENT);
            add(content, BorderLayout.CENTER);
        }
        revalidate(); repaint();
    }

    private void clearBody() {
        Component body = ((BorderLayout) getLayout()).getLayoutComponent(BorderLayout.CENTER);
        if (body != null) remove(body);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        generation++;
        cancel.cancel();
        if (worker instanceof ExecutorService service) service.shutdownNow();
    }

    /** "1 UT", "4 UTs": the count with its singular or plural noun. */
    private static String count(int n, String one, String many) { return n + " " + (n == 1 ? one : many); }
}
