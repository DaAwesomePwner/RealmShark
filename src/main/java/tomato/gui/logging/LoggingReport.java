package tomato.gui.logging;

import com.google.gson.GsonBuilder;
import packets.packetcapture.logger.DiscoveryLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Logging-local export contract. The manifest is a plain map for a later shared export adapter. */
public final class LoggingReport {
    public enum Source {
        CURRENT("Current fresh diagnostics"), DISPLAYED("Displayed diagnostic revision");
        final String label;
        Source(String label) { this.label=label; }
        @Override public String toString() { return label; }
    }

    public static String revision(DiscoveryLog.Snapshot data) { return data.runId + "@" + data.exportedAt; }

    public static Map<String,Object> manifest(Source source, DiscoveryLog.Snapshot data, Map<String,Object> displayContext) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1); result.put("source", source.name());
        result.put("snapshotRevision", revision(data)); result.put("snapshotTimeUtc", data.exportedAt);
        result.put("captureRunId", data.runId);
        result.put("scope", "All retained diagnostics in this snapshot; display filters are context only and are not applied.");
        result.put("coverage", "Sampled and bounded diagnostic events; counters cover collection-enabled traffic. No saved activity history is included. Diagnostic area IDs are not gameplay visit IDs.");
        result.put("packetCounterRows", data.packets.size()); result.put("statCounterRows", data.stats.size());
        result.put("retainedEventSamples", data.events.size());
        long deltas=0; for (DiscoveryLog.Event e : data.events) deltas += e.statChanges.size();
        result.put("retainedStatSamples", deltas); result.put("observedFrames", data.total);
        result.put("retainedFromUtc", data.events.isEmpty() ? null : data.events.get(0).timestamp);
        result.put("retainedThroughUtc", data.events.isEmpty() ? null : data.events.get(data.events.size()-1).timestamp);
        result.put("displayContext", new LinkedHashMap<>(displayContext));
        return result;
    }

    static String preview(Source source, DiscoveryLog.Snapshot data, Map<String,Object> context) {
        return source.label + "\nSnapshot: " + revision(data) + "\n"
            + data.packets.size() + " packet counter rows · " + data.stats.size() + " stat counter rows · "
            + data.events.size() + " retained event samples\n"
            + "All retained diagnostics; display filters are recorded, not applied.\n"
            + "Sampled, bounded evidence. No saved activity history.\n"
            + "Retained interval (UTC): " + (data.events.isEmpty() ? "No retained samples"
                : data.events.get(0).timestamp + " to " + data.events.get(data.events.size()-1).timestamp)
            + "\n\nRecorded display context (not an export filter): " + context.get("tab")
            + "\n" + context.get("matchingRows") + " matching / " + context.get("availableRows") + " available display rows"
            + "\nDisplay snapshot: " + context.get("snapshotRevision")
            + "\nFilters: " + context.get("filters");
    }

    static Path write(Path directory, Map<String,Object> document) throws IOException {
        Files.createDirectories(directory);
        Path file = Files.createTempFile(directory, "discovery-report-", ".json");
        try {
            Files.write(file, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(document).getBytes(StandardCharsets.UTF_8));
            return file.toAbsolutePath();
        } catch (IOException | RuntimeException error) {
            try { Files.deleteIfExists(file); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    private LoggingReport() {}
}
