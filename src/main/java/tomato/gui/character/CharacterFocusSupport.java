package tomato.gui.character;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import tomato.gui.modern.ContentStyle;

/** Reveals keyboard targets through the character page's nested scrolling at large text sizes. */
final class CharacterFocusSupport {
    private CharacterFocusSupport() { }
    static void install(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JComponent && child.isFocusable()) {
                JComponent control = (JComponent)child;
                control.addFocusListener(new FocusAdapter() {
                    @Override public void focusGained(FocusEvent event) {
                        Rectangle region = new Rectangle(0, 0, control.getWidth(), control.getHeight());
                        if (control instanceof JTable) {
                            JTable table = (JTable)control;
                            region = table.getCellRect(Math.max(0, table.getSelectedRow()), Math.max(0, table.getSelectedColumn()), true);
                        }
                        ContentStyle.reveal(control, region);
                    }
                });
            }
            if (child instanceof Container) install((Container)child);
        }
    }
}
