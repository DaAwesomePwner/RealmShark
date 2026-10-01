package tomato.gui.kit;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.Icon;
import tomato.realmshark.EnchantInfo;

/**
 * Loot's inventory-style enchant marks: one diamond per unlocked slot at the bottom-right, with a rarity halo behind the sprite.
 * Colors and live sprites resolve on each paint, so neither a theme change nor asynchronously loaded assets leave stale artwork.
 */
public final class EnchantPips {
    private EnchantPips() {}

    /** Start with legible diamonds, shrinking only for the actual slot count; prefer even pixel sizes when room permits. */
    static int size(int side, int count) {
        int d = Math.max(4, Math.min(8, Math.round(side * .16f)));
        while (d > 3 && count * d + (count - 1) * gap(d) > side - 3) d--;
        return d > 4 && d % 2 != 0 ? d - 1 : d;
    }

    /** Clear pixels between the diamonds' inclusive bounds. */
    static int gap(int size) { return size >= 6 ? 2 : 1; }

    /** Paints inside a well at x, y, {@code side + 1} px square, with the row anchored two pixels from its bottom-right edge. */
    public static void paint(Graphics2D graphics, EnchantInfo info, int x, int y, int side) {
        if (info == null) return;
        boolean unreadable = info.state() == EnchantInfo.State.UNREADABLE;
        if (!unreadable && !info.enchanted()) return;
        int count = unreadable ? 1 : info.rarity().ordinal(), d = size(side, count);
        Color ink = unreadable ? Tokens.color(Tokens.Role.TEXT_MUTED) : Tokens.rarity(info.rarity());
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            // Pixel-aligned diamonds keep even the smallest wells' slots separate and their centers fully colored.
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setStroke(new BasicStroke(1f));
            int top = y + side - 1 - d;
            for (int i = 0; i < count; i++) {
                int left = x + side - 1 - d - i * (d + gap(d));
                Polygon diamond = new Polygon(new int[] {left + d / 2, left + d - 1, left + d / 2, left},
                    new int[] {top, top + d / 2, top + d - 1, top + d / 2}, 4);
                g.setColor(ink);
                g.fillPolygon(diamond);
                g.setColor(Tokens.color(Tokens.Role.CANVAS));
                g.drawPolygon(diamond);
            }
        } finally { g.dispose(); }
    }

    /**
     * The unchanged sprite over a soft two-pixel alpha dilation. No artwork is retained: small loot sprites are sampled in memory,
     * placeholders bypass the halo on every paint, and a retained LiveSprite can therefore acquire its glow once assets arrive.
     * Dimensions stay unchanged; the halo may extend two pixels into the well's padding.
     */
    public static Icon glow(Icon sprite, EnchantInfo info) {
        if (sprite == null || info == null || !info.enchanted()) return sprite;
        return new Icon() {
            @Override public int getIconWidth() { return sprite.getIconWidth(); }
            @Override public int getIconHeight() { return sprite.getIconHeight(); }
            @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
                if (!Sprites.isPlaceholder(sprite) && getIconWidth() > 0 && getIconHeight() > 0) {
                    int w = getIconWidth() + 4, h = getIconHeight() + 4;
                    BufferedImage mask = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D sample = mask.createGraphics();
                    try { sprite.paintIcon(c, sample, 2, 2); } finally { sample.dispose(); }
                    BufferedImage halo = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    int rgb = Tokens.rarity(info.rarity()).getRGB() & 0xffffff;
                    for (int py = 0; py < h; py++) for (int px = 0; px < w; px++) {
                        int alpha = 0;
                        for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
                            int sx = px + dx, sy = py + dy, distance = dx * dx + dy * dy;
                            if (distance == 0 || distance > 5 || sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
                            alpha = Math.max(alpha, (mask.getRGB(sx, sy) >>> 24) * (6 - distance) / 12);
                        }
                        // Leave the sprite's own pixels alone, including translucent antialiased edges.
                        if ((mask.getRGB(px, py) >>> 24) == 0) halo.setRGB(px, py, (alpha << 24) | rgb);
                    }
                    graphics.drawImage(halo, x - 2, y - 2, null);
                }
                sprite.paintIcon(c, graphics, x, y);
            }
        };
    }
}
