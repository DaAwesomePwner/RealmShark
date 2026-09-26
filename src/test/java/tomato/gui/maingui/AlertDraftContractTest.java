package tomato.gui.maingui;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.After;
import org.junit.Test;
import tomato.realmshark.AlertRules;
import tomato.realmshark.Sound;
import tomato.realmshark.SoundSeam;
import util.PreferencesStore;
import static org.junit.Assert.*;
import static tomato.gui.maingui.SocialRuleEditorTest.*;

/** Contract published for Chat, Loot and Bridge drafts: opening evaluates silently and never saves, enables or plays. */
public class AlertDraftContractTest {
    private final Map<String, String> memory = new HashMap<>();
    private final AtomicInteger writes = new AtomicInteger();
    private final AlertRules rules = new AlertRules(memory::get, (key, value) -> {
        writes.incrementAndGet(); memory.put(key, value); return CompletableFuture.completedFuture(PreferencesStore.SaveResult.saved(1));
    });
    private final AtomicInteger audio = new AtomicInteger();

    @After public void restore() { SoundSeam.clear(); }

    private AlertRuleEditor open(AlertRules.Draft draft) {
        return new AlertRuleEditor(rules, draft.domain, Collections.emptyList(), AlertRuleEditor.titleFor(draft.domain),
            () -> AlertRuleEditor.soundFor(draft.domain).preview(null), draft);
    }

    @Test public void chatDraftAddsUnsavedRowAndChecksDetachedSampleWithoutSavingEnablingOrPlaying() throws Exception {
        boolean keywords = Sound.keywords.isEnabled();
        SoundSeam.count(audio);
        SwingUtilities.invokeAndWait(() -> {
            AlertRules.Draft draft = AlertRules.Draft.chat(AlertRules.Mode.TEXT_CONTAINS, "need a priest", "Ann: need a priest at the nexus", "Chat · 12:00:00 · World · Ann");
            AlertRuleEditor editor = open(draft);
            JTable table = named(editor, "alert-rule-table", JTable.class);
            assertEquals(1, table.getRowCount()); assertEquals(0, table.getSelectedRow());
            assertEquals("Ann: need a priest at the nexus", named(editor, "rule-sample-text", JTextField.class).getText());
            assertTrue(named(editor, "rule-sample-result", JTextArea.class).getText().startsWith("Matched rule 1"));
            String summary = named(editor, "rule-draft-source", JTextArea.class).getText();
            assertTrue(summary.contains("Chat · 12:00:00")); assertTrue(summary.contains("Nothing is saved or enabled"));
            assertTrue(named(editor, "rule-save-status", JTextArea.class).getText().contains("Unsaved"));
        });
        assertEquals("opening a draft must not save", 0, writes.get());
        assertEquals("opening a draft must not play", 0, audio.get());
        assertEquals(keywords, Sound.keywords.isEnabled());
        assertTrue(memory.isEmpty());
    }

    @Test public void identicalRuleIsSelectedInsteadOfDuplicatedAndInvalidProposalStaysOut() throws Exception {
        rules.save(rules.snapshot(AlertRules.Domain.ITEM, Collections.emptyList()), Collections.singletonList(AlertRules.Rule.of(AlertRules.Mode.ITEM_ID, "42")));
        int saved = writes.get();
        SoundSeam.count(audio);
        SwingUtilities.invokeAndWait(() -> {
            AlertRuleEditor same = open(AlertRules.Draft.item(AlertRules.Mode.ITEM_ID, "42", 42, "Synthetic Blade", "Loot · synthetic"));
            assertEquals(1, named(same, "alert-rule-table", JTable.class).getRowCount());
            assertTrue(named(same, "rule-draft-source", JTextArea.class).getText().contains("already exists (rule 1)"));
            assertTrue(named(same, "rule-sample-result", JTextArea.class).getText().startsWith("Matched rule 1"));
            assertTrue(same.focusRule(AlertRules.Mode.ITEM_ID, "42")); assertFalse(same.focusRule(AlertRules.Mode.ITEM_ID, "142"));

            AlertRuleEditor miss = open(AlertRules.Draft.item(AlertRules.Mode.ITEM_ID, "43", 142, null, "Bridge review · synthetic"));
            assertEquals(2, named(miss, "alert-rule-table", JTable.class).getRowCount());
            assertTrue(named(miss, "rule-sample-result", JTextArea.class).getText().startsWith("No match"));

            AlertRuleEditor invalid = new AlertRuleEditor(rules, AlertRules.Domain.CHAT, Collections.emptyList(), "Chat", () -> {},
                AlertRules.Draft.chat(AlertRules.Mode.SPACE_TOKEN, "two words", "two words here", "Chat · synthetic"));
            assertEquals(0, named(invalid, "alert-rule-table", JTable.class).getRowCount());
            assertTrue(named(invalid, "rule-draft-source", JTextArea.class).getText().contains("Proposed rule not added"));

            AlertRuleEditor entity = open(AlertRules.Draft.entity(45076, "Encounter · synthetic"));
            assertTrue(named(entity, "rule-sample-result", JTextArea.class).getText().startsWith("Matched rule 1"));
            button(entity, "Test sound").doClick();
        });
        assertEquals(saved, writes.get());
        assertEquals("only the explicit Test sound plays", 1, audio.get());
    }

    @Test public void draftsAreDetachedBoundedAndRejectMismatchedCategories() {
        StringBuilder longText = new StringBuilder(); for (int i = 0; i < 3000; i++) longText.append('x');
        AlertRules.Draft draft = AlertRules.Draft.chat(AlertRules.Mode.TEXT_CONTAINS, longText.toString(), longText.toString(), null);
        assertEquals(AlertRules.Draft.MAX_VALUE, draft.value.length()); assertEquals(AlertRules.Draft.MAX_SAMPLE, draft.sampleText.length());
        assertEquals("Selected record", draft.source);
        try { AlertRules.Draft.chat(AlertRules.Mode.ITEM_ID, "1", "x", "s"); fail(); } catch (IllegalArgumentException expected) { }
        try { new AlertRuleEditor(rules, AlertRules.Domain.ITEM, Collections.emptyList(), "Item", () -> {}, draft); fail(); } catch (IllegalArgumentException expected) { }
        memory.put(AlertRules.Domain.ITEM.legacyKey, "42§Blade§§");
        assertEquals(Arrays.asList("42", "Blade"), rules.storedLegacy(AlertRules.Domain.ITEM));
        assertEquals(0, writes.get());
    }
}
