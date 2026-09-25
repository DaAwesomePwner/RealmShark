package tomato.gui.chat;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.*;
import tomato.gui.history.*;
import tomato.gui.modern.ContentStyle;
import tomato.history.SessionStore;
import tomato.history.archive.*;

/** Whole-scope Chat query client. Frozen classification and bookmarks are part of each projected row. */
public final class ChatArchiveClient implements ArchiveClient<ChatArchiveClient.Row,ChatArchiveClient.Facets,ChatArchiveClient.Sort> {
    public enum Sort { TIME, PLAYER, CHANNEL, MESSAGE, STARRED }
    public static class Facets {
        public String channel = "ALL", player = "";
        public boolean starredOnly, showIgnoredPlayers;
    }
    public static final class Row {
        final ChatMessage message;
        public final boolean starred, playerIgnored;
        public final String reason, timeInterpretation;
        public final Long assumedTime;
        public final long bookmarkChanged;
        Row(ChatMessage message, boolean starred, long bookmarkChanged, ChatFilters.Classification policy, ZoneId zone) {
            this.message = message; this.starred = starred; this.bookmarkChanged = bookmarkChanged;
            reason = policy.reason(message); playerIgnored = policy.ignoresPlayer(message);
            assumedTime = message.received == null ? null : message.received.atZone(zone).toInstant().toEpochMilli();
            timeInterpretation = "Legacy local receipt time; assumed " + zone + "; overlap earlier offset, gap adjusted forward. Not captured UTC.";
        }
        public String transcript() { return message.transcript() + (reason.isEmpty() ? "" : " [Ignored: " + reason + "]"); }
    }
    private final SessionStore store;
    private final ChatFilters filters;
    private final Path scratch;
    private final ChatExplorer live;
    private final ChatBookmarkIntents bookmarks;
    private long generation;
    private final Map<String,String> policies = new ConcurrentHashMap<>();
    ChatArchiveClient(SessionStore store, ChatFilters filters, ChatExplorer live, Path scratch) {
        this(store, filters, live, scratch, ChatBookmarkIntents.forStore(store));
    }
    ChatArchiveClient(SessionStore store, ChatFilters filters, ChatExplorer live, Path scratch, ChatBookmarkIntents bookmarks) {
        this.store = store; this.filters = filters; this.live = live; this.scratch = scratch; this.bookmarks = bookmarks;
        if (live != null) live.useBookmarkIntents(store, bookmarks);
    }
    public ArchiveQuery<Facets,Sort> initialQuery() {
        Facets facets = new Facets(); facets.showIgnoredPlayers = live != null ? live.showsIgnoredPlayers()
            : Boolean.parseBoolean(util.PropertiesManager.getProperty(ChatExplorer.SHOW_IGNORED_PLAYERS));
        return query().withFacets(facets);
    }
    public static ArchiveQuery<Facets,Sort> query() {
        return ArchiveQuery.of(ArchiveQuery.CURRENT, new Facets(), Facets.class, Sort.TIME)
            .withOrder(Collections.singletonList(new ArchiveQuery.Order<>(Sort.TIME, ArchiveQuery.Direction.DESCENDING)));
    }
    public Path scratchDirectory() { return scratch; }
    public ArchiveAdapter<Row,Facets,Sort> adapter(ArchiveQuery<Facets,Sort> query) {
        generation++; policies.clear(); ChatFilters.Classification policy = filters.snapshot();
        return new Adapter(policy) {
            @Override public void scan(ReadSnapshot pin, ArchiveQuery<Facets,Sort> q, Sink<Row> rows, Cancellation cancel) throws IOException {
                super.scan(pin, q, rows, cancel); policies.put(pin.revision, policy.fingerprint() + ":" + starVersion);
            }
            private final long starVersion = live == null ? 0 : live.bookmarkRevision();
        };
    }
    static class Adapter implements ArchiveAdapter<Row,Facets,Sort> {
        private final ChatFilters.Classification policy;
        private long matching, ignored;
        Adapter(ChatFilters.Classification policy) { this.policy = policy; }
        public Class<Row> rowType() { return Row.class; }
        public String unit() { return "messages"; }
        public List<ReadSnapshot.Source> sources(SessionStore store, ArchiveQuery<Facets,Sort> q) {
            return Arrays.asList(new ReadSnapshot.Source(q.resolvedScope(store), "chat"), new ReadSnapshot.Source(SessionStore.ALL, "chat-stars"));
        }
        public void validate(ArchiveQuery<Facets,Sort> q) { ChatMessage.Channel.valueOf(q.facets().channel); Objects.requireNonNull(q.facets().player); }
        public void scan(ReadSnapshot pin, ArchiveQuery<Facets,Sort> q, Sink<Row> rows, Cancellation cancel) throws IOException {
            Map<String,ArchiveRow<ChatExplorer.Bookmark>> stars = new HashMap<>();
            pin.read("chat-stars", ChatExplorer.Bookmark.class, entry -> {
                String id = entry.value.id; if (id == null || id.isEmpty()) return;
                ArchiveRow<ChatExplorer.Bookmark> previous = stars.get(id);
                if (previous == null || entry.value.changed > previous.value.changed
                        || (entry.value.changed == previous.value.changed && entry.ref.compareTo(previous.ref) > 0)) stars.put(id, entry);
            }, cancel);
            ZoneId zone = ZoneId.of(q.bounds().zone);
            pin.read("chat", ChatMessage.class, source -> {
                ChatMessage message = source.value;
                if (message.channel == null) throw new IOException("Chat channel not captured or unsupported in " + source.ref);
                ArchiveRow<ChatExplorer.Bookmark> bookmark = message.id == null ? null : stars.get(message.id);
                Row value = new Row(message, bookmark != null && bookmark.value.starred, bookmark == null ? 0 : bookmark.value.changed, policy, zone);
                ArchiveRow<Row> row = source.project(value);
                if (inBounds(row, q) && matches(row, q)) { matching++; if (!value.reason.isEmpty()) ignored++; }
                rows.accept(row);
            }, cancel);
        }
        public Long time(ArchiveRow<Row> row) { return row.value.assumedTime; }
        public boolean matches(ArchiveRow<Row> row, ArchiveQuery<Facets,Sort> q) { return matches(row.value, q.facets(), q.text()); }
        static boolean matches(Row row, Facets f, String text) {
            boolean ignored = !row.reason.isEmpty(); ChatMessage.Channel channel = ChatMessage.Channel.valueOf(f.channel);
            boolean visible = channel == ChatMessage.Channel.IGNORED ? ignored
                : (!ignored || (f.showIgnoredPlayers && row.playerIgnored)) && (channel == ChatMessage.Channel.ALL || channel == row.message.channel);
            return visible && (!f.starredOnly || row.starred) && row.message.matches(text, f.player);
        }
        public Comparator<Row> comparator(Sort sort) {
            switch (sort) {
                case TIME: return Comparator.comparing(r -> r.message.received, Comparator.nullsLast(Comparator.naturalOrder()));
                case PLAYER: return Comparator.comparing(r -> r.message.player, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
                case CHANNEL: return Comparator.comparing(r -> r.message.channel.name());
                case STARRED: return Comparator.comparing(r -> r.starred);
                default: return Comparator.comparing(r -> r.message.text, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            }
        }
        public Map<String,String> dependencies() { return Collections.singletonMap("chat-policy-sha256", policy.fingerprint()); }
        public Map<String,Count> counts() {
            Map<String,Count> counts = new LinkedHashMap<>(); counts.put("matching", new Count(matching, "messages", "whole matching query"));
            counts.put("ignored", new Count(ignored, "messages", "ignored among whole matching query")); return counts;
        }
    }
    public List<ArchiveExport.Column<Row>> exportColumns() {
        return Arrays.asList(new ArchiveExport.Column<>("Local receipt (offset not captured)", r -> r.message.received),
            new ArchiveExport.Column<>("Time interpretation", r -> r.timeInterpretation), new ArchiveExport.Column<>("Channel", r -> r.message.channel.label),
            new ArchiveExport.Column<>("Sender", r -> r.message.sender), new ArchiveExport.Column<>("Recipient", r -> r.message.recipient),
            new ArchiveExport.Column<>("Direction", r -> r.message.direction), new ArchiveExport.Column<>("Message", r -> r.message.text),
            new ArchiveExport.Column<>("Starred at revision", r -> r.starred), new ArchiveExport.Column<>("Frozen ignore reason", r -> r.reason),
            new ArchiveExport.Column<>("Game ignore observed at receipt", r -> r.message.gameIgnored));
    }
    public JComponent render(ArchivePage<Row> page, ViewState<Facets,Sort> initial, Binding<Facets,Sort> binding) {
        long ticket = ++generation;
        SocialQueryControls.State<Row,Facets,Sort> state = new SocialQueryControls.State<>(initial, binding, () -> ticket == generation);
        Runnable policyChanged = () -> SwingUtilities.invokeLater(() -> {
            if (state.active() && !(filters.snapshot().fingerprint() + ":" + (live == null ? 0 : live.bookmarkRevision())).equals(policies.get(page.revision))) state.refresh();
        });
        JPanel view = new JPanel(new BorderLayout(0, 8)) {
            @Override public void addNotify() { super.addNotify(); filters.addListener(policyChanged); if (live != null) live.addBookmarkListener(policyChanged); policyChanged.run(); }
            @Override public void removeNotify() { filters.removeListener(policyChanged); if (live != null) live.removeBookmarkListener(policyChanged); super.removeNotify(); }
        };
        Facets f = initial.query.facets(); JPanel controls = ContentStyle.controls();
        JComboBox<ChatMessage.Channel> channel = new JComboBox<>(ChatMessage.Channel.values()); channel.setSelectedItem(ChatMessage.Channel.valueOf(f.channel));
        JTextField player = new JTextField(f.player, 12); JCheckBox stars = new JCheckBox("Starred only", f.starredOnly), ignored = new JCheckBox("Show ignored players", f.showIgnoredPlayers);
        controls.add(SocialQueryControls.labeled("Channel", channel, "chat-archive-channel"));
        controls.add(SocialQueryControls.labeled("Sender or recipient contains", player, "chat-archive-player")); controls.add(stars); controls.add(ignored);
        JButton apply = new JButton("Apply Chat filters"); controls.add(apply);
        Runnable change = () -> { Facets next = new Facets(); next.channel = ((ChatMessage.Channel)channel.getSelectedItem()).name();
            next.player = player.getText(); next.starredOnly = stars.isSelected(); next.showIgnoredPlayers = ignored.isSelected(); state.query(state.value.query.withFacets(next)); };
        apply.addActionListener(e -> change.run()); player.addActionListener(e -> change.run()); channel.addActionListener(e -> change.run());
        stars.addActionListener(e -> change.run()); ignored.addActionListener(e -> change.run());
        JPanel header = new JPanel(new BorderLayout(0, 6)); header.add(controls, BorderLayout.NORTH);
        header.add(SocialQueryControls.dates(initial.query.bounds(), true, b -> state.query(state.value.query.withBounds(b))));
        JTextArea detail = ContentStyle.wrappingText("Select a saved message. Classification and stars describe the pinned revision."); detail.setName("chat-archive-detail");
        List<HistoryTables.Column<Row,?>> columns = Arrays.asList(
            new HistoryTables.Column<>("star", "Starred", Boolean.class, r -> r.starred, null),
            new HistoryTables.Column<>("time", "Local receipt", LocalDateTime.class, r -> r.message.received, null),
            new HistoryTables.Column<>("channel", "Channel", String.class, r -> r.message.channel.label + (r.reason.isEmpty() ? "" : " · Ignored"), null),
            new HistoryTables.Column<>("player", "Player", String.class, r -> r.message.playerLabel(), null),
            new HistoryTables.Column<>("message", "Message", String.class, r -> r.message.text, null));
        Map<String,Sort> sorts = new LinkedHashMap<>(); sorts.put("star", Sort.STARRED); sorts.put("time", Sort.TIME); sorts.put("channel", Sort.CHANNEL); sorts.put("player", Sort.PLAYER); sorts.put("message", Sort.MESSAGE);
        JTable table = HistoryTables.queried("chat-archive-messages", columns, page, sorts, initial.query, state::query,
            row -> detail.setText(row.value.transcript() + "\n" + row.value.timeInterpretation + (row.value.message.gameIgnored ? "\nIn-game ignore observed at receipt." : "")));
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && table.getSelectedRow() >= 0)
            table.getActionMap().get("archive-details").actionPerformed(null); });
        JScrollPane scroll = ContentStyle.tableScroll(table, 3); JPanel actions = ContentStyle.controls();
        JButton star = new JButton("Toggle star"), ignore = new JButton("Toggle local sender ignore"), copy = new JButton("Copy loaded page transcripts");
        JButton copySelected = new JButton("Copy selected transcripts"), thisPlayer = new JButton("This player"), editPolicy = new JButton("Chat filters…");
        JTextArea saveStatus = ContentStyle.wrappingText(""); saveStatus.setName("chat-archive-save-status");
        ChatBookmarkIntents.Intent[] retry = new ChatBookmarkIntents.Intent[1];
        star.addActionListener(e -> {
            int index = table.getSelectedRow(); if (index < 0 || !state.active()) return;
            Row selected = page.rows.get(index).value;
            if (selected.message.id == null || selected.message.id.isEmpty() || !store.writable()) { saveStatus.setText("Star unavailable: message ID missing or history is read-only."); return; }
            star.setEnabled(false); saveStatus.setText("Saving star…");
            try {
                ChatBookmarkIntents.Intent intent = retry[0] != null && retry[0].bookmark.id.equals(selected.message.id)
                    ? bookmarks.retry(retry[0]) : bookmarks.toggle(selected.message.id, selected.starred, selected.bookmarkChanged);
                retry[0] = null; star.setText("Toggle star");
                if (live != null) live.bookmarkIntentAccepted(intent);
                intent.saved.whenComplete((ignoredResult, failure) -> {
                    if (!bookmarks.current(intent)) return;
                    if (live != null) live.bookmarkIntentFinished(intent, failure);
                    if (!state.active()) return;
                    if (failure == null) { saveStatus.setText("Star saved."); state.refresh(); }
                    else { retry[0] = intent; saveStatus.setText("Star save failed; retry. " + failure.getMessage()); star.setText("Retry star save"); star.setEnabled(true); }
                });
            } catch (RuntimeException failure) { saveStatus.setText("Star not changed: " + failure.getMessage()); star.setEnabled(true); }
        });
        ignore.addActionListener(e -> { int index = table.getSelectedRow(); if (index >= 0 && state.active()) {
            ChatMessage message = page.rows.get(index).value.message;
            if (!message.ownMessage && message.channel != ChatMessage.Channel.SYSTEM) { filters.togglePlayer(message.sender); state.refresh(); }
        }});
        copy.addActionListener(e -> {
            StringBuilder text = new StringBuilder(); for (ArchiveRow<Row> row : page.rows) text.append(row.value.transcript()).append('\n');
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text.toString()), null);
        });
        Runnable selectedTranscript = () -> {
            StringBuilder text = new StringBuilder(); for (int index : table.getSelectedRows()) text.append(page.rows.get(index).value.transcript()).append('\n');
            if (text.length() > 0) Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text.toString()), null);
        };
        copySelected.addActionListener(e -> selectedTranscript.run());
        table.getActionMap().put("archive-copy", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { selectedTranscript.run(); } });
        thisPlayer.addActionListener(e -> { int index = table.getSelectedRow(); if (index >= 0) { Facets next = state.value.query.facets(); next.player = page.rows.get(index).value.message.player; state.query(state.value.query.withFacets(next)); } });
        editPolicy.setEnabled(live != null); editPolicy.addActionListener(e -> { JDialog dialog = live.createFiltersDialog(); dialog.setLocationRelativeTo(view); dialog.setVisible(true); });
        actions.add(star); actions.add(ignore); actions.add(thisPlayer); actions.add(copySelected); actions.add(copy); actions.add(editPolicy);
        Map<String,List<String>> presets = new LinkedHashMap<>(); presets.put("Conversation", Arrays.asList("star", "time", "player", "message")); presets.put("All columns", new ArrayList<>(sorts.keySet()));
        JPanel body = new JPanel(new BorderLayout(0, 4)); body.add(scroll); body.add(state.tableControls(table, scroll, page, "messages", presets), BorderLayout.SOUTH);
        JPanel footer = new JPanel(new BorderLayout(0, 4)); footer.add(actions, BorderLayout.NORTH); footer.add(detail);
        footer.add(ContentStyle.wrappingText(page.description() + " · use workspace Export selected / page / all matches (CSV or JSON).\n" + (page.rows.isEmpty() ? "No saved messages match this query; adjust filters or Refresh." : "")), BorderLayout.SOUTH);
        JPanel lower = new JPanel(new BorderLayout()); lower.add(footer); lower.add(saveStatus, BorderLayout.SOUTH);
        view.add(ContentStyle.page(header, body, lower)); state.owner(view); policyChanged.run(); return view;
    }
}
