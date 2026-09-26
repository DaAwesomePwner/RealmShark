package tomato.gui.route;

import tomato.history.archive.ArchiveQuery;
import tomato.history.archive.ArchiveRow;
import tomato.history.link.VisitRef;

import java.util.Objects;

/**
 * Immutable navigation request. All references are optional and exact; a destination must show an
 * explicit unavailable state rather than guess when a reference cannot be resolved. Time bounds are
 * half-open epoch milliseconds [{@code from}, {@code until}). {@code payload} carries a detached,
 * destination-specific value (for example an alert-draft sample) and must never trigger a save.
 */
public final class Route {
    public final Destination destination;
    public final ArchiveQuery<?, ?> query;
    public final VisitRef visit;
    public final ArchiveRow.Ref record;
    public final String recordingId;
    public final Integer localObjectId;
    public final Long from, until;
    public final Object payload;

    private Route(Destination destination, ArchiveQuery<?, ?> query, VisitRef visit, ArchiveRow.Ref record,
                  String recordingId, Integer localObjectId, Long from, Long until, Object payload) {
        this.destination = Objects.requireNonNull(destination, "destination");
        if (from != null && until != null && until < from) throw new IllegalArgumentException("Route bounds are reversed");
        this.query = query; this.visit = visit; this.record = record == null ? null : ArchiveRow.Ref.copyOf(record);
        this.recordingId = recordingId; this.localObjectId = localObjectId;
        this.from = from; this.until = until; this.payload = payload;
    }

    public static Route to(Destination destination) { return new Route(destination, null, null, null, null, null, null, null, null); }

    public Route withQuery(ArchiveQuery<?, ?> value) { return new Route(destination, value, visit, record, recordingId, localObjectId, from, until, payload); }
    public Route withVisit(VisitRef value) { return new Route(destination, query, value, record, recordingId, localObjectId, from, until, payload); }
    public Route withRecord(ArchiveRow.Ref value) { return new Route(destination, query, visit, value, recordingId, localObjectId, from, until, payload); }
    public Route withRecording(String id, Integer localObject) { return new Route(destination, query, visit, record, id, localObject, from, until, payload); }
    public Route withBounds(Long start, Long end) { return new Route(destination, query, visit, record, recordingId, localObjectId, start, end, payload); }
    public Route withPayload(Object value) { return new Route(destination, query, visit, record, recordingId, localObjectId, from, until, value); }

    @Override public String toString() {
        return "Route{" + destination + (visit == null ? "" : " visit=" + visit) + (record == null ? "" : " record=" + record)
            + (recordingId == null ? "" : " recording=" + recordingId) + (from == null && until == null ? "" : " [" + from + "," + until + ")") + "}";
    }
}
