package tomato.gui.character;

import java.awt.Component;
import java.awt.Container;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.backend.data.TomatoData;
import tomato.gui.glance.character.CharacterSheet;
import tomato.gui.glance.character.SheetContext;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.route.Navigator;
import tomato.planning.PlanningStore;

/**
 * Builds the Characters Roster tab (the list and the sheet) over a fixture journal, clock and definitions. The view is bound to
 * Navigator.NONE, so opening a character switches cards in place even if another test left a navigator installed.
 */
final class RosterFixtures {
    private RosterFixtures() { }

    static CharacterSheet sheet(CharacterJournal journal, LongSupplier clock, Supplier<RosterDefinitions> definitions, PlanningStore plans) {
        return new CharacterSheet(new SheetContext(new TomatoData(), journal, definitions, DisplayModeModel.application(), clock, plans));
    }
    static CharacterSheet sheet(CharacterJournal journal, LongSupplier clock, Supplier<RosterDefinitions> definitions) {
        return sheet(journal, clock, definitions, PlanningStore.shared());
    }
    static CharacterRosterView view(CharacterJournal journal, LongSupplier clock, Supplier<RosterDefinitions> definitions, PlanningStore plans) {
        CharacterRosterView view = new CharacterRosterView(new CharacterJournalGUI(journal, clock, definitions), sheet(journal, clock, definitions, plans));
        view.bindNavigator(Navigator.NONE);
        return view;
    }
    static CharacterRosterView view(CharacterJournal journal, LongSupplier clock, Supplier<RosterDefinitions> definitions) {
        return view(journal, clock, definitions, PlanningStore.shared());
    }
    /**
     * Enter on the list's selected row, through the table's own key binding. Returns once the sheet shows that character
     * (at once here; the sheet builds off the EDT from Task 5 on, and SnapshotTestSupport.await runs the EDT while it waits).
     */
    static void enter(CharacterRosterView view) {
        JTable roster = named(view.listPanel(), "character-roster", JTable.class);
        roster.getActionMap().get(roster.getInputMap().get(KeyStroke.getKeyStroke("ENTER"))).actionPerformed(null);
        if (view.showingSheet()) tomato.gui.activity.SnapshotTestSupport.await(view.sheet()::ready);
    }
    static <T> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container) { T found = named((Container) child, name, type); if (found != null) return found; }
        }
        return null;
    }
}
