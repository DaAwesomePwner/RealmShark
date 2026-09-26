package tomato.gui.notifications;

import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import tomato.gui.maingui.AlertRuleEditor;
import tomato.gui.modern.ContentStyle;
import tomato.realmshark.AlertDecisions;
import tomato.realmshark.AlertDecisions.Decision;
import tomato.realmshark.AlertDecisions.Result;
import tomato.realmshark.AlertRules;

/**
 * ALERT-4 view of recorded decisions. Reads detached records only: it never replays an observation,
 * and Edit rule resolves the recorded rule against the current rules instead of trusting its index.
 */
final class RecentDecisionsPanel extends JPanel {
    static final String[] OUTCOMES = {"All outcomes", "Played", "Suppressed", "Unavailable", "Pending", "No match"};
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final AlertDecisions decisions;
    private final NotificationsGUI owner;
    final DefaultTableModel model = new DefaultTableModel(new String[]{"Time", "Source", "Outcome", "Rule / sound", "Observation"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    final JTable table = new JTable(model);
    final JCheckBox includeNoMatch = new JCheckBox("Include no match");
    final JComboBox<String> outcome = new JComboBox<>(OUTCOMES);
    final JTextArea detail = ContentStyle.wrappingText("Select a decision to see why it did or did not play.", 3);
    final JTextArea status = ContentStyle.wrappingText("");
    final JButton editRule = new JButton("Edit rule"), draftRule = new JButton("Draft rule from sample");
    static final String NONE_RECORDED = "No decisions recorded yet", NONE_SHOWN = "No decisions match this view";
    /** Shown in place of the rows when nothing is listed; never added to the table model. */
    final JLabel empty = new JLabel(NONE_RECORDED, SwingConstants.CENTER);
    private final JPanel rowsCard = new JPanel(new CardLayout());
    /** Minimum rows the table keeps before the tab content scrolls instead. */
    static final int MINIMUM_ROWS = 4;
    final JScrollPane page;
    private List<Decision> shown = new ArrayList<>();
    private boolean rebuilding, stale;
    private final java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean();
    /** Seams: tests replace the rule service and the modal openers. */
    AlertRules rules = AlertRules.application();
    Consumer<AlertRuleEditor> editorOpener = AlertRuleEditor::open;
    Consumer<AlertRules.Draft> draftOpener = draft -> AlertRuleEditor.openDraft(draft, null);
    Runnable enchantOpener = tomato.gui.TomatoGUI::openEnchantPing;
    // Coalesced: at most one queued EDT task, and hidden tabs only mark themselves stale (busy chat must not rebuild a hidden table).
    private final Runnable listener = () -> { if (pending.compareAndSet(false, true)) SwingUtilities.invokeLater(this::queued); };

    RecentDecisionsPanel(AlertDecisions decisions, NotificationsGUI owner) {
        super(new BorderLayout(0, 8)); this.decisions = decisions; this.owner = owner;
        // One short line keeps rows on screen in a compact window; the capacity details stay in tooltips.
        JTextArea note = ContentStyle.wrappingText("This app session's latest " + AlertDecisions.CAPACITY + " decisions, newest first. Not saved; nothing is replayed.");
        note.setToolTipText("Latest " + AlertDecisions.CAPACITY + " alert decisions from this app session, newest first. No-match results keep their own latest "
            + AlertDecisions.NO_MATCH_CAPACITY + ". Records are not saved and nothing is replayed; Edit rule opens the current rule when it still exists.");
        includeNoMatch.setToolTipText("No-match results keep their own latest " + AlertDecisions.NO_MATCH_CAPACITY + ".");
        editRule.setToolTipText("Opens the current rule when it still exists.");
        table.setName("decisions-table"); includeNoMatch.setName("decisions-include-no-match"); outcome.setName("decisions-outcome");
        detail.setName("decisions-detail"); status.setName("decisions-status"); editRule.setName("decisions-edit-rule"); draftRule.setName("decisions-draft-rule");
        outcome.getAccessibleContext().setAccessibleName("Decision outcome");
        outcome.setToolTipText("Suppressed: ignored, cooldown, alert off, muted or volume 0. Unavailable: playback failed or audio was busy."); table.getAccessibleContext().setAccessibleName("Recent alert decisions");
        ContentStyle.table(table); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.getTableHeader().setReorderingAllowed(false);
        int[] widths = {80, 90, 170, 220, 260};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        JPanel controls = ContentStyle.controls(); controls.add(outcome); controls.add(includeNoMatch);
        JPanel actions = ContentStyle.controls(); actions.add(editRule); actions.add(draftRule);
        note.setName("decisions-intro");
        JPanel top = new JPanel(new BorderLayout(0, 6)); top.add(note, BorderLayout.NORTH); top.add(controls);
        JPanel lower = new JPanel(new BorderLayout(0, 6)); lower.add(detail, BorderLayout.NORTH); lower.add(actions); lower.add(status, BorderLayout.SOUTH);
        JScrollPane rows = ContentStyle.tableScroll(table, MINIMUM_ROWS); rows.setName("decisions-rows");
        empty.setName("decisions-empty"); empty.setFont(ContentStyle.metadata(ContentStyle.body()));
        JPanel emptyCard = new JPanel(new BorderLayout()) {
            @Override public Dimension getMinimumSize() { return rows.getMinimumSize(); }
            @Override public Dimension getPreferredSize() { return rows.getMinimumSize(); }
        };
        emptyCard.setBorder(rows.getBorder()); emptyCard.add(empty);
        rowsCard.add(rows, "rows"); rowsCard.add(emptyCard, "empty");
        // The page fills the tab when it fits (the table takes the spare height) and otherwise scrolls, keeping the
        // intro, filter, column headers and at least MINIMUM_ROWS rows in normal flow instead of overlapping.
        page = ContentStyle.page(top, rowsCard, lower); page.setName("decisions-page");
        add(page);
        includeNoMatch.addActionListener(e -> refresh()); outcome.addActionListener(e -> { if (outcome.getSelectedIndex() == 5) includeNoMatch.setSelected(true); refresh(); });
        table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && !rebuilding) showDetail(); });
        editRule.addActionListener(e -> editRule()); draftRule.addActionListener(e -> draftRule());
        addHierarchyListener(e -> { if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing() && stale) refresh(); });
        refresh();
    }
    @Override public void addNotify() { super.addNotify(); decisions.addListener(listener); refresh(); }
    @Override public void removeNotify() { decisions.removeListener(listener); super.removeNotify(); }
    private void queued() { pending.set(false); if (isShowing()) refresh(); else stale = true; }

    void refresh() {
        stale = false;
        Decision selected = selected();
        rebuilding = true;
        shown = new ArrayList<>();
        for (Decision d : decisions.snapshot(includeNoMatch.isSelected())) if (included(d)) shown.add(d);
        model.setRowCount(0);
        for (Decision d : shown) model.addRow(new Object[]{CLOCK.format(Instant.ofEpochMilli(d.time).atZone(ZoneId.systemDefault())), d.source.label,
            d.result.label, d.ruleLabel(), d.subject});
        if (selected != null) select(selected.id, false);
        rebuilding = false;
        empty.setText(decisions.snapshot(true).isEmpty() ? NONE_RECORDED : NONE_SHOWN + (includeNoMatch.isSelected() ? "." : ". No-match results are hidden; select Include no match to list them."));
        ((CardLayout) rowsCard.getLayout()).show(rowsCard, shown.isEmpty() ? "empty" : "rows");
        showDetail();
    }
    private boolean included(Decision d) {
        switch (outcome.getSelectedIndex()) {
            case 1: return d.result == Result.PLAYED;
            case 2: return d.result.suppressed();
            case 3: return d.result.failed();
            case 4: return d.result == Result.SUBMITTED;
            case 5: return d.result == Result.NO_MATCH || d.result == Result.RULES_UNAVAILABLE;
            default: return true;
        }
    }
    /** Selects a recorded decision, widening filters when needed. Returns false when it has been evicted. */
    boolean select(long id, boolean widen) {
        for (int i = 0; i < shown.size(); i++) if (shown.get(i).id == id) { table.setRowSelectionInterval(i, i); revealRow(i); return true; }
        Decision d = decisions.find(id);
        if (d == null || !widen) { if (widen) status.setText("Decision #" + id + " is no longer retained."); return false; }
        outcome.setSelectedIndex(0); includeNoMatch.setSelected(true); refresh();
        return select(id, false);
    }
    /**
     * Scrolls only the table's own viewport to {@code row}. JTable.scrollRectToVisible would also scroll every
     * enclosing page, hiding the intro, filter and column headers. Repeated after layout for a page not yet shown.
     */
    private void revealRow(int row) {
        scrollTableTo(row);
        SwingUtilities.invokeLater(() -> { if (table.getSelectedRow() == row) scrollTableTo(row); });
    }
    private void scrollTableTo(int row) {
        if (!(table.getParent() instanceof JViewport) || row < 0 || row >= table.getRowCount()) return;
        JViewport viewport = (JViewport) table.getParent();
        Rectangle cell = table.getCellRect(row, 0, true), view = viewport.getViewRect();
        if (view.height <= 0 || cell.y >= view.y && cell.y + cell.height <= view.y + view.height) return;
        int y = cell.y + cell.height > view.y + view.height ? cell.y + cell.height - view.height : cell.y;
        y = Math.max(0, Math.min(y, table.getHeight() - view.height));
        viewport.setViewPosition(new Point(view.x, y));
    }
    /** Shows the top of the page: intro, outcome filter and column headers. */
    void scrollPageToTop() { page.getViewport().setViewPosition(new Point(0, 0)); }
    Decision selected() { int row = table.getSelectedRow(); return row < 0 || row >= shown.size() ? null : shown.get(row); }

    private void showDetail() {
        Decision d = selected();
        editRule.setEnabled(d != null && (d.hasRule() || d.sound != null));
        draftRule.setEnabled(d != null && draftable(d));
        if (d == null) { detail.setText(shown.isEmpty() ? "No decisions match this view yet." : "Select a decision to see why it did or did not play."); return; }
        StringBuilder text = new StringBuilder("Decision #").append(d.id).append(" · ").append(CLOCK.format(Instant.ofEpochMilli(d.time).atZone(ZoneId.systemDefault())))
            .append(" · ").append(d.source.label).append('\n').append("Outcome: ").append(d.result.label).append('\n');
        if (d.hasRule()) text.append("Rule (as recorded): ").append(d.ruleLabel()).append('\n');
        text.append("Observation: ").append(d.subject).append('\n').append("Why: ").append(d.explanation);
        if (d.soundLabel != null) text.append('\n').append("Sound: ").append(d.soundLabel).append(d.playback.isEmpty() ? (d.result == Result.SUBMITTED ? " — waiting for the playback result." : "") : " — " + d.playback);
        detail.setText(text.toString()); detail.setCaretPosition(0);
    }
    private static boolean draftable(Decision d) {
        if (d.result != Result.NO_MATCH || d.domain == null) return false;
        return d.domain == AlertRules.Domain.CHAT ? d.sample != null && !d.sample.trim().isEmpty() : d.sampleId != null;
    }

    /** Builds the current editor for a typed-rule decision, resolving the recorded rule by mode and value. */
    AlertRuleEditor editorFor(Decision d) {
        AlertRuleEditor editor = new AlertRuleEditor(rules, d.domain, rules.storedLegacy(d.domain), AlertRuleEditor.titleFor(d.domain),
            () -> AlertRuleEditor.soundFor(d.domain).preview(null));
        String message;
        if (d.ruleMode == null) message = "This decision did not use a specific rule; showing the current " + AlertRuleEditor.titleFor(d.domain).toLowerCase(Locale.ROOT) + ".";
        else if (!editor.focusRule(d.ruleMode, d.ruleValue))
            message = "The recorded rule (" + d.ruleMode.label + " “" + d.ruleValue + "”) is no longer active; it was edited or removed after this decision. No rule is selected.";
        else {
            int now = rules.snapshot(d.domain, rules.storedLegacy(d.domain)).indexOf(d.ruleMode, d.ruleValue);
            message = now == d.ruleIndex ? "Showing the recorded rule " + (now + 1) + "."
                : "Showing the recorded rule, now rule " + (now + 1) + " (it was rule " + (d.ruleIndex + 1) + " when this decision was made).";
        }
        editor.explain(message); status.setText(message);
        return editor;
    }
    void editRule() {
        Decision d = selected(); if (d == null) return;
        if (AlertDecisions.ENCHANT_REF.equals(d.ruleRef)) { status.setText("Opening the current enchantment selection."); enchantOpener.run(); return; }
        if (d.domain != null && d.ruleMode != null) { editorOpener.accept(editorFor(d)); return; }
        switch (d.source) {
            case REALM_EVENT: status.setText(owner.focusRealmRule(d.ruleRef) ? "Showing the realm event rule." : "That realm event rule was removed after this decision."); return;
            case KEY_POP: owner.focusDungeon(d.ruleRef, null); status.setText("Showing " + d.ruleRef + " in Key-pops."); return;
            default:
                if (d.sound != null) { status.setText(owner.focusSound(d.sound) ? "Showing the " + d.soundLabel + " sound settings." : "That sound is no longer available."); return; }
                if (d.domain != null) editorOpener.accept(editorFor(d));
        }
    }
    void draftRule() {
        Decision d = selected(); if (d == null || !draftable(d)) return;
        String source = "Recent decision #" + d.id + " · " + d.subject;
        AlertRules.Draft draft = d.domain == AlertRules.Domain.CHAT ? AlertRules.Draft.chat(AlertRules.Mode.TEXT_CONTAINS, d.sample.trim(), d.sample, source)
            : d.domain == AlertRules.Domain.ITEM ? AlertRules.Draft.item(AlertRules.Mode.ITEM_ID, Integer.toString(d.sampleId), d.sampleId, d.sample, source)
            : AlertRules.Draft.entity(d.sampleId, source);
        draftOpener.accept(draft);
    }
}
