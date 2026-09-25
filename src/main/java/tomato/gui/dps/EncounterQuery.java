package tomato.gui.dps;

import java.util.*;

/** Local-library predicates; archive adapters can reuse these before paging. */
public final class EncounterQuery {
    public enum Source { ANY, CAPTURED, IMPORTED }
    public final String text, context;
    public final Source source;
    public EncounterQuery(String text, Source source, String context) { this.text = text.trim().toLowerCase(Locale.ROOT); this.source = source; this.context = context; }
    public boolean matches(EncounterCatalog.Entry entry, EncounterSummary summary) {
        if (source == Source.CAPTURED && entry.origin != null || source == Source.IMPORTED && entry.origin == null) return false;
        if (context != null && !context.equals(summary.localContext)) return false;
        return (summary.dungeon + " " + entry.source() + " " + Objects.toString(entry.data.getRecordingId(), "") + " " + entry.id
            + " " + (summary.started == null ? "" : java.time.Instant.ofEpochMilli(summary.started) + " "
                + tomato.gui.modern.DisplayFormat.formatTimestamp(summary.started))).toLowerCase(Locale.ROOT).contains(text);
    }
}
