package tomato.gui.kit;

import java.awt.*;
import java.util.Locale;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.modern.ContentStyle;

/** Label, metric and optional sub-line on a raised surface. The kit's one KPI tile (it replaced the old StatsUi metric labels). */
public class StatTile extends JPanel {
    private final String label;
    private final JLabel name = new JLabel(), value = new JLabel(), sub = new JLabel();
    private final JPanel trend = new JPanel(new BorderLayout());
    private DisplayValue current = DisplayValue.unknown("");

    public StatTile(String label) {
        this.label = label;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setBorder(new EmptyBorder(Tokens.S + 2, Tokens.M, Tokens.S + 2, Tokens.M));
        setName("tile-" + label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", ""));
        for (JLabel text : new JLabel[]{name, value, sub}) text.putClientProperty("html.disable", true);
        ContentStyle.font(name, Type.caption());
        ContentStyle.font(value, Type.metric());
        ContentStyle.font(sub, Type.caption());
        trend.setOpaque(false);
        for (JComponent part : new JComponent[]{name, value, sub, trend}) { part.setAlignmentX(LEFT_ALIGNMENT); add(part); }
        name.setText(label);
        setValue(current, null);
    }

    public void setValue(DisplayValue shown, String subline) {
        current = shown;
        value.setText(shown.display());
        value.setToolTipText(shown.tooltip());
        boolean hasSub = subline != null && !subline.isEmpty();
        sub.setText(hasSub ? subline : "");
        sub.setVisible(hasSub);
        getAccessibleContext().setAccessibleName(label + ": " + shown.display() + (hasSub ? ", " + subline : ""));
        refreshColors();
    }

    public DisplayValue value() { return current; }

    /** The value exactly as shown ("—" when unknown, "≈ …" when estimated), read-only: for tests and accessibility checks. */
    public String valueText() { return value.getText(); }

    /** Holds an optional Sparkline (Task 9). */
    public JPanel trendSlot() { return trend; }

    @Override public void updateUI() { super.updateUI(); if (sub != null && current != null) refreshColors(); }

    private void refreshColors() {
        name.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
        sub.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
        value.setForeground(Tokens.color(current.dimmed() ? Tokens.Role.TEXT_MUTED : Tokens.Role.TEXT));
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Tokens.color(Tokens.Role.RAISED));
        g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tokens.ARC_CARD, Tokens.ARC_CARD);
        g.dispose();
    }
}
