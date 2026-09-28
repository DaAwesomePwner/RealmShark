package tomato.gui.dps;

import packets.incoming.*;
import packets.Packet;
import packets.data.ObjectData;
import packets.outgoing.PlayerShootPacket;
import packets.outgoing.EnemyHitPacket;
import packets.data.enums.StatType;
import tomato.backend.data.*;
import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import javax.swing.event.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.*;
import java.util.List;
import tomato.gui.history.HistoryTables;
import tomato.gui.kit.Chip;
import tomato.gui.kit.ColumnKind;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.KitButton;
import tomato.gui.kit.Sprites;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.modern.LineIcon;

/**
 * Interactive encounter overview with a stable selection during live updates. Players rank by the chosen metric
 * (true rank, fixed-width prefix), carry their class hue and sprite, and your row keeps the accent wash; enemies are
 * cards with a Boss chip; a player's hit details open in a drawer under the table, with Explore in a footer that is
 * always visible. A refill adds the enemy list in one model event and the renderers reuse their borders, derived
 * fonts and number formats (S8: a 300-enemy refill made about 45,000 renderer calls one element at a time).
 */
public class MeterDpsGUI extends DisplayDpsGUI {
    private static final int[] METRIC_COLUMNS = {2, 3, 5, 8, 9};
    private final JComboBox<String> enemySort = new JComboBox<>(new String[]{"Highest enemy HP", "Latest hit", "Longest fight", "Bosses only"});
    private final JComboBox<String> classes = new JComboBox<>(new String[]{"All classes"});
    private final JComboBox<String> metric = new JComboBox<>(new String[]{"Damage", "DPS", "Hits dealt", "Damage taken", "Hits taken"});
    private final JTextField search = new JTextField(12);
    private final JCheckBox colors = new JCheckBox("Class colors", true);
    private final EnemyModel enemies = new EnemyModel();
    private final JList<Entity> enemyList = new JList<>(enemies);
    private final JLabel summary = new JLabel("Waiting for combat");
    private final JLabel scope = new JLabel(" ") {
        @Override public void updateUI() { super.updateUI(); setForeground(ContentStyle.color("muted")); }
    };
    private final JTextArea details = new JTextArea("Select a player to inspect individual hits.");
    private final JTextArea captureWarning = new JTextArea() {
        @Override public void updateUI() { super.updateUI(); setForeground(ContentStyle.color("amber")); }
    };
    private final JButton explore = new JButton("Explore all events…");
    private final JLabel exploreReason = new JLabel("Select a player first");
    /** Hit details drawer: a header "Details · name · class ×" over the details, shown only while a player is selected. */
    private final JPanel drawer = new JPanel(new BorderLayout());
    private final JLabel drawerTitle = new JLabel(" ");
    private final KitButton detailsClose = KitButton.icon(new LineIcon(LineIcon.CLOSE, 12), "Close details (Esc)");
    private boolean detailsOpen, placeDrawer;
    /** Divider location the reader last left the open drawer at; -1 until it has been open with a size. */
    private int drawerLocation = -1;
    /** Rows the damage table keeps visible in {@link #usableHeight()}. */
    static final int USABLE_TABLE_ROWS = 4;
    private JComponent header;
    private JPanel tableArea;
    private JScrollPane tableScroll, detailScroll;
    private JSplitPane right, split;
    /** Player object ID → 1-based rank by the chosen metric over every row of the scope; filters and header sorts never renumber. */
    private Map<Integer, Integer> ranks = Collections.emptyMap();
    /** Recorded damage per enemy card, summed once per enemy refill. */
    private final Map<Entity, Long> damageByEnemy = new IdentityHashMap<>();
    /** EDT-only number formats shared by the renderers. */
    private final Formats formats = new Formats();
    /** Derived table fonts, rebuilt only when the table font object changes (font edits and theme refreshes replace it). */
    private Font tableFont, tableEmphasis, tableMetadata;
    /** The mode the enemy cards were last measured in; object IDs show only in Analyst. */
    private DisplayModeModel.Mode shownMode = DisplayModeModel.application().mode();
    /** Selected player, metric and encounter the hit details were last written for; a change starts them at the top. */
    private Object detailsKey;
    private final JTextArea routeNotice = new JTextArea() {
        @Override public void updateUI() { super.updateUI(); setForeground(ContentStyle.color("violet")); }
    };
    private final MeterModel model = new MeterModel();
    private final JTable table = new JTable(model);
    private List<Entity> targets = new ArrayList<>();
    private List<CombatMeterData.Row> visible = new ArrayList<>();
    private CombatMeterData snapshot = new CombatMeterData(Collections.emptyList(), null);
    private Entity localPlayer;
    private DpsData.LocalPlayerContext playerContext;
    private Object encounter;
    private String inspectOrigin;
    private boolean updating;
    private String mapName = "No encounter";
    private boolean live;
    private boolean wholeEncounter;
    private boolean missingLocalSpawn;
    private int scopedEnemies;
    private double meterMaximum;

