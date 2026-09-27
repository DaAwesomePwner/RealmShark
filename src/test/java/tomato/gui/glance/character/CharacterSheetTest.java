package tomato.gui.glance.character;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.LiveCharacter;
import tomato.backend.data.RosterDefinitions;
import tomato.backend.data.TomatoData;
import tomato.gui.kit.Banner;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.planning.PlanningStore;
import tomato.realmshark.RealmCharacter;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;

public class CharacterSheetTest {
    private static final String ORDER = "ui.tabs.character";
    private static final String ACCOUNT = CharacterJournal.accountKey("sheet-fixture");
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String savedOrder; private DisplayModeModel.Mode savedMode;

    @Before public void remember() throws Exception {
        savedOrder = PropertiesManager.getProperty(ORDER); PropertiesManager.setProperties(ORDER, "");
        SwingUtilities.invokeAndWait(() -> savedMode = DisplayModeModel.application().mode());
    }
    @After public void restore() throws Exception {
        PropertiesManager.setProperties(ORDER, savedOrder == null ? "" : savedOrder);
        SwingUtilities.invokeAndWait(() -> DisplayModeModel.application().set(savedMode));
    }

    private CharacterJournal journal(String file, int... ids) { return journal(new CharacterJournal(temp.getRoot().toPath().resolve(file)), ids); }
    private static CharacterJournal journal(CharacterJournal journal, int... ids) {
        List<RealmCharacter> roster = new ArrayList<>();
        for (int id : ids) {
            RealmCharacter c = new RealmCharacter(); c.charId = id; c.classNum = 782; c.level = 20; c.receivedAt = 1000;
            c.supplied("class"); c.supplied("level"); roster.add(c);
        }
        journal.mergeRoster(ACCOUNT, roster);
        return journal;
    }
    private static CharacterSheet sheet(CharacterJournal journal) {
        return new CharacterSheet(new SheetContext(new TomatoData(), journal, RosterDefinitions::empty, DisplayModeModel.application(), () -> 5000, PlanningStore.shared()));
    }
    /** Opens {@code key} and waits (running the EDT) until the sheet shows its read: the model is built off the EDT. */
    private static void open(CharacterSheet sheet, String key, String tab) { sheet.open(key, tab); await(sheet::ready); }
    private static List<String> titles(JTabbedPane tabs) {
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) titles.add(tabs.getTitleAt(i));
        return titles;
    }

    @Test public void tabsKeepTheirIdsAndOrderSnapshotEvidenceIsAnalystOnlyAndSlotsAreReplaceable() throws Exception {
        try (CharacterJournal journal = journal("tabs.json", 1)) {
            SwingUtilities.invokeAndWait(() -> {
                DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
                CharacterSheet sheet = sheet(journal);
                assertEquals("character-sheet", sheet.getName());
                List<String> order = Arrays.asList("overview", "gear", "exalts", "goals", "notes", "evidence", "death");
                assertEquals(order, sheet.tabs().order());
                JTabbedPane tabs = sheet.tabs().component();
                assertEquals("character-tabs", tabs.getName());
                assertEquals("Death annotation shows only for a character marked dead", Arrays.asList("Overview", "Gear", "Exalts", "Goals", "Notes", "Snapshot evidence"), titles(tabs));
                DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
                assertEquals(5, tabs.getTabCount()); assertEquals(-1, tabs.indexOfTab("Snapshot evidence"));
                open(sheet, ACCOUNT + ":1", "notes"); assertEquals("notes", sheet.selectedTab());
                open(sheet, ACCOUNT + ":1", "goals"); assertEquals("An explicit tab is selected", "goals", sheet.selectedTab());
                JPanel replacement = new JPanel();
                sheet.setTab("overview", replacement);
                assertEquals("A replaced slot keeps its id and place", order, sheet.tabs().order());
                assertTrue(SwingUtilities.isDescendingFrom(replacement, tabs.getComponentAt(0)));
                try { sheet.setTab("notes", new JPanel()); fail("Only overview, gear and exalts are slots"); } catch (IllegalArgumentException expected) { }
            });
        }
    }

    @Test public void headerHasTheBackLinkIdentityAndMarkDeadShowsTheDeathTab() throws Exception {
        try (CharacterJournal journal = journal("header.json", 7)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                AtomicInteger backs = new AtomicInteger();
                sheet.onBack(backs::incrementAndGet);
                open(sheet, ACCOUNT + ":7", null);
                assertEquals(ACCOUNT + ":7", sheet.key());
                AbstractButton back = named(sheet, "character-sheet-back", AbstractButton.class);
                assertEquals("‹ Characters", back.getText());
                back.doClick(); assertEquals(1, backs.get());
                assertTrue("The fixture has no name: the header reads \"<class> #7\"", named(sheet, "character-sheet-name", JLabel.class).getText().endsWith("#7"));
                assertTrue(named(sheet, "character-sheet-meta", JLabel.class).getText().contains("Level 20"));
                AbstractButton death = named(sheet, "character-sheet-death", AbstractButton.class), restore = named(sheet, "character-sheet-restore", AbstractButton.class);
                assertEquals("Mark dead", death.getText()); assertTrue(death.isVisible()); assertFalse(restore.isVisible());
                assertFalse("An alive character has no Death annotation tab", sheet.tabs().visibleIds().contains("death"));
                death.doClick();
                assertTrue(journal.characterCopy(ACCOUNT + ":7").dead);
                assertFalse("Nothing acts again until the re-read shows the new state", sheet.ready());
                await(sheet::ready);
                assertFalse(death.isVisible()); assertTrue(restore.isVisible()); assertEquals("Restore alive", restore.getText());
                assertTrue(named(sheet, "character-sheet-dead", JComponent.class).isVisible());
                assertTrue("Marking dead shows the Death annotation tab", sheet.tabs().visibleIds().contains("death"));
                assertEquals("…without rewriting the saved order", "", PropertiesManager.getProperty(ORDER));
                restore.doClick();
                assertFalse(journal.characterCopy(ACCOUNT + ":7").dead);
                await(sheet::ready);
                assertTrue(death.isVisible()); assertEquals("Mark dead", death.getText());
                assertFalse(sheet.tabs().visibleIds().contains("death"));
            });
        }
    }

    @Test public void openingADeadCharactersDeathTabLandsOnDeathOnceItsRecordLoads() throws Exception {
        try (CharacterJournal journal = journal("death-tab.json", 1)) {
            journal.markDead(ACCOUNT + ":1", true);
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                sheet.open(ACCOUNT + ":1", "death");
                assertFalse("Death is not visible until the dead record actually loads", sheet.tabs().visibleIds().contains("death"));
                await(sheet::ready);
                assertTrue("The explicit request is retried once the record applies", sheet.tabs().visibleIds().contains("death"));
                assertEquals("death", sheet.selectedTab());
            });
        }
    }

    @Test public void movingBetweenDeadCharactersKeepsTheDeathTabSelected() throws Exception {
        try (CharacterJournal journal = journal("death-tab-move.json", 1, 2)) {
            journal.markDead(ACCOUNT + ":1", true);
            journal.markDead(ACCOUNT + ":2", true);
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                open(sheet, ACCOUNT + ":1", "death");
                assertEquals("death", sheet.selectedTab());
                open(sheet, ACCOUNT + ":2", "death");
                assertEquals("Moving to another dead character keeps Death, not the transient Overview fallback", "death", sheet.selectedTab());
            });
        }
    }

    @Test public void aMapChangeGraceExpiringWithNoNewPublishDropsPlayingNowAndTheLiveBoosts() throws Exception {
        try (CharacterJournal journal = journal("grace.json", 7)) {
            long[] clock = {5_000};
            // A stable reference: RosterDefinitions::empty would build a fresh, unequal instance on every read and make the
            // presenter's token differ (and so rebuild) on every refresh regardless of live state, defeating this test.
            RosterDefinitions defs = RosterDefinitions.empty();
            SwingUtilities.invokeAndWait(() -> {
                TomatoData data = new TomatoData();
                CharacterSheet sheet = new CharacterSheet(new SheetContext(data, journal, () -> defs, DisplayModeModel.application(), () -> clock[0], PlanningStore.shared()));
                data.liveCharacter.publish(SheetFixtures.live(ACCOUNT, 7, "Sharkbait", null));
                open(sheet, ACCOUNT + ":7", null);
                assertTrue("The live character shows Playing now", named(sheet, "character-sheet-playing", JComponent.class).isVisible());
                assertTrue("A live boost shows while playing", named(sheet, "character-overview-boost-0", JComponent.class).isVisible());

                data.liveCharacter.clear(clock[0], LiveCharacter.Boundary.TRANSIENT);
                sheet.refresh(); // the clear alone bumps live's revision and settles one rebuild, still well within Home's 5 s grace

                clock[0] += 5_001; // past the grace window now, with no further publish or clear: live's revision does not move again
                sheet.refresh();
                await(() -> !named(sheet, "character-sheet-playing", JComponent.class).isVisible());
                assertFalse("The grace expired with no new publish: the live boost drops too",
                    named(sheet, "character-overview-boost-0", JComponent.class).isVisible());
            });
        }
    }

    @Test public void anUnknownKeyShowsTheUnavailableStateAndAKnownOneTheTabs() throws Exception {
        try (CharacterJournal journal = journal("unknown.json", 1)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                open(sheet, ACCOUNT + ":404", null);
                EmptyState missing = named(sheet, "character-sheet-unavailable", EmptyState.class);
                assertTrue(missing.isVisible());
                assertEquals(CharacterSheet.UNAVAILABLE, missing.getAccessibleContext().getAccessibleName());
                assertFalse(named(sheet, "character-sheet-death", AbstractButton.class).isEnabled());
                assertEquals(" ", named(sheet, "character-snapshot-evidence", JTextArea.class).getText());
                open(sheet, ACCOUNT + ":1", null);
                assertFalse(missing.isVisible());
                assertTrue(named(sheet, "character-sheet-death", AbstractButton.class).isEnabled());
            });
        }
    }

    @Test public void aNotesDraftSurvivesRefreshAndIsSavedWhenAnotherCharacterOpens() throws Exception {
        try (CharacterJournal journal = journal("notes.json", 1, 2)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                open(sheet, ACCOUNT + ":1", "notes");
                JTextArea notes = named(sheet, "character-notes", JTextArea.class);
                notes.setText("Draft for one");
                // A real journal change for the SAME key forces the presenter to rebuild (unlike calling refresh() on an
                // unchanged journal, which only queues a build without necessarily proving one was actually applied): mark the
                // character dead, wait for the rebuilt model to actually land (the Death tab becoming visible is the applied
                // model's own, observable effect), then check the draft was not overwritten by the freshly loaded record.
                journal.markDead(ACCOUNT + ":1", true);
                sheet.refresh();
                await(() -> sheet.tabs().visibleIds().contains("death"));
                assertEquals("A real rebuild of the same character keeps the unsaved draft", "Draft for one", notes.getText());
                assertEquals("The draft was never saved to the journal", "", journal.characterCopy(ACCOUNT + ":1").notes);
                sheet.open(ACCOUNT + ":2", null);
                assertEquals("Opening another character saves the draft", "Draft for one", journal.characterCopy(ACCOUNT + ":1").notes);
                assertEquals("", notes.getText());
                await(sheet::ready);
                notes.setText("Saved for two"); named(sheet, "character-notes-save", AbstractButton.class).doClick();
                assertEquals("Saved for two", journal.characterCopy(ACCOUNT + ":2").notes);
            });
        }
    }

    @Test public void openingShowsLoadingUntilItsOwnReadAndDropsAnEarlierCharactersResult() throws Exception {
        try (CharacterJournal journal = journal("loading.json", 1, 2)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                open(sheet, ACCOUNT + ":1", null);
                Banner status = named(sheet, "character-sheet-status", Banner.class);
                assertFalse(status.isVisible());
                sheet.open(ACCOUNT + ":2", null);
                assertTrue("Loading shows until the new character's read applies", status.isVisible());
                assertEquals("Loading…", status.text()); assertFalse(status.warns());
                assertFalse(sheet.ready());
                assertEquals("Nothing of the previous character stays", "", named(sheet, "character-sheet-name", JLabel.class).getText());
                assertFalse("Nothing acts while loading", named(sheet, "character-sheet-death", AbstractButton.class).isEnabled());
                sheet.open(ACCOUNT + ":1", null); // #2's build may still arrive: it is for another key now, so it is dropped
                await(sheet::ready);
                assertFalse(status.isVisible());
                assertEquals(ACCOUNT + ":1", sheet.key());
                assertTrue(named(sheet, "character-sheet-name", JLabel.class).getText().endsWith("#1"));
                long settled = System.currentTimeMillis() + 200;
                await(() -> System.currentTimeMillis() >= settled); // runs the EDT, so a late result would arrive now
                assertTrue("A late result for #2 never replaces #1", named(sheet, "character-sheet-name", JLabel.class).getText().endsWith("#1"));
                assertTrue(named(sheet, "character-sheet-death", AbstractButton.class).isEnabled());
            });
        }
    }

    @Test public void aFailedBuildShowsAWarnBannerAndNothingActs() throws Exception {
        RosterDefinitions none = RosterDefinitions.empty();
        try (CharacterJournal journal = journal("failure.json", 1)) {
            SheetContext failing = new SheetContext(new TomatoData(), journal, () -> {
                if ("character-sheet".equals(Thread.currentThread().getName())) throw new IllegalStateException("Synthetic build failure");
                return none;
            }, DisplayModeModel.application(), () -> 5000, PlanningStore.shared());
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = new CharacterSheet(failing);
                sheet.open(ACCOUNT + ":1", null);
                Banner status = named(sheet, "character-sheet-status", Banner.class);
                await(status::warns);
                assertTrue(status.isVisible()); assertTrue(status.text(), status.text().contains("Synthetic build failure"));
                assertFalse(sheet.ready()); assertFalse(named(sheet, "character-sheet-death", AbstractButton.class).isEnabled());
            });
        }
    }

    @Test public void hidingTheSheetSavesItsNotesDraft() throws Exception {
        try (CharacterJournal journal = journal("hide.json", 1)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                JFrame frame = new JFrame("Sheet hide"); frame.setContentPane(sheet); frame.setSize(900, 600); frame.setVisible(true);
                try {
                    open(sheet, ACCOUNT + ":1", "notes");
                    named(sheet, "character-notes", JTextArea.class).setText("Kept when the sheet hides");
                    sheet.setVisible(false); // what another card, Back or another Characters tab does
                    assertEquals("Kept when the sheet hides", journal.characterCopy(ACCOUNT + ":1").notes);
                } finally { frame.dispose(); }
            });
        }
    }

    @Test public void snapshotEvidenceAndTheTabHintAreAnalystOnly() throws Exception {
        try (CharacterJournal journal = journal("provenance.json", 1)) {
            SwingUtilities.invokeAndWait(() -> {
                DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
                CharacterSheet sheet = sheet(journal);
                open(sheet, ACCOUNT + ":1", null);
                JTextArea evidence = named(sheet, "character-snapshot-evidence", JTextArea.class), hint = named(sheet, "character-sheet-hint", JTextArea.class);
                assertFalse("Simple hides provenance (spec §3.2)", evidence.isVisible()); assertFalse(hint.isVisible());
                assertTrue("The text is still kept current", evidence.getText().contains("Snapshot update age"));
                DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
                assertTrue(evidence.isVisible()); assertTrue(hint.isVisible());
            });
        }
    }

    @Test public void anUnreadableJournalAndAFailedNotesSaveShowAWarnBanner() throws Exception {
        Path broken = temp.getRoot().toPath().resolve("broken.json");
        Files.writeString(broken, "{broken");
        Path blocker = temp.newFile("blocker").toPath(); // a file where the journal's folder should be: every save fails
        CharacterSheet[] shown = new CharacterSheet[1];
        try (CharacterJournal unreadable = new CharacterJournal(broken); CharacterJournal failing = journal(new CharacterJournal(blocker.resolve("journal.json")), 1)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(unreadable);
                open(sheet, ACCOUNT + ":1", null);
                Banner storage = named(sheet, "character-sheet-storage", Banner.class);
                assertTrue(storage.isVisible()); assertTrue(storage.warns()); assertTrue(storage.text(), storage.text().startsWith("Cannot read"));
                shown[0] = sheet(failing);
                open(shown[0], ACCOUNT + ":1", "notes");
                assertFalse("Nothing has failed yet", named(shown[0], "character-sheet-storage", Banner.class).isVisible());
                named(shown[0], "character-notes", JTextArea.class).setText("Never reaches the disk");
                named(shown[0], "character-notes-save", AbstractButton.class).doClick();
            });
            failing.save(); // the saver thread's write fails
            SwingUtilities.invokeAndWait(() -> {
                shown[0].refresh();
                Banner storage = named(shown[0], "character-sheet-storage", Banner.class);
                assertTrue("A failed save warns inside the sheet", storage.isVisible()); assertTrue(storage.warns());
                assertTrue(storage.text(), storage.text().startsWith("Save failed"));
            });
        }
    }

    private static <T> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = named((Container) child, name, type); if (found != null) return found; }
        }
        return null;
    }
}
