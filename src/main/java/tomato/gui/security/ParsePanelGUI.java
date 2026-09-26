package tomato.gui.security;

import assets.IdToAsset;
import assets.ImageBuffer;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.backend.data.InspectSnapshot;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.history.ViewStateStore;
import tomato.gui.roster.RosterViewState;
import tomato.gui.modern.ContentStyle;
import tomato.realmshark.ParseEnchants;
import tomato.realmshark.enums.CharacterClass;
import util.PropertiesManager;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.table.TableColumn;
import packets.packetcapture.logger.ActivityJournal;
import tomato.gui.modern.DisplayFormat;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

public class ParsePanelGUI extends JPanel {
    private static volatile ParsePanelGUI INSTANCE;
    private static final String DISABLE_FILTER = "Default";
    private static final TreeMap<String, SecurityFilter> filters = new TreeMap<>();
    public static SecurityFilter currentFilter;
    private final boolean liveOwner;
    private final java.util.function.Supplier<RosterDefinitions> definitionsSource;
    private RosterDefinitions definitions = RosterDefinitions.empty();
    private SecurityFilter selectedFilter;
    private long requirementsRevision;
    private final JTextField rosterSearch = new JTextField(16);
    private final JToggleButton displayFilters = new JToggleButton("Display filters (0)");
    private final JComboBox<Choice<Integer>> classFacet = new JComboBox<>();
    private final JComboBox<Choice<String>> guildFacet = new JComboBox<>();
    private final JComboBox<String> seasonalFacet = new JComboBox<>(new String[]{"Any season", "Seasonal", "Non-seasonal", "Season unknown"});
    private final JComboBox<String> crucibleFacet = new JComboBox<>(new String[]{"Any crucible mode", "Crucible", "Not crucible", "Crucible unknown"});
    private final JComboBox<String> verdictFacet = new JComboBox<>(new String[]{"Any result", "Not evaluated", "Pass", "Below requirements", "Unknown"});
    private final JComboBox<String> maxedFacet = new JComboBox<>(new String[]{"Any maxed count", "Known maxed range", "Unknown maxed count"});
    private final JSpinner minMaxed = new JSpinner(new SpinnerNumberModel(0, 0, 8, 1)), maxMaxed = new JSpinner(new SpinnerNumberModel(8, 0, 8, 1));
    private final JTextArea resultDetails = ContentStyle.wrappingText("Select a player to explain requirements."), rosterCount = ContentStyle.wrappingText("No captured players");
    private RosterViewState viewState;
    private final JPanel stateHost = new JPanel(new BorderLayout());
    private boolean restoringState;
    private JToggleButton explain;

    // Latest state only: arrivals/updates never enqueue one Swing task per packet.
    private final Object rosterLock = new Object();
    private final Map<Integer, CapturedPlayer> roster = new LinkedHashMap<>();
    private long revision;
    private long themeRevision;
    private long displayedRevision = -1;
    private final RosterModel model = new RosterModel();
    private final JTable table = new JTable(model);
    private final JScrollPane rosterScroll = new JScrollPane(table);
    private final javax.swing.Timer refreshTimer;
    private final JComboBox<String> filterComboBox;
    private final JCheckBox copyOnlyUnderReqCheckbox;
    private final JCheckBox sortCheckBox;
    private final List<Action> playerActions = new ArrayList<>();
    private final List<Action> guildActions = new ArrayList<>();
    private boolean guiUpdateSuppression;
    private String displayedRun;
    private List<CapturedPlayer> historicalPlayers;
    private ActivityJournal.Visit inspectedRun;
    private tomato.history.link.VisitRef displayedSource;
    /** Pinned comparison baseline shared by every Inspect roster (EDT only); a detached copy. */
    private static BuildComparison.Build pinned;
    private final Action pinBaseline = action("Pin build as comparison baseline", e -> pinSelected());
    private final Action compareBaseline = action("Compare with pinned baseline…", e -> compareSelected());
    private final List<TableColumn> runColumns = new ArrayList<>();
    private boolean runColumnsVisible;

