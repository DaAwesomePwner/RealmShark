package tomato.gui.dps;

import tomato.backend.data.Damage;
import tomato.backend.data.DamageSource;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;

/**
 * Paged table over every retained hit of one meter row (outgoing or incoming), with time, amount,
 * item/source and flag filters and the selected event's loadout evidence. Works on a detached copy of the
 * row's hit lists, so live updates never move the explored population. EDT only.
 */
final class DamageEventExplorer extends JPanel {
    private final String player;
    private final List<DamageEvents.Event> outgoing, incoming;
    private final boolean incomingAvailable;
    private final JComboBox<String> direction = new JComboBox<>(new String[]{"Outgoing hits", "Incoming hits"});
    private final JTextField from = field("From seconds (inclusive)", 5), until = field("Until seconds (exclusive)", 5);
    private final JTextField minimum = field("Minimum damage", 5), maximum = field("Maximum damage", 5);
    private final JTextField text = field("Item, source or attacker contains", 12), jump = field("Go to event number", 5);
    private final JComboBox<Object> source = new JComboBox<>(), flag = new JComboBox<>();
    private final JLabel status = new JLabel(" ");
    private final JTextArea detail = ContentStyle.wrappingText("Select an event to see its retained loadout evidence.", 6);
    private final JButton previous = new JButton("Previous page"), next = new JButton("Next page");
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private List<DamageEvents.Event> filtered = Collections.emptyList();
    private int page;

