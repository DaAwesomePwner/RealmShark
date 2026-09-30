package tomato.gui.quest;

import assets.IdToAsset;
import assets.ImageBuffer;
import packets.data.QuestData;
import tomato.backend.data.ProgressionData;
import tomato.backend.data.TomatoData;
import tomato.gui.stats.Formatters;
import tomato.gui.modern.ContentStyle;
import tomato.gui.history.FilterChips;
import tomato.gui.history.WrapRow;
import tomato.gui.kit.ColumnKind;
import tomato.gui.kit.CustomizableTabs;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.SegmentedControl;
import tomato.gui.kit.Sprites;
import tomato.gui.kit.Tokens;
import util.PropertiesManager;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import java.util.prefs.Preferences;

/**
 * Read-only quest planner. The server's requirements and reward choices remain authoritative.
 * The Board tab (id "captured") shows the quests as grouped painted cards (spec §6.5) or, as its Table view, the quest table with
 * its split detail and footer actions exactly as before; one filter row, search and drawer serve both, and the Sort order (the ⋯
 * menu's "Sort by ▸") is each view's order (the cards' within each group). Simple offers the other view in the ⋯ menu, Analyst a
 * Cards/Table toggle at the row's end; the view, grouping and "Pinned first" (a ⋯ item of the cards) persist ({@link #VIEW_KEY},
 * {@link #GROUP_KEY}, {@link #PINNED_FIRST_KEY}). Simple shows no account, capture or raw server detail (Analyst's). Card and board
 * models are built on the EDT from the page's detached quest copies, only when the list, pins, type labels, filters, sort,
 * grouping or "Pinned first" change and only while the cards show; the summary line re-reads its relative age once a minute.
 */
public class QuestGUI extends JPanel {
    static final String VIEW_KEY = "ui.quests.view", GROUP_KEY = "ui.quests.group", PINNED_FIRST_KEY = "ui.quests.pinned-first";
    /** Group-by choices and their saved ids, in QuestBoardModel.GroupBy order. */
    private static final String[] GROUPS = {"Chest tier", "Type label", "None"}, GROUP_IDS = {"tier", "type", "none"};
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
    private final CustomizableTabs views = new CustomizableTabs("quests");
    private final JTabbedPane tabs = views.component();
    /** The Sort order's choices (a "Sort by ▸" radio group in the filter row's ⋯) and their component-name ids. */
    private static final String[] SORTS = {"Pinned first", "Reward name", "Quest type", "Fewest required items", "Quest name"},
        SORT_IDS = {"pinned", "reward", "type", "fewest", "name"};
    private final JMenu sort = new JMenu("Sort by");
    private final JRadioButtonMenuItem[] sortItems = new JRadioButtonMenuItem[SORTS.length];
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
    private final FilterBar filterBar = new FilterBar("quests");
    private Runnable clearFilters = () -> {};
    // The Board's Cards view and its controls; the table, split detail and footer above are its Table view.
    private final JComboBox<String> groupBy = new JComboBox<>(GROUPS);
    private final JCheckBoxMenuItem pinnedFirst = new JCheckBoxMenuItem("Pinned first");
    private final SegmentedControl view = new SegmentedControl("quest-view", "Cards", "Table");
    /** The cards' own row control: "Group by", labeled inline. */
    private final JPanel boardControls = inline("Group by", groupBy);
    private final DisplayModeModel mode = DisplayModeModel.application();
    private final QuestDetail detail;
    private final QuestBoard board;
    private final ViewBody body = new ViewBody();
    private JSplitPane split;
    private JPanel pinActions, footer;
    private JMenuItem viewItem;
    private boolean cardsShown, ready, analyst;
    /** The quest the drawer shows and the card the user last selected (in either view), by pin key; null for none. */
    private String detailKey, cardKey;
    /** The visible quests' cards in the Sort order, as last built. */
    private List<QuestCardModel> cards = List.of();
    private QuestBoardModel.Summary summaryModel;
    /** When an unbound (legacy preview) panel last received a list; bound panels use the publication's capture time. */
    private long updatedAt;
    private LongSupplier clock = System::currentTimeMillis;
    private final javax.swing.Timer summaryTimer = new javax.swing.Timer(60_000, e -> showSummaryText());
    private int boardBuilds;

