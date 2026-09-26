package tomato.gui.notifications;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import tomato.gui.keypop.KeypopGUI;
import tomato.gui.maingui.AlertRuleEditor;
import tomato.gui.modern.WorkspaceShell;
import tomato.realmshark.AlertDecisions;
import tomato.realmshark.AlertDecisions.Entry;
import tomato.realmshark.AlertDecisions.Result;
import tomato.realmshark.AlertDecisions.Source;
import tomato.realmshark.AlertRules;
import tomato.realmshark.Sound;
import tomato.realmshark.SoundSeam;
import ui.VisualEvidence;
import util.PreferencesStore;
import static org.junit.Assert.*;
import static ui.WaveThreeEvidence.*;

/**
 * Wave 3 visual evidence for ALERT-4 Recent decisions, the KEY-3 Key-pops focus banner and CHAT-3 draft editing.
 * Decisions are synthetic in-memory records; rules live in memory; no audio is submitted.
 */
public class WaveThreeEvidenceTest {
    @Rule public VisualEvidence evidence = new VisualEvidence(FOLDER);
    private final AtomicInteger audio = new AtomicInteger();
    private final Map<String, String> memory = new HashMap<>();
    private final AtomicInteger writes = new AtomicInteger();
    private final AlertRules rules = new AlertRules(memory::get, (key, value) -> {
        writes.incrementAndGet(); memory.put(key, value); return CompletableFuture.completedFuture(PreferencesStore.SaveResult.saved(1));
    });
    private Set<String> previousDungeons;

    @Before public void isolate() { AlertDecisions.INSTANCE.clear(); SoundSeam.count(audio); previousDungeons = KeypopGUI.getSelectedDungeons(); }
    @After public void restore() throws Exception {
        run(() -> KeypopGUI.setSelectedDungeons(previousDungeons));
        SoundSeam.clear(); AlertDecisions.INSTANCE.clear();
    }

    private static WorkspaceShell shell(NotificationsGUI page) {
        JComponent[] pages = new JComponent[WorkspaceShell.TITLES.length]; Arrays.setAll(pages, i -> new JPanel());
        pages[13] = page;
        WorkspaceShell shell = new WorkspaceShell(pages, () -> fail("Synthetic page must not capture"), true);
        shell.select(13); return shell;
    }

    @Test public void notificationsRecentDecisionsMixedOutcomesAndEmpty() throws Exception {
        rules.save(rules.snapshot(AlertRules.Domain.CHAT, Collections.emptyList()), Collections.singletonList(AlertRules.Rule.of(AlertRules.Mode.TEXT_CONTAINS, "help")));
        AlertRules.Rule help = rules.snapshot(AlertRules.Domain.CHAT, Collections.emptyList()).rules.get(0);
        NotificationsGUI page = edt(NotificationsGUI::new);
        WorkspaceShell shell = edt(() -> { page.decisions.rules = rules; page.decisions.editorOpener = editor -> fail("No editor"); page.decisions.draftOpener = draft -> fail("No draft"); return shell(page); });
        try {
            run(() -> page.selectSection(NotificationsGUI.DECISIONS));
            wideAndCompact(evidence, shell, "notifications-recent-decisions-empty", () -> {
                page.decisions.refresh();
                assertEquals(0, page.decisions.model.getRowCount());
            });
            AlertDecisions.INSTANCE.record(new Entry(Source.CHAT).result(Result.NO_MATCH).rule(AlertRules.Domain.CHAT, null, -1).subject("World · Ann")
                .sample("where is the vault", null).explain("No match. Literal case-insensitive contains: help"));
            AlertDecisions.INSTANCE.record(new Entry(Source.CHAT).result(Result.IGNORED).sound(Sound.keywords).rule(AlertRules.Domain.CHAT, help, 0).subject("World · Bot").explain("Would have played; the sender is ignored."));
            AlertDecisions.INSTANCE.record(new Entry(Source.REALM_EVENT).result(Result.COOLDOWN).sound(Sound.keypop).ref("synthetic-rule").subject("Synthetic · announcement").explain("Cooldown: 12 s since the last alert."));
            long muted = AlertDecisions.INSTANCE.record(new Entry(Source.CHAT).sound(Sound.keywords).rule(AlertRules.Domain.CHAT, help, 0).subject("World · Ann").explain("Matched rule 1"));
            AlertDecisions.INSTANCE.complete(muted, Result.MUTED, "Mute all is on.");
            long played = AlertDecisions.INSTANCE.record(new Entry(Source.CHAT).sound(Sound.keywords).rule(AlertRules.Domain.CHAT, help, 0).subject("Guild · Wren").explain("Matched rule 1"));
            AlertDecisions.INSTANCE.complete(played, Result.PLAYED, "Played on the synthetic output device.");
            long unavailable = AlertDecisions.INSTANCE.record(new Entry(Source.ITEM).sound(Sound.custom).subject("Synthetic Blade (#42)").sample("Synthetic Blade", 42).explain("Matched item rule"));
            AlertDecisions.INSTANCE.complete(unavailable, Result.UNAVAILABLE, "Could not play: no device.");
            AlertDecisions.INSTANCE.record(new Entry(Source.CHAT).sound(Sound.keywords).rule(AlertRules.Domain.CHAT, help, 0).subject("World · Cole").explain("Matched rule 1"));
            run(() -> assertTrue(page.focusDecision(unavailable)));
            wideAndCompact(evidence, shell, "notifications-recent-decisions-mixed", () -> {
                page.decisions.select(unavailable, true);
                reveal(named(page, "decisions-detail", JTextArea.class));
                Set<String> outcomes = new HashSet<>();
                for (int i = 0; i < page.decisions.model.getRowCount(); i++) outcomes.add((String) page.decisions.model.getValueAt(i, 2));
                assertTrue(outcomes.toString(), outcomes.containsAll(Arrays.asList("Played", "Matched but muted", "Playback unavailable", "Matched · cooldown", "Matched · playback pending")));
                assertTrue(page.decisions.detail.getText().contains("no device"));
            });
        } finally { run(evidence::closeWindow); }
        assertEquals("viewing decisions never plays", 0, audio.get());
    }

