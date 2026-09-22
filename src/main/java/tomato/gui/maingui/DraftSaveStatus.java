package tomato.gui.maingui;

import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import tomato.gui.modern.ContentStyle;
import util.PreferencesStore;

/** EDT-owned draft feedback; persistence remains memory-immediate and asynchronous. */
public final class DraftSaveStatus {
    public final JTextArea status = ContentStyle.wrappingText("Draft changes apply only when you save.");
    private final JButton save;
    private final String label;
    private long revision, request;
    private boolean submitted;
    private String outcome = "";
    public DraftSaveStatus(JButton save, String name) {
        this.save = save; label = save.getText(); status.setName(name);
    }
    public void edited() {
        revision++;
        status.setText((outcome.isEmpty() ? "" : outcome + " ") + "Unsaved draft changes.");
    }
    public void submit(Supplier<CompletionStage<PreferencesStore.SaveResult>> action) {
        submit(action, () -> true);
    }
    public void submit(Supplier<CompletionStage<PreferencesStore.SaveResult>> action, BooleanSupplier stillCurrent) {
        long token = ++request, submittedRevision = revision;
        try {
            CompletionStage<PreferencesStore.SaveResult> completion = action.get();
            submitted = true; outcome = "Applied now; saving to disk…"; status.setText(outcome); save.setText(label);
            completion.whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
                if (token != request) return;
                boolean success = failure == null && result != null && result.isSuccess();
                outcome = success ? "Submitted changes saved." : "Save failed. Changes are active in memory; not confirmed on disk. Retry to save.";
                if (!stillCurrent.getAsBoolean()) outcome = "Save attempt finished; active settings changed since submission. Reopen to inspect current settings. This draft is retained.";
                status.setText(outcome + (revision != submittedRevision ? " Newer draft changes are unsaved." : ""));
                status.setToolTipText(failure != null ? failure.getMessage() : result == null ? "No save result" : result.detail());
                save.setText(success ? label : "Retry save");
            }));
        } catch (RuntimeException failure) {
            outcome = "Not applied: " + failure.getMessage(); status.setText(outcome); save.setText("Retry save");
        }
    }
    public String closeExplanation() {
        return submitted ? "Discard unsubmitted edits and close? Already applied changes remain active, even if saving failed."
            : "Discard this draft and close?";
    }
}
