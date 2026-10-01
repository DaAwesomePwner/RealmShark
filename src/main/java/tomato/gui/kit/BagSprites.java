package tomato.gui.kit;

import java.awt.*;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.Icon;
import tomato.realmshark.enums.LootBags;

/**
 * Loot bags as the game draws them: a bag's sprite by its saved name ("White", "B.White", "Egg Basket"), and the value order bags
 * are sorted by everywhere. A bag without a sprite (unknown name, assets not extracted yet) paints a well in its bag color.
 */
public final class BagSprites {
    /** Base bag names, most valuable first; a boosted bag ranks immediately above its base. */
    private static final List<String> ORDER = List.of("white", "red", "orange", "blue", "teal", "gold", "egg basket", "purple", "pink", "soulbound", "brown");

    private BagSprites() {}

    /** The game object ID of a saved bag name (case and surrounding spaces ignored); 0 when unknown. */
    public static int objectId(String bagName) {
        if (bagName == null) return 0;
        String key = bagName.trim();
        for (Map.Entry<Integer, String> bag : LootBags.lootBagName.entrySet()) if (bag.getValue().equalsIgnoreCase(key)) return bag.getKey();
        return 0;
    }

    /** Higher is more valuable: White highest, Brown lowest of the known bags, 0 for an unknown or missing name. */
    public static int rank(String bagName) {
        if (bagName == null) return 0;
        String key = bagName.trim().toLowerCase(Locale.ROOT);
        boolean boosted = key.startsWith("b.");
        if (boosted) key = key.substring(2).trim();
        if (key.equals("egg")) key = "egg basket";
        int index = ORDER.indexOf(key);
        return index < 0 ? 0 : (ORDER.size() - index) * 2 + (boosted ? 1 : 0);
    }

    /** The bag's game sprite at {@code size}, or a tinted well while none is available; resolves live after asset reloads. */
    public static Icon sprite(String bagName, int size) { return new BagIcon(bagName, size); }

    /** True when {@code icon} is a bag icon that currently paints the tinted well (no game sprite). */
    public static boolean isFallback(Icon icon) { return icon instanceof BagIcon bag && Sprites.isPlaceholder(bag.live); }

    private static final class BagIcon implements Icon {
        private final String bag;
        private final int size;
        private final Icon live;

        BagIcon(String bag, int size) {
            this.bag = bag;
            this.size = size;
            live = Sprites.sprite(objectId(bag), size);
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
            if (!Sprites.isPlaceholder(live)) { live.paintIcon(c, graphics, x, y); return; }
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color color = Tokens.bag(bag);
                g.setColor(Tokens.tint(color));
                g.fillRoundRect(x, y, size - 1, size - 1, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
                g.setColor(color);
                g.drawRoundRect(x, y, size - 1, size - 1, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
            } finally {
                g.dispose();
            }
        }
    }
}
