package tomato.gui.character;

import java.awt.BorderLayout;
import javax.swing.*;
import tomato.backend.data.TomatoData;
import tomato.gui.kit.CustomizableTabs;

/**
 * Character GUI class to display character data in the character tab.
 */
public class CharacterPanelGUI extends JPanel {
    private final CharacterJournalGUI journal;
    private final CustomizableTabs tabs = new CustomizableTabs("characters");

    public CharacterPanelGUI(TomatoData data) {
        setLayout(new BorderLayout());

        journal = new CharacterJournalGUI(data.characterJournal());
        tabs.add("roster", "Roster", journal).add("exalts", "Exalts", journal.exaltPanel()).add("pets", "Pets", new CharacterPetsGUI(data));
        tabs.component().addChangeListener(e -> journal.refresh());
        add(tabs.component(), BorderLayout.CENTER);
    }
    public void bindNavigator(tomato.gui.route.Navigator navigator) { journal.bindNavigator(navigator); }
    public void openGoals() { tabs.show("roster"); tabs.select("roster"); journal.openGoals(); }
}
