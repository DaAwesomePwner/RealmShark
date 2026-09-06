package ui;

import org.junit.Test;
import tomato.gui.modern.TextSearchBar;
import javax.swing.*;
import javax.swing.text.Highlighter;
import java.awt.*;
import static org.junit.Assert.*;

public class TextSearchBarTest {
    @Test public void searchWrapsAndSwitchesChannelsWithoutEditingMessages() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea all = new JTextArea("Alpha [tag] alpha [TAG]"), guild = new JTextArea("Guild [tag]");
            JTextArea[] active = {all};
            TextSearchBar search = new TextSearchBar(() -> active[0], all, guild);
            JTextField input = (JTextField) search.getComponent(0);
            JButton next = button(search, "Next"), previous = button(search, "Previous");
            input.setText("[tag]"); search.refresh(); next.doClick();
            assertEquals(6, all.getHighlighter().getHighlights()[0].getStartOffset());
            next.doClick(); assertEquals(18, all.getHighlighter().getHighlights()[0].getStartOffset());
            next.doClick(); assertEquals(6, all.getHighlighter().getHighlights()[0].getStartOffset());
            previous.doClick(); assertEquals(18, all.getHighlighter().getHighlights()[0].getStartOffset());
            active[0] = guild; search.refresh();
            assertEquals(0, all.getHighlighter().getHighlights().length);
            next.doClick(); assertEquals(6, guild.getHighlighter().getHighlights()[0].getStartOffset());
            assertEquals("Alpha [tag] alpha [TAG]", all.getText());
            assertEquals("Guild [tag]", guild.getText());
            input.setText("missing"); search.refresh(); next.doClick();
            assertEquals(0, guild.getHighlighter().getHighlights().length);
            input.setText(""); search.refresh();
        });
    }
    private static JButton button(Container c, String text) {
        for (Component child : c.getComponents()) {
            if (child instanceof JButton && text.equals(((JButton)child).getText())) return (JButton)child;
            if (child instanceof Container) { JButton found = button((Container)child, text); if (found != null) return found; }
        }
        return null;
    }
}
