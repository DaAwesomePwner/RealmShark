package tomato.gui.dps;

import java.util.*;

/**
 * What the Recordings tab lists ({@link RecordingsSource#read}). {@code text} is a literal, case-insensitive search of the map
 * names, recording ID and import file name; {@code kinds} empty lists every kind; {@code link} null lists every link state.
 * {@link Scope#LAST_30_DAYS} (the default) limits saved records to those entered in the last 30 days of the source's clock;
 * rows in memory (this app run and imports) are listed in both scopes.
 */
public record RecordingsQuery(String text, Scope scope, Set<RecordingItem.Kind> kinds, RecordingItem.Link link) {
    public enum Scope { LAST_30_DAYS, ALL }

    public RecordingsQuery {
        text = text == null ? "" : text;
        scope = scope == null ? Scope.LAST_30_DAYS : scope;
        kinds = kinds == null || kinds.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(kinds));
    }

    /** No text, the last 30 days, every kind and link. */
    public static RecordingsQuery defaults() { return new RecordingsQuery("", Scope.LAST_30_DAYS, Set.of(), null); }

    /** Whether {@code item} matches the text, kinds and link (the scope is the source's). */
    public boolean matches(RecordingItem item) {
        if (!kinds.isEmpty() && !kinds.contains(item.kind())) return false;
        if (link != null && link != item.link()) return false;
        String needle = text.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return true;
        StringBuilder haystack = new StringBuilder();
        for (String value : new String[]{item.mapName(), item.map(), item.recordingId(), item.fileName()}) if (value != null) haystack.append(value).append('\n');
        return haystack.toString().toLowerCase(Locale.ROOT).contains(needle);
    }
}
