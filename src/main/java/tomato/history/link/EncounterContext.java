package tomato.history.link;

import java.io.Serializable;

/**
 * Identity frozen when an encounter was entered. Every field is optional: legacy or ambiguous
 * recordings carry no context and must stay unlinked rather than be matched by name or time.
 */
public final class EncounterContext implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Visit verified at encounter entry, or null. */
    public final VisitRef visit;
    /** Local player's object ID, meaningful only inside this encounter, or null. */
    public final Integer localPlayerObjectId;
    /** Producer time the context was captured (epoch ms). */
    public final long capturedAt;

    public EncounterContext(VisitRef visit, Integer localPlayerObjectId, long capturedAt) {
        this.visit = visit;
        this.localPlayerObjectId = localPlayerObjectId;
        this.capturedAt = capturedAt;
    }

    public boolean linked() { return visit != null; }
}
