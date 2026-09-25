package tomato.gui.dps;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.*;
import tomato.backend.data.*;
import tomato.gui.history.ArchiveNativeSupport.Memory;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static ui.VisualEvidence.*;
import static tomato.gui.chat.SocialArchiveTestSupport.edt;
import static tomato.gui.history.ArchiveNativeSupport.*;

public class EncounterLibraryNativeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence("wave2");
    @Rule public ErrorCollector layouts = new ErrorCollector();

    @Test public void importedIdentityAndHiddenExportChecksRemainUsableInCompactLibrary() throws Exception {
        TomatoData data = new TomatoData();
        data.dpsData.add(EncounterCatalogTest.encounter("Synthetic Lost Halls"));
        data.dpsData.add(EncounterCatalogTest.encounter("Synthetic Shatters"));
        DpsGUI dps = edt(() -> new DpsGUI(data));
        DungeonListGUI library = edt(() -> new DungeonListGUI(dps, data, new Memory().states));
        Path imported = temp.getRoot().toPath().resolve("synthetic-import.dps");
        EncounterCatalogTest.write(imported, EncounterCatalogTest.encounter("Synthetic imported encounter"));
        try {
            edt(() -> library.importFile(imported.toFile())).get(10, TimeUnit.SECONDS);
            await(() -> named(library, "saved-encounters", JTable.class).getRowCount() == 4 && button(library, "View imported encounter").isEnabled());
            edt(() -> {
                button(library, "View imported encounter").doClick();
                JTable table = named(library, "saved-encounters", JTable.class);
                table.getActionMap().get("toggle-export").actionPerformed(null); return null;
            });
            String selected = edt(dps::currentEncounterId); assertNotNull(selected);
            matrix(evidence, layouts, library, "encounter-library", () -> button(library, "View imported encounter").isEnabled(), () -> {
                assertEquals(selected, dps.currentEncounterId()); assertTrue(dps.encounters().checked(selected));
                tableRows(named(library, "saved-encounters", JTable.class));
                for (String label : new String[]{"Load", "Save checked", "View imported encounter", "Reset filters"}) completeButton(button(library, label));
                completeText(named(library, "encounter-details", JTextArea.class));
                completeText(named(library, "encounter-status", JTextArea.class));
            });
            edt(() -> { named(library, "encounter-search", JTextField.class).setText("missing literal [encounter]"); assertEquals(1, named(library, "saved-encounters", JTable.class).getRowCount()); assertEquals(selected, dps.currentEncounterId()); evidence.capture("encounter-library-hidden-check"); return null; });
            Path output = temp.newFolder().toPath();
            assertEquals(1, edt(() -> library.exportFiles(output.toFile(), false)).get(10, TimeUnit.SECONDS).intValue());
            try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(output)) { assertEquals(1, files.count()); }
        } finally { edt(() -> { evidence.closeWindow(); return null; }); }
    }
}
