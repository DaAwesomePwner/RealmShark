package tomato.gui.history;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import org.junit.rules.ErrorCollector;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.Motion;
import tomato.gui.kit.OverflowMenu;
import tomato.history.archive.*;
import ui.VisualEvidence;
import util.PreferencesStore;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static ui.VisualEvidence.*;
import static tomato.gui.chat.SocialArchiveTestSupport.edt;

/** Shared native checks use the production query/state/export controls and isolated storage. */
public final class ArchiveNativeSupport {
    private static VisualEvidence activeEvidence;
    private static String activeCapture;
    private ArchiveNativeSupport() {}

    public static final class Memory {
        private final Map<String,String> values = new ConcurrentHashMap<>();
        private final AtomicLong revision = new AtomicLong();
        public boolean failSaves;
        public final ViewStateStore states = new ViewStateStore(new ViewStateStore.Storage() {
            public String get(String key) { return values.get(key); }
            public CompletionStage<PreferencesStore.SaveResult> put(String key, String value) {
                values.put(key, value);
                long next = revision.incrementAndGet();
                return CompletableFuture.completedFuture(failSaves
                    ? PreferencesStore.SaveResult.failed(next, new IOException("Synthetic view-state write denied"))
                    : PreferencesStore.SaveResult.saved(next));
            }
        });
    }

    /** Wait on the test thread; the EDT keeps dispatching real reader/export completions. */
    public static void await(BooleanSupplier condition) throws Exception {
        assertFalse("Wait outside the EDT", SwingUtilities.isEventDispatchThread());
        CompletableFuture<Void> ready = new CompletableFuture<>();
        Timer timer = new Timer(10, event -> {
            try { if (condition.getAsBoolean()) ready.complete(null); }
            catch (Throwable failure) { ready.completeExceptionally(failure); }
        });
        SwingUtilities.invokeAndWait(timer::start);
        try { ready.get(30, TimeUnit.SECONDS); }
        finally { SwingUtilities.invokeAndWait(timer::stop); }
    }

    public static boolean ready(ArchiveWorkspace<?,?,?> workspace) {
        return !workspace.loading() && workspace.displayedPage() != null;
    }

