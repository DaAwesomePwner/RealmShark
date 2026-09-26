package tomato.gui.dps;

import packets.incoming.MapInfoPacket;
import packets.incoming.NotificationPacket;
import tomato.backend.data.DpsData;
import tomato.backend.data.DpsSnapshot;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import util.PropertiesManager;
import tomato.gui.modern.ContentStyle;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;
import tomato.gui.route.RouteTarget;
import tomato.history.link.EncounterContext;

public class DpsGUI extends JPanel {

    private static final String DISABLE_FILTER = "Default";
    private static volatile DpsGUI INSTANCE;
    private volatile DpsSnapshot latest;
    private DpsSnapshot rendered;
    private long lastSnapshotNanos;
    private final javax.swing.Timer refreshTimer;

    private TomatoData data;
    private JButton next, prev, live, dList;
    private StringDpsGUI displayString;
    private IconDpsGUI displayIcon;
    private DisplayDpsGUI centerDisplay;
    private MeterDpsGUI displayMeter;
    private JComboBox<String> viewMode;
    private JPanel dpsTopPanel;
    private JPanel center;
    private final JTextArea filterNotice = new JTextArea();
    private final JCheckBox paused = new JCheckBox("Pause this view");
    private final JTextArea pauseNotice = ContentStyle.wrappingText("",1);
    private final JTextArea linkStatus = ContentStyle.wrappingText("",1);
    private final JButton openRun = new JButton("Open run"), openTimeline = new JButton("Open Timeline"), openResources = new JButton("Open Resources");
    private EncounterLink shownLink = EncounterLink.live();
    private DisplayFrame displayed;
    private boolean liveUpdates = true;
    private int index = 0;
    private final EncounterCatalog encounterCatalog = new EncounterCatalog();
    private EncounterCatalog.Entry selectedEncounter;
    private boolean selectionChosen;
    boolean hasSelectionIntent() { return selectionChosen; }
    private final JTabbedPane combatTabs = new JTabbedPane();
    private final JComponent resourcesWorkspace;
    public EncounterCatalog encounters() { return encounterCatalog; }
    public String currentEncounterId() { return liveUpdates || selectedEncounter == null ? null : selectedEncounter.id; }
    /** EDT action: select the exact local library entry; a missing ID leaves the current view intact. */
    public boolean showEncounter(String entryId) {
        EncounterCatalog.Entry entry = encounterCatalog.find(entryId);
        if (entry == null) return false;
        paused.setSelected(false); selectedEncounter = entry; selectionChosen = true; liveUpdates = false; updateEncounterLabel(); updateGui(); return true;
    }
    private JComboBox<String> filterComboBox;
    private HashMap<String, String> filterList = new HashMap<>();