    public ParsePanelGUI() {
        this(true);
        bindViewState(ViewStateStore.application());
    }
    ParsePanelGUI(boolean liveOwner) {
        this(liveOwner, RosterDefinitions::current);
    }
    ParsePanelGUI(boolean liveOwner, java.util.function.Supplier<RosterDefinitions> definitionsSource) {
        this.liveOwner = liveOwner; this.definitionsSource = definitionsSource;
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setFont(ContentStyle.body());
        ContentStyle.table(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setDefaultRenderer(Object.class, new RosterCell());
        table.setDefaultRenderer(Integer.class, new RosterCell());
        table.setDefaultRenderer(Long.class, new RosterCell());
        table.setDefaultRenderer(Double.class, new RosterCell());
        TableRowSorter<RosterModel> sorter = new TableRowSorter<RosterModel>(model) {
            @Override public void toggleSortOrder(int column) {
                if ((column == 7 || column == 9 || column == 10) && (getSortKeys().isEmpty() || getSortKeys().get(0).getColumn() != column))
                    setSortKeys(Collections.singletonList(new RowSorter.SortKey(column, SortOrder.DESCENDING)));
                else super.toggleSortOrder(column);
            }
        };
        for (int column = 0; column < model.getColumnCount(); column++)
            if (model.getColumnClass(column) == String.class) sorter.setComparator(column, String.CASE_INSENSITIVE_ORDER);
        table.setRowSorter(sorter);
        table.getTableHeader().setToolTipText("Click a column to sort; click again to reverse. Maxed starts with 8/8 first.");
        table.getAccessibleContext().setAccessibleName("Captured player roster");
        int[] widths = {190, 185, 160, 75, 75, 75, 75, 90, 210, 110, 110, 165};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        for (int i = 9; i <= 10; i++) runColumns.add(table.getColumnModel().getColumn(i));
        for (TableColumn column : runColumns) table.removeColumn(column);
        rosterScroll.setName("security-roster-scroll");
        rosterScroll.getVerticalScrollBar().setUnitIncrement(40);
        RosterPage page = new RosterPage();
        page.add(rosterScroll, BorderLayout.CENTER);
        JScrollPane pageScroll = new JScrollPane(page) {
            @Override public Dimension getMinimumSize() {
                // A nested Inspect archive must retain a useful viewport even when its
                // filter controls scroll above the roster at compact sizes.
                Insets border = getInsets();
                // A saved-run roster sits beneath the run evidence inside an already scrolling archive page: keep its
                // controls, footer and at least three player rows so that page scrolls instead of reducing the
                // players table to a bare scroll bar.
                if (!ParsePanelGUI.this.liveOwner) return new Dimension(0, page.getPreferredSize().height + border.top + border.bottom);
                return new Dimension(0, table.getRowHeight() * 3
                        + table.getTableHeader().getPreferredSize().height
                        + rosterScroll.getHorizontalScrollBar().getPreferredSize().height
                        + border.top + border.bottom + 4);
            }
        };
        pageScroll.setName("security-page-scroll");
        pageScroll.setBorder(null);
        pageScroll.getVerticalScrollBar().setUnitIncrement(40);
        pageScroll.getAccessibleContext().setAccessibleName("Inspect page; scroll for controls at large text sizes");
        add(pageScroll, BorderLayout.CENTER);

        JPanel top = new JPanel(new BorderLayout(8, 4));
        JPanel filterRow = new JPanel(new BorderLayout(8, 0));
        filterComboBox = new JComboBox<>(new String[]{DISABLE_FILTER});
        filterComboBox.setPrototypeDisplayValue("Select an inspect filter");
        filterComboBox.getAccessibleContext().setAccessibleName("Inspect filter");
        filterComboBox.addActionListener(this::comboAction);
        JLabel filterLabel = new JLabel("Filter");
        filterLabel.setLabelFor(filterComboBox);
        filterRow.add(filterLabel, BorderLayout.WEST);
        filterRow.add(filterComboBox, BorderLayout.CENTER);
        JPanel options = ContentStyle.controls();
        options.setVisible(false);
        JToggleButton optionsButton = new JToggleButton("Options");
        optionsButton.getAccessibleContext().setAccessibleDescription("Show copy and sorting options");
        optionsButton.addActionListener(e -> {
            options.setVisible(optionsButton.isSelected());
            optionsButton.getAccessibleContext().setAccessibleDescription(optionsButton.isSelected()
                    ? "Copy and sorting options expanded" : "Copy and sorting options collapsed");
            page.revalidate();
        });
        filterRow.add(optionsButton, BorderLayout.EAST);
        copyOnlyUnderReqCheckbox = new JCheckBox("Only copy below or unknown requirements");
        copyOnlyUnderReqCheckbox.setToolTipText("Bulk copy/export uses the latest full current-area or selected-run roster; display facets are independent.");
        copyOnlyUnderReqCheckbox.setSelected("true".equals(PropertiesManager.getProperty("copyOnlyUnderReqCheckbox")));
        copyOnlyUnderReqCheckbox.addActionListener(e -> PropertiesManager.setProperties(
                "copyOnlyUnderReqCheckbox", Boolean.toString(copyOnlyUnderReqCheckbox.isSelected())));
        options.add(copyOnlyUnderReqCheckbox);
        sortCheckBox = new JCheckBox("Sort by guild");
        sortCheckBox.setSelected(!"false".equals(PropertiesManager.getProperty("sortCheckBox")));
        sortCheckBox.addActionListener(e -> {
            PropertiesManager.setProperties("sortCheckBox", Boolean.toString(sortCheckBox.isSelected()));
            table.getRowSorter().setSortKeys(Collections.emptyList());
            requestRefresh();
        });
        options.add(sortCheckBox);
        top.add(filterRow, BorderLayout.NORTH);
        top.add(options, BorderLayout.CENTER);
        JPanel facets = ContentStyle.controls();
        JPanel searchActions = ContentStyle.controls();
        rosterSearch.setName("inspect-roster-search"); rosterSearch.getAccessibleContext().setAccessibleName("Search displayed roster");
        searchActions.add(rosterSearch);
        displayFilters.setName("inspect-display-filters");
        displayFilters.setToolTipText("Expand display filters; the count shows active advanced filters even while collapsed.");
        searchActions.add(displayFilters);
        facets.setVisible(false);
        displayFilters.addActionListener(e -> { facets.setVisible(displayFilters.isSelected()); updateDisplayFilterSummary(); page.revalidate(); });
        classFacet.addItem(new Choice<>(null, "All classes"));
        guildFacet.addItem(new Choice<>(null, "All guilds")); guildFacet.addItem(new Choice<>(null, "No guild (captured)")); guildFacet.addItem(new Choice<>(null, "Guild not captured"));
        JComponent[] controls = {classFacet, guildFacet, seasonalFacet, crucibleFacet, verdictFacet, maxedFacet, minMaxed, maxMaxed};
        String[] facetNames = {"Class", "Guild", "Season", "Crucible", "Requirements result", "Maxed count", "Minimum maxed", "Maximum maxed"};
        for (int i = 0; i < controls.length; i++) { controls[i].setName("inspect-facet-" + i); controls[i].getAccessibleContext().setAccessibleName(facetNames[i]); facets.add(controls[i]); }
        JButton reset = new JButton("Reset display filters"); searchActions.add(reset);
        reset.addActionListener(e -> {
            guiUpdateSuppression = true; rosterSearch.setText("");
            for (JComboBox<?> combo : new JComboBox<?>[]{classFacet, guildFacet, seasonalFacet, crucibleFacet, verdictFacet, maxedFacet}) combo.setSelectedIndex(0);
            minMaxed.setValue(0); maxMaxed.setValue(8); guiUpdateSuppression = false; requestRefresh();
        });
        for (JComboBox<?> combo : new JComboBox<?>[]{classFacet, guildFacet, seasonalFacet, crucibleFacet, verdictFacet, maxedFacet})
            combo.addActionListener(e -> { if (!guiUpdateSuppression) requestRefresh(); });
        minMaxed.addChangeListener(e -> { if (!guiUpdateSuppression) requestRefresh(); });
        maxMaxed.addChangeListener(e -> { if (!guiUpdateSuppression) requestRefresh(); });
        rosterSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { if (!guiUpdateSuppression) requestRefresh(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { insertUpdate(e); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { insertUpdate(e); }
        });
        JPanel displayControls = new JPanel(new BorderLayout(0, 2));
        displayControls.add(searchActions, BorderLayout.NORTH); displayControls.add(facets);
        top.add(displayControls, BorderLayout.SOUTH);
        page.add(top, BorderLayout.NORTH);

        JPopupMenu actions = new JPopupMenu("Roster actions");
        actions.getAccessibleContext().setAccessibleName("Roster actions and keyboard shortcuts");
        addSelectionAction(actions, "Copy player", false, false, KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK));
        addSelectionAction(actions, "Open player on RealmEye", false, true, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        addSelectionAction(actions, "Copy guild", true, false, KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        addSelectionAction(actions, "Open guild on RealmEye", true, true, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK));
        Action equipment = action("Equipment details…", e -> selectedEquipmentDetails());
        equipment.putValue(Action.SHORT_DESCRIPTION, "Read all equipment and enchant details for the selected row (Ctrl+E)");
        playerActions.add(equipment);
        addMenuAction(actions, equipment, KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK));
        pinBaseline.putValue(Action.SHORT_DESCRIPTION, "Copy the selected recorded build as the comparison baseline (Ctrl+B)");
        compareBaseline.putValue(Action.SHORT_DESCRIPTION, "Compare the selected build with the pinned baseline (Ctrl+Shift+B)");
        playerActions.add(pinBaseline);
        addMenuAction(actions, pinBaseline, KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK));
        addMenuAction(actions, compareBaseline, KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        table.getSelectionModel().addListSelectionListener(e -> updateSelectionActions());

        JPanel buttons = ContentStyle.controls();
        JButton names = new JButton(action("Copy names", e -> {
            if (shiftDown(e)) saveNamesAsText(); else clicked(false);
        }));
        JButton all = new JButton(action("Copy all (JSON)", e -> {
            if (shiftDown(e)) saveAsJson(getFilteredPlayers()); else clicked(true);
        }));
        names.setToolTipText("Copy names; Shift+click exports a text file.");
        all.setToolTipText("Copy JSON; Shift+click exports a JSON file.");
        buttons.add(names);
        buttons.add(all);
        actions.addSeparator();
        addMenuAction(actions, action("Export names…", e -> saveNamesAsText()),
                KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK));
        addMenuAction(actions, action("Export JSON…", e -> saveAsJson(getFilteredPlayers())),
                KeyStroke.getKeyStroke(KeyEvent.VK_J, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK));
        actions.addSeparator();
        actions.add(new JMenuItem(action("Edit filters…", e -> SecurityFilterGUI.open(this))));
        JButton actionsButton = new JButton("Actions…");
        actionsButton.setMnemonic(KeyEvent.VK_A);
        actionsButton.setToolTipText("Player/guild actions, equipment details (Ctrl+E), exports and filters (Alt+A).");
        actionsButton.setComponentPopupMenu(actions);
        actionsButton.addActionListener(e -> {
            SwingUtilities.updateComponentTreeUI(actions);
            ContentStyle.refreshFonts(actions);
            actions.show(actionsButton, 0, actionsButton.getHeight());
            for (Component item : actions.getComponents()) if (item instanceof JMenuItem && item.isEnabled()) {
                MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[]{actions, (JMenuItem) item});
                break;
            }
        });
        buttons.add(actionsButton);
        JPanel footer = new JPanel(new BorderLayout(0, 4));
        rosterCount.setName("inspect-roster-count"); resultDetails.setName("inspect-requirement-reasons"); resultDetails.setFocusable(true); resultDetails.setVisible(false);
        explain = new JToggleButton("Requirement details"); buttons.add(explain);
        explain.addActionListener(e -> { resultDetails.setVisible(explain.isSelected()); page.revalidate(); rememberViewState(); });
        JPanel bottom = new JPanel(new BorderLayout(0, 4)); bottom.add(buttons, BorderLayout.NORTH); bottom.add(stateHost, BorderLayout.SOUTH); stateHost.setVisible(false);
        footer.add(rosterCount, BorderLayout.NORTH); footer.add(resultDetails, BorderLayout.CENTER); footer.add(bottom, BorderLayout.SOUTH);
        page.add(footer, BorderLayout.SOUTH);
        for (JComponent control : new JComponent[]{rosterSearch, displayFilters, classFacet, guildFacet, seasonalFacet, crucibleFacet, verdictFacet, maxedFacet, minMaxed, maxMaxed, reset, explain}) {
            JComponent focus = control instanceof JSpinner ? ((JSpinner.DefaultEditor)((JSpinner)control).getEditor()).getTextField() : control;
            focus.addFocusListener(new FocusAdapter() { public void focusGained(FocusEvent e) { ContentStyle.reveal(control, new Rectangle(0, 0, control.getWidth(), control.getHeight())); } });
        }
        resultDetails.addFocusListener(new FocusAdapter() { public void focusGained(FocusEvent e) {
            try { Rectangle caret = resultDetails.modelToView(resultDetails.getCaretPosition()); if (caret != null) ContentStyle.reveal(resultDetails, caret); }
            catch (javax.swing.text.BadLocationException impossible) { throw new IllegalStateException(impossible); }
        } });

