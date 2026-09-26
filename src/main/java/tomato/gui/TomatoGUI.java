package tomato.gui;

import com.github.weisj.darklaf.LafManager;
import com.github.weisj.darklaf.theme.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.nio.file.Paths;
import packets.data.QuestData;
import packets.packetcapture.logger.DiscoveryLog;
import realmshark.branding.AppIdentity;
import tomato.Tomato;
import tomato.backend.data.TomatoData;
import tomato.gui.character.CharacterPanelGUI;
import tomato.gui.chat.ChatGUI;
import tomato.gui.chat.ChatPingGUI;
import tomato.gui.dps.DpsDisplayOptions;
import tomato.gui.dps.DpsGUI;
import tomato.gui.activity.ActivityPanel;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.ViewStateStore;
import tomato.gui.keypop.KeypopGUI;
import tomato.gui.maingui.*;
import tomato.gui.myinfo.MyInfoGUI;
import tomato.gui.quest.QuestGUI;
import tomato.gui.security.ParsePanelGUI;
import tomato.gui.security.SecurityGUI;
import tomato.gui.stats.StatisticsGUI;
import tomato.gui.stats.HistoricalStatistics;
import tomato.gui.stats.LootQuery;
import tomato.gui.stats.LootRouteTarget;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import util.PropertiesManager;
import tomato.gui.modern.VioletTheme;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.modern.ContentStyle;
import tomato.gui.route.ArchiveRouteTarget;
import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.RouteTarget;
import tomato.gui.route.ShellNavigator;

/**
 * Example GUI for Tomato mod.
 */
public class TomatoGUI {

    private static final int windowWidth = 1240;
    private static final int windowHeight = 800;
    private static int fontSize = ContentStyle.FONT_SIZE;
    private static int fontStyle = 0;
    private static String fontName = ContentStyle.FONT_FAMILY;
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
    private static TomatoData data;
    private static WorkspaceShell shell;
    private static JComponent runsWorkspace;
    private static ShellNavigator navigator;
    private static tomato.gui.notifications.NotificationsGUI notifications;

    public TomatoGUI(TomatoData data) {
        this.data = data;
    }

    /**
     * Create main panel and initializes the GUI for the example Tomato.
     */
    public void create() {
        if (!SwingUtilities.isEventDispatchThread()) { onEdt(this::create); return; }
        createWorkspace();
        makeFrame();
        frame.setVisible(true);
    }

    /** Builds the usable shell independently of a native window, asset setup and capture startup. */
    public JComponent createWorkspace() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Build workspace on the EDT");
        loadFontPreset();
        ContentStyle.applyFontDefaults();
        chatPanel = new ChatGUI(data);
        KeypopGUI keypopPanel = new KeypopGUI();
        securityPanel = new SecurityGUI();
        characterPanel = new CharacterPanelGUI(data);
        statistics = new StatisticsGUI(data);
        questPanel = new QuestGUI(data);
        myDmg = new MyInfoGUI(data);
        dpsPanel = new DpsGUI(data);

        menuBar = new TomatoMenuBar();
        notifications = new tomato.gui.notifications.NotificationsGUI();

        SessionStore store = AppHistory.store();
        ViewStateStore states = ViewStateStore.application();
        // Queries create private pin/result directories here, outside captured journals.
        Path scratch = Paths.get(System.getProperty("java.io.tmpdir"), "realmshark-archive");
        JComponent statisticsWorkspace = store == null ? statistics : HistoricalStatistics.statisticsWorkspace(
            store, statistics, scratch.resolve("statistics"), states);
        JComponent lootWorkspace = store == null ? statistics.getLootDashboard() : HistoricalStatistics.lootWorkspace(
            store, statistics.getLootDashboard(), scratch.resolve("loot"), states);
        runsWorkspace = ActivityPanel.workspace(DiscoveryLog.INSTANCE, ActivityPanel.Mode.RUNS);
        tomato.gui.logging.LoggingGUI logging = new tomato.gui.logging.LoggingGUI(DiscoveryLog.INSTANCE);
        shell = new WorkspaceShell(new JComponent[] {
            chatPanel.workspace(), keypopPanel.workspace(), SecurityGUI.workspace(securityPanel),
            characterPanel, statisticsWorkspace,
            questPanel, myDmg, dpsPanel,
            lootWorkspace,
            logging,
            runsWorkspace,
            ActivityPanel.workspace(DiscoveryLog.INSTANCE, ActivityPanel.Mode.TIMELINE),
            new tomato.gui.bridge.BridgeReviewGUI(tomato.bridge.BridgeService.getInstance()), notifications},
            TomatoMenuBar::togglePacketSniffer, Tomato.isPreview(), Tomato::chooseAssets, Tomato::retryAssets, TomatoGUI::browseSavedHistory);
        mainPanel = shell;
        navigator = shell.createNavigator();
        registerArchive(navigator, Destination.RUNS, runsWorkspace);
        registerArchive(navigator, Destination.STATISTICS, statisticsWorkspace);
        registerArchive(navigator, Destination.LOOT, lootWorkspace);
        // Analytics targets resolve exact visit/variant routes; registered later, so they are tried first.
        registerLoot(navigator, Destination.STATISTICS, statisticsWorkspace);
        registerLoot(navigator, Destination.LOOT, lootWorkspace);
        navigator.register(new tomato.gui.logging.LoggingRouteTarget(logging));
        Navigator.install(navigator);