    public QuestGUI() {
        this(id -> {
            String name = IdToAsset.objectName(id);
            return name == null || name.isEmpty() ? "Unknown item #" + id : name;
        }, id -> ImageBuffer.getOutlinedIcon(id, 24), Sprites::sprite,
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
    public void openPlans() { views.show("plans"); views.select("plans"); tabs.requestFocusInWindow(); }
    /** Explicit navigation to the Board: shows its tab even when a saved order hid it, and selects it. */
    public void openBoard() { views.show("captured"); views.select("captured"); }

    private void listen() { if (source != null && !listening) { source.addListener(publicationListener); listening = true; } }
    @Override public void addNotify() {
        super.addNotify(); listen(); if (source != null) schedulePublication(); ageTimer.start(); summaryTimer.start();
    }
    @Override public void removeNotify() {
        ageTimer.stop(); summaryTimer.stop();
        if (listening) { source.removeListener(publicationListener); listening = false; }
        super.removeNotify();
    }
    private void schedulePublication() {
        if (scheduled.compareAndSet(false, true)) SwingUtilities.invokeLater(() -> { scheduled.set(false); applyPublication(); });
    }
    private void applyPublication() {
        ProgressionData.Snapshot next = source.snapshot();
        if (publication != null && next.quests == publication.quests && next.scope == publication.scope) return;
        if (publication != null && next.scope != publication.scope) { table.clearSelection(); cardKey = null; closeDetail(false); }
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
        if (detailKey != null) detail.pinEnabled(canPin());
    }

    /** Tests: injected names and icons; the cards and the detail drawer paint the same icon lookup (null paints the placeholder). */
    QuestGUI(IntFunction<String> names, IntFunction<Icon> images, Preferences preferences) {
        this(names, images, (id, size) -> images.apply(id), preferences);
    }

    private QuestGUI(IntFunction<String> names, IntFunction<Icon> images, QuestCardRenderer.SpriteLookup sprites, Preferences preferences) {
        this.names = names; this.images = images; this.preferences = preferences;
        detail = new QuestDetail(sprites);
        board = new QuestBoard(detail, sprites);
        setLayout(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        summary.setName("quest-summary"); count.setName("quest-count");
        search.setName("quest-search"); type.setName("quest-type"); reward.setName("quest-reward"); sort.setName("quest-sort");
        repeatMode.setName("quest-repeat-mode"); rewardMode.setName("quest-reward-mode"); expirationMode.setName("quest-expiration-mode");
        requirementItem.setName("quest-requirement-item"); requirementCount.setName("quest-requirement-count");
        onlyPinned.setName("quest-pinned-only"); completed.setName("quest-completed"); pin.setName("quest-pin");
        details.setName("quest-details"); table.setName("quest-table");
        table.getAccessibleContext().setAccessibleName("Captured quests");
        JPanel header = new JPanel(new BorderLayout(0, 8));
        JPanel descriptions = new JPanel(new BorderLayout(0, 4));
        context.setName("quest-capture-context"); context.getAccessibleContext().setAccessibleName("Quest account and freshness");
        descriptions.add(context, BorderLayout.NORTH); descriptions.add(summary, BorderLayout.SOUTH);
        header.add(descriptions, BorderLayout.NORTH);
        // Narrower, so the row keeps room for its chips in the shell (P6b); the tooltip keeps what the search reads.
        search.putClientProperty("JTextField.placeholderText", "Search quests or items…");
        search.setToolTipText("Search quest names, descriptions, types, rewards and required marks or tokens");
        search.getAccessibleContext().setAccessibleName("Search quests"); search.setColumns(18);
        JPanel filters = new JPanel(new BorderLayout(0, 6));
        // Wrap whole labeled fields using their font-aware preferred sizes, not 180px cells.
        JPanel selects = ContentStyle.controls();
        type.setPrototypeDisplayValue("Category 99999");
        reward.setPrototypeDisplayValue("Standard quest chests");
        reward.addPropertyChangeListener(e -> {
            if ("font".equals(e.getPropertyName()) || "UI".equals(e.getPropertyName())) sizeRewardChoice();
        });
        sizeRewardChoice();
        selects.add(field("Quest type", type)); selects.add(field("Reward", reward));
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
            completed.setSelected(false); onlyPinned.setSelected(false);
            sortItems[0].setSelected(true); table.getRowSorter().setSortKeys(null); // as choosing a Sort order does
            repeatMode.setSelectedIndex(0); rewardMode.setSelectedIndex(0); expirationMode.setSelectedIndex(0);
            requirementItem.setText(""); requirementCount.setValue(0);
            refreshing = false; refresh();
        });
        // Category labeling lives in the Filters drawer (spec §6.5), beside the pinned and completed filters.
        options.add(onlyPinned); options.add(completed); options.add(labels);
        filters.add(options, BorderLayout.CENTER);
        // One filter row (P6b: one control high at 1240×800 font 13 in the shell, in Simple and Analyst): the search, Reset filters
        // and the cards' grouping (a primary control, P4) with its label inline, wrapping whole when narrow; Analyst's Cards/Table
        // toggle is the bar's trailing control (Quests has no Scope chip). Sort by and Pinned first are ⋯ items (below), and every
        // narrowing filter lives in the drawer.
        groupBy.setName("quest-group-by");
        groupBy.setToolTipText("Group the cards by chest tier (from reward names), by your type labels, or not at all");
        boardControls.setName("quest-board-controls");
        view.getAccessibleContext().setAccessibleName("Board view");
        filterBar.search(new WrapRow(search, reset, boardControls)).scope(view).drawer(filters); clearFilters = reset::doClick;
        // The ⋯ menu: a "Sort by ▸" radio group (as Party's "Duration unit ▸"), the Sort order of the cards within each group and of
        // the table; and "Pinned first", the cards' own check item, shown while the cards show.
        sort.getAccessibleContext().setAccessibleName("Sort by");
        sort.setToolTipText("The order of the cards within each group, and of the table");
        ButtonGroup sorts = new ButtonGroup();
        for (int i = 0; i < SORTS.length; i++) {
            JRadioButtonMenuItem item = sortItems[i] = new JRadioButtonMenuItem(SORTS[i], i == 0);
            item.setName("quest-sort-" + SORT_IDS[i]);
            item.addActionListener(e -> { table.getRowSorter().setSortKeys(null); refresh(); });
            sorts.add(item); sort.add(item);
        }
        pinnedFirst.setName("quest-pinned-first");
        pinnedFirst.setToolTipText("Put pinned quests first within each group");
        header.add(filterBar, BorderLayout.CENTER);

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
                // Simple reads an unlabeled type as such, never the raw server category; the model, sort and search keep it.
                if (c == 2 && !analyst && typeLabel(visible.get(t.convertRowIndexToModel(r))).isEmpty()) {
                    setText(QuestBoardModel.NO_TYPE_TITLE);
                    setToolTipText("No type label: Name types… in Filters labels it");
                }
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
        table.getSelectionModel().addListSelectionListener(e -> {
            if (refreshing || e.getValueIsAdjusting()) return;
            showDetails();
            Quest q = selected(); cardKey = q == null ? null : key(q); // the user's row is the card the cards select
        });
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
        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, list, detailScroll) {
            @Override public void doLayout() {
                super.doLayout();
                int current = getUI().getDividerLocation(this);
                int usable = Math.max(getMinimumDividerLocation(), Math.min(current, getMaximumDividerLocation()));
                if (usable != current) { setDividerLocation(usable); super.doLayout(); }
            }
        };
        split.setName("quest-list-detail-split");
        split.setResizeWeight(.5); split.setDividerLocation(245); split.setBorder(null);
        footer = new JPanel(new BorderLayout(8, 0));
        count.setFont(ContentStyle.metadata(ContentStyle.body()));
        footer.add(count, BorderLayout.CENTER);
        pin.setEnabled(false); pin.addActionListener(e -> togglePin(selected()));
        removeGlobal.setVisible(false); removeGlobal.addActionListener(e -> removeGlobal(selected()));
        // The footer's pin actions are the Table view's; on the cards the detail drawer carries them.
        pinActions = ContentStyle.controls(); pinActions.add(removeGlobal); pinActions.add(pin); footer.add(pinActions, BorderLayout.EAST);
        JButton plan = new JButton("Add to account plan"); plan.setName("quest-add-plan"); pinActions.add(plan);
        plans = new QuestPlanPanel(tomato.planning.PlanningStore.shared(), this::itemName);
        plan.addActionListener(e -> addToPlan(selected()));
        body.add(split); body.add(board);
        JScrollPane page = ContentStyle.page(header, body, footer);
        page.setName("quest-page-scroll");
        page.getAccessibleContext().setAccessibleName("Quests; scroll for filters, selected details and actions");
        views.add("captured", "Board", page).add("plans", "Planner", plans); add(tabs, BorderLayout.CENTER);
        for (JComponent control : new JComponent[]{search, type, reward, repeatMode, rewardMode, expirationMode,
                requirementItem, requirementCount, onlyPinned, completed, labels, reset, pin, removeGlobal, plan, groupBy}) revealOnFocus(control);
        // Swing transfers spinner keyboard focus to its editor, not to the spinner itself.
        revealOnFocus(((JSpinner.DefaultEditor) requirementCount.getEditor()).getTextField());
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
        completed.addActionListener(e -> refresh()); onlyPinned.addActionListener(e -> refresh());

        // Cards view: grouping and "Pinned first" rebuild only the cards; the view, grouping and toggle persist.
        groupBy.setSelectedIndex(Math.max(0, Arrays.asList(GROUP_IDS).indexOf(PropertiesManager.getProperty(GROUP_KEY))));
        pinnedFirst.setSelected(!"false".equals(PropertiesManager.getProperty(PINNED_FIRST_KEY)));
        groupBy.addActionListener(e -> {
            PropertiesManager.setProperties(GROUP_KEY, GROUP_IDS[Math.max(0, groupBy.getSelectedIndex())]);
            groupBy.setToolTipText("Cards grouped by " + String.valueOf(groupBy.getSelectedItem()).toLowerCase(Locale.ROOT));
            refreshBoard();
        });
        pinnedFirst.addActionListener(e -> { PropertiesManager.setProperties(PINNED_FIRST_KEY, Boolean.toString(pinnedFirst.isSelected())); refreshBoard(); });
        view.onChange(index -> showCards(index == 0, true));
        viewItem = filterBar.overflow().add("Table view", () -> showCards(!cardsShown, true));
        viewItem.setName("quest-view-item");
        filterBar.overflow().section("arrange").replace(sort, pinnedFirst);
        board.onSelect(this::selectCard);
        board.onOpen(this::openDetail);
        detail.onPin(() -> togglePin(quest(detailKey)));
        detail.onRemoveGlobal(() -> removeGlobal(quest(detailKey)));
        detail.onPlan(() -> addToPlan(quest(detailKey)));
        detail.onClose(() -> closeDetail(true));
        summary.addPropertyChangeListener("UI", e -> { if (summaryModel != null) tintSummary(); }); // the stale tone follows the theme
        showCards(!"table".equals(PropertiesManager.getProperty(VIEW_KEY)), false);
        rebuildFilters(); showDetails(); showSummary();
        ready = true;
        refreshBoard();
        mode.bind(this, this::modeChanged);
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
        ColumnKind[] kinds = {ColumnKind.STATUS, ColumnKind.TEXT, ColumnKind.STATUS, ColumnKind.ITEM, ColumnKind.COUNT, ColumnKind.STATUS};
        for (int column = 0; column < examples.length; column++) {
            TableColumn value = table.getColumnModel().getColumn(column);
            Component heading = table.getTableHeader().getDefaultRenderer().getTableCellRendererComponent(
                table, value.getHeaderValue(), false, false, -1, column);
            int minimum = Math.max(heading.getPreferredSize().width + 8, table.getFontMetrics(table.getFont()).stringWidth(examples[column]) + 16);
            value.setMinWidth(minimum);
            value.setPreferredWidth(Math.max(minimum, kinds[column].width(table.getFont())));
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

    /**
     * A filter-row control labeled inline: the label left of it on its line, so the row stays one control high; the pair wraps whole.
     * The label names the control (its labelFor and accessible name), as {@link #field} does for the drawer's stacked fields.
     */
    private static JPanel inline(String title, JComponent component) {
        JPanel p = new JPanel(new BorderLayout(Tokens.XS + 2, 0));
        p.setOpaque(false);
        JLabel label = new JLabel(title); label.setLabelFor(component);
        label.setFont(ContentStyle.metadata(ContentStyle.body()));
        component.getAccessibleContext().setAccessibleName(title);
        p.add(label, BorderLayout.WEST); p.add(component, BorderLayout.CENTER); return p;
    }

    /** Copy mutable packet arrays before dispatching work to Swing. */
    public void update(QuestData[] data) {
        // Legacy previews have no source identity. Bound live views only accept the source mailbox.
        if (source != null) return;
        List<Quest> snapshot = new ArrayList<>();
        if (data != null) for (QuestData q : data) if (q != null) snapshot.add(new Quest(q));
        Runnable apply = () -> {
            quests = snapshot; captured = true; updatedAt = clock.getAsLong();
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

    /** Active quest filters as removable chips; sort order is not a filter. */
    private void updateChips() {
        List<FilterBar.ActiveFilter> chips = new ArrayList<>();
        for (JComboBox<String> box : Arrays.asList(type, reward, repeatMode, rewardMode, expirationMode))
            if (box.getSelectedIndex() > 0) chips.add(new FilterBar.ActiveFilter(String.valueOf(box.getSelectedItem()), () -> box.setSelectedIndex(0)));
        String item = requirementItem.getText().trim(); int quantity = (Integer) requirementCount.getValue();
        if (!item.isEmpty() || quantity > 0) chips.add(new FilterBar.ActiveFilter("Needs " + (item.isEmpty() ? "any item" : item) + (quantity > 0 ? " ≥ " + quantity : ""),
            () -> { requirementItem.setText(""); requirementCount.setValue(0); }));
        if (onlyPinned.isSelected()) chips.add(new FilterBar.ActiveFilter("Pinned only", onlyPinned::doClick));
        if (completed.isSelected()) chips.add(new FilterBar.ActiveFilter("Completed shown", completed::doClick));
        FilterChips.update(filterBar, chips, clearFilters, false);
    }
    private void refresh() {
        if (refreshing) return;
        updateChips();
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
        switch (sortOrder()) {
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
        showSummary();
        count.setText(captured ? visible.size() + " shown • Requirements shown; owned items not checked." : "No quests captured");
        for (JComboBox<String> combo : Arrays.asList(type, reward)) combo.setToolTipText((String)combo.getSelectedItem());
        showDetails();
        showContext();
        refreshBoard();
    }

    /** The Board summary over the whole captured list (not only the matches): count, pins, when captured, and stale. */
    private void showSummary() {
        int pins = 0, repeatable = 0, done = 0;
        for (Quest q : quests) { if (pinned.contains(key(q))) pins++; if (q.repeatable) repeatable++; if (q.completed) done++; }
        summaryModel = new QuestBoardModel.Summary(quests.size(), pins, repeatable, done, captured ? capturedAt() : 0, stale());
        showSummaryText();
    }

    /** Re-reads the summary's relative age (the minute timer), without rebuilding anything. */
    private void showSummaryText() {
        if (summaryModel == null) return;
        summary.setText(summaryModel.text(clock));
        tintSummary();
    }

    /** A stale list reads in the warn tone (spec §1: stale is labeled). */
    private void tintSummary() {
        summary.setForeground(summaryModel.stale() ? Tokens.tone(Tokens.Tone.WARN) : Tokens.color(Tokens.Role.TEXT));
    }

    /** When the shown list was received: the publication's capture time, or an unbound panel's last update; 0 when none. */
    private long capturedAt() {
        if (source == null) return captured ? updatedAt : 0;
        return publication == null || publication.quests == null ? 0 : publication.quests.capturedAt;
    }

    /** The page's existing rule: a bound list that no longer matches the current capture (scope) is stale. */
    private boolean stale() {
        return source != null && publication != null && publication.quests != null
            && !(publication.currentQuests() && source.scope() == publication.scope);
    }

    /** The user's own label for the quest's category, "" when unlabeled (no chip; never inferred). */
    private String typeLabel(Quest q) {
        String label = categoryNames.get(q.category);
        return label == null ? "" : label.trim();
    }

    private QuestBoardModel.GroupBy groupBy() { return QuestBoardModel.GroupBy.values()[Math.max(0, groupBy.getSelectedIndex())]; }

    /** The chosen Sort order: the index of the selected "Sort by ▸" item in {@link #SORTS}. */
    private int sortOrder() {
        for (int i = 0; i < sortItems.length; i++) if (sortItems[i].isSelected()) return i;
        return 0;
    }

    /**
     * Rebuilds the cards from the visible quests (the filters' result in the Sort order) and applies them: only when an input
     * changed (every caller is one) and only while the cards show; switching to the cards does it then.
     */
    private void refreshBoard() {
        if (!cardsShown || !ready) return;
        List<QuestCardModel> next = new ArrayList<>(visible.size());
        for (Quest q : visible) next.add(QuestCardModel.of(q, pinned.contains(key(q)), typeLabel(q), names));
        cards = List.copyOf(next);
        QuestBoardModel model = QuestBoardModel.build(cards, groupBy(), pinnedFirst.isSelected(), capturedAt(), stale());
        boardBuilds++;
        if (!captured) board.apply(model, "Plan your next turn-in",
            "Enter the Daily Quest Room during capture to load your quests. Each card shows what a quest awards and what to bring.");
        else if (quests.isEmpty()) board.apply(model, "No quests in the captured list",
            "The server sent an empty quest list. Enter the Daily Quest Room during capture to refresh it.");
        else board.apply(model, "No matching quests", "Change or reset your filters to show every captured quest.");
        board.select(cardKey, false);
        updateDetail();
        showFooter();
    }

    /**
     * Shows the cards or the table; the other stays in the tree, hidden and unmeasured. A user's choice is remembered. Keyboard focus
     * inside the view being hidden (e.g. the card Close returned it to) or on the ⋯ menu that switched it moves into the view being
     * shown, so it never falls out of the page; focus anywhere else (the search, the Analyst toggle) stays where it is.
     */
    private void showCards(boolean show, boolean remember) {
        boolean refocus = holdsFocus(show ? new Component[]{split, pinActions} : new Component[]{board, boardControls})
            || holdsFocus(filterBar.overflow(), filterBar.overflow().menu());
        cardsShown = show;
        if (remember) PropertiesManager.setProperties(VIEW_KEY, show ? "cards" : "table");
        split.setVisible(!show);
        board.setVisible(show);
        boardControls.setVisible(show);
        pinnedFirst.setVisible(show); // the cards' own ⋯ item
        pinActions.setVisible(!show);
        view.setSelected(show ? 0 : 1);
        viewItem.setText(show ? "Table view" : "Cards view");
        if (!show) closeDetail(false);
        body.revalidate(); body.repaint();
        refreshBoard();
        showFooter();
        if (refocus && !(show ? board.focusCards() : table.requestFocusInWindow())) search.requestFocusInWindow(); // no card: stay on the page
    }

    /**
     * The footer's count line, except in the Cards view while the Board shows its empty state (the summary and the empty state say
     * it once); the Table view keeps its footer. A footer with nothing to show takes no room.
     */
    private void showFooter() {
        boolean empty = cardsShown && board.groups().isEmpty();
        count.setVisible(!empty);
        footer.setVisible(!empty || pinActions.isVisible());
    }

    /** Whether the keyboard focus owner is one of {@code parts} or inside one (a popup menu's items included). */
    static boolean holdsFocus(Component... parts) {
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (owner != null) for (Component part : parts) if (part != null && SwingUtilities.isDescendingFrom(owner, part)) return true;
        return false;
    }

    /**
     * Analyst: the Cards/Table toggle in the filter row, the account and capture line above the summary, and the raw details (the
     * stable ID, the server category, the raw expiration) in the drawer and the Table view. Simple: the plain summary line alone
     * (stale still labeled there), the user's type labels only, and the other view in the ⋯ menu (hidden when alone). The hidden
     * line keeps its text current; the table's model, sort and search keep the raw category (renderer only).
     */
    private void modeChanged(DisplayModeModel.Mode value) {
        analyst = value == DisplayModeModel.Mode.ANALYST;
        context.setVisible(analyst);
        context.getParent().revalidate();
        table.repaint();
        showDetails();
        view.setVisible(analyst);
        viewItem.setVisible(!analyst);
        boolean items = false;
        for (Component item : filterBar.overflow().menu().getComponents()) items |= item instanceof JMenuItem && item.isVisible();
        filterBar.overflow().setVisible(items);
        filterBar.revalidate(); filterBar.repaint();
        updateDetail();
    }

    /** The user selected a card: the table selects the same quest, so the Table view and its actions follow. */
    private void selectCard(String key) {
        cardKey = key;
        for (int i = 0; i < visible.size(); i++) if (key(visible.get(i)).equals(key)) {
            int row = table.convertRowIndexToView(i);
            if (row < 0 || table.getSelectedRow() == row) return;
            refreshing = true;
            try { table.setRowSelectionInterval(row, row); } finally { refreshing = false; }
            showDetails();
            return;
        }
    }

    /** Enter, Space or a double-click on a card: the drawer shows it, revealed and focused at its Close button. */
    private void openDetail(QuestCardModel card) {
        detailKey = QuestBoard.key(card);
        selectCard(detailKey);
        updateDetail();
        if (!detail.isVisible()) return;
        SwingUtilities.invokeLater(() -> {
            if (!detail.isVisible()) return;
            ContentStyle.reveal(detail, new Rectangle(0, 0, detail.getWidth(), detail.getHeight()));
            detail.closeButton().requestFocusInWindow();
        });
    }

    /** Re-shows the drawer's quest from the newest cards and page state; closes it when the quest no longer shows. */
    private void updateDetail() {
        if (detailKey == null) return;
        QuestCardModel card = null;
        for (QuestCardModel c : cards) if (QuestBoard.key(c).equals(detailKey)) { card = c; break; }
        Quest q = quest(detailKey);
        if (!cardsShown || card == null || q == null) { closeDetail(false); return; }
        detail.show(card, new QuestDetail.State(pinText(q), canPin(), globalPinned.contains(detailKey), analyst));
    }

    /** Closes the drawer; {@code refocus} hands keyboard focus back to the card it showed. */
    private void closeDetail(boolean refocus) {
        String key = detailKey;
        detailKey = null;
        if (detail.card() != null || detail.isVisible()) detail.clear();
        if (refocus && key != null) board.focus(key);
    }

    /** The visible quest with pin key {@code key}, or null. */
    private Quest quest(String key) {
        if (key != null) for (Quest q : visible) if (key(q).equals(key)) return q;
        return null;
    }

    private String pinText(Quest q) { return pinned.contains(key(q)) ? "Unpin quest" : source == null ? "Pin quest" : "Pin for account"; }

    /** Tests: the Board's clock (the summary's "captured N ago" and an unbound list's receipt time). */
    void clock(LongSupplier value) { clock = Objects.requireNonNull(value, "clock"); showSummaryText(); }
    javax.swing.Timer summaryTimer() { return summaryTimer; }
    int boardBuilds() { return boardBuilds; }
    boolean cardsShown() { return cardsShown; }
    QuestBoard board() { return board; }
    QuestDetail detail() { return detail; }

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
            // As the drawer: Simple names the user's type label (never the server category) and leaves out the raw server details.
            String type = analyst ? typeName(q) : typeLabel(q).isEmpty() ? "No type label (Name types… in Filters)" : typeLabel(q);
            body.add(text(type + " • " + status(q) + " • " + (q.requirementsKnown ? q.requirements.length + " required items" : "Requirements not captured")));
            if (analyst) body.add(text(q.id.isEmpty() ? "Stable ID unavailable · provisional interest only" : "Stable quest ID: " + q.id));
            if (!q.description.isEmpty()) body.add(text(q.description));
            if (analyst) body.add(text("Expiration (raw server value): " + (q.expiration.isEmpty() ? "Not supplied" : q.expiration)));
            body.add(Box.createVerticalStrut(8));
            JPanel exchange = ContentStyle.responsiveGrid(2, 220, 8);
            exchange.add(itemPanel("BRING • all required items", q.requirements, q.requirementsKnown));
            exchange.add(itemPanel(q.choice ? "CHOOSE ONE • reward options" : "RECEIVE • all rewards", q.rewards, q.rewardsKnown));
            exchange.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(exchange);
            body.add(Box.createVerticalStrut(8));
            body.add(text("Compare turn-ins: select a reward above, then choose ⋯ › Sort by › Fewest required items. Pin quests you want to keep at the top."));
            if (analyst) body.add(text("Server category: " + q.category + " • Use Name types to label it Daily, Event, or Utility."));
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

    private String key(Quest q) { return QuestPins.key(q.id, q.name, q.category); }

    private String status(Quest q) {
        if (q.repeatable) return q.completed ? "Repeatable • completed before" : "Repeatable";
        return q.completed ? "Completed" : "One-time";
    }

    /** Pins or unpins {@code q} (the Table's selected row, or the drawer's quest). */
    private void togglePin(Quest q) {
        if (q == null || !canPin()) return;
        String key = key(q); boolean value = !pinned.contains(key);
        if (value) pinned.add(key); else pinned.remove(key);
        if (source == null) { if (preferences != null) preferences.putBoolean("pin." + key, value); }
        else new QuestPins(preferences).set(pinAccount(), key, value);
        QuestPinning.changed();
        refresh();
    }

    private void removeGlobal(Quest q) {
        if (q == null) return;
        new QuestPins(preferences).removeGlobal(key(q)); QuestPinning.changed(); globalPinned.remove(key(q)); refresh();
    }

    private void addToPlan(Quest q) {
        views.show("plans"); views.select("plans");
        if (q != null) plans.importQuest(q, globalPinned.contains(key(q)) ? key(q) : null);
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

    /** Holds the Table view's split and the cards; only the visible one is laid out and measured, so the page sizes to it alone. */
    private static final class ViewBody extends JPanel {
        ViewBody() { super(null); setOpaque(false); setName("quest-board-body"); }
        private Component shown() { for (Component child : getComponents()) if (child.isVisible()) return child; return null; }
        @Override public Dimension getPreferredSize() { Component c = shown(); return c == null ? new Dimension(0, 0) : c.getPreferredSize(); }
        @Override public Dimension getMinimumSize() { Component c = shown(); return c == null ? new Dimension(0, 0) : c.getMinimumSize(); }
        @Override public void doLayout() { for (Component child : getComponents()) if (child.isVisible()) child.setBounds(0, 0, getWidth(), getHeight()); }
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
