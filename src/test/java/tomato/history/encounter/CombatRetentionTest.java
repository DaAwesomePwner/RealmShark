package tomato.history.encounter;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import static org.junit.Assert.*;

/** Combat history pruning: full detail after its days, summaries only when a retention is set; closed, unlocked sessions only. */
public class CombatRetentionTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static final long DAY = 86_400_000L, NOW = 1_800_000_000_000L;
    private static final String CLOSED = HomeHistoryFixture.id("combat-closed");

    private Path root;

    @Before public void setUp() throws Exception {
        root = temp.newFolder("history").toPath();
        HomeHistoryFixture.session(root, CLOSED, NOW - 500 * DAY, NOW - 499 * DAY);
        saved(CLOSED, "fresh", NOW - 10 * DAY);
        saved(CLOSED, "old", NOW - 40 * DAY);
        saved(CLOSED, "ancient", NOW - 400 * DAY);
        // No entry time: the record's file time dates the record, its detail and its full detail.
        Path unknown = saved(CLOSED, "unknown", null);
        Files.setLastModifiedTime(unknown, FileTime.fromMillis(NOW - 100 * DAY));
        // Full detail without a record: its own file time.
        Files.setLastModifiedTime(full(CLOSED, "orphan-old"), FileTime.fromMillis(NOW - 40 * DAY));
        Files.setLastModifiedTime(full(CLOSED, "orphan-new"), FileTime.fromMillis(NOW - DAY));
    }

    /** A record, its detail and its full-detail file; returns the record's file. */
    private Path saved(String session, String id, Long enteredAt) throws Exception {
        CombatRecord record = new CombatRecord(); record.recordingId = id; record.enteredAt = enteredAt;
        CombatDetail detail = new CombatDetail(); detail.recordingId = id;
        Path file = CombatFixtures.writeRecord(root, session, record);
        CombatFixtures.writeDetail(root, session, detail);
        full(session, id);
        return file;
    }
    private Path full(String session, String id) throws Exception {
        Path folder = Files.createDirectories(root.resolve(session).resolve(CombatRetention.FULL_DETAIL));
        return Files.write(folder.resolve(SessionStore.checkpointName(id) + ".dps"), new byte[100]);
    }
    private Set<String> left(String session, String module) throws Exception {
        Path folder = root.resolve(session).resolve(module);
        if (!Files.isDirectory(folder)) return Set.of();
        Map<String, String> names = new HashMap<>();
        for (String id : List.of("fresh", "old", "ancient", "unknown", "orphan-old", "orphan-new", "current", "locked"))
            names.put(SessionStore.checkpointName(id), id);
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(f -> f.getFileName().toString()).map(n -> names.getOrDefault(n.substring(0, n.indexOf('.')), n))
                .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    @Test public void fullDetailIsPrunedByAgeAndSummariesAreKeptForever() throws Exception {
        try (SessionStore store = new SessionStore(root, true, "synthetic")) {
            CombatRetention.Result result = CombatRetention.prune(store, new CombatSettings.Values(false, 30, null), NOW, new Cancellation());
            assertEquals("Entry time decides; without one the record's file time; without a record the file's own",
                Set.of("fresh", "orphan-new"), left(CLOSED, CombatRetention.FULL_DETAIL));
            assertEquals(new CombatRetention.Result(4, 400, 0), result);
            assertEquals("Forever keeps every summary", Set.of("fresh", "old", "ancient", "unknown"), left(CLOSED, CombatFacts.RECORDS));
            assertEquals(Set.of("fresh", "old", "ancient", "unknown"), left(CLOSED, CombatFacts.DETAILS));
        }
    }

    @Test public void summaryRetentionPrunesARecordWithItsDetailAndFullDetail() throws Exception {
        try (SessionStore store = new SessionStore(root, true, "synthetic")) {
            CombatRetention.Result result = CombatRetention.prune(store, new CombatSettings.Values(true, 365, 90), NOW, new Cancellation());
            assertEquals(Set.of("fresh", "old"), left(CLOSED, CombatFacts.RECORDS));
            assertEquals(Set.of("fresh", "old"), left(CLOSED, CombatFacts.DETAILS));
            assertEquals("Summary retention also bounds full detail", Set.of("fresh", "old", "orphan-old", "orphan-new"),
                left(CLOSED, CombatRetention.FULL_DETAIL));
            assertEquals("ancient and unknown: record, detail and full detail each", 6, result.files());
            assertEquals(0, result.sessionsSkipped());
            List<CombatRecord> read = new ArrayList<>();
            CombatFacts.read(store, store.catalog(), CLOSED, read::add);
            assertEquals(2, read.size());
        }
    }

    @Test public void theCurrentSessionAndSessionsOpenElsewhereAreSkipped() throws Exception {
        try (SessionStore store = new SessionStore(root, true, "synthetic"); SessionStore other = new SessionStore(root, true, "synthetic")) {
            for (SessionStore s : List.of(store, other)) {
                CombatRecord record = new CombatRecord(); record.recordingId = s == store ? "current" : "locked"; record.enteredAt = NOW - 400 * DAY;
                s.put(CombatFacts.RECORDS, record.recordingId, record);
                s.flush();
                Path folder = Files.createDirectories(s.currentDirectory().get().resolve(CombatRetention.FULL_DETAIL));
                Files.write(folder.resolve(SessionStore.checkpointName(record.recordingId) + ".dps"), new byte[10]);
            }
            CombatRetention.Result result = CombatRetention.prune(store, new CombatSettings.Values(false, 7, 90), NOW, new Cancellation());
            assertEquals("This app run's session and another instance's are never touched", 2, result.sessionsSkipped());
            assertEquals(Set.of("current"), left(store.currentId(), CombatFacts.RECORDS));
            assertEquals(Set.of("current"), left(store.currentId(), CombatRetention.FULL_DETAIL));
            assertEquals(Set.of("locked"), left(other.currentId(), CombatFacts.RECORDS));
            assertEquals(Set.of("locked"), left(other.currentId(), CombatRetention.FULL_DETAIL));
            assertEquals("The closed session is pruned meanwhile", Set.of("orphan-new"), left(CLOSED, CombatRetention.FULL_DETAIL));
            String locked = other.currentId();
            other.close();
            CombatRetention.prune(store, new CombatSettings.Values(false, 7, 90), NOW, new Cancellation());
            assertEquals("Once closed it is pruned like any other", Set.of(), left(locked, CombatFacts.RECORDS));
            assertEquals(Set.of(), left(locked, CombatRetention.FULL_DETAIL));
        }
    }

    @Test public void previewPrunesNothingAndTheEventThreadIsRefused() throws Exception {
        try (SessionStore preview = new SessionStore(root, false, "synthetic")) {
            assertEquals(new CombatRetention.Result(0, 0, 0), CombatRetention.prune(preview, new CombatSettings.Values(false, 7, 90), NOW, new Cancellation()));
            assertEquals(Set.of("fresh", "old", "ancient", "unknown", "orphan-old", "orphan-new"), left(CLOSED, CombatRetention.FULL_DETAIL));
        }
        try (SessionStore store = new SessionStore(root, true, "synthetic")) {
            Throwable[] failure = new Throwable[1];
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                try { CombatRetention.prune(store, new CombatSettings.Values(false, 7, 90), NOW, new Cancellation()); }
                catch (Throwable t) { failure[0] = t; }
            });
            assertTrue(String.valueOf(failure[0]), failure[0] instanceof IllegalStateException);
            assertEquals(6, left(CLOSED, CombatRetention.FULL_DETAIL).size());
        }
    }
}
