package tomato.gui.kit;

import java.awt.*;
import java.awt.dnd.DragSource;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import javax.swing.*;
import util.PropertiesManager;

/**
 * A JTabbedPane whose tabs have stable IDs and can be reordered (drag, menu or Ctrl+Shift+Left/Right)
 * and hidden. Analyst-only tabs are skipped in Simple mode, and conditional tabs while their condition is false, without changing the saved order.
 * A left-button drag past the system threshold swaps tabs live once the pointer crosses a neighbour's midpoint, saves once on
 * release, and Escape puts the order back without saving.
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
    /** Where the left button went down on {@link #dragging}'s tab; a drag starts only past {@link DragSource#getDragThreshold()}. */
    private Point pressedAt;
    /** Non-null while a drag is under way: the saved order at its start, for Escape and for "did the order change". */
    private List<String> dragStart;
    /** Set by a move onto another tab run (WRAP layout); cleared once the pointer is back on the dragged tab's own run. */
    private boolean crossedRun;
    /** Escape during a drag, wherever the keyboard focus is; installed only while a drag is under way. */
    private final KeyEventDispatcher dragEscape = e -> {
        if (dragStart == null || e.getKeyCode() != KeyEvent.VK_ESCAPE) return false;
        if (e.getID() == KeyEvent.KEY_PRESSED) cancelDrag();
        e.consume();
        return true;
    };

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

    /** Adjusts the loaded order before adding tabs, without writing preferences or changing hidden choices. */
    public void initializeOrder(UnaryOperator<List<String>> migration) {
        if (!entries.isEmpty()) throw new IllegalStateException("Initialize tab order before adding tabs");
        List<String> order = new ArrayList<>(migration.apply(Collections.unmodifiableList(new ArrayList<>(savedOrder))));
        for (String id : order) if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid tab ID " + id);
        savedOrder.clear();
        savedOrder.addAll(order);
    }

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
    public List<String> order() { return orderOf(savedOrder); }

    private List<String> orderOf(List<String> saved) {
        List<String> result = new ArrayList<>();
        for (String id : saved) if (entries.containsKey(id) && !result.contains(id)) result.add(id);
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
        place(id, visible.get(to), delta > 0);
        save();
        rebuild();
        select(id);
    }

    /** A drag's live move: the strip and selection follow at once, and the order is saved once, when the drag ends. */
    private void moveLive(String id, String neighbor, boolean after) {
        place(id, neighbor, after);
        rebuild();
        select(id);
    }

    /** Puts {@code id} just after (or before) {@code neighbor} in the saved order; hidden and skipped tabs keep their places. */
    private void place(String id, String neighbor, boolean after) {
        List<String> all = order();
        all.remove(id);
        int at = all.indexOf(neighbor);
        all.add(after ? at + 1 : at, id);
        savedOrder.clear();
        savedOrder.addAll(all);
    }

    /** Whether {@link #hide} would hide {@code id}: a visible tab that is neither the view's last tab nor its last steady tab. */
    public boolean canHide(String id) {
        List<String> visible = visibleIds();
        if (!visible.contains(id) || visible.size() <= 1) return false;
        return !(entries.get(id).steady() && visible.stream().filter(key -> entries.get(key).steady()).count() <= 1);
    }

    public boolean hide(String id) {
        if (!canHide(id)) return false;
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
                if (e.isPopupTrigger()) { if (dragStart == null) showMenu(e.getPoint()); return; }
                // Only the left button drags: a middle or (on Windows, whose popup trigger comes on release) right press never reorders.
                if (e.getButton() != MouseEvent.BUTTON1 || dragStart != null) return;
                int index = tabs.indexAtLocation(e.getX(), e.getY());
                dragging = index < 0 ? null : idAt(index);
                pressedAt = dragging == null ? null : e.getPoint();
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) { finishDrag(); return; }
                if (e.isPopupTrigger() && dragStart == null) showMenu(e.getPoint());
            }
        });
        tabs.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) { dragTo(e); }
        });
        // A strip that stops showing mid-drag (a page switch) keeps the moves made so far, saved once, as a release would.
        tabs.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && !tabs.isShowing()) finishDrag();
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
            showMenu(new Point(bounds.x, bounds.y + bounds.height));
        }));
    }

    /**
     * One drag event. Tabs swap live, and only once the pointer crosses the target tab's midpoint in the direction of travel: after
     * a swap the pointer is over the dragged tab or on its side of the neighbour's new midpoint, so unequal widths cannot flip the
     * order back and forth. A tab on another run (WRAP layout with several rows) takes the tab's place without the midpoint rule;
     * as runs re-flow and rotate after such a move, the next cross-run move waits until the pointer is back on the dragged tab's run.
     */
    private void dragTo(MouseEvent e) {
        if (dragging == null || (e.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) == 0) return;
        if (dragStart == null) {
            int threshold = DragSource.getDragThreshold();
            if (Math.abs(e.getX() - pressedAt.x) <= threshold && Math.abs(e.getY() - pressedAt.y) <= threshold) return;
            dragStart = new ArrayList<>(savedOrder);
            crossedRun = false;
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dragEscape);
        }
        List<String> strip = currentIds();
        int from = strip.indexOf(dragging), target = tabs.indexAtLocation(e.getX(), e.getY());
        if (from < 0 || target < 0) return; // the dragged tab left the strip (a mode change), or the pointer is off the tabs
        if (target == from) { crossedRun = false; return; }
        Rectangle source = tabs.getBoundsAt(from), over = tabs.getBoundsAt(target);
        boolean horizontal = tabs.getTabPlacement() == JTabbedPane.TOP || tabs.getTabPlacement() == JTabbedPane.BOTTOM;
        int delta = target - from;
        if (horizontal ? source.y == over.y : source.x == over.x) {
            crossedRun = false;
            double pointer = horizontal ? e.getX() : e.getY(), middle = horizontal ? over.getCenterX() : over.getCenterY(),
                own = horizontal ? source.getCenterX() : source.getCenterY();
            // Crossed: the pointer is past the target's midpoint on the side away from the dragged tab (right-to-left works too).
            // Otherwise the tabs before the target were passed in full, so the dragged tab goes just before it.
            if ((middle - own) * (pointer - middle) <= 0) delta -= Integer.signum(delta);
        } else if (crossedRun) {
            return;
        } else {
            crossedRun = true;
        }
        if (delta != 0) moveLive(dragging, strip.get(from + delta), delta > 0);
    }

    /** Ends the drag; the order is saved once, and only if it changed. */
    private void finishDrag() {
        List<String> start = dragStart;
        endDrag();
        if (start == null) return;
        if (!order().equals(orderOf(start))) save();
        else { savedOrder.clear(); savedOrder.addAll(start); } // dragged back to where it began: nothing to save
    }

    /** Escape: every tab goes back where the drag found it, and nothing is written. */
    private void cancelDrag() {
        List<String> start = dragStart;
        String id = dragging;
        endDrag();
        if (start == null) return;
        savedOrder.clear(); savedOrder.addAll(start);
        rebuild();
        select(id);
    }

    /** Forgets the press and the drag; later drag events are ignored until the next left press. */
    private void endDrag() {
        if (dragStart != null) KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dragEscape);
        dragStart = null;
        dragging = null;
        pressedAt = null;
        crossedRun = false;
    }

    private static Action action(Runnable run) {
        return new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { run.run(); } };
    }

    private void showMenu(Point at) { if (tabs.isShowing()) menu(at).show(tabs, at.x, at.y); }

    /**
     * The menu for the tab at {@code at} (the selected tab when none is there), in spec §4.4's words: Move left, Move right,
     * Hide tab, Show hidden ▸ and Reset order. An item is enabled exactly when its action would change something, and each is
     * named {@code <group>-tab-<action>} ({@code <group>-tab-show-<id>} for a hidden tab).
     */
    JPopupMenu menu(Point at) {
        int index = tabs.indexAtLocation(at.x, Math.max(0, at.y - 1));
        if (index < 0) index = tabs.getSelectedIndex();
        String id = index < 0 ? null : idAt(index);
        List<String> visible = visibleIds();
        int place = visible.indexOf(id);
        JPopupMenu menu = new JPopupMenu();
        menu.setName(group + "-tab-menu");
        menu.add(menuItem("move-left", "Move left", place > 0, () -> move(id, -1)));
        menu.add(menuItem("move-right", "Move right", place >= 0 && place < visible.size() - 1, () -> move(id, 1)));
        menu.add(menuItem("hide", "Hide tab", id != null && canHide(id), () -> hide(id)));
        menu.addSeparator();
        JMenu restore = new JMenu("Show hidden"); // the submenu paints its own ▸, as the sidebar's does
        restore.setName(group + "-tab-show-hidden");
        for (String hiddenId : hiddenIds()) restore.add(menuItem("show-" + hiddenId, entries.get(hiddenId).title, true, () -> show(hiddenId)));
        restore.setEnabled(restore.getItemCount() > 0);
        menu.add(restore);
        boolean customized = !hiddenIds().isEmpty() || !order().equals(new ArrayList<>(entries.keySet()));
        menu.add(menuItem("reset", "Reset order", customized, this::reset));
        return menu;
    }

    private JMenuItem menuItem(String action, String label, boolean enabled, Runnable run) {
        JMenuItem item = new JMenuItem(label);
        item.setName(group + "-tab-" + action);
        item.setEnabled(enabled);
        item.addActionListener(e -> run.run());
        return item;
    }
}
