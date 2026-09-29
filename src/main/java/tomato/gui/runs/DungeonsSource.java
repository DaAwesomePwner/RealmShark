package tomato.gui.runs;

import java.io.IOException;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import javax.swing.SwingUtilities;
import packets.packetcapture.logger.ActivityJournal;
import tomato.backend.data.DungeonStatData;
import tomato.gui.activity.ActivityQueries;
import tomato.gui.stats.LootFacts;
import tomato.history.SessionStamps;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.encounter.CombatFacts;
import tomato.history.encounter.CombatRecord;
import tomato.history.link.VisitRef;
import tomato.realmshark.ParseDungeon;

/**
 * The Dungeons tab's reader of saved history (spec §6.3 Dungeons): one {@link DungeonCardModel} per canonical dungeon
 * ({@code DungeonStatData.Snapshot.canonicalName}) over the saved dungeon runs ({@link ParseDungeon#isDungeon}, as the run
 * feed keeps them) of every session. Off the EDT only; the EDT applies the immutable {@link DungeonsModel}.
 *
 * <p>Each run is read exactly as the run feed reads it, through {@link RunCardModel#of} with the feed's session state
 * ({@link SessionFacts#state}) and the session's loot bags and combat records joined only by exact {@link VisitRef}, so a card
 * and the feed's cards of its runs agree. A run needs its visit ID to be linked; saved runs without one are left out and
 * named in {@link DungeonsModel#issues()}. Unlike the feed, a run without an entry time still counts (its span is unknown),
 * so its outcome can be Unknown.
 *
 * <p>No archive pin (the cards need no paging, and the pin costs most of the feed's first read): each session's runs are read
 * from the store and folded into per-dungeon partials ({@link DungeonCardModel.Tally}), merged across sessions. A closed
 * session's partials are kept while its stamp is unchanged (the name, size and modification time of every entry of its
 * folder and of its runs, loot and encounters folders, as the feed keeps its facts; {@link SessionStamps}); the current
 * session is read every time. One session degrades alone: when its metadata or its saved runs cannot be read it is left out
 * whole and counted in {@link DungeonsModel#sessionsSkipped()}; when its loot or combat records cannot be read, its completed
 * runs' loot or DPS is unknown with a reason ({@link DungeonCardModel#LOOT_UNREADABLE}, {@link DungeonCardModel#COMBAT_UNREADABLE}).
 * Each is named in the issues by the failure's kind, never a path.
 */
public final class DungeonsSource {
    /** The session folders whose files the kept partials come from (besides the session folder's own entries). */
    private static final String[] FOLDERS = {"runs", "loot", CombatFacts.RECORDS};

    private final SessionStore store;
    private final ZoneId zone;
    private final LongSupplier clock;
    /** Closed sessions' partials, kept while their stamps are unchanged (thread-safe). */
    private final SessionStamps<Partial> kept = new SessionStamps<>(FOLDERS);
    /** Reads one session's combat records ({@link CombatFacts#read}); tests replace it to fail one session's read. */
    private volatile SessionFacts.CombatReader records = CombatFacts::read;
    /** Sessions read from disk rather than kept (tests). */
    private final AtomicInteger reads = new AtomicInteger();

