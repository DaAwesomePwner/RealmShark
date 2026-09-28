package tomato.gui.quest;

import tomato.gui.kit.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.stats.Formatters;
import tomato.planning.PlanData;
import tomato.planning.PlanData.*;
import tomato.planning.PlanningStore;
import util.PropertiesManager;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.BooleanSupplier;

/**
 * Account-explicit manual planning: the Quests page's Planner (spec §6.5). Drafts and asynchronous completions stay with their
 * account. Presentation only: every number comes from QuestPlanning.totals, and validation, revision checks, the account rules and
 * Characters/plans.json stay PlanData's and PlanningStore's.
 * - Cards (the default): the All plans summary (totals over every plan of the account, so unreserved stock is counted once) and
 *   one painted card per plan (totals over that plan alone). Selecting a card selects that plan for the editors.
 * - Table: the plan table with its multi-selection, the detail and the combined totals, as before this view existed.
 *   Simple offers the other view in the ⋯ menu, Analyst a Cards/Table toggle; the choice persists ({@link #VIEW_KEY}).
 * - The table's selection is the one selection the editors use: a card selects its row, and switching to the cards keeps a
 *   single selected plan.
 * - The manual stock editor and the account-wide held values are in the "Manual stock" drawer (ui.collapse.quest-plan-stock).
 *   The "Release affected reservations" checkbox stays outside it, below the plan edits, because it also governs Set repeats and
 *   Refresh / reconfirm: a checked box must never be hidden.
 * - Cards rebuild only when their inputs change (the account, the draft's plan, the store revision, the observed quests); the
 *   700 ms poll re-reads the store but rebuilds nothing unless its revision changed.
 * EDT only: the models are built from the detached effective plan the table already shows.
 */
