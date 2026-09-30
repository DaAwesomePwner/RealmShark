package tomato.gui.kit;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.Objects;
import javax.swing.Icon;
import tomato.realmshark.EnchantInfo;

/**
 * An item sprite for table cells: the base icon, the rarity gem over its top-right corner, and the shared enchant tooltip, which
 * tables built by the stats pages ask the cell value for on hover. Colors resolve while painting and on hover, so it follows the
 * theme. The base is kept as given (callers may cache and reuse it).
 */
public final class ItemIcon implements Icon {
    private final Icon base;
    private final String heading;
    private final EnchantInfo enchant;

    public ItemIcon(Icon base, String heading, EnchantInfo enchant) {
        this.base = Objects.requireNonNull(base, "base");
        this.heading = heading;
        this.enchant = enchant == null ? EnchantInfo.notRecorded() : enchant;
    }

    public Icon base() { return base; }
    public EnchantInfo enchant() { return enchant; }

    /** The shared enchant tooltip, built now (call it on hover); null when there is no enchant data (e.g. a potion). */
    public String tooltip() { return enchant.state() == EnchantInfo.State.NOT_RECORDED ? null : EnchantTooltip.html(heading, enchant); }

    @Override public void paintIcon(Component c, Graphics g, int x, int y) {
        base.paintIcon(c, g, x, y);
        EnchantGem.paint((Graphics2D) g, enchant, x, y, Math.min(getIconWidth(), getIconHeight()) - 1);
    }

    @Override public int getIconWidth() { return base.getIconWidth(); }
    @Override public int getIconHeight() { return base.getIconHeight(); }
}
