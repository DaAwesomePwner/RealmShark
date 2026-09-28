package tomato.gui.stats;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import tomato.history.SessionStore;
import tomato.history.link.VisitRef;

/**
 * Read-only facts about saved loot bags for pages outside this package (Home). The persisted drop classes stay
 * package-private; this projection carries the exact visit recorded at drop time and the saved item classifications.
 */
public final class LootFacts {
    /** One saved item occurrence; {@code untiered}/{@code setTiered} are UT/ST gear exactly as Statistics counts them. */
    public record Item(int id, boolean untiered, boolean setTiered, boolean highTier, boolean potion) {}
    /**
     * One saved bag; {@code bag} is the bag name the drop recorded ("White", "B.White", "Orange", …; {@code Tokens.bag} colors it)
     * or null when none was saved (legacy drops); {@code visit} is {@code DropContext.visit} (exact, recorded at drop time) or null.
     */
    public record Bag(String session, long time, boolean white, String bag, VisitRef visit, List<Item> items) {
        public Bag { items = List.copyOf(items); }
    }

    private LootFacts() {}

    /** Off the EDT only. A bag without its item list fails the read instead of counting as empty. */
    public static void read(SessionStore store, String scope, Consumer<Bag> sink) throws IOException {
        read(store, store.catalog(), scope, sink);
    }

    /** As above over a catalog the caller already listed ({@link SessionStore#catalog()}). */
    public static void read(SessionStore store, List<SessionStore.SessionEntry> catalog, String scope, Consumer<Bag> sink) throws IOException {
        try {
            store.read(catalog, scope, "loot", LootDashboard.Drop.class, (session, drop) -> sink.accept(bag(session.id, drop)));
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    static Bag bag(String session, LootDashboard.Drop drop) {
        if (drop.items == null) throw new UncheckedIOException(new IOException("A saved loot bag has no item list; its contents are unknown"));
        List<Item> items = new ArrayList<>(drop.items.size());
        for (LootDashboard.Item item : drop.items) {
            if (item == null) throw new UncheckedIOException(new IOException("A saved loot bag has an unreadable item"));
            items.add(new Item(item.id, item.ut, item.st, item.highTier, item.potion));
        }
        VisitRef visit = drop.context == null ? null : drop.context.visit;
        boolean exact = visit != null && visit.sessionId != null && !visit.sessionId.isEmpty() && visit.visitId != null && !visit.visitId.isEmpty();
        String name = drop.bag == null || drop.bag.isBlank() ? null : drop.bag;   // not recorded: unknown, never guessed
        return new Bag(session, drop.time, LootArchiveAdapter.white(drop.bag), name, exact ? visit : null, items);
    }
}
