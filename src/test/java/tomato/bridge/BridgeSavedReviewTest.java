package tomato.bridge;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.bridge.BridgeReviewGUI;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import static org.junit.Assert.*;

/** BRIDGE-4: reopening synthetic versioned and legacy journals restores outcomes and exports without any delivery. */
public class BridgeSavedReviewTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String TOKEN = "saved-review-token";
    private Path csv, journal;

    @Before public void fixtures() throws Exception {
        csv = temp.newFile("items.csv").toPath(); Files.write(csv, "Item Name\nTest Sword\n".getBytes(StandardCharsets.UTF_8));
        journal = temp.getRoot().toPath().resolve("journals").resolve("review.jsonl");
    }
    private BridgeConfig localOnly() {
        Properties p = new Properties(); p.setProperty(BridgeConfig.PREFIX + "enabled", "true"); p.setProperty(BridgeConfig.PREFIX + "send", "false");
        p.setProperty(BridgeConfig.PREFIX + "endpoint", "https://example.invalid/ingest"); p.setProperty(BridgeConfig.PREFIX + "guild_id", "123456789012345678");
        p.setProperty(BridgeConfig.PREFIX + "link_token", TOKEN); p.setProperty(BridgeConfig.PREFIX + "csv_path", csv.toString());
        p.setProperty(BridgeConfig.PREFIX + "local_review_log", journal.toString());
        return new BridgeConfig(p);
    }
    private static BridgePayload.Drop drop(String name, int id) {
        return new BridgePayload.Drop(new BridgePayload.Item(id, name, "EQUIPMENT", "UT", "Damage(3)", false), 7, "Fixture", "Wizard", "Synthetic Dungeon", false, false, 1, 0);
    }
    private static final BridgeService.Transport FAIL = (url, json) -> { throw new AssertionError("Saved review must never deliver"); };

    /** Writes real journal lines through the production storage path, then closes the writer. */
    private List<BridgeService.Review> write(String settingsName, BridgePayload.Drop... drops) throws Exception {
        BridgeService writer = new BridgeService(temp.getRoot().toPath().resolve(settingsName), false, FAIL, 8);
        try { writer.configure(localOnly(), false, false); writer.receive(Arrays.asList(drops)); writer.awaitIdle(3000); return writer.snapshot().reviews; }
        finally { writer.close(); writer.awaitClosed(3000); }
    }
    /** Reader-side storage that counts every configuration touch after startup. */
    private final class Counting extends BridgeStorage {
        final AtomicInteger touches = new AtomicInteger();
        @Override BridgeConfig loadSettings(Path path) { return localOnly(); }
        @Override BridgeCatalog loadCatalog(BridgeConfig config) throws IOException { touches.incrementAndGet(); return super.loadCatalog(config); }
        @Override void save(BridgeConfig config, Path path) { touches.incrementAndGet(); }
        @Override void audit(BridgeService.Review entry, BridgeConfig config) { throw new AssertionError("Reader must not audit"); }
    }
    private static <T> T edt(Callable<T> action) throws Exception { FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeLater(task); return task.get(5, TimeUnit.SECONDS); }
    private static Component find(Container root, String name) { for (Component c : root.getComponents()) { if (name.equals(c.getName())) return c; if (c instanceof Container) { Component f = find((Container)c, name); if (f != null) return f; } } return null; }
    private static String text(BridgeReviewGUI panel, String name) { return ((JTextArea)find(panel, name)).getText(); }
    private static void awaitRead(BridgeReviewGUI panel, BridgeJournal.Result previous) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) { if (edt(() -> panel.savedResult() != previous)) return; Thread.sleep(20); }
        fail("Journal read did not finish");
    }

    @Test public void versionedAndLegacyJournalsReopenWithQualifiedIdentitiesAndNoDelivery() throws Exception {
        List<BridgeService.Review> first = write("a.properties", drop("Test Sword", 42), drop("Unlisted Blade", 43));
        Files.move(journal, journal.resolveSibling("review.jsonl.1")); // Simulates the 5 MB rotation.
        List<BridgeService.Review> second = write("b.properties", drop("Test Sword", 42));
        Files.write(journal, ("{not json\n{\"journal\":\"" + BridgeJournal.FORMAT + "\",\"version\":2,\"review\":{}}\n\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        String disk = new String(Files.readAllBytes(journal), StandardCharsets.UTF_8) + new String(Files.readAllBytes(journal.resolveSibling("review.jsonl.1")), StandardCharsets.UTF_8);
        assertFalse(disk.contains(TOKEN)); assertTrue(disk.contains("\"version\":1"));
        Path legacy = temp.getRoot().toPath().resolve("old-bridge-review.jsonl");
        Files.write(legacy, (BridgeStorage.legacyLine(first.get(0)) + "\n" + BridgeStorage.legacyLine(first.get(1)) + "\n" + BridgeStorage.legacyLine(second.get(0)) + "\n").getBytes(StandardCharsets.UTF_8));

        Counting storage = new Counting(); AtomicInteger deliveries = new AtomicInteger();
        try (BridgeService reader = new BridgeService(temp.getRoot().toPath().resolve("c.properties"), false,
                (url, json) -> { deliveries.incrementAndGet(); throw new AssertionError("Saved review must never deliver"); }, 8, storage)) {
            reader.awaitReady(3000);
            int touches = storage.touches.get(); BridgeConfig active = reader.config(); long observed = reader.snapshot().observed;
            BridgeReviewGUI panel = edt(() -> new BridgeReviewGUI(reader));
            assertTrue(edt(() -> text(panel, "bridge-saved-note")).contains("cannot be recovered"));
            edt(() -> { ((JButton)find(panel, "bridge-saved-open-configured")).doClick(); return null; });
            awaitRead(panel, null);
            BridgeJournal.Result result = edt(panel::savedResult);
            assertEquals(3, result.entries.size()); assertEquals(2, result.sessions().size()); assertEquals(2, result.malformed);
            assertEquals("review.jsonl.1", result.entries.get(0).journal);
            assertEquals(result.entries.get(0).reviewId, result.entries.get(2).reviewId);
            assertNotEquals("same counter, different qualified identity", result.entries.get(0).identity(), result.entries.get(2).identity());
            assertEquals("Local only", result.entries.get(0).review.status); assertEquals("Not in CSV", result.entries.get(1).review.status);
            assertEquals(BridgeService.Outcome.LOCAL, result.entries.get(1).review.outcome());
            String problems = edt(() -> text(panel, "bridge-saved-problems"));
            assertTrue(problems, problems.contains("review.jsonl line 2: not a JSON object") && problems.contains("review.jsonl line 3: unsupported journal version 2"));
            assertTrue(edt(() -> text(panel, "bridge-saved-summary")).startsWith("3 saved reviews from 2 sessions in 2 journals · 2 unreadable lines"));
            JTable table = (JTable)find(panel, "bridge-saved-table");
            assertEquals(3, (int)edt(table::getRowCount));
            edt(() -> { table.setRowSelectionInterval(0, 0); return null; });
            String details = edt(() -> text(panel, "bridge-saved-details"));
            assertTrue(details, details.contains("historical; nothing here is sent") && details.contains("Identity: review.jsonl · session"));
            String export = edt(panel::savedCsv);
            assertTrue(export.startsWith("Journal,Session,Review ID")); assertTrue(export.contains("\"review.jsonl.1\"")); assertTrue(export.contains("\"Not in CSV\""));
            assertFalse(export.contains(TOKEN));

            BridgeJournal.Result configured = result;
            edt(() -> { panel.openJournals(Collections.singletonList(legacy)); return null; });
            awaitRead(panel, configured);
            BridgeJournal.Result old = edt(panel::savedResult);
            assertEquals(3, old.entries.size()); assertTrue(old.entries.get(0).legacy);
            assertEquals(new LinkedHashSet<>(Arrays.asList("old-bridge-review.jsonl · legacy run 1", "old-bridge-review.jsonl · legacy run 2")), old.sessions());
            assertTrue(edt(panel::savedCsv).contains("\"legacy\""));

            assertEquals("no delivery", 0, deliveries.get());
            assertEquals("no configure, CSV reload or save", touches, storage.touches.get());
            assertSame(active, reader.config()); assertEquals("no receive", observed, reader.snapshot().observed);
        }
    }

    @Test public void readerReportsMissingAndIncompleteRecordsWithoutInventingThem() throws Exception {
        Path broken = temp.newFile("broken.jsonl").toPath();
        Files.write(broken, ("{\"id\":1,\"time\":\"2026-09-01T00:00:00Z\"}\n[1,2]\n{\"journal\":\"other\",\"version\":1}\n").getBytes(StandardCharsets.UTF_8));
        BridgeJournal.Result result = BridgeJournal.read(Arrays.asList(temp.getRoot().toPath().resolve("missing.jsonl"), broken));
        assertTrue(result.entries.isEmpty()); assertEquals(3, result.malformed);
        assertEquals(Arrays.asList("missing.jsonl (not found)", "broken.jsonl (0 records)"), result.sources);
        assertEquals("broken.jsonl line 1: incomplete review record", result.problems.get(0).toString());
        assertEquals(Collections.emptyList(), BridgeJournal.configured(new BridgeConfig(new Properties())));
    }
}
