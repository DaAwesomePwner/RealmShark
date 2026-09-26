package tomato.gui.dps;

import tomato.gui.modern.DisplayFormat;
import tomato.gui.route.Destination;
import tomato.gui.route.Route;

/** Detached description of one library encounter for other modules (for example My Info). No live data. */
public final class RecordedEncounter {
    public final String recordingId, map;
    /** Encounter entry time (epoch ms), or null when unknown. */
    public final Long started;
    /** Recorded encounter duration (ms), or null when unknown. */
    public final Long elapsed;
    public final EncounterLink link;

    public RecordedEncounter(String recordingId, String map, Long started, Long elapsed, EncounterLink link) {
        this.recordingId = recordingId; this.map = map; this.started = started; this.elapsed = elapsed; this.link = link;
    }

    /** Scope and window text: which recording, when it was entered, how long it lasted and its link. */
    public String scope() {
        return map + " · entered " + (started == null ? "at an unknown time" : DisplayFormat.formatTimestamp(started))
            + " · recorded for " + (elapsed == null ? "an unknown duration" : DisplayFormat.formatDurationSeconds(elapsed, 1) + " s")
            + " · " + link.label() + ". Its DPS uses that recording's first-to-last recorded hit window and the build recorded then.";
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
