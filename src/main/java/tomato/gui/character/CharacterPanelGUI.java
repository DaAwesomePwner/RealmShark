package tomato.gui.character;

import java.awt.BorderLayout;
import javax.swing.*;
import tomato.backend.data.TomatoData;

/**
 * Character GUI class to display character data in the character tab.
 */
public class CharacterPanelGUI extends JPanel {

    public CharacterPanelGUI(TomatoData data) {
        setLayout(new BorderLayout());

        CharacterJournalGUI journal = new CharacterJournalGUI(data.characterJournal());
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Roster", journal);
        tabs.addTab("Exalts", journal.exaltPanel());
        tabs.addTab("Pets", new CharacterPetsGUI(data));
        tabs.addChangeListener(e -> journal.refresh());
        add(tabs, BorderLayout.CENTER);
    }
}
