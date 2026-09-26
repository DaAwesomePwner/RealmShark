package tomato.bridge;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * BRIDGE-4: read-only reader for saved review journals, independent of delivery. It never touches a
 * BridgeService, transport or settings; it only parses local JSONL files. Review IDs are counters
 * local to one service run, so every restored record is qualified by journal and session.
 */
public final class BridgeJournal {
    public static final String FORMAT = "realmshark.bridge.review";
    public static final int VERSION = 1;
    public static final int MAX_RECORDS = 100_000, MAX_LINE_CHARS = 256 * 1024, MAX_PROBLEMS = 200;
    public static final String UNRECOVERABLE = "Only observations that were written to a review journal can be reopened. Drops seen while no review log was set, "
        + "while the bridge was disabled, after a failed journal write, or in an older backup that rotation replaced were never saved and cannot be recovered.";

    private BridgeJournal() {}

    /** One restored review with its qualified identity. */
    public static final class Entry {
        public final String journal, session, appSession;
        public final long reviewId;
        public final int line;
        public final boolean legacy;
        public final BridgeService.Review review;
        Entry(String journal, String session, String appSession, long reviewId, int line, boolean legacy, BridgeService.Review review) {
            this.journal = journal; this.session = session; this.appSession = appSession; this.reviewId = reviewId; this.line = line; this.legacy = legacy; this.review = review;
        }
        /** Journal file name, writer session and service-local review ID; unique across restarts. */
        public String identity() { return journal + " · " + session + " · review #" + reviewId; }
    }
    public static final class Problem {
        public final String journal; public final int line; public final String reason;
        Problem(String journal, int line, String reason) { this.journal = journal; this.line = line; this.reason = reason; }
        @Override public String toString() { return journal + (line > 0 ? " line " + line : "") + ": " + reason; }
    }
    private static final String NOT_FOUND = " (not found)", UNREADABLE = " (unreadable)";
    public static final class Result {
        public final List<Entry> entries; public final List<Problem> problems; public final List<String> sources;
        public final int malformed, superseded; public final boolean truncated;
        Result(List<Entry> entries, List<Problem> problems, List<String> sources, int malformed, int superseded, boolean truncated) {
            this.entries = Collections.unmodifiableList(entries); this.problems = Collections.unmodifiableList(problems); this.sources = Collections.unmodifiableList(sources);
            this.malformed = malformed; this.superseded = superseded; this.truncated = truncated;
        }
        public Set<String> sessions() { Set<String> result = new LinkedHashSet<>(); for (Entry e : entries) result.add(e.journal + " · " + e.session); return result; }
        /** Journals that were opened and read (including ones with no usable records); missing and unreadable files are not counted. */
        public int journalsRead() { int read = 0; for (String source : sources) if (!source.endsWith(NOT_FOUND) && !source.endsWith(UNREADABLE)) read++; return read; }
        public int journalsMissing() { int missing = 0; for (String source : sources) if (source.endsWith(NOT_FOUND)) missing++; return missing; }
        public int journalsUnreadable() { int unreadable = 0; for (String source : sources) if (source.endsWith(UNREADABLE)) unreadable++; return unreadable; }
        public String summary() {
            StringBuilder text = new StringBuilder();
            int read = journalsRead(), missing = journalsMissing(), unreadable = journalsUnreadable();
            text.append(entries.size()).append(entries.size() == 1 ? " saved review" : " saved reviews").append(" from ").append(sessions().size())
                .append(sessions().size() == 1 ? " session" : " sessions").append(" in ").append(read).append(read == 1 ? " journal" : " journals");
            if (missing > 0) text.append(" · ").append(missing).append(missing == 1 ? " journal not found" : " journals not found");
            if (unreadable > 0) text.append(" · ").append(unreadable).append(unreadable == 1 ? " journal unreadable" : " journals unreadable");
            if (malformed > 0) text.append(" · ").append(malformed).append(malformed == 1 ? " unreadable line" : " unreadable lines");
            if (superseded > 0) text.append(" · ").append(superseded).append(" repeated records replaced by later lines");
            if (truncated) text.append(" · stopped after ").append(MAX_RECORDS).append(" records");
            return text.toString();
        }
    }

    /** The configured review log's rotated backup (older) and current file, in reading order. Missing files are reported, not invented. */
    public static List<Path> configured(BridgeConfig config) {
        if (config == null || config.reviewLog.isEmpty()) return Collections.emptyList();
        Path current = Paths.get(config.reviewLog).toAbsolutePath().normalize();
        return Arrays.asList(current.resolveSibling(current.getFileName() + ".1"), current);
    }

