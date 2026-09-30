package tomato.gui.chat;

import java.awt.*;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import javax.swing.event.*;
import tomato.gui.history.*;
import tomato.gui.modern.ContentStyle;
import tomato.history.archive.*;

/** Social query controls composed from the archive foundation; no storage or query engine. */
public final class SocialQueryControls {
    private SocialQueryControls() { }
    public static JPanel labeled(String text, JComponent field, String name) {
        field.setName(name); field.getAccessibleContext().setAccessibleName(text);
        JPanel panel = new JPanel(new BorderLayout(0, 4)); JTextArea label = ContentStyle.wrappingText(text); label.setFocusable(false);
        if (field instanceof JScrollPane && ((JScrollPane)field).getViewport().getView() instanceof JComponent)
            ((JComponent)((JScrollPane)field).getViewport().getView()).getAccessibleContext().setAccessibleName(text);
        panel.add(label, BorderLayout.NORTH); panel.add(field); return panel;
    }
    public static String boundsLabel(ArchiveQuery.Bounds bounds, boolean legacyChat) {
        ZoneId zone = ZoneId.of(bounds.zone);
        return "[" + (bounds.from == null ? "unbounded" : Instant.ofEpochMilli(bounds.from).atZone(zone)) + ", "
            + (bounds.until == null ? "unbounded" : Instant.ofEpochMilli(bounds.until).atZone(zone)) + ") · until exclusive · "
            + bounds.zone + (legacyChat ? " assumed for legacy Chat (offset not captured; DST overlap: earlier offset, gap: forward adjustment)" : "")
            + (bounds.includeUnknown || bounds.from == null && bounds.until == null ? " · unknown times included" : " · unknown times excluded");
    }
    public static JPanel dates(ArchiveQuery.Bounds bounds, boolean legacyChat, Consumer<ArchiveQuery.Bounds> changed) {
        JPanel panel = new JPanel(new BorderLayout(0, 6)), fields = ContentStyle.responsiveGrid(3, 190, 8);
        ZoneId zone = ZoneId.of(bounds.zone);
        JTextField from = new JTextField(bounds.from == null ? "" : Instant.ofEpochMilli(bounds.from).atZone(zone).toOffsetDateTime().toString());
        JTextField until = new JTextField(bounds.until == null ? "" : Instant.ofEpochMilli(bounds.until).atZone(zone).toOffsetDateTime().toString());
        JTextField zoneField = new JTextField(bounds.zone);
        fields.add(labeled("From (inclusive)", from, "social-date-from")); fields.add(labeled("Until (exclusive)", until, "social-date-until"));
        fields.add(labeled(legacyChat ? "Assumed receipt zone" : "Date input / bounds zone", zoneField, "social-date-zone"));
        JCheckBox unknown = new JCheckBox("Include unknown times when bounded", bounds.includeUnknown);
        JTextArea description = ContentStyle.wrappingText(boundsLabel(bounds, legacyChat)); description.setName("social-resolved-bounds");
        JPanel actions = ContentStyle.controls(); JButton apply = new JButton("Apply dates"), clear = new JButton("All dates");
        actions.add(unknown); actions.add(apply); actions.add(clear);
        apply.addActionListener(e -> {
            try {
                ZoneId selected = ZoneId.of(zoneField.getText().trim());
                changed.accept(new ArchiveQuery.Bounds(parseDate(from.getText(), selected), parseDate(until.getText(), selected), selected, ArchiveQuery.TimeMode.ENTRY, unknown.isSelected()));
            } catch (RuntimeException invalid) { description.setText("Dates not applied: " + invalid.getMessage()); }
        });
        clear.addActionListener(e -> {
            try { changed.accept(new ArchiveQuery.Bounds(null, null, ZoneId.of(zoneField.getText().trim()), ArchiveQuery.TimeMode.ENTRY, unknown.isSelected())); }
            catch (RuntimeException invalid) { description.setText("Dates not applied: " + invalid.getMessage()); }
        });
        JPanel top = new JPanel(new BorderLayout(0, 4)); top.add(fields); top.add(actions, BorderLayout.SOUTH);
        panel.add(top, BorderLayout.NORTH); panel.add(description); return panel;
    }
    public static Long parseDate(String text, ZoneId zone) {
        if (text.trim().isEmpty()) return null;
        try { return OffsetDateTime.parse(text.trim()).toInstant().toEpochMilli(); }
        catch (java.time.format.DateTimeParseException noOffset) {
            LocalDateTime value = LocalDateTime.parse(text.trim());
            List<ZoneOffset> offsets = zone.getRules().getValidOffsets(value);
            if (offsets.size() != 1) throw new IllegalArgumentException("Ambiguous/nonexistent local time; enter an explicit offset, for example 2026-09-22T12:00:00+02:00.");
            return value.toInstant(offsets.get(0)).toEpochMilli();
        }
    }
    public static Set<String> lines(String text) {
        Set<String> result = new LinkedHashSet<>(); for (String line : text.split("\\R")) if (!line.trim().isEmpty()) result.add(line.trim()); return result;
    }
    public static <F,S extends Enum<S>> JComponent liveViews(String key, ViewStateStore store, Supplier<ViewState<F,S>> capture,
            Consumer<ViewState<F,S>> restore, ViewState<F,S> defaults, JTextArea status) {
        JPanel panel = ContentStyle.controls(); JTextField name = new JTextField(12); JComboBox<String> names = new JComboBox<>();
        name.getAccessibleContext().setAccessibleName("Live view name"); names.getAccessibleContext().setAccessibleName("Named live views");
        Runnable reload = () -> { names.removeAllItems(); try { for (String value : store.names(key)) names.addItem(value); }
            catch (RuntimeException failure) { status.setText(failure.getMessage()); } };
        JButton save = new JButton("Save live view"), load = new JButton("Load live view"), remove = new JButton("Delete live view"), reset = new JButton("Reset saved live state");
        save.addActionListener(e -> { try { store.saveNamed(key, name.getText(), capture.get()).whenComplete((result, error) -> SwingUtilities.invokeLater(() ->
            status.setText(error == null && result.isSuccess() ? "Live view saved." : "Live view active; save failed. Retry Save live view."))); reload.run(); }
            catch (RuntimeException failure) { status.setText(failure.getMessage()); } });
        load.addActionListener(e -> { if (names.getSelectedItem() != null) try { restore.accept(store.loadNamed(key, names.getSelectedItem().toString(), defaults)); }
            catch (RuntimeException failure) { status.setText("Live view not applied: " + failure.getMessage()); } });
        remove.addActionListener(e -> { if (names.getSelectedItem() != null) { store.deleteNamed(key, names.getSelectedItem().toString()); reload.run(); } });
        reset.addActionListener(e -> { store.reset(key); restore.accept(defaults); reload.run(); });
        panel.add(names); panel.add(load); panel.add(name); panel.add(save); panel.add(remove); panel.add(reset); reload.run(); return panel;
    }

