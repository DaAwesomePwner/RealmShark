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
            shell.select("chat");
            TomatoGUI.openNotifications(tomato.gui.notifications.NotificationsGUI.KEY_POPS);
            assertEquals("settings", shell.selectedPage());
            assertEquals(tomato.gui.settings.SettingsPage.NOTIFICATIONS, settings.currentSection());
            JTabbedPane tabs = find(notifications, JTabbedPane.class);
            assertEquals(tomato.gui.notifications.NotificationsGUI.KEY_POPS, tabs.getTitleAt(tabs.getSelectedIndex()));
            settings.showSection(tomato.gui.settings.SettingsPage.APPEARANCE);
            shell.select("chat");
            assertTrue(tomato.gui.route.Navigator.current().open(tomato.gui.route.Route.to(tomato.gui.route.Destination.NOTIFICATIONS)));
            assertEquals("settings", shell.selectedPage());
            assertEquals("The Notifications route shows its section", tomato.gui.settings.SettingsPage.NOTIFICATIONS, settings.currentSection());
            assertTrue(tomato.gui.route.Navigator.current().back());
            assertEquals("chat", shell.selectedPage());
            tomato.gui.search.ActionRegistry registry = tomato.gui.search.ActionRegistry.application();
            assertTrue(registry.search("appearance.settings").get(0).open());
            assertEquals("settings", shell.selectedPage());
            assertEquals(tomato.gui.settings.SettingsPage.APPEARANCE, settings.currentSection());
            assertTrue(tomato.gui.route.Navigator.current().open(tomato.gui.route.Route.to(tomato.gui.route.Destination.NOTIFICATIONS)));
            assertEquals(tomato.gui.settings.SettingsPage.NOTIFICATIONS, settings.currentSection());
            assertTrue(tomato.gui.route.Navigator.current().back());
            assertEquals("Back restores the Settings section as well as notification state",
                    tomato.gui.settings.SettingsPage.APPEARANCE, settings.currentSection());
            assertNotNull("Settings hosts General", named(settings, "settings-general", JComponent.class));
            assertNotNull(named(settings, "settings-combat-full-detail", JCheckBox.class));
            shell.select("chat");
            assertEquals(1, registry.search("combat.settings").size());
            assertEquals("Combat history is found by its words", "combat.settings", registry.search("full detail retention").get(0).id);
            assertTrue(registry.search("combat.settings").get(0).open());
            assertEquals("settings", shell.selectedPage());
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
    /**
     * The Runs & DPS tabs and the Live meter's nested tabs: cleared so each shell opens on the Feed with every tab shown, then
     * restored; also the Dungeons tab's view and the Dungeons, analysis, feed and Recordings filter drawers (P5b Task 12).
     */
    private static final String[] TAB_PREFERENCES = {"ui.tabs.runs", "ui.tabs.dps", "ui.dungeons.view", "ui.filters.dungeons.open",
        "ui.filters.dungeon-analysis.open", "ui.filters.run-feed.open", "ui.filters.encounter-library.open"};
    private final Map<String,String> tabPreferences = new LinkedHashMap<>();
    /** The run recap's section choices (ui.collapse.run-recap-*): cleared so each recap opens with its defaults, then restored. */
    private static final String[] RECAP_PREFERENCES = {"damage", "loot", "players", "resources", "timeline", "evidence"};
    private final Map<String,String> recapPreferences = new LinkedHashMap<>();
    /** The queried archive workspaces of the shell (P6a Task 12 removed the Statistics one). */
    private static final String[] MODULES = {"chat", "keypops", "inspect", "loot", "runs", "timeline"};
    /**
     * P6a Task 11: Loot's tabs (it opens on Highlights with both tabs shown), the Characters tabs (the Fame history search entry
     * may show that tab) and the Highlights window: cleared, then restored.
     */
    private static final String[] LOOT_PREFERENCES = {"ui.tabs.loot", "ui.tabs.characters", tomato.gui.loot.LootHighlights.WINDOW_KEY};
    private final Map<String,String> lootPreferences = new LinkedHashMap<>();

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
        for (String key : LOOT_PREFERENCES) {
            lootPreferences.put(key, PropertiesManager.getProperty(key));
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
        // Build now follows the journal (live or most recent character): with this empty temporary journal a Build route opens Characters.
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
        for (String key : LOOT_PREFERENCES) { String saved = lootPreferences.get(key); PropertiesManager.setProperties(key, saved == null ? "" : saved); }
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
            shell.select("chat");
            assertEquals(1, registry.search("plans.characters").size());
            assertEquals("chat", shell.selectedPage());
            assertTrue(registry.search("plans.characters").get(0).open());
            assertEquals("characters", shell.selectedPage());
            assertTrue(tomato.gui.route.Navigator.current().back()); assertEquals("chat", shell.selectedPage());
            assertTrue(registry.search("plans.quests").get(0).open()); assertEquals("quests", shell.selectedPage());
            JTabbedPane quests = named(shell, "quests-tabs", JTabbedPane.class);
            assertEquals("The search opens the Planner", "Planner", quests.getTitleAt(quests.getSelectedIndex()));
            assertTrue(tomato.gui.route.Navigator.current().back()); assertEquals("chat", shell.selectedPage());
            // The search opens the Planner through the navigator, so its tab switch is part of the Back entry: from the Board on the
            // Quests page, Back returns to the Board.
            shell.select("quests"); quests.setSelectedIndex(quests.indexOfTab("Board"));
            assertTrue(registry.search("plans.quests").get(0).open());
            assertEquals("quests", shell.selectedPage()); assertEquals("Planner", quests.getTitleAt(quests.getSelectedIndex()));
            assertTrue(tomato.gui.route.Navigator.current().back());
            assertEquals("quests", shell.selectedPage());
            assertEquals("Back returns to the Board the search left", "Board", quests.getTitleAt(quests.getSelectedIndex()));
            assertEquals(capture, Tomato.isCaptureRunning());
        });
    }

    @Test public void charactersRoutesOpenTheListOrOneSheetAndBackRestoresEach() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();
            tomato.gui.character.CharacterRosterView roster = find(shell, tomato.gui.character.CharacterRosterView.class);
            assertNotNull("The Characters Roster tab hosts the list and the sheet", roster);
            shell.select("chat");
            String unknown = "0".repeat(64) + ":404";
            assertTrue(navigator.open(tomato.gui.route.Route.to(tomato.gui.route.Destination.CHARACTER_SHEET)
                .withPayload(new tomato.gui.glance.character.SheetFocus(unknown, null))));
            assertEquals("characters", shell.selectedPage());
            assertTrue(roster.showingSheet());
            tomato.gui.activity.SnapshotTestSupport.await(roster.sheet()::ready); // at once here; from Task 5 the sheet reads off the EDT
            assertTrue("An unknown key shows the unavailable state",
                named(roster, "character-sheet-unavailable", tomato.gui.kit.EmptyState.class).isVisible());
            assertTrue(navigator.open(tomato.gui.route.Route.to(tomato.gui.route.Destination.CHARACTERS)));
            assertFalse("A plain Characters route shows the list", roster.showingSheet());
            assertTrue(navigator.back());
            assertTrue("Back restores the sheet", roster.showingSheet()); assertEquals(unknown, roster.sheet().key());
            assertTrue(navigator.back()); assertEquals("chat", shell.selectedPage());
        });
    }

    @Test public void homeIsAPageAndTheBuildSearchOpensCharactersWithoutACharacter() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();
            tomato.gui.glance.home.HomePage home = find(shell, tomato.gui.glance.home.HomePage.class);
            assertNotNull("Home is a shell page", home);
            assertEquals("home-page", home.getName());
            assertNotNull("Home shows its cards", named(home, "home-hero", tomato.gui.kit.Card.class));
            shell.select("chat");
            assertTrue(navigator.open(tomato.gui.route.Route.to(tomato.gui.route.Destination.HOME)));
            assertEquals("home", shell.selectedPage());
            assertTrue(home.isVisible());
            assertTrue(navigator.back()); assertEquals("chat", shell.selectedPage());
            tomato.gui.search.ActionRegistry registry = tomato.gui.search.ActionRegistry.application();
            assertEquals(1, registry.search("build.open").size());
            assertEquals("Build (weapon damage and recovery)", registry.search("build.open").get(0).label);
            assertTrue(registry.search("build.open").get(0).open());
            assertEquals("With no character, Build opens Characters", "characters", shell.selectedPage());
            assertEquals("Characters", named(shell, "page-title", JLabel.class).getText());
            assertFalse("…on the list, not a sheet", find(shell, tomato.gui.character.CharacterRosterView.class).showingSheet());
            assertNull("Build has no page or row of its own", named(shell, "nav-my-info", AbstractButton.class));
            assertTrue(navigator.back()); assertEquals("chat", shell.selectedPage());
        });
    }


    @Test public void homeCardsOpenTheirPagesThroughTheNavigatorAndBackReturnsHome() throws Exception {
        tomato.history.link.VisitRef[] opened = new tomato.history.link.VisitRef[1];
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.glance.home.HomePage home = find(shell, tomato.gui.glance.home.HomePage.class);
            assertNotNull("Home is a shell page", home);
            tomato.gui.glance.home.HomeModel model = tomato.gui.glance.home.HomeModels.populated(System.currentTimeMillis());
            home.apply(model);
            tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();
            String[] cards = {"home-hero", "home-now", "home-quests"};
            String[] pages = {"characters", "runs", "quests"}; // Characters, Runs & DPS (the Live meter moved there in P5b), Quests
            for (int i = 0; i < cards.length; i++) {
                shell.select("home");
                named(home, cards[i], tomato.gui.kit.Card.class).getActionMap().get("open-card").actionPerformed(null);
                assertEquals(cards[i] + " opens its page", pages[i], shell.selectedPage());
                if (cards[i].equals("home-now"))
                    assertEquals("The Now card opens the Live meter tab", tomato.gui.runs.RunsTab.LIVE_METER, runsDps().selectedTab());
                assertTrue(navigator.back());
                assertEquals(cards[i] + ": Back returns to Home", "home", shell.selectedPage());
            }
            named(home, "home-build", AbstractButton.class).doClick();
            assertEquals("With no character yet, the hero's Build action opens Characters", "characters", shell.selectedPage());
            assertFalse("…on the list, not a sheet", find(shell, tomato.gui.character.CharacterRosterView.class).showingSheet());
            assertTrue(navigator.back());
            assertEquals("home", shell.selectedPage());
            named(home, "home-run-0", JComponent.class).getActionMap().get("open-run").actionPerformed(null);
            assertEquals("A recent run opens Runs", "runs", shell.selectedPage());
            // Home's Recent runs opens the run recap (P5a), no longer the Table view's row.
            tomato.gui.runs.RunsPage runs = named(shell, "runs-page", tomato.gui.runs.RunsPage.class);
            assertTrue("The exact run shows in the Runs page's recap", runs.recapShown());
            tomato.history.link.VisitRef visit = model.runs().rows().get(0).visit();
            assertEquals("The recap shows that exact visit, not a name or time match", visit, ((tomato.gui.runs.RunRecapView) runs.recap()).ref());
            opened[0] = visit;
            assertTrue(navigator.back());
            assertEquals("home", shell.selectedPage());
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
            shell.select("home");
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
            assertEquals("The row opens Runs", "runs", shell.selectedPage());
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
            assertEquals("Back returns Home", "home", shell.selectedPage());
            assertFalse("…and the Runs page no longer shows the recap Back left", named(shell, "runs-page", tomato.gui.runs.RunsPage.class).recapShown());
        });

        // From the sidebar: Runs, then the first completed card.
        tomato.gui.runs.RunsPage runs = edt(() -> named(shell, "runs-page", tomato.gui.runs.RunsPage.class));
        int[] clicks = {0};
        SwingUtilities.invokeAndWait(() -> {
            named(shell, "nav-runs", JToggleButton.class).doClick(); clicks[0]++;
            assertEquals("runs", shell.selectedPage());
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
            assertEquals("runs", shell.selectedPage());
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
                    shell.select("home");
                    JLabel needs = named(home, "home-hero-needs", JLabel.class);
                    assertEquals("S2, 0 clicks: Home names the stat and the count", "Needs WIS 3 potions", needs.getText());
                    assertTrue(shown(needs));
                    named(home, "home-hero", tomato.gui.kit.Card.class).getActionMap().get("open-card").actionPerformed(null); // click 1
                    assertEquals("The hero opens the Characters page", "characters", shell.selectedPage());
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
                    assertEquals("Back returns Home", "home", shell.selectedPage());
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
                    assertEquals("home", shell.selectedPage());
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
                shell.select("home");
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
                assertEquals("The Quests card opens the Quests page", "quests", shell.selectedPage());
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
                assertEquals("Back returns Home", "home", shell.selectedPage());
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
            shell.select("home");
            named(home, "home-hero", tomato.gui.kit.Card.class).getActionMap().get("open-card").actionPerformed(null);
            assertEquals("characters", shell.selectedPage());
            assertFalse("The list shows, not a sheet", shown(find(shell, tomato.gui.glance.character.CharacterSheet.class)));
            assertTrue(tomato.gui.route.Navigator.current().back());
            assertEquals("home", shell.selectedPage());
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
        SwingUtilities.invokeAndWait(() -> { shell.select("chat"); panel.selectSession(SessionStore.ALL); });
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
            for (String index : new String[]{"key-pops", "loot", "runs", "chat"}) named(shell, "nav-" + index, JToggleButton.class).doClick();
            assertEquals(past, chat.state().query.scope()); assertTrue(chat.state().archive);
            assertEquals(SessionStore.ALL, workspace("keypops").state().query.scope());
            assertTrue(workspace("loot").state().archive);
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
            assertTrue(workspace("loot").state().archive); assertFalse(workspace("runs").state().archive);
            restored.selectSession(ArchiveQuery.CURRENT); assertFalse(restored.state().archive);
            restored.loadNamed("Past review");
            assertEquals(past, restored.state().query.scope()); assertTrue(restored.state().archive);
            assertEquals(Collections.singletonList("Past review"), ViewStateStore.application().names("chat"));
            TomatoGUI.browseSavedHistory();
            assertEquals("runs", shell.selectedPage()); assertEquals(SessionStore.ALL, workspace("runs").state().query.scope());
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
            named(shell, "runs-tabs", JTabbedPane.class).addTab("Nested lifecycle fixture", container);
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
     * P5b: the runs page is Runs & DPS, whose tabs are the Feed (the Runs page), Dungeons, the app's single DPS meter (Live meter)
     * and the encounter library (Recordings); P6a removed the DPS Logger page that pointed there. ENCOUNTER and RESOURCES routes
     * bring the Live meter forward,
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
            assertEquals(Arrays.asList("Feed", "Dungeons", "Live meter", "Resources & buffs", "Recordings"), titles);
            assertEquals("Runs & DPS opens on the Feed", tomato.gui.runs.RunsTab.FEED, page.selectedTab());
            assertSame("The Feed is the Runs page", named(shell, "runs-page", tomato.gui.runs.RunsPage.class), page.feed());
            assertSame("The Live meter tab is the app's single DPS meter", dps, tabs.getComponentAt(tabs.indexOfTab("Live meter")));
            assertNotNull("Recordings hosts the encounter library, not a dialog",
                find(named(page, "runs-recordings-slot", JPanel.class), tomato.gui.dps.DungeonListGUI.class));
            assertNull("No DPS Logger page remains", tomato.gui.modern.NavEntry.forId("dps-logger"));
            assertNull("…and no pointer to Runs & DPS", named(shell, "dps-moved", JComponent.class));
            assertEquals("runs", WorkspaceShell.pageOf(tomato.gui.route.Destination.ENCOUNTER));
            assertEquals("runs", WorkspaceShell.pageOf(tomato.gui.route.Destination.RESOURCES));
            tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();

            shell.select("chat");
            assertTrue("A plain ENCOUNTER route (Home's Now card)", navigator.open(tomato.gui.route.Route.to(tomato.gui.route.Destination.ENCOUNTER)));
            assertEquals("runs", shell.selectedPage());
            assertEquals("…opens the Live meter", tomato.gui.runs.RunsTab.LIVE_METER, page.selectedTab());
            assertTrue(navigator.back()); assertEquals("chat", shell.selectedPage());

            // From the Feed on the same page: an exact recording opens in the Live meter; Back brings the Feed forward again.
            shell.select("runs"); page.tabs().select(tomato.gui.runs.RunsTab.FEED.id());
            assertTrue(navigator.open(tomato.gui.route.Route.to(tomato.gui.route.Destination.ENCOUNTER).withRecording(fight.getRecordingId(), null)));
            assertEquals("runs", shell.selectedPage());
            assertEquals(tomato.gui.runs.RunsTab.LIVE_METER, page.selectedTab());
            assertEquals("The exact recording shows", dps.encounters().find(fight).id, dps.currentEncounterId());
            assertTrue(navigator.back());
            assertEquals("runs", shell.selectedPage());
            assertEquals("Back returns to the Feed it left", tomato.gui.runs.RunsTab.FEED, page.selectedTab());

            shell.select("chat");
            assertTrue(navigator.open(tomato.gui.route.Route.to(tomato.gui.route.Destination.RESOURCES).withVisit(visit)));
            assertEquals("runs", shell.selectedPage());
            assertEquals("RESOURCES opens its own tab", tomato.gui.runs.RunsTab.RESOURCES, page.selectedTab());
            JTabbedPane nested = page.tabs().component();
            assertEquals("…on its Resources & buffs tab", "Resources & buffs", nested.getTitleAt(nested.getSelectedIndex()));
            assertTrue(navigator.back()); assertEquals("chat", shell.selectedPage());
        });
    }

    /**
     * Alt+8 (TomatoGUI's key binding; P6a removed the DPS Logger page) and the meter's library button open their tab through the
     * navigator; Back returns. Recordings is also reached by search (searchOpensTheLiveMeterAndRecordings).
     */
    @Test public void altEightAndTheLibraryButtonOpenTheirTabsAndBackReturns() throws Exception {
        int windows = Window.getWindows().length;
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.runs.RunsDpsPage page = runsDps();
            tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();
            Object binding = shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_8, java.awt.event.InputEvent.ALT_DOWN_MASK));
            assertEquals("Alt+8 is bound to the Live meter route", "open-live-meter", binding);
            assertNull("No DPS Logger page action remains", shell.getActionMap().get("page-dps-logger"));
            Action altEight = shell.getActionMap().get(binding);
            shell.select("chat");
            altEight.actionPerformed(null);
            assertEquals("Alt+8 opens Runs & DPS", "runs", shell.selectedPage());
            assertEquals("…on the Live meter", tomato.gui.runs.RunsTab.LIVE_METER, page.selectedTab());
            assertTrue("Alt+8 goes through the navigator", navigator.back()); assertEquals("chat", shell.selectedPage());
            shell.select("runs"); page.tabs().select(tomato.gui.runs.RunsTab.FEED.id());
            assertTrue(page.tabs().hide(tomato.gui.runs.RunsTab.LIVE_METER.id()));
            altEight.actionPerformed(null);
            assertEquals("Alt+8 brings a hidden Live meter forward (explicit navigation)", tomato.gui.runs.RunsTab.LIVE_METER, page.selectedTab());
            assertFalse(page.tabs().hiddenIds().contains(tomato.gui.runs.RunsTab.LIVE_METER.id()));
            assertTrue(navigator.back());
            assertEquals("runs", shell.selectedPage());
            assertEquals("Back returns to the Feed", tomato.gui.runs.RunsTab.FEED, page.selectedTab());
            shell.select("home");
            altEight.actionPerformed(null);
            assertEquals("From another page too", "runs", shell.selectedPage());
            assertEquals(tomato.gui.runs.RunsTab.LIVE_METER, page.selectedTab());
            assertEquals("The Back label names the page Alt+8 left", "Back to Home", named(shell, "navigate-back", AbstractButton.class).getText());
            assertTrue(navigator.back()); assertEquals("home", shell.selectedPage());

            // The meter's library button opens the Recordings tab, no longer a modal dialog; Back returns to the Live meter.
            shell.select("runs"); page.tabs().select(tomato.gui.runs.RunsTab.LIVE_METER.id());
            AbstractButton library = named(find(shell, tomato.gui.dps.DpsGUI.class), "dps-open-library", AbstractButton.class);
            assertTrue(library.isEnabled());
            library.doClick();
            assertEquals(tomato.gui.runs.RunsTab.RECORDINGS, page.selectedTab());
            assertTrue(navigator.back());
            assertEquals("runs", shell.selectedPage());
            assertEquals(tomato.gui.runs.RunsTab.LIVE_METER, page.selectedTab());
        });
        assertEquals("No dialog opened", windows, Window.getWindows().length);
    }

    /**
     * P6a: the Build and DPS Logger pointer pages are gone. Alt+7 (TomatoGUI's key binding) opens Build through the navigator: with
     * no character (this fixture's empty journal) the Characters list; Back returns. Neither page keeps a row, a compact-menu item,
     * a page action or a page.
     */
    @Test public void altSevenWithoutACharacterOpensCharactersAndThePointerPagesAreGone() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();
            InputMap keys = shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            Object altSeven = keys.get(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_7, java.awt.event.InputEvent.ALT_DOWN_MASK));
            assertEquals("Alt+7 is bound to the Build route", "open-build", altSeven);
            shell.select("chat");
            shell.getActionMap().get(altSeven).actionPerformed(null);
            assertEquals("With no character, Alt+7 opens Characters", "characters", shell.selectedPage());
            assertFalse("…on the list, not a sheet", find(shell, tomato.gui.character.CharacterRosterView.class).showingSheet());
            assertEquals("Back to Chat", named(shell, "navigate-back", AbstractButton.class).getText());
            assertTrue("Alt+7 goes through the navigator", navigator.back());
            assertEquals("chat", shell.selectedPage());
            JPopupMenu popup = named(shell, "compact-navigation", AbstractButton.class).getComponentPopupMenu();
            for (String removed : new String[] {"my-info", "dps-logger"}) {
                assertNull(removed + " has no row", named(shell, "nav-" + removed, AbstractButton.class));
                assertNull(removed + " has no compact menu item", named(popup, "compact-nav-" + removed, JMenuItem.class));
                assertNull(removed + " has no page action", shell.getActionMap().get("page-" + removed));
                try { shell.select(removed); fail(removed + " is no page"); }
                catch (IllegalArgumentException expected) { assertEquals("Invalid page", expected.getMessage()); }
            }
            assertNull("No Build moved page", named(shell, "build-moved", JComponent.class));
            assertNull("No DPS Logger moved page", named(shell, "dps-moved", JComponent.class));
            assertEquals("chat", shell.selectedPage());
        });
    }

    /**
     * P6a Task 12: Statistics is gone. Alt+5 (TomatoGUI's key binding) opens Runs & DPS › Dungeons through the navigator, bringing a
     * hidden Dungeons tab forward (explicit navigation); Back returns. No page, row, compact-menu item, page action, route,
     * destination, search entry, Dungeons banner or link for Statistics remains.
     */
    @Test public void altFiveOpensDungeonsWithABackEntryAndStatisticsIsGone() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.runs.RunsDpsPage page = runsDps();
            tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();
            Object binding = shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_5, java.awt.event.InputEvent.ALT_DOWN_MASK));
            assertEquals("Alt+5 is bound to the Dungeons route", "open-dungeons", binding);
            Action altFive = shell.getActionMap().get(binding);
            shell.select("chat");
            altFive.actionPerformed(null);
            assertEquals("Alt+5 opens Runs & DPS", "runs", shell.selectedPage());
            assertEquals("…on Dungeons", tomato.gui.runs.RunsTab.DUNGEONS, page.selectedTab());
            assertEquals("Back to Chat", named(shell, "navigate-back", AbstractButton.class).getText());
            assertTrue("Alt+5 goes through the navigator", navigator.back()); assertEquals("chat", shell.selectedPage());
            shell.select("runs"); page.tabs().select(tomato.gui.runs.RunsTab.FEED.id());
            assertTrue(page.tabs().hide(tomato.gui.runs.RunsTab.DUNGEONS.id()));
            altFive.actionPerformed(null);
            assertEquals("Alt+5 brings a hidden Dungeons tab forward (explicit navigation)", tomato.gui.runs.RunsTab.DUNGEONS, page.selectedTab());
            assertFalse(page.tabs().hiddenIds().contains(tomato.gui.runs.RunsTab.DUNGEONS.id()));
            assertTrue(navigator.back());
            assertEquals("runs", shell.selectedPage());
            assertEquals("Back returns to the Feed", tomato.gui.runs.RunsTab.FEED, page.selectedTab());

            JPopupMenu popup = named(shell, "compact-navigation", AbstractButton.class).getComponentPopupMenu();
            assertNull("No Statistics row", named(shell, "nav-statistics", AbstractButton.class));
            assertNull("No Statistics compact menu item", named(popup, "compact-nav-statistics", JMenuItem.class));
            assertNull("No Statistics page action", shell.getActionMap().get("page-statistics"));
            try { shell.select("statistics"); fail("Statistics is no page"); }
            catch (IllegalArgumentException expected) { assertEquals("Invalid page", expected.getMessage()); }
            assertNull("No Statistics destination entry", tomato.gui.modern.NavEntry.forId("statistics"));
            for (tomato.gui.route.Destination destination : tomato.gui.route.Destination.values())
                assertNotEquals("No Statistics route destination", "STATISTICS", destination.name());
            for (Component component : descendants(shell))
                assertNotEquals("No Statistics page is built", "StatisticsGUI", component.getClass().getSimpleName());
            tomato.gui.search.ActionRegistry registry = tomato.gui.search.ActionRegistry.application();
            assertTrue("No Statistics search entry", registry.search("statistics.open").isEmpty());
            assertEquals("Its words lead to Dungeons", Collections.singletonList("dungeons.open"), ids(registry.search("statistics")));
            assertEquals(Collections.singletonList("dungeons.open"), ids(registry.search("dungeon stats")));
            assertEquals(Collections.singletonList("dungeons.open"), ids(registry.search("alt+5")));
            assertTrue("…and fame to Characters › Fame history", ids(registry.search("fame")).contains("fame.history"));
            JComponent dungeons = named(page, "dungeons-view", JComponent.class);
            assertNull("No Statistics pointer on the Dungeons analysis", named(dungeons, "dungeons-statistics-banner", JComponent.class));
            assertNull("No Open Statistics link", named(dungeons, "dungeons-open-statistics", AbstractButton.class));
        });
    }

    /** Every component under {@code root}, including the contents of hidden customizable tabs. */
    private static java.util.List<Component> descendants(Component root) {
        java.util.List<Component> all = new ArrayList<>();
        Deque<Component> todo = new ArrayDeque<>(Collections.singletonList(root));
        Set<Component> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!todo.isEmpty()) {
            Component next = todo.pop();
            if (!seen.add(next)) continue;
            all.add(next);
            Object tabs = next instanceof JComponent ? ((JComponent) next).getClientProperty(tomato.gui.kit.CustomizableTabs.class) : null;
            if (tabs instanceof tomato.gui.kit.CustomizableTabs) todo.addAll(((tomato.gui.kit.CustomizableTabs) tabs).contents());
            if (next instanceof Container) todo.addAll(Arrays.asList(((Container) next).getComponents()));
        }
        return all;
    }

    /** The Live meter and Recordings are found by search and open through their routes (the Statistics entry went with the page: see the Alt+5 test). */
    @Test public void searchOpensTheLiveMeterAndRecordings() throws Exception {
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
            // The entries other tests search for stay unambiguous.
            assertEquals(1, registry.search("build.open").size());
            assertEquals(1, registry.search("combat.settings").size());
            assertEquals("combat.settings", registry.search("full detail retention").get(0).id);

            shell.select("chat");
            assertTrue(meter.open());
            assertEquals("runs", shell.selectedPage()); assertEquals(tomato.gui.runs.RunsTab.LIVE_METER, page.selectedTab());
            assertTrue(navigator.back()); assertEquals("chat", shell.selectedPage());
            assertTrue(recordings.open());
            assertEquals("runs", shell.selectedPage()); assertEquals(tomato.gui.runs.RunsTab.RECORDINGS, page.selectedTab());
            assertTrue(navigator.back()); assertEquals("chat", shell.selectedPage());
        });
    }

    /** Browse saved history is explicit navigation to the Runs table: the Feed comes forward, even from a hidden Feed tab. */
    @Test public void browseSavedHistoryBringsTheFeedForward() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.runs.RunsDpsPage page = runsDps();
            shell.select("runs"); page.tabs().select(tomato.gui.runs.RunsTab.LIVE_METER.id());
            TomatoGUI.browseSavedHistory();
            assertEquals("runs", shell.selectedPage());
            assertEquals("Browse saved history shows the Feed", tomato.gui.runs.RunsTab.FEED, page.selectedTab());
            assertTrue("…on its Table view", page.feed().feed().tableShown());
            page.tabs().select(tomato.gui.runs.RunsTab.LIVE_METER.id());
            assertTrue(page.tabs().hide(tomato.gui.runs.RunsTab.FEED.id()));
            shell.select("chat");
            TomatoGUI.browseSavedHistory();
            assertEquals("runs", shell.selectedPage());
            assertEquals(tomato.gui.runs.RunsTab.FEED, page.selectedTab());
            assertFalse("A hidden Feed is shown again", page.tabs().hiddenIds().contains(tomato.gui.runs.RunsTab.FEED.id()));
        });
    }

    /**
     * A hidden tab is detached from the tree, so closing walks the tabs' contents too: hidden Feed, Live meter and Resources & buffs tabs still release their saved readers.
     */
    @Test public void hiddenFeedAndLiveMeterTabsStillCloseTheirWorkspaces() throws Exception {
        ArchiveWorkspace<?,?,?> runs = workspace("runs"), combat = workspace("combat");
        SwingUtilities.invokeAndWait(() -> { runs.showSaved(); combat.showSaved(); });
        await(() -> !runs.loading() && runs.displayedPage() != null && !combat.loading() && combat.displayedPage() != null);
        java.util.List<ArchivePage<?>> pages = edt(() -> Arrays.asList(runs.displayedPage(), combat.displayedPage()));
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.runs.RunsDpsPage page = runsDps();
            JTabbedPane nested = page.tabs().component();
            assertTrue(((tomato.gui.kit.CustomizableTabs) nested.getClientProperty(tomato.gui.kit.CustomizableTabs.class)).hide("resources"));
            assertTrue(page.tabs().hide(tomato.gui.runs.RunsTab.FEED.id()));
            assertTrue(page.tabs().hide(tomato.gui.runs.RunsTab.LIVE_METER.id()));
            assertNull("The hidden Feed is detached", named(shell, "runs-session-view", ArchiveWorkspace.class));
            assertNull("…and so is the hidden Resources workspace", named(shell, "combat-session-view", ArchiveWorkspace.class));
        });
        for (ArchivePage<?> page : pages) try (ArchiveResult.Lease<?> open = page.lease()) { assertNotNull(open); }
        gui.closeWorkspace();
        for (ArchivePage<?> page : pages) {
            try (ArchiveResult.Lease<?> unexpected = page.lease()) { fail("A hidden tab's workspace kept its result owner"); }
            catch (java.io.IOException expected) { /* Owner closed. */ }
        }
    }

    /**
     * P5b Task 12: the Dungeons tab hosts the per-dungeon cards over saved history, and its route brings it forward (a hidden tab
     * too). "Show runs" (Enter on a card) shows the Feed's cards filtered to that canonical dungeon, "Open best run" (the card's
     * menu) opens that exact run's recap on its recording, and Back from either returns to Dungeons.
     */
    @Test public void dungeonsShowRunsAndOpenBestRunLeaveTheTabAndBackReturnsToDungeons() throws Exception {
        tomato.gui.runs.RunFixtures.writeMixed(store.directory());   // synthetic saved sessions beside this app run's
        tomato.gui.runs.DungeonsView dungeons = edt(() -> {
            tomato.gui.runs.RunsDpsPage page = runsDps();
            tomato.gui.runs.DungeonsView view = named(named(page, "runs-dungeons-slot", JPanel.class), "dungeons-view", tomato.gui.runs.DungeonsView.class);
            assertNotNull("The Dungeons tab hosts the cards", view);
            tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();
            tomato.gui.route.Route route = tomato.gui.route.Route.to(tomato.gui.route.Destination.RUNS)
                .withPayload(tomato.gui.runs.RunsFocus.of(tomato.gui.runs.RunsTab.DUNGEONS));
            shell.select("chat");
            assertTrue(page.tabs().hide(tomato.gui.runs.RunsTab.DUNGEONS.id()));
            assertTrue(navigator.open(route));
            assertEquals("runs", shell.selectedPage());
            assertEquals("The Dungeons route brings the tab forward", tomato.gui.runs.RunsTab.DUNGEONS, page.selectedTab());
            assertFalse("…a hidden one too (explicit navigation)", page.tabs().hiddenIds().contains(tomato.gui.runs.RunsTab.DUNGEONS.id()));
            assertTrue(navigator.back()); assertEquals("chat", shell.selectedPage());
            assertTrue(navigator.open(route));
            view.refresh();   // the tab reads when it shows; this shell has no window
            return view;
        });
        @SuppressWarnings("unchecked") tomato.gui.kit.TileList<tomato.gui.runs.DungeonCardModel> cards =
            edt(() -> (tomato.gui.kit.TileList<tomato.gui.runs.DungeonCardModel>) named(dungeons, "dungeons-cards", tomato.gui.kit.TileList.class));
        await(() -> cards.getModel().getSize() == 5);
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.runs.RunsDpsPage page = runsDps();
            tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();
            cards.setSelectedIndex(cards.items().indexOf(card(cards, tomato.gui.runs.RunFixtures.CRONUS)));
            cards.getActionMap().get(tomato.gui.kit.TileList.OPEN).actionPerformed(null);   // Enter: Show runs
            assertEquals("runs", shell.selectedPage());
            assertEquals("Show runs opens the Feed", tomato.gui.runs.RunsTab.FEED, page.selectedTab());
            assertFalse("…its feed, not a recap", page.feed().recapShown());
            assertFalse("…on the cards", page.feed().feed().tableShown());
            assertEquals("…filtered to that canonical dungeon only", new tomato.gui.runs.RunFeedQuery("", Collections.emptySet(),
                tomato.gui.runs.RunFixtures.CRONUS), page.feed().feed().query());
            assertTrue(navigator.back());
            assertEquals("runs", shell.selectedPage());
            assertEquals("Back returns to Dungeons", tomato.gui.runs.RunsTab.DUNGEONS, page.selectedTab());

            tomato.gui.runs.DungeonCardModel halls = card(cards, "Lost Halls");
            assertEquals(tomato.gui.runs.RunFixtures.C1, halls.bestRun());
            named(cardMenu(dungeons, halls), "dungeons-card-best", JMenuItem.class).doClick();   // Open best run
            assertEquals(tomato.gui.runs.RunsTab.FEED, page.selectedTab());
            assertTrue("Open best run opens the recap", page.feed().recapShown());
            assertEquals("…of that exact run", tomato.gui.runs.RunFixtures.C1, ((tomato.gui.runs.RunRecapView) page.feed().recap()).ref());
            assertTrue(navigator.back());
            assertEquals("runs", shell.selectedPage());
            assertEquals("Back returns to Dungeons", tomato.gui.runs.RunsTab.DUNGEONS, page.selectedTab());
        });
    }

    /**
     * The Analyst Analysis view of Dungeons is the saved-only Dungeons analysis workspace over the shell's history store, built
     * only when it first shows, and closing the shell releases it with the page.
     */
    @Test public void theDungeonsAnalysisIsBuiltOnFirstUseOverSavedHistoryAndClosedWithThePage() throws Exception {
        tomato.gui.kit.DisplayModeModel mode = tomato.gui.kit.DisplayModeModel.application();
        tomato.gui.kit.DisplayModeModel.Mode before = mode.mode();
        String saved = PropertiesManager.getProperty(tomato.gui.kit.DisplayModeModel.KEY);
        try {
            ArchiveWorkspace<?,?,?> analysis = edt(() -> {
                tomato.gui.runs.DungeonsView view = named(runsDps(), "dungeons-view", tomato.gui.runs.DungeonsView.class);
                assertNull("Nothing is built before the Analysis view is used", find(view, ArchiveWorkspace.class));
                mode.set(tomato.gui.kit.DisplayModeModel.Mode.ANALYST);
                view.analyze("Lost Halls");
                ArchiveWorkspace<?,?,?> built = find(view, ArchiveWorkspace.class);
                assertNotNull("Analyze builds the Dungeons analysis", built);
                assertTrue("…a saved-only workspace", built.savedOnly());
                assertEquals(tomato.gui.stats.DungeonAnalysis.NAME + "-session-view", built.getName());
                return built;
            });
            await(() -> !analysis.loading() && analysis.displayedPage() != null);
            ArchivePage<?> page = edt(analysis::displayedPage);
            try (ArchiveResult.Lease<?> open = page.lease()) { assertNotNull(open); }
            gui.closeWorkspace();
            try (ArchiveResult.Lease<?> unexpected = page.lease()) { fail("The Dungeons analysis kept its result owner"); }
            catch (java.io.IOException expected) { /* Owner closed. */ }
        } finally {
            SwingUtilities.invokeAndWait(() -> mode.set(before));
            PropertiesManager.setProperties(tomato.gui.kit.DisplayModeModel.KEY, saved == null ? "" : saved);
        }
    }

    /** Without saved history the Dungeons tab says so, and its Analysis view is a note: the analysis workspace is never built. */
    @Test public void withoutSavedHistoryTheDungeonsTabSaysSoAndNeverBuildsItsAnalysis() throws Exception {
        gui.closeWorkspace(); SwingUtilities.invokeAndWait(() -> shell.removeNotify());
        Field field = AppHistory.class.getDeclaredField("store"); field.setAccessible(true); field.set(null, null);
        tomato.gui.kit.DisplayModeModel mode = tomato.gui.kit.DisplayModeModel.application();
        tomato.gui.kit.DisplayModeModel.Mode before = mode.mode();
        String saved = PropertiesManager.getProperty(tomato.gui.kit.DisplayModeModel.KEY);
        try {
            SwingUtilities.invokeAndWait(() -> {
                buildShell();
                tomato.gui.runs.DungeonsView view = named(runsDps(), "dungeons-view", tomato.gui.runs.DungeonsView.class);
                assertNotNull(view);
                view.refresh();
                tomato.gui.kit.EmptyState empty = named(view, "dungeons-empty", tomato.gui.kit.EmptyState.class);
                assertNotNull(empty);
                assertTrue(empty.getParent().isVisible());
                assertEquals("Saved history is unavailable", empty.getAccessibleContext().getAccessibleName());
                mode.set(tomato.gui.kit.DisplayModeModel.Mode.ANALYST);
                view.analyze("Lost Halls");
                assertTrue(view.analysisShown());
                assertNull("No analysis workspace without saved history", find(view, ArchiveWorkspace.class));
                assertNotNull("…a note says why", named(view, "dungeon-analysis-unavailable", tomato.gui.kit.EmptyState.class));
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> mode.set(before));
            PropertiesManager.setProperties(tomato.gui.kit.DisplayModeModel.KEY, saved == null ? "" : saved);
        }
    }

    /**
     * Each Recordings open lands where the recording lives: the live row and a recording in memory with a unique ID open the Live
     * meter through the navigator (Back returns to Recordings), a recording whose ID another entry shares shows its entry in the
     * Live meter, and a saved summary linked to its run opens that run's recap on the recording (Back returns to Recordings).
     */
    @Test public void eachRecordingsOpenLandsOnItsTabAndBackReturnsToRecordings() throws Exception {
        String session = tomato.gui.glance.home.HomeHistoryFixture.id("shell-recordings");
        tomato.history.link.VisitRef run = new tomato.history.link.VisitRef(session, "v1");
        long now = System.currentTimeMillis();
        tomato.gui.runs.RunFixtures.session(store.directory(), session, now - 3_600_000, now - 1_800_000);
        tomato.history.encounter.CombatFixtures.writeRecord(store.directory(), session, tomato.gui.runs.RunFixtures.record("r-saved-summary", run, 1, 60, 5_000, 3_000));
        tomato.history.link.VisitRef live = new tomato.history.link.VisitRef(store.currentId(), "journal:1");
        tomato.backend.data.DpsData unique = tomato.history.encounter.CombatFixtures.typical(live, now - 600_000, 10, 2, 3);
        tomato.backend.data.DpsData twin = tomato.history.encounter.CombatFixtures.typical(live, now - 300_000, 10, 2, 3);
        tomato.backend.data.DpsData twinCopy = twin.getSaveFile(false);   // an in-memory copy: the same recording ID, another entry
        tomato.gui.dps.DungeonListGUI library = edt(() -> {
            tomato.gui.dps.DpsGUI dps = find(shell, tomato.gui.dps.DpsGUI.class);
            dps.encounters().captured(new tomato.backend.data.DpsData[]{unique, twin, twinCopy});
            tomato.gui.runs.RunsDpsPage page = runsDps();
            tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();
            tomato.gui.dps.DungeonListGUI recordings = find(named(page, "runs-recordings-slot", JPanel.class), tomato.gui.dps.DungeonListGUI.class);
            JTable table = named(recordings, "saved-encounters", JTable.class);
            dps.showEncounter(dps.encounters().find(unique).id);

            // The live row (listed before any read): the meter follows the live fight, in the Live meter tab.
            shell.select("chat");
            assertTrue(navigator.open(tomato.gui.route.Route.to(tomato.gui.route.Destination.RUNS)
                .withPayload(tomato.gui.runs.RunsFocus.of(tomato.gui.runs.RunsTab.RECORDINGS))));
            open(recordings, table, rowOf(table, 2, "Live"));
            assertEquals("runs", shell.selectedPage());
            assertEquals("The live row opens the Live meter", tomato.gui.runs.RunsTab.LIVE_METER, page.selectedTab());
            assertNull("…following the live fight", dps.currentEncounterId());
            assertTrue(navigator.back());
            assertEquals("runs", shell.selectedPage());
            assertEquals("Back returns to Recordings", tomato.gui.runs.RunsTab.RECORDINGS, page.selectedTab());
            named(recordings, "encounter-scope-1", AbstractButton.class).doClick();   // All sessions: reads now
            return recordings;
        });
        JTable table = edt(() -> named(library, "saved-encounters", JTable.class));
        await(() -> rowOf(table, 7, "Saved history") >= 0 && rowOf(table, 1, entry(unique)) >= 0);
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.dps.DpsGUI dps = find(shell, tomato.gui.dps.DpsGUI.class);
            tomato.gui.runs.RunsDpsPage page = runsDps();
            tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();

            open(library, table, rowOf(table, 1, entry(unique)));
            assertEquals("A recording in memory with a unique ID opens in the Live meter", tomato.gui.runs.RunsTab.LIVE_METER, page.selectedTab());
            assertEquals("…that recording", dps.encounters().find(unique).id, dps.currentEncounterId());
            assertTrue(navigator.back());
            assertEquals("Back returns to Recordings", tomato.gui.runs.RunsTab.RECORDINGS, page.selectedTab());

            int shared = rowOf(table, 1, entry(twin));
            if (shared < 0) shared = rowOf(table, 1, entry(twinCopy));
            assertTrue("The shared recording is listed", shared >= 0);
            open(library, table, shared);
            assertEquals("A shared recording ID shows its entry in the Live meter", tomato.gui.runs.RunsTab.LIVE_METER, page.selectedTab());
            assertTrue(dps.currentEncounterId(), Arrays.asList(dps.encounters().find(twin).id, dps.encounters().find(twinCopy).id)
                .contains(dps.currentEncounterId()));

            page.tabs().select(tomato.gui.runs.RunsTab.RECORDINGS.id());
            open(library, table, rowOf(table, 7, "Saved history"));
            assertEquals("runs", shell.selectedPage());
            assertEquals("A linked summary opens its run's recap", tomato.gui.runs.RunsTab.FEED, page.selectedTab());
            assertTrue(page.feed().recapShown());
            assertEquals("…of that exact run", run, ((tomato.gui.runs.RunRecapView) page.feed().recap()).ref());
            assertTrue(navigator.back());
            assertEquals("runs", shell.selectedPage());
            assertEquals("Back returns to Recordings", tomato.gui.runs.RunsTab.RECORDINGS, page.selectedTab());
        });
    }

    /**
     * Search finds the Dungeons tab by its contents ({@code dungeons.open}) without making the other entries ambiguous; Back
     * returns. (The Statistics page's banner and Dungeons' "Open Statistics" link went with the page, P6a Task 12.)
     */
    @Test public void searchOpensDungeonsAndBackReturns() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.search.ActionRegistry registry = tomato.gui.search.ActionRegistry.application();
            tomato.gui.runs.RunsDpsPage page = runsDps();
            tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();
            assertEquals(1, registry.search("dungeons.open").size());
            tomato.gui.search.ActionDescriptor dungeons = registry.search("dungeons.open").get(0);
            assertEquals("Dungeons (per-dungeon cards, session comparison, cohorts)", dungeons.label);
            assertEquals("Runs & DPS › Dungeons", dungeons.location);
            assertEquals("Found by what it holds", Collections.singletonList("dungeons.open"), ids(registry.search("cohorts")));
            assertEquals(Collections.singletonList("dungeons.open"), ids(registry.search("session comparison")));
            // The entries other tests search for stay unambiguous.
            assertEquals(1, registry.search("build.open").size());
            assertEquals(1, registry.search("combat.settings").size());
            assertEquals("combat.settings", registry.search("full detail retention").get(0).id);
            assertEquals(Collections.singletonList("dps.meter"), ids(registry.search("dps logger")));
            assertEquals(Collections.singletonList("dps.meter"), ids(registry.search("alt+8")));
            assertEquals(Collections.singletonList("dps.recordings"), ids(registry.search("encounter library")));
            assertTrue("The Statistics entry went with the page", registry.search("statistics.open").isEmpty());

            shell.select("chat");
            assertTrue(dungeons.open());
            assertEquals("runs", shell.selectedPage());
            assertEquals(tomato.gui.runs.RunsTab.DUNGEONS, page.selectedTab());
            assertTrue(navigator.back()); assertEquals("chat", shell.selectedPage());
        });
    }

    // ---- P6a Task 11: the Loot page (Highlights · Explore) and the new sections' wiring ----

    /** Loot's page. EDT. */
    private tomato.gui.loot.LootPage lootPage() {
        tomato.gui.loot.LootPage page = named(shell, "loot-page", tomato.gui.loot.LootPage.class);
        assertNotNull("The loot page is the Loot page", page);
        return page;
    }
    /** A private field of {@code owner} (the Highlights hooks, the menu bar, LootCapture's game data). */
    private static Object field(Object owner, String name) {
        try { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    @SuppressWarnings("unchecked")
    private static <T> java.util.function.Consumer<T> hook(tomato.gui.loot.LootHighlights highlights, String name) {
        return (java.util.function.Consumer<T>) field(highlights, name);
    }
    /** The ⋯ entry named {@code name} of Loot highlights. */
    private static JMenuItem more(tomato.gui.loot.LootHighlights highlights, String name) {
        tomato.gui.kit.OverflowMenu menu = named(highlights, "loot-highlights-more", tomato.gui.kit.OverflowMenu.class);
        for (Component item : menu.menu().getComponents()) if (item instanceof JMenuItem && name.equals(item.getName())) return (JMenuItem) item;
        return null;
    }
    private static JMenuItem menuItem(MenuElement root, String text) {
        for (MenuElement element : root.getSubElements()) {
            if (element instanceof JMenuItem && text.equals(((JMenuItem) element).getText())) return (JMenuItem) element;
            JMenuItem found = menuItem(element, text);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * The loot page is Loot with the tabs Highlights · Explore (P6a): Highlights is the view over saved history, Explore the Loot
     * workspace whose live card attaches to the app's loot capture (which the shell binds to the game data), and Highlights' ⋯
     * holds "Loot sharing status…" and "Loot filter settings…". A plain Loot page opens on Highlights; Explore still holds the
     * workspace every Loot route and the S8 check reach.
     */
    @Test public void lootIsAPageWithHighlightsAndExploreOverTheAppsLootCapture() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.loot.LootPage page = lootPage();
            JTabbedPane tabs = named(page, "loot-tabs", JTabbedPane.class);
            assertEquals(Arrays.asList("Highlights", "Explore"), Arrays.asList(tabs.getTitleAt(0), tabs.getTitleAt(1)));
            assertEquals("Loot opens on Highlights", tomato.gui.loot.LootTab.HIGHLIGHTS, page.selectedTab());
            tomato.gui.loot.LootHighlights highlights = named(page, "loot-highlights", tomato.gui.loot.LootHighlights.class);
            assertSame(highlights, tabs.getComponentAt(0));
            assertSame("Explore is the Loot workspace", workspace("loot"), tabs.getComponentAt(1));
            tomato.gui.stats.LootDashboard live = find(workspace("loot"), tomato.gui.stats.LootDashboard.class);
            assertSame("Explore's live card is attached to the app's loot capture",
                field(tomato.gui.stats.LootCapture.get().feed(), "state"), field(live, "state"));
            assertSame("The shell binds the loot capture to the game data", data, field(tomato.gui.stats.LootCapture.get(), "data"));
            JMenuItem sharing = more(highlights, "loot-sharing-status"), filters = more(highlights, "loot-filter-settings");
            assertNotNull(sharing); assertEquals("Loot sharing status…", sharing.getText());
            assertNotNull(filters); assertEquals("Loot filter settings…", filters.getText());
            shell.select("loot");
            filters.doClick();
            assertEquals("settings", shell.selectedPage());
            assertEquals(tomato.gui.settings.SettingsPage.LOOT_FILTERS, find(shell, tomato.gui.settings.SettingsPage.class).currentSection());
            assertTrue(tomato.gui.route.Navigator.current().back());
            assertEquals("The Highlights settings action returns to Loot", "loot", shell.selectedPage());
            assertEquals(tomato.gui.loot.LootTab.HIGHLIGHTS, page.selectedTab());
        });
    }

    /**
     * A Loot visit route (the recap's and the workbench's "Open Loot") brings Explore forward with the workspace's drill, and Back
     * returns to the tab it left, from Loot's own Highlights or from another page.
     */
    @Test public void aLootVisitRouteBringsExploreAndBackReturnsToTheTabLeft() throws Exception {
        tomato.history.link.VisitRef visit = new tomato.history.link.VisitRef(store.currentId(), "journal:1");
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.loot.LootPage page = lootPage();
            tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();
            shell.select("loot");
            assertEquals(tomato.gui.loot.LootTab.HIGHLIGHTS, page.selectedTab());
            assertTrue(navigator.open(tomato.gui.route.Route.to(tomato.gui.route.Destination.LOOT).withVisit(visit)));
            assertEquals("loot", shell.selectedPage());
            assertEquals("A visit route brings Explore forward", tomato.gui.loot.LootTab.EXPLORE, page.selectedTab());
            assertEquals("…on the exact visit", "journal:1", ((tomato.gui.stats.LootQuery.Facets) workspace("loot").state().query.facets()).visitId);
            assertTrue(navigator.back());
            assertEquals("loot", shell.selectedPage());
            assertEquals("Back returns to Highlights", tomato.gui.loot.LootTab.HIGHLIGHTS, page.selectedTab());
            shell.select("chat");
            assertTrue(navigator.open(tomato.gui.route.Route.to(tomato.gui.route.Destination.LOOT).withVisit(visit)));
            assertEquals(tomato.gui.loot.LootTab.EXPLORE, page.selectedTab());
            assertTrue(navigator.back());
            assertEquals("chat", shell.selectedPage());
            assertTrue("Each Loot tab has a route", navigator.canOpen(tomato.gui.route.Route.to(tomato.gui.route.Destination.LOOT)
                .withPayload(new tomato.gui.loot.LootFocus(tomato.gui.loot.LootTab.HIGHLIGHTS))));
        });
    }

    /**
     * Highlights' hooks go through the navigator: a by-dungeon cell opens Explore on saved loot filtered to that dungeon over the
     * Highlights window (Today: every session, kept to the local day), Unknown area opens Explore as it is, and a notable drop
     * opens its exact run's recap; Back returns to Highlights after each.
     */
    @Test public void highlightsDungeonCellsAndNotableDropsOpenExploreAndTheRunAndBackReturns() throws Exception {
        tomato.history.link.VisitRef visit = new tomato.history.link.VisitRef(store.currentId(), "journal:1");
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.loot.LootPage page = lootPage();
            tomato.gui.loot.LootHighlights highlights = named(page, "loot-highlights", tomato.gui.loot.LootHighlights.class);
            tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();
            ArchiveWorkspace<?,?,?> loot = workspace("loot");
            shell.select("loot");
            hook(highlights, "openDungeon").accept("Lost Halls");
            assertEquals("loot", shell.selectedPage());
            assertEquals(tomato.gui.loot.LootTab.EXPLORE, page.selectedTab());
            assertTrue("Explore shows saved loot", loot.state().archive);
            tomato.gui.stats.LootQuery.Facets facets = (tomato.gui.stats.LootQuery.Facets) loot.state().query.facets();
            assertEquals(Collections.singleton("Lost Halls"), facets.dungeons);
            assertEquals(tomato.gui.stats.LootQuery.View.ITEMS, facets.view);
            assertEquals("Today (the default window) reads every session", SessionStore.ALL, loot.state().query.scope());
            assertNotNull("…kept to the local day", loot.state().query.bounds().from);
            assertTrue(navigator.back());
            assertEquals("loot", shell.selectedPage());
            assertEquals("Back returns to Highlights", tomato.gui.loot.LootTab.HIGHLIGHTS, page.selectedTab());

            String before = loot.state().query.toJson().toString();
            hook(highlights, "openDungeon").accept(null);
            assertEquals("Unknown area opens Explore", tomato.gui.loot.LootTab.EXPLORE, page.selectedTab());
            assertEquals("…without a dungeon filter of its own", before, loot.state().query.toJson().toString());
            assertTrue(navigator.back());
            assertEquals(tomato.gui.loot.LootTab.HIGHLIGHTS, page.selectedTab());

            hook(highlights, "openRun").accept(visit);
            assertEquals("A notable drop opens its run", "runs", shell.selectedPage());
            tomato.gui.runs.RunsPage runs = named(shell, "runs-page", tomato.gui.runs.RunsPage.class);
            assertTrue(runs.recapShown());
            assertEquals("…the exact visit", visit, ((tomato.gui.runs.RunRecapView) runs.recap()).ref());
            assertTrue(navigator.back());
            assertEquals("loot", shell.selectedPage());
            assertEquals(tomato.gui.loot.LootTab.HIGHLIGHTS, page.selectedTab());
        });
    }

    /** The new search entries: found by their words, unambiguous beside the old ones, each opening its place through the navigator. */
    @Test public void searchFindsLootHighlightsExploreFameHistoryAndTheNewSettingsSections() throws Exception {
        tomato.gui.kit.DisplayModeModel.Mode mode = edt(() -> tomato.gui.kit.DisplayModeModel.application().mode());
        try {
            SwingUtilities.invokeAndWait(() -> {
                tomato.gui.kit.DisplayModeModel.application().set(tomato.gui.kit.DisplayModeModel.Mode.SIMPLE);
                tomato.gui.search.ActionRegistry registry = tomato.gui.search.ActionRegistry.application();
                tomato.gui.route.Navigator navigator = tomato.gui.route.Navigator.current();
                tomato.gui.loot.LootPage page = lootPage();
                tomato.gui.settings.SettingsPage settings = find(shell, tomato.gui.settings.SettingsPage.class);
                String[][] entries = {
                    {"loot.highlights", "Loot highlights", "Loot › Highlights"},
                    {"loot.explore", "Explore loot", "Loot › Explore"},
                    {"fame.history", "Character fame history", "Characters › Fame history (Analyst)"},
                    {"fame.file", "Open fame session file…", "Characters › Fame history › Open fame session file…"},
                    {"loot.filters", "Loot filters", "Settings › Loot filters"},
                    {"chat.settings", "Chat settings (filters and saving)", "Settings › Chat"},
                    {"about.open", "About RealmShark", "Settings › About"}};
                for (String[] entry : entries) {
                    assertEquals(entry[0], Collections.singletonList(entry[0]), ids(registry.search(entry[0])));
                    assertEquals(entry[1], registry.search(entry[0]).get(0).label);
                    assertEquals(entry[2], registry.search(entry[0]).get(0).location);
                }
                assertEquals("Found by what it shows", Collections.singletonList("loot.highlights"), ids(registry.search("notable drops")));
                assertEquals(Collections.singletonList("loot.explore"), ids(registry.search("explore loot")));
                assertEquals(Collections.singletonList("fame.history"), ids(registry.search("fame gain")));
                assertEquals("Both fame entries answer their words", Arrays.asList("fame.history", "fame.file"), ids(registry.search("character fame history")));
                // The entries other tests search for stay unambiguous.
                assertEquals(1, registry.search("build.open").size());
                assertEquals(1, registry.search("combat.settings").size());
                assertEquals("combat.settings", registry.search("full detail retention").get(0).id);
                assertEquals(Collections.singletonList("dps.meter"), ids(registry.search("dps logger")));
                assertEquals(Collections.singletonList("dps.meter"), ids(registry.search("alt+8")));
                assertEquals(Collections.singletonList("dps.recordings"), ids(registry.search("encounter library")));
                assertEquals(Collections.singletonList("dungeons.open"), ids(registry.search("cohorts")));
                assertEquals(Collections.singletonList("dungeons.open"), ids(registry.search("session comparison")));
                assertTrue("The Statistics entry went with the page", registry.search("statistics.open").isEmpty());
                assertEquals(1, registry.search("appearance.settings").size());
                assertEquals(1, registry.search("plans.characters").size());

                shell.select("chat");
                assertTrue(registry.search("loot.explore").get(0).open());
                assertEquals("loot", shell.selectedPage()); assertEquals(tomato.gui.loot.LootTab.EXPLORE, page.selectedTab());
                assertTrue(navigator.back()); assertEquals("chat", shell.selectedPage());
                assertTrue(registry.search("loot.highlights").get(0).open());
                assertEquals("loot", shell.selectedPage()); assertEquals(tomato.gui.loot.LootTab.HIGHLIGHTS, page.selectedTab());
                assertTrue(navigator.back()); assertEquals("chat", shell.selectedPage());

                String[][] sections = {{"loot.filters", tomato.gui.settings.SettingsPage.LOOT_FILTERS}, {"chat.settings", tomato.gui.settings.SettingsPage.CHAT},
                    {"about.open", tomato.gui.settings.SettingsPage.ABOUT}};
                for (String[] section : sections) {
                    shell.select("chat");
                    assertTrue(registry.search(section[0]).get(0).open());
                    assertEquals(section[0], "settings", shell.selectedPage());
                    assertEquals(section[0], section[1], settings.currentSection());
                    assertTrue(navigator.back());
                    assertEquals("Settings search creates a Back entry", "chat", shell.selectedPage());
                }

                JTabbedPane characters = named(shell, "characters-tabs", JTabbedPane.class);
                shell.select("chat");
                assertTrue(registry.search("fame.history").get(0).open());
                assertEquals("In Simple it opens Characters", "characters", shell.selectedPage());
                assertEquals("Roster", characters.getTitleAt(characters.getSelectedIndex()));
                assertTrue(navigator.back()); assertEquals("chat", shell.selectedPage());
                tomato.gui.kit.DisplayModeModel.application().set(tomato.gui.kit.DisplayModeModel.Mode.ANALYST);
                assertTrue(registry.search("fame.history").get(0).open());
                assertEquals("characters", shell.selectedPage());
                assertEquals("In Analyst it shows Fame history", "Fame history", characters.getTitleAt(characters.getSelectedIndex()));
                assertNotNull("…built on its first show", named(shell, "character-fame-view", JComponent.class));
                assertTrue(navigator.back()); assertEquals("chat", shell.selectedPage());
            });
        } finally { SwingUtilities.invokeAndWait(() -> tomato.gui.kit.DisplayModeModel.application().set(mode)); }
    }

    /** With the Settings hook the menu's Loot filter settings…, Chat settings… and About open their Settings sections. */
    @Test public void theMenuOpensTheLootFiltersChatAndAboutSections() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JMenuBar bar = (JMenuBar) field(gui, "jMenuBar");
            tomato.gui.settings.SettingsPage settings = find(shell, tomato.gui.settings.SettingsPage.class);
            String[][] entries = {{"Loot filter settings…", tomato.gui.settings.SettingsPage.LOOT_FILTERS},
                {"Chat settings…", tomato.gui.settings.SettingsPage.CHAT}, {"About", tomato.gui.settings.SettingsPage.ABOUT}};
            for (String[] entry : entries) {
                shell.select("chat");
                JMenuItem item = menuItem(bar, entry[0]);
                assertNotNull(entry[0] + " is in the menu", item);
                item.doClick();
                assertEquals(entry[0], "settings", shell.selectedPage());
                assertEquals(entry[0], entry[1], settings.currentSection());
                assertTrue(tomato.gui.route.Navigator.current().back());
                assertEquals("Settings menu creates a Back entry", "chat", shell.selectedPage());
            }
            assertNotNull("Settings hosts Loot filters", named(settings, "settings-loot-filters", JComponent.class));
            assertNotNull("…Chat", named(settings, "settings-chat", JComponent.class));
            assertNotNull("…and About", named(settings, "settings-about", JComponent.class));
        });
    }

    /** Home's Notable loot tile opens Loot › Highlights through the navigator, even from Explore, and Back returns Home. */
    @Test public void homesNotableLootTileOpensHighlightsAndBackReturnsHome() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.glance.home.HomePage home = find(shell, tomato.gui.glance.home.HomePage.class);
            home.apply(tomato.gui.glance.home.HomeModels.populated(System.currentTimeMillis()));
            tomato.gui.loot.LootPage page = lootPage();
            page.bring(tomato.gui.loot.LootTab.EXPLORE);
            shell.select("home");
            tomato.gui.kit.StatTile tile = named(home, "home-tile-loot", tomato.gui.kit.StatTile.class);
            assertNotNull("The tile is activatable", tile.getActionMap().get("open-tile"));
            tile.getActionMap().get("open-tile").actionPerformed(null);
            assertEquals("loot", shell.selectedPage());
            assertEquals("Notable loot opens Highlights", tomato.gui.loot.LootTab.HIGHLIGHTS, page.selectedTab());
            assertTrue(tomato.gui.route.Navigator.current().back());
            assertEquals("Back returns Home", "home", shell.selectedPage());
        });
    }

    /**
     * Closing the app reaches Loot's hidden Explore tab (its workspace releases its result) and closes Loot highlights, and the
     * Fame history workspace, built in Analyst, is released while Simple detaches its tab.
     */
    @Test public void closingReachesAHiddenExploreTabHighlightsAndFameHistory() throws Exception {
        tomato.gui.kit.DisplayModeModel.Mode mode = edt(() -> tomato.gui.kit.DisplayModeModel.application().mode());
        try {
            ArchiveWorkspace<?,?,?> loot = workspace("loot");
            SwingUtilities.invokeAndWait(() -> {
                tomato.gui.kit.DisplayModeModel.application().set(tomato.gui.kit.DisplayModeModel.Mode.ANALYST);
                loot.showSaved();
                assertTrue("Analyst shows Fame history", find(shell, tomato.gui.character.CharacterPanelGUI.class).showFameHistory());
            });
            ArchiveWorkspace<?,?,?> fame = edt(() -> named(shell, "character-fame-session-view", ArchiveWorkspace.class));
            assertNotNull("Fame history's workspace is built", fame);
            await(() -> !loot.loading() && loot.displayedPage() != null && !fame.loading() && fame.displayedPage() != null);
            java.util.List<ArchivePage<?>> pages = edt(() -> Arrays.asList(loot.displayedPage(), fame.displayedPage()));
            tomato.gui.loot.LootHighlights highlights = edt(() -> named(shell, "loot-highlights", tomato.gui.loot.LootHighlights.class));
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(lootPage().tabs().hide(tomato.gui.loot.LootTab.EXPLORE.id()));
                assertNull("The hidden Explore tab is detached", named(shell, "loot-session-view", ArchiveWorkspace.class));
                tomato.gui.kit.DisplayModeModel.application().set(tomato.gui.kit.DisplayModeModel.Mode.SIMPLE);
                assertFalse("Simple has no Fame history to show", find(shell, tomato.gui.character.CharacterPanelGUI.class).showFameHistory());
                assertNull("Simple detaches Fame history", named(shell, "character-fame-session-view", ArchiveWorkspace.class));
            });
            for (ArchivePage<?> open : pages) try (ArchiveResult.Lease<?> lease = open.lease()) { assertNotNull(lease); }
            gui.closeWorkspace();
            for (ArchivePage<?> closed : pages) {
                try (ArchiveResult.Lease<?> unexpected = closed.lease()) { fail("A hidden tab's workspace kept its result owner"); }
                catch (java.io.IOException expected) { /* Owner closed. */ }
            }
            assertTrue("Loot highlights is closed", edt(() -> (Boolean) field(highlights, "closed")));
        } finally { SwingUtilities.invokeAndWait(() -> tomato.gui.kit.DisplayModeModel.application().set(mode)); }
    }

    /** The card named {@code canonical} among the loaded Dungeons cards. */
    private static tomato.gui.runs.DungeonCardModel card(tomato.gui.kit.TileList<tomato.gui.runs.DungeonCardModel> cards, String canonical) {
        for (tomato.gui.runs.DungeonCardModel card : cards.items()) if (card.canonical().equals(canonical)) return card;
        throw new AssertionError("No card " + canonical);
    }
    /** The card's menu (Show runs, Open best run, Analyze), as Shift+F10 or a right click opens it; this shell has no window. */
    private static JPopupMenu cardMenu(tomato.gui.runs.DungeonsView view, tomato.gui.runs.DungeonCardModel card) {
        try {
            Method menu = tomato.gui.runs.DungeonsView.class.getDeclaredMethod("cardMenu", tomato.gui.runs.DungeonCardModel.class);
            menu.setAccessible(true);
            return (JPopupMenu) menu.invoke(view, card);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    /** The first view row whose model {@code column} reads {@code value}, or -1. */
    private static int rowOf(JTable table, int column, String value) {
        for (int row = 0; row < table.getRowCount(); row++)
            if (value.equals(table.getModel().getValueAt(table.convertRowIndexToModel(row), column))) return row;
        return -1;
    }
    /** A recording's Entry column: its library entry's ID prefix. EDT. */
    private String entry(tomato.backend.data.DpsData recording) {
        return find(shell, tomato.gui.dps.DpsGUI.class).encounters().find(recording).id.substring(0, 8);
    }
    /** Selects {@code row} and presses Open (explicit opening; selection alone never switches the meter). */
    private static void open(tomato.gui.dps.DungeonListGUI library, JTable table, int row) {
        assertTrue("Row listed", row >= 0);
        table.setRowSelectionInterval(row, row);
        AbstractButton open = named(library, "encounter-open", AbstractButton.class);
        assertTrue(open.getText(), open.isEnabled());
        open.doClick();
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
            assertNotNull(find(shell, tomato.gui.stats.LootDashboard.class));
            TomatoGUI.browseSavedHistory(); assertEquals("runs", shell.selectedPage());
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
    /**
     * Whether an archive pin or result folder is still under {@code root}. The archive's cleanup worker deletes them while this
     * walks, and {@code Files.walk} throws an UncheckedIOException (NoSuchFileException) for an entry deleted between being
     * listed and being visited; such an entry is gone, so the walk skips it. Any other I/O failure still fails the test.
     */
    private static boolean hasArchiveScratch(Path root) throws Exception {
        boolean[] found = {false};
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            private FileVisitResult check(Path path) {
                String name = String.valueOf(path.getFileName());
                if (!name.startsWith("archive-pin-") && !name.startsWith("archive-result-")) return FileVisitResult.CONTINUE;
                found[0] = true;
                return FileVisitResult.TERMINATE;
            }
            @Override public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attributes) { return check(dir); }
            @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attributes) { return check(file); }
            @Override public FileVisitResult visitFileFailed(Path file, java.io.IOException failure) throws java.io.IOException { return gone(failure); }
            @Override public FileVisitResult postVisitDirectory(Path dir, java.io.IOException failure) throws java.io.IOException {
                return failure == null ? FileVisitResult.CONTINUE : gone(failure);
            }
            private FileVisitResult gone(java.io.IOException failure) throws java.io.IOException {
                if (failure instanceof NoSuchFileException) return FileVisitResult.CONTINUE;   // deleted since it was listed
                throw failure;
            }
        });
        return found[0];
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
