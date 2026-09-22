package tomato.gui.maingui;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import tomato.realmshark.AlertRules;
import ui.VisualEvidence;
import util.PreferencesStore;
import static org.junit.Assert.*;
import static ui.VisualEvidence.*;

public class AlertRuleLayoutEvidenceTest {
    @Rule public VisualEvidence evidence = new VisualEvidence();
    @Rule public ErrorCollector layouts = new ErrorCollector();

    @Test public void populatedAndFailedDraftsKeepRulesSamplesAndRetryReachableAtCompactAndLargeFonts() throws Exception {
        Map<String, String> memory = new HashMap<>(); AtomicInteger audio = new AtomicInteger();
        java.util.List<CompletableFuture<PreferencesStore.SaveResult>> writes = new ArrayList<>();
        AlertRules service = new AlertRules(memory::get, (key, value) -> {
            memory.put(key, value); CompletableFuture<PreferencesStore.SaveResult> pending = new CompletableFuture<>(); writes.add(pending); return pending;
        });
        AlertRuleEditor[] editor = new AlertRuleEditor[1];
        SwingUtilities.invokeAndWait(() -> {
            editor[0] = new AlertRuleEditor(service, AlertRules.Domain.ITEM, Arrays.asList("42", "Synthetic sword"), "Item rules", audio::incrementAndGet);
            JTable table = named(editor[0], "alert-rule-table", JTable.class);
            table.setValueAt(AlertRules.Mode.ITEM_ID, 0, 0);
            named(editor[0], "rule-sample-id", JTextField.class).setText("142");
            named(editor[0], "rule-sample-text", JTextField.class).setText("Synthetic shield");
            button(editor[0], "Check sample").doClick();
            assertEquals(0, audio.get()); assertTrue(memory.isEmpty());
        });
        renderMatrix(editor[0], "populated");
        SwingUtilities.invokeAndWait(() -> {
            button(editor[0], "Save rules").doClick();
            named(editor[0], "alert-rule-table", JTable.class).setValueAt("43", 0, 1);
        });
        writes.get(0).complete(PreferencesStore.SaveResult.failed(1, new IOException("Synthetic save denied")));
        SocialRuleEditorTest.drain();
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(named(editor[0], "rule-save-status", JTextArea.class).getText().contains("Save failed"));
            assertTrue(named(editor[0], "rule-save-status", JTextArea.class).getText().contains("Newer draft"));
            assertEquals("43", named(editor[0], "alert-rule-table", JTable.class).getValueAt(0, 1));
            assertTrue(service.matchItem(Collections.emptyList(), 42, "").matched);
            assertFalse(service.matchItem(Collections.emptyList(), 43, "").matched);
        });
        renderMatrix(editor[0], "save-error");
        SwingUtilities.invokeAndWait(() -> button(editor[0], "Retry save").doClick());
        writes.get(1).complete(PreferencesStore.SaveResult.saved(2)); SocialRuleEditorTest.drain();
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(service.matchItem(Collections.emptyList(), 43, "").matched);
            assertTrue(named(editor[0], "rule-save-status", JTextArea.class).getText().contains("Submitted changes saved"));
            assertEquals(0, audio.get());
        });
    }

    private void renderMatrix(AlertRuleEditor editor, String state) throws Exception {
        for (int font : new int[]{13, 24}) for (int width : new int[]{800, 680}) {
            String name = "alert-rules-" + state + "-" + width + "-" + font;
            SwingUtilities.invokeAndWait(() -> evidence.show(editor, "Item rule draft", width, width == 680 ? 520 : 620, font));
            evidence.settle();
            SwingUtilities.invokeAndWait(() -> {
                evidence.capture(name);
                layouts.checkSucceeds(() -> {
                    JTable table = named(editor, "alert-rule-table", JTable.class);
                    assertEquals(2, table.getRowCount());
                    assertTrue("Three usable rule rows", table.getParent().getHeight() >= table.getRowHeight() * 3);
                    reachable(table, table.getCellRect(0, 1, true));
                    completeButton(button(editor, "Add rule")); completeButton(button(editor, "Remove selected rule"));
                    reachable(named(editor, "rule-sample-id", JTextField.class)); reachable(named(editor, "rule-sample-text", JTextField.class));
                    completeButton(button(editor, "Check sample"));
                    completeText(named(editor, "rule-sample-result", JTextArea.class));
                    completeText(named(editor, "rule-save-status", JTextArea.class));
                    completeButton(button(editor, "Test sound")); completeButton(button(editor, "Cancel"));
                    completeButton(button(editor, state.equals("save-error") ? "Retry save" : "Save rules"));
                    return null;
                });
                evidence.capture(name + "-actions");
            });
        }
    }
}