    public DungeonsSource(SessionStore store, ZoneId zone, LongSupplier clock) {
        this.store = Objects.requireNonNull(store, "store"); this.zone = Objects.requireNonNull(zone, "zone");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The zone the view shows the cards' times in. */
    public ZoneId zone() { return zone; }

    /**
     * Every saved dungeon run as cards matching {@code query}, in its order. Closed sessions whose files did not change since
     * the last read are not read again.
     *
     * @throws IllegalStateException on the EDT
     * @throws java.util.concurrent.CancellationException when {@code cancel} is cancelled (nothing read meanwhile is kept
     *         for the session being read)
     * @throws IOException when the history folder cannot be listed
     */
    public DungeonsModel read(DungeonsQuery query, Cancellation cancel) throws IOException {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Read saved dungeon runs off the EDT");
        Objects.requireNonNull(query, "query"); Objects.requireNonNull(cancel, "cancel");
        List<SessionStore.SessionEntry> catalog = store.catalog(cancel);   // listed once for every session read below
        kept.forgetGone(store, catalog);   // everything when the store's folder changed, and the sessions that left the catalog
        Map<String, DungeonCardModel.Tally> dungeons = new HashMap<>();
        Set<String> issues = new LinkedHashSet<>();
        int runs = 0, skipped = 0, unidentified = 0;
        for (SessionStore.SessionEntry entry : catalog) {
            cancel.check();
            Partial partial = partial(catalog, entry, cancel);
            if (partial.skipped()) skipped++;
            runs += partial.runs(); unidentified += partial.unidentified();
            issues.addAll(partial.issues());
            // Merge into fresh tallies: a kept session's partials are never changed.
            for (DungeonCardModel.Tally tally : partial.dungeons().values())
                dungeons.computeIfAbsent(tally.canonical, DungeonCardModel.Tally::new).merge(tally);
        }
        if (unidentified > 0)
            issues.add(unidentified + (unidentified == 1 ? " saved dungeon run" : " saved dungeon runs")
                + " without a visit ID not counted: loot and recordings link only to a run's exact visit ID.");
        List<DungeonCardModel> cards = new ArrayList<>();
        for (DungeonCardModel.Tally tally : dungeons.values()) {
            DungeonCardModel card = DungeonCardModel.of(tally);
            if (query.matches(card)) cards.add(card);
        }
        cards.sort(query.order());
        cancel.check();
        return new DungeonsModel(cards, runs, skipped, List.copyOf(issues), clock.getAsLong());
    }

    /** Closed sessions whose partials are kept (tests). */
    int cachedSessions() { return kept.size(); }

    /** Sessions read from disk so far, the current one each time (tests: kept sessions are not read again). */
    int sessionReads() { return reads.get(); }

    /** Replaces the combat record reader (tests: a session whose combat read fails). */
    void combatReader(SessionFacts.CombatReader reader) { records = Objects.requireNonNull(reader, "reader"); }

    /**
     * One session's partials: kept while a closed session's stamp is unchanged; the current session's are read every time.
     * An unreadable entry, or a folder that cannot be stamped, is skipped and not kept (a cancelled read keeps nothing either).
     */
    private Partial partial(List<SessionStore.SessionEntry> catalog, SessionStore.SessionEntry entry, Cancellation cancel) {
        if (!entry.readable()) return Partial.skipped(entry.id + ": " + entry.error);   // the catalog's words, no path
        try { return kept.get(store, entry.id, stamp -> read(catalog, entry, cancel)); }   // read() itself throws no IOException
        catch (IOException failure) {   // the stamp: deleted or unlistable since the catalog was listed
            List<String> issues = new ArrayList<>();
            SessionFacts.unreadable(failure, entry.id, "session folder", issues);
            return Partial.skipped(issues.get(0));
        }
    }

    /**
     * One session's dungeon runs folded per canonical dungeon. Its saved runs must be readable (else the session is skipped);
     * its loot and combat records are each read on their own, and one that cannot be read is null for {@link RunCardModel#of}
     * (the runs' loot or combat unknown) and named in the issues. Kept with the stamp either way, since reading the same files
     * again fails the same way.
     */
    private Partial read(List<SessionStore.SessionEntry> catalog, SessionStore.SessionEntry entry, Cancellation cancel) {
        reads.incrementAndGet();
        List<String> issues = new ArrayList<>();
        List<ActivityJournal.Visit> visits = new ArrayList<>();
        try {
            store.read(catalog, entry.id, "runs", ActivityJournal.Visit.class, (session, visit) -> { if (ParseDungeon.isDungeon(visit.map)) visits.add(visit); });
        } catch (IOException | RuntimeException failure) {
            SessionFacts.unreadable(failure, entry.id, "runs", issues);
            return Partial.skipped(issues.get(0));
        }
        if (visits.isEmpty()) return new Partial(Map.of(), 0, 0, false, List.of());   // no loot or combat read needed
        cancel.check();
        boolean[] state = SessionFacts.state(entry.session(), entry.id.equals(store.currentId()));
        List<LootFacts.Bag> bags = SessionFacts.loot(store, catalog, entry.id, issues);
        Map<VisitRef, List<LootFacts.Bag>> linked = new HashMap<>();
        if (bags != null) for (LootFacts.Bag bag : bags) if (bag.visit() != null) linked.computeIfAbsent(bag.visit(), v -> new ArrayList<>()).add(bag);
        cancel.check();
        Map<VisitRef, List<CombatRecord>> combat = SessionFacts.combat(records, store, catalog, entry.id, issues);
        Map<String, DungeonCardModel.Tally> dungeons = new HashMap<>();
        int runs = 0, unidentified = 0;
        for (ActivityJournal.Visit visit : visits) {
            if (visit.id == null || visit.id.isEmpty()) { unidentified++; continue; }
            VisitRef ref = new VisitRef(entry.id, visit.id);
            ActivityQueries.Row run = ActivityQueries.visit(visit);
            // RunCardModel.of keeps the bags linked to exactly this run and reads "no bag at all" as a session without loot:
            // pass this run's own bags, or every bag when it has none (a known none), or null / none as the session saved them.
            List<LootFacts.Bag> loot = bags == null || bags.isEmpty() ? bags : linked.getOrDefault(ref, bags);
            RunCardModel card = RunCardModel.of(ref, run.map, RunOutcome.of(visit, state[0], state[1]), run.time == null ? 0 : run.time,
                run.durationMillis, visit.rosterSize, run.progress, combat == null ? null : combat.getOrDefault(ref, List.of()), loot, null);
            dungeons.computeIfAbsent(DungeonStatData.Snapshot.canonicalName(visit.map), DungeonCardModel.Tally::new).add(card);
            runs++;
        }
        return new Partial(dungeons, runs, unidentified, false, List.copyOf(issues));
    }

    /**
     * One session's contribution as last read (a closed session's is kept with its stamp by {@link #kept}): its per-dungeon
     * tallies, its counted runs, its dungeon runs without a visit ID, whether it was left out whole, and why its facts may be
     * partial.
     */
    private record Partial(Map<String, DungeonCardModel.Tally> dungeons, int runs, int unidentified, boolean skipped, List<String> issues) {
        static Partial skipped(String issue) { return new Partial(Map.of(), 0, 0, true, List.of(issue)); }
    }
}
