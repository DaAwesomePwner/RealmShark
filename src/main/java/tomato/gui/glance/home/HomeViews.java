package tomato.gui.glance.home;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.kit.Tokens;
import tomato.gui.kit.Type;
import tomato.gui.modern.ContentStyle;
import tomato.realmshark.ParseDungeon;
import tomato.realmshark.enums.CharacterStatistics;

/** Small shared pieces of the Home cards. EDT only; every lookup here is an in-memory map read, never I/O. */
final class HomeViews {
    /** Loading is static text: the spec allows no animated loaders (§5.8). */
    static final String LOADING = "Loading…";
    /** Home's desktop layout starts at this root-pane width, where the shell leaves compact mode (spec §6.1). */
    static final int WIDE = 1000;
    private static final Pattern TIER_LABEL = Pattern.compile("T\\d{1,2}");

    private HomeViews() {}

    /** True when the window's root pane (or, outside a window, the component) is at least WIDE pixels wide. */
    static boolean wide(Component component) {
        JRootPane root = SwingUtilities.getRootPane(component);
        return (root != null && root.getWidth() > 0 ? root.getWidth() : component.getWidth()) >= WIDE;
    }

    /** A label in one kit text role; html is off because names and maps come from capture. */
    static final class Text extends JLabel {
        private Tokens.Role role;

        Text(String text, Font font, Tokens.Role role) {
            super(text);
            this.role = role;
            putClientProperty("html.disable", true);
            ContentStyle.font(this, font);
            setForeground(Tokens.color(role));
        }

        void role(Tokens.Role value) {
            if (role == value) return;
            role = value;
            setForeground(Tokens.color(value));
        }

        @Override public void updateUI() {
            super.updateUI();
            if (role != null) setForeground(Tokens.color(role)); // null while JLabel's constructor runs
        }
    }

    static Text caption(String text) { return new Text(text, Type.caption(), Tokens.Role.TEXT_MUTED); }
    static Text body(String text) { return new Text(text, Type.body(), Tokens.Role.TEXT); }
    static Text emphasis(String text) { return new Text(text, Type.emphasis(), Tokens.Role.TEXT); }

    /**
     * A one-line text that wraps instead of clipping at 680 px: the muted loading text, or (warn) an error or stale-read
     * reason drawn as a WARN-tinted banner row (spec §7: errors render as a warn banner inside the card).
     */
    static final class Reason extends JPanel {
        private final JTextArea text;
        private boolean warn;

        Reason(String name) {
            super(new BorderLayout());
            setOpaque(false);
            setName(name);
            text = ContentStyle.wrappingText("");
            text.setName(name + "-text");
            add(text);
            refreshColors();
        }

        void setText(String value) { setText(value, false); }

        void setText(String value, boolean warning) {
            if (!value.equals(text.getText())) text.setText(value);
            if (warn != warning) {
                warn = warning;
                setBorder(warning ? new EmptyBorder(Tokens.XS, Tokens.S + 3, Tokens.XS, Tokens.S) : null);
                refreshColors();
                revalidate();
                repaint();
            }
            getAccessibleContext().setAccessibleName(value);
        }

        String text() { return text.getText(); }

        boolean warns() { return warn; }

        @Override public void updateUI() { super.updateUI(); if (text != null) refreshColors(); }

        private void refreshColors() { text.setForeground(Tokens.color(warn ? Tokens.Role.TEXT : Tokens.Role.TEXT_MUTED)); }

        @Override protected void paintComponent(Graphics graphics) {
            if (!warn) return;
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color ink = Tokens.tone(Tokens.Tone.WARN);
            g.setColor(Tokens.tint(ink));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
            g.setColor(ink); // the stripe keeps the warning visible without relying on the tint alone
            g.fillRect(0, 2, 3, Math.max(0, getHeight() - 4));
            g.dispose();
        }
    }

    static <T extends Component> T named(T component, String name) {
        component.setName(name);
        return component;
    }

