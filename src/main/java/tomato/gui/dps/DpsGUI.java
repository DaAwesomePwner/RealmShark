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
    private boolean liveUpdates = true;
    private int index = 0;
    private JComboBox<String> filterComboBox;
    private HashMap<String, String> filterList = new HashMap<>();

    public DpsGUI(TomatoData data) {
        this(data, packets.packetcapture.logger.DiscoveryLog.INSTANCE);
    }
    public DpsGUI(TomatoData data, packets.packetcapture.logger.DiscoveryLog history) {
        this.data = data;
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

        setLayout(new BorderLayout());
        JPanel damagePage = new JPanel(new BorderLayout());
        filterNotice.setEditable(false); filterNotice.setFocusable(false);
        filterNotice.setLineWrap(true); filterNotice.setWrapStyleWord(true); filterNotice.setOpaque(false);
        filterNotice.setName("dps-relative-filter-notice");
        ContentStyle.font(filterNotice, ContentStyle.metadata(ContentStyle.body()));
        filterNotice.setVisible(false);
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.add(dpsTopPanel, BorderLayout.NORTH); header.add(filterNotice, BorderLayout.CENTER);
        damagePage.add(header, BorderLayout.NORTH);

        center = new JPanel();
        center.setLayout(new BorderLayout());
        damagePage.add(center, BorderLayout.CENTER);
        JTabbedPane combatTabs = new JTabbedPane(); combatTabs.setName("dps-tabs");
        combatTabs.addTab("Damage meters", damagePage);
        combatTabs.addTab("Resources & buffs", new tomato.gui.activity.ActivityPanel(history, tomato.gui.activity.ActivityPanel.Mode.COMBAT));
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

    @Override public void addNotify() { super.addNotify(); refreshTimer.start(); }
    @Override public void removeNotify() { refreshTimer.stop(); super.removeNotify(); }

    private void refreshLiveView() {
        if (liveUpdates && centerDisplay.isShowing() && latest != rendered) updateGui();
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
        view.latest=DpsSnapshot.capture(data);
        view.lastSnapshotNanos=now;
    }

    private void renderData(MapInfoPacket map, Entity[] entityHitList, ArrayList<NotificationPacket> notifications, long totalDungeonPcTime, boolean b) {
        setCenterDisplay();
        DpsData saved = b ? null : data.dpsData.get(index);
        DpsData.LocalPlayerContext context = b ? rendered.localPlayerContext : saved.getLocalPlayerContext();
        displayMeter.setContext(b ? map : saved, b ? rendered.player : null, context);
        displayString.setPlayerContext(context);
        displayIcon.setPlayerContext(context);
        String notice = Filter.unavailableReason(context);
        filterNotice.setText(notice); filterNotice.setVisible(!notice.isEmpty());
        List<Entity> sortedEntityHitList = centerDisplay == displayMeter ? Arrays.asList(entityHitList) : getSortedEntityList(entityHitList);
        centerDisplay.renderData(map, sortedEntityHitList, notifications, totalDungeonPcTime, b);
    }

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
        INSTANCE.scrollData(1000000000);
    }

    /**
     * Updates the display label tracking dungeon index.
     */
    public static void updateLabel() {
        DpsGUI view=INSTANCE;
        if(view==null) return;
        SwingUtilities.invokeLater(() -> {
            if (!view.liveUpdates) view.dList.setText((view.index + 1) + "/" + view.data.dpsData.size());
        });
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
        if (liveUpdates) {
            rendered=latest;
            renderData(rendered.map, rendered.targets, rendered.notifications, rendered.elapsed, true);
        } else {
            DpsData dpsData = data.dpsData.get(index);
            Entity[] entityHitList = dpsData.hitList.values().toArray(new Entity[0]);
            renderData(dpsData.map, entityHitList, dpsData.deathNotifications, dpsData.totalDungeonPcTime, false);
        }
    }

    public int getIndex() {
        if (liveUpdates) return -1;
        return index;
    }

    public void setIndex(int index) {
        this.index = index;

        if (index == -1) {
            liveUpdates = true;
            dList.setText("Live");
            updateGui();
            return;
        } else {
            liveUpdates = false;
            int size = data.dpsData.size();
            dList.setText((index + 1) + "/" + size);
        }

        setCenterDisplay();
        DpsData dpsData = data.dpsData.get(index);
        Entity[] entityHitList = dpsData.hitList.values().toArray(new Entity[0]);
        renderData(dpsData.map, entityHitList, dpsData.deathNotifications, dpsData.totalDungeonPcTime, false);
    }

    private void scrollData(int a) {
        int size = data.dpsData.size();
        index += a;
        if (liveUpdates) {
            if (a > 0 || size == 0) {
                return;
            }
            index = size - 1;
            liveUpdates = false;
            setCenterDisplay();
        } else if (index >= size) {
            liveUpdates = true;
            dList.setText("Live");
            setCenterDisplay();
            updateGui();
            return;
        } else if (index < 0) {
            index = 0;
            return;
        }
        dList.setText((index + 1) + "/" + size);

        DpsData dpsData = data.dpsData.get(index);
        Entity[] entityHitList = dpsData.hitList.values().toArray(new Entity[0]);
        renderData(dpsData.map, entityHitList, dpsData.deathNotifications, dpsData.totalDungeonPcTime, false);
    }
}
