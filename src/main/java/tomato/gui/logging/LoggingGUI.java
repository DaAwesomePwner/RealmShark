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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.activity.SnapshotRefresh;

/** A searchable, bounded view over sanitized discovery data, refreshed only on the EDT. */
public final class LoggingGUI extends JPanel {
    private static final Set<String> REENTRY_PACKETS = new HashSet<>(Arrays.asList(
        "ESCAPE", "PARTY_JOIN_REQUEST", "PARTY_REQUEST_RESPONSE", "PARTY_ACTION", "PARTY_ACTION_RESULT",
        "FOR_RECONNECT", "RECONNECT", "HELLO", "QUEUE_INFORMATION", "FAILURE", "MAPINFO", "LOAD", "CREATE_SUCCESS"));
    private final DiscoveryLog log;
    private final JLabel summary = new JLabel(), losses = new JLabel(), exportStatus = new JLabel(" ");
    private final JTextField search = new JTextField(22);
    private final JCheckBox observedOnly = new JCheckBox("Observed packets only");
    private final JCheckBox issuesOnly = new JCheckBox("Packet issues only");
    private final JCheckBox freeze = new JCheckBox("Freeze");
    private final JCheckBox enabled = new JCheckBox("Collect"), save = new JCheckBox("Save logs");
    private final JComboBox<String> sampling = new JComboBox<>(new String[] {"Sampled", "Detailed"});
    private final JTabbedPane tabs = new JTabbedPane();
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

