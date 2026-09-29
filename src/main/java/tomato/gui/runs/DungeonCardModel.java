package tomato.gui.runs;

import java.util.*;
import tomato.gui.kit.Portals;
import tomato.history.link.VisitRef;

/**
 * One dungeon's card on the Dungeons tab (spec §6.3; user decision 2026-09-28). Immutable; built off the EDT by
 * {@link DungeonsSource} from the dungeon's saved runs, each read exactly as the run feed reads it ({@link RunCardModel#of}:
 * the outcome rule, the observed span, loot bags and combat records linked by exact {@link VisitRef}), so a card and the
 * feed's cards of its runs always agree. Nothing is matched by name or time; runs are grouped by the canonical dungeon name.
 *
 * <p>The rules:
 * <ul>
 * <li><b>Completion</b> = Completed ÷ (Completed + Left + App ended), a fraction 0–1. It is <b>observed</b>
 * ({@link #OBSERVED}): Left means completion unconfirmed. In progress and Unknown runs are not finished; they are counted
 * apart and named in {@code completionReason}, never as 0.</li>
 * <li><b>Average duration</b> is the mean observed span (entry to the last saved observation, not a verified clear time) of
 * the <b>completed</b> runs with a known span ({@code durationRuns} of them).</li>
 * <li><b>Loot per completed run</b> = the items of bags linked to the exact run ({@link #LOOT_CAPTION}), summed over the
 * completed runs whose loot is known ({@code RunCardModel.lootReason == null}; a known none counts as 0) and divided by
 * their number ({@code lootRuns}). Completed runs whose loot is unknown (a session that saved no bag, or whose loot could not
 * be read) are left out and counted in {@code lootExcluded}: the value is then partial (◐, {@link #lootPartial()}).</li>
 * <li><b>Best personal DPS</b> is the highest verified local DPS ({@link RunCardModel.Combat#localDps}, the representative
 * recording's verified local row) of a <b>completed</b> run, with that run, its entry time and recording. A verified player's
 * real zero never wins: a best needs DPS above 0. Equal DPS: the latest entry, then the greater run reference, so partials
 * merge to the same card in any order.</li>
 * </ul>
 * Every unknown value is null with a one-line reason in its {@code …Reason} field (never 0); a known value whose inputs left
 * some completed runs out carries a reason too (partial). A reason is null only when the value covers every run it could.
 *
 * @param canonical    the grouping key, {@code DungeonStatData.Snapshot.canonicalName} of the runs' saved area names
 * @param displayName  the card's title (the canonical name)
 * @param portalId     the portal sprite ({@link Portals#spriteId}), 0 for the kit's placeholder
 * @param visits       every counted saved run of this dungeon, of any outcome
 * @param completionRate    completed ÷ finished (0–1); null without a finished run
 * @param averageDurationMs mean observed span of the completed runs with one; null when none has one
 * @param lootPerCompletedRun null when no completed run's loot is known
 * @param bestLocalDps  null when no completed run has a verified local DPS above 0; {@code bestRun} and {@code bestRecordingId}
 *                      name its run and recording (both null with it)
 * @param bestEntered   that run's entry time (epoch ms, the Best DPS line's date); null without a best run or when the run has
 *                      no entry time (never 0)
 * @param lastVisit     the latest known entry time (epoch ms), a sort key; 0 when no run has one (never shown)
 */
