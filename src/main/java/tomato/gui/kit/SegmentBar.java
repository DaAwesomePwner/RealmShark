package tomato.gui.kit;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import tomato.gui.modern.DisplayFormat;

/**
 * A horizontal stacked bar of up to three segments over a total (the quest Planner's reserved / covered / missing, spec §6.5):
 * reserved ACCENT, covered GOOD, missing WARN, left to right over a CONTROL track, each as wide as its share of the total. Values
 * are longs, since a demand summed over several plans can exceed int. Segments above the total are scaled to their sum, so they
 * never overflow the bar.
 * - An unknown total (or segment) draws nothing at all, not even the track: an empty track reads as 0% (spec §1). The tooltip and
 *   accessible description then say "Not captured"; a caller usually hides the bar and says why in text.
 * - Painted lists draw the same bar with {@link #paint(Graphics2D, int, int, int, int, long, long, long, long)}, with no component
 *   per row (spec §9).
 * Colors come from Tokens at paint time. EDT only.
 */
public class SegmentBar extends JComponent implements Accessible {
    public static final String UNKNOWN = "Not captured";
    private static final Tokens.Role[] ROLES = {Tokens.Role.ACCENT, Tokens.Role.GOOD, Tokens.Role.WARN};
    private Long reserved, covered, missing, total;

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.PROGRESS_BAR; }
        };
        return accessibleContext;
    }

    public SegmentBar() { setOpaque(false); set(null, null, null, null); }

    /** reserved, covered and missing (each ≥ 0) of total; a null total or segment is unknown: no track, a "Not captured" tooltip. */
    public void set(Long reserved, Long covered, Long missing, Long total) {
        for (Long value : new Long[] {reserved, covered, missing, total})
            if (value != null && value < 0) throw new IllegalArgumentException("Segment values must not be negative: " + value);
        this.reserved = reserved;
        this.covered = covered;
        this.missing = missing;
        this.total = total;
        String text = known() ? "Reserved " + DisplayFormat.formatInteger(reserved.longValue()) + ", covered " + DisplayFormat.formatInteger(covered.longValue())
            + ", missing " + DisplayFormat.formatInteger(missing.longValue()) + " of " + DisplayFormat.formatInteger(total.longValue()) : UNKNOWN;
        setToolTipText(text);
        getAccessibleContext().setAccessibleDescription(text);
        repaint();
    }

    /** Whether every value is known, so the bar draws. */
    public boolean known() { return reserved != null && covered != null && missing != null && total != null; }

    /**
     * The segments' right edges in a bar {@code width} px wide: reserved, reserved + covered, and all three, each at its cumulative
     * share of the total (of the segments' sum when that is larger). A zero total fills nothing.
     */
    public static int[] edges(long reserved, long covered, long missing, long total, int width) {
        // Doubles: the cumulative sums of three longs can overflow a long, and a pixel edge needs no more precision than this.
        double scale = Math.max((double) total, (double) reserved + covered + missing);
        double[] sums = {reserved, (double) reserved + covered, (double) reserved + covered + missing};
        int[] edges = new int[3];
        for (int i = 0; i < 3; i++) edges[i] = scale <= 0 ? 0 : (int) Math.min(width, Math.round(width * (sums[i] / scale)));
        return edges;
    }

    /** Paints a known bar into (x, y, width, height): the CONTROL track, then the three segments inside its rounded ends. */
    public static void paint(Graphics2D graphics, int x, int y, int width, int height, long reserved, long covered, long missing, long total) {
        if (width <= 0 || height <= 0) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Shape track = new RoundRectangle2D.Float(x, y, width, height, height, height);
            g.setColor(Tokens.color(Tokens.Role.CONTROL));
            g.fill(track);
            g.clip(track); // square segment ends take the track's rounded ends
            int[] edges = edges(reserved, covered, missing, total, width);
            int left = x;
            for (int i = 0; i < 3; i++) {
                int right = x + edges[i];
                if (right > left) { g.setColor(Tokens.color(ROLES[i])); g.fillRect(left, y, right - left, height); }
                left = Math.max(left, right);
            }
        } finally {
            g.dispose();
        }
    }

    @Override public Dimension getPreferredSize() { return new Dimension(80, 6); }
    @Override public Dimension getMinimumSize() { return new Dimension(24, 6); }

    @Override protected void paintComponent(Graphics graphics) {
        if (!known()) return; // unknown: no track that could read as 0%
        int height = Math.min(getHeight(), 6);
        paint((Graphics2D) graphics, 0, (getHeight() - height) / 2, getWidth(), height, reserved, covered, missing, total);
    }
}
