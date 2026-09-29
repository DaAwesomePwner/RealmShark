package tomato.gui;

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
import tomato.gui.glance.home.HomePage;
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
import tomato.gui.modern.Themes;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.kit.Sprites;
import tomato.gui.settings.AppearanceSection;
import tomato.gui.settings.GeneralSection;
import tomato.gui.settings.SettingsPage;
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
    /** The run feed with {@link #runsWorkspace} as its Table view: the Feed tab of {@link #runsDps}. */
    private static tomato.gui.runs.RunsPage runsPage;
    /**
     * Runs & DPS (the {@code runs} page): the feed ({@link #runsPage}), the per-dungeon cards (Dungeons), the single DPS meter (Live meter) and
     * the encounter library (Recordings).
     */
    private static tomato.gui.runs.RunsDpsPage runsDps;
    private static ShellNavigator navigator;
    private static tomato.gui.notifications.NotificationsGUI notifications;
    private static SettingsPage settings;
    private static HomePage home;
    /** Where the Characters roster keeps its saved view; tests point it at an isolated store (they save and restore statics). */
    static ViewStateStore characterViewStates = ViewStateStore.application();

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
        characterPanel = new CharacterPanelGUI(data, characterViewStates);
        statistics = new StatisticsGUI(data);
        questPanel = new QuestGUI(data);
        java.util.List<String> planningAccounts = new java.util.ArrayList<>();
        for (tomato.backend.data.CharacterJournal.AccountRecord account : data.characterJournal().accounts()) planningAccounts.add(account.key);
        questPanel.knownPlanningAccounts(planningAccounts);
        myDmg = new MyInfoGUI(data); // the only instance: it lives in the character sheet's Build tab
        characterPanel.hostBuild(myDmg);
        dpsPanel = new DpsGUI(data);

        menuBar = new TomatoMenuBar();
        notifications = new tomato.gui.notifications.NotificationsGUI();
        // Settings hosts the existing Notifications page unchanged, beside General and Appearance.
        settings = new SettingsPage(notifications, () -> notifications.selectSection(null), new GeneralSection(),
            new AppearanceSection(TomatoGUI::refreshContentFonts));

        SessionStore store = AppHistory.store();
        ViewStateStore states = ViewStateStore.application();
        // Queries create private pin/result directories here, outside captured journals.
        Path scratch = Paths.get(System.getProperty("java.io.tmpdir"), "realmshark-archive");
        JComponent statisticsWorkspace = store == null ? statistics : HistoricalStatistics.statisticsWorkspace(
            store, statistics, scratch.resolve("statistics"), states);
        JComponent lootWorkspace = store == null ? statistics.getLootDashboard() : HistoricalStatistics.lootWorkspace(
            store, statistics.getLootDashboard(), scratch.resolve("loot"), states);
        runsWorkspace = ActivityPanel.workspace(DiscoveryLog.INSTANCE, ActivityPanel.Mode.RUNS);
        // The run feed opens on the saved-run cards; the archive workspace is kept whole as its Table view.
        runsPage = new tomato.gui.runs.RunsPage(runsWorkspace, AppHistory::store);
        // Runs & DPS (spec §6.3): the feed, the per-dungeon cards, the single DPS meter and the encounter library are tabs
        // of one page. The library is the Recordings tab, no longer a modal dialog: the meter's library button opens it through
        // the navigator, so Back returns to the meter. Their open actions are wired once the navigator exists (below).
        runsDps = new tomato.gui.runs.RunsDpsPage(runsPage, dpsPanel);
        tomato.gui.dps.DungeonListGUI recordings = new tomato.gui.dps.DungeonListGUI((DpsGUI) dpsPanel, data);
        runsDps.setContent(tomato.gui.runs.RunsTab.RECORDINGS, recordings);
        ((DpsGUI) dpsPanel).onOpenLibrary(TomatoGUI::openRecordings);
        // Dungeons reads its cards from saved history when it shows; its Analyst analysis is built on its first show over the store,
        // scratch and saved-view states the other saved workspaces use. Without saved history the cards say so and the analysis is
        // a note: no analysis workspace is built.
        tomato.gui.runs.DungeonsView dungeons = new tomato.gui.runs.DungeonsView(AppHistory::store, java.time.ZoneId.systemDefault(),
            System::currentTimeMillis, () -> store == null ? dungeonAnalysisUnavailable()
                : tomato.gui.stats.DungeonAnalysis.workspace(store, scratch.resolve("dungeon-analysis"), states));
        runsDps.setContent(tomato.gui.runs.RunsTab.DUNGEONS, dungeons);
        tomato.gui.logging.LoggingGUI logging = new tomato.gui.logging.LoggingGUI(DiscoveryLog.INSTANCE);
        JComponent inspectWorkspace = SecurityGUI.workspace(securityPanel);
        JComponent timelineWorkspace = ActivityPanel.workspace(DiscoveryLog.INSTANCE, ActivityPanel.Mode.TIMELINE);
        // Home reads its sources on its own refresher thread while it is showing (S9); AppHistory::store also sees a store opened later.
        home = new HomePage(new tomato.gui.glance.home.LiveHomeSources(data, AppHistory::store), homeActions());
        // Pages by NavEntry ID, built in their historical order.
        java.util.Map<String, JComponent> pages = new java.util.LinkedHashMap<>();
        pages.put("chat", chatPanel.workspace());
        pages.put("key-pops", keypopPanel.workspace());
        pages.put("party", inspectWorkspace);
        pages.put("characters", characterPanel);
        pages.put("statistics", statisticsWorkspace);
        pages.put("quests", questPanel);
        pages.put("my-info", new tomato.gui.myinfo.BuildMovedPanel(TomatoGUI::openBuild, () -> tomato.gui.myinfo.BuildRoute.key(data) != null));
        pages.put("dps-logger", new tomato.gui.dps.DpsMovedPanel(TomatoGUI::openLiveMeter, TomatoGUI::openRecordings)); // only points to Runs & DPS
        pages.put("loot", lootWorkspace);
        pages.put("logging", logging);
        pages.put("runs", runsDps);
        pages.put("timeline", timelineWorkspace);
        pages.put("bridge-review", new tomato.gui.bridge.BridgeReviewGUI(tomato.bridge.BridgeService.getInstance()));
        pages.put("settings", settings);
        pages.put("home", home);
        shell = new WorkspaceShell(pages,
            TomatoMenuBar::togglePacketSniffer, Tomato.isPreview(), Tomato::chooseAssets, Tomato::retryAssets, TomatoGUI::browseSavedHistory);
        mainPanel = shell;
        navigator = shell.createNavigator();
        // The Roster tab's list (CHARACTERS) and one character's sheet (CHARACTER_SHEET) share one view and one Back state.
        for (RouteTarget target : characterPanel.routeTargets()) navigator.register(target);
        // Quests: a plain route (Home's Quests card) opens the Board, QuestsFocus.PLANNER the Planner; Back returns to the tab left.
        navigator.register(new tomato.gui.quest.QuestsRouteTarget(questPanel));
        // Every target of the runs page is a Runs & DPS target (RunsDpsPage.routes, tabTarget, liveMeterTarget): opening brings its tab
        // forward, and each captures and restores the page's one Back state, the tab in front and that tab's owner state, since
        // the navigator captures one target per page. Today's registration order is kept.
        tomato.gui.runs.RunsDpsPage page = runsDps;
        // Home's Now card and Alt+8 open the Live meter tab as it is. This target accepts plain routes only and is registered before
        // DpsGUI's encounter target, which is therefore tried first: exact recording routes keep resolving there.
        navigator.register(page.liveMeterTarget(() -> page.tabs().component().requestFocusInWindow()));
        registerRetainedPage(Destination.HOME);
        // Build is a tab on the character sheet (spec §6.2). The Build route, Settings search, the Home hero and Alt+7 open it for
        // the character in game, else the most recent one. The my-info page only says that Build moved, for when no character
        // exists yet.
        navigator.register(new tomato.gui.myinfo.BuildRoute(() -> tomato.gui.myinfo.BuildRoute.key(data)));
        shell.getActionMap().put("page-my-info", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { openBuild(); }
        });
        // The live meter is the Live meter tab of Runs & DPS; the dps-logger page only points there. Alt+8 opens that tab through
        // the navigator (a Back entry, as Alt+7 adds), and the target moves focus to the tab strip once the page shows.
        shell.getActionMap().put("page-dps-logger", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { openLiveMeter(); }
        });
        // Runs routes to rows (a visit or a query) bring the Feed tab and its Table view forward; Back restores the view it left.
        RouteTarget runsTable = runsWorkspace instanceof ArchiveWorkspace ? archiveTarget(Destination.RUNS, (ArchiveWorkspace<?, ?, ?>) runsWorkspace) : null;
        if (runsTable != null) navigator.register(page.routes(tomato.gui.runs.RunsTab.FEED, runsPage.tableRoutes(runsTable)));
        registerArchive(navigator, Destination.STATISTICS, statisticsWorkspace);
        registerArchive(navigator, Destination.LOOT, lootWorkspace);
        // Analytics targets resolve exact visit/variant routes; registered later, so they are tried first.
        registerLoot(navigator, Destination.STATISTICS, statisticsWorkspace);
        registerLoot(navigator, Destination.LOOT, lootWorkspace);
        navigator.register(new tomato.gui.logging.LoggingRouteTarget(logging));
        registerSettingsNotifications(navigator, settings, notifications);
        navigator.register(tomato.gui.notifications.AlertRouteTargets.alertDraft());
        // Investigation targets resolve exact visits and windows; null for live-only (no saved history) views.
        RouteTarget exactRun = tomato.gui.activity.ActivityRouteTarget.of(Destination.RUNS, runsWorkspace);
        if (exactRun != null) navigator.register(page.routes(tomato.gui.runs.RunsTab.FEED, runsPage.tableRoutes(exactRun)));
        // The feed (a plain RUNS route) and one run's recap (RUN_RECAP with an exact visit), registered after the Table view's
        // targets so they are tried first; RUNS routes with a visit or a query still reach the Table view on that row. The RUNS
        // target owns the Feed tab's Back state (the recap or the feed, Cards or Table, and the table's own state).
        for (RouteTarget target : tomato.gui.runs.RunsRouteTarget.of(runsPage, runsTable, AppHistory::store, navigator)) {
            navigator.register(page.routes(tomato.gui.runs.RunsTab.FEED, target));
            if (target.destination() == Destination.RUNS) page.owner(tomato.gui.runs.RunsTab.FEED, target);
        }
        // RUNS routes with a RunsFocus payload (search, the DPS Logger pointer, the meter's library button) bring that tab forward.
        navigator.register(page.tabTarget());
        registerIfPresent(navigator, tomato.gui.activity.ActivityRouteTarget.of(Destination.TIMELINE, timelineWorkspace));
        registerIfPresent(navigator, tomato.gui.activity.ActivityRouteTarget.of(Destination.INSPECT, inspectWorkspace));
        // Resources & buffs stays nested in the meter (dps-tabs › resources), so both meter targets bring the Live meter forward; the
        // encounter target owns that tab's Back state (the nested tab, live or the chosen entry, and the resources archive state).
        RouteTarget resources = ((DpsGUI) dpsPanel).resourcesRouteTarget(), encounter = ((DpsGUI) dpsPanel).encounterRouteTarget();
        if (resources != null) navigator.register(page.routes(tomato.gui.runs.RunsTab.LIVE_METER, resources));
        navigator.register(page.routes(tomato.gui.runs.RunsTab.LIVE_METER, encounter));
        page.owner(tomato.gui.runs.RunsTab.LIVE_METER, encounter);
        Navigator.install(navigator);
        // A feed card opens its exact run's recap; Back (or "‹ Runs") returns to the feed as it was left.
        runsPage.feed().onOpen(visit -> navigator.open(tomato.gui.route.Route.to(Destination.RUN_RECAP).withVisit(visit)));
        wireRunsTabs(recordings, dungeons, (DpsGUI) dpsPanel);
        // The app opens on the first visible core destination; shells built directly keep Chat.
        shell.selectLanding();
        characterPanel.bindNavigator(navigator);

        // Capture explicit heading/report roles before legacy views update their cached fonts.
        ContentStyle.refreshFonts(shell);
        DpsGUI.loadFilterPreset();
        DpsDisplayOptions.loadProfileFilter();
        jMenuBar = menuBar.make();
        registerSearchControls();
        ContentStyle.refreshFonts(jMenuBar);
        refreshContentFonts();
        return mainPanel;
    }

    /**
     * The open actions of the Recordings and Dungeons tabs and the Statistics banner (P5b). Routes add a Back entry, so Back
     * returns to the tab they left.
     * - Recordings opens each recording where it lives: one in memory with a unique recording ID through the Live meter's exact
     *   route; one whose ID another entry shares as that entry in the Live meter (no route names an entry, so no Back entry); a
     *   summary linked to its run as that run's recap on the recording; the live row as the live fight in the Live meter.
     * - Dungeons: Show runs shows the Feed's cards filtered to the card's canonical dungeon (the page's Feed hook), Open best run
     *   that run's recap on its recording, and the Analysis banner's link the Statistics page (out of the sidebar).
     * - The Statistics page's banner opens Dungeons.
     */
    private static void wireRunsTabs(tomato.gui.dps.DungeonListGUI recordings, tomato.gui.runs.DungeonsView dungeons, DpsGUI dps) {
        recordings.onOpenEncounter(id -> navigator.open(tomato.gui.route.Route.to(Destination.ENCOUNTER).withRecording(id, null)));
        recordings.onShowEntry(entry -> { if (dps.showEncounter(entry)) runsDps.bring(tomato.gui.runs.RunsTab.LIVE_METER); });
        recordings.onOpenRecap(TomatoGUI::openRecap);
        recordings.onOpenLive(() -> { dps.setIndex(-1); openLiveMeter(); });
        dungeons.onOpenRuns(canonical -> navigator.open(tomato.gui.route.Route.to(Destination.RUNS)
            .withPayload(new tomato.gui.runs.RunsFocus(tomato.gui.runs.RunsTab.FEED, canonical))));
        dungeons.onOpenRecap(TomatoGUI::openRecap);
        dungeons.onOpenStatistics(TomatoGUI::openStatistics);
        runsDps.onFeedDungeon(runsPage.feed()::showDungeon);
        statistics.onOpenDungeons(TomatoGUI::openDungeons);
    }

    /** One run's recap on one of its recordings (null: the longest), through the navigator. */
    private static void openRecap(tomato.history.link.VisitRef run, String recordingId) {
        if (navigator != null) navigator.open(tomato.gui.route.Route.to(Destination.RUN_RECAP).withVisit(run).withRecording(recordingId, null));
    }

    /** The Dungeons tab's Analysis view without saved history: a note in its place (the analysis workspace is never built). */
    private static JComponent dungeonAnalysisUnavailable() {
        tomato.gui.kit.EmptyState note = new tomato.gui.kit.EmptyState("Saved history is unavailable",
            "Session comparison and cohorts read saved history, which is not open in this app run.", null);
        note.setName("dungeon-analysis-unavailable");
        return note;
    }

    /** The settings page owns both the Settings section and the nested notification view. Back restores both. */
    private static void registerSettingsNotifications(ShellNavigator navigator, SettingsPage page,
            tomato.gui.notifications.NotificationsGUI notifications) {
        RouteTarget delegate = tomato.gui.notifications.AlertRouteTargets.notifications(notifications,
                () -> page.showSection(SettingsPage.NOTIFICATIONS));
        navigator.register(new RouteTarget() {
            public Destination destination() { return delegate.destination(); }
            public boolean accepts(tomato.gui.route.Route route) { return delegate.accepts(route); }
            public Object captureState() { return new SettingsRouteState(page.currentSection(), delegate.captureState()); }
            public void open(tomato.gui.route.Route route) { delegate.open(route); }
            public void restoreState(Object state) {
                SettingsRouteState saved = (SettingsRouteState) state;
                delegate.restoreState(saved.notifications());
                page.showSection(saved.section());
            }
        });
    }

    private record SettingsRouteState(String section, Object notifications) { }

    private static void registerIfPresent(ShellNavigator navigator, RouteTarget target) {
        if (target != null) navigator.register(target);
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
     * Loads the theme preset chosen by the user, rewriting a retired theme value once.
     */
    public static void loadThemePreset() {
        if (!SwingUtilities.isEventDispatchThread()) { onEdt(TomatoGUI::loadThemePreset); return; }
        loadFontPreset();
        Themes.install(Themes.migrateSaved());
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
            if (characterPanel != null) characterPanel.sheet().saveDraft(); // save the user's draft before anything else closes
            if (navigator != null && Navigator.current() == navigator) Navigator.install(null);
            tomato.gui.search.ActionRegistry.application().clear();
            closeArchiveWorkspaces(mainPanel);
            if (home != null) home.close();
        });
    }

    /**
     * Closes every saved-history reader under {@code root}. A hidden tab of a {@link tomato.gui.kit.CustomizableTabs} is detached
     * from the tree, so the walk also visits each tab pane's contents (through its client property): a hidden Feed or Live meter
     * tab, or the meter's hidden Resources & buffs tab inside, still releases its workspaces. Each component is visited once.
     */
    private static void closeArchiveWorkspaces(Component root) {
        closeArchiveWorkspaces(root, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    private static void closeArchiveWorkspaces(Component component, java.util.Set<Component> visited) {
        if (!visited.add(component)) return;
        if (component instanceof ArchiveWorkspace) ((ArchiveWorkspace<?, ?, ?>) component).close();
        if (component instanceof tomato.gui.runs.RunsDpsPage) ((tomato.gui.runs.RunsDpsPage) component).close(); // its tabs' contents
        if (component instanceof tomato.gui.runs.RunsPage) ((tomato.gui.runs.RunsPage) component).close(); // the feed's reads and pins
        Object tabs = component instanceof JComponent ? ((JComponent) component).getClientProperty(tomato.gui.kit.CustomizableTabs.class) : null;
        if (tabs instanceof tomato.gui.kit.CustomizableTabs)
            for (Component content : ((tomato.gui.kit.CustomizableTabs) tabs).contents()) closeArchiveWorkspaces(content, visited);
        if (component instanceof Container)
            for (Component child : ((Container) component).getComponents()) closeArchiveWorkspaces(child, visited);
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
            // Explicit navigation to the archive: the Feed tab (shown again if hidden), then its Table view, before the page shows.
            if (runsDps != null) runsDps.bring(tomato.gui.runs.RunsTab.FEED);
            if (runsPage != null) runsPage.showTable();
            if (shell != null) shell.select("runs");
            if (runsWorkspace instanceof ArchiveWorkspace)
                ((ArchiveWorkspace<?, ?, ?>) runsWorkspace).selectSession(SessionStore.ALL);
            else if (runsWorkspace instanceof tomato.gui.history.SessionPanel)
                ((tomato.gui.history.SessionPanel) runsWorkspace).selectSession(SessionStore.ALL);
        });
    }
    public static void assetsReloaded() {
        onEdt(() -> {
            Sprites.clear(); // Cached sprites were scaled from the previous assets.
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
    private void registerSearchControls() {
        tomato.gui.search.ActionRegistry.application().clear();
        registerSearch("appearance.font", "Font and text size", "font appearance typography size", "Edit > Font",
            "App-folder realmShark.properties", () -> menuBar.focusSetting("font"));
        registerSearch("appearance.theme", "Theme", "appearance dark violet contrast", "Edit > Theme",
            "App-folder realmShark.properties", () -> menuBar.focusSetting("theme"));
        registerSearch("capture.controls", "Capture connection controls", "capture start stop connection", "File > Capture",
            "Capture request preference; the active connection belongs to this app session", () -> menuBar.focusSetting("capture"));
        registerSearch("sharing.original", "Original loot sharing preference", "sharing opt out privacy", "File > Opt-out Loot Sharing",
            "App-folder realmShark.properties; guild Bridge settings are separate", () -> menuBar.focusSetting("sharing"));
        registerSearch("history.location", "History location", "history directory folder storage", "History storage",
            "Configured realmshark.historyDir or the user-level RealmShark history folder", () -> {
                JTextArea text = ContentStyle.wrappingText("History folder: " + AppHistory.directory().toAbsolutePath()
                    + "\nConfigured at startup with realmshark.historyDir; otherwise uses the user-level history folder."
                    + "\nManual plans: Characters/plans.json in the app folder. These survive history deletion.");
                text.setEditable(false); text.setColumns(48); text.setRows(6);
                JOptionPane.showMessageDialog(frame, new JScrollPane(text), "Local storage locations", JOptionPane.INFORMATION_MESSAGE);
            });
        registerSearch("notifications.open", "Sound and notifications", "volume mute alerts notification", "Settings > Notifications",
            "Local notification settings; rule editors show their save state", TomatoGUI::openNotifications);
        registerSearch("alerts.item", "Create or edit item alert", "item alert drop ping", "Settings > Notifications > Item rules",
            "Local typed alert rules; changes require Save in the editor", TomatoGUI::openItemPing);
        registerSearch("alerts.chat", "Chat message alerts", "chat pm whisper keyword ping", "Settings > Notifications > Chat rules",
            "Local typed alert rules; changes require Save in the editor", TomatoGUI::openChatPingMessage);
        registerSearch("alerts.enchant", "Enchantment alerts", "enchant alert slots effects", "Settings > Notifications > Enchant rules",
            "Local typed alert rules; changes require Save in the editor", TomatoGUI::openEnchantPing);
        registerSearch("appearance.settings", "Appearance settings", "appearance theme dark light contrast motion simple analyst mode",
            "Settings > Appearance", "App-folder realmShark.properties", () -> openSettings(SettingsPage.APPEARANCE));
        registerSearch("combat.settings", "Combat history (auto-save and full detail)", "combat dps encounter auto save full detail retention storage",
            "Settings › General", "App-folder realmShark.properties", () -> openSettings(SettingsPage.GENERAL));
        registerSearch("bridge.review", "Guild Bridge settings and saved review", "sharing bridge guild delivery", "Bridge Review",
            "Bridge settings and journal use their configured local paths", () -> shell.select("bridge-review"));
        registerSearch("plans.characters", "Character and exalt goals", "maxing potions character goals equipment death", "Characters",
            "Characters/plans.json; death notes in Characters/journal.json", () -> characterPanel.openGoals());
        registerSearch("plans.quests", "Quest requirements and manual stock", "quest plan held reservations repeats", "Quests",
            "Characters/plans.json; legacy pins remain in Java Preferences",
            () -> navigator.open(tomato.gui.route.Route.to(Destination.QUESTS).withPayload(tomato.gui.quest.QuestsFocus.PLANNER)));
        registerSearch("build.open", "Build (weapon damage and recovery)", "build my info weapon damage dps recovery mana estimates equipment",
            "Characters › Build", "Nothing is saved; values come from the live capture", () -> navigator.open(tomato.gui.route.Route.to(Destination.MY_INFO)));
        // The live meter, the encounter library and the dungeon cards are tabs of Runs & DPS, and Statistics left the sidebar (P5b);
        // search still finds all four. Their words avoid "retention" and the build.open/combat.settings IDs, and the Dungeons words
        // avoid "logger", "alt+8" and "encounter library", which other entries are found by.
        registerSearch("dps.meter", "Live DPS meter", "dps logger meter damage live encounter boss alt+8", "Runs & DPS › Live meter",
            "This app run's recordings stay in memory; DPS filter presets are in the app-folder realmShark.properties", TomatoGUI::openLiveMeter);
        registerSearch("dps.recordings", "Recordings (encounter library)", "recordings encounter library import export load save .dps full detail",
            "Runs & DPS › Recordings", "Saved combat history in the history folder; imported and exported .dps files are the files you choose",
            TomatoGUI::openRecordings);
        registerSearch("statistics.open", "Statistics (fame table, live loot log)", "statistics fame table graph loot log dungeon stats alt+5",
            "Statistics (not in the sidebar)", "Fame and loot history in the history folder", TomatoGUI::openStatistics);
        registerSearch("dungeons.open", "Dungeons (per-dungeon cards, session comparison, cohorts)",
            "dungeons dungeon cards completion clears average duration loot best a/b cohort analysis dungeon stats", "Runs & DPS › Dungeons",
            "Built from saved runs, loot and combat in the history folder; the Cards or Analysis choice is in the app-folder realmShark.properties",
            TomatoGUI::openDungeons);
        shell.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_K,
            java.awt.event.InputEvent.CTRL_DOWN_MASK), "find-settings");
        shell.getActionMap().put("find-settings", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { tomato.gui.search.ActionSearchPanel.show(frame); }
        });
    }
    private void registerSearch(String id, String label, String keywords, String location, String persistence, Runnable action) {
        tomato.gui.search.ActionRegistry.application().register(new tomato.gui.search.ActionDescriptor(id, label, keywords,
            location, persistence, "Preview retains existing control restrictions; opening search does not save or enable anything.",
            () -> shell != null, "Workspace is closed", action));
    }
    /** Home's drill-downs go through the navigator, so Back returns to Home. */
    private static tomato.gui.glance.home.HomeActions homeActions() {
        return new tomato.gui.glance.home.HomeActions(
            TomatoGUI::openCharacterFromHome,
            () -> openFromHome(tomato.gui.route.Route.to(Destination.MY_INFO)),
            () -> openFromHome(tomato.gui.route.Route.to(Destination.ENCOUNTER)),
            // The exact run's recap with its damage breakdown (S4); the plain feed only when the recap is rejected (no saved history).
            visit -> openFromHome(tomato.gui.route.Route.to(Destination.RUN_RECAP).withVisit(visit), tomato.gui.route.Route.to(Destination.RUNS)),
            () -> openFromHome(tomato.gui.route.Route.to(Destination.QUESTS)));
    }

    /** Alt+7 and the my-info page's button: the Build route (the sheet's Build tab, or my-info while no character exists). */
    private static void openBuild() {
        if (navigator != null) navigator.open(tomato.gui.route.Route.to(Destination.MY_INFO));
    }

    /** Alt+8, the DPS Logger pointer and Settings search: the Live meter tab of Runs & DPS, through the navigator (Back returns). */
    private static void openLiveMeter() {
        if (navigator != null) navigator.open(tomato.gui.route.Route.to(Destination.ENCOUNTER));
    }

    /** The pointer's "Open Recordings", the meter's library button and Settings search: the Recordings tab of Runs & DPS. */
    private static void openRecordings() {
        if (navigator != null) navigator.open(tomato.gui.route.Route.to(Destination.RUNS)
            .withPayload(tomato.gui.runs.RunsFocus.of(tomato.gui.runs.RunsTab.RECORDINGS)));
    }

    /**
     * Settings search and the Dungeons Analysis link: the Statistics page (out of the sidebar), through the navigator so Back
     * returns to where the link was used. Without saved history no Statistics route target exists, so the statistics page is
     * selected as it is.
     */
    private static void openStatistics() {
        if (navigator != null && navigator.open(tomato.gui.route.Route.to(Destination.STATISTICS))) return;
        if (shell != null) shell.select("statistics");
    }

    /** Settings search and the Statistics banner: the Dungeons tab of Runs & DPS, through the navigator (Back returns). */
    private static void openDungeons() {
        if (navigator != null) navigator.open(tomato.gui.route.Route.to(Destination.RUNS)
            .withPayload(tomato.gui.runs.RunsFocus.of(tomato.gui.runs.RunsTab.DUNGEONS)));
    }

    /** Opens the first route a registered target accepts. */
    private static void openFromHome(tomato.gui.route.Route... routes) {
        if (navigator == null) return;
        for (tomato.gui.route.Route route : routes) if (navigator.open(route)) return;
    }

    /** The Home hero opens its own character's sheet at Overview; without a journal key (no character yet), the Characters list. */
    private static void openCharacterFromHome(String key) {
        tomato.gui.route.Route list = tomato.gui.route.Route.to(Destination.CHARACTERS);
        if (key == null) openFromHome(list);
        else openFromHome(tomato.gui.route.Route.to(Destination.CHARACTER_SHEET)
            .withPayload(new tomato.gui.glance.character.SheetFocus(key, "overview")), list);
    }


    private static void registerRetainedPage(final Destination destination) {
        navigator.register(new tomato.gui.route.RouteTarget() {
            public Destination destination() { return destination; }
            public boolean accepts(tomato.gui.route.Route route) {
                return route.destination == destination && route.query == null && route.visit == null && route.record == null
                    && route.recordingId == null && route.payload == null && route.from == null && route.until == null;
            }
            // These pages stay mounted; plain navigation does not change their selections, filters or drafts.
            public Object captureState() { return null; }
            public void open(tomato.gui.route.Route route) { }
            public void restoreState(Object state) { }
        });
    }

    /**
     * Opens chat message ping window.
     */
    /** Opens Settings on one of its sections, such as {@link SettingsPage#APPEARANCE}. */
    public static void openSettings(String section) {
        if (shell != null) shell.select(WorkspaceShell.pageOf(Destination.NOTIFICATIONS));
        if (settings != null) settings.showSection(section);
    }

    public static void openNotifications() { openNotifications(null); }
    public static void openNotifications(String section) {
        openSettings(SettingsPage.NOTIFICATIONS);
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
