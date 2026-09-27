package tomato.gui.character;

import java.awt.CardLayout;
import java.util.Objects;
import javax.swing.JPanel;
import tomato.gui.glance.character.CharacterSheet;
import tomato.gui.glance.character.SheetFocus;
import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;

/**
 * The Characters page's Roster tab: the character list or one character's sheet (CardLayout cards "list" and "sheet").
 * Enter or a double-click in the list opens the sheet through the navigator, so Back and the sheet's "‹ Characters" link
 * return to the list as it was. EDT only.
 */
public final class CharacterRosterView extends JPanel {
    public static final String LIST = "list", SHEET = "sheet";
    private final CardLayout cards = new CardLayout();
    private final CharacterJournalGUI list;
    private final CharacterSheet sheet;
    private Navigator navigator;
    private Runnable reveal = () -> { };
    private boolean sheetShowing;

    public CharacterRosterView(CharacterJournalGUI list, CharacterSheet sheet) {
        this.list = Objects.requireNonNull(list, "list"); this.sheet = Objects.requireNonNull(sheet, "sheet");
        setLayout(cards); setName("character-roster-view");
        add(list, LIST); add(sheet, SHEET);
        list.onOpenSheet(this::openCharacter);
        // The sheet's settled tab is saved with the list's view state and selected the next time the sheet opens.
        sheet.tabs().onSelect(id -> { if (id != null && sheet.key() != null) list.sheetTabSelected(id); });
        cards.show(this, LIST);
    }

    public void bindNavigator(Navigator navigator) { this.navigator = navigator; }
    Navigator navigator() { return navigator != null ? navigator : Navigator.current(); }
    /** Brings the Roster tab forward on the Characters page; routes use it because they are explicit navigation. */
    void onReveal(Runnable action) { reveal = Objects.requireNonNull(action); }
    void reveal() { reveal.run(); }

    /**
     * Shows one character's sheet.
     * - A null tab selects the remembered tab without showing it.
     * - A tab id is explicit navigation.
     * - {@code back} is what the sheet's "‹ Characters" link does.
     */
    public void showSheet(String key, String tab, Runnable back) {
        sheet.onBack(Objects.requireNonNull(back, "back"));
        sheet.open(key, tab);
        if (tab == null && list.sheetTab() != null) sheet.selectTab(list.sheetTab());
        sheetShowing = true; cards.show(this, SHEET);
    }
    /** Shows the list, refreshed with changes made on the sheet (Mark dead, notes); Back and "‹ Characters" keep a notes draft. */
    public void showList() {
        boolean fromSheet = sheetShowing;
        if (fromSheet) sheet.saveDraft();
        sheetShowing = false; cards.show(this, LIST);
        list.refresh();
        if (fromSheet) list.focusRoster();
    }
    public boolean showingSheet() { return sheetShowing; }
    public CharacterJournalGUI listPanel() { return list; }
    public CharacterSheet sheet() { return sheet; }
    CharactersRouteTarget.CharactersState state() { return new CharactersRouteTarget.CharactersState(sheetShowing, sheet.key(), sheet.selectedTab()); }
    /** The sheet's character while it shows, else the list's selected character; null when neither. */
    String currentKey() { return sheetShowing && sheet.key() != null ? sheet.key() : list.selectedKey(); }

    /** Opens a character from the list (and, in Task 9, the gallery) through the navigator; without one, switches in place. */
    void openCharacter(String key) {
        boolean routed = SheetFocus.validKey(key) && navigator().open(Route.to(Destination.CHARACTER_SHEET).withPayload(new SheetFocus(key, null)));
        if (!routed) showSheet(key, null, this::showList);
        sheet.focusBackLink();
    }
}
