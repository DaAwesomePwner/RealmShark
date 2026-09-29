package tomato.gui.glance.character;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.Tomato;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournalTest;
import tomato.backend.data.LiveCharacter;
import tomato.backend.data.RosterDefinitions;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.chat.ChatGUI;
import tomato.gui.glance.home.HomeModels;
import tomato.gui.glance.home.HomePage;
import tomato.gui.kit.Banner;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.KitButton;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.myinfo.BuildRoute;
import tomato.gui.myinfo.MyInfoGUI;
import tomato.gui.route.*;
import tomato.gui.search.ActionRegistry;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.planning.PlanningStore;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;
import static tomato.gui.glance.character.SheetFixtures.*;

/**
 * Build on the character sheet: one MyInfoGUI, hosted in the sheet; every Build entry opens the sheet's Build tab, or with no
 * character the Characters list (P6a removed the Build pointer page).
 */
public class BuildTabTest {
    private static final String TABS = "ui.tabs.character";
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String savedTabs;

    // Opening the sheet's Build tab explicitly (sheet.open(key, "build")) may show() a previously hidden tab, which persists
    // the group's saved order/hidden set (CustomizableTabs.show -> save()); isolate it like CharacterSheetTest does.
    @Before public void rememberTabs() { savedTabs = PropertiesManager.getProperty(TABS); PropertiesManager.setProperties(TABS, ""); }
    @After public void restoreTabs() { PropertiesManager.setProperties(TABS, savedTabs == null ? "" : savedTabs); }

    @Test public void buildTabHostsOneMyInfoAndPointsElsewhereWhileAnotherCharacterPlays() throws Exception {
        TomatoData data = new TomatoData();
        try (AutoCloseable wizard = className(WIZARD, "Wizard")) { // resolves the pointer's class name without bundled game assets
        SwingUtilities.invokeAndWait(() -> {
            List<String> opened = new ArrayList<>();
            BuildTab tab = new BuildTab(opened::add);
            assertEquals("unhosted", tab.card());
            MyInfoGUI build = new MyInfoGUI(data);
            tab.host(build);
            tab.apply(model(record(), account(), live(ACCOUNT, 7, "Sharkbait", null)), KEY);
            assertEquals("This character is in game: Build itself", "build", tab.card());
            assertSame(tab, SwingUtilities.getAncestorOfClass(BuildTab.class, build));
            tab.apply(model(record(), account(), live(ACCOUNT, 8, "Ann", null)), ACCOUNT + ":8");
            assertEquals("other", tab.card());
            EmptyState other = named(tab, "character-build-other-state", EmptyState.class);
            assertEquals(BuildTab.POINTER, "Build shows the character you're playing");
            assertEquals(BuildTab.POINTER, other.getAccessibleContext().getAccessibleName());
            // Worded by class and character id, not name: the game's name stat is the account's, shared by every character, and
            // same-class characters (both fixtures are Wizards, #7 and #8) need the id to disambiguate.
            assertEquals("Your Wizard #8 is in game now.", other.getAccessibleContext().getAccessibleDescription());
            AbstractButton open = named(tab, "character-build-open-live", AbstractButton.class);
            assertEquals("Open Wizard #8's Build", open.getText());
            open.doClick();
            assertEquals(List.of(ACCOUNT + ":8"), opened);
            assertSame("MyInfoGUI stays parented in its card", tab, SwingUtilities.getAncestorOfClass(BuildTab.class, build));
            assertFalse("…but that card is not shown", named(tab, "character-build-host", JPanel.class).isVisible());
            tab.apply(model(record(), account(), null), null);
            assertEquals("Build describes nobody: the pointer, not an empty Build", "other", tab.card());
            EmptyState nobody = named(tab, "character-build-other-state", EmptyState.class);
            assertEquals("Start capture and enter the game with this character.", nobody.getAccessibleContext().getAccessibleDescription());
            assertEquals("Nobody is in game: nothing to open", 0, count(named(tab, "character-build-other", JPanel.class), KitButton.class));
        });
        }
    }

