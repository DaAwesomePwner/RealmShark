package tomato.gui.logging;

import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.modern.DisplayFormat;

/** Explanations derived only from the supplied diagnostic revision. */
final class DiagnosticCoverage {
    private DiagnosticCoverage() { }
    static String describe(DiscoveryLog.Snapshot snapshot) {
        long failures = 0, trailing = 0, withheld = 0;
        for (DiscoveryLog.PacketRow row : snapshot.packets) { failures += row.failures; trailing += row.trailing; }
        for (DiscoveryLog.StatRow row : snapshot.stats) withheld += row.withheld;
        String interval = snapshot.events.isEmpty() ? "Not captured: no retained event samples"
            : snapshot.events.get(0).timestamp + " to " + snapshot.events.get(snapshot.events.size() - 1).timestamp + " (UTC)";
        return "Partial diagnostic evidence\nRetained sample interval: " + interval
            + "\nRetained event samples: " + n(snapshot.events.size()) + " / " + n(DiscoveryLog.EVENT_LIMIT)
            + ". Old samples are evicted at the limit; the interval is not continuous coverage."
            + "\nSampling: " + (snapshot.sampleMillis == 0 ? "every observed frame" : "one event per packet type per second, plus important events and errors")
            + "; at most " + DiscoveryLog.DELTA_LIMIT + " stat deltas per event."
            + "\nFrames counted: " + n(snapshot.total)
            + "\nDecode failures: " + n(failures) + " (packet could not be decoded; inspect its field/offset diagnostic)"
            + "\nTrailing-byte frames: " + n(trailing) + " (remaining bytes; not complete field evidence)"
            + "\nSampled-out events: " + n(snapshot.sampledOut)
            + "\nOmitted stat deltas: " + n(snapshot.deltaOmitted) + " (sampling or per-event limit)"
            + "\nWithheld stat observations: " + n(withheld) + " (privacy/allowlist; not a decode failure)"
            + "\nDelta-cache evictions: " + n(snapshot.cacheEvictions) + " (comparison baseline lost; not an event-retention count)"
            + "\nObserver errors: " + n(snapshot.observerErrors)
            + "\nDiagnostic disk drops: " + n(snapshot.diskDropped) + " (writer lifetime; not reset by Clear data)"
            + "\nDiagnostic sample saving: " + (snapshot.saving ? "on" : "off")
            + "\nCollection at this revision: " + (snapshot.enabled ? "on" : "off")
            + "\nOther counters are since diagnostic clear. Collection-off intervals are unobserved, not zero activity."
            + "\nSave diagnostic samples is separate from automatic session history. Queued writes can finish after disabling."
            + (snapshot.writerError.isEmpty() ? "" : "\nDiagnostic writer failed: " + snapshot.writerError)
            + (snapshot.activityWriterError.isEmpty() ? "" : "\nLegacy activity writer failed: " + snapshot.activityWriterError);
    }
    private static String n(long value) { return DisplayFormat.formatInteger(value); }
}