    public DpsGUI(TomatoData data) {
        this(data, packets.packetcapture.logger.DiscoveryLog.INSTANCE);
    }
    public DpsGUI(TomatoData data, packets.packetcapture.logger.DiscoveryLog history) {
        this(data, history, tomato.gui.activity.ActivityPanel.workspace(history, tomato.gui.activity.ActivityPanel.Mode.COMBAT));
    }
    DpsGUI(TomatoData data, packets.packetcapture.logger.DiscoveryLog history, JComponent resourcesWorkspace) {
        this.data = data;
        this.resourcesWorkspace = resourcesWorkspace;
        encounterCatalog.capture(() -> data.dpsData.toArray(new DpsData[0]));
        latest = DpsSnapshot.capture(data);

        next = new JButton("Next");
        prev = new JButton("Previous");
        live = new JButton("Go live");
        dList = new JButton("Live");
        next.setToolTipText("Next saved encounter");
        prev.setToolTipText("Previous saved encounter");
        live.setToolTipText("Return to the current encounter");
        dList.setToolTipText("Choose an encounter from the dungeon list");

        next.addActionListener(event -> nextDpsLogDungeon());
        prev.addActionListener(event -> previousDpsLogDungeon());
        live.addActionListener(event -> setLive());
        dList.addActionListener(event -> dListButton(dList));

//        textFilter = new JTextField();
//        textFilter.addKeyListener(new KeyAdapter() {
//            public void keyReleased(KeyEvent e) {
//                String text = textFilter.getText();
//                PropertiesManager.setProperties("nameFilter", text);
//                DpsDisplayOptions.filteredStrings = text.split(" ");
//                updateGui();
//            }
//        });
//        textFilterToggle = new JCheckBox();
//        textFilterToggle.setSelected(true);
//        textFilterToggle.addActionListener(event -> {
//            boolean selected = textFilterToggle.isSelected();
//            textFilter.setEnabled(selected);
//            PropertiesManager.setProperties("toggleFilter", selected ? "T" : "F");
//            DpsDisplayOptions.nameFilter = selected;
//            updateGui();
//        });
        JButton addFilter = new JButton("+");
        addFilter.setToolTipText("Create or edit a DPS filter");
        addFilter.getAccessibleContext().setAccessibleName("Edit DPS filters");
        addFilter.addActionListener(e -> openFilter());
        filterComboBox = new JComboBox<>(new String[]{DISABLE_FILTER});
        filterComboBox.setPrototypeDisplayValue("Filter preset name");
        filterComboBox.getAccessibleContext().setAccessibleName("DPS filter preset");
        filterComboBox.addActionListener(this::comboAction);

        dpsTopPanel = ContentStyle.controls();
        viewMode = new JComboBox<>(new String[]{"Meters", "Legacy"});
        viewMode.getAccessibleContext().setAccessibleName("Damage display mode");
        viewMode.addActionListener(e -> updateGui());
        dpsTopPanel.add(new JLabel("View"));
        dpsTopPanel.add(viewMode);
        dpsTopPanel.add(new JLabel("Filter"));
        dpsTopPanel.add(filterComboBox);
        dpsTopPanel.add(addFilter);
        dpsTopPanel.add(prev);
        dpsTopPanel.add(dList);
        dpsTopPanel.add(next);
        dpsTopPanel.add(live);
        paused.setName("dps-pause-view");
        paused.addActionListener(e -> updateGui());
        dpsTopPanel.add(paused);

        setLayout(new BorderLayout());
        JPanel damagePage = new JPanel(new BorderLayout());
        filterNotice.setEditable(false); filterNotice.setFocusable(false);
        filterNotice.setLineWrap(true); filterNotice.setWrapStyleWord(true); filterNotice.setOpaque(false);
        filterNotice.setName("dps-relative-filter-notice");
        ContentStyle.font(filterNotice, ContentStyle.metadata(ContentStyle.body()));
        filterNotice.setVisible(false);
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.add(dpsTopPanel, BorderLayout.NORTH); header.add(filterNotice, BorderLayout.CENTER);
        pauseNotice.setEditable(false);pauseNotice.setOpaque(false);pauseNotice.setLineWrap(true);pauseNotice.setWrapStyleWord(true);
        pauseNotice.setName("dps-pause-notice");pauseNotice.getAccessibleContext().setAccessibleName("Damage view source and pause state");
        ContentStyle.font(pauseNotice,ContentStyle.metadata(ContentStyle.body()));
        linkStatus.setEditable(false);linkStatus.setOpaque(false);linkStatus.setName("dps-encounter-link");
        linkStatus.getAccessibleContext().setAccessibleName("Encounter link status: linked, unlinked, legacy or live");
        ContentStyle.font(linkStatus,ContentStyle.metadata(ContentStyle.body()));
        openRun.setName("dps-open-run");openTimeline.setName("dps-open-timeline");openResources.setName("dps-open-resources");
        openRun.addActionListener(e->openLinked(tomato.gui.route.Destination.RUNS));
        openTimeline.addActionListener(e->openLinked(tomato.gui.route.Destination.TIMELINE));
        openResources.addActionListener(e->openLinked(tomato.gui.route.Destination.RESOURCES));
        JPanel linkActions=ContentStyle.controls();linkActions.add(openRun);linkActions.add(openTimeline);linkActions.add(openResources);
        JPanel notices=new JPanel(new BorderLayout(0,2));notices.add(pauseNotice,BorderLayout.NORTH);notices.add(linkStatus,BorderLayout.CENTER);notices.add(linkActions,BorderLayout.SOUTH);
        header.add(notices,BorderLayout.SOUTH);
        damagePage.add(header, BorderLayout.NORTH);

        center = new JPanel();
        center.setLayout(new BorderLayout());
        damagePage.add(center, BorderLayout.CENTER);
        combatTabs.setName("dps-tabs");
        combatTabs.addTab("Damage meters", damagePage);
        combatTabs.addTab("Resources & buffs", resourcesWorkspace);
        JButton savedResources = new JButton("Saved resources"); savedResources.setName("dps-open-saved-resources");
        savedResources.setEnabled(resourcesWorkspace instanceof tomato.gui.history.ArchiveWorkspace || resourcesWorkspace instanceof tomato.gui.history.SessionPanel);
        savedResources.addActionListener(e -> browseSavedResources()); dpsTopPanel.add(savedResources);
        add(combatTabs, BorderLayout.CENTER);

        displayString = new StringDpsGUI(data);
        displayIcon = new IconDpsGUI(data);
        displayMeter = new MeterDpsGUI();
        setCenterDisplay();
        refreshTimer = new javax.swing.Timer(250, e -> refreshLiveView());
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) refreshLiveView();
        });
        combatTabs.addChangeListener(e -> refreshLiveView());
        INSTANCE = this;
    }

    /** History-open callback for the Resources workspace; never routes a saved request into live data. */
    public boolean browseSavedResources() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Open saved resources on the EDT");
        if (resourcesWorkspace instanceof tomato.gui.history.ArchiveWorkspace)
            ((tomato.gui.history.ArchiveWorkspace<?, ?, ?>)resourcesWorkspace).selectSession(tomato.history.SessionStore.ALL);
        else if (resourcesWorkspace instanceof tomato.gui.history.SessionPanel)
            ((tomato.gui.history.SessionPanel)resourcesWorkspace).selectSession(tomato.history.SessionStore.ALL);
        else return false;
        combatTabs.setSelectedComponent(resourcesWorkspace); return true;
    }

    @Override public void addNotify() { super.addNotify(); refreshTimer.start(); }
    @Override public void removeNotify() { refreshTimer.stop(); super.removeNotify(); }

    private void refreshLiveView() {
        if (!paused.isSelected() && liveUpdates && centerDisplay.isShowing() && latest != rendered) updateGui();
    }

    private void dListButton(JButton dpsLabel) {
        DungeonListGUI.open(this, data);
    }

    private void comboAction(ActionEvent actionEvent) {
        JComboBox<String> combo = (JComboBox<String>) actionEvent.getSource();
        String selectedItem = String.valueOf(combo.getSelectedItem());
        setupFilter(selectedItem);
        PropertiesManager.setProperties("filterName", selectedItem);
        update();
    }

    private static void setupFilter(String selectedItem) {
        if (selectedItem.equals(DISABLE_FILTER)) {
            Filter.disable();
            return;
        }
        String s = INSTANCE.filterList.get(selectedItem);
        Filter.selectFilter(s);
    }

    public static String systemTimeToString(long time) {
        if (time == 0) return " [-]";
        long ms = time % 1000;
        if (time < 1000) return String.format(" [%dms]", ms);
        long s = time / 1000 % 60;
        if (time < 60000) return String.format(" [%ds %dms]", s, ms);
        long m = time / 60000 % 60;
        if (time < 3600000) return String.format(" [%dm %ds %dms]", m, s, ms);
        long h = time / 3600000;
        return String.format(" [%dh %dm %ds %dms]", h, m, s, ms);
    }

    private void openFilter() {
        FilterGUI.open(this);
    }

    private void setCenterDisplay() {
        DisplayDpsGUI display;
        if (viewMode.getSelectedIndex() == 0) {
            display = displayMeter;
        } else if (liveUpdates || DpsDisplayOptions.equipmentOption < 3) {
            display = displayString;
        } else if (DpsDisplayOptions.equipmentOption == 3) {
            display = displayIcon;
        } else {
            return;
        }

        if (display == centerDisplay) return;

        centerDisplay = display;
        center.removeAll();
        center.add(display);
        center.revalidate();
        repaint();
    }

    public static void updateNewTickPacket(TomatoData data) {
        publish(data, false);
    }

    /** Map transitions must replace the live snapshot even when the next tick never arrives. */
    public static void updateMapPacket(TomatoData data) { publish(data, true); }

    private static void publish(TomatoData data, boolean force) {
        DpsGUI view=INSTANCE;
        if(view==null || view.data!=data) return;
        long now=System.nanoTime();
        if(!force && now-view.lastSnapshotNanos<1_000_000_000L && view.latest.map==data.map) return;
        view.encounterCatalog.capture(() -> data.dpsData.toArray(new DpsData[0]));
        view.latest=DpsSnapshot.capture(data);
        view.lastSnapshotNanos=now;
    }

    private void renderData(MapInfoPacket map, Entity[] entityHitList, ArrayList<NotificationPacket> notifications, long totalDungeonPcTime, boolean b) {
        if(paused.isSelected()&&displayed!=null){present(displayed);return;}
        entityHitList = Arrays.stream(entityHitList).filter(e -> !e.isPlayerCharacter()).toArray(Entity[]::new);
        DpsData saved = b ? null : selectedEncounter.data;
        DpsData.LocalPlayerContext context = b ? rendered.localPlayerContext : saved.getLocalPlayerContext();
        EncounterLink link=b?EncounterLink.live():EncounterLink.of(saved,selectedEncounter.origin!=null);
        displayed=new DisplayFrame(map,entityHitList,notifications,totalDungeonPcTime,b,b?map:saved,b?rendered.player:null,context,link);
        present(displayed);
    }
    private void present(DisplayFrame frame){
        setCenterDisplay();
        displayMeter.setContext(frame.key, frame.player, frame.context);
        displayString.setPlayerContext(frame.context);
        displayIcon.setPlayerContext(frame.context);
        showLink(frame.link);
        displayMeter.setInspectOrigin((frame.live ? "Live DPS encounter" : "Saved DPS encounter") + " · " + (frame.map == null ? "No map" : frame.map.name)
            + " · " + frame.link.label() + (frame.link.linked() ? " · session " + frame.link.visit.sessionId + " · visit " + frame.link.visit.visitId : "")
            + " · player copy displayed in this encounter view");
        String notice = Filter.unavailableReason(frame.context);
        filterNotice.setText(notice); filterNotice.setVisible(!notice.isEmpty());
        pauseNotice.setText((paused.isSelected()?"Paused this view · ":"")+(frame.live?"Live encounter":"Saved encounter")+" · "+(frame.map==null?"No map":frame.map.name)
            +(paused.isSelected()?" · snapshot displayed at "+frame.displayedAt+". Capture continues; uncheck Pause to show the latest data. Switching display modes uses this same snapshot.":" · Pause freezes both Meters and Legacy; choosing another encounter resumes the view."));
        List<Entity> sortedEntityHitList = centerDisplay == displayMeter ? Arrays.asList(frame.targets) : getSortedEntityList(frame.targets);
        centerDisplay.renderData(frame.map, sortedEntityHitList, frame.notes, frame.elapsed, frame.live);
    }
    private static final class DisplayFrame {
        final MapInfoPacket map;final Entity[] targets;final ArrayList<NotificationPacket> notes;
        final long elapsed;final boolean live;final Object key;final Entity player;final DpsData.LocalPlayerContext context;final EncounterLink link;
        final String displayedAt=java.time.Instant.now().toString();
        DisplayFrame(MapInfoPacket map,Entity[] targets,ArrayList<NotificationPacket> notes,long elapsed,boolean live,Object key,Entity player,DpsData.LocalPlayerContext context,EncounterLink link){this.map=map;this.targets=targets;this.notes=notes;this.elapsed=elapsed;this.live=live;this.key=key;this.player=player;this.context=context;this.link=link;}
    }

    /** COMBAT-3: link status plus Open run / Timeline / Resources only for a linked encounter the navigator accepts. */
    private void showLink(EncounterLink link) {
        shownLink = link;
        List<String> reasons = new ArrayList<>();
        linkButton(openRun, "Open run", Destination.RUNS, link, reasons);
        linkButton(openTimeline, "Open Timeline", Destination.TIMELINE, link, reasons);
        linkButton(openResources, "Open Resources", Destination.RESOURCES, link, reasons);
        String text = "Encounter link: " + link.label() + " · " + link.description();
        if (link.linked() && !reasons.isEmpty()) text += "\n" + String.join("\n", reasons);
        linkStatus.setText(text);
    }
    private static void linkButton(JButton button, String label, Destination destination, EncounterLink link, List<String> reasons) {
        String reason;
        if (!link.linked()) reason = label + " unavailable: " + link.label().toLowerCase(Locale.ROOT) + " encounter";
        else {
            Route route = Route.to(destination).withVisit(link.visit);
            reason = Navigator.current().canOpen(route) ? null : label + " unavailable: that view cannot open this exact visit here";
            if (reason != null) reasons.add(reason);
        }
        button.setEnabled(reason == null);
        button.setToolTipText(reason == null ? label + " for the visit verified at encounter entry" : reason);
        button.getAccessibleContext().setAccessibleDescription(button.getToolTipText());
    }
    private void openLinked(Destination destination) {
        EncounterLink link = shownLink;
        if (!link.linked() || !Navigator.current().open(Route.to(destination).withVisit(link.visit)))
            linkStatus.setText("Encounter link: " + link.label() + " · the linked view could not be opened; nothing was changed. " + link.description());
    }
    EncounterLink shownLink() { return shownLink; }

    /** Detached library entries for other modules (EDT). Live data is never included. */
    public static List<RecordedEncounter> recordedEncounters() {
        DpsGUI view = INSTANCE;
        List<RecordedEncounter> result = new ArrayList<>();
        if (view == null) return result;
        for (EncounterCatalog.Entry entry : view.encounterCatalog.entries()) {
            DpsData data = entry.data;
            String map = data.map == null ? null : Objects.toString(data.map.displayName, "").isEmpty() ? data.map.name : data.map.displayName;
            result.add(new RecordedEncounter(data.getRecordingId(), map == null || map.isEmpty() ? "Unknown encounter" : map,
                data.dungeonStartTime > 0 ? data.dungeonStartTime : null, EncounterLink.of(data, entry.origin != null)));
        }
        return result;
    }

    /** Detached origin/destination state of the DPS Logger page for Back. */
    private static final class RouteState {
        final int tab; final boolean live; final String entry; final Object resources;
        RouteState(int tab, boolean live, String entry, Object resources) { this.tab = tab; this.live = live; this.entry = entry; this.resources = resources; }
    }
    private RouteState captureRouteState(RouteTarget resources) {
        return new RouteState(combatTabs.getSelectedIndex(), liveUpdates, selectedEncounter == null ? null : selectedEncounter.id,
            resources == null ? null : resources.captureState());
    }
    private void restoreRouteState(Object value, RouteTarget resources) {
        if (!(value instanceof RouteState)) throw new IllegalArgumentException("Not a DPS Logger route state");
        RouteState state = (RouteState) value;
        if (resources != null && state.resources != null) resources.restoreState(state.resources);
        if (state.live || state.entry == null || !showEncounter(state.entry)) { if (!liveUpdates) setIndex(-1); }
        if (state.tab >= 0 && state.tab < combatTabs.getTabCount()) combatTabs.setSelectedIndex(state.tab);
    }

    /** Resolves exactly one library recording; an ambiguous recording or unverified local object is rejected. */
    private EncounterCatalog.Entry routedEncounter(Route route) {
        if (route.recordingId == null || route.visit != null || route.record != null || route.query != null
            || route.from != null || route.until != null || route.payload != null) return null;
        EncounterCatalog.Entry match = null;
        for (EncounterCatalog.Entry entry : encounterCatalog.entries()) if (route.recordingId.equals(entry.data.getRecordingId())) {
            if (match != null) return null; // Duplicate claimed IDs (for example re-imports) are ambiguous.
            match = entry;
        }
        if (match == null || route.localObjectId == null) return match;
        EncounterContext context = match.data.getEncounterContext();
        return context != null && route.localObjectId.equals(context.localPlayerObjectId) ? match : null;
    }

    /**
     * ENCOUNTER destination (page shared with Resources): opens one exact recording in Meters and, when the
     * route carries the verified local object ID, selects that row with a historical-recording notice.
     */
    public RouteTarget encounterRouteTarget() {
        RouteTarget resources = resourcesRouteTarget();
        return new RouteTarget() {
            public Destination destination() { return Destination.ENCOUNTER; }
            public boolean accepts(Route route) { return route.destination == destination() && routedEncounter(route) != null; }
            public Object captureState() { return captureRouteState(resources); }
            public void open(Route route) {
                EncounterCatalog.Entry entry = routedEncounter(route);
                if (entry == null) throw new IllegalArgumentException("Recording is not in this library");
                viewMode.setSelectedIndex(0);
                if (!showEncounter(entry.id)) throw new IllegalArgumentException("Recording is not in this library");
                combatTabs.setSelectedIndex(0);
                if (route.localObjectId != null) {
                    String notice = historicalNotice(entry, route.localObjectId);
                    if (!displayMeter.focusPlayer(route.localObjectId, notice))
                        displayMeter.focusPlayer(-1, notice + " The local row is not shown: clear the DPS filter preset, or it dealt no recorded damage in this encounter.");
                }
            }
            public void restoreState(Object state) { restoreRouteState(state, resources); }
        };
    }
    static String historicalNotice(EncounterCatalog.Entry entry, int objectId) {
        DpsData data = entry.data;
        return "Historical recorded DPS · " + (data.map == null ? "Unknown encounter" : data.map.name) + " · entered "
            + (data.dungeonStartTime > 0 ? tomato.gui.modern.DisplayFormat.formatTimestamp(data.dungeonStartTime) : "at an unknown time")
            + ". Selected: the verified local player's row for this encounter only (object #" + objectId + "). Its DPS uses this recording's "
            + "first-to-last hit window and the build recorded then; it is not your current-build estimate.";
    }

    /** RESOURCES destination: the saved Resources workspace with an exact visit, on its tab; null without saved history. */
    public RouteTarget resourcesRouteTarget() {
        tomato.gui.activity.ActivityRouteTarget delegate = tomato.gui.activity.ActivityRouteTarget.of(Destination.RESOURCES, resourcesWorkspace);
        if (delegate == null) return null;
        return new RouteTarget() {
            public Destination destination() { return Destination.RESOURCES; }
            public boolean accepts(Route route) { return delegate.accepts(route); }
            public Object captureState() { return captureRouteState(delegate); }
            public void open(Route route) { delegate.open(route); combatTabs.setSelectedComponent(resourcesWorkspace); }
            public void restoreState(Object state) { restoreRouteState(state, delegate); }
        };
    }
    MeterDpsGUI meter() { return displayMeter; }
    JTabbedPane combatTabs() { return combatTabs; }

    private List<Entity> getSortedEntityList(Entity[] entityHitList) {
        if (DpsDisplayOptions.sortOption == 1) {
            return Arrays.stream(entityHitList).sorted(Comparator.comparingLong(Entity::getFirstDamageTaken).reversed()).collect(Collectors.toList());
        } else if (DpsDisplayOptions.sortOption == 2) {
            return Arrays.stream(entityHitList).sorted(Comparator.comparingLong(Entity::maxHp).reversed()).collect(Collectors.toList());
        } else if (DpsDisplayOptions.sortOption == 3) {
            return Arrays.stream(entityHitList).sorted(Comparator.comparingLong(Entity::getFightTimer).reversed()).collect(Collectors.toList());
        } else if (DpsDisplayOptions.sortOption == 4) {
            return Arrays.stream(entityHitList).filter(Entity::isBossMob).sorted(Comparator.comparingLong(Entity::maxHp).reversed()).collect(Collectors.toList());
        } else {
            return Arrays.stream(entityHitList).sorted(Comparator.comparingLong(Entity::getLastDamageTaken).reversed()).collect(Collectors.toList());
        }
    }

    public static void editFont(Font font) {
        INSTANCE.displayString.editFont(font);
        INSTANCE.displayIcon.editFont(font);
        INSTANCE.displayMeter.editFont(font);
        update();
    }

    public String getFilterString(String a) {
        return filterList.get(a);
    }

    public String[] getComboBoxStrings() {
        return filterList.keySet().toArray(new String[0]);
    }

    public boolean addComboBox(String a, String b) {
        boolean add = false;
        if (!filterList.containsKey(a)) {
            filterComboBox.addItem(a);
            add = true;
        }
        filterList.put(a, b);
        saveFilterProperty();
        return add;
    }

    public void removeComboBox(String o) {
        filterComboBox.removeItem(o);
        filterList.remove(o);
        saveFilterProperty();
    }

    /**
     * Clear the DPS logs.
     */
    public static void clearDpsLogs() {
        INSTANCE.data.dpsData.clear();
        INSTANCE.encounterCatalog.clear(); INSTANCE.selectedEncounter = null; INSTANCE.selectionChosen = true;
        INSTANCE.paused.setSelected(false);
        INSTANCE.liveUpdates = true;
        INSTANCE.dList.setText("Live");
        update();
    }

    /**
     * Next dungeon displayed by dps calculator.
     */
    public static void nextDpsLogDungeon() {
        INSTANCE.scrollData(1);
    }

    /**
     * Previous dungeon displayed by dps calculator.
     */
    public static void previousDpsLogDungeon() {
        INSTANCE.scrollData(-1);
    }

    private void setLive() {
        INSTANCE.setIndex(-1);
    }

    /**
     * Updates the display label tracking dungeon index.
     */
    public static void updateLabel() {
        DpsGUI view=INSTANCE;
        if(view==null) return;
        SwingUtilities.invokeLater(() -> {
            view.updateEncounterLabel();
        });
    }
    private void updateEncounterLabel() {
        dList.setText(liveUpdates ? "Live" : (getIndex() + 1) + "/" + encounterCatalog.entries().size());
    }

    /**
     * Saves the preset chosen by the user.
     */
    private void saveFilterProperty() {
        StringBuilder sb = new StringBuilder();
        for (String v : filterList.values()) {
            sb.append(v).append("\n");
        }
        String substring;
        if (sb.length() > 0) {
            substring = sb.substring(0, sb.length() - 1);
        } else {
            substring = "";
        }
        PropertiesManager.setProperties("filters", substring);
    }

    /**
     * Loads the filter preset chosen by the user.
     */
    public static void loadFilterPreset() {
        String f = PropertiesManager.getProperty("filters");
        if (f != null) {
            String[] lines = f.split("\n");
            for (String l : lines) {
                String name = l.split(",")[0];
                INSTANCE.filterList.put(name, l);
                INSTANCE.filterComboBox.addItem(name);
            }
        }

        String nameFilter = PropertiesManager.getProperty("filterName");
        if (nameFilter != null) {
            setupFilter(nameFilter);
            INSTANCE.filterComboBox.setSelectedItem(nameFilter);
        } else {
            Filter.disable();
            INSTANCE.filterComboBox.setSelectedItem(DISABLE_FILTER);
        }
    }

    public static void update() {
        INSTANCE.updateGui();
    }

    private void updateGui() {
        if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::updateGui);return;}
        if(paused.isSelected()&&displayed!=null){present(displayed);return;}
        if (liveUpdates) {
            rendered=latest;
            renderData(rendered.map, rendered.targets, rendered.notifications, rendered.elapsed, true);
        } else {
            if (selectedEncounter == null) { setIndex(-1); return; }
            DpsData dpsData = selectedEncounter.data;
            Entity[] entityHitList = dpsData.hitList.values().toArray(new Entity[0]);
            renderData(dpsData.map, entityHitList, dpsData.deathNotifications, dpsData.totalDungeonPcTime, false);
        }
    }

    public int getIndex() {
        if (liveUpdates) return -1;
        return encounterCatalog.entries().indexOf(selectedEncounter);
    }

    public void setIndex(int index) {
        paused.setSelected(false);
        this.index = index;

        if (index == -1) {
            liveUpdates = true;
            selectionChosen = true;
            selectedEncounter = null;
            dList.setText("Live");
            updateGui();
            return;
        }
        List<EncounterCatalog.Entry> entries = encounterCatalog.entries();
        if (index < 0 || index >= entries.size()) { setIndex(-1); return; }
        showEncounter(entries.get(index).id);
    }

    private void scrollData(int a) {
        paused.setSelected(false);
        int size = encounterCatalog.entries().size();
        index = getIndex() + a;
        if (liveUpdates) {
            if (a > 0 || size == 0) {
                updateGui();
                return;
            }
            index = size - 1;
        } else if (index >= size) {
            setIndex(-1);
            return;
        } else if (index < 0) {
            index = 0;
            return;
        }
        setIndex(index);
    }
}