    /** The ⋯ section {@link #liveViewItems} fills. */
    public static final String LIVE_VIEWS = "live-views";

    /**
     * The named live views of {@code key} as a "Saved views ▸" submenu ({@code <key>-saved-views}) in {@code menu}'s
     * {@link #LIVE_VIEWS} section, for a live page's own ⋯ instead of a row of view controls: Save current view…, Load: &lt;name&gt;
     * for each saved view, Delete view… and Reset saved state. The names relist whenever the submenu opens; a failed read or save
     * is reported through {@code status} and the current controls stay usable. Calling again replaces the submenu. EDT.
     */
    public static <F,S extends Enum<S>> tomato.gui.kit.OverflowMenu.Section liveViewItems(tomato.gui.kit.OverflowMenu menu, String key, ViewStateStore store,
            Supplier<ViewState<F,S>> capture, Consumer<ViewState<F,S>> restore, ViewState<F,S> defaults, Consumer<String> status) {
        Objects.requireNonNull(store, "store"); Objects.requireNonNull(capture, "capture"); Objects.requireNonNull(restore, "restore");
        Objects.requireNonNull(defaults, "defaults"); Objects.requireNonNull(status, "status");
        JMenu views = new JMenu("Saved views"); views.setName(key + "-saved-views");
        Runnable[] reload = new Runnable[1];
        reload[0] = () -> {
            List<String> names;
            try { names = store.names(key); } catch (RuntimeException failure) { status.accept(failure.getMessage()); names = Collections.emptyList(); }
            views.removeAll();
            JMenuItem save = new JMenuItem("Save current view…"); views.add(save);
            save.addActionListener(e -> {
                String label = JOptionPane.showInputDialog(menu, "Name for this view", "Save view", JOptionPane.PLAIN_MESSAGE);
                if (label == null) return;
                try { store.saveNamed(key, label, capture.get()).whenComplete((result, error) -> SwingUtilities.invokeLater(() ->
                    status.accept(error == null && result.isSuccess() ? "Live view saved." : "Live view active; save failed. Retry Save current view…"))); }
                catch (RuntimeException failure) { status.accept(failure.getMessage()); }
                reload[0].run();
            });
            if (!names.isEmpty()) views.addSeparator();
            for (String label : names) {
                JMenuItem load = new JMenuItem("Load: " + label); views.add(load);
                load.addActionListener(e -> {
                    try { restore.accept(store.loadNamed(key, label, defaults)); }
                    catch (RuntimeException failure) { status.accept("Live view not applied: " + failure.getMessage()); }
                });
            }
            views.addSeparator();
            List<String> deletable = names;
            JMenuItem delete = new JMenuItem("Delete view…"); delete.setEnabled(!names.isEmpty()); views.add(delete);
            delete.addActionListener(e -> {
                if (deletable.isEmpty()) return;
                Object label = JOptionPane.showInputDialog(menu, "Delete which saved view?", "Delete view", JOptionPane.PLAIN_MESSAGE, null, deletable.toArray(), deletable.get(0));
                if (label == null) return;
                store.deleteNamed(key, label.toString()); reload[0].run();
            });
            JMenuItem reset = new JMenuItem("Reset saved state"); views.add(reset);
            reset.addActionListener(e -> { store.reset(key); restore.accept(defaults); reload[0].run(); });
        };
        views.addMenuListener(new MenuListener() {
            public void menuSelected(MenuEvent e) { reload[0].run(); }
            public void menuDeselected(MenuEvent e) { }
            public void menuCanceled(MenuEvent e) { }
        });
        reload[0].run();
        tomato.gui.kit.OverflowMenu.Section section = menu.section(LIVE_VIEWS);
        section.replace(views);
        return section;
    }