    /** Reads journals in order. Never throws for file content; I/O failures become problems. Call off the EDT. */
    public static Result read(List<Path> journals) {
        List<Entry> entries = new ArrayList<>(); List<Problem> problems = new ArrayList<>(); List<String> sources = new ArrayList<>();
        Map<String, Integer> byIdentity = new HashMap<>();
        int[] malformed = {0}, superseded = {0}; boolean truncated = false;
        Gson gson = new Gson();
        for (Path path : journals) {
            String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
            if (!Files.isRegularFile(path)) { sources.add(name + NOT_FOUND); continue; }
            int lineNumber = 0, legacySegment = 1, read = 0; long lastLegacyId = 0;
            try (BufferedReader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = in.readLine()) != null) {
                    lineNumber++;
                    if (line.trim().isEmpty()) continue;
                    if (entries.size() >= MAX_RECORDS) { truncated = true; break; }
                    if (line.length() > MAX_LINE_CHARS) { problem(problems, malformed, name, lineNumber, "line exceeds " + MAX_LINE_CHARS + " characters"); continue; }
                    JsonObject object;
                    try { JsonElement parsed = JsonParser.parseString(line); if (!parsed.isJsonObject()) throw new JsonParseException("not an object"); object = parsed.getAsJsonObject(); }
                    catch (RuntimeException invalid) { problem(problems, malformed, name, lineNumber, "not a JSON object"); continue; }
                    boolean legacy = !object.has("journal");
                    String session, appSession = null; JsonObject body;
                    if (legacy) body = object;
                    else {
                        if (!FORMAT.equals(string(object, "journal"))) { problem(problems, malformed, name, lineNumber, "unknown journal type"); continue; }
                        JsonElement version = object.get("version");
                        if (version == null || !version.isJsonPrimitive() || !"1".equals(version.getAsString())) { problem(problems, malformed, name, lineNumber, "unsupported journal version " + (version == null ? "(missing)" : version)); continue; }
                        if (!object.has("review") || !object.get("review").isJsonObject()) { problem(problems, malformed, name, lineNumber, "missing review"); continue; }
                        body = object.getAsJsonObject("review");
                        String service = string(object, "service");
                        session = service == null || service.isEmpty() ? "unknown session" : "session " + service.substring(0, Math.min(8, service.length()));
                        appSession = string(object, "appSession");
                        BridgeService.Review review = review(gson, body);
                        if (review == null) { problem(problems, malformed, name, lineNumber, "incomplete review record"); continue; }
                        read++; add(entries, byIdentity, superseded, new Entry(name, session, appSession, review.id, lineNumber, false, review));
                        continue;
                    }
                    BridgeService.Review review = review(gson, body);
                    if (review == null) { problem(problems, malformed, name, lineNumber, "incomplete review record"); continue; }
                    // Legacy records carry no session: a non-increasing counter means the service restarted.
                    if (review.id <= lastLegacyId) legacySegment++;
                    lastLegacyId = review.id;
                    read++; add(entries, byIdentity, superseded, new Entry(name, "legacy run " + legacySegment, null, review.id, lineNumber, true, review));
                }
                sources.add(name + " (" + read + " records)");
            } catch (IOException | UncheckedIOException failure) {
                sources.add(name + UNREADABLE);
                problem(problems, malformed, name, 0, "could not read the file (" + failure.getClass().getSimpleName() + ")");
            }
            if (truncated) break;
        }
        return new Result(entries, problems, sources, malformed[0], superseded[0], truncated);
    }
    private static void add(List<Entry> entries, Map<String, Integer> byIdentity, int[] superseded, Entry entry) {
        Integer previous = byIdentity.get(entry.identity());
        if (previous != null) { entries.set(previous, entry); superseded[0]++; return; }
        byIdentity.put(entry.identity(), entries.size()); entries.add(entry);
    }
    private static void problem(List<Problem> problems, int[] malformed, String journal, int line, String reason) {
        malformed[0]++; if (problems.size() < MAX_PROBLEMS) problems.add(new Problem(journal, line, reason));
    }
    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
    /** Restores a detached review, or null when required fields are missing. Never re-derives outcomes. */
    private static BridgeService.Review review(Gson gson, JsonObject body) {
        try {
            JsonElement id = body.get("id");
            if (id == null || !id.isJsonPrimitive() || !body.has("drop") || !body.get("drop").isJsonObject()) return null;
            BridgePayload.Drop drop = gson.fromJson(body.get("drop"), BridgePayload.Drop.class);
            if (drop == null || drop.item == null || drop.item.rawName == null) return null;
            String status = string(body, "status"), time = string(body, "time");
            if (status == null || time == null) return null;
            return BridgeService.Review.restored(id.getAsLong(), time, drop, status, nonNull(string(body, "detail")), nonNull(string(body, "payload")), string(body, "localChoice"));
        } catch (RuntimeException invalid) { return null; }
    }
    private static String nonNull(String value) { return value == null ? "" : value; }

    /** CSV of restored reviews with their qualified identity; formula-safe and token-free (journals store redacted payloads). */
    public static String csv(List<Entry> entries) {
        StringBuilder out = new StringBuilder("Journal,Session,Review ID,Line,Format,Time (UTC),Item,Item ID,Rarity,Enchants,Character ID,Character name,Dungeon,Outcome,Status,Details\r\n");
        for (Entry e : entries) {
            BridgeService.Review r = e.review; BridgePayload.Drop d = r.drop;
            Object[] cells = {e.journal, e.session, e.reviewId, e.line, e.legacy ? "legacy" : "v" + VERSION, r.time, d.item.rawName, d.item.id, d.item.rarity, d.item.enchants,
                d.characterId, d.characterName, d.dungeon, r.outcome(), r.status, r.detail};
            for (int i = 0; i < cells.length; i++) { if (i > 0) out.append(','); out.append(cell(cells[i])); }
            out.append("\r\n");
        }
        return out.toString();
    }
    private static String cell(Object value) {
        String s = value == null ? "" : String.valueOf(value);
        if (s.matches("(?s)^[=+@\\-\\t\\r].*")) s = "'" + s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
