package tomato.gui.history;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import javax.swing.JComponent;
import tomato.gui.kit.FilterBar;
import tomato.history.archive.ArchiveQuery;

/** A module's facet controls for the workspace Filters drawer, plus chips describing the facets that narrow the query. */
public final class ArchiveFilters {
    private static final DateTimeFormatter CHIP_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");
    /** The module's existing facet controls; null keeps the Filters toggle hidden. */
    public final JComponent drawer;
    public final List<FilterBar.ActiveFilter> active;

    public ArchiveFilters(JComponent drawer, List<FilterBar.ActiveFilter> active) {
        this.drawer = drawer;
        this.active = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(active, "active")));
    }

    /** Adds a chip for bounded dates; removing it keeps the zone, time mode and unknown-time choice. */
    public static <F, S extends Enum<S>> void dates(List<FilterBar.ActiveFilter> chips, ArchiveQuery<F, S> query, Consumer<ArchiveQuery<F, S>> changed) {
        ArchiveQuery.Bounds b = query.bounds();
        if (b.from == null && b.until == null) return;
        chips.add(new FilterBar.ActiveFilter(dateLabel(b), () -> changed.accept(query.withBounds(
            new ArchiveQuery.Bounds(null, null, ZoneId.of(b.zone), b.mode, b.includeUnknown)))));
    }

    /** "2026-09-21 00:00 – 2026-09-22 00:00", "From …" or "Until …" in the bounds' own zone (until stays exclusive). */
    public static String dateLabel(ArchiveQuery.Bounds b) {
        ZoneId zone = ZoneId.of(b.zone);
        String from = b.from == null ? null : CHIP_TIME.format(Instant.ofEpochMilli(b.from).atZone(zone));
        String until = b.until == null ? null : CHIP_TIME.format(Instant.ofEpochMilli(b.until).atZone(zone));
        if (from != null && until != null) return from + " – " + until;
        return from != null ? "From " + from : "Until " + until;
    }

    /** "A", "A, B" or "A, B +3": short enough for a chip; the drawer shows the full selection. */
    public static String summary(Collection<?> values) {
        List<String> text = new ArrayList<>();
        for (Object value : values) text.add(String.valueOf(value));
        return text.size() <= 2 ? String.join(", ", text) : text.get(0) + ", " + text.get(1) + " +" + (text.size() - 2);
    }
}
