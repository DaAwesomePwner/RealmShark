package tomato.gui.runs;

import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.function.LongSupplier;
import javax.accessibility.*;
import javax.swing.*;
import tomato.gui.kit.*;

/** The run picker's short card: portal, day-qualified time/outcome and one readable best drop. */
public final class CompactRunCardRenderer extends JComponent implements ListCellRenderer<RunCardModel>, Accessible {
    static final int LOOT = 32, GAP = 8;
    private final ZoneId zone;
    private final LongSupplier now;
    private RunCardModel card;
    private boolean selected, focused;

    public CompactRunCardRenderer(ZoneId zone, LongSupplier now) {
        this.zone = Objects.requireNonNull(zone);
        this.now = Objects.requireNonNull(now);
        setOpaque(false);
    }

    static String when(RunCardModel card, ZoneId zone, long now) {
        boolean today = Instant.ofEpochMilli(card.entered()).atZone(zone).toLocalDate()
            .equals(Instant.ofEpochMilli(now).atZone(zone).toLocalDate());
        return (today ? "Today " : "") + RunCardRenderer.time(card.entered(), zone, now) + " · " + card.outcome().label();
    }

    static String note(RunCardModel card) {
        return card.lootReason() != null ? card.lootReason() : card.lootCount() == 0 ? RunCardRenderer.NO_LOOT
            : card.loot().isEmpty() ? card.lootSummary() : "";
    }

    static String rest(RunCardModel card) {
        int more = card.lootCount() - 1;
        return more <= 0 ? "" : "+" + more + (more == 1 ? " item" : " items");
    }

    static String accessibleName(RunCardModel card, ZoneId zone, long now) {
        String facts = card.mapName() + "; " + when(card, zone, now);
        if (!note(card).isEmpty() || card.loot().isEmpty()) return facts + "; " + note(card);
        RunCardModel.LootItem best = card.loot().get(0);
        return facts + "; Best drop: " + Sprites.name(best.id()) + "; " + Objects.toString(best.bag(), "Unknown") + " bag"
            + (best.tier() == null ? "" : "; " + best.tier()) + "; " + best.enchant().text()
            + (rest(card).isEmpty() ? "" : "; " + rest(card));
    }

    @Override public Dimension getPreferredSize() {
        int line = getFontMetrics(Type.caption()).getHeight();
        return new Dimension(Math.max(188, getFontMetrics(Type.body()).getHeight() * 10),
            GAP + 2 * Tokens.S + Math.max(24, 2 * line) + 2 * line + Tokens.S + Math.max(LOOT + Sprites.WELL, 3 * line));
    }

    @Override public Component getListCellRendererComponent(JList<? extends RunCardModel> list, RunCardModel value, int index,
                                                            boolean selected, boolean focused) {
        this.card = value;
        this.selected = selected;
        this.focused = focused;
        String name = value == null ? null : accessibleName(value, zone, now.getAsLong());
        getAccessibleContext().setAccessibleName(name);
        setToolTipText(name);
        return this;
    }

    @Override protected void paintComponent(Graphics graphics) {
        if (card == null) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth() - GAP, h = getHeight() - GAP, x = GAP / 2 + Tokens.S, y = GAP / 2 + Tokens.S;
            g.setColor(Tokens.color(selected ? Tokens.Role.ACCENT_WASH : Tokens.Role.RAISED));
            g.fillRoundRect(GAP / 2, GAP / 2, w, h, Tokens.ARC_CARD, Tokens.ARC_CARD);
            g.setColor(Tokens.color(selected || focused ? Tokens.Role.ACCENT : Tokens.Role.BORDER_SUBTLE));
            g.setStroke(new BasicStroke(focused ? 2 : 1));
            g.drawRoundRect(GAP / 2, GAP / 2, w - 1, h - 1, Tokens.ARC_CARD, Tokens.ARC_CARD);
            int width = w - 2 * Tokens.S;
            g.setFont(Type.caption());
            FontMetrics metrics = g.getFontMetrics();
            Sprites.sprite(card.portalId(), 24).paintIcon(this, g, x, y);
            draw(g, card.mapName(), x + 28, y, width - 28, 2, Tokens.Role.TEXT);
            y += Math.max(24, 2 * metrics.getHeight());
            draw(g, when(card, zone, now.getAsLong()), x, y, width, 2, Tokens.Role.TEXT_MUTED);
            y += 2 * metrics.getHeight() + Tokens.S;
            if (!note(card).isEmpty() || card.loot().isEmpty()) draw(g, note(card), x, y, width, 3, Tokens.Role.TEXT_MUTED);
            else {
                RunCardModel.LootItem best = card.loot().get(0);
                int well = LOOT + Sprites.WELL;
                Sprites.paintWell(this, g, EnchantPips.glow(Sprites.sprite(best.id(), LOOT), best.enchant()), best.bag(), x, y, well);
                EnchantPips.paintCorner(g, best.enchant(), best.tier(), x, y, well - 1);
                if (!rest(card).isEmpty()) draw(g, rest(card), x + well + Tokens.S, y + (well - metrics.getHeight()) / 2,
                    width - well - Tokens.S, 1, Tokens.Role.TEXT);
            }
        } finally { g.dispose(); }
    }

    private static void draw(Graphics2D g, String text, int x, int y, int width, int lines, Tokens.Role role) {
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(Tokens.color(role));
        for (String line : RunCardRenderer.wrap(text, metrics, width, lines)) {
            g.drawString(RunCardRenderer.fit(line, metrics, width), x, y + metrics.getAscent());
            y += metrics.getHeight();
        }
    }

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.LIST_ITEM; }
        };
        return accessibleContext;
    }
}
