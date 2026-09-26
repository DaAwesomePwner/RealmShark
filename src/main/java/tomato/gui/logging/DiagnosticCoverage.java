package tomato.gui.logging;

import packets.packetcapture.logger.DiscoveryCatalog;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.modern.DisplayFormat;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explanations derived only from the supplied diagnostic revision. */
final class DiagnosticCoverage {
    private DiagnosticCoverage() { }
    static String describe(DiscoveryLog.Snapshot snapshot) {
        long failures = 0, trailing = 0, withheld = 0;
        for (DiscoveryLog.PacketRow row : snapshot.packets) { failures += row.failures; trailing += row.trailing; }
        for (DiscoveryLog.StatRow row : snapshot.stats) withheld += row.withheld;
        String interval = snapshot.retainedFirst == null ? "Not captured: no retained event samples"
            : Instant.ofEpochMilli(snapshot.retainedFirst) + " to " + Instant.ofEpochMilli(snapshot.retainedLast) + " (UTC)";
        return "Partial diagnostic evidence\nRetained sample interval: " + interval
            + "\nRetained event samples: " + n(snapshot.events.size()) + " / " + n(DiscoveryLog.EVENT_LIMIT)
            + ". Old samples are evicted at the limit; the interval is not continuous coverage."
            + "\nRetention evictions: " + n(snapshot.retentionEvictions) + (snapshot.retentionEvictions == 0
                ? " (no retained sample has been dropped)"
                : " (older samples were dropped at the limit; evidence before the first retained sample is lost, not absent)")
            + "\nCollection: " + collection(snapshot)
            + transitions(snapshot)
            + "\nSampling: " + (snapshot.sampleMillis == 0 ? "every observed frame" : "one event per packet type per second, plus important events and errors")
            + "; at most " + DiscoveryLog.DELTA_LIMIT + " stat deltas per event."
            + "\nFrames counted: " + n(snapshot.total)
            + "\nDecode failures: " + n(failures) + " (packet could not be decoded; inspect its field/offset diagnostic)"
            + "\nTrailing-byte frames: " + n(trailing) + " (remaining bytes; not complete field evidence)"
            + affected(snapshot)
            + "\nSampled-out events: " + n(snapshot.sampledOut)
            + "\nOmitted stat deltas: " + n(snapshot.deltaOmitted) + " (sampling or per-event limit)"
            + "\nWithheld stat observations: " + n(withheld) + " (privacy/allowlist; not a decode failure)"
            + "\nDelta-cache evictions: " + n(snapshot.cacheEvictions) + " (comparison baseline lost; not an event-retention count, see Retention evictions)"
            + "\nObserver errors: " + n(snapshot.observerErrors)
            + "\nDiagnostic disk drops: " + n(snapshot.diskDropped) + " (writer lifetime; not reset by Clear data)"
            + "\nDiagnostic sample saving: " + (snapshot.saving ? "on" : "off")
            + "\nCollection at this revision: " + state(snapshot.enabled)
            + "\nOther counters are since diagnostic clear. Intervals while collection is paused are unobserved, not zero activity."
            + "\nSave diagnostic samples is separate from automatic session history. Queued writes can finish after disabling."
            + (snapshot.writerError.isEmpty() ? "" : "\nDiagnostic writer failed: " + snapshot.writerError)
            + (snapshot.activityWriterError.isEmpty() ? "" : "\nLegacy activity writer failed: " + snapshot.activityWriterError)
            + (snapshot.coverageError.isEmpty() ? "" : "\n" + snapshot.coverageError);
    }
    /** The one collection-state term used in Logging: "on" or "paused". */
    static String state(boolean collecting) { return collecting ? "on" : "paused"; }
    private static String collection(DiscoveryLog.Snapshot snapshot) {
        if (!snapshot.enabled) return "paused; frames are not observed while paused, which is not zero activity";
        return snapshot.observedSince == null ? "on; no frame observed since it started"
            : "on; observing since " + Instant.ofEpochMilli(snapshot.observedSince) + " (UTC)";
    }
    private static String transitions(DiscoveryLog.Snapshot snapshot) {
        if (snapshot.transitions.isEmpty()) return "\nCollection changes: none since launch";
        StringBuilder text = new StringBuilder("\nRecent collection changes (latest " + DiscoveryLog.TRANSITION_LIMIT + " kept):");
        for (DiscoveryLog.Transition change : snapshot.transitions)
            text.append("\n  ").append(Instant.ofEpochMilli(change.time)).append(" · ")
                .append("Collection: ").append(state(change.collecting)).append(" · ").append(change.reason);
        return text.toString();
    }
    /** Views whose inputs had decode or trailing-byte issues, via the catalog's reviewed allowlist. */
    static String affected(DiscoveryLog.Snapshot snapshot) {
        Map<String, Long> issues = new LinkedHashMap<>();
        for (String[] view : DiscoveryCatalog.AFFECTED_VIEWS) issues.put(view[1], 0L);
        for (DiscoveryLog.PacketRow row : snapshot.packets) {
            long count = row.failures + row.trailing;
            if (count > 0) for (String view : DiscoveryCatalog.affectedViews(row.name)) issues.put(view, issues.get(view) + count);
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Long> entry : issues.entrySet()) if (entry.getValue() > 0)
            text.append(text.length() == 0 ? "" : "; ").append(entry.getKey()).append(" (").append(n(entry.getValue())).append(" frames)");
        return "\nViews affected by decode issues: " + (text.length() == 0 ? "none observed"
            : text + "; these views may be incomplete for the affected areas");
    }
    private static String n(long value) { return DisplayFormat.formatInteger(value); }
}
