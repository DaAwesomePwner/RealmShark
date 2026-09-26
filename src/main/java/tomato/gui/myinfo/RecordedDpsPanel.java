package tomato.gui.myinfo;

import tomato.gui.dps.RecordedEncounter;
import tomato.gui.modern.ContentStyle;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Supplier;

/**
 * INFO-2: from a current estimate, open one recorded encounter's verified local-player row. The explanation
 * keeps the current-build estimate and the historical recording visibly separate, names the recording's
 * scope and window, and explains why an unlinked or unverified recording cannot be opened. EDT only.
 */
final class RecordedDpsPanel extends JPanel {
    private final Supplier<List<RecordedEncounter>> encounters;
    private final Supplier<String> estimate;
    private final JComboBox<RecordedEncounter> choice = new JComboBox<>();
    private final JButton open = new JButton("Open recorded local row");
    private final JTextArea explanation = ContentStyle.wrappingText("", 3);
    private SwingWorker<List<RecordedEncounter>, Void> reloadWorker;
    private long reloadGeneration;
    private boolean loading, disposed;
    private String loadError;

    RecordedDpsPanel(Supplier<List<RecordedEncounter>> encounters, Supplier<String> estimate) {
        super(new BorderLayout(0, 4));
        this.encounters = encounters; this.estimate = estimate;
        setName("myinfo-recorded-dps");
        setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        JLabel title = new JLabel("Historical recorded DPS (not this estimate)");
        title.setFont(ContentStyle.metadata(ContentStyle.body())); title.setLabelFor(choice);
        choice.setName("myinfo-recorded-encounter");
        choice.getAccessibleContext().setAccessibleName("Recorded encounter to compare with the current estimate");
        DefaultListCellRenderer literal = new DefaultListCellRenderer(); literal.putClientProperty("html.disable", true); choice.setRenderer(literal);
        choice.setPrototypeDisplayValue(new RecordedEncounter(null, "Lost Halls · 2026-09-21 20:14:00", null, null, tomato.gui.dps.EncounterLink.live()));
        open.setName("myinfo-open-recorded-row");
        JButton refresh = new JButton("Refresh recordings"); refresh.setName("myinfo-refresh-recordings");
        explanation.setName("myinfo-recorded-explanation"); explanation.setFocusable(true);
        explanation.getAccessibleContext().setAccessibleName("Current estimate versus historical recording");
        JPanel controls = ContentStyle.controls();
        controls.add(choice); controls.add(open); controls.add(refresh);
        add(title, BorderLayout.NORTH); add(controls, BorderLayout.CENTER); add(explanation, BorderLayout.SOUTH);
        choice.addActionListener(e -> explain());
        refresh.addActionListener(e -> reload());
        open.addActionListener(e -> openSelected());
        addHierarchyListener(e -> { if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) reload(); });
        reload();
    }

    /** Projects the detached library off EDT; late completions never replace a newer refresh. */
    void reload() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Reload must be requested on EDT");
        if (disposed) return;
        final RecordedEncounter selected = (RecordedEncounter) choice.getSelectedItem();
        final long generation = ++reloadGeneration;
        if (reloadWorker != null) reloadWorker.cancel(true);
        loading = true; loadError = null; choice.setEnabled(false); explain();
        reloadWorker = new SwingWorker<List<RecordedEncounter>, Void>() {
            @Override protected List<RecordedEncounter> doInBackground() { return new java.util.ArrayList<>(encounters.get()); }
            @Override protected void done() {
                if (disposed || generation != reloadGeneration || isCancelled()) return;
                try {
                    List<RecordedEncounter> list = get();
                    choice.removeAllItems();
                    for (int i = list.size() - 1; i >= 0; i--) choice.addItem(list.get(i));
                    if (selected != null) for (int i = 0; i < choice.getItemCount(); i++)
                        if (selected.recordingId != null && selected.recordingId.equals(choice.getItemAt(i).recordingId)) { choice.setSelectedIndex(i); break; }
                } catch (Exception failure) {
                    loadError = "Could not refresh recordings. Previous choices are retained but cannot be opened until Refresh recordings succeeds.";
                } finally { loading = false; choice.setEnabled(true); explain(); }
            }
        };
        reloadWorker.execute();
    }
    @Override public void addNotify() { disposed = false; super.addNotify(); }
    @Override public void removeNotify() {
        disposed = true; reloadGeneration++; if (reloadWorker != null) reloadWorker.cancel(true); loading = false;
        super.removeNotify();
    }
    boolean loading() { return loading; }

    private String currentEstimate() {
        return "Current estimate: " + estimate.get() + " — uses your current build, current stats and the chosen scenario; it is not a recording.";
    }

    void explain() {
        String current = currentEstimate();
        if (loading || loadError != null) {
            open.setEnabled(false);
            open.setToolTipText(loading ? "Wait for the recording refresh to finish" : loadError);
            explanation.setText(current + "\n" + (loading ? "Refreshing recorded encounters in the background… Previous selection is retained until the refresh finishes." : loadError));
            return;
        }
        RecordedEncounter selected = (RecordedEncounter) choice.getSelectedItem();
        if (selected == null) {
            open.setEnabled(false);
            explanation.setText(current + "\nNo recorded encounters in this session's DPS library. Recorded DPS appears after an encounter is saved or imported.");
            return;
        }
        Route route = selected.localRowRoute();
        String reason = selected.unavailableReason();
        if (reason == null && !Navigator.current().canOpen(route)) reason = "DPS Logger cannot open this recording here (it is no longer in the library or navigation is unavailable).";
        open.setEnabled(reason == null);
        open.setToolTipText(reason == null ? "Opens the verified local-player row of this historical recording" : reason);
        String value = selected.recordedValue();
        explanation.setText(current + "\n" + (value != null ? value
                : "Recorded DPS: not shown. This recording's damage is not attributable to you (no verified local-player row).")
            + "\nHistorical recording: " + selected.scope() + (reason == null ? "" : "\nUnavailable: " + reason));
    }

    private void openSelected() {
        if (loading || loadError != null || disposed) return;
        RecordedEncounter selected = (RecordedEncounter) choice.getSelectedItem();
        Route route = selected == null ? null : selected.localRowRoute();
        if (route == null || !Navigator.current().open(route))
            explanation.setText(explanation.getText() + "\nThe recorded row could not be opened; nothing was changed.");
    }

    String explanationText() { return explanation.getText(); }
    JButton openButton() { return open; }
    JComboBox<RecordedEncounter> choice() { return choice; }
}
