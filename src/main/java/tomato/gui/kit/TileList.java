package tomato.gui.kit;

import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.swing.*;

/**
 * A wrapping list of fixed-size painted tiles (spec §9, §10), extracted from the P3a character gallery so the Exalts grid and the
 * Pets gallery share it:
 * - One renderer paints every cell (no per-tile component tree), so hundreds of tiles stay fast. The cell is the renderer's
 *   preferred size (a renderer that is a Component is asked directly) and is re-measured when the font changes.
 * - The preferred height follows the width: as many columns as fit, and before the first layout the nearest sized ancestor's
 *   width. The page scrolls the tiles; the list itself never scrolls or squeezes.
 * - {@link #setItems} fires one model event per change: in place (one contentsChanged) when every position keeps its key, so a
 *   live tick keeps the selection and the scroll; otherwise one removal and one insertion, after which the selection follows
 *   its item's key. An equal list fires nothing.
 * - Arrow keys move between tiles; Enter, Space or a double-click on a tile runs the {@link #onOpen} action, a single click only
 *   selects. The renderer paints the selection and the focus ring from JList's selected and focused flags.
 * - Each tile's accessible name comes from {@code accessibleName}; the list's own name is the caller's.
 * EDT only.
 */
public class TileList<T> extends JList<T> {
    /** The action Enter and Space run, unless {@link #onOpen(String, Consumer)} names another. */
    public static final String OPEN = "open-tile";
    private final Tiles<T> tiles;
    private final ListCellRenderer<? super T> renderer;
    private final Function<T, String> key, names;
    private Consumer<T> open;
    private String command;
    private int measuredWidth = -1;

