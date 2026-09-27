package tomato.gui.character;

import java.awt.BorderLayout;
import java.util.List;
import javax.swing.JPanel;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.backend.data.TomatoData;
import tomato.gui.glance.character.CharacterSheet;
import tomato.gui.glance.character.SheetContext;
import tomato.gui.glance.character.SheetFocus;
import tomato.gui.history.ViewStateStore;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;

/** Characters (shell page 3): the Roster tab (the character list or one character's sheet), Exalts and Pets. */
public class CharacterPanelGUI extends JPanel {
    private final CharacterJournal characters;
    private final CharacterJournalGUI journal;
    private final CharacterSheet sheet;
    private final CharacterRosterView roster;
    private final List<RouteTarget> routeTargets;
    private final CustomizableTabs tabs = new CustomizableTabs("characters");

    public CharacterPanelGUI(TomatoData data) {
        this(data, new SheetContext(data, data.characterJournal(), RosterDefinitions::current, DisplayModeModel.application()));
    }

    public CharacterPanelGUI(TomatoData data, SheetContext context) {
        setLayout(new BorderLayout());
        characters = context.journal();
        journal = new CharacterJournalGUI(context.journal(), context.clock(), context.definitions());
        journal.bindViewState(ViewStateStore.application());
        sheet = new CharacterSheet(context);
        roster = new CharacterRosterView(journal, sheet);
        routeTargets = CharactersRouteTarget.of(roster); // built once: both targets share one Back origin
        // Routes are explicit navigation, so they may bring the Roster tab forward even when it is hidden.
        roster.onReveal(() -> { tabs.show("roster"); tabs.select("roster"); });
        tabs.add("roster", "Roster", roster).add("exalts", "Exalts", journal.exaltPanel()).add("pets", "Pets", new CharacterPetsGUI(data));
        // Another Characters tab refreshes the list and keeps the sheet's notes draft.
        tabs.component().addChangeListener(e -> { journal.refresh(); sheet.saveDraft(); });
        add(tabs.component(), BorderLayout.CENTER);
    }

    public void bindNavigator(Navigator navigator) { roster.bindNavigator(navigator); sheet.bindNavigator(navigator); }
    /** Hosts the app's single Build page (MyInfoGUI) in the character sheet's Build tab. */
    public void hostBuild(javax.swing.JComponent build) { sheet.hostBuild(build); }
    /** The CHARACTERS and CHARACTER_SHEET targets; TomatoGUI registers both with the shell navigator. */
    public List<RouteTarget> routeTargets() { return routeTargets; }
    public CharacterRosterView roster() { return roster; }
    public CharacterSheet sheet() { return sheet; }

    /**
     * Search's "Character and exalt goals": the Goals tab of the selected character's sheet, else the journal's most recent
     * character. It goes through the navigator so Back returns, and shows the list when the journal holds no character.
     */
    public void openGoals() {
        String key = roster.currentKey();
        if (key == null) { CharacterJournal.CharacterRecord recent = characters.mostRecentCharacter(); key = recent == null ? null : recent.key; }
        boolean sheetRoute = SheetFocus.validKey(key);
        Route route = sheetRoute ? Route.to(Destination.CHARACTER_SHEET).withPayload(new SheetFocus(key, "goals")) : Route.to(Destination.CHARACTERS);
        if (roster.navigator().open(route)) return;
        roster.reveal();
        if (sheetRoute) roster.showSheet(key, "goals", roster::showList); else roster.showList();
    }
}
