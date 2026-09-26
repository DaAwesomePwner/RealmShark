package tomato.gui.kit;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import javax.swing.*;

/** A small exclusive choice (Simple/Analyst, Feed/Table). setSelected is silent; user clicks notify. */
public class SegmentedControl extends JPanel {
    private final List<JToggleButton> buttons = new ArrayList<>();
    private final List<IntConsumer> listeners = new ArrayList<>();

    public SegmentedControl(String name, String... options) {
        super(new GridLayout(1, options.length, 0, 0));
        if (options.length < 2) throw new IllegalArgumentException("A segmented control needs at least two options");
        setName(name);
        setOpaque(false);
        getAccessibleContext().setAccessibleName(name.replace('-', ' '));
        ButtonGroup group = new ButtonGroup();
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            JToggleButton button = new JToggleButton(options[i]);
            button.setName(name + "-" + i);
            button.putClientProperty("FlatLaf.style", "arc: 0");
            button.addActionListener(e -> { for (IntConsumer listener : listeners) listener.accept(index); });
            group.add(button);
            buttons.add(button);
            add(button);
        }
        buttons.get(0).setSelected(true);
    }

    public int selected() {
        for (int i = 0; i < buttons.size(); i++) if (buttons.get(i).isSelected()) return i;
        return -1;
    }

    public void setSelected(int index) { buttons.get(index).setSelected(true); }

    public void onChange(IntConsumer listener) { listeners.add(listener); }
}
