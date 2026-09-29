package tomato.gui.kit;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.swing.*;
import util.PropertiesManager;

/**
 * A JTabbedPane whose tabs have stable IDs and can be reordered (drag, menu or Ctrl+Shift+Left/Right)
 * and hidden. Analyst-only tabs are skipped in Simple mode, and conditional tabs while their condition is false, without changing the saved order.
 */
public class CustomizableTabs {
    public static final String PREFIX = "ui.tabs.";
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private static final class Entry {
        final String id, title;
        final Component component;
        final boolean analystOnly;
        /** Null: always offered. Otherwise offered only while it is true; {@link #refreshConditions} re-checks it. */
        final BooleanSupplier condition;
        Entry(String id, String title, Component component, boolean analystOnly, BooleanSupplier condition) {
            this.id = id; this.title = title; this.component = component; this.analystOnly = analystOnly; this.condition = condition;
        }
        /** Neither Analyst-only nor conditional: only such a tab may be the one a view keeps when the others are hidden. */
        boolean steady() { return !analystOnly && condition == null; }
    }

    private final String group;
    private final DisplayModeModel mode;
    private final BiConsumer<String, String> write;
    private final JTabbedPane tabs = new JTabbedPane();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<String> savedOrder = new ArrayList<>();
    private final Set<String> hidden = new LinkedHashSet<>();
    private final List<Consumer<String>> selectionListeners = new ArrayList<>();
    private boolean rebuilding;
    private String dragging, lastSelected;

    public CustomizableTabs(String group) {
        this(group, DisplayModeModel.application(), PropertiesManager::getProperty, PropertiesManager::setProperties);
    }

    CustomizableTabs(String group, DisplayModeModel mode, Function<String, String> read, BiConsumer<String, String> write) {
        if (!ID.matcher(group).matches()) throw new IllegalArgumentException("Tab group IDs use lowercase letters, digits and hyphens: " + group);
        this.group = group;
        this.mode = mode;
        this.write = write;
        tabs.setName(group + "-tabs");
        // Hierarchy walkers that find the pane reach the tabs, and so the contents of hidden tabs (contents()).
        tabs.putClientProperty(CustomizableTabs.class, this);
        load(read.apply(PREFIX + group));
        installGestures();
        tabs.addChangeListener(e -> { if (!rebuilding) notifySelection(); });
        mode.bind(tabs, ignored -> rebuild());
    }

    public JTabbedPane component() { return tabs; }

    /**
     * Every tab's component in the user's order, including hidden, Analyst-only and conditional tabs that are not in the strip
     * (a hidden tab is detached, so a walk of the component tree misses it). Closing walks reach them through this, from the
     * pane's {@code CustomizableTabs.class} client property. A read-only snapshot.
     */
    public List<Component> contents() {
        List<Component> result = new ArrayList<>();
        for (String id : order()) result.add(entries.get(id).component);
        return Collections.unmodifiableList(result);
    }

    public CustomizableTabs add(String id, String title, Component component) { return add(id, title, component, false, null); }
    public CustomizableTabs addAnalyst(String id, String title, Component component) { return add(id, title, component, true, null); }
    /**
     * A tab offered only while {@code visible} is true (the sheet's Death annotation for a character marked dead). Like an
     * Analyst-only tab it is skipped without changing the saved order or hidden set (spec §4.4); call {@link #refreshConditions}
     * after the condition may have changed.
     */
    public CustomizableTabs addWhen(String id, String title, Component component, BooleanSupplier visible) {
        return add(id, title, component, false, Objects.requireNonNull(visible, "visible"));
    }

    /** Re-checks the conditional tabs and shows or skips each; the saved order and hidden set are never rewritten. EDT. */
    public void refreshConditions() { rebuild(); }

    private CustomizableTabs add(String id, String title, Component component, boolean analystOnly, BooleanSupplier condition) {
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Tab IDs use lowercase letters, digits and hyphens: " + id);
        if (entries.containsKey(id)) throw new IllegalArgumentException("Duplicate tab ID " + id);
        entries.put(id, new Entry(id, title, component, analystOnly, condition));
        rebuild();
        return this;
    }

