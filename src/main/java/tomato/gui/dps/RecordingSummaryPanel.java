package tomato.gui.dps;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import javax.swing.*;
import tomato.gui.kit.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.LineIcon;
import tomato.gui.runs.RunDamagePanel;
import tomato.gui.runs.RunRecapBuilder;
import tomato.gui.runs.RunRecapModel;
import tomato.history.SessionStore;
import tomato.history.archive.Cancellation;
import tomato.history.encounter.CombatDetail;
import tomato.history.encounter.CombatFacts;
import tomato.history.encounter.CombatRecord;

/**
 * Runs & DPS › Recordings: the read-only summary of one saved recording without full detail and without a run recap to open
 * (unlinked or legacy, or no recap target): its Damage section as the run recap shows that recording ({@link RunDamagePanel}
 * over {@link RunRecapBuilder#damage(CombatRecord, CombatDetail)}: rows, totals, chart and damage by source), under a title
 * and a caption saying hit detail was not kept ({@link #NOT_KEPT}) or was pruned ({@link #pruned}). Nothing here opens the
 * meter or writes anything. The record and its detail are read off the EDT ({@link #read}); the EDT applies the result.
 * Components: {@code recording-summary}, {@code recording-summary-title}, {@code recording-summary-caption},
 * {@code recording-summary-state} (loading or why it cannot be shown) and {@code recording-summary-close} (×). EDT only,
 * except {@link #read}.
 */
public final class RecordingSummaryPanel extends JPanel {
    public static final String NOT_KEPT = "Summary only: hit detail was not kept (Settings › General › Keep full combat detail)";
    static final String LOADING = "Reading the saved summary…";
    static final String GONE = "This recording's saved summary is no longer in saved history, so it cannot be shown.";
    static final String NO_HISTORY = "Saved history is not open in this app run, so this summary cannot be read.";
    static final String DETAIL_UNREADABLE = "The saved detail of this recording could not be read, so its damage over time and by source are not shown.";

    private final KitText title = KitText.emphasis(" ");
    private final KitText caption = KitText.caption(" ");
    private final JTextArea state = ContentStyle.wrappingText("");
    private final RunDamagePanel damage;
    private final KitButton close = KitButton.icon(new LineIcon(LineIcon.CLOSE, 12), "Close summary");
    private String key;

    /** {@code mode} is the Damage section's (its chart); {@code onClose} runs when × is pressed, after the panel hides. */
    public RecordingSummaryPanel(DisplayModeModel mode, Runnable onClose) {
        super(new BorderLayout(0, Tokens.S));
        Objects.requireNonNull(onClose, "onClose");
        setName("recording-summary");
        setOpaque(false);
        title.setName("recording-summary-title");
        title.putClientProperty("html.disable", Boolean.TRUE);
        caption.setName("recording-summary-caption");
        caption.putClientProperty("html.disable", Boolean.TRUE);
        state.setName("recording-summary-state");
        state.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
        close.setName("recording-summary-close");
        close.addActionListener(e -> { setVisible(false); key = null; onClose.run(); });
        damage = new RunDamagePanel(mode);
        JPanel header = new JPanel(new BorderLayout(Tokens.S, 0));
        header.setOpaque(false);
        header.add(title, BorderLayout.CENTER);
        header.add(close, BorderLayout.EAST);
        add(KitLayouts.stack(Tokens.XS, header, caption, state), BorderLayout.NORTH);
        add(damage, BorderLayout.CENTER);
        getAccessibleContext().setAccessibleName("Recording summary, read-only");
        setVisible(false);
    }

    @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }

    /** The caption of a summary whose full detail retention deleted: "Summary only: full detail was pruned (kept 30 days)". */
    public static String pruned(int days) { return "Summary only: full detail was pruned (kept " + days + " days)"; }

    /** The row reference whose summary shows or is being read; null while hidden. */
    String key() { return key; }

    /** Shows the panel for {@code key}: its title and caption, "Reading…", and no rows until {@link #show} or {@link #showFailure}. */
    void showLoading(String key, String heading, String note) {
        this.key = Objects.requireNonNull(key, "key");
        title.setText("Summary · " + heading);
        caption.setText(note);
        state.setText(LOADING);
        state.setVisible(true);
        damage.setVisible(false);
        setVisible(true);
        revalidate();
        repaint();
    }

    /** The read summary's Damage section. */
    void show(RunRecapModel.Damage section) {
        state.setText("");
        state.setVisible(false);
        damage.show(section);
        damage.setVisible(true);
        revalidate();
        repaint();
    }

    /** Why the summary cannot be shown (nothing else is shown in its place). */
    void showFailure(String reason) {
        state.setText(reason);
        state.setVisible(true);
        damage.setVisible(false);
        revalidate();
        repaint();
    }

    /** Hides the panel without running the close action (another row was selected). */
    void hideSummary() { key = null; setVisible(false); }

    /**
     * The Damage section of {@code item}'s saved record in its own session, read directly by recording ID,
     * and its saved detail; null when the record is no longer there or cannot be read. A detail that cannot be read
     * leaves the rows with its reason. Off the EDT.
     *
     * @throws IOException when saved history is not open
     */
    static RunRecapModel.Damage read(SessionStore store, RecordingItem item, Cancellation cancel) throws IOException {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Read saved history off the EDT");
        if (store == null) throw new IOException(NO_HISTORY);
        if (item.session() == null || item.recordingId() == null || item.recordingId().isEmpty()) return null;
        cancel.check();
        CombatRecord found;
        try { found = store.readCheckpoint(item.session(), CombatFacts.RECORDS, item.recordingId(), CombatRecord.class).orElse(null); }
        catch (IOException | RuntimeException unreadable) { return null; } // CombatFacts skips unreadable records too
        cancel.check();
        if (found == null || found.schemaVersion > CombatRecord.SCHEMA_VERSION || !item.recordingId().equals(found.recordingId)) return null;
        if (found.players == null) found.players = new ArrayList<>();
        if (found.bosses == null) found.bosses = new ArrayList<>();
        try { return RunRecapBuilder.damage(found, CombatFacts.detail(store, item.session(), item.recordingId())); }
        catch (IOException | RuntimeException unreadable) { return RunRecapBuilder.damage(found, null, DETAIL_UNREADABLE); }
    }
}
