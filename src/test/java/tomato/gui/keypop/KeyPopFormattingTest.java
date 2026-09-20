package tomato.gui.keypop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static tomato.gui.modern.FormattingTestSupport.*;

public class KeyPopFormattingTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void existingRenderersFollowFormatLocaleAndZoneWhileCsvStaysByteIdentical() throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        TimeZone zone = TimeZone.getDefault();
        Instant time = Instant.parse("2026-01-02T03:04:05Z");
        KeyPopHistory history = new KeyPopHistory();
        for (int i = 0; i < 2000; i++) history.add(new KeyPopEvent(time, i < 210 ? "Aster" : "Wren", "Lost Halls", KeyPopEvent.Kind.KEY));
        KeyPopDashboard[] view = new KeyPopDashboard[1];
        Path csv = temp.newFile("pops.csv").toPath();
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.US);
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            SwingUtilities.invokeAndWait(() -> {
                view[0] = new KeyPopDashboard(history);
                assertEquals("2,000", view[0].metrics[0].getText());
                assertEquals("1,790", cell(view[0].players, 0, 1));
                assertEquals("10.5%", cell(view[0].players, 1, 6));
                assertEquals("0", cell(view[0].players, 1, 3));
                assertEquals("2026-01-02 03:04:05", cell(view[0].events, 0, 0));
            });
            KeyPopDashboard.writeCsv(csv, history.snapshot().events);
            byte[] before = Files.readAllBytes(csv);
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
            SwingUtilities.invokeAndWait(() -> {
                KeyPopDashboard ui = view[0]; ui.refresh();
                assertEquals("2.000", ui.metrics[0].getText());
                assertEquals("1.790", cell(ui.players, 0, 1));
                assertEquals("10,5%", cell(ui.players, 1, 6));
                assertEquals("2026-01-02 04:04:05", cell(ui.events, 0, 0));
                JLabel timestamp = (JLabel)ui.events.prepareRenderer(ui.events.getCellRenderer(0, 0), 0, 0);
                assertTrue(timestamp.getToolTipText().contains("Europe/Berlin"));
                assertEquals(Integer.class, ui.players.getColumnClass(1));
                assertEquals(Double.class, ui.players.getColumnClass(6));
                assertEquals(Instant.class, ui.events.getColumnClass(0));
                ui.players.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(1, SortOrder.ASCENDING)));
                assertEquals(210, ui.players.getValueAt(0, 1));
                assertEquals(1790, ui.players.getValueAt(1, 1));
            });
            KeyPopDashboard.writeCsv(csv, history.snapshot().events);
            assertArrayEquals(before, Files.readAllBytes(csv));
            assertTrue(new String(before, StandardCharsets.UTF_8).contains("\"2026-01-02T03:04:05Z\""));
        } finally { Locale.setDefault(Locale.Category.FORMAT, previous); TimeZone.setDefault(zone); }
    }
}
