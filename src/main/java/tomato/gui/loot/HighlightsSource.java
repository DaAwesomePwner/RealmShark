package tomato.gui.loot;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import tomato.gui.stats.LootDashboard;
import tomato.gui.stats.LootFacts;
import tomato.history.SessionStamps;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;

/**
 * Loot Highlights' reader (P6a decision "Highlights reads saved history like Home"). Off the EDT only; the view applies the
 * immutable {@link HighlightsModel}.
 * - Saved history (the primary source): one catalog listing per read, then the window's sessions as Home's archive picks them
 *   ({@code HomeArchive}): Today = the sessions overlapping the local day (an import always; a crashed session ended when its
 *   folder was last written) with bags kept to the day; This session = the current session, every bag. "Loot was saved" is
 *   Home's rule too (a bag in the window's sessions), so the tiles and Home's agree to the number and to unknown.
 * - Per-session facts are kept with {@link SessionStamps}: a closed session's bags are reused while the name, size and
 *   modification time of its folder's entries and of its loot folder are unchanged; the current session's journal is read
 *   incrementally. A session is stamped only when it is in the window (or crashed, for its end).
 * - One session degrades alone: a session whose loot cannot be read, or whose folder cannot be stamped, is left out and counted
 *   in {@link HighlightsModel#sessionsSkipped()}, as are unreadable catalog entries that changed during the day (Home's rule; This
 *   session reads only the current session). A history folder that cannot be listed makes the model unavailable with the
 *   failure in words, never a path.
 * - Without a history store the live capture is shown instead ({@link LootDashboard.Feed#snapshot}), labeled "This app run ·
 *   not saved", and partial once the feed holds only its latest 1,000 bags.
 * {@link #revision()} is the feed's bag count, the view's nudge to read again. One read at a time (reads are serialized).
 */
public final class HighlightsSource {
    /** The live capture as Highlights reads it; production wraps the app's {@link LootDashboard.Feed}. */
    interface Live {
        long revision();
        List<LootFacts.Bag> snapshot(String session);
        boolean capped();
    }

    /** The session folders whose files the kept bags come from (besides the session folder's own entries). */
    private static final String[] FOLDERS = {"loot"};
    /** The session name the live projection carries (never shown; the live list is this app run). */
    private static final String LIVE_SESSION = "this-app-run";

    private final Supplier<SessionStore> stores;
    private final Live live;
    private final ZoneId zone;
    private final LongSupplier clock;
    /** Closed sessions' facts, kept while their stamps are unchanged; filled in place by the one reader at a time. */
    private final SessionStamps<Facts> kept = new SessionStamps<>(FOLDERS);
    /** Loot reads from disk (tests: kept sessions are not read again). */
    private final AtomicInteger reads = new AtomicInteger();
    private SessionStore currentStore;
    private SessionStore.JournalCursor currentCursor;
    private final List<LootFacts.Bag> currentBags = new ArrayList<>();

    /**
     * {@code store}: the open history store, or null while none is open (the live feed is shown then); {@code live}: the app's
     * loot feed ({@code LootCapture.get().feed()}); times are bucketed in {@code zone} at {@code clock}'s now.
     */
    public HighlightsSource(Supplier<SessionStore> store, LootDashboard.Feed live, ZoneId zone, LongSupplier clock) {
        this(store, feed(live), zone, clock);
    }

    /** As above over any live source (tests). */
    HighlightsSource(Supplier<SessionStore> store, Live live, ZoneId zone, LongSupplier clock) {
        this.stores = Objects.requireNonNull(store, "store"); this.live = Objects.requireNonNull(live, "live");
        this.zone = Objects.requireNonNull(zone, "zone"); this.clock = Objects.requireNonNull(clock, "clock");
    }

    private static Live feed(LootDashboard.Feed feed) {
        Objects.requireNonNull(feed, "live");
        return new Live() {
            @Override public long revision() { return feed.revision(); }
            @Override public List<LootFacts.Bag> snapshot(String session) { return feed.snapshot(session); }
            @Override public boolean capped() { return feed.capped(); }
        };
    }

    /** The live feed's bag count: it changes when a bag is observed (any thread). */
    public long revision() { return live.revision(); }

    /** The zone the view writes the drops' times in. */
    public ZoneId zone() { return zone; }

    /**
     * The window's highlights from saved history, or from the live capture when no history store is open. Never throws for a
     * history that cannot be read: the model says so ({@link HighlightsModel#unavailable()}, {@link HighlightsModel#sessionsSkipped()}).
     *
     * @throws IllegalStateException on the EDT
     * @throws CancellationException when {@code cancel} is cancelled (nothing read meanwhile is kept for the session being read)
     */
    public synchronized HighlightsModel read(HighlightsModel.Window window, Cancellation cancel) {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Read loot highlights off the EDT");
        Objects.requireNonNull(window, "window"); Objects.requireNonNull(cancel, "cancel");
        cancel.check();
        long now = clock.getAsLong();
        SessionStore store = stores.get();
        if (currentStore != store) { currentStore = store; currentCursor = null; currentBags.clear(); }
        HighlightsModel model = store == null ? live(window, now) : saved(store, window, now, cancel);
        cancel.check();
        return model;
    }

