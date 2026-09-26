package tomato.gui.notifications;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import tomato.gui.maingui.AlertRuleEditor;
import tomato.realmshark.AlertDecisions;
import tomato.realmshark.AlertDecisions.Entry;
import tomato.realmshark.AlertDecisions.Result;
import tomato.realmshark.AlertDecisions.Source;
import tomato.realmshark.AlertRules;
import tomato.realmshark.Sound;
import tomato.realmshark.SoundSeam;
import util.PreferencesStore;
import static org.junit.Assert.*;
import static tomato.gui.maingui.SocialRuleEditorTest.named;

/** ALERT-4 UI: outcomes are distinguishable without replaying the event, and Edit rule resolves the recorded rule safely. */
public class RecentDecisionsTest {
    private final AtomicInteger audio = new AtomicInteger();
    private final Map<String, String> memory = new HashMap<>();
    private final AlertRules rules = new AlertRules(memory::get, (key, value) -> { memory.put(key, value); return CompletableFuture.completedFuture(PreferencesStore.SaveResult.saved(1)); });

    @Before public void isolate() { AlertDecisions.INSTANCE.clear(); SoundSeam.count(audio); }
    @After public void restore() { SoundSeam.clear(); AlertDecisions.INSTANCE.clear(); }

    private static long record(Entry entry) { return AlertDecisions.INSTANCE.record(entry); }

    @Test public void outcomesAreDistinctDetachedAndEditRuleFollowsTheRecordedRule() throws Exception {
        rules.save(rules.snapshot(AlertRules.Domain.CHAT, Collections.emptyList()), Collections.singletonList(AlertRules.Rule.of(AlertRules.Mode.TEXT_CONTAINS, "help")));
        AlertRules.Rule help = rules.snapshot(AlertRules.Domain.CHAT, Collections.emptyList()).rules.get(0);
        long noMatch = record(new Entry(Source.CHAT).result(Result.NO_MATCH).rule(AlertRules.Domain.CHAT, null, -1).subject("World · Ann")
            .sample("where is the vault", null).explain("No match. Literal case-insensitive contains: help"));
        long ignored = record(new Entry(Source.CHAT).result(Result.IGNORED).sound(Sound.keywords).rule(AlertRules.Domain.CHAT, help, 0).subject("World · Bot").explain("Would have played; ignored."));
        long cooldown = record(new Entry(Source.REALM_EVENT).result(Result.COOLDOWN).sound(Sound.keypop).ref("removed-rule-id").subject("Synthetic · announcement").explain("Cooldown."));
        long muted = record(new Entry(Source.CHAT).sound(Sound.keywords).rule(AlertRules.Domain.CHAT, help, 0).subject("World · Ann").explain("Matched rule 1"));
        AlertDecisions.INSTANCE.complete(muted, Result.MUTED, "Mute all is on.");
        long unavailable = record(new Entry(Source.ITEM).sound(Sound.custom).subject("Synthetic Blade (#42)").sample("Synthetic Blade", 42).explain("Matched"));
        AlertDecisions.INSTANCE.complete(unavailable, Result.UNAVAILABLE, "Could not play: no device.");
        int recorded = AlertDecisions.INSTANCE.snapshot(true).size();

        SwingUtilities.invokeAndWait(() -> {
            NotificationsGUI ui = new NotificationsGUI(); RecentDecisionsPanel panel = ui.decisions; panel.rules = rules;
            List<AlertRuleEditor> editors = new ArrayList<>(); List<AlertRules.Draft> drafts = new ArrayList<>();
            panel.editorOpener = editors::add; panel.draftOpener = drafts::add;
            panel.refresh();
            Set<String> outcomes = new HashSet<>();
            for (int i = 0; i < panel.model.getRowCount(); i++) outcomes.add((String)panel.model.getValueAt(i, 2));
            assertEquals(new HashSet<>(Arrays.asList("Matched but ignored", "Matched · cooldown", "Matched but muted", "Playback unavailable")), outcomes);

            assertTrue(ui.focusDecision(noMatch));
            assertTrue(panel.includeNoMatch.isSelected()); assertEquals(noMatch, panel.selected().id);
            assertTrue(named(panel, "decisions-detail", JTextArea.class).getText().contains("Outcome: No match"));
            assertTrue(panel.draftRule.isEnabled()); panel.draftRule.doClick();
            assertEquals("where is the vault", drafts.get(0).sampleText); assertEquals(AlertRules.Domain.CHAT, drafts.get(0).domain);

            assertTrue(ui.focusDecision(unavailable));
            assertTrue(panel.detail.getText().contains("Playback unavailable") && panel.detail.getText().contains("no device"));

            // The recorded rule moved: Edit rule finds it by mode and value, not by its old index.
            rules.save(rules.snapshot(AlertRules.Domain.CHAT, Collections.emptyList()), Arrays.asList(AlertRules.Rule.of(AlertRules.Mode.SPACE_TOKEN, "priest"), help));
            assertTrue(ui.focusDecision(muted)); panel.editRule.doClick();
            assertEquals(1, editors.size()); assertTrue(panel.status.getText(), panel.status.getText().contains("now rule 2 (it was rule 1"));
            assertEquals(1, named(editors.get(0), "alert-rule-table", JTable.class).getSelectedRow());
            // The recorded rule was removed: nothing else is selected in its place.
            rules.save(rules.snapshot(AlertRules.Domain.CHAT, Collections.emptyList()), Collections.singletonList(AlertRules.Rule.of(AlertRules.Mode.SPACE_TOKEN, "priest")));
            assertTrue(ui.focusDecision(ignored)); panel.editRule.doClick();
            assertTrue(panel.status.getText().contains("no longer active")); assertEquals(-1, named(editors.get(1), "alert-rule-table", JTable.class).getSelectedRow());
            assertTrue(named(editors.get(1), "rule-draft-source", JTextArea.class).getText().contains("no longer active"));

            assertTrue(ui.focusDecision(cooldown)); panel.editRule.doClick();
            assertEquals("That realm event rule was removed after this decision.", panel.status.getText());
            assertFalse(ui.focusDecision(999_999));
        });
        assertEquals("viewing decisions never replays or plays anything", 0, audio.get());
        assertEquals(recorded, AlertDecisions.INSTANCE.snapshot(true).size());
    }
}
