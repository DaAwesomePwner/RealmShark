package tomato.gui.runs;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import tomato.gui.kit.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

/**
 * The run recap's Damage section (spec §6.3): one of the run's exactly linked recordings, as the meter recorded it (all
 * contributors). From the top: the recording picker ({@code run-recap-recording}, only with several recordings; choosing one asks
 * for a rebuilt model through {@link #onRecording}), the recording's totals, the reasons that apply (no rows, no row marked as
 * yours, no saved detail), the damage-over-time chart ({@link DamageChart}), the meter table ({@code run-recap-meter}: rank, class
 * sprite and name, damage with a bar, DPS, share, hits, max hit, taken, deaths; the rank column as narrow as its digits, the numbers
 * at their ColumnKind widths and the Player column taking the section's spare width, so a compact window shows rank, player, damage
 * and DPS before the table scrolls its other columns) and the damage by source of one player
 * ({@code run-recap-sources}). The verified local row is washed in {@link Tokens.Role#ACCENT_WASH} and named "(you)", so color is
 * not the only cue; no row is selected at first, so the wash shows, and the sources show your row (else the top row) until
 * another row is selected. Unknown values are "—" with their reason as the tooltip, never 0. Public for the Recordings tab's read-only
 * summary of one recording ({@code RecordingSummaryPanel}). EDT only.
 */
public final class RunDamagePanel extends JPanel {
    static final String TAKEN_UNKNOWN = "Incoming damage was not observed for this player";
    static final String DEATHS_UNKNOWN = "Deaths are counted only for a unique name in this recording";
    static final String DPS_UNKNOWN = "No first-to-last hit window was recorded, so DPS is unknown.";
    static final String SHARE_UNKNOWN = "No damage was recorded in this recording.";
    static final String NO_SOURCES = "No damage by source was saved for this player.";
    /** The meter shows this many rows before it scrolls. */
    private static final int VISIBLE_ROWS = 12;
    private static final int COLUMN_RANK = 0, COLUMN_PLAYER = 1, COLUMN_DAMAGE = 2;
    private static final String[] COLUMNS = {"#", "Player", "Damage", "DPS", "Share", "Hits", "Max hit", "Taken", "Deaths"};
    /** Column widths by kind, except the rank's (no kind is as narrow as a rank: {@link #fitRank} measures its digits). */
    private static final ColumnKind[] KINDS = {null, ColumnKind.PLAYER, ColumnKind.NUMBER, ColumnKind.NUMBER, ColumnKind.PERCENT,
        ColumnKind.COUNT, ColumnKind.NUMBER, ColumnKind.NUMBER, ColumnKind.COUNT};

