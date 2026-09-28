package tomato.gui.glance.character;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
import tomato.planning.PlanData;
import tomato.planning.PlanningMetadata;
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
                // Pet and Fame are registered right after Exalts (P3b): new users get this order.
                List<String> order = Arrays.asList("overview", "gear", "exalts", "pet", "fame", "build", "goals", "notes", "evidence", "death");
                assertEquals(order, sheet.tabs().order());
                JTabbedPane tabs = sheet.tabs().component();
                assertEquals("character-tabs", tabs.getName());
                assertEquals("Death annotation shows only for a character marked dead", Arrays.asList("Overview", "Gear", "Exalts", "Pet", "Fame", "Build", "Goals", "Notes", "Snapshot evidence"), titles(tabs));
                DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);
                assertEquals(8, tabs.getTabCount()); assertEquals(-1, tabs.indexOfTab("Snapshot evidence"));
                assertEquals(3, tabs.indexOfTab("Pet")); assertEquals(4, tabs.indexOfTab("Fame"));
                open(sheet, ACCOUNT + ":1", "notes"); assertEquals("notes", sheet.selectedTab());
                open(sheet, ACCOUNT + ":1", "goals"); assertEquals("An explicit tab is selected", "goals", sheet.selectedTab());
                JPanel replacement = new JPanel();
                sheet.setTab("overview", replacement);
                assertEquals("A replaced slot keeps its id and place", order, sheet.tabs().order());
                assertTrue(SwingUtilities.isDescendingFrom(replacement, tabs.getComponentAt(0)));
                JPanel fame = new JPanel();
                sheet.setTab("fame", fame); // the Fame tab's slot: an empty placeholder until the Fame tab sets it
                assertTrue(SwingUtilities.isDescendingFrom(fame, tabs.getComponentAt(tabs.indexOfTab("Fame"))));
                assertEquals(order, sheet.tabs().order());
                try { sheet.setTab("notes", new JPanel()); fail("Only overview, gear, exalts, pet, fame and build are slots"); } catch (IllegalArgumentException expected) { }
            });
        }
    }

    /**
     * The Overview's pet card is explicit navigation: it shows the Pet tab even when the user hid it, then selects it. The fixture
     * roster says nothing about pets, so the Pet tab shows the unknown state (never "No pet").
     */
    @Test public void thePetCardOpensThePetTabEvenWhenItIsHidden() throws Exception {
        try (CharacterJournal journal = journal("pet.json", 1)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                open(sheet, ACCOUNT + ":1", "notes");
                assertTrue(sheet.tabs().hide("pet")); // saved to ui.tabs.character; @After restores it
                assertFalse(sheet.tabs().visibleIds().contains("pet"));
                named(sheet, "character-overview-pet", tomato.gui.kit.Card.class).getActionMap().get("open-card").actionPerformed(null);
                assertEquals("pet", sheet.selectedTab());
                assertTrue("Explicit navigation shows the hidden tab", sheet.tabs().visibleIds().contains("pet"));
                assertTrue(named(sheet, "character-pet-empty", EmptyState.class).isVisible());
                assertFalse(named(sheet, "character-pet-none", EmptyState.class).isVisible());
                sheet.openTab("fame");
                assertEquals("fame", sheet.selectedTab());
            });
        }
    }

    /**
     * P3b: the Goals tab shows cards for its own character only: this character's stat goals and this class's exalt goals from its
     * own account's saved plan (another character, another class, and another account's character with the same id show none).
     * A saved change shows on the next refresh; an unchanged refresh keeps the cards; another character shows its own cards. The
     * account-wide Manage goals panel stays below the cards: in Simple inside its collapsed section, in Analyst shown.
     */
    @Test public void theGoalsTabShowsCardsForItsOwnCharacterOnly() throws Exception {
        String otherAccount = CharacterJournal.accountKey("another-sheet-fixture");
        RosterDefinitions defs = SheetFixtures.defs(); // one stable instance: the cards rebuild only when an input moves
        try (CharacterJournal journal = journal("goals.json", 1, 2); PlanningStore plans = PlanningStore.memory()) {
            PlanData.AccountPlan plan = new PlanData.AccountPlan();
            SheetFixtures.statGoal(plan, ACCOUNT + ":1", 3, 25, defs, 1_000);
            SheetFixtures.statGoal(plan, ACCOUNT + ":2", 6, 40, defs, 1_000);
            SheetFixtures.exaltGoal(plan, SheetFixtures.WIZARD, 0, 2, PlanningMetadata.unavailable(), 1_000);
            SheetFixtures.exaltGoal(plan, SheetFixtures.PRIEST, 1, 1, PlanningMetadata.unavailable(), 1_000);
            assertTrue(plans.update(ACCOUNT, 0, plan).get().saved);
            PlanData.AccountPlan other = new PlanData.AccountPlan();
            SheetFixtures.statGoal(other, otherAccount + ":1", 0, 700, defs, 1_000); // the same character id on another account
            assertTrue(plans.update(otherAccount, 0, other).get().saved);
            SwingUtilities.invokeAndWait(() -> {
                DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE); // @After restores the mode
                CharacterSheet sheet = new CharacterSheet(new SheetContext(new TomatoData(), journal, () -> defs, DisplayModeModel.application(), () -> 5000, plans));
                open(sheet, ACCOUNT + ":1", "goals");
                assertEquals("goals", sheet.selectedTab());
                String title = named(sheet, "character-goals-title", JLabel.class).getText();
                assertTrue(title, title.startsWith("Goals for ") && title.endsWith(" #1"));
                JPanel grid = named(sheet, "character-goals-cards", JPanel.class);
                assertEquals("This character's DEF goal and this class's Life exalt goal only", 2, grid.getComponentCount());
                tomato.gui.kit.Card def = named(sheet, "character-goal-stat-3", tomato.gui.kit.Card.class);
                assertNotNull(def);
                assertNotNull(named(sheet, "character-goal-exalt-0", tomato.gui.kit.Card.class));
                assertNull("Character #2's goal", named(sheet, "character-goal-stat-6", tomato.gui.kit.Card.class));
                assertNull("The Priest's exalt goal", named(sheet, "character-goal-exalt-1", tomato.gui.kit.Card.class));
                assertNull("Another account's #1", named(sheet, "character-goal-stat-0", tomato.gui.kit.Card.class));
                assertEquals("The roster read no stats: unknown, never 0", "Unknown: base not captured",
                    named(sheet, "character-goal-stat-3-remaining", JTextArea.class).getText());
                assertFalse(named(sheet, "character-goals-empty", EmptyState.class).isVisible());

                tomato.gui.kit.Collapsible manage = named(sheet, "character-goals-manage", tomato.gui.kit.Collapsible.class);
                JComboBox<?> account = named(sheet, "planning-0", JComboBox.class);
                assertTrue("Simple: Manage goals is a section below the cards", manage.isVisible());
                assertTrue(SwingUtilities.isDescendingFrom(account, manage));
                DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST);
                assertFalse(manage.isVisible());
                assertFalse(SwingUtilities.isDescendingFrom(account, manage));
                for (java.awt.Component at = account; at != sheet; at = at.getParent()) assertTrue("Analyst shows the panel: " + at, at.isVisible());
                DisplayModeModel.application().set(DisplayModeModel.Mode.SIMPLE);

                sheet.refresh();
                assertSame("An unchanged refresh keeps the cards", def, named(sheet, "character-goal-stat-3", tomato.gui.kit.Card.class));
                PlanData.AccountPlan more = plans.snapshot(ACCOUNT).plan();
                SheetFixtures.statGoal(more, ACCOUNT + ":1", 7, 60, defs, 2_000);
                try { assertTrue(plans.update(ACCOUNT, plans.snapshot(ACCOUNT).revision, more).get().saved); } catch (Exception e) { throw new AssertionError(e); }
                sheet.refresh();
                assertNotNull("A saved goal shows on the next refresh", named(sheet, "character-goal-stat-7", tomato.gui.kit.Card.class));
                assertEquals(3, grid.getComponentCount());

                open(sheet, ACCOUNT + ":2", "goals");
                assertNotNull(named(sheet, "character-goal-stat-6", tomato.gui.kit.Card.class));
                assertNotNull("The same class's exalt goal", named(sheet, "character-goal-exalt-0", tomato.gui.kit.Card.class));
                assertNull(named(sheet, "character-goal-stat-3", tomato.gui.kit.Card.class));
                assertEquals(2, grid.getComponentCount());
            });
        }
    }

    /**
     * P3b evidence review: two vertical scroll bars side by side (the Goals tab scrolled inside the scrolling sheet page). The Goals
     * tab has no scroll pane of its own: while it shows it asks for its whole content's height and the sheet page scrolls it; while
     * another tab shows it asks only for Manage goals' minimum, Task 9's floor, so every other tab keeps its minimum height.
     */
    @Test public void onlyTheSheetPageScrollsTheGoalsTabAndTheOtherTabsKeepTheirMinimum() throws Exception {
        RosterDefinitions defs = SheetFixtures.defs();
        try (CharacterJournal journal = journal("goal-scroll.json", 1); PlanningStore plans = PlanningStore.memory()) {
            PlanData.AccountPlan plan = new PlanData.AccountPlan();
            for (int stat = 0; stat < 8; stat++) SheetFixtures.statGoal(plan, ACCOUNT + ":1", stat, 20, defs, 1_000); // eight cards
            assertTrue(plans.update(ACCOUNT, 0, plan).get().saved);
            CharacterSheet[] shown = new CharacterSheet[1];
            JFrame[] frame = new JFrame[1];
            SwingUtilities.invokeAndWait(() -> {
                DisplayModeModel.application().set(DisplayModeModel.Mode.ANALYST); // @After restores the mode
                shown[0] = new CharacterSheet(new SheetContext(new TomatoData(), journal, () -> defs, DisplayModeModel.application(), () -> 5000, plans));
                open(shown[0], ACCOUNT + ":1", "overview");
                frame[0] = new JFrame();
                frame[0].setContentPane(shown[0]);
                frame[0].setSize(900, 600);
                frame[0].setVisible(true);
            });
            CharacterSheet sheet = shown[0];
            try {
                JComponent[] parts = new JComponent[4]; // goals, its tab, the sheet page, Manage goals
                SwingUtilities.invokeAndWait(() -> {
                    JTabbedPane strip = named(sheet, "character-tabs", JTabbedPane.class);
                    parts[0] = named(sheet, "character-goals", JComponent.class);
                    Component tab = parts[0];
                    while (tab.getParent() != strip) tab = tab.getParent();
                    parts[1] = (JComponent) tab;
                    parts[2] = named(sheet, "character-sheet-scroll", JScrollPane.class);
                    parts[3] = (JComponent) SwingUtilities.getAncestorOfClass(tomato.gui.character.CharacterPlanningPanel.class,
                        named(sheet, "planning-0", JComboBox.class));
                    assertSame("Only the sheet page scrolls the Goals tab", parts[2], SwingUtilities.getAncestorOfClass(JScrollPane.class, parts[0]));
                    assertEquals("Another tab shows: Manage goals' minimum only (Task 9's floor), as before",
                        parts[3].getMinimumSize().height, parts[1].getMinimumSize().height);
                    sheet.tabs().select("goals");
                    assertEquals("goals", sheet.selectedTab());
                });
                await(() -> parts[0].getHeight() > 0 && parts[1].getMinimumSize().height >= parts[0].getPreferredSize().height
                    && parts[0].getHeight() >= parts[0].getPreferredSize().height);
                SwingUtilities.invokeAndWait(() -> {
                    assertTrue("Goals shows: its whole content is laid out and taller than Manage goals' floor",
                        parts[1].getMinimumSize().height > parts[3].getMinimumSize().height);
                    assertTrue("…so the sheet page scrolls it", ((JScrollPane) parts[2]).getVerticalScrollBar().isVisible());
                    sheet.tabs().select("overview");
                    assertEquals("Back on another tab: the floor again", parts[3].getMinimumSize().height, parts[1].getMinimumSize().height);
                });
            } finally {
                SwingUtilities.invokeAndWait(frame[0]::dispose);
            }
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

    @Test public void selectingARememberedDeathTabWhileTheRecordLoadsLandsOnDeathOnceItApplies() throws Exception {
        try (CharacterJournal journal = journal("select-restore.json", 1, 2)) {
            journal.markDead(ACCOUNT + ":1", true);
            journal.markDead(ACCOUNT + ":2", true);
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                // Mirrors CharacterRosterView.showSheet's implicit-tab restore: open(key, null) then selectTab(remembered).
                sheet.open(ACCOUNT + ":1", null);
                sheet.selectTab("death");
                assertFalse("Death is not offered until the record loads: the restore request cannot reach it yet",
                    sheet.tabs().visibleIds().contains("death"));
                await(sheet::ready);
                assertEquals("The restore request is retried, select-only, once the record applies", "death", sheet.selectedTab());

                sheet.open(ACCOUNT + ":2", null);
                sheet.selectTab("death");
                await(sheet::ready);
                assertEquals("Landing on Death this way also works for a second, freshly opened character", "death", sheet.selectedTab());
            });
        }
    }

    @Test public void selectingAHiddenTabNeverShowsIt() throws Exception {
        try (CharacterJournal journal = journal("select-hidden.json", 1)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                open(sheet, ACCOUNT + ":1", null);
                assertTrue(sheet.tabs().hide("goals"));
                assertTrue(sheet.tabs().hiddenIds().contains("goals"));
                sheet.selectTab("goals"); // the restore-only path must never show() a hidden tab
                assertTrue("selectTab never un-hides a tab", sheet.tabs().hiddenIds().contains("goals"));
                assertNotEquals("goals", sheet.selectedTab());
            });
        }
    }

    @Test public void aUsersTabChoiceMadeWhileLoadingSurvivesThePendingTabsOneRetry() throws Exception {
        try (CharacterJournal journal = journal("user-choice-loading.json", 1)) {
            journal.markDead(ACCOUNT + ":1", true);
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                sheet.open(ACCOUNT + ":1", "death"); // explicit; not yet reachable while the record loads
                assertFalse(sheet.tabs().visibleIds().contains("death"));
                sheet.tabs().select("notes"); // the user's own pick, made while the sheet is still loading
                assertEquals("notes", sheet.selectedTab());
                await(sheet::ready); // the record loads and Death becomes available
                assertEquals("The user's choice, made before the pending request's one retry, is not overridden",
                    "notes", sheet.selectedTab());
            });
        }
    }

    @Test public void aUsersTabChoiceAfterLoadIsNotOverriddenByALaterRefresh() throws Exception {
        try (CharacterJournal journal = journal("user-choice-refresh.json", 1)) {
            // The character starts alive: Death is never offered on the sheet's first read, so the explicit request is spent.
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = sheet(journal);
                sheet.open(ACCOUNT + ":1", "death");
                await(sheet::ready);
                assertFalse("Death was never offered on the first read", sheet.tabs().visibleIds().contains("death"));
                assertEquals("overview", sheet.selectedTab());

                sheet.tabs().select("notes"); // the user's own choice, made after the sheet settled
                assertEquals("notes", sheet.selectedTab());

                journal.markDead(ACCOUNT + ":1", true); // Death becomes available now
                sheet.refresh(); // forces another rebuild of the SAME key
                await(() -> sheet.tabs().visibleIds().contains("death"));
                assertEquals("The abandoned request must not pull the user back off their own later choice",
                    "notes", sheet.selectedTab());
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

    /**
     * Replaces the P3a late-result test, which could not fail: the shared worker and invokeLater are both FIFO, so an earlier
     * build always reached the EDT first. Here a manual executor holds the builds and runs them newest first, so the older
     * results really do arrive last. Only the newest request's result for the key the sheet still shows may apply: the older
     * request for the same key and the one for another character are both dropped. While the builds are held the sheet says
     * "Loading…", shows nothing of the previous character, and nothing acts (the P3a test's loading checks, kept).
     */
    @Test public void onlyTheNewestBuildForTheShownKeyApplies() throws Exception {
        try (CharacterJournal journal = journal("newest.json", 1, 2)) {
            long[] clock = {5_000};
            RosterDefinitions defs = RosterDefinitions.empty(); // one stable instance: the token moves only when this test moves it
            TomatoData data = new TomatoData();
            // #1 is in game, so its model carries its request's time (Identity.lastSeen is the build's "now" while playing).
            data.liveCharacter.publish(SheetFixtures.live(ACCOUNT, 1, "Sharkbait", null));
            ManualExecutor worker = new ManualExecutor();
            CharacterSheet[] shown = new CharacterSheet[1];
            SwingUtilities.invokeAndWait(() -> {
                shown[0] = new CharacterSheet(new SheetContext(data, journal, () -> defs, DisplayModeModel.application(), () -> clock[0], PlanningStore.shared()), worker);
                shown[0].open(ACCOUNT + ":1", null);
            });
            CharacterSheet sheet = shown[0];
            assertEquals(1, worker.size());
            worker.run(0); // on this thread, off the EDT, as the "character-sheet" thread would
            await(sheet::ready);
            SwingUtilities.invokeAndWait(() -> {
                Banner status = named(sheet, "character-sheet-status", Banner.class);
                assertFalse(status.isVisible());
                assertEquals(5_000, sheet.model().identity().lastSeen());
                sheet.open(ACCOUNT + ":2", null); // build A: another character
                assertTrue("Loading shows until the new character's read applies", status.isVisible());
                assertEquals("Loading…", status.text()); assertFalse(status.warns());
                assertFalse(sheet.ready());
                assertNull(sheet.model());
                assertEquals("Nothing of the previous character stays", "", named(sheet, "character-sheet-name", JLabel.class).getText());
                assertFalse("Nothing acts while loading", named(sheet, "character-sheet-death", AbstractButton.class).isEnabled());
                clock[0] = 6_000; sheet.open(ACCOUNT + ":1", null); // build B: back to #1
                clock[0] = 7_000; sheet.open(ACCOUNT + ":1", null); // build C: #1 again, the newest request
            });
            assertEquals(3, worker.size());
            worker.run(2); worker.run(1); worker.run(0); // C, then the older B, then A for #2: each posts its result in this order
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(sheet.ready());
                assertEquals(ACCOUNT + ":1", sheet.key());
                assertTrue("A late result for #2 never replaces #1", named(sheet, "character-sheet-name", JLabel.class).getText().endsWith("#1"));
                assertEquals("The older request for the same key never replaces the newest one's result", 7_000, sheet.model().identity().lastSeen());
                assertFalse(named(sheet, "character-sheet-status", Banner.class).isVisible());
                assertTrue(named(sheet, "character-sheet-death", AbstractButton.class).isEnabled());
            });
        }
    }

    /**
     * An Error (not only a RuntimeException) in a build is caught on the build thread, logged once with its stack trace and
     * reported in the sheet; nothing is rethrown on the EDT, and the next refresh retries and applies a model.
     */
    @Test public void anErrorInABuildIsReportedAndRetried() throws Exception {
        RosterDefinitions none = RosterDefinitions.empty();
        AtomicBoolean thrown = new AtomicBoolean();
        List<String> logged = Collections.synchronizedList(new ArrayList<>());
        Consumer<String> previousLog = SheetPresenter.errorLog;
        SheetPresenter.errorLog = logged::add;
        try (CharacterJournal journal = journal("error.json", 1)) {
            SheetContext failing = new SheetContext(new TomatoData(), journal, () -> {
                // Once, and only on the build thread: the EDT reads the definitions for the presenter's token too.
                if ("character-sheet".equals(Thread.currentThread().getName()) && thrown.compareAndSet(false, true)) throw new SyntheticBuildError();
                return none;
            }, DisplayModeModel.application(), () -> 5000, PlanningStore.shared());
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = new CharacterSheet(failing);
                sheet.open(ACCOUNT + ":1", null);
                Banner status = named(sheet, "character-sheet-status", Banner.class);
                await(status::warns);
                assertTrue(status.isVisible()); assertTrue(status.text(), status.text().contains("Synthetic build error"));
                assertFalse(sheet.ready()); assertFalse(named(sheet, "character-sheet-death", AbstractButton.class).isEnabled());
                assertEquals("Logged once", 1, logged.size());
                assertTrue(logged.get(0), logged.get(0).contains(SyntheticBuildError.class.getName() + ": Synthetic build error"));
                assertTrue("…with its stack trace: " + logged.get(0), logged.get(0).contains("at " + CharacterSheetTest.class.getName()));
                sheet.refresh(); // the failure cleared the presenter's token: the next refresh retries
                await(sheet::ready);
                assertFalse("A model applied: the banner is gone", status.isVisible());
                assertTrue(named(sheet, "character-sheet-name", JLabel.class).getText().endsWith("#1"));
                assertTrue(named(sheet, "character-sheet-death", AbstractButton.class).isEnabled());
                assertEquals("A successful build logs nothing", 1, logged.size());
            });
        } finally { SheetPresenter.errorLog = previousLog; }
    }

    /** A build failure that is not a RuntimeException. */
    private static final class SyntheticBuildError extends Error { SyntheticBuildError() { super("Synthetic build error"); } }

    /** Holds queued builds until the test runs them, one at a time and in any order (the shared worker runs them FIFO). */
    private static final class ManualExecutor implements Executor {
        private final List<Runnable> queued = new ArrayList<>();
        @Override public synchronized void execute(Runnable task) { queued.add(task); }
        synchronized int size() { return queued.size(); }
        /** Runs (and removes) the queued build at {@code index} on the calling thread. */
        void run(int index) { Runnable task; synchronized (this) { task = queued.remove(index); } task.run(); }
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
