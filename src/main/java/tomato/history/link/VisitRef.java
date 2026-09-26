package tomato.history.link;

import java.io.Serializable;
import java.util.Objects;

/**
 * Exact reference to one recorded visit: the app session that stored it plus the journal's visit ID.
 * The two identities are separate and both are required. Only producers that observed the visit's
 * boundary create these; never build one from a dungeon name or a nearest timestamp.
 */
public final class VisitRef implements Serializable {
    private static final long serialVersionUID = 1L;

    /** {@code SessionStore.currentId()} when the visit was recorded, not the journal UUID. */
    public final String sessionId;
    /** {@code ActivityJournal.Visit.id}. */
    public final String visitId;

    public VisitRef(String sessionId, String visitId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.visitId = Objects.requireNonNull(visitId, "visitId");
        if (sessionId.isEmpty() || visitId.isEmpty()) throw new IllegalArgumentException("Visit references need both identities");
    }

    @Override public boolean equals(Object other) {
        return other instanceof VisitRef && sessionId.equals(((VisitRef) other).sessionId) && visitId.equals(((VisitRef) other).visitId);
    }
    @Override public int hashCode() { return Objects.hash(sessionId, visitId); }
    @Override public String toString() { return sessionId + "/" + visitId; }
}
