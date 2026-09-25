package tomato.gui.logging;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.*;
import packets.packetcapture.logger.DiscoveryLog;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static ui.VisualEvidence.*;
import static tomato.gui.chat.SocialArchiveTestSupport.edt;
import static tomato.gui.history.ArchiveNativeSupport.*;

public class LoggingNativeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence("wave2");
    @Rule public ErrorCollector layouts = new ErrorCollector();

    @Test public void retainedSamplesLinkToExactFieldAndExportFrozenPopulationWithDisplayContext() throws Exception {
        try (DiscoveryLog log = LoggingQueryTest.fixture()) {
            LoggingGUI view = edt(() -> { LoggingGUI v = new LoggingGUI(log, LoggingStateTestSupport.memoryStore()); v.refresh(); return v; });
            JComponent root = edt(() -> shell(view, 9));
            try {
                edt(() -> { evidence.show(root, "Logging samples", 1240, 800, 13); find(view, JTabbedPane.class, c -> true).setSelectedIndex(4); return null; });
                await(() -> named(view, "logging-table-2", JTable.class).getRowCount() == 2);
                edt(() -> {
                    find(view, JTabbedPane.class, c -> true).setSelectedIndex(4);
                    named(view, "logging-search", JTextField.class).setText("800");
                    JTable table = named(view, "logging-table-2", JTable.class); assertEquals(1, table.getRowCount()); table.setRowSelectionInterval(0, 0);
                    button(view, "Pause this view").doClick(); return null;
                });
                matrix(evidence, layouts, root, "logging-retained-filtered", () -> named(view, "logging-table-2", JTable.class).getRowCount() == 1, () -> {
                    tableRows(named(view, "logging-table-2", JTable.class));
                    completeButton(button(view, "Copy full detail")); completeButton(button(view, "Open field definition")); completeButton(button(view, "Export report"));
                    reachable(named(view, "logging-search", JTextField.class));
                    assertTrue(named(view, "logging-details", JTextArea.class).getText().contains("800"));
                    completeText(named(view, "logging-details", JTextArea.class));
                });
                edt(() -> { named(view, "logging-field-choice", JComboBox.class).setSelectedItem("status[].stats[].statValue"); button(view, "Open field definition").doClick(); return null; });
                edt(() -> { assertEquals(5, find(view, JTabbedPane.class, c -> true).getSelectedIndex()); assertTrue(named(view, "logging-details", JTextArea.class).getText().contains("status[].stats[].statValue")); evidence.capture("logging-linked-field"); return null; });
                LoggingQueryTest.tick(log, 700, 80);
                Path file = edt(() -> view.exportTo(temp.getRoot().toPath(), LoggingReport.Source.DISPLAYED)).get(10, TimeUnit.SECONDS);
                assertEquals(2, json(file).getAsJsonObject("observations").getAsJsonArray("events").size());
                assertFalse(log.isSaving());
            } finally { edt(() -> { evidence.closeWindow(); return null; }); }
        }
    }
}
