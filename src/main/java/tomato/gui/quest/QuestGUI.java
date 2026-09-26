package tomato.gui.quest;

import assets.IdToAsset;
import assets.ImageBuffer;
import packets.data.QuestData;
import tomato.backend.data.ProgressionData;
import tomato.backend.data.TomatoData;
import tomato.gui.stats.Formatters;
import tomato.gui.modern.ContentStyle;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.IntFunction;
import java.util.prefs.Preferences;

/** Read-only quest planner. The server's requirements and reward choices remain authoritative. */
public class QuestGUI extends JPanel {
    private final IntFunction<String> names;
    private final IntFunction<Icon> images;
    private final Preferences preferences;
    private final Map<Integer, String> categoryNames = new HashMap<>();
    private List<Quest> quests = new ArrayList<>();
    private List<Quest> visible = new ArrayList<>();
    private final Set<String> pinned = new HashSet<>();
    private final Set<String> globalPinned = new HashSet<>();
    private ProgressionData source;
    private ProgressionData.Snapshot publication;
    private final java.util.concurrent.atomic.AtomicBoolean scheduled = new java.util.concurrent.atomic.AtomicBoolean();
    private final Runnable publicationListener = this::schedulePublication;
    private boolean listening;
    private final JTextArea context = labelText("Unverified snapshot · Pins are global interests");
    private final javax.swing.Timer ageTimer = new javax.swing.Timer(1000, e -> showContext());
    private final JTextField search = new JTextField();
    private final JComboBox<String> type = new JComboBox<>();
    private final JComboBox<String> reward = new JComboBox<>();
    private final JComboBox<String> repeatMode = new JComboBox<>(new String[]{"Any repeatability", "Repeatable", "One-time"});
    private final JComboBox<String> rewardMode = new JComboBox<>(new String[]{"Any reward mode", "All rewards", "Choose one", "Rewards not captured"});
    private final JComboBox<String> expirationMode = new JComboBox<>(new String[]{"Any expiration", "Expiration supplied", "Expiration not supplied"});
    private final JTextField requirementItem = new JTextField(12);
    private final JSpinner requirementCount = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 1));
    private QuestPlanPanel plans;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JComboBox<String> sort = new JComboBox<>(new String[] {
        "Pinned first", "Reward name", "Quest type", "Fewest required items", "Quest name"
    });
    private final JCheckBox completed = new JCheckBox("Show completed");
    private final JCheckBox onlyPinned = new JCheckBox("Pinned only");
    private final JTextArea summary = labelText("Enter the Daily Quest Room during capture to load your quests.");
    private final JTextArea count = labelText("No quests captured");
    private final QuestModel model = new QuestModel();
    private final JTable table = new JTable(model);
    private final JPanel details = new DetailPanel();
    private final JButton pin = new JButton("Pin quest");
    private final JButton removeGlobal = new JButton("Remove global interest");
    private boolean refreshing;
    private boolean captured;
    private boolean columnSizingPending;

    public QuestGUI() {
        this(id -> {
            String name = IdToAsset.objectName(id);
            return name == null || name.isEmpty() ? "Unknown item #" + id : name;
        }, id -> ImageBuffer.getOutlinedIcon(id, 24),
            Preferences.userNodeForPackage(QuestGUI.class));
    }

    /** The shell binds this constructor to the same source used by packet ingestion. */
    public QuestGUI(TomatoData data) {
        this();
        bind(data);
    }
    QuestGUI(TomatoData data, IntFunction<String> names, IntFunction<Icon> images, Preferences preferences) {
        this(names, images, preferences); bind(data);
    }
    private void bind(TomatoData data) {
        source = Objects.requireNonNull(data).progression();
        plans.verification(() -> publication != null && publication.currentQuests() && source.scope() == publication.scope);
        listen(); applyPublication();
    }

    /** Supply detached known account keys from the character journal for offline selection. */
    public void knownPlanningAccounts(Collection<String> accounts) { plans.knownAccounts(accounts); }

    private void listen() { if (source != null && !listening) { source.addListener(publicationListener); listening = true; } }
    @Override public void addNotify() { super.addNotify(); listen(); if (source != null) schedulePublication(); ageTimer.start(); }
    @Override public void removeNotify() {
        ageTimer.stop();
        if (listening) { source.removeListener(publicationListener); listening = false; }
        super.removeNotify();
    }
    private void schedulePublication() {
        if (scheduled.compareAndSet(false, true)) SwingUtilities.invokeLater(() -> { scheduled.set(false); applyPublication(); });
    }
    private void applyPublication() {
        ProgressionData.Snapshot next = source.snapshot();
        if (publication != null && next.quests == publication.quests && next.scope == publication.scope) return;
        if (publication != null && next.scope != publication.scope) table.clearSelection();
        publication = next;
        quests = new ArrayList<>(); pinned.clear(); globalPinned.clear();
        captured = next.quests != null;
        if (captured) for (QuestData q : next.quests.rows()) quests.add(new Quest(q));
        loadPreferences(); rebuildFilters(); refresh();
        plans.observations(pinAccount(), next.currentQuests() && source.scope() == next.scope, quests,
            next.quests == null ? 0 : next.quests.capturedAt, next.scope.generation);
    }
    private String pinAccount() {
        return publication == null || publication.quests == null ? null : publication.quests.scope.account;
    }
    private void loadPreferences() {
        QuestPins store = new QuestPins(preferences);
        for (Quest q : quests) {
            if (!categoryNames.containsKey(q.category)) categoryNames.put(q.category,
                preferences == null ? "" : preferences.get("category." + q.category, ""));
            if (source == null ? store.global(key(q)) : store.pinned(pinAccount(), key(q))) pinned.add(key(q));
            if (source != null && store.global(key(q))) globalPinned.add(key(q));
        }
    }
    private boolean canPin() { return source == null || publication != null && publication.currentQuests() && source.scope() == publication.scope; }
    private void showContext() {
        if (source == null || publication == null) return;
        ProgressionData.Quests q = publication.quests;
        boolean current = publication.currentQuests() && source.scope() == publication.scope;
        String text = q == null ? "No quest list captured · " + publication.scope.description()
            : q.scope.description() + " · Captured " + Formatters.formatTimestamp(q.capturedAt) + " · "
                + Math.max(0, (System.currentTimeMillis() - q.capturedAt) / 1000) + "s ago · "
                + (current ? "Last captured list; server changes require a fresh capture" : "Stale / unverified for the current capture");
        context.setText(text + "\n" + (current ? "Account-scoped snapshot" : publication.scope.reason) + " · Account pins; legacy global interests retained.");
        pin.setEnabled(selected() != null && canPin());
    }

    QuestGUI(IntFunction<String> names, IntFunction<Icon> images, Preferences preferences) {
        this.names = names; this.images = images; this.preferences = preferences;
        setLayout(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        summary.setName("quest-summary"); count.setName("quest-count");
        search.setName("quest-search"); type.setName("quest-type"); reward.setName("quest-reward"); sort.setName("quest-sort");
        onlyPinned.setName("quest-pinned-only"); completed.setName("quest-completed"); pin.setName("quest-pin");
        details.setName("quest-details"); table.setName("quest-table");
        table.getAccessibleContext().setAccessibleName("Captured quests");
        JPanel header = new JPanel(new BorderLayout(0, 8));
        JPanel descriptions = new JPanel(new BorderLayout(0, 4));
        context.setName("quest-capture-context"); context.getAccessibleContext().setAccessibleName("Quest account and freshness");
        descriptions.add(context, BorderLayout.NORTH); descriptions.add(summary, BorderLayout.SOUTH);
        header.add(descriptions, BorderLayout.NORTH);
        search.putClientProperty("JTextField.placeholderText", "Search quests, rewards, marks or tokens…");
        search.getAccessibleContext().setAccessibleName("Search quests");
        header.add(search, BorderLayout.CENTER);
        JPanel filters = new JPanel(new BorderLayout(0, 6));
        // Wrap whole labeled fields using their font-aware preferred sizes, not 180px cells.
        JPanel selects = ContentStyle.controls();
        type.setPrototypeDisplayValue("Category 99999");
        reward.setPrototypeDisplayValue("Standard quest chests");
        reward.addPropertyChangeListener(e -> {
            if ("font".equals(e.getPropertyName()) || "UI".equals(e.getPropertyName())) sizeRewardChoice();
        });
        sizeRewardChoice();
        selects.add(field("Quest type", type)); selects.add(field("Reward", reward)); selects.add(field("Sort by", sort));
        selects.add(field("Repeatability", repeatMode)); selects.add(field("Reward mode", rewardMode));
        selects.add(field("Expiration", expirationMode)); selects.add(field("Required item name / ID", requirementItem));
        selects.add(field("Minimum quantity of item", requirementCount));
        filters.add(selects, BorderLayout.NORTH);
        JPanel options = ContentStyle.controls();
        JButton labels = new JButton("Name types…");
        labels.setName("quest-name-types");
        labels.setToolTipText("Label captured server categories Daily, Event, Utility, or your own name.");
        labels.addActionListener(e -> nameTypes());
        JButton reset = new JButton("Reset filters");
        reset.setName("quest-reset");
        reset.addActionListener(e -> {
            refreshing = true; search.setText(""); type.setSelectedIndex(0); reward.setSelectedIndex(0);
            completed.setSelected(false); onlyPinned.setSelected(false); sort.setSelectedIndex(0);
            repeatMode.setSelectedIndex(0); rewardMode.setSelectedIndex(0); expirationMode.setSelectedIndex(0);
            requirementItem.setText(""); requirementCount.setValue(0);
            refreshing = false; refresh();
        });
        options.add(onlyPinned); options.add(completed); options.add(labels); options.add(reset);
        filters.add(options, BorderLayout.CENTER); header.add(filters, BorderLayout.SOUTH);

        ContentStyle.table(table, ContentStyle.Density.COMFORTABLE);
        ContentStyle.tableFont(table, ContentStyle.body(), 32); // Room for 24px reward icons.
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new ContentStyle.Cell() {
            public Component getTableCellRendererComponent(JTable t, Object v, boolean selected, boolean focus, int r, int c) {
                super.getTableCellRendererComponent(t, v, selected, focus, r, c);
                setToolTipText(v == null ? null : v.toString());
                setIcon(null);
                if (c == 3) {
                    Quest q = visible.get(t.convertRowIndexToModel(r));
                    if (q.rewards.length > 0) setIcon(icon(q.rewards[0]));
                }
                return this;
            }
        });
        sizeColumns();
        table.addPropertyChangeListener(e -> {
            if ("font".equals(e.getPropertyName()) || "UI".equals(e.getPropertyName())) sizeColumnsLater();
        });
        table.getTableHeader().addPropertyChangeListener(e -> {
            if ("font".equals(e.getPropertyName()) || "UI".equals(e.getPropertyName())) sizeColumnsLater();
        });
        table.getSelectionModel().addListSelectionListener(e -> { if (!refreshing && !e.getValueIsAdjusting()) showDetails(); });
        JScrollPane list = ContentStyle.tableScroll(table, 3);
        JScrollPane detailScroll = new JScrollPane(details) {
            @Override public Dimension getMinimumSize() {
                Insets insets = getInsets();
                return new Dimension(0, Math.max(130, details.getFontMetrics(ContentStyle.body()).getHeight() * 3
                    + insets.top + insets.bottom));
            }
        };
        list.setName("quest-list-scroll"); detailScroll.setName("quest-detail-scroll");
        detailScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        detailScroll.getVerticalScrollBar().setUnitIncrement(28);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, list, detailScroll) {
            @Override public void doLayout() {
                super.doLayout();
                int current = getUI().getDividerLocation(this);
                int usable = Math.max(getMinimumDividerLocation(), Math.min(current, getMaximumDividerLocation()));
                if (usable != current) { setDividerLocation(usable); super.doLayout(); }
            }
        };
        split.setName("quest-list-detail-split");
        split.setResizeWeight(.5); split.setDividerLocation(245); split.setBorder(null);
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        count.setFont(ContentStyle.metadata(ContentStyle.body()));
        footer.add(count, BorderLayout.CENTER);
        pin.setEnabled(false); pin.addActionListener(e -> togglePin());
        removeGlobal.setVisible(false); removeGlobal.addActionListener(e -> {
            Quest q = selected(); if (q == null) return;
            new QuestPins(preferences).removeGlobal(key(q)); globalPinned.remove(key(q)); refresh();
        });
        JPanel pinActions = ContentStyle.controls(); pinActions.add(removeGlobal); pinActions.add(pin); footer.add(pinActions, BorderLayout.EAST);
        JButton plan = new JButton("Add to account plan"); plan.setName("quest-add-plan"); pinActions.add(plan);
        plans = new QuestPlanPanel(tomato.planning.PlanningStore.shared(), this::itemName);
        plan.addActionListener(e -> { Quest q = selected(); tabs.setSelectedIndex(1); if (q != null) plans.importQuest(q, globalPinned.contains(key(q)) ? key(q) : null); });
        JScrollPane page = ContentStyle.page(header, split, footer);
        page.setName("quest-page-scroll");
        page.getAccessibleContext().setAccessibleName("Quests; scroll for filters, selected details and actions");
        tabs.addTab("Captured quests", page); tabs.addTab("Saved plans", plans); add(tabs, BorderLayout.CENTER);
        for (JComponent control : new JComponent[]{search, type, reward, sort, onlyPinned, completed, labels, reset, pin, removeGlobal}) revealOnFocus(control);
        table.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) {
                ContentStyle.reveal(table, table.getCellRect(Math.max(0, table.getSelectedRow()), 1, true));
            }
        });

        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refresh(); }
            public void removeUpdate(DocumentEvent e) { refresh(); }
            public void changedUpdate(DocumentEvent e) { refresh(); }
        });
        for (JComboBox<String> combo : Arrays.asList(type, reward)) combo.addActionListener(e -> refresh());
        for (JComboBox<String> combo : Arrays.asList(repeatMode, rewardMode, expirationMode)) combo.addActionListener(e -> refresh());
        requirementCount.addChangeListener(e -> refresh());
        requirementItem.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refresh(); } public void removeUpdate(DocumentEvent e) { refresh(); } public void changedUpdate(DocumentEvent e) { refresh(); }
        });
        sort.addActionListener(e -> { table.getRowSorter().setSortKeys(null); refresh(); });
        completed.addActionListener(e -> refresh()); onlyPinned.addActionListener(e -> refresh());
        rebuildFilters(); showDetails();
    }

    private void sizeRewardChoice() {
        String widest = "";
        FontMetrics metrics = reward.getFontMetrics(reward.getFont());
        for (String value : new String[]{"All rewards", "Any quest chest", "Mighty quest chests", "Epic quest chests",
                "Standard quest chests", "Beginner quest chests"})
            if (metrics.stringWidth(value) > metrics.stringWidth(widest)) widest = value;
        reward.setPrototypeDisplayValue(widest);
    }

    private void sizeColumnsLater() {
        if (columnSizingPending) return;
        columnSizingPending = true;
        SwingUtilities.invokeLater(() -> { columnSizingPending = false; sizeColumns(); });
    }

    private void sizeColumns() {
        String[] examples = {"Global interest", "Quest name", "Category 99999", "Choose: Quest Chest", "999", "Repeatable • completed before"};
        int[] preferred = {40, 210, 105, 210, 75, 110};
        for (int column = 0; column < examples.length; column++) {
            TableColumn value = table.getColumnModel().getColumn(column);
            Component heading = table.getTableHeader().getDefaultRenderer().getTableCellRendererComponent(
                table, value.getHeaderValue(), false, false, -1, column);
            int minimum = Math.max(heading.getPreferredSize().width + 8, table.getFontMetrics(table.getFont()).stringWidth(examples[column]) + 16);
            value.setMinWidth(minimum);
            value.setPreferredWidth(Math.max(minimum, Math.round(preferred[column] * table.getFont().getSize2D() / ContentStyle.FONT_SIZE)));
        }
    }

    private static void revealOnFocus(JComponent control) {
        control.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) {
                ContentStyle.reveal(control, new Rectangle(0, 0, control.getWidth(), control.getHeight()));
            }
        });
    }

    private JPanel field(String title, JComponent component) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        JLabel label = new JLabel(title); label.setLabelFor(component);
        label.setFont(ContentStyle.metadata(ContentStyle.body()));
        component.getAccessibleContext().setAccessibleName(title);
        p.add(label, BorderLayout.NORTH); p.add(component, BorderLayout.CENTER); return p;
    }

    /** Copy mutable packet arrays before dispatching work to Swing. */
    public void update(QuestData[] data) {
        // Legacy previews have no source identity. Bound live views only accept the source mailbox.
        if (source != null) return;
        List<Quest> snapshot = new ArrayList<>();
        if (data != null) for (QuestData q : data) if (q != null) snapshot.add(new Quest(q));
        Runnable apply = () -> {
            quests = snapshot; captured = true;
            loadPreferences();
            rebuildFilters(); refresh();
        };
        if (SwingUtilities.isEventDispatchThread()) apply.run(); else SwingUtilities.invokeLater(apply);
    }

    private String typeName(Quest q) {
        String label = categoryNames.get(q.category);
        return label == null || label.trim().isEmpty() ? "Category " + q.category : label.trim();
    }

    private void rebuildFilters() {
        refreshing = true;
        String oldType = (String) type.getSelectedItem(), oldReward = (String) reward.getSelectedItem();
        TreeSet<String> types = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        TreeSet<String> rewards = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Quest q : quests) {
            types.add(typeName(q));
            for (int id : q.rewards) rewards.add(itemName(id));
        }
        type.removeAllItems(); type.addItem("All types"); for (String name : types) type.addItem(name);
        reward.removeAllItems(); reward.addItem("All rewards"); reward.addItem("Any quest chest");
        reward.addItem("Mighty quest chests"); reward.addItem("Epic quest chests");
        reward.addItem("Standard quest chests"); reward.addItem("Beginner quest chests");
        for (String name : rewards) reward.addItem(name);
        restore(type, oldType); restore(reward, oldReward);
        refreshing = false;
    }

    private void restore(JComboBox<String> combo, String value) {
        for (int i = 0; i < combo.getItemCount(); i++) if (Objects.equals(combo.getItemAt(i), value)) {
            combo.setSelectedIndex(i); return;
        }
        combo.setSelectedIndex(0);
    }

    private void refresh() {
        if (refreshing) return;
        Quest selected = selected();
        String selectedKey = selected == null ? null : key(selected);
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        visible = new ArrayList<>();
        for (Quest q : quests) {
            if (!completed.isSelected() && q.completed && !q.repeatable) continue;
            if (onlyPinned.isSelected() && !pinned.contains(key(q)) && !globalPinned.contains(key(q))) continue;
            if (type.getSelectedIndex() > 0 && !typeName(q).equals(type.getSelectedItem())) continue;
            if (!matchesReward(q, (String) reward.getSelectedItem())) continue;
            if (repeatMode.getSelectedIndex() == 1 && !q.repeatable || repeatMode.getSelectedIndex() == 2 && q.repeatable) continue;
            if (rewardMode.getSelectedIndex() == 1 && (!q.rewardsKnown || q.choice)
                || rewardMode.getSelectedIndex() == 2 && (!q.rewardsKnown || !q.choice)
                || rewardMode.getSelectedIndex() == 3 && q.rewardsKnown) continue;
            if (expirationMode.getSelectedIndex() == 1 && q.expiration.isEmpty() || expirationMode.getSelectedIndex() == 2 && !q.expiration.isEmpty()) continue;
            String required = requirementItem.getText().trim().toLowerCase(Locale.ROOT);
            int min = ((Number) requirementCount.getValue()).intValue();
            if (!required.isEmpty() || min > 0) {
                boolean found = false;
                for (Map.Entry<Integer, Integer> entry : quantities(q.requirements).entrySet())
                    if ((required.isEmpty() || String.valueOf(entry.getKey()).equals(required) || itemName(entry.getKey()).toLowerCase(Locale.ROOT).contains(required)) && entry.getValue() >= min) found = true;
                if (!q.requirementsKnown || !found) continue;
            }
            String haystack = q.id + " " + q.name + " " + q.description + " " + typeName(q) + " "
                + (q.requirementsKnown ? itemsText(q.requirements) : "Requirements not captured unknown") + " "
                + (q.rewardsKnown ? itemsText(q.rewards) : "Rewards not captured unknown");
            if (!haystack.toLowerCase(Locale.ROOT).contains(query)) continue;
            visible.add(q);
        }
        Comparator<Quest> byName = Comparator.comparing(q -> q.name.toLowerCase(Locale.ROOT));
        Comparator<Quest> order;
        switch (sort.getSelectedIndex()) {
            case 1: order = Comparator.comparing(q -> Arrays.stream(q.rewards).mapToObj(this::itemName)
                .sorted(String.CASE_INSENSITIVE_ORDER).findFirst().orElse("~").toLowerCase(Locale.ROOT)); break;
            case 2: order = Comparator.comparing(this::typeName); break;
            case 3: order = Comparator.comparingInt(q -> q.requirementsKnown ? q.requirements.length : Integer.MAX_VALUE); break;
            case 4: order = byName; break;
            default: order = Comparator.comparingInt(q -> pinned.contains(key(q)) ? 0 : 1);
        }
        visible.sort(order.thenComparing(byName));
        // Explicit sort controls and clickable column sorts work together until the user chooses a new sort.
        // Do not transiently disable a focused Pin button while restoring the same selected identity.
        refreshing = true;
        try {
            table.clearSelection();
            model.fireTableDataChanged();
            int select = -1;
            for (int i = 0; i < visible.size(); i++) if (key(visible.get(i)).equals(selectedKey)) select = i;
            if (select < 0 && !visible.isEmpty()) select = 0;
            if (select >= 0) { int view = table.convertRowIndexToView(select); table.setRowSelectionInterval(view, view); }
        } finally { refreshing = false; }
        long chests = quests.stream().filter(q -> !q.completed || q.repeatable).filter(q -> matchesReward(q, "Any quest chest")).count();
        summary.setText(captured ? quests.size() + " quests captured  •  " + chests + " chest reward quests"
            : "Enter the Daily Quest Room during capture to load your quests.");
        count.setText(captured ? visible.size() + " shown • Requirements shown; owned items not checked." : "No quests captured");
        for (JComboBox<String> combo : Arrays.asList(type, reward, sort)) combo.setToolTipText((String)combo.getSelectedItem());
        showDetails();
        showContext();
    }

    private boolean matchesReward(Quest q, String filter) {
        if (filter == null || filter.equals("All rewards")) return true;
        for (int id : q.rewards) {
            String name = itemName(id), lower = name.toLowerCase(Locale.ROOT);
            if (filter.equals(name)) return true;
            boolean chest = lower.contains("quest chest");
            if (filter.equals("Any quest chest") && chest) return true;
            if (filter.equals("Mighty quest chests") && chest && lower.contains("mighty")) return true;
            if (filter.equals("Epic quest chests") && chest && lower.contains("epic")) return true;
            if (filter.equals("Standard quest chests") && chest && (lower.contains("standard") || lower.equals("quest chest"))) return true;
            if (filter.equals("Beginner quest chests") && chest && lower.contains("beginner")) return true;
        }
        return false;
    }

    private Quest selected() {
        int view = table.getSelectedRow();
        if (view < 0) return null;
        int row = table.convertRowIndexToModel(view);
        return row >= 0 && row < visible.size() ? visible.get(row) : null;
    }

    private void showDetails() {
        details.removeAll();
        Quest q = selected(); pin.setEnabled(q != null && canPin());
        removeGlobal.setVisible(q != null && globalPinned.contains(key(q)));
        if (q == null) {
            pin.setText("Pin quest");
            details.add(text(captured ? "No matching quests. Change your filters or enter the Daily Quest Room to refresh."
                : "Plan your next turn-in\nSee exactly what to bring and what each quest awards."), BorderLayout.NORTH);
        } else {
            JPanel body = new JPanel(); body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            JTextArea title = text(q.name); title.setName("quest-detail-title");
            ContentStyle.font(title, ContentStyle.emphasis(ContentStyle.body()).deriveFont(ContentStyle.body().getSize2D() * 16f / ContentStyle.FONT_SIZE));
            body.add(title); body.add(Box.createVerticalStrut(6));
            body.add(text(typeName(q) + " • " + status(q) + " • " + (q.requirementsKnown ? q.requirements.length + " required items" : "Requirements not captured")));
            body.add(text(q.id.isEmpty() ? "Stable ID unavailable · provisional interest only" : "Stable quest ID: " + q.id));
            if (!q.description.isEmpty()) body.add(text(q.description));
            body.add(text("Expiration (raw server value): " + (q.expiration.isEmpty() ? "Not supplied" : q.expiration)));
            body.add(Box.createVerticalStrut(8));
            JPanel exchange = ContentStyle.responsiveGrid(2, 220, 8);
            exchange.add(itemPanel("BRING • all required items", q.requirements, q.requirementsKnown));
            exchange.add(itemPanel(q.choice ? "CHOOSE ONE • reward options" : "RECEIVE • all rewards", q.rewards, q.rewardsKnown));
            exchange.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(exchange);
            body.add(Box.createVerticalStrut(8));
            body.add(text("Compare turn-ins: select a reward above, then sort by Fewest required items. Pin quests you want to keep at the top."));
            body.add(text("Server category: " + q.category + " • Use Name types to label it Daily, Event, or Utility."));
            if (globalPinned.contains(key(q))) body.add(text("Legacy global interest · Not an account-specific plan."));
            details.add(body, BorderLayout.NORTH);
            pin.setText(pinned.contains(key(q)) ? "Unpin quest" : source == null ? "Pin quest" : "Pin for account");
        }
        details.revalidate(); details.repaint();
    }

    private JPanel itemPanel(String heading, int[] items, boolean known) {
        JPanel panel = new JPanel(); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JTextArea label = labelText(heading);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(label); panel.add(Box.createVerticalStrut(6));
        if (!known) panel.add(text("Not captured; requirements/rewards unknown."));
        else if (items.length == 0) panel.add(text("No items listed by the server."));
        for (Map.Entry<Integer, Integer> entry : quantities(items).entrySet()) {
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(new JLabel(icon(entry.getKey())), BorderLayout.WEST);
            JTextArea name = text(entry.getValue() + " × " + itemName(entry.getKey()));
            name.setToolTipText("Item ID: " + entry.getKey());
            row.add(name, BorderLayout.CENTER);
            row.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
            panel.add(row);
        }
        return panel;
    }

    /** Wrapping replacement for a static JLabel; descriptive text and editors retain keyboard access. */
    private static JTextArea labelText(String value) {
        JTextArea label = ContentStyle.wrappingText(value);
        label.setName("quest-static-label");
        label.setFocusable(false);
        return label;
    }

    private JTextArea text(String value) {
        JTextArea area = ContentStyle.wrappingText(value);
        ContentStyle.font(area, ContentStyle.body());
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        area.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) {
                revealCaret(area);
            }
        });
        // Caret scrolling within the detail viewport must also reveal that viewport in the outer page.
        area.addCaretListener(e -> { if (area.isFocusOwner()) SwingUtilities.invokeLater(() -> {
            if (area.isFocusOwner()) revealCaret(area);
        }); });
        return area;
    }

    private static void revealCaret(JTextArea area) {
        try { ContentStyle.reveal(area, area.modelToView(area.getCaretPosition())); }
        catch (javax.swing.text.BadLocationException e) { throw new IllegalStateException(e); }
    }

    private Icon icon(int id) { try { return images.apply(id); } catch (RuntimeException e) { return null; } }
    private String itemName(int id) {
        try { String name = names.apply(id); return name == null || name.isEmpty() ? "Unknown item #" + id : name; }
        catch (RuntimeException e) { return "Unknown item #" + id; }
    }

    static Map<Integer, Integer> quantities(int[] items) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int id : items) counts.put(id, counts.getOrDefault(id, 0) + 1);
        return counts;
    }

    private String itemsText(int[] items) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : quantities(items).entrySet())
            parts.add(entry.getValue() + " × " + itemName(entry.getKey()));
        return parts.isEmpty() ? "None listed" : String.join(", ", parts);
    }

    private String key(Quest q) {
        return UUID.nameUUIDFromBytes((q.id.isEmpty() ? q.name + ":" + q.category : q.id)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private String status(Quest q) {
        if (q.repeatable) return q.completed ? "Repeatable • completed before" : "Repeatable";
        return q.completed ? "Completed" : "One-time";
    }

    private void togglePin() {
        Quest q = selected(); if (q == null || !canPin()) return;
        String key = key(q); boolean value = !pinned.contains(key);
        if (value) pinned.add(key); else pinned.remove(key);
        if (source == null) { if (preferences != null) preferences.putBoolean("pin." + key, value); }
        else new QuestPins(preferences).set(pinAccount(), key, value);
        refresh();
    }

    /** Category numbers are not self-describing; never guess daily/event from chest rarity or repeatability. */
    private void nameTypes() {
        if (quests.isEmpty()) { JOptionPane.showMessageDialog(this, "Enter the Daily Quest Room during capture first."); return; }
        createTypesDialog().setVisible(true);
    }

    private JDialog createTypesDialog() {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "Name quest types", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setName("quest-types-dialog"); dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel form = new JPanel(); form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        Map<Integer, JComboBox<String>> fields = new TreeMap<>();
        for (Quest q : quests) if (!fields.containsKey(q.category)) {
            JComboBox<String> field = new JComboBox<>(new String[] {"", "Daily", "Event", "Utility", "Epic"});
            field.setEditable(true); field.setSelectedItem(categoryNames.get(q.category));
            field.setPrototypeDisplayValue("Quest category");
            field.setName("quest-category-" + q.category);
            field.getAccessibleContext().setAccessibleName("Label for category " + q.category);
            JComponent editor = (JComponent)field.getEditor().getEditorComponent();
            editor.getAccessibleContext().setAccessibleName("Label for category " + q.category);
            revealOnFocus(editor);
            fields.put(q.category, field);
            JPanel row = new JPanel(new BorderLayout(0, 4));
            row.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
            row.add(labelText("Category " + q.category + " • " + q.name), BorderLayout.NORTH);
            row.add(field, BorderLayout.CENTER); form.add(row);
        }
        JPanel prompt = new JPanel(new BorderLayout(0, 8));
        prompt.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JScrollPane page = ContentStyle.page(text("Match these groups to the tabs in your game. Labels are saved on this computer."), form, null);
        page.setName("quest-types-scroll"); prompt.add(page, BorderLayout.CENTER);
        JPanel actions = ContentStyle.controls();
        JButton ok = new JButton("OK"), cancel = new JButton("Cancel");
        ok.setName("quest-types-ok"); cancel.setName("quest-types-cancel");
        actions.add(ok); actions.add(cancel); prompt.add(actions, BorderLayout.SOUTH);
        cancel.addActionListener(e -> dialog.dispose());
        ok.addActionListener(event -> {
            for (Map.Entry<Integer, JComboBox<String>> e : fields.entrySet()) {
                Object edited = e.getValue().getEditor().getItem();
                String value = edited == null ? "" : edited.toString().trim();
                categoryNames.put(e.getKey(), value);
                if (preferences != null) preferences.put("category." + e.getKey(), value);
            }
            rebuildFilters(); refresh();
            dialog.dispose();
        });
        dialog.setContentPane(prompt); dialog.getRootPane().setDefaultButton(ok);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        ContentStyle.refreshFonts(dialog);
        Rectangle screen = getGraphicsConfiguration() == null ? GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds()
            : getGraphicsConfiguration().getBounds();
        dialog.setSize(Math.min(660, screen.width), Math.min(520, screen.height));
        dialog.setLocationRelativeTo(this);
        return dialog;
    }

    private static final class DetailPanel extends JPanel implements Scrollable {
        DetailPanel() { super(new BorderLayout()); }
        public Dimension getPreferredScrollableViewportSize() { return new Dimension(650, 280); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 28; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(28, r.height - 28); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private final class QuestModel extends AbstractTableModel {
        private final String[] columns = {"Pin", "Quest", "Type", "Rewards", "Needed", "Availability"};
        public int getRowCount() { return visible.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int c) { return columns[c]; }
        public Class<?> getColumnClass(int c) { return c == 4 ? Integer.class : String.class; }
        public Object getValueAt(int r, int c) {
            Quest q = visible.get(r);
            switch (c) {
                case 0: return pinned.contains(key(q)) ? "Yes" : globalPinned.contains(key(q)) ? "Global interest" : "";
                case 1: return q.name;
                case 2: return typeName(q);
                case 3: return q.rewardsKnown ? (q.choice ? "Choose: " : "") + itemsText(q.rewards) : "Not captured";
                case 4: return q.requirementsKnown ? q.requirements.length : null;
                default: return status(q);
            }
        }
    }

    static final class Quest {
        final String id, name, description, expiration;
        final int[] requirements, rewards;
        final int category;
        final boolean completed, repeatable, choice, requirementsKnown, rewardsKnown;
        Quest(QuestData q) {
            id = safe(q.id); name = safe(q.name); description = safe(q.description); expiration = safe(q.expiration);
            requirementsKnown = q.requirements != null; rewardsKnown = q.rewards != null;
            requirements = q.requirements == null ? new int[0] : q.requirements.clone();
            rewards = q.rewards == null ? new int[0] : q.rewards.clone();
            category = q.category; completed = q.completed; repeatable = q.repeatable; choice = q.itemOfChoice;
        }
        private static String safe(String s) { return s == null ? "" : s; }
    }
}