    @Test public void afterCaptureStopsBuildStaysOnTheLastCharactersSheetOnly() throws Exception {
        TomatoData data = new TomatoData();
        LiveCharacter live = new LiveCharacter();
        live.publish(live(ACCOUNT, 7, "Sharkbait", null));
        live.stop(NOW);
        assertNull("Capture stopped: nobody is in game", SheetModelBuilder.inGame(live, NOW + 1));
        assertEquals("…while Build still describes the last character", KEY, BuildTab.shownKey(live));
        CharacterJournal.CharacterRecord other = record(); other.key = ACCOUNT + ":8"; other.characterId = 8;
        SheetModel mine = model(record(), account(), SheetModelBuilder.inGame(live, NOW + 1));
        SheetModel theirs = model(other, account(), SheetModelBuilder.inGame(live, NOW + 1));
        SwingUtilities.invokeAndWait(() -> {
            BuildTab tab = new BuildTab(key -> fail("Nothing to open while nobody is in game"));
            tab.host(new MyInfoGUI(data));
            tab.apply(mine, BuildTab.shownKey(live));
            assertEquals("The last character's sheet still shows its Build", "build", tab.card());
            tab.apply(theirs, BuildTab.shownKey(live));
            assertEquals("Another sheet never shows the last character's Build", "other", tab.card());
            EmptyState state = named(tab, "character-build-other-state", EmptyState.class);
            assertEquals(BuildTab.POINTER, state.getAccessibleContext().getAccessibleName());
            assertEquals("Start capture and enter the game with this character.", state.getAccessibleContext().getAccessibleDescription());
            assertEquals(0, count(named(tab, "character-build-other", JPanel.class), KitButton.class));
        });
    }

