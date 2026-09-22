package tomato.gui.maingui;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.AbstractTableModel;
import tomato.gui.modern.ContentStyle;
import tomato.realmshark.AlertRules;

/** A detached rule draft and a pure sample checker. Constructing this panel never opens a window. */
public class AlertRuleEditor extends JPanel {
    private final AlertRules service;
    private AlertRules.Snapshot base;
    private final String title;
    private final List<Row> rows = new ArrayList<>();
    private final RuleTable model = new RuleTable();
    private final JTable table = new JTable(model);
    private final JTextField sampleId = new JTextField(10), sampleText = new JTextField(24);
    private final JTextArea sampleResult = ContentStyle.wrappingText("Check a sample silently; this does not test delivery or sound.");
    private final JButton save = new JButton("Save rules");
    private final DraftSaveStatus saving = new DraftSaveStatus(save, "rule-save-status");
    private JDialog dialog;

    public AlertRuleEditor(AlertRules service, AlertRules.Domain domain, Collection<String> legacy, String title, Runnable testSound) {
        super(new BorderLayout(8, 8)); this.service = service; this.title = title; base = service.snapshot(domain, legacy);
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        for (AlertRules.Rule rule : base.rules) rows.add(new Row(rule));
        JTextArea help = ContentStyle.wrappingText("Edit a draft, then Save rules. Save applies immediately; disk confirmation follows. "
            + "Chat tokens split only at spaces: punctuation, tabs and newlines stay in tokens. Legacy rules retain their original matching.");
        if (!base.editable()) help.setText(base.problem);
        else if (rows.stream().anyMatch(row -> !row.original.supported())) help.append(" Unsupported rules are preserved and not evaluated; Remove is explicit deletion.");
        ContentStyle.table(table); table.setName("alert-rule-table"); table.getAccessibleContext().setAccessibleName(title + " draft rules");
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(270);
        table.getColumnModel().getColumn(1).setPreferredWidth(360);
        table.getTableHeader().setReorderingAllowed(false);
        JComboBox<AlertRules.Mode> modes = new JComboBox<>();
        for (AlertRules.Mode mode : AlertRules.Mode.values()) if (mode.domain == domain) modes.addItem(mode);
        table.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(modes));
        table.getColumnModel().getColumn(0).setCellRenderer(new ContentStyle.Cell() {
            @Override protected void setValue(Object value) { setText(value == null ? "Unsupported — preserved" : value.toString()); }
        });
        JTextField valueEditor = new JTextField();
        valueEditor.getDocument().addDocumentListener(changes(saving::edited));
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(valueEditor));
        Action add = new AbstractAction("Add rule") {
            public void actionPerformed(ActionEvent e) {
                if (!finishEditing()) return;
                rows.add(new Row((AlertRules.Mode)modes.getItemAt(0))); model.fireTableDataChanged(); saving.edited();
                int row = rows.size() - 1; table.setRowSelectionInterval(row, row); table.editCellAt(row, 1);
                if (table.getEditorComponent() != null) table.getEditorComponent().requestFocusInWindow();
            }
        };
        Action remove = new AbstractAction("Remove selected rule") {
            public void actionPerformed(ActionEvent e) {
                int row = table.getSelectedRow(); if (row < 0) return;
                if (table.isEditing()) table.getCellEditor().cancelCellEditing();
                rows.remove(row); model.fireTableDataChanged(); saving.edited();
                if (!rows.isEmpty()) table.setRowSelectionInterval(Math.min(row, rows.size() - 1), Math.min(row, rows.size() - 1));
            }
        };
        add.setEnabled(base.editable()); remove.setEnabled(base.editable()); save.setEnabled(base.editable());
        JPanel actions = ContentStyle.controls(); actions.add(new JButton(add)); actions.add(new JButton(remove));
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("INSERT"), "add-rule"); table.getActionMap().put("add-rule", add);
        table.getInputMap().put(KeyStroke.getKeyStroke("DELETE"), "remove-rule"); table.getActionMap().put("remove-rule", remove);
        JPanel samples = new JPanel(new BorderLayout(0, 6)), fields = ContentStyle.controls();
        sampleId.setName("rule-sample-id"); sampleText.setName("rule-sample-text"); sampleResult.setName("rule-sample-result");
        if (domain != AlertRules.Domain.CHAT) fields.add(labeled("Sample ID", sampleId));
        if (domain != AlertRules.Domain.ENTITY) fields.add(labeled(domain == AlertRules.Domain.ITEM ? "Sample name" : "Sample message", sampleText));
        JButton check = new JButton("Check sample"); check.setName("rule-check-sample"); fields.add(check);
        check.addActionListener(e -> checkSample()); samples.add(fields, BorderLayout.NORTH); samples.add(sampleResult);
        JPanel body = new JPanel(new BorderLayout(0, 6)); body.add(ContentStyle.tableScroll(table, 5)); body.add(actions, BorderLayout.SOUTH);
        JPanel lower = new JPanel(new BorderLayout(0, 6)); lower.add(samples); lower.add(saving.status, BorderLayout.SOUTH);
        add(ContentStyle.page(help, body, lower));
        JPanel bottom = ContentStyle.controls(); JButton cancel = new JButton("Cancel"), test = new JButton("Test sound");
        test.addActionListener(e -> testSound.run()); cancel.addActionListener(e -> closeDraft());
        bottom.add(test); bottom.add(cancel); bottom.add(save); add(bottom, BorderLayout.SOUTH);
        save.setName("rule-save"); save.addActionListener(e -> {
            if (!finishEditing()) return;
            AlertRules.Snapshot[] accepted = new AlertRules.Snapshot[1];
            saving.submit(() -> {
                AlertRules.Submission submitted = service.save(base, draft()); base = submitted.active;
                accepted[0] = submitted.active;
                return submitted.completion;
            }, () -> service.isCurrent(accepted[0]));
        });
        ContentStyle.refreshFonts(this);
    }
    private List<AlertRules.Rule> draft() {
        List<AlertRules.Rule> result = new ArrayList<>();
        for (Row row : rows) {
            if (row.value.trim().isEmpty() && (row.original == null || !row.value.equals(row.original.value)))
                throw new IllegalArgumentException("Enter a rule value or remove the empty row.");
            result.add(row.mode == null ? row.original : row.original == null
                ? AlertRules.Rule.of(row.mode, row.value) : row.original.edited(row.mode, row.value));
        }
        return result;
    }
    private boolean finishEditing() { return !table.isEditing() || table.getCellEditor().stopCellEditing(); }
    private void checkSample() {
        if (!finishEditing()) return;
        try {
            AlertRules.Snapshot snapshot = base.withRules(draft());
            AlertRules.Match match;
            if (base.domain == AlertRules.Domain.CHAT) match = snapshot.matchChat(sampleText.getText());
            else {
                int id = Integer.parseInt(sampleId.getText().trim());
                match = base.domain == AlertRules.Domain.ITEM ? snapshot.matchItem(id, sampleText.getText().isEmpty() ? null : sampleText.getText())
                    : snapshot.matchEntityType(id);
            }
            sampleResult.setText(match.explanation);
        } catch (IllegalArgumentException invalid) { sampleResult.setText("Check input: " + invalid.getMessage()); }
    }
    public void open() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::open); return; }
        dialog = new JDialog(tomato.gui.TomatoGUI.getFrame(), title, true);
        realmshark.branding.AppIdentity.apply(dialog); dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { closeDraft(); } });
        dialog.setContentPane(this); dialog.pack();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        dialog.setSize(Math.min(800, screen.width), Math.min(620, screen.height)); dialog.setLocationRelativeTo(dialog.getOwner()); dialog.setVisible(true);
    }
    private void closeDraft() {
        if (dialog != null && JOptionPane.showConfirmDialog(this, saving.closeExplanation(), "Close rules", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) dialog.dispose();
    }
    private static JPanel labeled(String text, JTextField field) {
        JLabel label = new JLabel(text); label.setLabelFor(field); field.getAccessibleContext().setAccessibleName(text);
        JPanel panel = new JPanel(new BorderLayout(4, 0)); panel.add(label, BorderLayout.WEST); panel.add(field); return panel;
    }
    public static DocumentListener changes(Runnable action) {
        return new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { action.run(); }
            public void removeUpdate(DocumentEvent e) { action.run(); }
            public void changedUpdate(DocumentEvent e) { action.run(); }
        };
    }
    private static final class Row {
        final AlertRules.Rule original; AlertRules.Mode mode; String value;
        Row(AlertRules.Rule rule) { original = rule; mode = rule.mode; value = rule.value; }
        Row(AlertRules.Mode mode) { original = null; this.mode = mode; value = ""; }
    }
    private final class RuleTable extends AbstractTableModel {
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return 2; }
        public String getColumnName(int column) { return column == 0 ? "Match mode" : "Value"; }
        public Object getValueAt(int row, int column) { return column == 0 ? rows.get(row).mode : rows.get(row).value; }
        public boolean isCellEditable(int row, int column) { return base.editable() && rows.get(row).mode != null; }
        public void setValueAt(Object value, int row, int column) {
            Row entry = rows.get(row); if (column == 0) entry.mode = (AlertRules.Mode)value; else entry.value = String.valueOf(value);
            saving.edited(); fireTableCellUpdated(row, column);
        }
    }
}
