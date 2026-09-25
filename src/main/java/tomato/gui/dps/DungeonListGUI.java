package tomato.gui.dps;

import tomato.backend.data.DpsData;
import tomato.backend.data.TomatoData;
import tomato.gui.activity.SnapshotRefresh;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.history.ViewStateStore;
import tomato.gui.roster.RosterViewState;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** Source-local encounter library; entry IDs own selection/checks, not row positions or recording claims. */
public class DungeonListGUI extends JPanel {
    private final DpsGUI dps;
    private final EncounterCatalog catalog;
    private final EncounterModel model = new EncounterModel();
    private final JTable table = new JTable(model);
    private final TableRowSorter<EncounterModel> sorter = new TableRowSorter<>(model);
    private final JButton load = new JButton("Load"), save = new JButton("Save checked"), viewImported = new JButton("View imported encounter");
    private final JTextField search = new JTextField(18);
    private final JComboBox<String> source = new JComboBox<>(new String[]{"All sources", "Captured", "Imported"});
    private final JComboBox<String> context = new JComboBox<>(new String[]{"Any local context", "Available", "Partial", "Unavailable"});
    private final JTextArea status = ContentStyle.wrappingText("Select an encounter to view it; checks are independent."), details = ContentStyle.wrappingText("");
    private final JLabel count = new JLabel();
    private final SnapshotRefresh<List<Row>> refresh = new SnapshotRefresh<>();
    private final javax.swing.Timer timer;
    private boolean rebuilding, busy;
    private String selectedId, importedId;
    private long displayedRevision = -1;
    private RosterViewState viewState;
    private final JPanel stateHost = new JPanel(new BorderLayout());
    private final Set<String> rememberedChecks = new LinkedHashSet<>();
    private String rememberedSelection = "";
    private boolean restoringState;
    private long rememberedGeneration;

