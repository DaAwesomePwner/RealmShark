package tomato.gui.chat;

import java.awt.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.data.QuestData;
import packets.incoming.QuestFetchResponsePacket;
import tomato.Tomato;
import tomato.backend.TomatoPacketCapture;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.ViewStateStore;
import tomato.gui.modern.WorkspaceShell;
import tomato.history.*;
import tomato.history.archive.*;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;
import static tomato.gui.activity.ActivityArchiveUiTest.edt;

/** Actual shell wiring with synthetic history; no frame, focus, remote rules, or capture. */
public class ShellHookIntegrationTest {
    @Test public void settingsHostsNotificationsAndAppearanceAndAssetReloadsClearSprites() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.settings.SettingsPage settings = find(shell, tomato.gui.settings.SettingsPage.class);
            assertNotNull("Settings is shell page 13", settings);
            tomato.gui.notifications.NotificationsGUI notifications = find(settings, tomato.gui.notifications.NotificationsGUI.class);
            assertNotNull("Notifications keeps its page, inside Settings", notifications);
            assertNotNull(named(settings, "settings-appearance", JComponent.class));
            settings.showSection(tomato.gui.settings.SettingsPage.APPEARANCE);
            shell.select(0);
            TomatoGUI.openNotifications(tomato.gui.notifications.NotificationsGUI.KEY_POPS);
            assertEquals(13, shell.getSelectedPage());
            assertEquals(tomato.gui.settings.SettingsPage.NOTIFICATIONS, settings.currentSection());
            JTabbedPane tabs = find(notifications, JTabbedPane.class);
            assertEquals(tomato.gui.notifications.NotificationsGUI.KEY_POPS, tabs.getTitleAt(tabs.getSelectedIndex()));
            settings.showSection(tomato.gui.settings.SettingsPage.APPEARANCE);
            shell.select(0);
            assertTrue(tomato.gui.route.Navigator.current().open(tomato.gui.route.Route.to(tomato.gui.route.Destination.NOTIFICATIONS)));
            assertEquals(13, shell.getSelectedPage());
            assertEquals("The Notifications route shows its section", tomato.gui.settings.SettingsPage.NOTIFICATIONS, settings.currentSection());
            assertTrue(tomato.gui.route.Navigator.current().back());
            assertEquals(0, shell.getSelectedPage());
            tomato.gui.search.ActionRegistry registry = tomato.gui.search.ActionRegistry.application();
            assertTrue(registry.search("appearance.settings").get(0).open());
            assertEquals(13, shell.getSelectedPage());
            assertEquals(tomato.gui.settings.SettingsPage.APPEARANCE, settings.currentSection());
            assertTrue(tomato.gui.route.Navigator.current().open(tomato.gui.route.Route.to(tomato.gui.route.Destination.NOTIFICATIONS)));
            assertEquals(tomato.gui.settings.SettingsPage.NOTIFICATIONS, settings.currentSection());
            assertTrue(tomato.gui.route.Navigator.current().back());
            assertEquals("Back restores the Settings section as well as notification state",
                    tomato.gui.settings.SettingsPage.APPEARANCE, settings.currentSection());
            assertNotNull("Settings hosts General", named(settings, "settings-general", JComponent.class));
            assertNotNull(named(settings, "settings-combat-full-detail", JCheckBox.class));
            shell.select(0);
            assertEquals(1, registry.search("combat.settings").size());
            assertEquals("Combat history is found by its words", "combat.settings", registry.search("full detail retention").get(0).id);
            assertTrue(registry.search("combat.settings").get(0).open());
            assertEquals(13, shell.getSelectedPage());
            assertEquals("The Combat history entry opens Settings › General",
                    tomato.gui.settings.SettingsPage.GENERAL, settings.currentSection());
            Icon before = tomato.gui.kit.Sprites.sprite(987_654_321, 24);
            TomatoGUI.assetsReloaded();
            assertNotSame("Asset reloads drop cached sprites", before, tomato.gui.kit.Sprites.sprite(987_654_321, 24));
        });
    }
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    /** Each shell opens Runs on its default Cards view; routes and Browse saved history may remember the Table view, restored after. */
    @Rule public final tomato.gui.runs.RunsViewRule runsView = tomato.gui.runs.RunsViewRule.cards();
    private final Map<Field,Object> original = new LinkedHashMap<>();
    private WorkspaceShell shell;
    private TomatoGUI gui;
    private SessionStore store;
    private TomatoData data;
    private static final String TABS = "ui.tabs.character";
    private String filters, ignoredVisibility, temporaryDirectory, sheetTabs;
    private final Map<String,String> archivePreferences = new LinkedHashMap<>();
    /** The Quests page's saved tabs and Board choices: cleared so each shell opens on the Board's default Cards view, then restored. */
    private static final String[] QUEST_PREFERENCES = {"ui.tabs.quests", "ui.quests.view", "ui.quests.group", "ui.quests.pinned-first"};
    private final Map<String,String> questPreferences = new LinkedHashMap<>();
    /** The Runs & DPS tabs and the Live meter's nested tabs: cleared so each shell opens on the Feed with every tab shown, then restored. */
    private static final String[] TAB_PREFERENCES = {"ui.tabs.runs", "ui.tabs.dps"};
    private final Map<String,String> tabPreferences = new LinkedHashMap<>();
    /** The run recap's section choices (ui.collapse.run-recap-*): cleared so each recap opens with its defaults, then restored. */
    private static final String[] RECAP_PREFERENCES = {"damage", "loot", "players", "resources", "timeline", "evidence"};
    private final Map<String,String> recapPreferences = new LinkedHashMap<>();
    private static final String[] MODULES = {"chat", "keypops", "inspect", "statistics", "loot", "runs", "timeline"};

    @Before public void open() throws Exception {
        filters = PropertiesManager.getProperty("chat.filters");
        ignoredVisibility = PropertiesManager.getProperty("chat.showIgnoredPlayers");
        sheetTabs = PropertiesManager.getProperty(TABS);
        PropertiesManager.setProperties("chat.filters", "{}");
        PropertiesManager.setProperties("chat.showIgnoredPlayers", "false");
        PropertiesManager.setProperties(TABS, ""); // explicit navigation (charactersRoutesOpenTheListOrOneSheetAndBackRestoresEach) may show() a tab
        for (String key : archiveKeys()) {
            archivePreferences.put(key, PropertiesManager.getProperty(key));
            PropertiesManager.setProperties(key, "");
        }
        for (String key : QUEST_PREFERENCES) {
            questPreferences.put(key, PropertiesManager.getProperty(key));
            PropertiesManager.setProperties(key, "");
        }
        for (String key : TAB_PREFERENCES) {
            tabPreferences.put(key, PropertiesManager.getProperty(key));
            PropertiesManager.setProperties(key, "");
        }
        for (String id : RECAP_PREFERENCES) {
            String key = tomato.gui.kit.Collapsible.PREFIX + "run-recap-" + id;
            recapPreferences.put(key, PropertiesManager.getProperty(key));
            PropertiesManager.setProperties(key, "");
        }
        temporaryDirectory = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", temp.newFolder("scratch").getAbsolutePath());
        store = new SessionStore(temp.newFolder().toPath(), true, "synthetic");
        remember(AppHistory.class, "store", store); remember(Tomato.class, "preview", true);
        for (Class<?> type : new Class<?>[]{TomatoGUI.class, ChatGUI.class})
            for (Field field : type.getDeclaredFields()) if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                field.setAccessible(true); original.put(field, field.get(null));
            }
        data = new TomatoData();
        // Build now follows the journal (live or most recent character): an empty temporary journal keeps Build on page 6 here.
        tomato.gui.glance.character.SheetFixtures.inject(data, new tomato.backend.data.CharacterJournal(temp.newFolder().toPath().resolve("journal.json")));
        SwingUtilities.invokeAndWait(this::buildShell);
    }
    private void buildShell() { gui = new TomatoGUI(data); shell = (WorkspaceShell)gui.createWorkspace(); }
    private static Set<String> archiveKeys() throws Exception {
        Field field = PropertiesManager.class.getDeclaredField("properties"); field.setAccessible(true);
        Set<String> keys = new HashSet<>(((Properties)field.get(null)).stringPropertyNames());
        keys.removeIf(key -> !key.startsWith("ux.archive.")); return keys;
    }
    private void remember(Class<?> type, String name, Object next) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); original.put(field, field.get(null)); field.set(null, next);
    }
    @After public void close() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (gui != null) gui.closeWorkspace();
            if (shell != null) shell.removeNotify();
            try { for (Map.Entry<Field,Object> entry : original.entrySet()) entry.getKey().set(null, entry.getValue()); }
            catch (IllegalAccessException e) { throw new AssertionError(e); }
            PropertiesManager.setProperties("chat.filters", filters == null ? "{}" : filters);
            PropertiesManager.setProperties("chat.showIgnoredPlayers", ignoredVisibility == null ? "false" : ignoredVisibility);
            PropertiesManager.setProperties(TABS, sheetTabs == null ? "" : sheetTabs);
        });
        for (String key : archiveKeys()) PropertiesManager.setProperties(key, archivePreferences.getOrDefault(key, ""));
        for (String key : QUEST_PREFERENCES) { String saved = questPreferences.get(key); PropertiesManager.setProperties(key, saved == null ? "" : saved); }
        for (String key : TAB_PREFERENCES) { String saved = tabPreferences.get(key); PropertiesManager.setProperties(key, saved == null ? "" : saved); }
        for (Map.Entry<String,String> saved : recapPreferences.entrySet()) PropertiesManager.setProperties(saved.getKey(), saved.getValue() == null ? "" : saved.getValue());
        PropertiesManager.flush().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (temporaryDirectory != null) System.setProperty("java.io.tmpdir", temporaryDirectory);
        if (store != null) store.close();
    }

    @Test public void shellQuestsReceiveScopedPacketsAndRejectTheLegacyUnscopedBypass() throws Exception {
        data.progression().reset("synthetic-account", "fixture identified");
        QuestData quest = new QuestData(); quest.id = "scoped"; quest.name = "Scoped fixture quest"; quest.description = "Synthetic";
        quest.requirements = new int[0]; quest.rewards = new int[0];
        QuestFetchResponsePacket packet = new QuestFetchResponsePacket(); packet.quests = new QuestData[]{quest};
        new TomatoPacketCapture(data).packetCapture(packet);
        SwingUtilities.invokeAndWait(() -> {
            JTable table = named(shell, "quest-table", JTable.class);
            assertEquals(1, table.getRowCount()); assertEquals("Scoped fixture quest", table.getValueAt(0, 1));
            TomatoGUI.updateQuests(new QuestData[0]);
        });
        SwingUtilities.invokeAndWait(() -> assertEquals(1, named(shell, "quest-table", JTable.class).getRowCount()));
        data.captureStopped();
        SwingUtilities.invokeAndWait(() -> assertTrue(named(shell, "quest-capture-context", JTextArea.class).getText().contains("Stale / unverified")));
    }
    @Test public void planningSearchUsesRealRoutesAndDiscoveryDoesNotToggleCapture() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.search.ActionRegistry registry = tomato.gui.search.ActionRegistry.application();
            assertFalse(registry.search("font").isEmpty());
            assertFalse(registry.search("history location").isEmpty());
            assertFalse(registry.search("item alert").isEmpty());
            boolean capture = Tomato.isCaptureRunning();
            shell.select(0);
            assertEquals(1, registry.search("plans.characters").size());
            assertEquals(0, shell.getSelectedPage());
            assertTrue(registry.search("plans.characters").get(0).open());
            assertEquals(3, shell.getSelectedPage());
            assertTrue(tomato.gui.route.Navigator.current().back()); assertEquals(0, shell.getSelectedPage());
            assertTrue(registry.search("plans.quests").get(0).open()); assertEquals(5, shell.getSelectedPage());
            JTabbedPane quests = named(shell, "quests-tabs", JTabbedPane.class);
            assertEquals("The search opens the Planner", "Planner", quests.getTitleAt(quests.getSelectedIndex()));
            assertTrue(tomato.gui.route.Navigator.current().back()); assertEquals(0, shell.getSelectedPage());
            // The search opens the Planner through the navigator, so its tab switch is part of the Back entry: from the Board on the
            // Quests page, Back returns to the Board.
            shell.select(5); quests.setSelectedIndex(quests.indexOfTab("Board"));
            assertTrue(registry.search("plans.quests").get(0).open());
            assertEquals(5, shell.getSelectedPage()); assertEquals("Planner", quests.getTitleAt(quests.getSelectedIndex()));
            assertTrue(tomato.gui.route.Navigator.current().back());
            assertEquals(5, shell.getSelectedPage());
            assertEquals("Back returns to the Board the search left", "Board", quests.getTitleAt(quests.getSelectedIndex()));
            assertEquals(capture, Tomato.isCaptureRunning());
        });
    }

    @Test public void charactersRoutesOpenTheListOrOneSheetAndBackRestoresEach() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();
            tomato.gui.character.CharacterRosterView roster = find(shell, tomato.gui.character.CharacterRosterView.class);
            assertNotNull("The Characters Roster tab hosts the list and the sheet", roster);
            shell.select(0);
            String unknown = "0".repeat(64) + ":404";
            assertTrue(navigator.open(tomato.gui.route.Route.to(tomato.gui.route.Destination.CHARACTER_SHEET)
                .withPayload(new tomato.gui.glance.character.SheetFocus(unknown, null))));
            assertEquals(3, shell.getSelectedPage());
            assertTrue(roster.showingSheet());
            tomato.gui.activity.SnapshotTestSupport.await(roster.sheet()::ready); // at once here; from Task 5 the sheet reads off the EDT
            assertTrue("An unknown key shows the unavailable state",
                named(roster, "character-sheet-unavailable", tomato.gui.kit.EmptyState.class).isVisible());
            assertTrue(navigator.open(tomato.gui.route.Route.to(tomato.gui.route.Destination.CHARACTERS)));
            assertFalse("A plain Characters route shows the list", roster.showingSheet());
            assertTrue(navigator.back());
            assertTrue("Back restores the sheet", roster.showingSheet()); assertEquals(unknown, roster.sheet().key());
            assertTrue(navigator.back()); assertEquals(0, shell.getSelectedPage());
        });
    }

    @Test public void homeIsPageFourteenAndBuildOpensFromSearchUnderItsNewTitle() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();
            tomato.gui.glance.home.HomePage home = find(shell, tomato.gui.glance.home.HomePage.class);
            assertNotNull("Home is shell page 14", home);
            assertEquals("home-page", home.getName());
            assertNotNull("Home shows its cards", named(home, "home-hero", tomato.gui.kit.Card.class));
            shell.select(0);
            assertTrue(navigator.open(tomato.gui.route.Route.to(tomato.gui.route.Destination.HOME)));
            assertEquals(14, shell.getSelectedPage());
            assertTrue(home.isVisible());
            assertTrue(navigator.back()); assertEquals(0, shell.getSelectedPage());
            tomato.gui.search.ActionRegistry registry = tomato.gui.search.ActionRegistry.application();
            assertEquals(1, registry.search("build.open").size());
            assertEquals("Build (weapon damage and recovery)", registry.search("build.open").get(0).label);
            assertTrue(registry.search("build.open").get(0).open());
            assertEquals(6, shell.getSelectedPage());
            assertEquals("Build", named(shell, "page-title", JLabel.class).getText());
            assertNotNull("With no character, page 6 says that Build moved", find(shell, tomato.gui.myinfo.BuildMovedPanel.class));
            assertFalse("Build never takes a sidebar row", named(shell, "nav-6", AbstractButton.class).isVisible());
            assertTrue(navigator.back()); assertEquals(0, shell.getSelectedPage());
        });
    }


    @Test public void homeCardsOpenTheirPagesThroughTheNavigatorAndBackReturnsHome() throws Exception {
        tomato.history.link.VisitRef[] opened = new tomato.history.link.VisitRef[1];
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.glance.home.HomePage home = find(shell, tomato.gui.glance.home.HomePage.class);
            assertNotNull("Home is shell page 14", home);
            tomato.gui.glance.home.HomeModel model = tomato.gui.glance.home.HomeModels.populated(System.currentTimeMillis());
            home.apply(model);
            tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();
            String[] cards = {"home-hero", "home-now", "home-quests"};
            int[] pages = {3, 10, 5}; // Characters, Runs & DPS (the Live meter moved there in P5b), Quests
            for (int i = 0; i < cards.length; i++) {
                shell.select(14);
                named(home, cards[i], tomato.gui.kit.Card.class).getActionMap().get("open-card").actionPerformed(null);
                assertEquals(cards[i] + " opens its page", pages[i], shell.getSelectedPage());
                if (cards[i].equals("home-now"))
                    assertEquals("The Now card opens the Live meter tab", tomato.gui.runs.RunsTab.LIVE_METER, runsDps().selectedTab());
                assertTrue(navigator.back());
                assertEquals(cards[i] + ": Back returns to Home", 14, shell.getSelectedPage());
            }
            named(home, "home-build", AbstractButton.class).doClick();
            assertEquals("The hero's Build action opens page 6", 6, shell.getSelectedPage());
            assertNotNull("With no character yet, that is the Build moved page", find(shell, tomato.gui.myinfo.BuildMovedPanel.class));
            assertTrue(navigator.back());
            assertEquals(14, shell.getSelectedPage());
            named(home, "home-run-0", JComponent.class).getActionMap().get("open-run").actionPerformed(null);
            assertEquals("A recent run opens Runs", 10, shell.getSelectedPage());
            // Home's Recent runs opens the run recap (P5a), no longer the Table view's row.
            tomato.gui.runs.RunsPage runs = named(shell, "runs-page", tomato.gui.runs.RunsPage.class);
            assertTrue("The exact run shows in the Runs page's recap", runs.recapShown());
            tomato.history.link.VisitRef visit = model.runs().rows().get(0).visit();
            assertEquals("The recap shows that exact visit, not a name or time match", visit, ((tomato.gui.runs.RunRecapView) runs.recap()).ref());
            opened[0] = visit;
            assertTrue(navigator.back());
            assertEquals(14, shell.getSelectedPage());
        });
        // The synthetic Home model's run is not in this saved history: the recap says so for that exact reference.
        tomato.gui.runs.RunRecapView recap = edt(() -> (tomato.gui.runs.RunRecapView) named(shell, "runs-page", tomato.gui.runs.RunsPage.class).recap());
        await(() -> recap.model() != null);
        SwingUtilities.invokeAndWait(() -> {
            assertFalse("Nothing is substituted", recap.model().available());
            assertTrue(recap.model().unavailable(), recap.model().unavailable().contains("session " + opened[0].sessionId + " · visit " + opened[0].visitId));
        });
    }

    /**
     * Spec S4, the damage breakdown of the last completed run in at most two clicks, over synthetic saved history: an earlier
     * session (HomeHistoryFixture) with two completed runs, the later one linked to a real combat summary whose local row is
     * verified (CombatFixtures.typical through CombatSummaries), then a run left, and this app run's session with a run in
     * progress. 0 clicks: Home's Recent runs lists them newest first with the linked run's DPS. 1 click: that row opens its recap
     * with the Damage section open and the meter's "(you)" row; Back returns Home and leaves the recap. From the sidebar: Runs
     * (click 1) shows the feed, and opening the first completed card (click 2: a double-click, or Enter/Space on the selected
     * card, as TileList opens tiles) shows the same recap; "‹ Runs" returns to the feed. Home's refresher and the feed read when
     * their page shows in a window; with none here, Home's sources are read off the EDT as the refresher reads them and the feed
     * is asked to read as showing it does. Clicks are counted as in the S2 and S3 methods.
     */
    @Test public void homeRecentRunAndFeedOpenTheDamageBreakdownOfTheLastCompletedRunForS4() throws Exception {
        long now = System.currentTimeMillis(), minute = 60_000L;
        Path root = store.directory();
        String past = tomato.gui.glance.home.HomeHistoryFixture.id("s4-earlier-session");
        tomato.history.link.VisitRef last = new tomato.history.link.VisitRef(past, "p2");
        tomato.gui.glance.home.HomeHistoryFixture.session(root, past, now - 180 * minute, now - 60 * minute);
        tomato.gui.glance.home.HomeHistoryFixture.runs(root, past,
            tomato.gui.glance.home.HomeHistoryFixture.visit("p1", "Pirate Cave", now - 170 * minute, now - 150 * minute, true),
            tomato.gui.glance.home.HomeHistoryFixture.visit("p2", "Lost Halls", now - 140 * minute, now - 110 * minute, true),
            tomato.gui.glance.home.HomeHistoryFixture.visit("p3", "Snake Pit", now - 100 * minute, now - 90 * minute, false));
        tomato.gui.dps.CombatSummaries.Result fight = tomato.gui.dps.CombatSummaries.build(
            tomato.history.encounter.CombatFixtures.typical(last, now - 135 * minute, 150, 8, 60));   // Player1 is the verified local row
        tomato.history.encounter.CombatFixtures.writeRecord(root, past, fight.record());
        tomato.history.encounter.CombatFixtures.writeDetail(root, past, fight.detail());
        packets.packetcapture.logger.ActivityJournal.Visit live = tomato.gui.glance.home.HomeHistoryFixture.visit("c1", "Ice Citadel", now - 10 * minute, 0, false);
        live.lastSeen = now - minute;
        store.put("runs", live.id, live); store.flush();   // this app run's checkpoint of its run in progress

        // 0 clicks: Home's Recent runs, read off the EDT as its refresher reads them.
        tomato.gui.glance.home.HomeModel.Runs recent = tomato.gui.glance.home.HomeModelBuilder.runs(
            new tomato.gui.glance.home.LiveHomeSources(data, () -> store).archive(tomato.gui.glance.home.HomeArchive.Window.TODAY, now), null);
        long[] clicked = new long[1];
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.glance.home.HomePage home = find(shell, tomato.gui.glance.home.HomePage.class);
            home.apply(tomato.gui.glance.home.HomeModels.populated(now).withRuns(recent));
            shell.select(14);
            java.util.List<String> outcomes = new ArrayList<>();
            for (tomato.gui.glance.home.HomeArchive.RecentRun run : recent.rows()) outcomes.add(run.outcome());
            assertEquals("Newest first: in progress, left, then the last completed run", "In progress", outcomes.get(0));
            assertTrue(outcomes.get(1), outcomes.get(1).startsWith("Left"));
            int row = outcomes.indexOf("Completed");
            assertEquals(2, row);
            assertEquals(last, recent.rows().get(row).visit());
            assertEquals("S4, 0 clicks: Home names the linked run", "Lost Halls", named(home, "home-run-2-map", JLabel.class).getText());
            assertTrue("…with your DPS from its saved recording", named(home, "home-run-2-dps", JLabel.class).getText().startsWith("DPS "));
            int clicks = 0;
            clicked[0] = System.nanoTime();
            named(home, "home-run-2", JComponent.class).getActionMap().get("open-run").actionPerformed(null); clicks++;
            assertEquals("S4 takes one click from Home", 1, clicks);
            assertEquals("The row opens Runs", 10, shell.getSelectedPage());
            tomato.gui.runs.RunsPage runs = named(shell, "runs-page", tomato.gui.runs.RunsPage.class);
            assertTrue("…on the run recap", runs.recapShown());
            assertEquals(last, ((tomato.gui.runs.RunRecapView) runs.recap()).ref());
        });
        tomato.gui.runs.RunRecapView recap = edt(() -> (tomato.gui.runs.RunRecapView) named(shell, "runs-page", tomato.gui.runs.RunsPage.class).recap());
        await(() -> !recap.loading() && recap.model() != null);
        long fromHome = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - clicked[0]);
        SwingUtilities.invokeAndWait(() -> {
            assertDamageBreakdown(recap, last);
            assertTrue(tomato.gui.route.Navigator.current().back());
            assertEquals("Back returns Home", 14, shell.getSelectedPage());
            assertFalse("…and the Runs page no longer shows the recap Back left", named(shell, "runs-page", tomato.gui.runs.RunsPage.class).recapShown());
        });

        // From the sidebar: Runs, then the first completed card.
        tomato.gui.runs.RunsPage runs = edt(() -> named(shell, "runs-page", tomato.gui.runs.RunsPage.class));
        int[] clicks = {0};
        SwingUtilities.invokeAndWait(() -> {
            named(shell, "nav-10", JToggleButton.class).doClick(); clicks[0]++;
            assertEquals(10, shell.getSelectedPage());
            assertFalse("Runs shows the feed", runs.recapShown());
            assertFalse("…on its cards", runs.feed().tableShown());
            runs.feed().refresh();   // what showing the Cards view in a window does
        });
        await(() -> firstCompletedCard(runs) != null);
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.kit.TileList<tomato.gui.runs.RunCardModel> list = firstCompletedCard(runs);
            int index = -1;
            for (int i = 0; i < list.getModel().getSize() && index < 0; i++)
                if (list.getModel().getElementAt(i).outcome() == tomato.gui.runs.RunOutcome.COMPLETED) index = i;
            assertEquals("The first completed card is the last completed run", last, list.getModel().getElementAt(index).ref());
            list.setSelectedIndex(index);
            list.getActionMap().get(tomato.gui.kit.TileList.OPEN).actionPerformed(null); clicks[0]++;
            assertEquals("S4 takes two clicks from the sidebar", 2, clicks[0]);
            assertTrue("The card opens the recap", runs.recapShown());
            assertEquals(last, recap.ref());
        });
        await(() -> !recap.loading() && recap.model() != null);
        SwingUtilities.invokeAndWait(() -> {
            assertDamageBreakdown(recap, last);
            named(recap, "run-recap-back", AbstractButton.class).doClick();
            assertFalse("‹ Runs returns to the feed", runs.recapShown());
            assertEquals(10, shell.getSelectedPage());
            assertFalse("…using the Back entry that led to the recap", tomato.gui.route.Navigator.current().canGoBack());
        });
        System.out.println("S4: Home 1 click, sidebar 2 clicks; the recap applied " + fromHome + " ms after the Home click");
    }

    /** The recap of {@code run} with its Damage section open, the meter showing, and the meter's verified local row "(you)". */
    private void assertDamageBreakdown(tomato.gui.runs.RunRecapView recap, tomato.history.link.VisitRef run) {
        assertTrue(recap.model().available());
        assertEquals(run, recap.model().ref());
        assertTrue("S4: the Damage section is open", named(recap, "run-recap-damage", tomato.gui.kit.Collapsible.class).expanded());
        JTable meter = named(recap, "run-recap-meter", JTable.class);
        assertTrue("…with the meter showing", shown(meter));
        java.util.List<String> players = new ArrayList<>();
        for (int r = 0; r < meter.getRowCount(); r++) players.add(String.valueOf(meter.getValueAt(r, 1)));
        assertEquals("All eight players of the recording", 8, players.size());
        assertTrue("…with your verified row marked: " + players, players.contains("Player1 (you)"));
        assertEquals("Player1", recap.model().damage().local().name());
    }

    /** The first day's card list, in the feed's order, that holds a completed run; null until the feed has read one. */
    @SuppressWarnings("unchecked")
    private static tomato.gui.kit.TileList<tomato.gui.runs.RunCardModel> firstCompletedCard(tomato.gui.runs.RunsPage runs) {
        for (tomato.gui.kit.TileList<?> list : all(runs.feed(), tomato.gui.kit.TileList.class)) {
            if (list.getName() == null || !list.getName().startsWith("run-feed-day-")) continue;
            for (Object card : list.items())
                if (((tomato.gui.runs.RunCardModel) card).outcome() == tomato.gui.runs.RunOutcome.COMPLETED)
                    return (tomato.gui.kit.TileList<tomato.gui.runs.RunCardModel>) list;
        }
        return null;
    }

    /**
     * Spec S2 and S5 from Home over a synthetic roster (CharacterFixtures). S2: Home names the stat that needs potions and how
     * many with no click, and the hero's sheet Overview repeats it after one. S5: the hero, then the Exalts tab (two clicks) show
     * every exalt tier and the distance to the next. The hero opens its own sheet at Overview; Back returns Home.
     */
    @Test public void homeHeroOpensItsSheetForS2AndS5AndBackReturnsHome() throws Exception {
        try (AutoCloseable definitions = tomato.gui.glance.character.CharacterFixtures.installDefinitions()) {
            tomato.backend.data.CharacterJournal journal = rebuildWithSyntheticRoster();
            try {
                SwingUtilities.invokeAndWait(() -> {
                    tomato.gui.glance.home.HomePage home = find(shell, tomato.gui.glance.home.HomePage.class);
                    home.apply(tomato.gui.glance.home.HomeModels.populated(System.currentTimeMillis()));
                    shell.select(14);
                    JLabel needs = named(home, "home-hero-needs", JLabel.class);
                    assertEquals("S2, 0 clicks: Home names the stat and the count", "Needs WIS 3 potions", needs.getText());
                    assertTrue(shown(needs));
                    named(home, "home-hero", tomato.gui.kit.Card.class).getActionMap().get("open-card").actionPerformed(null); // click 1
                    assertEquals("The hero opens the Characters page", 3, shell.getSelectedPage());
                    tomato.gui.glance.character.CharacterSheet sheet = find(shell, tomato.gui.glance.character.CharacterSheet.class);
                    assertEquals("…on its own character's sheet", tomato.gui.glance.home.HomeModels.KEY, sheet.key());
                    assertEquals("…at Overview", "overview", sheet.selectedTab());
                    assertTrue(shown(sheet));
                });
                // Built off the EDT; the needs row holds one label per stat that needs potions.
                await(() -> texts(named(shell, "character-overview-needs", JComponent.class)).stream().anyMatch(t -> t.contains("WIS needs 3")));
                SwingUtilities.invokeAndWait(() -> {
                    assertTrue("S2, 1 click: the Overview names the stat and the count", shown(named(shell, "character-overview-needs", JComponent.class)));
                    tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();
                    assertTrue(navigator.back());
                    assertEquals("Back returns Home", 14, shell.getSelectedPage());
                    tomato.gui.glance.home.HomePage home = find(shell, tomato.gui.glance.home.HomePage.class);
                    int clicks = 0;
                    named(home, "home-hero", tomato.gui.kit.Card.class).getActionMap().get("open-card").actionPerformed(null); clicks++;
                    JTabbedPane tabs = named(shell, "character-tabs", JTabbedPane.class);
                    tabs.setSelectedIndex(tabs.indexOfTab("Exalts")); clicks++;
                    assertEquals("S5 takes two clicks from Home", 2, clicks);
                    assertEquals("exalts", find(shell, tomato.gui.glance.character.CharacterSheet.class).selectedTab());
                });
                await(() -> exaltTiers() == 19); // tiers 5+4+3+2+1+0+0+4 of the fixture Wizard
                SwingUtilities.invokeAndWait(() -> {
                    Container exalts = (Container) named(shell, "character-tabs", JTabbedPane.class).getSelectedComponent();
                    assertTrue("S5: all eight stats' tiers show", all(exalts, tomato.gui.kit.PipMeter.class).size() >= 8);
                    assertTrue("S5: with the distance to the next tier", texts(exalts).stream().anyMatch(t -> t.contains("to next tier")));
                    assertTrue(tomato.gui.route.Navigator.current().back());
                    assertEquals(14, shell.getSelectedPage());
                });
            } finally {
                journal.close();
            }
        }
    }

    /**
     * Spec S3, pinned quests and what they reward, over synthetic quests with two pinned for the list's account. 0 clicks: Home's
     * Quests card, built from the live sources as Home's refresher builds it, lists both pinned quests with their reward ids. 1 click:
     * the card opens the Quests page on the Board, although the Planner was the tab last shown, with the pinned quests first and
     * their reward items in the painted cards (read from the applied card models and QuestCardRenderer.lines, not pixels). Back
     * returns Home. Reward ids no asset defines keep every card in one chest-tier group whatever assets the test JVM loaded. S3's
     * other half, which pinned quests expire today, is deferred with the expiry countdown phase (the server's expiration format is
     * unconfirmed, open item O1).
     */
    @Test public void homeQuestsCardAndBoardShowPinnedQuestsAndRewardsForS3AndBackReturnsHome() throws Exception {
        String account = tomato.backend.data.CharacterJournal.accountKey("s3-fixture");
        QuestData tribute = s3Quest("Royal tribute", 900_101, 900_102), haul = s3Quest("Mighty haul", 900_201);
        QuestData swap = s3Quest("Token swap", 900_301), exchange = s3Quest("Festival exchange", 900_401, 900_402, 900_403);
        java.util.prefs.Preferences pins = java.util.prefs.Preferences.userNodeForPackage(tomato.gui.quest.QuestGUI.class)
            .node("accounts").node(account);
        try {
            // Pinned before the list is published: the page reads its pins with each publication. The key is the page's own
            // (a UUID of the server id); QuestPinning.pinned, which Home reads, proves it.
            for (QuestData quest : new QuestData[]{haul, exchange})
                pins.putBoolean("pin." + UUID.nameUUIDFromBytes(quest.id.getBytes(java.nio.charset.StandardCharsets.UTF_8)), true);
            assertTrue(tomato.gui.quest.QuestPinning.pinned(account, haul) && tomato.gui.quest.QuestPinning.pinned(account, exchange));
            assertFalse(tomato.gui.quest.QuestPinning.pinned(account, tribute) || tomato.gui.quest.QuestPinning.pinned(account, swap));
            long now = System.currentTimeMillis();
            data.progression().reset(account, "fixture identified");
            assertTrue(data.progression().quests(data.progression().scope(), new QuestData[]{tribute, haul, swap, exchange}, now - 60_000L));
            // Off the EDT, as Home's refresher (which needs a window) reads it: the real sources and the real pins.
            tomato.gui.glance.home.HomeModel.Quests section = new tomato.gui.glance.home.LiveHomeSources(data, () -> store).quests(now);
            SwingUtilities.invokeAndWait(() -> {
                tomato.gui.glance.home.HomePage home = find(shell, tomato.gui.glance.home.HomePage.class);
                home.apply(tomato.gui.glance.home.HomeModels.populated(now).withQuests(section));
                shell.select(14);
                String[] pinned = {"Mighty haul", "Festival exchange"}; // open pinned quests in the list's order
                int[][] rewards = {{900_201}, {900_401, 900_402, 900_403}};
                for (int i = 0; i < pinned.length; i++) {
                    JLabel name = named(home, "home-quest-" + i + "-name", JLabel.class);
                    assertEquals("S3, 0 clicks: Home lists the pinned quest", pinned[i], name.getText());
                    assertTrue(shown(name));
                    for (int r = 0; r < rewards[i].length; r++) {
                        tomato.gui.kit.ItemSlot slot = named(home, "home-quest-" + i + "-reward-" + r, tomato.gui.kit.ItemSlot.class);
                        assertEquals("…with its rewards", rewards[i][r], slot.itemId());
                        assertEquals(tomato.gui.kit.ItemSlot.State.ITEM, slot.state());
                        assertTrue(shown(slot));
                    }
                }
                assertFalse("Only the pinned quests are listed", shown(named(home, "home-quest-2", JComponent.class)));
                // The Planner is the tab last shown on the Quests page; the Board must still come forward.
                JTabbedPane tabs = named(shell, "quests-tabs", JTabbedPane.class);
                tabs.setSelectedIndex(tabs.indexOfTab("Planner"));
                int clicks = 0;
                named(home, "home-quests", tomato.gui.kit.Card.class).getActionMap().get("open-card").actionPerformed(null); clicks++;
                assertEquals("S3 takes one click from Home", 1, clicks);
                assertEquals("The Quests card opens the Quests page", 5, shell.getSelectedPage());
                assertEquals("…on the Board, not the Planner last shown", "Board", tabs.getTitleAt(tabs.getSelectedIndex()));
                java.util.List<tomato.gui.quest.QuestsRouteTargetTest.ShownCard> cards =
                    tomato.gui.quest.QuestsRouteTargetTest.shownCards(find(shell, tomato.gui.quest.QuestGUI.class));
                assertEquals("The Board opens on its cards with every quest", 4, cards.size());
                assertEquals("S3, 1 click: the pinned quests come first (then the page's name order)",
                    java.util.List.of("Festival exchange", "Mighty haul", "Royal tribute", "Token swap"),
                    cards.stream().map(tomato.gui.quest.QuestsRouteTargetTest.ShownCard::name).collect(java.util.stream.Collectors.toList()));
                assertEquals(java.util.List.of(true, true, false, false),
                    cards.stream().map(tomato.gui.quest.QuestsRouteTargetTest.ShownCard::pinned).collect(java.util.stream.Collectors.toList()));
                assertEquals("…each card with its reward items", java.util.List.of(900_401, 900_402, 900_403), cards.get(0).rewards());
                assertEquals(java.util.List.of(900_201), cards.get(1).rewards());
                for (tomato.gui.quest.QuestsRouteTargetTest.ShownCard card : cards)
                    assertTrue(card.list() + " shows on the page", shown(named(shell, card.list(), tomato.gui.kit.TileList.class)));
                assertTrue(tomato.gui.route.Navigator.current().back());
                assertEquals("Back returns Home", 14, shell.getSelectedPage());
            });
        } finally {
            pins.removeNode(); // in-memory Preferences are shared by every test in the JVM
        }
    }
    /** A synthetic one-time quest (id = name) needing one Mark of Malus and rewarding {@code rewards}. */
    private static QuestData s3Quest(String name, int... rewards) {
        return tomato.gui.quest.QuestFixtures.data(name, 5, new int[]{tomato.gui.quest.QuestFixtures.MALUS}, rewards);
    }

    @Test public void aHeroWithoutAJournalKeyOpensTheCharactersList() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.glance.home.HomePage home = find(shell, tomato.gui.glance.home.HomePage.class);
            tomato.gui.glance.home.HomeModel model = tomato.gui.glance.home.HomeModels.populated(System.currentTimeMillis());
            home.apply(model.withHero(tomato.gui.glance.home.HomeModels.withKey(model.hero(), null)));
            shell.select(14);
            named(home, "home-hero", tomato.gui.kit.Card.class).getActionMap().get("open-card").actionPerformed(null);
            assertEquals(3, shell.getSelectedPage());
            assertFalse("The list shows, not a sheet", shown(find(shell, tomato.gui.glance.character.CharacterSheet.class)));
            assertTrue(tomato.gui.route.Navigator.current().back());
            assertEquals(14, shell.getSelectedPage());
        });
    }

    /** Rebuilds the workspace over a TomatoData whose journal is a temporary file holding the synthetic roster. */
    private tomato.backend.data.CharacterJournal rebuildWithSyntheticRoster() throws Exception {
        tomato.backend.data.CharacterJournal journal = tomato.gui.glance.character.CharacterFixtures.journal(
            temp.newFolder("roster").toPath().resolve("Characters").resolve("journal.json"), System.currentTimeMillis());
        SwingUtilities.invokeAndWait(() -> {
            gui.closeWorkspace(); shell.removeNotify();
            data = new TomatoData() { @Override public tomato.backend.data.CharacterJournal characterJournal() { return journal; } };
            buildShell();
        });
        return journal;
    }
    /** Visible up to the shell: there is no window here, so isShowing is false everywhere. */
    private boolean shown(Component component) {
        for (Component c = component; c != null; c = c.getParent()) { if (!c.isVisible()) return false; if (c == shell) return true; }
        return false;
    }
    /** The filled tiers of every pip meter on the sheet's selected tab (0 while it is still loading). */
    private int exaltTiers() {
        JTabbedPane tabs = named(shell, "character-tabs", JTabbedPane.class);
        if (tabs == null || !(tabs.getSelectedComponent() instanceof Container)) return 0;
        int sum = 0;
        for (tomato.gui.kit.PipMeter meter : all((Container) tabs.getSelectedComponent(), tomato.gui.kit.PipMeter.class)) sum += meter.filled();
        return sum;
    }
    private static <T> java.util.List<T> all(Container root, Class<T> type) {
        java.util.List<T> found = new ArrayList<>();
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) found.add(type.cast(c));
            if (c instanceof Container) found.addAll(all((Container) c, type));
        }
        return found;
    }
    private static java.util.List<String> texts(Container root) {
        java.util.List<String> found = new ArrayList<>();
        for (Component c : all(root, Component.class)) { String t = text(c); if (!t.isEmpty()) found.add(t); }
        return found;
    }
    private static String text(Component component) {
        if (component instanceof JLabel) return Objects.toString(((JLabel) component).getText(), "");
        if (component instanceof javax.swing.text.JTextComponent) return ((javax.swing.text.JTextComponent) component).getText();
        return "";
    }

    @Test public void shellQueriedHistorySharesTheLiveChatPolicy() throws Exception {
        ChatMessage message = new ChatMessage(LocalDateTime.of(2026, 9, 1, 12, 0), ChatMessage.Channel.WORLD,
            "IntegrationAnn", "", "IntegrationAnn", "synthetic conversation", "");
        store.append("chat", message); store.flush();
        ArchiveWorkspace<?,?,?> panel = workspace("chat");
        // The app now opens on the first core page; saved-history bindings only act while their page is shown.
        SwingUtilities.invokeAndWait(() -> { shell.select(0); panel.selectSession(SessionStore.ALL); });
        await(() -> !panel.loading() && named(panel, "chat-archive-messages", JTable.class) != null);
        Field field = ChatGUI.class.getDeclaredField("filters"); field.setAccessible(true);
        ChatFilters livePolicy = (ChatFilters)field.get(find(panel, ChatGUI.class));
        SwingUtilities.invokeAndWait(() -> {
            JTable table = named(panel, "chat-archive-messages", JTable.class);
            assertEquals(1, table.getRowCount()); table.setRowSelectionInterval(0, 0);
            button(panel, "Toggle local sender ignore").doClick();
            assertTrue(livePolicy.ignoresPlayer("IntegrationAnn"));
        });
        await(() -> !panel.loading() && panel.displayedPage().matches == 0);
        assertEquals(1, store.read(store.currentId(), "chat", ChatMessage.class).size());
    }

    @Test public void shellRegistersQueriedFactoriesWithFreshLiveDefaultsAndLibraryInEveryWrapper() throws Exception {
        int windows = Window.getWindows().length;
        SwingUtilities.invokeAndWait(() -> {
            Set<Component> wrappers = Collections.newSetFromMap(new IdentityHashMap<>());
            for (String module : MODULES) {
                ArchiveWorkspace<?,?,?> workspace = workspace(module);
                assertTrue(wrappers.add(workspace));
                assertFalse(module, workspace.state().archive);
                assertEquals(module, ArchiveQuery.CURRENT, workspace.state().query.scope());
                assertNull(module, workspace.displayedPage());
                JMenuItem library = tomato.gui.history.ArchiveNativeSupport.action(workspace, "History library…");
                assertNotNull(module, library); assertTrue(library.isEnabled());
                assertTrue(library.isVisible()); assertEquals(1, library.getActionListeners().length);
            }
            assertNotNull(find(workspace("chat"), ChatGUI.class));
            assertNotNull(find(workspace("keypops"), tomato.gui.keypop.KeypopGUI.class));
            assertNotNull(find(workspace("inspect"), tomato.gui.security.SecurityGUI.class));
            assertNotNull(find(workspace("statistics"), tomato.gui.stats.StatisticsGUI.class));
            assertNotNull(find(workspace("loot"), tomato.gui.stats.LootDashboard.class));
            assertNotNull(find(workspace("runs"), tomato.gui.activity.ActivityPanel.class));
            tomato.gui.runs.RunsPage runs = named(shell, "runs-page", tomato.gui.runs.RunsPage.class);
            assertSame("Page 10 keeps the archive workspace as its Table view", workspace("runs"), runs.workspace());
            assertFalse("Runs opens on the saved-run cards", runs.feed().tableShown());
            assertNotNull(find(workspace("timeline"), tomato.gui.activity.ActivityPanel.class));
        });
        assertFalse(Tomato.isCaptureRunning()); assertEquals(windows, Window.getWindows().length);
    }

    @Test public void sidebarAndRecreatedShellPreserveIndependentScopesAndNamedPastViews() throws Exception {
        String past;
        try (SessionStore old = new SessionStore(store.directory(), true, "synthetic-past")) {
            past = old.currentId(); old.append("chat", message("Past conversation")); old.flush();
        }
        ArchiveWorkspace<ChatArchiveClient.Row,ChatArchiveClient.Facets,ChatArchiveClient.Sort> chat = chatWorkspace();
        SwingUtilities.invokeAndWait(() -> {
            chat.changeQuery(ChatArchiveClient.query().withScope(past).withText("Past conversation"));
            workspace("keypops").selectSession(SessionStore.ALL);
            workspace("loot").showSaved();
        });
        await(() -> !chat.loading() && chat.displayedPage() != null);
        edt(() -> chat.saveNamed("Past review")).toCompletableFuture().get(5, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> {
            for (int index : new int[]{1, 8, 10, 0}) named(shell, "nav-" + index, JToggleButton.class).doClick();
            assertEquals(past, chat.state().query.scope()); assertTrue(chat.state().archive);
            assertEquals(SessionStore.ALL, workspace("keypops").state().query.scope());
            assertTrue(workspace("loot").state().archive); assertFalse(workspace("statistics").state().archive);
            assertFalse(workspace("runs").state().archive);
            gui.closeWorkspace(); shell.removeNotify();
        });
        PropertiesManager.flush().toCompletableFuture().get(5, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(this::buildShell);
        ArchiveWorkspace<?,?,?> restored = workspace("chat");
        await(() -> !restored.loading() && restored.displayedPage() != null);
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(1, restored.displayedPage().matches); assertEquals(past, restored.state().query.scope());
            assertEquals("Past conversation", restored.state().query.text());
            assertEquals(SessionStore.ALL, workspace("keypops").state().query.scope());
            assertTrue(workspace("loot").state().archive); assertFalse(workspace("statistics").state().archive);
            restored.selectSession(ArchiveQuery.CURRENT); assertFalse(restored.state().archive);
            restored.loadNamed("Past review");
            assertEquals(past, restored.state().query.scope()); assertTrue(restored.state().archive);
            assertEquals(Collections.singletonList("Past review"), ViewStateStore.application().names("chat"));
            TomatoGUI.browseSavedHistory();
            assertEquals(10, shell.getSelectedPage()); assertEquals(SessionStore.ALL, workspace("runs").state().query.scope());
            assertTrue("Browse saved history shows the Table view", named(shell, "runs-page", tomato.gui.runs.RunsPage.class).feed().tableShown());
            assertEquals(past, restored.state().query.scope());
        });
    }

    @Test public void shellDisposalReleasesAllReadersButKeepsHistoryOpenForFinalCollectorCheckpoint() throws Exception {
        store.append("chat", message("Before close")); store.flush();
        // Exercise a nested Resources query even before the separately owned DpsGUI registration lands.
        Path nestedScratch = temp.newFolder("nested-scratch").toPath();
        ArchiveWorkspace<?,?,?> nested = edt(() -> {
            ArchiveWorkspace<?,?,?> resources = tomato.gui.activity.ActivityPanel.workspace(store, new JLabel("Live resources"),
                tomato.gui.activity.ActivityPanel.Mode.COMBAT, nestedScratch, ViewStateStore.application());
            JPanel container = new JPanel(new BorderLayout()); container.add(resources);
            named(shell, "dps-tabs", JTabbedPane.class).addTab("Nested lifecycle fixture", container);
            resources.showSaved(); return resources;
        });
        java.util.List<ArchivePage<?>> pages = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> { for (String module : MODULES) workspace(module).showSaved(); });
        await(() -> !nested.loading() && nested.displayedPage() != null && Arrays.stream(MODULES).allMatch(module -> !workspace(module).loading() && workspace(module).displayedPage() != null));
        SwingUtilities.invokeAndWait(() -> {
            for (String module : MODULES) pages.add(workspace(module).displayedPage());
            pages.add(nested.displayedPage());
        });
        try (ArchiveResult.Lease<ChatArchiveClient.Row> held = edt(() -> chatWorkspace().displayedPage().lease())) {
            gui.closeWorkspace(); gui.closeWorkspace();
            for (ArchivePage<?> page : pages) {
                try (ArchiveResult.Lease<?> unexpected = page.lease()) { fail("Disposed workspace retained its result owner"); }
                catch (java.io.IOException expected) { /* Owner closed; existing leases remain valid. */ }
            }
            java.util.List<ChatArchiveClient.Row> rows = new ArrayList<>();
            held.stream(ExportSelection.all(), row -> rows.add(row.value), new Cancellation());
            assertEquals(1, rows.size()); assertEquals("Before close", rows.get(0).message.text);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (hasArchiveScratch(temp.getRoot().toPath()) && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse("Closing the shell and final lease releases private pin/result scratch", hasArchiveScratch(temp.getRoot().toPath()));
        store.append("chat", message("After view disposal")); store.flush();
        store.collect("integration-final", () -> store.put("integration-final", "checkpoint", "Final collected value"));
        String session = store.currentId(); Path root = store.directory(); store.close();
        try (SessionStore reader = new SessionStore(root, false, "read-only")) {
            assertEquals(2, reader.read(session, "chat", ChatMessage.class).size());
            assertEquals(Collections.singletonList("Final collected value"), reader.read(session, "integration-final", String.class));
        }
    }

    /**
     * P5b: page 10 is Runs & DPS, whose tabs are the Feed (the Runs page), Dungeons, the app's single DPS meter (Live meter) and
     * the encounter library (Recordings); page 7 only points there. ENCOUNTER and RESOURCES routes bring the Live meter forward,
     * and Back returns to the page and the tab they left (the page's composite Back state).
     */
    @Test public void runsAndDpsHostsTheMeterAndRecordingsAndMeterRoutesBringTheLiveMeterForward() throws Exception {
        packets.incoming.MapInfoPacket map = new packets.incoming.MapInfoPacket(); map.name = map.displayName = "Lost Halls";
        tomato.history.link.VisitRef visit = new tomato.history.link.VisitRef(store.currentId(), "journal:1");
        tomato.backend.data.DpsData fight = new tomato.backend.data.DpsData(map, new HashMap<>(), new ArrayList<>(), 60_000, 160_001,
            null, null, new tomato.history.link.EncounterContext(visit, 21, 160_001));
        data.dpsData.add(fight);
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.dps.DpsGUI dps = find(shell, tomato.gui.dps.DpsGUI.class);
            dps.encounters().captured(data.dpsData.toArray(new tomato.backend.data.DpsData[0]));
            tomato.gui.runs.RunsDpsPage page = runsDps();
            JTabbedPane tabs = named(page, "runs-tabs", JTabbedPane.class);
            java.util.List<String> titles = new ArrayList<>();
            for (int i = 0; i < tabs.getTabCount(); i++) titles.add(tabs.getTitleAt(i));
            assertEquals(Arrays.asList("Feed", "Dungeons", "Live meter", "Recordings"), titles);
            assertEquals("Runs & DPS opens on the Feed", tomato.gui.runs.RunsTab.FEED, page.selectedTab());
            assertSame("The Feed is the Runs page", named(shell, "runs-page", tomato.gui.runs.RunsPage.class), page.feed());
            assertSame("The Live meter tab is the app's single DPS meter", dps, tabs.getComponentAt(tabs.indexOfTab("Live meter")));
            assertNotNull("Recordings hosts the encounter library, not a dialog",
                find(named(page, "runs-recordings-slot", JPanel.class), tomato.gui.dps.DungeonListGUI.class));
            assertNotNull("Page 7 only points to Runs & DPS", find(shell, tomato.gui.dps.DpsMovedPanel.class));
            assertEquals(10, WorkspaceShell.pageOf(tomato.gui.route.Destination.ENCOUNTER));
            assertEquals(10, WorkspaceShell.pageOf(tomato.gui.route.Destination.RESOURCES));
            tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();

            shell.select(0);
            assertTrue("A plain ENCOUNTER route (Home's Now card)", navigator.open(tomato.gui.route.Route.to(tomato.gui.route.Destination.ENCOUNTER)));
            assertEquals(10, shell.getSelectedPage());
            assertEquals("…opens the Live meter", tomato.gui.runs.RunsTab.LIVE_METER, page.selectedTab());
            assertTrue(navigator.back()); assertEquals(0, shell.getSelectedPage());

            // From the Feed on the same page: an exact recording opens in the Live meter; Back brings the Feed forward again.
            shell.select(10); page.tabs().select(tomato.gui.runs.RunsTab.FEED.id());
            assertTrue(navigator.open(tomato.gui.route.Route.to(tomato.gui.route.Destination.ENCOUNTER).withRecording(fight.getRecordingId(), null)));
            assertEquals(10, shell.getSelectedPage());
            assertEquals(tomato.gui.runs.RunsTab.LIVE_METER, page.selectedTab());
            assertEquals("The exact recording shows", dps.encounters().find(fight).id, dps.currentEncounterId());
            assertTrue(navigator.back());
            assertEquals(10, shell.getSelectedPage());
            assertEquals("Back returns to the Feed it left", tomato.gui.runs.RunsTab.FEED, page.selectedTab());

            shell.select(0);
            assertTrue(navigator.open(tomato.gui.route.Route.to(tomato.gui.route.Destination.RESOURCES).withVisit(visit)));
            assertEquals(10, shell.getSelectedPage());
            assertEquals("RESOURCES opens the Live meter", tomato.gui.runs.RunsTab.LIVE_METER, page.selectedTab());
            JTabbedPane nested = named(dps, "dps-tabs", JTabbedPane.class);
            assertEquals("…on its Resources & buffs tab", "Resources & buffs", nested.getTitleAt(nested.getSelectedIndex()));
            assertTrue(navigator.back()); assertEquals(0, shell.getSelectedPage());
        });
    }

    /** Alt+8, the DPS Logger pointer's two buttons and the meter's library button open their tab through the navigator; Back returns. */
    @Test public void altEightThePointerAndTheLibraryButtonOpenTheirTabsAndBackReturns() throws Exception {
        int windows = Window.getWindows().length;
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.runs.RunsDpsPage page = runsDps();
            tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();
            Action altEight = shell.getActionMap().get("page-7");   // WorkspaceShellLayoutTest pins Alt+8 to "page-7"
            shell.select(0);
            altEight.actionPerformed(null);
            assertEquals("Alt+8 opens Runs & DPS", 10, shell.getSelectedPage());
            assertEquals("…on the Live meter", tomato.gui.runs.RunsTab.LIVE_METER, page.selectedTab());
            assertTrue("Alt+8 goes through the navigator", navigator.back()); assertEquals(0, shell.getSelectedPage());
            shell.select(10); page.tabs().select(tomato.gui.runs.RunsTab.FEED.id());
            assertTrue(page.tabs().hide(tomato.gui.runs.RunsTab.LIVE_METER.id()));
            altEight.actionPerformed(null);
            assertEquals("Alt+8 brings a hidden Live meter forward (explicit navigation)", tomato.gui.runs.RunsTab.LIVE_METER, page.selectedTab());
            assertFalse(page.tabs().hiddenIds().contains(tomato.gui.runs.RunsTab.LIVE_METER.id()));
            assertTrue(navigator.back());
            assertEquals(10, shell.getSelectedPage());
            assertEquals("Back returns to the Feed", tomato.gui.runs.RunsTab.FEED, page.selectedTab());

            shell.select(7);
            tomato.gui.dps.DpsMovedPanel pointer = find(shell, tomato.gui.dps.DpsMovedPanel.class);
            named(pointer, "dps-moved-open", AbstractButton.class).doClick();
            assertEquals(10, shell.getSelectedPage());
            assertEquals("Open Live meter", tomato.gui.runs.RunsTab.LIVE_METER, page.selectedTab());
            assertTrue(navigator.back()); assertEquals("Back returns to the pointer", 7, shell.getSelectedPage());
            named(pointer, "dps-moved-recordings", AbstractButton.class).doClick();
            assertEquals(10, shell.getSelectedPage());
            assertEquals("Open Recordings", tomato.gui.runs.RunsTab.RECORDINGS, page.selectedTab());
            assertTrue(navigator.back()); assertEquals(7, shell.getSelectedPage());

            // The meter's library button opens the Recordings tab, no longer a modal dialog; Back returns to the Live meter.
            shell.select(10); page.tabs().select(tomato.gui.runs.RunsTab.LIVE_METER.id());
            AbstractButton library = named(find(shell, tomato.gui.dps.DpsGUI.class), "dps-open-library", AbstractButton.class);
            assertTrue(library.isEnabled());
            library.doClick();
            assertEquals(tomato.gui.runs.RunsTab.RECORDINGS, page.selectedTab());
            assertTrue(navigator.back());
            assertEquals(10, shell.getSelectedPage());
            assertEquals(tomato.gui.runs.RunsTab.LIVE_METER, page.selectedTab());
        });
        assertEquals("No dialog opened", windows, Window.getWindows().length);
    }

    /** The Live meter, Recordings and Statistics (no longer in the sidebar) are found by search and open through their routes. */
    @Test public void searchOpensTheLiveMeterRecordingsAndStatistics() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.search.ActionRegistry registry = tomato.gui.search.ActionRegistry.application();
            tomato.gui.runs.RunsDpsPage page = runsDps();
            tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();
            assertEquals(1, registry.search("dps.meter").size());
            tomato.gui.search.ActionDescriptor meter = registry.search("dps.meter").get(0);
            assertEquals("Live DPS meter", meter.label);
            assertEquals("Runs & DPS › Live meter", meter.location);
            assertEquals("The old page name finds it", Collections.singletonList("dps.meter"), ids(registry.search("dps logger")));
            assertEquals("…and so does its shortcut", Collections.singletonList("dps.meter"), ids(registry.search("alt+8")));
            assertEquals(1, registry.search("dps.recordings").size());
            tomato.gui.search.ActionDescriptor recordings = registry.search("dps.recordings").get(0);
            assertEquals("Recordings (encounter library)", recordings.label);
            assertEquals("Runs & DPS › Recordings", recordings.location);
            assertEquals(Collections.singletonList("dps.recordings"), ids(registry.search("encounter library")));
            assertEquals(1, registry.search("statistics.open").size());
            tomato.gui.search.ActionDescriptor statistics = registry.search("statistics.open").get(0);
            assertEquals("Statistics (fame table, live loot log)", statistics.label);
            assertEquals("Statistics (not in the sidebar)", statistics.location);
            // The entries other tests search for stay unambiguous.
            assertEquals(1, registry.search("build.open").size());
            assertEquals(1, registry.search("combat.settings").size());
            assertEquals("combat.settings", registry.search("full detail retention").get(0).id);

            shell.select(0);
            assertTrue(meter.open());
            assertEquals(10, shell.getSelectedPage()); assertEquals(tomato.gui.runs.RunsTab.LIVE_METER, page.selectedTab());
            assertTrue(navigator.back()); assertEquals(0, shell.getSelectedPage());
            assertTrue(recordings.open());
            assertEquals(10, shell.getSelectedPage()); assertEquals(tomato.gui.runs.RunsTab.RECORDINGS, page.selectedTab());
            assertTrue(navigator.back()); assertEquals(0, shell.getSelectedPage());
            assertTrue(statistics.open());
            assertEquals("Statistics stays page 4", 4, shell.getSelectedPage());
            assertFalse("…without a sidebar row", named(shell, "nav-4", AbstractButton.class).isVisible());
        });
    }

    /** Browse saved history is explicit navigation to the Runs table: the Feed comes forward, even from a hidden Feed tab. */
    @Test public void browseSavedHistoryBringsTheFeedForward() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.runs.RunsDpsPage page = runsDps();
            shell.select(10); page.tabs().select(tomato.gui.runs.RunsTab.LIVE_METER.id());
            TomatoGUI.browseSavedHistory();
            assertEquals(10, shell.getSelectedPage());
            assertEquals("Browse saved history shows the Feed", tomato.gui.runs.RunsTab.FEED, page.selectedTab());
            assertTrue("…on its Table view", page.feed().feed().tableShown());
            page.tabs().select(tomato.gui.runs.RunsTab.LIVE_METER.id());
            assertTrue(page.tabs().hide(tomato.gui.runs.RunsTab.FEED.id()));
            shell.select(0);
            TomatoGUI.browseSavedHistory();
            assertEquals(10, shell.getSelectedPage());
            assertEquals(tomato.gui.runs.RunsTab.FEED, page.selectedTab());
            assertFalse("A hidden Feed is shown again", page.tabs().hiddenIds().contains(tomato.gui.runs.RunsTab.FEED.id()));
        });
    }

    /**
     * A hidden tab is detached from the tree, so closing walks the tabs' contents too: hidden Feed and Live meter tabs (and the
     * Live meter's hidden Resources & buffs tab inside) still release their saved readers.
     */
    @Test public void hiddenFeedAndLiveMeterTabsStillCloseTheirWorkspaces() throws Exception {
        ArchiveWorkspace<?,?,?> runs = workspace("runs"), combat = workspace("combat");
        SwingUtilities.invokeAndWait(() -> { runs.showSaved(); combat.showSaved(); });
        await(() -> !runs.loading() && runs.displayedPage() != null && !combat.loading() && combat.displayedPage() != null);
        java.util.List<ArchivePage<?>> pages = edt(() -> Arrays.asList(runs.displayedPage(), combat.displayedPage()));
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.runs.RunsDpsPage page = runsDps();
            JTabbedPane nested = named(shell, "dps-tabs", JTabbedPane.class);
            assertTrue(((tomato.gui.kit.CustomizableTabs) nested.getClientProperty(tomato.gui.kit.CustomizableTabs.class)).hide("resources"));
            assertTrue(page.tabs().hide(tomato.gui.runs.RunsTab.FEED.id()));
            assertTrue(page.tabs().hide(tomato.gui.runs.RunsTab.LIVE_METER.id()));
            assertNull("The hidden Feed is detached", named(shell, "runs-session-view", ArchiveWorkspace.class));
            assertNull("…and so is the hidden Live meter with its Resources workspace", named(shell, "combat-session-view", ArchiveWorkspace.class));
        });
        for (ArchivePage<?> page : pages) try (ArchiveResult.Lease<?> open = page.lease()) { assertNotNull(open); }
        gui.closeWorkspace();
        for (ArchivePage<?> page : pages) {
            try (ArchiveResult.Lease<?> unexpected = page.lease()) { fail("A hidden tab's workspace kept its result owner"); }
            catch (java.io.IOException expected) { /* Owner closed. */ }
        }
    }

    /** Page 10, Runs & DPS. */
    private tomato.gui.runs.RunsDpsPage runsDps() {
        tomato.gui.runs.RunsDpsPage page = named(shell, "runs-dps-page", tomato.gui.runs.RunsDpsPage.class);
        assertNotNull("Page 10 is the Runs & DPS page", page);
        return page;
    }
    private static java.util.List<String> ids(java.util.List<tomato.gui.search.ActionDescriptor> found) {
        java.util.List<String> ids = new ArrayList<>();
        for (tomato.gui.search.ActionDescriptor entry : found) ids.add(entry.id);
        return ids;
    }

    @Test public void previewQueriesKeepCapturedDataReadOnlyAndAvailabilityUnknownWhileViewStatePersists() throws Exception {
        store.append("chat", message("Preview conversation")); store.flush();
        gui.closeWorkspace(); SwingUtilities.invokeAndWait(() -> shell.removeNotify());
        String session = store.currentId(); Path root = store.directory(); store.close();
        Map<String,String> before = capturedFiles(root.resolve(session));
        store = new SessionStore(root, false, "preview");
        Field field = AppHistory.class.getDeclaredField("store"); field.setAccessible(true); field.set(null, store);
        SwingUtilities.invokeAndWait(this::buildShell);
        SwingUtilities.invokeAndWait(() -> { for (String module : MODULES) workspace(module).selectSession(session); });
        await(() -> Arrays.stream(MODULES).allMatch(module -> !workspace(module).loading() && workspace(module).displayedPage() != null));
        SwingUtilities.invokeAndWait(() -> {
            JTable chat = named(workspace("chat"), "chat-archive-messages", JTable.class);
            chat.setRowSelectionInterval(0, 0); button(workspace("chat"), "Toggle star").doClick();
            assertTrue(named(workspace("chat"), "chat-archive-save-status", JTextArea.class).getText().contains("read-only"));
        });
        AppHistory.append("chat", message("Must not be saved")); store.flush();
        assertEquals(before, capturedFiles(root.resolve(session)));
        for (SessionStore.SessionEntry entry : store.catalog(new Cancellation()))
            for (String module : MODULES) assertEquals(SessionStore.ModuleAvailability.State.UNKNOWN, entry.availability(module).state);
        assertTrue(PropertiesManager.flush().toCompletableFuture().get(5, TimeUnit.SECONDS).isSuccess());
        assertTrue(PropertiesManager.getProperty("ux.archive.chat").contains(session));
    }

    @Test public void missingHistoryStoreRetainsTheExistingLiveViews() throws Exception {
        gui.closeWorkspace(); SwingUtilities.invokeAndWait(() -> shell.removeNotify());
        Field field = AppHistory.class.getDeclaredField("store"); field.setAccessible(true); field.set(null, null);
        SwingUtilities.invokeAndWait(() -> {
            buildShell(); assertNull(find(shell, ArchiveWorkspace.class));
            assertNotNull(find(shell, ChatGUI.class)); assertNotNull(find(shell, tomato.gui.keypop.KeypopGUI.class));
            assertNotNull(find(shell, tomato.gui.security.SecurityGUI.class));
            assertNotNull(find(shell, tomato.gui.stats.StatisticsGUI.class));
            assertNotNull(find(shell, tomato.gui.stats.LootDashboard.class));
            TomatoGUI.browseSavedHistory(); assertEquals(10, shell.getSelectedPage());
        });
    }

    private ArchiveWorkspace<?,?,?> workspace(String module) {
        ArchiveWorkspace<?,?,?> result = named(shell, module + "-session-view", ArchiveWorkspace.class);
        assertNotNull(module + " queried factory must be registered", result); return result;
    }
    @SuppressWarnings("unchecked")
    private ArchiveWorkspace<ChatArchiveClient.Row,ChatArchiveClient.Facets,ChatArchiveClient.Sort> chatWorkspace() {
        return (ArchiveWorkspace<ChatArchiveClient.Row,ChatArchiveClient.Facets,ChatArchiveClient.Sort>) workspace("chat");
    }
    private static ChatMessage message(String text) {
        return new ChatMessage(LocalDateTime.of(2026, 9, 1, 12, 0), ChatMessage.Channel.WORLD, "Ann", "", "Ann", text, "");
    }
    private static Map<String,String> capturedFiles(Path root) throws Exception {
        Map<String,String> contents = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : (Iterable<Path>)files.filter(Files::isRegularFile)::iterator)
                contents.put(root.relativize(path).toString(), Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
        }
        return contents;
    }
    private static boolean hasArchiveScratch(Path root) throws Exception {
        try (Stream<Path> files = Files.walk(root)) {
            return files.anyMatch(path -> path.getFileName().toString().startsWith("archive-pin-")
                || path.getFileName().toString().startsWith("archive-result-"));
        }
    }
    private static AbstractButton button(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof AbstractButton && text.equals(((AbstractButton)c).getText())) return (AbstractButton)c;
            if (c instanceof Container) { AbstractButton found = button((Container)c, text); if (found != null) return found; }
        }
        return null;
    }
    private static <T> T find(Container root, Class<T> type) { return named(root, null, type); }
    private static <T> T named(Container root, String name, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && (name == null || name.equals(c.getName()))) return type.cast(c);
            if (c instanceof Container) { T found = named((Container)c, name, type); if (found != null) return found; }
        }
        return null;
    }
}
