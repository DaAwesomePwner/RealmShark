package tomato.gui.maingui;

import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import org.junit.Test;
import tomato.realmshark.AlertRules;
import tomato.realmshark.ParseEnchants;
import util.PreferencesStore;
import static org.junit.Assert.*;

public class SocialRuleEditorTest {
    @Test public void typedDraftSampleAndExplicitTestAreIndependentOfSaveAndFailureRetainsEdits() throws Exception {
        Map<String, String> memory = new HashMap<>(); AtomicInteger audio = new AtomicInteger();
        List<CompletableFuture<PreferencesStore.SaveResult>> writes = new ArrayList<>();
        AlertRules service = new AlertRules(memory::get, (key, value) -> {
            memory.put(key, value); CompletableFuture<PreferencesStore.SaveResult> future = new CompletableFuture<>(); writes.add(future); return future;
        });
        AlertRuleEditor[] editor = new AlertRuleEditor[1];
        SwingUtilities.invokeAndWait(() -> {
            editor[0] = new AlertRuleEditor(service, AlertRules.Domain.ITEM, Arrays.asList("42"), "Synthetic rules", audio::incrementAndGet);
            JTable table = named(editor[0], "alert-rule-table", JTable.class);
            table.setValueAt(AlertRules.Mode.ITEM_ID, 0, 0);
            named(editor[0], "rule-sample-id", JTextField.class).setText("142");
            button(editor[0], "Check sample").doClick();
            assertTrue(named(editor[0], "rule-sample-result", JTextArea.class).getText().contains("differs from 42"));
            assertTrue(memory.isEmpty()); assertEquals(0, audio.get());
            button(editor[0], "Test sound").doClick(); assertEquals(1, audio.get());
            button(editor[0], "Save rules").doClick();
            assertTrue(service.matchItem(Collections.emptyList(), 42, null).matched);
            assertFalse(service.matchItem(Collections.emptyList(), 142, null).matched);
            table.setValueAt("43", 0, 1); button(editor[0], "Save rules").doClick();
            table.setValueAt("44", 0, 1); // Edit during the second write, without another save.
        });
        writes.get(0).complete(PreferencesStore.SaveResult.saved(1)); drain();
        SwingUtilities.invokeAndWait(() -> assertFalse(named(editor[0], "rule-save-status", JTextArea.class).getText().contains("Submitted changes saved")));
        writes.get(1).complete(PreferencesStore.SaveResult.failed(2, new IOException("move denied"))); drain();
        SwingUtilities.invokeAndWait(() -> {
            JTextArea status = named(editor[0], "rule-save-status", JTextArea.class);
            assertTrue(status.getText().contains("Save failed")); assertTrue(status.getText().contains("Newer draft"));
            assertEquals("44", named(editor[0], "alert-rule-table", JTable.class).getValueAt(0, 1));
            assertTrue(service.matchItem(Collections.emptyList(), 43, null).matched);
            button(editor[0], "Retry save").doClick();
        });
        writes.get(2).complete(PreferencesStore.SaveResult.saved(3)); drain();
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(named(editor[0], "rule-save-status", JTextArea.class).getText().contains("Submitted changes saved"));
            assertTrue(service.matchItem(Collections.emptyList(), 44, null).matched); assertEquals(1, audio.get());
        });
    }
    @Test public void exceptionalSaveAndSynchronousValidationAreVisibleWithoutLosingDraft() throws Exception {
        CompletableFuture<PreferencesStore.SaveResult> future = new CompletableFuture<>(); DraftSaveStatus[] saving = new DraftSaveStatus[1];
        SwingUtilities.invokeAndWait(() -> {
            saving[0] = new DraftSaveStatus(new JButton("Save"), "test");
            saving[0].submit(() -> future); saving[0].edited();
        });
        future.completeExceptionally(new IOException("offline")); drain();
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(saving[0].status.getText().contains("Save failed")); assertTrue(saving[0].status.getText().contains("Newer draft"));
            saving[0].submit(() -> { throw new IllegalArgumentException("Enter an ID"); });
            assertEquals("Not applied: Enter an ID", saving[0].status.getText());
            assertTrue(saving[0].closeExplanation().contains("Already applied"));
        });
    }
    @Test public void enchantShownAndGroupActionsRetainHiddenUnknownAndUnrecognizedSelections() throws Exception {
        Map<Short, String> previous = new HashMap<>(ParseEnchants.ENCHANTS);
        AtomicReference<String> saved = new AtomicReference<>();
        try { SwingUtilities.invokeAndWait(() -> {
            ParseEnchants.ENCHANTS.clear(); ParseEnchants.ENCHANTS.put((short)1, "Agile"); ParseEnchants.ENCHANTS.put((short)2, "Alacrity");
            ParseEnchants.ENCHANTS.put((short)3, "Brisk");
            EnchantPingGUI panel = new EnchantPingGUI(null, "1,2,99,future-token", value -> {
                saved.set(value); return CompletableFuture.completedFuture(PreferencesStore.SaveResult.saved(1));
            });
            named(panel, "enchant-search", JTextField.class).setText("Agile");
            assertTrue(named(panel, "enchant-selection-count", JTextArea.class).getText().contains("2 selected outside filter"));
            button(panel, "Clear shown").doClick();
            assertEquals(new TreeSet<>(Arrays.asList((short)2, (short)99)), panel.selectedIds());
            button(panel, "Select shown").doClick(); assertTrue(panel.selectedIds().contains((short)1));
            named(panel, "enchant-selected-only", JCheckBox.class).doClick();
            button(panel, "Clear shown").doClick(); assertFalse(panel.selectedIds().contains((short)1));
            assertTrue(panel.selectedIds().contains((short)2));
            named(panel, "enchant-search", JTextField.class).setText("");
            button(panel, "Clear all catalog").doClick(); assertEquals(Collections.singleton((short)99), panel.selectedIds());
            panel.setItems(Collections.emptyList()); assertEquals(Collections.singleton((short)99), panel.selectedIds());
            button(panel, "Save").doClick(); assertEquals("99,future-token", saved.get());
            assertTrue(panel.getSelectedItems().get(0).contains("Unavailable enchant(99)"));
        }); } finally { SwingUtilities.invokeAndWait(() -> { ParseEnchants.ENCHANTS.clear(); ParseEnchants.ENCHANTS.putAll(previous); }); }
    }
    @Test public void futureTypedEnvelopeIsVisibleAndCannotBeOverwrittenByEditor() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String future = "{\"version\":8,\"rules\":[]}";
            AlertRules service = new AlertRules(key -> key.equals(AlertRules.Domain.ITEM.key()) ? future : null,
                (key, value) -> { throw new AssertionError("Must preserve future data"); });
            AlertRuleEditor editor = new AlertRuleEditor(service, AlertRules.Domain.ITEM, Arrays.asList("42"), "Rules", () -> fail("No audio"));
            assertFalse(button(editor, "Save rules").isEnabled()); assertFalse(button(editor, "Add rule").isEnabled());
            named(editor, "rule-sample-id", JTextField.class).setText("42"); button(editor, "Check sample").doClick();
            assertTrue(named(editor, "rule-sample-result", JTextArea.class).getText().contains("preserved"));
        });
    }
    @Test public void coalescedSaveCompletionCannotClaimAnOverwrittenDraftIsSaved() throws Exception {
        Map<String, String> memory = new HashMap<>(); CompletableFuture<PreferencesStore.SaveResult> coalesced = new CompletableFuture<>();
        AlertRules service = new AlertRules(memory::get, (key, value) -> { memory.put(key, value); return coalesced; });
        AlertRuleEditor[] editor = new AlertRuleEditor[1];
        SwingUtilities.invokeAndWait(() -> {
            editor[0] = new AlertRuleEditor(service, AlertRules.Domain.ITEM, Arrays.asList("42"), "Rules", () -> fail("No audio"));
            button(editor[0], "Save rules").doClick();
            AlertRules.Snapshot newer = service.snapshot(AlertRules.Domain.ITEM, Collections.emptyList());
            service.save(newer, Arrays.asList(AlertRules.Rule.of(AlertRules.Mode.ITEM_ID, "99")));
        });
        coalesced.complete(PreferencesStore.SaveResult.saved(2)); drain();
        SwingUtilities.invokeAndWait(() -> {
            String text = named(editor[0], "rule-save-status", JTextArea.class).getText();
            assertTrue(text.contains("active settings changed")); assertFalse(text.contains("Submitted changes saved"));
        });
    }
    public static void drain() throws Exception { SwingUtilities.invokeAndWait(() -> {}); SwingUtilities.invokeAndWait(() -> {}); }
    public static AbstractButton button(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof AbstractButton && text.equals(((AbstractButton)c).getText())) return (AbstractButton)c;
            if (c instanceof Container) { AbstractButton found = button((Container)c, text); if (found != null) return found; }
        } return null;
    }
    public static <T> T named(Container root, String name, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && name.equals(c.getName())) return type.cast(c);
            if (c instanceof Container) { T found = named((Container)c, name, type); if (found != null) return found; }
        } return null;
    }
}
