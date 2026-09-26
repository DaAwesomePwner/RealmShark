package tomato.gui.dps;

import tomato.gui.modern.DisplayFormat;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;

/** Detached description of one library encounter for other modules (for example My Info). No live data. */
public final class RecordedEncounter {
    public final String recordingId, map;
    /** Encounter entry time (epoch ms), or null when unknown. */
    public final Long started;
    public final EncounterLink link;

    RecordedEncounter(String recordingId, String map, Long started, EncounterLink link) {
        this.recordingId = recordingId; this.map = map; this.started = started; this.link = link;
    }

    /** Route to this encounter's verified local-player row, or null when that row is not verified. */
    public Route localRowRoute() {
        return recordingId == null || link.localObjectId == null ? null
            : Route.to(Destination.ENCOUNTER).withRecording(recordingId, link.localObjectId);
    }

    /** Why the verified local row cannot be opened, or null when it can be routed. */
    public String unavailableReason() {
        if (recordingId == null) return "This recording has no recording ID; it cannot be opened by reference.";
        if (link.state == EncounterLink.State.LEGACY) return "Legacy recording: no verified local-player row was recorded.";
        if (link.localObjectId == null) return "The local player's row was not verified for this encounter; another player's row is never substituted.";
        return null;
    }

    @Override public String toString() {
        return map + " · " + (started == null ? "entry time unknown" : DisplayFormat.formatTimestamp(started)) + " · " + link.label()
            + (link.localObjectId == null ? " · local row not verified" : " · local row verified");
    }
}
