package tomato.gui.kit;

import java.awt.*;
import javax.swing.Icon;
import tomato.realmshark.EnchantInfo;

/**
 * The enchant rarity gem: a small diamond in an item well's top-right corner, inside its edge so the tier border still reads.
 * Recorded rarities paint their {@link Tokens#rarity} color, unreadable data a muted gem, unenchanted and not-recorded nothing.
 * Colors resolve while painting, so gems follow the theme.
 */
public final class EnchantGem {
    private EnchantGem() {}

    /** The gem's width and height for a well {@code side + 1} px square: 7 px on a 20 px sprite's well, 14 px on a 48 px one. */
    static int size(int side) { return Math.max(6, Math.round(side * 0.27f)); }

    /** The gem's color, or null when no gem is shown. */
    static Color ink(EnchantInfo info) {
        if (info == null) return null;
        if (info.state() == EnchantInfo.State.UNREADABLE) return Tokens.color(Tokens.Role.TEXT_MUTED);
        return info.enchanted() ? Tokens.rarity(info.rarity()) : null;
    }

    /** Paints the gem for a well drawn at x, y, {@code side + 1} px square (ItemSlot's geometry); nothing when none is shown. */
    public static void paint(Graphics2D graphics, EnchantInfo info, int x, int y, int side) {
        Color ink = ink(info);
        if (ink == null) return;
        int d = size(side), left = x + side - 2 - d, top = y + 2;
        Polygon diamond = new Polygon(new int[] {left + d / 2, left + d, left + d / 2, left}, new int[] {top, top + d / 2, top + d, top + d / 2}, 4);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(ink);
            g.fillPolygon(diamond);
            // A canvas-colored outline keeps the gem readable over any sprite.
            g.setColor(Tokens.color(Tokens.Role.CANVAS));
            g.setStroke(new BasicStroke(1f));
            g.drawPolygon(diamond);
        } finally {
            g.dispose();
        }
    }

    /** {@code base} with the gem over its top-right corner, sized to its shorter side; {@code base} itself (even null) when no gem is shown. */
    public static Icon decorate(Icon base, EnchantInfo info) {
        if (base == null || ink(info) == null) return base;
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                base.paintIcon(c, g, x, y);
                // Sized to the shorter side and placed at the right edge, so a wide icon's gem is still in its top-right corner.
                int side = Math.min(getIconWidth(), getIconHeight());
                paint((Graphics2D) g, info, x + getIconWidth() - side, y, side - 1);
            }
            @Override public int getIconWidth() { return base.getIconWidth(); }
            @Override public int getIconHeight() { return base.getIconHeight(); }
        };
    }
}
