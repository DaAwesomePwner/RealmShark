package tomato.gui.runs;

import java.util.*;
import tomato.backend.data.DungeonStatData;
import tomato.gui.activity.ActivityQueries;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveQuery;

/**
 * What the run feed shows: saved dungeon runs of all sessions, newest first, narrowed by text, outcomes and one dungeon. The
 * text and a superset of the outcomes go to the Runs archive as its facets ({@link #archiveQuery()}); the exact outcome rule
 * ({@link RunOutcome}, whose App ended the archive does not have) and the dungeon are applied to each projected run
 * ({@link #matches}).
 *
 * <p>The dungeon is a canonical dungeon ({@link #dungeon}: {@code DungeonStatData.Snapshot.canonicalName} of a run's saved
 * area name, the Dungeons cards' key), so a raw alias and its canonical name are one dungeon: a Dungeons card's "Show runs"
 * lists every run the card counted under one filter. The saved area names are never rewritten.
 *
 * @param text     the archive's search text ("" = everything)
 * @param outcomes the outcomes shown; empty = all
 * @param map      the canonical dungeon shown; null = all
 */
public record RunFeedQuery(String text, Set<RunOutcome> outcomes, String map) {
    public RunFeedQuery {
        text = text == null ? "" : text;
        outcomes = outcomes == null || outcomes.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(outcomes));
        map = map == null || map.isBlank() ? null : map;
    }

    /** Every saved run. */
    public static RunFeedQuery all() { return new RunFeedQuery("", Set.of(), null); }

    /**
     * The Runs archive's facets: the archive outcomes that can hold the chosen ones (empty = all). The archive reads a visit
     * the app never closed as left (its session saved its end) or as unfinished (in progress, or unknown without an entry time).
     */
    public ActivityQueries.Filters facets() {
        ActivityQueries.Filters filters = new ActivityQueries.Filters();
        for (RunOutcome outcome : outcomes) switch (outcome) {
            case COMPLETED: filters.outcomes.add(ActivityQueries.Outcome.COMPLETED); break;
            case LEFT: filters.outcomes.add(ActivityQueries.Outcome.LEFT); break;
            case IN_PROGRESS: filters.outcomes.add(ActivityQueries.Outcome.IN_PROGRESS); break;
            case UNKNOWN: filters.outcomes.add(ActivityQueries.Outcome.UNKNOWN); break;
            case APP_ENDED:
                filters.outcomes.add(ActivityQueries.Outcome.LEFT); filters.outcomes.add(ActivityQueries.Outcome.IN_PROGRESS);
                filters.outcomes.add(ActivityQueries.Outcome.UNKNOWN);
                break;
        }
        return filters;
    }

    /** The Runs archive query: all sessions, this text and {@link #facets()}, newest entry first. */
    public ArchiveQuery<ActivityQueries.Filters, ActivityQueries.Sort> archiveQuery() {
        return new ArchiveQuery<>(SessionStore.ALL, ArchiveQuery.Bounds.all(), text, facets(), ActivityQueries.Filters.class, ActivityQueries.Sort.class,
            List.of(new ArchiveQuery.Order<>(ActivityQueries.Sort.TIME, ArchiveQuery.Direction.DESCENDING)));
    }

    /** Whether a run with this outcome (the shared rule) and saved area name is shown: its canonical dungeon is {@link #map}. */
    public boolean matches(RunOutcome outcome, String area) {
        return (outcomes.isEmpty() || outcomes.contains(outcome)) && (map == null || map.equals(dungeon(area)));
    }

    /**
     * The canonical dungeon of a saved area name ({@code DungeonStatData.Snapshot.canonicalName}, as the Dungeons cards group
     * runs), or null for a missing or blank name (no dungeon's).
     */
    public static String dungeon(String area) {
        return area == null || area.isBlank() ? null : DungeonStatData.Snapshot.canonicalName(area);
    }
}