public record DungeonCardModel(String canonical, String displayName, int portalId, int visits, int completed, int left, int appEnded,
                               int inProgress, int unknown, Double completionRate, Long averageDurationMs, int durationRuns,
                               Double lootPerCompletedRun, int lootRuns, int lootExcluded, Double bestLocalDps, VisitRef bestRun,
                               String bestRecordingId, Long bestEntered, String completionReason, String durationReason, String lootReason,
                               String dpsReason, long lastVisit) {
    /** The completion rate's caveat (its tooltip): the rate is a lower bound. */
    public static final String OBSERVED = "Observed: Left and App ended runs may include clears the app did not see.";
    /** The loot line's caption: unlinked bags are not guessed into a run. */
    public static final String LOOT_CAPTION = "bags linked to the exact run";
    public static final String NO_FINISHED_RUN = "No finished run yet (Completed, Left or App ended).";
    public static final String NO_COMPLETED_RUN = "No completed run yet.";
    public static final String NO_OBSERVED_SPAN = "No completed run has an observed span.";
    /** {@link RunCardModel#LOOT_NOT_SAVED} for every completed run of a card. */
    public static final String LOOT_NOT_SAVED = "No loot bag was saved in these completed runs' sessions, so their loot is unknown.";
    /** {@link RunCardModel#LOOT_UNREADABLE} for every completed run of a card. */
    public static final String LOOT_UNREADABLE = "Loot for these completed runs' sessions could not be read.";
    /** {@link RunCardModel#NO_RECORDING} for every completed run of a card. */
    public static final String NO_RECORDING = "No combat recording is linked to these completed runs.";
    /** {@link RunCardModel#COMBAT_UNREADABLE} for every completed run of a card. */
    public static final String COMBAT_UNREADABLE = "Combat records for these completed runs' sessions could not be read.";
    /** {@link RunCardModel#UNVERIFIED_LOCAL} for every completed run of a card. */
    public static final String UNVERIFIED_LOCAL = "The local player's row was not verified in these completed runs' recordings; another player's row is never substituted.";
    /** Every completed run's recording has no timed window, so no DPS can be computed. */
    public static final String NO_WINDOW = "These completed runs' recordings have no timed window, so no DPS is known.";
    /** Every verified completed run recorded no damage for you: a real zero, which is never a best. */
    public static final String NO_DAMAGE = "Your verified row recorded no damage in these completed runs.";

    public DungeonCardModel {
        Objects.requireNonNull(canonical, "canonical");
        displayName = displayName == null || displayName.isBlank() ? RunCardModel.UNKNOWN_AREA : displayName;
    }

    /** A card whose best run's entry time is not known ({@code bestEntered} null): the values as named above. */
    public DungeonCardModel(String canonical, String displayName, int portalId, int visits, int completed, int left, int appEnded,
                            int inProgress, int unknown, Double completionRate, Long averageDurationMs, int durationRuns,
                            Double lootPerCompletedRun, int lootRuns, int lootExcluded, Double bestLocalDps, VisitRef bestRun,
                            String bestRecordingId, String completionReason, String durationReason, String lootReason, String dpsReason,
                            long lastVisit) {
        this(canonical, displayName, portalId, visits, completed, left, appEnded, inProgress, unknown, completionRate, averageDurationMs,
            durationRuns, lootPerCompletedRun, lootRuns, lootExcluded, bestLocalDps, bestRun, bestRecordingId, null, completionReason,
            durationReason, lootReason, dpsReason, lastVisit);
    }

    /** Completed + Left + App ended: the completion rate's denominator. */
    public int finished() { return completed + left + appEnded; }

    /** Loot per completed run is known but leaves out completed runs whose loot is unknown (◐). */
    public boolean lootPartial() { return lootPerCompletedRun != null && lootExcluded > 0; }

    /** A card of {@code runs} (feed cards of one dungeon), in any order. */
    static DungeonCardModel of(String canonical, Collection<RunCardModel> runs) {
        Tally tally = new Tally(canonical);
        for (RunCardModel run : runs) tally.add(run);
        return of(tally);
    }

    /** The card of a tally (one session's runs of this dungeon, or several sessions' merged). */
    static DungeonCardModel of(Tally t) {
        int finished = t.completed + t.left + t.appEnded;
        Double completion = finished == 0 ? null : t.completed / (double) finished;
        String completionReason = excluded(t.inProgress, t.unknown);
        if (completion == null) completionReason = completionReason == null ? NO_FINISHED_RUN : NO_FINISHED_RUN + " " + completionReason;

        Long duration = t.durationRuns == 0 ? null : Math.round(t.durationSum / (double) t.durationRuns);
        String durationReason = t.completed == 0 ? NO_COMPLETED_RUN : t.durationRuns == 0 ? NO_OBSERVED_SPAN
            : t.durationRuns < t.completed ? (t.completed - t.durationRuns) + " of " + t.completed + " completed runs left out (no observed span)." : null;

        int lootExcluded = t.lootNotSaved + t.lootUnreadable;
        Double loot = t.lootRuns == 0 ? null : t.lootSum / (double) t.lootRuns;
        String lootReason;
        if (t.completed == 0) lootReason = NO_COMPLETED_RUN;
        else if (lootExcluded == 0) lootReason = null;
        else {
            String causes = causes(new int[] {t.lootNotSaved, t.lootUnreadable},
                new String[] {"in a session that saved no loot bag", "in sessions that saved no loot bag"},
                new String[] {"whose session's loot could not be read", "whose sessions' loot could not be read"});
            if (loot != null) lootReason = lootExcluded + " of " + t.completed + " completed runs left out (loot unknown): " + causes + ".";
            else if (t.lootUnreadable == 0) lootReason = LOOT_NOT_SAVED;
            else if (t.lootNotSaved == 0) lootReason = LOOT_UNREADABLE;
            else lootReason = "Loot is unknown for every completed run: " + causes + ".";
        }

        int missing = t.dpsNoRecording + t.dpsUnreadable + t.dpsUnverified + t.dpsNoWindow + t.dpsZero;
        String dpsReason;
        if (t.completed == 0) dpsReason = NO_COMPLETED_RUN;
        else if (missing == 0) dpsReason = null;
        else {
            int[] counts = {t.dpsNoRecording, t.dpsUnreadable, t.dpsUnverified, t.dpsNoWindow, t.dpsZero};
            String causes = causes(counts, new String[] {"without a linked recording", "without a linked recording"},
                new String[] {"whose session's combat records could not be read", "whose sessions' combat records could not be read"},
                new String[] {"without your verified row", "without your verified row"},
                new String[] {"whose recording has no timed window", "whose recordings have no timed window"},
                new String[] {"where your verified row recorded no damage", "where your verified row recorded no damage"});
            String single = null;
            String[] singles = {NO_RECORDING, COMBAT_UNREADABLE, UNVERIFIED_LOCAL, NO_WINDOW, NO_DAMAGE};
            for (int i = 0; i < counts.length; i++) if (counts[i] == missing) single = singles[i];
            if (t.bestDps != null) dpsReason = "Best of " + t.dpsRuns + " of " + t.completed + " completed runs; the others: " + causes + ".";
            else dpsReason = single != null ? single : "No completed run has your verified DPS: " + causes + ".";
        }

        int portal = Portals.spriteId(t.canonical);
        return new DungeonCardModel(t.canonical, t.canonical, portal != 0 ? portal : t.portalId, t.visits, t.completed, t.left, t.appEnded,
            t.inProgress, t.unknown, completion, duration, t.durationRuns, loot, t.lootRuns, lootExcluded, t.bestDps,
            t.bestDps == null ? null : t.bestRun, t.bestDps == null ? null : t.bestRecordingId,
            t.bestDps == null || t.bestEntered <= 0 ? null : t.bestEntered, completionReason, durationReason,
            lootReason, dpsReason, t.lastVisit);
    }

    /** "Not counted: 1 in progress, 2 unknown." or null when both are 0. */
    private static String excluded(int inProgress, int unknown) {
        List<String> parts = new ArrayList<>();
        if (inProgress > 0) parts.add(inProgress + " in progress");
        if (unknown > 0) parts.add(unknown + " unknown");
        return parts.isEmpty() ? null : "Not counted: " + String.join(", ", parts) + ".";
    }

    /** "2 in sessions that saved no loot bag, 1 whose session's loot could not be read": {count} {singular | plural phrase}. */
    private static String causes(int[] counts, String[]... phrases) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) if (counts[i] > 0) parts.add(counts[i] + " " + phrases[i][counts[i] == 1 ? 0 : 1]);
        return String.join(", ", parts);
    }

    /**
     * The mergeable counts behind a card: one session's runs of one dungeon ({@link #add}), or several sessions' tallies
     * ({@link #merge}). Mutable and confined to the reading thread; a tally kept for a closed session is only ever read
     * ({@link #merge} changes only the receiver).
     */
    static final class Tally {
        final String canonical;
        private int portalId, visits, completed, left, appEnded, inProgress, unknown;
        private long durationSum, lootSum, lastVisit;
        private int durationRuns, lootRuns, lootNotSaved, lootUnreadable;
        private int dpsRuns, dpsNoRecording, dpsUnreadable, dpsUnverified, dpsNoWindow, dpsZero;
        private Double bestDps;
        private VisitRef bestRun;
        private String bestRecordingId;
        private long bestEntered;

        Tally(String canonical) { this.canonical = Objects.requireNonNull(canonical, "canonical"); }

        int visits() { return visits; }

        /** Counts one run's card; only a completed run adds to the averages, loot and best DPS. */
        void add(RunCardModel run) {
            visits++;
            if (run.entered() > 0) lastVisit = Math.max(lastVisit, run.entered());
            portalId = Math.max(portalId, run.portalId());
            switch (run.outcome()) {
                case COMPLETED -> completed++;
                case LEFT -> left++;
                case APP_ENDED -> appEnded++;
                case IN_PROGRESS -> inProgress++;
                default -> unknown++;
            }
            if (run.outcome() != RunOutcome.COMPLETED) return;
            if (run.durationMs() != null) { durationSum += run.durationMs(); durationRuns++; }
            if (run.lootReason() == null) { lootSum += run.lootCount(); lootRuns++; }
            else if (RunCardModel.LOOT_UNREADABLE.equals(run.lootReason())) lootUnreadable++;
            else lootNotSaved++;
            RunCardModel.Combat combat = run.combat();
            Double dps = combat == null ? null : combat.localDps();
            if (combat == null) { if (RunCardModel.COMBAT_UNREADABLE.equals(run.combatReason())) dpsUnreadable++; else dpsNoRecording++; }
            else if (dps == null) { if (combat.localUnavailable() != null) dpsUnverified++; else dpsNoWindow++; }
            else if (dps.isNaN() || dps.isInfinite()) dpsNoWindow++;   // no usable window: no DPS is known
            else if (dps <= 0) dpsZero++;   // a verified real zero: known, never a best
            else { dpsRuns++; best(dps, run.ref(), combat.recordingId(), run.entered()); }
        }

        /** Adds {@code other}'s counts to this tally; {@code other} is unchanged. */
        void merge(Tally other) {
            visits += other.visits; completed += other.completed; left += other.left; appEnded += other.appEnded;
            inProgress += other.inProgress; unknown += other.unknown;
            portalId = Math.max(portalId, other.portalId); lastVisit = Math.max(lastVisit, other.lastVisit);
            durationSum += other.durationSum; durationRuns += other.durationRuns;
            lootSum += other.lootSum; lootRuns += other.lootRuns; lootNotSaved += other.lootNotSaved; lootUnreadable += other.lootUnreadable;
            dpsRuns += other.dpsRuns; dpsNoRecording += other.dpsNoRecording; dpsUnreadable += other.dpsUnreadable;
            dpsUnverified += other.dpsUnverified; dpsNoWindow += other.dpsNoWindow; dpsZero += other.dpsZero;
            if (other.bestDps != null) best(other.bestDps, other.bestRun, other.bestRecordingId, other.bestEntered);
        }

        /** Keeps the higher DPS; equal DPS: the later entry, then the greater run reference (a total order, so merges agree). */
        private void best(double dps, VisitRef run, String recordingId, long entered) {
            int order = bestDps == null ? 1 : Double.compare(dps, bestDps);
            if (order == 0) order = Long.compare(entered, bestEntered);
            if (order == 0) order = Comparator.comparing((VisitRef ref) -> ref.sessionId).thenComparing(ref -> ref.visitId).compare(run, bestRun);
            if (order > 0) { bestDps = dps; bestRun = run; bestRecordingId = recordingId; bestEntered = entered; }
        }
    }
}