    /** A transparent panel holding the children in order. */
    static JPanel clear(LayoutManager layout, Component... children) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        for (Component child : children) panel.add(child);
        return panel;
    }

    /** A transparent row that wraps to its width (ContentStyle.controls), so nothing is cut at 680 px or font 18. */
    static JPanel wrap(Component... children) {
        JPanel panel = ContentStyle.controls();
        panel.setOpaque(false);
        for (Component child : children) panel.add(child);
        return panel;
    }

    /** `center` with `side` at a BorderLayout `edge`, `gap` apart. */
    static JPanel beside(Component center, Component side, String edge, int gap) {
        JPanel panel = clear(new BorderLayout(gap, gap));
        panel.add(center, BorderLayout.CENTER);
        panel.add(side, edge);
        return panel;
    }

    /** Rows top to bottom at full width; hidden rows take no space, and wrapping rows grow with their width. */
    static JPanel stack(int gap, JComponent... rows) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        for (int i = 0; i < rows.length; i++) {
            c.gridy = i;
            c.insets = new Insets(i == 0 ? 0 : gap, 0, 0, 0);
            panel.add(rows[i], c);
        }
        // Rows stay at the top when the grid stretches this card to its row partner's height.
        c.gridy = rows.length;
        c.weighty = 1;
        c.insets = new Insets(0, 0, 0, 0);
        panel.add(Box.createVerticalGlue(), c);
        return panel;
    }

    /**
     * One row: `lead` stretches at the left and `trailing` sit at the right edge, all vertically centered, when everything
     * fits (and, with {@code wideOnly}, the page is {@link #wide}). Otherwise `lead` takes its own line and `trailing` flow
     * below it from the left, wrapping at the width. Hidden parts take no space. Rows re-measure when their width changes.
     */
    static JPanel spread(boolean wideOnly, int gap, Component lead, Component... trailing) {
        JPanel panel = new JPanel() {
            @Override public void setBounds(int x, int y, int width, int height) {
                boolean changed = width != getWidth();
                super.setBounds(x, y, width, height);
                if (changed) SwingUtilities.invokeLater(this::revalidate); // the row count depends on this width
            }
        };
        panel.setOpaque(false);
        panel.setLayout(new Spread(wideOnly, gap));
        panel.add(lead);
        for (Component part : trailing) panel.add(part);
        return panel;
    }

    private static final class Spread implements LayoutManager {
        private final boolean wideOnly;
        private final int gap;

        Spread(boolean wideOnly, int gap) { this.wideOnly = wideOnly; this.gap = gap; }

        @Override public void addLayoutComponent(String name, Component component) {}
        @Override public void removeLayoutComponent(Component component) {}
        @Override public Dimension preferredLayoutSize(Container target) { return size(target); }
        /** Height as preferred and no width floor, so enclosing GridBag stacks never switch to minimum sizes. */
        @Override public Dimension minimumLayoutSize(Container target) { return new Dimension(0, size(target).height); }
        @Override public void layoutContainer(Container target) { place(target, true); }

        private Dimension size(Container target) {
            synchronized (target.getTreeLock()) {
                Insets insets = target.getInsets();
                int available = available(target), height = place(target, false);
                return new Dimension(available > 0 ? available : natural(target) + insets.left + insets.right, height);
            }
        }

        /** The one-row width of the visible parts. */
        private int natural(Container target) {
            int width = 0;
            for (Component part : target.getComponents()) if (part.isVisible()) width += (width == 0 ? 0 : gap) + part.getPreferredSize().width;
            return width;
        }

        private int place(Container target, boolean apply) {
            Insets insets = target.getInsets();
            int width = Math.max(1, available(target) - insets.left - insets.right), left = insets.left, y = insets.top;
            Component[] parts = target.getComponents();
            Component lead = parts[0];
            List<Component> rest = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) if (parts[i].isVisible()) rest.add(parts[i]);
            int restWidth = 0, restHeight = 0;
            for (Component part : rest) {
                Dimension size = part.getPreferredSize();
                restWidth += (restWidth == 0 ? 0 : gap) + size.width;
                restHeight = Math.max(restHeight, size.height);
            }
            Dimension leadSize = lead.getPreferredSize();
            if (lead.isVisible() && !rest.isEmpty() && (!wideOnly || wide(target)) && leadSize.width + gap + restWidth <= width) {
                int height = Math.max(leadSize.height, restHeight);
                if (apply) {
                    lead.setBounds(left, y + (height - leadSize.height) / 2, width - restWidth - gap, leadSize.height);
                    int x = left + width - restWidth;
                    for (Component part : rest) {
                        Dimension size = part.getPreferredSize();
                        part.setBounds(x, y + (height - size.height) / 2, size.width, size.height);
                        x += size.width + gap;
                    }
                }
                return y + height + insets.bottom;
            }
            if (lead.isVisible()) {
                if (apply) lead.setBounds(left, y, width, leadSize.height);
                y += leadSize.height + (rest.isEmpty() ? 0 : gap);
            }
            List<Component> row = new ArrayList<>();
            int rowWidth = 0;
            for (Component part : rest) {
                int partWidth = part.getPreferredSize().width;
                if (!row.isEmpty() && rowWidth + gap + partWidth > width) { y = row(row, left, y, apply) + gap; row.clear(); rowWidth = 0; }
                rowWidth += (row.isEmpty() ? 0 : gap) + partWidth;
                row.add(part);
            }
            if (!row.isEmpty()) y = row(row, left, y, apply);
            return y + insets.bottom;
        }

        /** One wrapped row of trailing parts from the left, vertically centered; returns its bottom. */
        private int row(List<Component> row, int left, int y, boolean apply) {
            int height = 0;
            for (Component part : row) height = Math.max(height, part.getPreferredSize().height);
            int x = left;
            for (Component part : row) {
                Dimension size = part.getPreferredSize();
                if (apply) part.setBounds(x, y + (height - size.height) / 2, size.width, size.height);
                x += size.width + gap;
            }
            return y + height;
        }

        private static int available(Container target) {
            if (target.getWidth() > 0) return target.getWidth();
            Container parent = target.getParent();
            if (parent == null) return 0;
            Insets insets = parent.getInsets();
            return parent.getWidth() - insets.left - insets.right;
        }
    }

    /** Portal sprite for a map: the catalog portal, else the dungeon-statistics sprite, else 0 (the kit's placeholder glyph). */
    static int portalId(String map) {
        if (map == null || map.isEmpty()) return 0;
        int portal = ParseDungeon.getPortalId(map);
        if (portal > 0) return portal;
        CharacterStatistics stat = CharacterStatistics.statByName(map);
        return stat == null ? 0 : stat.getSpriteId();
    }

    /** "UT", "ST" or "T12" from the loaded item definitions; empty while unknown. RosterDefinitions.current() is constant time on the EDT. */
    static String tier(int itemId) {
        RosterDefinitions.Item item = itemId > 0 ? RosterDefinitions.current().item(itemId) : null;
        if (item == null) return "";
        if (item.labels != null) {
            if (item.labels.contains("UT")) return "UT";
            if (item.labels.contains("ST")) return "ST";
            for (String label : item.labels) if (TIER_LABEL.matcher(label).matches()) return label;
        }
        return item.tier == null ? "" : "T" + item.tier;
    }

    /** KitFormat.relative's wording against the page clock (HomeModelBuilder.ago): "just now", "12 min ago", "3 h ago", … */
    static String ago(long at, long now) { return HomeModelBuilder.ago(at, now); }

    /** The symbol and a space when the body font can draw it, else the words alone (like DisplayValue's "≈" fallback). */
    static String glyph(char symbol, String text) { return ContentStyle.body().canDisplay(symbol) ? symbol + " " + text : text; }
}
