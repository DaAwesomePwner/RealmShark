package tomato.history.encounter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Predicate;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;

/**
 * Settings › General › Combat history pruning (spec §8.4, §13 O3). Full detail files ({@value #FULL_DETAIL}) are deleted once
 * older than their days; when summaries have a retention, a recording older than it loses its record, detail and full
 * detail together. A recording's age comes from its record's entry time, else the record file's time; a file without a
 * record is dated by its own time. Only closed sessions this instance can lock are touched: the current session and
 * sessions open in another instance are skipped and counted. Runs on the combat history worker, never the EDT or producer.
 * An archive read pinning a file that is deleted meanwhile fails with its usual retry message.
 */
public final class CombatRetention {
    /** Side-file folder of full combat detail: {@code <session>/combat-full/<SessionStore.checkpointName(recordingId)>.dps}. */
    public static final String FULL_DETAIL = "combat-full";
    private static final long DAY = 86_400_000L;

    /** Deleted files and their bytes, and the sessions with prunable modules that were skipped (current or locked). */
    public record Result(int files, long bytes, int sessionsSkipped) {}

    private CombatRetention() {}

    /** Prunes with {@code values} as of {@code now} (epoch ms); read-only (preview) history prunes nothing. */
    public static Result prune(SessionStore store, CombatSettings.Values values, long now, Cancellation cancel) throws IOException {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Prune history off the EDT");
        Objects.requireNonNull(values, "values"); Objects.requireNonNull(cancel, "cancel");
        if (!store.writable()) return new Result(0, 0, 0);
        long fullBefore = now - values.fullDetailDays() * DAY;
        Long summariesBefore = values.summaryDays() == null ? null : now - values.summaryDays() * DAY;
        int files = 0, skipped = 0; long[] bytes = {0};
        for (SessionStore.SessionEntry entry : store.catalog(cancel)) {
            cancel.check();
            boolean full = entry.modules.contains(FULL_DETAIL);
            boolean summaries = summariesBefore != null && (entry.modules.contains(CombatFacts.RECORDS) || entry.modules.contains(CombatFacts.DETAILS));
            if (!full && !summaries) continue;
            if (entry.id.equals(store.currentId())) { skipped++; continue; }   // never the session being recorded
            Ages ages = new Ages(store.directory().resolve(entry.id).resolve(CombatFacts.RECORDS));
            try {
                // Full detail and details first, records last: until then a record still dates the other files.
                files += delete(store, entry.id, FULL_DETAIL, file -> ages.before(file, fullBefore)
                    || summariesBefore != null && ages.before(file, summariesBefore), bytes);
                if (summariesBefore != null) {
                    files += delete(store, entry.id, CombatFacts.DETAILS, file -> ages.before(file, summariesBefore), bytes);
                    files += delete(store, entry.id, CombatFacts.RECORDS, file -> ages.before(file, summariesBefore), bytes);
                }
            } catch (IOException open) { skipped++; }   // open in another instance (or this one): tried again next time
        }
        return new Result(files, bytes[0], skipped);
    }

    private static int delete(SessionStore store, String session, String module, Predicate<Path> old, long[] bytes) throws IOException {
        Map<Path, Long> matched = new HashMap<>();
        int deleted = store.deleteFiles(session, module, file -> {
            if (!old.test(file)) return false;
            matched.put(file, size(file));
            return true;
        });
        for (Map.Entry<Path, Long> file : matched.entrySet()) if (!Files.exists(file.getKey(), LinkOption.NOFOLLOW_LINKS)) bytes[0] += file.getValue();
        return deleted;
    }

    private static long size(Path file) { try { return Files.size(file); } catch (IOException gone) { return 0; } }

    /** One session's recording ages by file stem ({@code SessionStore.checkpointName(recordingId)}), read once from its records. */
    private static final class Ages {
        private final Path records;
        private Map<String, Long> byStem;

        Ages(Path records) { this.records = records; }

        /** Whether {@code file}'s recording (or, without a record, the file itself) is older than {@code cutoff}. */
        boolean before(Path file, long cutoff) {
            Long time = age(file);
            return time != null && time < cutoff;
        }

        private Long age(Path file) {
            String name = file.getFileName().toString();
            int dot = name.indexOf('.');
            Long recorded = dot > 0 ? ages().get(name.substring(0, dot)) : null;
            return recorded != null ? recorded : modified(file);
        }

        private Map<String, Long> ages() {
            if (byStem != null) return byStem;
            byStem = new HashMap<>();
            if (!Files.isDirectory(records, LinkOption.NOFOLLOW_LINKS)) return byStem;
            try (DirectoryStream<Path> listing = Files.newDirectoryStream(records, "*.json")) {
                for (Path record : listing) {
                    String name = record.getFileName().toString();
                    Long entered = enteredAt(record);
                    Long time = entered != null && entered > 0 ? entered : modified(record);
                    if (time != null) byStem.put(name.substring(0, name.length() - ".json".length()), time);
                }
            } catch (IOException | DirectoryIteratorException unlisted) { /* files are then dated by their own times */ }
            return byStem;
        }

        /** The record's entry time, reading only up to that field; null when absent, not a number or unreadable. */
        private static Long enteredAt(Path record) {
            try (JsonReader reader = new JsonReader(Files.newBufferedReader(record, StandardCharsets.UTF_8))) {
                reader.beginObject();
                while (reader.hasNext()) {
                    if ("enteredAt".equals(reader.nextName()) && reader.peek() == JsonToken.NUMBER) return reader.nextLong();
                    reader.skipValue();
                }
            } catch (IOException | RuntimeException unreadable) { /* dated by its file time */ }
            return null;
        }

        private static Long modified(Path file) {
            try { return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).lastModifiedTime().toMillis(); }
            catch (IOException gone) { return null; }
        }
    }
}
