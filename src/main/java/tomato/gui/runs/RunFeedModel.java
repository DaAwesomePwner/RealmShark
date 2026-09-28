package tomato.gui.runs;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * The run feed as loaded so far: runs grouped by the local day they were entered, newest first (spec §6.3 Feed). Immutable;
 * built off the EDT by {@link RunFeedSource} and applied on the EDT.
 *
 * @param more       saved runs matching the query remain below the loaded ones ("Load more")
 * @param capturedAt when this model was read (the source's clock)
 */
public record RunFeedModel(List<Day> days, boolean more, long capturedAt) {
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH),
        WEEKDAY_YEAR = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH);

    public RunFeedModel { days = days == null ? List.of() : List.copyOf(days); }

    /**
     * One local day of loaded runs. Its counts cover only the loaded runs: {@code continues} says the day goes on below them.
     *
     * @param title      "Today", "Yesterday", or the weekday and date ("Monday 13 January", with the year when not this year's)
     * @param completed  loaded runs with the Completed outcome
     * @param durationMs the sum of the loaded runs' known observed spans (runs without one add nothing)
     * @param continues  the first run below the loaded ones was entered on this day too
     */
    public record Day(LocalDate date, String title, List<RunCardModel> cards, int completed, long durationMs, boolean continues) {
        public Day {
            Objects.requireNonNull(date, "date"); Objects.requireNonNull(title, "title");
            cards = cards == null ? List.of() : List.copyOf(cards);
        }

        /** "Today · 5 runs · 4 completed · 1 h 12 m", plus " · more below" when the day continues past the loaded runs. */
        public String header() {
            return title + " · " + cards.size() + (cards.size() == 1 ? " run" : " runs") + " · " + completed + " completed"
                + (durationMs > 0 ? " · " + duration(durationMs) : "") + (continues ? " · more below" : "");
        }
    }

    /** Every loaded run, newest first. */
    public List<RunCardModel> cards() {
        List<RunCardModel> cards = new ArrayList<>();
        for (Day day : days) cards.addAll(day.cards());
        return Collections.unmodifiableList(cards);
    }

    /**
     * Groups {@code cards} (newest first) by their entry day in {@code zone}, titled against {@code now}'s day there.
     * {@code continuesOn} is the entry day of the first matching run that is not loaded (null when none): the last loaded day
     * continues only when it is that day.
     */
    public static RunFeedModel of(List<RunCardModel> cards, boolean more, LocalDate continuesOn, ZoneId zone, long now) {
        Objects.requireNonNull(zone, "zone");
        LocalDate today = day(now, zone);
        Map<LocalDate, List<RunCardModel>> grouped = new LinkedHashMap<>();
        for (RunCardModel card : cards == null ? List.<RunCardModel>of() : cards)
            grouped.computeIfAbsent(day(card.entered(), zone), key -> new ArrayList<>()).add(card);
        List<Day> days = new ArrayList<>();
        int index = 0;
        for (Map.Entry<LocalDate, List<RunCardModel>> entry : grouped.entrySet()) {
            int completed = 0; long duration = 0;
            for (RunCardModel card : entry.getValue()) {
                if (card.outcome() == RunOutcome.COMPLETED) completed++;
                if (card.durationMs() != null && card.durationMs() > 0) duration += card.durationMs();
            }
            boolean last = ++index == grouped.size();
            days.add(new Day(entry.getKey(), title(entry.getKey(), today), entry.getValue(), completed, duration,
                last && more && entry.getKey().equals(continuesOn)));
        }
        return new RunFeedModel(days, more, now);
    }

    static LocalDate day(long epochMillis, ZoneId zone) { return Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate(); }

    static String title(LocalDate date, LocalDate today) {
        if (date.equals(today)) return "Today";
        if (date.equals(today.minusDays(1))) return "Yesterday";
        return (date.getYear() == today.getYear() ? WEEKDAY : WEEKDAY_YEAR).format(date);
    }

    /** "45 s", "12 m", "2 h", "1 h 12 m" (whole units, rounded down). */
    static String duration(long millis) {
        long minutes = millis / 60_000;
        if (minutes < 1) return millis / 1_000 + " s";
        if (minutes < 60) return minutes + " m";
        return minutes / 60 + " h" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " m");
    }
}
