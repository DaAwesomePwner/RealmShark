package tomato.gui.security;

import java.util.*;

/** Detached rule result: missing evidence is neither a pass nor a confirmed failure. */
public final class RequirementResult {
    public enum Verdict {
        NOT_EVALUATED("Not evaluated"), PASS("Pass"), BELOW("Below requirements"), UNKNOWN("Unknown");
        public final String label; Verdict(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public enum Kind { FAILURE, UNKNOWN, INFO }
    public static final class Reason {
        public final Kind kind;
        public final String code, message;
        public Reason(Kind kind, String code, String message) { this.kind = kind; this.code = code; this.message = message; }
    }
    public final Verdict verdict;
    public final List<Reason> reasons;
    public final long knownPoints;
    public final Integer requiredPoints;
    public final boolean scoreComplete;
    RequirementResult(Verdict verdict, List<Reason> reasons, long knownPoints, Integer requiredPoints, boolean complete) {
        this.verdict = verdict; this.reasons = Collections.unmodifiableList(new ArrayList<>(reasons));
        this.knownPoints = knownPoints; this.requiredPoints = requiredPoints; scoreComplete = complete;
    }
    public static RequirementResult notEvaluated() {
        return new RequirementResult(Verdict.NOT_EVALUATED, Collections.singletonList(new Reason(Kind.INFO, "no-preset", "Choose a requirements preset to evaluate this roster.")), 0, null, false);
    }
    public boolean nonPassing() { return verdict == Verdict.BELOW || verdict == Verdict.UNKNOWN; }
    public String description() {
        StringBuilder text = new StringBuilder(verdict.label);
        if (requiredPoints != null) text.append(" · ").append(scoreComplete ? "Points " : "Known points (partial) ").append(knownPoints).append(" / ").append(requiredPoints);
        for (Reason reason : reasons) text.append('\n').append(reason.message);
        return text.toString();
    }
}