        // Capture explicit heading/report roles before legacy views update their cached fonts.
        ContentStyle.refreshFonts(shell);
        DpsGUI.loadFilterPreset();
        DpsDisplayOptions.loadProfileFilter();
        jMenuBar = menuBar.make();
        ContentStyle.refreshFonts(jMenuBar);
        refreshContentFonts();
        return mainPanel;
    }

    /** Query-only generic target; module-specific targets registered later take precedence. */
    private static void registerArchive(ShellNavigator navigator, Destination destination, JComponent workspace) {
        if (workspace instanceof ArchiveWorkspace) navigator.register(archiveTarget(destination, (ArchiveWorkspace<?, ?, ?>) workspace));
    }
    @SuppressWarnings("unchecked")
    private static void registerLoot(ShellNavigator navigator, Destination destination, JComponent workspace) {
        // Both analytics workspaces are typed by LootQuery when saved history is available.
        if (!(workspace instanceof ArchiveWorkspace)) return;
        ArchiveWorkspace<LootQuery.Row, LootQuery.Facets, LootQuery.Sort> typed =
            (ArchiveWorkspace<LootQuery.Row, LootQuery.Facets, LootQuery.Sort>) workspace;
        navigator.register(LootRouteTarget.forWorkspace(destination, typed, typed::restore));
    }
    private static <R, F, S extends Enum<S>> RouteTarget archiveTarget(Destination destination, ArchiveWorkspace<R, F, S> workspace) {
        return new ArchiveRouteTarget<>(destination, workspace);
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
        textArea.setMargin(new Insets(6, 8, 6, 8));
        textArea.setFont(ContentStyle.body());
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
        if (!SwingUtilities.isEventDispatchThread()) { onEdt(TomatoGUI::loadThemePreset); return; }
        loadFontPreset();
        String theme = PropertiesManager.getProperty("theme");
        if (theme == null) theme = "violet";

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
        refreshContentFonts();
    }

    /**
     * Loads the font size preset chosen by the user.
     */
    private static void loadFontPreset() {
        fontSize = presetNumber("fontSize", ContentStyle.FONT_SIZE, 1, 1000);
        fontStyle = presetNumber("fontStyle", Font.PLAIN, Font.PLAIN, Font.BOLD | Font.ITALIC);
        fontName = PropertiesManager.getProperty("fontName");
        if (fontName == null || fontName.trim().isEmpty() || "Segoe".equals(fontName)) fontName = ContentStyle.FONT_FAMILY;
        ContentStyle.setBodyFont(new Font(fontName, fontStyle, fontSize));
    }

    private static int presetNumber(String key, int fallback, int minimum, int maximum) {
        try {
            int value = Integer.parseInt(PropertiesManager.getProperty(key));
            return value >= minimum && value <= maximum ? value : fallback;
        } catch (NumberFormatException ignored) { return fallback; }
    }

    /**
     * Creates the frame with icon.
     */
    public void makeFrame() {
        frame = new JFrame(AppIdentity.title() + (Tomato.isPreview() ? "  |  Preview" : ""));
        AppIdentity.apply(frame);
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        frame.setMinimumSize(new Dimension(Math.min(680, screen.width), Math.min(520, screen.height)));
        frame.setSize(Math.min(windowWidth, screen.width), Math.min(windowHeight, screen.height));
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { closeWorkspace(); }
            @Override public void windowClosed(WindowEvent event) { closeWorkspace(); }
        });
        frame.setJMenuBar(jMenuBar);
        menuBar.setFrame(frame);
        frame.setContentPane(mainPanel);
        frame.setVisible(true);
    }

    /** Releases saved readers, including nested Resources, without closing capture or history writers. */
    public void closeWorkspace() {
        // AppHistory's shutdown hook still checkpoints DiscoveryLog before closing SessionStore.
        onEdt(() -> {
            if (navigator != null && Navigator.current() == navigator) Navigator.install(null);
            closeArchiveWorkspaces(mainPanel);
        });
    }

    private static void closeArchiveWorkspaces(Component component) {
        if (component instanceof ArchiveWorkspace) ((ArchiveWorkspace<?, ?, ?>) component).close();
        if (component instanceof Container)
            for (Component child : ((Container) component).getComponents()) closeArchiveWorkspaces(child);
    }

    /**
     * Set font size of text area.
     */
    public static void fontSizeTextAreas(int size) {
        if (size < 1 || size > 1000) throw new IllegalArgumentException("Font size must be between 1 and 1000");
        onEdt(() -> {
            fontSize = size;
            ContentStyle.setBodyFont(new Font(fontName, fontStyle, size));
            refreshContentFonts();
        });
    }

    /**
     * Set font size of text area.
     */
    public static void fontNameTextAreas(String name, int style) {
        final String family = name == null || name.trim().isEmpty() || "Segoe".equals(name) ? ContentStyle.FONT_FAMILY : name;
        onEdt(() -> {
            fontName = family;
            fontStyle = style & (Font.BOLD | Font.ITALIC);
            ContentStyle.setBodyFont(new Font(fontName, fontStyle, fontSize));
            refreshContentFonts();
        });
    }

    /** Reapplies font roles and metric sizing after a font or look-and-feel change. */
    public static void refreshContentFonts() {
        onEdt(() -> {
            ContentStyle.applyFontDefaults();
            if (shell != null) {
                // These views also cache fonts for custom-painted or subsequently rebuilt content.
                DpsGUI.editFont(ContentStyle.body());
                ParsePanelGUI.editFont(ContentStyle.body());
                shell.refreshTheme();
                if (!shell.isDisplayable()) ContentStyle.refreshFonts(shell);
            }
            for (Window window : Window.getWindows()) if (window.isDisplayable()) ContentStyle.refreshFonts(window);
        });
    }

    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) { action.run(); return; }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while updating the interface", e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Could not update the interface", e.getCause());
        }
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

    public static void setCaptureDetail(String detail) {
        Runnable update = () -> { if (shell != null) shell.setCaptureDetail(detail); };
        if (SwingUtilities.isEventDispatchThread()) update.run(); else SwingUtilities.invokeLater(update);
    }

    public static void setCaptureFailure(String reason) {
        Runnable update = () -> { if (shell != null) shell.setCaptureFailure(reason); };
        if (SwingUtilities.isEventDispatchThread()) update.run(); else SwingUtilities.invokeLater(update);
    }

    public static void setCaptureReadiness(packets.packetcapture.CaptureState state) {
        onEdt(() -> { if (shell != null) shell.setCaptureReadiness(state); });
    }
    public static void setSetupState(String message, boolean ready, boolean busy) {
        onEdt(() -> { if (shell != null) shell.setSetupState(message, ready, busy); });
    }
    public static void browseSavedHistory() {
        onEdt(() -> {
            if (shell != null) shell.select(10);
            if (runsWorkspace instanceof ArchiveWorkspace)
                ((ArchiveWorkspace<?, ?, ?>) runsWorkspace).selectSession(SessionStore.ALL);
            else if (runsWorkspace instanceof tomato.gui.history.SessionPanel)
                ((tomato.gui.history.SessionPanel) runsWorkspace).selectSession(SessionStore.ALL);
        });
    }
    public static void assetsReloaded() {
        onEdt(() -> {
            if (shell != null) SwingUtilities.updateComponentTreeUI(shell);
            refreshContentFonts();
            ParsePanelGUI.update();
            if (myDmg != null) myDmg.refreshAssets();
        });
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
    public static void openNotifications() { openNotifications(null); }
    public static void openNotifications(String section) {
        if (shell != null) shell.select(13);
        if (notifications != null) notifications.selectSection(section);
    }

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
