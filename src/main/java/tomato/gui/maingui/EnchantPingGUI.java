package tomato.gui.maingui;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import tomato.gui.modern.ContentStyle;
import tomato.realmshark.ParseEnchants;
import tomato.realmshark.Sound;
import util.PropertiesManager;
import util.PreferencesStore;

public class EnchantPingGUI extends JPanel {

    private static final long serialVersionUID = 1L;

    private final JTextField searchField = new JTextField(20);
    private final JPanel listPanel = new JPanel();
    // Map enchant id -> checkbox
    private final Map<Short, JCheckBox> checkBoxMap = new LinkedHashMap<>();
    // persisted selection (ids)
    private final Set<Short> savedSelected = new HashSet<>();
    private final List<Group> groups = new ArrayList<>();
    private final JCheckBox selectedOnly = new JCheckBox("Selected only");
    private final JTextArea selectionCount = ContentStyle.wrappingText("");
    private final JButton saveButton = new JButton("Save");
    private final DraftSaveStatus saving = new DraftSaveStatus(saveButton, "enchant-save-status");
    private final Set<String> unrecognizedSaved = new TreeSet<>();

    public EnchantPingGUI(List<String> items) {
        this(items, PropertiesManager.getProperty("enchantPing.selected"),
            preferenceSaver());
    }

    private static Function<String, CompletionStage<PreferencesStore.SaveResult>> preferenceSaver() {
        String[] expected = {PropertiesManager.getProperty("enchantPing.selected")};
        return value -> {
            synchronized (EnchantPingGUI.class) {
                if (!java.util.Objects.equals(expected[0], PropertiesManager.getProperty("enchantPing.selected")))
                    throw new IllegalStateException("Enchant choices changed in another editor. Reopen to load them; this draft is retained.");
                CompletionStage<PreferencesStore.SaveResult> result = PropertiesManager.setPropertiesAsync("enchantPing.selected", value);
                expected[0] = value; return result;
            }
        };
    }