    DamageEventExplorer(String player, List<Damage> outgoing, List<Damage> incoming, boolean incomingAvailable, long start, String scope) {
        super(new BorderLayout(0, 6));
        setName("dps-event-explorer");
        this.player = player == null || player.isEmpty() ? "Unknown player" : player;
        this.outgoing = DamageEvents.of(outgoing, false, start);
        this.incoming = DamageEvents.of(incoming, true, start);
        this.incomingAvailable = incomingAvailable;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JLabel title = new JLabel(this.player + " · " + scope + " · every retained event, not only the latest 500; times are seconds from the first recorded hit");
        title.putClientProperty("html.disable", true);
        ContentStyle.font(title, ContentStyle.metadata(ContentStyle.body()));
        direction.setName("dps-event-direction"); direction.getAccessibleContext().setAccessibleName("Hit direction");
        source.setName("dps-event-source"); source.getAccessibleContext().setAccessibleName("Damage source (outgoing)");
        source.addItem("Any source"); for (DamageSource value : DamageSource.values()) source.addItem(value);
        flag.setName("dps-event-flag"); flag.getAccessibleContext().setAccessibleName("Counter flag");
        flag.addItem("Any or no flag"); for (DamageEvents.Flag value : DamageEvents.Flag.values()) flag.addItem(value);
        JPanel filters = ContentStyle.controls();
        filters.add(direction); filters.add(labeled("From s", from)); filters.add(labeled("until s", until));
        filters.add(labeled("Damage ≥", minimum)); filters.add(labeled("≤", maximum)); filters.add(labeled("Item / source", text));
        filters.add(source); filters.add(flag);
        JButton apply = new JButton("Apply filters"), reset = new JButton("Reset filters"), go = new JButton("Go to event");
        apply.setName("dps-event-apply"); reset.setName("dps-event-reset"); go.setName("dps-event-go");
        filters.add(apply); filters.add(reset);
        JPanel paging = ContentStyle.controls();
        paging.add(previous); paging.add(next); paging.add(labeled("Event #", jump)); paging.add(go); paging.add(status);
        status.setName("dps-event-status"); status.putClientProperty("html.disable", true);
        JPanel top = new JPanel(); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{title, filters, paging}) { c.setAlignmentX(LEFT_ALIGNMENT); top.add(c); }
        add(top, BorderLayout.NORTH);
        table.setName("dps-event-table"); ContentStyle.table(table, ContentStyle.Density.DENSE);
        table.getAccessibleContext().setAccessibleName("Retained damage events, paged");
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ContentStyle.Cell cell = new ContentStyle.Cell(); cell.putClientProperty("html.disable", true);
        table.setDefaultRenderer(Object.class, cell); table.setDefaultRenderer(String.class, cell);
        detail.setName("dps-event-loadout"); detail.setFocusable(true);
        detail.getAccessibleContext().setAccessibleName("Selected event loadout evidence");
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, ContentStyle.tableScroll(table, 5), new JScrollPane(detail));
        split.setResizeWeight(.65); split.setBorder(null);
        add(split, BorderLayout.CENTER);
        direction.setEnabled(incomingAvailable);
        direction.setToolTipText(incomingAvailable ? "Outgoing or incoming recorded hits" : "Incoming damage is unavailable: no incoming events were recorded for this player");
        direction.addActionListener(e -> { source.setEnabled(direction.getSelectedIndex() == 0); applyFilters(); });
        apply.addActionListener(e -> applyFilters());
        reset.addActionListener(e -> { for (JTextField f : new JTextField[]{from, until, minimum, maximum, text}) f.setText(""); source.setSelectedIndex(0); flag.setSelectedIndex(0); applyFilters(); });
        previous.addActionListener(e -> showPage(page - 1));
        next.addActionListener(e -> showPage(page + 1));
        go.addActionListener(e -> { try { goToEvent(Integer.parseInt(jump.getText().trim())); } catch (NumberFormatException bad) { status.setText("Enter an event number"); } });
        jump.addActionListener(e -> go.doClick());
        table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showDetail(); });
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, KeyEvent.CTRL_DOWN_MASK), "next-page");
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, KeyEvent.CTRL_DOWN_MASK), "previous-page");
        getActionMap().put("next-page", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { showPage(page + 1); } });
        getActionMap().put("previous-page", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { showPage(page - 1); } });
        previous.setToolTipText("Previous page (Ctrl+Page Up)"); next.setToolTipText("Next page (Ctrl+Page Down)");
        applyFilters();
    }

    static JDialog open(Component owner, DamageEventExplorer explorer) {
        Window window = owner == null ? null : owner instanceof Window ? (Window) owner : SwingUtilities.getWindowAncestor(owner);
        JDialog dialog = new JDialog(window, "Damage events — " + explorer.player, Dialog.ModalityType.MODELESS);
        dialog.setName("dps-event-dialog"); dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(explorer);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        ContentStyle.refreshFonts(explorer);
        dialog.setSize(900, 600); dialog.setLocationRelativeTo(owner); dialog.setVisible(true);
        return dialog;
    }

    private List<DamageEvents.Event> population() { return direction.getSelectedIndex() == 1 && incomingAvailable ? incoming : outgoing; }

    /** Parses the controls; an invalid entry leaves the previous result and explains the problem. */
    void applyFilters() {
        DamageEvents.Filter filter = new DamageEvents.Filter();
        try {
            filter.fromMillis = seconds(from.getText()); filter.untilMillis = seconds(until.getText());
            filter.minimum = integer(minimum.getText()); filter.maximum = integer(maximum.getText());
        } catch (RuntimeException invalid) { status.setText("Filters not applied: enter numbers"); return; }
        filter.text = text.getText();
        if (source.getSelectedItem() instanceof DamageSource) filter.sources = EnumSet.of((DamageSource) source.getSelectedItem());
        if (flag.getSelectedItem() instanceof DamageEvents.Flag) filter.flags = EnumSet.of((DamageEvents.Flag) flag.getSelectedItem());
        apply(filter);
    }
    void apply(DamageEvents.Filter filter) { filtered = DamageEvents.filter(population(), filter); showPage(0); }

    /** Pages to chronological event {@code number} and selects it; false when absent or excluded by filters. */
    boolean goToEvent(int number) {
        int target = DamageEvents.pageOf(filtered, number, DamageEvents.PAGE_SIZE);
        if (target < 0) { status.setText("Event " + number + " is not in the current filtered events"); return false; }
        showPage(target);
        List<DamageEvents.Event> rows = model.rows;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).number == number) { table.setRowSelectionInterval(i, i); table.scrollRectToVisible(table.getCellRect(i, 0, true)); break; }
        return true;
    }

    private void showPage(int requested) {
        int pages = DamageEvents.pages(filtered.size(), DamageEvents.PAGE_SIZE);
        page = Math.max(0, Math.min(pages - 1, requested));
        model.rows = DamageEvents.page(filtered, page, DamageEvents.PAGE_SIZE);
        model.fireTableDataChanged();
        previous.setEnabled(page > 0); next.setEnabled(page < pages - 1);
        List<DamageEvents.Event> all = population();
        status.setText("Page " + (page + 1) + " of " + pages + " · " + DisplayFormat.formatInteger(filtered.size()) + " matching of "
            + DisplayFormat.formatInteger(all.size()) + " retained " + (direction.getSelectedIndex() == 1 ? "incoming" : "outgoing") + " events");
        showDetail();
    }

    private void showDetail() {
        int row = table.getSelectedRow();
        detail.setText(row < 0 ? (filtered.isEmpty() ? "No retained events match these filters." : "Select an event to see its retained loadout evidence.")
            : DamageEvents.loadout(model.rows.get(table.convertRowIndexToModel(row)), player));
        detail.setCaretPosition(0);
    }

    JTable table() { return table; }
    String detailText() { return detail.getText(); }
    String statusText() { return status.getText(); }
    int pageIndex() { return page; }
    void selectDirection(boolean incomingHits) { direction.setSelectedIndex(incomingHits ? 1 : 0); }

    private static Long seconds(String value) {
        return value.trim().isEmpty() ? null : new java.math.BigDecimal(value.trim()).movePointRight(3).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }
    private static Integer integer(String value) { return value.trim().isEmpty() ? null : Integer.valueOf(value.trim()); }
    private static JTextField field(String name, int columns) { JTextField field = new JTextField(columns); field.getAccessibleContext().setAccessibleName(name); return field; }
    private static JPanel labeled(String text, JComponent component) {
        JPanel group = new JPanel(new BorderLayout(4, 0)); JLabel label = new JLabel(text); label.setLabelFor(component);
        group.add(label, BorderLayout.WEST); group.add(component); return group;
    }

    private static final class Model extends AbstractTableModel {
        private final String[] names = {"#", "Time s", "Damage", "Source / attacker", "Flags"};
        List<DamageEvents.Event> rows = Collections.emptyList();
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return names.length; }
        public String getColumnName(int c) { return names[c]; }
        public Class<?> getColumnClass(int c) { return c == 0 || c == 2 ? Integer.class : String.class; }
        public Object getValueAt(int r, int c) {
            DamageEvents.Event e = rows.get(r);
            switch (c) {
                case 0: return e.number;
                case 1: return e.offset == null ? DisplayFormat.UNAVAILABLE : DisplayFormat.formatDurationSeconds(e.offset, 3);
                case 2: return e.damage();
                case 3: return e.sourceText();
                default: return e.flags.isEmpty() ? "" : e.flags.toString();
            }
        }
    }
}
