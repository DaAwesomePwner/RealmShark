package tomato.gui.loot;

import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.util.Objects;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.kit.KitButton;
import tomato.gui.kit.KitLayouts;
import tomato.gui.kit.KitText;
import tomato.gui.kit.SectionHeader;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;
import tomato.realmshark.LootDelivery;
import tomato.realmshark.SendLoot;

/**
 * Loot › ⋯ › "Loot sharing status…" (P6a decision: legacy loot-sharing status moves to Loot's ⋯; the opt-out stays under File):
 * the legacy delivery summary and details the Statistics Live log showed, in the same words, in a modeless dialog
 * ({@code loot-sharing-status-dialog}) the app stays usable beside.
 * - Summary ({@code loot-sharing-summary}): mode (On, Opted out, Preview, Stopped), queued, socket writes (unconfirmed), dropped,
 *   uncertain and whether there is an error; its accessible description is the full details text.
 * - Details ({@code loot-sharing-details}): the delivery state, counters, the last error and what socket writes, merging,
 *   opt-out and uncertain sends mean.
 * - Polls the session twice a second only while it shows ({@value #POLL_MILLIS} ms, never an event per delivery), and once when
 *   it comes back; hidden, nothing polls. Reading the session's snapshot takes no I/O-held lock, so the EDT never waits on a send.
 * EDT only.
 */
public final class LootSharingStatus extends JPanel implements AutoCloseable {
    public static final String TITLE = "Loot sharing status";
    /** 2 Hz while showing. */
    static final int POLL_MILLIS = 500;

    private final SendLoot.Session sharing;
    private final JTextArea summary = ContentStyle.wrappingText("", 2), details = ContentStyle.wrappingText("", 8);
    private final Timer poll;
    private JDialog dialog;
    private int polls;
    private boolean closed;

    /** The status of {@code sharing} (the app's: {@code LootCapture.get().sharing()}). */
    public LootSharingStatus(SendLoot.Session sharing) {
        super(new BorderLayout(0, Tokens.S));
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build the loot sharing status on the EDT");
        this.sharing = Objects.requireNonNull(sharing, "sharing");
        setName("loot-sharing-status");
        setOpaque(false);
        setBorder(new EmptyBorder(Tokens.M, Tokens.M, Tokens.M, Tokens.M));
        summary.setName("loot-sharing-summary");
        summary.getAccessibleContext().setAccessibleName("Legacy loot delivery status");
        details.setName("loot-sharing-details");
        details.getAccessibleContext().setAccessibleName("Legacy loot delivery details");
        JScrollPane detailsScroll = new JScrollPane(details, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        detailsScroll.setBorder(BorderFactory.createEmptyBorder());
        detailsScroll.setOpaque(false);
        detailsScroll.getViewport().setOpaque(false);
        detailsScroll.setPreferredSize(new Dimension(460, 180));
        KitText optOut = KitText.caption("Sharing is turned off under File › Opt-out Loot Sharing.");
        optOut.setName("loot-sharing-opt-out");
        KitButton close = KitButton.secondary("Close");
        close.setName("loot-sharing-close");
        close.addActionListener(e -> { if (dialog != null) dialog.setVisible(false); });
        add(summary, BorderLayout.NORTH);
        add(KitLayouts.stack(Tokens.XS, new SectionHeader("Details"), detailsScroll), BorderLayout.CENTER);
        add(KitLayouts.spread(Tokens.S, optOut, close), BorderLayout.SOUTH);
        refresh();
        poll = new Timer(POLL_MILLIS, e -> refresh());
        poll.setCoalesce(true);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
            if (isShowing() && !closed) { refresh(); poll.start(); } else poll.stop();
        });
    }

    /** Shows the modeless dialog over {@code owner}'s window (built once, then reused) and brings it to the front. */
    public void open(Component owner) {
        if (closed) return;
        if (dialog == null) {
            Window window = owner == null ? null : owner instanceof Window ? (Window) owner : SwingUtilities.getWindowAncestor(owner);
            dialog = new JDialog(window, TITLE, Dialog.ModalityType.MODELESS);
            dialog.setName("loot-sharing-status-dialog");
            dialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            dialog.setContentPane(this);
            ContentStyle.refreshFonts(dialog);
            dialog.pack();
            dialog.setLocationRelativeTo(owner);
        }
        refresh();
        dialog.setVisible(true);
        dialog.toFront();
    }

    /** One poll: rewrites the texts only when they changed. */
    void refresh() {
        polls++;
        LootDelivery.Status status = sharing.snapshot();
        String text = details(status, sharing.pendingBags());
        if (!text.equals(details.getText())) { details.setText(text); details.setCaretPosition(0); }
        String line = summary(status);
        if (!line.equals(summary.getText())) { summary.setText(line); summary.setCaretPosition(0); }
        summary.getAccessibleContext().setAccessibleDescription(text);
    }

    /** The summary, in the Live log's words. */
    static String summary(LootDelivery.Status status) {
        String mode = status.closed ? "Stopped" : status.preview ? "Preview" : status.enabled ? "On" : "Opted out";
        return "Legacy loot: " + mode + " · Queued: " + status.queued
            + " · Socket: " + status.sentToSocket + " (unconfirmed)"
            + "\nDropped: " + status.dropped + " · Uncertain: " + status.uncertain
            + " · Error: " + (status.lastError.isEmpty() ? "None" : "See Details");
    }

    /** The details, in the Live log's words; {@code pending} bags wait to merge. */
    static String details(LootDelivery.Status status, int pending) {
        return "Legacy loot sharing: " + status.state
            + "\nQueued total: " + status.queued + " · Waiting: " + status.waiting
            + " · Pending merge: " + pending + " · Active: " + status.active
            + "\nSent to socket: " + status.sentToSocket + " (unconfirmed) · Dropped before send: " + status.dropped
            + " · Uncertain: " + status.uncertain
            + "\nLast error: " + (status.lastError.isEmpty() ? "None" : status.lastError)
            + "\nSocket writes are not application confirmation. Merged bags count as one payload. Opt-out cancels unsent bags; an in-flight send cannot be retracted and is counted as uncertain. Uncertain sends are not retried.";
    }

    // Tests.
    String summaryText() { return summary.getText(); }
    String detailsText() { return details.getText(); }
    boolean polling() { return poll.isRunning(); }
    int polls() { return polls; }
    JDialog dialog() { return dialog; }

    /** Stops polling and disposes the dialog (the app closing). Idempotent. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        poll.stop();
        if (dialog != null) dialog.dispose();
    }

    @Override public void removeNotify() {
        poll.stop();
        super.removeNotify();
    }
}
