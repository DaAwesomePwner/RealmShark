package tomato.gui.security;

import assets.IdToAsset;
import assets.ImageBuffer;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.backend.data.InspectSnapshot;
import tomato.gui.modern.ContentStyle;
import tomato.realmshark.ParseEnchants;
import tomato.realmshark.enums.CharacterClass;
import util.PropertiesManager;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.table.TableColumn;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.modern.DisplayFormat;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

public class ParsePanelGUI extends JPanel {
    private static volatile ParsePanelGUI INSTANCE;
    private static final String DISABLE_FILTER = "Default";
    private static final TreeMap<String, SecurityFilter> filters = new TreeMap<>();
    public static SecurityFilter currentFilter;

    // Latest state only: arrivals/updates never enqueue one Swing task per packet.
    private final Object rosterLock = new Object();
    private final Map<Integer, CapturedPlayer> roster = new LinkedHashMap<>();
    private long revision;
    private long themeRevision;
    private long displayedRevision = -1;
    private final RosterModel model = new RosterModel();
    private final JTable table = new JTable(model);
    private final JScrollPane rosterScroll = new JScrollPane(table);
    private final javax.swing.Timer refreshTimer;
    private final JComboBox<String> filterComboBox;
    private final JCheckBox copyOnlyUnderReqCheckbox;
    private final JCheckBox sortCheckBox;
    private final List<Action> playerActions = new ArrayList<>();
    private final List<Action> guildActions = new ArrayList<>();
    private boolean guiUpdateSuppression;
    private String displayedRun;
    private List<CapturedPlayer> historicalPlayers;
    private ActivityJournal.Visit inspectedRun;
    private final List<TableColumn> runColumns = new ArrayList<>();
    private boolean runColumnsVisible;

