package tomato.gui.kit;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.Icon;
import tomato.realmshark.EnchantInfo;

/**
 * Inventory-style item corners: tier text or one diamond per unlocked slot, with a rarity halo behind enchanted sprites.
 * Colors and live sprites resolve on each paint, so neither a theme change nor asynchronously loaded assets leave stale artwork.
 */
public final class EnchantPips {
    private EnchantPips() {}

    /** Rarity ink, muted for unreadable data and absent when there is no enchant mark. */
    static Color ink(EnchantInfo info) {
        if (info == null) return null;
        if (info.state() == EnchantInfo.State.UNREADABLE) return Tokens.color(Tokens.Role.TEXT_MUTED);
        return info.enchanted() ? Tokens.rarity(info.rarity()) : null;
    }

    /**
     * Shared corner for a well {@code side + 1} px square. Enchant marks replace tier text; unreadable data keeps one muted pip.
     * Tier text needs at least 28 px, stays inside the bottom-right edge, and uses a canvas outline over the unchanged sprite.
     */
    public static void paintCorner(Graphics2D graphics, EnchantInfo info, String tierLabel, int x, int y, int side) {
        if (ink(info) != null) { paint(graphics, info, x, y, side); return; }
        if (side + 1 < 28 || tierLabel == null || tierLabel.isEmpty()) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.clipRect(x + 1, y + 1, side - 1, side - 1);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(Type.emphasis().deriveFont(Font.BOLD, Math.round((side + 1) * .29f)));
            FontMetrics metrics = g.getFontMetrics();
            int left = x + side - 2 - metrics.stringWidth(tierLabel), baseline = y + side - 2 - metrics.getDescent();
            g.setColor(Tokens.color(Tokens.Role.CANVAS));
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++)
                if (dx != 0 || dy != 0) g.drawString(tierLabel, left + dx, baseline + dy);
            g.setColor(Tokens.color("UT".equals(tierLabel) ? Tokens.Role.WARN : "ST".equals(tierLabel) ? Tokens.Role.BAD : Tokens.Role.TEXT));
            g.drawString(tierLabel, left, baseline);
        } finally { g.dispose(); }
    }

    /** Raw sprite with glow and its shared corner, sized to the shorter side and right-aligned for wide icons. */
    public static Icon decorate(Icon sprite, EnchantInfo info, String tierLabel) {
        if (sprite == null || (ink(info) == null && (tierLabel == null || tierLabel.isEmpty()
                || Math.min(sprite.getIconWidth(), sprite.getIconHeight()) < 28))) return sprite;
        Icon glowing = glow(sprite, info);
        return new Icon() {
            @Override public int getIconWidth() { return sprite.getIconWidth(); }
            @Override public int getIconHeight() { return sprite.getIconHeight(); }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                glowing.paintIcon(c, g, x, y);
                int side = Math.min(getIconWidth(), getIconHeight());
                paintCorner((Graphics2D) g, info, tierLabel, x + getIconWidth() - side, y, side - 1);
            }
        };
    }

    /** Solid diamond size: reserve a one-pixel gap and backing, shrinking only when needed; Bridge may clip the outer backing. */
    static int size(int side, int count) {
        int d = Math.max(4, Math.min(8, Math.round(side * .16f)));
        while (d > 3 && count * d + count - 1 > side - 3) d--;
        return d > 4 && d % 2 != 0 ? d - 1 : d;
    }

    /** Pixels between solid diamonds: two where the row and backing fit, otherwise one shared backing pixel. */
    static int gap(int side, int count) { return count * size(side, count) + (count - 1) * 2 <= side - 3 ? 2 : 1; }

    /**
     * One bottom-right row in a well {@code side + 1} px square. Paint the larger canvas diamonds first, then the complete solid
     * rarity diamonds: an outline drawn over the solids would erase their small interiors. A cramped Bridge row reaches the
     * icon edge and clips only the outer backing; otherwise both backing and solids stay inside the well's border.
     */
    public static void paint(Graphics2D graphics, EnchantInfo info, int x, int y, int side) {
        if (info == null) return;
        boolean unreadable = info.state() == EnchantInfo.State.UNREADABLE;
        if (!unreadable && !info.enchanted()) return;
        int count = unreadable ? 1 : info.rarity().ordinal(), d = size(side, count), gap = gap(side, count);
        Color ink = unreadable ? Tokens.color(Tokens.Role.TEXT_MUTED) : Tokens.rarity(info.rarity());
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            // Integer scanlines give even-sized diamonds a symmetric, solid center without stroked or antialiased edges.
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.clipRect(x, y, side + 1, side + 1);
            int top = y + side - 1 - d;
            int width = count * d + (count - 1) * gap;
            int right = x + (width > side - 3 ? side : side - 2);
            g.setColor(Tokens.color(Tokens.Role.CANVAS));
            for (int i = 0; i < count; i++) {
                int left = right - d + 1 - i * (d + gap);
                diamond(g, left - 1, top - 1, d + 2);
            }
            g.setColor(ink);
            for (int i = 0; i < count; i++) diamond(g, right - d + 1 - i * (d + gap), top, d);
        } finally { g.dispose(); }
    }

    /** Filled pixel diamond in exactly {@code d} square pixels; even sizes have two central rows, odd sizes one. */
    private static void diamond(Graphics2D g, int x, int y, int d) {
        for (int row = 0; row < d; row++) {
            int inset = Math.abs(2 * row - (d - 1)) / 2;
            g.fillRect(x + inset, y + row, d - 2 * inset, 1);
        }
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
