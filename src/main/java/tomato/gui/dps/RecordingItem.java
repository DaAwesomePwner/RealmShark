package tomato.gui.dps;

import tomato.history.link.VisitRef;

/**
 * One row of the Recordings tab (spec §6.3): a combat recording of this app run's memory, of saved history (its
 * {@code CombatRecord} summary, with or without a full-detail file) or of a user's import, after {@link RecordingsSource}
 * merged the copies of one recording ID. Immutable; built off the EDT. Unknown values are null, never 0 (a saved 0 is a real
 * 0); facts use the damage meter's definitions ({@code CombatSummaries}).
 *
 * @param key              unique in one read: the recording ID; an import's exact bytes ({@code file:<sha-256>}); an
 *                         in-memory recording without an ID {@code entry:<id>}
 * @param recordingId      the recording's own ID; null for legacy imports
 * @param kind             where the row's recording is: this app run, saved history (possibly loaded back into memory), an
 *                         import, or a legacy import without a recording ID
 * @param map              captured MapInfo name; {@code mapName} its display name (null when the recording has no map)
 * @param enteredAt        MAPINFO entry time (epoch ms); null for legacy recordings without context
 * @param startedAt        first tick (epoch ms); {@code elapsedMs} first-to-last tick span; both null when no tick was recorded
 * @param windowSeconds    the meter's first-to-last hit window; null without a positive window
 * @param contributors     players with recorded damage
 * @param totalDamage      all recorded damage on enemies
 * @param localDps         the verified local row's DPS (0 when verified without recorded damage over a known window); null
 *                         with {@code localUnavailable} saying why
 * @param link             from the recording's own fields: its verified visit, else unlinked with an entry context, else legacy
 * @param visit            the exact visit when linked, else null
 * @param session          the saved session holding its record; null when no record was read for it
 * @param summarySaved     whether a saved record was read for it ("No saved summary (yet)" when not)
 * @param fullDetail       its full-detail file: none, present, or pruned (the record says it was kept; the file is gone)
 * @param fullDetailBytes  the full-detail file's size when present, else null
 * @param sameRecordingAs  an import claiming the recording ID of another row: that row's key; else null
 * @param memoryEntryId    the {@link EncounterCatalog.Entry#id} to open when the recording is in memory; else null
 * @param fileName         an import's file name (never a path); else null
 */
public record RecordingItem(String key, String recordingId, Kind kind, String map, String mapName, Long enteredAt, Long startedAt,
                            Long elapsedMs, Double windowSeconds, int contributors, Long totalDamage, Double localDps,
                            String localUnavailable, Link link, VisitRef visit, String session, boolean summarySaved,
                            FullDetail fullDetail, Long fullDetailBytes, String sameRecordingAs, String memoryEntryId, String fileName) {
    public enum Kind { THIS_RUN, SAVED, IMPORTED, LEGACY_IMPORT }
    public enum Link { LINKED, UNLINKED, LEGACY }
    public enum FullDetail { NONE, PRESENT, PRUNED }

    /** {@code RecordedEncounter.unavailableReason()}'s wording for a legacy recording. */
    public static final String LEGACY_LOCAL = "Legacy recording: no verified local-player row was recorded.";
    /** {@code RecordedEncounter.unavailableReason()}'s wording for a recording without a verified local row. */
    public static final String UNVERIFIED_LOCAL = "The local player's row was not verified for this encounter; another player's row is never substituted.";
    /** The local row is verified but the recording has no first-to-last hit window. */
    public static final String NO_WINDOW = "No first-to-last hit window was recorded, so DPS is unknown.";

    /** Whether a copy of this recording is in memory ({@link #memoryEntryId}). */
    public boolean inMemory() { return memoryEntryId != null; }
}