    public ParsePanelGUI() {
        this(true);
    }
    ParsePanelGUI(boolean liveOwner) {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setFont(ContentStyle.body());
        ContentStyle.table(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setDefaultRenderer(Object.class, new RosterCell());
        table.setDefaultRenderer(Integer.class, new RosterCell());
        table.setDefaultRenderer(Long.class, new RosterCell());
        table.setDefaultRenderer(Double.class, new RosterCell());
        TableRowSorter<RosterModel> sorter = new TableRowSorter<RosterModel>(model) {
            @Override public void toggleSortOrder(int column) {
                if ((column == 7 || column >= 9) && (getSortKeys().isEmpty() || getSortKeys().get(0).getColumn() != column))
                    setSortKeys(Collections.singletonList(new RowSorter.SortKey(column, SortOrder.DESCENDING)));
                else super.toggleSortOrder(column);
            }
        };
        for (int column = 0; column < model.getColumnCount(); column++)
            if (model.getColumnClass(column) == String.class) sorter.setComparator(column, String.CASE_INSENSITIVE_ORDER);
        table.setRowSorter(sorter);
        table.getTableHeader().setToolTipText("Click a column to sort; click again to reverse. Maxed starts with 8/8 first.");
        table.getAccessibleContext().setAccessibleName("Captured player roster");
        int[] widths = {190, 185, 160, 75, 75, 75, 75, 90, 210, 110, 110};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        for (int i = 9; i < widths.length; i++) runColumns.add(table.getColumnModel().getColumn(i));
        for (TableColumn column : runColumns) table.removeColumn(column);
        rosterScroll.setName("security-roster-scroll");
        rosterScroll.getVerticalScrollBar().setUnitIncrement(40);
        RosterPage page = new RosterPage();
        page.add(rosterScroll, BorderLayout.CENTER);
        JScrollPane pageScroll = new JScrollPane(page);
        pageScroll.setName("security-page-scroll");
        pageScroll.setBorder(null);
        pageScroll.getVerticalScrollBar().setUnitIncrement(40);
        pageScroll.getAccessibleContext().setAccessibleName("Inspect page; scroll for controls at large text sizes");
        add(pageScroll, BorderLayout.CENTER);

        JPanel top = new JPanel(new BorderLayout(8, 4));
        JPanel filterRow = new JPanel(new BorderLayout(8, 0));
        filterComboBox = new JComboBox<>(new String[]{DISABLE_FILTER});
        filterComboBox.setPrototypeDisplayValue("Select an inspect filter");
        filterComboBox.getAccessibleContext().setAccessibleName("Inspect filter");
        filterComboBox.addActionListener(this::comboAction);
        JLabel filterLabel = new JLabel("Filter");
        filterLabel.setLabelFor(filterComboBox);
        filterRow.add(filterLabel, BorderLayout.WEST);
        filterRow.add(filterComboBox, BorderLayout.CENTER);
        JPanel options = ContentStyle.controls();
        options.setVisible(false);
        JToggleButton optionsButton = new JToggleButton("Options");
        optionsButton.getAccessibleContext().setAccessibleDescription("Show copy and sorting options");
        optionsButton.addActionListener(e -> {
            options.setVisible(optionsButton.isSelected());
            optionsButton.getAccessibleContext().setAccessibleDescription(optionsButton.isSelected()
                    ? "Copy and sorting options expanded" : "Copy and sorting options collapsed");
            page.revalidate();
        });
        filterRow.add(optionsButton, BorderLayout.EAST);
        copyOnlyUnderReqCheckbox = new JCheckBox("Only copy below requirements");
        copyOnlyUnderReqCheckbox.setSelected("true".equals(PropertiesManager.getProperty("copyOnlyUnderReqCheckbox")));
        copyOnlyUnderReqCheckbox.addActionListener(e -> PropertiesManager.setProperties(
                "copyOnlyUnderReqCheckbox", Boolean.toString(copyOnlyUnderReqCheckbox.isSelected())));
        options.add(copyOnlyUnderReqCheckbox);
        sortCheckBox = new JCheckBox("Sort by guild");
        sortCheckBox.setSelected(!"false".equals(PropertiesManager.getProperty("sortCheckBox")));
        sortCheckBox.addActionListener(e -> {
            PropertiesManager.setProperties("sortCheckBox", Boolean.toString(sortCheckBox.isSelected()));
            table.getRowSorter().setSortKeys(Collections.emptyList());
            requestRefresh();
        });
        options.add(sortCheckBox);
        top.add(filterRow, BorderLayout.NORTH);
        top.add(options, BorderLayout.CENTER);
        page.add(top, BorderLayout.NORTH);

        JPopupMenu actions = new JPopupMenu("Roster actions");
        actions.getAccessibleContext().setAccessibleName("Roster actions and keyboard shortcuts");
        addSelectionAction(actions, "Copy player", false, false, KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK));
        addSelectionAction(actions, "Open player on RealmEye", false, true, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        addSelectionAction(actions, "Copy guild", true, false, KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        addSelectionAction(actions, "Open guild on RealmEye", true, true, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK));
        Action equipment = action("Equipment details…", e -> selectedEquipmentDetails());
        equipment.putValue(Action.SHORT_DESCRIPTION, "Read all equipment and enchant details for the selected row (Ctrl+E)");
        playerActions.add(equipment);
        addMenuAction(actions, equipment, KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK));
        table.getSelectionModel().addListSelectionListener(e -> updateSelectionActions());

        JPanel buttons = ContentStyle.controls();
        JButton names = new JButton(action("Copy names", e -> {
            if (shiftDown(e)) saveNamesAsText(); else clicked(false);
        }));
        JButton all = new JButton(action("Copy all (JSON)", e -> {
            if (shiftDown(e)) saveAsJson(getFilteredPlayers()); else clicked(true);
        }));
        names.setToolTipText("Copy names; Shift+click exports a text file.");
        all.setToolTipText("Copy JSON; Shift+click exports a JSON file.");
        buttons.add(names);
        buttons.add(all);
        actions.addSeparator();
        addMenuAction(actions, action("Export names…", e -> saveNamesAsText()),
                KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK));
        addMenuAction(actions, action("Export JSON…", e -> saveAsJson(getFilteredPlayers())),
                KeyStroke.getKeyStroke(KeyEvent.VK_J, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK));
        actions.addSeparator();
        actions.add(new JMenuItem(action("Edit filters…", e -> SecurityFilterGUI.open(this))));
        JButton actionsButton = new JButton("Actions…");
        actionsButton.setMnemonic(KeyEvent.VK_A);
        actionsButton.setToolTipText("Player/guild actions, equipment details (Ctrl+E), exports and filters (Alt+A).");
        actionsButton.setComponentPopupMenu(actions);
        actionsButton.addActionListener(e -> {
            SwingUtilities.updateComponentTreeUI(actions);
            ContentStyle.refreshFonts(actions);
            actions.show(actionsButton, 0, actionsButton.getHeight());
            for (Component item : actions.getComponents()) if (item instanceof JMenuItem && item.isEnabled()) {
                MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[]{actions, (JMenuItem) item});
                break;
            }
        });
        buttons.add(actionsButton);
        page.add(buttons, BorderLayout.SOUTH);

        bind("copy-player", KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), false, false);
        bind("open-player", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), false, true);
        bind("copy-guild", KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), true, false);
        bind("open-guild", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), true, true);
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                int row = table.rowAtPoint(e.getPoint());
                int column = table.columnAtPoint(e.getPoint());
                if (row < 0 || column < 0) return;
                column = table.convertColumnIndexToModel(column);
                if (column == 0 || column == 1) {
                    table.setRowSelectionInterval(row, row);
                    selectedAction(column == 1, e.isControlDown());
                }
            }
        });
        refreshTimer = new javax.swing.Timer(100, e -> refreshRoster());
        refreshTimer.setCoalesce(true);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (isShowing()) { refreshRoster(); refreshTimer.start(); }
                else refreshTimer.stop();
            }
        });
        loadFilters();
        updateSelectionActions();
        ContentStyle.refreshFonts(this);
        if (liveOwner) INSTANCE = this;
    }

    @Override public void updateUI() {
        super.updateUI();
        // JPanel calls updateUI before these fields are initialized. Invalidate only;
        // the visible refresh timer (or refresh-on-show) rebuilds themed icon rows.
        if (rosterLock != null) synchronized (rosterLock) { themeRevision++; revision++; }
    }

    private static Action action(String name, java.util.function.Consumer<ActionEvent> handler) {
        return new AbstractAction(name) {
            @Override public void actionPerformed(ActionEvent e) { handler.accept(e); }
        };
    }

    private static boolean shiftDown(ActionEvent e) {
        return (e.getModifiers() & (ActionEvent.SHIFT_MASK | InputEvent.SHIFT_DOWN_MASK)) != 0;
    }

    private void addSelectionAction(JPopupMenu menu, String name, boolean guild, boolean open, KeyStroke shortcut) {
        Action action = action(name, e -> selectedAction(guild, open));
        (guild ? guildActions : playerActions).add(action);
        JMenuItem item = new JMenuItem(action);
        item.setAccelerator(shortcut);
        item.getAccessibleContext().setAccessibleDescription("For the selected roster row; " + shortcut);
        menu.add(item);
    }

    private void addMenuAction(JPopupMenu menu, Action action, KeyStroke shortcut) {
        JMenuItem item = new JMenuItem(action);
        item.setAccelerator(shortcut);
        menu.add(item);
        String name = String.valueOf(action.getValue(Action.NAME));
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(shortcut, name);
        getActionMap().put(name, action);
    }

    private void bind(String name, KeyStroke key, boolean guild, boolean open) {
        table.getInputMap().put(key, name);
        table.getActionMap().put(name, action(name, e -> selectedAction(guild, open)));
    }

    private Row selectedRow() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= table.getRowCount()) return null;
        int index = table.convertRowIndexToModel(row);
        return index < model.rows.size() ? model.rows.get(index) : null;
    }

    private void updateSelectionActions() {
        Row row = selectedRow();
        playerActions.forEach(a -> a.setEnabled(row != null));
        guildActions.forEach(a -> a.setEnabled(row != null && !row.guild.isEmpty()));
    }

    private void selectedAction(boolean guild, boolean open) {
        Row row = selectedRow();
        if (row == null) return;
        String value = guild ? row.guild : row.player.playerEntity.name();
        if (value == null || value.isEmpty()) return;
        if (open) openWebpage("https://www.realmeye.com/" + (guild ? "guild/" : "player/") + value.replace(" ", "%20"));
        else copyToClipboard(value);
    }

    private void selectedEquipmentDetails() {
        Row row = selectedRow();
        if (row == null) return;
        // Use the displayed row, never the newer producer roster or the current filter.
        showEquipmentDetails(inspectionName(row), inspectionDetails(row));
    }

    /** Opens the same Inspect details for a detached player from another view or saved encounter. */
    public static void inspectPlayer(Component owner, InspectSnapshot captured) {
        if (captured == null) return;
        Row row = detachedRow(captured);
        showEquipmentDetails(owner, inspectionName(row), inspectionDetails(row));
    }

    static String detachedDetails(InspectSnapshot captured) {
        return inspectionDetails(detachedRow(captured));
    }

    private static Row detachedRow(InspectSnapshot captured) {
        Entity entity = captured.toEntity();
        CapturedPlayer player = snapshot(entity.id, entity);
        player.className = captured.className();
        player.origin = "Detached recorded build · Source session/run not supplied";
        return new Row(player, 0, null);
    }

    private static String inspectionName(Row row) {
        String name = row.player.playerEntity.name();
        return name == null || name.isEmpty() ? "Player #" + row.player.playerEntity.id : name;
    }

    private static String inspectionDetails(Row row) {
        Entity entity = row.player.playerEntity;
        StatData level = entity.stat.get(StatType.LEVEL_STAT);
        String clazz = Objects.toString(row.player.className, Objects.toString(CharacterClass.getName(entity.objectType), "Unknown class"));
        String mode = Player.modeDescription(entity);
        return row.player.origin + "\nRecorded build/change time: " + (row.player.recordedAt > 0 ? DisplayFormat.formatTimestamp(row.player.recordedAt) : "Not captured")
                + "\nThis is the retained loadout, not continuous last-seen evidence or equipment at every hit."
                + "\n\nPlayer: " + inspectionName(row) + "\nClass: " + clazz + "\nLevel: " + (level == null ? "Not captured" : level.statValue)
                + "\nGuild: " + (entity.getStatGuild() == null ? "Not captured" : row.guild.isEmpty() ? "None (captured)" : row.guild) + "\nCharacter mode: " + mode
                + "\n\n" + row.player.statsDescription() + "\n\n" + String.join("\n\n", row.equipmentDetails);
    }

    protected void showEquipmentDetails(String playerName, String details) {
        showEquipmentDetails(this, playerName, details);
    }

    private static void showEquipmentDetails(Component owner, String playerName, String details) {
        Window window = owner == null ? null : owner instanceof Window ? (Window)owner : SwingUtilities.getWindowAncestor(owner);
        JDialog dialog = new JDialog(window, "Equipment — " + playerName,
                Dialog.ModalityType.MODELESS);
        dialog.setName("security-equipment-dialog");
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JTextArea body = ContentStyle.wrappingText(details, 8);
        body.setName("security-equipment-details");
        body.setFocusable(true);
        body.getAccessibleContext().setAccessibleName("Equipment and enchant details for " + playerName);
        body.getAccessibleContext().setAccessibleDescription("Read-only snapshot of the selected roster row. Select text to copy.");
        body.setCaretPosition(0);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JScrollPane scroll = new JScrollPane(body);
        FontMetrics metrics = body.getFontMetrics(body.getFont());
        scroll.setPreferredSize(new Dimension(Math.min(720, metrics.charWidth('m') * 60), Math.min(480, metrics.getHeight() * 16)));
        content.add(scroll, BorderLayout.CENTER);
        JButton close = new JButton("Close"); close.addActionListener(e -> dialog.dispose());
        content.add(close, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(close);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), WHEN_IN_FOCUSED_WINDOW);
        ContentStyle.refreshFonts(content);
        dialog.pack();
        Rectangle bounds = dialog.getGraphicsConfiguration().getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(dialog.getGraphicsConfiguration());
        dialog.setSize(Math.min(dialog.getWidth(), bounds.width - insets.left - insets.right),
                Math.min(dialog.getHeight(), bounds.height - insets.top - insets.bottom));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        body.requestFocusInWindow();
    }

    private void loadFilters() {
        filters.clear();
        currentFilter = null;
        guiUpdateSuppression = true;
        String saved = PropertiesManager.getProperty("securityFilters");
        if (saved != null && !saved.isEmpty()) for (String json : saved.split("§")) {
            SecurityFilter filter = SecurityFilter.loadJson(json);
            if (filter != null) filters.put(filter.name, filter);
        }
        for (String name : filters.keySet()) filterComboBox.addItem(name);
        currentFilter = filters.get(Objects.toString(PropertiesManager.getProperty("securityFilterName"), ""));
        filterComboBox.setSelectedItem(currentFilter == null ? DISABLE_FILTER : currentFilter.name);
        guiUpdateSuppression = false;
        copyOnlyUnderReqCheckbox.setEnabled(currentFilter != null);
    }

    private void comboAction(ActionEvent e) {
        if (guiUpdateSuppression) return;
        currentFilter = filters.get(String.valueOf(filterComboBox.getSelectedItem()));
        PropertiesManager.setProperties("securityFilterName", currentFilter == null ? "" : currentFilter.name);
        copyOnlyUnderReqCheckbox.setEnabled(currentFilter != null);
        requestRefresh();
    }

    TreeMap<String, SecurityFilter> getFilters() { return filters; }

    public void filterUpdate() {
        String selected = currentFilter == null ? DISABLE_FILTER : currentFilter.name;
        guiUpdateSuppression = true;
        filterComboBox.removeAllItems();
        filterComboBox.addItem(DISABLE_FILTER);
        for (String name : filters.keySet()) filterComboBox.addItem(name);
        filterComboBox.setSelectedItem(filters.containsKey(selected) ? selected : DISABLE_FILTER);
        guiUpdateSuppression = false;
        comboAction(null);
    }

    private void requestRefresh() {
        synchronized (rosterLock) { revision++; }
    }

    /** Runs only on the EDT, at most once per timer tick, and never while hidden. */
    private void refreshRoster() {
        if (!isShowing()) return;
        List<CapturedPlayer> players;
        long nextRevision;
        synchronized (rosterLock) {
            if (displayedRevision == revision) return;
            nextRevision = revision;
            players = historicalPlayers == null ? new ArrayList<>(roster.values()) : new ArrayList<>(historicalPlayers);
        }
        Row selected = selectedRow();
        Map<String, Row> previous = new HashMap<>();
        for (Row row : model.rows) previous.put(rowKey(row.player), row);
        List<Row> rows = new ArrayList<>(players.size());
        for (CapturedPlayer player : players) {
            Row row = previous.get(rowKey(player));
            if (row == null || row.player != player || row.themeRevision != themeRevision)
                row = new Row(player, themeRevision, inspectedRun);
            rows.add(row);
        }
        if (sortCheckBox.isSelected()) rows.sort(Comparator.comparing((Row r) -> r.guild.isEmpty())
                .thenComparing(r -> r.guild, String.CASE_INSENSITIVE_ORDER));
        model.rows = rows;
        displayedRevision = nextRevision;
        model.fireTableDataChanged();
        if (selected != null) for (int i = 0; i < rows.size(); i++) {
            if (rowKey(rows.get(i).player).equals(rowKey(selected.player))) {
                int view = table.convertRowIndexToView(i);
                if (view >= 0) table.setRowSelectionInterval(view, view);
                break;
            }
        }
        updateSelectionActions();
    }

    void showCurrentArea() {
        displayedRun = null;
        inspectedRun = null;
        showRunColumns(false);
        synchronized (rosterLock) { historicalPlayers = null; }
        table.clearSelection();
        requestRefresh();
        refreshRoster();
    }

    void showRun(String id, Collection<InspectSnapshot> players) {
        showRun(id, players, null);
    }

    void showRun(ActivityJournal.Visit visit) { showRun(visit.id, visit.inspectedPlayers.values(), visit); }

    private void showRun(String id, Collection<InspectSnapshot> players, ActivityJournal.Visit visit) {
        if (!Objects.equals(displayedRun, id)) table.clearSelection();
        displayedRun = id;
        inspectedRun = visit;
        showRunColumns(true);
        List<CapturedPlayer> captured = new ArrayList<>();
        for (InspectSnapshot player : players) {
            Entity entity = player.toEntity();
            CapturedPlayer row=snapshot(entity.id, entity);row.className=player.className();
            row.recordedAt=player.observedAt();
            row.origin="Recorded run: " + id + (visit == null ? "" : " · " + visit.map) + " · Last recorded loadout";
            captured.add(row);
        }
        synchronized (rosterLock) { historicalPlayers = captured; }
        requestRefresh();
        refreshRoster();
    }

    private void showRunColumns(boolean visible) {
        if (runColumnsVisible == visible) return;
        runColumnsVisible = visible;
        for (TableColumn column : runColumns) { if (visible) table.addColumn(column); else table.removeColumn(column); }
        if (!visible) {
            List<RowSorter.SortKey> keys = new ArrayList<>();
            for (RowSorter.SortKey key : table.getRowSorter().getSortKeys()) if (key.getColumn() < 9) keys.add(key);
            table.getRowSorter().setSortKeys(keys);
        }
    }

    private String rowKey(CapturedPlayer player) {
        Entity entity = player.playerEntity;
        return historicalPlayers == null ? "object:" + entity.id : player.historyKey();
    }

    private static final StatType[] DISPLAY_STATS = {StatType.NAME_STAT, StatType.GUILD_NAME_STAT,
            StatType.LEVEL_STAT, StatType.SKIN_ID, StatType.SEASONAL, StatType.CRUCIBLE_STAT,
            StatType.INVENTORY_0_STAT, StatType.INVENTORY_1_STAT, StatType.INVENTORY_2_STAT,
            StatType.INVENTORY_3_STAT, StatType.UNIQUE_DATA_STRING};

    // Copy values on the producer thread, before the mutable capture entity can change again.
    private static final class CapturedPlayer extends Player {
        String className;
        String origin = "Current area · Detached captured build";
        long recordedAt;
        final boolean[] equipmentCaptured;
        CapturedPlayer(Entity entity, boolean[] equipmentCaptured) {
            super(entity);
            this.equipmentCaptured = equipmentCaptured;
            className=CharacterClass.getName(entity.objectType);
        }
        String historyKey() {
            return InspectSnapshot.playerKey(playerEntity.id, playerEntity.getStatName());
        }
    }

    private static CapturedPlayer snapshot(int id, Entity source) {
        Entity copy = new Entity(null, id, 0);
        copy.markPlayerIdentity();
        boolean[] equipmentCaptured = new boolean[4];
        copy.objectType = source.objectType;
        copy.baseStats = source.baseStats == null ? new int[]{-1, -1, -1, -1, -1, -1, -1, -1} : source.baseStats.clone();
        for (StatType type : DISPLAY_STATS) {
            StatData original = source.stat.get(type);
            if (original == null) continue;
            StatData value = new StatData();
            value.statType = type;
            value.statTypeNum = type.get();
            value.statValue = original.statValue;
            value.statValueTwo = original.statValueTwo;
            value.stringStatValue = original.stringStatValue;
            int slot = type.get() - StatType.INVENTORY_0_STAT.get();
            if (slot >= 0 && slot < 4) equipmentCaptured[slot] = true;
            copy.stat.set(type, value);
        }
        return new CapturedPlayer(copy, equipmentCaptured);
    }

    public static void addPlayer(int id, Entity entity) {
        ParsePanelGUI panel = INSTANCE;
        if (panel == null || entity == null) return;
        CapturedPlayer copy = snapshot(id, entity);
        synchronized (panel.rosterLock) { panel.roster.put(id, copy); if (panel.historicalPlayers == null) panel.revision++; }
    }

    public static void update(Entity entity) {
        ParsePanelGUI panel = INSTANCE;
        if (panel == null || entity == null) return;
        synchronized (panel.rosterLock) {
            if (!panel.roster.containsKey(entity.id)) return;
        }
        CapturedPlayer copy = snapshot(entity.id, entity);
        synchronized (panel.rosterLock) {
            CapturedPlayer previous = panel.roster.get(entity.id);
            if (previous == null || sameDisplay(previous, copy)) return;
            panel.roster.put(entity.id, copy);
            if (panel.historicalPlayers == null) panel.revision++;
        }
    }

    private static boolean sameDisplay(CapturedPlayer a, CapturedPlayer b) {
        if (!Arrays.equals(a.equipmentCaptured, b.equipmentCaptured)) return false;
        if (a.playerEntity.objectType != b.playerEntity.objectType || !Arrays.equals(a.playerEntity.baseStats, b.playerEntity.baseStats)) return false;
        for (StatType type : DISPLAY_STATS) {
            StatData x = a.playerEntity.stat.get(type), y = b.playerEntity.stat.get(type);
            if (x == null || y == null) { if (x != y) return false; else continue; }
            if (x.statValue != y.statValue || x.statValueTwo != y.statValueTwo || !Objects.equals(x.stringStatValue, y.stringStatValue)) return false;
        }
        return true;
    }

    public static void removePlayer(int id) {
        ParsePanelGUI panel = INSTANCE;
        if (panel == null) return;
        synchronized (panel.rosterLock) { if (panel.roster.remove(id) != null && panel.historicalPlayers == null) panel.revision++; }
    }

    public static void clear() {
        ParsePanelGUI panel = INSTANCE;
        if (panel == null) return;
        synchronized (panel.rosterLock) { panel.roster.clear(); if (panel.historicalPlayers == null) panel.revision++; }
    }

    public static void update() {
        ParsePanelGUI panel = INSTANCE;
        if (panel != null) panel.requestRefresh();
    }

    public static void editFont(Font font) {
        ParsePanelGUI panel = INSTANCE;
        if (panel == null || font == null) return;
        Runnable change = () -> { ContentStyle.tableFont(panel.table, font, 0); panel.revalidate(); panel.repaint(); };
        if (SwingUtilities.isEventDispatchThread()) change.run(); else SwingUtilities.invokeLater(change);
    }

    private List<Player> getFilteredPlayers() {
        List<Player> players;
        synchronized (rosterLock) { players = historicalPlayers == null ? new ArrayList<>(roster.values()) : new ArrayList<>(historicalPlayers); }
        if (currentFilter != null && copyOnlyUnderReqCheckbox.isSelected())
            players.removeIf(p -> !currentFilter.parsePlayer(p).isUnderReqs);
        return players;
    }

    protected void clicked(boolean full) {
        List<Player> players = getFilteredPlayers();
        if (full) copyToClipboard(json(players));
        else {
            StringJoiner names = new StringJoiner(" ");
            for (Player player : players) names.add(player.playerEntity.name());
            copyToClipboard(names.toString());
        }
    }

    private static String json(List<Player> players) {
        StringJoiner result = new StringJoiner(",\n", "[\n", "\n]");
        for (Player player : players) result.add(player.toString());
        return result.toString();
    }

    protected void saveNamesAsText() {
        StringBuilder names = new StringBuilder();
        for (Player player : getFilteredPlayers()) names.append(player.playerEntity.name()).append('\n');
        saveToFile(names.toString(), "ExportNames", ".txt");
    }

    protected void saveAsJson(List<Player> players) { saveToFile(json(players), "Export", ".json"); }

    private static void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private void saveToFile(String content, String prefix, String extension) {
        try {
            File directory = new File("exports");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create exports directory");
            File file = new File(directory, prefix + new SimpleDateFormat("MMddyyyy_HHmmss").format(new Date()) + extension);
            try (FileWriter writer = new FileWriter(file)) { writer.write(content); }
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Transferable() {
                public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.javaFileListFlavor}; }
                public boolean isDataFlavorSupported(DataFlavor flavor) { return DataFlavor.javaFileListFlavor.equals(flavor); }
                public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                    if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
                    return Collections.singletonList(file);
                }
            }, null);
            JOptionPane.showMessageDialog(this, "Successfully exported data to:\n" + file.getAbsolutePath()
                    + "\n\n(File object copied to clipboard - ready to paste)", "Export successful", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Failed to export data:\n" + e.getMessage(), "Export failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void openWebpage(String url) {
        try { Desktop.getDesktop().browse(new URI(url)); }
        catch (Exception e) { System.err.println("Failed to open webpage for URL: " + url); }
    }

    private static String html(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    private static class Row {
        final long themeRevision;
        final CapturedPlayer player;
        final String guild;
        final Long damage;
        final Double dps;
        final String[] equipmentLabels = new String[4], equipmentDetails = new String[4];
        final ImageIcon[] icons = new ImageIcon[4];
        final ImageIcon skin;
        Row(CapturedPlayer player, long themeRevision, ActivityJournal.Visit run) {
            this.player = player;
            this.themeRevision = themeRevision;
            guild = Objects.toString(player.playerEntity.getStatGuild(), "");
            damage = run == null ? null : run.damage(player.historyKey());
            dps = run == null ? null : run.dps(damage);
            ParseEnchants.EquippedCapture capture = ParseEnchants.equippedCapture(player.playerEntity);
            skin = ImageBuffer.getOutlinedIcon(player.getSkinId(), 20);
            for (int i = 0; i < 4; i++) {
                player.itemName[i] = IdToAsset.objectName(player.inv[i]);
                String slot = Player.equipmentNames[i];
                slot = Character.toUpperCase(slot.charAt(0)) + slot.substring(1);
                String item = !player.equipmentCaptured[i] ? "Not captured"
                        : player.inv[i] < 0 ? "Empty (ID " + player.inv[i] + ")"
                        : (player.itemName[i] == null ? "Unrecognized item" : player.itemName[i]) + " (ID " + player.inv[i] + ")";
                equipmentLabels[i] = slot + ": " + item;
                String enchant = capture.description(i).trim();
                boolean known = capture.state(i) == ParseEnchants.CaptureState.KNOWN;
                equipmentDetails[i] = equipmentLabels[i] + "\nEnchants: "
                        + (known && enchant.isEmpty() ? "None (captured)" : enchant);
                int count = !known || enchant.isEmpty() ? 0 : enchant.split("\n").length;
                String role = count == 1 ? "mint" : count == 2 ? "blue" : count == 3 ? "violet" : "amber";
                icons[i] = count == 0 ? ImageBuffer.getOutlinedIcon(player.inv[i], 20)
                        : ImageBuffer.getOutlinedIconWithGlow(player.inv[i], 20, ContentStyle.color(role), 3);
            }
        }
    }

    /** The whole page scrolls only when controls plus three roster rows cannot fit. */
    private class RosterPage extends JPanel implements Scrollable {
        RosterPage() { super(new BorderLayout(0, 8)); }
        @Override public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            int chrome = size.height - rosterScroll.getPreferredSize().height;
            Insets border = rosterScroll.getInsets();
            Insets viewportBorder = rosterScroll.getViewportBorder() == null ? new Insets(0, 0, 0, 0)
                    : rosterScroll.getViewportBorder().getBorderInsets(rosterScroll);
            int rosterHeight = table.getRowHeight() * 3 + table.getTableHeader().getPreferredSize().height
                    + rosterScroll.getHorizontalScrollBar().getPreferredSize().height
                    + border.top + border.bottom + viewportBorder.top + viewportBorder.bottom;
            return new Dimension(size.width, chrome + rosterHeight);
        }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return table.getRowHeight(); }
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(table.getRowHeight(), visible.height - table.getRowHeight());
        }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport && getParent().getHeight() >= getPreferredSize().height;
        }
    }

    private static class RosterModel extends AbstractTableModel {
        private final String[] columns = {"Player / level", "Guild", "Class", "Weapon", "Ability", "Armor", "Ring", "Maxed", "Character mode", "Damage", "DPS"};
        private List<Row> rows = new ArrayList<>();
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int column) { return columns[column]; }
        public Class<?> getColumnClass(int column) { return column == 7 ? Integer.class : column == 9 ? Long.class : column == 10 ? Double.class : String.class; }
        public Object getValueAt(int index, int column) {
            Row row = rows.get(index);
            Entity entity = row.player.playerEntity;
            switch (column) {
                case 0: return entity.name() + " [" + (entity.stat.get(StatType.LEVEL_STAT) == null ? "Not captured" : entity.stat.get(StatType.LEVEL_STAT).statValue) + "]";
                case 1: return row.guild;
                case 2: return Objects.toString(row.player.className, Objects.toString(CharacterClass.getName(entity.objectType), "Unknown class"));
                case 7: return row.player.statsMaxed()<0?null:row.player.statsMaxed();
                case 8: return Player.modeDescription(entity);
                case 9: return row.damage;
                case 10: return row.dps;
                default: return row.equipmentLabels[column - 3];
            }
        }
    }

    private class RosterCell extends ContentStyle.Cell {
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int rowIndex, int columnIndex) {
            super.getTableCellRendererComponent(table, value, selected, focus, rowIndex, columnIndex);
            Row row = model.rows.get(table.convertRowIndexToModel(rowIndex));
            int column = table.convertColumnIndexToModel(columnIndex);
            setToolTipText(null);
            if (column == 7) setText(value==null?DisplayFormat.UNAVAILABLE:value + " / 8");
            if (column == 9) setText(DisplayFormat.formatInteger((Long)value));
            if (column == 10) setText(value == null ? DisplayFormat.UNAVAILABLE : DisplayFormat.formatNumber(((Number)value).doubleValue(), 0, 1));
            String name = model.getColumnName(column) + ": " + getText();
            getAccessibleContext().setAccessibleName(name);
            getAccessibleContext().setAccessibleDescription(name);
            if (focus) setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(ContentStyle.color("violet"), 2), BorderFactory.createEmptyBorder(0, 6, 0, 6)));
            if (column == 0) {
                setIcon(row.skin);
                setToolTipText("Click to copy player; Ctrl+click or Enter to open RealmEye. Ctrl+C copies the selected player.");
            } else if (column == 1) {
                setToolTipText(row.guild + " — click to copy; Ctrl+click or Ctrl+Enter opens RealmEye.");
            } else if (column >= 3 && column <= 6) {
                int slot = column - 3;
                setText(""); setIcon(row.icons[slot]); setHorizontalAlignment(CENTER);
                setToolTipText("<html>" + html(row.equipmentDetails[slot]) + "</html>");
                getAccessibleContext().setAccessibleName(row.equipmentLabels[slot]);
                getAccessibleContext().setAccessibleDescription(row.equipmentDetails[slot]);
            } else if (column == 7) {
                setToolTipText("<html>" + html(row.player.statsDescription()) + "</html>");
            } else if (column == 8 && !selected) {
                boolean seasonal = Boolean.TRUE.equals(Player.seasonal(row.player.playerEntity)), crucible = Boolean.TRUE.equals(Player.crucible(row.player.playerEntity));
                setForeground(ContentStyle.color(crucible ? (seasonal ? "violet" : "amber") : seasonal ? "mint" : "muted"));
            } else if (column >= 9) {
                setToolTipText(column == 9 ? "Captured outgoing damage in this dungeon; missing older recordings are shown as —."
                        : "Damage per second over the dungeon's shared first-to-last captured hit window. A single timestamp has no measurable DPS. Gear is the last captured loadout.");
            }
            return this;
        }
    }
}