    /** Every known tab in the user's order, including hidden and Analyst-only tabs; new tabs append. */
    public List<String> order() {
        List<String> result = new ArrayList<>();
        for (String id : savedOrder) if (entries.containsKey(id) && !result.contains(id)) result.add(id);
        for (String id : entries.keySet()) if (!result.contains(id)) result.add(id);
        return result;
    }

    public List<String> visibleIds() {
        List<String> result = new ArrayList<>();
        for (String id : order()) if (shown(entries.get(id))) result.add(id);
        // Corrupt/older preferences, or a mode change, must not strand a view with no tab.
        if (result.isEmpty()) for (String id : order()) {
            if (offered(entries.get(id))) { result.add(id); break; }
        }
        return result;
    }

    public Set<String> hiddenIds() {
        Set<String> result = new LinkedHashSet<>();
        for (String id : order()) if (hidden.contains(id)) result.add(id);
        return Collections.unmodifiableSet(result);
    }

    public String selectedId() {
        int index = tabs.getSelectedIndex();
        return index < 0 ? null : idAt(index);
    }

    public void select(String id) {
        int index = visibleIds().indexOf(id);
        if (index >= 0) tabs.setSelectedIndex(index);
    }

    public boolean isRebuilding() { return rebuilding; }

    public void move(String id, int delta) {
        List<String> visible = visibleIds();
        int from = visible.indexOf(id), to = from + delta;
        if (from < 0 || delta == 0 || to < 0 || to >= visible.size()) return;
        List<String> all = order();
        String neighbor = visible.get(to);
        all.remove(id);
        int at = all.indexOf(neighbor);
        all.add(delta > 0 ? at + 1 : at, id);
        savedOrder.clear();
        savedOrder.addAll(all);
        save();
        rebuild();
        select(id);
    }

    public boolean hide(String id) {
        List<String> visible = visibleIds();
        if (!visible.contains(id) || visible.size() <= 1) return false;
        if (entries.get(id).steady() && visible.stream().filter(key -> entries.get(key).steady()).count() <= 1) return false;
        hidden.add(id);
        save();
        rebuild();
        return true;
    }

    public void show(String id) {
        if (!hidden.remove(id)) return;
        save();
        rebuild();
        select(id);
    }

    public void reset() {
        savedOrder.clear();
        hidden.clear();
        save();
        rebuild();
    }

    private boolean shown(Entry entry) { return !hidden.contains(entry.id) && offered(entry); }
    /** Offered by the display mode and, for a conditional tab, by its condition. */
    private boolean offered(Entry entry) { return (!entry.analystOnly || mode.analyst()) && (entry.condition == null || entry.condition.getAsBoolean()); }

    private void load(String saved) {
        if (saved == null || saved.isEmpty()) return;
        String[] parts = saved.split("\\|", -1);
        for (String id : parts[0].split(",")) if (ID.matcher(id).matches()) savedOrder.add(id);
        if (parts.length > 1) for (String id : parts[1].split(",")) if (ID.matcher(id).matches()) hidden.add(id);
    }

    private void save() {
        hidden.retainAll(entries.keySet());
        write.accept(PREFIX + group, String.join(",", order()) + "|" + String.join(",", hiddenIds()));
    }

    /**
     * Brings the tab strip to the visible order touching only tabs that must change: hidden tabs are removed,
     * moved tabs are re-inserted, untouched tabs keep their content, focus and bound listeners. The final
     * selection is applied after rebuilding ends, so selection listeners see the settled tab once.
     */
    private void rebuild() {
        List<String> visible = visibleIds();
        if (visible.equals(currentIds())) return;
        String selected = selectedId();
        rebuilding = true;
        try {
            for (int i = tabs.getTabCount() - 1; i >= 0; i--) if (!visible.contains(idAt(i))) tabs.removeTabAt(i);
            for (int i = 0; i < visible.size(); i++) {
                String id = visible.get(i);
                List<String> current = currentIds();
                if (i < current.size() && id.equals(current.get(i))) continue;
                int existing = current.indexOf(id);
                if (existing >= 0) tabs.removeTabAt(existing);
                Entry entry = entries.get(id);
                tabs.insertTab(entry.title, null, entry.component, null, i);
            }
        } finally {
            rebuilding = false;
        }
        int index = selected == null ? -1 : visible.indexOf(selected);
        if (!visible.isEmpty() && tabs.getSelectedIndex() != Math.max(0, index)) tabs.setSelectedIndex(Math.max(0, index));
        notifySelection();
        tabs.revalidate();
        tabs.repaint();
    }

