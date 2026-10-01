package tomato.gui.loot.explore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import tomato.gui.stats.LootFacts;
import tomato.history.SessionStamps;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;

/**
 * Every saved loot bag of every readable session, for Explore's dungeon panel, Collection and Item levels. Closed sessions' bags are kept
 * while their loot files are unchanged ({@link SessionStamps}); the current session is read each time. Off the EDT only.
 */
public final class LootCatalog {
    /** How the levels read bags ({@link #over} in production). */
    public interface Reader { List<LootFacts.Bag> bags(Cancellation cancel) throws IOException; }

    private LootCatalog() {}

    /** Reads saved history from {@code stores}; while none is open, every read fails saying so. One reader keeps its own cache. */
    public static Reader over(Supplier<SessionStore> stores) {
        Objects.requireNonNull(stores, "stores");
        SessionStamps<List<LootFacts.Bag>> kept = new SessionStamps<>("loot");
        return cancel -> {
            SessionStore store = stores.get();
            if (store == null) throw new IOException(RunHauls.NOT_OPEN);
            return read(store, kept, cancel);
        };
    }

    static List<LootFacts.Bag> read(SessionStore store, SessionStamps<List<LootFacts.Bag>> kept, Cancellation cancel) throws IOException {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Read history off the EDT");
        List<SessionStore.SessionEntry> catalog = store.catalog(cancel);
        kept.forgetGone(store, catalog);
        List<LootFacts.Bag> all = new ArrayList<>();
        for (SessionStore.SessionEntry entry : catalog) {
            if (!entry.readable()) continue;
            cancel.check();
            all.addAll(kept.get(store, entry.id, stamp -> {
                List<LootFacts.Bag> bags = new ArrayList<>();
                LootFacts.read(store, catalog, entry.id, bags::add);
                return List.copyOf(bags);
            }));
        }
        return all;
    }
}