    public MeterDpsGUI() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        ContentStyle.font(summary, ContentStyle.emphasis(ContentStyle.body()));
        summary.putClientProperty("html.disable", true);
        ContentStyle.font(scope, ContentStyle.metadata(ContentStyle.body()));
        JPanel filters = ContentStyle.controls();
        filters.add(new JLabel("Rank by")); filters.add(metric); filters.add(classes);
        filters.add(new JLabel("Player")); filters.add(search); filters.add(colors);
        summary.setAlignmentX(LEFT_ALIGNMENT); scope.setAlignmentX(LEFT_ALIGNMENT);
        filters.setAlignmentX(LEFT_ALIGNMENT);
        controls.add(summary); controls.add(filters); controls.add(scope);
        captureWarning.setEditable(false); captureWarning.setFocusable(false);
        captureWarning.setLineWrap(true); captureWarning.setWrapStyleWord(true);
        captureWarning.setOpaque(false); ContentStyle.font(captureWarning, ContentStyle.body());
        captureWarning.setVisible(false);
        header = new JPanel(new BorderLayout(0, 5));
        JPanel notices = new JPanel(new BorderLayout(0, 4));
        notices.add(captureWarning, BorderLayout.NORTH); notices.add(routeNotice, BorderLayout.SOUTH);
        header.add(controls, BorderLayout.CENTER); header.add(notices, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);
        JPanel left = new JPanel(new BorderLayout(4, 4));
        left.add(enemySort, BorderLayout.NORTH);
        enemyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ContentStyle.font(enemyList, ContentStyle.body());
        enemyList.setCellRenderer(new EnemyCardRenderer());
        left.add(new JScrollPane(enemyList), BorderLayout.CENTER);
        ContentStyle.table(table); table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setName("dps-player-table");
        PlayerInspectMenu.install(table, row -> visible.get(row).player, () -> inspectOrigin);
        rank();
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        Map<String, ColumnKind> kinds = new HashMap<>();
        kinds.put("Player / meter", ColumnKind.TEXT); kinds.put("Class", ColumnKind.CLASS); kinds.put("Recorded share %", ColumnKind.PERCENT);
        for (String number : new String[]{"Damage", "DPS", "Avg hit", "Max hit", "Taken (est.)"}) kinds.put(number, ColumnKind.NUMBER);
        for (String count : new String[]{"Hits dealt", "Hits taken"}) kinds.put(count, ColumnKind.COUNT);
        HistoryTables.kinds(table, kinds);
        table.getColumnModel().getColumn(0).setCellRenderer(new BarRenderer());
        table.getColumnModel().getColumn(1).setCellRenderer(new ContentStyle.Cell() {
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                CombatMeterData.Row entry = visible.get(t.convertRowIndexToModel(row));
                if (!selected) setForeground(colors.isSelected() ? classColor(entry.player.objectType) : ContentStyle.color("muted"));
                setFont(tableMetadata(t.getFont()));
                // The sprite names the class beside its hue, so color is never the only cue.
                setIcon(Sprites.sprite(entry.player.objectType, 16)); setIconTextGap(Tokens.XS);
                washMine(entry, selected, this); return this;
            }
        });
        DefaultTableCellRenderer numeric = new ContentStyle.Cell() {
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                int c = t.convertColumnIndexToModel(column);
                if (c == 4) setText(value == null ? DisplayFormat.UNAVAILABLE : formats.percentage(((Number)value).doubleValue()));
                setHorizontalAlignment(RIGHT);
                if (c == 2 || c == 3) setFont(tableEmphasis(t.getFont()));
                if (!selected && (value == null || c >= 8 || c == 3)) setForeground(ContentStyle.color(value == null ? "muted" : c >= 8 ? "rose" : "violet"));
                washMine(visible.get(t.convertRowIndexToModel(row)), selected, this);
                return this;
            }
            protected void setValue(Object value) {
                setText(value == null ? "—" : value instanceof Double ? formats.decimal(((Number)value).doubleValue()) : formats.integer(((Number)value).longValue()));
                setHorizontalAlignment(RIGHT);
            }
        };
        table.setDefaultRenderer(Long.class, numeric); table.setDefaultRenderer(Double.class, numeric);
        details.setName("dps-hit-details"); details.setEditable(false); ContentStyle.font(details, ContentStyle.report(ContentStyle.body()));
        details.setMargin(new Insets(6, 8, 6, 8));
        explore.setName("dps-explore-events"); explore.setEnabled(false);
        explore.setToolTipText("Page through every retained hit of the selected player with filters and event-time loadouts");
        explore.addActionListener(e -> exploreSelected());
        routeNotice.setName("dps-route-notice"); routeNotice.setEditable(false); routeNotice.setFocusable(true);
        routeNotice.setLineWrap(true); routeNotice.setWrapStyleWord(true); routeNotice.setOpaque(false); routeNotice.setVisible(false);
        routeNotice.getAccessibleContext().setAccessibleName("Historical recorded DPS notice");
        ContentStyle.font(routeNotice, ContentStyle.body());
        // Explore and its reason sit in a one-line footer under the table that is always visible, so the reason a
        // disabled Explore gives shows before any row is selected; the hit details open in the drawer below only
        // for a selected player, and keep a two-line floor so short or scaled windows do not squeeze them to a sliver.
        JPanel footer = new JPanel(new BorderLayout(Tokens.S, 0));
        footer.setName("dps-details-footer"); footer.setBorder(BorderFactory.createEmptyBorder(Tokens.XS, 0, 0, 0));
        footer.add(explore, BorderLayout.WEST);
        // The disabled reason stays visible beside the button, not only in its tooltip.
        exploreReason.setName("dps-explore-reason"); exploreReason.setHorizontalAlignment(SwingConstants.LEADING);
        ContentStyle.font(exploreReason, ContentStyle.metadata(ContentStyle.body()));
        footer.add(exploreReason, BorderLayout.CENTER);
        detailScroll = new JScrollPane(details) {
            @Override public Dimension getMinimumSize() {
                Insets border = getInsets(), text = details.getInsets();
                int height = details.getFontMetrics(details.getFont()).getHeight() * 2 + text.top + text.bottom + border.top + border.bottom
                    + getHorizontalScrollBar().getPreferredSize().height;
                return new Dimension(super.getMinimumSize().width, height);
            }
        };
        detailScroll.setName("dps-hit-details-scroll");
        drawerTitle.setName("dps-details-title"); drawerTitle.putClientProperty("html.disable", true);
        ContentStyle.font(drawerTitle, ContentStyle.emphasis(ContentStyle.body()));
        detailsClose.setName("dps-details-close"); detailsClose.addActionListener(e -> closeDetails());
        JPanel drawerHeader = new JPanel(new BorderLayout(Tokens.S, 0));
        drawerHeader.setBorder(BorderFactory.createEmptyBorder(Tokens.XS, 0, Tokens.XS, 0));
        drawerHeader.add(drawerTitle, BorderLayout.CENTER); drawerHeader.add(detailsClose, BorderLayout.EAST);
        drawer.setName("dps-details-drawer");
        drawer.add(drawerHeader, BorderLayout.NORTH); drawer.add(detailScroll, BorderLayout.CENTER);
        drawer.setVisible(false);
        tableScroll = new JScrollPane(table) {
            @Override public Dimension getMinimumSize() {
                // USABLE_TABLE_ROWS rows with the column header and horizontal scroll bar.
                Insets border = getInsets();
                return new Dimension(super.getMinimumSize().width, table.getTableHeader().getPreferredSize().height
                    + table.getRowHeight() * USABLE_TABLE_ROWS + getHorizontalScrollBar().getPreferredSize().height + border.top + border.bottom);
            }
        };
        tableScroll.setName("dps-player-table-scroll");
        tableArea = new JPanel(new BorderLayout());
        tableArea.add(tableScroll, BorderLayout.CENTER); tableArea.add(footer, BorderLayout.SOUTH);
        right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableArea, drawer) {
            @Override public void updateUI() {
                super.updateUI();
                setDividerSize(detailsOpen ? openDividerSize() : 0); // a closed drawer leaves no divider behind
            }
            @Override public void doLayout() {
                // Keep the divider between the table's row minimum and the details' two-line minimum. Resizing
                // otherwise shares a shrink by weight and can leave the table a row or two while the details keep
                // spare lines. When both minimums cannot fit, the details keep theirs (the host page scrolls first).
                // A drawer that just opened starts where the reader last left it, else with 72% for the table.
                if (getHeight() > 0) {
                    int maximum = getMaximumDividerLocation(), location = getDividerLocation();
                    int wanted = !placeDrawer ? location : drawerLocation > 0 ? drawerLocation : Math.round((getHeight() - getDividerSize()) * .72f);
                    placeDrawer = false;
                    int target = Math.min(Math.max(wanted, getMinimumDividerLocation()), maximum);
                    if (target != location && target >= 0) setDividerLocation(target);
                }
                super.doLayout();
            }
        };
        right.setResizeWeight(.72);
        // Escape closes the open drawer from the table or anywhere in the meter; while it is closed, Escape passes on.
        Action closeAction = new AbstractAction("Close details") {
            @Override public void actionPerformed(ActionEvent e) { closeDetails(); }
            @Override public boolean isEnabled() { return detailsOpen; }
        };
        KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        table.getInputMap(JComponent.WHEN_FOCUSED).put(escape, "dps-close-details");
        table.getActionMap().put("dps-close-details", closeAction);
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(escape, "dps-close-details");
        getActionMap().put("dps-close-details", closeAction);
        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        left.setMinimumSize(new Dimension(150, 80)); right.setMinimumSize(new Dimension(260, 80));
        split.setDividerLocation(245); split.setResizeWeight(.24); add(split, BorderLayout.CENTER);
        enemySort.addActionListener(e -> rebuildEnemies());
        enemyList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !updating) rebuildScope(); });
        classes.addActionListener(e -> { if (!updating) filterRows(); });
        metric.addActionListener(e -> { updateMeterMaximum(); updateRanks(); rank(); showDetails(); table.repaint(); });
        colors.addActionListener(e -> table.repaint());
        table.getSelectionModel().addListSelectionListener(e -> { if (!updating) showDetails(); });
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filterRows(); }
            public void removeUpdate(DocumentEvent e) { filterRows(); }
            public void changedUpdate(DocumentEvent e) { filterRows(); }
        });
        search.getAccessibleContext().setAccessibleName("Filter player name");
        scope.setToolTipText(CombatMeterData.WINDOW_DEFINITION+" "+CombatMeterData.POPULATION);
        // Object IDs on the enemy cards follow Simple/Analyst; re-measure the cards only when the mode really changed
        // (the binding also runs whenever the meter becomes displayable).
        DisplayModeModel.application().bind(this, mode -> { if (mode != shownMode) { shownMode = mode; enemies.refresh(); } });
    }

    /**
     * Height at which the meter stays usable: its header, {@value #USABLE_TABLE_ROWS} table rows with the column header,
     * horizontal scroll bar and the Explore footer, and, only while the drawer is open, the split divider and the
     * drawer's header with the hit details' two-line minimum. Hosts that cannot give this much scroll instead of
     * collapsing the table and details.
     */
    int usableHeight() {
        Insets own = getInsets(), outer = split.getInsets(), inner = right.getInsets();
        int drawerArea = detailsOpen ? right.getDividerSize() + drawer.getMinimumSize().height : 0;
        return own.top + own.bottom + header.getPreferredSize().height + ((BorderLayout) getLayout()).getVgap()
            + outer.top + outer.bottom + inner.top + inner.bottom + tableArea.getMinimumSize().height + drawerArea;
    }
    JScrollPane tableScroll() { return tableScroll; }
    JTable table() { return table; }

    void setContext(Object key, Entity player) {
        setContext(key, player, DpsData.LocalPlayerContext.capture(player));
    }
    void setContext(Object key, Entity player, DpsData.LocalPlayerContext context) {
        if (encounter != key) {
            encounter = key;
            routeNotice.setText(""); routeNotice.setVisible(false);
            updating = true; enemyList.clearSelection(); updating = false;
            missingLocalSpawn = key instanceof DpsData && missingLocalSpawn((DpsData)key);
        }
        localPlayer = player;
        playerContext = context;
    }
    static boolean missingLocalSpawn(DpsData saved) {
        if (saved.debugPackets == null) return false;
        int localId = -1;
        boolean spawned = false, missedShots = false, localHit = false;
        for (Packet packet : saved.debugPackets) {
            if (packet instanceof CreateSuccessPacket) localId = ((CreateSuccessPacket)packet).objectId;
            else if (packet instanceof UpdatePacket && localId >= 0) {
                for (ObjectData object : ((UpdatePacket)packet).newObjects)
                    if (object.status.objectId == localId) spawned = true;
            } else if (packet instanceof PlayerShootPacket && localId >= 0 && !spawned) missedShots = true;
            else if (packet instanceof EnemyHitPacket && localId >= 0 && ((EnemyHitPacket)packet).shooterID == localId) localHit = true;
        }
        return missedShots && localHit;
    }
    protected void renderData(MapInfoPacket map, List<Entity> entities, ArrayList<NotificationPacket> notes, long elapsed, boolean isLive) {
        targets = new ArrayList<>(entities); mapName = map == null ? "No encounter" : map.name; live = isLive;
        targets.removeIf(Entity::isPlayerCharacter);
        String warning = missingLocalSpawn && !isLive
            ? "Personal damage is incomplete: shots arrived before your character data. This saved encounter cannot show your full damage."
            : isLive && map != null && localPlayer == null
                ? "Your character data has not arrived. If personal damage stays missing, change areas or reconnect to start a fresh capture."
                : "";
        captureWarning.setText(warning); captureWarning.setVisible(!warning.isEmpty());
        rebuildEnemies();
    }
    private void rebuildEnemies() {
        Entity selected = enemyList.getSelectedValue();
        List<Entity> sorted = new ArrayList<>(targets);
        Comparator<Entity> comparator = Comparator.comparingInt(Entity::maxHp);
        if (enemySort.getSelectedIndex() == 1) comparator = Comparator.comparingLong(Entity::getLastDamageTaken);
        if (enemySort.getSelectedIndex() == 2) comparator = Comparator.comparingLong(Entity::getFightDuration);
        if (enemySort.getSelectedIndex() == 3) sorted.removeIf(e -> !e.isBossMob());
        sorted.sort(comparator.reversed().thenComparingInt(e -> e.id));
        updating = true;
        // One model event for the whole list: adding cards one at a time made the list re-measure every card on each
        // add (about 45,000 renderer calls for 300 enemies on every live render).
        List<Entity> rows = new ArrayList<>(sorted.size() + 1);
        rows.add(null); rows.addAll(sorted);
        damageByEnemy.clear();
        enemies.clear(); enemies.addAll(rows);
        int selection = 0;
        if (selected != null) for(int i=0;i<sorted.size();i++) if(sorted.get(i).id==selected.id) { selection=i+1; break; }
        enemyList.setSelectedIndex(Math.max(0, selection)); updating = false;
        rebuildScope();
    }
    private void rebuildScope() {
        if (updating) return;
        Entity enemy = enemyList.getSelectedValue();
        List<Entity> chosen = new ArrayList<>();
        if (enemy != null) chosen.add(enemy);
        else for (int i = 1; i < enemies.size(); i++) chosen.add(enemies.get(i));
        wholeEncounter = enemy == null && enemySort.getSelectedIndex() != 3;
        snapshot = new CombatMeterData(chosen, localPlayer, wholeEncounter);
        scopedEnemies = chosen.size();
        updateRanks();
        String selectedClass = (String) classes.getSelectedItem();
        TreeSet<String> available = new TreeSet<>(); snapshot.rows.forEach(r -> available.add(r.className()));
        updating = true;
        classes.removeAllItems(); classes.addItem("All classes"); available.forEach(classes::addItem);
        classes.setSelectedItem(available.contains(selectedClass) ? selectedClass : "All classes");
        updating = false;
        filterRows();
    }
    private void filterRows() {
        if (updating) return;
        Integer selected = selectedPlayer();
        updating = true;
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        visible = new ArrayList<>();
        for (CombatMeterData.Row row : snapshot.rows) {
            if (!"All classes".equals(classes.getSelectedItem()) && !row.className().equals(classes.getSelectedItem())) continue;
            if (!String.valueOf(row.player.name()).toLowerCase(Locale.ROOT).contains(query)) continue;
            if (Filter.shouldFilter(playerContext) && Filter.filter(row.player, playerContext) != 1) continue;
            visible.add(row);
        }
        updateMeterMaximum();
        model.fireTableDataChanged();
        if (selected != null) for (int i = 0; i < visible.size(); i++) if (visible.get(i).player.id == selected) {
            int view = table.convertRowIndexToView(i); table.setRowSelectionInterval(view, view); break;
        }
        updating = false;
        summary.setText(mapName + "  ·  " + (live ? "LIVE" : "SAVED") + "  ·  " + number(scopedEnemies) + " enemies  ·  " + number(visible.size()) + "/" + number(snapshot.rows.size()) + " players  ·  DMG: " + number(snapshot.total));
        summary.setToolTipText(summary.getText().startsWith("<html>") ? " " + summary.getText() : summary.getText());
        scope.setText(DisplayFormat.formatNumber(snapshot.seconds, 1) + "s first-to-last hit window · Taken: "
            + (wholeEncounter ? "full dungeon" : "inclusive fight window") + " · Represented contributors only");
        showDetails();
    }
    private int metricColumn() { return METRIC_COLUMNS[metric.getSelectedIndex()]; }
    private void updateMeterMaximum() {
        meterMaximum = 0;
        int column = metricColumn();
        for (int i = 0; i < visible.size(); i++) {
            Object value = model.getValueAt(i, column);
            if (value instanceof Number) meterMaximum = Math.max(meterMaximum, ((Number)value).doubleValue());
        }
    }
    private Integer selectedPlayer() {
        int i = table.getSelectedRow();
        return i < 0 ? null : visible.get(table.convertRowIndexToModel(i)).player.id;
    }
    private void rank() {
        table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(metricColumn(), SortOrder.DESCENDING)));
    }
    /**
     * True ranks by the chosen metric over every row of the scope (hidden and filtered players included): descending,
     * ties by object ID as {@link MeterSummary} orders them. A row without a value (no incoming events, no DPS window)
     * has no rank, never a number.
     */
    private void updateRanks() {
        int column = metricColumn();
        List<CombatMeterData.Row> ranked = new ArrayList<>();
        Map<CombatMeterData.Row, Double> values = new IdentityHashMap<>();
        for (CombatMeterData.Row row : snapshot.rows) {
            Object value = value(row, column);
            if (value instanceof Number) { values.put(row, ((Number) value).doubleValue()); ranked.add(row); }
        }
        ranked.sort(Comparator.comparingDouble((CombatMeterData.Row row) -> values.get(row)).reversed().thenComparingInt(row -> row.player.id));
        Map<Integer, Integer> next = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) next.putIfAbsent(ranked.get(i).player.id, i + 1);
        ranks = next;
    }
    private String rankText(CombatMeterData.Row row) {
        Integer rank = ranks.get(row.player.id);
        return rank == null ? DisplayFormat.UNAVAILABLE : "#" + rank;
    }
    private void exploreSelected() {
        int index = table.getSelectedRow();
        if (index < 0) return;
        DamageEventExplorer.open(this, explorer(visible.get(table.convertRowIndexToModel(index))));
    }
    /** Detached explorer over the row's retained hits in the current enemy scope. */
    DamageEventExplorer explorer(CombatMeterData.Row row) {
        String scope = mapName + " · " + (enemyList.getSelectedValue() == null ? "all enemies" : "selected enemy")
            + " · incoming: " + (wholeEncounter ? "full dungeon" : "inclusive fight window");
        return new DamageEventExplorer(row.player.name(), row.outgoing, row.incoming, row.incomingAvailable, snapshot.first, scope);
    }
    /**
     * Routed handoff: shows every enemy and class, then selects the row whose object ID is {@code objectId}
     * (verified for this encounter by the caller) and shows {@code notice}. Returns false when no such row exists.
     */
    boolean focusPlayer(int objectId, String notice) {
        updating = true;
        try { search.setText(""); if (enemySort.getSelectedIndex() == 3) enemySort.setSelectedIndex(0); enemyList.setSelectedIndex(0); classes.setSelectedItem("All classes"); }
        finally { updating = false; }
        rebuildScope();
        routeNotice.setText(notice == null ? "" : notice); routeNotice.setVisible(notice != null && !notice.isEmpty());
        for (int i = 0; i < visible.size(); i++) if (visible.get(i).player.id == objectId) {
            int view = table.convertRowIndexToView(i); if (view < 0) return false;
            table.setRowSelectionInterval(view, view); table.scrollRectToVisible(table.getCellRect(view, 0, true)); return true;
        }
        return false;
    }
    Integer selectedObjectId() { return selectedPlayer(); }
    /** Provenance shown by Inspect for a row of this encounter (source encounter and its link status). */
    void setInspectOrigin(String origin) { inspectOrigin = origin; }
    String routeNoticeText() { return routeNotice.isVisible() ? routeNotice.getText() : ""; }
    private void showDetails() {
        int index = table.getSelectedRow();
        explore.setEnabled(index >= 0);
        exploreReason.setText(index >= 0 ? " " : visible.isEmpty() ? "No player rows" : "Select a player first");
        explore.setToolTipText(index >= 0 ? "Page through every retained hit of the selected player with filters and event-time loadouts"
            : "Unavailable: select a player row in the table to explore its retained hits");
        Object key = index < 0 ? Arrays.asList(encounter, null) : Arrays.asList(encounter, visible.get(table.convertRowIndexToModel(index)).player.id, metric.getSelectedIndex() >= 3);
        boolean fresh = !key.equals(detailsKey); detailsKey = key;
        if (index >= 0) {
            CombatMeterData.Row selected = visible.get(table.convertRowIndexToModel(index));
            String name = selected.player.name();
            drawerTitle.setText("Details · " + (name == null || name.isEmpty() ? "Unknown player" : name) + " · " + selected.className());
        }
        setDetailsOpen(index >= 0);
        if (index < 0) { setDetails((visible.isEmpty() ? "No players match this view. Clear filters or choose another enemy." : "Select a player for hit details. Right-click a player and choose Inspect for their captured build.")+"\n"+CombatMeterData.WINDOW_DEFINITION+"\nRecorded damage share = player damage / all recorded damage on selected enemies; player filters do not change the denominator. Legacy uses enemy max HP instead.\n"+CombatMeterData.POPULATION, fresh); return; }
        CombatMeterData.Row row = visible.get(table.convertRowIndexToModel(index));
        boolean incoming = metric.getSelectedIndex() >= 3;
        StringBuilder text = new StringBuilder(String.valueOf(row.player.name())).append(" · ").append(row.className()).append("\n");
        text.append("Damage: ").append(number(row.damage)).append(" · Hits: ").append(number(row.hits))
            .append(" · Max hit: ").append(number(row.biggest)).append("\n");
        text.append("Recorded damage share: ").append(number(row.damage)).append(" / ").append(number(snapshot.total)).append(" = ")
            .append(snapshot.share(row)==null?DisplayFormat.UNAVAILABLE:DisplayFormat.formatPercentage(snapshot.share(row),1)).append("; denominator includes hidden players, not enemy max HP. Unattributed damage included: ").append(number(snapshot.unattributed)).append(".\n")
            .append("DPS: ").append(number(row.damage)).append(" / ").append(DisplayFormat.formatNumber(snapshot.seconds,3)).append(" seconds; ").append(CombatMeterData.WINDOW_DEFINITION).append('\n')
            .append(CombatMeterData.POPULATION).append('\n');
        if (row.incomingAvailable) text.append("Taken (recorded/estimated): ").append(number(row.taken))
            .append(" · Incoming events: ").append(number(row.incomingHits)).append("\n");
        if (row.incomingAvailable && !wholeEncounter) text.append("Full dungeon taken: ").append(number(row.totalTaken))
            .append(" · Incoming events: ").append(number(row.totalIncomingHits)).append("\n");
        if (!incoming) appendSources(text, row);
        if (incoming && !row.incomingAvailable) text.append("Incoming damage is unavailable: no incoming events were recorded for this player.");
        else {
            List<Damage> hits = incoming ? row.incoming : row.outgoing;
            text.append(incoming ? (wholeEncounter ? "Incoming recorded/estimated · full dungeon\n" : "Incoming recorded/estimated · all sources during selected hit window\n") : "Outgoing recorded hits · selected enemies\n");
            text.append("  Time        Damage    Source\n");
            // Keep live detail rendering bounded; totals always include every hit.
            int start = Math.max(0, hits.size() - 500);
            if (start > 0) text.append("Showing latest 500 of ").append(number(hits.size())).append(" hits\n");
            for (int i = start; i < hits.size(); i++) {
                Damage hit = hits.get(i);
                String source = incoming ? (hit.owner == null ? "AoE / ground / unknown" : String.valueOf(hit.owner.name())) :
                    DamageSource.describe(hit);
                text.append(String.format(Locale.ROOT, "%7ss  %10s    %s%n",
                    snapshot.first==Long.MAX_VALUE?DisplayFormat.UNAVAILABLE:DisplayFormat.formatDurationSeconds(hit.time - snapshot.first, 2), number(hit.damage), source));
            }
        }
        setDetails(text.toString(), fresh);
    }
    /**
     * Replaces the hit details. A different player, metric direction or encounter starts at line 1, column 0
     * (setText alone leaves the caret at the end, which scrolled the view right to the middle of a word); a
     * refresh of the same selection keeps the caret where the reader left it.
     */
    private void setDetails(String text, boolean fresh) {
        int caret = details.getCaretPosition();
        details.setText(text);
        details.setCaretPosition(fresh ? 0 : Math.min(caret, details.getDocument().getLength()));
        if (fresh) detailScroll.getViewport().setViewPosition(new Point(0, 0));
    }
    int detailsCaret() { return details.getCaretPosition(); }
    /**
     * Opens or closes the hit-details drawer. The host re-measures because {@link #usableHeight()} counts the drawer
     * only while it is open; a closed drawer leaves no divider, so the table and its footer take the whole height.
     */
    private void setDetailsOpen(boolean open) {
        if (open == detailsOpen) return;
        if (!open && right.getHeight() > 0) drawerLocation = right.getDividerLocation();
        detailsOpen = open;
        drawer.setVisible(open);
        right.setDividerSize(open ? openDividerSize() : 0);
        placeDrawer = open;
        revalidate(); repaint();
        if (open) SwingUtilities.invokeLater(this::revealSelectedRow);
    }
    /** Keeps the selected row visible in the table's own viewport once the opened drawer has taken its share. */
    private void revealSelectedRow() {
        int view = table.getSelectedRow();
        if (!detailsOpen || view < 0) return;
        Rectangle cell = table.getCellRect(view, 0, true);
        // In the viewport's own coordinates, and only the viewport: the host page does not move.
        tableScroll.getViewport().scrollRectToVisible(new Rectangle(0, cell.y + table.getY(), 1, cell.height));
    }
    /** Escape or ×: clears the selected player, which closes the drawer; focus inside the drawer returns to the table. */
    private void closeDetails() {
        if (!detailsOpen) return;
        Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        boolean inDrawer = focused != null && SwingUtilities.isDescendingFrom(focused, drawer);
        table.clearSelection();
        if (inDrawer) table.requestFocusInWindow();
    }
    private static int openDividerSize() {
        int size = UIManager.getInt("SplitPane.dividerSize");
        return size > 0 ? size : 5;
    }
    /** Your row keeps the accent wash in every renderer unless it is selected ("(you)" names it as well). */
    private static void washMine(CombatMeterData.Row entry, boolean selected, JComponent cell) {
        if (!selected && entry.player.isUser()) cell.setBackground(Tokens.color(Tokens.Role.ACCENT_WASH));
    }
    private Font tableEmphasis(Font base) { tableFonts(base); return tableEmphasis; }
    private Font tableMetadata(Font base) { tableFonts(base); return tableMetadata; }
    private void tableFonts(Font base) {
        if (base == tableFont) return;
        tableFont = base; tableEmphasis = ContentStyle.emphasis(base); tableMetadata = ContentStyle.metadata(base);
    }
    /** Recorded damage on one enemy (unattributed hits included, as the meter's DMG total counts them), once per refill. */
    private long recordedDamage(Entity enemy) {
        Long cached = damageByEnemy.get(enemy);
        if (cached == null) {
            long sum = 0;
            for (Damage hit : enemy.getDamageList()) sum += hit.damage;
            damageByEnemy.put(enemy, cached = sum);
        }
        return cached;
    }
    /**
     * An enemy card's facts: max HP ("HP —" when the stat was never seen, never 0), recorded damage and the first-to-last
     * hit window ("— s" before any hit); the object ID only in Analyst.
     */
    private String cardFacts(Entity enemy, boolean analyst) {
        StringBuilder text = new StringBuilder(48);
        text.append(enemy.stat.get(StatType.MAX_HP_STAT) == null ? "HP " + DisplayFormat.UNAVAILABLE : formats.integer(enemy.maxHp()) + " HP");
        text.append(" · ").append(formats.compact(recordedDamage(enemy))).append(" dmg · ");
        text.append(enemy.getFirstDamageTaken() < 0 ? DisplayFormat.UNAVAILABLE : formats.decimal(CombatMeterData.windowMillis(enemy) / 1000.0)).append(" s");
        if (analyst) text.append(" · #").append(enemy.id);
        return text.toString();
    }
    private static final int SOURCE_ITEMS_SHOWN = 5;
    /** Share of the player's own recorded damage by source and item, over every hit, not only the latest 500. */
    private static void appendSources(StringBuilder text, CombatMeterData.Row row) {
        if (row.damage <= 0) return;
        List<CombatMeterData.SourceShare> sources = CombatMeterData.sources(row);
        text.append("Damage by source · share of this player's ").append(number(row.damage)).append(" recorded damage\n");
        boolean other = false, unknown = false;
        for (CombatMeterData.SourceShare share : sources) {
            text.append(sourceLine("  ", share.source.label, share.damage, row.damage)).append("   ").append(number(share.hits)).append(share.hits == 1 ? " hit\n" : " hits\n");
            int shown = 0;
            for (Map.Entry<String, long[]> item : share.items.entrySet()) {
                if (shown++ == SOURCE_ITEMS_SHOWN) { text.append("      + ").append(number(share.items.size() - SOURCE_ITEMS_SHOWN)).append(" more items\n"); break; }
                text.append(sourceLine("      ", item.getKey(), item.getValue()[0], row.damage)).append('\n');
            }
            other |= share.source == DamageSource.OTHER; unknown |= share.source == DamageSource.UNKNOWN;
        }
        if (other) text.append(DamageSource.OTHER_DEFINITION).append('\n');
        if (unknown) text.append(DamageSource.UNKNOWN_DEFINITION).append('\n');
    }
    private static String sourceLine(String indent, String label, long damage, long total) {
        String name = indent + label;
        if (name.length() > 30) name = name.substring(0, 29) + "…";
        return String.format(Locale.ROOT, "%-30s %7s  %12s", name, DisplayFormat.formatPercentage(damage * 100.0 / total, 1), number(damage));
    }
    protected void editFont(Font font) {
        ContentStyle.tableFont(table, font, 0); ContentStyle.font(enemyList, font);
        ContentStyle.font(details, ContentStyle.report(font));
        ContentStyle.font(summary, ContentStyle.emphasis(font)); ContentStyle.font(scope, ContentStyle.metadata(font));
        ContentStyle.font(captureWarning, font);
        ContentStyle.font(drawerTitle, ContentStyle.emphasis(font)); ContentStyle.font(exploreReason, ContentStyle.metadata(font));
    }
    public static Color classColor(int type) {
        Color surface = UIManager.getColor("Table.background");
        boolean dark = surface == null || surface.getRed() < 128;
        return Color.getHSBColor((type * .618034f) % 1f, dark ? .36f : .72f, dark ? .94f : .48f);
    }
    private static String number(long value) { return DisplayFormat.formatInteger(value); }
    private final class MeterModel extends AbstractTableModel {
        private final String[] names = {"Player / meter", "Class", "Damage", "DPS", "Recorded share %", "Hits dealt", "Avg hit", "Max hit", "Taken (est.)", "Hits taken"};
        public int getRowCount() { return visible.size(); }
        public int getColumnCount() { return names.length; }
        public String getColumnName(int c) { return names[c]; }
        public Class<?> getColumnClass(int c) { return c < 2 ? String.class : (c == 3 || c == 4 || c == 6 ? Double.class : Long.class); }
        public Object getValueAt(int r, int c) { return value(visible.get(r), c); }
    }
    /** A row's value in model column {@code c}; the ranks read the same values the table shows. */
    private Object value(CombatMeterData.Row row, int c) {
        switch (c) {
            case 0: return row.player.name(); case 1: return row.className(); case 2: return row.damage;
            case 3: return snapshot.dps(row); case 4: return snapshot.share(row);
            case 5: return row.hits; case 6: return row.hits == 0 ? null : (double)row.damage / row.hits;
            case 7: return row.biggest; case 8: return row.incomingAvailable ? row.taken : null;
            default: return row.incomingAvailable ? row.incomingHits : null;
        }
    }
    /**
     * The player cell: a bar in the class hue scaled to the metric with its 3 px rail, the true rank as a fixed-width
     * muted prefix, the name with "(you)" and ★, and the metric amount painted in the right inset. Your row keeps the
     * accent wash; the tooltip names the rank in words.
     */
    private final class BarRenderer extends ContentStyle.Cell {
        private double fraction;
        private Color color;
        private final JLabel amountLabel = new JLabel("", SwingConstants.RIGHT);
        private final RankIcon rankIcon = new RankIcon();
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
            super.getTableCellRendererComponent(t, value, selected, focus, row, column);
            int c = metricColumn();
            int m = t.convertRowIndexToModel(row); Object n = model.getValueAt(m, c);
            fraction = meterMaximum <= 0 || !(n instanceof Number) ? 0 : ((Number)n).doubleValue() / meterMaximum;
            CombatMeterData.Row entry = visible.get(m);
            color = colors.isSelected() ? classColor(entry.player.objectType) : ContentStyle.color("violet");
            String amount = n instanceof Double ? formats.decimal(((Number)n).doubleValue()) : n instanceof Number ? formats.integer(((Number)n).longValue()) : "—";
            Font small = tableMetadata(t.getFont());
            amountLabel.setText(amount); amountLabel.setFont(small);
            amountLabel.setForeground(getForeground());
            setBorder(BorderFactory.createCompoundBorder(getBorder(),
                BorderFactory.createEmptyBorder(0, 0, 0, amountLabel.getPreferredSize().width + 8)));
            setFont(tableEmphasis(t.getFont()));
            String rank = rankText(entry);
            rankIcon.set(rank, small, selected ? getForeground() : ContentStyle.color("muted"), snapshot.rows.size());
            setIcon(rankIcon); setIconTextGap(Tokens.S);
            setText(String.valueOf(value) + (entry.player.isUser() ? " (you)" : "") + (Filter.filter(entry.player, playerContext) == 2 ? " ★" : ""));
            setToolTipText(String.valueOf(value) + " · " + entry.className() + " · " + metric.getSelectedItem() + ": " + amount + " · Rank " + rank);
            washMine(entry, selected, this);
            setOpaque(false); return this;
        }
        protected void paintComponent(Graphics g) {
            Graphics2D bars = (Graphics2D) g.create();
            bars.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            bars.setColor(getBackground()); bars.fillRect(0, 0, getWidth(), getHeight());
            bars.setColor(Tokens.blend(getBackground(), color, .30f));
            bars.fillRoundRect(4, 3, (int)((getWidth() - 8) * fraction), getHeight() - 6, 4, 4);
            bars.setColor(color); bars.fillRect(4, 6, 3, getHeight() - 12);
            bars.dispose();
            super.paintComponent(g);
            rankIcon.paintPlaced(g);
            int width = Math.min(getWidth(), amountLabel.getPreferredSize().width);
            amountLabel.setSize(width, getHeight());
            Graphics valueGraphics = g.create(getWidth() - width - 8, 0, width, getHeight());
            amountLabel.paint(valueGraphics); valueGraphics.dispose();
        }
    }
    /**
     * The true rank as a fixed-width prefix ("#12", or "—" without a value), right-aligned so the names line up. The
     * icon only reserves the space and records where the cell placed it; the cell paints the rank after its own text,
     * because BasicLabelUI shares its layout rectangles between labels and a label painted inside another label's
     * paintIcon moves that label's text.
     */
    private static final class RankIcon implements Icon {
        private final JLabel label = new JLabel("", SwingConstants.RIGHT);
        private int width, height, x = -1, y;
        RankIcon() { label.putClientProperty("html.disable", true); }
        /** {@code rows} is the scope's row count: the prefix is wide enough for its largest rank. */
        void set(String text, Font font, Color color, int rows) {
            label.setText(text); label.setFont(font); label.setForeground(color);
            FontMetrics metrics = label.getFontMetrics(font);
            width = metrics.stringWidth("#" + "8".repeat(String.valueOf(Math.max(9, rows)).length()));
            height = metrics.getHeight();
            x = -1;
        }
        @Override public int getIconWidth() { return width; }
        @Override public int getIconHeight() { return height; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) { this.x = x; this.y = y; }
        void paintPlaced(Graphics g) {
            if (x < 0) return;
            label.setSize(width, height);
            Graphics area = g.create(x, y, width, height);
            try { label.paint(area); } finally { area.dispose(); }
        }
    }
    /** The enemy list model; {@link #refresh()} re-measures every card in one event (object IDs follow Simple/Analyst). */
    private static final class EnemyModel extends DefaultListModel<Entity> {
        void refresh() { if (!isEmpty()) fireContentsChanged(this, 0, size() - 1); }
    }
    /**
     * Enemy cards: the LAF cell border (focus) outermost, then a rounded card; the name NORTH, the facts SOUTH and a
     * Boss chip EAST. The card border is cached per LAF cell border, the derived fonts per list font and the card
     * colors per theme (updateUI clears them), so a refill builds no borders, fonts or formats per card.
     */
    private final class EnemyCardRenderer extends DefaultListCellRenderer {
        private final JPanel row = new JPanel(new CardRowLayout());
        private final JLabel title = new JLabel(), subtitle = new JLabel();
        private final Chip boss = new Chip("Boss", Tokens.Tone.WARN);
        private final Map<Border, Border> borders = new IdentityHashMap<>();
        private Font base, titleFont, subtitleFont;
        private Color raised, muted;
        EnemyCardRenderer() {
            title.putClientProperty("html.disable", true); subtitle.putClientProperty("html.disable", true);
            row.setOpaque(false); // the card paints its own rounded fill inside the LAF border
            row.add(title, BorderLayout.NORTH); row.add(subtitle, BorderLayout.SOUTH); row.add(boss, BorderLayout.EAST);
        }
        @Override public void updateUI() {
            super.updateUI();
            if (row == null) return; // DefaultListCellRenderer's constructor runs this before the fields exist
            borders.clear(); base = null; raised = null; muted = null;
            SwingUtilities.updateComponentTreeUI(row);
        }
        @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
            super.getListCellRendererComponent(l, v, i, s, f); // LAF colors and the focus border
            Border cell = getBorder(), card = borders.get(cell);
            if (card == null) {
                if (borders.size() > 8) borders.clear();
                borders.put(cell, card = BorderFactory.createCompoundBorder(cell, CARD));
            }
            row.setBorder(card);
            Font font = l.getFont();
            if (font != base) { base = font; titleFont = ContentStyle.emphasis(font); subtitleFont = ContentStyle.metadata(font); }
            title.setFont(titleFont); subtitle.setFont(subtitleFont); boss.setFont(subtitleFont);
            if (raised == null) { raised = Tokens.color(Tokens.Role.RAISED); muted = ContentStyle.color("muted"); }
            Entity e = (Entity) v;
            title.setText(e == null ? "All enemies · " + formats.integer(Math.max(0, l.getModel().getSize() - 1)) : e.name() == null ? "Unknown enemy" : e.name());
            subtitle.setText(e == null ? "Encounter totals" : cardFacts(e, DisplayModeModel.application().analyst()));
            boolean isBoss = e != null && e.isBossMob();
            boss.setVisible(isBoss);
            row.setBackground(s ? getBackground() : raised); title.setForeground(getForeground());
            subtitle.setForeground(s ? getForeground() : muted);
            String tip = title.getText() + (isBoss ? " · Boss" : "") + " · " + subtitle.getText();
            row.setToolTipText(tip.startsWith("<html>") ? " " + tip : tip);
            row.getAccessibleContext().setAccessibleName(tip);
            return row;
        }
    }
    /** The rounded card inside the LAF cell border, filled with the cell's background and outlined in the subtle border color. */
    private static final Border CARD = new AbstractBorder() {
        @Override public Insets getBorderInsets(Component c, Insets insets) { insets.set(6, 10, 6, 10); return insets; }
        @Override public void paintBorder(Component c, Graphics graphics, int x, int y, int width, int height) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(c.getBackground());
                g.fillRoundRect(x + 2, y + 2, width - 4, height - 4, Tokens.ARC_CARD, Tokens.ARC_CARD);
                g.setColor(Tokens.color(Tokens.Role.BORDER_SUBTLE));
                g.drawRoundRect(x + 2, y + 2, width - 5, height - 5, Tokens.ARC_CARD, Tokens.ARC_CARD);
            } finally { g.dispose(); }
        }
    };
    /** BorderLayout whose EAST (the Boss chip) is centered beside NORTH and SOUTH over the card's height, not between them. */
    private static final class CardRowLayout extends BorderLayout {
        CardRowLayout() { super(Tokens.S, 2); }
        @Override public Dimension preferredLayoutSize(Container parent) { return size(parent); }
        @Override public Dimension minimumLayoutSize(Container parent) { return size(parent); }
        private Dimension size(Container parent) {
            Dimension north = preferred(getLayoutComponent(NORTH)), south = preferred(getLayoutComponent(SOUTH)), east = preferred(getLayoutComponent(EAST));
            Insets insets = parent.getInsets();
            int width = Math.max(north.width, south.width) + (east.width > 0 ? getHgap() + east.width : 0);
            int height = Math.max(north.height + getVgap() + south.height, east.height);
            return new Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom);
        }
        @Override public void layoutContainer(Container parent) {
            Insets insets = parent.getInsets();
            int x = insets.left, y = insets.top;
            int width = Math.max(0, parent.getWidth() - insets.left - insets.right), height = Math.max(0, parent.getHeight() - insets.top - insets.bottom);
            Component east = getLayoutComponent(EAST), north = getLayoutComponent(NORTH), south = getLayoutComponent(SOUTH);
            Dimension chip = preferred(east);
            if (chip.width > 0) {
                int w = Math.min(chip.width, width);
                east.setBounds(x + width - w, y + (height - chip.height) / 2, w, chip.height);
                width = Math.max(0, width - w - getHgap());
            }
            if (north != null) north.setBounds(x, y, width, preferred(north).height);
            if (south != null) { int h = preferred(south).height; south.setBounds(x, y + height - h, width, h); }
        }
        private static Dimension preferred(Component component) {
            return component == null || !component.isVisible() ? new Dimension() : component.getPreferredSize();
        }
    }
    /**
     * EDT-only number formats for the renderers, rebuilt when the FORMAT locale changes: the text of
     * DisplayFormat.formatInteger, formatNumber(value, 1) and formatPercentage and of KitFormat.compact, without a new
     * NumberFormat per cell (a 300-enemy refill otherwise builds about a thousand).
     */
    static final class Formats {
        private static final String[] SUFFIXES = {"", "k", "M", "B"};
        private Locale locale;
        private NumberFormat integer, decimal, compact;
        private void current() {
            Locale now = Locale.getDefault(Locale.Category.FORMAT);
            if (now.equals(locale)) return;
            locale = now; integer = format(now, 0, 0); decimal = format(now, 1, 1); compact = format(now, 0, 1);
        }
        private static NumberFormat format(Locale locale, int minimumDecimals, int maximumDecimals) {
            NumberFormat format = NumberFormat.getNumberInstance(locale);
            format.setGroupingUsed(true);
            format.setMinimumFractionDigits(minimumDecimals); format.setMaximumFractionDigits(maximumDecimals);
            format.setRoundingMode(RoundingMode.HALF_UP);
            return format;
        }
        String integer(long value) { current(); return integer.format(value); }
        String decimal(double value) { current(); return Double.isFinite(value) ? decimal.format(BigDecimal.valueOf(value)) : DisplayFormat.UNAVAILABLE; }
        String percentage(double value) { return Double.isFinite(value) ? decimal(value) + "%" : DisplayFormat.UNAVAILABLE; }
        /** 25, 25.5, 41.2k, 2.3M: one decimal at most, promoted before it would round up to 1,000. */
        String compact(double value) {
            if (!Double.isFinite(value)) return DisplayFormat.UNAVAILABLE;
            current();
            int index = 0;
            double scaled = value;
            while (index < SUFFIXES.length - 1 && Math.abs(scaled) >= 999.95) { scaled /= 1000; index++; }
            return compact.format(BigDecimal.valueOf(scaled)) + SUFFIXES[index];
        }
    }
}
