package tomato.gui.logging;

import com.google.gson.*;
import java.awt.Container;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import packets.PacketType;
import packets.outgoing.ForReconnectPacket;
import packets.packetcapture.logger.DiscoveryLog;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;
import static tomato.gui.modern.FormattingTestSupport.*;

public class LoggingFormattingTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void diagnosticsRenderCountsAndLocalTimesButPreserveRawEvidenceAndNumericIntervals() throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        TimeZone zone = TimeZone.getDefault();
        DiscoveryLog log = new DiscoveryLog(null); log.setSampleMillis(0); log.setSaving(false);
        try {
            ForReconnectPacket packet = new ForReconnectPacket(); packet.reconnectInfo = ":USSouth";
            for (int i = 0; i < 3; i++) log.observe(PacketType.byClass(packet).getIndex(), 1234, packet, "decoded", 0);
            Gson json = new Gson(); JsonObject source = json.toJsonTree(log.snapshot()).getAsJsonObject();
            String[] times = {"2026-01-02T03:04:05Z", "2026-01-02T03:04:05.900Z", "2026-01-02T03:04:15.900Z"};
            for (int i = 0; i < times.length; i++) source.getAsJsonArray("events").get(i).getAsJsonObject().addProperty("timestamp", times[i]);
            source.getAsJsonArray("packets").get(0).getAsJsonObject().addProperty("count", 9007199254740993L);
            DiscoveryLog.Snapshot snapshot = json.fromJson(source, DiscoveryLog.Snapshot.class);
            Locale.setDefault(Locale.Category.FORMAT, Locale.US); TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            LoggingGUI[] view = new LoggingGUI[1];
            SwingUtilities.invokeAndWait(() -> {
                view[0] = new LoggingGUI(log);
                try { Field f = LoggingGUI.class.getDeclaredField("snapshot"); f.setAccessible(true); f.set(view[0], snapshot); }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                JTabbedPane tabs = named(view[0], null, JTabbedPane.class); tabs.setSelectedIndex(2);
                JTable packets = named((Container)tabs.getSelectedComponent(), null, JTable.class);
                packets.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(4, SortOrder.DESCENDING)));
                assertEquals("9,007,199,254,740,993", cell(packets, 0, 4));
                assertEquals(Long.class, packets.getColumnClass(4));
                tabs.setSelectedIndex(1);
                JTable trace = named((Container)tabs.getSelectedComponent(), null, JTable.class);
                assertEquals("2026-01-02 03:04:15", cell(trace, 0, 0));
                assertEquals("+10.0 s", cell(trace, 0, 4)); assertEquals("+900 ms", cell(trace, 1, 4));
                assertEquals("—", cell(trace, 2, 4));
                assertEquals(Instant.class, trace.getColumnClass(0)); assertEquals(Long.class, trace.getColumnClass(4));
                trace.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(4, SortOrder.ASCENDING)));
                assertNull(trace.getValueAt(0, 4)); assertEquals(900L, trace.getValueAt(1, 4)); assertEquals(10000L, trace.getValueAt(2, 4));
                JTextField search = named(view[0], "logging-search", JTextField.class);
                search.setText("+10.0 s"); assertEquals(1, trace.getRowCount()); assertEquals(10000L, trace.getValueAt(0, 4));
                search.setText("2026-01-02 04:04"); assertEquals(0, trace.getRowCount());
                field(view[0], "freeze", JCheckBox.class).setSelected(true);
            });
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY); TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
            SwingUtilities.invokeAndWait(() -> {
                view[0].refresh(); // Re-evaluate the unchanged query against the new display zone, while frozen.
                JTabbedPane tabs = named(view[0], null, JTabbedPane.class);
                JTable trace = named((Container)tabs.getSelectedComponent(), null, JTable.class);
                assertEquals(3, trace.getRowCount());
                assertEquals("+10,0 s", cell(trace, 2, 4));
                assertEquals("2026-01-02 04:04:15", cell(trace, 2, 0));
                JLabel time = (JLabel)trace.prepareRenderer(trace.getCellRenderer(2, 0), 2, 0);
                assertTrue(time.getToolTipText().contains("Europe/Berlin"));
                trace.setRowSelectionInterval(2, 2);
                assertTrue(named(view[0], "logging-details", JTextArea.class).getText().contains(times[2]));
                JTextField search = named(view[0], "logging-search", JTextField.class);
                search.setText("+10,0 s"); assertEquals(1, trace.getRowCount()); assertEquals(10000L, trace.getValueAt(0, 4));
                search.setText("10000"); assertEquals(1, trace.getRowCount());
                search.setText(times[2]); assertEquals(1, trace.getRowCount());
                search.setText(".*"); assertEquals(0, trace.getRowCount());
                search.setText("["); assertEquals(0, trace.getRowCount());
                search.setText("");
                tabs.setSelectedIndex(2);
                JTable packets = named((Container)tabs.getSelectedComponent(), null, JTable.class);
                assertEquals("9.007.199.254.740.993", cell(packets, 0, 4));
                assertEquals(Integer.toString(PacketType.byClass(packet).getIndex()), cell(packets, 0, 0));
            });
            assertEquals("Rendering must not mutate raw evidence", source, json.toJsonTree(snapshot));
        } finally { log.close(); Locale.setDefault(Locale.Category.FORMAT, previous); TimeZone.setDefault(zone); }
    }

    @Test public void diagnosticJsonExportIsLocaleIndependentApartFromItsFreshSnapshotTimes() throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        TimeZone zone = TimeZone.getDefault();
        DiscoveryLog log = new DiscoveryLog(null); log.setSaving(false);
        Path directory = temp.newFolder().toPath();
        LoggingGUI[] panel = new LoggingGUI[1];
        AtomicReference<SwingWorker<Path,Void>> worker = new AtomicReference<>();
        try {
            ForReconnectPacket packet = new ForReconnectPacket(); packet.reconnectInfo = ":USSouth";
            log.observe(PacketType.byClass(packet).getIndex(), 1234, packet, "decoded", 0);
            SwingUtilities.invokeAndWait(() -> panel[0] = new LoggingGUI(log));
            String original = null;
            for (Locale locale : Arrays.asList(Locale.US, Locale.GERMANY)) {
                Locale.setDefault(Locale.Category.FORMAT, locale);
                TimeZone.setDefault(TimeZone.getTimeZone(locale.equals(Locale.US) ? "UTC" : "Europe/Berlin"));
                SwingUtilities.invokeAndWait(() -> worker.set(panel[0].exportTo(directory)));
                Path file = worker.get().get(5, TimeUnit.SECONDS);
                String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                JsonObject document = new Gson().fromJson(content, JsonObject.class);
                JsonObject observations = document.getAsJsonObject("observations");
                // These two values are generated afresh on every export, independently of the locale.
                String exported = observations.get("exportedAt").getAsString();
                String checkpoint = observations.getAsJsonObject("activity").get("checkpointTime").getAsString();
                assertNotNull(Instant.parse(exported)); assertNotNull(Instant.parse(checkpoint));
                content = content.replace("\"exportedAt\": \"" + exported + "\"", "\"exportedAt\": \"SNAPSHOT_TIME\"")
                    .replace("\"checkpointTime\": \"" + checkpoint + "\"", "\"checkpointTime\": \"SNAPSHOT_TIME\"");
                if (original == null) original = content; else assertEquals(original, content);
                assertTrue(content.contains("1234"));
                await(() -> !field(panel[0], "exporting", Boolean.class));
            }
        } finally { log.close(); Locale.setDefault(Locale.Category.FORMAT, previous); TimeZone.setDefault(zone); }
    }

    @Test public void explicitRefreshReformatsUnchangedAndFrozenDiagnosticsWithoutNewSnapshots() throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT); TimeZone zone = TimeZone.getDefault();
        DiscoveryLog log = new DiscoveryLog(null); log.setSampleMillis(0); log.setSaving(false);
        LoggingGUI[] view = new LoggingGUI[1];
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.US); TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            ForReconnectPacket packet = new ForReconnectPacket(); packet.reconnectInfo = ":USSouth";
            for (int i = 0; i < 1234; i++) log.observe(PacketType.byClass(packet).getIndex(), 1234, packet, "decoded", 0);
            SwingUtilities.invokeAndWait(() -> { view[0] = new LoggingGUI(log); view[0].refresh(); });
            await(() -> field(view[0], "snapshot", DiscoveryLog.Snapshot.class) != null && loggingIdle(view[0]));
            DiscoveryLog.Snapshot retained = field(view[0], "snapshot", DiscoveryLog.Snapshot.class);
            Gson json = new Gson(); String raw = json.toJson(retained);
            long copies = log.diagnosticsSnapshotCopies(), fullCopies = log.activitySnapshotStats().full;
            Instant[] selected = new Instant[1];
            SwingUtilities.invokeAndWait(() -> {
                named(view[0], null, JTabbedPane.class).setSelectedIndex(1);
                named(view[0], "logging-search", JTextField.class).setText("reconnect");
                JTable trace = activeTable(view[0]); assertEquals(1234, trace.getRowCount()); trace.setRowSelectionInterval(0, 0);
                selected[0] = (Instant)trace.getValueAt(0, 0);
                assertTrue(field(view[0], "summary", JLabel.class).getText().contains("1,234 frames"));
            });
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY); TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
            SwingUtilities.invokeAndWait(() -> {
                view[0].refresh();
                assertDiagnosticPresentation(view[0], retained, selected[0], true, "Europe/Berlin");
                JTable trace = activeTable(view[0]); JTextField search = named(view[0], "logging-search", JTextField.class);
                search.setText(expectedTime(selected[0], "Europe/Berlin")); assertTrue(trace.getRowCount() > 0);
                search.setText(selected[0].toString()); assertTrue(trace.getRowCount() > 0);
                search.setText(".*"); assertEquals(0, trace.getRowCount());
                search.setText("["); assertEquals(0, trace.getRowCount()); search.setText("");
                named(view[0], null, JTabbedPane.class).setSelectedIndex(2);
                JTable packets = activeTable(view[0]);
                search.setText("1.234"); assertEquals(1, packets.getRowCount());
                assertEquals(1234L, packets.getValueAt(0, 4)); assertEquals(Long.class, packets.getColumnClass(4));
                assertEquals(Integer.toString(PacketType.byClass(packet).getIndex()), cell(packets, 0, 0));
                search.setText("1234"); assertEquals(1, packets.getRowCount());
                search.setText("reconnect"); named(view[0], null, JTabbedPane.class).setSelectedIndex(1);
                activeTable(view[0]).setRowSelectionInterval(0, 0);
            });
            await(() -> loggingIdle(view[0]));
            assertEquals(copies, log.diagnosticsSnapshotCopies()); assertEquals(fullCopies, log.activitySnapshotStats().full);
            assertEquals(raw, json.toJson(retained));
            SwingUtilities.invokeAndWait(() -> field(view[0], "freeze", JCheckBox.class).setSelected(true));
            log.clearDiagnostics();
            Locale[] locales = {Locale.US, Locale.GERMANY, Locale.GERMANY};
            String[] zones = {"UTC", "Europe/Berlin", "UTC"};
            for (int i = 0; i < locales.length; i++) {
                Locale locale = locales[i]; String zoneName = zones[i];
                Locale.setDefault(Locale.Category.FORMAT, locale); TimeZone.setDefault(TimeZone.getTimeZone(zoneName));
                SwingUtilities.invokeAndWait(() -> {
                    view[0].refresh();
                    assertDiagnosticPresentation(view[0], retained, selected[0], locale.equals(Locale.GERMANY), zoneName);
                });
                assertEquals(copies, log.diagnosticsSnapshotCopies()); assertEquals(fullCopies, log.activitySnapshotStats().full);
                assertEquals(raw, json.toJson(retained));
            }
        } finally { log.close(); Locale.setDefault(Locale.Category.FORMAT, previous); TimeZone.setDefault(zone); }
    }
    private static boolean loggingIdle(LoggingGUI panel) {
        return !field(field(panel, "snapshots", Object.class), "running", Boolean.class);
    }
    private static JTable activeTable(LoggingGUI panel) {
        return named((Container)named(panel, null, JTabbedPane.class).getSelectedComponent(), null, JTable.class);
    }
    private static String expectedTime(Instant time, String zone) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(time.atZone(ZoneId.of(zone)));
    }
    private static void assertDiagnosticPresentation(LoggingGUI panel, DiscoveryLog.Snapshot retained, Instant selected, boolean german, String zone) {
        assertSame(retained, field(panel, "snapshot", DiscoveryLog.Snapshot.class));
        assertTrue(field(panel, "summary", JLabel.class).getText().contains(german ? "1.234 frames" : "1,234 frames"));
        JTable table = activeTable(panel); assertEquals(1234, table.getRowCount()); assertTrue(table.getSelectedRow() >= 0);
        assertEquals(Instant.class, table.getColumnClass(0)); assertEquals(Long.class, table.getColumnClass(4));
        assertEquals(selected, table.getValueAt(table.getSelectedRow(), 0));
        assertEquals(expectedTime(selected, zone), cell(table, table.getSelectedRow(), 0));
        assertTrue(named(panel, "logging-details", JTextArea.class).getText().startsWith("Display time zone: " + zone));
    }
}
