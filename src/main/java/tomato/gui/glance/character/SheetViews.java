package tomato.gui.glance.character;

import java.awt.*;
import java.util.List;
import javax.swing.*;
import tomato.backend.data.FieldCapture;
import tomato.gui.kit.Card;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

/** Small shared pieces of the character sheet tabs. EDT only, except {@link #fieldEvidence}, a pure string helper also called from SheetModelBuilder on its build thread. */
final class SheetViews {
    /** Canonical stat order: life, mana, atk, def, spd, dex, vit, wis. */
    static final String[] STATS = {"Life", "Mana", "ATT", "DEF", "SPD", "DEX", "VIT", "WIS"};
    static final String[] SLOTS = {"Weapon", "Ability", "Armor", "Ring"};

    private SheetViews() {}

    static <T extends Component> T named(T component, String name) { component.setName(name); return component; }

    static JPanel clear(LayoutManager layout, Component... children) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        for (Component child : children) panel.add(child);
        return panel;
    }

    /** A transparent row that wraps at its width (ContentStyle.controls), so nothing is cut at 680 px or font 18. */
    static JPanel row(Component... children) {
        JPanel panel = ContentStyle.controls();
        panel.setOpaque(false);
        for (Component child : children) panel.add(child);
        return panel;
    }

    static JPanel beside(Component center, Component side, String edge, int gap) {
        JPanel panel = clear(new BorderLayout(gap, gap));
        panel.add(center, BorderLayout.CENTER);
        panel.add(side, edge);
        return panel;
    }

    static Card card(DisplayModeModel mode, String title, JComponent body, String name) { return named(new Card(mode).title(title).body(body), name); }

    /** The value at {@code index}, or null when it is unknown (-1) or missing. */
    static Integer at(List<Integer> values, int index) {
        return values == null || index >= values.size() || values.get(index) < 0 ? null : values.get(index);
    }

    /**
     * "source · when" field evidence text, with a note when it was retained from an earlier observation; {@code at} 0 means
     * unknown timing (legacy data, {@link FieldCapture#at}), never shown as an epoch date. Shared by SheetModelBuilder's stat
     * evidence and CharacterSheet's Snapshot evidence table so the two never drift apart.
     */
    static String fieldEvidence(String source, long at, long lastSeen) {
        return source + " · " + (at > 0 ? DisplayFormat.formatTimestamp(at) : "Unknown")
            + (at > 0 && at < lastSeen ? " · Retained from earlier observation" : "");
    }

    /** A tab body that scrolls vertically, follows the viewport width (never sideways) and keeps its content at the top. */
    static JScrollPane scroll(JComponent body) {
        JScrollPane scroll = new JScrollPane(new Page(body));
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        return scroll;
    }

    private static final class Page extends JPanel implements Scrollable {
        Page(JComponent body) {
            super(new BorderLayout());
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(Tokens.S, 0, Tokens.S, 0));
            add(body, BorderLayout.NORTH);
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return getParent() instanceof JViewport && getParent().getHeight() > getPreferredSize().height; }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(24, visible.height - 24); }
    }
}