    public DungeonListGUI(DpsGUI dps, TomatoData data) {
        this(dps, data, ViewStateStore.application());
    }
    DungeonListGUI(DpsGUI dps, TomatoData data, ViewStateStore states) {
        super(new BorderLayout(0, 8)); this.dps = dps; catalog = dps.encounters(); selectedId = dps.currentEncounterId();
        rememberedGeneration = catalog.generation();
        JPanel buttons = ContentStyle.controls(); buttons.add(load); buttons.add(save); buttons.add(viewImported);
        load.addActionListener(e -> loadButton()); save.addActionListener(e -> saveButton());
        viewImported.addActionListener(e -> {
            if (dps.showEncounter(importedId)) { selectedId = importedId; rememberedSelection = EncounterCatalog.reference(catalog.find(importedId)); restoreSelection(); rememberViewState(); status.setText("Opened imported encounter. Display filters are unchanged."); }
        });
        JPanel filters = ContentStyle.controls(); JLabel label = new JLabel("Search encounters"); label.setLabelFor(search);
        search.setName("encounter-search"); filters.add(label); filters.add(search); filters.add(source); filters.add(context);
        source.getAccessibleContext().setAccessibleName("Encounter source"); context.getAccessibleContext().setAccessibleName("Local context availability");
        JButton reset = new JButton("Reset filters"); filters.add(reset);
        reset.addActionListener(e -> { search.setText(""); source.setSelectedIndex(0); context.setSelectedIndex(0); });
        JPanel header = new JPanel(new BorderLayout(0, 4)); header.add(buttons, BorderLayout.NORTH); header.add(filters, BorderLayout.CENTER); header.add(count, BorderLayout.SOUTH);
        ContentStyle.table(table); table.setName("saved-encounters"); table.getAccessibleContext().setAccessibleName("Encounter library and export checks");
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); table.setRowSorter(sorter);
        table.addPropertyChangeListener("font", e -> ContentStyle.tableDensity(table, ContentStyle.Density.COMFORTABLE));
        table.getTableHeader().setReorderingAllowed(false);
        int[] widths = {70, 120, 190, 180, 120, 110, 140, 200, 130};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getColumnModel().getColumn(3).setCellRenderer(new ContentStyle.Cell() { protected void setValue(Object value) { setText(value == null ? "Not captured" : DisplayFormat.formatTimestamp((Long)value)); } });
        table.getColumnModel().getColumn(4).setCellRenderer(new ContentStyle.Cell() { protected void setValue(Object value) { setText(value == null ? "Unavailable" : DisplayFormat.formatNumber(((Long)value) / 1000d, 0, 1)); } });
        table.getTableHeader().setToolTipText("Recorded start is the first captured tick, not a guaranteed map-entry timestamp. Elapsed is the retained encounter duration, not the DPS hit window.");
        sorter.setSortKeys(Collections.singletonList(new RowSorter.SortKey(3, SortOrder.DESCENDING)));
        table.getSelectionModel().addListSelectionListener(e -> {
            if (rebuilding || e.getValueIsAdjusting() || table.getSelectedRow() < 0) return;
            Row row = selected(); selectedId = row.entry == null ? null : row.entry.id;
            rememberedSelection = EncounterCatalog.reference(row.entry);
            if (row.entry == null) dps.setIndex(-1); else dps.showEncounter(row.entry.id);
            showDetails();
            rememberViewState();
        });
        table.getInputMap().put(KeyStroke.getKeyStroke("SPACE"), "toggle-export");
        table.getActionMap().put("toggle-export", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                int view = table.getSelectedRow(); if (view < 0) return;
                int row = table.convertRowIndexToModel(view);
                if (model.isCellEditable(row, 0)) model.setValueAt(!Boolean.TRUE.equals(model.getValueAt(row, 0)), row, 0);
            }
        });
        JPanel footer = new JPanel(new BorderLayout(0, 4)); footer.add(details, BorderLayout.NORTH);
        JPanel bottom = new JPanel(new BorderLayout(0, 4)); bottom.add(status, BorderLayout.NORTH); bottom.add(stateHost, BorderLayout.SOUTH); stateHost.setVisible(false); footer.add(bottom, BorderLayout.SOUTH);
        details.setName("encounter-details"); status.setName("encounter-status");
        add(ContentStyle.page(header, ContentStyle.tableScroll(table, 3), footer), BorderLayout.CENTER);
        for (JComponent control : new JComponent[]{load, save, viewImported, search, source, context, reset})
            control.addFocusListener(new FocusAdapter() { public void focusGained(FocusEvent e) { ContentStyle.reveal(control, new Rectangle(0, 0, control.getWidth(), control.getHeight())); } });
        table.addFocusListener(new FocusAdapter() { public void focusGained(FocusEvent e) { ContentStyle.reveal(table, table.getCellRect(Math.max(0, table.getSelectedRow()), Math.max(0, table.getSelectedColumn()), true)); } });
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filter(); }
        });
        source.addActionListener(e -> filter()); context.addActionListener(e -> filter());
        timer = new javax.swing.Timer(500, e -> { if (catalog.revision() != displayedRevision) refreshEncounters(); });
        addHierarchyListener(e -> { if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) { if (isShowing()) { refreshEncounters(); timer.start(); } else timer.stop(); } });
        if (states != null) bindViewState(states);
        refreshEncounters(); updateButtons();
    }
    void refreshEncounters() {
        applyRememberedReferences();
        long revision = catalog.revision(); List<EncounterCatalog.Entry> entries = catalog.entries();
        refresh.request(revision, () -> {
            List<Row> rows = new ArrayList<>(); rows.add(new Row(null, null));
            for (EncounterCatalog.Entry entry : entries) rows.add(new Row(entry, entry.summary()));
            return rows;
        }, rows -> {
            rebuilding = true;
            try { model.rows = rows; displayedRevision = revision; model.fireTableDataChanged(); restoreSelection(); }
            finally { rebuilding = false; }
            updateButtons(); showDetails();
        }, error -> status.setText("Could not read encounter summaries: " + error.getClass().getSimpleName()));
    }
    private void filter() {
        EncounterQuery query = new EncounterQuery(search.getText(), EncounterQuery.Source.values()[source.getSelectedIndex()], context.getSelectedIndex() == 0 ? null : (String)context.getSelectedItem());
        rebuilding = true;
        try {
            sorter.setRowFilter(new RowFilter<EncounterModel, Integer>() {
                public boolean include(Entry<? extends EncounterModel, ? extends Integer> value) {
                    Row row = model.rows.get(value.getIdentifier()); return row.entry == null || query.matches(row.entry, row.summary);
                }
            });
            restoreSelection();
        } finally { rebuilding = false; }
        updateButtons(); showDetails();
        rememberViewState();
    }
    private Row selected() { int row = table.getSelectedRow(); return row < 0 ? null : model.rows.get(table.convertRowIndexToModel(row)); }
    private void restoreSelection() {
        table.clearSelection();
        for (int i = 0; i < model.rows.size(); i++) {
            EncounterCatalog.Entry entry = model.rows.get(i).entry;
            if (Objects.equals(selectedId, entry == null ? null : entry.id)) {
                int view = table.convertRowIndexToView(i); if (view >= 0) table.setRowSelectionInterval(view, view); break;
            }
        }
    }
    private void showDetails() {
        Row row = selected();
        details.setText(row == null ? "Selected encounter is not loaded or is outside these display filters." : row.entry == null ? "Live capture is not an exportable saved encounter."
            : row.summary.dungeon + " · Entry " + row.entry.id + "\nRecording ID: " + Objects.toString(row.entry.data.getRecordingId(), "Not recorded (legacy)")
                + "\n" + row.summary.coverage + "\nLocal context: " + row.summary.localContext + " · " + row.summary.contextDescription
                + (row.entry.origin == null ? "\nCaptured in this application" : "\nImported file: " + row.entry.origin.fileName + " · SHA-256 " + row.entry.origin.fingerprint));
    }
    private void loadButton() {
        JFileChooser chooser = new JFileChooser(new File(".")); chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("DPS encounters (*.dps)", "dps"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) importFile(chooser.getSelectedFile());
    }
    SwingWorker<DpsData, Void> importFile(File file) {
        requireIdleEdt(); setBusy(true, "Loading " + file.getName() + "…"); long generation = catalog.generation();
        SwingWorker<DpsData, Void> worker = new SwingWorker<DpsData, Void>() {
            private EncounterImport imported;
            protected DpsData doInBackground() throws IOException, ClassNotFoundException { imported = EncounterImport.read(file.toPath()); return imported.data; }
            protected void done() {
                try {
                    get();
                    EncounterCatalog.Admission admission = catalog.add(imported, generation);
                    if (admission == null) { setBusy(false, "Library cleared during import; load again to add this encounter."); return; }
                    importedId = admission.entry.id;
                    refreshEncounters(); DpsGUI.updateLabel();
                    setBusy(false, admission.duplicate ? "Already loaded identical file bytes; View imported encounter opens the existing entry."
                        : admission.sameRecordingId ? "Loaded a separate file variant with the same recording ID; existing entries preserved." : "Loaded " + file.getName());
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); failed(e); }
                catch (ExecutionException e) { failed(e.getCause()); }
            }
        };
        worker.execute(); return worker;
    }
    private void saveButton() {
        JFileChooser chooser = new JFileChooser(new File(".")); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        CheckBoxAccessory accessory = new CheckBoxAccessory(); chooser.setAccessory(accessory);
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) exportFiles(chooser.getSelectedFile(), accessory.isBoxSelected());
    }
    SwingWorker<Integer, Void> exportFiles(File folder, boolean debug) {
        requireIdleEdt(); List<DpsData> exports = new ArrayList<>(); List<String> names = new ArrayList<>();
        for (EncounterCatalog.Entry entry : catalog.checkedEntries()) { exports.add(entry.data.getSaveFile(debug)); names.add(name(entry.data)); }
        setBusy(true, "Saving " + exports.size() + " checked encounters (including hidden checks)…");
        SwingWorker<Integer, Void> worker = new SwingWorker<Integer, Void>() {
            protected Integer doInBackground() throws IOException {
                SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd-HH.mm.ss");
                for (int i = 0; i < exports.size(); i++) {
                    DpsData saved = exports.get(i);
                    String base = names.get(i).replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_") + " " + (saved.dungeonStartTime > 0 ? date.format(new Date(saved.dungeonStartTime)) : "unknown-start");
                    DpsExport.write(folder.toPath(), base, saved);
                }
                return exports.size();
            }
            protected void done() {
                try { setBusy(false, "Saved " + get() + " encounters."); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); failed(e); }
                catch (ExecutionException e) { failed(e.getCause()); }
            }
        };
        worker.execute(); return worker;
    }
    private static String name(DpsData data) { return data.map == null ? "Unknown encounter" : Objects.toString(data.map.name, "Unknown encounter"); }
    private void requireIdleEdt() { if (!SwingUtilities.isEventDispatchThread() || busy) throw new IllegalStateException("Start one file operation at a time on the EDT"); }
    private void setBusy(boolean value, String message) { busy = value; status.setText(message); updateButtons(); }
    private void updateButtons() {
        load.setEnabled(!busy); List<EncounterCatalog.Entry> checked = catalog.checkedEntries(); save.setEnabled(!busy && !checked.isEmpty()); viewImported.setEnabled(catalog.find(importedId) != null);
        int visible = 0, hiddenChecks = checked.size();
        for (int i = 0; i < table.getRowCount(); i++) { Row row = model.rows.get(table.convertRowIndexToModel(i)); if (row.entry != null) { visible++; if (catalog.checked(row.entry.id)) hiddenChecks--; } }
        count.setText(visible + " of " + catalog.entries().size() + " retained encounters shown · " + checked.size() + " checked · " + hiddenChecks + " checked outside filters");
    }
    private void failed(Throwable error) { setBusy(false, "File operation failed: " + error.getMessage()); }
    public void bindViewState(ViewStateStore states) {
        if (viewState != null) return;
        viewState = new RosterViewState(states, "encounter-library-live", this::captureViewState, this::prepareViewState);
        stateHost.add(viewState.controls()); stateHost.setVisible(true); RosterViewState.listenTable(table, this::rememberViewState);
    }
    public java.util.concurrent.CompletionStage<util.PreferencesStore.SaveResult> saveViewState() {
        if (viewState == null) throw new IllegalStateException("View state is not bound"); return viewState.save();
    }
    @Override public void removeNotify() { if (viewState != null) viewState.save(); super.removeNotify(); }
    private void rememberViewState() { if (viewState != null && !rebuilding && !restoringState) viewState.changed(); }
    private Map<String, String> captureViewState() {
        applyRememberedReferences();
        Map<String, String> values = new LinkedHashMap<>(); values.put("text", search.getText());
        values.put("source", EncounterQuery.Source.values()[source.getSelectedIndex()].name());
        values.put("context", context.getSelectedIndex() == 0 ? "ANY" : context.getSelectedItem().toString().toUpperCase(Locale.ROOT));
        values.put("selected", rememberedSelection.isEmpty() ? EncounterCatalog.reference(catalog.find(selectedId)) : rememberedSelection);
        Set<String> checks = new LinkedHashSet<>(rememberedChecks); for (EncounterCatalog.Entry entry : catalog.checkedEntries()) checks.add(EncounterCatalog.reference(entry));
        values.put("checked", String.join("\n", checks)); values.put("catalog", catalog.lifetimeId()); values.put("generation", Long.toString(catalog.generation()));
        RosterViewState.captureTable(values, table); return values;
    }
    private Runnable prepareViewState(Map<String, String> values) {
        int selectedSource = RosterViewState.option(values, "source", source.getSelectedIndex(), "ANY", "CAPTURED", "IMPORTED");
        int selectedContext = RosterViewState.option(values, "context", context.getSelectedIndex(), "ANY", "AVAILABLE", "PARTIAL", "UNAVAILABLE");
        String selection = dps.hasSelectionIntent() ? EncounterCatalog.reference(catalog.find(dps.currentEncounterId()))
            : values.getOrDefault("selected", EncounterCatalog.reference(catalog.find(selectedId)));
        validateReference(selection);
        Set<String> checks = new LinkedHashSet<>();
        if (values.containsKey("checked")) for (String ref : values.get("checked").split("\n")) { validateReference(ref); if (!ref.isEmpty()) checks.add(ref); }
        else for (EncounterCatalog.Entry entry : catalog.checkedEntries()) checks.add(EncounterCatalog.reference(entry));
        long generation = values.containsKey("generation") ? Long.parseLong(values.get("generation")) : catalog.generation();
        if (generation < 0) throw new IllegalArgumentException("Invalid catalog generation");
        boolean cleared = catalog.lifetimeId().equals(values.get("catalog")) && generation != catalog.generation();
        Runnable columns = RosterViewState.prepareTable(values, table);
        return () -> {
            restoringState = true;
            try {
                rememberedChecks.clear(); if (!cleared) rememberedChecks.addAll(checks);
                rememberedSelection = cleared ? "" : selection; rememberedGeneration = catalog.generation();
                search.setText(values.getOrDefault("text", search.getText())); source.setSelectedIndex(selectedSource); context.setSelectedIndex(selectedContext);
                columns.run(); applyRememberedReferences(); filter();
            } finally { restoringState = false; }
        };
    }
    private static void validateReference(String reference) {
        if (!reference.isEmpty() && !reference.matches("file:[0-9a-f]{64}|(?:native|entry):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("Invalid encounter reference");
    }
    private void applyRememberedReferences() {
        if (rememberedGeneration != catalog.generation()) {
            rememberedGeneration = catalog.generation(); rememberedChecks.clear(); rememberedSelection = ""; selectedId = null; rememberViewState();
        }
        for (Iterator<String> pending = rememberedChecks.iterator(); pending.hasNext();) {
            EncounterCatalog.Entry entry = catalog.resolve(pending.next());
            if (entry != null) { catalog.check(entry.id, true); pending.remove(); }
        }
        EncounterCatalog.Entry selection = catalog.resolve(rememberedSelection);
        if (!rememberedSelection.isEmpty()) selectedId = selection == null ? "unresolved:" + rememberedSelection : selection.id;
    }
    public static void open(DpsGUI dps, TomatoData data) {
        DungeonListGUI list = new DungeonListGUI(dps, data); JButton close = new JButton("Close");
        JOptionPane pane = new JOptionPane(list, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION, null, new JButton[]{close}, close);
        close.addActionListener(e -> { pane.setValue(-1); SwingUtilities.getWindowAncestor(close).dispose(); });
        JDialog dialog = pane.createDialog(dps, "Encounter library"); realmshark.branding.AppIdentity.apply(dialog);
        dialog.setResizable(true); dialog.setSize(960, 650); dialog.setVisible(true);
    }
    private static final class Row { final EncounterCatalog.Entry entry; final EncounterSummary summary; Row(EncounterCatalog.Entry entry, EncounterSummary summary) { this.entry = entry; this.summary = summary; } }
    private final class EncounterModel extends AbstractTableModel {
        private final String[] columns = {"Export", "Entry", "Dungeon", "Recorded start", "Elapsed (s)", "Contributors", "Damage", "Source file", "Local context"};
        private List<Row> rows = new ArrayList<>();
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int column) { return columns[column]; }
        public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : column == 3 || column == 4 || column == 6 ? Long.class : column == 5 ? Integer.class : String.class; }
        public boolean isCellEditable(int row, int column) { return column == 0 && rows.get(row).entry != null; }
        public Object getValueAt(int row, int column) {
            Row value = rows.get(row); if (value.entry == null) return column == 2 ? "Live" : null;
            switch (column) {
                case 0: return catalog.checked(value.entry.id); case 1: return value.entry.id.substring(0, 8); case 2: return value.summary.dungeon;
                case 3: return value.summary.started; case 4: return value.summary.elapsed; case 5: return value.summary.contributors;
                case 6: return value.summary.damage; case 7: return value.entry.source(); default: return value.summary.localContext;
            }
        }
        public void setValueAt(Object value, int row, int column) {
            if (!isCellEditable(row, column)) return;
            catalog.check(rows.get(row).entry.id, Boolean.TRUE.equals(value)); fireTableCellUpdated(row, column); updateButtons();
            String reference = EncounterCatalog.reference(rows.get(row).entry);
            rememberedChecks.remove(reference); // Loaded checks are authoritative in the catalog; only unresolved references wait here.
            rememberViewState();
        }
    }
    public class CheckBoxAccessory extends JPanel {
        private final JCheckBox checkBox = new JCheckBox("Save Debug Data");
        public CheckBoxAccessory() { super(new BorderLayout()); setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8)); add(checkBox, BorderLayout.NORTH); }
        public boolean isBoxSelected() { return checkBox.isSelected(); }
    }
}
