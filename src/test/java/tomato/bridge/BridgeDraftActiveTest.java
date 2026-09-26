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

/** BRIDGE-3: draft versus active settings, inline validation, Revert and the actual confirmation result. */
public class BridgeDraftActiveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path settings, csv;

    @Before public void fixtures() throws Exception {
        settings = temp.getRoot().toPath().resolve("bridge.properties"); csv = temp.newFile("items.csv").toPath();
        Files.write(csv, "Item Name\nTest Sword\n".getBytes(StandardCharsets.UTF_8));
    }
    private BridgeConfig config(String host, boolean send) {
        Properties p = new Properties(); p.setProperty(BridgeConfig.PREFIX + "enabled", "true"); p.setProperty(BridgeConfig.PREFIX + "send", String.valueOf(send));
        p.setProperty(BridgeConfig.PREFIX + "endpoint", "https://" + host + ".invalid/ingest"); p.setProperty(BridgeConfig.PREFIX + "guild_id", "123456789012345678");
        p.setProperty(BridgeConfig.PREFIX + "link_token", "draft-test-token"); p.setProperty(BridgeConfig.PREFIX + "csv_path", csv.toString());
        return new BridgeConfig(p);
    }
    /** Storage that saves in memory and fails for one endpoint. */
    private static final class Storage extends BridgeStorage {
        final List<BridgeConfig> saved = new CopyOnWriteArrayList<>(); volatile String failSave; volatile BridgeConfig initial = new BridgeConfig(new Properties());
        @Override BridgeConfig loadSettings(Path path) { return initial; }
        @Override void save(BridgeConfig next, Path path) throws IOException { if (next.endpoint.equals(failSave)) throw new IOException("Disk is read-only"); saved.add(next); }
        @Override void audit(BridgeService.Review entry, BridgeConfig config) { }
    }
    private static <T> T edt(Callable<T> action) throws Exception { FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeLater(task); return task.get(5, TimeUnit.SECONDS); }
    private static Component find(Container root, String name) { for (Component c : root.getComponents()) { if (name.equals(c.getName())) return c; if (c instanceof Container) { Component f = find((Container)c, name); if (f != null) return f; } } return null; }
    private static String text(BridgeReviewGUI panel, String name) { return ((JTextArea)find(panel, "bridge-" + name)).getText(); }
    private static JTextField field(BridgeReviewGUI panel, String name) { return (JTextField)find(panel, "bridge-" + name); }
    private static void awaitIdle(BridgeReviewGUI panel) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (edt(() -> ((JButton)find(panel, "bridge-save")).isEnabled() && !text(panel, "save-result").startsWith("Saving"))) return;
            Thread.sleep(20);
        }
        fail("Save did not finish");
    }

    @Test public void failedSaveKeepsPreviousActiveSettingsAndEditableDraft() throws Exception {
        Storage storage = new Storage(); AtomicInteger sends = new AtomicInteger();
        BridgeConfig old = config("old", false); storage.initial = old;
        try (BridgeService service = new BridgeService(settings, false, (url, json) -> { sends.incrementAndGet(); throw new AssertionError("No delivery expected"); }, 8, storage)) {
            service.awaitReady(3000);
            BridgeReviewGUI panel = edt(() -> new BridgeReviewGUI(service)); edt(() -> { panel.refresh(); return null; });
            assertTrue(edt(() -> text(panel, "active")).startsWith("Active now: Local review only · CSV items: 1"));
            assertEquals("The form matches the active settings.", edt(() -> text(panel, "draft-state")));
            assertTrue(edt(() -> text(panel, "confirmation")).contains("not requested"));

            // Inline validation before any save attempt; the token never appears in feedback.
            edt(() -> { ((JCheckBox)find(panel, "bridge-send")).setSelected(true); field(panel, "endpoint").setText("http://example.com/ingest"); return null; });
            assertTrue(edt(() -> text(panel, "validation")).contains("HTTPS"));
            assertTrue(edt(() -> text(panel, "draft-state")).contains("Unsaved changes: Send, Endpoint"));

            storage.failSave = "https://broken.invalid/ingest";
            edt(() -> { field(panel, "endpoint").setText(storage.failSave); assertEquals("", text(panel, "validation")); ((JButton)find(panel, "bridge-save")).doClick(); return null; });
            awaitIdle(panel);
            assertSame("a failed save leaves the previous configuration active", old, service.config());
            String result = edt(() -> text(panel, "save-result"));
            assertTrue(result, result.contains("Not saved: Disk is read-only") && result.contains("previous settings remain active (Local review only)"));
            assertFalse(result.contains("draft-test-token"));
            assertEquals("the draft stays editable", storage.failSave, edt(() -> field(panel, "endpoint").getText()));
            assertTrue(edt(() -> field(panel, "endpoint").isEditable()));
            assertTrue(edt(() -> text(panel, "draft-state")).contains("Unsaved changes: Send, Endpoint"));
            assertTrue(edt(() -> text(panel, "active")).startsWith("Active now: Local review only"));

            edt(() -> { ((JButton)find(panel, "bridge-revert")).doClick(); return null; });
            assertEquals(old.endpoint, edt(() -> field(panel, "endpoint").getText()));
            assertFalse(edt(() -> ((JCheckBox)find(panel, "bridge-send")).isSelected()));
            assertEquals("The form matches the active settings.", edt(() -> text(panel, "draft-state")));
            assertTrue(storage.saved.isEmpty()); assertEquals(0, sends.get());
        }
    }

    @Test public void reviewAlertDraftIsDetachedAndReturnsToTheSameDrop() throws Exception {
        Storage storage = new Storage(); storage.initial = config("old", false);
        AtomicInteger audio = new AtomicInteger(); tomato.realmshark.SoundSeam.count(audio);
        try (BridgeService service = new BridgeService(settings, false, (url, json) -> { throw new AssertionError("No delivery expected"); }, 8, storage)) {
            service.awaitReady(3000);
            service.receive(Arrays.asList(new BridgePayload.Drop(new BridgePayload.Item(42, "Test Sword", "EQUIPMENT", "UT", "", false), 7, "Fixture", "Wizard", "Test", false, false, 1, 0),
                new BridgePayload.Drop(new BridgePayload.Item(43, "Other Sword", "EQUIPMENT", "UT", "", false), 7, "Fixture", "Wizard", "Test", false, false, 1, 1)));
            service.awaitIdle(3000);
            BridgeReviewGUI panel = edt(() -> new BridgeReviewGUI(service));
            List<Object[]> opened = new ArrayList<>();
            edt(() -> {
                panel.refresh(); panel.useDraftOpener((draft, back) -> opened.add(new Object[]{draft, back}));
                JTable table = (JTable)find(panel, "bridge-review-table");
                int row = -1; for (int i = 0; i < table.getRowCount(); i++) if ("Test Sword".equals(table.getValueAt(i, 1))) row = i;
                table.setRowSelectionInterval(row, row); ((JButton)find(panel, "bridge-alert-draft")).doClick();
                tomato.realmshark.AlertRules.Draft draft = (tomato.realmshark.AlertRules.Draft)opened.get(0)[0];
                assertEquals(tomato.realmshark.AlertRules.Mode.ITEM_ID, draft.mode); assertEquals("42", draft.value);
                assertEquals(Integer.valueOf(42), draft.sampleId); assertEquals("Test Sword", draft.sampleText); assertTrue(draft.source.startsWith("Bridge review"));
                table.setRowSelectionInterval(1 - row, 1 - row); ((Runnable)opened.get(0)[1]).run();
                assertEquals("Test Sword", table.getValueAt(table.getSelectedRow(), 1));
                return null;
            });
            assertTrue(storage.saved.isEmpty()); assertEquals(0, audio.get());
        } finally { tomato.realmshark.SoundSeam.clear(); }
    }

    @Test public void confirmationShowsTheActualBotResponseNotTheSave() throws Exception {
        Storage storage = new Storage(); storage.initial = config("old", false);
        BlockingQueue<BridgeService.Response> responses = new LinkedBlockingQueue<>();
        responses.add(new BridgeService.Response(403, "{\"ok\":false,\"error\":\"invalid_link_token\"}"));
        responses.add(new BridgeService.Response(200, "{\"ok\":true}"));
        List<String> pings = new CopyOnWriteArrayList<>();
        try (BridgeService service = new BridgeService(settings, false, (url, json) -> { pings.add(json); return responses.remove(); }, 8, storage)) {
            service.awaitReady(3000);
            BridgeReviewGUI panel = edt(() -> new BridgeReviewGUI(service));
            edt(() -> { ((JCheckBox)find(panel, "bridge-send")).setSelected(true); ((JButton)find(panel, "bridge-save")).doClick(); return null; });
            awaitIdle(panel); service.awaitIdle(3000);
            assertEquals(BridgeService.Confirmation.State.REJECTED, service.snapshot().confirmation.state);
            edt(() -> { panel.refresh(); return null; });
            String rejected = edt(() -> text(panel, "confirmation"));
            assertTrue(rejected, rejected.startsWith("Confirmation rejected") && rejected.contains("invalid_link_token"));
            assertTrue("saving succeeded even though the bot rejected the ping", edt(() -> text(panel, "save-result")).startsWith("Saved and active: Sending matching drops"));

            edt(() -> { field(panel, "guild").setText("223456789012345678"); ((JButton)find(panel, "bridge-save")).doClick(); return null; });
            awaitIdle(panel); service.awaitIdle(3000); edt(() -> { panel.refresh(); return null; });
            assertTrue(edt(() -> text(panel, "confirmation")).startsWith("Confirmation received: Accepted"));
            assertEquals(2, pings.size()); for (String ping : pings) assertTrue(ping.contains("bridge_settings_test"));
            assertFalse(edt(() -> text(panel, "confirmation")).contains("draft-test-token"));
        }
    }
}
