package tomato.gui.kit;

import assets.IdToAsset;
import assets.ImageBuffer;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import javax.swing.Icon;
import javax.swing.ImageIcon;

/** Retained sprite references resolve through ImageBuffer's current cache after asset reloads. */
public final class Sprites {
    /** A well's room around its sprite, both sides together: a well {@code side} wide fits a sprite of up to {@code side - WELL}. */
    public static final int WELL = 6;
    private static final Map<Long, Icon> cache = new HashMap<>();

    private Sprites() {}

    public static synchronized Icon sprite(int objectId, int size) {
        if (size < 3 || size > 512) throw new IllegalArgumentException("Sprite size must be 3..512");
        long key = ((long) objectId << 32) | size;
        Icon cached = cache.get(key);
        if (cached == null) { cached = objectId <= 0 ? new Placeholder(size) : new LiveSprite(objectId, size); cache.put(key, cached); }
        return cached;
    }

    public static synchronized void clear() { cache.clear(); }

    /**
     * A rounded well at {@code x, y}, {@code side} square, tinted and outlined in the bag's color ({@link Tokens#bag}: muted when the
     * bag was not saved), the sprite centered and scaled down to the well's room, never enlarged. Colors resolve now, so call it while
     * painting. Shared by the run cards and Loot Highlights.
     */
    public static void paintWell(Component owner, Graphics2D g, Icon sprite, String bag, int x, int y, int side) {
        Color color = Tokens.bag(bag);
        g.setColor(Tokens.tint(color));
        g.fillRoundRect(x, y, side - 1, side - 1, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
        g.setColor(color);
        g.drawRoundRect(x, y, side - 1, side - 1, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
        int room = side - WELL, width = sprite.getIconWidth(), height = sprite.getIconHeight();
        double scale = Math.min(1d, (double) room / Math.max(1, Math.max(width, height)));
        Graphics2D icon = (Graphics2D) g.create();
        try {
            icon.translate(x + (side - width * scale) / 2, y + (side - height * scale) / 2);
            icon.scale(scale, scale);
            sprite.paintIcon(owner, icon, 0, 0);
        } finally {
            icon.dispose();
        }
    }

    public static boolean isPlaceholder(Icon icon) {
        return icon instanceof Placeholder || icon instanceof LiveSprite && ((LiveSprite) icon).current() instanceof Placeholder;
    }

    /** Display name, "Empty" for empty slots, or "Unknown item #id" without assets. */
    public static String name(int objectId) {
        if (objectId <= 0) return "Empty";
        String name;
        try { name = IdToAsset.objectName(objectId); } catch (RuntimeException e) { name = null; }
        return name == null || name.isEmpty() ? "Unknown item #" + objectId : name;
    }

    private static final class LiveSprite implements Icon {
        private final int id, size;
        private final Icon fallback;
        private ImageIcon previous;
        private Icon resolved;
        LiveSprite(int id, int size) { this.id = id; this.size = size; fallback = new Placeholder(size); }
        synchronized Icon current() {
            ImageIcon icon = ImageBuffer.getOutlinedIcon(id, size);
            if (icon != previous || resolved == null) {
                previous = icon;
                resolved = visible(icon) ? icon : fallback;
            }
            return resolved;
        }
        private boolean visible(ImageIcon icon) {
            if (icon == null || icon.getIconWidth() <= 0) return false;
            if (!(icon.getImage() instanceof BufferedImage)) return true;
            BufferedImage image = (BufferedImage) icon.getImage();
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
                if ((image.getRGB(x, y) >>> 24) != 0) return true;
            return false;
        }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) { current().paintIcon(c, g, x, y); }
    }

    /** A dashed rounded square in the muted color. */
    private static final class Placeholder implements Icon {
        private final int size;
        Placeholder(int size) { this.size = size; }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
        @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Tokens.color(Tokens.Role.TEXT_MUTED));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{2f, 2f}, 0f));
            float inset = Math.max(1f, size / 8f), side = size - 2 * inset - 1;
            g.draw(new RoundRectangle2D.Float(x + inset, y + inset, side, side, size / 4f, size / 4f));
            g.dispose();
        }
    }
}
