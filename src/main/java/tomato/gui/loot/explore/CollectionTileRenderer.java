package tomato.gui.loot.explore;

import java.awt.*;
import javax.swing.*;
import tomato.gui.kit.*;
import tomato.gui.loot.haul.HaulView;
import tomato.realmshark.EnchantInfo;

/**
 * Paints one Collection entry: the item's sprite in its slot (tier label, enchant pips and rarity glow of the front drop) with its
 * drop count ("×12") under it; selection and focus as a tinted rounded tile. One component reused for every cell; colors resolve at
 * paint time. The tooltip and accessible name say the same in words ({@link #accessibleName}).
 */
final class CollectionTileRenderer extends JComponent implements ListCellRenderer<CollectionModel.Entry> {
    static final int SPRITE = 40, CELL_WIDTH = 72, PAD = Tokens.XS;
    private CollectionModel.Entry entry;
    private boolean selected, focused;

    CollectionTileRenderer() { setOpaque(false); }

    @Override public Component getListCellRendererComponent(JList<? extends CollectionModel.Entry> list, CollectionModel.Entry value, int index,
                                                            boolean isSelected, boolean cellHasFocus) {
        entry = value;
        selected = isSelected;
        focused = cellHasFocus;
        setToolTipText(value == null ? null : accessibleName(value));
        return this;
    }

    @Override public Dimension getPreferredSize() {
        FontMetrics caption = getFontMetrics(Type.caption());
        return new Dimension(CELL_WIDTH, PAD + SPRITE + Sprites.WELL + Tokens.XS + caption.getHeight() + PAD);
    }

    @Override protected void paintComponent(Graphics graphics) {
        if (entry == null) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (selected) {
                g.setColor(Tokens.color(Tokens.Role.SELECTION));
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
            }
            EnchantInfo enchant = entry.front().enchant().state() == EnchantInfo.State.NOT_RECORDED ? null : entry.front().enchant();
            Icon icon = ItemSlot.icon(Sprites.sprite(entry.itemId(), SPRITE), HaulView.tierLabel(entry.front()), ItemSlot.State.ITEM, SPRITE, enchant);
            icon.paintIcon(this, g, (getWidth() - icon.getIconWidth()) / 2, PAD);
            g.setFont(Type.caption());
            g.setColor(Tokens.color(Tokens.Role.TEXT_MUTED));
            FontMetrics metrics = g.getFontMetrics();
            String count = "×" + entry.count();
            g.drawString(count, (getWidth() - metrics.stringWidth(count)) / 2, PAD + icon.getIconHeight() + Tokens.XS + metrics.getAscent());
            if (focused) {
                g.setColor(Tokens.color(Tokens.Role.ACCENT));
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
            }
        } finally {
            g.dispose();
        }
    }

    /** "Synthetic Seal, 3 drops, best Legendary": the name, the count, and the front drop's rarity when enchanted. */
    static String accessibleName(CollectionModel.Entry entry) {
        EnchantInfo enchant = entry.front().enchant();
        return Sprites.name(entry.itemId()) + ", " + entry.count() + (entry.count() == 1 ? " drop" : " drops")
            + (enchant.enchanted() ? ", best " + enchant.rarity().label : "");
    }
}
