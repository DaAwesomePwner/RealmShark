package tomato.gui.settings;

import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.swing.*;
import tomato.gui.kit.Tokens;

/**
 * The shell's settings page: a short list of sections beside the chosen section; below 720 px the list moves above
 * it. Sections are fixed (no hiding or reordering), so every setting stays reachable.
 */
public final class SettingsPage extends JPanel {
    public static final String NOTIFICATIONS = "notifications", GENERAL = "general", APPEARANCE = "appearance";
    /** P6a sections; their menu entries (Edit › Filter Loot, Chat, Info › About) open them through TomatoMenuBar.onOpenSettings. */
    public static final String LOOT_FILTERS = "loot-filters", CHAT = "chat", ABOUT = "about";
    // Spec §6.7 order.
    private static final String[] IDS = {NOTIFICATIONS, GENERAL, APPEARANCE, LOOT_FILTERS, CHAT, ABOUT};
    private static final String[] TITLES = {"Notifications", "General", "Appearance", "Loot filters", "Chat", "About"};
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
     * @param general the General section (Combat history)
     * @param appearance the Appearance section
     */
    public SettingsPage(JComponent notifications, Runnable notificationsShown, JComponent general, JComponent appearance) {
        this(notificationsShown, notifications, general, appearance);
    }

    /**
     * The full page (P6a): as the four-argument page, then Loot filters, Chat and About.
     *
     * @param lootFilters Settings › Loot filters (the Filter Loot model)
     * @param chat Settings › Chat (Save chat and the chat filter editor)
     * @param about Settings › About
     */
    public SettingsPage(JComponent notifications, Runnable notificationsShown, JComponent general, JComponent appearance,
                        JComponent lootFilters, JComponent chat, JComponent about) {
        this(notificationsShown, notifications, general, appearance, Objects.requireNonNull(lootFilters, "lootFilters"),
            Objects.requireNonNull(chat, "chat"), Objects.requireNonNull(about, "about"));
    }

    /** @param contents the first contents.length sections of IDS, in that order */
    private SettingsPage(Runnable notificationsShown, JComponent... contents) {
        super(new BorderLayout(Tokens.L, Tokens.S));
        setName("settings-page");
        setOpaque(false);
        list.setName("settings-sections");
        list.setOpaque(false);
        list.getAccessibleContext().setAccessibleName("Settings sections");
        column.setOpaque(false);
        column.add(list, BorderLayout.NORTH);
        content.setOpaque(false);
        // Spec §6.7 order: Notifications, General, Appearance, Loot filters, Chat, About.
        for (int i = 0; i < contents.length; i++) addSection(IDS[i], TITLES[i], contents[i]);
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
        if (now == narrow) {
            // The wrapped row count follows the page width; the list's cached size does not see a resize by itself.
            if (narrow) list.revalidate();
            return;
        }
        narrow = now;
        list.setLayout(narrow ? new WrappingRow() : new GridLayout(0, 1, 0, 2));
        remove(column);
        add(column, narrow ? BorderLayout.NORTH : BorderLayout.WEST);
        revalidate();
        repaint();
    }

    /**
     * Narrow: the section buttons as a row that wraps at the page width (the list spans the page above the content), so
     * every section stays reachable at a large font. FlowLayout places the buttons; this only reports the wrapped height.
     */
    private final class WrappingRow extends FlowLayout {
        WrappingRow() { super(FlowLayout.LEADING, Tokens.XS, 2); }

        @Override public Dimension preferredLayoutSize(Container target) {
            synchronized (target.getTreeLock()) {
                Insets page = SettingsPage.this.getInsets(), insets = target.getInsets();
                int available = SettingsPage.this.getWidth() - page.left - page.right - insets.left - insets.right - getHgap() * 2;
                int x = 0, row = 0, height = 0, widest = 0;
                for (Component child : target.getComponents()) {
                    if (!child.isVisible()) continue;
                    Dimension size = child.getPreferredSize();
                    // FlowLayout.layoutContainer's own wrapping rule.
                    if (x > 0 && x + size.width > available) {
                        height += row + getVgap();
                        widest = Math.max(widest, x);
                        x = row = 0;
                    }
                    x += (x > 0 ? getHgap() : 0) + size.width;
                    row = Math.max(row, size.height);
                }
                widest = Math.max(widest, x);
                return new Dimension(widest + insets.left + insets.right + getHgap() * 2,
                    height + row + getVgap() * 2 + insets.top + insets.bottom);
            }
        }

        @Override public Dimension minimumLayoutSize(Container target) { return preferredLayoutSize(target); }
    }
}
