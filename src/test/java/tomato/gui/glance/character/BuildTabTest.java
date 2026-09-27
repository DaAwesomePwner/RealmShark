package tomato.gui.glance.character;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.Tomato;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournalTest;
import tomato.backend.data.LiveCharacter;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.chat.ChatGUI;
import tomato.gui.glance.home.HomeModels;
import tomato.gui.glance.home.HomePage;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.KitButton;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.myinfo.BuildMovedPanel;
import tomato.gui.myinfo.BuildRoute;
import tomato.gui.myinfo.MyInfoGUI;
import tomato.gui.route.*;
import tomato.gui.search.ActionRegistry;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;
import static tomato.gui.glance.character.SheetFixtures.*;

/** Build on the character sheet: one MyInfoGUI, hosted in the sheet; every Build entry opens the sheet's Build tab; page 6 points there. */
public class BuildTabTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void buildTabHostsOneMyInfoAndPointsElsewhereWhileAnotherCharacterPlays() throws Exception {
        TomatoData data = new TomatoData();
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
            assertEquals("Ann is in game now.", other.getAccessibleContext().getAccessibleDescription());
            AbstractButton open = named(tab, "character-build-open-live", AbstractButton.class);
            assertEquals("Open Ann's Build", open.getText());
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

    @Test public void buildRoutePrefersTheLiveCharacterInTheJournalThenTheMostRecent() throws Exception {
        TomatoData data = new TomatoData();
        CharacterJournal journal = new CharacterJournal(temp.newFolder().toPath().resolve("journal.json"));
        inject(data, journal);
        assertNull("No character at all: no key (page 6)", BuildRoute.key(data));
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
                BuildMovedPanel moved = find(w.shell, BuildMovedPanel.class);
                assertNotNull("Page 6 only says that Build moved", moved);
                moved.refresh();
                HomePage home = find(w.shell, HomePage.class);
                home.apply(HomeModels.populated(System.currentTimeMillis()));
                Navigator navigator = Navigator.current();
                for (String entry : new String[]{"MY_INFO route", "Alt+7", "Settings search", "Home Build", "Build moved button"}) {
                    w.shell.select(14);
                    switch (entry) {
                        case "MY_INFO route": assertTrue(navigator.open(Route.to(Destination.MY_INFO))); break;
                        case "Alt+7": w.shell.getActionMap().get("page-6").actionPerformed(null); break;
                        case "Settings search": assertTrue(ActionRegistry.application().search("build.open").get(0).open()); break;
                        case "Home Build": named(home, "home-build", AbstractButton.class).doClick(); break;
                        default: named(moved, "build-moved-open", AbstractButton.class).doClick();
                    }
                    assertEquals(entry + " opens Characters", 3, w.shell.getSelectedPage());
                    assertEquals(entry + " opens this character's sheet", w.key, sheet[0].key());
                    assertEquals(entry + " selects Build", "build", sheet[0].selectedTab());
                    assertTrue(entry + ": Back is available", navigator.back());
                    assertEquals(entry + ": Back returns to Home", 14, w.shell.getSelectedPage());
                }
            });
            await(() -> "Sample".equals(named(sheet[0], "character-sheet-name", JLabel.class).getText())); // built off the EDT
        }
    }

    @Test public void withoutAnyCharacterBuildLandsOnTheBuildMovedPage() throws Exception {
        try (Workspace w = new Workspace(temp, false)) {
            SwingUtilities.invokeAndWait(() -> {
                w.shell.select(14);
                assertTrue(Navigator.current().open(Route.to(Destination.MY_INFO)));
                assertEquals(6, w.shell.getSelectedPage());
                BuildMovedPanel moved = find(w.shell, BuildMovedPanel.class);
                moved.refresh();
                assertFalse("Nothing to open yet", named(moved, "build-moved-open", AbstractButton.class).isEnabled());
                assertTrue(Navigator.current().back());
                assertEquals(14, w.shell.getSelectedPage());
            });
        }
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
