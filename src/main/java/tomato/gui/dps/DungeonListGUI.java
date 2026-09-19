package tomato.gui.dps;

import tomato.backend.data.DpsData;
import tomato.backend.data.TomatoData;
import tomato.gui.modern.ContentStyle;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** Keyboard-navigable encounter history. Export checks are independent of the viewed encounter. */
public class DungeonListGUI extends JPanel {
    private final DpsGUI dps;
    private final TomatoData data;
    private final List<DpsData> encounters = new ArrayList<>();
    private final Set<DpsData> checked = Collections.newSetFromMap(new IdentityHashMap<>());
    private final EncounterModel model = new EncounterModel();
    private final JTable table = new JTable(model);
    private final JButton load = new JButton("Load"), save = new JButton("Save checked");
    private final JLabel status = new JLabel("Select an encounter to view it; check saved encounters to export.");
    private boolean rebuilding, busy;

    public DungeonListGUI(DpsGUI dps, TomatoData data) {
        super(new BorderLayout(0, 8));
        this.dps = dps;
        this.data = data;
        JPanel buttons = ContentStyle.controls();
        buttons.add(load); buttons.add(save);
        load.addActionListener(e -> loadButton());
        save.addActionListener(e -> saveButton());
        add(buttons, BorderLayout.NORTH);
        ContentStyle.table(table);
        table.setName("saved-encounters");
        table.getAccessibleContext().setAccessibleName("Saved encounters and export selection");
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(75);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(300);
        table.addPropertyChangeListener("font", e -> ContentStyle.tableDensity(table, ContentStyle.Density.COMFORTABLE));
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !rebuilding && table.getSelectedRow() >= 0) select(table.getSelectedRow());
        });
        table.getInputMap().put(KeyStroke.getKeyStroke("SPACE"), "toggle-export");
        table.getActionMap().put("toggle-export", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                int row = table.getSelectedRow();
                if (row > 0) model.setValueAt(!checked.contains(encounters.get(row)), row, 0);
            }
        });
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(520, 300));
        add(scroll, BorderLayout.CENTER);
        ContentStyle.font(status, ContentStyle.metadata(ContentStyle.body()));
        add(status, BorderLayout.SOUTH);
        refreshEncounters();
    }

    void refreshEncounters() {
        DpsData selected = table.getSelectedRow() < 0 ? currentEncounter() : encounters.get(table.getSelectedRow());
        DpsData[] saved = data.dpsData.toArray(new DpsData[0]);
        rebuilding = true;
        encounters.clear(); encounters.add(null); // Live always remains the first row.
        for (int i = saved.length - 1; i >= 0; i--) encounters.add(saved[i]);
        checked.retainAll(encounters);
        model.fireTableDataChanged();
        int row = Math.max(0, encounters.indexOf(selected));
        table.setRowSelectionInterval(row, row);
        rebuilding = false;
        updateButtons();
    }

    private DpsData currentEncounter() {
        int index = dps.getIndex();
        return index < 0 || index >= data.dpsData.size() ? null : data.dpsData.get(index);
    }

    private void select(int row) {
        DpsData encounter = encounters.get(row);
        int index = encounter == null ? -1 : data.dpsData.indexOf(encounter);
        if (encounter == null || index >= 0) dps.setIndex(index);
    }

    private static String name(DpsData encounter) {
        if (encounter == null) return "Live";
        if (encounter.map == null || encounter.map.name == null) return "Unknown encounter";
        String name = encounter.map.name, realm = encounter.map.realmName;
        return "Realm of the Mad God".equals(name) && realm != null && realm.length() > 12
            ? "Realm - " + realm.substring(12) : name;
    }

    private void loadButton() {
        JFileChooser chooser = new JFileChooser(new File("."));
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("DPS encounters (*.dps)", "dps"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) importFile(chooser.getSelectedFile());
    }

    SwingWorker<DpsData, Void> importFile(File file) {
        requireIdleEdt();
        setBusy(true, "Loading " + file.getName() + "…");
        SwingWorker<DpsData, Void> worker = new SwingWorker<DpsData, Void>() {
            protected DpsData doInBackground() throws IOException, ClassNotFoundException {
                try (ObjectInputStream input = new ObjectInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                    Object value = input.readObject();
                    if (!(value instanceof DpsData)) throw new IOException("This file is not a DPS encounter.");
                    DpsData saved = (DpsData)value;
                    if (saved.hitList == null || saved.deathNotifications == null) throw new IOException("The encounter is incomplete.");
                    return saved;
                }
            }
            protected void done() {
                try {
                    data.dpsData.add(get());
                    refreshEncounters(); DpsGUI.updateLabel();
                    setBusy(false, "Loaded " + file.getName());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); failed(e);
                } catch (ExecutionException e) { failed(e.getCause()); }
            }
        };
        worker.execute();
        return worker;
    }

    private void saveButton() {
        JFileChooser chooser = new JFileChooser(new File("."));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        CheckBoxAccessory accessory = new CheckBoxAccessory();
        chooser.setAccessory(accessory);
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION)
            exportFiles(chooser.getSelectedFile(), accessory.isBoxSelected());
    }

    SwingWorker<Integer, Void> exportFiles(File folder, boolean debug) {
        requireIdleEdt();
        // Snapshot UI selection/options before leaving the EDT. Archived entity/packet graphs are
        // no longer written by capture; detach their containers so clears/imports cannot change the job.
        List<DpsData> exports = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (DpsData saved : encounters) if (saved != null && checked.contains(saved)) {
            exports.add(new DpsData(saved.map, new HashMap<>(saved.hitList), new ArrayList<>(saved.deathNotifications),
                saved.totalDungeonPcTime, saved.dungeonStartTime,
                debug && saved.debugPackets != null ? new ArrayList<>(saved.debugPackets) : null));
            names.add(name(saved));
        }
        setBusy(true, "Saving " + exports.size() + " encounters…");
        SwingWorker<Integer, Void> worker = new SwingWorker<Integer, Void>() {
            protected Integer doInBackground() throws IOException {
                SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd-HH.mm.ss");
                Set<String> used = new HashSet<>();
                for (int i = 0; i < exports.size(); i++) {
                    DpsData saved = exports.get(i);
                    String base = names.get(i).replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_") + " " + date.format(new Date(saved.dungeonStartTime));
                    String filename = base + ".dps";
                    for (int suffix = 2; !used.add(filename); suffix++) filename = base + " (" + suffix + ").dps";
                    try (ObjectOutputStream output = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(new File(folder, filename))))) {
                        output.writeObject(saved);
                    }
                }
                return exports.size();
            }
            protected void done() {
                try { setBusy(false, "Saved " + get() + " encounters."); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); failed(e); }
                catch (ExecutionException e) { failed(e.getCause()); }
            }
        };
        worker.execute();
        return worker;
    }

    private void requireIdleEdt() {
        if (!SwingUtilities.isEventDispatchThread() || busy) throw new IllegalStateException("Start one file operation at a time on the EDT");
    }
    private void setBusy(boolean value, String message) { busy = value; status.setText(message); updateButtons(); }
    private void updateButtons() { load.setEnabled(!busy); save.setEnabled(!busy && !checked.isEmpty()); }
    private void failed(Throwable error) { setBusy(false, "File operation failed: " + error.getMessage()); }

    public static void open(DpsGUI dps, TomatoData data) {
        DungeonListGUI list = new DungeonListGUI(dps, data);
        JButton close = new JButton("Close");
        JOptionPane pane = new JOptionPane(list, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION, null, new JButton[]{close}, close);
        close.addActionListener(e -> { pane.setValue(-1); SwingUtilities.getWindowAncestor(close).dispose(); });
        JDialog dialog = pane.createDialog(dps, "Dungeon List");
        realmshark.branding.AppIdentity.apply(dialog);
        dialog.setResizable(true); dialog.setVisible(true);
    }

    private final class EncounterModel extends AbstractTableModel {
        private final String[] columns = {"Export", "Encounter", "Dungeon"};
        public int getRowCount() { return encounters.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int column) { return columns[column]; }
        public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
        public boolean isCellEditable(int row, int column) { return row > 0 && column == 0; }
        public Object getValueAt(int row, int column) {
            DpsData saved = encounters.get(row);
            if (column == 0) return saved == null ? null : checked.contains(saved);
            if (column == 1) return row == 0 ? "" : (encounters.size() - row) + " / " + (encounters.size() - 1);
            return name(saved);
        }
        public void setValueAt(Object value, int row, int column) {
            if (!isCellEditable(row, column)) return;
            if (Boolean.TRUE.equals(value)) checked.add(encounters.get(row)); else checked.remove(encounters.get(row));
            fireTableCellUpdated(row, column); updateButtons();
        }
    }

    public class CheckBoxAccessory extends JPanel {
        private final JCheckBox checkBox = new JCheckBox("Save Debug Data");
        public CheckBoxAccessory() {
            super(new BorderLayout());
            setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            add(checkBox, BorderLayout.NORTH);
        }
        public boolean isBoxSelected() { return checkBox.isSelected(); }
    }
}
