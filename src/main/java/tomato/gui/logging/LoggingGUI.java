package tomato.gui.logging;

import com.google.gson.GsonBuilder;
import packets.PacketType;
import packets.packetcapture.logger.DiscoveryCatalog;
import packets.packetcapture.logger.DiscoveryLog;
import packets.packetcapture.logger.ActivityJournal;
import packets.packetcapture.register.Register;
import javax.swing.*;
import javax.swing.table.*;
import javax.swing.event.*;
import java.awt.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.modern.CollectionControl;
import tomato.gui.activity.SnapshotRefresh;
import tomato.gui.history.FilterChips;
import tomato.gui.history.ViewState;
import tomato.gui.history.ViewStateStore;
import tomato.gui.history.WrapRow;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.ColumnKind;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.KitButton;
import tomato.gui.kit.OverflowMenu;
import tomato.gui.kit.Tokens;
import tomato.gui.history.HistoryTables;

/** A searchable, bounded view over sanitized discovery data, refreshed only on the EDT. */
public final class LoggingGUI extends JPanel {
    private static final String[] TAB_KEYS={"discovery","reentry","packets","stats","events","fields"};
    private static final Set<String> REENTRY_PACKETS = new HashSet<>(Arrays.asList(
        "ESCAPE", "PARTY_JOIN_REQUEST", "PARTY_REQUEST_RESPONSE", "PARTY_ACTION", "PARTY_ACTION_RESULT",
        "FOR_RECONNECT", "RECONNECT", "HELLO", "QUEUE_INFORMATION", "FAILURE", "MAPINFO", "LOAD", "CREATE_SUCCESS"));
    private final DiscoveryLog log;
    private final JLabel summary = new JLabel(), losses = new JLabel(), exportStatus = new JLabel(" ");
    private final JTextField search = new JTextField(12); // 12 columns: one chip and Clear still fit the row at 1240×800, font 13.
    private final JCheckBox observedOnly = new JCheckBox("Observed packets only");
    private final JCheckBox issuesOnly = new JCheckBox("Packet issues only");
    private final JCheckBox freeze = new JCheckBox("Pause this view");
    private final CollectionControl enabled;
    // One filter row (S6): [Search][Reset filters] [Filters · n][chips][Clear] … [collection][Pause][⋯]; the facets live in the drawer.
    private final FilterBar filterBar = new FilterBar("logging");
    private final KitButton reset = KitButton.ghost("Reset filters"), coverage = KitButton.ghost("Diagnostic coverage");
    private final JPanel searchRow, trailing = new JPanel(new GridBagLayout()) {
        // As tall as the search slot's line, so collection and Pause centre on the search field.
        @Override public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            size.height = Math.max(size.height, Math.max(search.getPreferredSize().height, reset.getPreferredSize().height) + 4);
            return size;
        }
    };
    private boolean narrowRow;
    // ⋯ items: disk samples and the sampling rate are collector settings, not view state.
    private final JCheckBoxMenuItem save = new JCheckBoxMenuItem("Save diagnostic samples");
    private final JRadioButtonMenuItem sampled = new JRadioButtonMenuItem("Sampled"), detailed = new JRadioButtonMenuItem("Detailed");
    private final CustomizableTabs tabGroup = new CustomizableTabs("logging");
    private final JTabbedPane tabs = tabGroup.component();
    private boolean tabSyncPending;
    private final JPanel facets = ContentStyle.controls();
    private final JLabel counts = new JLabel();
    private final JComboBox<String> packetFacet = new JComboBox<>(), statFacet = new JComboBox<>(), objectFacet = new JComboBox<>(), areaFacet = new JComboBox<>(), outcomeFacet = new JComboBox<>();
    private final JCheckBox changedOnly = new JCheckBox("Changed values only");
    private final JButton samplesLink = new JButton("View retained samples"), fieldLink = new JButton("Open field definition");
    private final JComboBox<String> fieldChoice = new JComboBox<>();
    private final JComboBox<LoggingReport.Source> exportSource = new JComboBox<>(LoggingReport.Source.values());
    private final JButton openFolder = new JButton("Open report folder");
    private Path reportFolder;
    private final List<DiscoveryCatalog.SchemaField> catalog = DiscoveryCatalog.fields();
    private final DataTable packets = new DataTable("ID", "Packet", "Direction", "Evidence", "Count", "Bytes", "Decode errors", "Trailing", "Type listeners");
    private final DataTable stats = new DataTable("ID", "Stat", "Observations", "Changes", "Latest", "Secondary", "Min", "Max", "Withheld");
    private final DataTable events = new DataTable("Time", "Area", "Packet", "Outcome", "Bytes", "Selected values / stat samples");
    private final DataTable fields = new DataTable("Packet", "Field path", "Java type", "Retention");
    private final DataTable discoveries = new DataTable("Area", "Evidence", "Fields to explore", "Sources");
    private final DataTable reentry = new DataTable("Time", "Area", "Step", "Direction", "Since prior", "Retained evidence");
    private final JTextArea details = new JTextArea();
    private final javax.swing.Timer timer;
    private DiscoveryLog.Snapshot snapshot;
    private boolean refreshing;
    private boolean exporting;
    private Locale presentationLocale;
    private ZoneId presentationZone;
    private volatile DiscoveryLog.DiagnosticsRevision revision;
    private final SnapshotRefresh<DiscoveryLog.DiagnosticsSnapshot> snapshots=new SnapshotRefresh<>();
    private LoggingViewState viewState;
    private JSplitPane split;
    private boolean applyingState, stateReady;
    private int activeTab;

    public LoggingGUI(DiscoveryLog log) {
        this(log,ViewStateStore.application());
    }
    LoggingGUI(DiscoveryLog log, ViewStateStore store) {
        super(new BorderLayout(0, 8)); this.log = log;
        enabled = new CollectionControl(log, this::refresh);
        setName("logging-panel");
        JPanel top = new Header(); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        save.setSelected(log.isSaving());
        save.setToolTipText("Save rotating diagnostic samples (and legacy activity checkpoints when no session store is attached). Separate from automatic session history; queued writes may finish.");
        save.addActionListener(e -> log.setSaving(save.isSelected()));
        freeze.setToolTipText("Pause is temporary. Reopening or loading a saved view resumes fresh diagnostics; saved views never restore collection or disk-saving controls.");
        coverage.setName("logging-coverage"); coverage.getAccessibleContext().setAccessibleName("Diagnostic coverage");
        coverage.setToolTipText("What the counters cover: decode failures, retention and delta-cache evictions, and the collection state");
        coverage.addActionListener(e -> {
            if (snapshot != null) ContentStyle.showDetails(this, "Diagnostic coverage", DiagnosticCoverage.describe(snapshot));
        });
        JButton export = new JButton("Export report"); export.addActionListener(e -> export());
        export.setToolTipText("Preview the chosen snapshot and counts, then export all retained diagnostics. Display filters are recorded, not applied.");
        exportSource.setName("logging-export-source"); exportSource.getAccessibleContext().setAccessibleName("Diagnostic export source");
        search.setToolTipText("Literal search of displayed columns and retained nested stat names, IDs, objects and values");
        search.setName("logging-search");search.getAccessibleContext().setAccessibleName("Search logging views");
        search.putClientProperty("JTextField.placeholderText", "Search logging views…");
        reset.setName("logging-reset"); reset.setToolTipText("Clear this tab's search and facets");
        reset.addActionListener(e -> resetFilters());
        searchRow = new WrapRow(search, reset);
        trailing.setOpaque(false);
        filterBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        filterBar.search(searchRow).drawer(facets);
        placeTrailing(false);
        // Collection and Pause move after Reset filters when the row is too narrow for its right slot.
        filterBar.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { fitRow(); }
        });
        search.addPropertyChangeListener("font", e -> fitRow());
        JPanel exportActions = ContentStyle.controls(); exportActions.add(exportSource); exportActions.add(export); exportActions.add(openFolder);
        openFolder.setEnabled(false); openFolder.addActionListener(e -> {
            final Path folder=reportFolder;
            if (folder==null) return;
            new SwingWorker<Void,Void>() {
                protected Void doInBackground() throws Exception { Desktop.getDesktop().open(folder.toFile()); return null; }
                protected void done() { try { get(); } catch (Exception error) { exportStatus.setText("Could not open the report folder; its path is available in the saved report tooltip."); } }
            }.execute();
        });
        exportActions.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(filterBar);
        // The drawer holds each tab's facets (visibility rules in updateFacets) and the packet/stat-change checks.
        configureFacet(packetFacet, "Packet"); configureFacet(statFacet, "Stat"); configureFacet(objectFacet, "Object");
        configureFacet(areaFacet, "Area"); configureFacet(outcomeFacet, "Outcome");
        changedOnly.setName("logging-changed"); changedOnly.addActionListener(e -> { if (!refreshing) { activeTable().filters.changed=changedOnly.isSelected(); filter(); } });
        facets.add(changedOnly); facets.add(observedOnly); facets.add(issuesOnly);
        counts.setName("logging-counts"); counts.getAccessibleContext().setAccessibleName("Matching and retained diagnostic rows");
        counts.setBorder(BorderFactory.createEmptyBorder(2, 8, 0, 8));
        counts.setAlignmentX(Component.LEFT_ALIGNMENT); top.add(counts);
        losses.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (JLabel label : new JLabel[]{summary, losses, exportStatus}) label.setFont(ContentStyle.metadata(ContentStyle.body()));
        // Diagnostic coverage is a Ghost link beside the summary's first line.
        JPanel summaryRow = ContentStyle.controls(); ((FlowLayout) summaryRow.getLayout()).setAlignOnBaseline(true);
        summary.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 0)); summaryRow.add(summary); summaryRow.add(coverage);
        summaryRow.setAlignmentX(Component.LEFT_ALIGNMENT); top.add(summaryRow);
        losses.setBorder(BorderFactory.createEmptyBorder(2, 8, 6, 8)); top.add(losses);
        // Tab IDs are the saved-state keys (TAB_KEYS), so saved Logging views survive reordering and hiding.
        tabGroup.add("discovery", "Discovery", discoveries.scroll()).add("reentry", "Re-entry trace", reentry.scroll()).add("packets", "Packets", packets.scroll())
            .add("stats", "Stat explorer", stats.scroll()).add("events", "Event samples", events.scroll()).add("fields", "Field catalog", fields.scroll());
        activeTab = activeIndex();
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        details.setEditable(false); details.setLineWrap(true); details.setWrapStyleWord(true);
        details.setFont(ContentStyle.report(ContentStyle.body())); details.setMargin(new Insets(6,8,6,8));
        details.setName("logging-details");
        details.getAccessibleContext().setAccessibleName("Selected diagnostic details");
        JScrollPane detailScroll = new JScrollPane(details);
        detailScroll.setMinimumSize(new Dimension(0,70)); detailScroll.setPreferredSize(new Dimension(700,115));
        tabs.setPreferredSize(new Dimension(700,170));
        JPanel detailPanel = new JPanel(new BorderLayout()); detailPanel.add(detailScroll);
        JPanel detailActions = ContentStyle.controls();
        JButton copy = new JButton("Copy full detail"); copy.setName("logging-copy-detail");
        copy.addActionListener(e -> { details.selectAll(); details.copy(); details.setCaretPosition(0); });
        fieldChoice.setName("logging-field-choice"); fieldChoice.getAccessibleContext().setAccessibleName("Retained sample field path");
        fieldChoice.setPrototypeDisplayValue("newObjects[].status.stats[].statValue");
        samplesLink.setName("logging-samples-link"); samplesLink.addActionListener(e -> openSamples());
        fieldLink.setName("logging-field-link"); fieldLink.addActionListener(e -> openField());
        detailActions.add(copy); detailActions.add(samplesLink); detailActions.add(fieldChoice); detailActions.add(fieldLink);
        detailPanel.add(detailActions, BorderLayout.NORTH);
        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, detailPanel) {
            @Override public void doLayout() {
                super.doLayout();
                // Swing can retain a desktop divider position after shrinking the window.
                int detailHeight=Math.min(detailActions.getPreferredSize().height+75,getHeight()-70-getDividerSize());
                if (detailHeight>0 && getBottomComponent().getHeight() < detailHeight) {
                    setDividerLocation(getHeight() - detailHeight - getDividerSize()); super.doLayout();
                }
            }
        };
        split.setResizeWeight(.60); split.setBorder(null);
        JPanel bottom = new JPanel(new BorderLayout());
        // Shares the export row when it fits (and wraps to its own row when it does not), so the page's last line
        // is not left a few pixels below the fold in a standard-height window.
        JLabel privacy = new JLabel("Bounded, sanitized local samples. Field definitions do not prove live availability.");
        privacy.setName("logging-privacy");
        privacy.setToolTipText("Payloads, credentials, chat, string-stat values and opaque/unknown values are withheld. Counters cover traffic observed while collection is on.");
        privacy.setFont(ContentStyle.metadata(ContentStyle.body())); exportActions.add(privacy);
        bottom.add(exportActions, BorderLayout.NORTH); bottom.add(exportStatus, BorderLayout.SOUTH);
        // Scroll the complete workspace when controls and detail actions no longer fit.
        // A capped header alone can still consume every table row at large text sizes.
        JScrollPane page = ContentStyle.page(top, split, bottom);
        page.getAccessibleContext().setAccessibleName("Logging workspace; scroll for controls and details");
        add(page);
        for (DiscoveryCatalog.SchemaField field : catalog) fields.add(new Object[] {field.packet, field.path, field.type, field.retention}, field);
        fields.changed();
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { update(); } public void removeUpdate(DocumentEvent e) { update(); } public void changedUpdate(DocumentEvent e) { update(); }
            private void update() { if (!applyingState) filter(); }
        });
        observedOnly.addActionListener(e -> { packets.filters.observed=observedOnly.isSelected(); filter(); });
        issuesOnly.addActionListener(e -> { packets.filters.issues=issuesOnly.isSelected(); filter(); });
        tabs.addChangeListener(e -> {
            if (applyingState) return;
            if (tabGroup.isRebuilding()) { syncTabLater(); return; }
            tabChanged();
        });
        String[] names = {"Packet diagnostics", "Stat observations", "Retained event samples", "Decoder field catalog", "Diagnostic discovery", "Retained re-entry trace"};
        DataTable[] all = allTables();
        for (int i=0; i<all.length; i++) {
            DataTable table=all[i]; table.table.setName("logging-table-" + i); table.table.getAccessibleContext().setAccessibleName(names[i]);
            table.table.getSelectionModel().addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting() && !refreshing && !applyingState) {
                    table.pendingSelection=null; showDetails(); stateChanged();
                }
            });
            table.sorter.addRowSorterListener(e -> { if (e.getType()==RowSorterEvent.Type.SORT_ORDER_CHANGED) stateChanged(); });
            table.table.getColumnModel().addColumnModelListener(new TableColumnModelListener() {
                public void columnAdded(TableColumnModelEvent e) { }
                public void columnRemoved(TableColumnModelEvent e) { }
                public void columnMoved(TableColumnModelEvent e) { if (e.getFromIndex()!=e.getToIndex()) stateChanged(); }
                public void columnMarginChanged(ChangeEvent e) { stateChanged(); }
                public void columnSelectionChanged(ListSelectionEvent e) { }
            });
            table.table.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "diagnostic-details");
            table.table.getActionMap().put("diagnostic-details", new AbstractAction() {
                public void actionPerformed(java.awt.event.ActionEvent e) { showDetails(); details.requestFocusInWindow(); }
            });
        }
        freeze.addItemListener(e -> { if (!applyingState) { snapshots.invalidate(); refresh(); } });
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY,e -> stateChanged());
        timer = new javax.swing.Timer(1000, e -> { if (isShowing()) refresh(); });
        addHierarchyListener(e -> { if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) visibilityChanged(); });
        updateFacets(); filter();
        viewState=new LoggingViewState(store,this::captureViewState,this::applyViewState,this::validateViewState);
        viewState.prompts=LoggingViewState.Prompts.dialogs(this);
        JLabel status=viewState.status; status.setAlignmentX(Component.LEFT_ALIGNMENT); status.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        top.add(status, 1); // Under the filter row; shown only while saving or reading saved views fails.
        buildOverflow();
        viewState.restore();
        stateReady=true;
    }
    @Override public void addNotify() { super.addNotify(); visibilityChanged(); }
    @Override public void removeNotify() { timer.stop(); snapshots.invalidate(); if (viewState!=null) viewState.detach(); super.removeNotify(); }
    private void visibilityChanged() { if (isShowing()) { timer.start(); refresh(); } else { timer.stop(); snapshots.invalidate(); } }
    public void refresh() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::refresh); return; }
        enabled.refresh();
        save.setSelected(log.isSaving());
        updateSummary();
        if (snapshot != null && presentationChanged()) refreshTables();
        if (freeze.isSelected() || (isDisplayable()&&!isShowing())) return;
        snapshots.request("diagnostics",()->log.diagnosticsSnapshot(revision),next->{
            refreshing=true;
            if (snapshot!=null && (!snapshot.runId.equals(next.data.runId) || snapshot.area!=next.data.area)) {
                boolean differentRun=!snapshot.runId.equals(next.data.runId);
                for (DataTable table:allTables()) if (differentRun || table!=events && table!=reentry) table.table.clearSelection();
            }
            revision=next.revision; snapshot=next.data;
            enabled.refresh(); save.setSelected(log.isSaving()); (snapshot.sampleMillis==0 ? detailed : sampled).setSelected(true);
            refreshing=false; refreshTables();
        },error->exportStatus.setText("Could not refresh diagnostics; retrying on the next refresh."));
    }
    private void refreshTables() {
        if (snapshot == null) return;
        boolean reformat = presentationChanged();
        updateSummary();
        losses.setText("Omitted: " + DisplayFormat.formatInteger(snapshot.sampledOut) + " events / " + DisplayFormat.formatInteger(snapshot.deltaOmitted)
            + " stat deltas · Disk drops: " + DisplayFormat.formatInteger(snapshot.diskDropped) + " · Observer errors: " + DisplayFormat.formatInteger(snapshot.observerErrors));
        losses.setToolTipText(snapshot.writerError.isEmpty() ? "Cache evictions: " + DisplayFormat.formatInteger(snapshot.cacheEvictions) + ". Sampling and limits affect event history, not packet counts. Export includes all counters." : snapshot.writerError);
        if (!snapshot.writerError.isEmpty()) exportStatus.setText(snapshot.writerError);
        if (!snapshot.activityWriterError.isEmpty()) exportStatus.setText(snapshot.activityWriterError);
        DataTable active = activeTable(); refreshing = true;
        if (active != fields) active.clear();
        if (active == packets) {
            Map<Integer, DiscoveryLog.PacketRow> seen = new HashMap<>();
            for (DiscoveryLog.PacketRow row : snapshot.packets) seen.put(row.id, row);
            for (PacketType type : PacketType.values()) {
                if (type.getIndex() > 255) continue;
                DiscoveryLog.PacketRow row = seen.remove(type.getIndex());
                packets.add(new Object[] {type.getIndex(), type.name(), DiscoveryCatalog.direction(type), row == null ? "Defined; not observed" : row.lastOutcome,
                    row == null ? 0L : row.count, row == null ? 0L : row.bytes, row == null ? 0L : row.failures, row == null ? 0L : row.trailing,
                    Register.INSTANCE.directListenerCount(type)}, row == null ? type.name() : row);
            }
            for (DiscoveryLog.PacketRow row : seen.values()) packets.add(new Object[] {row.id,row.name,row.direction,row.lastOutcome,row.count,row.bytes,row.failures,row.trailing,0}, row);
        } else if (active == stats) {
            for (DiscoveryLog.StatRow row : snapshot.stats) stats.add(new Object[] {row.id,row.name,row.observations,row.changes,row.latest,row.latestSecondary,row.min,row.max,row.withheld}, row);
        } else if (active == events) {
            for (int i = snapshot.events.size()-1; i >= 0; i--) {
                DiscoveryLog.Event event = snapshot.events.get(i);
                events.add(new Object[] {displayInstant(event.timestamp),event.area,event.packet,event.outcome,event.bytes,event.values + " / " + DisplayFormat.formatInteger(event.statChanges.size()) + " stat samples"}, event);
            }
        } else if (active == reentry) {
            List<TraceRow> traceRows = traceRows(snapshot.events);
            for (int i = traceRows.size()-1; i >= 0; i--) {
                TraceRow row = traceRows.get(i);
                reentry.add(new Object[] {displayInstant(row.timestamp),row.area,row.step,row.direction,row.elapsedMillis,row.evidence()}, row);
            }
        } else if (active == discoveries) {
            for (String[] opportunity : DiscoveryCatalog.OPPORTUNITIES) {
                long count = 0;
                for (DiscoveryLog.PacketRow row : snapshot.packets) if (Arrays.asList(opportunity[1].split(", ")).contains(row.name)) count += row.count - row.failures - row.trailing;
                discoveries.add(new Object[] {opportunity[0],count == 0 ? "Awaiting clean sample" : DisplayFormat.formatInteger(count) + " clean frames",opportunity[2],opportunity[1]}, opportunity);
            }
        }
        if (active != fields) active.changed();
        active.renderedRun=snapshot.runId; active.renderedArea=snapshot.area;
        refreshing = false; updateFacets(); filter();
        restoreSelection(active);
        showDetails();
        presentationLocale=Locale.getDefault(Locale.Category.FORMAT);presentationZone=ZoneId.systemDefault();
        if (reformat) active.table.repaint();
    }
    private boolean presentationChanged() {
        return !Locale.getDefault(Locale.Category.FORMAT).equals(presentationLocale) || !ZoneId.systemDefault().equals(presentationZone);
    }
    /** Logging's collection state line, using the same "Collection: on / paused" term as the coverage details. */
    private String collectionStatus() {
        if (log.isHistorical()) return CollectionControl.status(log, freeze.isSelected());
        return "Collection: " + DiagnosticCoverage.state(log.isEnabled()) + (freeze.isSelected() ? " · View paused (collection state is current)" : "");
    }
    private void updateSummary() {
        summary.setText("<html>" + collectionStatus()
            + (snapshot == null ? " · No diagnostic revision displayed" : "<br>" + DisplayFormat.formatInteger(snapshot.total)
                + " frames · " + DisplayFormat.formatInteger(snapshot.packets.size()) + " types · "
                + DisplayFormat.formatInteger(snapshot.events.size()) + " retained samples · Partial coverage<br>Snapshot: " + snapshot.exportedAt) + "</html>");
        summary.setToolTipText(snapshot==null ? null : "Displayed snapshot reference: " + LoggingReport.revision(snapshot));
    }
    private void filter() {
        if (applyingState) return;
        DataTable table = activeTable();
        table.filters.text=search.getText().trim();
        boolean wasRefreshing=refreshing; refreshing=true;
        try {
            Object selection=table.selectedKey(); table.table.clearSelection();
            LoggingQuery query=table.filters.copy();
            table.sorter.setRowFilter(new RowFilter<TableModel,Integer>() {
                public boolean include(Entry<? extends TableModel,? extends Integer> entry) {
                    StringBuilder text=new StringBuilder();
                    for (int i=0; i<entry.getValueCount(); i++) text.append(entry.getStringValue(i)).append(' ');
                    Object source=table.objects.get(entry.getIdentifier());
                    return query.matches(source instanceof TraceRow ? ((TraceRow)source).event : source, text.toString());
                }
            });
            table.restore(selection);
        } finally { refreshing=wasRefreshing; }
        updateChips();
        String unit=table==events ? "event samples" : table==fields ? "field definitions" : table==stats ? "stat counter rows"
            : table==packets ? "packet rows (defined + observed)" : table==reentry ? "re-entry samples" : "discovery topics";
        counts.setText(DisplayFormat.formatInteger(table.table.getRowCount()) + " matching / " + DisplayFormat.formatInteger(table.rows.size())
            + (table==fields || table==packets || table==discoveries ? " available " : " retained ") + unit
            + (table.table.getRowCount()==0 ? (table.rows.isEmpty() ? " · No retained data yet" : " · No matches; reset filters") : ""));
        if (!refreshing) showDetails();
        stateChanged();
    }
    private DataTable activeTable() { return tabTables()[activeIndex()]; }
    /** The selected tab as an index into TAB_KEYS/tabTables(), whatever its visible position. */
    private int activeIndex() { return Math.max(0, Arrays.asList(TAB_KEYS).indexOf(tabGroup.selectedId())); }
    private void showTab(String id) { tabGroup.show(id); tabGroup.select(id); }
    private void tabChanged() {
        activeTab=activeIndex(); applyingState=true;
        try { search.setText(activeTable().filters.text); } finally { applyingState=false; }
        refreshTables(); updateFacets(); filter(); stateChanged();
    }
    /** A rebuild (reorder, hide) passes through transient selections; sync once on the final one. */
    private void syncTabLater() {
        if (tabSyncPending) return;
        tabSyncPending = true; SwingUtilities.invokeLater(() -> { tabSyncPending = false; if (!applyingState) tabChanged(); });
    }
    private void configureFacet(JComboBox<String> box, String label) {
        box.setName("logging-facet-" + label.toLowerCase(Locale.ROOT));
        box.getAccessibleContext().setAccessibleName(label + " equals");
        box.setPrototypeDisplayValue(label.equals("Packet") ? "REALM_SCORE_UPDATE" : label.equals("Stat") ? "123: MAXIMUM_HP_STAT"
            : label.equals("Outcome") ? "trailing-bytes" : "1234567");
        JPanel group=new JPanel(new BorderLayout(4,0)); group.setOpaque(false);
        JLabel name=new JLabel(label); name.setLabelFor(box); group.add(name, BorderLayout.WEST); group.add(box); facets.add(group);
        box.addActionListener(e -> {
            if (refreshing) return;
            LoggingQuery q=activeTable().filters;
            if (box==packetFacet) q.packet=choice(box);
            if (box==outcomeFacet) q.outcome=choice(box);
            if (box==statFacet) q.stat=choice(box).isEmpty() ? null : Integer.valueOf(choice(box).split(":",2)[0]);
            if (box==objectFacet) q.object=choice(box).isEmpty() ? null : Integer.valueOf(choice(box));
            if (box==areaFacet) q.area=choice(box).isEmpty() ? null : Long.valueOf(choice(box));
            if (box==objectFacet || box==areaFacet) q.captureRun=(q.object==null && q.area==null) || snapshot==null ? "" : snapshot.runId;
            filter();
        });
    }
    private static String choice(JComboBox<String> box) { return box.getSelectedIndex()<=0 ? "" : (String)box.getSelectedItem(); }
    private static void choices(JComboBox<String> box, Collection<String> values, String selected, boolean visible) {
        box.getParent().setVisible(visible);
        LinkedHashSet<String> items=new LinkedHashSet<>(); items.add("Any"); items.addAll(values);
        if (!selected.isEmpty()) items.add(selected); // A vanished value stays resettable, rather than silently broadening the query.
        List<String> list=new ArrayList<>(items);
        boolean same=box.getItemCount()==list.size();
        for (int i=0; same && i<list.size(); i++) same=list.get(i).equals(box.getItemAt(i));
        if (!same) box.setModel(new DefaultComboBoxModel<>(list.toArray(new String[0])));
        box.setSelectedItem(selected.isEmpty() ? "Any" : selected);
    }
    private void updateFacets() {
        boolean before=refreshing; refreshing=true;
        try {
            DataTable active=activeTable(); LoggingQuery q=active.filters;
            Set<String> packetNames=new TreeSet<>(), statNames=new TreeSet<>(), objects=new TreeSet<>(), areas=new TreeSet<>(), outcomes=new TreeSet<>();
            if (active==fields) for (DiscoveryCatalog.SchemaField f:catalog) packetNames.add(f.packet);
            if (active==packets) for (PacketType p:PacketType.values()) if (p.getIndex()<=255) packetNames.add(p.name());
            if (snapshot!=null) {
                if (active==packets) for (DiscoveryLog.PacketRow p:snapshot.packets) { packetNames.add(p.name); outcomes.add(p.lastOutcome); }
                if (active==stats) for (DiscoveryLog.StatRow s:snapshot.stats) statNames.add(s.id + ": " + s.name);
                for (DiscoveryLog.Event e:snapshot.events) {
                    if (active==reentry && !REENTRY_PACKETS.contains(e.packet)) continue;
                    if (active==events || active==reentry) { packetNames.add(e.packet); areas.add(Long.toString(e.area)); outcomes.add(e.outcome); }
                    if (active==events) for (DiscoveryLog.Delta d:e.statChanges) {
                        statNames.add(d.statId + ": " + LoggingQuery.statName(d.statId)); objects.add(Integer.toString(d.objectId));
                    }
                }
            }
            choices(packetFacet, packetNames, q.packet, active==packets || active==events || active==fields || active==reentry);
            choices(statFacet, statNames, q.stat==null ? "" : q.stat + ": " + LoggingQuery.statName(q.stat), active==events || active==stats);
            choices(objectFacet, objects, q.object==null ? "" : q.object.toString(), active==events);
            choices(areaFacet, areas, q.area==null ? "" : q.area.toString(), active==events || active==reentry);
            choices(outcomeFacet, outcomes, q.outcome, active==events || active==reentry || active==packets);
            changedOnly.setVisible(active==events); changedOnly.setSelected(q.changed);
            observedOnly.setVisible(active==packets); issuesOnly.setVisible(active==packets);
            observedOnly.setSelected(packets.filters.observed); issuesOnly.setSelected(packets.filters.issues);
            // Discovery has no facets, so it has no drawer and no Filters toggle; the open state is kept for the other tabs.
            JComponent drawer=active==discoveries ? null : facets;
            if (filterBar.drawerContent()!=drawer) FilterChips.keepingFocus(() -> filterBar.drawer(drawer));
        } finally { refreshing=before; }
    }
    /** A removable chip; its action reads the displayed tab's query when clicked, so a kept chip never clears another tab. */
    private FilterBar.ActiveFilter chip(String label, java.util.function.Consumer<LoggingQuery> clear) {
        return new FilterBar.ActiveFilter(label.length()>48 ? label.substring(0,45) + "…" : label,
            () -> { clear.accept(activeTable().filters); updateFacets(); filter(); });
    }
    private void updateChips() {
        LoggingQuery q=activeTable().filters; List<FilterBar.ActiveFilter> active=new ArrayList<>();
        if (!q.text.isEmpty()) active.add(chip("Search: " + q.text, c -> search.setText("")));
        if (!q.packet.isEmpty()) active.add(chip("Packet: " + q.packet, c -> c.packet=""));
        if (q.stat!=null) active.add(chip("Stat: " + q.stat, c -> c.stat=null));
        if (q.object!=null) active.add(chip("Object: " + q.object, c -> { c.object=null; if (c.area==null) c.captureRun=""; }));
        if (q.area!=null) active.add(chip("Area: " + q.area, c -> { c.area=null; if (c.object==null) c.captureRun=""; }));
        if (!q.captureRun.isEmpty()) active.add(chip("Capture: " + q.captureRun, c -> { c.captureRun=""; c.object=null; c.area=null; }));
        if (!q.outcome.isEmpty()) active.add(chip("Outcome: " + q.outcome, c -> c.outcome=""));
        if (!q.fieldPath.isEmpty()) active.add(chip("Field: " + q.fieldPath, c -> c.fieldPath=""));
        if (q.changed) active.add(chip("Changed values", c -> c.changed=false));
        if (q.observed) active.add(chip("Observed packets", c -> c.observed=false));
        if (q.issues) active.add(chip("Packet issues", c -> c.issues=false));
        // Unchanged labels keep the existing chips, so background refreshes leave keyboard targets intact. Clear = Reset filters.
        FilterChips.update(filterBar, active, this::resetFilters, false);
    }
    private void resetFilters() { activeTable().filters=new LoggingQuery(); search.setText(""); updateFacets(); filter(); }
    /** Collection and Pause use the row's right slot when it has room; on narrow rows they wrap after Reset filters. */
    private void placeTrailing(boolean narrow) {
        narrowRow=narrow;
        FilterChips.keepingFocus(() -> {
            if (narrow) { searchRow.add(enabled); searchRow.add(freeze); }
            else {
                GridBagConstraints gap=new GridBagConstraints(); // The bar's right slot already leads with a gap.
                trailing.add(enabled, gap); gap.insets=new Insets(0, Tokens.XS + 2, 0, 0); trailing.add(freeze, gap);
            }
            filterBar.scope(narrow ? null : trailing);
            searchRow.revalidate(); trailing.revalidate();
        });
    }
    private void fitRow() { SwingUtilities.invokeLater(() -> { boolean narrow=narrowFit(); if (narrow!=narrowRow) placeTrailing(narrow); }); }
    private boolean narrowFit() {
        int needed=search.getPreferredSize().width+reset.getPreferredSize().width+(enabled.isVisible() ? enabled.getPreferredSize().width : 0)
            +freeze.getPreferredSize().width+filterBar.overflow().getPreferredSize().width+10*search.getFontMetrics(search.getFont()).charWidth('m');
        return filterBar.getWidth()>0 && filterBar.getWidth()<needed;
    }
    /** ⋯: saved views, then the collector's disk-sample and sampling settings, then Clear data (Danger, confirmed). */
    private void buildOverflow() {
        OverflowMenu more=filterBar.overflow();
        more.menu().add(viewState.menu()); more.addSeparator();
        save.setName("logging-save-samples"); more.menu().add(save);
        JMenu rate=more.submenu("Sampling"); rate.setName("logging-sampling");
        rate.setToolTipText("Sampled: one event per type per second, plus important events and errors. Activity aggregates use every clean packet. Detailed: every packet; at most 24 stat samples each.");
        rate.getAccessibleContext().setAccessibleName("Diagnostic event sampling");
        ButtonGroup rates=new ButtonGroup();
        for (JRadioButtonMenuItem item:new JRadioButtonMenuItem[]{sampled,detailed}) { rates.add(item); rate.add(item); }
        sampled.addActionListener(e -> { if (!refreshing) log.setSampleMillis(1000); });
        detailed.addActionListener(e -> { if (!refreshing) log.setSampleMillis(0); });
        more.addSeparator();
        JMenuItem clear=new JMenuItem("Clear data…") {
            @Override public void updateUI() { super.updateUI(); setForeground(Tokens.color(Tokens.Role.BAD)); }
        };
        clear.setName("logging-clear-data");
        clear.setToolTipText("Clear diagnostic counters and samples. Runs, Timeline and resource history remain available.");
        clear.addActionListener(e -> clearData()); more.menu().add(clear);
    }
    private void clearData() {
        if (!viewState.prompts.confirm("Clear diagnostic data", "Clear diagnostic counters and retained samples?\nRuns, Timeline and resource history remain available.")) return;
        log.clearDiagnostics(); freeze.setSelected(false); refresh();
    }
    private void openSamples() {
        Object selected=activeTable().selected(); LoggingQuery target=new LoggingQuery();
        if (selected instanceof DiscoveryLog.StatRow) target.stat=((DiscoveryLog.StatRow)selected).id;
        else if (selected instanceof DiscoveryLog.PacketRow) target.packet=((DiscoveryLog.PacketRow)selected).name;
        else if (selected instanceof String) target.packet=(String)selected;
        else return;
        events.filters=target; showTab("events"); updateFacets(); filter();
    }
    private void openField() {
        Object selected=activeTable().selected();
        DiscoveryLog.Event event=selected instanceof DiscoveryLog.Event ? (DiscoveryLog.Event)selected
            : selected instanceof TraceRow ? ((TraceRow)selected).event : null;
        String path=(String)fieldChoice.getSelectedItem();
        if (event==null || path==null) return;
        fields.filters=new LoggingQuery(); fields.filters.packet=event.packet; fields.filters.fieldPath=path;
        showTab("fields"); updateFacets(); filter(); fields.restore(event.packet + "|" + path); showDetails();
    }
    private void showDetails() {
        DataTable table = activeTable();
        Object selected = table.selected();
        samplesLink.setVisible(table==packets || table==stats); samplesLink.setEnabled(selected!=null);
        DiscoveryLog.Event event=selected instanceof DiscoveryLog.Event ? (DiscoveryLog.Event)selected
            : selected instanceof TraceRow ? ((TraceRow)selected).event : null;
        String priorPath=(String)fieldChoice.getSelectedItem();
        List<String> paths=event==null ? Collections.emptyList() : LoggingQuery.fieldPaths(event,catalog);
        boolean same=fieldChoice.getItemCount()==paths.size();
        for (int i=0; same && i<paths.size(); i++) same=paths.get(i).equals(fieldChoice.getItemAt(i));
        if (!same) fieldChoice.setModel(new DefaultComboBoxModel<>(paths.toArray(new String[0])));
        if (paths.contains(priorPath)) fieldChoice.setSelectedItem(priorPath);
        fieldChoice.setVisible(table==events || table==reentry); fieldLink.setVisible(fieldChoice.isVisible());
        fieldLink.setEnabled(!paths.isEmpty()); fieldChoice.setEnabled(!paths.isEmpty());
        fieldLink.setToolTipText(paths.isEmpty() ? "No retained value has a verified catalog path in this sample" : "Open the selected decoder path; schema presence does not prove protocol meaning");
        String text;
        if (selected instanceof String[]) {
            String[] opportunity=(String[])selected;
            text = opportunity[0] + "\n\nAvailable in the decoder: " + opportunity[2] + "\nSources: " + opportunity[1] + "\n\nValidation needed: " + opportunity[3];
        } else if (selected != null) {
            text = new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(selected);
            if (event!=null && !event.statChanges.isEmpty()) {
                StringBuilder semantic=new StringBuilder("Retained stat samples (initial observations have no previous value):\n");
                for (DiscoveryLog.Delta delta:event.statChanges) semantic.append(LoggingQuery.deltaText(delta)).append('\n');
                text=semantic + "\nFull retained event:\n" + text;
            }
            if (event!=null) text = "Display time zone: " + DisplayFormat.timestampZoneLabel() + " · Raw evidence below uses UTC.\n"
                + event.timestamp + " · Diagnostic area " + event.area + " · " + event.packet + " · " + event.outcome + "\n\n" + text;
            if (selected instanceof DiscoveryLog.StatRow) text += "\n\nCounters combine observed objects. View retained samples for per-object/area evidence; sampling may omit changes.";
            if (selected instanceof DiscoveryCatalog.SchemaField) text += "\n\nDecoder definition only; this does not prove live availability or protocol meaning.";
        } else text = (table.rows.isEmpty() ? "No retained data yet. " : table.table.getRowCount()==0 ? "No matches; reset the active filters. " : "Select a row; Enter focuses full details. ")
            + "\n\nPacket → retained samples → selected field definition. Stat → retained object/area samples.\n\nInitial stat observations are not confirmed changes. Object IDs are local to diagnostic areas, not verified player identities.\n\nRe-entry trace is passive evidence, not proof of admission or a queue bypass. Field definitions do not establish protocol meaning. Exports include bounded diagnostics from the explicitly chosen revision.";
        if (!text.equals(details.getText())) { details.setText(text); details.setCaretPosition(0); }
    }
    private DataTable[] allTables() { return new DataTable[] {packets,stats,events,fields,discoveries,reentry}; }
    private DataTable[] tabTables() { return new DataTable[] {discoveries,reentry,packets,stats,events,fields}; }
    private void stateChanged() { if (stateReady && viewState!=null && !applyingState && !refreshing) viewState.changed(); }

    LoggingViewState.Fields captureViewState() {
        LoggingViewState.Fields state=new LoggingViewState.Fields(); state.tab=TAB_KEYS[activeTab]; state.divider=split.getDividerLocation();
        DataTable[] tables=tabTables();
        for (int i=0;i<tables.length;i++) {
            DataTable table=tables[i]; LoggingViewState.Tab tab=new LoggingViewState.Tab(); tab.query=table.filters.copy();
            for (RowSorter.SortKey sort:table.sorter.getSortKeys()) tab.sort.add(new LoggingViewState.Sort(table.model.getColumnName(sort.getColumn()),sort.getSortOrder()));
            for (Enumeration<TableColumn> columns=table.table.getColumnModel().getColumns();columns.hasMoreElements();) {
                TableColumn column=columns.nextElement();
                tab.columns.add(new ViewState.Column(table.model.getColumnName(column.getModelIndex()),Math.max(16,Math.min(10000,column.getWidth())),true));
            }
            tab.selection=table.pendingSelection;
            if (table.selectedKey()!=null && !table.renderedRun.isEmpty()) {
                Object value=table.selected(); DiscoveryLog.Event event=value instanceof DiscoveryLog.Event ? (DiscoveryLog.Event)value : value instanceof TraceRow ? ((TraceRow)value).event : null;
                tab.selection=new LoggingViewState.Selection(event==null ? table.renderedRun : event.runId,
                    event==null ? table.renderedArea : event.area,table.selectedKey().toString());
            }
            state.tabs.put(TAB_KEYS[i],tab);
        }
        return state;
    }
    private void validateViewState(LoggingViewState.Fields state) {
        if (state==null || state.version!=1 || !Arrays.asList(TAB_KEYS).contains(state.tab) || state.tabs==null
                || state.divider < -1 || state.divider>10000) throw new IllegalArgumentException("Unsupported Logging state");
        for (Map.Entry<String,LoggingViewState.Tab> entry:state.tabs.entrySet()) {
            int index=Arrays.asList(TAB_KEYS).indexOf(entry.getKey());
            if (index<0 || entry.getValue()==null) throw new IllegalArgumentException("Unknown Logging tab");
            LoggingViewState.Tab tab=entry.getValue(); LoggingQuery q=tab.query;
            if (q==null || q.text==null || q.packet==null || q.fieldPath==null || q.outcome==null || q.captureRun==null
                    || (q.object!=null || q.area!=null) && q.captureRun.isEmpty() || tab.sort==null || tab.columns==null)
                throw new IllegalArgumentException("Invalid Logging query");
            if (q.stat!=null && (q.stat<0 || q.stat>255) || q.area!=null && q.area<0
                    || !q.captureRun.isEmpty() && q.object==null && q.area==null
                    || (q.observed || q.issues) && index!=2 || !q.fieldPath.isEmpty() && index!=5
                    || q.stat!=null && index!=3 && index!=4 || (q.object!=null || q.changed) && index!=4
                    || q.area!=null && index!=1 && index!=4 || !q.outcome.isEmpty() && index!=1 && index!=2 && index!=4
                    || !q.packet.isEmpty() && index!=1 && index!=2 && index!=4 && index!=5)
                throw new IllegalArgumentException("Facet does not apply to this Logging tab");
            if (tab.selection!=null && (tab.selection.run==null || tab.selection.run.isEmpty() || tab.selection.key==null || tab.selection.key.isEmpty()))
                throw new IllegalArgumentException("Invalid diagnostic reference");
            DataTable table=tabTables()[index]; Set<String> sorts=new HashSet<>(), columns=new HashSet<>();
            for (LoggingViewState.Sort sort:tab.sort) if (sort==null || sort.order==null || columnIndex(table,sort.column)<0 || !sorts.add(sort.column))
                throw new IllegalArgumentException("Invalid sort");
            for (ViewState.Column column:tab.columns) if (column==null || !column.visible || columnIndex(table,column.id)<0
                    || !columns.add(column.id) || column.width<16 || column.width>10000) throw new IllegalArgumentException("Invalid column");
            if (!tab.columns.isEmpty() && columns.size()!=table.model.getColumnCount()) throw new IllegalArgumentException("Incomplete column layout");
        }
    }
    private static int columnIndex(DataTable table,String name) {
        for (int i=0;i<table.model.getColumnCount();i++) if (table.model.getColumnName(i).equals(name)) return i;
        return -1;
    }
    void applyViewState(LoggingViewState.Fields state) {
        validateViewState(state); applyingState=true; snapshots.invalidate();
        try {
            freeze.setSelected(false); snapshot=null; revision=null;
            DataTable[] tables=tabTables();
            for (int i=0;i<tables.length;i++) {
                DataTable table=tables[i]; LoggingViewState.Tab saved=state.tabs.get(TAB_KEYS[i]);
                table.table.clearSelection(); table.pendingSelection=saved==null ? null : saved.selection;
                table.filters=saved==null ? new LoggingQuery() : saved.query.copy(); table.renderedRun="";
                if (table!=fields) { table.clear(); table.changed(); }
                List<RowSorter.SortKey> sorts=new ArrayList<>();
                if (saved!=null) for (LoggingViewState.Sort sort:saved.sort) sorts.add(new RowSorter.SortKey(columnIndex(table,sort.column),sort.order));
                table.sorter.setSortKeys(sorts);
                List<ViewState.Column> columns=saved==null || saved.columns.isEmpty() ? table.defaultColumns : saved.columns;
                for (int target=0;target<columns.size();target++) {
                    ViewState.Column column=columns.get(target); int model=columnIndex(table,column.id);
                    int from=table.table.convertColumnIndexToView(model); table.table.moveColumn(from,target);
                    TableColumn actual=table.table.getColumnModel().getColumn(target); actual.setPreferredWidth(column.width); actual.setWidth(column.width);
                }
            }
            activeTab=Arrays.asList(TAB_KEYS).indexOf(state.tab); showTab(TAB_KEYS[activeTab]);
            search.setText(activeTable().filters.text); split.setDividerLocation(state.divider);
        } finally { applyingState=false; }
        updateFacets(); filter(); updateSummary(); if (stateReady) refresh();
    }
    private void restoreSelection(DataTable table) {
        LoggingViewState.Selection selected=table.pendingSelection;
        if (selected==null || snapshot==null) return;
        table.pendingSelection=null;
        if (!selected.run.equals(snapshot.runId)) { stateChanged(); return; }
        boolean before=refreshing; refreshing=true;
        try {
            for (int i=0;i<table.objects.size();i++) {
                Object value=table.objects.get(i); DiscoveryLog.Event event=value instanceof DiscoveryLog.Event ? (DiscoveryLog.Event)value : value instanceof TraceRow ? ((TraceRow)value).event : null;
                if (selected.area==(event==null ? snapshot.area : event.area) && selected.key.equals(table.key(i).toString())) {
                    int view=table.table.convertRowIndexToView(i); if (view>=0) table.table.setRowSelectionInterval(view,view); break;
                }
            }
        } finally { refreshing=before; }
        showDetails(); stateChanged();
    }
    private void export() {
        exportTo(Paths.get("logs", "discovery", "reports"), (LoggingReport.Source)exportSource.getSelectedItem(), true);
    }
    SwingWorker<Path,Void> exportTo(Path directory) {
        return exportTo(directory, LoggingReport.Source.CURRENT);
    }
    SwingWorker<Path,Void> exportTo(Path directory, LoggingReport.Source source) {
        return exportTo(directory, source, false);
    }
    private SwingWorker<Path,Void> exportTo(Path directory, LoggingReport.Source source, boolean confirm) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Export must be requested on the EDT");
        if (exporting) return null;
        final DiscoveryLog.Snapshot displayed=snapshot;
        if (source==LoggingReport.Source.DISPLAYED && displayed==null) {
            exportStatus.setText("No diagnostic revision displayed yet. Refresh before exporting this view."); return null;
        }
        Map<String,Object> context=new LinkedHashMap<>();
        context.put("snapshotRevision", displayed==null ? null : LoggingReport.revision(displayed));
        context.put("tab", tabs.getTitleAt(tabs.getSelectedIndex())); context.put("filters", activeTable().filters.copy().metadata());
        context.put("matchingRows", activeTable().table.getRowCount()); context.put("availableRows", activeTable().rows.size());
        context.put("displayPaused", freeze.isSelected()); context.put("displayTimeZone", DisplayFormat.timestampZoneLabel());
        context.put("filtersAppliedToExport", false);
        exporting=true;
        exportStatus.setText("Preparing " + source.label.toLowerCase(Locale.ROOT) + "…");
        SwingWorker<Path,Void> worker=new SwingWorker<Path,Void>() {
            protected Path doInBackground() throws Exception {
                DiscoveryLog.Snapshot report=source==LoggingReport.Source.DISPLAYED ? displayed : log.diagnosticsSnapshot(null).data;
                if (confirm) {
                    boolean[] approved={false};
                    SwingUtilities.invokeAndWait(() -> {
                        JTextArea preview=new JTextArea(LoggingReport.preview(source,report,context));
                        preview.setEditable(false); preview.setLineWrap(true); preview.setWrapStyleWord(true);
                        preview.getAccessibleContext().setAccessibleName("Diagnostic export scope, revision, counts and filters");
                        JScrollPane scroll=new JScrollPane(preview); scroll.setPreferredSize(new Dimension(520,300));
                        approved[0]=JOptionPane.showConfirmDialog(LoggingGUI.this, scroll, "Export diagnostic snapshot",
                            JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE)==JOptionPane.OK_OPTION;
                    });
                    if (!approved[0]) return null;
                }
                Map<String,Object> document=new LinkedHashMap<>(); document.put("observations",report);
                document.put("manifest",LoggingReport.manifest(source,report,context));
                document.put("reentryTrace", traceRows(report.events));
                document.put("fieldCatalog",catalog); document.put("opportunities",DiscoveryCatalog.OPPORTUNITIES);
                return LoggingReport.write(directory,document);
            }
            protected void done() {
                try {
                    Path path=get(); exportStatus.setText(path==null ? "Export cancelled." : "Saved " + source.label.toLowerCase(Locale.ROOT) + ": " + path.getFileName());
                    exportStatus.setToolTipText(path==null ? null : path.toString());
                    if (path!=null) { reportFolder=path.getParent(); openFolder.setEnabled(true); }
                }
                catch (Exception e) { exportStatus.setText("Export failed. Check folder permissions and free disk space."); }
                finally { exporting=false; }
            }
        };worker.execute();return worker;
    }
    private static List<TraceRow> traceRows(List<DiscoveryLog.Event> events) {
        List<TraceRow> result = new ArrayList<>();
        long previous = -1;
        for (DiscoveryLog.Event event : events) {
            if (!REENTRY_PACKETS.contains(event.packet)) continue;
            long now = timestamp(event.timestamp);
            Long elapsed = previous < 0 || now < 0 ? null : Math.max(0, now - previous);
            result.add(new TraceRow(event, elapsed));
            previous = now;
        }
        return result;
    }
    private static long timestamp(String value) {
        try { return Instant.parse(value).toEpochMilli(); }
        catch (RuntimeException ignored) { return -1; }
    }
    private static String elapsed(Long millis) {
        if (millis == null) return "—";
        if (millis < 1000) return "+" + DisplayFormat.formatInteger(millis) + " ms";
        return "+" + DisplayFormat.formatDurationSeconds(millis, 1) + " s";
    }
    private static Instant displayInstant(String value) {
        try { return Instant.parse(value); }
        catch (RuntimeException invalid) { return null; }
    }
    private static String step(DiscoveryLog.Event event) {
        switch (event.packet) {
            case "ESCAPE": return "Client requested Nexus";
            case "PARTY_JOIN_REQUEST": return "Client requested party join";
            case "PARTY_REQUEST_RESPONSE": return "Server returned party request state";
            case "PARTY_ACTION": return "TeleportTo".equals(event.values.get("actionId"))
                ? "Server authorized TeleportTo" : "Server issued party action";
            case "PARTY_ACTION_RESULT": return "Client acknowledged party action";
            case "FOR_RECONNECT": return "Server supplied reconnect candidates";
            case "RECONNECT": return "Server directed reconnect";
            case "HELLO": return "Client began destination handshake";
            case "QUEUE_INFORMATION": return "Server reported queue position";
            case "FAILURE": return "Server rejected / failed transition";
            case "MAPINFO": return "Destination map metadata received";
            case "LOAD": return "Client selected character for destination";
            case "CREATE_SUCCESS": return "Character admitted to destination";
            default: return event.packet;
        }
    }
    private static final class TraceRow {
        final transient DiscoveryLog.Event event;
        final String timestamp, packet, direction, step, outcome;
        final long area;
        final Long elapsedMillis;
        final Map<String,Object> retainedValues;
        final String interpretation = "Passive sequence evidence only; server admission remains authoritative.";
        TraceRow(DiscoveryLog.Event event, Long elapsed) {
            this.event=event;
            timestamp=event.timestamp; packet=event.packet; direction=event.direction; step=step(event); outcome=event.outcome;
            area=event.area; elapsedMillis=elapsed; retainedValues=event.values;
        }
        String evidence() { return retainedValues.isEmpty() ? outcome : outcome + " · " + retainedValues; }
        String key() { return event.runId + "|" + area + "|" + timestamp + "|" + packet + "|" + event.observedPacketCount; }
    }
    /** Keep short-window controls scrollable instead of consuming the evidence/detail split. */
    private static final class Header extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle visible,int orientation,int direction) { return 24; }
        public int getScrollableBlockIncrement(Rectangle visible,int orientation,int direction) { return Math.max(24,visible.height-24); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }
    private static final class DataTable {
        final List<Object[]> rows=new ArrayList<>(); final List<Object> objects=new ArrayList<>();
        final AbstractTableModel model;
        final JTable table;
        final TableRowSorter<TableModel> sorter;
        private List<Object[]> pendingRows;
        private List<Object> pendingObjects;
        private LoggingQuery filters=new LoggingQuery();
        private LoggingViewState.Selection pendingSelection;
        private String renderedRun="";
        private long renderedArea;
        private final List<ViewState.Column> defaultColumns=new ArrayList<>();
        DataTable(String... columns) {
            model=new AbstractTableModel() {
                public int getRowCount(){return rows.size();} public int getColumnCount(){return columns.length;}
                public String getColumnName(int column){return columns[column];}
                public Object getValueAt(int row,int column){return rows.get(row)[column];}
                public Class<?> getColumnClass(int column) {
                    switch (columns[column]) {
                        case "Time": return Instant.class;
                        case "ID": case "Latest": case "Secondary": case "Min": case "Max": case "Type listeners": return Integer.class;
                        case "Count": case "Observations": case "Changes": case "Withheld": case "Decode errors": case "Trailing": case "Since prior": return Long.class;
                        case "Bytes": return columns.length==9 ? Long.class : Integer.class;
                        case "Area": return columns.length==4 ? String.class : Long.class;
                        default: return String.class;
                    }
                }
            };
            table=new JTable(model); ContentStyle.table(table,ContentStyle.Density.DENSE);
            for(int i=0;i<columns.length;i++) {
                String column=columns[i];
                table.getColumnModel().getColumn(i).setCellRenderer(new ContentStyle.Cell() {
                    protected void setValue(Object value) {
                        setToolTipText(null);
                        setHorizontalAlignment(value instanceof Number ? SwingConstants.RIGHT : SwingConstants.LEFT);
                        setText(displayValue(column,value));
                        if ("Time".equals(column)) {
                            setToolTipText(value==null?null:getText()+" ("+DisplayFormat.timestampZoneLabel()+")");
                        }
                    }
                });
            }
            table.getTableHeader().setReorderingAllowed(true);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); table.setFillsViewportHeight(true);
            sorter=new TableRowSorter<>(model); table.setRowSorter(sorter);
            sorter.setStringConverter(new TableStringConverter() {
                public String toString(TableModel model,int row,int column) {
                    Object value=model.getValueAt(row,column);
                    String shown=displayValue(columns[column],value),raw=value==null?"":value.toString();
                    return shown.equals(raw)?shown:shown+"\n"+raw;
                }
            });
            Map<String,ColumnKind> kinds=new HashMap<>();for(String c:columns)kinds.put(c,kind(c,columns.length));
            HistoryTables.kinds(table,kinds);
            for(int i=0;i<columns.length;i++)defaultColumns.add(new ViewState.Column(columns[i],table.getColumnModel().getColumn(i).getPreferredWidth(),true));
        }
        /** Column kind by header; Area is a map name in Discovery (four columns) and a numeric area ID elsewhere. */
        private static ColumnKind kind(String column,int count) {
            switch (column) {
                case "Time": return ColumnKind.DATE_TIME;
                case "Area": return count==4 ? ColumnKind.DUNGEON : ColumnKind.ID;
                case "ID": return ColumnKind.ID;
                case "Direction": case "Evidence": case "Outcome": case "Java type": return ColumnKind.STATUS;
                case "Count": case "Bytes": case "Observations": case "Changes": case "Withheld": case "Decode errors": case "Trailing": case "Type listeners": return ColumnKind.COUNT;
                case "Latest": case "Secondary": case "Min": case "Max": return ColumnKind.NUMBER;
                case "Since prior": return ColumnKind.DURATION;
                default: return ColumnKind.TEXT;
            }
        }
        private static String displayValue(String column,Object value) {
            if ("Time".equals(column)) return DisplayFormat.formatTimestamp((Instant)value);
            if ("Since prior".equals(column)) return elapsed((Long)value);
            // Stat values can be IDs/bitmasks: keep the decoder's evidence raw, including searches.
            switch (column) {
                case "ID": case "Area": case "Latest": case "Secondary": case "Min": case "Max":
                    return value==null?DisplayFormat.UNAVAILABLE:value.toString();
                default: return value instanceof Number ? DisplayFormat.formatExact((Number)value)
                    : value==null?DisplayFormat.UNAVAILABLE:value.toString();
            }
        }
        JScrollPane scroll(){return ContentStyle.tableScroll(table,3);}
        void add(Object[] row,Object object){if(pendingRows==null){rows.add(row);objects.add(object);}else{pendingRows.add(row);pendingObjects.add(object);}}
        void clear(){pendingRows=new ArrayList<>();pendingObjects=new ArrayList<>();}
        void changed(){
            if(pendingRows==null){model.fireTableDataChanged();return;}
            Object selection=selectedKey();boolean same=rows.size()==pendingRows.size();
            for(int i=0;same&&i<rows.size();i++)same=Arrays.deepEquals(rows.get(i),pendingRows.get(i));
            if(!same)table.clearSelection();
            rows.clear();rows.addAll(pendingRows);objects.clear();objects.addAll(pendingObjects);pendingRows=null;pendingObjects=null;
            if(!same){model.fireTableDataChanged();restore(selection);}
        }
        Object selected(){int row=table.getSelectedRow();return row<0?null:objects.get(table.convertRowIndexToModel(row));}
        private Object key(int row) {
            Object value=objects.get(row);
            if(value instanceof DiscoveryCatalog.SchemaField) return LoggingQuery.fieldKey((DiscoveryCatalog.SchemaField)value);
            if(value instanceof ActivityJournal.Visit) return ((ActivityJournal.Visit)value).id;
            if(value instanceof ActivityJournal.Entry) return ((ActivityJournal.Entry)value).id;
            if(value instanceof TraceRow) return ((TraceRow)value).key();
            if(value instanceof DiscoveryLog.Event) { DiscoveryLog.Event event=(DiscoveryLog.Event)value;return event.runId+"|"+event.area+"|"+event.timestamp+"|"+event.id+"|"+event.observedPacketCount; }
            return rows.get(row)[0];
        }
        Object selectedKey(){int row=table.getSelectedRow();return row<0?null:key(table.convertRowIndexToModel(row));}
        void restore(Object selected){if(selected==null)return;for(int i=0;i<rows.size();i++)if(selected.equals(key(i))){int view=table.convertRowIndexToView(i);if(view>=0)table.setRowSelectionInterval(view,view);break;}}
    }
}
