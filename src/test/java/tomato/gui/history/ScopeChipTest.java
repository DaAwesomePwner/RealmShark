package tomato.gui.history;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.junit.Test;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveQuery;
import static org.junit.Assert.*;

/** The Scope ▾ chip on its own: labels, the menu, silent state and keyboard; synthetic sessions, fake actions. */
public class ScopeChipTest {
    private static final List<ScopeChip.SessionChoice> RECENT = Arrays.asList(
        new ScopeChip.SessionChoice("s1", "Raid night · 2026-09-20 12:00:00", "Raid night"),
        new ScopeChip.SessionChoice("s2", "Session · 2026-09-19 08:30:00", "09-19 08:30"));

    private static final class Recorder implements ScopeChip.Actions {
        final List<String> calls = new ArrayList<>();
        public void live() { calls.add("live"); }
        public void saved(String scope) { calls.add("saved:" + scope); }
        public void library() { calls.add("library"); }
        public void refreshList() { calls.add("refresh"); }
    }

    @Test public void labelsTooltipsAndAccessibleTextFollowEachState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Recorder actions = new Recorder();
            ScopeChip chip = new ScopeChip("runs", false, actions);
            assertEquals("runs-scope", chip.getName());
            assertEquals("runs-scope-menu", chip.menu().getName());
            assertEquals("Scope", chip.getAccessibleContext().getAccessibleName());
            assertTrue("The chip takes keyboard focus", chip.isFocusable());
            chip.show(false, ArchiveQuery.CURRENT, "now", RECENT);
            assertState(chip, "Scope: Live", "Live view of this app run, not saved history");
            chip.show(true, ArchiveQuery.CURRENT, "now", RECENT);
            assertState(chip, "Scope: Saved · this session", "Saved history of this app run");
            chip.show(true, "now", "now", RECENT);
            assertState(chip, "Scope: Saved · this session", "Saved history of this app run");
            chip.show(true, SessionStore.ALL, "now", RECENT);
            assertState(chip, "Scope: Saved · all sessions", "Saved history of all sessions");
            chip.show(true, "s1", "now", RECENT);
            assertState(chip, "Scope: Saved · Raid night", "Saved history of Raid night · 2026-09-20 12:00:00");
            chip.show(true, "gone", "now", RECENT);
            assertState(chip, "Scope: Saved · selected session", "Selected session · gone");
            assertEquals("Selected session · gone", chip.item("session:gone").getText());
            assertEquals("Scope", chip.getAccessibleContext().getAccessibleName());
            assertEquals("show() fires nothing", Collections.emptyList(), actions.calls);
        });
    }

    @Test public void itemsAreNamedInOrderAndTheirRadiosFollowShowSilently() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Recorder actions = new Recorder();
            ScopeChip chip = new ScopeChip("chat", false, actions);
            chip.show(false, ArchiveQuery.CURRENT, "now", RECENT);
            List<String> shown = new ArrayList<>();
            for (Component item : chip.menu().getComponents())
                shown.add(item instanceof JMenuItem ? ((JMenuItem) item).getText() + (item.isEnabled() ? "" : " (off)") : "—");
            assertEquals(Arrays.asList("Live · this app run", "Saved history (off)", "This session", "All sessions",
                "Raid night · 2026-09-20 12:00:00", "Session · 2026-09-19 08:30:00", "—", "History library…", "Refresh session list"), shown);
            for (String suffix : new String[]{"live", "current", "all", "session", "library", "refresh"})
                assertEquals("chat-scope-" + suffix, chip.item(suffix).getName());
            assertEquals("s2", chip.item("session:s2").getClientProperty(ScopeChip.SCOPE));
            assertEquals("Sessions keep the catalog's order", "s1", chip.item("session").getClientProperty(ScopeChip.SCOPE));
            assertTrue(chip.item("live").isSelected());
            chip.show(true, SessionStore.ALL, "now", RECENT);
            assertTrue(chip.item("all").isSelected()); assertFalse(chip.item("live").isSelected());
            chip.show(true, "s2", "now", RECENT);
            assertTrue(chip.item("session:s2").isSelected());
            chip.show(true, "gone", "now", RECENT);
            assertTrue("An unlisted scope gets a pending radio", chip.item("session:gone").isSelected());
            chip.show(true, ArchiveQuery.CURRENT, "now", RECENT);
            assertNull("The pending radio goes once the scope is listed", chip.item("session:gone"));
            assertTrue(chip.item("current").isSelected());
            assertEquals("Radios follow show() silently", Collections.emptyList(), actions.calls);
        });
    }

    @Test public void eachItemCallsItsAction() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Recorder actions = new Recorder();
            ScopeChip chip = new ScopeChip("loot", false, actions);
            chip.show(false, ArchiveQuery.CURRENT, "now", RECENT);
            for (String suffix : new String[]{"live", "current", "all", "session:s1", "session:s2", "library", "refresh"}) chip.item(suffix).doClick();
            assertEquals(Arrays.asList("live", "saved:" + ArchiveQuery.CURRENT, "saved:" + SessionStore.ALL, "saved:s1", "saved:s2", "library", "refresh"),
                actions.calls);
            assertTrue("A pick the workspace did not apply leaves the shown radio selected", chip.item("live").isSelected());
        });
    }

    @Test public void aSavedOnlyChipHasNoLiveItem() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ScopeChip chip = new ScopeChip("dungeon-analysis", true, new Recorder());
            assertNull(chip.item("live"));
            chip.show(false, SessionStore.ALL, "now", RECENT);
            assertState(chip, "Scope: Saved · all sessions", "Saved history of all sessions");
            assertTrue(chip.item("all").isSelected());
        });
    }

    @Test public void sessionLabelsAreLiteralText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ScopeChip chip = new ScopeChip("keypops", false, new Recorder());
            String markup = "<html><b>Bold</b> night";
            chip.show(true, "s3", "now", Collections.singletonList(new ScopeChip.SessionChoice("s3", markup)));
            assertEquals(markup, chip.item("session:s3").getText());
            assertEquals(Boolean.TRUE, chip.getClientProperty("html.disable"));
            for (Component item : chip.menu().getComponents())
                if (item instanceof JMenuItem) assertEquals(((JMenuItem) item).getText(), Boolean.TRUE, ((JMenuItem) item).getClientProperty("html.disable"));
        });
    }

    @Test public void aLongCustomLabelIsCutInTheLabelAndWholeInTheTooltip() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ScopeChip chip = new ScopeChip("timeline", false, new Recorder());
            String label = "Thursday guild raid with the whole crew";
            chip.show(true, "s4", "now", Collections.singletonList(new ScopeChip.SessionChoice("s4", label + " · 2026-09-18 21:00:00", label)));
            String prefix = "Scope: Saved · ";
            assertTrue(chip.getText(), chip.getText().startsWith(prefix + "Thursday guild"));
            assertTrue("Cut with an ellipsis", chip.getText().endsWith("…"));
            assertTrue("About 18 characters", chip.getText().length() - prefix.length() <= ScopeChip.LABEL_LIMIT);
            assertEquals("Saved history of " + label + " · 2026-09-18 21:00:00", chip.getToolTipText());
            assertEquals(chip.getToolTipText(), chip.getAccessibleContext().getAccessibleDescription());
            assertEquals("The menu keeps the whole label", label + " · 2026-09-18 21:00:00", chip.item("session:s4").getText());
        });
    }

    @Test public void theKeyboardOpensTheMenuOnTheSelectedRadioAndPicks() throws Exception {
        Recorder actions = new Recorder();
        ScopeChip[] chip = new ScopeChip[1];
        JFrame[] frame = new JFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                chip[0] = new ScopeChip("combat", false, actions);
                chip[0].show(true, SessionStore.ALL, "now", RECENT);
                JPanel content = new JPanel(); content.add(chip[0]);
                frame[0] = new JFrame("Scope"); frame[0].setContentPane(content); frame[0].setSize(500, 400); frame[0].setVisible(true);
            });
            int[][] openers = {{KeyEvent.VK_ENTER, 0}, {KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK}, {KeyEvent.VK_F4, 0}, {KeyEvent.VK_SPACE, 0}};
            for (int[] opener : openers) {
                SwingUtilities.invokeAndWait(() -> {
                    press(chip[0], opener[0], opener[1]);
                    assertTrue(KeyEvent.getKeyText(opener[0]) + " opens the menu", chip[0].menu().isVisible());
                    MenuElement[] path = MenuSelectionManager.defaultManager().getSelectedPath();
                    assertSame("The selected radio is highlighted", chip[0].item("all"), path[path.length - 1]);
                    MenuSelectionManager.defaultManager().clearSelectedPath();
                    assertFalse(chip[0].menu().isVisible());
                });
            }
            // Real key flow: posted events go to the focus owner. The open menu takes Up/Down, Enter and Esc (it moves focus to the
            // root pane while open) and gives focus back to the chip when it closes.
            SwingUtilities.invokeAndWait(() -> { frame[0].toFront(); frame[0].requestFocus(); });
            await(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow() == frame[0]);
            SwingUtilities.invokeAndWait(() -> chip[0].requestFocusInWindow());
            await(() -> chip[0].isFocusOwner());
            post(chip[0], KeyEvent.VK_ENTER);
            await(() -> chip[0].menu().isVisible() && selectedLast() == chip[0].item("all"));
            post(chip[0], KeyEvent.VK_DOWN);
            await(() -> selectedLast() == chip[0].item("session:s1"));
            post(chip[0], KeyEvent.VK_ENTER);
            await(() -> !chip[0].menu().isVisible() && actions.calls.equals(Collections.singletonList("saved:s1")));
            await(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == chip[0]);
            post(chip[0], KeyEvent.VK_F4);
            await(() -> chip[0].menu().isVisible());
            post(chip[0], KeyEvent.VK_ESCAPE);
            await(() -> !chip[0].menu().isVisible());
            await(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == chip[0]);
            assertEquals("Esc picks nothing", Collections.singletonList("saved:s1"), actions.calls);
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                MenuSelectionManager.defaultManager().clearSelectedPath();
                if (frame[0] != null) frame[0].dispose();
            });
        }
    }

    private static void assertState(ScopeChip chip, String text, String tip) {
        assertEquals(text, chip.getText());
        assertEquals(tip, chip.getToolTipText());
        assertEquals(tip, chip.getAccessibleContext().getAccessibleDescription());
    }

    /** A pressed (and released) key delivered to the showing chip; non-posted, so it is not retargeted. */
    private static void press(JComponent target, int code, int modifiers) {
        long when = System.currentTimeMillis();
        char typed = code == KeyEvent.VK_SPACE ? ' ' : code == KeyEvent.VK_ENTER ? '\n' : KeyEvent.CHAR_UNDEFINED;
        target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_PRESSED, when, modifiers, code, typed));
        target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_RELEASED, when, modifiers, code, typed));
    }

    private static MenuElement selectedLast() {
        MenuElement[] path = MenuSelectionManager.defaultManager().getSelectedPath();
        return path.length == 0 ? null : path[path.length - 1];
    }

    /** A posted key press and release: the focus manager delivers it to whatever owns focus, as for a real key. */
    private static void post(Component source, int code) throws Exception {
        long when = System.currentTimeMillis();
        char typed = code == KeyEvent.VK_ENTER ? '\n' : code == KeyEvent.VK_ESCAPE ? (char) 27 : KeyEvent.CHAR_UNDEFINED;
        java.awt.EventQueue queue = java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue();
        queue.postEvent(new KeyEvent(source, KeyEvent.KEY_PRESSED, when, 0, code, typed));
        queue.postEvent(new KeyEvent(source, KeyEvent.KEY_RELEASED, when, 0, code, typed));
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean[] done = {false};
        while (System.nanoTime() < end) {
            SwingUtilities.invokeAndWait(() -> done[0] = condition.getAsBoolean());
            if (done[0]) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for EDT state");
    }
}
