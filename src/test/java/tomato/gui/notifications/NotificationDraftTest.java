package tomato.gui.notifications;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.keypop.KeypopGUI;
import tomato.realmshark.RealmEventAlerts;
import util.PreferencesStore;
import static org.junit.Assert.*;
import static tomato.gui.maingui.SocialRuleEditorTest.*;

public class NotificationDraftTest {
    @Test public void failedRealmCreationKeepsEditablePhraseAndRetryDoesNotDuplicateEvent() throws Exception {
        int before = RealmEventAlerts.INSTANCE.getRules().size();
        CompletableFuture<PreferencesStore.SaveResult> first = new CompletableFuture<>(), second = new CompletableFuture<>();
        int[] attempts = {0}; JPanel[] draft = new JPanel[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                NotificationsGUI ui = new NotificationsGUI(); draft[0] = ui.createRealmDraft(() -> ++attempts[0] == 1 ? first : second);
                named(draft[0], "realm-draft-name", JTextField.class).setText("Synthetic draft event");
                named(draft[0], "realm-draft-phrase", JTextField.class).setText("synthetic arrives"); button(draft[0], "Save event").doClick();
                assertEquals(before + 1, RealmEventAlerts.INSTANCE.getRules().size());
            });
            first.complete(PreferencesStore.SaveResult.failed(1, new IOException("disk denied"))); drain();
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(named(draft[0], "realm-draft-save-status", JTextArea.class).getText().contains("Save failed"));
                JTextField phrase = named(draft[0], "realm-draft-phrase", JTextField.class); assertTrue(phrase.isEditable());
                phrase.setText("synthetic enters"); button(draft[0], "Retry save").doClick();
                assertEquals(before + 1, RealmEventAlerts.INSTANCE.getRules().size());
                RealmEventAlerts.Rule rule = RealmEventAlerts.INSTANCE.getRules().get(before);
                assertEquals("synthetic enters", rule.getPhrase()); assertFalse(rule.sound.isEnabled());
            });
            second.complete(PreferencesStore.SaveResult.saved(2)); drain();
            SwingUtilities.invokeAndWait(() -> assertTrue(named(draft[0], "realm-draft-save-status", JTextArea.class).getText().contains("saved")));
        } finally { for (RealmEventAlerts.Rule rule : RealmEventAlerts.INSTANCE.getRules()) if ("Synthetic draft event".equals(rule.name)) RealmEventAlerts.INSTANCE.remove(rule); }
    }
    @Test public void dungeonSelectedOnlyBulkClearPreservesHiddenChoicesAndCounts() throws Exception {
        Set<String> previous = KeypopGUI.getSelectedDungeons();
        try { SwingUtilities.invokeAndWait(() -> {
            KeypopGUI.setSelectedDungeons(new TreeSet<>(Arrays.asList("Lost Halls", "The Shatters")));
            NotificationsGUI ui = new NotificationsGUI(); ui.dungeonSearch.setText("Lost Halls"); ui.dungeonSelectedOnly.doClick();
            assertTrue(ui.dungeonCount.getText().contains("1 outside filter"));
            button(ui, "Clear shown").doClick();
            assertEquals(Collections.singleton("The Shatters"), KeypopGUI.getSelectedDungeons());
            assertTrue(ui.dungeonCount.getText().contains("0 selected shown"));
        }); } finally { KeypopGUI.setSelectedDungeons(previous); }
    }
}
