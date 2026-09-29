package tomato.gui.kit;

import java.awt.Component;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import tomato.gui.modern.LineIcon;

/** A "More actions" button that holds secondary actions instead of a wall of buttons. Hidden while empty. */
public class OverflowMenu extends KitButton {
    private final JPopupMenu menu = new JPopupMenu();
    private final Map<String, Section> sections = new LinkedHashMap<>();

    public OverflowMenu(String name) {
        super(Variant.ICON, null, new LineIcon(LineIcon.DOTS, 16));
        setName(name);
        setToolTipText("More actions");
        getAccessibleContext().setAccessibleName("More actions");
        menu.setName(name + "-menu");
        addActionListener(e -> menu.show(this, 0, getHeight()));
        // Other items may have been shown or hidden since a section last changed: settle its separators before the menu shows.
        menu.addPopupMenuListener(new PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(PopupMenuEvent e) { tidy(); }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { }
            @Override public void popupMenuCanceled(PopupMenuEvent e) { }
        });
        setVisible(false);
    }

    public JMenuItem add(String label, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> action.run());
        menu.add(item);
        setVisible(true);
        return item;
    }

    public JMenu submenu(String label) {
        JMenu submenu = new JMenu(label);
        menu.add(submenu);
        setVisible(true);
        return submenu;
    }

    public void addSeparator() { menu.addSeparator(); }

    public JPopupMenu menu() { return menu; }

    /**
     * The named section {@code id}, created empty at the current end of the menu on first use; later calls return the same
     * section, which keeps its place while items are added after it. A page swaps a section's items (for example the displayed
     * table's column tools) without rebuilding the rest of the menu. EDT.
     */
    public Section section(String id) {
        Objects.requireNonNull(id, "id");
        return sections.computeIfAbsent(id, Section::new);
    }

    /**
     * A run of items between two separators of its own. Each separator shows only while the section has a visible item and a
     * visible item stands on that side with no separator in between, so separators never double up and an empty section leaves
     * no trace. The menu stays hidden while it holds no item at all.
     */
    public final class Section {
        private final String id;
        private final JPopupMenu.Separator lead = new JPopupMenu.Separator(), trail = new JPopupMenu.Separator();
        private final List<Component> items = new ArrayList<>();

        private Section(String id) {
            this.id = id;
            lead.setName(OverflowMenu.this.getName() + "-" + id + "-start");
            trail.setName(OverflowMenu.this.getName() + "-" + id + "-end");
            lead.setVisible(false);
            trail.setVisible(false);
            menu.add(lead);
            menu.add(trail);
        }

        public String id() { return id; }

        /** Replaces this section's items with {@code next}, in order, in place. */
        public void replace(List<? extends Component> next) {
            for (Component item : items) menu.remove(item);
            items.clear();
            int at = index(trail);
            for (Component item : next) { menu.insert(Objects.requireNonNull(item, "item"), at++); items.add(item); }
            tidy();
            // As add() does: items show the button; only a menu left with no item at all hides it.
            if (!items.isEmpty()) setVisible(true);
            else if (!holdsItems()) setVisible(false);
            menu.revalidate();
            menu.repaint();
        }

        public void replace(Component... next) { replace(Arrays.asList(next)); }

        public void clear() { replace(Collections.<Component>emptyList()); }

        /** The items this section holds now, in order. */
        public List<Component> items() { return Collections.unmodifiableList(new ArrayList<>(items)); }

        private boolean showsItems() {
            for (Component item : items) if (item.isVisible()) return true;
            return false;
        }
    }

    private int index(Component component) {
        Component[] children = menu.getComponents();
        for (int i = 0; i < children.length; i++) if (children[i] == component) return i;
        throw new IllegalStateException("Not in this menu");
    }

    private boolean holdsItems() {
        for (Component child : menu.getComponents()) if (!(child instanceof JSeparator)) return true;
        return false;
    }

    /** Settles every section's separators in menu order. */
    private void tidy() {
        Component[] children = menu.getComponents();
        Set<Component> own = new HashSet<>();
        for (Section section : sections.values()) { own.add(section.lead); own.add(section.trail); }
        for (int i = 0; i < children.length; i++) {
            Section section = owner(children[i]);
            if (section == null) continue;
            boolean items = section.showsItems();
            if (children[i] == section.lead) {
                // Looking back, earlier separators are already settled.
                Component before = null;
                for (int j = i - 1; j >= 0 && before == null; j--) if (children[j].isVisible()) before = children[j];
                section.lead.setVisible(items && before != null && !(before instanceof JSeparator));
            } else {
                // Looking ahead, other sections' separators are not settled yet: the next visible entry that is not one decides.
                Component after = null;
                for (int j = i + 1; j < children.length && after == null; j++) if (children[j].isVisible() && !own.contains(children[j])) after = children[j];
                section.trail.setVisible(items && after != null && !(after instanceof JSeparator));
            }
        }
    }

    private Section owner(Component separator) {
        for (Section section : sections.values()) if (section.lead == separator || section.trail == separator) return section;
        return null;
    }

    /** The popup is not in the component tree until shown, so theme changes must reach it here. */
    @Override public void updateUI() {
        super.updateUI();
        if (menu != null) SwingUtilities.updateComponentTreeUI(menu); // null during KitButton's constructor
    }

    /** Finds a menu item by its exact text, including inside submenus; null when absent. */
    public JMenuItem item(String label) { return find(menu.getComponents(), label); }

    private static JMenuItem find(Component[] components, String label) {
        for (Component component : components) {
            if (component instanceof JMenu) {
                JMenu submenu = (JMenu) component;
                if (label.equals(submenu.getText())) return submenu;
                JMenuItem nested = find(submenu.getMenuComponents(), label);
                if (nested != null) return nested;
            } else if (component instanceof JMenuItem && label.equals(((JMenuItem) component).getText())) {
                return (JMenuItem) component;
            }
        }
        return null;
    }
}
