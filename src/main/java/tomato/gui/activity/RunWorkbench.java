package tomato.gui.activity;

import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.modern.Evidence;
import tomato.history.link.VisitRef;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Pure, detached presentation model for one exact saved visit, grouped as Outcome, Timing/coverage,
 * Progression and Related evidence. Unknown stays unknown, a confirmed zero stays numeric, and a completion
 * confirmed after the visit ended stays at its observation time, labelled "observed later".
 */
public final class RunWorkbench {
    private RunWorkbench() { }
    public static final String OUTCOME = "Outcome", TIMING = "Timing / coverage", PROGRESSION = "Progression", RELATED = "Related evidence";
    public static final String OBSERVED_LATER = "observed later";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    public static final class Section {
        public final String title;
        public final List<String> lines;
        Section(String title, List<String> lines) { this.title = title; this.lines = Collections.unmodifiableList(new ArrayList<>(lines)); }
    }

    /** Completion evidence was confirmed after the visit had already ended (for example a delayed counter). */
    public static boolean observedLater(ActivityJournal.Visit visit) {
        return visit.completionObservedAt > 0 && visit.ended > 0 && visit.completionObservedAt > visit.ended;
    }

    /** One line describing the recorded outcome evidence at its own observation time. */
    public static String outcomeLine(ActivityJournal.Visit visit, ZoneId zone) {
        String evidence = Objects.toString(visit.completionEvidence, "");
        if (evidence.isEmpty()) return visit.runStatus() + " · completion evidence not observed";
        String at = visit.completionObservedAt > 0 ? time(visit.completionObservedAt, zone) : "unknown time";
        return "Completed · " + evidence + " · evidence observed at " + at
                + (observedLater(visit) ? " (" + OBSERVED_LATER + ": " + DisplayFormat.formatDurationSeconds(visit.completionObservedAt - visit.ended, 1)
                    + " s after the visit ended at " + time(visit.ended, zone) + "; not moved to an invented completion time)" : "");
    }

    public static List<Section> sections(VisitRef ref, ActivityQueries.Row row, ActivityJournal.Visit visit, ZoneId zone) {
        List<Section> result = new ArrayList<>();
        List<String> outcome = new ArrayList<>();
        outcome.add(row.outcome + " · evidence source: " + row.evidence);
        outcome.add(outcomeLine(visit, zone));
        outcome.add("Visit ended: " + (Objects.toString(visit.endReason, "").isEmpty() ? "not recorded" : visit.endReason)
                + " · stored status: " + Objects.toString(visit.status, "Unknown"));
        if (row.outcome == ActivityQueries.Outcome.LEFT) outcome.add("Left without confirmed completion; this is not a recorded failure.");
        result.add(new Section(OUTCOME, outcome));

        List<String> timing = new ArrayList<>();
        timing.add("Entered " + (row.time == null ? "unknown time" : time(row.time, zone)) + " · last seen "
                + (row.end == null ? "unknown time" : time(row.end, zone)) + (visit.ended > 0 ? " · left " + time(visit.ended, zone) : " · exit not recorded"));
        timing.add("Observed span: " + (row.durationMillis == null ? DisplayFormat.UNAVAILABLE : DisplayFormat.formatDurationSeconds(row.durationMillis, 1) + " s")
                + " (entry to last seen; not a verified clear time)");
        timing.add("Capture issues: " + DisplayFormat.formatExact(visit.issues) + " · timing gaps: " + DisplayFormat.formatExact(visit.timingGaps)
                + " · completeness: " + Evidence.Coverage.PARTIAL.toString().toLowerCase(Locale.ROOT) + " or unknown");
        timing.add("Resource samples: " + DisplayFormat.formatExact(visit.resourceTimeline.size()));
        timing.add("Buff coverage: " + DisplayFormat.formatDurationSeconds(visit.conditionObservedMillis, 1) + " s, extra flags "
                + DisplayFormat.formatDurationSeconds(visit.extraConditionObservedMillis, 1) + " s (gaps are unknown, not zero uptime)");
        timing.add("Timeline records omitted by retention: " + DisplayFormat.formatExact(visit.timelineOmitted));
        result.add(new Section(TIMING, timing));

        List<String> progression = new ArrayList<>();
        progression.add("Progress increase within this visit: " + DisplayFormat.formatExact(visit.exaltIncrease)
                + " · item/ability use requests: " + DisplayFormat.formatExact(visit.useRequests) + " (requests, not confirmed actions)");
        progression.add("Realm score: " + number(visit.realmStart) + " → " + number(visit.realmLatest));
        progression.add("Equipment slots observed: " + DisplayFormat.formatExact(visit.equipment.size()) + " · HP range "
                + range(visit.hpMin, visit.hpMax) + " · MP range " + range(visit.mpMin, visit.mpMax));
        result.add(new Section(PROGRESSION, progression));

        List<String> related = new ArrayList<>();
        related.add("Exact reference: " + (ref == null ? Evidence.Coverage.UNLINKED + " (visit ID missing; linked evidence unavailable)"
                : "session " + ref.sessionId + " · visit " + ref.visitId));
        related.add("Recorded players: " + DisplayFormat.formatExact(visit.inspectedPlayerCount) + " last-recorded loadouts"
                + (visit.partyId == null ? " · party not observed" : " · party " + visit.partyId + ", " + number(visit.rosterSize) + " observed members (identity links unverified)"));
        Long damage = visit.damageTracked ? visit.totalDamage : null;
        Double dps = visit.dps(damage);
        related.add("Recorded damage: " + number(damage) + (damage == null ? " (not tracked)" : "") + " · DPS "
                + (dps == null ? DisplayFormat.UNAVAILABLE : DisplayFormat.formatNumber(dps, 0, 1))
                + (visit.firstDamageAt >= 0 && visit.lastDamageAt > visit.firstDamageAt
                    ? " over shared first-to-last hit window " + time(visit.firstDamageAt, zone) + " – " + time(visit.lastDamageAt, zone) : " · hit window unknown"));
        related.add("Timeline, Resources, Inspect and Loot open only this session + visit ID; same-name visits are never substituted.");
        result.add(new Section(RELATED, related));
        return result;
    }

    public static String text(List<Section> sections) {
        StringBuilder text = new StringBuilder();
        for (Section section : sections) {
            if (text.length() > 0) text.append('\n');
            text.append(section.title.toUpperCase(Locale.ROOT)).append('\n');
            for (String line : section.lines) text.append("  ").append(line).append('\n');
        }
        return text.toString();
    }

    static String time(long millis, ZoneId zone) {
        return TIME.format(Instant.ofEpochMilli(millis).atZone(zone == null ? ZoneId.systemDefault() : zone));
    }
    private static String number(Number value) { return value == null ? DisplayFormat.UNAVAILABLE : DisplayFormat.formatExact(value); }
    private static String range(Integer low, Integer high) { return low == null && high == null ? DisplayFormat.UNAVAILABLE : number(low) + "–" + number(high); }
}