    EnchantPingGUI(List<String> items, String saved,
                   Function<String, CompletionStage<PreferencesStore.SaveResult>> saveSelection) {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Load saved selection (comma separated short ids)
        if (saved != null && !saved.trim().isEmpty()) {
            String[] parts = saved.split(",");
            for (String p : parts) {
                try {
                    short v = Short.parseShort(p.trim());
                    savedSelected.add(v);
                } catch (NumberFormatException ignored) { unrecognizedSaved.add(p); }
            }
        }

        // Top: search bar
        JPanel topPanel = new JPanel(new BorderLayout());
        JLabel searchLabel = new JLabel("Search enchants: ");
        searchLabel.setLabelFor(searchField);
        topPanel.add(searchLabel, BorderLayout.WEST);
        topPanel.add(searchField, BorderLayout.CENTER); topPanel.add(selectedOnly, BorderLayout.SOUTH);
        this.add(topPanel, BorderLayout.NORTH);

        // Middle: scroll pane with checkbox list (grouped)
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        JScrollPane scrollPane = new JScrollPane(listPanel);
        scrollPane.getVerticalScrollBar().setUnitIncrement(32);
        scrollPane.setVerticalScrollBarPolicy(
            ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS
        );
        this.add(scrollPane, BorderLayout.CENTER);

        // Bottom: save button + global controls
        JPanel bottomPanel = ContentStyle.controls();
        JButton selectShown = new JButton("Select shown"), clearShown = new JButton("Clear shown");
        JButton selectAllBtn = new JButton("Select all catalog");
        JButton clearAllBtn = new JButton("Clear all catalog");
        JButton test = new JButton("Test sound"), cancel = new JButton("Cancel");
        bottomPanel.add(selectShown); bottomPanel.add(clearShown); bottomPanel.add(selectAllBtn); bottomPanel.add(clearAllBtn);
        bottomPanel.add(test); bottomPanel.add(cancel);
        bottomPanel.add(saveButton);
        JPanel notes = new JPanel(new BorderLayout()); notes.add(selectionCount); notes.add(saving.status, BorderLayout.SOUTH);
        JPanel footer = new JPanel(new BorderLayout()); footer.add(notes); footer.add(bottomPanel, BorderLayout.SOUTH);
        this.add(footer, BorderLayout.SOUTH);
        selectionCount.setName("enchant-selection-count"); selectedOnly.setName("enchant-selected-only"); searchField.setName("enchant-search");
        test.addActionListener(e -> Sound.custom.preview(null));
        cancel.setToolTipText("Discard unsubmitted edits; already applied changes remain active even if saving failed.");
        cancel.addActionListener(e -> { Window window = SwingUtilities.getWindowAncestor(this); if (window != null) window.dispose(); });

        // Populate grouped checkboxes from ParseEnchants
        buildGroupedListFromParseEnchants();
        selectedOnly.addActionListener(e -> filterList());

        // Search filtering: simple filter that shows matching checkboxes (expands groups with matches)
        searchField
            .getDocument()
            .addDocumentListener(
                new DocumentListener() {
                    @Override
                    public void insertUpdate(DocumentEvent e) {
                        filterList();
                    }

                    @Override
                    public void removeUpdate(DocumentEvent e) {
                        filterList();
                    }

                    @Override
                    public void changedUpdate(DocumentEvent e) {
                        filterList();
                    }
                }
            );

        // Save action - persist IDs
        saveButton.addActionListener(e -> {
            Set<String> ids = new TreeSet<>(unrecognizedSaved);
            for (short id : selectedIds()) ids.add(Short.toString(id));
            saving.submit(() -> saveSelection.apply(String.join(",", ids)));
        });

        selectShown.addActionListener(e -> select(checkBoxMap.keySet(), true, true));
        clearShown.addActionListener(e -> select(checkBoxMap.keySet(), false, true));
        selectAllBtn.addActionListener(e -> select(checkBoxMap.keySet(), true, false));
        clearAllBtn.addActionListener(e -> select(checkBoxMap.keySet(), false, false));
        filterList();
        ContentStyle.refreshFonts(this);
    }

    // Build grouped UI using ParseEnchants.ENCHANTS
    private void buildGroupedListFromParseEnchants() {
        listPanel.removeAll();
        checkBoxMap.clear();
        groups.clear();

        // Group enchants by first character (A-Z or Unique for all caps)
        Map<String, List<Map.Entry<Short, String>>> groupedEntries = new TreeMap<>();

        ParseEnchants.ENCHANTS.entrySet()
            .stream()
            .sorted(Comparator.comparing(e -> e.getValue().toLowerCase(Locale.ROOT)))
            .forEach(entry -> {
                String name = entry.getValue();
                if (name.equals(name.toUpperCase(Locale.ROOT))) {
                    // All uppercase names go to "Unique" group
                    groupedEntries
                        .computeIfAbsent("Unique", k -> new ArrayList<>())
                        .add(entry);
                    return;
                }
                char c = name.isEmpty() ? '#' : name.charAt(0);
                String key = (Character.isLetter(c))
                    ? String.valueOf(Character.toUpperCase(c))
                    : "#";
                groupedEntries
                    .computeIfAbsent(key, k -> new java.util.ArrayList<>())
                    .add(entry);
            });

        // For each group, create a collapsible panel
        for (Map.Entry<
            String,
            List<Map.Entry<Short, String>>
        > g : groupedEntries.entrySet()) {
            String groupName = g.getKey();
            List<Map.Entry<Short, String>> entries = g.getValue();

            JPanel groupContainer = new JPanel() {
                @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
            };
            groupContainer.setLayout(new BorderLayout());
            groupContainer.setAlignmentX(Component.LEFT_ALIGNMENT);

            // Header with toggle and group controls
            JPanel header = new JPanel(new BorderLayout());
            //            header.setBackground(new Color(0,0,0,0));
            JButton toggle = new JButton("▶ " + groupName);
            toggle.setFocusPainted(true);
            toggle.getAccessibleContext().setAccessibleName("Toggle enchant group " + groupName);
            JPanel headerRight = new JPanel(
                new FlowLayout(FlowLayout.RIGHT, 5, 0)
            );
            headerRight.setOpaque(false);
            JButton groupSelect = new JButton("Select shown");
            JButton groupClear = new JButton("Clear shown");
            groupSelect.setFont(ContentStyle.metadata(ContentStyle.body()));
            groupClear.setFont(ContentStyle.metadata(ContentStyle.body()));
            groupSelect.setMargin(new Insets(2, 8, 2, 8)); groupClear.setMargin(new Insets(2, 8, 2, 8));
            groupSelect.getAccessibleContext().setAccessibleName("Select shown enchants in group " + groupName);
            groupClear.getAccessibleContext().setAccessibleName("Clear shown enchants in group " + groupName);
            headerRight.add(groupSelect);
            headerRight.add(groupClear);
            header.add(toggle, BorderLayout.WEST);
            header.add(headerRight, BorderLayout.EAST);
            groupContainer.add(header, BorderLayout.NORTH);

            // Content panel (checkboxes)
            JPanel content = new JPanel();
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));

