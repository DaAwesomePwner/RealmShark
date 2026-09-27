package tomato.gui.history;

import java.awt.*;
import javax.swing.JPanel;

/**
 * A FilterBar slot whose controls wrap to the width of the row that hosts it. FilterBar lays its slots out at their
 * preferred size, so a plain panel with several controls would push past the page edge at 680 px or 24 pt.
 */
public final class WrapRow extends JPanel {
    public WrapRow(Component... children) {
        super(new FlowLayout(FlowLayout.LEADING, 6, 2));
        setOpaque(false);
        for (Component child : children) add(child);
    }

    @Override public Dimension getPreferredSize() {
        Container host = getParent();
        if (host == null || host.getWidth() <= 0) return super.getPreferredSize();
        Insets hostInsets = host.getInsets();
        // The host is FilterBar's wrapping row (FlowLayout hgap 6): a child may use its width minus both gaps.
        int limit = host.getWidth() - hostInsets.left - hostInsets.right - 12;
        return limit > 0 ? size(limit) : super.getPreferredSize();
    }

    @Override public Dimension getMinimumSize() { return getPreferredSize(); }

    /** Mirrors FlowLayout.layoutContainer's line breaking for a row of the given width. */
    private Dimension size(int width) {
        FlowLayout flow = (FlowLayout) getLayout();
        Insets insets = getInsets();
        int available = width - insets.left - insets.right - flow.getHgap() * 2, x = 0, rowHeight = 0, widest = 0;
        int height = insets.top + flow.getVgap();
        for (Component child : getComponents()) {
            if (!child.isVisible()) continue;
            Dimension size = child.getPreferredSize();
            if (x > 0 && x + size.width > available) { height += rowHeight + flow.getVgap(); widest = Math.max(widest, x); x = 0; rowHeight = 0; }
            x += (x > 0 ? flow.getHgap() : 0) + size.width;
            rowHeight = Math.max(rowHeight, size.height);
        }
        widest = Math.max(widest, x);
        return new Dimension(Math.min(width, widest + insets.left + insets.right + flow.getHgap() * 2),
            height + rowHeight + flow.getVgap() + insets.bottom);
    }
}