    /** Closed sessions whose facts are kept (tests). */
    int cachedSessions() { return kept.size(); }

    /** Loot read attempts so far, including incremental retries and full-read fallbacks (tests). */
    int sessionReads() { return reads.get(); }

    /** This app run's retained bags (not saved), kept to the window: Today's local day, or every retained bag. */
    private HighlightsModel live(HighlightsModel.Window window, long now) {
        List<LootFacts.Bag> snapshot = live.snapshot(LIVE_SESSION);
        boolean capped = live.capped();
        long[] day = day(now);
        List<LootFacts.Bag> kept = new ArrayList<>();
        for (LootFacts.Bag bag : snapshot) if (window == HighlightsModel.Window.SESSION || bag.time() >= day[0] && bag.time() < day[1]) kept.add(bag);
        return HighlightsModel.of(window, HighlightsModel.Source.LIVE_UNSAVED, kept, !snapshot.isEmpty(), 0, capped, now);
    }

    private HighlightsModel saved(SessionStore store, HighlightsModel.Window window, long now, Cancellation cancel) {
        List<SessionStore.SessionEntry> catalog;
        try { catalog = store.catalog(cancel); }   // listed once; every session read below reuses it
        catch (IOException failure) { return HighlightsModel.unavailable(window, HighlightsModel.Source.SAVED, "Saved history could not be read: " + words(failure), now); }
        kept.forgetGone(store, catalog);   // everything when the store's folder changed, and the sessions that left the catalog
        Map<String, Facts> checked = new HashMap<>();   // this read's facts: each session stamped at most once
        List<SessionStore.Session> sessions = new ArrayList<>();
        int skipped = 0;
        long keepFrom = Long.MIN_VALUE, keepUntil = Long.MAX_VALUE;
        if (window == HighlightsModel.Window.TODAY) {
            long[] day = day(now);
            keepFrom = day[0]; keepUntil = day[1];
            List<SessionStore.SessionEntry> unreadable = new ArrayList<>();
            for (SessionStore.SessionEntry entry : catalog) {
                cancel.check();
                if (!entry.readable()) { unreadable.add(entry); continue; }
                SessionStore.Session session = entry.session();
                Long end = end(store, session, checked);
                if (end == null) { skipped++; continue; }   // a crashed session whose folder cannot be stamped
                if (overlaps(session, end, day[0], day[1])) sessions.add(session);
            }
            skipped += changedSince(store, unreadable, day[0]);
        } else {
            SessionStore.SessionEntry current = null;
            for (SessionStore.SessionEntry entry : catalog) if (entry.id.equals(store.currentId())) current = entry;
            if (current == null || !current.readable())
                return HighlightsModel.unavailable(window, HighlightsModel.Source.SAVED, "This session's saved details could not be read", now);
            sessions.add(current.session());
        }
        List<LootFacts.Bag> inWindow = new ArrayList<>();
        boolean recorded = false;
        for (SessionStore.Session session : sessions) {
            cancel.check();
            List<LootFacts.Bag> bags = loot(store, catalog, session, checked, cancel);
            if (bags == null) { skipped++; continue; }   // this session's loot is unknown; the others still count
            if (!bags.isEmpty()) recorded = true;        // loot was saved in the window's sessions: a count of 0 is a real zero
            for (LootFacts.Bag bag : bags) if (bag.time() >= keepFrom && bag.time() < keepUntil) inWindow.add(bag);
        }
        return HighlightsModel.of(window, HighlightsModel.Source.SAVED, inWindow, recorded, skipped, false, now);
    }

    /** The local day of {@code now}: {from, until} in epoch ms. */
    private long[] day(long now) {
        LocalDate day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        return new long[] {day.atStartOfDay(zone).toInstant().toEpochMilli(), day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()};
    }

    /** Home's rule: imported sessions carry one import time, not their records' span, so they are always read. */
    private static boolean overlaps(SessionStore.Session session, long end, long from, long until) {
        return "Imported".equals(session.version) || session.started < until && (end <= 0 || end >= from);
    }

    /** An earlier launch that never saved its end (a crash); the current session is still open. */
    private static boolean crashed(SessionStore store, SessionStore.Session session) {
        return session.ended <= 0 && !session.id.equals(store.currentId()) && !"Imported".equals(session.version);
    }