            for (Map.Entry<Short, String> entry : entries) {
                short id = entry.getKey();
                String name = entry.getValue();
                String cleanedName = name.replaceAll("_", " ").trim();
                String label = String.format("%s", cleanedName);
                JCheckBox cb = new JCheckBox(label);
                cb.setName("enchant-" + id);
                cb.setAlignmentX(Component.LEFT_ALIGNMENT);
                // pre-select if stored
                if (savedSelected.contains(id)) cb.setSelected(true);
                cb.addActionListener(e -> { saving.edited(); filterList(); });
                checkBoxMap.put(id, cb);
                content.add(cb);
            }

            // If any of the group's items were saved as selected, expand this group by default
            boolean groupHasSaved = entries
                .stream()
                .anyMatch(en -> savedSelected.contains(en.getKey()));
            groupContainer.add(content, BorderLayout.CENTER);
            listPanel.add(groupContainer);
            Group group = new Group(groupName, groupContainer, content, toggle, groupHasSaved);
            groups.add(group);
            group.showExpanded(groupHasSaved);

            // Toggle behavior
            toggle.addActionListener(e -> {
                group.expanded = !content.isVisible();
                group.showExpanded(group.expanded);
                listPanel.revalidate();
                listPanel.repaint();
            });

            // If expanded due to saved selection, set toggle text accordingly
            if (groupHasSaved) {
                toggle.setText("▼ " + groupName);
            }
            // Group select/clear actions
            List<Short> groupIds = new ArrayList<>(); for (Map.Entry<Short, String> entry : entries) groupIds.add(entry.getKey());
            groupSelect.addActionListener(e -> select(groupIds, true, true));
            groupClear.addActionListener(e -> select(groupIds, false, true));
        }

        listPanel.revalidate();
        listPanel.repaint();
    }

    private void filterList() {
        String q = searchField.getText().trim().toLowerCase(Locale.ROOT);
        for (Group group : groups) {
            boolean groupHasMatch = false;
            for (Component item : group.content.getComponents()) {
                JCheckBox checkBox = (JCheckBox) item;
                boolean matches = matches(checkBox, q);
                checkBox.setVisible(matches);
                groupHasMatch |= matches;
            }
            group.container.setVisible(groupHasMatch);
            group.showExpanded(groupHasMatch && (!q.isEmpty() || selectedOnly.isSelected() || group.expanded));
        }
        int shownSelected = 0;
        for (JCheckBox box : checkBoxMap.values()) if (box.isSelected() && matches(box, q)) shownSelected++;
        int total = selectedIds().size(), unknown = total - (int)checkBoxMap.values().stream().filter(JCheckBox::isSelected).count();
        selectionCount.setText(total + " selected · " + shownSelected + " selected shown · " + (total - shownSelected)
            + " selected outside filter (" + unknown + " unavailable in catalog). "
            + (unrecognizedSaved.isEmpty() ? "" : unrecognizedSaved.size() + " unrecognized saved entries preserved. ")
            + "Shown means filter matches, including collapsed groups. All catalog actions preserve unavailable IDs.");
        listPanel.revalidate();
        listPanel.repaint();
    }

    private boolean matches(JCheckBox box, String query) {
        return (!selectedOnly.isSelected() || box.isSelected())
            && (box.getText().toLowerCase(Locale.ROOT).contains(query) || box.getName().substring("enchant-".length()).equals(query));
    }

    private void select(java.util.Collection<Short> ids, boolean selected, boolean shownOnly) {
        String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
        List<JCheckBox> targets = new ArrayList<>();
        for (short id : ids) { JCheckBox box = checkBoxMap.get(id); if (!shownOnly || matches(box, query)) targets.add(box); }
        for (JCheckBox box : targets) box.setSelected(selected);
        saving.edited(); filterList();
    }

    Set<Short> selectedIds() {
        Set<Short> ids = new TreeSet<>(savedSelected); ids.removeAll(checkBoxMap.keySet());
        checkBoxMap.forEach((id, box) -> { if (box.isSelected()) ids.add(id); }); return ids;
    }

    public List<String> getSelectedItems() {
        List<String> sel = new ArrayList<>();
        for (short id : selectedIds()) sel.add(String.format("%s(%d)", ParseEnchants.ENCHANTS.getOrDefault(id, "Unavailable enchant"), id));
        return sel;
    }

    // keep setItems for API compatibility (not used currently)
    public void setItems(List<String> items) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setItems(items));
            return;
        }
        Set<Short> retained = selectedIds(); savedSelected.clear(); savedSelected.addAll(retained);
        buildGroupedListFromParseEnchants();
        filterList();
        ContentStyle.refreshFonts(this);
    }

    private static class Group {
        final String name;
        final JPanel container, content;
        final JButton toggle;
        boolean expanded;
        Group(String name, JPanel container, JPanel content, JButton toggle, boolean expanded) {
            this.name = name; this.container = container; this.content = content; this.toggle = toggle; this.expanded = expanded;
        }
        void showExpanded(boolean visible) {
            Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (!visible && focus != null && SwingUtilities.isDescendingFrom(focus, content)) toggle.requestFocusInWindow();
            content.setVisible(visible);
            toggle.setText((visible ? "▼ " : "▶ ") + name);
            toggle.getAccessibleContext().setAccessibleDescription((visible ? "Expanded" : "Collapsed") + "; press Space to toggle");
        }
    }

    /**
     * Opens the EnchantPing dialog with an empty/default list.
     * Call EnchantPingGUI.open() from other code to show the dialog.
     */
    public static void open() {
        open(java.util.Collections.emptyList());
    }

    /**
     * Opens the EnchantPing dialog with the provided items.
     *
     * @param items list of strings to populate checkboxes (ignored currently, ParseEnchants used)
     */
    public static void open(List<String> items) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> open(items));
            return;
        }
        JFrame parent = tomato.gui.TomatoGUI.getFrame();
        EnchantPingGUI panel = new EnchantPingGUI(items);
        JDialog dialog = new JDialog(parent, "Enchant Pings", true);
        realmshark.branding.AppIdentity.apply(dialog);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.getContentPane().add(panel);
        dialog.pack();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        dialog.setSize(Math.min(560, screen.width), Math.min(700, screen.height));
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    // Simple test harness
    public static void main(String[] args) {
        PropertiesManager.preload();
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Enchant Ping GUI");
            realmshark.branding.AppIdentity.apply(frame);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            EnchantPingGUI panel = new EnchantPingGUI(null);
            frame.getContentPane().add(panel);
            frame.setSize(500, 700);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
