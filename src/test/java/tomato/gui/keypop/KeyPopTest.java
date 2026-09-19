package tomato.gui.keypop;
import ui.UiTestLayout;

import org.junit.Test;
import packets.data.enums.NotificationEffectType;
import packets.incoming.NotificationPacket;
import tomato.gui.modern.VioletTheme;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class KeyPopTest {
    private static final Instant NOW = Instant.parse("2026-09-08T19:00:00Z");
    private static NotificationPacket packet(NotificationEffectType type, String message) {
        NotificationPacket packet = new NotificationPacket(); packet.effect = type; packet.message = message; return packet;
    }
    private static KeyPopEvent event(Instant time, String player, String item, KeyPopEvent.Kind kind) {
        return new KeyPopEvent(time, player, item, kind);
    }

    @Test public void parsesReorderedFieldsRunesVialsAndIncs() {
        KeyPopEvent vial = KeyPopEvent.fromPacket(packet(NotificationEffectType.ServerMessage, "#{\"player\" : \"Aster\", \"name\" : \"The Void\"}"), NOW);
        assertEquals(KeyPopEvent.Kind.VIAL, vial.kind); assertEquals("Aster", vial.player); assertEquals(NOW, vial.time);
        KeyPopEvent rune = KeyPopEvent.fromPacket(packet(NotificationEffectType.ServerMessage, "#{\"name\":\"The Shield Monument has been activated\",\"player\":\"Wren\"}"), NOW);
        assertEquals(KeyPopEvent.Kind.RUNE, rune.kind); assertEquals("Shield Rune", rune.item);
        KeyPopEvent inc = KeyPopEvent.fromPacket(packet(NotificationEffectType.ServerMessage, "#{\"name\":\"Wine Cellar\",\"player\":\"Nova\"}"), NOW);
        assertEquals(KeyPopEvent.Kind.INC, inc.kind);
        assertEquals("A\"ster", KeyPopEvent.field("{\"player\":\"A\\\"ster\"}", "player"));
    }

    @Test public void portalPreservesUnknownAssetIdAndStripsPlayerMetadata() {
        NotificationPacket packet = packet(NotificationEffectType.PortalOpened, "{\"player\":\"Aster,metadata\"}");
        packet.pictureType = 0x7FFFFFFF;
        KeyPopEvent event = KeyPopEvent.fromPacket(packet, NOW);
        assertEquals("Aster", event.player); assertEquals(KeyPopEvent.Kind.KEY, event.kind);
        assertEquals("Unknown portal (0x7FFFFFFF)", event.item);
    }

    @Test public void ignoresCalloutsUnrelatedAndMalformedNotifications() {
        assertNull(KeyPopEvent.fromPacket(null, NOW));
        assertNull(KeyPopEvent.fromPacket(packet(NotificationEffectType.PortalOpened, null), NOW));
        assertNull(KeyPopEvent.fromPacket(packet(NotificationEffectType.PortalOpened, "garbage"), NOW));
        assertNull(KeyPopEvent.fromPacket(packet(NotificationEffectType.PortalOpened, "{\"player\":\"\"}"), NOW));
        assertNull(KeyPopEvent.fromPacket(packet(NotificationEffectType.PlayerCallout, "Aster;{\"name\":\"Lost Halls\",\"player\":\"Aster\"}"), NOW));
        assertNull(KeyPopEvent.fromPacket(packet(NotificationEffectType.ServerMessage, "{\"name\":\"Some achievement\",\"player\":\"Aster\"}"), NOW));
    }

    @Test public void filtersAreLiteralCombinedAndIncludeTimeBoundary() {
        KeyPopEvent event = event(NOW, "Aster", "Lost Halls", KeyPopEvent.Kind.KEY);
        assertTrue(event.matches("ASTER halls", "Key", "Lost Halls", NOW));
        assertFalse(event.matches("[", "All types", "All dungeons / items", null));
        assertFalse(event.matches("aster", "Vial", "Lost Halls", null));
        assertFalse(event.matches("", "Key", "The Shatters", null));
        assertFalse(event.matches("", "Key", "Lost Halls", NOW.plusSeconds(1)));
        assertEquals(KeyPopEvent.Kind.OTHER, KeyPopEvent.fromLegacy("12:30 [Aster]: Lost Halls", NOW).kind);
    }

    @Test public void bufferRetainsNewestEventsAndClearResetsCounts() throws Exception {
        KeyPopHistory history = new KeyPopHistory();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try { for (int i = 0; i < 10100; i++) history.add(event(NOW.plusSeconds(i), "Aster", "Vial", KeyPopEvent.Kind.VIAL)); }
            catch (Throwable e) { failure.set(e); }
        });
        writer.start();
        while (writer.isAlive()) assertTrue(history.snapshot().events.size() <= KeyPopHistory.CAPACITY);
        writer.join(); assertNull(failure.get());
        KeyPopHistory.Snapshot snapshot = history.snapshot();
        assertEquals(10000, snapshot.events.size()); assertEquals(100, snapshot.discarded);
        assertEquals(NOW.plusSeconds(100), snapshot.events.get(0).time);
        history.clear(); assertEquals(0, history.snapshot().events.size()); assertEquals(0, history.snapshot().discarded);
        assertEquals(10000, snapshot.events.size());
    }

    @Test public void exportEscapesCapturedContentAndWritesUtc() throws Exception {
        Path file = Files.createTempFile(new File(".").toPath(), "keypop-export-", ".csv");
        try {
            KeyPopDashboard.writeCsv(file, Arrays.asList(event(NOW, "=SUM(A1)", "A, \"B\"\nC", KeyPopEvent.Kind.KEY)));
            String csv = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            assertTrue(csv.startsWith("Timestamp (UTC),Player,Type,Dungeon / item\r\n"));
            assertTrue(csv.contains("\"2026-09-08T19:00:00Z\""));
            assertTrue(csv.contains("\"'=SUM(A1)\"")); assertTrue(csv.contains("\"A, \"\"B\"\"\nC\""));
        } finally { Files.deleteIfExists(file); }
    }

    private static KeyPopHistory sample() {
        KeyPopHistory history = new KeyPopHistory(); Instant now = Instant.now();
        history.add(event(now.minusSeconds(4000), "Nova", "Inc", KeyPopEvent.Kind.INC));
        history.add(event(now.minusSeconds(200), "Aster", "Lost Halls", KeyPopEvent.Kind.KEY));
        history.add(event(now.minusSeconds(120), "Wren", "The Shatters", KeyPopEvent.Kind.KEY));
        history.add(event(now.minusSeconds(90), "aster", "Vial", KeyPopEvent.Kind.VIAL));
        history.add(event(now.minusSeconds(30), "Wren", "Shield Rune", KeyPopEvent.Kind.RUNE));
        history.add(event(now, "Aster", "Lost Halls", KeyPopEvent.Kind.KEY));
        return history;
    }

    @Test public void liveMetricsSummarySortingAndCombinedFiltersStayConsistent() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            KeyPopHistory history = sample();
            KeyPopDashboard ui = new KeyPopDashboard(history);
            assertEquals("6", ui.metrics[0].getText()); assertEquals("3", ui.metrics[1].getText());
            assertEquals("3", ui.metrics[2].getText()); assertEquals("5", ui.metrics[3].getText());
            assertEquals("Aster", ui.players.getValueAt(0, 0)); assertEquals(3, ui.players.getValueAt(0, 1));
            assertEquals(50.0, (Double)ui.players.getValueAt(0, 6), .001);
            assertEquals("Lost Halls", ui.items.getValueAt(0, 0)); assertEquals(2, ui.items.getValueAt(0, 1));
            ui.search.setText("aster"); assertEquals(3, ui.events.getRowCount());
            ui.type.setSelectedItem("Key"); assertEquals(2, ui.events.getRowCount()); assertEquals("1", ui.metrics[2].getText());
            ui.item.setSelectedItem("Vial"); assertEquals(0, ui.events.getRowCount()); assertTrue(ui.empty.isVisible());
            ui.resetFilters(); ui.period.setSelectedItem("Last hour"); assertEquals(5, ui.events.getRowCount());
            ui.resetFilters(); ui.events.getRowSorter().toggleSortOrder(0);
            assertEquals("Nova", ui.events.getValueAt(0, 1));
            ui.refresh(); assertEquals("Nova", ui.events.getValueAt(0, 1));
            ui.events.setRowSelectionInterval(0, 0);
            history.add(event(Instant.now(), "Wren", "Lost Halls", KeyPopEvent.Kind.KEY));
            ui.refresh(); assertEquals("Nova", ui.events.getValueAt(ui.events.getSelectedRow(), 1));
            ui.clearHistory(); assertEquals("0", ui.metrics[0].getText()); assertEquals(0, ui.players.getRowCount());
        });
    }

    @Test public void notificationSearchCancelAndApplyPreserveSavedChoices() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            util.PropertiesManager.setProperties("keypopSound", "Lost Halls");
            new KeypopGUI();
            JDialog dialog = KeypopGUI.createConfigureDialog();
            try {
                JCheckBox missing = (JCheckBox)find(dialog, "Missing dungeon completes on current character");
                missing.setSelected(true);
                ((JButton)find(dialog, "Cancel")).doClick();
                assertEquals("Lost Halls", util.PropertiesManager.getProperty("keypopSound"));
            } finally { dialog.dispose(); }
            dialog = KeypopGUI.createConfigureDialog();
            try {
                assertFalse(((JCheckBox)find(dialog, "Missing dungeon completes on current character")).isSelected());
                JTextField search = field(dialog); search.setText("Lost Halls");
                ((JButton)find(dialog, "Unselect shown")).doClick();
                ((JButton)find(dialog, "Apply")).doClick();
                assertEquals("", util.PropertiesManager.getProperty("keypopSound"));
            } finally { dialog.dispose(); }
        });
    }

    private static AbstractButton find(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton && ((AbstractButton)child).getText().equals(text)) return (AbstractButton)child;
            if (child instanceof Container) { AbstractButton found = find((Container)child, text); if (found != null) return found; }
        }
        return null;
    }
    private static JTextField field(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextField) return (JTextField)child;
            if (child instanceof Container) { JTextField found = field((Container)child); if (found != null) return found; }
        }
        return null;
    }

    @Test public void rendersAllViewsAtNormalAndCompactSizes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VioletTheme.install();
            KeyPopDashboard ui = new KeyPopDashboard(sample());
            JFrame frame = new JFrame("Key pops preview"); frame.setContentPane(ui);
            try {
                File folder = new File("screenshots"); folder.mkdirs();
                for (int width : new int[] {1020, 580}) {
                    frame.setSize(width, width == 580 ? 460 : 700); frame.setVisible(true); frame.validate();
                    UiTestLayout.settle(frame);
                    for (int tab = 0; tab < 3; tab++) {
                        ui.tabs.setSelectedIndex(tab); frame.validate();
                        assertTrue(ui.search.isShowing()); assertTrue(ui.search.getWidth() > 150);
                        assertTrue(ui.tabs.getHeight() > 100);
                        BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
                        Graphics2D graphics = image.createGraphics(); frame.printAll(graphics); graphics.dispose();
                        ImageIO.write(image, "png", new File(folder, "keypops-" + width + "-tab-" + tab + ".png"));
                        assertTrue("Item filter at " + width + ": " + ui.item.getBounds() + ", parent " + ui.item.getParent().getBounds(), ui.item.getWidth() > 130);
                    }
                }
            } catch (Exception e) { throw new AssertionError(e); } finally { frame.dispose(); }
        });
    }
}
