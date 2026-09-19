package tomato.gui.character;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.backend.data.TomatoData;

/**
 * Character GUI class to display character data in the character tab.
 */
public class CharacterPanelGUI extends JPanel {

    static final int CHAR_PANEL_SIZE = 120;

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
    /**
     * Right mid larger box to fill with components.
     *
     * @return Right mid larger box to fill with components
     */
    static JPanel createMidRightBox(
        JPanel panelTop,
        JPanel panelMid,
        JPanel panelBot
    ) {
        JPanel panel = new JPanel();
        panel.setPreferredSize(new Dimension(240, 120));
        panel.setLayout(new GridLayout(3, 1));
        panel.setBorder(new EmptyBorder(5, 5, 5, 5));

        panelTop.setLayout(new BoxLayout(panelTop, BoxLayout.X_AXIS));
        panelMid.setLayout(new BoxLayout(panelMid, BoxLayout.X_AXIS));
        panelBot.setLayout(new BoxLayout(panelBot, BoxLayout.X_AXIS));

        panel.add(panelTop);
        panel.add(panelMid);
        panel.add(panelBot);
        return panel;
    }

    /**
     * Creates a large box to add smaller content boxes into.
     *
     * @return Returns a main box panel.
     */
    static JPanel createMainBox() {
        JPanel panel = new JPanel();
        panel.setBorder(
            BorderFactory.createMatteBorder(1, 0, 1, 0, Color.GRAY)
        );
        panel.setPreferredSize(new Dimension(370, CHAR_PANEL_SIZE));
        panel.setMaximumSize(new Dimension(370, CHAR_PANEL_SIZE));
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        return panel;
    }

    /**
     * Left box to fill with components.
     *
     * @return Left box to fill with components
     */
    static JPanel createLeftBox() {
        JPanel panel = new JPanel();
        panel.setMaximumSize(new Dimension(120, CHAR_PANEL_SIZE));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(5, 5, 5, 5));
        return panel;
    }

    /**
     * Vault update method called when receiving vault packets.
     */
    public static void vaultDataUpdate() {
        CharacterStatMaxingGUI.vaultDataUpdate();
    }

    /**
     * Method for receiving realm character list info.
     */
    public static void updateRealmChars() {
        CharacterListGUI.updateRealmChars();
        CharacterStatsGUI.updateRealmChars();
        CharacterExaltGUI.updateRealmChars();
        CharacterStatMaxingGUI.updateRealmChars();
        CharacterCollectionGUI.updateRealmChars();
        CharacterPetsGUI.updateEquipedPet();
        tomato.gui.stats.FameTablePanel.updateRealmChars();
    }
}
