package tomato.gui.loot.explore;

import java.awt.*;
import java.util.Locale;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.*;
import tomato.gui.kit.*;

/** One painted dungeon tile: portal, counts, rates per run with loot and the three most-dropped items. */
final class DungeonTileRenderer extends JComponent implements ListCellRenderer<AtlasModel.Tile>, Accessible {
    private static final int PAD = Tokens.S, PORTAL = 40, DROP = 24;
    private AtlasModel.Tile tile;
    private boolean selected, focused;

    DungeonTileRenderer() { setOpaque(false); }

    @Override public Component getListCellRendererComponent(JList<? extends AtlasModel.Tile> list, AtlasModel.Tile value,
                                                            int index, boolean isSelected, boolean cellHasFocus) {
        tile = value;
        selected = isSelected;
        focused = cellHasFocus;
        setToolTipText(value == null ? null : accessibleName(value)
            + (value.runs() == null ? "; No saved run record for this dungeon; only saved loot knows it." : "")
            + "; Rates count runs with loot, not all saved runs.");
        return this;
    }

    @Override public Dimension getPreferredSize() {
        FontMetrics caption = getFontMetrics(Type.caption());
        int width = Math.max(230, caption.stringWidth("37 runs · 12 white bags · 8 UTs") + PAD * 2);
        width = Math.max(width, caption.stringWidth("0.32 whites/run · 0.22 UTs/run") + PAD * 2);
        return new Dimension(width, PAD * 2 + PORTAL + caption.getHeight() * 2 + Tokens.XS * 2 + DROP);
    }

    @Override protected void paintComponent(Graphics graphics) {
        if (tile == null) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Tokens.color(selected ? Tokens.Role.SELECTION : Tokens.Role.SURFACE));
            g.fillRoundRect(1, 1, getWidth() - 3, getHeight() - 3, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
            Sprites.sprite(tile.portalId(), PORTAL).paintIcon(this, g, PAD, PAD);
            g.setFont(Type.body());
            g.setColor(Tokens.color(Tokens.Role.TEXT));
            String name = tile.dungeon();
            FontMetrics metrics = g.getFontMetrics();
            int available = getWidth() - PORTAL - PAD * 3;
            while (name.length() > 1 && metrics.stringWidth(name) > available) name = name.substring(0, name.length() - 1);
            if (!name.equals(tile.dungeon())) {
                while (name.length() > 1 && metrics.stringWidth(name + "…") > available) name = name.substring(0, name.length() - 1);
                name += "…";
            }
            g.drawString(name, PAD * 2 + PORTAL, PAD + PORTAL / 2 + metrics.getAscent() / 2);
            g.setFont(Type.caption());
            g.setColor(Tokens.color(Tokens.Role.TEXT_MUTED));
            int line = g.getFontMetrics().getHeight(), y = PAD + PORTAL + g.getFontMetrics().getAscent();
            g.drawString(counts(tile), PAD, y);
            g.drawString(rates(tile), PAD, y + line);
            int x = PAD;
            y += line + Tokens.XS;
            for (DungeonStats.Item item : tile.top()) {
                Sprites.sprite(item.id(), DROP).paintIcon(this, g, x, y);
                String count = "×" + item.count();
                g.drawString(count, x + DROP + Tokens.XS, y + DROP / 2 + g.getFontMetrics().getAscent() / 2);
                x += DROP + Tokens.XS + g.getFontMetrics().stringWidth(count) + Tokens.S;
            }
            if (focused) {
                g.setColor(Tokens.color(Tokens.Role.ACCENT));
                g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
            }
        } finally { g.dispose(); }
    }

    private static String counts(AtlasModel.Tile tile) {
        return (tile.runs() == null ? "— runs" : count(tile.runs(), "run", "runs")) + " · "
            + count(tile.whites(), "white bag", "white bags") + " · " + count(tile.uts(), "UT", "UTs");
    }

    private static String rates(AtlasModel.Tile tile) {
        return tile.lootRuns() == 0 ? "No runs with loot yet"
            : String.format(Locale.ROOT, "%.2f whites/run · %.2f UTs/run", tile.whitesPerRun(), tile.utsPerRun());
    }

    static String count(int n, String one, String many) { return n + " " + (n == 1 ? one : many); }

    static String accessibleName(AtlasModel.Tile tile) {
        StringBuilder name = new StringBuilder(tile.dungeon()).append(", ").append(counts(tile)).append(", ")
            .append(rates(tile).replace("whites/run", "white bags per run with loot").replace("UTs/run", "UTs per run with loot"));
        for (DungeonStats.Item item : tile.top()) name.append(", ").append(Sprites.name(item.id())).append(", ").append(count(item.count(), "drop", "drops"));
        return name.toString();
    }

    @Override public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.LABEL; }
            @Override public String getAccessibleName() { return tile == null ? "Dungeon" : accessibleName(tile); }
        };
        return accessibleContext;
    }
}
