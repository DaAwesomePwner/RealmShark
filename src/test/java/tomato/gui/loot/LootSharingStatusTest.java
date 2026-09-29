package tomato.gui.loot;

import java.awt.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.Test;
import tomato.realmshark.LootDelivery;
import tomato.realmshark.SendLoot;
import static org.junit.Assert.*;

/**
 * Loot › ⋯ › Loot sharing status: the legacy delivery status and details moved from the Statistics Live log (same wording) for
 * sharing on, opted out, stopped and a delivery error, and the 2 Hz poll running only while the dialog shows. Synthetic sessions
 * whose transport never connects: nothing is sent.
 */
public class LootSharingStatusTest {
    private static final String SEMANTICS = "Socket writes are not application confirmation. Merged bags count as one payload. Opt-out"
        + " cancels unsent bags; an in-flight send cannot be retracted and is counted as uncertain. Uncertain sends are not retried.";

    private static SendLoot.Session session(boolean enabled) {
        return new SendLoot.Session(new LootDelivery(() -> { throw new AssertionError("The status view never connects"); }, 2, enabled, false));
    }

    @Test public void theStatusAndDetailsSayOnOptedOutStoppedAndTheError() throws Exception {
        SendLoot.Session on = session(true), off = session(false);
        LootDelivery failing = new LootDelivery(() -> { throw new AssertionError("never connects"); }, 2, true, false);
        SendLoot.Session error = new SendLoot.Session(failing);
        try {
            edt(() -> {
                LootSharingStatus status = new LootSharingStatus(on);
                assertEquals("loot-sharing-status", status.getName());
                assertEquals("Legacy loot: On · Queued: 0 · Socket: 0 (unconfirmed)\nDropped: 0 · Uncertain: 0 · Error: None", status.summaryText());
                assertEquals("Legacy loot sharing: Idle\nQueued total: 0 · Waiting: 0 · Pending merge: 0 · Active: 0"
                    + "\nSent to socket: 0 (unconfirmed) · Dropped before send: 0 · Uncertain: 0\nLast error: None\n" + SEMANTICS, status.detailsText());
                JTextArea summary = named(status, "loot-sharing-summary", JTextArea.class), details = named(status, "loot-sharing-details", JTextArea.class);
                assertEquals("Legacy loot delivery status", summary.getAccessibleContext().getAccessibleName());
                assertEquals("Legacy loot delivery details", details.getAccessibleContext().getAccessibleName());
                assertEquals("The summary describes itself with the details", status.detailsText(), summary.getAccessibleContext().getAccessibleDescription());
                assertFalse(summary.isEditable());
                assertFalse(details.isEditable());

                LootSharingStatus optedOut = new LootSharingStatus(off);
                assertTrue(optedOut.summaryText(), optedOut.summaryText().startsWith("Legacy loot: Opted out · Queued: 0"));
                assertTrue(optedOut.detailsText().startsWith("Legacy loot sharing: Opted out\n"));
                assertTrue("The opt-out stays under File", named(optedOut, "loot-sharing-opt-out", JComponent.class) != null);

                on.setEnabled(false);
                status.refresh();
                assertTrue("A refresh shows the change", status.summaryText().startsWith("Legacy loot: Opted out"));
                on.close();
                status.refresh();
                assertTrue(status.summaryText(), status.summaryText().startsWith("Legacy loot: Stopped · Queued: 0"));
                assertTrue(status.detailsText().startsWith("Legacy loot sharing: Stopped\n"));

                failing.recordDrop("Loot queue full; newest payload dropped");
                LootSharingStatus failed = new LootSharingStatus(error);
                assertEquals("Legacy loot: On · Queued: 0 · Socket: 0 (unconfirmed)\nDropped: 1 · Uncertain: 0 · Error: See Details", failed.summaryText());
                assertTrue(failed.detailsText(), failed.detailsText().contains("Dropped before send: 1 · Uncertain: 0\nLast error: Loot queue full; newest payload dropped\n"));
                return null;
            });
        } finally { on.close(); off.close(); error.close(); }
    }

    @Test public void itPollsTwiceASecondOnlyWhileTheDialogShows() throws Exception {
        assertEquals("2 Hz", 500, LootSharingStatus.POLL_MILLIS);
        SendLoot.Session sharing = session(true);
        JFrame[] owner = new JFrame[1];
        LootSharingStatus status = edt(() -> new LootSharingStatus(sharing));
        try {
            edt(() -> {
                assertFalse("Built but hidden: no poll", status.polling());
                assertNull("No dialog until opened", status.dialog());
                owner[0] = new JFrame("Loot sharing status owner");
                owner[0].setSize(400, 300);
                owner[0].setVisible(true);
                status.open(owner[0]);
                return null;
            });
            edt(() -> {
                JDialog dialog = status.dialog();
                assertNotNull(dialog);
                assertEquals("Loot sharing status", dialog.getTitle());
                assertFalse("Modeless: the app stays usable", dialog.isModal());
                assertTrue(dialog.isVisible());
                assertTrue("Showing: it polls", status.polling());
                sharing.setEnabled(false);
                return null;
            });
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline && !edt(() -> status.summaryText().startsWith("Legacy loot: Opted out"))) Thread.sleep(50);
            assertTrue("The poll picks up the change while showing", edt(() -> status.summaryText().startsWith("Legacy loot: Opted out")));
            edt(() -> { status.dialog().setVisible(false); return null; });
            edt(() -> {
                assertFalse("Hidden: the poll stops", status.polling());
                int polls = status.polls();
                sharing.close();
                return polls;
            });
            int before = edt(status::polls);
            Thread.sleep(3 * LootSharingStatus.POLL_MILLIS);
            assertEquals("No poll while hidden", before, (int) edt(status::polls));
            assertTrue("…so the summary still shows what was last polled", edt(() -> status.summaryText().startsWith("Legacy loot: Opted out")));
            edt(() -> {
                JDialog first = status.dialog();
                status.open(owner[0]);
                assertSame("Opening again reuses the dialog", first, status.dialog());
                assertTrue(status.summaryText(), status.summaryText().startsWith("Legacy loot: Stopped"));
                status.close();
                assertFalse(status.polling());
                assertFalse("Closing disposes the dialog", first.isDisplayable());
                status.close();
                return null;
            });
        } finally {
            edt(() -> { status.close(); if (owner[0] != null) owner[0].dispose(); return null; });
            sharing.close();
        }
    }

    private static <T extends Component> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = named((Container) child, name, type); if (found != null) return found; }
        }
        return null;
    }

    interface Checked<T> { T get() throws Exception; }
    static <T> T edt(Checked<T> value) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(value.get()); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() instanceof Error) throw (Error) error.get();
        if (error.get() != null) throw new AssertionError(error.get());
        return result.get();
    }
}
