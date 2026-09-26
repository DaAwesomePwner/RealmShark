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
import tomato.realmshark.Sound;

/**
 * A detached rule draft and a pure sample checker. Constructing this panel never opens a window,
 * saves, enables an alert or plays audio. Contextual drafts ({@link #draft(AlertRules.Draft)}) add a
 * proposed row and evaluate their detached sample silently; only Save rules and Test sound act.
 */
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
    private final JTextArea draftSummary = ContentStyle.wrappingText("");
    /** Why a proposed rule was not added; shown in the error style, separate from the draft's source. */
    private final JTextArea draftProblem = ContentStyle.wrappingText("");
    static final String NO_RULES = "No rules yet. Choose Add rule to create one.";
    /** Shown in place of rows while the draft has none; never part of the rules. */
    private final JLabel emptyRules = new JLabel(NO_RULES, SwingConstants.CENTER);
    private final JPanel ruleRows = new JPanel(new CardLayout());
    private JDialog dialog;
    private Runnable returnAction;

    public AlertRuleEditor(AlertRules service, AlertRules.Domain domain, Collection<String> legacy, String title, Runnable testSound) {
        this(service, domain, legacy, title, testSound, null);
    }

    /**
     * Opens a contextual draft. The proposed rule is added as an unsaved row (or the identical existing
     * rule is selected) and the detached sample is checked silently. Nothing is saved, enabled or played.
     */
    public AlertRuleEditor(AlertRules service, AlertRules.Domain domain, Collection<String> legacy, String title, Runnable testSound, AlertRules.Draft draft) {
        super(new BorderLayout(8, 8)); this.service = service; this.title = title; base = service.snapshot(domain, legacy);
        if (draft != null && draft.domain != domain) throw new IllegalArgumentException("Draft belongs to another alert category.");
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
        JPanel samples = new JPanel(new BorderLayout(0, 6));
        JPanel fields = ContentStyle.responsiveGrid(2, 260, 8);
        sampleId.setName("rule-sample-id"); sampleText.setName("rule-sample-text"); sampleResult.setName("rule-sample-result");
        if (domain != AlertRules.Domain.CHAT) fields.add(labeled("Sample ID", sampleId));
        if (domain != AlertRules.Domain.ENTITY) fields.add(labeled(domain == AlertRules.Domain.ITEM ? "Sample name" : "Sample message", sampleText));
        JButton check = new JButton("Check sample"); check.setName("rule-check-sample");
        // Check sample sits beside the sample field (bottom-aligned with it) so the match result stays in view
        // in a compact editor instead of below another row of controls.
        JPanel sampleActions = new JPanel(new BorderLayout()); sampleActions.add(check, BorderLayout.SOUTH);
        JPanel sampleInputs = new JPanel(new BorderLayout(8, 0));
        sampleInputs.add(fields); sampleInputs.add(sampleActions, BorderLayout.EAST);
        check.addActionListener(e -> checkSample()); samples.add(sampleInputs, BorderLayout.NORTH); samples.add(sampleResult);
        // Three rows keep the sample result on screen in a compact editor; taller windows give the table the rest.
        JScrollPane ruleScroll = ContentStyle.tableScroll(table, 3);
        emptyRules.setName("alert-rule-empty"); emptyRules.setFont(ContentStyle.metadata(ContentStyle.body()));
        JPanel emptyCard = new JPanel(new BorderLayout()) {
            @Override public Dimension getMinimumSize() { return ruleScroll.getMinimumSize(); }
            @Override public Dimension getPreferredSize() { return ruleScroll.getMinimumSize(); }
        };
        emptyCard.setBorder(ruleScroll.getBorder()); emptyCard.add(emptyRules);
        ruleRows.add(ruleScroll, "rows"); ruleRows.add(emptyCard, "empty");
        model.addTableModelListener(e -> showRuleRows());
        JPanel body = new JPanel(new BorderLayout(0, 6)); body.add(ruleRows); body.add(actions, BorderLayout.SOUTH);
        JPanel lower = new JPanel(new BorderLayout(0, 6)); lower.add(samples);
        draftSummary.setName("rule-draft-source"); draftSummary.setVisible(false);
        draftProblem.setName("rule-draft-problem"); draftProblem.setVisible(false); draftProblem.setForeground(ContentStyle.color("rose"));
        draftProblem.getAccessibleContext().setAccessibleName("Draft problem");
        JPanel notes = new JPanel(new BorderLayout(0, 4)); notes.add(draftProblem, BorderLayout.NORTH); notes.add(draftSummary);
        JPanel intro = new JPanel(new BorderLayout(0, 6)); intro.add(notes, BorderLayout.NORTH); intro.add(help);
        add(ContentStyle.page(intro, body, lower));
        JPanel bottom = ContentStyle.controls(); JButton cancel = new JButton("Cancel"), test = new JButton("Test sound");
        test.addActionListener(e -> testSound.run()); cancel.addActionListener(e -> closeDraft());
        bottom.add(test); bottom.add(cancel); bottom.add(save);
        // The save state stays beside Save, outside the scrolling page, so "Unsaved" is visible without scrolling.
        JPanel footer = new JPanel(new BorderLayout(0, 4)); footer.add(saving.status, BorderLayout.NORTH); footer.add(bottom);
        add(footer, BorderLayout.SOUTH);
        showRuleRows();
        save.setName("rule-save"); save.addActionListener(e -> {
            if (!finishEditing()) return;
            AlertRules.Snapshot[] accepted = new AlertRules.Snapshot[1];
            saving.submit(() -> {
                AlertRules.Submission submitted = service.save(base, draft()); base = submitted.active;
                accepted[0] = submitted.active;
                return submitted.completion;
            }, () -> service.isCurrent(accepted[0]));
        });
        if (draft != null) applyDraft(draft);
        ContentStyle.refreshFonts(this);
    }

    /** Application editor for a contextual draft: current rules, the category's sound for Test only. EDT only. */
    public static AlertRuleEditor draft(AlertRules.Draft draft) {
        AlertRules service = AlertRules.application();
        Sound sound = soundFor(draft.domain);
        return new AlertRuleEditor(service, draft.domain, service.storedLegacy(draft.domain), titleFor(draft.domain), () -> sound.preview(null), draft);
    }
    /** Opens {@link #draft(AlertRules.Draft)} in a dialog, then runs {@code onReturn} (restore the source record) when it closes. */
    public static void openDraft(AlertRules.Draft draft, Runnable onReturn) {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> openDraft(draft, onReturn)); return; }
        draft(draft).open(onReturn);
    }
    public static String titleFor(AlertRules.Domain domain) {
        return domain == AlertRules.Domain.CHAT ? "Chat alert rules" : domain == AlertRules.Domain.ITEM ? "Item alert rules" : "Entity alert rules";
    }
    public static Sound soundFor(AlertRules.Domain domain) { return domain == AlertRules.Domain.CHAT ? Sound.keywords : Sound.custom; }

    private void applyDraft(AlertRules.Draft draft) {
        Sound sound = soundFor(draft.domain);
        StringBuilder text = new StringBuilder("Draft from ").append(draft.source).append('.');
        String problem = "";
        if (!base.editable()) problem = base.problem;
        else {
            AlertRules.Rule proposed = null;
            try { proposed = draft.proposedRule(); } catch (IllegalArgumentException invalid) { problem = "Proposed rule not added: " + invalid.getMessage(); }
            if (proposed != null) {
                int existing = base.indexOf(proposed.mode, proposed.value);
                if (existing >= 0) {
                    text.append(" An identical rule already exists (rule ").append(existing + 1).append("); no row was added.");
                    table.setRowSelectionInterval(existing, existing);
                } else {
                    rows.add(new Row(proposed.mode, proposed.value)); model.fireTableDataChanged(); saving.edited();
                    int row = rows.size() - 1; table.setRowSelectionInterval(row, row);
                    text.append(" Proposed rule ").append(row + 1).append(": ").append(proposed.mode.label).append(" \u201c").append(proposed.value).append("\u201d.");
                }
            }
        }
        text.append(" Nothing is saved or enabled until you choose Save rules; Test sound is the only playback. The ")
            .append(sound.label).append(" sound is currently ").append(sound.isEnabled() ? "on" : "off").append(" and saving a rule does not change it.");
        draftSummary.setText(text.toString()); draftSummary.setVisible(true);
        draftProblem.setText(problem); draftProblem.setVisible(!problem.isEmpty());
        if (draft.sampleId != null) sampleId.setText(Integer.toString(draft.sampleId));
        if (draft.sampleText != null) sampleText.setText(draft.sampleText);
        checkSample();
    }
    /** Selects the saved rule with this exact mode and value. Returns false when the recorded rule is no longer present. */
    public boolean focusRule(AlertRules.Mode mode, String value) {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.original != null && row.original.mode == mode && row.original.value.equals(value)) {
                table.setRowSelectionInterval(i, i); table.scrollRectToVisible(table.getCellRect(i, 0, true)); return true;
            }
        }
        table.clearSelection(); return false;
    }
    /** Shows an explanation above the rules, e.g. why a recorded rule could not be resolved. */
    public void explain(String message) { draftSummary.setText(message); draftSummary.setVisible(!message.isEmpty()); }
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
    private void showRuleRows() { ((CardLayout) ruleRows.getLayout()).show(ruleRows, rows.isEmpty() ? "empty" : "rows"); }
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
    public void open() { open(null); }
    /** Modal editor; {@code onReturn} runs once on the EDT after the dialog closes, e.g. to reselect the source record. */
    public void open(Runnable onReturn) {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> open(onReturn)); return; }
        returnAction = onReturn;
        dialog = new JDialog(tomato.gui.TomatoGUI.getFrame(), title, true);
        realmshark.branding.AppIdentity.apply(dialog); dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { closeDraft(); }
            @Override public void windowClosed(WindowEvent e) { returned(); }
        });
        dialog.setContentPane(this); dialog.pack();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        dialog.setSize(Math.min(800, screen.width), Math.min(620, screen.height)); dialog.setLocationRelativeTo(dialog.getOwner()); dialog.setVisible(true);
    }
    private void returned() { Runnable action = returnAction; returnAction = null; if (action != null) action.run(); }
    private void closeDraft() {
        if (dialog != null && JOptionPane.showConfirmDialog(this, saving.closeExplanation(), "Close rules", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) dialog.dispose();
    }
    private static JPanel labeled(String text, JTextField field) {
        JLabel label = new JLabel(text); label.setLabelFor(field); field.getAccessibleContext().setAccessibleName(text);
        JPanel panel = new JPanel(new BorderLayout(0, 4)); panel.add(label, BorderLayout.NORTH); panel.add(field); return panel;
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
        Row(AlertRules.Mode mode) { this(mode, ""); }
        Row(AlertRules.Mode mode, String value) { original = null; this.mode = mode; this.value = value; }
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
