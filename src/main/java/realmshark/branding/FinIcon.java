package realmshark.branding;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/** Canonical fin artwork. Deliberately AWT-only so the build can compile it in isolation. */
public final class FinIcon {
    private static final int[] SIZES = {16, 20, 24, 32, 48, 64, 128, 256};
    private static final Color BACKGROUND = new Color(0x191724);
    private static final Color VIOLET = new Color(0xB89CFF);

    private FinIcon() { }

    public static int[] sizes() { return SIZES.clone(); }

    // Original 22-unit workspace fin, shared by the outline and application badge.
    private static Path2D fin() {
        Path2D path = new Path2D.Double();
        path.moveTo(2, 18);
        path.lineTo(9, 14);
        path.lineTo(13, 3);
        path.lineTo(15, 14);
        path.lineTo(20, 18);
        return path;
    }

    public static void paintOutline(Graphics2D graphics, int x, int y, int size, Color color) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.translate(x, y);
            g.scale(size / 22.0, size / 22.0);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(color);
            g.draw(fin());
            g.draw(new Line2D.Double(2, 20, 20, 20));
        } finally { g.dispose(); }
    }

    /** An opaque dark badge with transparent rounded corners and a high-contrast filled fin. */
    public static BufferedImage image(int size) {
        if (size < 1 || size > 1024) throw new IllegalArgumentException("Icon size must be 1..1024");
        int resolution = size * 4;
        BufferedImage artwork = new BufferedImage(resolution, resolution, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = artwork.createGraphics();
        try {
            g.scale(resolution / 32.0, resolution / 32.0);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(BACKGROUND);
            g.fill(new RoundRectangle2D.Double(0, 0, 32, 32, 8, 8));
            g.translate(3, 2);
            g.scale(26.0 / 22, 26.0 / 22);
            g.setColor(VIOLET);
            Path2D filled = fin();
            filled.closePath();
            g.fill(filled);
            g.setStroke(new BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(2, 20, 20, 20));
        } finally { g.dispose(); }
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(artwork, 0, 0, size, size, null);
        } finally { g.dispose(); }
        return image;
    }
}