    /** Renderer-local evolving state; callbacks from a retired renderer cannot replace later intent. */
    public static final class State<R,F,S extends Enum<S>> {
        public ViewState<F,S> value;
        private final ArchiveClient.Binding<F,S> binding;
        private final BooleanSupplier current;
        private boolean retired, restoring;
        private JComponent owner;
        private HistoryTables.ColumnTools tools;
        public State(ViewState<F,S> initial, ArchiveClient.Binding<F,S> binding, BooleanSupplier current) {
            value = initial; this.binding = binding; this.current = current;
        }
        public boolean active() { return !retired && current.getAsBoolean(); }
        public void owner(JComponent owner) { this.owner = owner; }
        private void retire() { retired = true; if (owner != null) disable(owner); if (tools != null) tools.setEnabled(false); }
        private void disable(Component component) {
            if (component instanceof AbstractButton || component instanceof JTextField || component instanceof JTextArea && ((JTextArea)component).isEditable()
                    || component instanceof JComboBox || component instanceof JTable || component instanceof JTabbedPane) component.setEnabled(false);
            if (component instanceof Container) for (Component child : ((Container)component).getComponents()) disable(child);
        }
        public void query(ArchiveQuery<F,S> next) { if (active()) { retire(); binding.queryChanged(next); } }
        public void refresh() { if (active()) { retire(); binding.refresh(); } }
        public void tab(String tab) { if (active()) { value = value.withPosition(tab, Collections.emptyList(), null, 0); binding.viewChanged(value); } }
        /**
         * Restores the table's saved layout ({@code key}) and position, remembers later layout, selection and scroll changes, and
         * returns the table's column tools for the workspace ⋯ (also {@link #tools()}, which the client hands to ArchiveFilters).
         */
        public HistoryTables.ColumnTools tableTools(JTable table, JScrollPane scroll, ArchivePage<R> page, String key, Map<String,List<String>> presets) {
            restoring = true;
            ViewState.Table defaults = HistoryTables.columnState(table, "Default");
            if (value.tables.containsKey(key)) HistoryTables.applyColumns(table, value.tables.get(key));
            HistoryTables.restorePosition(table, scroll, page, value);
            java.util.function.Consumer<ViewState.Table> save = layout -> {
                if (active() && !restoring) { value = value.withTable(key, layout); binding.viewChanged(value); }
            };
            HistoryTables.rememberLayout(table, save);
            tools = HistoryTables.columnTools(table, defaults, presets, save);
            Runnable position = () -> {
                if (active() && !restoring && !table.getSelectionModel().getValueIsAdjusting()) {
                    value = HistoryTables.position(table, scroll, page, value); binding.viewChanged(value);
                }
            };
            table.getSelectionModel().addListSelectionListener(e -> position.run());
            scroll.getViewport().addChangeListener(e -> position.run()); restoring = false; return tools;
        }
        /** The column tools {@link #tableTools} made for this render's table; null before. */
        public HistoryTables.ColumnTools tools() { return tools; }
    }
}
