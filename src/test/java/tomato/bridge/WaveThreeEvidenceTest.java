package tomato.bridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tomato.gui.bridge.BridgeReviewGUI;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static ui.WaveThreeEvidence.*;

/**
 * Wave 3 visual evidence for BRIDGE-3 (unsaved draft versus active, failed save) and BRIDGE-4 (Saved review with
 * malformed lines). Fake transports fail if invoked; storage is in memory; paths shown are relative to the test
 * working directory so no user-data path appears in the captures.
 */
public class WaveThreeEvidenceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder(new File("."));
    @Rule public VisualEvidence evidence = new VisualEvidence(FOLDER);
    private static final String TOKEN = "synthetic-evidence-token";

    private Path relative(String name) { return Paths.get("").toAbsolutePath().relativize(temp.getRoot().toPath().toAbsolutePath().resolve(name)); }

    private Path csv() throws IOException {
        Path csv = relative("items.csv"); Files.write(csv, "Item Name\nTest Sword\n".getBytes(StandardCharsets.UTF_8)); return csv;
    }

    private BridgeConfig config(String host, boolean send, Path csv, Path journal) {
        Properties p = new Properties(); p.setProperty(BridgeConfig.PREFIX + "enabled", "true"); p.setProperty(BridgeConfig.PREFIX + "send", String.valueOf(send));
        p.setProperty(BridgeConfig.PREFIX + "endpoint", "https://" + host + ".invalid/ingest"); p.setProperty(BridgeConfig.PREFIX + "guild_id", "123456789012345678");
        p.setProperty(BridgeConfig.PREFIX + "link_token", TOKEN); p.setProperty(BridgeConfig.PREFIX + "csv_path", csv.toString());
        if (journal != null) p.setProperty(BridgeConfig.PREFIX + "local_review_log", journal.toString());
        return new BridgeConfig(p);
    }

    private static final class Storage extends BridgeStorage {
        final List<BridgeConfig> saved = new CopyOnWriteArrayList<>(); volatile String failSave; volatile BridgeConfig initial;
        @Override BridgeConfig loadSettings(Path path) { return initial; }
        @Override void save(BridgeConfig next, Path path) throws IOException { if (next.endpoint.equals(failSave)) throw new IOException("Disk is read-only"); saved.add(next); }
        @Override void audit(BridgeService.Review entry, BridgeConfig config) { }
    }

    private static JComponent shell(BridgeReviewGUI panel) {
        JComponent[] pages = new JComponent[tomato.gui.modern.WorkspaceShell.TITLES.length]; Arrays.setAll(pages, i -> new JPanel());
        pages[12] = panel;
        tomato.gui.modern.WorkspaceShell shell = new tomato.gui.modern.WorkspaceShell(pages, () -> fail("Synthetic Bridge must not capture"), true);
        shell.select(12); return shell;
    }

    private static JTextArea area(BridgeReviewGUI panel, String name) { return named(panel, name, JTextArea.class); }

    @Test public void bridgeSettingsUnsavedDraftAndFailedSave() throws Exception {
        Storage storage = new Storage(); AtomicInteger sends = new AtomicInteger();
        BridgeConfig old = config("old", false, csv(), null); storage.initial = old;
        try (BridgeService service = new BridgeService(relative("bridge.properties"), false,
                (url, json) -> { sends.incrementAndGet(); throw new AssertionError("No delivery expected"); }, 8, storage)) {
            service.awaitReady(3000);
            BridgeReviewGUI panel = edt(() -> { BridgeReviewGUI created = new BridgeReviewGUI(service); created.refresh(); selectTab(created, "Settings"); return created; });
            JComponent shell = edt(() -> shell(panel));
            try {
                wideAndCompact(evidence, shell, "bridge-settings-active", () -> {
                    assertTrue(area(panel, "bridge-active").getText().startsWith("Active now: Local review only"));
                    assertEquals("The form matches the active settings.", area(panel, "bridge-draft-state").getText());
                });
                run(() -> { named(panel, "bridge-send", JCheckBox.class).setSelected(true); named(panel, "bridge-endpoint", JTextField.class).setText("http://example.invalid/ingest"); });
                wideAndCompact(evidence, shell, "bridge-settings-unsaved-draft-invalid", () -> {
                    assertTrue(area(panel, "bridge-validation").getText().contains("HTTPS"));
                    assertTrue(area(panel, "bridge-draft-state").getText().contains("Unsaved changes: Send, Endpoint"));
                    reveal(area(panel, "bridge-draft-state"));
                });
                storage.failSave = "https://broken.invalid/ingest";
                run(() -> { named(panel, "bridge-endpoint", JTextField.class).setText(storage.failSave); named(panel, "bridge-save", JButton.class).doClick(); });
                await(() -> named(panel, "bridge-save", JButton.class).isEnabled() && !area(panel, "bridge-save-result").getText().startsWith("Saving"));
                wideAndCompact(evidence, shell, "bridge-settings-failed-save", () -> {
                    String result = area(panel, "bridge-save-result").getText();
                    assertTrue(result, result.contains("Not saved: Disk is read-only") && result.contains("previous settings remain active"));
                    assertFalse(result.contains(TOKEN));
                    reveal(area(panel, "bridge-active"));
                    assertTrue(area(panel, "bridge-active").getText().startsWith("Active now: Local review only"));
                });
                assertSame(old, service.config());
            } finally { run(evidence::closeWindow); }
        }
        assertTrue(storage.saved.isEmpty()); assertEquals(0, sends.get());
    }

    @Test public void bridgeSavedReviewWithMalformedLines() throws Exception {
        Path csv = csv(), journal = relative("journals/review.jsonl");
        BridgeConfig local = config("example", false, csv, journal);
        BridgeService.Transport fail = (url, json) -> { throw new AssertionError("Saved review must never deliver"); };
        BridgeService writer = new BridgeService(relative("writer.properties"), false, fail, 8);
        try {
            writer.configure(local, false, false);
            writer.receive(Arrays.asList(
                new BridgePayload.Drop(new BridgePayload.Item(42, "Test Sword", "EQUIPMENT", "UT", "Damage(3)", false), 7, "Fixture", "Wizard", "Synthetic Dungeon", false, false, 1, 0),
                new BridgePayload.Drop(new BridgePayload.Item(43, "Unlisted Blade", "EQUIPMENT", "UT", "", false), 7, "Fixture", "Wizard", "Synthetic Dungeon", false, false, 1, 1)));
            writer.awaitIdle(3000);
        } finally { writer.close(); writer.awaitClosed(3000); }
        Files.write(journal, ("{not json\n{\"journal\":\"" + BridgeJournal.FORMAT + "\",\"version\":2,\"review\":{}}\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        Storage storage = new Storage(); storage.initial = local;
        AtomicInteger deliveries = new AtomicInteger();
        try (BridgeService reader = new BridgeService(relative("reader.properties"), false,
                (url, json) -> { deliveries.incrementAndGet(); throw new AssertionError("Saved review must never deliver"); }, 8, storage)) {
            reader.awaitReady(3000);
            BridgeReviewGUI panel = edt(() -> { BridgeReviewGUI created = new BridgeReviewGUI(reader); selectTab(created, "Saved review"); return created; });
            JComponent shell = edt(() -> shell(panel));
            try {
                wideAndCompact(evidence, shell, "bridge-saved-review-not-opened", () -> {
                    assertNull(panel.savedResult());
                    assertTrue(area(panel, "bridge-saved-note").getText().contains("cannot be recovered"));
                });
                run(() -> named(panel, "bridge-saved-open-configured", JButton.class).doClick());
                await(() -> panel.savedResult() != null);
                run(() -> named(panel, "bridge-saved-table", JTable.class).setRowSelectionInterval(0, 0));
                wideAndCompact(evidence, shell, "bridge-saved-review-malformed-lines", () -> {
                    reveal(area(panel, "bridge-saved-problems"));
                    assertEquals(2, panel.savedResult().entries.size()); assertEquals(2, panel.savedResult().malformed);
                    String problems = area(panel, "bridge-saved-problems").getText();
                    assertTrue(problems, problems.contains("review.jsonl line 3: not a JSON object") || problems.contains("not a JSON object"));
                    assertTrue(problems, problems.contains("unsupported journal version 2"));
                    assertTrue(area(panel, "bridge-saved-details").getText().contains("historical; nothing here is sent"));
                });
            } finally { run(evidence::closeWindow); }
        }
        assertEquals(0, deliveries.get());
    }
}