    /**
     * When the session ended; 0 while it is open. A crashed session ended when its folder's own entries were last written (Home's
     * rule); null when its folder cannot be stamped.
     */
    private Long end(SessionStore store, SessionStore.Session session, Map<String, Facts> checked) {
        if (!crashed(store, session)) return session.ended;
        Facts facts;
        try { facts = facts(store, session.id, checked); }
        catch (IOException unlisted) { return null; }
        if (facts.end == null) {
            long last = session.started;
            for (SessionStamps.Stamp file : facts.stamp) if (file.name().indexOf('/') < 0) last = Math.max(last, file.modified());
            facts.end = last;
        }
        return facts.end;
    }

    /** The session's saved bags, or null (unknown) when its folder cannot be stamped or its loot cannot be read. */
    private List<LootFacts.Bag> loot(SessionStore store, List<SessionStore.SessionEntry> catalog, SessionStore.Session metadata,
            Map<String, Facts> checked, Cancellation cancel) {
        String session = metadata.id;
        if (session.equals(store.currentId())) return currentLoot(store, catalog, metadata, cancel);
        Facts facts;
        try { facts = facts(store, session, checked); }
        catch (IOException unlisted) { return null; }
        if (facts.loot == null) {
            reads.incrementAndGet();
            List<LootFacts.Bag> bags = new ArrayList<>();
            try { LootFacts.read(store, catalog, session, bag -> { cancel.check(); bags.add(bag); }); }
            catch (IOException | RuntimeException failure) {
                if (failure instanceof CancellationException) throw (CancellationException) failure;
                return null;   // not kept: read again next time
            }
            facts.loot = List.copyOf(bags);
        }
        return facts.loot;
    }

    private List<LootFacts.Bag> currentLoot(SessionStore store, List<SessionStore.SessionEntry> catalog,
            SessionStore.Session session, Cancellation cancel) {
        // Capture appends loot, but an imported/manually restored checkpoint folder must retain full-read semantics.
        if (!Files.isDirectory(store.directory().resolve(session.id).resolve("loot"))) {
            for (int attempt = 0; attempt < 2; attempt++) {
                List<LootFacts.Bag> tail = new ArrayList<>();
                SessionStore.JournalCursor cursor = currentCursor;
                reads.incrementAndGet();
                try {
                    SessionStore.JournalCursor next = LootFacts.readJournalFrom(store, session, cursor, tail::add, cancel);
                    cancel.check();
                    currentBags.addAll(tail); currentCursor = next;
                    return currentBags;
                } catch (CancellationException cancelled) { throw cancelled; }
                catch (IOException | RuntimeException failure) {
                    currentCursor = null; currentBags.clear();
                    if (cursor == null && !(failure instanceof SessionStore.JournalChangedException)) break;
                }
            }
        }
        currentCursor = null; currentBags.clear();
        List<LootFacts.Bag> bags = new ArrayList<>();
        reads.incrementAndGet();
        try {
            LootFacts.read(store, catalog, session.id, bag -> { cancel.check(); bags.add(bag); });
            cancel.check(); return bags;
        } catch (CancellationException cancelled) { throw cancelled; }
        catch (IOException | RuntimeException failure) { return null; }
    }

    /**
     * One session's facts for this read: the current session's are fresh (a null stamp), never kept; a closed session's are kept
     * while its stamp is unchanged, else new empty facts, kept at once and filled as they are read.
     */
    private Facts facts(SessionStore store, String session, Map<String, Facts> checked) throws IOException {
        Facts facts = checked.get(session);
        if (facts != null) return facts;
        facts = kept.get(store, session, Facts::new);
        checked.put(session, facts);
        return facts;
    }

    /**
     * Home's rule for unreadable catalog entries: how many have a file (in the session folder or its loot folder) modified at or
     * after {@code since}, since only those can hold loot from then on. A folder that cannot be listed counts.
     */
    private static int changedSince(SessionStore store, List<SessionStore.SessionEntry> unreadable, long since) {
        int count = 0;
        for (SessionStore.SessionEntry entry : unreadable) {
            List<SessionStamps.Stamp> stamp;
            try { stamp = SessionStamps.stamp(store.directory().resolve(entry.id), FOLDERS); }
            catch (IOException unlisted) { count++; continue; }
            for (SessionStamps.Stamp file : stamp) if (file.modified() >= since) { count++; break; }
        }
        return count;
    }

    /**
     * A failure in words without a path: the root cause's message unless it names a file path (NIO failures carry the absolute
     * history path), else its kind.
     */
    static String words(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() || message.contains("/") || message.contains("\\") ? cause.getClass().getSimpleName() : message;
    }

    /** One session's facts, each read on first use; {@code stamp} is null for the current session. */
    private static final class Facts {
        final List<SessionStamps.Stamp> stamp;
        Long end;
        List<LootFacts.Bag> loot;
        Facts(List<SessionStamps.Stamp> stamp) { this.stamp = stamp; }
    }
}
