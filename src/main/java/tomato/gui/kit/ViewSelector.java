package tomato.gui.kit;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.accessibility.Accessible;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.plaf.basic.ComboPopup;
import tomato.gui.modern.ContentStyle;

/**
 * One choice among a module's views (spec §6.4 Explore): a combo box in the kit's font that lists the Simple views, then an
 * "Analyst" header and the Analyst views, then a "Current view" header and a restored view neither list has, so the view in front is
 * never hidden. Headers are shown, never selected: a click on one changes nothing, the arrow, Page Up/Down and Home/End keys step
 * over them (and stay put at either end), typing matches item titles only, and select() cannot reach one. select() and setItems()
 * are silent; onChange listeners hear the user's choices only. Each item shows its title and its tooltip (the closed selector shows
 * the chosen view's); the accessible name is "View". The combo itself is the component, named by the constructor. EDT only.
 */
public final class ViewSelector<V> {
    /** Navigation actions of the look and feel, by the way they step when they land on a header. */
    private static final String[] FORWARD = {"selectNext", "selectNext2", "pageDownPassThrough", "endPassThrough"},
        BACKWARD = {"selectPrevious", "selectPrevious2", "pageUpPassThrough", "homePassThrough"};

    /** A header row. Its empty toString keeps Swing's own list type-ahead off it too. */
    private static final class Header {
        final String title;
        Header(String title) { this.title = title; }
        @Override public String toString() { return ""; }
    }

    private final Function<V, String> title, tooltip;
    private final Box box = new Box();
    private final List<Consumer<V>> listeners = new ArrayList<>();
    private boolean quiet;

    public ViewSelector(String name, Function<V, String> title, Function<V, String> tooltip) {
        this.title = Objects.requireNonNull(title, "title");
        this.tooltip = Objects.requireNonNull(tooltip, "tooltip");
        box.setName(Objects.requireNonNull(name, "name"));
        box.getAccessibleContext().setAccessibleName("View");
        // Neither is a UIResource, so both survive theme changes.
        box.setRenderer(new Renderer());
        box.setKeySelectionManager(new TitleKeys());
        ContentStyle.font(box, Type.body());
        for (String action : FORWARD) step(action, 1);
        for (String action : BACKWARD) step(action, -1);
        box.addItemListener(e -> {
            describe();
            if (quiet || e.getStateChange() != ItemEvent.SELECTED) return;
            V value = selected();
            for (Consumer<V> listener : new ArrayList<>(listeners)) listener.accept(value);
        });
    }

    /**
     * Lists {@code simple}, then {@code analyst} under an "Analyst" header (none when empty), then {@code extra} under a "Current
     * view" header when it is not null and neither list has it. Nulls and repeats are dropped. The selection stays when the new rows
     * still list it and otherwise moves to the first item. Silent.
     */
    public void setItems(List<V> simple, List<V> analyst, V extra) {
        List<Object> rows = new ArrayList<>();
        List<V> listed = new ArrayList<>();
        add(rows, listed, simple, null);
        add(rows, listed, analyst, "Analyst");
        if (extra != null) add(rows, listed, List.of(extra), "Current view");
        V keep = selected();
        DefaultComboBoxModel<Object> model = new DefaultComboBoxModel<>(rows.toArray());
        model.setSelectedItem(keep != null && listed.contains(keep) ? keep : listed.isEmpty() ? null : listed.get(0));
        quiet = true;
        try { box.setModel(model); } finally { quiet = false; }
        box.setMaximumRowCount(Math.max(box.getMaximumRowCount(), rows.size()));
        describe();
    }

    /** Adds the values not listed yet, the header (when not null) before the first of them. */
    private static <V> void add(List<Object> rows, List<V> listed, List<V> values, String header) {
        if (values == null) return;
        boolean headed = header == null;
        for (V value : values) {
            if (value == null || listed.contains(value)) continue;
            if (!headed) { rows.add(new Header(header)); headed = true; }
            rows.add(value);
            listed.add(value);
        }
    }

    /** The view shown, or null when nothing is listed. */
    @SuppressWarnings("unchecked")
    public V selected() {
        Object item = box.getSelectedItem();
        return item == null || item instanceof Header ? null : (V) item;
    }

    /** Shows {@code value} without notifying. A null or unlisted value leaves the selection as it is (list it as setItems' extra). */
    public void select(V value) {
        if (value == null) return;
        quiet = true;
        try { box.setSelectedItem(value); } finally { quiet = false; }
    }

    /** Hears the user's choices only, never select() or setItems(). */
    public void onChange(Consumer<V> listener) { listeners.add(Objects.requireNonNull(listener, "listener")); }

    public JComponent component() { return box; }

    private void describe() {
        V value = selected();
        box.setToolTipText(value == null ? null : tip(value));
    }

    private String tip(V value) {
        String text = tooltip.apply(value);
        return text == null || text.isEmpty() ? null : text;
    }

