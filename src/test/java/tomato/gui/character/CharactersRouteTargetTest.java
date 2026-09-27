package tomato.gui.character;

import java.awt.Point;
import java.util.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.backend.data.TomatoData;
import tomato.gui.glance.character.CharacterSheet;
import tomato.gui.glance.character.SheetContext;
import tomato.gui.glance.character.SheetFocus;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.roster.RosterStateTestSupport;
import tomato.gui.route.*;
import tomato.planning.PlanningStore;
import tomato.realmshark.RealmCharacter;
import util.PropertiesManager;
import static org.junit.Assert.*;

/** Characters routes on a real ShellNavigator: the list, a sheet and its tab, Back, the sheet's back link and search's goals. */
public class CharactersRouteTargetTest {
    private static final String TABS = "ui.tabs.character";
    private static final String[] KEYS = {TABS, "ui.tabs.characters", "ux.archive.characters-live-roster", "ui.filters.characters.open"};
    private static final String ACCOUNT = CharacterJournal.accountKey("route-fixture");
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final Map<String, String> saved = new LinkedHashMap<>();
    private final int[] page = {0};
    private CharacterJournal journal;

    @Before public void isolate() {
        for (String key : KEYS) { saved.put(key, PropertiesManager.getProperty(key)); PropertiesManager.setProperties(key, ""); }
        journal = new CharacterJournal(temp.getRoot().toPath().resolve("journal.json"));
        List<RealmCharacter> roster = new ArrayList<>();
        for (int id = 1; id <= 3; id++) {
            RealmCharacter c = new RealmCharacter(); c.charId = id; c.classNum = 782; c.receivedAt = 1000L * id; c.supplied("class"); roster.add(c);
        }
        journal.mergeRoster(ACCOUNT, roster);
    }
    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> { }); // Queued view-state saves run before the preferences are restored.
        journal.close();
        saved.forEach((key, value) -> PropertiesManager.setProperties(key, value == null ? "" : value));
    }

    private static String key(int id) { return ACCOUNT + ":" + id; }
    private static Route sheet(String key, String tab) { return Route.to(Destination.CHARACTER_SHEET).withPayload(new SheetFocus(key, tab)); }
    private static String keyAt(JTable roster, int row) {
        String label = roster.getValueAt(row, 0).toString();
        return ACCOUNT + ":" + label.substring(label.lastIndexOf('#') + 1);
    }
    private ShellNavigator navigator(CharacterRosterView view, List<RouteTarget> targets) {
        ShellNavigator navigator = new ShellNavigator(() -> page[0], selected -> page[0] = selected, WorkspaceShell::pageOf, ShellNavigator.DEFAULT_CAPACITY);
        for (RouteTarget target : targets) navigator.register(target);
        view.bindNavigator(navigator);
        return navigator;
    }
    private ShellNavigator navigator(CharacterRosterView view) { return navigator(view, CharactersRouteTarget.of(view)); }

    @Test public void aPlainRouteOpensTheListAndASheetRouteOpensItsTab() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterRosterView view = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
            ShellNavigator navigator = navigator(view);
            view.showSheet(key(1), null, view::showList);
            assertTrue(navigator.open(Route.to(Destination.CHARACTERS)));
            assertEquals(3, page[0]); assertFalse("A plain route shows the list", view.showingSheet());
            assertTrue(navigator.open(sheet(key(2), "notes")));
            assertEquals(3, page[0]); assertTrue(view.showingSheet());
            assertEquals(key(2), view.sheet().key()); assertEquals("notes", view.sheet().selectedTab());
            assertFalse("The list takes no payload", navigator.canOpen(Route.to(Destination.CHARACTERS).withPayload(new SheetFocus(key(2), null))));
            assertFalse("A sheet needs a character", navigator.canOpen(Route.to(Destination.CHARACTER_SHEET)));
            assertFalse(navigator.canOpen(Route.to(Destination.CHARACTER_SHEET).withPayload(key(2))));
            for (String[] bad : new String[][]{{"not-a-key", null}, {key(1).toUpperCase(Locale.ROOT), null}, {key(1), "Not A Tab"}}) {
                try { new SheetFocus(bad[0], bad[1]); fail("Rejected: " + Arrays.toString(bad)); } catch (IllegalArgumentException expected) { }
            }
            assertTrue(navigator.back()); assertFalse("Back restores the list", view.showingSheet()); assertEquals(3, page[0]);
            assertTrue(navigator.back()); assertEquals(0, page[0]);
        });
    }

    @Test public void theBackLinkReturnsToTheListAsItWasAndPopsOnlyItsOwnEntry() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterRosterView view = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
            ShellNavigator navigator = navigator(view);
            page[0] = 3;
            JTable roster = RosterFixtures.named(view.listPanel(), "character-roster", JTable.class);
            JViewport viewport = (JViewport) roster.getParent();
            roster.setRowSelectionInterval(2, 2); String selected = keyAt(roster, 2);
            viewport.setViewPosition(new Point(0, 20));
            RosterFixtures.enter(view);
            assertTrue(view.showingSheet()); assertEquals(selected, view.sheet().key()); assertTrue(navigator.canGoBack());
            RosterFixtures.named(view.sheet(), "character-sheet-back", AbstractButton.class).doClick();
            assertFalse(view.showingSheet());
            assertFalse("The link returned through the Back entry its open pushed", navigator.canGoBack());
            assertEquals(selected, keyAt(roster, roster.getSelectedRow()));
            assertEquals(new Point(0, 20), viewport.getViewPosition());
            page[0] = 14;
            assertTrue(navigator.open(sheet(key(1), null)));
            assertEquals(3, page[0]);
            RosterFixtures.named(view.sheet(), "character-sheet-back", AbstractButton.class).doClick();
            assertFalse("Opened from elsewhere, the link still goes to the list", view.showingSheet());
            assertEquals(3, page[0]);
            assertTrue("Shell Back still returns to the page the sheet was opened from", navigator.back());
            assertEquals(14, page[0]);
        });
    }

    @Test public void anUnknownKeyShowsTheUnavailableState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterRosterView view = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
            ShellNavigator navigator = navigator(view);
            String missing = ACCOUNT + ":404";
            assertTrue("A well-formed key is accepted; the sheet says what it cannot show", navigator.open(sheet(missing, null)));
            assertTrue(view.showingSheet()); assertEquals(missing, view.sheet().key());
            tomato.gui.activity.SnapshotTestSupport.await(view.sheet()::ready); // at once here; from Task 5 the sheet reads off the EDT
            EmptyState state = RosterFixtures.named(view.sheet(), "character-sheet-unavailable", EmptyState.class);
            assertTrue(state.isVisible());
            assertEquals("This character is not in the journal", CharacterSheet.UNAVAILABLE);
            assertEquals(CharacterSheet.UNAVAILABLE, state.getAccessibleContext().getAccessibleName());
            RosterFixtures.named(view.sheet(), "character-sheet-unavailable-back", AbstractButton.class).doClick();
            assertFalse(view.showingSheet());
        });
    }

    @Test public void onlyExplicitNavigationShowsAHiddenTab() throws Exception {
        RosterStateTestSupport.Memory memory = new RosterStateTestSupport.Memory();
        SwingUtilities.invokeAndWait(() -> {
            CharacterRosterView first = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
            first.listPanel().bindViewState(memory.store);
            first.showSheet(key(1), "goals", first::showList);
            assertEquals("goals", first.listPanel().sheetTab());
            first.listPanel().saveViewState();
            PropertiesManager.setProperties(TABS, "overview,gear,exalts,goals,notes,evidence,death|goals");
            CharacterRosterView view = RosterFixtures.view(journal, () -> 5000, RosterDefinitions::empty);
            view.listPanel().bindViewState(memory.store);
            ShellNavigator navigator = navigator(view);
            assertNull("Startup restore leaves the sheet closed", view.sheet().key());
            assertEquals("goals", view.listPanel().sheetTab());
            assertTrue(navigator.open(sheet(key(1), null)));
            assertTrue("A route without a tab only selects the saved one", view.sheet().tabs().hiddenIds().contains("goals"));
            assertEquals("overview", view.sheet().selectedTab());
            assertEquals("overview,gear,exalts,goals,notes,evidence,death|goals", PropertiesManager.getProperty(TABS));
            assertTrue(navigator.open(sheet(key(1), "goals")));
            assertFalse("A route's tab is shown", view.sheet().tabs().hiddenIds().contains("goals"));
            assertEquals("goals", view.sheet().selectedTab());
        });
    }

    @Test public void planningSearchOpensTheSelectedCharactersGoalsElseTheMostRecent() throws Exception {
        TomatoData data = new TomatoData() { @Override public synchronized CharacterJournal characterJournal() { return journal; } };
        SwingUtilities.invokeAndWait(() -> {
            CharacterPanelGUI panel = new CharacterPanelGUI(data,
                new SheetContext(data, journal, RosterDefinitions::empty, DisplayModeModel.application(), () -> 5000, PlanningStore.shared()));
            CharacterRosterView roster = panel.roster();
            ShellNavigator navigator = navigator(roster, panel.routeTargets());
            panel.bindNavigator(navigator);
            JTable table = RosterFixtures.named(roster.listPanel(), "character-roster", JTable.class);
            table.setRowSelectionInterval(1, 1); String selected = keyAt(table, 1);
            page[0] = 5;
            panel.openGoals();
            assertEquals(3, page[0]); assertTrue(roster.showingSheet());
            assertEquals(selected, roster.sheet().key()); assertEquals("goals", roster.sheet().selectedTab());
            assertTrue(navigator.back()); assertEquals(5, page[0]);
            roster.showList(); table.clearSelection();
            panel.openGoals();
            assertEquals("Without a selection, the journal's most recent character", journal.mostRecentCharacter().key, roster.sheet().key());
        });
    }
}
