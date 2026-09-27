package tomato.gui.history;

// Explicit AWT imports: java.awt.* would make ArchiveFixtures.Event ambiguous with java.awt.Event.
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.activity.ActivityPanel;
import tomato.gui.activity.ActivityQueries;
import tomato.gui.kit.ColumnKind;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import static org.junit.Assert.*;
import static tomato.history.archive.ArchiveFixtures.*;

public class HistoryTablesKindTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void kindsSetDefaultWidthsTextRenderersAndResetTargets() throws Exception {
        Path root = temp.newFolder().toPath(), scratch = temp.newFolder().toPath(); String id = session(root, 5);
        try (SessionStore store = new SessionStore(root, false, "test"); ArchiveResult<Event> result = ArchiveResult.open(store, query(id), adapter(), scratch, new Cancellation())) {
            ArchivePage<Event> page = result.page(0, 5, new Cancellation());
            SwingUtilities.invokeAndWait(() -> {
                JTable table = HistoryTables.queried("kinds", columns(), page, Collections.emptyMap(), query(id), q -> {}, row -> {});
                Font font = table.getFont();
                assertEquals(ColumnKind.COUNT.width(font), table.getColumnModel().getColumn(0).getPreferredWidth());
                assertEquals(ColumnKind.TEXT.width(font), table.getColumnModel().getColumn(1).getPreferredWidth());
                assertEquals("Columns without a kind keep the legacy width", 145, table.getColumnModel().getColumn(2).getPreferredWidth());
                assertNull("Numbers keep their typed renderer", table.getColumnModel().getColumn(0).getCellRenderer());
                assertNotNull("Text columns use the kit renderer", table.getColumnModel().getColumn(1).getCellRenderer());
                assertEquals("Model values are unchanged", 0, table.getValueAt(0, 0));
                ViewState.Table defaults = HistoryTables.columnState(table, "Default");
                HistoryTables.applyColumns(table, new ViewState.Table("Custom", Arrays.asList(new ViewState.Column("value", 300, true),
                    new ViewState.Column("text", 40, true), new ViewState.Column("group", 90, true))));
                assertEquals(300, table.getColumnModel().getColumn(0).getWidth());
                JComponent controls = HistoryTables.controls(table, defaults, Collections.emptyMap(), layout -> {});
                for (Component c : controls.getComponents()) if (c instanceof JButton && "Reset columns".equals(((JButton) c).getText())) ((JButton) c).doClick();
                assertEquals(ColumnKind.COUNT.width(font), table.getColumnModel().getColumn(0).getWidth());
                assertEquals(ColumnKind.TEXT.width(font), table.getColumnModel().getColumn(1).getWidth());
                JTable adhoc = new JTable(new DefaultTableModel(new Object[]{"When", "Who"}, 0));
                adhoc.getColumnModel().getColumn(1).setMinWidth(400);
                Map<String, ColumnKind> kinds = new HashMap<>(); kinds.put("When", ColumnKind.DATE_TIME); kinds.put("Who", ColumnKind.PLAYER);
                HistoryTables.kinds(adhoc, kinds);
                assertEquals(ColumnKind.DATE_TIME.width(adhoc.getFont()), adhoc.getColumnModel().getColumn(0).getPreferredWidth());
                assertEquals("Kind widths never shrink below an existing minimum", 400, adhoc.getColumnModel().getColumn(1).getPreferredWidth());
                assertNull("Ad-hoc renderers are untouched", adhoc.getColumnModel().getColumn(0).getCellRenderer());
            });
        }
    }

    @Test public void savedRunsColumnsDefaultToTheirKinds() throws Exception {
        Path scratch = temp.newFolder().toPath(); ArchiveNativeSupport.Memory memory = new ArchiveNativeSupport.Memory();
        try (SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "kinds")) {
            ActivityJournal.Visit v = new ActivityJournal.Visit(); v.id = "visit"; v.map = "Lost Halls"; v.started = 1000; v.lastSeen = v.ended = 61_000; store.put("runs", v.id, v); store.flush();
            ArchiveWorkspace<ActivityQueries.Row, ActivityQueries.Filters, ActivityQueries.Sort> workspace =
                edt(() -> ActivityPanel.workspace(store, new JLabel("Live"), ActivityPanel.Mode.RUNS, scratch, memory.states));
            try {
                edt(() -> { workspace.showSaved(); return null; }); await(() -> ArchiveNativeSupport.ready(workspace));
                edt(() -> {
                    JTable table = find(workspace, "saved-activity-table"); Font font = table.getFont();
                    assertEquals(ColumnKind.DUNGEON.width(font), table.getColumn("map").getPreferredWidth());
                    assertEquals(ColumnKind.DATE_TIME.width(font), table.getColumn("time").getPreferredWidth());
                    assertEquals(ColumnKind.STATUS.width(font), table.getColumn("outcome").getPreferredWidth());
                    return null; });
            } finally { edt(() -> { workspace.close(); return null; }); }
        }
    }

    private static List<HistoryTables.Column<Event, ?>> columns() {
        return Arrays.asList(new HistoryTables.Column<>("value", "Value", Integer.class, e -> e.value, null, ColumnKind.COUNT),
            new HistoryTables.Column<>("text", "Message", String.class, e -> e.text, null, ColumnKind.TEXT),
            new HistoryTables.Column<>("group", "Group", String.class, e -> e.group, null));
    }
    @FunctionalInterface private interface Checked<T> { T get() throws Exception; }
    private static <T> T edt(Checked<T> value) throws Exception {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(value.get()); } catch (Throwable t) { failure.set(t); } });
        if (failure.get() != null) throw new AssertionError(failure.get()); return result.get();
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < end) { if (edt(condition::getAsBoolean)) return; Thread.sleep(20); }
        fail("Timed out waiting for EDT state");
    }
    private static JTable find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTable && name.equals(child.getName())) return (JTable) child;
            if (child instanceof Container) { JTable found = find((Container) child, name); if (found != null) return found; }
        }
        return null;
    }
}
