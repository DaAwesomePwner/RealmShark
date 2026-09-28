package tomato.history.encounter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import util.PropertiesManager;

/**
 * Settings › General › Combat history (spec §8.4). Summaries are always saved; these keys only choose whether the full
 * hit detail is kept too and how long each is kept. Every key is parsed strictly: an absent, empty or unexpected value is
 * that key's default, so an edited properties file can never turn full detail on or shorten retention by accident.
 */
public final class CombatSettings {
    /** "true" keeps the full detail file of each fight; absent (or anything else) is off. */
    public static final String KEEP_FULL_DETAIL = "combat.keepFullDetail";
    /** Days a full detail file is kept: one of {@link #FULL_DETAIL_DAYS_VALUES}; default 30. */
    public static final String FULL_DETAIL_DAYS = "combat.fullDetailDays";
    /** How long summaries are kept: one of {@link #SUMMARY_RETENTION_VALUES}; default {@link #FOREVER}. */
    public static final String SUMMARY_RETENTION = "combat.summaryRetention";
    /** The stored {@link #SUMMARY_RETENTION} value meaning summaries are never pruned. */
    public static final String FOREVER = "all";
    /** The stored values Settings offers, in its order (7 days, 30 days, 90 days, 1 year). */
    public static final List<String> FULL_DETAIL_DAYS_VALUES = List.of("7", "30", "90", "365");
    /** The stored values Settings offers, in its order (Forever, 1 year, 90 days). */
    public static final List<String> SUMMARY_RETENTION_VALUES = List.of(FOREVER, "365", "90");
    public static final int DEFAULT_FULL_DETAIL_DAYS = 30;
    public static final Values DEFAULTS = new Values(false, DEFAULT_FULL_DETAIL_DAYS, null);

    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** @param summaryDays days summaries are kept; null keeps them forever */
    public record Values(boolean keepFullDetail, int fullDetailDays, Integer summaryDays) {}

    private CombatSettings() {}

    /** Reads each key once through read (null means absent). */
    public static Values read(Function<String, String> read) {
        String keep = read.apply(KEEP_FULL_DETAIL), days = read.apply(FULL_DETAIL_DAYS), summaries = read.apply(SUMMARY_RETENTION);
        return new Values("true".equals(keep),
            days != null && FULL_DETAIL_DAYS_VALUES.contains(days) ? Integer.parseInt(days) : DEFAULT_FULL_DETAIL_DAYS,
            summaries != null && SUMMARY_RETENTION_VALUES.contains(summaries) && !FOREVER.equals(summaries) ? Integer.valueOf(summaries) : null);
    }

    /** The saved values now; any thread (PropertiesManager is memory-immediate and synchronized). */
    public static Values current() { return read(PropertiesManager::getProperty); }

    /**
     * Runs listener after each Combat history change, once the new value is saved in memory, so {@link #current()} already
     * returns it. Listeners run synchronously on the thread that made the change (the EDT for Settings) and must only hand
     * off, for example queue pruning on the combat history worker. Safe to call from any thread.
     */
    public static void onChange(Runnable listener) { if (listener != null) listeners.add(listener); }

    /** Removes a listener added with {@link #onChange}; unknown listeners are ignored. */
    public static void removeOnChange(Runnable listener) { listeners.remove(listener); }

    /** Announces a change. Called by Settings › General after it writes one of the keys above; a failing listener does not stop the others. */
    public static void changed() {
        for (Runnable listener : listeners) {
            try { listener.run(); }
            catch (RuntimeException e) { System.err.println("A combat history settings listener failed: " + e); }
        }
    }
}
