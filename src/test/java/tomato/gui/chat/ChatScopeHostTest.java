package tomato.gui.chat;

import java.awt.*;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.history.ArchiveNativeSupport;
import tomato.gui.history.ArchiveNativeSupport.Memory;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.LiveFilterHost;
import tomato.gui.history.ScopeChip;
import tomato.gui.history.ViewState;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.FilterBarAssert;
import tomato.gui.kit.OverflowMenu;
import tomato.gui.modern.TestPages;
import tomato.history.SessionStore;
import ui.VisualEvidence;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.*;

/**
 * P6b Task 12: the Chat page keeps one filter row. While live, its own {@code chat-live} row lends the workspace the Scope chip;
 * saved Chat keeps the workspace row. The live table's column tools and the named live views are {@code chat-live} ⋯ items.
 * Synthetic history and in-memory view states only.
 */
public class ChatScopeHostTest {
    /** Drawer-open keys of both bars and the legacy ignored-player key the live view reads. */
    private static final String[] KEYS = {"ui.filters.chat-live.open", "ui.filters.chat.open", ChatExplorer.SHOW_IGNORED_PLAYERS};
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Rule public VisualEvidence evidence = new VisualEvidence("p6b-chat");
    private final Map<String, String> saved = new HashMap<>();

    @Before public void isolate() {
        for (String key : KEYS) saved.put(key, PropertiesManager.getProperty(key));
        for (String key : KEYS) PropertiesManager.setProperties(key, "false");
    }
    @After public void restore() {
        for (String key : KEYS) PropertiesManager.setProperties(key, saved.get(key) == null ? "" : saved.get(key));
    }

    @Test public void theLiveRowHostsTheChipAndTheWorkspaceRowHides() throws Exception {
        try (Fixture f = new Fixture()) {
            edt(() -> {
                assertTrue("ChatGUI lends the live explorer's row", f.chat instanceof LiveFilterHost);
                FilterBar bar = f.workspace.liveFilterBar();
                assertNotNull("Chat has a live row", bar);
                assertEquals("chat-live-filter-bar", bar.getName());
                assertTrue("The row is the live explorer's own", SwingUtilities.isDescendingFrom(bar, f.chat));
                assertTrue("The chip sits in the live row", SwingUtilities.isDescendingFrom(ArchiveNativeSupport.scope(f.workspace), bar));
                assertFalse("The workspace row hides while the live row hosts the chip", f.workspace.filterBar().isVisible());
                FilterBarAssert.assertChipInVisibleBar(f.workspace);
                assertEquals("Scope: Live", ArchiveNativeSupport.scope(f.workspace).getText());
                return null;
            });
        }
    }

    @Test public void theLiveRowIsOneFilterRowAt1240x800Font13InTheShell() throws Exception {
        try (Fixture f = new Fixture()) {
            JComponent shell = edt(() -> TestPages.shell("chat", f.workspace));
            try {
                edt(() -> { evidence.show(shell, "Chat live filter row", 1240, 800, 13); return null; });
                evidence.settle();
                edt(() -> {
                    evidence.capture("chat-live-1240-13");
                    FilterBar bar = named(f.chat, "chat-live-filter-bar", FilterBar.class);
                    assertFalse("The drawer is closed", bar.drawerOpen());
                    assertTrue("The row has the shell's content width (" + bar.getWidth() + ")", bar.getWidth() > 850 && bar.getWidth() < 1100);
                    FilterBarAssert.assertChipInVisibleBar(f.workspace);
                    FilterBarAssert.assertOneRow(bar);
                    ScopeChip chip = ArchiveNativeSupport.scope(f.workspace);
                    System.out.println("Chat live bar width=" + bar.getWidth() + ", search slot=" + bar.searchSlot().getWidth()
                        + ", chip=" + chip.getWidth() + ", chip x=" + SwingUtilities.convertPoint(chip, 0, 0, bar).x
                        + ", in search slot=" + SwingUtilities.isDescendingFrom(chip, bar.searchSlot()));
                    assertTrue("The chip shows", chip.isShowing());
                    VisualEvidence.completeButton(chip);
                    assertTrue("The live ⋯ shows its items", bar.overflow().isShowing());
                    return null;
                });
            } finally { edt(() -> { evidence.closeWindow(); return null; }); }
        }
    }

