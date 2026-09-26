package tomato.gui.character;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournal.*;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.modern.ContentStyle;
import tomato.gui.stats.Formatters;
import tomato.planning.*;
import tomato.realmshark.enums.CharacterClass;

/** Account selection is explicit even when the roster auto-selects its first character. */
public final class CharacterPlanningPanel extends JPanel {
    private final PlanningStore store;
    private final JComboBox<Choice<String>> account = new JComboBox<>(), character = new JComboBox<>();
    private final JComboBox<Choice<Integer>> clazz = new JComboBox<>();
    private final JComboBox<String> stat = new JComboBox<>(CharacterJournal.STATS), exaltStat = new JComboBox<>(CharacterJournal.STATS);
    private final JSpinner target = new JSpinner(new SpinnerNumberModel(0, 0, 100000, 1)), tier = new JSpinner(new SpinnerNumberModel(1, 1, 5, 1));
    private final JTextField search = new JTextField(15);
    private final JTextArea status = ContentStyle.wrappingText("Select a known account to edit offline goals. No account is selected automatically.");
    private final JTextArea metadataText = ContentStyle.wrappingText("");
    private final JButton save = new JButton("Save goals"), reload = new JButton("Reload saved goals"), remove = new JButton("Remove selected goal");
    private final JButton pin = new JButton("Set stat target"), max = new JButton("Pin max"), exalt = new JButton("Set tier"), next = new JButton("Pin next tier");
    private final DefaultTableModel model = new DefaultTableModel(new String[]{"Goal", "Stat", "Fixed target", "Remaining", "Status", "Metadata", "Observed", "Updated"}, 0) {
        public boolean isCellEditable(int row, int col) { return false; }
        public Class<?> getColumnClass(int col) { return col == 3 ? Integer.class : String.class; }
    };
    private final JTable table = new JTable(model);
    private final List<String> rowKeys = new ArrayList<>();
    private final Map<String, Draft> drafts = new HashMap<>();
    private List<CharacterRecord> records = Collections.emptyList();
    private List<AccountRecord> accounts = Collections.emptyList();
    private RosterDefinitions definitions = RosterDefinitions.empty();
    private PlanningMetadata metadata = PlanningMetadata.unavailable();
    private List<CharacterRecord> sourceRecords;
    private List<AccountRecord> sourceAccounts;
    private String storeStamp;
    private boolean refreshing;
    private static final class Draft {
        PlanData.AccountPlan plan; long revision; boolean dirty, saving; String message;
        Draft(PlanningStore.Snapshot saved) { plan = saved.plan(); revision = saved.revision; message = saved.status; }
    }
    public CharacterPlanningPanel(PlanningStore store) {
        super(new BorderLayout(0, 6)); this.store = Objects.requireNonNull(store);
        account.addItem(new Choice<>(null, "Select account…"));
        for (JComboBox<?> box : new JComboBox<?>[]{account, character, clazz}) box.setMaximumRowCount(12);
        account.setPrototypeDisplayValue(new Choice<>(null, "Account name · 000000"));
        character.setPrototypeDisplayValue(new Choice<>(null, "Character class #000000"));
        clazz.setPrototypeDisplayValue(new Choice<>(null, "Character class"));
        JPanel controls = new JPanel(); controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        JPanel scope = ContentStyle.controls(); scope.add(new JLabel("Planning account")); scope.add(account); scope.add(search); controls.add(scope);
        JPanel stats = ContentStyle.controls(); stats.add(character); stats.add(stat); stats.add(new JLabel("Base target")); stats.add(target); stats.add(pin); stats.add(max); controls.add(stats);
        JPanel exalts = ContentStyle.controls(); exalts.add(clazz); exalts.add(exaltStat); exalts.add(new JLabel("Exalt tier")); exalts.add(tier); exalts.add(exalt); exalts.add(next); controls.add(exalts);
        controls.add(metadataText); add(controls, BorderLayout.NORTH);
        ContentStyle.table(table, ContentStyle.Density.DENSE); table.setAutoCreateRowSorter(true); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); int[] widths = {180, 90, 105, 100, 220, 200, 160, 160};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getColumnModel().getColumn(3).setCellRenderer(new ContentStyle.Cell() { protected void setValue(Object value) { setText(value == null ? "Unknown" : value.toString()); } });
        table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(3, SortOrder.ASCENDING)));
        table.getTableHeader().setToolTipText("Remaining is standard potions for stat targets; dungeon completions for exalt tiers. Sort ascending for near-complete targets. Unknown is never zero.");
        add(ContentStyle.tableScroll(table, 3));
        JPanel footer = new JPanel(new BorderLayout(0, 4)); JPanel actions = ContentStyle.controls();
        actions.add(save); actions.add(reload); actions.add(remove); JButton details = new JButton("Full goal details"); actions.add(details);
        footer.add(actions, BorderLayout.NORTH); footer.add(status); add(footer, BorderLayout.SOUTH);
        JComponent[] components = {account, character, clazz, stat, exaltStat, target, tier, search, table, save};
        String[] names = {"Goal account", "Goal character", "Exalt goal class", "Stat target stat", "Exalt target stat", "Base stat target", "Fixed exalt tier", "Search saved goals and unknowns", "Saved character and exalt goals", "Save goals"};
        for (int i = 0; i < components.length; i++) { components[i].setName("planning-" + i); components[i].getAccessibleContext().setAccessibleName(names[i]); }
        account.addActionListener(e -> { if (!refreshing) { choices(); render(); } });
        character.addActionListener(e -> suggestCap()); stat.addActionListener(e -> suggestCap());
        pin.addActionListener(e -> setCharacter(false)); max.addActionListener(e -> setCharacter(true));
        exalt.addActionListener(e -> setExalt(false)); next.addActionListener(e -> setExalt(true));
        save.addActionListener(e -> save());
        reload.addActionListener(e -> {
            String key = selected(account); if (key == null) return; Draft draft = drafts.get(key);
            if (draft != null && draft.saving) return;
            if (draft != null && draft.dirty && JOptionPane.showConfirmDialog(this, "Discard this account's unsaved goal edits and load the saved plan?", "Reload saved goals", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            drafts.remove(key); render();
        });
        remove.addActionListener(e -> {
            Draft draft = editable(); int row = table.getSelectedRow(); if (draft == null || row < 0) return;
            String key = rowKeys.get(table.convertRowIndexToModel(row));
            if (key.startsWith("C:")) draft.plan.characterGoals.remove(key.substring(2)); else draft.plan.exaltGoals.remove(key.substring(2));
            dirty(draft);
        });
        details.addActionListener(e -> showDetails());
        table.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "goal-details"); table.getActionMap().put("goal-details", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { showDetails(); } });
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { render(); } public void removeUpdate(DocumentEvent e) { render(); } public void changedUpdate(DocumentEvent e) { render(); }
        });
        CharacterFocusSupport.install(this); render();
    }
    public void refresh(List<CharacterRecord> records, List<AccountRecord> accounts, RosterDefinitions definitions) {
        PlanningMetadata nextMetadata = PlanningMetadata.current();
        PlanningStore.Snapshot snapshot = store.snapshot(selected(account));
        String stamp = selected(account) + ":" + snapshot.revision + ":" + snapshot.ready + ":" + snapshot.readOnly + ":" + snapshot.status;
        if (sourceRecords == records && sourceAccounts == accounts && this.definitions == definitions && nextMetadata == metadata && Objects.equals(stamp, storeStamp)) return;
        sourceRecords = records; sourceAccounts = accounts; storeStamp = stamp;
        this.records = new ArrayList<>(records); this.accounts = new ArrayList<>(accounts); this.definitions = definitions; metadata = nextMetadata;
        String selected = selected(account); refreshing = true;
        account.removeAllItems(); account.addItem(new Choice<>(null, "Select account…"));
        Map<String, String> known = new LinkedHashMap<>();
        for (AccountRecord a : accounts) known.put(a.key, Objects.toString(a.name, "Account") + " · " + shortKey(a.key));
        for (CharacterRecord r : records) known.putIfAbsent(r.account, "Account · " + shortKey(r.account));
        for (String key : store.accounts()) known.putIfAbsent(key, "Saved planning account · " + shortKey(key));
        known.forEach((key, name) -> { Choice<String> item = new Choice<>(key, name); account.addItem(item); if (key.equals(selected)) account.setSelectedItem(item); });
        refreshing = false; choices(); render();
    }
    private void choices() {
        String selectedCharacter = selected(character), key = selected(account); Integer selectedClass = selected(clazz); refreshing = true;
        character.removeAllItems(); clazz.removeAllItems(); Set<Integer> classes = new TreeSet<>();
        for (CharacterRecord r : records) if (Objects.equals(r.account, key)) {
            Choice<String> item = new Choice<>(r.key, className(r.classId) + " #" + r.characterId); character.addItem(item);
            if (r.key.equals(selectedCharacter)) character.setSelectedItem(item); classes.add(r.classId);
        }
        for (AccountRecord a : accounts) if (Objects.equals(a.key, key)) classes.addAll(a.exalts.keySet());
        if (key != null) { Draft draft = draft(); if (draft != null) for (PlanData.ExaltGoal g : draft.plan.exaltGoals.values()) classes.add(g.classId); }
        for (int id : classes) { Choice<Integer> item = new Choice<>(id, className(id)); clazz.addItem(item); if (Objects.equals(id, selectedClass)) clazz.setSelectedItem(item); }
        refreshing = false; if (!Objects.equals(selectedCharacter, selected(character))) suggestCap();
    }
    private void suggestCap() {
        if (refreshing) return; CharacterRecord r = record(selected(character)); if (r == null) return;
        Integer cap = definitions.cap(r.classId, stat.getSelectedIndex()); if (cap != null && cap <= 100000) target.setValue(cap);
    }
    private Draft draft() {
        String key = selected(account); if (key == null) return null;
        PlanningStore.Snapshot saved = store.snapshot(key); if (!saved.ready) return null;
        Draft result = drafts.get(key);
        if (result == null || (!result.dirty && !result.saving && result.revision != saved.revision)) { result = new Draft(saved); drafts.put(key, result); }
        return result;
    }
    private Draft editable() {
        String key = selected(account); Draft draft = draft();
        return key == null || draft == null || draft.saving || store.snapshot(key).readOnly ? null : draft;
    }
    private void dirty(Draft draft) { draft.dirty = true; draft.message = "Unsaved goal edits retained for this account. Save goals to persist."; render(); }
    private void setCharacter(boolean maximum) {
        Draft draft = editable(); CharacterRecord r = record(selected(character)); if (draft == null || r == null) return;
        try {
            target.commitEdit(); Integer cap = definitions.cap(r.classId, stat.getSelectedIndex());
            if (maximum && cap == null) throw new IllegalArgumentException("Cap unavailable; max target cannot be verified");
            CharacterGoals.pinCharacter(draft.plan, r, stat.getSelectedIndex(), maximum ? cap : ((Number)target.getValue()).intValue(), definitions, System.currentTimeMillis()); dirty(draft);
        } catch (Exception invalid) { status.setText(Objects.toString(invalid.getMessage(), "Invalid target")); }
    }
    private void setExalt(boolean nextTier) {
        Draft draft = editable(); Integer classId = selected(clazz); if (draft == null || classId == null) return;
        try { tier.commitEdit(); CharacterGoals.pinExalt(draft.plan, classId, exaltStat.getSelectedIndex(), nextTier ? CharacterGoals.nextTier(count(classId, exaltStat.getSelectedIndex())) : ((Number)tier.getValue()).intValue(), metadata, System.currentTimeMillis()); dirty(draft); }
        catch (Exception invalid) { status.setText(Objects.toString(invalid.getMessage(), "Invalid tier")); }
    }
    private void save() {
        final String key = selected(account); final Draft draft = editable(); if (draft == null || !draft.dirty) return;
        draft.saving = true; draft.message = "Saving goals…"; render();
        store.update(key, draft.revision, draft.plan).whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
            draft.saving = false;
            if (failure != null) draft.message = "Save failed; edits retained. Retry Save goals.";
            else { draft.message = result.message; if (result.saved) { draft.revision = result.revision; draft.dirty = false; } }
            if (Objects.equals(key, selected(account)) && drafts.get(key) == draft) render();
        }));
    }
    private void render() {
        if (refreshing) return; Draft draft = draft();
        String selectedGoal = table.getSelectedRow() < 0 ? null : rowKeys.get(table.convertRowIndexToModel(table.getSelectedRow()));
        model.setRowCount(0); rowKeys.clear();
        boolean enabled = editable() != null;
        for (JComponent control : new JComponent[]{pin, max, exalt, next, remove, character, clazz, stat, exaltStat, target, tier}) control.setEnabled(enabled);
        save.setEnabled(enabled && draft.dirty); reload.setEnabled(draft != null && !draft.saving);
        metadataText.setText(metadata.status + "\nFixed targets never advance automatically. Remaining: standard potions (+5 Life/Mana, +1 others) or exalt completions.");
        if (draft == null) { status.setText(selected(account) == null ? "Select a known account to edit offline goals. No account is selected automatically." : store.snapshot(selected(account)).status); return; }
        PlanningStore.Snapshot stored = store.snapshot(selected(account));
        status.setText(stored.readOnly ? stored.status : draft.message + (draft.dirty && stored.revision != draft.revision ? " Another editor changed this account; reload saved goals before reapplying edits." : ""));
        for (Map.Entry<String, PlanData.CharacterGoal> e : draft.plan.characterGoals.entrySet()) {
            PlanData.CharacterGoal g = e.getValue(); CharacterRecord r = record(g.characterKey);
            CharacterGoals.Progress p = r == null ? null : CharacterGoals.character(g, r, definitions);
            add("C:" + e.getKey(), new Object[]{r == null ? "Unavailable character " + g.characterKey : className(r.classId) + " #" + r.characterId, CharacterJournal.STATS[g.statIndex], g.targetBaseValue + " base", p == null ? null : p.remaining,
                p == null ? "Snapshot unavailable" : p.state, p == null || p.metadataChanged ? "Changed / unavailable; reconfirm target" : "Matches local cap metadata", r == null ? "Unknown" : date(r.lastSeen), date(g.updatedAt)});
        }
        for (Map.Entry<String, PlanData.ExaltGoal> e : draft.plan.exaltGoals.entrySet()) {
            PlanData.ExaltGoal g = e.getValue(); CharacterGoals.Progress p = CharacterGoals.exalt(g, count(g.classId, g.statIndex), metadata);
            add("E:" + e.getKey(), new Object[]{className(g.classId) + " · account exalt", CharacterJournal.STATS[g.statIndex], "Tier " + g.targetTier + " (" + PlanningMetadata.threshold(g.targetTier) + ")", p.remaining,
                p.state + (metadata.dungeons(g.statIndex).isEmpty() ? " · Dungeon mapping unavailable" : " · " + String.join(", ", metadata.dungeons(g.statIndex))), p.metadataChanged ? "Changed; reconfirm target" : "Matches local metadata", date(exaltSeen()), date(g.updatedAt)});
        }
        if (selectedGoal != null) { int row = rowKeys.indexOf(selectedGoal); if (row >= 0) { int view = table.convertRowIndexToView(row); table.setRowSelectionInterval(view, view); } }
    }
    private void add(String key, Object[] values) {
        String needle = search.getText().trim().toLowerCase(Locale.ROOT); String text = Arrays.toString(values).toLowerCase(Locale.ROOT);
        if (!needle.isEmpty() && !text.contains(needle)) return; rowKeys.add(key); model.addRow(values);
    }
    private void showDetails() {
        int row = table.getSelectedRow(); if (row < 0) return; row = table.convertRowIndexToModel(row);
        StringBuilder text = new StringBuilder(); for (int i = 0; i < model.getColumnCount(); i++) text.append(model.getColumnName(i)).append(": ").append(Objects.toString(model.getValueAt(row, i), "Unknown")).append('\n');
        Draft draft = draft(); String key = rowKeys.get(row); String version = key.startsWith("C:") ? draft.plan.characterGoals.get(key.substring(2)).metadataVersion : draft.plan.exaltGoals.get(key.substring(2)).metadataVersion;
        text.append("Saved definition version: ").append(version).append("\n").append(metadata.status);
        JTextArea full = new JTextArea(text.toString(), 14, 50); full.setEditable(false); full.setLineWrap(true); full.setWrapStyleWord(true); full.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(full), "Full goal details · select text to copy", JOptionPane.PLAIN_MESSAGE);
    }
    private CharacterRecord record(String key) { for (CharacterRecord r : records) if (r.key.equals(key)) return r; return null; }
    private Integer count(int classId, int stat) { for (AccountRecord a : accounts) if (Objects.equals(a.key, selected(account))) { int[] values = a.exalts.get(classId); return values == null || values.length <= CharacterJournal.EXALT_ORDER[stat] ? null : values[CharacterJournal.EXALT_ORDER[stat]]; } return null; }
    private long exaltSeen() { for (AccountRecord a : accounts) if (Objects.equals(a.key, selected(account))) return a.exaltSeen; return 0; }
    private static String className(int id) { return Objects.toString(CharacterClass.getName(id), "Class #" + id); }
    private static String shortKey(String key) { return key.substring(0, Math.min(6, key.length())); }
    private static String date(long at) { return at <= 0 ? "Unknown" : Formatters.formatTimestamp(at); }
    private static <T> T selected(JComboBox<Choice<T>> box) { Choice<T> item = (Choice<T>)box.getSelectedItem(); return item == null ? null : item.key; }
    private static final class Choice<T> { final T key; final String label; Choice(T key, String label) { this.key = key; this.label = label; } public String toString() { return label; } }
}
