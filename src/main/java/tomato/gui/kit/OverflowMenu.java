package tomato.gui.kit;

import java.awt.Component;
import javax.swing.*;
import tomato.gui.modern.LineIcon;

/** A "More actions" button that holds secondary actions instead of a wall of buttons. Hidden while empty. */
public class OverflowMenu extends KitButton {
    private final JPopupMenu menu = new JPopupMenu();

    public OverflowMenu(String name) {
        super(Variant.ICON, null, new LineIcon(LineIcon.DOTS, 16));
        setName(name);
        setToolTipText("More actions");
        getAccessibleContext().setAccessibleName("More actions");
        menu.setName(name + "-menu");
        addActionListener(e -> menu.show(this, 0, getHeight()));
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
