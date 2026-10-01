package tomato.gui.modern;

import org.junit.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicReference;
import tomato.gui.route.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static tomato.gui.modern.WorkspaceShell.MouseNavigation.*;

public class ShellMouseNavigationTest {
    @Test public void decisionsConsumeOnlySideButtonClicksInOwnWindow() {
        for (boolean sameWindow : new boolean[] {false, true}) {
            for (int button = 1; button <= 5; button++) {
                for (int id : new int[] {MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED, MouseEvent.MOUSE_MOVED}) {
                    WorkspaceShell.MouseNavigation expected = IGNORE;
                    if (sameWindow && button >= 4 && id != MouseEvent.MOUSE_MOVED) {
                        expected = id == MouseEvent.MOUSE_RELEASED ? button == 4 ? BACK : FORWARD : CONSUME;
                    }
                    assertEquals("window=" + sameWindow + " button=" + button + " id=" + id,
                        expected, WorkspaceShell.mouseNavigation(id, button, sameWindow));
                }
            }
        }
    }

    @Test public void realEventsNavigateOnceAndIgnoreOtherWindowsAndDisposedShell() throws Exception {
        assumeTrue(!GraphicsEnvironment.isHeadless());
        assumeTrue(Toolkit.getDefaultToolkit().areExtraMouseButtonsEnabled());
        assumeTrue(MouseInfo.getNumberOfButtons() >= 5);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame(), other = new JFrame();
            JDialog dialog = new JDialog(frame);
            try {
                WorkspaceShell shell = new WorkspaceShell(TestPages.placeholders(), () -> {}, true);
                ShellNavigator nav = shell.createNavigator();
                nav.register(new RouteTarget() {
                    public Destination destination() { return Destination.RUNS; }
                    public boolean accepts(Route route) { return route.destination == Destination.RUNS; }
                    public Object captureState() { return null; }
                    public void open(Route route) {}
                    public void restoreState(Object state) {}
                });
                frame.setContentPane(shell); frame.pack(); other.pack(); dialog.pack();
                shell.select("quests"); nav.open(Route.to(Destination.RUNS)); nav.open(Route.to(Destination.RUNS));
                MouseEvent press = dispatch(shell, MouseEvent.MOUSE_PRESSED, 4);
                assertTrue(press.isConsumed()); assertEquals(2, nav.depth());
                assertTrue(dispatch(shell, MouseEvent.MOUSE_RELEASED, 4).isConsumed()); assertEquals(1, nav.depth());
                assertTrue(dispatch(shell, MouseEvent.MOUSE_CLICKED, 4).isConsumed()); assertEquals(1, nav.depth());
                assertTrue(dispatch(shell, MouseEvent.MOUSE_RELEASED, 4).isConsumed()); assertEquals("quests", shell.selectedPage());
                assertTrue(dispatch(shell, MouseEvent.MOUSE_RELEASED, 5).isConsumed()); assertEquals("runs", shell.selectedPage());
                assertEquals(1, nav.depth()); assertEquals(1, nav.forwardDepth());
                for (Component source : new Component[] {other, dialog, dialog.getContentPane()}) {
                    for (int button : new int[] {4, 5}) {
                        for (int id : new int[] {MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED}) {
                            assertFalse(dispatch(source, id, button).isConsumed());
                            assertEquals(1, nav.depth()); assertEquals(1, nav.forwardDepth());
                        }
                    }
                }
                frame.dispose();
                assertFalse(dispatch(shell, MouseEvent.MOUSE_RELEASED, 4).isConsumed()); assertEquals(1, nav.depth());
                frame.pack(); // Reattachment must install exactly one listener again.
                assertTrue(dispatch(shell, MouseEvent.MOUSE_RELEASED, 5).isConsumed()); assertEquals(2, nav.depth());
            } catch (Throwable t) { failure.set(t); }
            finally { dialog.dispose(); other.dispose(); frame.dispose(); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static MouseEvent dispatch(Component source, int id, int button) {
        MouseEvent event = new MouseEvent(source, id, System.currentTimeMillis(), 0, 2, 2, 2, 2, 1, false, button);
        source.dispatchEvent(event);
        return event;
    }
}