    @Test public void openingAnotherCharacterNeverShowsThePreviousCharactersBuildWhileItLoads() throws Exception {
        TomatoData data = new TomatoData();
        try (CharacterJournal journal = new CharacterJournal(temp.newFolder().toPath().resolve("journal.json"))) {
            String mine = seed(journal), theirs = ACCOUNT + ":8";
            journal.observe(CharacterJournalTest.player("sheet-fixture", WIZARD), 8);
            data.liveCharacter.publish(live(ACCOUNT, 7, "Sharkbait", null)); // #7 is in game
            SheetContext context = new SheetContext(data, journal, RosterDefinitions::empty, DisplayModeModel.application(), () -> NOW, PlanningStore.shared());
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = new CharacterSheet(context);
                sheet.hostBuild(new MyInfoGUI(data));
                BuildTab tab = named(sheet, "character-build", BuildTab.class);
                sheet.open(mine, "build");
                await(sheet::ready);
                assertEquals("The character in game: Build itself", "build", tab.card());
                sheet.open(theirs, "build"); // its result arrives later, through the EDT
                assertFalse("#8's read has not arrived yet", sheet.ready());
                assertNotEquals("#7's Build never stays on screen while #8 loads", "build", tab.card());
                assertFalse("…and nothing offers to open anything yet", offersOpen(tab));
                await(sheet::ready);
                assertEquals("Once #8 loads: the pointer to the character in game", "other", tab.card());
                assertTrue(offersOpen(tab));
                sheet.open(mine, "build");
                assertFalse(sheet.ready());
                assertNotEquals("build", tab.card());
                assertFalse("#8's Open button does not stay clickable while #7 loads", offersOpen(tab));
                await(sheet::ready);
                assertEquals("build", tab.card());
            });
        }
    }

    @Test public void aFailedBuildNeverLeavesTheLastCharactersBuildOnScreen() throws Exception {
        TomatoData data = new TomatoData();
        AtomicBoolean failing = new AtomicBoolean();
        RosterDefinitions none = RosterDefinitions.empty();
        try (CharacterJournal journal = new CharacterJournal(temp.newFolder().toPath().resolve("journal.json"))) {
            String mine = seed(journal);
            data.liveCharacter.publish(live(ACCOUNT, 7, "Sharkbait", null));
            SheetContext context = new SheetContext(data, journal, () -> {
                if (failing.get() && "character-sheet".equals(Thread.currentThread().getName())) throw new IllegalStateException("Synthetic build failure");
                return none;
            }, DisplayModeModel.application(), () -> NOW, PlanningStore.shared());
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = new CharacterSheet(context);
                sheet.hostBuild(new MyInfoGUI(data));
                BuildTab tab = named(sheet, "character-build", BuildTab.class);
                sheet.open(mine, "build");
                await(sheet::ready);
                assertEquals("build", tab.card());
                failing.set(true);
                data.liveCharacter.publish(live(ACCOUNT, 8, "Ann", null)); // someone else is in game now: the sheet rebuilds, and fails
                sheet.refresh();
                Banner status = named(sheet, "character-sheet-status", Banner.class);
                await(status::warns);
                assertTrue(status.text(), status.text().contains("Synthetic build failure"));
                assertNotEquals("A failed build never leaves #7's Build on screen", "build", tab.card());
                assertFalse("…nor an Open button", offersOpen(tab));
            });
        }
    }

    /** A failed build says so in the Build tab (never "Loading…" beside the failure banner), and the retry picks the card again. */
    @Test public void aFailedBuildSaysUnavailableNotLoading() throws Exception {
        TomatoData data = new TomatoData();
        AtomicBoolean failing = new AtomicBoolean();
        RosterDefinitions none = RosterDefinitions.empty();
        try (CharacterJournal journal = new CharacterJournal(temp.newFolder().toPath().resolve("journal.json"));
             AutoCloseable wizard = className(WIZARD, "Wizard")) {
            String mine = seed(journal);
            data.liveCharacter.publish(live(ACCOUNT, 7, "Sharkbait", null));
            SheetContext context = new SheetContext(data, journal, () -> {
                if (failing.get() && "character-sheet".equals(Thread.currentThread().getName())) throw new IllegalStateException("Synthetic build failure");
                return none;
            }, DisplayModeModel.application(), () -> NOW, PlanningStore.shared());
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = new CharacterSheet(context);
                sheet.hostBuild(new MyInfoGUI(data));
                BuildTab tab = named(sheet, "character-build", BuildTab.class);
                sheet.open(mine, "build");
                await(sheet::ready);
                failing.set(true);
                data.liveCharacter.publish(live(ACCOUNT, 8, "Ann", null)); // the live revision moves: the sheet rebuilds, and fails
                sheet.refresh();
                await(named(sheet, "character-sheet-status", Banner.class)::warns);
                assertEquals("failed", tab.card());
                EmptyState failed = named(tab, "character-build-failed", EmptyState.class);
                assertTrue(failed.isVisible());
                assertEquals("Build unavailable", failed.getAccessibleContext().getAccessibleName());
                assertEquals("The character sheet could not be built. It retries automatically.", failed.getAccessibleContext().getAccessibleDescription());
                assertFalse("Not \"Loading…\" beside the failure banner", named(tab, "character-build-loading", EmptyState.class).isVisible());
                assertFalse("Neither MyInfoGUI…", named(tab, "character-build-host", JPanel.class).isVisible());
                assertFalse("…nor an Open button", offersOpen(tab));
                failing.set(false);
                sheet.refresh(); // the failure cleared the token: the next refresh retries
                await(() -> !"failed".equals(tab.card()));
                assertEquals("The retry applies: #8 is in game, so the pointer to it", "other", tab.card());
                assertTrue(offersOpen(tab));
            });
        }
        SwingUtilities.invokeAndWait(() -> {
            BuildTab bare = new BuildTab(key -> { });
            bare.failed();
            assertEquals("Without a hosted Build there is nothing to fail: still unhosted", "unhosted", bare.card());
        });
    }

    /** Whether {@code root} holds an enabled "Open X's Build" button. */
    private static boolean offersOpen(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton && "character-build-open-live".equals(child.getName()) && child.isEnabled()) return true;
            if (child instanceof Container && offersOpen((Container) child)) return true;
        }
        return false;
    }

    @Test public void buildRoutePrefersTheLiveCharacterInTheJournalThenTheMostRecent() throws Exception {
        TomatoData data = new TomatoData();
        CharacterJournal journal = new CharacterJournal(temp.newFolder().toPath().resolve("journal.json"));
        inject(data, journal);
        assertNull("No character at all: no key (the route opens Characters)", BuildRoute.key(data));
        String recent = seed(journal);
        assertEquals(recent, BuildRoute.key(data));
        data.liveCharacter.publish(live(ACCOUNT, 8, "Ann", null));
        assertEquals("A live character missing from the journal is skipped", recent, BuildRoute.key(data));
        journal.observe(CharacterJournalTest.player("sheet-fixture", WIZARD), 8);
        assertEquals(ACCOUNT + ":8", BuildRoute.key(data));
        assertEquals(Destination.CHARACTER_SHEET, BuildRoute.sheet(recent).destination);
        assertEquals(new SheetFocus(recent, "build"), BuildRoute.sheet(recent).payload);
    }

    @Test public void theWorkspaceHostsOneBuildAndEveryBuildEntryOpensTheSheetsBuildTab() throws Exception {
        try (Workspace w = new Workspace(temp, true)) {
            CharacterSheet[] sheet = new CharacterSheet[1];
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Exactly one Build page exists", 1, count(w.shell, MyInfoGUI.class));
                sheet[0] = find(w.shell, CharacterSheet.class);
                assertTrue("It lives in the sheet's Build tab", SwingUtilities.isDescendingFrom(find(w.shell, MyInfoGUI.class),
                    named(sheet[0], "character-build", BuildTab.class)));
                HomePage home = find(w.shell, HomePage.class);
                home.apply(HomeModels.populated(System.currentTimeMillis()));
                Navigator navigator = Navigator.current();
                for (String entry : BUILD_ENTRIES) {
                    w.shell.select("home");
                    openBuild(w.shell, home, navigator, entry);
                    assertEquals(entry + " opens Characters", "characters", w.shell.selectedPage());
                    assertEquals(entry + " opens this character's sheet", w.key, sheet[0].key());
                    assertEquals(entry + " selects Build", "build", sheet[0].selectedTab());
                    assertTrue(entry + ": Back is available", navigator.back());
                    assertEquals(entry + ": Back returns to Home", "home", w.shell.selectedPage());
                }
            });
            await(() -> "Sample".equals(named(sheet[0], "character-sheet-name", JLabel.class).getText())); // built off the EDT
        }
    }

    /** closeWorkspace saves the sheet's notes draft first, before closeArchiveWorkspaces/home.close can drop anything unsaved. */
    @Test public void closingTheWorkspaceSavesTheSheetsNotesDraftBeforeAnythingElseCloses() throws Exception {
        try (Workspace w = new Workspace(temp, true)) {
            SwingUtilities.invokeAndWait(() -> {
                CharacterSheet sheet = find(w.shell, CharacterSheet.class);
                sheet.open(w.key, "notes");
                await(sheet::ready);
                named(sheet, "character-notes", JTextArea.class).setText("Kept when the workspace closes");
            });
            w.gui.closeWorkspace();
            assertEquals("The draft is saved before archives and Home close", "Kept when the workspace closes",
                w.data.characterJournal().characterCopy(w.key).notes);
        }
    }

    /** P6a: with no character at all, every Build entry opens the Characters list ("No characters yet"); Back returns to Home. */
    @Test public void withoutAnyCharacterEveryBuildEntryOpensTheCharactersList() throws Exception {
        String view = PropertiesManager.getProperty("ui.characters.view");
        PropertiesManager.setProperties("ui.characters.view", "gallery");   // the gallery's empty state; the Table view says it in its footer
        try (Workspace w = new Workspace(temp, false)) {
            SwingUtilities.invokeAndWait(() -> {
                assertNull("No Build pointer page remains", w.shell.getActionMap().get("page-my-info"));
                HomePage home = find(w.shell, HomePage.class);
                home.apply(HomeModels.populated(System.currentTimeMillis()));
                Navigator navigator = Navigator.current();
                CharacterSheet sheet = find(w.shell, CharacterSheet.class);
                tomato.gui.character.CharacterRosterView roster = find(w.shell, tomato.gui.character.CharacterRosterView.class);
                for (String entry : BUILD_ENTRIES) {
                    w.shell.select("home");
                    openBuild(w.shell, home, navigator, entry);
                    assertEquals(entry + " opens Characters", "characters", w.shell.selectedPage());
                    assertFalse(entry + " shows the list, not a sheet", roster.showingSheet() || visibleIn(sheet, w.shell));
                    EmptyState none = named(w.shell, "character-gallery-empty", EmptyState.class);
                    assertTrue(entry + ": the gallery says there is no character yet", visibleIn(none, w.shell));
                    assertEquals("No characters yet", none.getAccessibleContext().getAccessibleName());
                    assertTrue(entry + ": Back is available", navigator.back());
                    assertEquals(entry + ": Back returns to Home", "home", w.shell.selectedPage());
                }
            });
        } finally { PropertiesManager.setProperties("ui.characters.view", view == null ? "" : view); }
    }

    /** Every way to open Build: the route, Alt+7 (TomatoGUI's key binding, no page of its own), Settings search and Home's hero. */
    private static final String[] BUILD_ENTRIES = {"MY_INFO route", "Alt+7", "Settings search", "Home Build"};

    private static void openBuild(WorkspaceShell shell, HomePage home, Navigator navigator, String entry) {
        switch (entry) {
            case "MY_INFO route": assertTrue(navigator.open(Route.to(Destination.MY_INFO))); break;
            case "Alt+7": {
                Object action = shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .get(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_7, java.awt.event.InputEvent.ALT_DOWN_MASK));
                assertEquals("Alt+7 is bound to the Build route", "open-build", action);
                shell.getActionMap().get(action).actionPerformed(null);
                break;
            }
            case "Settings search": assertTrue(ActionRegistry.application().search("build.open").get(0).open()); break;
            default: named(home, "home-build", AbstractButton.class).doClick();
        }
    }

    /** Visible up to {@code root} (the workspace has no window, so isShowing is false). */
    private static boolean visibleIn(Component component, Container root) {
        for (Component c = component; c != null; c = c.getParent()) {
            if (!c.isVisible()) return false;
            if (c == root) return true;
        }
        return false;
    }

    /**
     * The real workspace (TomatoGUI.createWorkspace) over a temporary journal, preview mode and a temporary history store. It puts
     * back what it changes: TomatoGUI's and ChatGUI's static fields, the display mode, the Characters and sheet preferences
     * (ui.tabs.*, ui.characters.*, ui.collapse.*, ui.filters.characters.open) and every ux.archive.* saved view.
     */
    private static final class Workspace implements AutoCloseable {
        private static final List<String> PREFERENCES = List.of("ui.tabs.character", "ui.tabs.characters", "ui.characters.view",
            "ui.characters.sort", "ui.collapse.characters-graveyard", "ui.filters.characters.open");
        final TomatoData data = new TomatoData();
        final TomatoGUI gui;
        final String key;
        WorkspaceShell shell;
        private final Field store = AppHistory.class.getDeclaredField("store"), preview = Tomato.class.getDeclaredField("preview");
        private final Object previousStore, previousPreview;
        private final String tmp = System.getProperty("java.io.tmpdir");
        private final Map<String, String> preferences = new HashMap<>();
        private final Map<Field, Object> statics = new LinkedHashMap<>();
        private final DisplayModeModel.Mode mode = DisplayModeModel.application().mode();
        private final SessionStore history;

        Workspace(TemporaryFolder temp, boolean seeded) throws Exception {
            for (String name : preferenceKeys()) preferences.put(name, PropertiesManager.getProperty(name));
            for (Class<?> type : new Class<?>[]{TomatoGUI.class, ChatGUI.class})
                for (Field field : type.getDeclaredFields())
                    if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) { field.setAccessible(true); statics.put(field, field.get(null)); }
            store.setAccessible(true); preview.setAccessible(true);
            previousStore = store.get(null); previousPreview = preview.get(null);
            preview.set(null, true);
            System.setProperty("java.io.tmpdir", temp.newFolder().getAbsolutePath());
            history = new SessionStore(temp.newFolder().toPath(), false, "synthetic");
            store.set(null, history);
            CharacterJournal journal = new CharacterJournal(temp.newFolder().toPath().resolve("journal.json"));
            key = seeded ? seed(journal) : null;
            inject(data, journal);
            gui = new TomatoGUI(data);
            SwingUtilities.invokeAndWait(() -> shell = (WorkspaceShell) gui.createWorkspace());
        }

        @Override public void close() throws Exception {
            gui.closeWorkspace();
            SwingUtilities.invokeAndWait(() -> {
                if (shell != null) shell.removeNotify();
                DisplayModeModel.application().set(mode);
                try { for (Map.Entry<Field, Object> entry : statics.entrySet()) entry.getKey().set(null, entry.getValue()); }
                catch (IllegalAccessException e) { throw new AssertionError(e); }
            });
            for (String name : preferenceKeys()) { String value = preferences.get(name); PropertiesManager.setProperties(name, value == null ? "" : value); }
            System.setProperty("java.io.tmpdir", tmp);
            store.set(null, previousStore); preview.set(null, previousPreview);
            history.close();
        }

        /** The preferences above and every ux.archive.* key present now (a key the workspace added is cleared on close). */
        private static Set<String> preferenceKeys() throws ReflectiveOperationException {
            Field field = PropertiesManager.class.getDeclaredField("properties"); field.setAccessible(true);
            Set<String> keys = new HashSet<>(PREFERENCES);
            for (String name : ((Properties) field.get(null)).stringPropertyNames()) if (name.startsWith("ux.archive.")) keys.add(name);
            return keys;
        }
    }
}
