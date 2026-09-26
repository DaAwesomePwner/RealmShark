package tomato.gui.kit;

import java.awt.*;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.swing.*;
import tomato.gui.modern.LineIcon;
import util.PropertiesManager;

/** A titled section that remembers whether it is open. Opening and closing use Motion (≤100 ms). */
public class Collapsible extends JPanel {
    public static final String PREFIX = "ui.collapse.";

    private final String id;
    private final JComponent content;
    private final JPanel holder;
    private final KitButton toggle;
    private final BiConsumer<String, String> write;
    private boolean expanded;
    private float fraction = 1f;
    private Timer animation;

    public Collapsible(String id, String title, JComponent content, boolean expandedByDefault) {
        this(id, title, content, expandedByDefault, PropertiesManager::getProperty, PropertiesManager::setProperties);
    }

    Collapsible(String id, String title, JComponent content, boolean expandedByDefault,
                Function<String, String> read, BiConsumer<String, String> write) {
        // No layout gap: the content holder carries its own padding, so a closed section leaves no space.
        super(new BorderLayout());
        this.id = Objects.requireNonNull(id, "id");
        this.content = Objects.requireNonNull(content, "content");
        this.write = write;
        setOpaque(false);
        toggle = KitButton.ghost(title);
        toggle.setName("collapsible-" + id);
        toggle.setHorizontalAlignment(SwingConstants.LEFT);
        toggle.addActionListener(e -> setExpanded(!expanded));
        holder = new JPanel(new BorderLayout()) {
            @Override public Dimension getPreferredSize() {
                if (!content.isVisible()) return new Dimension(0, 0);
                Dimension size = content.getPreferredSize();
                Insets padding = getInsets();
                return new Dimension(size.width + padding.left + padding.right,
                    Math.round((size.height + padding.top + padding.bottom) * fraction));
            }
            @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }
        };
        holder.setOpaque(false);
        holder.setBorder(BorderFactory.createEmptyBorder(Tokens.XS, 0, 0, 0));
        holder.add(content);
        add(toggle, BorderLayout.NORTH);
        add(holder, BorderLayout.CENTER);
        String saved = read.apply(PREFIX + id);
        expanded = saved == null || saved.isEmpty() ? expandedByDefault : Boolean.parseBoolean(saved);
        content.setVisible(expanded);
        updateToggle();
    }

    public boolean expanded() { return expanded; }
    public KitButton toggle() { return toggle; }

    public void setExpanded(boolean value) {
        if (value == expanded) return;
        expanded = value;
        write.accept(PREFIX + id, Boolean.toString(value));
        updateToggle();
        if (animation != null) animation.stop();
        if (value) content.setVisible(true);
        animation = Motion.run(Motion.MAX_MILLIS,
            progress -> { fraction = (float) (value ? progress : 1 - progress); holder.revalidate(); repaint(); },
            () -> { fraction = 1f; content.setVisible(expanded); animation = null; holder.revalidate(); repaint(); });
    }

    private void updateToggle() {
        toggle.setIcon(new LineIcon(expanded ? LineIcon.CHEVRON_DOWN : LineIcon.CHEVRON_RIGHT, 14));
        toggle.getAccessibleContext().setAccessibleDescription(expanded ? "Expanded" : "Collapsed");
    }
}
