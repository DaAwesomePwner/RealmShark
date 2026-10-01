package tomato.gui.loot.explore;

import java.io.IOException;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Supplier;
import tomato.gui.loot.haul.HaulModel;
import tomato.gui.runs.RunRecapBuilder;
import tomato.gui.runs.RunRecapModel;
import tomato.gui.stats.LootFacts;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.link.VisitRef;

/**
 * What Loot › Explore's Runs level reads, off the EDT: one run's haul, through the run recap's reader so a run's loot reads the
 * same in Explore and in its recap, and the loot saved outside any run.
 */
public final class RunHauls {
    /** At most this many sessions are shown under "Loot outside runs", newest first. */
    public static final int UNLINKED_SESSIONS = 10;
    static final String NOT_OPEN = "Saved history is not open in this app run";

    /** One run's haul, or why it cannot be shown: {@code unavailable} (not in saved history), {@code emptyReason} (no bags). */
    public record RunHaul(VisitRef ref, HaulModel haul, String emptyReason, String unavailable, String dungeon) {
        public RunHaul { Objects.requireNonNull(ref, "ref"); Objects.requireNonNull(haul, "haul"); }
        /** Without a canonical dungeon, leave the dungeon panel unknown; never infer it from the display name. */
        public RunHaul(VisitRef ref, HaulModel haul, String emptyReason, String unavailable) {
            this(ref, haul, emptyReason, unavailable, null);
        }
    }

    /** One saved session's bags that recorded no run, oldest first. */
    public record UnlinkedSession(String sessionId, long started, List<HaulModel.Bag> bags) {
        public UnlinkedSession { Objects.requireNonNull(sessionId, "sessionId"); bags = List.copyOf(bags); }
        public int items() {
            int count = 0;
            for (HaulModel.Bag bag : bags) count += bag.items().size();
            return count;
        }
    }

    /** How the Runs level reads ({@link #over} in production). Off the EDT only. */
    public interface Loader {
        RunHaul run(VisitRef ref, Cancellation cancel) throws IOException;
        List<UnlinkedSession> unlinked(Cancellation cancel) throws IOException;
    }

    private RunHauls() {}

    /** Reads saved history from {@code store}; while none is open, every read fails saying so. */
    public static Loader over(Supplier<SessionStore> store) {
        Objects.requireNonNull(store, "store");
        return new Loader() {
            @Override public RunHaul run(VisitRef ref, Cancellation cancel) throws IOException {
                return of(new RunRecapBuilder(open(store), ZoneId.systemDefault(), System::currentTimeMillis).build(ref, null, cancel));
            }
            @Override public List<UnlinkedSession> unlinked(Cancellation cancel) throws IOException { return RunHauls.unlinked(open(store), cancel); }
        };
    }

    private static SessionStore open(Supplier<SessionStore> store) throws IOException {
        SessionStore open = store.get();
        if (open == null) throw new IOException(NOT_OPEN);
        return open;
    }

    /** A built run recap as a haul: its header facts and its bags, or its unavailable reason. */
    public static RunHaul of(RunRecapModel recap) {
        if (!recap.available()) return new RunHaul(recap.ref(), HaulModel.of(null, List.of()), null, recap.unavailable());
        return new RunHaul(recap.ref(), haul(recap.header(), recap.loot()), recap.loot().reason(), null, recap.header().map());
    }

    /** {@code header} and {@code loot} as a Full haul. */
    public static HaulModel haul(RunRecapModel.Header header, RunRecapModel.Loot loot) {
        HaulModel.Header facts = header == null ? null : new HaulModel.Header(header.mapName(), header.portalId(), header.outcome().label(),
            header.outcome().tone(), header.entered(), header.durationMs(), header.character());
        List<HaulModel.Bag> bags = new ArrayList<>();
        for (RunRecapModel.Loot.Bag bag : loot.bags()) bags.add(new HaulModel.Bag(bag.bag(), bag.time(), bag.dropper(), bag.items()));
        return HaulModel.of(facts, bags);
    }

    /**
     * The newest {@value #UNLINKED_SESSIONS} readable saved sessions holding bags recorded without a run (no drop-time visit),
     * newest first, each session's bags oldest first. Unreadable sessions are skipped; a loot file that cannot be read fails the read.
     */
    public static List<UnlinkedSession> unlinked(SessionStore store, Cancellation cancel) throws IOException {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Read history off the EDT");
        List<SessionStore.SessionEntry> catalog = store.catalog(cancel);
        record Dated(SessionStore.SessionEntry entry, long started) {}
        List<Dated> sessions = new ArrayList<>();
        for (SessionStore.SessionEntry entry : catalog) if (entry.readable()) sessions.add(new Dated(entry, entry.session().started));
        sessions.sort(Comparator.comparingLong(Dated::started).reversed());
        List<UnlinkedSession> result = new ArrayList<>();
        for (Dated dated : sessions) {
            if (result.size() == UNLINKED_SESSIONS) break;
            cancel.check();
            List<LootFacts.Bag> bags = new ArrayList<>();
            LootFacts.read(store, catalog, dated.entry().id, bag -> { if (bag.visit() == null) bags.add(bag); });
            if (bags.isEmpty()) continue;
            bags.sort(Comparator.comparingLong(LootFacts.Bag::time));
            List<HaulModel.Bag> haul = new ArrayList<>();
            for (LootFacts.Bag bag : bags) haul.add(new HaulModel.Bag(bag.bag(), bag.time(), bag.dropper(), bag.items()));
            result.add(new UnlinkedSession(dated.entry().id, dated.started(), haul));
        }
        return result;
    }
}