    public LoggingGUI(DiscoveryLog log) {
        super(new BorderLayout(0, 8)); this.log = log;
        setName("logging-panel");
        JPanel top = new JPanel(); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JPanel controls = ContentStyle.controls();
        enabled.setSelected(log.isEnabled());
        enabled.setToolTipText("Collect decoded game traffic. Capture must also be running.");
        enabled.addActionListener(e -> { log.setEnabled(enabled.isSelected()); refresh(); });
        save.setSelected(log.isSaving());
        save.setToolTipText("Local rotating discovery logs and activity-history.json; queued writes may finish after disabling.");
        save.addActionListener(e -> log.setSaving(save.isSelected()));
        sampling.setToolTipText("Sampled: one event per type per second, plus important events and errors. Activity aggregates use every clean packet. Detailed: every packet; at most 24 stat samples each.");
        sampling.getAccessibleContext().setAccessibleName("Diagnostic event sampling");
        sampling.addActionListener(e -> { if (!refreshing) log.setSampleMillis(sampling.getSelectedIndex() == 0 ? 1000 : 0); });
        controls.add(enabled); controls.add(save); controls.add(sampling); controls.add(freeze);
        JPanel actions = ContentStyle.controls();
        actions.setBorder(BorderFactory.createEmptyBorder(2,0,2,0));
        JButton export = new JButton("Export report"); export.addActionListener(e -> export());
        export.setToolTipText("Export current capture diagnostics and full activity history, even while this display is frozen.");
        JButton clear = new JButton("Clear data");
        clear.setToolTipText("Clear diagnostic counters and samples. Runs, Timeline and resource history remain available.");
        clear.addActionListener(e -> { log.clearDiagnostics(); freeze.setSelected(false); refresh(); });
        search.setToolTipText("Search all columns of every logging view");
        search.setName("logging-search");search.getAccessibleContext().setAccessibleName("Search logging views");
        JPanel searchBox = new JPanel(new BorderLayout(5,0)); JLabel searchLabel = new JLabel("Search");searchLabel.setLabelFor(search);searchBox.add(searchLabel, BorderLayout.WEST); searchBox.add(search);
        actions.add(searchBox); actions.add(observedOnly); actions.add(issuesOnly); controls.add(export); controls.add(clear);
        top.add(controls); top.add(actions);
        controls.setAlignmentX(Component.LEFT_ALIGNMENT); actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        summary.setAlignmentX(Component.LEFT_ALIGNMENT); losses.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (JLabel label : new JLabel[]{summary, losses, exportStatus}) label.setFont(ContentStyle.metadata(ContentStyle.body()));
        summary.setBorder(BorderFactory.createEmptyBorder(4, 8, 2, 8)); top.add(summary);
        losses.setBorder(BorderFactory.createEmptyBorder(2, 8, 6, 8)); top.add(losses); add(top, BorderLayout.NORTH);
        tabs.addTab("Discovery", discoveries.scroll()); tabs.addTab("Re-entry trace", reentry.scroll()); tabs.addTab("Packets", packets.scroll());
        tabs.addTab("Stat explorer", stats.scroll()); tabs.addTab("Event samples", events.scroll()); tabs.addTab("Field catalog", fields.scroll());
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        details.setEditable(false); details.setLineWrap(true); details.setWrapStyleWord(true);
        details.setFont(ContentStyle.report(ContentStyle.body())); details.setMargin(new Insets(6,8,6,8));
        details.setName("logging-details");
        details.getAccessibleContext().setAccessibleName("Selected diagnostic details");
        JScrollPane detailScroll = new JScrollPane(details);
        detailScroll.setMinimumSize(new Dimension(0,70)); detailScroll.setPreferredSize(new Dimension(700,115));
        tabs.setPreferredSize(new Dimension(700,170));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, detailScroll) {
            @Override public void doLayout() {
                super.doLayout();
                // Swing can retain a desktop divider position after shrinking the window.
                if (getHeight() >= 140 && getBottomComponent().getHeight() < 80) {
                    setDividerLocation(getHeight() - 85); super.doLayout();
                }
            }
        };
        split.setResizeWeight(.60); split.setBorder(null);
        add(split, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        JTextArea privacy = new JTextArea("Local gameplay logs; payloads, credentials and chat are excluded.\nSampled history. Field definitions do not prove live availability.");
        privacy.setToolTipText("String-stat values and opaque/unknown values are also withheld. Counters cover traffic observed while Collect is on.");
        privacy.setEditable(false); privacy.setOpaque(false); privacy.setLineWrap(true); privacy.setWrapStyleWord(true); privacy.setRows(2);
        privacy.setFont(ContentStyle.metadata(ContentStyle.body()));
        bottom.add(privacy, BorderLayout.CENTER); bottom.add(exportStatus, BorderLayout.SOUTH); add(bottom, BorderLayout.SOUTH);
        for (DiscoveryCatalog.SchemaField field : DiscoveryCatalog.fields()) fields.add(new Object[] {field.packet, field.path, field.type, field.retention}, field);
        fields.changed();
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); } public void removeUpdate(DocumentEvent e) { filter(); } public void changedUpdate(DocumentEvent e) { filter(); }
        });
        observedOnly.addActionListener(e -> { refreshTables(); }); issuesOnly.addActionListener(e -> refreshTables());
        tabs.addChangeListener(e -> refreshTables());
        for (DataTable table : allTables()) table.table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !refreshing) showDetails(); });
        freeze.addItemListener(e -> { snapshots.invalidate(); refresh(); });
        timer = new javax.swing.Timer(1000, e -> { if (isShowing()) refresh(); });
        addHierarchyListener(e -> { if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) visibilityChanged(); });
    }
    @Override public void addNotify() { super.addNotify(); visibilityChanged(); }
    @Override public void removeNotify() { timer.stop(); snapshots.invalidate(); super.removeNotify(); }
    private void visibilityChanged() { if (isShowing()) { timer.start(); refresh(); } else { timer.stop(); snapshots.invalidate(); } }
    public void refresh() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::refresh); return; }
        if (snapshot != null && presentationChanged()) refreshTables();
        if (freeze.isSelected() || (isDisplayable()&&!isShowing())) return;
        snapshots.request("diagnostics",()->log.diagnosticsSnapshot(revision),next->{
            revision=next.revision; snapshot=next.data; refreshing=true;
            enabled.setSelected(snapshot.enabled); save.setSelected(snapshot.saving); sampling.setSelectedIndex(snapshot.sampleMillis==0 ? 1 : 0);
            refreshing=false; refreshTables();
        },error->exportStatus.setText("Could not refresh diagnostics; retrying on the next refresh."));
    }
    private void refreshTables() {
        if (snapshot == null) return;
        boolean reformat = presentationChanged();
        if (reformat) for (DataTable table : allTables()) table.query = null;
        summary.setText(DisplayFormat.formatInteger(snapshot.total) + " frames · " + DisplayFormat.formatInteger(snapshot.packets.size())
            + " types · " + DisplayFormat.formatInteger(snapshot.stats.size()) + " stats · " + DisplayFormat.formatInteger(snapshot.events.size())
            + " events · " + (snapshot.enabled ? "Collect on" : "Paused"));
        losses.setText("Omitted: " + DisplayFormat.formatInteger(snapshot.sampledOut) + " events / " + DisplayFormat.formatInteger(snapshot.deltaOmitted)
            + " stats · Disk drops: " + DisplayFormat.formatInteger(snapshot.diskDropped) + " · Errors: " + DisplayFormat.formatInteger(snapshot.observerErrors));
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
                if (observedOnly.isSelected() && row == null) continue;
                if (issuesOnly.isSelected() && (row == null || row.failures + row.trailing == 0)) continue;
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
        filter(); refreshing = false;
        showDetails();
        presentationLocale=Locale.getDefault(Locale.Category.FORMAT);presentationZone=ZoneId.systemDefault();
        if (reformat) active.table.repaint();
    }
    private boolean presentationChanged() {
        return !Locale.getDefault(Locale.Category.FORMAT).equals(presentationLocale) || !ZoneId.systemDefault().equals(presentationZone);
    }
    private void filter() {
        String query=search.getText().trim();
        DataTable table = activeTable();
        if (!query.equals(table.query)) {
            boolean wasRefreshing=refreshing;refreshing=true;
            try {
                Object selection=table.selectedKey();table.table.clearSelection();
                table.query = query; table.sorter.setRowFilter(query.isEmpty() ? null : RowFilter.regexFilter("(?i)" + Pattern.quote(query)));
                table.restore(selection);
            } finally { refreshing=wasRefreshing; }
        }
        if (!refreshing) showDetails();
    }
    private DataTable activeTable() { return new DataTable[] {discoveries, reentry, packets, stats, events, fields}[tabs.getSelectedIndex()]; }
    private void showDetails() {
        DataTable table = activeTable();
        Object selected = table.selected();
        String text;
        if (selected instanceof String[]) {
            String[] opportunity=(String[])selected;
            text = opportunity[0] + "\n\nAvailable in the decoder: " + opportunity[2] + "\nSources: " + opportunity[1] + "\n\nValidation needed: " + opportunity[3];
        } else if (selected != null) {
            text = new GsonBuilder().setPrettyPrinting().create().toJson(selected);
            if (selected instanceof DiscoveryLog.Event || selected instanceof TraceRow)
                text = "Display time zone: " + DisplayFormat.timestampZoneLabel() + " · Raw evidence below uses UTC.\n\n" + text;
        } else text = "Select a row to inspect captured packet fields and decoder diagnostics.\n\nRe-entry trace orders passive party, reconnect, queue, and admission evidence. It does not send packets or prove a queue bypass.\n\nRuns and Timeline are available in the sidebar. Resources and buff timelines are in DPS Logger.\n\nStat ranges here combine visible objects. Field definitions and clean decoding do not establish protocol meaning. Export includes the current diagnostics and activity history.";
        if (!text.equals(details.getText())) { details.setText(text); details.setCaretPosition(0); }
    }
    private DataTable[] allTables() { return new DataTable[] {packets,stats,events,fields,discoveries,reentry}; }
    private void export() {
        exportTo(Paths.get("logs", "discovery", "reports"));
    }
    SwingWorker<Path,Void> exportTo(Path directory) {
        if (exporting) return null;
        exporting=true;
        exportStatus.setText("Exporting local report…");
        SwingWorker<Path,Void> worker=new SwingWorker<Path,Void>() {
            protected Path doInBackground() throws Exception {
                // Logging exports have always represented current capture, independently of Freeze.
                DiscoveryLog.Snapshot report=log.snapshot();
                Files.createDirectories(directory);
                Path file=Files.createTempFile(directory, "discovery-report-", ".json");
                Map<String,Object> document=new LinkedHashMap<>(); document.put("observations",report);
                document.put("reentryTrace", traceRows(report.events));
                document.put("fieldCatalog",DiscoveryCatalog.fields()); document.put("opportunities",DiscoveryCatalog.OPPORTUNITIES);
                Files.write(file,new GsonBuilder().setPrettyPrinting().create().toJson(document).getBytes(StandardCharsets.UTF_8));
                return file.toAbsolutePath();
            }
            protected void done() {
                try { Path path=get(); exportStatus.setText("Saved " + path.getFileName() + " in logs/discovery/reports"); exportStatus.setToolTipText(path.toString()); }
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
        final String timestamp, packet, direction, step, outcome;
        final long area;
        final Long elapsedMillis;
        final Map<String,Object> retainedValues;
        final String interpretation = "Passive sequence evidence only; server admission remains authoritative.";
        TraceRow(DiscoveryLog.Event event, Long elapsed) {
            timestamp=event.timestamp; packet=event.packet; direction=event.direction; step=step(event); outcome=event.outcome;
            area=event.area; elapsedMillis=elapsed; retainedValues=event.values;
        }
        String evidence() { return retainedValues.isEmpty() ? outcome : outcome + " · " + retainedValues; }
        String key() { return timestamp + "|" + packet + "|" + area; }
    }
    private static final class DataTable {
        final List<Object[]> rows=new ArrayList<>(); final List<Object> objects=new ArrayList<>();
        final AbstractTableModel model;
        final JTable table;
        final TableRowSorter<TableModel> sorter;
        private List<Object[]> pendingRows;
        private List<Object> pendingObjects;
        private String query;
        DataTable(String... columns) {
            model=new AbstractTableModel() {
                public int getRowCount(){return rows.size();} public int getColumnCount(){return columns.length;}
                public String getColumnName(int column){return columns[column];}
                public Object getValueAt(int row,int column){return rows.get(row)[column];}
                public Class<?> getColumnClass(int column) { for(Object[] row:rows) if(row[column]!=null) return row[column].getClass(); return Object.class; }
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
            table.getTableHeader().setReorderingAllowed(false);
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
            for(int i=0;i<columns.length;i++) table.getColumnModel().getColumn(i).setPreferredWidth(i==1?230:i==0?110:170);
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
            if(value instanceof ActivityJournal.Visit) return ((ActivityJournal.Visit)value).id;
            if(value instanceof ActivityJournal.Entry) return ((ActivityJournal.Entry)value).id;
            if(value instanceof TraceRow) return ((TraceRow)value).key();
            if(value instanceof DiscoveryLog.Event) { DiscoveryLog.Event event=(DiscoveryLog.Event)value;return event.runId+"|"+event.timestamp+"|"+event.id+"|"+event.observedPacketCount; }
            return rows.get(row)[0];
        }
        Object selectedKey(){int row=table.getSelectedRow();return row<0?null:key(table.convertRowIndexToModel(row));}
        void restore(Object selected){if(selected==null)return;for(int i=0;i<rows.size();i++)if(selected.equals(key(i))){int view=table.convertRowIndexToView(i);if(view>=0)table.setRowSelectionInterval(view,view);break;}}
    }
}
