package tomato.history.encounter;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.DpsData;
import tomato.gui.dps.CombatSummaries;
import tomato.gui.glance.home.HomeArchive;
import tomato.gui.glance.home.HomeHistoryFixture;
import tomato.history.SessionStore;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;

/**
 * Storage measurement for spec §13 O3 (synthetic data only): the saved size of real summaries of three fight shapes, and the
 * bytes and read times of the large synthetic history with one record and one detail per run. The printed numbers go into
 * the P5a validation record; the bounds below are generous so only a regression fails.
 */
public class CombatStorageMeasurementTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private record Shape(String name, int seconds, int players, int enemies) {}

    @Test public void summarySizesAndLargeHistoryReads() throws Exception {
        long record600 = -1, detail600 = -1;
        for (Shape shape : List.of(new Shape("dungeon", 150, 8, 60), new Shape("long dungeon", 600, 8, 150), new Shape("realm", 3_600, 30, 2_000))) {
            DpsData data = CombatFixtures.typical(new VisitRef(HomeHistoryFixture.id("measure"), "M:1"), HomeHistoryFixture.NOW,
                shape.seconds(), shape.players(), shape.enemies());
            long built = System.nanoTime();
            CombatSummaries.Result result = CombatSummaries.build(data);
            long buildMillis = (System.nanoTime() - built) / 1_000_000;
            int record = SessionStore.JSON.toJson(result.record()).getBytes(StandardCharsets.UTF_8).length;
            int detail = SessionStore.JSON.toJson(result.detail()).getBytes(StandardCharsets.UTF_8).length;
            System.out.println("Combat summary " + shape.name() + " (" + shape.seconds() + " s, " + shape.players() + " players, "
                + shape.enemies() + " enemies, " + (shape.seconds() * shape.players() * 2) + " hits): record " + record + " B, detail "
                + detail + " B, built in " + buildMillis + " ms");
            if (shape.seconds() == 600) { record600 = record; detail600 = detail; }
        }
        assertTrue("A 600 s record stays card-sized: " + record600 + " B", record600 > 0 && record600 <= 16 * 1024);
        assertTrue("A 600 s detail stays recap-sized: " + detail600 + " B", detail600 > 0 && detail600 <= 64 * 1024);

        Path root = temp.newFolder().toPath();
        HomeHistoryFixture.writeLarge(root, HomeHistoryFixture.LARGE_SESSIONS, HomeHistoryFixture.LARGE_RUNS);
        long before = bytes(root, null);
        CombatFixtures.writeLarge(root, HomeHistoryFixture.LARGE_SESSIONS, HomeHistoryFixture.LARGE_RUNS);
        long records = bytes(root, CombatFacts.RECORDS), details = bytes(root, CombatFacts.DETAILS), total = bytes(root, null);
        int runs = HomeHistoryFixture.LARGE_SESSIONS * HomeHistoryFixture.LARGE_RUNS;
        System.out.println("Large history (" + HomeHistoryFixture.LARGE_SESSIONS + " sessions x " + HomeHistoryFixture.LARGE_RUNS + " runs): "
            + total + " B on disk (runs, loot and fame " + before + " B; " + CombatFacts.RECORDS + " " + records + " B = " + records / runs
            + " B each; " + CombatFacts.DETAILS + " " + details + " B = " + details / runs + " B each)");
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            long[] read = new long[2]; int[] count = new int[2];
            for (int pass = 0; pass < 2; pass++) {
                long started = System.nanoTime();
                List<SessionStore.SessionEntry> catalog = store.catalog();
                int[] seen = {0};
                CombatFacts.read(store, catalog, SessionStore.ALL, record -> seen[0]++);
                read[pass] = (System.nanoTime() - started) / 1_000_000; count[pass] = seen[0];
            }
            long[] home = new long[2]; HomeArchive.Result result = null;
            for (int pass = 0; pass < 2; pass++) {
                long started = System.nanoTime();
                result = HomeArchive.read(store, HomeArchive.Window.TODAY, HomeHistoryFixture.NOW, HomeHistoryFixture.ZONE, List.of());
                home[pass] = (System.nanoTime() - started) / 1_000_000;
            }
            System.out.println("CombatFacts.read all " + count[0] + " records: cold " + read[0] + " ms, warm " + read[1]
                + " ms (bound 250 ms warm); HomeArchive.read with saved records: cold " + home[0] + " ms, warm " + home[1] + " ms");
            assertEquals(runs, count[0]); assertEquals(runs, count[1]);
            assertNotNull("Recent runs get their DPS from the saved records", result.recent().get(0).localDps());
            assertTrue("The large read stays fast when warm: " + read[1] + " ms", read[1] <= 250);
        }
    }

    /** Bytes of the regular files under root, or only those of one module's folders. */
    private static long bytes(Path root, String module) throws Exception {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                .filter(file -> module == null || file.getParent().getFileName().toString().equals(module))
                .mapToLong(file -> file.toFile().length()).sum();
        }
    }
}
