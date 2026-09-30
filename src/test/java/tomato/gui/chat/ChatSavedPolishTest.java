package tomato.gui.chat;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.SessionPanel;
import tomato.gui.kit.DisplayModeModel;
import tomato.history.SessionStore;
import tomato.history.archive.ArchivePage;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.*;

/**
 * P6b Polish C: saved Chat keeps the pinned revision out of Simple (spec §3.2), and its Local receipt column reads one local
 * "yyyy-MM-dd HH:mm:ss" format whatever the saved precision, while the model, sorting, search and exports keep the saved value.
 * Synthetic messages and an in-memory display mode and view states only.
 */
public class ChatSavedPolishTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final Map<String, String> prefs = new HashMap<>();
    /** Simple until a test switches it; never the application's mode. */
    private final DisplayModeModel mode = new DisplayModeModel(prefs::get, prefs::put);
    /** Saved at whole minutes (ISO text without seconds) and with milliseconds (ISO text with a fraction). */
    private static final LocalDateTime MINUTE = LocalDateTime.of(2026, 9, 29, 11, 48), FRACTION = LocalDateTime.of(2026, 9, 29, 15, 21, 40, 123_000_000);

    private final class Fixture implements AutoCloseable {
        final SessionStore store;
        final ArchiveWorkspace<ChatArchiveClient.Row, ChatArchiveClient.Facets, ChatArchiveClient.Sort> workspace;
        Fixture() throws Exception {
            Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath();
            store = new SessionStore(root, true, "synthetic-chat");
            store.append("chat", new ChatMessage(MINUTE, ChatMessage.Channel.GUILD, "Echo", "", "Echo", "Nice drop earlier", ""));
            store.append("chat", new ChatMessage(FRACTION, ChatMessage.Channel.WORLD, "Kai", "", "Kai", "Anyone for the Shatters?", ""));
            store.flush();
            ChatArchiveClient client = new ChatArchiveClient(store, new ChatFilters(), null, scratch, mode);
            workspace = edt(() -> SessionPanel.queried(store, "chat", new JPanel(), client, new ArchiveNativeSupport.Memory().states));
            edt(() -> { workspace.changeQuery(ChatArchiveClient.query().withScope(SessionStore.ALL)); return null; });
            await(() -> ArchiveNativeSupport.ready(workspace) && workspace.displayedPage().matches == 2);
        }
        @Override public void close() throws Exception { try { edt(() -> { workspace.close(); return null; }); } finally { store.close(); } }
    }

    @Test public void theSavedCountLineShowsThePinnedRevisionInAnalystOnly() throws Exception {
        try (Fixture f = new Fixture()) {
            edt(() -> {
                ArchivePage<ChatArchiveClient.Row> page = f.workspace.displayedPage();
                String revision = page.revision.substring(0, 8);
                JTextArea note = named(f.workspace, "chat-archive-population", JTextArea.class);
                assertNotNull("The count line", note);
                assertTrue("Simple keeps the plain count: " + note.getText(), note.getText().startsWith("2 displayed / 2 matching messages · page 1"));
                assertFalse("Simple hides the revision: " + note.getText(), note.getText().contains(revision));
                assertFalse(note.getText().contains("pinned"));
                assertTrue("The export hint stays", note.getText().contains("use workspace Export selected / page / all matches"));
                mode.set(DisplayModeModel.Mode.ANALYST);
                assertTrue("Analyst shows the pinned revision: " + note.getText(), note.getText().contains("pinned " + revision));
                mode.set(DisplayModeModel.Mode.SIMPLE);
                assertFalse("The line rebinds on a mode change", note.getText().contains("pinned"));
                return null;
            });
        }
    }

    @Test public void localReceiptReadsOneLocalFormatAndSearchMatchesIt() throws Exception {
        try (Fixture f = new Fixture()) {
            edt(() -> {
                JTable table = named(f.workspace, "chat-archive-messages", JTable.class);
                int column = table.getColumnModel().getColumnIndex("time");
                Map<LocalDateTime, String> shown = new HashMap<>();
                for (int row = 0; row < table.getRowCount(); row++) {
                    Object value = table.getValueAt(row, column);
                    assertTrue("The model keeps the saved LocalDateTime", value instanceof LocalDateTime);
                    JLabel cell = (JLabel) table.prepareRenderer(table.getCellRenderer(row, column), row, column);
                    shown.put((LocalDateTime) value, cell.getText());
                }
                assertEquals("Whole minutes read with seconds", "2026-09-29 11:48:00", shown.get(MINUTE));
                assertEquals("Fractions are not shown", "2026-09-29 15:21:40", shown.get(FRACTION));
                // Analyst reads the same absolute text (Chat has no relative times).
                mode.set(DisplayModeModel.Mode.ANALYST);
                int row = 0; while (!MINUTE.equals(table.getValueAt(row, column))) row++;
                assertEquals("2026-09-29 11:48:00", ((JLabel) table.prepareRenderer(table.getCellRenderer(row, column), row, column)).getText());
                mode.set(DisplayModeModel.Mode.SIMPLE);
                f.workspace.changeQuery(f.workspace.state().query.withText("2026-09-29 11:48:00"));
                return null;
            });
            await(() -> ArchiveNativeSupport.ready(f.workspace) && f.workspace.displayedPage().matches == 1);
            assertEquals("Search matches the shown text", MINUTE, edt(() -> f.workspace.displayedPage().rows.get(0).value.message.received));
            List<String> exported = new ArrayList<>();
            for (tomato.history.archive.ArchiveExport.Column<ChatArchiveClient.Row> column : new ChatArchiveClient(f.store, new ChatFilters(), null, temp.newFolder().toPath(), mode).exportColumns())
                if (column.name.startsWith("Local receipt")) exported.add(String.valueOf(column.value.apply(edt(() -> f.workspace.displayedPage().rows.get(0).value))));
            assertEquals("Exports keep the saved value", Collections.singletonList(MINUTE.toString()), exported);
        }
    }
}
