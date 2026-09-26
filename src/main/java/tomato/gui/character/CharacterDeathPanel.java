package tomato.gui.character;

import java.awt.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import packets.packetcapture.logger.ActivityJournal;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.CharacterJournal.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.route.*;
import tomato.gui.stats.Formatters;
import tomato.history.*;
import tomato.history.link.VisitRef;

/** Optional manual occurrence and association. Mark time is never relabelled as actual death time. */
public final class CharacterDeathPanel extends JPanel {
    private final CharacterJournal journal;
    private final JTextField occurred = new JTextField(24);
    private final JTextArea notes = new JTextArea(3, 28), status = ContentStyle.wrappingText("Select a character"), link = ContentStyle.wrappingText("No manual run association");
    private final JButton save = new JButton("Save death annotation"), choose = new JButton("Choose saved run…"), clear = new JButton("Remove run link"), open = new JButton("Open exact run");
    private CharacterRecord record;
    private VisitRef selectedVisit;
    private Navigator navigator;
    private final Map<String, Edit> drafts = new HashMap<>();
    private static final class Edit { String occurred, notes; VisitRef visit; }
    public CharacterDeathPanel(CharacterJournal journal) {
        super(new BorderLayout(0, 6)); this.journal = journal;
        JPanel top = new JPanel(); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(ContentStyle.wrappingText("Optional manual death record. Restoring alive preserves this annotation; a run association does not establish the cause of death."));
        JPanel time = ContentStyle.controls(); time.add(new JLabel("Occurred at (UTC, optional)")); time.add(occurred); top.add(time); top.add(link);
        occurred.setToolTipText("ISO instant, for example 2026-09-26T18:30:00Z; blank means unknown"); occurred.getAccessibleContext().setAccessibleName("Manual death occurrence time in UTC; optional");
        notes.setLineWrap(true); notes.setWrapStyleWord(true); notes.getAccessibleContext().setAccessibleName("Manual death notes");
        occurred.setName("death-occurred"); notes.setName("death-notes"); link.setName("death-run-link");
        add(top, BorderLayout.NORTH); add(new JScrollPane(notes));
        JPanel footer = new JPanel(new BorderLayout(0, 4)), actions = ContentStyle.controls(); actions.add(choose); actions.add(clear); actions.add(open); actions.add(save);
        footer.add(actions, BorderLayout.NORTH); footer.add(status); add(footer, BorderLayout.SOUTH);
        clear.addActionListener(e -> { selectedVisit = null; updateLink(); }); open.addActionListener(e -> openRun());
        choose.addActionListener(e -> pickRun()); save.addActionListener(e -> save()); showRecord(null);
        javax.swing.event.DocumentListener edits = new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { draftStatus(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { draftStatus(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { draftStatus(); }
        };
        occurred.getDocument().addDocumentListener(edits); notes.getDocument().addDocumentListener(edits);
        CharacterFocusSupport.install(this);
    }
    public void bindNavigator(Navigator navigator) { this.navigator = navigator; updateLink(); }
    public void showRecord(CharacterRecord next) {
        boolean changed = !Objects.equals(record == null ? null : record.key, next == null ? null : next.key);
        if (changed && record != null) { Edit edit = new Edit(); edit.occurred = occurred.getText(); edit.notes = notes.getText(); edit.visit = selectedVisit; drafts.put(record.key, edit); }
        record = next;
        if (changed || next == null) {
            Edit draft = next == null ? null : drafts.get(next.key); DeathAnnotation annotation = next == null ? null : next.deathAnnotation;
            occurred.setText(draft != null ? draft.occurred : annotation == null || annotation.occurredAt == null ? "" : Instant.ofEpochMilli(annotation.occurredAt).toString());
            notes.setText(draft != null ? draft.notes : annotation == null ? "" : annotation.notes);
            selectedVisit = draft != null ? draft.visit : annotation == null ? null : annotation.visit;
        }
        for (JComponent control : new JComponent[]{occurred, notes, save, choose}) control.setEnabled(next != null);
        status.setText(next == null ? "Select a character" : "Marked manually: " + date(next.deathAnnotation == null ? next.diedAt : next.deathAnnotation.markedAt)
            + " · Annotation edited: " + date(next.deathAnnotation == null ? 0 : next.deathAnnotation.editedAt) + "\n" + journal.storageStatus());
        updateLink();
        draftStatus();
    }
    private void draftStatus() {
        if (record == null) return; DeathAnnotation a = record.deathAnnotation;
        String previousTime = a == null || a.occurredAt == null ? "" : Instant.ofEpochMilli(a.occurredAt).toString();
        if (!Objects.equals(previousTime, occurred.getText()) || !Objects.equals(a == null ? "" : Objects.toString(a.notes, ""), notes.getText()) || !Objects.equals(a == null ? null : a.visit, selectedVisit))
            status.setText("Unsaved manual annotation; use Save death annotation. Draft stays with this character while this window remains open.");
    }
    public static Long parseOccurred(String text) {
        if (text.trim().isEmpty()) return null;
        long value = Instant.parse(text.trim()).toEpochMilli(); if (value <= 0) throw new IllegalArgumentException("Occurrence must be after the Unix epoch"); return value;
    }
    private void save() {
        if (record == null) return;
        try {
            DeathAnnotation annotation = new DeathAnnotation(); annotation.occurredAt = parseOccurred(occurred.getText()); annotation.notes = notes.getText(); annotation.visit = selectedVisit;
            annotation.markedAt = record.deathAnnotation == null ? record.diedAt : record.deathAnnotation.markedAt;
            journal.annotateDeath(record.key, annotation); status.setText(journal.storageStatus());
        } catch (RuntimeException invalid) { status.setText("Annotation not saved. Use an ISO UTC instant such as 2026-09-26T18:30:00Z, or leave it blank. " + invalid.getMessage()); }
    }
    private Navigator navigation() { return navigator == null ? Navigator.current() : navigator; }
    private void updateLink() {
        link.setText(selectedVisit == null ? "No manual run association" : "Manual run reference: " + selectedVisit + "\nOpen resolves this exact saved visit; removed or unavailable history is not replaced by a similar run.");
        clear.setEnabled(selectedVisit != null); open.setEnabled(selectedVisit != null);
        draftStatus();
    }
    private void openRun() {
        if (selectedVisit != null && !navigation().open(Route.to(Destination.RUNS).withVisit(selectedVisit))) status.setText("The saved run cannot be opened here. The manual reference is retained; remove it explicitly if unwanted.");
    }
    private void pickRun() {
        final SessionStore history = AppHistory.store(); if (history == null) { status.setText("Saved run history is unavailable. Existing associations are preserved."); return; }
        final String key = record == null ? null : record.key;
        VisitPicker picker = new VisitPicker(history);
        int answer = JOptionPane.showConfirmDialog(this, picker, "Choose an exact saved run · manual association", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        picker.cancel();
        if (answer == JOptionPane.OK_OPTION && record != null && Objects.equals(key, record.key)) {
            VisitRef selected = picker.selected(); if (selected != null) { selectedVisit = selected; updateLink(); }
        }
    }
    static final class RunChoice {
        final VisitRef reference; final String map; final long started;
        RunChoice(VisitRef reference, String map, long started) { this.reference = reference; this.map = map; this.started = started; }
    }
    static List<RunChoice> findRuns(SessionStore history, String text, int limit, long[] matched) throws Exception {
        String needle = text.trim().toLowerCase(Locale.ROOT);
        Comparator<RunChoice> order = Comparator.comparingLong((RunChoice r) -> r.started).thenComparing(r -> r.reference.toString());
        PriorityQueue<RunChoice> retained = new PriorityQueue<>(order);
        history.read(SessionStore.ALL, "runs", ActivityJournal.Visit.class, (session, visit) -> {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            if (visit.id == null || visit.id.isEmpty()) return;
            RunChoice row = new RunChoice(new VisitRef(session.id, visit.id), Objects.toString(visit.map, "Unknown map"), visit.started);
            String haystack = (row.map + " " + row.reference + " " + date(row.started)).toLowerCase(Locale.ROOT);
            if (!haystack.contains(needle)) return; matched[0]++; retained.offer(row); if (retained.size() > limit) retained.poll();
        });
        List<RunChoice> rows = new ArrayList<>(retained); rows.sort(order.reversed()); return rows;
    }
    private static final class VisitPicker extends JPanel {
        private final SessionStore history;
        private final JTextField search = new JTextField(24);
        private final JTextArea status = ContentStyle.wrappingText("Loading saved visits…");
        private final DefaultTableModel model = new DefaultTableModel(new String[]{"Map", "Entered", "Origin session", "Visit ID"}, 0) { public boolean isCellEditable(int r, int c) { return false; } };
        private final JTable table = new JTable(model);
        private List<RunChoice> rows = Collections.emptyList(); private SwingWorker<List<RunChoice>, Void> loading; private long generation;
        VisitPicker(SessionStore history) {
            super(new BorderLayout(0, 6)); this.history = history; JPanel controls = ContentStyle.controls(); controls.add(search); JButton filter = new JButton("Search all saved runs"); controls.add(filter); add(controls, BorderLayout.NORTH);
            ContentStyle.table(table, ContentStyle.Density.DENSE); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            for (int i = 0; i < 4; i++) table.getColumnModel().getColumn(i).setPreferredWidth(i < 2 ? 160 : 240);
            JScrollPane scroll = ContentStyle.tableScroll(table, 7); scroll.setPreferredSize(new Dimension(540, 240)); add(scroll); add(status, BorderLayout.SOUTH);
            search.getAccessibleContext().setAccessibleName("Search all saved run maps, dates or exact references"); table.getAccessibleContext().setAccessibleName("Choose one exact saved run; duplicate maps remain separate");
            filter.addActionListener(e -> load()); search.addActionListener(e -> load()); load();
        }
        void load() {
            cancel(); final long requested = ++generation; final String needle = search.getText(); final long[] matched = {0}; table.clearSelection(); rows = Collections.emptyList(); model.setRowCount(0); status.setText("Searching all saved visits…");
            loading = new SwingWorker<List<RunChoice>, Void>() {
                protected List<RunChoice> doInBackground() throws Exception { return findRuns(history, needle, 500, matched); }
                protected void done() {
                    if (isCancelled() || requested != generation) return;
                    try { rows = get(); for (RunChoice row : rows) model.addRow(new Object[]{row.map, date(row.started), row.reference.sessionId, row.reference.visitId});
                        status.setText(rows.size() + " shown / " + matched[0] + " matches across saved history. Newest 500 retained; narrow search to find older visits. Select explicitly; no nearest-run guessing.");
                    } catch (Exception failure) { status.setText("History could not be read completely. No association selected; retry after restoring readable history."); }
                }
            }; loading.execute();
        }
        void cancel() { if (loading != null) loading.cancel(true); }
        VisitRef selected() { int row = table.getSelectedRow(); return row < 0 ? null : rows.get(row).reference; }
    }
    private static String date(long at) { return at <= 0 ? "Unknown" : Formatters.formatTimestamp(at); }
}
