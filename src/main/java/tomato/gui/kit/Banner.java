package tomato.gui.kit;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.modern.ContentStyle;

/**
 * One line of status text that wraps instead of clipping. NEUTRAL is plain muted text (loading); any other tone draws a
 * tinted row with a stripe in that tone and reads in the body color: WARN for errors, stale or incomplete data (spec §7:
 * errors render as a warn banner inside the card), INFO for notes.
 */
public final class Banner extends JPanel {
    private final JTextArea text;
    private Tokens.Tone tone = Tokens.Tone.NEUTRAL;

    public Banner(String name) {
        super(new BorderLayout());
        setOpaque(false);
        setName(name);
        text = ContentStyle.wrappingText("");
        text.setName(name + "-text");
        add(text);
        refreshColors();
    }

    public void setText(String value) {
        String next = value == null ? "" : value;
        if (!next.equals(text.getText())) text.setText(next);
        getAccessibleContext().setAccessibleName(next);
    }

    public String text() { return text.getText(); }

    public Tokens.Tone tone() { return tone; }

    public void setTone(Tokens.Tone value) {
        Tokens.Tone next = value == null ? Tokens.Tone.NEUTRAL : value;
        if (tone == next) return;
        tone = next;
        setBorder(next == Tokens.Tone.NEUTRAL ? null : new EmptyBorder(Tokens.XS, Tokens.S + 3, Tokens.XS, Tokens.S));
        refreshColors();
        revalidate();
        repaint();
    }

    /** True for a warn banner. */
    public boolean warns() { return tone == Tokens.Tone.WARN; }

    @Override public void updateUI() { super.updateUI(); if (text != null) refreshColors(); }   // text is null while JPanel's constructor runs

    private void refreshColors() { text.setForeground(Tokens.color(tone == Tokens.Tone.NEUTRAL ? Tokens.Role.TEXT_MUTED : Tokens.Role.TEXT)); }

    @Override protected void paintComponent(Graphics graphics) {
        if (tone == Tokens.Tone.NEUTRAL) return;
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color ink = Tokens.tone(tone);
        g.setColor(Tokens.tint(ink));
        g.fillRoundRect(0, 0, getWidth(), getHeight(), Tokens.ARC_CONTROL, Tokens.ARC_CONTROL);
        g.setColor(ink); // the stripe keeps the tone visible without relying on the tint alone
        g.fillRect(0, 2, 3, Math.max(0, getHeight() - 4));
        g.dispose();
    }
}