    @Test public void notificationsKeyPopFocusBannerKnownAndUnknown() throws Exception {
        run(() -> KeypopGUI.setSelectedDungeons(new TreeSet<>(Arrays.asList("The Shatters", "missingDungeons"))));
        Set<String> before = edt(KeypopGUI::getSelectedDungeons);
        NotificationsGUI page = edt(NotificationsGUI::new);
        WorkspaceShell shell = edt(() -> shell(page));
        try {
            run(() -> assertTrue(page.focusDungeon("Lost Halls", () -> {})));
            wideAndCompact(evidence, shell, "keypop-focus-banner", () -> {
                JTextArea focus = named(page, "sound-dungeon-focus", JTextArea.class);
                assertTrue(focus.getText(), focus.getText().contains("Lost Halls is currently not selected"));
                VisualEvidence.reachable(focus);
                assertTrue(named(page, "sound-dungeon-focus-back", JButton.class).isShowing());
            });
            run(() -> assertFalse(page.focusDungeon("Shield Rune", () -> {})));
            wideAndCompact(evidence, shell, "keypop-focus-unknown-dungeon", () ->
                assertTrue(named(page, "sound-dungeon-focus", JTextArea.class).getText().contains("not a known notification dungeon")));
            assertEquals("Focusing never changes a choice", before, edt(KeypopGUI::getSelectedDungeons));
        } finally { run(evidence::closeWindow); }
    }

    @Test public void alertRuleEditorOpenedAsDraft() throws Exception {
        AlertRuleEditor[] editor = new AlertRuleEditor[1];
        JPanel host = edt(() -> new JPanel(new java.awt.BorderLayout()));
        try {
            run(() -> {
                AlertRules.Draft draft = AlertRules.Draft.chat(AlertRules.Mode.TEXT_CONTAINS, "need a priest", "Ann: need a priest at the nexus", "Chat · 12:00:00 · World · Ann");
                editor[0] = new AlertRuleEditor(rules, draft.domain, Collections.emptyList(), AlertRuleEditor.titleFor(draft.domain), () -> Sound.keywords.preview(null), draft);
                host.add(editor[0]);
            });
            wideAndCompact(evidence, host, "alert-rule-editor-draft", () -> {
                assertEquals(1, named(editor[0], "alert-rule-table", JTable.class).getRowCount());
                assertTrue(named(editor[0], "rule-sample-result", JTextArea.class).getText().startsWith("Matched rule 1"));
                assertTrue(named(editor[0], "rule-draft-source", JTextArea.class).getText().contains("Nothing is saved or enabled"));
                assertTrue(named(editor[0], "rule-save-status", JTextArea.class).getText().contains("Unsaved"));
            });
            run(() -> {
                host.removeAll();
                AlertRules.Draft invalid = AlertRules.Draft.chat(AlertRules.Mode.SPACE_TOKEN, "two words", "Ann: two words here", "Chat · 12:01:00 · World · Ann");
                editor[0] = new AlertRuleEditor(rules, invalid.domain, Collections.emptyList(), AlertRuleEditor.titleFor(invalid.domain), () -> Sound.keywords.preview(null), invalid);
                host.add(editor[0]); host.revalidate();
            });
            wideAndCompact(evidence, host, "alert-rule-editor-draft-invalid", () -> {
                assertEquals("An invalid proposal is not added", 0, named(editor[0], "alert-rule-table", JTable.class).getRowCount());
                assertFalse(named(editor[0], "rule-draft-source", JTextArea.class).getText().trim().isEmpty());
            });
        } finally { run(evidence::closeWindow); }
        assertEquals("Opening a draft never saves", 0, writes.get());
        assertEquals("Opening a draft never plays", 0, audio.get());
    }
}
