package tomato.gui.kit;

import java.awt.BorderLayout;
import javax.swing.*;
import tomato.gui.modern.ContentStyle;

/** Full provenance text, muted and wrapping. Hidden until a card or mode shows it. */
public class EvidenceNote extends JPanel {
    private final JTextArea text;

    public EvidenceNote(String value) {
        super(new BorderLayout());
        setOpaque(false);
        text = ContentStyle.wrappingText(value);
        text.setName("evidence-note");
        text.getAccessibleContext().setAccessibleName("Evidence");
        add(text);
        setVisible(false);
        refreshColors();
    }

    public void setText(String value) { text.setText(value); }
    public String getText() { return text.getText(); }

    @Override public void updateUI() { super.updateUI(); if (text != null) refreshColors(); }

    private void refreshColors() { text.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED)); }
}
