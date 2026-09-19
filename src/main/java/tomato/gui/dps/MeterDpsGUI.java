package tomato.gui.dps;

import packets.incoming.*;
import packets.Packet;
import packets.data.ObjectData;
import packets.outgoing.PlayerShootPacket;
import packets.outgoing.EnemyHitPacket;
import tomato.backend.data.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import tomato.gui.modern.ContentStyle;

/** Interactive encounter overview with a stable selection during live updates. */
public class MeterDpsGUI extends DisplayDpsGUI {
    private static final int[] METRIC_COLUMNS = {2, 3, 5, 8, 9};
    private final JComboBox<String> enemySort = new JComboBox<>(new String[]{"Highest enemy HP", "Latest hit", "Longest fight", "Bosses only"});
    private final JComboBox<String> classes = new JComboBox<>(new String[]{"All classes"});
    private final JComboBox<String> metric = new JComboBox<>(new String[]{"Damage", "DPS", "Hits dealt", "Damage taken", "Hits taken"});
    private final JTextField search = new JTextField(12);
    private final JCheckBox colors = new JCheckBox("Class colors", true), paused = new JCheckBox("Pause view");
    private final DefaultListModel<Entity> enemies = new DefaultListModel<>();
    private final JList<Entity> enemyList = new JList<>(enemies);
    private final JLabel summary = new JLabel("Waiting for combat");
    private final JLabel scope = new JLabel(" ") {
        @Override public void updateUI() { super.updateUI(); setForeground(ContentStyle.color("muted")); }
    };
    private final JTextArea details = new JTextArea("Select a player to inspect individual hits.");
    private final JTextArea captureWarning = new JTextArea() {
        @Override public void updateUI() { super.updateUI(); setForeground(ContentStyle.color("amber")); }
    };
    private final MeterModel model = new MeterModel();
    private final JTable table = new JTable(model);
    private List<Entity> targets = new ArrayList<>();
    private List<CombatMeterData.Row> visible = new ArrayList<>();
    private CombatMeterData snapshot = new CombatMeterData(Collections.emptyList(), null);
    private Entity localPlayer;
    private Object encounter;
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
        filters.add(new JLabel("Player")); filters.add(search); filters.add(colors); filters.add(paused);
        summary.setAlignmentX(LEFT_ALIGNMENT); scope.setAlignmentX(LEFT_ALIGNMENT);
        filters.setAlignmentX(LEFT_ALIGNMENT);
        controls.add(summary); controls.add(filters); controls.add(scope);
        captureWarning.setEditable(false); captureWarning.setFocusable(false);
        captureWarning.setLineWrap(true); captureWarning.setWrapStyleWord(true);
        captureWarning.setOpaque(false); ContentStyle.font(captureWarning, ContentStyle.body());
        captureWarning.setVisible(false);
        JPanel header = new JPanel(new BorderLayout(0, 5));
        header.add(controls, BorderLayout.CENTER); header.add(captureWarning, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);
        JPanel left = new JPanel(new BorderLayout(4, 4));
        left.add(enemySort, BorderLayout.NORTH);
        enemyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ContentStyle.font(enemyList, ContentStyle.body());
        enemyList.setCellRenderer(new DefaultListCellRenderer() {
            private final JPanel row = new JPanel(new BorderLayout(0, 2));
            private final JLabel title = new JLabel(), subtitle = new JLabel();
            {
                title.putClientProperty("html.disable", true); subtitle.putClientProperty("html.disable", true);
                row.add(title, BorderLayout.NORTH); row.add(subtitle, BorderLayout.SOUTH);
            }
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                super.getListCellRendererComponent(l, v, i, s, f);
                row.setBorder(BorderFactory.createCompoundBorder(getBorder(), BorderFactory.createEmptyBorder(4, 8, 4, 8)));
                Entity e = (Entity)v;
                title.setText(e == null ? "All enemies" : e.name() == null ? "Unknown enemy" : e.name());
                subtitle.setText(e == null ? "Encounter totals" : number(e.maxHp()) + " HP  ·  #" + e.id);
                title.setFont(ContentStyle.emphasis(l.getFont())); subtitle.setFont(ContentStyle.metadata(l.getFont()));
                row.setBackground(getBackground()); title.setForeground(getForeground());
                subtitle.setForeground(s ? getForeground() : ContentStyle.color("muted"));
                row.setToolTipText(title.getText().startsWith("<html>") ? " " + title.getText() : title.getText());
                return row;
            }
        });
        left.add(new JScrollPane(enemyList), BorderLayout.CENTER);
        ContentStyle.table(table); table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        rank();
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        for (int i = 0; i < model.getColumnCount(); i++) table.getColumnModel().getColumn(i).setPreferredWidth(i == 0 ? 230 : 110);
        table.getColumnModel().getColumn(0).setCellRenderer(new BarRenderer());
        table.getColumnModel().getColumn(1).setCellRenderer(new ContentStyle.Cell() {
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                if (!selected) setForeground(colors.isSelected() ? classColor(visible.get(t.convertRowIndexToModel(row)).player.objectType) : ContentStyle.color("muted"));
                setFont(ContentStyle.metadata(t.getFont())); return this;
            }
        });
        DefaultTableCellRenderer numeric = new ContentStyle.Cell() {
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                int c = t.convertColumnIndexToModel(column);
                setHorizontalAlignment(RIGHT);
                if (c == 2 || c == 3) setFont(ContentStyle.emphasis(t.getFont()));
                if (!selected && (value == null || c >= 8 || c == 3)) setForeground(ContentStyle.color(value == null ? "muted" : c >= 8 ? "rose" : "violet"));
                return this;
            }
            protected void setValue(Object value) {
                setText(value == null ? "—" : value instanceof Double ? String.format(Locale.ROOT, "%,.1f", value) : number(((Number)value).longValue()));
                setHorizontalAlignment(RIGHT);
            }
        };
        table.setDefaultRenderer(Long.class, numeric); table.setDefaultRenderer(Double.class, numeric);
        details.setEditable(false); ContentStyle.font(details, ContentStyle.report(ContentStyle.body()));
        details.setMargin(new Insets(6, 8, 6, 8));
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(details));
        right.setResizeWeight(.72); right.setDividerLocation(320);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        left.setMinimumSize(new Dimension(150, 80)); right.setMinimumSize(new Dimension(260, 80));
        split.setDividerLocation(245); split.setResizeWeight(.24); add(split, BorderLayout.CENTER);
        enemySort.addActionListener(e -> rebuildEnemies());
        enemyList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !updating) rebuildScope(); });
        classes.addActionListener(e -> { if (!updating) filterRows(); });
        metric.addActionListener(e -> { updateMeterMaximum(); rank(); showDetails(); table.repaint(); });
        colors.addActionListener(e -> table.repaint());
        paused.addActionListener(e -> { if (!paused.isSelected()) DpsGUI.update(); });
        table.getSelectionModel().addListSelectionListener(e -> { if (!updating) showDetails(); });
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filterRows(); }
            public void removeUpdate(DocumentEvent e) { filterRows(); }
            public void changedUpdate(DocumentEvent e) { filterRows(); }
        });
        search.getAccessibleContext().setAccessibleName("Filter player name");
        scope.setToolTipText("DPS uses the same first-to-last target hit interval for every player. All enemies shows full recorded dungeon incoming totals, as in the legacy tooltip. Selecting an enemy limits incoming events to its fight window. Incoming data can include estimates; missing capture cannot be reconstructed.");
    }

    void setContext(Object key, Entity player) {
        if (encounter != key) {
            encounter = key; paused.setSelected(false);
            updating = true; enemyList.clearSelection(); updating = false;
            missingLocalSpawn = key instanceof DpsData && missingLocalSpawn((DpsData)key);
        }
        localPlayer = player;
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
        if (paused.isSelected()) return;
        targets = new ArrayList<>(entities); mapName = map == null ? "No encounter" : map.name; live = isLive;
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
        enemies.clear(); enemies.addElement(null); sorted.forEach(enemies::addElement);
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
            if (Filter.shouldFilter() && Filter.filter(row.player, localPlayer) != 1) continue;
            visible.add(row);
        }
        updateMeterMaximum();
        model.fireTableDataChanged();
        if (selected != null) for (int i = 0; i < visible.size(); i++) if (visible.get(i).player.id == selected) {
            int view = table.convertRowIndexToView(i); table.setRowSelectionInterval(view, view); break;
        }
        updating = false;
        summary.setText(mapName + "  ·  " + (live ? "LIVE" : "SAVED") + "  ·  " + scopedEnemies + " enemies  ·  " + visible.size() + "/" + snapshot.rows.size() + " players  ·  DMG: " + number(snapshot.total));
        summary.setToolTipText(summary.getText().startsWith("<html>") ? " " + summary.getText() : summary.getText());
        scope.setText(String.format(Locale.ROOT, "%.1fs DPS window · Taken: %s · — = no recorded data", snapshot.seconds,
            wholeEncounter ? "full dungeon" : "fight window, all sources"));
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
    private void showDetails() {
        int index = table.getSelectedRow();
        if (index < 0) { details.setText(visible.isEmpty() ? "No players match this view. Clear filters or choose another enemy." : "Select a player for hit details. Click column headers to sort; drag dividers to resize."); return; }
        CombatMeterData.Row row = visible.get(table.convertRowIndexToModel(index));
        boolean incoming = metric.getSelectedIndex() >= 3;
        StringBuilder text = new StringBuilder(String.valueOf(row.player.name())).append(" · ").append(row.className()).append("\n");
        text.append("Damage: ").append(number(row.damage)).append(" · Hits: ").append(row.hits)
            .append(" · Max hit: ").append(number(row.biggest)).append("\n");
        if (row.incomingAvailable) text.append("Taken (recorded/estimated): ").append(number(row.taken))
            .append(" · Incoming events: ").append(row.incomingHits).append("\n");
        if (row.incomingAvailable && !wholeEncounter) text.append("Full dungeon taken: ").append(number(row.totalTaken))
            .append(" · Incoming events: ").append(row.totalIncomingHits).append("\n");
        if (incoming && !row.incomingAvailable) text.append("Incoming damage is unavailable: no incoming events were recorded for this player.");
        else {
            List<Damage> hits = incoming ? row.incoming : row.outgoing;
            text.append(incoming ? (wholeEncounter ? "Incoming recorded/estimated · full dungeon\n" : "Incoming recorded/estimated · all sources during selected hit window\n") : "Outgoing recorded hits · selected enemies\n");
            text.append("  Time        Damage    Source\n");
            // Keep live detail rendering bounded; totals always include every hit.
            int start = Math.max(0, hits.size() - 500);
            if (start > 0) text.append("Showing latest 500 of ").append(hits.size()).append(" hits\n");
            for (int i = start; i < hits.size(); i++) {
                Damage hit = hits.get(i);
                String source = incoming ? (hit.owner == null ? "AoE / ground / unknown" : String.valueOf(hit.owner.name())) :
                    (hit.projectile == null || hit.projectile.getContainerType() <= 0 ? "Unknown item / generic" : "Item #" + hit.projectile.getContainerType());
                text.append(String.format(Locale.ROOT, "%7.2fs  %,10d    %s%n", (hit.time - snapshot.first) / 1000.0, hit.damage, source));
            }
        }
        int caret = details.getCaretPosition(); details.setText(text.toString()); details.setCaretPosition(Math.min(caret, details.getDocument().getLength()));
    }
    protected void editFont(Font font) {
        ContentStyle.tableFont(table, font, 0); ContentStyle.font(enemyList, font);
        ContentStyle.font(details, ContentStyle.report(font));
        ContentStyle.font(summary, ContentStyle.emphasis(font)); ContentStyle.font(scope, ContentStyle.metadata(font));
        ContentStyle.font(captureWarning, font);
    }
    private static Color classColor(int type) {
        Color surface = UIManager.getColor("Table.background");
        boolean dark = surface == null || surface.getRed() < 128;
        return Color.getHSBColor((type * .618034f) % 1f, dark ? .36f : .72f, dark ? .94f : .48f);
    }
    private static String number(long value) { return String.format(Locale.ROOT, "%,d", value); }
    private final class MeterModel extends AbstractTableModel {
        private final String[] names = {"Player / meter", "Class", "Damage", "DPS", "Share %", "Hits dealt", "Avg hit", "Max hit", "Taken (est.)", "Hits taken"};
        public int getRowCount() { return visible.size(); }
        public int getColumnCount() { return names.length; }
        public String getColumnName(int c) { return names[c]; }
        public Class<?> getColumnClass(int c) { return c < 2 ? String.class : (c == 3 || c == 4 || c == 6 ? Double.class : Long.class); }
        public Object getValueAt(int r, int c) {
            CombatMeterData.Row row = visible.get(r);
            switch (c) {
                case 0: return row.player.name(); case 1: return row.className(); case 2: return row.damage;
                case 3: return snapshot.dps(row); case 4: return snapshot.total == 0 ? 0.0 : row.damage * 100.0 / snapshot.total;
                case 5: return row.hits; case 6: return row.hits == 0 ? null : (double)row.damage / row.hits;
                case 7: return row.biggest; case 8: return row.incomingAvailable ? row.taken : null;
                default: return row.incomingAvailable ? row.incomingHits : null;
            }
        }
    }
    private final class BarRenderer extends ContentStyle.Cell {
        private double fraction;
        private Color color;
        private final JLabel amountLabel = new JLabel("", SwingConstants.RIGHT);
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
            super.getTableCellRendererComponent(t, value, selected, focus, row, column);
            int c = metricColumn();
            int m = t.convertRowIndexToModel(row); Object n = model.getValueAt(m, c);
            fraction = meterMaximum <= 0 || !(n instanceof Number) ? 0 : ((Number)n).doubleValue() / meterMaximum;
            CombatMeterData.Row entry = visible.get(m);
            color = colors.isSelected() ? classColor(entry.player.objectType) : ContentStyle.color("violet");
            String amount = n instanceof Double ? String.format(Locale.ROOT, "%,.1f", n) : n instanceof Number ? number(((Number)n).longValue()) : "—";
            amountLabel.setText(amount); amountLabel.setFont(ContentStyle.metadata(t.getFont()));
            amountLabel.setForeground(getForeground());
            setBorder(BorderFactory.createCompoundBorder(getBorder(),
                BorderFactory.createEmptyBorder(0, 0, 0, amountLabel.getPreferredSize().width + 8)));
            setFont(ContentStyle.emphasis(t.getFont()));
            setText((row + 1) + "   " + String.valueOf(value) + (entry.player.isUser() ? " (you)" : "") + (Filter.filter(entry.player, localPlayer) == 2 ? " ★" : ""));
            setToolTipText(String.valueOf(value) + " · " + entry.className() + " · " + metric.getSelectedItem() + ": " + amount);
            setOpaque(false); return this;
        }
        protected void paintComponent(Graphics g) {
            Graphics2D bars = (Graphics2D) g.create();
            bars.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            bars.setColor(getBackground()); bars.fillRect(0, 0, getWidth(), getHeight());
            bars.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 42));
            bars.fillRoundRect(4, 3, (int)((getWidth() - 8) * fraction), getHeight() - 6, 4, 4);
            bars.setColor(color); bars.fillRect(4, 6, 3, getHeight() - 12);
            bars.dispose();
            super.paintComponent(g);
            int width = Math.min(getWidth(), amountLabel.getPreferredSize().width);
            amountLabel.setSize(width, getHeight());
            Graphics valueGraphics = g.create(getWidth() - width - 8, 0, width, getHeight());
            amountLabel.paint(valueGraphics); valueGraphics.dispose();
        }
    }
}
