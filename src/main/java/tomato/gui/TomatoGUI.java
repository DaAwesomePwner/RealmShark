package tomato.gui;

import com.github.weisj.darklaf.LafManager;
import com.github.weisj.darklaf.theme.*;
import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import packets.data.QuestData;
import tomato.Tomato;
import tomato.backend.data.TomatoData;
import tomato.gui.character.CharacterPanelGUI;
import tomato.gui.chat.ChatGUI;
import tomato.gui.chat.ChatPingGUI;
import tomato.gui.dps.DpsDisplayOptions;
import tomato.gui.dps.DpsGUI;
import tomato.gui.keypop.KeypopGUI;
import tomato.gui.maingui.*;
import tomato.gui.myinfo.MyInfoGUI;
import tomato.gui.quest.QuestGUI;
import tomato.gui.security.ParsePanelGUI;
import tomato.gui.security.SecurityGUI;
import tomato.gui.stats.DungeonStats;
import tomato.gui.stats.StatisticsGUI;
import tomato.version.Version;
import util.PropertiesManager;
import tomato.gui.modern.VioletTheme;
import tomato.gui.modern.WorkspaceShell;

/**
 * Example GUI for Tomato mod.
 */
public class TomatoGUI {

    private static final int windowWidth = 1240;
    private static final int windowHeight = 800;
    private static int fontSize = 12;
    private static int fontStyle = 0;
    private static String fontName = "Monospaced";
    private static JFrame frame;
    private static ChatGUI chatPanel;
    private static SecurityGUI securityPanel;
    private static CharacterPanelGUI characterPanel;
    private static QuestGUI questPanel;
    private static MyInfoGUI myDmg;
    private static StatisticsGUI statistics;
    private JMenuBar jMenuBar;
    private JPanel mainPanel, dpsPanel;
    private TomatoMenuBar menuBar;
    private Image icon;
    private static TomatoData data;
    private static WorkspaceShell shell;

    public TomatoGUI(TomatoData data) {
        this.data = data;
    }

    /**
     * Create main panel and initializes the GUI for the example Tomato.
     */
    public void create() {
        chatPanel = new ChatGUI(data);
        KeypopGUI keypopPanel = new KeypopGUI();
        securityPanel = new SecurityGUI();
        characterPanel = new CharacterPanelGUI(data);
        statistics = new StatisticsGUI(data);
        questPanel = new QuestGUI();
        myDmg = new MyInfoGUI(data);
        dpsPanel = new DpsGUI(data);

        menuBar = new TomatoMenuBar();

        shell = new WorkspaceShell(new JComponent[] {chatPanel, keypopPanel, securityPanel,
            characterPanel, statistics, questPanel, myDmg, dpsPanel},
            TomatoMenuBar::togglePacketSniffer, Tomato.isPreview());
        mainPanel = shell;

        icon = Toolkit.getDefaultToolkit().getImage(Tomato.imagePath);
        loadFontSizePreset();
        loadFontNamePreset();
        DpsGUI.loadFilterPreset();
        DpsDisplayOptions.loadProfileFilter();
        jMenuBar = menuBar.make();
        makeFrame();

        frame.setVisible(true);
    }

    /**
     * Method to create text areas.
     *
     * @param textArea Text area object.
     * @return Scroll pane object to add to a parent object.
     */
    public static JScrollPane createTextArea(
        JTextArea textArea,
        boolean stayAtTop
    ) {
        textArea.setEnabled(true);
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setMargin(new Insets(16, 16, 16, 16));
        textArea.setFont(new Font(fontName, fontStyle, fontSize));
        JScrollPane scrollChat = new JScrollPane(textArea);
        scrollChat.setVerticalScrollBarPolicy(
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        );
        scrollChat.setAutoscrolls(true);
        scrollChat.setBorder(BorderFactory.createEmptyBorder());
        scrollChat.getVerticalScrollBar().setUnitIncrement(24);
        if (stayAtTop) {
            new SmartScroller(scrollChat, 0);
        } else {
            new SmartScroller(scrollChat);
        }
        return scrollChat;
    }

