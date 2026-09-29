package tomato.gui.runs;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import tomato.gui.stats.LootFacts;
import tomato.history.SessionStamps;
import tomato.history.SessionStore;
import tomato.history.encounter.CombatFacts;
import tomato.history.encounter.CombatRecord;
import tomato.history.link.VisitRef;

/**
 * One saved session's facts as the run feed ({@link RunFeedSource}) and the Dungeons cards ({@link DungeonsSource}) read them,
 * so both apply the same rules: the session state {@link RunOutcome#of} takes, loot bags and combat records each read on
 * their own (a module that cannot be read is null, its facts unknown, and named in the issues by the failure's kind). Both
 * keep closed sessions' facts with {@link SessionStamps}. Off the EDT only (the store refuses it).
 */
final class SessionFacts {
    private SessionFacts() {}

    /** {@link CombatFacts#read}'s shape; tests replace it to fail one session's read. */
    @FunctionalInterface interface CombatReader {
        void read(SessionStore store, List<SessionStore.SessionEntry> catalog, String scope, Consumer<CombatRecord> sink) throws IOException;
    }

    /**
     * {ended, current} for {@link RunOutcome#of}, as Home reads its sessions: a session is still open while it is this app
     * run's (or an import that saved no end); one that neither saved its end nor is open ended with the app (a crash). The
     * readers already closed the unfinished runs of sessions that saved their end with the App ended marker.
     */
    static boolean[] state(SessionStore.Session session, boolean current) {
        if (session == null) return new boolean[] {false, false};
        return new boolean[] {session.ended > 0, session.ended <= 0 && (current || "Imported".equals(session.version))};
    }

    /** Every loot bag {@code session} saved, or null (unknown) when its loot cannot be read, which is named in {@code issues}. */
    static List<LootFacts.Bag> loot(SessionStore store, List<SessionStore.SessionEntry> catalog, String session, List<String> issues) {
        List<LootFacts.Bag> bags = new ArrayList<>();
        try { LootFacts.read(store, catalog, session, bags::add); }
        catch (IOException | RuntimeException failure) { unreadable(failure, session, "loot", issues); return null; }
        return bags;
    }

    /**
     * {@code session}'s combat records by exact visit ({@link CombatFacts#byVisit}), or null (unknown) when they cannot be
     * listed, which is named in {@code issues}; a single damaged record is skipped by {@link CombatFacts#read}.
     */
    static Map<VisitRef, List<CombatRecord>> combat(CombatReader reader, SessionStore store, List<SessionStore.SessionEntry> catalog,
                                                   String session, List<String> issues) {
        List<CombatRecord> saved = new ArrayList<>();
        try { reader.read(store, catalog, session, saved::add); }
        catch (IOException | RuntimeException failure) { unreadable(failure, session, CombatFacts.RECORDS, issues); return null; }
        return CombatFacts.byVisit(saved);
    }

    /** Notes a module of {@code session} that could not be read, by the failure's kind (never its message, which may hold a path). */
    static void unreadable(Exception failure, String session, String module, List<String> issues) {
        if (failure instanceof java.util.concurrent.CancellationException) throw (java.util.concurrent.CancellationException) failure;
        issues.add(session + ": " + module + " could not be read (" + failure.getClass().getSimpleName() + ")");
    }
}