    /** Called once per settled selection change; rebuild churn is not reported. */
    public void onSelect(Consumer<String> listener) { selectionListeners.add(listener); }

    private void notifySelection() {
        String id = selectedId();
        if (Objects.equals(id, lastSelected)) return;
        lastSelected = id;
        for (Consumer<String> listener : selectionListeners) listener.accept(id);
    }

    private List<String> currentIds() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) ids.add(idAt(i));
        return ids;
    }

    private String idAt(int index) {
        Component component = tabs.getComponentAt(index);
        for (Entry entry : entries.values()) if (entry.component == component) return entry.id;
        return null;
    }

    private void installGestures() {
        tabs.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) { menu(e.getPoint()); return; }
                int index = tabs.indexAtLocation(e.getX(), e.getY());
                dragging = index < 0 ? null : idAt(index);
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) menu(e.getPoint());
                dragging = null;
            }
        });
        tabs.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (dragging == null) return;
                int target = tabs.indexAtLocation(e.getX(), e.getY()), from = visibleIds().indexOf(dragging);
                if (target >= 0 && from >= 0 && target != from) move(dragging, target - from);
            }
        });
        InputMap keys = tabs.getInputMap(JComponent.WHEN_FOCUSED);
        keys.put(KeyStroke.getKeyStroke("ctrl shift LEFT"), "tab-move-left");
        keys.put(KeyStroke.getKeyStroke("ctrl shift RIGHT"), "tab-move-right");
        keys.put(KeyStroke.getKeyStroke("shift F10"), "tab-menu");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0), "tab-menu");
        tabs.getActionMap().put("tab-move-left", action(() -> { String id = selectedId(); if (id != null) move(id, -1); }));
        tabs.getActionMap().put("tab-move-right", action(() -> { String id = selectedId(); if (id != null) move(id, 1); }));
        tabs.getActionMap().put("tab-menu", action(() -> {
            int index = tabs.getSelectedIndex();
            Rectangle bounds = index < 0 ? new Rectangle() : tabs.getBoundsAt(index);
            menu(new Point(bounds.x, bounds.y + bounds.height));
        }));
    }

    private static Action action(Runnable run) {
        return new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { run.run(); } };
    }

    private void menu(Point at) {
        int index = tabs.indexAtLocation(at.x, Math.max(0, at.y - 1));
        if (index < 0) index = tabs.getSelectedIndex();
        String id = index < 0 ? null : idAt(index);
        List<String> visible = visibleIds();
        JPopupMenu menu = new JPopupMenu();
        menu.setName(group + "-tab-menu");
        JMenuItem left = new JMenuItem("Move left"), right = new JMenuItem("Move right"), hide = new JMenuItem("Hide tab"),
            reset = new JMenuItem("Reset tabs");
        left.setEnabled(id != null && visible.indexOf(id) > 0);
        right.setEnabled(id != null && visible.indexOf(id) < visible.size() - 1);
        hide.setEnabled(id != null && visible.size() > 1);
        left.addActionListener(e -> move(id, -1));
        right.addActionListener(e -> move(id, 1));
        hide.addActionListener(e -> hide(id));
        reset.addActionListener(e -> reset());
        JMenu restore = new JMenu("Show hidden tab");
        for (String hiddenId : hiddenIds()) {
            JMenuItem item = new JMenuItem(entries.get(hiddenId).title);
            item.addActionListener(e -> show(hiddenId));
            restore.add(item);
        }
        restore.setEnabled(restore.getItemCount() > 0);
        menu.add(left); menu.add(right); menu.add(hide); menu.addSeparator(); menu.add(restore); menu.add(reset);
        menu.show(tabs, at.x, at.y);
    }
}
