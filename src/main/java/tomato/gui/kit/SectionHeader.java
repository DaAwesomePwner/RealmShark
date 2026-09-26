package tomato.gui.kit;

import java.awt.*;
import javax.swing.*;
import tomato.gui.modern.ContentStyle;

/** Title, optional count and right-aligned actions. */
public class SectionHeader extends JPanel {
    private final JLabel title = new JLabel(), count = new JLabel();
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, Tokens.XS, 0));

    public SectionHeader(String text) {
        super(new BorderLayout(Tokens.S, 0));
        setOpaque(false);
        actions.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.S, 0));
        left.setOpaque(false);
        title.putClientProperty("html.disable", true);
        count.putClientProperty("html.disable", true);
        ContentStyle.font(title, Type.title());
        ContentStyle.font(count, Type.caption());
        left.add(title);
        left.add(count);
        add(left, BorderLayout.CENTER);
        add(actions, BorderLayout.EAST);
        setTitle(text);
        setCount(null);
        refreshColors();
    }

    public void setTitle(String text) { title.setText(text); title.setVisible(text != null && !text.isEmpty()); }
    public String title() { return title.getText(); }
    public void setCount(String text) { count.setText(text); count.setVisible(text != null && !text.isEmpty()); }
    public JPanel actions() { return actions; }

    @Override public void updateUI() { super.updateUI(); if (count != null) refreshColors(); }

    private void refreshColors() {
        title.setForeground(Tokens.color(Tokens.Role.TEXT));
        count.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
    }
}