    private String text(Object item) {
        @SuppressWarnings("unchecked") String text = item == null || item instanceof Header ? null : title.apply((V) item);
        return text == null ? "" : text;
    }

    /**
     * Wraps the look and feel's navigation action (looked up on each press, so it follows theme changes) with the direction it
     * steps in, then moves the popup's highlight off a header when the selection stayed put at either end.
     */
    private void step(String action, int direction) {
        box.getActionMap().put(action, new AbstractAction(action) {
            @Override public void actionPerformed(ActionEvent e) {
                ActionMap ui = box.getActionMap().getParent();
                Action original = ui == null ? null : ui.get(action);
                if (original == null) return;
                box.stepping = direction;
                try { original.actionPerformed(e); } finally { box.stepping = 0; }
                JList<?> list = popupList();
                if (list == null || !(box.getItemAt(list.getSelectedIndex()) instanceof Header)) return;
                int row = box.over(list.getSelectedIndex(), direction);
                if (row >= 0) { list.setSelectedIndex(row); list.ensureIndexIsVisible(row); }
            }
        });
    }

    private JList<?> popupList() {
        Accessible popup = box.getUI() == null ? null : box.getUI().getAccessibleChild(box, 0);
        return popup instanceof ComboPopup ? ((ComboPopup) popup).getList() : null;
    }

    /** The combo: a header is never selected, except that a navigation key landing on one moves on to the nearest item. */
    private final class Box extends JComboBox<Object> {
        /** +1 or -1 while a navigation key runs, else 0. */
        int stepping;

        @Override public void setSelectedItem(Object item) {
            if (item instanceof Header) {
                int row = stepping == 0 ? -1 : over(indexOf(item), stepping);
                if (row < 0) return;
                item = getItemAt(row);
            }
            super.setSelectedItem(item);
        }

        /** The nearest item from {@code row} in {@code direction}, else the other way; -1 when there is none. */
        int over(int row, int direction) {
            for (int way : new int[] {direction, -direction})
                for (int i = row; i >= 0 && i < getItemCount(); i += way) if (!(getItemAt(i) instanceof Header)) return i;
            return -1;
        }

        private int indexOf(Object item) {
            for (int i = 0; i < getItemCount(); i++) if (getItemAt(i) == item) return i;
            return -1;
        }
    }

    /** Items show their title and tooltip; a header is a muted caption with a rule above it and never looks selected. */
    private final class Renderer extends DefaultListCellRenderer {
        private final JLabel header = new JLabel();
        private Font base, caption;

        Renderer() {
            putClientProperty("html.disable", Boolean.TRUE);
            header.putClientProperty("html.disable", Boolean.TRUE);
        }

        @SuppressWarnings("unchecked")
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused) {
            if (value instanceof Header) {
                header.setText(((Header) value).title);
                header.setFont(caption(list.getFont()));
                header.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
                header.setBorder(new CompoundBorder(new MatteBorder(index > 0 ? 1 : 0, 0, 0, 0, Tokens.color(Tokens.Role.BORDER_SUBTLE)),
                    new EmptyBorder(Tokens.XS, 0, 2, 0)));
                return header;
            }
            super.getListCellRendererComponent(list, text(value), index, selected, focused);
            setToolTipText(value == null ? null : tip((V) value));
            return this;
        }

        private Font caption(Font font) {
            if (font != base) { base = font; caption = ContentStyle.emphasis(ContentStyle.metadata(font)); }
            return caption;
        }

        /** The header is not in any component tree; JComboBox.updateUI reaches this renderer, and this passes it on. */
        @Override public void updateUI() {
            super.updateUI();
            if (header != null) header.updateUI(); // null while DefaultListCellRenderer's constructor runs
        }
    }

    /** Typing matches item titles, never a header: from the row after the selection, a repeated letter cycling as in a list. */
    private final class TitleKeys implements JComboBox.KeySelectionManager {
        private String typed = "", prefix = "";
        private long last;

        @Override public int selectionForKey(char key, ComboBoxModel<?> model) {
            long now = EventQueue.getMostRecentEventTime();
            String letter = String.valueOf(key).toLowerCase(Locale.ROOT);
            int from = box.getSelectedIndex(), start = from + 1;
            if (now - last >= 0 && now - last < 1000 && !typed.isEmpty()) {
                typed += letter;
                // A repeated first letter moves on to the next title with it; a longer prefix may still match the selected row.
                if (!(prefix.length() == 1 && letter.equals(prefix))) { prefix = typed; start = Math.max(from, 0); }
            } else {
                typed = prefix = letter;
            }
            last = now;
            int size = model.getSize();
            for (int i = 0; i < size; i++) {
                int row = (start + i) % size;
                Object item = model.getElementAt(row);
                if (!(item instanceof Header) && text(item).toLowerCase(Locale.ROOT).startsWith(prefix)) return row;
            }
            return -1;
        }
    }
}