    /**
     * {@code name}: the component name; {@code key}: an item's stable identity (equal keys at the same position update in place);
     * {@code accessibleName}: the name a screen reader announces for one tile.
     */
    public TileList(String name, ListCellRenderer<? super T> renderer, Function<T, String> key, Function<T, String> accessibleName) {
        super(new Tiles<>(key));
        this.tiles = (Tiles<T>) getModel();
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.key = Objects.requireNonNull(key, "key");
        this.names = Objects.requireNonNull(accessibleName, "accessibleName");
        setName(name);
        setLayoutOrientation(HORIZONTAL_WRAP);
        setVisibleRowCount(0);
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setOpaque(false);
        setCellRenderer(renderer);
        resize();
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                if (getWidth() != measuredWidth) { measuredWidth = getWidth(); revalidate(); } // the row count depends on the width
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (open == null || e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) return;
                int index = locationToIndex(e.getPoint());
                Rectangle cell = index < 0 ? null : getCellBounds(index, index);
                if (cell != null && cell.contains(e.getPoint())) open.accept(tiles.getElementAt(index));
            }
        });
    }

    /** EDT: shows items; an item whose key equals the one at the same position updates in place (one change event, no scroll jump). */
    public void setItems(List<T> items) {
        T selected = getSelectedValue();
        if (!tiles.set(items)) return;
        resize();
        if (selected == null) return;
        String wanted = key.apply(selected);
        for (int i = 0; i < tiles.getSize(); i++)
            if (key.apply(tiles.getElementAt(i)).equals(wanted)) { if (getSelectedIndex() != i) setSelectedIndex(i); return; }
    }

    /** The items shown, in order (an unmodifiable copy of the last {@link #setItems}). */
    public List<T> items() { return tiles.items; }

    /** Enter, Space or double-click on an item runs the action; a single click only selects. */
    public void onOpen(Consumer<T> action) { onOpen(OPEN, action); }

    /** As {@link #onOpen(Consumer)}, bound under the action name {@code command} (a list may keep a name its callers already use). */
    public void onOpen(String command, Consumer<T> action) {
        open = Objects.requireNonNull(action, "action");
        if (Objects.requireNonNull(command, "command").equals(this.command)) return;
        if (this.command != null) getActionMap().remove(this.command);
        this.command = command;
        for (int code : new int[] {KeyEvent.VK_ENTER, KeyEvent.VK_SPACE})
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(code, 0), command);
        getActionMap().put(command, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                T item = getSelectedValue();
                if (item != null) open.accept(item);
            }
        });
    }

    /**
     * Selects the item whose key is {@code key}; scrolls it into view when the selection actually changes, or always when
     * {@code reveal} is true. Null or an unknown key clears the selection.
     */
    public void selectKey(String key, boolean reveal) {
        for (int i = 0; key != null && i < tiles.getSize(); i++)
            if (this.key.apply(tiles.getElementAt(i)).equals(key)) {
                boolean changed = getSelectedIndex() != i;
                if (changed) setSelectedIndex(i);
                if (changed || reveal) ensureIndexIsVisible(i);
                return;
            }
        if (!isSelectionEmpty()) clearSelection();
    }

    /** The renderer's preferred size; a renderer that is not itself a Component is measured on the first item (null when empty). */
    private Dimension cell() {
        if (renderer instanceof Component) return ((Component) renderer).getPreferredSize();
        if (getModel().getSize() == 0) return null;
        return renderer.getListCellRendererComponent(this, getModel().getElementAt(0), 0, false, false).getPreferredSize();
    }

    private void resize() {
        Dimension cell = cell();
        if (cell == null) return;
        if (getFixedCellWidth() != cell.width) super.setFixedCellWidth(cell.width);
        if (getFixedCellHeight() != cell.height) super.setFixedCellHeight(cell.height);
    }

    /** ContentStyle.refreshFonts sizes text lists by line height; a tile keeps the height its renderer needs. */
    @Override public void setFixedCellHeight(int height) {
        Dimension cell = renderer == null ? null : cell();
        super.setFixedCellHeight(cell == null ? height : cell.height);
    }
    @Override public void setFont(Font font) { super.setFont(font); if (renderer != null) resize(); }
    @Override public void addNotify() { super.addNotify(); resize(); }

    /** Rows of as many tiles as fit the list's width (before its first layout, its nearest sized ancestor's). */
    @Override public Dimension getPreferredSize() {
        Insets insets = getInsets();
        int count = getModel().getSize(), cellWidth = Math.max(1, getFixedCellWidth()), width = getWidth();
        for (Container parent = getParent(); width <= 0 && parent != null; parent = parent.getParent()) width = parent.getWidth();
        int columns = Math.max(1, (width - insets.left - insets.right) / cellWidth), rows = (count + columns - 1) / columns;
        return new Dimension(Math.min(count, columns) * cellWidth + insets.left + insets.right,
            rows * Math.max(0, getFixedCellHeight()) + insets.top + insets.bottom);
    }
    @Override public Dimension getMinimumSize() { return getPreferredSize(); }

    /** Each tile announces {@code accessibleName} for its item (the renderer still supplies its role and description). */
    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleTiles();
        return accessibleContext;
    }

    private final class AccessibleTiles extends AccessibleJList {
        @Override public Accessible getAccessibleChild(int index) {
            return index < 0 || index >= getModel().getSize() ? null : new Tile(index);
        }
        @Override public Accessible getAccessibleAt(Point point) {
            int index = locationToIndex(point);
            return index < 0 ? null : new Tile(index);
        }

        private final class Tile extends AccessibleJListChild {
            private final int index;
            Tile(int index) { super(TileList.this, index); this.index = index; }
            @Override public String getAccessibleName() {
                if (accessibleName != null) return accessibleName; // one a caller set explicitly on this child wins
                return index < getModel().getSize() ? names.apply(getModel().getElementAt(index)) : super.getAccessibleName();
            }
        }
    }

    /** The items shown; a change fires one removal and one insertion, never one event per item, and an equal list fires nothing. */
    private static final class Tiles<T> extends AbstractListModel<T> {
        private final Function<T, String> key;
        private List<T> items = List.of();
        Tiles(Function<T, String> key) { this.key = key; }
        @Override public int getSize() { return items.size(); }
        @Override public T getElementAt(int index) { return items.get(index); }

        boolean set(List<T> next) {
            List<T> copy = List.copyOf(next);
            if (copy.equals(items)) return false;
            if (sameKeys(copy)) {
                // Only field values changed (e.g. a live tick's "Played N ago"): update in place so the selection is untouched
                // and no interval event fires, instead of a remove+insert that would clear it every second.
                items = copy;
                if (!copy.isEmpty()) fireContentsChanged(this, 0, copy.size() - 1);
                return true;
            }
            int before = items.size();
            items = List.of();
            if (before > 0) fireIntervalRemoved(this, 0, before - 1);
            items = copy;
            if (!copy.isEmpty()) fireIntervalAdded(this, 0, copy.size() - 1);
            return true;
        }

        /** Same items in the same order (by key), so the change is field-only and needs no structural list event. */
        private boolean sameKeys(List<T> next) {
            if (next.size() != items.size()) return false;
            for (int i = 0; i < next.size(); i++) if (!key.apply(next.get(i)).equals(key.apply(items.get(i)))) return false;
            return true;
        }
    }
}