        bind("copy-player", KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), false, false);
        bind("open-player", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), false, true);
        bind("copy-guild", KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), true, false);
        bind("open-guild", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), true, true);
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                int row = table.rowAtPoint(e.getPoint());
                int column = table.columnAtPoint(e.getPoint());
                if (row < 0 || column < 0) return;
                column = table.convertColumnIndexToModel(column);
                if (column == 0 || column == 1) {
                    table.setRowSelectionInterval(row, row);
                    selectedAction(column == 1, e.isControlDown());
                }
            }
        });
        refreshTimer = new javax.swing.Timer(100, e -> refreshRoster());
        refreshTimer.setCoalesce(true);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (isShowing()) { refreshRoster(); refreshTimer.start(); }
                else refreshTimer.stop();
            }
        });
        loadFilters();
        updateSelectionActions();
        ContentStyle.refreshFonts(this);
        if (liveOwner) INSTANCE = this;
    }

    @Override public void updateUI() {
        super.updateUI();
        // JPanel calls updateUI before these fields are initialized. Invalidate only;
        // the visible refresh timer (or refresh-on-show) rebuilds themed icon rows.
        if (rosterLock != null) synchronized (rosterLock) { themeRevision++; revision++; }
    }

    private static Action action(String name, java.util.function.Consumer<ActionEvent> handler) {
        return new AbstractAction(name) {
            @Override public void actionPerformed(ActionEvent e) { handler.accept(e); }
        };
    }

    private static boolean shiftDown(ActionEvent e) {
        return (e.getModifiers() & (ActionEvent.SHIFT_MASK | InputEvent.SHIFT_DOWN_MASK)) != 0;
    }

    private void addSelectionAction(JPopupMenu menu, String name, boolean guild, boolean open, KeyStroke shortcut) {
        Action action = action(name, e -> selectedAction(guild, open));
        (guild ? guildActions : playerActions).add(action);
        JMenuItem item = new JMenuItem(action);
        item.setAccelerator(shortcut);
        item.getAccessibleContext().setAccessibleDescription("For the selected roster row; " + shortcut);
        menu.add(item);
    }

    private void addMenuAction(JPopupMenu menu, Action action, KeyStroke shortcut) {
        JMenuItem item = new JMenuItem(action);
        item.setAccelerator(shortcut);
        menu.add(item);
        String name = String.valueOf(action.getValue(Action.NAME));
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(shortcut, name);
        getActionMap().put(name, action);
    }

    private void bind(String name, KeyStroke key, boolean guild, boolean open) {
        table.getInputMap().put(key, name);
        table.getActionMap().put(name, action(name, e -> selectedAction(guild, open)));
    }

    private Row selectedRow() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= table.getRowCount()) return null;
        int index = table.convertRowIndexToModel(row);
        return index < model.rows.size() ? model.rows.get(index) : null;
    }

    private void updateSelectionActions() {
        Row row = selectedRow();
        playerActions.forEach(a -> a.setEnabled(row != null));
        compareBaseline.setEnabled(row != null && pinned != null);
        guildActions.forEach(a -> a.setEnabled(row != null && !row.guild.isEmpty()));
        resultDetails.setText(row == null ? "Select a player to explain requirements." : inspectionName(row) + " · " + row.player.origin
            + "\n" + (row.player.currentArea ? "Current-area snapshot" : "Recorded snapshot") + " · Build/change time: "
            + (row.player.recordedAt > 0 ? DisplayFormat.formatTimestamp(row.player.recordedAt) : "Not captured") + "\n" + row.requirements.description());
    }

    private void selectedAction(boolean guild, boolean open) {
        Row row = selectedRow();
        if (row == null) return;
        String value = guild ? row.guild : row.player.playerEntity.name();
        if (value == null || value.isEmpty()) return;
        if (open) openWebpage("https://www.realmeye.com/" + (guild ? "guild/" : "player/") + value.replace(" ", "%20"));
        else copyToClipboard(value);
    }

    private void selectedEquipmentDetails() {
        Row row = selectedRow();
        if (row == null) return;
        // Use the displayed row, never the newer producer roster or the current filter.
        showEquipmentDetails(inspectionName(row), inspectionDetails(row));
    }

    /** Opens the same Inspect details for a detached player from another view or saved encounter. */
    public static void inspectPlayer(Component owner, InspectSnapshot captured) { inspectPlayer(owner, captured, null); }

    /** As {@link #inspectPlayer(Component, InspectSnapshot)}, naming the source view/recording and its link, when known. */
    public static void inspectPlayer(Component owner, InspectSnapshot captured, String origin) {
        if (captured == null) return;
        Row row = detachedRow(captured, origin);
        showEquipmentDetails(owner, inspectionName(row), inspectionDetails(row));
    }

    static String detachedDetails(InspectSnapshot captured) {
        return inspectionDetails(detachedRow(captured, null));
    }
    static String detachedDetails(InspectSnapshot captured, String origin) {
        return inspectionDetails(detachedRow(captured, origin));
    }

    private static Row detachedRow(InspectSnapshot captured, String origin) {
        Entity entity = captured.toEntity();
        CapturedPlayer player = snapshot(entity.id, entity);
        player.className = captured.className();
        player.origin = "Detached recorded build · " + (origin == null || origin.isEmpty() ? "Source session/run not supplied" : origin);
        player.currentArea = false;
        return new Row(player, 0, null);
    }

    private static String inspectionName(Row row) {
        String name = row.player.playerEntity.name();
        return name == null || name.isEmpty() ? "Player #" + row.player.playerEntity.id : name;
    }

    private static String inspectionDetails(Row row) {
        Entity entity = row.player.playerEntity;
        StatData level = entity.stat.get(StatType.LEVEL_STAT);
        String clazz = Objects.toString(row.player.className, Objects.toString(CharacterClass.getName(entity.objectType), "Unknown class"));
        String mode = Player.modeDescription(entity);
        return row.player.origin + "\nRecorded build/change time: " + (row.player.recordedAt > 0 ? DisplayFormat.formatTimestamp(row.player.recordedAt) : "Not captured")
                + "\nThis is the retained loadout, not continuous last-seen evidence or equipment at every hit."
                + "\n\nPlayer: " + inspectionName(row) + "\nClass: " + clazz + "\nLevel: " + (level == null ? "Not captured" : level.statValue)
                + "\nGuild: " + (entity.getStatGuild() == null ? "Not captured" : row.guild.isEmpty() ? "None (captured)" : row.guild) + "\nCharacter mode: " + mode
                + "\n\n" + row.player.statsDescription(row.definitions) + "\n\n" + String.join("\n\n", row.equipmentDetails);
    }

    protected void showEquipmentDetails(String playerName, String details) {
        showEquipmentDetails(this, playerName, details);
    }

    private static void showEquipmentDetails(Component owner, String playerName, String details) {
        Window window = owner == null ? null : owner instanceof Window ? (Window)owner : SwingUtilities.getWindowAncestor(owner);
        JDialog dialog = new JDialog(window, "Equipment — " + playerName,
                Dialog.ModalityType.MODELESS);
        dialog.setName("security-equipment-dialog");
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JTextArea body = ContentStyle.wrappingText(details, 8);
        body.setName("security-equipment-details");
        body.setFocusable(true);
        body.getAccessibleContext().setAccessibleName("Equipment and enchant details for " + playerName);
        body.getAccessibleContext().setAccessibleDescription("Read-only snapshot of the selected roster row. Select text to copy.");
        body.setCaretPosition(0);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JScrollPane scroll = new JScrollPane(body);
        FontMetrics metrics = body.getFontMetrics(body.getFont());
        scroll.setPreferredSize(new Dimension(Math.min(720, metrics.charWidth('m') * 60), Math.min(480, metrics.getHeight() * 16)));
        content.add(scroll, BorderLayout.CENTER);
        JButton close = new JButton("Close"); close.addActionListener(e -> dialog.dispose());
        content.add(close, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(close);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), WHEN_IN_FOCUSED_WINDOW);
        ContentStyle.refreshFonts(content);
        dialog.pack();
        Rectangle bounds = dialog.getGraphicsConfiguration().getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(dialog.getGraphicsConfiguration());
        dialog.setSize(Math.min(dialog.getWidth(), bounds.width - insets.left - insets.right),
                Math.min(dialog.getHeight(), bounds.height - insets.top - insets.bottom));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        body.requestFocusInWindow();
    }

    private void loadFilters() {
        guiUpdateSuppression = true;
        String saved = PropertiesManager.getProperty("securityFilters");
        if (liveOwner || filters.isEmpty()) {
            filters.clear();
            if (saved != null && !saved.isEmpty()) for (String json : saved.split("§")) {
                SecurityFilter filter = SecurityFilter.loadJson(json);
                if (filter != null && filter.name != null) filters.put(filter.name, filter);
            }
        }
        for (String name : filters.keySet()) filterComboBox.addItem(name);
        selectedFilter = filters.get(Objects.toString(PropertiesManager.getProperty("securityFilterName"), ""));
        if (liveOwner) currentFilter = selectedFilter;
        filterComboBox.setSelectedItem(selectedFilter == null ? DISABLE_FILTER : selectedFilter.name);
        guiUpdateSuppression = false;
        copyOnlyUnderReqCheckbox.setEnabled(selectedFilter != null);
    }

    private void comboAction(ActionEvent e) {
        if (guiUpdateSuppression) return;
        selectedFilter = filters.get(String.valueOf(filterComboBox.getSelectedItem()));
        if (liveOwner && historicalPlayers == null) { currentFilter = selectedFilter; PropertiesManager.setProperties("securityFilterName", selectedFilter == null ? "" : selectedFilter.name); }
        copyOnlyUnderReqCheckbox.setEnabled(selectedFilter != null);
        requirementsRevision++;
        requestRefresh();
    }

    TreeMap<String, SecurityFilter> getFilters() { return filters; }
    SecurityFilter selectedFilter() { return selectedFilter; }

    public void filterUpdate() {
        SecurityFilter active = liveOwner ? currentFilter : selectedFilter;
        String selected = active == null ? DISABLE_FILTER : active.name;
        guiUpdateSuppression = true;
        filterComboBox.removeAllItems();
        filterComboBox.addItem(DISABLE_FILTER);
        for (String name : filters.keySet()) filterComboBox.addItem(name);
        filterComboBox.setSelectedItem(filters.containsKey(selected) ? selected : DISABLE_FILTER);
        guiUpdateSuppression = false;
        comboAction(null);
    }

    private void requestRefresh() {
        synchronized (rosterLock) { revision++; }
        rememberViewState();
    }

    private InspectRosterQuery displayQuery() {
        int guild = guildFacet.getSelectedIndex();
        return new InspectRosterQuery(rosterSearch.getText(), choice(classFacet), guild == 0 ? InspectRosterQuery.Guild.ANY
            : guild == 1 ? InspectRosterQuery.Guild.NONE : guild == 2 ? InspectRosterQuery.Guild.UNKNOWN : InspectRosterQuery.Guild.EXACT,
            choice(guildFacet), InspectRosterQuery.Mode.values()[seasonalFacet.getSelectedIndex()], InspectRosterQuery.Mode.values()[crucibleFacet.getSelectedIndex()],
            verdictFacet.getSelectedIndex() == 0 ? null : RequirementResult.Verdict.values()[verdictFacet.getSelectedIndex() - 1],
            maxedFacet.getSelectedIndex() == 1, maxedFacet.getSelectedIndex() == 2, (Integer)minMaxed.getValue(), (Integer)maxMaxed.getValue());
    }
    private void rebuildDisplayChoices(List<CapturedPlayer> players) {
        Integer selectedClass = choice(classFacet); String selectedGuild = choice(guildFacet); int guildMode = guildFacet.getSelectedIndex();
        Map<Integer, String> classes = new TreeMap<>(); Set<String> guilds = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (CapturedPlayer player : players) {
            classes.put(player.playerEntity.objectType, Objects.toString(player.className, "Class " + player.playerEntity.objectType));
            String guild = player.playerEntity.getStatGuild(); if (guild != null && !guild.isEmpty()) guilds.add(guild);
        }
        if (selectedClass != null) classes.putIfAbsent(selectedClass, "Class " + selectedClass);
        if (selectedGuild != null) guilds.add(selectedGuild);
        guiUpdateSuppression = true;
        classFacet.removeAllItems(); classFacet.addItem(new Choice<>(null, "All classes"));
        guildFacet.removeAllItems(); guildFacet.addItem(new Choice<>(null, "All guilds")); guildFacet.addItem(new Choice<>(null, "No guild (captured)")); guildFacet.addItem(new Choice<>(null, "Guild not captured"));
        for (Map.Entry<Integer, String> entry : classes.entrySet()) {
            Choice<Integer> value = new Choice<>(entry.getKey(), entry.getValue()); classFacet.addItem(value);
            if (entry.getKey().equals(selectedClass)) classFacet.setSelectedItem(value);
        }
        for (String guild : guilds) {
            Choice<String> value = new Choice<>(guild, guild); guildFacet.addItem(value);
            if (selectedGuild != null && guild.equalsIgnoreCase(selectedGuild)) guildFacet.setSelectedItem(value);
        }
        if (guildMode < 3) guildFacet.setSelectedIndex(Math.max(0, guildMode));
        guiUpdateSuppression = false;
    }
    private static <T> T choice(JComboBox<Choice<T>> box) { Choice<T> selected = (Choice<T>)box.getSelectedItem(); return selected == null ? null : selected.value; }
    private static final class Choice<T> { final T value; final String label; Choice(T value, String label) { this.value = value; this.label = label; } public String toString() { return label; } }

    /** Runs only on the EDT, at most once per timer tick, and never while hidden. */
    void refreshRoster() {
        if (!isShowing() && isDisplayable()) return;
        RosterDefinitions nextDefinitions = definitionsSource.get();
        if (nextDefinitions != definitions) { definitions = nextDefinitions; requestRefresh(); }
        List<CapturedPlayer> players;
        long nextRevision;
        synchronized (rosterLock) {
            if (displayedRevision == revision) return;
            nextRevision = revision;
            players = historicalPlayers == null ? new ArrayList<>(roster.values()) : new ArrayList<>(historicalPlayers);
        }
        Row selected = selectedRow();
        rebuildDisplayChoices(players);
        updateDisplayFilterSummary();
        SecurityFilter rules = selectedFilter == null ? null : selectedFilter.snapshot();
        InspectRosterQuery query = displayQuery();
        Map<String, Row> previous = new HashMap<>();
        for (Row row : model.rows) previous.put(rowKey(row.player), row);
        List<Row> rows = new ArrayList<>(players.size());
        for (CapturedPlayer player : players) {
            Row row = previous.get(rowKey(player));
            if (row == null || row.player != player || row.themeRevision != themeRevision || row.rulesRevision != requirementsRevision || row.definitions != definitions)
                row = new Row(player, themeRevision, inspectedRun, rules, definitions, requirementsRevision);
            if (query.matches(player, row.requirements, row.maxedCount, player.className)) rows.add(row);
        }
        if (sortCheckBox.isSelected()) rows.sort(Comparator.comparing((Row r) -> r.guild.isEmpty())
                .thenComparing(r -> r.guild, String.CASE_INSENSITIVE_ORDER));
        model.rows = rows;
        displayedRevision = nextRevision;
        model.fireTableDataChanged();
        if (selected != null) for (int i = 0; i < rows.size(); i++) {
            if (rowKey(rows.get(i).player).equals(rowKey(selected.player))) {
                int view = table.convertRowIndexToView(i);
                if (view >= 0) table.setRowSelectionInterval(view, view);
                break;
            }
        }
        updateSelectionActions();
        rosterCount.setText(players.isEmpty() ? "No captured players in this roster." : rows.isEmpty() ? "No matching players. Reset display filters."
            : rows.size() + " of " + players.size() + " players shown · " + (historicalPlayers == null ? "Current area" : "Selected run")
                + " · Display filters do not change bulk-copy scope.");
    }

    private void updateDisplayFilterSummary() {
        int activeFilters = 0;
        for (JComboBox<?> facet : new JComboBox<?>[]{classFacet, guildFacet, seasonalFacet, crucibleFacet, verdictFacet, maxedFacet})
            if (facet.getSelectedIndex() > 0) activeFilters++;
        displayFilters.setText("Display filters (" + activeFilters + ")");
        displayFilters.getAccessibleContext().setAccessibleDescription(activeFilters + " active advanced display filters; "
                + (displayFilters.isSelected() ? "expanded" : "collapsed") + ". Search and Reset remain available.");
    }

    /** Detached copy of a displayed row for INS-3; a current-area row has no recorded outcome or DPS. */
    private BuildComparison.Build build(Row row) {
        Entity entity = row.player.playerEntity;
        StatData level = entity.stat.get(StatType.LEVEL_STAT);
        Integer[] equipment = new Integer[4]; String[] names = new String[4];
        for (int i = 0; i < 4; i++) { equipment[i] = row.player.equipmentCaptured[i] ? row.player.inv[i] : null; names[i] = row.player.itemName[i]; }
        ActivityJournal.Visit run = row.player.currentArea ? null : inspectedRun;
        String outcome = run == null ? "Not recorded (current area)" : run.runStatus() + " · " + (run.completionEvidence.isEmpty() ? "completion evidence not observed" : run.completionEvidence);
        Long start = run == null || run.firstDamageAt < 0 ? null : run.firstDamageAt, end = run == null || run.lastDamageAt < 0 ? null : run.lastDamageAt;
        return new BuildComparison.Build(inspectionName(row), Objects.toString(row.player.className, CharacterClass.getName(entity.objectType)), entity.objectType,
            level == null ? null : level.statValue, entity.baseStats, equipment, names, row.player.recordedAt, run == null ? null : displayedSource,
            row.player.origin, run == null ? null : run.map, outcome, row.damage, start, end);
    }
    private void pinSelected() {
        Row row = selectedRow();
        if (row == null) return;
        pinned = build(row);
        rosterCount.setText("Pinned comparison baseline: " + pinned.name + " · " + BuildComparison.source(pinned) + ". Select another build and choose Compare (Ctrl+Shift+B).");
        updateSelectionActions();
    }
    private void compareSelected() {
        Row row = selectedRow();
        if (row == null || pinned == null) return;
        showComparison(this, new BuildComparison(pinned, build(row)));
    }
    /** Currently pinned baseline; null when none. */
    static BuildComparison.Build pinnedBaseline() { return pinned; }
    static void clearPinnedBaseline() { pinned = null; }
    BuildComparison comparisonForSelection() { Row row = selectedRow(); return row == null || pinned == null ? null : new BuildComparison(pinned, build(row)); }
    void pinSelection() { pinSelected(); }
    JTable rosterTable() { return table; }
    String selectedOrigin() { Row row = selectedRow(); return row == null ? null : row.player.origin; }

    static JDialog showComparison(Component owner, BuildComparison comparison) {
        Window window = owner == null ? null : owner instanceof Window ? (Window) owner : SwingUtilities.getWindowAncestor(owner);
        JDialog dialog = new JDialog(window, "Compare recorded builds", Dialog.ModalityType.MODELESS);
        dialog.setName("inspect-build-comparison"); dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        String[] columns = {"Field", "Baseline", "Candidate", "Note"};
        Object[][] values = new Object[comparison.lines.size()][];
        for (int i = 0; i < values.length; i++) { BuildComparison.Line line = comparison.lines.get(i); values[i] = new Object[]{line.field, line.baseline, line.candidate, line.note}; }
        JTable table = new JTable(new javax.swing.table.DefaultTableModel(values, columns) { @Override public boolean isCellEditable(int r, int c) { return false; } });
        table.setName("inspect-build-comparison-table"); ContentStyle.table(table);
        ContentStyle.Cell literal = new ContentStyle.Cell(); literal.putClientProperty("html.disable", true); table.setDefaultRenderer(Object.class, literal);
        table.getAccessibleContext().setAccessibleName("Baseline versus candidate build fields");
        JTextArea summary = ContentStyle.wrappingText(comparison.summary(), 5); summary.setName("inspect-build-comparison-summary"); summary.setFocusable(true);
        summary.getAccessibleContext().setAccessibleName("Comparison summary, DPS windows and attribution limits");
        JPanel content = new JPanel(new BorderLayout(0, 8)); content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(new JScrollPane(summary), BorderLayout.NORTH); content.add(ContentStyle.tableScroll(table, 6), BorderLayout.CENTER);
        JButton close = new JButton("Close"); close.addActionListener(e -> dialog.dispose()); content.add(close, BorderLayout.SOUTH);
        dialog.setContentPane(content); dialog.getRootPane().setDefaultButton(close);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), WHEN_IN_FOCUSED_WINDOW);
        ContentStyle.refreshFonts(content); dialog.setSize(820, 560); dialog.setLocationRelativeTo(owner); dialog.setVisible(true);
        return dialog;
    }

    void showCurrentArea() {
        boolean returning = historicalPlayers != null;
        displayedRun = null;
        inspectedRun = null;
        showRunColumns(false);
        synchronized (rosterLock) { historicalPlayers = null; }
        updateViewStateOwnership();
        if (returning && liveOwner) { selectedFilter = currentFilter; guiUpdateSuppression = true; filterComboBox.setSelectedItem(selectedFilter == null ? DISABLE_FILTER : selectedFilter.name); guiUpdateSuppression = false; requirementsRevision++; }
        if (returning && viewState != null) viewState.restoreLast();
        table.clearSelection();
        requestRefresh();
        refreshRoster();
    }

    void showRun(String id, Collection<InspectSnapshot> players) {
        showRun(id, players, null);
    }

    void showRun(ActivityJournal.Visit visit) { showRun(null, visit); }
    /** Shows one recorded run's last-recorded loadouts; {@code source} is its exact saved session + visit, when known. */
    void showRun(tomato.history.link.VisitRef source, ActivityJournal.Visit visit) {
        displayedSource = source;
        showRun(visit.id, visit.inspectedPlayers.values(), visit);
    }

    private void showRun(String id, Collection<InspectSnapshot> players, ActivityJournal.Visit visit) {
        if (visit == null) displayedSource = null;
        if (historicalPlayers == null && viewState != null) viewState.save();
        if (!Objects.equals(displayedRun, id)) table.clearSelection();
        displayedRun = id;
        inspectedRun = visit;
        showRunColumns(true);
        List<CapturedPlayer> captured = new ArrayList<>();
        for (InspectSnapshot player : players) {
            Entity entity = player.toEntity();
            CapturedPlayer row=snapshot(entity.id, entity);row.className=player.className();
            row.recordedAt=player.observedAt();
            row.currentArea=false;
            row.origin=(displayedSource == null ? "Recorded run: visit " + id + " · saved session not referenced"
                : "Recorded run: session " + displayedSource.sessionId + " · visit " + displayedSource.visitId) + (visit == null ? "" : " · " + visit.map) + " · Last recorded loadout";
            captured.add(row);
        }
        synchronized (rosterLock) { historicalPlayers = captured; }
        updateViewStateOwnership();
        requestRefresh();
        refreshRoster();
    }

    private void showRunColumns(boolean visible) {
        if (runColumnsVisible == visible) return;
        runColumnsVisible = visible;
        for (TableColumn column : runColumns) { if (visible) table.addColumn(column); else table.removeColumn(column); }
        if (visible) { table.moveColumn(table.convertColumnIndexToView(9), 9); table.moveColumn(table.convertColumnIndexToView(10), 10); }
        if (!visible) {
            List<RowSorter.SortKey> keys = new ArrayList<>();
            for (RowSorter.SortKey key : table.getRowSorter().getSortKeys()) if (key.getColumn() != 9 && key.getColumn() != 10) keys.add(key);
            table.getRowSorter().setSortKeys(keys);
        }
    }

    public void bindViewState(ViewStateStore store) {
        if (viewState != null || !liveOwner) return;
        viewState = new RosterViewState(store, "inspect-live-roster", this::captureViewState, this::prepareViewState, this::ownsLiveViewState);
        stateHost.add(viewState.controls()); updateViewStateOwnership();
        RosterViewState.listenTable(table, this::rememberViewState);
    }
    public java.util.concurrent.CompletionStage<util.PreferencesStore.SaveResult> saveViewState() {
        if (viewState == null || !ownsLiveViewState()) throw new IllegalStateException("Live view state is not active"); return viewState.save();
    }
    @Override public void removeNotify() { if (viewState != null && historicalPlayers == null) viewState.save(); super.removeNotify(); }
    private void rememberViewState() {
        if (viewState != null && ownsLiveViewState() && !guiUpdateSuppression && !restoringState) viewState.changed();
    }
    private boolean ownsLiveViewState() { return liveOwner && historicalPlayers == null; }
    private void updateViewStateOwnership() {
        if (viewState == null) return;
        viewState.ownershipChanged(); stateHost.setVisible(ownsLiveViewState());
    }
    private Map<String, String> captureViewState() {
        if (!ownsLiveViewState()) throw new IllegalStateException("Cannot capture recorded controls as live state");
        Map<String, String> values = new LinkedHashMap<>(); InspectRosterQuery q = displayQuery();
        values.put("text", rosterSearch.getText()); values.put("class", Objects.toString(q.classId, ""));
        values.put("guildMode", q.guild.name()); values.put("guild", Objects.toString(q.guildName, ""));
        values.put("season", q.seasonal.name()); values.put("crucible", q.crucible.name());
        values.put("verdict", q.verdict == null ? "ANY" : q.verdict.name());
        values.put("maxed", q.knownRange ? "RANGE" : q.unknownMaxed ? "UNKNOWN" : "ANY");
        values.put("minimum", minMaxed.getValue().toString()); values.put("maximum", maxMaxed.getValue().toString());
        values.put("details", Boolean.toString(explain.isSelected())); RosterViewState.captureTable(values, table); return values;
    }
    private Runnable prepareViewState(Map<String, String> values) {
        String clazzText = values.getOrDefault("class", Objects.toString(choice(classFacet), "")); Integer clazz = clazzText.isEmpty() ? null : Integer.valueOf(clazzText);
        InspectRosterQuery.Guild guildMode = InspectRosterQuery.Guild.valueOf(values.getOrDefault("guildMode", displayQuery().guild.name()));
        String guild = values.getOrDefault("guild", Objects.toString(choice(guildFacet), ""));
        if (guildMode == InspectRosterQuery.Guild.EXACT && guild.isEmpty()) throw new IllegalArgumentException("Missing exact guild value");
        InspectRosterQuery.Mode season = InspectRosterQuery.Mode.valueOf(values.getOrDefault("season", displayQuery().seasonal.name()));
        InspectRosterQuery.Mode crucible = InspectRosterQuery.Mode.valueOf(values.getOrDefault("crucible", displayQuery().crucible.name()));
        int verdict = RosterViewState.option(values, "verdict", verdictFacet.getSelectedIndex(), "ANY", "NOT_EVALUATED", "PASS", "BELOW", "UNKNOWN");
        int maxed = RosterViewState.option(values, "maxed", maxedFacet.getSelectedIndex(), "ANY", "RANGE", "UNKNOWN");
        int minimum = RosterViewState.number(values, "minimum", (Integer)minMaxed.getValue(), 0, 8), maximum = RosterViewState.number(values, "maximum", (Integer)maxMaxed.getValue(), 0, 8);
        int details = RosterViewState.option(values, "details", explain.isSelected() ? 1 : 0, "false", "true");
        Runnable columns = RosterViewState.prepareTable(values, table);
        return () -> {
            restoringState = guiUpdateSuppression = true;
            try {
                rosterSearch.setText(values.getOrDefault("text", rosterSearch.getText())); selectChoice(classFacet, clazz, clazz == null ? "All classes" : "Class " + clazz);
                if (guildMode == InspectRosterQuery.Guild.EXACT) selectChoice(guildFacet, guild, guild);
                else guildFacet.setSelectedIndex(guildMode == InspectRosterQuery.Guild.NONE ? 1 : guildMode == InspectRosterQuery.Guild.UNKNOWN ? 2 : 0);
                seasonalFacet.setSelectedIndex(season.ordinal()); crucibleFacet.setSelectedIndex(crucible.ordinal()); verdictFacet.setSelectedIndex(verdict); maxedFacet.setSelectedIndex(maxed);
                minMaxed.setValue(minimum); maxMaxed.setValue(maximum); explain.setSelected(details == 1); resultDetails.setVisible(details == 1); columns.run();
                synchronized (rosterLock) { revision++; } refreshRoster();
            } finally { restoringState = guiUpdateSuppression = false; }
        };
    }
    private static <T> void selectChoice(JComboBox<Choice<T>> box, T value, String label) {
        for (int i = 0; i < box.getItemCount(); i++) if (Objects.equals(box.getItemAt(i).value, value)) { box.setSelectedIndex(i); return; }
        Choice<T> item = new Choice<>(value, label); box.addItem(item); box.setSelectedItem(item);
    }

    private String rowKey(CapturedPlayer player) {
        Entity entity = player.playerEntity;
        return historicalPlayers == null ? "object:" + entity.id : "run:" + displayedRun + ":object:" + entity.id;
    }

    private static final StatType[] DISPLAY_STATS = {StatType.NAME_STAT, StatType.GUILD_NAME_STAT,
            StatType.LEVEL_STAT, StatType.SKIN_ID, StatType.SEASONAL, StatType.CRUCIBLE_STAT,
            StatType.INVENTORY_0_STAT, StatType.INVENTORY_1_STAT, StatType.INVENTORY_2_STAT,
            StatType.INVENTORY_3_STAT, StatType.UNIQUE_DATA_STRING};

    // Copy values on the producer thread, before the mutable capture entity can change again.
    private static final class CapturedPlayer extends Player {
        String className;
        String origin = "Current area · Detached captured build";
        long recordedAt;
        boolean currentArea = true;
        final boolean[] equipmentCaptured;
        CapturedPlayer(Entity entity, boolean[] equipmentCaptured) {
            super(entity);
            this.equipmentCaptured = equipmentCaptured;
            className=CharacterClass.getName(entity.objectType);
        }
        String historyKey() {
            return InspectSnapshot.playerKey(playerEntity.id, playerEntity.getStatName());
        }
    }

    private static CapturedPlayer snapshot(int id, Entity source) {
        Entity copy = new Entity(null, id, 0);
        copy.markPlayerIdentity();
        boolean[] equipmentCaptured = new boolean[4];
        copy.objectType = source.objectType;
        copy.baseStats = source.baseStats == null ? new int[]{-1, -1, -1, -1, -1, -1, -1, -1} : source.baseStats.clone();
        for (StatType type : DISPLAY_STATS) {
            StatData original = source.stat.get(type);
            if (original == null) continue;
            StatData value = new StatData();
            value.statType = type;
            value.statTypeNum = type.get();
            value.statValue = original.statValue;
            value.statValueTwo = original.statValueTwo;
            value.stringStatValue = original.stringStatValue;
            int slot = type.get() - StatType.INVENTORY_0_STAT.get();
            if (slot >= 0 && slot < 4) equipmentCaptured[slot] = true;
            copy.stat.set(type, value);
        }
        CapturedPlayer player = new CapturedPlayer(copy, equipmentCaptured);
        player.recordedAt = source.observedAt();
        return player;
    }

    public static void addPlayer(int id, Entity entity) {
        ParsePanelGUI panel = INSTANCE;
        if (panel == null || entity == null) return;
        CapturedPlayer copy = snapshot(id, entity);
        synchronized (panel.rosterLock) { panel.roster.put(id, copy); if (panel.historicalPlayers == null) panel.revision++; }
    }

    public static void update(Entity entity) {
        ParsePanelGUI panel = INSTANCE;
        if (panel == null || entity == null) return;
        synchronized (panel.rosterLock) {
            if (!panel.roster.containsKey(entity.id)) return;
        }
        CapturedPlayer copy = snapshot(entity.id, entity);
        synchronized (panel.rosterLock) {
            CapturedPlayer previous = panel.roster.get(entity.id);
            if (previous == null || sameDisplay(previous, copy)) return;
            panel.roster.put(entity.id, copy);
            if (panel.historicalPlayers == null) panel.revision++;
        }
    }

    private static boolean sameDisplay(CapturedPlayer a, CapturedPlayer b) {
        if (!Arrays.equals(a.equipmentCaptured, b.equipmentCaptured)) return false;
        if (a.playerEntity.objectType != b.playerEntity.objectType || !Arrays.equals(a.playerEntity.baseStats, b.playerEntity.baseStats)) return false;
        for (StatType type : DISPLAY_STATS) {
            StatData x = a.playerEntity.stat.get(type), y = b.playerEntity.stat.get(type);
            if (x == null || y == null) { if (x != y) return false; else continue; }
            if (x.statValue != y.statValue || x.statValueTwo != y.statValueTwo || !Objects.equals(x.stringStatValue, y.stringStatValue)) return false;
        }
        return true;
    }

    public static void removePlayer(int id) {
        ParsePanelGUI panel = INSTANCE;
        if (panel == null) return;
        synchronized (panel.rosterLock) { if (panel.roster.remove(id) != null && panel.historicalPlayers == null) panel.revision++; }
    }

    public static void clear() {
        ParsePanelGUI panel = INSTANCE;
        if (panel == null) return;
        synchronized (panel.rosterLock) { panel.roster.clear(); if (panel.historicalPlayers == null) panel.revision++; }
    }

    public static void update() {
        ParsePanelGUI panel = INSTANCE;
        if (panel != null) panel.requestRefresh();
    }

    public static void editFont(Font font) {
        ParsePanelGUI panel = INSTANCE;
        if (panel == null || font == null) return;
        Runnable change = () -> { ContentStyle.tableFont(panel.table, font, 0); panel.revalidate(); panel.repaint(); };
        if (SwingUtilities.isEventDispatchThread()) change.run(); else SwingUtilities.invokeLater(change);
    }

    List<Player> getFilteredPlayers() {
        List<Player> players;
        synchronized (rosterLock) { players = historicalPlayers == null ? new ArrayList<>(roster.values()) : new ArrayList<>(historicalPlayers); }
        SecurityFilter rules = selectedFilter == null ? null : selectedFilter.snapshot();
        RosterDefinitions catalog = definitionsSource.get();
        if (rules != null && copyOnlyUnderReqCheckbox.isSelected()) players.removeIf(p -> !rules.evaluate(p, catalog).nonPassing());
        return players;
    }

    protected void clicked(boolean full) {
        List<Player> players = getFilteredPlayers();
        if (full) copyToClipboard(json(players));
        else {
            StringJoiner names = new StringJoiner(" ");
            for (Player player : players) names.add(player.playerEntity.name());
            copyToClipboard(names.toString());
        }
    }

    private String json(List<Player> players) {
        StringJoiner result = new StringJoiner(",\n", "[\n", "\n]");
        RosterDefinitions catalog = definitionsSource.get();
        for (Player player : players) result.add(player.toJson(catalog));
        return result.toString();
    }

    protected void saveNamesAsText() {
        StringBuilder names = new StringBuilder();
        for (Player player : getFilteredPlayers()) names.append(player.playerEntity.name()).append('\n');
        saveToFile(names.toString(), "ExportNames", ".txt");
    }

    protected void saveAsJson(List<Player> players) { saveToFile(json(players), "Export", ".json"); }

    private static void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private void saveToFile(String content, String prefix, String extension) {
        try {
            File directory = new File("exports");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create exports directory");
            File file = new File(directory, prefix + new SimpleDateFormat("MMddyyyy_HHmmss").format(new Date()) + extension);
            try (FileWriter writer = new FileWriter(file)) { writer.write(content); }
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Transferable() {
                public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.javaFileListFlavor}; }
                public boolean isDataFlavorSupported(DataFlavor flavor) { return DataFlavor.javaFileListFlavor.equals(flavor); }
                public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                    if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
                    return Collections.singletonList(file);
                }
            }, null);
            JOptionPane.showMessageDialog(this, "Successfully exported data to:\n" + file.getAbsolutePath()
                    + "\n\n(File object copied to clipboard - ready to paste)", "Export successful", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Failed to export data:\n" + e.getMessage(), "Export failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void openWebpage(String url) {
        try { Desktop.getDesktop().browse(new URI(url)); }
        catch (Exception e) { System.err.println("Failed to open webpage for URL: " + url); }
    }

    private static String html(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    private static class Row {
        final long themeRevision;
        final CapturedPlayer player;
        final RequirementResult requirements;
        final RosterDefinitions definitions;
        final Integer maxedCount;
        final long rulesRevision;
        final String guild;
        final Long damage;
        final Double dps;
        final String[] equipmentLabels = new String[4], equipmentDetails = new String[4];
        final ImageIcon[] icons = new ImageIcon[4];
        final ImageIcon skin;
        Row(CapturedPlayer player, long themeRevision, ActivityJournal.Visit run) {
            this(player, themeRevision, run, null, RosterDefinitions.current(), 0);
        }
        Row(CapturedPlayer player, long themeRevision, ActivityJournal.Visit run, SecurityFilter rules, RosterDefinitions definitions, long rulesRevision) {
            this.player = player;
            this.definitions = definitions; this.rulesRevision = rulesRevision;
            requirements = rules == null ? RequirementResult.notEvaluated() : rules.evaluate(player, definitions);
            maxedCount = player.statsMaxed(definitions);
            this.themeRevision = themeRevision;
            guild = Objects.toString(player.playerEntity.getStatGuild(), "");
            damage = run == null ? null : run.damage(player.historyKey());
            dps = run == null ? null : run.dps(damage);
            ParseEnchants.EquippedCapture capture = ParseEnchants.equippedCapture(player.playerEntity);
            skin = ImageBuffer.getOutlinedIcon(player.getSkinId(), 20);
            for (int i = 0; i < 4; i++) {
                player.itemName[i] = IdToAsset.objectName(player.inv[i]);
                String slot = Player.equipmentNames[i];
                slot = Character.toUpperCase(slot.charAt(0)) + slot.substring(1);
                String item = !player.equipmentCaptured[i] ? "Not captured"
                        : player.inv[i] < 0 ? "Empty (ID " + player.inv[i] + ")"
                        : (player.itemName[i] == null ? "Unrecognized item" : player.itemName[i]) + " (ID " + player.inv[i] + ")";
                equipmentLabels[i] = slot + ": " + item;
                String enchant = capture.description(i).trim();
                boolean known = capture.state(i) == ParseEnchants.CaptureState.KNOWN;
                equipmentDetails[i] = equipmentLabels[i] + "\nEnchants: "
                        + (known && enchant.isEmpty() ? "None (captured)" : enchant);
                int count = !known || enchant.isEmpty() ? 0 : enchant.split("\n").length;
                String role = count == 1 ? "mint" : count == 2 ? "blue" : count == 3 ? "violet" : "amber";
                icons[i] = count == 0 ? ImageBuffer.getOutlinedIcon(player.inv[i], 20)
                        : ImageBuffer.getOutlinedIconWithGlow(player.inv[i], 20, ContentStyle.color(role), 3);
            }
        }
    }

    /** The whole page scrolls only when controls plus three roster rows cannot fit. */
    private class RosterPage extends JPanel implements Scrollable {
        RosterPage() { super(new BorderLayout(0, 8)); }
        @Override public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            int chrome = size.height - rosterScroll.getPreferredSize().height;
            Insets border = rosterScroll.getInsets();
            Insets viewportBorder = rosterScroll.getViewportBorder() == null ? new Insets(0, 0, 0, 0)
                    : rosterScroll.getViewportBorder().getBorderInsets(rosterScroll);
            int rosterHeight = table.getRowHeight() * 3 + table.getTableHeader().getPreferredSize().height
                    + rosterScroll.getHorizontalScrollBar().getPreferredSize().height
                    + border.top + border.bottom + viewportBorder.top + viewportBorder.bottom;
            return new Dimension(size.width, chrome + rosterHeight);
        }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return table.getRowHeight(); }
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(table.getRowHeight(), visible.height - table.getRowHeight());
        }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport && getParent().getHeight() >= getPreferredSize().height;
        }
    }

    private static class RosterModel extends AbstractTableModel {
        private final String[] columns = {"Player / level", "Guild", "Class", "Weapon", "Ability", "Armor", "Ring", "Maxed", "Character mode", "Damage", "DPS", "Requirements"};
        private List<Row> rows = new ArrayList<>();
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int column) { return columns[column]; }
        public Class<?> getColumnClass(int column) { return column == 7 ? Integer.class : column == 9 ? Long.class : column == 10 ? Double.class : String.class; }
        public Object getValueAt(int index, int column) {
            Row row = rows.get(index);
            Entity entity = row.player.playerEntity;
            switch (column) {
                case 0: return entity.name() + " [" + (entity.stat.get(StatType.LEVEL_STAT) == null ? "Not captured" : entity.stat.get(StatType.LEVEL_STAT).statValue) + "]";
                case 1: return entity.getStatGuild() == null ? "Not captured" : row.guild.isEmpty() ? "None (captured)" : row.guild;
                case 2: return Objects.toString(row.player.className, Objects.toString(CharacterClass.getName(entity.objectType), "Unknown class"));
                case 7: return row.maxedCount;
                case 8: return Player.modeDescription(entity);
                case 9: return row.damage;
                case 10: return row.dps;
                case 11: return row.requirements.verdict.label;
                default: return row.equipmentLabels[column - 3];
            }
        }
    }

    private class RosterCell extends ContentStyle.Cell {
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int rowIndex, int columnIndex) {
            super.getTableCellRendererComponent(table, value, selected, focus, rowIndex, columnIndex);
            Row row = model.rows.get(table.convertRowIndexToModel(rowIndex));
            int column = table.convertColumnIndexToModel(columnIndex);
            setToolTipText(null);
            if (column == 7) setText(value==null?DisplayFormat.UNAVAILABLE:value + " / 8");
            if (column == 9) setText(DisplayFormat.formatInteger((Long)value));
            if (column == 10) setText(value == null ? DisplayFormat.UNAVAILABLE : DisplayFormat.formatNumber(((Number)value).doubleValue(), 0, 1));
            String name = model.getColumnName(column) + ": " + getText();
            getAccessibleContext().setAccessibleName(name);
            getAccessibleContext().setAccessibleDescription(name);
            if (focus) setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(ContentStyle.color("violet"), 2), BorderFactory.createEmptyBorder(0, 6, 0, 6)));
            if (column == 0) {
                setIcon(row.skin);
                setToolTipText("Click to copy player; Ctrl+click or Enter to open RealmEye. Ctrl+C copies the selected player.");
            } else if (column == 1) {
                setToolTipText(row.guild + " — click to copy; Ctrl+click or Ctrl+Enter opens RealmEye.");
            } else if (column >= 3 && column <= 6) {
                int slot = column - 3;
                setText(""); setIcon(row.icons[slot]); setHorizontalAlignment(CENTER);
                setToolTipText("<html>" + html(row.equipmentDetails[slot]) + "</html>");
                getAccessibleContext().setAccessibleName(row.equipmentLabels[slot]);
                getAccessibleContext().setAccessibleDescription(row.equipmentDetails[slot]);
            } else if (column == 7) {
                setToolTipText("<html>" + html(row.player.statsDescription(row.definitions)) + "</html>");
            } else if (column == 8 && !selected) {
                boolean seasonal = Boolean.TRUE.equals(Player.seasonal(row.player.playerEntity)), crucible = Boolean.TRUE.equals(Player.crucible(row.player.playerEntity));
                setForeground(ContentStyle.color(crucible ? (seasonal ? "violet" : "amber") : seasonal ? "mint" : "muted"));
            } else if (column == 11) {
                setToolTipText("<html>" + html(row.requirements.description()) + "</html>");
                if (!selected) setForeground(ContentStyle.color(row.requirements.verdict == RequirementResult.Verdict.PASS ? "mint"
                    : row.requirements.verdict == RequirementResult.Verdict.BELOW ? "rose" : "muted"));
            } else if (column == 9 || column == 10) {
                setToolTipText(column == 9 ? "Captured outgoing damage in this dungeon; missing older recordings are shown as —."
                        : "Damage per second over the dungeon's shared first-to-last captured hit window. A single timestamp has no measurable DPS. Gear is the last captured loadout.");
            }
            return this;
        }
    }
}
