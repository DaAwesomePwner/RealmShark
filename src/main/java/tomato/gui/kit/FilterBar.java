package tomato.gui.kit;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.LineIcon;
import tomato.gui.modern.VioletTheme;
import util.PropertiesManager;

/**
 * One row: search, a Filters toggle with the active count, removable chips, Clear, then scope and
 * "More actions" on the right. The module's existing facet controls live in a drawer below, closed by default; the
 * toggle is selected (drawn pressed) while the drawer is open.
 * While the drawer is open and focus is inside the bar, Esc closes it (as the toggle does) and focuses the toggle.
 */
public class FilterBar extends JPanel {
    public static final class ActiveFilter {
        public final String label;
        public final Runnable remove;
        public ActiveFilter(String label, Runnable remove) {
            this.label = Objects.requireNonNull(label, "label");
            this.remove = Objects.requireNonNull(remove, "remove");
        }
    }

    private final String name;
    private final BiConsumer<String, String> write;
    private final JPanel leading = ContentStyle.controls();
    private final JPanel trailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, Tokens.XS + 2, 2));
    private final KitButton filters = KitButton.secondary("Filters");
    private final KitButton clear = KitButton.ghost("Clear");
    private final OverflowMenu overflow;
    private final JPanel drawer = new JPanel(new BorderLayout());
    private final JPanel drawerHolder;
    /** Components disabled by setDrawerEnabled(false) and their previous state, restored exactly. */
    private final Map<Component, Boolean> disabledByLoad = new IdentityHashMap<>();
    private JComponent search, scope, drawerContent;
    private List<ActiveFilter> active = Collections.emptyList();
    private Runnable clearAll;
    private boolean open;
    private float fraction = 1f;
    private Timer animation;
    private boolean drawerEnabled = true;

    public FilterBar(String name) { this(name, PropertiesManager::getProperty, PropertiesManager::setProperties); }

    FilterBar(String name, Function<String, String> read, BiConsumer<String, String> write) {
        // No layout gap: the drawer carries its own top padding, so a closed drawer leaves no space.
        super(new BorderLayout());
        this.name = Objects.requireNonNull(name, "name");
        this.write = write;
        setOpaque(false);
        setName(name + "-filter-bar");
        overflow = new OverflowMenu(name + "-more");
        filters.setName(name + "-filters");
        filters.setIcon(new LineIcon(LineIcon.FILTER, 14));
        // While the drawer is open the toggle is selected: the theme paints it pressed and assistive technology hears "checked".
        filters.putClientProperty("FlatLaf.styleClass", VioletTheme.PRESSED_TOGGLE);
        filters.addActionListener(e -> setDrawerOpen(!open));
        clear.setName(name + "-clear-filters");
        clear.addActionListener(e -> { if (clearAll != null) clearAll.run(); });
        leading.setOpaque(false);
        trailing.setOpaque(false);
        drawer.setOpaque(false);
        drawer.setBorder(new EmptyBorder(Tokens.S, 0, 0, 0));
        drawer.setName(name + "-filter-drawer");
        JPanel row = new JPanel(new BorderLayout(Tokens.S, 0));
        row.setOpaque(false);
        row.add(leading, BorderLayout.CENTER);
        row.add(trailing, BorderLayout.EAST);
        drawerHolder = new JPanel(new BorderLayout()) {
            @Override public Dimension getPreferredSize() {
                if (!drawer.isVisible()) return new Dimension(0, 0);
                Dimension size = drawer.getPreferredSize();
                return new Dimension(size.width, Math.round(size.height * fraction));
            }
            @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }
        };
        drawerHolder.setOpaque(false);
        drawerHolder.add(drawer);
        add(row, BorderLayout.NORTH);
        add(drawerHolder, BorderLayout.CENTER);
        open = "true".equals(read.apply(key()));
        drawer.setVisible(false);
        // Esc closes an open drawer; while it is closed the action is disabled, so the key falls through to ancestors.
        // A focused component's own WHEN_FOCUSED Esc binding is processed first and keeps precedence.
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CLOSE_DRAWER);
        getActionMap().put(CLOSE_DRAWER, new AbstractAction() {
            @Override public boolean isEnabled() { return drawerOpen(); }
            @Override public void actionPerformed(ActionEvent e) {
                if (!drawerOpen()) return;
                setDrawerOpen(false);
                filters.requestFocusInWindow();
            }
        });
        rebuild();
    }

    private static final String CLOSE_DRAWER = "filter-drawer-close";

    private String key() { return "ui.filters." + name + ".open"; }

    /** Search and scope are added once and never re-parented by rebuild(), so typing keeps focus. */
    public FilterBar search(JComponent field) {
        JTextField input = firstSearchField(field);
        if (input != null) tomato.gui.modern.TextSearchBar.decorateSearch(input);
        if (search != null) leading.remove(search);
        search = field;
        if (field != null) leading.add(field, 0);
        rebuild();
        return this;
    }

    /** Wrapped rows may include secondary fields; decorate only the first search, never control editors. */
    private static JTextField firstSearchField(Component component) {
        if (component instanceof JComboBox || component instanceof JSpinner
                || component instanceof JFormattedTextField || component instanceof JPasswordField) return null;
        if (component instanceof JTextField) return (JTextField) component;
        if (component instanceof Container) for (Component child : ((Container) component).getComponents()) {
            JTextField field = firstSearchField(child);
            if (field != null) return field;
        }
        return null;
    }

    public FilterBar scope(JComponent value) {
        if (scope != null) trailing.remove(scope);
        scope = value;
        if (value != null) trailing.add(value, 0);
        rebuild();
        return this;
    }

    /** The module's facet controls; null removes the Filters toggle. Replacing content keeps the open state. */
    public FilterBar drawer(JComponent content) {
        if (animation != null) { animation.stop(); animation = null; fraction = 1f; }
        drawer.removeAll();
        drawerContent = content;
        if (content != null) drawer.add(content);
        drawer.setVisible(content != null && open);
        rebuild();
        return this;
    }

    public JComponent drawerContent() { return drawerContent; }
    /** The component passed to {@link #search}, or null. */
    public JComponent searchSlot() { return search; }
    public OverflowMenu overflow() { return overflow; }

    public void setActive(List<ActiveFilter> filters, Runnable clearAll) {
        active = Collections.unmodifiableList(new ArrayList<>(filters));
        this.clearAll = clearAll;
        rebuild();
    }

    public int activeCount() { return active.size(); }
    public boolean drawerOpen() { return open && drawerContent != null; }

    public void setDrawerOpen(boolean value) {
        if (drawerContent == null) value = false;
        if (value == open && (drawer.isVisible() == value)) return;
        open = value;
        write.accept(key(), Boolean.toString(value));
        updateFiltersButton();
        if (animation != null) animation.stop();
        if (value) { drawer.setVisible(true); drawerHolder.setVisible(true); }
        final boolean opening = value;
        animation = Motion.run(Motion.MAX_MILLIS,
            progress -> { fraction = (float) (opening ? progress : 1 - progress); drawerHolder.revalidate(); repaint(); },
            () -> {
                fraction = 1f;
                drawer.setVisible(open && drawerContent != null);
                drawerHolder.setVisible(drawer.isVisible());
                animation = null;
                revalidate();
                repaint();
            });
    }

    /**
     * Disables the facet controls, chips and Clear while a query is loading, so edits cannot be silently
     * discarded; true restores each component's previous state. The Filters toggle stays usable.
     */
    public void setDrawerEnabled(boolean enabled) {
        drawerEnabled = enabled;
        if (enabled) {
            for (Map.Entry<Component, Boolean> entry : disabledByLoad.entrySet()) entry.getKey().setEnabled(entry.getValue());
            disabledByLoad.clear();
            return;
        }
        if (drawerContent != null) disableTree(drawerContent);
        for (Component child : leading.getComponents()) if (child != search && child != filters) disableTree(child);
    }

    private void disableTree(Component component) {
        snapshotEnabled(component);
        disableCapturedTree(component);
    }

    private void snapshotEnabled(Component component) {
        if (!disabledByLoad.containsKey(component)) disabledByLoad.put(component, component.isEnabled());
        if (component instanceof Container) for (Component child : ((Container) component).getComponents()) snapshotEnabled(child);
    }

    private void disableCapturedTree(Component component) {
        component.setEnabled(false);
        if (component instanceof Container) for (Component child : ((Container) component).getComponents()) disableCapturedTree(child);
    }

    private void rebuild() {
        for (Component child : leading.getComponents()) if (child != search) leading.remove(child);
        leading.add(filters);
        filters.setVisible(drawerContent != null);
        for (ActiveFilter filter : active) leading.add(Chip.removable(filter.label, filter.remove));
        leading.add(clear);
        clear.setVisible(!active.isEmpty() && clearAll != null);
        for (Component child : trailing.getComponents()) if (child != scope) trailing.remove(child);
        trailing.add(overflow);
        drawerHolder.setVisible(drawer.isVisible());
        updateFiltersButton();
        // Facet/arrival updates may replace chips or drawer controls during a load.
        disabledByLoad.entrySet().removeIf(entry -> {
            if (SwingUtilities.isDescendingFrom(entry.getKey(), this)) return false;
            entry.getKey().setEnabled(entry.getValue());
            return true;
        });
        if (!drawerEnabled) setDrawerEnabled(false);
        revalidate();
        repaint();
    }

    private void updateFiltersButton() {
        filters.setText(active.isEmpty() ? "Filters" : "Filters · " + active.size());
        filters.setSelected(drawerOpen());
        filters.getAccessibleContext().setAccessibleDescription(drawerOpen() ? "Filters shown" : "Filters hidden");
    }
}
