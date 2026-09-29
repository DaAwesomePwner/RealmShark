package tomato.gui.settings;

import java.awt.*;
import java.lang.ref.WeakReference;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.kit.SectionHeader;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;
import tomato.gui.stats.LootFilters;

/**
 * Settings › Loot filters (P6a): which bag colors are listed, the same {@link LootFilters} model and preferences as Edit › Filter
 * Loot. Every checkbox saves at once; each follows the model, so a change in the menu (or anywhere else) shows here too.
 */
public final class LootFiltersSection extends JPanel {
    static final String HELP = "Which bag colors show in Loot › Highlights' notable drops. Tiles and counts always include every "
        + "observed drop. Bag sounds are under Notifications.";
    /**
     * Below this section width the colors stack in one column. CONTROL_INSET is the leading gap of {@link ContentStyle#controls()}'
     * row, so the checkboxes start where Show all does.
     */
    static final int NARROW = 420, CONTROL_INSET = 6;

    private final LootFilters filters = LootFilters.get();
    private final Map<LootFilters.Kind, JCheckBox> boxes = new EnumMap<>(LootFilters.Kind.class);
    private final JButton showAll = new JButton("Show all");
    private final JTextArea note = ContentStyle.wrappingText(HELP);

    public LootFiltersSection() {
        super(new BorderLayout());
        setName("settings-loot-filters");
        setOpaque(false);
        JPanel grid = new Columns();
        grid.setOpaque(false);
        grid.setBorder(new EmptyBorder(0, CONTROL_INSET, 0, 0));
        grid.setName("settings-loot-filters-kinds");
        for (LootFilters.Kind kind : LootFilters.Kind.values()) {
            JCheckBox box = new JCheckBox(kind.label());
            box.setName("settings-loot-filter-" + kind.name().toLowerCase(Locale.ROOT));
            box.setOpaque(false);
            box.addActionListener(e -> filters.set(kind, box.isSelected()));
            boxes.put(kind, box);
            grid.add(box);
        }
        showAll.setName("settings-loot-filters-show-all");
        showAll.addActionListener(e -> {
            for (LootFilters.Kind kind : LootFilters.Kind.values()) if (!filters.shows(kind)) filters.set(kind, true);
        });
        note.setName("settings-loot-filters-help");

        JPanel group = new JPanel(new BorderLayout(0, Tokens.XS));
        group.setOpaque(false);
        group.setBorder(new EmptyBorder(Tokens.M, 0, Tokens.S, 0));
        JPanel top = new JPanel(new BorderLayout(0, Tokens.XS));
        top.setOpaque(false);
        top.add(new SectionHeader("Loot filters"), BorderLayout.NORTH);
        top.add(note, BorderLayout.CENTER);
        group.add(top, BorderLayout.NORTH);
        group.add(grid, BorderLayout.CENTER);
        JPanel actions = ContentStyle.controls();
        actions.setOpaque(false);
        actions.add(showAll);
        group.add(actions, BorderLayout.SOUTH);
        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.add(group, BorderLayout.NORTH);
        add(ContentStyle.page(null, body, null));

        sync();
        filters.addListener(new Follow(this));
    }

    @Override public void updateUI() {
        super.updateUI();
        if (note != null) note.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
    }

    /** Shows the model: one checkbox per color, and Show all only while a color is hidden. */
    private void sync() {
        boolean hidden = false;
        for (Map.Entry<LootFilters.Kind, JCheckBox> entry : boxes.entrySet()) {
            boolean shown = filters.shows(entry.getKey());
            entry.getValue().setSelected(shown);
            hidden |= !shown;
        }
        showAll.setEnabled(hidden);
        showAll.setToolTipText(hidden ? "Show every bag color again" : "Every bag color is shown");
        note.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
    }

    /**
     * The colors in the menu's order, row by row, in two columns as wide as the widest label with a {@link Tokens#XL} gap, packed
     * from the left instead of spread across the page; one column when the section is narrower than {@link #NARROW} or two would
     * not fit. The column count follows the width, so a width change lays the page out again (after the layout that changed it:
     * an invalidation during it is lost).
     */
    private static final class Columns extends JPanel implements LayoutManager {
        private static final int GAP = Tokens.XL, ROW_GAP = Tokens.XS;

        Columns() { setLayout(this); }

        @Override public void setBounds(int x, int y, int width, int height) {
            boolean changed = width != getWidth();
            super.setBounds(x, y, width, height);
            if (changed) SwingUtilities.invokeLater(this::revalidate);
        }

        /** The widest and tallest visible checkbox. */
        private static Dimension cell(Container target) {
            Dimension cell = new Dimension();
            for (Component child : target.getComponents()) {
                if (!child.isVisible()) continue;
                Dimension size = child.getPreferredSize();
                cell.width = Math.max(cell.width, size.width);
                cell.height = Math.max(cell.height, size.height);
            }
            return cell;
        }

        private static int visible(Container target) {
            int count = 0;
            for (Component child : target.getComponents()) if (child.isVisible()) count++;
            return count;
        }

        /** Two columns unless the width (this panel's, or its parent's before the first layout) is known and too narrow. */
        private static int columns(Container target, Dimension cell) {
            int width = target.getWidth();
            if (width <= 0 && target.getParent() != null) {
                Insets parent = target.getParent().getInsets();
                width = target.getParent().getWidth() - parent.left - parent.right;
            }
            return width > 0 && width < Math.max(NARROW, target.getInsets().left + target.getInsets().right + 2 * cell.width + GAP) ? 1 : 2;
        }

        @Override public Dimension preferredLayoutSize(Container target) {
            synchronized (target.getTreeLock()) {
                Insets insets = target.getInsets();
                Dimension cell = cell(target);
                int columns = columns(target, cell), rows = (visible(target) + columns - 1) / columns;
                return new Dimension(insets.left + insets.right + columns * cell.width + (columns - 1) * GAP,
                    insets.top + insets.bottom + rows * cell.height + Math.max(0, rows - 1) * ROW_GAP);
            }
        }

        /** Page bodies are sized from their minimum height: the same rows as the preferred layout, one column wide. */
        @Override public Dimension minimumLayoutSize(Container target) {
            Insets insets = target.getInsets();
            return new Dimension(insets.left + insets.right + cell(target).width, preferredLayoutSize(target).height);
        }

        @Override public void layoutContainer(Container target) {
            synchronized (target.getTreeLock()) {
                Insets insets = target.getInsets();
                Dimension cell = cell(target);
                int columns = columns(target, cell), index = 0;
                boolean leftToRight = target.getComponentOrientation().isLeftToRight();
                for (Component child : target.getComponents()) {
                    if (!child.isVisible()) continue;
                    int column = index % columns, row = index++ / columns;
                    int x = insets.left + column * (cell.width + GAP);
                    child.setBounds(leftToRight ? x : target.getWidth() - x - cell.width, insets.top + row * (cell.height + ROW_GAP), cell.width, cell.height);
                }
            }
        }

        @Override public void addLayoutComponent(String name, Component component) { }
        @Override public void removeLayoutComponent(Component component) { }
    }

    /** Follows LootFilters (EDT) without keeping a discarded section alive: it unregisters once the section is collected. */
    private static final class Follow implements Runnable {
        private final WeakReference<LootFiltersSection> section;

        Follow(LootFiltersSection section) { this.section = new WeakReference<>(section); }

        @Override public void run() {
            LootFiltersSection current = section.get();
            if (current == null) LootFilters.get().removeListener(this); else current.sync();
        }
    }
}
