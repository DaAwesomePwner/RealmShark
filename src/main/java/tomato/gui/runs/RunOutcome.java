package tomato.gui.runs;

import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.kit.Tokens;

/**
 * One outcome rule for a saved run, shared by the run feed, the run recap and Home. Completed, Left and In progress follow the
 * Runs archive's evidence ({@code ActivityQueries.visit(v).outcome}); App ended is a visit the app never closed because its
 * session ended first — one that saved its end, or an earlier launch that never saved one (the app crashed or was killed).
 * Home has shown such a visit as closed since P2, never as in progress. The archive Table view keeps its own wording.
 */
public enum RunOutcome {
    COMPLETED("Completed", Tokens.Tone.GOOD),
    LEFT("Left", Tokens.Tone.NEUTRAL),
    IN_PROGRESS("In progress", Tokens.Tone.ACCENT),
    APP_ENDED("App ended", Tokens.Tone.NEUTRAL),
    UNKNOWN("Unknown", Tokens.Tone.NEUTRAL);

    /**
     * The end reason the readers write when they close a visit its session left open: {@code SessionStore} and the archive pin
     * for sessions that saved their end, Home for crashed sessions, and a restored journal. The live journal never writes it.
     */
    public static final String APP_ENDED_REASON = "App ended";

    private final String label;
    private final Tokens.Tone tone;

    RunOutcome(String label, Tokens.Tone tone) { this.label = label; this.tone = tone; }

    /** The short chip text: "Completed", "Left", "In progress", "App ended", "Unknown". */
    public String label() { return label; }
    /** The chip and card-edge tone. */
    public Tokens.Tone tone() { return tone; }

    /**
     * The outcome of a saved visit, as read or already closed by a reader with {@link #APP_ENDED_REASON}. Completion evidence wins
     * in every session state. A session that neither saved its end nor is current is an earlier launch that never saved its end:
     * as on Home, its unfinished visits ended with the app. No visit (null), or an unfinished current visit without a start time,
     * is Unknown, as the archive reads it.
     *
     * @param sessionEnded   the visit's session saved its end ({@code SessionStore.Session.ended > 0})
     * @param sessionCurrent the visit's session is this app run's, still recording
     */
    public static RunOutcome of(ActivityJournal.Visit v, boolean sessionEnded, boolean sessionCurrent) {
        if (v == null) return UNKNOWN;
        if (v.completionEvidence != null && !v.completionEvidence.isEmpty() || "Completed".equals(v.status)) return COMPLETED;
        if (v.ended > 0) return APP_ENDED_REASON.equals(v.endReason) ? APP_ENDED : LEFT;
        // Unfinished: in progress only while this app run records it; a saved end wins, as the store then closes the visit.
        if (sessionEnded || !sessionCurrent) return APP_ENDED;
        return v.started == 0 ? UNKNOWN : IN_PROGRESS;
    }
}
