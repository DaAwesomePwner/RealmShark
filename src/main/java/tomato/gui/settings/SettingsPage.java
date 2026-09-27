package tomato.gui.settings;

import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.*;
import tomato.gui.kit.Tokens;

/**
 * Shell page 13: a short list of sections beside the chosen section; below 720 px the list moves above
 * it. Sections are fixed (no hiding or reordering), so every setting stays reachable.
 */
public final class SettingsPage extends JPanel {
    public static final String NOTIFICATIONS = "notifications", APPEARANCE = "appearance";
    private static final int NARROW = 720;
    private final JPanel list = new JPanel(new GridLayout(0, 1, 0, 2));
    private final JPanel column = new JPanel(new BorderLayout());
    private final JPanel content = new JPanel(new CardLayout());
    private final Map<String, JToggleButton> sections = new LinkedHashMap<>();
    private final ButtonGroup group = new ButtonGroup();
    private String current;
    private boolean narrow;

    /**
     * @param notifications the existing Notifications page, hosted unchanged
     * @param notificationsShown refreshes it when Settings opens on that section; CardLayout only notifies this page
     * @param appearance the Appearance section
     */
    public SettingsPage(JComponent notifications, Runnable notificationsShown, JComponent appearance) {
        super(new BorderLayout(Tokens.L, Tokens.S));
        setName("settings-page");
        setOpaque(false);
        list.setName("settings-sections");
        list.setOpaque(false);
        list.getAccessibleContext().setAccessibleName("Settings sections");
        column.setOpaque(false);
        column.add(list, BorderLayout.NORTH);
        content.setOpaque(false);
        addSection(NOTIFICATIONS, "Notifications", notifications);
        addSection(APPEARANCE, "Appearance", appearance);
        add(column, BorderLayout.WEST);
        add(content, BorderLayout.CENTER);
        addComponentListener(new ComponentAdapter() {
            @Override public void componentShown(ComponentEvent e) { if (NOTIFICATIONS.equals(current)) notificationsShown.run(); }
            @Override public void componentResized(ComponentEvent e) { adapt(); }
        });
        showSection(NOTIFICATIONS);
    }

    private void addSection(String id, String title, JComponent section) {
        JToggleButton button = new JToggleButton(title);
        button.setName("settings-section-" + id);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.addActionListener(e -> showSection(id));
        group.add(button);
        sections.put(id, button);
        list.add(button);
        content.add(section, id);
    }

    /** Shows one section; unknown IDs leave the page as it is. */
    public void showSection(String id) {
        JToggleButton button = sections.get(id);
        if (button == null) return;
        current = id;
        button.setSelected(true);
        ((CardLayout) content.getLayout()).show(content, id);
    }

    public String currentSection() { return current; }

    private void adapt() {
        boolean now = getWidth() > 0 && getWidth() < NARROW;
        if (now == narrow) return;
        narrow = now;
        list.setLayout(narrow ? new FlowLayout(FlowLayout.LEADING, Tokens.XS, 0) : new GridLayout(0, 1, 0, 2));
        remove(column);
        add(column, narrow ? BorderLayout.NORTH : BorderLayout.WEST);
        revalidate();
        repaint();
    }
}