    private final JLabel pickerLabel = new JLabel("Recording");
    private final JComboBox<RunRecapModel.Damage.Recording> picker = new JComboBox<>() {
        // The row gives the picker the width it has; its height follows the font.
        @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }
    };
    private final JPanel pickerRow = new JPanel(new BorderLayout(Tokens.S, 0));
    private final JTextArea summary = ContentStyle.wrappingText("");
    private final JTextArea reason = ContentStyle.wrappingText(""), local = ContentStyle.wrappingText(""), detail = ContentStyle.wrappingText("");
    private final DamageChart chart;
    private final MeterModel model = new MeterModel();
    private final JTable meter = new JTable(model) {
        // Fill the section while the columns fit; past that the table scrolls its own columns sideways.
        @Override public boolean getScrollableTracksViewportWidth() { return getParent() != null && getPreferredSize().width < getParent().getWidth(); }

        /**
         * The numbers keep their kind's width and the rank its digits' width; the Player column takes whatever the section gives
         * beyond them (the stock layout would share it among all nine columns, widening "#" most of all). A header drag resizes
         * as usual and becomes the columns' preferred widths.
         */
        @Override public void doLayout() {
            javax.swing.table.JTableHeader header = getTableHeader();
            if (header != null && header.getResizingColumn() != null) { super.doLayout(); return; }
            int preferred = 0;
            for (int c = 0; c < getColumnCount(); c++) preferred += getColumnModel().getColumn(c).getPreferredWidth();
            int extra = Math.max(0, getWidth() - preferred);
            for (int c = 0; c < getColumnCount(); c++) {
                TableColumn column = getColumnModel().getColumn(c);
                column.setWidth(column.getPreferredWidth() + (column.getModelIndex() == COLUMN_PLAYER ? extra : 0));
            }
        }
    };
    private final JScrollPane meterScroll = new JScrollPane(meter) {
        @Override public Dimension getPreferredSize() {
            Insets insets = getInsets();
            int header = meter.getTableHeader() == null ? 0 : meter.getTableHeader().getPreferredSize().height;
            int rows = Math.max(1, Math.min(VISIBLE_ROWS, meter.getRowCount()));
            boolean sideways = getViewport().getWidth() <= 0 || meter.getPreferredSize().width > getViewport().getWidth();
            return new Dimension(120, header + rows * meter.getRowHeight() + insets.top + insets.bottom
                + (sideways ? getHorizontalScrollBar().getPreferredSize().height : 0));
        }
        @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }
    };
    private final KitText sourcesTitle = KitText.emphasis(" ");
    private final KitText sourcesHint = KitText.caption("Select a player in the meter to see their damage by source.");
    private final JTextArea sourcesReason = ContentStyle.wrappingText("");
    private final JPanel sourceRows = KitLayouts.stack(Tokens.XS);
    private final JPanel sources;
    private RunRecapModel.Damage shown;
    private RunRecapModel.Damage.Row sourcesRow;
    private Consumer<String> recording = id -> { };
    private boolean applying;
    /** The rank column's width as {@link #fitRank} last set it; 0 before the first fit. */
    private int rankWidth;

    public RunDamagePanel(DisplayModeModel mode) {
        super(new BorderLayout());
        setOpaque(false);
        setName("run-recap-damage-panel");
        chart = new DamageChart(mode);
        chart.setName("run-recap-chart");
        pickerLabel.putClientProperty("html.disable", Boolean.TRUE);
        ContentStyle.font(pickerLabel, Type.caption());
        pickerLabel.setLabelFor(picker);
        picker.setName("run-recap-recording");
        picker.getAccessibleContext().setAccessibleName("Recording");
        picker.setToolTipText("The run's recordings, longest first; tiles always use the longest");
        picker.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                List<RunRecapModel.Damage.Recording> recordings = shown == null ? List.of() : shown.recordings();
                int position = recordings.indexOf(value);
                setText(position < 0 ? " " : recordingLabel(recordings, position));
                // The open list's highlight uses the app's selection roles: the stock combo-box selection is 4.03:1 in Violet Dark.
                if (selected && index >= 0) {
                    setBackground(Tokens.color(Tokens.Role.SELECTION));
                    setForeground(Tokens.color(Tokens.Role.SELECTION_TEXT));
                }
                return this;
            }
        });
        picker.addActionListener(e -> {
            if (applying || shown == null) return;
            RunRecapModel.Damage.Recording chosen = (RunRecapModel.Damage.Recording) picker.getSelectedItem();
            if (chosen != null && !chosen.id().equals(shown.selected())) recording.accept(chosen.id());
        });
        pickerRow.setOpaque(false);
        pickerRow.setName("run-recap-recording-row");
        pickerRow.add(pickerLabel, BorderLayout.WEST);
        pickerRow.add(picker, BorderLayout.CENTER);
        summary.setName("run-recap-damage-summary");
        reason.setName("run-recap-damage-reason");
        local.setName("run-recap-damage-local");
        detail.setName("run-recap-damage-detail");
        for (JTextArea note : new JTextArea[] {summary, reason, local, detail, sourcesReason}) note.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));

        meter.setName("run-recap-meter");
        meter.getAccessibleContext().setAccessibleName("Damage meter");
        ContentStyle.table(meter, ContentStyle.Density.COMFORTABLE);
        meter.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        meter.getTableHeader().setReorderingAllowed(false);
        MeterCell cell = new MeterCell();
        for (int c = 0; c < COLUMNS.length; c++) {
            TableColumn column = meter.getColumnModel().getColumn(c);
            if (KINDS[c] != null) KitTables.fitKind(meter, column, KINDS[c]);
            column.setCellRenderer(cell);
        }
        fitRank();
        // The rank's width follows the table and header fonts, re-fitted once both have settled (as KitTables does for kinds).
        java.beans.PropertyChangeListener refit = e -> SwingUtilities.invokeLater(this::fitRank);
        meter.addPropertyChangeListener("font", refit);
        meter.getTableHeader().addPropertyChangeListener("font", refit);
        meter.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || applying) return;
            int row = meter.getSelectedRow();
            if (row >= 0) showSources(model.rows.get(meter.convertRowIndexToModel(row)));
        });
        meterScroll.setName("run-recap-meter-scroll");
        meterScroll.getVerticalScrollBar().setUnitIncrement(24);

        sourcesTitle.setName("run-recap-sources-title");
        sourcesHint.setName("run-recap-sources-hint");
        sourcesReason.setName("run-recap-sources-reason");
        sourceRows.setName("run-recap-source-rows");
        sources = KitLayouts.stack(Tokens.XS, sourcesTitle, sourcesHint, sourcesReason, sourceRows);
        sources.setName("run-recap-sources");
        sources.getAccessibleContext().setAccessibleName("Damage by source");
        add(KitLayouts.stack(Tokens.S, pickerRow, summary, reason, local, detail, chart, meterScroll, sources), BorderLayout.CENTER);
        // Nothing shows until the first model arrives.
        for (JComponent part : new JComponent[] {pickerRow, summary, reason, local, detail, chart, meterScroll, sources}) part.setVisible(false);
    }

    @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }

    @Override public void updateUI() {
        super.updateUI();
        if (reason == null) return;   // null while JPanel's constructor runs
        for (JTextArea note : new JTextArea[] {summary, reason, local, detail, sourcesReason}) note.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
    }

    /** What choosing another recording in the picker asks for (its id); the rebuilt model arrives through {@link #show}. */
    void onRecording(Consumer<String> listener) { recording = Objects.requireNonNull(listener, "listener"); }

    /** The row whose damage by source shows, or null without rows. */
    RunRecapModel.Damage.Row sourcesRow() { return sourcesRow; }

    /** EDT: applies one Damage section; an equal one changes nothing (the table keeps its selection and scroll). */
    public void show(RunRecapModel.Damage damage) {
        Objects.requireNonNull(damage, "damage");
        if (damage.equals(shown)) return;
        shown = damage;
        applying = true;
        try {
            boolean recorded = damage.selected() != null, rows = !damage.rows().isEmpty();
            DefaultComboBoxModel<RunRecapModel.Damage.Recording> recordings = new DefaultComboBoxModel<>();
            for (RunRecapModel.Damage.Recording item : damage.recordings()) recordings.addElement(item);
            picker.setModel(recordings);
            RunRecapModel.Damage.Recording selected = damage.selectedRecording();
            picker.setSelectedItem(selected);
            boolean several = damage.recordings().size() > 1;
            picker.setVisible(several);
            pickerRow.setVisible(several);

            text(summary, recorded ? summary(damage) : null);
            summary.setToolTipText(recorded ? "Total " + DisplayFormat.formatInteger(damage.total()) + " damage recorded, unattributed hits included" : null);
            text(reason, damage.reason());
            text(local, rows && damage.localReason() != null ? "No row is marked as yours. " + damage.localReason() : null);
            text(detail, rows ? damage.detailReason() : null);

            List<DamageChart.Series> series = new ArrayList<>();
            for (RunRecapModel.Damage.Series s : damage.series()) series.add(new DamageChart.Series(s.name(), s.local(), s.values()));
            chart.setData(series, damage.bucketSeconds(), damage.bucketOrigin(), damage.rows().size(), damage.hitsBeforeFirstTick());
            chart.setVisible(recorded && rows && damage.detailReason() == null);

            model.set(damage.rows());
            fitRank();   // a recording with 100 or more players needs a third digit
            meter.clearSelection();
            meterScroll.setVisible(rows);
            sources.setVisible(rows);
            RunRecapModel.Damage.Row first = damage.local() != null ? damage.local() : rows ? damage.rows().get(0) : null;
            sourcesRow = null;
            if (first != null) showSources(first);
        } finally {
            applying = false;
        }
        revalidate();
        repaint();
    }

    /**
     * Sizes the rank column narrow and numeric: the widest rank the shown recording can have (at least two digits) or the "#"
     * header, whichever is wider, plus the cell's padding (ContentStyle.Cell pads 8 px on each side, as ColumnKind widths assume).
     * A width the user dragged is kept, as KitTables keeps it for the kinds.
     */
    private void fitRank() {
        TableColumn column = meter.getColumnModel().getColumn(COLUMN_RANK);
        if (rankWidth > 0 && column.getPreferredWidth() != rankWidth) return;
        String digits = "0".repeat(Math.max(2, String.valueOf(model.getRowCount()).length()));
        int width = meter.getFontMetrics(meter.getFont()).stringWidth(digits) + 16;
        javax.swing.table.TableCellRenderer header = column.getHeaderRenderer() != null ? column.getHeaderRenderer() : meter.getTableHeader().getDefaultRenderer();
        width = Math.max(width, header.getTableCellRendererComponent(meter, column.getHeaderValue(), false, false, -1, COLUMN_RANK).getPreferredSize().width);
        column.setPreferredWidth(width);
        column.setWidth(width);
        rankWidth = column.getPreferredWidth();
    }

    /** "Recording 1 of 2 · longest · 40 s window · 3 players"; the list is longest first. */
    static String recordingLabel(List<RunRecapModel.Damage.Recording> recordings, int index) {
        RunRecapModel.Damage.Recording recording = recordings.get(index);
        Double window = recording.windowSeconds();
        return "Recording " + (index + 1) + " of " + recordings.size() + (index == 0 ? " · longest" : "") + " · "
            + (window == null ? "window unknown" : DisplayFormat.formatNumber(window, 0, 1) + " s window") + " · "
            + recording.contributors() + (recording.contributors() == 1 ? " player" : " players");
    }

    /** "Total 100k damage · 40 s first-to-last hit window · 3 players[ · 1,234 unattributed]"; only for a shown recording. */
    private static String summary(RunRecapModel.Damage damage) {
        Double window = damage.windowSeconds();
        int players = damage.rows().size();
        return "Total " + KitFormat.compact(damage.total()) + " damage · "
            + (window == null ? "hit window unknown" : DisplayFormat.formatNumber(window, 0, 1) + " s first-to-last hit window") + " · "
            + players + (players == 1 ? " player" : " players")
            + (damage.unattributed() > 0 ? " · " + DisplayFormat.formatInteger(damage.unattributed()) + " unattributed" : "");
    }

    /** "Alpha (you)", "Bravo", "Unnamed player". */
    static String label(RunRecapModel.Damage.Row row) {
        String name = row.name() == null || row.name().isEmpty() ? "Unnamed player" : row.name();
        return row.local() ? name + " (you)" : name;
    }

    private static void text(JTextArea area, String value) {
        boolean show = value != null && !value.isEmpty();
        if (!(show ? value : "").equals(area.getText())) area.setText(show ? value : "");
        area.setVisible(show);
    }

    /** Shows one row's damage by source: the saved sources, largest first, each with its share of the row and its top items. */
    private void showSources(RunRecapModel.Damage.Row row) {
        if (row.equals(sourcesRow)) return;
        sourcesRow = row;
        sourcesTitle.setText("Damage by source · " + label(row));
        sourceRows.removeAll();
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.anchor = GridBagConstraints.NORTHWEST;
        int y = 0;
        for (RunRecapModel.Damage.Source source : row.sources()) {
            c.gridy = y; c.insets = new Insets(y == 0 ? 0 : Tokens.XS, 0, 0, 0);
            sourceRows.add(sourceRow(row, source), c);
            y++;
        }
        c.gridy = y; c.weighty = 1; c.insets = new Insets(0, 0, 0, 0);
        sourceRows.add(Box.createVerticalGlue(), c);
        sourceRows.setVisible(!row.sources().isEmpty());
        text(sourcesReason, row.sources().isEmpty() ? shown != null && shown.detailReason() != null ? shown.detailReason() : NO_SOURCES : null);
        sources.revalidate();
        sources.repaint();
    }

    private static JComponent sourceRow(RunRecapModel.Damage.Row row, RunRecapModel.Damage.Source source) {
        KitText name = KitText.body(source.label());
        name.setName("run-recap-source-name");
        String share = row.damage() > 0 ? DisplayFormat.formatNumber(source.damage() * 100.0 / row.damage(), 0, 1) + "%" : null;
        String text = KitFormat.compact(source.damage()) + (share == null ? "" : " · " + share) + " · "
            + DisplayFormat.formatInteger(source.hits()) + (source.hits() == 1 ? " hit" : " hits");
        KitText values = KitText.caption(text);
        values.setName("run-recap-source-detail");
        values.setToolTipText(DisplayFormat.formatInteger(source.damage()) + " damage from " + source.label().toLowerCase(java.util.Locale.ROOT));
        JPanel items = new JPanel(new FlowLayout(FlowLayout.LEADING, Tokens.XS, 0));
        items.setOpaque(false);
        items.setName("run-recap-source-items");
        List<String> itemNames = new ArrayList<>();
        for (RunRecapModel.Damage.Item item : source.items()) {
            if (item.itemId() <= 0 || items.getComponentCount() == 4) continue;
            ItemSlot slot = new ItemSlot(20);
            slot.setItem(item.itemId(), ItemTiers.label(item.itemId()));
            items.add(slot);
            itemNames.add(Sprites.name(item.itemId()) + " " + KitFormat.compact(item.damage()));
        }
        items.setVisible(items.getComponentCount() > 0);
        JPanel line = KitLayouts.spread(Tokens.S, name, values, items);
        line.setName("run-recap-source");
        line.getAccessibleContext().setAccessibleName(source.label() + ": " + text + (itemNames.isEmpty() ? "" : "; top items " + String.join(", ", itemNames)));
        return line;
    }

    /** The meter rows as cell values; the renderer turns them into text, icons and bars. */
    private static final class MeterModel extends AbstractTableModel {
        private List<RunRecapModel.Damage.Row> rows = List.of();
        private long max;

        void set(List<RunRecapModel.Damage.Row> next) {
            rows = next;
            max = 0;
            for (RunRecapModel.Damage.Row row : rows) max = Math.max(max, row.damage());
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }
        @Override public boolean isCellEditable(int row, int column) { return false; }

        @Override public Object getValueAt(int index, int column) {
            RunRecapModel.Damage.Row row = rows.get(index);
            switch (column) {
                case 0: return row.rank();
                case 1: return new KitTables.IconText(Sprites.sprite(row.classType(), 18), label(row));
                case 2: return row.damage();
                case 3: return row.dps() == null || !Double.isFinite(row.dps()) ? DisplayValue.unknown(DPS_UNKNOWN)
                    : DisplayValue.known(KitFormat.compact(row.dps()), "Damage over the recording's shared first-to-last hit window");
                case 4: return row.share() == null ? DisplayValue.unknown(SHARE_UNKNOWN)
                    : DisplayValue.known(DisplayFormat.formatNumber(row.share(), 0, 1) + "%", "Share of all damage recorded in this recording, unattributed hits included");
                case 5: return row.hits();
                case 6: return row.maxHit();
                case 7: return row.taken() == null ? DisplayValue.unknown(TAKEN_UNKNOWN) : DisplayValue.count(row.taken(), "Incoming damage observed for this player", null);
                case 8: return row.deaths() == null ? DisplayValue.unknown(DEATHS_UNKNOWN)
                    : DisplayValue.count(row.deaths().longValue(), "Death notifications naming this player in this recording", null);
                default: return null;
            }
        }
    }

    /** Text, icons and the damage bar; the verified local row keeps the accent wash unless selected. */
    private final class MeterCell extends ContentStyle.Cell {
        private double bar = -1;
        private boolean mine;

        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            int modelColumn = table.convertColumnIndexToModel(column);
            RunRecapModel.Damage.Row line = model.rows.get(table.convertRowIndexToModel(row));
            mine = line.local();
            bar = -1;
            setToolTipText(null);
            setHorizontalAlignment(modelColumn == COLUMN_PLAYER ? LEFT : RIGHT);
            if (value instanceof DisplayValue) {
                DisplayValue shownValue = (DisplayValue) value;
                setText(shownValue.display());
                setToolTipText(shownValue.tooltip());
                if (!selected && shownValue.dimmed()) setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
            } else if (value instanceof KitTables.IconText) {
                setIcon(((KitTables.IconText) value).icon);
                setIconTextGap(6);
                setText(((KitTables.IconText) value).text);
            } else if (modelColumn == COLUMN_DAMAGE) {
                long damage = ((Number) value).longValue();
                setText(KitFormat.compact(damage));
                setToolTipText(DisplayFormat.formatInteger(damage));
                bar = model.max <= 0 ? 0 : damage / (double) model.max;
            } else if (modelColumn == COLUMN_RANK) {
                setText(String.valueOf(value));
            } else {
                setText(DisplayFormat.formatInteger(((Number) value).longValue()));
            }
            if (!selected && mine) setBackground(Tokens.color(Tokens.Role.ACCENT_WASH));
            return this;
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (bar < 0) return;
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int inset = 8, width = Math.max(0, getWidth() - 2 * inset);
            g.setColor(Tokens.color(Tokens.Role.BORDER_SUBTLE));
            g.fillRoundRect(inset, getHeight() - 5, width, 3, 3, 3);
            g.setColor(Tokens.color(mine ? Tokens.Role.ACCENT : Tokens.Role.TEXT_MUTED));
            g.fillRoundRect(inset, getHeight() - 5, (int) Math.round(width * Math.min(1, bar)), 3, 3, 3);
            g.dispose();
        }
    }
}
