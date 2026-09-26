package tomato.gui.kit;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.LineIcon;

/** A tinted status pill. Use removable(...) for active filters. */
public class Chip extends JLabel {
    private Tokens.Tone tone;

    public Chip(String text, Tokens.Tone tone) {
        super(text);
        this.tone = tone;
        putClientProperty("html.disable", true);
        setBorder(new EmptyBorder(1, 7, 1, 7));
        ContentStyle.font(this, Type.caption());
        refreshColors();
    }

    public Tokens.Tone tone() { return tone; }
    public void setTone(Tokens.Tone value) { tone = value; refreshColors(); repaint(); }

    @Override public void updateUI() { super.updateUI(); if (tone != null) refreshColors(); }

    private void refreshColors() { setForeground(Tokens.tone(tone)); }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Tokens.tint(Tokens.tone(tone)));
        g.fillRoundRect(0, 0, getWidth(), getHeight(), Tokens.ARC_CHIP, Tokens.ARC_CHIP);
        g.dispose();
        super.paintComponent(graphics);
    }

    /** An accent chip with a keyboard-reachable remove button. */
    public static JComponent removable(String text, Runnable remove) {
        JPanel chip = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0)) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Tokens.tint(Tokens.tone(Tokens.Tone.ACCENT)));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), Tokens.ARC_CHIP, Tokens.ARC_CHIP);
                g.dispose();
            }
        };
        chip.setOpaque(false);
        chip.setBorder(new EmptyBorder(0, 6, 0, 1));
        JLabel label = new JLabel(text) {
            @Override public void updateUI() { super.updateUI(); setForeground(Tokens.tone(Tokens.Tone.ACCENT)); }
        };
        label.putClientProperty("html.disable", true);
        ContentStyle.font(label, Type.caption());
        KitButton close = KitButton.icon(new LineIcon(LineIcon.CLOSE, 12), "Remove filter: " + text);
        close.setName("remove-filter");
        close.addActionListener(e -> remove.run());
        chip.add(label);
        chip.add(close);
        chip.getAccessibleContext().setAccessibleName("Filter: " + text);
        return chip;
    }
}
