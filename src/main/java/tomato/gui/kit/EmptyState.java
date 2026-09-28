package tomato.gui.kit;

import java.awt.*;
import javax.swing.*;
import tomato.gui.modern.ContentStyle;

/** An invitation, not an apology: what belongs here and the next action. */
public class EmptyState extends JPanel {
    private final JTextArea text;

    public EmptyState(String title, String body, KitButton action) {
        super(new GridBagLayout());
        setOpaque(false);
        setName("empty-state");
        JLabel heading = new JLabel(title) {
            @Override public void updateUI() { super.updateUI(); setForeground(Tokens.color(Tokens.Role.TEXT)); }
        };
        heading.putClientProperty("html.disable", true);
        ContentStyle.font(heading, Type.emphasis());
        text = ContentStyle.wrappingText(body);
        text.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.insets = new Insets(0, 0, Tokens.XS, 0);
        add(heading, c);
        c.gridy = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; c.insets = new Insets(0, Tokens.XL, Tokens.S, Tokens.XL);
        add(text, c);
        if (action != null) { c.gridy = 2; c.fill = GridBagConstraints.NONE; c.weightx = 0; c.insets = new Insets(0, 0, 0, 0); add(action, c); }
        getAccessibleContext().setAccessibleName(title);
        getAccessibleContext().setAccessibleDescription(body);
    }

    /** Replaces the body line (the next action can depend on what is known) and the accessible description with it. EDT. */
    public void setBody(String body) {
        if (body.equals(text.getText())) return;
        text.setText(body);
        getAccessibleContext().setAccessibleDescription(body);
        revalidate();
        repaint();
    }

    @Override public void updateUI() {
        super.updateUI();
        if (text != null) text.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED)); // null during JPanel's constructor
    }
}
