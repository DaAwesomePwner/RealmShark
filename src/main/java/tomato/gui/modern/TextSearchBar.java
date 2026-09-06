package tomato.gui.modern;

import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;

/** Searches the active channel without removing messages or changing saved logs. */
public final class TextSearchBar extends JPanel {
    private final JTextField query = new JTextField();
    private final JLabel count = new JLabel("Find in channel");
    private final Supplier<JTextArea> active;
    private final List<int[]> matches = new ArrayList<>();
    private final Timer debounce;
    private JTextArea highlightedArea;
    private Object highlight;
    private int index = -1;

    public TextSearchBar(Supplier<JTextArea> active, JTextArea... areas) {
        super(new BorderLayout(12, 0)); this.active = active;
        setBorder(BorderFactory.createEmptyBorder(0, 0, 14, 0));
        query.putClientProperty("JTextField.placeholderText", "Find a player or message...");
        query.getAccessibleContext().setAccessibleName("Find in active chat channel");
        query.setToolTipText("Find in this channel (Ctrl+F). Enter: next match; Shift+Enter: previous.");
        add(query, BorderLayout.CENTER);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        count.setFont(count.getFont().deriveFont(12f)); controls.add(count);
        JButton previous = new JButton("Previous"), next = new JButton("Next");
        previous.addActionListener(e -> move(-1)); next.addActionListener(e -> move(1));
        controls.add(previous); controls.add(next); add(controls, BorderLayout.EAST);
        debounce = new Timer(160, e -> refresh()); debounce.setRepeats(false);
        DocumentListener listener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { queueRefresh(); }
            public void removeUpdate(DocumentEvent e) { queueRefresh(); }
            public void changedUpdate(DocumentEvent e) { queueRefresh(); }
        };
        query.getDocument().addDocumentListener(listener);
        for (JTextArea area : areas) area.getDocument().addDocumentListener(listener);
        query.addActionListener(e -> move(1));
        bind(query, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "previous", () -> move(-1));
        bind(query, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clear", () -> query.setText(""));
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "focus");
        getActionMap().put("focus", new AbstractAction() { public void actionPerformed(ActionEvent e) { focusSearch(); }});
    }
    public void focusSearch() { query.requestFocusInWindow(); query.selectAll(); }
    private static void bind(JComponent c, KeyStroke key, String name, Runnable action) {
        c.getInputMap().put(key, name); c.getActionMap().put(name, new AbstractAction() { public void actionPerformed(ActionEvent e) { action.run(); }});
    }
    private void queueRefresh() { if (SwingUtilities.isEventDispatchThread()) debounce.restart(); else SwingUtilities.invokeLater(debounce::restart); }
    public void refresh() {
        clearHighlight(); matches.clear(); index = -1;
        if (!query.getText().isEmpty()) {
            Matcher matcher = Pattern.compile(Pattern.quote(query.getText()), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(active.get().getText());
            while (matcher.find()) matches.add(new int[] {matcher.start(), matcher.end()});
        }
        count.setText(query.getText().isEmpty() ? "Find in channel" : matches.size() + " matches");
    }
    private void move(int direction) {
        // Document changes may still be waiting for the debounce timer.
        if (debounce.isRunning()) { debounce.stop(); refresh(); }
        if (matches.isEmpty()) return;
        index = Math.floorMod(index < 0 ? (direction > 0 ? 0 : matches.size() - 1) : index + direction, matches.size());
        clearHighlight(); highlightedArea = active.get(); int[] match = matches.get(index);
        try {
            highlight = highlightedArea.getHighlighter().addHighlight(match[0], match[1], new DefaultHighlighter.DefaultHighlightPainter(new Color(0x7452A8)));
            highlightedArea.setCaretPosition(match[0]);
            Rectangle position = highlightedArea.modelToView(match[0]);
            if (position != null) highlightedArea.scrollRectToVisible(position);
        } catch (BadLocationException ignored) { refresh(); return; }
        count.setText((index + 1) + " / " + matches.size());
    }
    private void clearHighlight() {
        if (highlight != null && highlightedArea != null) highlightedArea.getHighlighter().removeHighlight(highlight);
        highlight = null;
    }
}