public final class QuestPlanPanel extends JPanel {
    static final String VIEW_KEY = "ui.quests.plan-view";
    private static final String NONE = "Select a known account…";
    private final PlanningStore store;
    private final IntFunction<String> names;
    private final JComboBox<String> account = new AccountList();
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
    private final BiConsumer<String, String> write;
    private final TileList<PlanCardModel> cards = new TileList<>("quest-plan-cards", new PlanCardRenderer(), PlanCardModel::entryId, PlanCardRenderer::accessibleName);
    private final Card summary;
    private final JTextArea summaryReadiness = text("", Type.body()), summaryBasis = text("Combined over every plan of this account, so stock is"
        + " counted once; each card counts its plan alone. Available stock is manually confirmed held stock only, never captured inventory.", Type.caption());
    private final EmptyState noAccount = new EmptyState("Select a planning account", "Plans are saved per account; choose one above to show its plans.", null);
    private final EmptyState empty = new EmptyState("No saved plans for this account", "Add quests from the Board with Add to account plan.", null);
    private final JTextArea heldValues = ContentStyle.wrappingText("");
    private final JPanel cardsView, tableView;
    private final SegmentedControl view = new SegmentedControl("quest-plan-view", "Cards", "Table");
    private final OverflowMenu overflow = new OverflowMenu("quest-plan-overflow");
    private final JMenuItem viewItem;
    private final Collapsible stockDrawer;
    private boolean cardsShown, syncing;
    private long observedVersion;
    private CardInputs cardInputs;
    private PlanCardModel.Summary shownSummary;
    private static final class Draft {
        AccountPlan plan; long revision; boolean dirty, pending, loaded; String message;
        Draft(PlanningStore.Snapshot s) { plan = s.plan(); revision = s.revision; message = s.status; loaded = s.ready; }
    }
    /** What the cards show; equal inputs rebuild nothing. {@code plan} compares by identity: every edit, save and reload replaces it. */
    private record CardInputs(String account, AccountPlan plan, long revision, long observed, boolean verified) {}
    public QuestPlanPanel(PlanningStore store, IntFunction<String> names) {
        this(store, names, DisplayModeModel.application(), PropertiesManager::getProperty, PropertiesManager::setProperties);
    }
    /** {@code mode}: Simple or Analyst (the view switch); {@code read}/{@code write}: the view preference ({@link #VIEW_KEY}). */
    QuestPlanPanel(PlanningStore store, IntFunction<String> names, DisplayModeModel mode, Function<String, String> read, BiConsumer<String, String> write) {
        super(new BorderLayout(0, 6)); this.store = store; this.names = names; this.write = write;
        setName("quest-plan-panel"); account.setName("quest-plan-account");
        status.setName("quest-plan-status"); status.setFocusable(false);
        detail.setName("quest-plan-detail"); totals.setName("quest-plan-totals");
        repeats.setName("quest-plan-repeat-count"); item.setName("quest-plan-item-id"); quantity.setName("quest-plan-quantity");
        note.setName("quest-plan-note"); release.setName("quest-plan-release-affected");
        account.getAccessibleContext().setAccessibleName("Account for manual quest plans");
        JPanel header = new JPanel(new BorderLayout(0, 6));
        JPanel scope = ContentStyle.controls(); scope.add(new JLabel("Planning account")); scope.add(account);
        view.getAccessibleContext().setAccessibleName("Planner view");
        view.onChange(index -> showCards(index == 0, true)); scope.add(view);
        viewItem = overflow.add("Table view", () -> showCards(!cardsShown, true)); viewItem.setName("quest-plan-view-item"); scope.add(overflow);
        header.add(scope, BorderLayout.NORTH);
        header.add(status, BorderLayout.CENTER);
        // Cards view: the All plans summary above one painted card per plan (or why there is none).
        noAccount.setName("quest-plan-no-account"); empty.setName("quest-plan-empty");
        summaryReadiness.setName("quest-plan-summary-readiness");
        summary = new Card(mode).title("All plans"); summary.setName("quest-plan-summary");
        summary.getAccessibleContext().setAccessibleName("All plans: combined requirements");
        cards.getAccessibleContext().setAccessibleName("Quest plans; select a card to edit its plan");
        cards.addListSelectionListener(e -> {
            PlanCardModel card = cards.getSelectedValue();
            if (!e.getValueIsAdjusting() && !syncing && !refreshing && card != null) selectPlan(card.entryId());
        });
        cardsView = KitLayouts.stack(Tokens.M, noAccount, empty, summary, cards); cardsView.setName("quest-plan-cards-view");
        // Table view: the plan table, its detail and the combined totals of the selected rows, unchanged.
        ContentStyle.table(table, ContentStyle.Density.COMFORTABLE); table.setName("quest-plan-table");
        table.getAccessibleContext().setAccessibleName("Saved quest plans; select multiple rows for combined totals");
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION); table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        for (int c = 0; c < table.getColumnCount(); c++) table.getColumnModel().getColumn(c).setPreferredWidth(c == 0 ? 220 : 170);
        table.setDefaultRenderer(Object.class, new ContentStyle.Cell());
        table.getSelectionModel().addListSelectionListener(e -> { if (!refreshing && !e.getValueIsAdjusting()) { showDetails(); followSelection(); } });
        tableView = new JPanel(); tableView.setLayout(new BoxLayout(tableView, BoxLayout.Y_AXIS)); tableView.setName("quest-plan-table-view");
        tableView.setAlignmentX(LEFT_ALIGNMENT); // as every body part: BoxLayout offsets parts whose alignments differ
        JScrollPane list = ContentStyle.tableScroll(table, 4); list.setColumnHeaderView(table.getTableHeader());
        list.setPreferredSize(new Dimension(650, 210)); tableView.add(list);
        tableView.add(detail); tableView.add(totals);
        JPanel body = new JPanel(); body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.add(fixed(cardsView)); body.add(tableView);
        JPanel edit = ContentStyle.controls(); edit.add(label("Desired repeats", repeats));
        edit.add(button("Set repeats", "quest-plan-repeats", () -> mutate(p -> {
            QuestPlanEntry q = one(p); q.desiredRepeats = ((Number) repeats.getValue()).longValue();
            if (release.isSelected()) p.reservations.remove(q.entryId);
        })));
        edit.add(button("Refresh / reconfirm selected", "quest-plan-reconfirm", this::reconfirm));
        edit.add(button("Remove selected plans", "quest-plan-remove", () -> mutate(p -> {
            for (String id : selectedIds()) { p.quests.remove(id); p.reservations.remove(id); }
        })));
        body.add(fixed(edit));
        // The release checkbox also governs Set repeats and Refresh / reconfirm, so it stays beside them, never hidden in the drawer.
        body.add(fixed(release));
        // The manual stock editor, in a drawer (collapsed by default, remembered): held stock and reservations are manual values.
        JPanel stock = ContentStyle.controls(); stock.add(label("Item ID", item)); stock.add(label("Quantity", quantity)); stock.add(label("Manual note", note));
        JPanel stockActions = ContentStyle.controls();
        stockActions.add(button("Confirm held quantity", "quest-plan-held", () -> mutate(p -> QuestPlanning.held(p, itemId(), number(), note.getText(), release.isSelected(), System.currentTimeMillis()))));
        stockActions.add(button("Set reservation for selected plan", "quest-plan-reserve", () -> mutate(p -> QuestPlanning.reserve(p, one(p).entryId, itemId(), number()))));
        stockActions.add(button("Release selected reservations", "quest-plan-release", () -> mutate(p -> { for (String id : selectedIds()) p.reservations.remove(id); })));
        stockActions.add(button("Release all reservations", "quest-plan-release-all", () -> mutate(p -> p.reservations.clear())));
        heldValues.setName("quest-plan-held-values");
        JPanel stockContent = KitLayouts.stack(Tokens.XS, stock, stockActions, heldValues,
            ContentStyle.wrappingText("Quantity 0 explicitly confirms zero held, or releases that reservation. Lowering stock or demand below reservations requires the release checkbox. Held quantities are account-wide manual estimates, including their confirmation time and note. No automatic allocation or consumption occurs."));
        stockContent.setName("quest-plan-stock-content");
        stockDrawer = new Collapsible("quest-plan-stock", "Manual stock", stockContent, false); stockDrawer.setName("quest-plan-stock");
        body.add(fixed(stockDrawer));
        JPanel actions = ContentStyle.controls(); actions.add(save);
        actions.add(button("Reload / discard draft", "quest-plan-reload", () -> {
            String key = selectedAccount(); Draft d = draft(); if (key == null || d == null || d.pending) return;
            if (d.dirty && JOptionPane.showConfirmDialog(this, "Discard this account's unsaved quest plan changes?", "Reload saved plan", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            drafts.put(key, new Draft(store.snapshot(key))); refresh();
        }));
        JScrollPane page = ContentStyle.page(header, body, actions); page.setName("quest-plan-scroll");
        add(page, BorderLayout.CENTER); installReveal(this);
        // Analyst: the Cards/Table toggle beside the account; Simple: the other view in the ⋯ menu.
        mode.bind(this, value -> {
            boolean analyst = value == DisplayModeModel.Mode.ANALYST;
            view.setVisible(analyst); overflow.setVisible(!analyst); scope.revalidate(); scope.repaint();
        });
        showCards(!"table".equals(read.apply(VIEW_KEY)), false);
        applyCards(null, null);
        account.addActionListener(e -> { if (!refreshing) refresh(); }); poll();
    }
    @Override public void addNotify() { super.addNotify(); poll.start(); poll(); }
    @Override public void removeNotify() { poll.stop(); super.removeNotify(); }
    public void knownAccounts(Collection<String> values) { known.addAll(values); poll(); }
    void verification(BooleanSupplier current) { scopeStillCurrent = current; }
    private boolean isVerified() { return verified && scopeStillCurrent.getAsBoolean(); }
    /** Current verified scope is required to import or reconfirm; offline editing needs no capture. */
    void observations(String accountKey, boolean current, List<QuestGUI.Quest> quests, long at, long generation) {
        observedAccount = accountKey; verified = current; observed.clear(); observedVersion++;
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
            message("Select the verified captured account on the Planner before adding a quest. A unique stable quest ID is required."); return;
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
        if (d == null) { message("Select a known account on the Planner first."); return; }
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
        AccountPlan shown = d == null ? null : effective(d);
        if (shown != null) rows.addAll(shown.quests.values()); model.fireTableDataChanged();
        for (int i = 0; i < rows.size(); i++) if (selected.contains(rows.get(i).entryId)) table.addRowSelectionInterval(table.convertRowIndexToView(i), table.convertRowIndexToView(i));
        if (table.getSelectedRowCount() == 0 && !rows.isEmpty()) table.setRowSelectionInterval(0, 0);
        applyCards(d, shown); // still refreshing: a rebuilt list's own selection events select no table row
        refreshing = false; followSelection(); showDetails(); updateStatus();
    }
    /**
     * Shows the cards or the table (the other stays in the tree, hidden). A user's choice is remembered. Keyboard focus inside the
     * view being hidden or on the ⋯ menu that switched it moves into the view being shown (the table; the plan cards, else the account
     * list when no card shows); focus anywhere else (the account list, the Analyst toggle) stays where it is.
     */
    private void showCards(boolean show, boolean remember) {
        boolean refocus = QuestGUI.holdsFocus(show ? tableView : cardsView, overflow, overflow.menu());
        cardsShown = show;
        if (remember) write.accept(VIEW_KEY, show ? "cards" : "table");
        cardsView.setVisible(show); tableView.setVisible(!show);
        view.setSelected(show ? 0 : 1); viewItem.setText(show ? "Table view" : "Cards view");
        List<String> ids = selectedIds();
        if (show && ids.size() > 1) selectPlan(ids.get(0)); // the cards edit one plan at a time
        revalidate(); repaint();
        if (refocus) { JComponent target = !show ? table : cards.isVisible() ? cards : account; target.requestFocusInWindow(); }
    }
    /** Selects one plan's table row, the editors' selection; the table's listener updates the detail and the selected card. */
    private void selectPlan(String entryId) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).entryId.equals(entryId)) {
            int view = table.convertRowIndexToView(i); if (view >= 0) table.setRowSelectionInterval(view, view); return;
        }
    }
    /** The selected card is the table's first selected plan; set without scrolling, so the page stays where the user left it. */
    private void followSelection() {
        List<String> ids = selectedIds(); List<PlanCardModel> items = cards.items(); int index = -1;
        for (int i = 0; !ids.isEmpty() && i < items.size(); i++) if (items.get(i).entryId().equals(ids.get(0))) { index = i; break; }
        syncing = true;
        try { if (index < 0) cards.clearSelection(); else if (cards.getSelectedIndex() != index) cards.setSelectedIndex(index); }
        finally { syncing = false; }
    }
    /** Rebuilds the cards, the All plans summary and the held values from the effective plan, only when their inputs changed. */
    private void applyCards(Draft d, AccountPlan shown) {
        CardInputs next = new CardInputs(selectedAccount(), d == null ? null : d.plan, d == null ? -1 : d.revision, observedVersion,
            isVerified() && Objects.equals(selectedAccount(), observedAccount));
        if (next.equals(cardInputs)) return;
        cardInputs = next;
        List<PlanCardModel> models = shown == null ? List.of() : PlanCardModel.cards(shown, names);
        cards.setItems(models);
        applySummary(shown == null ? null : PlanCardModel.summary(shown, names));
        heldValues.setText(shown == null ? "Held stock (account-wide, manual): select an account first." : heldText(shown));
        noAccount.setVisible(shown == null); empty.setVisible(shown != null && models.isEmpty());
        summary.setVisible(!models.isEmpty()); cards.setVisible(!models.isEmpty());
        cardsView.revalidate(); cardsView.repaint();
    }
    /** The All plans card: the combined readiness and one row per item; rebuilt only when the summary changed. */
    private void applySummary(PlanCardModel.Summary all) {
        if (Objects.equals(all, shownSummary)) return;
        shownSummary = all;
        List<JComponent> parts = new ArrayList<>(List.of(summaryReadiness, summaryBasis));
        if (all != null) {
            summary.header().setCount(all.plans() == 1 ? "1 plan" : DisplayFormat.formatInteger(all.plans()) + " plans");
            summaryReadiness.setText(all.readiness());
            for (PlanCardModel.Row row : all.rows()) parts.add(summaryRow(row));
        }
        summary.body(KitLayouts.stack(Tokens.S, parts.toArray(new JComponent[0])));
    }
    /** One All plans row: the item and its numbers, then its bar; unknown stock says so and draws no bar (an empty track reads as 0%). */
    private static JComponent summaryRow(PlanCardModel.Row row) {
        String name = "quest-plan-summary-" + row.itemId();
        JTextArea numbers = text(row.label() + ": " + row.numbers(), Type.body()); numbers.setName(name);
        if (!row.stockKnown()) return numbers;
        SegmentBar bar = new SegmentBar(); bar.setName(name + "-bar");
        bar.set(row.reserved(), row.covered(), row.missing(), row.need());
        bar.getAccessibleContext().setAccessibleName(row.name() + ": reserved, covered and missing of the need");
        return KitLayouts.stack(Tokens.XS, numbers, bar);
    }
    /** The account-wide held stock: DisplayValue's manual wording, what reservations leave unallocated, when it was confirmed, the note. */
    private String heldText(AccountPlan p) {
        StringBuilder text = new StringBuilder("Held stock (account-wide, manual):");
        if (p.held.isEmpty()) text.append(" none confirmed");
        for (Map.Entry<Integer, ManualHeld> e : p.held.entrySet()) {
            long allocated = 0; for (Map<Integer, Long> r : p.reservations.values()) allocated = Math.addExact(allocated, r.getOrDefault(e.getKey(), 0L));
            ManualHeld held = e.getValue(); String confirmed = Formatters.formatTimestamp(held.confirmedAt);
            text.append("\n").append(label(e.getKey())).append(": ")
                .append(DisplayValue.manual(DisplayFormat.formatInteger(held.quantity), "Confirmed " + confirmed).display())
                .append(" · unallocated ").append(DisplayFormat.formatInteger(held.quantity - allocated)).append(" · confirmed ").append(confirmed)
                .append(held.note == null || held.note.isEmpty() ? "" : " · " + held.note);
        }
        return text.toString();
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
            int id = e.getKey(); sum.append("\n").append(label(id)).append(": need ").append(e.getValue())
                .append(" · reserved here ").append(t.reserved.get(id)).append(" · available to selection ").append(t.available.containsKey(id) ? t.available.get(id) : "Unconfirmed")
                .append(" · deficit ").append(t.missing.containsKey(id) ? t.missing.get(id) : "Unknown");
        }
        sum.append("\nManual held stock (account-wide):");
        if (p.held.isEmpty()) sum.append(" none confirmed");
        for (Map.Entry<Integer, ManualHeld> e : p.held.entrySet()) {
            long allocated = 0; for (Map<Integer, Long> r : p.reservations.values()) allocated = Math.addExact(allocated, r.getOrDefault(e.getKey(), 0L));
            sum.append("\n").append(label(e.getKey())).append(": ").append(e.getValue().quantity)
                .append(" · unallocated ").append(e.getValue().quantity - allocated).append(" · confirmed ").append(Formatters.formatTimestamp(e.getValue().confirmedAt))
                .append(" · ").append(e.getValue().note == null ? "" : e.getValue().note);
        }
        totals.setText(sum.toString());
    }
    /** "Name (#id)", or the name alone when it already ends with its id ("Unknown item #9999"): PlanCardModel.label. */
    private String label(int id) { return PlanCardModel.label(PlanCardModel.name(names, id), id); }
    private String items(Map<Integer, Long> values) { List<String> text = new ArrayList<>(); values.forEach((id, n) -> text.add(n + " × " + label(id))); return text.isEmpty() ? "Observed empty" : String.join(", ", text); }
    private static JPanel label(String text, JComponent control) {
        JPanel p = new JPanel(new BorderLayout(0, 3)); JLabel l = new JLabel(text); l.setLabelFor(control); control.getAccessibleContext().setAccessibleName(text); p.add(l, BorderLayout.NORTH); p.add(control); return p;
    }
    private static JButton button(String text, String name, Runnable action) { JButton b = new JButton(text); b.setName(name); b.addActionListener(e -> action.run()); return b; }
    /**
     * A body part at exactly its preferred height: the page sizes its body from minimum heights, so a shorter minimum would clip the
     * cards, and a short page leaves its spare height below the controls instead of spreading them apart.
     */
    private static JPanel fixed(JComponent content) {
        JPanel holder = new JPanel(new BorderLayout()) {
            @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        holder.setOpaque(false); holder.setAlignmentX(LEFT_ALIGNMENT); holder.add(content); return holder;
    }
    /** Wrapping, read-only text in a kit font role; not focusable, so rebuilding the summary never drops the focus. */
    private static JTextArea text(String value, Font font) {
        JTextArea area = ContentStyle.wrappingText(value); ContentStyle.font(area, font); area.setFocusable(false); return area;
    }
    private static void installReveal(Component value) {
        if (value instanceof JComponent && (value instanceof AbstractButton || value instanceof JComboBox || value instanceof javax.swing.text.JTextComponent
                || value instanceof JTable || value instanceof JList)) {
            JComponent c = (JComponent) value;
            c.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusGained(java.awt.event.FocusEvent e) {
                    if (c instanceof JTable) ContentStyle.reveal(c, ((JTable)c).getCellRect(Math.max(0, ((JTable)c).getSelectedRow()), 0, true));
                    else if (c instanceof JList) { // the plan cards: the selected card, or the first
                        int index = Math.max(0, ((JList<?>)c).getSelectedIndex()); Rectangle cell = ((JList<?>)c).getCellBounds(index, index);
                        if (cell != null) ContentStyle.reveal(c, cell);
                    }
                    else ContentStyle.reveal(c, new Rectangle(0, 0, c.getWidth(), c instanceof JTextArea
                        ? Math.min(c.getHeight(), c.getFontMetrics(c.getFont()).getHeight() + 12) : c.getHeight()));
                }
            });
            if (c instanceof JTextArea) ((JTextArea)c).addCaretListener(e -> {
                if (c.isFocusOwner()) SwingUtilities.invokeLater(() -> {
                    try { if (c.isFocusOwner()) ContentStyle.reveal(c, ((JTextArea)c).modelToView(((JTextArea)c).getCaretPosition())); }
                    catch (javax.swing.text.BadLocationException invalid) { throw new IllegalStateException(invalid); }
                });
            });
        }
        if (value instanceof Container) for (Component child : ((Container)value).getComponents()) installReveal(child);
    }
    /**
     * The account list never asks for more width than its row can give: a 64-character account key is cut by the renderer instead
     * of pushing the list and its drop-down arrow past the row's edge, and the tooltip keeps the whole selected key (the accessible
     * description falls back to it). The items, the selection and the popup's full-width entries are unchanged.
     */
    private static final class AccountList extends JComboBox<String> {
        AccountList() { super(new String[]{NONE}); describe(); }
        @Override public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            int room = room();
            return room > 0 && size.width > room ? new Dimension(room, size.height) : size;
        }
        @Override protected void selectedItemChanged() { super.selectedItemChanged(); describe(); }
        private void describe() { Object value = getSelectedItem(); setToolTipText(value == null || NONE.equals(value) ? null : value.toString()); }
        /** The row's width inside its insets and its flow gaps (as ContentStyle.controls wraps it); 0 before anything is laid out. */
        private int room() {
            Container row = getParent();
            if (row == null) return 0;
            int width = row.getWidth();
            if (width <= 0 && row.getParent() != null) { Insets outer = row.getParent().getInsets(); width = row.getParent().getWidth() - outer.left - outer.right; }
            Insets insets = row.getInsets();
            int gap = row.getLayout() instanceof FlowLayout ? ((FlowLayout) row.getLayout()).getHgap() : 0;
            return width <= 0 ? 0 : Math.max(1, width - insets.left - insets.right - 2 * gap);
        }
    }
    private final class PlanModel extends AbstractTableModel {
        final String[] columns = {"Quest", "Stable ID", "Repeats", "Status"};
        public int getRowCount() { return rows.size(); } public int getColumnCount() { return columns.length; }
        public String getColumnName(int c) { return columns[c]; }
        public Object getValueAt(int r, int c) { QuestPlanEntry q = rows.get(r); switch (c) {
            case 0: return q.name; case 1: return q.stableQuestId; case 2: return q.desiredRepeats;
            default: return PlanCardModel.status(q);
        } }
    }
}