    @Test public void savedChatKeepsTheWorkspaceRowAndLiveReturnsTheChip() throws Exception {
        try (Fixture f = new Fixture()) {
            JPanel host = edt(() -> { JPanel panel = new JPanel(new BorderLayout()); panel.add(f.workspace); return panel; });
            JFrame frame = edt(() -> { JFrame window = new JFrame("Chat scope"); window.setContentPane(host); window.setSize(1240, 800); window.setVisible(true); return window; });
            try {
                edt(() -> { f.workspace.selectSession(SessionStore.ALL); return null; });
                await(() -> ArchiveNativeSupport.ready(f.workspace));
                edt(() -> {
                    assertTrue(f.workspace.state().archive);
                    FilterBar live = named(f.chat, "chat-live-filter-bar", FilterBar.class);
                    assertTrue("Saved: the workspace row shows", f.workspace.filterBar().isVisible());
                    assertTrue("Saved: the chip is in the workspace row", SwingUtilities.isDescendingFrom(ArchiveNativeSupport.scope(f.workspace), f.workspace.filterBar()));
                    assertFalse("Saved: the chip left the live row", SwingUtilities.isDescendingFrom(ArchiveNativeSupport.scope(f.workspace), live));
                    assertSame("The live row stays the host's row", live, f.workspace.liveFilterBar());
                    FilterBarAssert.assertChipInVisibleBar(f.workspace);
                    // Saved Chat's own tools are unchanged: its table's column tools are in the workspace ⋯ (Task 6).
                    OverflowMenu more = ArchiveNativeSupport.more(f.workspace);
                    assertEquals("chat-archive-messages-columns", more.item("Columns").getName());
                    assertNotNull(named(f.workspace, "chat-archive-messages", JTable.class));
                    ArchiveNativeSupport.scopeItem(f.workspace, "live").doClick();
                    assertFalse(f.workspace.state().archive);
                    assertTrue("Live again: the chip returns to the live row", SwingUtilities.isDescendingFrom(ArchiveNativeSupport.scope(f.workspace), live));
                    assertFalse(f.workspace.filterBar().isVisible());
                    FilterBarAssert.assertChipInVisibleBar(f.workspace);
                    return null;
                });
            } finally { edt(() -> { frame.dispose(); return null; }); }
        }
    }

    @Test public void theLiveTablesColumnToolsAndNamedLiveViewsAreLiveRowItems() throws Exception {
        try (Fixture f = new Fixture()) {
            edt(() -> {
                FilterBar bar = named(f.chat, "chat-live-filter-bar", FilterBar.class);
                OverflowMenu more = bar.overflow();
                assertTrue("The live ⋯ holds items", more.isVisible());
                for (String label : new String[]{"Columns", "Column preset", "Reset columns", "Copy selected rows", "Row details…", "Saved views",
                        "Save current view…", "Delete view…", "Reset saved state"})
                    assertNotNull("Live ⋯ item " + label, more.item(label));
                assertEquals("chat-messages-columns", more.item("Columns").getName());
                assertEquals("chat-live-saved-views", more.item("Saved views").getName());
                assertTrue("Saved views come before the column tools", index(more, "Saved views") < index(more, "Columns"));
                JComponent drawer = bar.drawerContent();
                for (String gone : new String[]{"Save live view", "Load live view", "Delete live view", "Reset saved live state", "Copy selected", "Details…", "Columns…", "Reset columns"})
                    assertNull("Not in the drawer: " + gone, button(drawer, gone));

                JTable table = named(f.chat, "chat-messages", JTable.class);
                assertEquals(5, table.getColumnCount());
                more.item("Conversation").doClick();   // ⋯ Column preset ▸ Conversation
                assertEquals("Conversation hides Channel", 4, table.getColumnCount());
                assertFalse(columns(f.explorer().captureLiveState()).get("channel"));
                more.item("Reset columns").doClick();
                assertEquals(5, table.getColumnCount());
                assertTrue("Reset shows Channel again in the live state", columns(f.explorer().captureLiveState()).get("channel"));

                // A named live view saved elsewhere is listed and applies from ⋯ › Saved views.
                ViewState<ChatExplorer.LiveFacets, ChatArchiveClient.Sort> view = f.explorer().captureLiveState();
                ChatExplorer.LiveFacets facets = view.query.facets(); facets.player = "Wren";
                f.memory.states.saveNamed("chat-live", "Wren only", view.withQuery(view.query.withFacets(facets)));
                JMenu views = (JMenu) more.item("Saved views");
                views.setSelected(true);   // opening the submenu relists the names
                views.setSelected(false);
                more.item("Load: Wren only").doClick();
                assertEquals("Wren", named(f.chat, "chat-player", JTextField.class).getText());
                return null;
            });
        }
    }

