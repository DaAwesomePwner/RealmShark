package tomato.gui.kit;

import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.ToolTipManager;
import tomato.realmshark.EnchantInfo;

/**
 * An item icon that is not an {@link ItemSlot} (DPS icon view, My Info): the rarity gem on the icon, an accessible name ending
 * with the enchant summary, and the shared enchant tooltip built when it is asked for, so its colors follow the theme.
 */
public class EnchantIconLabel extends JLabel {
    private String heading;
    private EnchantInfo enchant;

    public EnchantIconLabel() { ToolTipManager.sharedInstance().registerComponent(this); }

    /** Shows {@code icon} (null shows none) for the item named {@code heading}; a null {@code enchant} means no enchant data. */
    public void setItem(Icon icon, String heading, EnchantInfo enchant) {
        this.heading = heading;
        this.enchant = enchant;
        setIcon(EnchantGem.decorate(icon, enchant));
        String name = heading == null ? "" : heading;
        getAccessibleContext().setAccessibleName(enchant == null ? name : name.isEmpty() ? enchant.summary() : name + " · " + enchant.summary());
    }

    /** No item: no icon, no tooltip, no accessible name. */
    public void clear() { setItem(null, null, null); }

    public EnchantInfo enchant() { return enchant; }

    @Override public String getToolTipText() { return enchant == null ? heading : EnchantTooltip.html(heading, enchant); }
}
