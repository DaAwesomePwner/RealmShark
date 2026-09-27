package tomato.gui.kit;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

/** Row and column layouts shared by glance cards and pages. EDT only. */
public final class KitLayouts {
    private KitLayouts() {}

    /** Rows top to bottom at full width, {@code gap} apart; hidden rows take no space, and wrapping rows grow with their width. */
    public static JPanel stack(int gap, JComponent... rows) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        for (int i = 0; i < rows.length; i++) {
            c.gridy = i;
            c.insets = new Insets(i == 0 ? 0 : gap, 0, 0, 0);
            panel.add(rows[i], c);
        }
        // Rows stay at the top when a grid stretches this panel to its row partner's height.
        c.gridy = rows.length;
        c.weighty = 1;
        c.insets = new Insets(0, 0, 0, 0);
        panel.add(Box.createVerticalGlue(), c);
        return panel;
    }

    /**
     * One row: the first part stretches at the left and the rest sit at the right edge, all vertically centered, when
     * everything fits. Otherwise the first part takes its own line and the rest flow below it from the left, wrapping at the
     * width. Hidden parts take no space. Rows re-measure when their width changes.
     */
    public static JPanel spread(int gap, JComponent... parts) { return spreadWhenWide(0, gap, parts); }

    /** As {@link #spread}, with the one-row form only while {@link #rootWidth} is at least {@code minimumRootWidth}. */
    public static JPanel spreadWhenWide(int minimumRootWidth, int gap, JComponent... parts) {
        if (parts.length == 0) throw new IllegalArgumentException("A spread row needs a leading part");
        JPanel panel = new JPanel() {
            @Override public void setBounds(int x, int y, int width, int height) {
                boolean changed = width != getWidth();
                super.setBounds(x, y, width, height);
                if (changed) SwingUtilities.invokeLater(this::revalidate); // the row count depends on this width
            }
        };
        panel.setOpaque(false);
        panel.setLayout(new Spread(minimumRootWidth, gap));
        for (JComponent part : parts) panel.add(part);
        return panel;
    }

    /** The width of the component's root pane, or outside a window (or before layout) the component's own width. */
    public static int rootWidth(Component component) {
        JRootPane root = SwingUtilities.getRootPane(component);
        return root != null && root.getWidth() > 0 ? root.getWidth() : component.getWidth();
    }

    private static final class Spread implements LayoutManager {
        private final int minimumRootWidth, gap;

        Spread(int minimumRootWidth, int gap) { this.minimumRootWidth = minimumRootWidth; this.gap = gap; }

        @Override public void addLayoutComponent(String name, Component component) {}
        @Override public void removeLayoutComponent(Component component) {}
        @Override public Dimension preferredLayoutSize(Container target) { return size(target); }
        /** Height as preferred and no width floor, so enclosing GridBag stacks never switch to minimum sizes. */
        @Override public Dimension minimumLayoutSize(Container target) { return new Dimension(0, size(target).height); }
        @Override public void layoutContainer(Container target) { place(target, true); }

        private Dimension size(Container target) {
            synchronized (target.getTreeLock()) {
                Insets insets = target.getInsets();
                int available = available(target), height = place(target, false);
                return new Dimension(available > 0 ? available : natural(target) + insets.left + insets.right, height);
            }
        }

        /** The one-row width of the visible parts. */
        private int natural(Container target) {
            int width = 0;
            for (Component part : target.getComponents()) if (part.isVisible()) width += (width == 0 ? 0 : gap) + part.getPreferredSize().width;
            return width;
        }

        private int place(Container target, boolean apply) {
            Insets insets = target.getInsets();
            int width = Math.max(1, available(target) - insets.left - insets.right), left = insets.left, y = insets.top;
            Component[] parts = target.getComponents();
            Component lead = parts[0];
            List<Component> rest = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) if (parts[i].isVisible()) rest.add(parts[i]);
            int restWidth = 0, restHeight = 0;
            for (Component part : rest) {
                Dimension size = part.getPreferredSize();
                restWidth += (restWidth == 0 ? 0 : gap) + size.width;
                restHeight = Math.max(restHeight, size.height);
            }
            Dimension leadSize = lead.getPreferredSize();
            if (lead.isVisible() && !rest.isEmpty() && (minimumRootWidth <= 0 || rootWidth(target) >= minimumRootWidth) && leadSize.width + gap + restWidth <= width) {
                int height = Math.max(leadSize.height, restHeight);
                if (apply) {
                    lead.setBounds(left, y + (height - leadSize.height) / 2, width - restWidth - gap, leadSize.height);
                    int x = left + width - restWidth;
                    for (Component part : rest) {
                        Dimension size = part.getPreferredSize();
                        part.setBounds(x, y + (height - size.height) / 2, size.width, size.height);
                        x += size.width + gap;
                    }
                }
                return y + height + insets.bottom;
            }
            if (lead.isVisible()) {
                if (apply) lead.setBounds(left, y, width, leadSize.height);
                y += leadSize.height + (rest.isEmpty() ? 0 : gap);
            }
            List<Component> row = new ArrayList<>();
            int rowWidth = 0;
            for (Component part : rest) {
                int partWidth = part.getPreferredSize().width;
                if (!row.isEmpty() && rowWidth + gap + partWidth > width) { y = row(row, left, y, apply) + gap; row.clear(); rowWidth = 0; }
                rowWidth += (row.isEmpty() ? 0 : gap) + partWidth;
                row.add(part);
            }
            if (!row.isEmpty()) y = row(row, left, y, apply);
            return y + insets.bottom;
        }

        /** One wrapped row of trailing parts from the left, vertically centered; returns its bottom. */
        private int row(List<Component> row, int left, int y, boolean apply) {
            int height = 0;
            for (Component part : row) height = Math.max(height, part.getPreferredSize().height);
            int x = left;
            for (Component part : row) {
                Dimension size = part.getPreferredSize();
                if (apply) part.setBounds(x, y + (height - size.height) / 2, size.width, size.height);
                x += size.width + gap;
            }
            return y + height;
        }

        private static int available(Container target) {
            if (target.getWidth() > 0) return target.getWidth();
            Container parent = target.getParent();
            if (parent == null) return 0;
            Insets insets = parent.getInsets();
            return parent.getWidth() - insets.left - insets.right;
        }
    }
}
