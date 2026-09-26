package tomato.gui.dps;

import tomato.backend.data.DpsData;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.modern.Evidence;
import tomato.history.link.EncounterContext;
import tomato.history.link.VisitRef;

/**
 * Detached link status of one damage encounter: linked (visit verified at encounter entry), unlinked (a
 * context exists but no verified visit), legacy (recorded before encounter identity) or live (not yet saved).
 * Links are only ever the frozen entry reference; names and nearest times are never used.
 */
public final class EncounterLink {
    public enum State { LINKED, UNLINKED, LEGACY, LIVE }
    public final State state;
    public final VisitRef visit;
    /** Local player object ID verified for this encounter only, or null. */
    public final Integer localObjectId;
    public final Long capturedAt;
    public final boolean imported;
    /** The encounter is still in progress; its link is the entry-frozen one, the recording is not saved yet. */
    public final boolean inProgress;

    private EncounterLink(State state, VisitRef visit, Integer localObjectId, Long capturedAt, boolean imported) {
        this(state, visit, localObjectId, capturedAt, imported, false);
    }
    private EncounterLink(State state, VisitRef visit, Integer localObjectId, Long capturedAt, boolean imported, boolean inProgress) {
        this.state = state; this.visit = visit; this.localObjectId = localObjectId; this.capturedAt = capturedAt; this.imported = imported;
        this.inProgress = inProgress;
    }

    public static EncounterLink live() { return new EncounterLink(State.LIVE, null, null, null, false); }
    /** Live encounter with its entry-frozen context; without one it stays LIVE (identity not known yet). */
    public static EncounterLink live(EncounterContext context) {
        if (context == null) return live();
        return new EncounterLink(context.linked() ? State.LINKED : State.UNLINKED, context.visit, context.localPlayerObjectId, context.capturedAt, false, true);
    }
    public static EncounterLink of(DpsData data, boolean imported) {
        EncounterContext context = data == null ? null : data.getEncounterContext();
        if (context == null) return new EncounterLink(State.LEGACY, null, null, null, imported);
        return new EncounterLink(context.linked() ? State.LINKED : State.UNLINKED, context.visit, context.localPlayerObjectId, context.capturedAt, imported);
    }

    public boolean linked() { return state == State.LINKED && visit != null; }

    public String label() {
        switch (state) {
            case LINKED: return inProgress ? "Linked (live encounter)" : imported ? "Linked (imported; may not resolve here)" : "Linked";
            case UNLINKED: return inProgress ? Evidence.Coverage.UNLINKED + " (live encounter)" : Evidence.Coverage.UNLINKED.toString();
            case LEGACY: return "Legacy · " + Evidence.Coverage.UNLINKED.toString().toLowerCase(java.util.Locale.ROOT);
            default: return "Live";
        }
    }

    /** Full explanation, including why run/Timeline/Resources handoffs are unavailable. */
    public String description() {
        switch (state) {
            case LINKED:
                return "Linked to visit " + visit.visitId + " (session " + visit.sessionId + "), verified at encounter entry"
                    + (capturedAt == null ? "" : " " + DisplayFormat.formatTimestamp(capturedAt)) + ". "
                    + (localObjectId == null ? "Local player row not verified for this encounter." : "Local player row verified: object #" + localObjectId + ".")
                    + (imported ? " Imported file: the session may be absent on this machine; destinations say so instead of substituting another visit." : "")
                    + (inProgress ? " Live encounter: its run record is saved periodically while the visit continues; until it is, destinations show it as unavailable." : "");
            case UNLINKED:
                return "Unlinked: the visit could not be verified when this encounter began (collection paused, capture boundary, failed or partial map data, or no saved history). "
                    + "Run, Timeline and Resources handoffs are unavailable; no visit is matched by name or time."
                    + (localObjectId == null ? "" : " Local player row verified: object #" + localObjectId + ".")
                    + (inProgress ? " This live encounter stays unlinked after it is saved." : "");
            case LEGACY:
                return "Legacy recording without encounter identity. Run, Timeline and Resources handoffs are unavailable; no visit is matched by dungeon name or time.";
            default:
                return "Live encounter: its entry link is shown once the encounter is saved (after leaving the area). Choose a saved encounter to open its run.";
        }
    }
}