    /**
     * Loads the theme preset chosen by the user.
     */
    public static void loadThemePreset() {
        String theme = PropertiesManager.getProperty("theme");
        if (theme == null) {
            VioletTheme.install();
            return;
        }

        switch (theme) {
            case "violet":
                VioletTheme.install();
                break;
            case "contrastDark":
                LafManager.install(new HighContrastDarkTheme());
                break;
            case "contrastLight":
                LafManager.install(new HighContrastLightTheme());
                break;
            case "intelliJ":
                LafManager.install(new IntelliJTheme());
                break;
            case "solarizedDark":
                LafManager.install(new SolarizedDarkTheme());
                break;
            case "solarizedLight":
                LafManager.install(new SolarizedLightTheme());
                break;
            default:
            case "darcula":
                LafManager.install(new DarculaTheme());
                break;
        }
    }

    /**
     * Loads the font size preset chosen by the user.
     */
    private void loadFontSizePreset() {
        String fontSize = PropertiesManager.getProperty("fontSize");
        int fs = TomatoGUI.fontSize;
        if (fontSize != null) {
            try {
                fs = Integer.parseInt(fontSize);
            } catch (Exception ignored) {}
        }

        fontSizeTextAreas(fs);
    }

    /**
     * Loads the font size preset chosen by the user.
     */
    private void loadFontNamePreset() {
        String fontName = PropertiesManager.getProperty("fontName");
        if (fontName == null) {
            fontName = TomatoGUI.fontName;
        } else {
            TomatoGUI.fontName = fontName;
        }
        String fontStyle = PropertiesManager.getProperty("fontStyle");
        int fontStyleNum = TomatoGUI.fontStyle;
        if (fontStyle != null) {
            try {
                fontStyleNum = Integer.parseInt(fontStyle);
            } catch (Exception ignored) {}
        }
        fontNameTextAreas(fontName, fontStyleNum);
    }

    /**
     * Creates the frame with icon.
     */
    public void makeFrame() {
        frame = new JFrame("RealmShark  |  Tomato " + Version.VERSION + (Tomato.isPreview() ? "  |  Preview" : ""));
        frame.setIconImage(icon);
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        frame.setMinimumSize(new Dimension(Math.min(680, screen.width), Math.min(520, screen.height)));
        frame.setSize(Math.min(windowWidth, screen.width), Math.min(windowHeight, screen.height));
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setJMenuBar(jMenuBar);
        menuBar.setFrame(frame);
        frame.setContentPane(mainPanel);
        frame.setVisible(true);
    }

    /**
     * Set font size of text area.
     */
    public static void fontSizeTextAreas(int size) {
        fontSize = size;
        Font font = new Font(fontName, fontStyle, size);
        ChatGUI.editFont(font);
        KeypopGUI.editFont(font);
        DpsGUI.editFont(font);
        ParsePanelGUI.editFont(font);
        DungeonStats.editFont(font);
    }

    /**
     * Set font size of text area.
     */
    public static void fontNameTextAreas(String name, int style) {
        fontName = name;
        fontStyle = style;
        Font font = new Font(name, style, fontSize);
        ChatGUI.editFont(font);
        KeypopGUI.editFont(font);
        DpsGUI.editFont(font);
        ParsePanelGUI.editFont(font);
        DungeonStats.editFont(font);
    }

    /**
     * Updates the questGUI with quest data.
     *
     * @param q Quest data received when visiting quest room.
     */
    public static void updateQuests(QuestData[] q) {
        questPanel.update(q);
    }

    /**
     * Updates the state of the sniffer at the bottom label to show if running or off.
     *
     * @param running Set the label to running or off.
     */
    public static void setStateOfSniffer(boolean running) {
        Runnable update = () -> { if (shell != null) shell.setCaptureState(running); };
        if (SwingUtilities.isEventDispatchThread()) update.run(); else SwingUtilities.invokeLater(update);
    }

    /**
     * Getter for the main object.
     *
     * @return The main tomato frame object.
     */
    public static JFrame getFrame() {
        return frame;
    }

    /**
     * Opens chat message ping window.
     */
    public static void openChatPingMessage() {
        new ChatPingGUI(data, chatPanel).open();
    }

    /**
     * Opens entity ID ping window.
     */
    public static void openEntityIdPing() {
        new EntityPingGUI(data).open();
    }

    /**
     * Opens entity ID ping window.
     */
    public static void openItemPing() {
        new ItemPingGUI(data).open();
    }

    /**
     * Opens enchantment ping window.
     */
    public static void openEnchantPing() {
        EnchantPingGUI.open();
    }
}
