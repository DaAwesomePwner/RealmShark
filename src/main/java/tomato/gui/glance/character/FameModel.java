package tomato.gui.glance.character;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import tomato.backend.data.CharacterJournal.CharacterRecord;
import tomato.backend.data.LiveCharacter;
import tomato.gui.kit.DisplayValue;
import tomato.gui.modern.DisplayFormat;

/**
 * Sheet › Fame's view model (pure, no Swing): the three tiles, the readings to chart, and what was left out.
 * - {@code current}: the character's fame now. Live character fame while this exact character (the key's account and character
 *   id) is in game; else the journal's saved fame, stale, with the time the tab words as "as of …"; else unknown.
 * - {@code perHour}: Home's rule (only increases count; divided by the session's own first-to-last reading time; only with at
 *   least ten minutes of readings) for the newest session that qualifies; {@code perHourBasis} is that session (null: no rate),
 *   which the tab words as its sub-line ("this session", "session 4 days ago") and, with its date, in the tooltip.
 * - {@code gained}: every increase between readings within each shown session (decreases, and whatever changed between
 *   sessions while nothing recorded, are not counted), {@code gainedBasis} "over N sessions".
 * Readings are fame estimated from captured experience ({@code Entity.fame}: (exp + 40071) / 2000), not the game's fame stat,
 * so every value built from them is an estimate ("≈"); the live and saved fame are the game's own values.
 */
record FameModel(String key, Current current, DisplayValue perHour, RateBasis perHourBasis, DisplayValue gained, String gainedBasis,
                 List<FameHistory.Session> sessions, int untagged, int unreadable) {
    /** Home's minimum (HomeArchive.RATE_MINIMUM_MILLIS, package-private there): ten minutes of readings, here within one session. */
    static final long RATE_MINIMUM_MILLIS = 10 * 60_000L;
    static final String RATE_UNKNOWN = "Needs 10 minutes of fame readings in one session";
    static final String ESTIMATED = "Estimated from captured experience";
    static final String NO_READINGS = "No fame readings of this character are saved yet";

    /**
     * The Fame tile: {@code value} with, when {@code live}, the subline "Live"; a stale saved value is worded "as of <age>" from
     * {@code savedAt} (epoch ms, 0 unknown) by the tab, so the age advances between reads.
     */
    record Current(DisplayValue value, boolean live, long savedAt) {
        Current { Objects.requireNonNull(value, "value"); }
        static final Current UNKNOWN = new Current(DisplayValue.unknown(SheetModelBuilder.FAME_UNKNOWN), false, 0);
    }

    /**
     * The session a rate comes from: its first reading's time (epoch ms) and whether it is the session recording now. The tab words
     * its age when it shows it, so "session 4 days ago" advances between reads.
     */
    record RateBasis(long start, boolean current) {
        /** "this session" or "the session of 2026-09-24": the full wording, for tooltips. */
        String fullName() { return current ? "this session" : "the session of " + date(start); }
    }

    FameModel {
        Objects.requireNonNull(current, "current"); Objects.requireNonNull(perHour, "perHour"); Objects.requireNonNull(gained, "gained");
        sessions = List.copyOf(sessions);
    }

    /** The model of {@code key} from its readings, the sheet's record of it (null: the journal lacks it) and whoever is in game (null: nobody). */
    static FameModel build(String key, FameHistory.Series series, CharacterRecord record, LiveCharacter.Snapshot live) {
        return build(key, series, current(key, record, live));
    }

    /** As above with the Fame tile already built (the presenter builds it from the sheet's newest build; null: not yet). */
    static FameModel build(String key, FameHistory.Series series, Current current) {
        // FameHistory returns only sessions with readings; a session without any has nothing to chart or count.
        List<FameHistory.Session> sessions = series.sessions().stream().filter(s -> !s.points().isEmpty()).collect(Collectors.toUnmodifiableList());
        DisplayValue perHour = DisplayValue.unknown(RATE_UNKNOWN), gained = DisplayValue.unknown(NO_READINGS);
        RateBasis perHourBasis = null;
        String gainedBasis = null;
        // The newest session with ten minutes of readings, divided by its own reading time only.
        for (int i = sessions.size() - 1; i >= 0; i--) {
            FameHistory.Session session = sessions.get(i);
            List<FameHistory.Point> points = session.points();
            long reading = points.get(points.size() - 1).time() - points.get(0).time();
            if (reading < RATE_MINIMUM_MILLIS) continue;
            perHourBasis = new RateBasis(points.get(0).time(), session.current());
            perHour = DisplayValue.estimate(DisplayFormat.formatInteger(Math.round(gain(points) * 3_600_000.0 / reading)),
                "Fame gained per hour of readings in " + perHourBasis.fullName() + ": increases only, over that session's own reading time. "
                    + ESTIMATED + ".");
            break;
        }
        if (!sessions.isEmpty()) {
            long total = 0;
            for (FameHistory.Session session : sessions) total += gain(session.points());
            gainedBasis = "over " + sessions.size() + (sessions.size() == 1 ? " session" : " sessions");
            gained = DisplayValue.estimate(total > 0 ? "+" + DisplayFormat.formatInteger(total) : DisplayFormat.formatInteger(total),
                "Fame gained between readings within each session; decreases and changes between sessions are not counted. "
                    + ESTIMATED + (series.unreadable() > 0 ? "; saved sessions that could not be read are left out" : "") + ".");
        }
        return new FameModel(key, current == null ? Current.UNKNOWN : current, perHour, perHourBasis, gained, gainedBasis, sessions,
            series.untagged(), series.unreadable());
    }

    /**
     * The Fame tile of {@code key}: live only when the character in game has exactly the key's account and character id and its
     * fame was captured (SheetModelBuilder's identity rule); else the record's saved fame, stale; else unknown. A record of
     * another key is ignored. Any thread; reads only the record's key, fame and lastSeen, which nothing changes after the
     * journal's copy is made.
     */
    static Current current(String key, CharacterRecord record, LiveCharacter.Snapshot live) {
        FameHistory.Ref ref = FameHistory.parse(key);
        boolean playing = ref != null && live != null && ref.account().equals(live.account()) && live.characterId() == ref.characterId();
        if (playing && live.characterFame() != null)
            return new Current(DisplayValue.count(live.characterFame(), "Live character stats", SheetModelBuilder.FAME_UNKNOWN), true, 0);
        if (record != null && Objects.equals(record.key, key) && record.fame != null)
            return new Current(DisplayValue.stale(DisplayFormat.formatInteger(record.fame.longValue()), "Saved in the character journal, last seen "
                + (record.lastSeen > 0 ? DisplayFormat.formatTimestamp(record.lastSeen) : "at an unknown time")), false, Math.max(0, record.lastSeen));
        return Current.UNKNOWN;
    }

    /** This model with another Fame tile; the readings are shared, not copied. */
    FameModel withCurrent(Current next) {
        return new FameModel(key, next, perHour, perHourBasis, gained, gainedBasis, sessions, untagged, unreadable);
    }

    /** Home's gain: the first reading is a baseline; only increases add. */
    static long gain(List<FameHistory.Point> points) {
        long gain = 0;
        for (int i = 1; i < points.size(); i++) gain += Math.max(0, points.get(i).fame() - points.get(i - 1).fame());
        return gain;
    }

    /** A reading's local date, as the chart labels it. */
    static String date(long time) { return DisplayFormat.DATE.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())); }
}