    /** Posted Swing key dispatch after native focus; this does not synthesize OS keyboard input. */
    public static void key(JComponent target, int keyCode) throws Exception {
        Window window = edt(() -> SwingUtilities.getWindowAncestor(target));
        edt(() -> { window.toFront(); window.requestFocus(); return null; });
        await(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow() == window);
        edt(() -> {
            if (target instanceof JTable) {
                JTable table = (JTable)target; reachable(table, table.getCellRect(Math.max(0, table.getSelectedRow()), 0, true));
            } else reachable(target);
            target.requestFocusInWindow(); return null;
        });
        await(() -> target.isFocusOwner() && KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow() == window);
        CompletableFuture<Void> delivered = new CompletableFuture<>();
        KeyEventDispatcher observer = event -> {
            if (event.getID() == java.awt.event.KeyEvent.KEY_PRESSED && event.getKeyCode() == keyCode) {
                if (event.getComponent() == target && target.isFocusOwner()) delivered.complete(null);
                else delivered.completeExceptionally(new AssertionError("Key reached a different focus owner"));
            }
            return false;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(observer);
        try {
            long when = System.currentTimeMillis(); EventQueue queue = Toolkit.getDefaultToolkit().getSystemEventQueue();
            queue.postEvent(new java.awt.event.KeyEvent(target, java.awt.event.KeyEvent.KEY_PRESSED, when, 0, keyCode, java.awt.event.KeyEvent.CHAR_UNDEFINED));
            queue.postEvent(new java.awt.event.KeyEvent(target, java.awt.event.KeyEvent.KEY_RELEASED, when, 0, keyCode, java.awt.event.KeyEvent.CHAR_UNDEFINED));
            delivered.get(5, TimeUnit.SECONDS);
        } finally { KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(observer); }
    }

    public static void matrix(VisualEvidence evidence, ErrorCollector errors, JComponent root, String name,
                              BooleanSupplier ready, Runnable assertions) throws Exception {
        for (int font : new int[]{13,24}) for (int width : new int[]{1240,680}) {
            edt(() -> { evidence.show(root, name, width, width == 680 ? 520 : 800, font); return null; });
            await(ready); evidence.settle(); await(ready);
            edt(() -> {
                String file = name + "-" + width + "-" + font;
                evidence.capture(file);
                activeEvidence = evidence; activeCapture = file;
                try { errors.checkSucceeds(() -> { assertions.run(); return null; }); }
                finally { activeEvidence = null; activeCapture = null; }
                evidence.capture(file + "-targets");
                return null;
            });
        }
    }

    public static void tableRows(JTable table) {
        JViewport viewport = (JViewport)table.getParent();
        int rows = Math.min(3, table.getRowCount());
        assertTrue("Fixture must contain matching data", rows > 0);
        System.out.println(table.getName() + " viewport=" + viewport.getExtentSize() + ", rowHeight=" + table.getRowHeight());
        assertTrue("Usable matching rows: " + table.getName(), viewport.getHeight() >= rows * table.getRowHeight());
        reachable(table, new Rectangle(0, 0, Math.min(viewport.getWidth(), table.getWidth()), rows * table.getRowHeight()));
        if (activeEvidence != null) activeEvidence.capture(activeCapture + "-" + table.getName() + "-table");
    }

    public static void archiveControls(ArchiveWorkspace<?,?,?> workspace, String module, String table, String detail) {
        assertTrue(workspace.state().archive); assertTrue(ready(workspace));
        JTable rows = named(workspace, table, JTable.class); assertNull("Global order must not become page-local", rows.getRowSorter());
        tableRows(rows);
        completeButton(scope(workspace));
        reachable(named(workspace, module + "-history-search", JTextField.class));
        for (String label : new String[]{"Previous page", "Next page"}) completeButton(button(workspace, label));
        for (String suffix : new String[]{"live", "current", "all", "library", "refresh"}) scopeItem(workspace, suffix);
        OverflowMenu more = more(workspace); reachable(more);
        for (String label : new String[]{"Refresh", "Saved views", "Save current view…", "Reset saved state",
                "Export selected…", "Export page…", "Export all matches…", "Open export folder"}) assertNotNull("Overflow action " + label, more.item(label));
        assertTrue("⋯ Refresh shows in saved history", more.item("Refresh").isVisible());
        completeButton(find(workspace, AbstractButton.class, b -> b.isShowing() && "Columns…".equals(b.getText())));
        if (detail != null) completeText(named(workspace, detail, JTextArea.class));
    }

    /** The workspace's ⋯ menu: Refresh (saved history), saved views and exports; History library… is in the Scope menu. */
    public static OverflowMenu more(ArchiveWorkspace<?,?,?> workspace) {
        String module = workspace.getName().substring(0, workspace.getName().length() - "-session-view".length());
        return named(workspace, module + "-more", OverflowMenu.class);
    }

    /** A ⋯ action, or else a Scope menu item (History library… moved there in P6b), by its exact text. */
    public static JMenuItem action(ArchiveWorkspace<?,?,?> workspace, String label) {
        JMenuItem item = more(workspace).item(label);
        if (item == null) for (Component child : scope(workspace).menu().getComponents())
            if (child instanceof JMenuItem && label.equals(((JMenuItem)child).getText())) { item = (JMenuItem)child; break; }
        assertNotNull("Overflow or Scope action " + label, item); return item;
    }

    /** The workspace's Scope chip: live vs saved and the session picker since P6b. It is always inside the workspace. */
    public static ScopeChip scope(ArchiveWorkspace<?,?,?> workspace) {
        String module = workspace.getName().substring(0, workspace.getName().length() - "-session-view".length());
        return named(workspace, module + "-scope", ScopeChip.class);
    }

    /** A Scope menu item by suffix ("live", "current", "all", "session:&lt;id&gt;", "library", "refresh"); fails when absent. */
    public static JMenuItem scopeItem(ArchiveWorkspace<?,?,?> workspace, String suffix) {
        JMenuItem item = scope(workspace).item(suffix); assertNotNull("Scope item " + suffix, item); return item;
    }

    /** Chip labels in display order, read from each chip's remove button ("Remove filter: <label>"). */
    public static List<String> chipLabels(FilterBar bar) { List<String> labels = new ArrayList<>(); collectChips(bar, labels); return labels; }

    private static void collectChips(Container root, List<String> labels) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton && "remove-filter".equals(child.getName()))
                labels.add(((AbstractButton)child).getAccessibleContext().getAccessibleName().substring("Remove filter: ".length()));
            else if (child instanceof Container) collectChips((Container)child, labels);
        }
    }

    public static void removeChip(FilterBar bar, String label) {
        find(bar, AbstractButton.class, b -> "remove-filter".equals(b.getName())
            && ("Remove filter: " + label).equals(b.getAccessibleContext().getAccessibleName())).doClick();
    }

    /**
     * Opens or closes a filter drawer without its 100 ms animation (Reduce motion for this call only), so geometry
     * assertions see the final layout. Tests that open a drawer close it again in finally: the state persists.
     */
    public static void drawer(FilterBar bar, boolean open) {
        String motion = PropertiesManager.getProperty(Motion.REDUCE_KEY);
        PropertiesManager.setProperties(Motion.REDUCE_KEY, "true");
        try { bar.setDrawerOpen(open); } finally { PropertiesManager.setProperties(Motion.REDUCE_KEY, motion == null ? "" : motion); }
    }

    public static boolean textPresent(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextComponent && ((JTextComponent)child).getText().contains(text)) return true;
            if (child instanceof JLabel && ((JLabel)child).getText() != null && ((JLabel)child).getText().contains(text)) return true;
            if (child instanceof Container && textPresent((Container)child, text)) return true;
        }
        return false;
    }

    /** Capture the actual failure message after scrolling it into the native viewport. */
    public static void failureStatus(Container root, String text) {
        JTextArea message = find(root, JTextArea.class, area -> area.isShowing() && area.getText().contains(text));
        completeText(message);
        if (activeEvidence != null) activeEvidence.capture(activeCapture + "-failure-message");
    }

    public static void failExport(ArchiveWorkspace<?,?,?> workspace) throws Exception {
        Path blocked = Files.createTempFile(Paths.get("."), "synthetic-export-blocked-", ".tmp");
        try {
            SwingWorker<Path,Void> worker = edt(() -> workspace.exportTo(blocked, "synthetic", ExportSelection.all(), ArchiveExport.Format.JSON));
            try { worker.get(15, TimeUnit.SECONDS); fail("Export destination is a file"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof IOException); }
            await(() -> textPresent(workspace, "Export failed:") && action(workspace, "Export all matches…").isEnabled());
        } finally { Files.deleteIfExists(blocked); }
    }

    /** Exercise the actual toolbar's modal population preview, then cancel before a destination chooser. */
    public static String preview(ArchiveWorkspace<?,?,?> workspace, VisualEvidence evidence, String name) throws Exception {
        await(() -> action(workspace, "Export selected…").isEnabled());
        SwingUtilities.invokeLater(() -> action(workspace, "Export selected…").doClick());
        await(() -> dialog("Export pinned revision") != null);
        JDialog preview = edt(() -> dialog("Export pinned revision"));
        try {
            return edt(() -> {
                JTextArea message = find(preview, JTextArea.class, a -> "Export population and revision".equals(a.getAccessibleContext().getAccessibleName()));
                evidence.capture(preview, name); completeText(message); return message.getText();
            });
        } finally {
            edt(() -> { button(preview, "Cancel").doClick(); return null; });
            await(() -> !preview.isShowing() && action(workspace, "Export all matches…").isEnabled());
        }
    }

    public static JDialog dialog(String title) {
        for (Window window : Window.getWindows())
            if (window instanceof JDialog && window.isShowing() && title.equals(((JDialog)window).getTitle())) return (JDialog)window;
        return null;
    }

    public static JsonObject json(Path file) throws IOException {
        try (java.io.Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { return JsonParser.parseReader(reader).getAsJsonObject(); }
    }
}
