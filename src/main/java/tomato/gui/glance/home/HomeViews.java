package tomato.gui.glance.home;

import java.awt.*;
import javax.swing.*;
import tomato.gui.kit.Banner;
import tomato.gui.kit.ItemTiers;
import tomato.gui.kit.KitLayouts;
import tomato.gui.kit.KitText;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;
import tomato.realmshark.ParseDungeon;
import tomato.realmshark.enums.CharacterStatistics;

/** Small shared pieces of the Home cards; the general ones live in the kit (Banner, KitLayouts, KitText, ItemTiers). EDT only. */
final class HomeViews {
    /** Loading is static text: the spec allows no animated loaders (§5.8). */
    static final String LOADING = "Loading…";
    /** Home's desktop layout starts at this root-pane width, where the shell leaves compact mode (spec §6.1). */
    static final int WIDE = 1000;

    private HomeViews() {}

    /** True when the window's root pane (or, outside a window, the component) is at least WIDE pixels wide. */
    static boolean wide(Component component) { return KitLayouts.rootWidth(component) >= WIDE; }

    static KitText caption(String text) { return KitText.caption(text); }
    static KitText body(String text) { return KitText.body(text); }
    static KitText emphasis(String text) { return KitText.emphasis(text); }

    /**
     * The muted loading text, or (warn) an error or stale-read reason, as a kit {@link Banner} (spec §7: errors render as a
     * warn banner inside the card). Home's tests find it by class and name, so it stays a named panel around the banner.
     */
    static final class Reason extends JPanel {
        private final Banner banner;

        Reason(String name) {
            super(new BorderLayout());
            setOpaque(false);
            setName(name);
            banner = new Banner(name + "-banner");
            add(banner);
        }

        void setText(String value) { setText(value, false); }

        void setText(String value, boolean warning) {
            banner.setText(value);
            banner.setTone(warning ? Tokens.Tone.WARN : Tokens.Tone.NEUTRAL);
            getAccessibleContext().setAccessibleName(value);
        }

        String text() { return banner.text(); }

        boolean warns() { return banner.warns(); }
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

    /** Rows top to bottom at full width ({@link KitLayouts#stack}). */
    static JPanel stack(int gap, JComponent... rows) { return KitLayouts.stack(gap, rows); }

    /** One row that wraps below {@code lead} when it does not fit; with {@code wideOnly}, one row only while the page is {@link #wide}. */
    static JPanel spread(boolean wideOnly, int gap, JComponent lead, JComponent... trailing) {
        JComponent[] parts = new JComponent[trailing.length + 1];
        parts[0] = lead;
        System.arraycopy(trailing, 0, parts, 1, trailing.length);
        return KitLayouts.spreadWhenWide(wideOnly ? WIDE : 0, gap, parts);
    }

    /** Portal sprite for a map: the catalog portal, else the dungeon-statistics sprite, else 0 (the kit's placeholder glyph). */
    static int portalId(String map) {
        if (map == null || map.isEmpty()) return 0;
        int portal = ParseDungeon.getPortalId(map);
        if (portal > 0) return portal;
        CharacterStatistics stat = CharacterStatistics.statByName(map);
        return stat == null ? 0 : stat.getSpriteId();
    }

    /** "UT", "ST" or "T12" from the loaded item definitions; empty while unknown ({@link ItemTiers#label(int)}). */
    static String tier(int itemId) { return ItemTiers.label(itemId); }

    /** KitFormat.relative's wording against the page clock (HomeModelBuilder.ago): "just now", "12 min ago", "3 h ago", … */
    static String ago(long at, long now) { return HomeModelBuilder.ago(at, now); }

    /** The symbol and a space when the body font can draw it, else the words alone (like DisplayValue's "≈" fallback). */
    static String glyph(char symbol, String text) { return ContentStyle.body().canDisplay(symbol) ? symbol + " " + text : text; }
}
