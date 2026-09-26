package tomato.gui.quest;

import tomato.gui.modern.ContentStyle;
import tomato.gui.stats.Formatters;
import tomato.planning.PlanData;
import tomato.planning.PlanData.*;
import tomato.planning.PlanningStore;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.BooleanSupplier;

/** Account-explicit manual planning. Drafts and asynchronous completions stay with their account. */
public final class QuestPlanPanel extends JPanel {
    private static final String NONE = "Select a known account…";
    private final PlanningStore store;
    private final IntFunction<String> names;
    private final JComboBox<String> account = new JComboBox<>(new String[]{NONE});
    private final Map<String, Draft> drafts = new HashMap<>();
    private final Set<String> known = new TreeSet<>();
    private final Map<String, QuestPlanEntry> observed = new LinkedHashMap<>();
    private String observedAccount;
    private boolean verified, refreshing;
    private BooleanSupplier scopeStillCurrent = () -> true;
    private final JTextArea status = ContentStyle.wrappingText("Select an account explicitly to edit local plans.");
    private final JTextArea detail = ContentStyle.wrappingText("Saved requirements are not a live inventory. Manual quantities never change from loot or redemption packets.");
    private final JTextArea totals = ContentStyle.wrappingText("");
    private final PlanModel model = new PlanModel();
    private final JTable table = new JTable(model);
    private final List<QuestPlanEntry> rows = new ArrayList<>();
    private final JSpinner repeats = new JSpinner(new SpinnerNumberModel(1L, 1L, PlanData.MAX_QUANTITY, 1L));
    private final JSpinner item = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 1));
    private final JSpinner quantity = new JSpinner(new SpinnerNumberModel(0L, 0L, PlanData.MAX_QUANTITY, 1L));
    private final JTextField note = new JTextField(24);
    private final JCheckBox release = new JCheckBox("Release affected reservations with this edit");
    private final JButton save = button("Save plan", "quest-plan-save", this::save);
    private final javax.swing.Timer poll = new javax.swing.Timer(700, e -> poll());
    private static final class Draft {
        AccountPlan plan; long revision; boolean dirty, pending, loaded; String message;
        Draft(PlanningStore.Snapshot s) { plan = s.plan(); revision = s.revision; message = s.status; loaded = s.ready; }
    }
    public QuestPlanPanel(PlanningStore store, IntFunction<String> names) {
        super(new BorderLayout(0, 6)); this.store = store; this.names = names;
        setName("quest-plan-panel"); account.setName("quest-plan-account");
        account.getAccessibleContext().setAccessibleName("Account for manual quest plans");
        JPanel header = new JPanel(new BorderLayout(0, 6));
        JPanel scope = ContentStyle.controls(); scope.add(new JLabel("Planning account")); scope.add(account); header.add(scope, BorderLayout.NORTH);
        header.add(status, BorderLayout.CENTER);
        ContentStyle.table(table, ContentStyle.Density.COMFORTABLE); table.setName("quest-plan-table");
        table.getAccessibleContext().setAccessibleName("Saved quest plans; select multiple rows for combined totals");
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION); table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        for (int c = 0; c < table.getColumnCount(); c++) table.getColumnModel().getColumn(c).setPreferredWidth(c == 0 ? 220 : 170);
        table.setDefaultRenderer(Object.class, new ContentStyle.Cell());
        table.getSelectionModel().addListSelectionListener(e -> { if (!refreshing && !e.getValueIsAdjusting()) showDetails(); });
        JPanel body = new JPanel(); body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        JScrollPane list = ContentStyle.tableScroll(table, 4); list.setPreferredSize(new Dimension(650, 210)); body.add(list);
        body.add(detail); body.add(totals);
        JPanel edit = ContentStyle.controls(); edit.add(label("Desired repeats", repeats));
        edit.add(button("Set repeats", "quest-plan-repeats", () -> mutate(p -> {
            QuestPlanEntry q = one(p); q.desiredRepeats = ((Number) repeats.getValue()).longValue();
            if (release.isSelected()) p.reservations.remove(q.entryId);
        })));
        edit.add(button("Refresh / reconfirm selected", "quest-plan-reconfirm", this::reconfirm));
        edit.add(button("Remove selected plans", "quest-plan-remove", () -> mutate(p -> {
            for (String id : selectedIds()) { p.quests.remove(id); p.reservations.remove(id); }
        })));
        body.add(edit); body.add(release);
        JPanel stock = ContentStyle.controls(); stock.add(label("Item ID", item)); stock.add(label("Quantity", quantity)); stock.add(label("Manual note", note)); body.add(stock);
        JPanel stockActions = ContentStyle.controls();
        stockActions.add(button("Confirm held quantity", "quest-plan-held", () -> mutate(p -> QuestPlanning.held(p, itemId(), number(), note.getText(), release.isSelected(), System.currentTimeMillis()))));
        stockActions.add(button("Set reservation for selected plan", "quest-plan-reserve", () -> mutate(p -> QuestPlanning.reserve(p, one(p).entryId, itemId(), number()))));
        stockActions.add(button("Release selected reservations", "quest-plan-release", () -> mutate(p -> { for (String id : selectedIds()) p.reservations.remove(id); })));
        stockActions.add(button("Release all reservations", "quest-plan-release-all", () -> mutate(p -> p.reservations.clear())));
        body.add(stockActions);
        body.add(ContentStyle.wrappingText("Quantity 0 explicitly confirms zero held, or releases that reservation. Lowering stock or demand below reservations requires the release checkbox. Held quantities are account-wide manual estimates, including their confirmation time and note. No automatic allocation or consumption occurs."));
        JPanel actions = ContentStyle.controls(); actions.add(save);
        actions.add(button("Reload / discard draft", "quest-plan-reload", () -> {
            String key = selectedAccount(); Draft d = draft(); if (key == null || d == null || d.pending) return;
            if (d.dirty && JOptionPane.showConfirmDialog(this, "Discard this account's unsaved quest plan changes?", "Reload saved plan", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            drafts.put(key, new Draft(store.snapshot(key))); refresh();
        }));
        add(ContentStyle.page(header, body, actions), BorderLayout.CENTER);
        account.addActionListener(e -> { if (!refreshing) refresh(); }); poll();
    }
    @Override public void addNotify() { super.addNotify(); poll.start(); poll(); }
    @Override public void removeNotify() { poll.stop(); super.removeNotify(); }
    public void knownAccounts(Collection<String> values) { known.addAll(values); poll(); }
    void verification(BooleanSupplier current) { scopeStillCurrent = current; }
    private boolean isVerified() { return verified && scopeStillCurrent.getAsBoolean(); }
    /** Current verified scope is required to import or reconfirm; offline editing needs no capture. */
    void observations(String accountKey, boolean current, List<QuestGUI.Quest> quests, long at, long generation) {
        observedAccount = accountKey; verified = current; observed.clear();
        if (current && accountKey != null) {
            known.add(accountKey);
            Set<String> duplicates = new HashSet<>();
            for (QuestGUI.Quest q : quests) if (!q.id.trim().isEmpty()) {
                if (observed.put(q.id, QuestPlanning.snapshot(q, at, generation)) != null) duplicates.add(q.id);
            }
            for (String id : duplicates) observed.remove(id); // Ambiguous stable IDs cannot become actionable.
        }
        poll(); refresh();
    }
    void importQuest(QuestGUI.Quest quest, String legacyPinKey) {
        String key = selectedAccount();
        if (!isVerified() || !Objects.equals(key, observedAccount) || !observed.containsKey(quest.id)) {
            message("Select the verified captured account on Saved plans before adding a quest. A unique stable quest ID is required."); return;
        }
        mutate(p -> {
            if (p.quests.containsKey(quest.id)) throw new IllegalArgumentException("Already planned; use Refresh / reconfirm to replace its saved snapshot");
            QuestPlanEntry q = copy(observed.get(quest.id)); q.legacyPinKey = legacyPinKey; p.quests.put(q.entryId, q);
        });
    }
    private static QuestPlanEntry copy(QuestPlanEntry q) { AccountPlan p = new AccountPlan(); p.quests.put(q.entryId, q); return PlanData.copy(p).quests.get(q.entryId); }
    private void poll() {
        known.addAll(store.accounts());
        Object previous = account.getSelectedItem(); refreshing = true;
        for (String key : known) { boolean found = false; for (int i = 1; i < account.getItemCount(); i++) found |= key.equals(account.getItemAt(i)); if (!found) account.addItem(key); }
        account.setSelectedItem(previous); refreshing = false;
        Draft d = draft(); String key = selectedAccount();
        if (key != null && d != null && !d.dirty && !d.pending) {
            PlanningStore.Snapshot s = store.snapshot(key);
            if (s.revision != d.revision || !d.loaded && s.ready) { drafts.put(key, new Draft(s)); refresh(); }
        }
        updateStatus();
    }
    private String selectedAccount() { Object value = account.getSelectedItem(); return value == null || NONE.equals(value) ? null : value.toString(); }
    private Draft draft() {
        String key = selectedAccount(); if (key == null) return null;
        return drafts.computeIfAbsent(key, k -> new Draft(store.snapshot(k)));
    }
    private AccountPlan effective(Draft d) {
        return isVerified() && Objects.equals(selectedAccount(), observedAccount) ? QuestPlanning.reconcile(d.plan, observed) : PlanData.copy(d.plan);
    }
    private void mutate(Consumer<AccountPlan> action) {
        Draft d = draft(); String key = selectedAccount();
        if (d == null) { message("Select a known account on Saved plans first."); return; }
        PlanningStore.Snapshot s = store.snapshot(key);
        if (d.pending || !s.ready || s.readOnly) { message(d.pending ? "Saving; wait before editing this account." : s.status); return; }
        AccountPlan next = effective(d);
        try { repeats.commitEdit(); item.commitEdit(); quantity.commitEdit(); }
        catch (java.text.ParseException invalid) { message("Enter a whole number within the displayed bounds before applying an edit."); return; }
        try { action.accept(next); PlanData.validate(key, next); d.plan = next; d.dirty = true; d.message = "Unsaved draft — Save plan to persist"; refresh(); }
        catch (RuntimeException failure) { message(failure.getMessage()); }
    }
    private void reconfirm() {
        if (!isVerified() || !Objects.equals(selectedAccount(), observedAccount)) { message("A fresh verified quest list for this account is required to reconfirm."); return; }
        mutate(p -> {
            for (String id : selectedIds()) {
                QuestPlanEntry old = p.quests.get(id), fresh = observed.get(old.stableQuestId);
                if (fresh == null) throw new IllegalArgumentException("Quest unavailable or ambiguous in the current list; retain or remove this plan");
                QuestPlanEntry q = copy(fresh); q.desiredRepeats = old.desiredRepeats; q.legacyPinKey = old.legacyPinKey;
                p.quests.put(id, q); if (release.isSelected()) p.reservations.remove(id);
            }
        });
    }
    private void save() {
        final String key = selectedAccount(); final Draft d = draft();
        if (d == null || d.pending || !d.dirty) return;
        final AccountPlan next = effective(d);
        try { PlanData.validate(key, next); } catch (RuntimeException failure) { message(failure.getMessage()); return; }
        d.pending = true; d.message = "Saving…"; updateStatus();
        store.update(key, d.revision, next).whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
            d.pending = false;
            if (failure == null && result.saved) { d.plan = next; d.revision = result.revision; d.dirty = false; }
            d.message = failure == null ? result.message : "Save failed; draft retained for retry";
            if (Objects.equals(selectedAccount(), key)) refresh();
        }));
    }
    private List<String> selectedIds() {
        List<String> ids = new ArrayList<>(); for (int view : table.getSelectedRows()) ids.add(rows.get(table.convertRowIndexToModel(view)).entryId); return ids;
    }
    private QuestPlanEntry one(AccountPlan p) {
        List<String> ids = selectedIds(); if (ids.size() != 1) throw new IllegalArgumentException("Select exactly one saved quest plan"); return p.quests.get(ids.get(0));
    }
    private int itemId() { return ((Number) item.getValue()).intValue(); }
    private long number() { return ((Number) quantity.getValue()).longValue(); }
    private void refresh() {
        List<String> selected = selectedIds(); refreshing = true; rows.clear(); Draft d = draft();
        if (d != null) rows.addAll(effective(d).quests.values()); model.fireTableDataChanged();
        for (int i = 0; i < rows.size(); i++) if (selected.contains(rows.get(i).entryId)) table.addRowSelectionInterval(table.convertRowIndexToView(i), table.convertRowIndexToView(i));
        if (table.getSelectedRowCount() == 0 && !rows.isEmpty()) table.setRowSelectionInterval(0, 0);
        refreshing = false; showDetails(); updateStatus();
    }
    private void updateStatus() {
        Draft d = draft();
        status.setText(d == null ? "Select an account explicitly to edit local plans." : d.message + "\n" + (d.dirty ? "Unsaved changes retained for this account. " : "")
            + (isVerified() && Objects.equals(selectedAccount(), observedAccount) ? "Verified snapshot available for import/reconfirmation." : "Offline manual editing; server eligibility and snapshot freshness are unverified."));
        PlanningStore.Snapshot s = store.snapshot(selectedAccount()); save.setEnabled(d != null && d.dirty && !d.pending && s.ready && !s.readOnly);
    }
    private void message(String value) { Draft d = draft(); if (d != null) d.message = value; status.setText(value); }
    private void showDetails() {
        Draft d = draft(); if (d == null) { detail.setText("No account selected."); totals.setText(""); return; }
        AccountPlan p = effective(d); List<String> ids = selectedIds();
        StringBuilder text = new StringBuilder();
        for (String id : ids) {
            QuestPlanEntry q = p.quests.get(id);
            text.append(q.name).append(" · ID ").append(q.stableQuestId).append(" · Snapshot ").append(Formatters.formatTimestamp(q.capturedAt))
                .append("\nExpiration (raw): ").append(q.rawExpiration == null || q.rawExpiration.isEmpty() ? "Not supplied" : q.rawExpiration)
                .append(" · ").append(q.metadataVersion).append("\nRewards: ").append(q.rewardsKnown ? (q.chooseOne ? "CHOOSE ONE " : "ALL ") + items(q.rewards) : "Not captured").append("\n");
            if (q.stale && isVerified() && Objects.equals(selectedAccount(), observedAccount)) {
                QuestPlanEntry current = observed.get(q.stableQuestId);
                text.append("Saved requirements per repeat: ").append(q.requirementsKnown ? items(q.requirements) : "Not captured").append("\n");
                if (current == null) text.append("Current list: absent or ambiguous; this snapshot remains saved.\n");
                else text.append("Current requirements per repeat: ").append(current.requirementsKnown ? items(current.requirements) : "Not captured")
                    .append("\nCurrent rewards: ").append(current.rewardsKnown ? (current.chooseOne ? "CHOOSE ONE " : "ALL ") + items(current.rewards) : "Not captured")
                    .append("\nCurrent availability: ").append(current.repeatable ? "Repeatable" : current.completed ? "Completed one-time" : "One-time")
                    .append(" · expiration (raw): ").append(current.rawExpiration).append("\nReview these changes before Refresh / reconfirm.\n");
            }
        }
        detail.setText(text.length() == 0 ? "Select saved quests for combined requirements; multiple selection avoids double-counting internal reservations." : text.toString());
        QuestPlanning.Totals t = QuestPlanning.totals(p, ids);
        StringBuilder sum = new StringBuilder(ids.isEmpty() ? "No plans selected" : t.readiness());
        for (Map.Entry<Integer, Long> e : t.demand.entrySet()) {
            int id = e.getKey(); sum.append("\n").append(name(id)).append(" (#").append(id).append("): need ").append(e.getValue())
                .append(" · reserved here ").append(t.reserved.get(id)).append(" · available to selection ").append(t.available.containsKey(id) ? t.available.get(id) : "Unconfirmed")
                .append(" · deficit ").append(t.missing.containsKey(id) ? t.missing.get(id) : "Unknown");
        }
        sum.append("\nManual held stock (account-wide):");
        if (p.held.isEmpty()) sum.append(" none confirmed");
        for (Map.Entry<Integer, ManualHeld> e : p.held.entrySet()) {
            long allocated = 0; for (Map<Integer, Long> r : p.reservations.values()) allocated = Math.addExact(allocated, r.getOrDefault(e.getKey(), 0L));
            sum.append("\n").append(name(e.getKey())).append(" (#").append(e.getKey()).append("): ").append(e.getValue().quantity)
                .append(" · unallocated ").append(e.getValue().quantity - allocated).append(" · confirmed ").append(Formatters.formatTimestamp(e.getValue().confirmedAt))
                .append(" · ").append(e.getValue().note == null ? "" : e.getValue().note);
        }
        totals.setText(sum.toString());
    }
    private String name(int id) { String value = names.apply(id); return value == null ? "Unknown item" : value; }
    private String items(Map<Integer, Long> values) { List<String> text = new ArrayList<>(); values.forEach((id, n) -> text.add(n + " × " + name(id) + " (#" + id + ")")); return text.isEmpty() ? "Observed empty" : String.join(", ", text); }
    private static JPanel label(String text, JComponent control) {
        JPanel p = new JPanel(new BorderLayout(0, 3)); JLabel l = new JLabel(text); l.setLabelFor(control); control.getAccessibleContext().setAccessibleName(text); p.add(l, BorderLayout.NORTH); p.add(control); return p;
    }
    private static JButton button(String text, String name, Runnable action) { JButton b = new JButton(text); b.setName(name); b.addActionListener(e -> action.run()); return b; }
    private final class PlanModel extends AbstractTableModel {
        final String[] columns = {"Quest", "Stable ID", "Repeats", "Status"};
        public int getRowCount() { return rows.size(); } public int getColumnCount() { return columns.length; }
        public String getColumnName(int c) { return columns[c]; }
        public Object getValueAt(int r, int c) { QuestPlanEntry q = rows.get(r); switch (c) {
            case 0: return q.name; case 1: return q.stableQuestId; case 2: return q.desiredRepeats;
            default: return q.stale ? "Changed / removed; reconfirm" : q.completed && !q.repeatable ? "Completed one-time" : q.requirementsKnown ? "Saved requirements; verify server" : "Requirements unknown";
        } }
    }
}
