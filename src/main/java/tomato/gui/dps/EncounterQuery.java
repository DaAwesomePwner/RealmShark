package tomato.gui.dps;

import java.util.*;

/**
 * Recordings' local predicates (Runs & DPS › Recordings; archive adapters can reuse these before paging): a literal,
 * case-insensitive text, a source, a local-context availability and a link state. Sources describe where a recording is or
 * what is kept of it: {@link Source#CAPTURED} this app run's captures, {@link Source#SAVED} a saved summary,
 * {@link Source#FULL_DETAIL} saved full detail, {@link Source#IMPORTED} a user's import. A loaded saved full-detail recording is
 * never an import. The names are stored in the library's view state; older states hold only ANY, CAPTURED and IMPORTED.
 */
public final class EncounterQuery {
    public enum Source { ANY, CAPTURED, SAVED, FULL_DETAIL, IMPORTED }
    public final String text, context;
    public final Source source;
    /** Null matches every link state. */
    public final RecordingItem.Link link;
    public EncounterQuery(String text, Source source, String context) { this(text, source, context, null); }
    public EncounterQuery(String text, Source source, String context, RecordingItem.Link link) {
        this.text = text.trim().toLowerCase(Locale.ROOT); this.source = source; this.context = context; this.link = link;
    }

    /** One library entry in memory (the source by its kind, the context and text from its summary). */
    public boolean matches(EncounterCatalog.Entry entry, EncounterSummary summary) {
        switch (source) {
            case CAPTURED: if (entry.kind() != EncounterCatalog.Kind.CAPTURED) return false; break;
            case SAVED: case FULL_DETAIL: if (entry.kind() != EncounterCatalog.Kind.SAVED) return false; break;
            case IMPORTED: if (!entry.imported()) return false; break;
            default: break;
        }
        if (context != null && !context.equals(summary.localContext)) return false;
        return (summary.dungeon + " " + entry.source() + " " + Objects.toString(entry.data.getRecordingId(), "") + " " + entry.id
            + " " + (summary.started == null ? "" : java.time.Instant.ofEpochMilli(summary.started) + " "
                + tomato.gui.modern.DisplayFormat.formatTimestamp(summary.started))).toLowerCase(Locale.ROOT).contains(text);
    }

    /**
     * One Recordings row: {@code localContext} is the in-memory copy's ("Available", "Partial", "Unavailable"), null when it is
     * not in memory (unknown, so a chosen context never matches it); {@code haystack} is {@link #haystack}'s text for the row.
     */
    public boolean matches(RecordingItem item, String localContext, String haystack) {
        switch (source) {
            case CAPTURED: if (item.kind() != RecordingItem.Kind.THIS_RUN) return false; break;
            case SAVED: if (!item.summarySaved()) return false; break;
            case FULL_DETAIL: if (item.fullDetail() != RecordingItem.FullDetail.PRESENT) return false; break;
            case IMPORTED: if (item.kind() != RecordingItem.Kind.IMPORTED && item.kind() != RecordingItem.Kind.LEGACY_IMPORT) return false; break;
            default: break;
        }
        if (link != null && item.link() != link) return false;
        if (context != null && !context.equals(localContext)) return false;
        return haystack.contains(text);
    }

    /**
     * The lower-case text a row is searched in: its dungeon, map, recording ID, import file name, library entry ID, source label
     * and its entry and first-tick times (ISO and as displayed). Built off the EDT, once per read.
     */
    public static String haystack(RecordingItem item, String entryId, String dungeon, String source) {
        StringBuilder text = new StringBuilder();
        for (String value : new String[]{dungeon, item.mapName(), item.map(), item.recordingId(), item.fileName(), entryId, source})
            if (value != null) text.append(value).append(' ');
        for (Long time : new Long[]{item.enteredAt(), item.startedAt()})
            if (time != null) text.append(java.time.Instant.ofEpochMilli(time)).append(' ').append(tomato.gui.modern.DisplayFormat.formatTimestamp(time)).append(' ');
        return text.toString().toLowerCase(Locale.ROOT);
    }
}
