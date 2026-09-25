package tomato.gui.history;

import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.*;
import tomato.history.SessionStore;
import ui.VisualEvidence;
import static org.junit.Assert.*;
import static ui.VisualEvidence.*;
import static tomato.gui.chat.SocialArchiveTestSupport.edt;
import static tomato.gui.history.ArchiveNativeSupport.*;
import static tomato.history.archive.ArchiveFixtures.session;

public class HistoryLibraryNativeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence("wave2");
    @Rule public ErrorCollector layouts = new ErrorCollector();

    @Test public void malformedMetadataStaysVisibleWhileValidSelectionOpensAndSurvivesRefresh() throws Exception {
        Path root = temp.newFolder().toPath(); String valid = session(root, 3), bad = session(root, 2);
        Files.write(root.resolve(bad).resolve("session.json"), new byte[]{'{'});
        try (SessionStore store = new SessionStore(root, false, "native-library")) {
            AtomicReference<String> opened = new AtomicReference<>();
            HistoryLibrary library = edt(() -> new HistoryLibrary(store, opened::set));
            try {
                await(() -> library.entries().size() == 3);
                edt(() -> { library.select(valid); return null; });
                matrix(evidence, layouts, library, "history-library", () -> library.entries().size() == 3, () -> {
                    assertEquals(valid, library.selectedId());
                    assertEquals(1, library.entries().stream().filter(entry -> !entry.readable()).count());
                    tableRows(named(library, "history-library-table", JTable.class));
                    reachable(named(library, "history-library-search", JTextField.class));
                    for (String label : new String[]{"Refresh", "Open session", "Rename…", "Delete…", "Import old folder…"}) completeButton(button(library, label));
                    completeText(find(library, JTextArea.class, area -> area.getText().contains("unreadable")));
                });
                key(edt(() -> named(library, "history-library-table", JTable.class)), java.awt.event.KeyEvent.VK_ENTER);
                await(() -> valid.equals(opened.get()));
                edt(() -> { library.reload(); return null; });
                await(() -> valid.equals(library.selectedId())); assertEquals(valid, opened.get());
                edt(() -> { library.select(bad); assertFalse(button(library, "Open session").isEnabled()); return null; });
                edt(() -> { named(library, "history-library-search", JTextField.class).setText("missing literal [session]"); assertEquals(0, named(library, "history-library-table", JTable.class).getRowCount()); evidence.capture("history-library-empty"); return null; });
            } finally { edt(() -> { library.close(); evidence.closeWindow(); return null; }); }
        }
    }
}