    @Test public void browseSavedWordingPointsToTheScopeChip() throws Exception {
        try (Fixture f = new Fixture()) {
            edt(() -> {
                JTextArea summary = find(f.chat, JTextArea.class, area -> area.getToolTipText() != null && area.getToolTipText().startsWith("Live view keeps"));
                assertNotNull("The summary line", summary);
                assertFalse(summary.getToolTipText(), summary.getToolTipText().contains("Browse saved"));
                assertTrue(summary.getToolTipText(), summary.getToolTipText().contains("Scope ▾ › Saved history"));
                assertFalse(ChatExplorer.CLEAR_PROMPT, ChatExplorer.CLEAR_PROMPT.contains("Browse saved"));
                assertTrue(ChatExplorer.CLEAR_PROMPT, ChatExplorer.CLEAR_PROMPT.contains("Scope ▾ › Saved history"));
                return null;
            });
        }
    }

    private static Map<String, Boolean> columns(ViewState<?, ?> state) {
        Map<String, Boolean> visible = new LinkedHashMap<>();
        for (ViewState.Column column : state.tables.get("messages").columns) visible.put(column.id, column.visible);
        return visible;
    }

    private static int index(OverflowMenu more, String label) {
        Component[] items = more.menu().getComponents();
        for (int i = 0; i < items.length; i++) if (items[i] instanceof JMenuItem && label.equals(((JMenuItem) items[i]).getText())) return i;
        fail("No top-level ⋯ item " + label); return -1;
    }

    private static <T extends Component> T find(Container root, Class<T> type, java.util.function.Predicate<T> test) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && test.test(type.cast(child))) return type.cast(child);
            if (child instanceof Container) { T found = find((Container) child, type, test); if (found != null) return found; }
        }
        return null;
    }

    /** A live Chat page (no capture) in its workspace over one synthetic saved session, with in-memory view states. */
    private final class Fixture implements AutoCloseable {
        final SessionStore store; final ChatGUI chat; final Memory memory = new Memory();
        final ArchiveWorkspace<ChatArchiveClient.Row, ChatArchiveClient.Facets, ChatArchiveClient.Sort> workspace;
        @SuppressWarnings("unchecked") Fixture() throws Exception {
            Path root = temp.newFolder().toPath();
            store = new SessionStore(root, true, "synthetic-chat");
            store.append("chat", new ChatMessage(LocalDateTime.of(2026, 9, 22, 12, 0), ChatMessage.Channel.GUILD, "Wren", "", "Wren", "Synthetic hello", ""));
            store.flush();
            chat = edt(() -> new ChatGUI(null, new ChatFilters(), false));
            workspace = edt(() -> (ArchiveWorkspace<ChatArchiveClient.Row, ChatArchiveClient.Facets, ChatArchiveClient.Sort>)
                chat.workspace(store, root.resolve(".query-scratch/chat"), memory.states));
        }
        @Override public void close() throws Exception {
            try { edt(() -> { workspace.close(); explorer().removeNotify(); return null; }); } finally { store.close(); }
        }
        ChatExplorer explorer() { return find(chat, ChatExplorer.class, explorer -> true); }
    }
}
