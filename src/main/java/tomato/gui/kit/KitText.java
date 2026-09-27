package tomato.gui.kit;

import java.awt.Font;
import java.util.Objects;
import javax.swing.JLabel;
import tomato.gui.modern.ContentStyle;

/** A label in one kit text role and font. HTML is off, because names and maps come from capture. */
public final class KitText extends JLabel {
    private Tokens.Role role;

    public KitText(String text, Font font, Tokens.Role role) {
        super(text);
        this.role = Objects.requireNonNull(role, "role");
        putClientProperty("html.disable", Boolean.TRUE);
        ContentStyle.font(this, font);
        setForeground(Tokens.color(role));
    }

    public static KitText caption(String text) { return new KitText(text, Type.caption(), Tokens.Role.TEXT_MUTED); }
    public static KitText body(String text) { return new KitText(text, Type.body(), Tokens.Role.TEXT); }
    public static KitText emphasis(String text) { return new KitText(text, Type.emphasis(), Tokens.Role.TEXT); }

    public Tokens.Role role() { return role; }

    /** Switches the text role; the color follows the theme from then on. */
    public void role(Tokens.Role value) {
        if (value == null || role == value) return;
        role = value;
        setForeground(Tokens.color(value));
    }

    @Override public void updateUI() {
        super.updateUI();
        if (role != null) setForeground(Tokens.color(role)); // null while JLabel's constructor runs
    }
}
