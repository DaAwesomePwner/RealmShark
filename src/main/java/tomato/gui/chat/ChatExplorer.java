package tomato.gui.chat;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import javax.swing.text.DefaultHighlighter;
import tomato.gui.modern.ContentStyle;
import util.PropertiesManager;

/** Session-local, bounded chat history. All model and Swing changes happen on the EDT. */
final class ChatExplorer extends JPanel {
    static final int HISTORY_LIMIT = 10000;
    static final String SHOW_IGNORED_PLAYERS = "chat.showIgnoredPlayers";
    private static final Icon STAR_ICON = new Icon() {
        public int getIconWidth() { return 14; }
        public int getIconHeight() { return 14; }
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(component.getForeground()); Polygon shape = new Polygon();
            for (int i = 0; i < 10; i++) {
                double angle = -Math.PI / 2 + i * Math.PI / 5, radius = i % 2 == 0 ? 6.5 : 3;
                shape.addPoint(x + 7 + (int)Math.round(Math.cos(angle) * radius), y + 7 + (int)Math.round(Math.sin(angle) * radius));
            }
            g.fillPolygon(shape); g.dispose();
        }
    };
    private final ChatFilters spamFilters;
    private final boolean archive;
    private tomato.history.SessionStore bookmarkStore = tomato.history.AppHistory.store();
    private final java.util.function.Supplier<String> ignoreStatus;
    private final Map<ChatMessage, String> reasons = new IdentityHashMap<>();
    private long filterRevision = -1;
    private ChatFilters.Classification classification;
    private final JTextArea filterStatus = ContentStyle.wrappingText(""), ignoreReason = ContentStyle.wrappingText("");
    private final JButton ignorePlayer = new JButton("Ignore player");
    private final List<ChatMessage> history = new ArrayList<>();
    private final Set<ChatMessage> starred = new HashSet<>();
    private final ArrayDeque<ChatMessage> pending = new ArrayDeque<>();
    private boolean drainScheduled;
    private boolean viewDirty;
    private long pendingEvicted, evicted;
    private int viewRevision;
    private List<ChatMessage> visible = new ArrayList<>();
    private ChatMessage detailedMessage;
    private String highlightedQuery = "";
    private ChatMessage.Channel channel = ChatMessage.Channel.ALL;
    private final JTextField search = new JTextField(), player = new JTextField();
    private final JCheckBox starredOnly = new JCheckBox("Starred"), follow = new JCheckBox("Follow latest", true);
    private final JCheckBox showIgnoredPlayers = new JCheckBox("Show ignored players");
    private final JTextArea summary = ContentStyle.wrappingText(""), detailHeader = ContentStyle.wrappingText("Select a message");
    private final JLabel emptyTitle = new JLabel(), emptyHint = new JLabel();
    private final JTextArea detail = new JTextArea();
    private final JButton star = new JButton("Star"), copy = new JButton("Copy"), filterPlayer = new JButton("This player");
    private final JToggleButton[] channels = new JToggleButton[ChatMessage.Channel.values().length];
    private final int[] channelCounts = new int[channels.length];
    private final JPanel channelRow = ContentStyle.controls();
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards) {
        @Override public Dimension getMinimumSize() { return scroll.getMinimumSize(); }
    };
    private final JPanel details = new JPanel(new BorderLayout(0, 7));
    private final MessageTable model = new MessageTable();
    private final JTable table = new JTable(model) {
        @Override public boolean getScrollableTracksViewportWidth() {
            return getParent() instanceof JViewport && getParent().getWidth() >= getMinimumSize().width;
        }
    };
    private final JScrollPane scroll = ContentStyle.tableScroll(table, 3);
    private final javax.swing.Timer debounce;
    private final JMenuItem copyView = new JMenuItem("Copy filtered messages"), exportView = new JMenuItem("Export filtered messages…");
    private boolean columnSizingPending;
    private final java.util.concurrent.atomic.AtomicBoolean policyRefreshPending = new java.util.concurrent.atomic.AtomicBoolean();
    private final Runnable policyChanged = () -> {
        if (policyRefreshPending.compareAndSet(false, true)) SwingUtilities.invokeLater(() -> {
            policyRefreshPending.set(false);
            if (isDisplayable()) refreshPolicy();
        });
    };

    @Override public void addNotify() { super.addNotify(); spamFilters.addListener(policyChanged); refreshPolicy(); }
    @Override public void removeNotify() { spamFilters.removeListener(policyChanged); debounce.stop(); super.removeNotify(); }

    private void refreshPolicy() {
        boolean following = follow.isSelected(); follow.setSelected(false);
        refresh(true); follow.setSelected(following);
    }

    ChatExplorer(Runnable editAlerts) {
        this(editAlerts, new ChatFilters(), () -> "In-game ignore status is unavailable in this preview.");
    }

    ChatExplorer(Runnable editAlerts, ChatFilters spamFilters, java.util.function.Supplier<String> ignoreStatus) {
        this(editAlerts, spamFilters, ignoreStatus, true);
    }
    ChatExplorer(Runnable editAlerts, ChatFilters spamFilters, java.util.function.Supplier<String> ignoreStatus, boolean archive) {
        super(new BorderLayout(0, 8));
        this.archive = archive;
        this.spamFilters = spamFilters; this.ignoreStatus = ignoreStatus;
        setMinimumSize(new Dimension(0, 0));
        JButton actions = new JButton("Actions");
        JPopupMenu menu = new JPopupMenu();
        menu.add(copyView); menu.add(exportView); menu.addSeparator();
        JMenuItem alerts = new JMenuItem("Chat alert rules…"), clear = new JMenuItem("Clear session history…");
        alerts.setEnabled(archive);clear.setEnabled(archive);
        menu.add(alerts); menu.addSeparator(); menu.add(clear);
        copyView.addActionListener(e -> copyText(filteredTranscript()));
        exportView.addActionListener(e -> exportFiltered());
        alerts.addActionListener(e -> editAlerts.run());
        clear.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "Clear the live messages and stars in every channel?\nSaved session history remains available through Browse saved.",
                    "Clear chat history", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) clear();
        });
        actions.setComponentPopupMenu(menu);
        actions.addActionListener(e -> {
            SwingUtilities.updateComponentTreeUI(menu); ContentStyle.refreshFonts(menu);
            menu.show(actions, 0, actions.getHeight());
        });
        JPanel filters = new JPanel(new BorderLayout(0, 6));
        JPanel searchRow = new JPanel(new BorderLayout(8, 0));
        search.setName("chat-search"); search.putClientProperty("JTextField.placeholderText", "Search messages, players, or dates…");
        search.getAccessibleContext().setAccessibleName("Search chat history");
        search.setToolTipText("Literal, case-insensitive search within the selected channel. Ctrl+F to focus; Esc to clear.");
        searchRow.add(search, BorderLayout.CENTER);
        JButton reset = new JButton("Reset"); reset.setToolTipText("Reset all search and channel filters");
        reset.addActionListener(e -> resetFilters());
        JPanel searchActions = new JPanel(new GridLayout(1, 2, 6, 0));
        searchActions.add(reset); searchActions.add(actions); searchRow.add(searchActions, BorderLayout.EAST);
        filters.add(searchRow, BorderLayout.NORTH);
        JPanel filterRow = ContentStyle.controls();
        JPanel playerRow = new JPanel(new BorderLayout(8, 0));
        JLabel playerLabel = new JLabel("Player"); playerLabel.setLabelFor(player);
        player.setName("chat-player"); player.putClientProperty("JTextField.placeholderText", "Sender or recipient");
        player.getAccessibleContext().setAccessibleName("Filter by player");
        player.setColumns(12);
        playerRow.add(playerLabel, BorderLayout.WEST); playerRow.add(player, BorderLayout.CENTER);
        filterRow.add(playerRow); filterRow.add(starredOnly); filterRow.add(follow); filterRow.add(showIgnoredPlayers);
        showIgnoredPlayers.setName("chat-show-ignored-players");
        showIgnoredPlayers.setSelected(Boolean.parseBoolean(PropertiesManager.getProperty(SHOW_IGNORED_PLAYERS)));
        showIgnoredPlayers.setToolTipText("Show captured messages from locally or in-game ignored players in All and their original channels. Still logged and silent; spam-only matches stay in Ignored.");
        showIgnoredPlayers.getAccessibleContext().setAccessibleDescription(showIgnoredPlayers.getToolTipText());
        showIgnoredPlayers.addActionListener(e -> {
            PropertiesManager.setProperties(SHOW_IGNORED_PLAYERS, Boolean.toString(showIgnoredPlayers.isSelected()));
            refresh(false);
        });
        starredOnly.setToolTipText("Show starred messages retained in this session");
        follow.setToolTipText("Scroll to new matching messages. Turn off to read earlier history.");
        filters.add(filterRow, BorderLayout.CENTER);
        ((FlowLayout) channelRow.getLayout()).setHgap(4);
        ((FlowLayout) channelRow.getLayout()).setVgap(0);
        ButtonGroup group = new ButtonGroup();
        for (ChatMessage.Channel value : ChatMessage.Channel.values()) {
            JToggleButton button = new JToggleButton(value.label); channels[value.ordinal()] = button;
            button.setBackground(UIManager.getColor("Button.background"));
            button.setName("chat-channel-" + value.name());
            button.putClientProperty("JButton.buttonType", "tab");
            button.putClientProperty("JComponent.minimumHeight", 28);
            button.setMargin(new Insets(2, 8, 2, 8));
            ContentStyle.font(button, ContentStyle.body());
            button.addActionListener(e -> { channel = value; refresh(false); });
            group.add(button); channelRow.add(button);
        }
        channelRow.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { updateChannelLabels(); }
        });
        for (JToggleButton button : channels) button.addPropertyChangeListener("font", e -> updateChannelLabels());
        channels[0].setSelected(true); filters.add(channelRow, BorderLayout.SOUTH);
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.add(filters, BorderLayout.CENTER);
        ContentStyle.font(filterStatus, ContentStyle.metadata(ContentStyle.body()));
        filterStatus.setForeground(ContentStyle.color("muted"));
        JMenuItem editFilters = new JMenuItem("Chat filters…");
        editFilters.setName("chat-edit-filters");
        editFilters.addActionListener(e -> openFilters());
        menu.insert(editFilters, 3);
        actions.setToolTipText("Copy, export, chat filters and alert rules");
        header.add(filterStatus, BorderLayout.SOUTH);

        table.setName("chat-messages"); ContentStyle.table(table);
        table.setIntercellSpacing(new Dimension(0, 0)); table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getAccessibleContext().setAccessibleName("Filtered chat messages");
        DefaultTableCellRenderer starHeader = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                      boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                setFont(table.getTableHeader().getFont());
                setBackground(table.getTableHeader().getBackground()); setForeground(table.getTableHeader().getForeground());
                return this;
            }
        };
        starHeader.setHorizontalAlignment(SwingConstants.CENTER); starHeader.setIcon(STAR_ICON);
        starHeader.setToolTipText("Starred messages");
        table.getColumnModel().getColumn(0).setHeaderRenderer(starHeader);
        table.setDefaultRenderer(Object.class, new MessageRenderer());
        table.getColumnModel().getColumn(2).setCellRenderer(new ContentStyle.Badge() {
            protected Color badgeColor(Object value) {
                String label = String.valueOf(value);
                return label.endsWith(" · Ignored") ? ContentStyle.color("rose") : channelColor(label);
            }
        });
        sizeColumns();
        table.addPropertyChangeListener(e -> {
            if ("font".equals(e.getPropertyName()) || "UI".equals(e.getPropertyName())) sizeColumnsLater();
        });
        table.getTableHeader().addPropertyChangeListener(e -> {
            if ("font".equals(e.getPropertyName()) || "UI".equals(e.getPropertyName())) sizeColumnsLater();
        });
        table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showDetail(); });
        table.addMouseListener(new MouseAdapter() { public void mousePressed(MouseEvent e) { follow.setSelected(false); } });
        table.addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_UP || e.getKeyCode() == KeyEvent.VK_PAGE_UP || e.getKeyCode() == KeyEvent.VK_HOME) follow.setSelected(false);
            }
        });
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(table.getRowHeight());
        // Reading older messages automatically releases auto-follow without interrupting capture.
        scroll.addMouseWheelListener(e -> { if (e.getWheelRotation() < 0) follow.setSelected(false); });
        scroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            JScrollBar bar = scroll.getVerticalScrollBar();
            if (e.getValueIsAdjusting() && bar.getValue() + bar.getVisibleAmount() < bar.getMaximum() - 4) follow.setSelected(false);
        });
        body.add(scroll, "messages");
        JPanel empty = new JPanel(new GridBagLayout());
        JPanel emptyText = new JPanel(new GridLayout(0, 1, 0, 6));
        emptyTitle.setHorizontalAlignment(SwingConstants.CENTER); ContentStyle.font(emptyTitle, ContentStyle.emphasis(ContentStyle.body()));
        emptyHint.setHorizontalAlignment(SwingConstants.CENTER);
        emptyText.add(emptyTitle); emptyText.add(emptyHint); empty.add(emptyText); body.add(empty, "empty");

        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        details.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(6, 0, 0, 0)));
        JPanel detailTop = new JPanel(); detailTop.setLayout(new BoxLayout(detailTop, BoxLayout.Y_AXIS));
        detailHeader.setName("chat-detail-header"); ignoreReason.setName("chat-ignore-reason");
        ContentStyle.font(detailHeader, ContentStyle.emphasis(ContentStyle.body()));
        ContentStyle.font(ignoreReason, ContentStyle.metadata(ContentStyle.body()));
        ignoreReason.setForeground(ContentStyle.color("rose"));
        detailHeader.setAlignmentX(Component.LEFT_ALIGNMENT); detailTop.add(detailHeader);
        ignoreReason.putClientProperty("html.disable", true);
        ignoreReason.setAlignmentX(Component.LEFT_ALIGNMENT); detailTop.add(ignoreReason);
        JPanel detailActions = ContentStyle.controls();
        for (JButton button : new JButton[]{star, copy, filterPlayer, ignorePlayer}) {
            detailActions.add(button);
        }
        detailActions.setAlignmentX(Component.LEFT_ALIGNMENT); detailTop.add(detailActions); details.add(detailTop, BorderLayout.NORTH);
        detail.setName("chat-detail-message");
        detail.setEditable(false); detail.setLineWrap(true); detail.setWrapStyleWord(true);
        detail.setRows(2); detail.setMargin(new Insets(4, 8, 4, 8));
        ContentStyle.font(detail, ContentStyle.body());
        detail.getAccessibleContext().setAccessibleName("Full selected message");
        details.add(new JScrollPane(detail), BorderLayout.CENTER);
        bottom.add(details, BorderLayout.CENTER);
        ContentStyle.font(summary, ContentStyle.metadata(ContentStyle.body()));
        summary.setForeground(ContentStyle.color("muted")); bottom.add(summary, BorderLayout.SOUTH);
        JScrollPane page = ContentStyle.page(header, body, bottom);
        page.setName("chat-page-scroll"); add(page, BorderLayout.CENTER);
        star.addActionListener(e -> toggleStar()); copy.addActionListener(e -> copyText(selectedTranscript()));
        filterPlayer.addActionListener(e -> { ChatMessage m = selected(); if (m != null) { player.setText(m.player); refresh(false); } });
        ignorePlayer.setName("chat-ignore-player");
        ignorePlayer.setToolTipText("Toggle a local RealmShark sender ignore. Spam rules still apply after removal.");
        ignorePlayer.addActionListener(e -> {
            ChatMessage m = selected();
            if (m != null && !m.ownMessage && m.channel != ChatMessage.Channel.SYSTEM) {
                spamFilters.togglePlayer(m.sender); refresh(false);
            }
        });
        follow.addActionListener(e -> { if (follow.isSelected()) scrollToLatest(); });
        starredOnly.addActionListener(e -> refresh(false));
        debounce = new javax.swing.Timer(150, e -> refresh(false)); debounce.setRepeats(false);
        DocumentListener listener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { debounce.restart(); }
            public void removeUpdate(DocumentEvent e) { debounce.restart(); }
            public void changedUpdate(DocumentEvent e) { debounce.restart(); }
        };
        search.getDocument().addDocumentListener(listener); player.getDocument().addDocumentListener(listener);
        search.addActionListener(e -> { refresh(false); if (!visible.isEmpty()) { table.setRowSelectionInterval(0, 0); table.requestFocusInWindow(); } });
        bind(this, WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, "control F", "find", () -> { search.requestFocusInWindow(); search.selectAll(); });
        bind(search, WHEN_FOCUSED, "ESCAPE", "clear-search", () -> search.setText(""));
        bind(player, WHEN_FOCUSED, "ESCAPE", "clear-player", () -> player.setText(""));
        bind(table, WHEN_FOCUSED, "control C", "copy-messages", () -> copyText(selectedTranscript()));
        bind(table, WHEN_FOCUSED, "SPACE", "star-message", this::toggleStar);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                boolean show = Boolean.parseBoolean(PropertiesManager.getProperty(SHOW_IGNORED_PLAYERS));
                if (showIgnoredPlayers.isSelected() != show) { showIgnoredPlayers.setSelected(show); refresh(false); }
                else if (viewDirty || filterRevision != spamFilters.revision()) refreshPolicy();
            }
        });
        refresh(false);
    }

    @Override public void updateUI() {
        super.updateUI();
        if (filterStatus == null) return;
        filterStatus.setForeground(ContentStyle.color("muted"));
        summary.setForeground(ContentStyle.color("muted"));
        ignoreReason.setForeground(ContentStyle.color("rose"));
        detailHeader.setForeground(ContentStyle.color(ignoreReason.isVisible() ? "rose" : "violet"));
    }

    void accept(ChatMessage message) {
        if (archive) tomato.history.AppHistory.append("chat", message);
        if (SwingUtilities.isEventDispatchThread()) { append(Collections.singletonList(message), 0); return; }
        synchronized (pending) {
            if (pending.size() == HISTORY_LIMIT) { pending.removeFirst(); pendingEvicted++; }
            pending.addLast(message);
            if (!drainScheduled) { drainScheduled = true; SwingUtilities.invokeLater(this::drain); }
        }
    }
    void loadHistory(List<ChatMessage> messages, Set<String> stars, tomato.history.SessionStore store) {
        bookmarkStore=store;
        for(ChatMessage message:messages)if(stars.contains(message.id))starred.add(message);
        append(messages,0);
    }

    private void drain() {
        List<ChatMessage> batch; long dropped;
        synchronized (pending) {
            batch = new ArrayList<>(pending); pending.clear(); dropped = pendingEvicted;
            pendingEvicted = 0; drainScheduled = false;
        }
        append(batch, dropped);
    }

    private void append(List<ChatMessage> batch, long dropped) {
        history.addAll(batch); evicted += dropped;
        int excess = history.size() - HISTORY_LIMIT;
        if (excess > 0) {
            for (ChatMessage message : history.subList(0, excess)) reasons.remove(message);
            starred.removeAll(history.subList(0, excess));
            history.subList(0, excess).clear(); evicted += excess;
        }
        if (isDisplayable() && !isShowing()) viewDirty = true;
        else refresh(true);
    }

    void clear() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::clear); return; }
        synchronized (pending) { pending.clear(); pendingEvicted = 0; }
        history.clear(); starred.clear(); reasons.clear(); evicted = 0; refresh(false);
    }

    void editFont(Font font) {
        ContentStyle.tableFont(table, font, 0); ContentStyle.font(detail, font);
        ContentStyle.font(detailHeader, ContentStyle.emphasis(font));
        scroll.getVerticalScrollBar().setUnitIncrement(table.getRowHeight());
    }

    private void sizeColumnsLater() {
        if (columnSizingPending) return;
        columnSizingPending = true;
        SwingUtilities.invokeLater(() -> { columnSizingPending = false; sizeColumns(); });
    }

    private void sizeColumns() {
        for (int index = 0; index < table.getColumnCount(); index++) {
            TableColumn column = table.getColumnModel().getColumn(index);
            TableCellRenderer header = column.getHeaderRenderer();
            if (header == null) header = table.getTableHeader().getDefaultRenderer();
            Component heading = header.getTableCellRendererComponent(table, column.getHeaderValue(), false, false, -1, index);
            int minimum = heading.getPreferredSize().width;
            if (index == 0) minimum = Math.max(minimum, STAR_ICON.getIconWidth() + cellWidth(index, ""));
            if (index == 1) {
                // Include the widest digit in proportional fonts, as well as the end-of-day clock.
                minimum = Math.max(minimum, cellWidth(index, "23:59:59"));
                for (char digit = '0'; digit <= '9'; digit++)
                    minimum = Math.max(minimum, cellWidth(index, "" + digit + digit + ':' + digit + digit + ':' + digit + digit));
            }
            if (index == 2) for (ChatMessage.Channel value : ChatMessage.Channel.values()) {
                minimum = Math.max(minimum, cellWidth(index, value.label));
                if (value != ChatMessage.Channel.ALL && value != ChatMessage.Channel.SYSTEM && value != ChatMessage.Channel.IGNORED)
                    minimum = Math.max(minimum, cellWidth(index, value.label + " · Ignored"));
            }
            if (index == 3) minimum = Math.max(minimum, cellWidth(index, "From: Wren"));
            if (index == 4) minimum = Math.max(minimum, cellWidth(index, "Message text"));
            // Start semantic columns at their measured size; users may still widen them.
            column.setMaxWidth(Integer.MAX_VALUE);
            column.setMinWidth(minimum);
            column.setMaxWidth(index == 0 ? minimum : Integer.MAX_VALUE);
            column.setPreferredWidth(index == 3 ? Math.max(minimum, cellWidth(index, "From: LongPlayerName"))
                : index == 4 ? Math.max(minimum, cellWidth(index, "Meet at the portal when everyone is ready.")) : minimum);
        }
        table.revalidate();
    }

    private int cellWidth(int column, String text) {
        TableCellRenderer renderer = table.getCellRenderer(0, column);
        int width = 0;
        for (boolean focus : new boolean[] {false, true}) {
            Component cell = renderer.getTableCellRendererComponent(table, text, false, focus, -1, column);
            width = Math.max(width, cell.getPreferredSize().width);
        }
        return width + table.getIntercellSpacing().width;
    }

    void refresh(boolean arriving) {
        viewDirty = false;
        if (!arriving && debounce != null) debounce.stop();
        int revision = ++viewRevision;
        Set<ChatMessage> selection = new HashSet<>();
        for (int row : table.getSelectedRows()) if (row < visible.size()) selection.add(visible.get(row));
        int firstRow = table.rowAtPoint(scroll.getViewport().getViewPosition());
        ChatMessage anchor = firstRow >= 0 && firstRow < visible.size() ? visible.get(firstRow) : null;
        int offset = firstRow >= 0 ? scroll.getViewport().getViewPosition().y - firstRow * table.getRowHeight() : 0;
        classification = spamFilters.snapshot();
        long currentRevision = classification.revision;
        if (filterRevision != currentRevision) { reasons.clear(); filterRevision = currentRevision; }
        List<ChatMessage> filtered = new ArrayList<>(); int[] counts = new int[channels.length];
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        String playerQuery = player.getText().trim().toLowerCase(Locale.ROOT);
        for (ChatMessage message : history) {
            boolean ignored = !reason(message).isEmpty();
            if (ignored) counts[ChatMessage.Channel.IGNORED.ordinal()]++;
            boolean inChannels = !ignored || (showIgnoredPlayers.isSelected() && classification.ignoresPlayer(message));
            if (inChannels) { counts[0]++; counts[message.channel.ordinal()]++; }
            if ((channel == ChatMessage.Channel.IGNORED ? ignored : inChannels && (channel == ChatMessage.Channel.ALL || message.channel == channel))
                    && (!starredOnly.isSelected() || starred.contains(message)) && message.matchesNormalized(query, playerQuery)) filtered.add(message);
        }
        visible = filtered;
        table.getSelectionModel().setValueIsAdjusting(true);
        model.fireTableDataChanged();
        for (int row = 0; row < visible.size(); row++) if (selection.contains(visible.get(row))) table.addRowSelectionInterval(row, row);
        table.getSelectionModel().setValueIsAdjusting(false);
        for (ChatMessage.Channel value : ChatMessage.Channel.values()) {
            JToggleButton button = channels[value.ordinal()];
            channelCounts[value.ordinal()] = counts[value.ordinal()];
            button.getAccessibleContext().setAccessibleName(value.label + ": " + counts[value.ordinal()] + " retained messages");
            button.setToolTipText(value.label + " · " + counts[value.ordinal()] + " retained messages before search filters");
        }
        updateChannelLabels();
        filterStatus.setText(counts[ChatMessage.Channel.IGNORED.ordinal()] + " ignored · "
                + (showIgnoredPlayers.isSelected() ? "ignored players shown in channels · " : "") + "filtered messages stay silent"
                + (spamFilters.saveStatus().isEmpty() ? "" : " · " + spamFilters.saveStatus()));
        filterStatus.setToolTipText("Actions > Chat filters edits player ignores, blocked phrases, and advertisement detection.");
        cards.show(body, visible.isEmpty() ? "empty" : "messages");
        emptyTitle.setText(history.isEmpty() ? "Your Realm conversations, together" : "No matching messages");
        emptyHint.setText(history.isEmpty() ? "Start capture and join the game to begin." : "Try another channel or reset your filters.");
        summary.setText(visible.size() + " shown · " + history.size() + " / 10,000 retained · " + starred.size() + " starred"
                + (evicted > 0 ? " · " + evicted + " older messages in saved history" : archive ? " · Current session" : " · Historical page"));
        summary.setToolTipText("Live view keeps the latest 10,000 messages. Session history retains all captured messages; use Browse saved for older pages. Actions exports the filtered view.");
        copyView.setEnabled(!visible.isEmpty()); exportView.setEnabled(!visible.isEmpty()); showDetail();
        if (arriving && follow.isSelected()) SwingUtilities.invokeLater(() -> {
            if (revision == viewRevision && follow.isSelected()) scrollToLatest();
        });
        else if (arriving && anchor != null) {
            int row = visible.indexOf(anchor);
            if (row >= 0) scroll.getViewport().setViewPosition(new Point(0, row * table.getRowHeight() + offset));
        } else if (!arriving) scroll.getViewport().setViewPosition(new Point(0, 0));
    }

    private void updateChannelLabels() {
        FlowLayout layout = (FlowLayout) channelRow.getLayout();
        Insets insets = channelRow.getInsets();
        int fullWidth = insets.left + insets.right + layout.getHgap() * (channels.length + 1);
        for (ChatMessage.Channel value : ChatMessage.Channel.values()) {
            JToggleButton button = channels[value.ordinal()];
            button.setText(value.label + " " + channelCounts[value.ordinal()]);
            fullWidth += button.getPreferredSize().width;
        }
        // Keep every channel reachable: omit visual counts first, then let controls wrap.
        if (channelRow.getWidth() > 0 && fullWidth > channelRow.getWidth()) {
            for (ChatMessage.Channel value : ChatMessage.Channel.values()) channels[value.ordinal()].setText(value.label);
        }
        channelRow.revalidate();
    }

    private void resetFilters() {
        search.setText(""); player.setText(""); starredOnly.setSelected(false);
        channel = ChatMessage.Channel.ALL; channels[0].setSelected(true); refresh(false);
    }

    private ChatMessage selected() { int row = table.getSelectedRow(); return row >= 0 && row < visible.size() ? visible.get(row) : null; }

    private void showDetail() {
        ChatMessage message = selected(); boolean hasMessage = message != null;
        if (details.isVisible() != hasMessage) { details.setVisible(hasMessage); revalidate(); }
        star.setEnabled(hasMessage); copy.setEnabled(hasMessage); filterPlayer.setEnabled(hasMessage && !message.player.isEmpty());
        ignorePlayer.setEnabled(hasMessage && !message.ownMessage && message.channel != ChatMessage.Channel.SYSTEM && !message.sender.isEmpty());
        ignorePlayer.setText(hasMessage && spamFilters.ignoresPlayer(message.sender) ? "Unignore player" : "Ignore player");
        String why = hasMessage ? reason(message) : "";
        ignoreReason.setText(why.isEmpty() ? "" : "Ignored: " + why);
        ignoreReason.setToolTipText(why.isEmpty() ? null : plainTooltip(why));
        ignoreReason.setVisible(!why.isEmpty());
        star.setText(hasMessage && starred.contains(message) ? "Unstar" : "Star");
        detailHeader.setText(hasMessage ? message.clock() + " · " + message.channel.label + " · " + message.playerLabel()
            + (message.gameIgnored ? " · In-game ignore observed at receipt" : "") : "Select a message to read or star");
        detailHeader.setForeground(ContentStyle.color(why.isEmpty() ? "violet" : "rose"));
        detailHeader.setToolTipText(hasMessage ? message.date() + " · " + message.sender + (message.recipient.isEmpty() ? "" : " → " + message.recipient) : null);
        String query = search.getText().trim();
        if (detailedMessage == message && highlightedQuery.equals(query)) return;
        detailedMessage = message; highlightedQuery = query;
        detail.setText(hasMessage ? message.text : ""); detail.setCaretPosition(0);
        detail.getHighlighter().removeAllHighlights();
        if (hasMessage && !query.isEmpty()) {
            java.util.regex.Matcher matches = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(query),
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE).matcher(message.text);
            while (matches.find()) try {
                detail.getHighlighter().addHighlight(matches.start(), matches.end(),
                        new DefaultHighlighter.DefaultHighlightPainter(UIManager.getColor("TextArea.selectionBackground")));
            } catch (javax.swing.text.BadLocationException ignored) { break; }
        }
    }

    private void toggleStar() {
        ChatMessage message = selected(); if (message == null) return;
        if (!starred.remove(message)) starred.add(message);
        if(bookmarkStore!=null&&message.id!=null)bookmarkStore.put("chat-stars",message.id,new Bookmark(message.id,starred.contains(message)));
        boolean following = follow.isSelected(); follow.setSelected(false);
        refresh(true);
        follow.setSelected(following);
    }
    static final class Bookmark {
        final String id;final boolean starred;final long changed=System.currentTimeMillis();
        Bookmark(String id,boolean starred){this.id=id;this.starred=starred;}
    }

    private void scrollToLatest() {
        if (!visible.isEmpty()) table.scrollRectToVisible(table.getCellRect(visible.size() - 1, 0, true));
    }

    String filteredTranscript() { if (viewDirty) refresh(true); return transcript(visible); }
    String selectedTranscript() {
        List<ChatMessage> messages = new ArrayList<>();
        for (int row : table.getSelectedRows()) if (row < visible.size()) messages.add(visible.get(row));
        return transcript(messages);
    }
    private String transcript(List<ChatMessage> messages) {
        StringBuilder result = new StringBuilder();
        for (ChatMessage message : messages) {
            result.append(message.transcript());
            String why = reason(message);
            if (!why.isEmpty()) result.append(" [Ignored: ").append(why).append("]");
            result.append(System.lineSeparator());
        }
        return result.toString();
    }

    private String reason(ChatMessage message) {
        return reasons.computeIfAbsent(message, classification::reason);
    }

    private void openFilters() {
        JDialog dialog = createFiltersDialog();
        dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }

    JDialog createFiltersDialog() {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "Chat filters", Dialog.ModalityType.APPLICATION_MODAL);
        realmshark.branding.AppIdentity.apply(dialog);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(new ChatFilterPanel(spamFilters, ignoreStatus.get(), () -> {
            refreshPolicy();
        }, dialog::dispose));
        int line = getFontMetrics(ContentStyle.body()).getHeight();
        GraphicsConfiguration configuration = dialog.getGraphicsConfiguration();
        Rectangle screen = configuration.getBounds();
        Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        int availableWidth = screen.width - screenInsets.left - screenInsets.right;
        int availableHeight = screen.height - screenInsets.top - screenInsets.bottom;
        dialog.setMinimumSize(new Dimension(Math.min(460, availableWidth), Math.min(360, availableHeight)));
        dialog.setSize(Math.min(Math.max(660, line * 26), availableWidth), Math.min(Math.max(600, line * 24), availableHeight));
        return dialog;
    }

    private static String plainTooltip(String value) {
        // Prevent packet-derived rule reasons from activating Swing HTML.
        return value.startsWith("<html>") ? " " + value : value;
    }

    private void copyText(String text) {
        if (text.isEmpty()) return;
        try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null); }
        catch (IllegalStateException | SecurityException e) { JOptionPane.showMessageDialog(this, "The clipboard is busy or unavailable. Please try again.", "Copy chat", JOptionPane.ERROR_MESSAGE); }
    }

    private void exportFiltered() {
        // Snapshot before opening a modal chooser: new packets must not change the export underneath it.
        String text = filteredTranscript();
        JFileChooser chooser = new JFileChooser(); chooser.setDialogTitle("Export filtered chat as UTF-8 text");
        chooser.setSelectedFile(new File("realmshark-chat.txt"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        if (file.exists() && JOptionPane.showConfirmDialog(this, "Replace " + file.getName() + "?", "Export chat", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception { Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); return null; }
            protected void done() {
                try { get(); JOptionPane.showMessageDialog(ChatExplorer.this, "Saved filtered messages to:\n" + file.getAbsolutePath(), "Export complete", JOptionPane.INFORMATION_MESSAGE); }
                catch (Exception e) { JOptionPane.showMessageDialog(ChatExplorer.this, "Could not write the selected file. Check its folder and permissions.", "Export failed", JOptionPane.ERROR_MESSAGE); }
            }
        }.execute();
    }

    private static void bind(JComponent target, int condition, String key, String name, Runnable action) {
        target.getInputMap(condition).put(KeyStroke.getKeyStroke(key), name);
        target.getActionMap().put(name, new AbstractAction() { public void actionPerformed(ActionEvent e) { action.run(); } });
    }

    private final class MessageTable extends AbstractTableModel {
        private final String[] titles = {"", "Time", "Channel", "Player", "Message"};
        public int getRowCount() { return visible.size(); }
        public int getColumnCount() { return titles.length; }
        public String getColumnName(int column) { return titles[column]; }
        public Object getValueAt(int row, int column) {
            ChatMessage message = visible.get(row);
            switch (column) {
                case 0: return starred.contains(message) ? "★" : "";
                case 1: return message.clock();
                case 2: return message.channel.label + (reason(message).isEmpty() ? "" : " · Ignored");
                case 3: return message.playerLabel();
                default: return message.text.replace('\n', ' ').replace('\r', ' ');
            }
        }
    }

    private static Color channelColor(String channel) {
        switch (channel) {
            case "PM": return ContentStyle.color("rose");
            case "Party": return ContentStyle.color("blue");
            case "Guild": return ContentStyle.color("mint");
            case "System": return ContentStyle.color("amber");
            case "Ignored": return ContentStyle.color("muted");
            default: return ContentStyle.color("violet");
        }
    }

    private final class MessageRenderer extends ContentStyle.Cell {
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            boolean hasRow = row >= 0 && row < visible.size();
            setIcon(column == 0 && hasRow && starred.contains(visible.get(row)) ? STAR_ICON : null);
            if (column == 0) setText("");
            if (!selected) {
                if (hasRow && !reason(visible.get(row)).isEmpty()) setForeground(ContentStyle.color("rose"));
                else if (column == 0) setForeground(ContentStyle.color("amber"));
                else if (column == 1) setForeground(ContentStyle.color("muted"));
                else if (column == 3 && hasRow && visible.get(row).ownMessage) setForeground(ContentStyle.color("violet"));
            }
            setFont(column == 3 ? ContentStyle.emphasis(table.getFont()) : column == 1 ? ContentStyle.metadata(table.getFont()) : table.getFont());
            setToolTipText(null); // Packet text is rendered literally, never as Swing HTML.
            return this;
        }
    }
}
