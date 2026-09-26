package tomato.gui.logging;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Arrays;
import javax.swing.*;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import packets.PacketType;
import packets.incoming.NewTickPacket;
import packets.packetcapture.logger.DiscoveryLog;
import packets.reader.BufferReader;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.route.*;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static ui.WaveThreeEvidence.*;

/** Wave 3 visual evidence for LOG-2: separate coverage counters and the routed packet-issues view. Synthetic frames only. */
public class WaveThreeEvidenceTest {
    @Rule public VisualEvidence evidence = new VisualEvidence(FOLDER);
    @Rule public FixtureZone zone = new FixtureZone();

    @After public void restore() throws Exception { run(() -> Navigator.install(Navigator.NONE)); }

    private static Object snapshot(LoggingGUI logging) throws Exception {
        Field field = LoggingGUI.class.getDeclaredField("snapshot"); field.setAccessible(true); return field.get(logging);
    }

    @Test public void loggingCoverageExplanationAndIssuesRoute() throws Exception {
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            log.setSaving(false); log.setSampleMillis(0);
            int tick = PacketType.NEWTICK.getIndex();
            for (int i = 0; i < DiscoveryLog.EVENT_LIMIT + 5; i++) log.observe(tick, 20, new NewTickPacket(), "decoded", 0);
            log.decodeFailure(PacketType.UPDATE.getIndex(), 30, new BufferReader(ByteBuffer.wrap(new byte[]{1, 2})), new IllegalArgumentException("synthetic"));
            log.setEnabled(false);
            LoggingGUI logging = edt(() -> new LoggingGUI(log, LoggingStateTestSupport.memoryStore()));
            WorkspaceShell shell = edt(() -> {
                JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length]; Arrays.setAll(pages, i -> new JPanel());
                pages[9] = logging;
                WorkspaceShell created = new WorkspaceShell(pages, () -> fail("Synthetic logging must not capture"), true);
                ShellNavigator navigator = created.createNavigator(); Navigator.install(navigator);
                navigator.register(new LoggingRouteTarget(logging));
                created.select(10); return created;
            });
            JDialog[] coverage = new JDialog[1];
            try {
                assertTrue(edt(() -> Navigator.current().open(Route.to(Destination.LOGGING).withPayload(LoggingRouteTarget.issuesFor(Destination.RUNS)))));
                wideAndCompact(evidence, shell, "logging-packet-issues-route", () -> { try { return snapshot(logging) != null; } catch (Exception e) { throw new AssertionError(e); } }, () -> {
                    assertEquals(9, shell.getSelectedPage());
                    assertTrue(logging.captureViewState().tabs.get("packets").query.issues);
                    assertShows(shell, "Back to Runs");
                    assertShows(logging, "Collection: paused");
                    assertFalse("one collection-state term", shows(logging, "collection: off"));
                    JLabel privacy = named(logging, "logging-privacy", JLabel.class);
                    if (shell.getWidth() >= WIDE_WIDTH - 40 && shell.getHeight() >= WIDE_HEIGHT - 120) {
                        JScrollPane page = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, VisualEvidence.find(logging, JTabbedPane.class, t -> true));
                        assertEquals("the logging page opens at the top", 0, page.getViewport().getViewPosition().y);
                        assertTrue("the last status line is not clipped at a standard wide size", fullyVisible(privacy));
                    } else { reveal(privacy, privacy.getHeight()); assertTrue(fullyVisible(privacy)); }
                });
                run(() -> {
                    VisualEvidence.find(logging, AbstractButton.class, b -> "Diagnostic coverage details".equals(b.getAccessibleContext().getAccessibleName())).doClick();
                    coverage[0] = (JDialog) window(JDialog.class, "Diagnostic coverage");
                    assertNotNull(coverage[0]);
                });
                windowWideAndCompact(coverage[0], "logging-coverage-explanation", 900, 720, () -> {
                    JTextArea body = VisualEvidence.find(coverage[0].getRootPane(), JTextArea.class, a -> true);
                    String text = body.getText();
                    assertTrue(text, text.contains("Retention evictions: 6"));
                    assertTrue(text, text.contains("Delta-cache evictions: 0 (comparison baseline lost; not an event-retention count"));
                    assertTrue(text, text.contains("Decode failures: 1"));
                    assertTrue(text, text.contains("Collection: paused"));
                    assertTrue(text, text.contains("Collection at this revision: paused"));
                    assertFalse(text, text.contains("Collection at this revision: off") || text.contains("not collecting"));
                    assertFalse("fixture zone, not the workstation's", text.contains("America/"));
                });
            } finally { run(() -> { if (coverage[0] != null) coverage[0].dispose(); evidence.closeWindow(); }); }
        }
    }
}
