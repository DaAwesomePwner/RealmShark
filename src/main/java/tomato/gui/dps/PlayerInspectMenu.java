package tomato.gui.dps;

import tomato.backend.data.Entity;
import tomato.backend.data.InspectSnapshot;
import tomato.gui.modern.ContentStyle;
import tomato.gui.security.ParsePanelGUI;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.function.Function;
import java.util.function.IntFunction;

/** Pins the clicked build before live sorting, filtering or encounter changes can replace the row. */
final class PlayerInspectMenu extends JPopupMenu {
    private final Function<Point, Entity> playerAt;
    private final java.util.function.Supplier<String> origin;
    private final JMenuItem inspect = new JMenuItem("Inspect");
    private InspectSnapshot target;
    private String pinnedOrigin;
    private Component owner;

    private PlayerInspectMenu(Function<Point, Entity> playerAt, java.util.function.Supplier<String> origin) {
        super("Player actions"); this.playerAt = playerAt; this.origin = origin;
        setName("dps-player-actions");
        inspect.setName("dps-inspect-player");
        inspect.setToolTipText("Show this player's captured class, equipment, enchants and base stats.");
        inspect.setEnabled(false);
        inspect.addActionListener(e -> ParsePanelGUI.inspectPlayer(owner, target, pinnedOrigin));
        add(inspect);
    }

    @Override public void show(Component invoker, int x, int y) {
        target = null;
        Entity player = playerAt.apply(new Point(x, y));
        inspect.setEnabled(player != null);
        if (player == null) { setVisible(false); return; }
        // Producer receipt time of the displayed copy; 0 (Not captured) for saved encounters. Never the menu-open time.
        target = new InspectSnapshot(player, player.observedAt());
        pinnedOrigin = origin == null ? null : origin.get();
        Window window = SwingUtilities.getWindowAncestor(invoker);
        owner = window == null ? invoker : window;
        SwingUtilities.updateComponentTreeUI(this);
        ContentStyle.refreshFonts(this);
        super.show(invoker, x, y);
    }

    static void install(JTable table, IntFunction<Entity> playerAtModelRow) { install(table, playerAtModelRow, null); }
    static void install(JTable table, IntFunction<Entity> playerAtModelRow, java.util.function.Supplier<String> origin) {
        PlayerInspectMenu menu = new PlayerInspectMenu(point -> {
            int row = table.rowAtPoint(point);
            if (row < 0 || row >= table.getRowCount()) { table.clearSelection(); return null; }
            table.setRowSelectionInterval(row, row);
            return playerAtModelRow.apply(table.convertRowIndexToModel(row));
        }, origin);
        table.setComponentPopupMenu(menu);
        table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0), "inspect-player-menu");
        table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK), "inspect-player-menu");
        table.getActionMap().put("inspect-player-menu", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                int selected = table.getSelectedRow();
                if (selected < 0) return;
                Rectangle cell = table.getCellRect(selected, 0, true);
                table.scrollRectToVisible(new Rectangle(table.getVisibleRect().x, cell.y, 1, cell.height));
                menu.show(table, table.getVisibleRect().x + 8, cell.y + cell.height / 2);
            }
        });
    }

    static void install(JComponent row, Entity player) {
        row.setComponentPopupMenu(new PlayerInspectMenu(point -> player, null));
        inheritPopup(row);
    }

    private static void inheritPopup(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JComponent && ((JComponent)child).getComponentPopupMenu() == null)
                ((JComponent)child).setInheritsPopupMenu(true);
            if (child instanceof Container) inheritPopup((Container)child);
        }
    }
}
