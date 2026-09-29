package tomato.gui.character;

import java.awt.BorderLayout;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JPanel;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.backend.data.TomatoData;
import tomato.gui.glance.character.CharacterSheet;
import tomato.gui.glance.character.ExaltsGrid;
import tomato.gui.glance.character.SheetContext;
import tomato.gui.glance.character.SheetFocus;
import tomato.gui.history.ViewStateStore;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;

/**
 * Characters (the shell's characters page): the Roster tab (the character list or one character's sheet), the account Exalts
 * grid, Pets and, with saved history, the Analyst tab Fame history ({@link #hostFame}).
 */
public class CharacterPanelGUI extends JPanel {
    /** The Analyst tab of saved character fame, in the {@code characters} tabs. */
    public static final String FAME_HISTORY = "fame-history";
    private final CharacterJournal characters;
    private final CharacterJournalGUI journal;
    private final CharacterSheet sheet;
    private final CharacterRosterView roster;
    private final List<RouteTarget> routeTargets;
    private final CustomizableTabs tabs = new CustomizableTabs("characters");
    /** Fame history's holder (null until hosted) and its content (null until the tab is first selected). */
    private JPanel fameHolder;
    private JComponent fame;

    public CharacterPanelGUI(TomatoData data) { this(data, ViewStateStore.application()); }

    /** {@code viewState}: where the roster list keeps its saved view (TomatoGUI's store; tests pass an isolated one). */
    public CharacterPanelGUI(TomatoData data, ViewStateStore viewState) {
        this(data, new SheetContext(data, data.characterJournal(), RosterDefinitions::current, DisplayModeModel.application()), viewState);
    }

    public CharacterPanelGUI(TomatoData data, SheetContext context) { this(data, context, ViewStateStore.application()); }

    public CharacterPanelGUI(TomatoData data, SheetContext context, ViewStateStore viewState) {
        java.util.Objects.requireNonNull(viewState, "viewState");
        setLayout(new BorderLayout());
        characters = context.journal();
        journal = new CharacterJournalGUI(context.journal(), context.clock(), context.definitions());
        // The gallery's "Playing now": the exact journal key of the character in game, with Home's map-change grace (no flicker).
        journal.setLiveKey(() -> {
            tomato.backend.data.LiveCharacter.Snapshot live = tomato.gui.glance.character.SheetModelBuilder.inGame(data.liveCharacter, System.currentTimeMillis());
            return live == null ? null : live.journalKey();
        });
        journal.bindViewState(viewState);
        sheet = new CharacterSheet(context);
        roster = new CharacterRosterView(journal, sheet);
        routeTargets = CharactersRouteTarget.of(roster); // built once: both targets share one Back origin
        // Routes are explicit navigation, so they may bring the Roster tab forward even when it is hidden.
        roster.onReveal(() -> { tabs.show("roster"); tabs.select("roster"); });
        // Back returns to the Characters tab the user left from: Back state records the tab in front and Back brings it forward
        // again. Back is explicit navigation too, so it may show that tab when it has been hidden since.
        roster.onPageTab(tabs::selectedId);
        roster.onBringTabForward(id -> { tabs.show(id); tabs.select(id); });
        tabs.add("roster", "Roster", roster).add("exalts", "Exalts", new ExaltsGrid(context)).add("pets", "Pets", new CharacterPetsGUI(data));
        // Another Characters tab refreshes the list and keeps the sheet's notes draft.
        tabs.component().addChangeListener(e -> { journal.refresh(); sheet.saveDraft(); });
        add(tabs.component(), BorderLayout.CENTER);
    }

    /**
     * Adds the Analyst tab "Fame history" ({@value #FAME_HISTORY}) after Pets: saved character fame, moved from Statistics.
     * {@code factory} builds its content (CharacterFameHistory's view, whose workspace starts a saved-history read) on the tab's
     * first selection only, never at construction and never twice. The built content stays in the tab's holder, so the app's
     * workspace close reaches it through the tabs' contents also while the tab is hidden or skipped in Simple. Without a call
     * (no history store) there is no tab. Once. EDT.
     */
    public void hostFame(Supplier<JComponent> factory) {
        Objects.requireNonNull(factory, "factory");
        if (fameHolder != null) throw new IllegalStateException("Fame history is already hosted");
        fameHolder = new JPanel(new BorderLayout());
        fameHolder.setName("characters-fame-history");
        fameHolder.setOpaque(false);
        tabs.addAnalyst(FAME_HISTORY, "Fame history", fameHolder);
        tabs.onSelect(id -> { if (FAME_HISTORY.equals(id)) buildFame(factory); });
        if (FAME_HISTORY.equals(tabs.selectedId())) buildFame(factory);
    }

    /**
     * Search's "Character fame history" (P6a): in Analyst, shows the Fame history tab (explicit navigation, so a tab hidden from the
     * strip comes back) and brings it forward, which builds it on first use; returns whether it is in front. In Simple the tab is
     * not offered, and without saved history there is none: nothing changes (the saved hidden set included) and it returns false.
     * EDT.
     */
    public boolean showFameHistory() {
        if (fameHolder == null || !DisplayModeModel.application().analyst()) return false;
        tabs.show(FAME_HISTORY);
        tabs.select(FAME_HISTORY);
        return FAME_HISTORY.equals(tabs.selectedId());
    }

    private void buildFame(Supplier<JComponent> factory) {
        if (fame != null) return;
        fame = Objects.requireNonNull(factory.get(), "fame history view");
        fameHolder.add(fame, BorderLayout.CENTER);
        fameHolder.revalidate();
        fameHolder.repaint();
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
