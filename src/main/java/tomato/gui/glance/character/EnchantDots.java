package tomato.gui.glance.character;

import java.awt.*;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;

/** Four dots, the first N filled: an equipped item's unlocked enchant slots (its rarity). Hidden while unknown. */
final class EnchantDots extends JComponent {
    /** ParseEnchants.Summary.rarity() names by unlocked slot count. */
    private static final String[] RARITY = {"Common", "Uncommon", "Rare", "Legendary", "Divine"};
    private static final int GAP = 3;
    private int slots = -1;

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.LABEL; }
        };
        return accessibleContext;
    }

    EnchantDots() { setOpaque(false); set(-1); }

    /** 0-4 unlocked enchant slots; -1 hides the dots (not decodable, or not the character in game). */
    void set(int unlocked) {
        slots = unlocked < 0 || unlocked > 4 ? -1 : unlocked;
        setVisible(slots >= 0);
        String text = slots < 0 ? null : RARITY[slots] + " · " + slots + (slots == 1 ? " enchant slot" : " enchant slots");
        setToolTipText(text);
        getAccessibleContext().setAccessibleName(text);
        repaint();
    }

    int slots() { return slots; }

    private int dot() { return Math.max(6, Math.round(ContentStyle.body().getSize2D() * 0.45f)); }

    @Override public Dimension getPreferredSize() { int dot = dot(); return new Dimension(4 * dot + 3 * GAP, dot); }
    @Override public Dimension getMinimumSize() { return getPreferredSize(); }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int dot = dot(), y = (getHeight() - dot) / 2;
        for (int i = 0; i < 4; i++) {
            g.setColor(Tokens.color(i < slots ? Tokens.Role.ACCENT : Tokens.Role.CONTROL));
            g.fillOval(i * (dot + GAP), y, dot, dot);
        }
        g.dispose();
    }
}
