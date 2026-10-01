package tomato.history;

import packets.packetcapture.logger.ActivityJournal;

/** Saved-run outcome evidence, independent of presentation colors and Swing. */
public enum RunOutcomeRule {
    COMPLETED("Completed"), LEFT("Left"), IN_PROGRESS("In progress"), APP_ENDED("App ended"), UNKNOWN("Unknown");
    public final String label;
    RunOutcomeRule(String label) { this.label = label; }
    public static RunOutcomeRule of(ActivityJournal.Visit v, boolean sessionEnded, boolean sessionCurrent) {
        if (v == null) return UNKNOWN;
        if (v.completionEvidence != null && !v.completionEvidence.isEmpty() || "Completed".equals(v.status)) return COMPLETED;
        if (v.ended > 0) return APP_ENDED.label.equals(v.endReason) ? APP_ENDED : LEFT;
        if (sessionEnded || !sessionCurrent) return APP_ENDED;
        return v.started == 0 ? UNKNOWN : IN_PROGRESS;
    }
}
