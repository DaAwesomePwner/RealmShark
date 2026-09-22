package tomato.gui.logging;

import org.junit.Test;
import packets.PacketType;
import packets.incoming.RealmScoreUpdatePacket;
import packets.packetcapture.logger.ActivityJournal;
import packets.packetcapture.logger.DiscoveryLog;
import tomato.gui.activity.ActivityPanel;
import tomato.gui.modern.CollectionControl;
import javax.swing.*;
import java.awt.*;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;

public class CollectionEvidenceTest {
    @Test public void sharedCollectionStateChangesWhileBothDisplaysRemainPaused() throws Exception {
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            log.setSampleMillis(0);
            RealmScoreUpdatePacket packet = new RealmScoreUpdatePacket();
            log.observe(PacketType.byClass(packet).getIndex(), 20, packet, "decoded", 0);
            LoggingGUI[] diagnostics = new LoggingGUI[1]; ActivityPanel[] activity = new ActivityPanel[1];
            SwingUtilities.invokeAndWait(() -> {
                diagnostics[0] = new LoggingGUI(log,LoggingStateTestSupport.memoryStore()); activity[0] = new ActivityPanel(log, ActivityPanel.Mode.TIMELINE);
                find(diagnostics[0], JTabbedPane.class, null).setSelectedIndex(4);
                diagnostics[0].refresh(); activity[0].refresh();
            });
            await(() -> activeTable(diagnostics[0]).getRowCount() == 1);
            SwingUtilities.invokeAndWait(() -> {
                find(diagnostics[0], JCheckBox.class, "Pause this view").setSelected(true);
                find(activity[0], JCheckBox.class, "Pause this view").setSelected(true);
                find(activity[0], CollectionControl.class, null).doClick();
                diagnostics[0].refresh();
                assertFalse(log.isEnabled());
                assertFalse(find(diagnostics[0], CollectionControl.class, null).isSelected());
                find(diagnostics[0], CollectionControl.class, null).doClick();
                activity[0].refresh();
                assertTrue(log.isEnabled());
                assertTrue(find(activity[0], CollectionControl.class, null).isSelected());
            });
            log.observe(PacketType.byClass(packet).getIndex(), 20, packet, "decoded", 0);
            SwingUtilities.invokeAndWait(() -> {
                diagnostics[0].refresh(); activity[0].refresh();
                assertEquals(1, activeTable(diagnostics[0]).getRowCount());
            });
            assertEquals(2, log.snapshot().total);
        }
    }

    @Test public void coverageDistinguishesDecodeLossRetentionAndHistoricalScope() {
        try (DiscoveryLog log = new DiscoveryLog(null)) {
            RealmScoreUpdatePacket packet = new RealmScoreUpdatePacket();
            int id = PacketType.byClass(packet).getIndex();
            log.observe(id, 20, null, "decode-error", 10);
            log.observe(id, 20, packet, "trailing-bytes", 2);
            String text = DiagnosticCoverage.describe(log.snapshot());
            assertTrue(text.contains("Decode failures: 1"));
            assertTrue(text.contains("Trailing-byte frames: 1"));
            assertTrue(text.contains("Delta-cache evictions: 0"));
            assertTrue(text.contains("not an event-retention count"));
            assertTrue(text.contains("interval is not continuous coverage"));
            assertTrue(text.contains("writer lifetime"));
            log.setEnabled(false);
            assertTrue(CollectionControl.status(log, true).contains("off · View paused"));
        }
        DiscoveryLog saved = DiscoveryLog.historyView(new ActivityJournal.State());
        assertEquals("Saved history", CollectionControl.status(saved, false));
    }

    private static JTable activeTable(LoggingGUI panel) { return find((Container)find(panel, JTabbedPane.class, null).getSelectedComponent(), JTable.class, null); }
    private static <T> T find(Container root, Class<T> type, String text) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && (text == null || c instanceof AbstractButton && text.equals(((AbstractButton)c).getText()))) return type.cast(c);
            if (c instanceof Container) { T found = find((Container)c, type, text); if (found != null) return found; }
        }
        return null;
    }
}
