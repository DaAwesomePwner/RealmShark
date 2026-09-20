package tomato.gui.modern;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import util.PreferencesStore;
import util.PropertiesManager;

/** Responsive navigation around the original feature panels; no data is duplicated. */
public final class WorkspaceShell extends JPanel {
    public static final String[] TITLES = {"Chat", "Key-pops", "Security", "Characters", "Statistics", "Daily Quests", "My Info", "DPS Logger", "Loot", "Logging", "Runs", "Timeline", "Bridge Review", "Notifications"};
    private static final String[] DESCRIPTIONS = {
        "Your conversations across the Realm, in one place.",
        "Follow dungeon openings and configure your notifications.",
        "Inspect players, equipment and ability activity.",
        "Keep a character roster, track maxing and equipment, and follow exalts.",
        "Track fame, loot and dungeon progress over time.",
        "Review quests collected from the Daily Quest Room.",
        "Inspect your current character, equipment and damage.",
        "Review encounters, compare damage and fine-tune your filters.",
        "Explore live loot by item, stat potion and bag type.",
        "Discover available fields, inspect stat changes and diagnose capture gaps.",
        "Review area visits, progression and party activity.",
        "Follow party, equipment and progression events across your sessions.",
        "Review detected loot, configure guild exports and troubleshoot delivery.",
        "Choose sounds for messages, dungeon openings, realm events and loot."
    };
    private final Sidebar sidebar = new Sidebar();
    private final JPanel workspace = new JPanel(new BorderLayout(0, 8));
    private final JPanel branding = new JPanel(new CardLayout());
    private final JPanel nav = new JPanel(new GridBagLayout());
    private final JScrollPane navScroll = new JScrollPane(nav);
    private final JPanel cards = ContentStyle.card(new CardLayout());
    private final JToggleButton[] navigation = new JToggleButton[TITLES.length];
    private final JRadioButtonMenuItem[] destinations = new JRadioButtonMenuItem[TITLES.length];
    private final JButton compactNavigation = new JButton(new NavigationMenuIcon()) {
        @Override public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            size.height = Math.max(32, size.height);
            return size;
        }
    };
    private final JPopupMenu navigationPopup = new JPopupMenu("Workspace navigation");
    private final JLabel title = new JLabel(), description = new JLabel();
    private final JLabel mark = new JLabel(new LineIcon(8, 22));
    private final JLabel brand = new JLabel("RealmShark"), eyebrow = new JLabel("WORKSPACE");
    private final JLabel status = new JLabel("Capture is off"), hint = new JLabel("Start capture, then enter the Realm to see activity.");
    private final JLabel preferencesStatus = new JLabel();
    private final Timer preferencesTimer = new Timer(250, e -> refreshPreferencesStatus());
    private final JLabel sideFooter = new JLabel("Powered by RealmShark");
    private final JButton capture = new JButton("Start capture");
    private final JLabel previewLabel = new JLabel("PREVIEW");
    private boolean compact;
    private final JTextArea captureFailure = new JTextArea();
    private int selected;
    private boolean scrollPending;

    public WorkspaceShell(JComponent[] panels, Runnable toggleCapture, boolean preview) {
        super(new BorderLayout());
        if (panels.length != TITLES.length) throw new IllegalArgumentException("All feature panels are required");
        sidebar.setPreferredSize(new Dimension(188, 0));
        JPanel brandRow = new JPanel(new BorderLayout(8, 0)); brandRow.setOpaque(false);
        brand.setFont(ContentStyle.emphasis(ContentStyle.body().deriveFont(ContentStyle.body().getSize2D() * 17f / ContentStyle.FONT_SIZE)));
        brandRow.add(mark, BorderLayout.WEST); brandRow.add(brand, BorderLayout.CENTER);
        branding.setOpaque(false); branding.add(brandRow, "brand");
        compactNavigation.setName("compact-navigation");
        compactNavigation.setMargin(new Insets(3, 4, 3, 4));
        compactNavigation.putClientProperty("JComponent.minimumWidth", 0);
        compactNavigation.setToolTipText("Choose workspace (Alt+M)");
        compactNavigation.getAccessibleContext().setAccessibleName("Choose workspace");
        navigationPopup.setName("compact-navigation-popup");
        navigationPopup.getAccessibleContext().setAccessibleName("Workspace navigation");
        compactNavigation.setComponentPopupMenu(navigationPopup);
        compactNavigation.addActionListener(e -> showNavigation());
        compactNavigation.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "open-navigation");
        compactNavigation.getActionMap().put("open-navigation", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { showNavigation(); }
        });
        branding.add(compactNavigation, "menu");
        branding.setBorder(new EmptyBorder(0, 4, 10, 4));
        sidebar.add(branding, BorderLayout.NORTH);
        GridBagConstraints gc = new GridBagConstraints(); gc.gridx = 0; gc.weightx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
        eyebrow.setFont(ContentStyle.metadata(ContentStyle.body()));
        eyebrow.setBorder(new EmptyBorder(0, 10, 6, 0)); gc.gridy = 0; nav.add(eyebrow, gc);
        ButtonGroup group = new ButtonGroup(), menuGroup = new ButtonGroup();
        for (int i = 0; i < panels.length; i++) {
            final int index = i;
            JToggleButton button = new NavigationButton(TITLES[i], new LineIcon(i >= 8 ? i + 1 : i));
            button.setFont(ContentStyle.body());
            button.setName("nav-" + i); button.setToolTipText(TITLES[i] + "  (Alt+" + (i == 13 ? "N" : i == 12 ? "B" : i == 10 ? "R" : i == 11 ? "T" : Integer.toString((i + 1) % 10)) + ")");
            button.getAccessibleContext().setAccessibleName(TITLES[i]);
            button.setHorizontalAlignment(SwingConstants.LEFT); button.setIconTextGap(8);
            // FlatLaf paints keyboard focus in its border, even with explicit navigation colors.
            button.setMargin(new Insets(2, 6, 2, 6)); button.setFocusPainted(true);
            button.putClientProperty("JComponent.minimumWidth", 0);
            button.addActionListener(e -> select(index));
            navigation[i] = button; group.add(button); gc.gridy = i + 1; gc.insets = new Insets(1, 0, 1, 0); nav.add(button, gc);
            cards.add(panels[i], Integer.toString(i));
            KeyStroke shortcut = KeyStroke.getKeyStroke(i == 13 ? KeyEvent.VK_N : i == 12 ? KeyEvent.VK_B : i == 10 ? KeyEvent.VK_R : i == 11 ? KeyEvent.VK_T : i == 9 ? KeyEvent.VK_0 : KeyEvent.VK_1 + i, InputEvent.ALT_DOWN_MASK);
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(shortcut, "page-" + i);
            getActionMap().put("page-" + i, new AbstractAction() { public void actionPerformed(ActionEvent e) { select(index); navigation[index].requestFocusInWindow(); }});
            JRadioButtonMenuItem destination = new JRadioButtonMenuItem(TITLES[i], button.getIcon());
            destination.setName("compact-nav-" + i); destination.setAccelerator(shortcut);
            destination.addActionListener(e -> { select(index); navigation[index].requestFocusInWindow(); });
            destinations[i] = destination; menuGroup.add(destination); navigationPopup.add(destination);
        }
        gc.gridy++; gc.weighty = 1; nav.add(Box.createVerticalGlue(), gc);
        navScroll.setBorder(null); navScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        navScroll.getVerticalScrollBar().setUnitIncrement(34);
        sidebar.add(navScroll, BorderLayout.CENTER);
        sideFooter.setFont(ContentStyle.metadata(ContentStyle.body())); sideFooter.setBorder(new EmptyBorder(8, 4, 0, 0));
        sidebar.add(sideFooter, BorderLayout.SOUTH);
        add(sidebar, BorderLayout.WEST);

        workspace.setName("workspace-content"); workspace.setBorder(new EmptyBorder(12, 12, 10, 12));
        JPanel header = new JPanel(new BorderLayout(12, 0));
        JPanel heading = new JPanel(new BorderLayout(0, 2));
        title.setFont(ContentStyle.emphasis(ContentStyle.body().deriveFont(ContentStyle.body().getSize2D() * 20f / ContentStyle.FONT_SIZE)));
        description.setFont(ContentStyle.metadata(ContentStyle.body()));
        heading.add(title, BorderLayout.NORTH); heading.add(description, BorderLayout.CENTER);
        header.add(heading, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        capture.setName("capture-toggle");
        capture.setToolTipText("Start or stop the network sniffer (Ctrl+Shift+S)");
        capture.addActionListener(e -> toggleCapture.run());
        previewLabel.setFont(ContentStyle.metadata(ContentStyle.body()));
        previewLabel.setVisible(preview); actions.add(previewLabel); actions.add(capture);
        capture.setEnabled(!preview);
        header.add(actions, BorderLayout.EAST); workspace.add(header, BorderLayout.NORTH);
        cards.setMinimumSize(new Dimension(0, 0)); workspace.add(cards, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(16, 0));
        status.setFont(ContentStyle.metadata(ContentStyle.body()));
        hint.setFont(ContentStyle.metadata(ContentStyle.body()));
        footer.add(status, BorderLayout.WEST); footer.add(hint, BorderLayout.CENTER);
        preferencesStatus.setName("preferences-status");
        preferencesStatus.setFont(ContentStyle.metadata(ContentStyle.body()));
        footer.add(preferencesStatus, BorderLayout.EAST);
        captureFailure.setName("capture-failure");
        captureFailure.setEditable(false); captureFailure.setLineWrap(true); captureFailure.setWrapStyleWord(true);
        captureFailure.setOpaque(false);
        captureFailure.setFont(status.getFont()); captureFailure.setRows(3); captureFailure.setVisible(false);
        JPanel captureInfo = new JPanel(new BorderLayout(0, 8));
        captureInfo.add(footer, BorderLayout.NORTH); captureInfo.add(captureFailure, BorderLayout.CENTER);
        workspace.add(captureInfo, BorderLayout.SOUTH);
        add(workspace, BorderLayout.CENTER);
        addComponentListener(new ComponentAdapter() { @Override public void componentResized(ComponentEvent e) { adapt(); }});
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "capture");
        getActionMap().put("capture", new AbstractAction() { public void actionPerformed(ActionEvent e) { if (capture.isEnabled()) capture.doClick(); }});
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.ALT_DOWN_MASK), "open-navigation");
        getActionMap().put("open-navigation", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { showNavigation(); }
        });
        refreshTheme();
        select(0);
    }

    @Override public void updateUI() {
        super.updateUI();
        if (sidebar != null) refreshTheme();
    }

    @Override public void addNotify() {
        super.addNotify();
        refreshPreferencesStatus();
        preferencesTimer.start();
    }

    @Override public void removeNotify() {
        preferencesTimer.stop();
        super.removeNotify();
    }

    private void refreshPreferencesStatus() {
        PreferencesStore.Status saving = PropertiesManager.status();
        switch (saving.state) {
            case LOADING: preferencesStatus.setText("Preferences loading…"); break;
            case SAVING: preferencesStatus.setText("Preferences saving…"); break;
            case FAILED: preferencesStatus.setText("Preferences not saved"); break;
            default: preferencesStatus.setText("Preferences saved");
        }
        preferencesStatus.setToolTipText(saving.detail);
        preferencesStatus.setForeground(ContentStyle.color(saving.state == PreferencesStore.State.FAILED ? "rose" : "muted"));
    }

    /** Refreshes explicit shell colors from the active LAF rather than retaining a dark sidebar. */
    public void refreshTheme() {
        Color background = ContentStyle.color("navigation");
        setBackground(ContentStyle.color("background"));
        sidebar.setBackground(background); nav.setBackground(background);
        // The wash ends at the branding row, so the destinations below it sit on an even backdrop.
        sidebar.wash(blend(background, ContentStyle.color("violet"), .12f), background);
        sidebar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, ContentStyle.color("border")),
            new EmptyBorder(12, compact ? 6 : 8, 10, compact ? 6 : 8)));
        cards.setBackground(ContentStyle.color("background"));
        cards.setBorder(new EmptyBorder(6, 6, 6, 6));
        brand.setForeground(ContentStyle.color("text")); title.setForeground(ContentStyle.color("text"));
        mark.setForeground(ContentStyle.color("violet")); previewLabel.setForeground(ContentStyle.color("violet"));
        for (JLabel label : new JLabel[] {eyebrow, sideFooter, description, hint}) label.setForeground(ContentStyle.color("muted"));
        status.setForeground(ContentStyle.color("text")); captureFailure.setForeground(ContentStyle.color("rose"));
        refreshPreferencesStatus();
        capture.setBackground(VioletTheme.CAPTURE_BACKGROUND); capture.setForeground(Color.WHITE);
        capture.putClientProperty("FlatLaf.style", "background: #7041BD; foreground: #FFFFFF; focusedBackground: #8052CD; hoverBackground: #8052CD; pressedBackground: #6036A5; hoverForeground: #FFFFFF; pressedForeground: #FFFFFF");
        for (int i = 0; i < navigation.length; i++) if (navigation[i] != null) styleNavigation(i);
    }

    private void styleNavigation(int index) {
        navigation[index].setForeground(ContentStyle.color(index == selected ? "selectionText" : "text"));
        navigation[index].setBackground(ContentStyle.color(index == selected ? "selection" : "navigation"));
        // Hover and pressed live in the style map, so the background stays the destination's own color.
        java.util.Map<String, Object> style = new java.util.HashMap<>();
        style.put("selectedBackground", ContentStyle.color("selection"));
        style.put("selectedForeground", ContentStyle.color("selectionText"));
        style.put("hoverBackground", ContentStyle.color("hover"));
        style.put("pressedBackground", ContentStyle.color("accentWash"));
        // Destinations are a rail, not a stack of buttons, so the resting outline matches its own
        // fill. The real border stays in place, and keyboard focus still recolors it.
        Color rest = ContentStyle.color(index == selected ? "selection" : "navigation");
        style.put("borderColor", rest);
        style.put("disabledBorderColor", rest);
        navigation[index].putClientProperty("FlatLaf.style", style);
    }

    /** Mixes two colors, for shell surfaces that sit between two semantic roles. */
    private static Color blend(Color from, Color to, float weight) {
        return new Color(Math.round(from.getRed() + (to.getRed() - from.getRed()) * weight),
            Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * weight),
            Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * weight));
    }

    private void showNavigation() {
        Component anchor = compact ? compactNavigation : navigation[selected];
        navigationPopup.show(anchor, 0, anchor.getHeight());
        MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[] {navigationPopup, destinations[selected]});
    }

    /**
     * A destination in the rail. The selected destination carries a violet rail on its leading
     * edge, which survives compact mode, where the label is hidden and only the icon remains.
     */
    private static final class NavigationButton extends JToggleButton {
        private static final int RAIL_WIDTH = 3;
        /** Resolved once per look-and-feel rather than on every paint. */
        private Color rail;

        NavigationButton(String title, Icon icon) { super(title, icon); }

        @Override public void updateUI() { rail = null; super.updateUI(); }

        @Override public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            size.height = Math.max(32, size.height);
            return size;
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (!isSelected()) return;
            if (rail == null) rail = ContentStyle.color("violet");
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(rail);
            int height = Math.max(10, getHeight() - 12);
            g.fillRoundRect(2, (getHeight() - height) / 2, RAIL_WIDTH, height, RAIL_WIDTH, RAIL_WIDTH);
            g.dispose();
        }
    }

    /**
     * The navigation rail's backdrop. Its vertical wash is rebuilt only when the height or the
     * theme changes, so scrolling and resizing repaint with a cached paint.
     */
    private static final class Sidebar extends JPanel {
        private Paint wash;
        private Color top, bottom;
        private int washFade = -1;

        Sidebar() { super(new BorderLayout()); }

        void wash(Color top, Color bottom) {
            this.top = top; this.bottom = bottom;
            wash = null; washFade = -1;
        }

        /**
         * The distance over which the wash reaches the base color. The scrolling destination list
         * below the branding row is opaque and paints the base color flat, so the wash has to
         * arrive there exactly; a fade tied to the sidebar height would leave a step at that seam.
         */
        private int fade() {
            Component branding = ((BorderLayout) getLayout()).getLayoutComponent(BorderLayout.NORTH);
            return branding == null ? Math.max(1, getHeight() / 4)
                : Math.max(1, branding.getY() + branding.getHeight());
        }

        @Override protected void paintComponent(Graphics graphics) {
            if (top == null || bottom == null) { super.paintComponent(graphics); return; }
            int fade = fade();
            if (wash == null || washFade != fade) {
                wash = new GradientPaint(0, 0, top, 0, fade, bottom);
                washFade = fade;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            g.setPaint(wash);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.dispose();
        }
    }

    private static final class NavigationMenuIcon implements Icon {
        @Override public int getIconWidth() { return 18; }
        @Override public int getIconHeight() { return 18; }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(x, y);
            g.scale(18.0 / 22, 18.0 / 22);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(component.getForeground());
            for (int line = 5; line <= 17; line += 6) g.drawLine(3, line, 19, line);
            g.dispose();
        }
    }

    public void select(int index) {
        if (index < 0 || index >= navigation.length) throw new IllegalArgumentException("Invalid page");
        selected = index; ((CardLayout) cards.getLayout()).show(cards, Integer.toString(index));
        title.setText(TITLES[index]); description.setText(DESCRIPTIONS[index]); description.setToolTipText(DESCRIPTIONS[index]);
        title.setToolTipText(DESCRIPTIONS[index]);
        for (int i = 0; i < navigation.length; i++) {
            navigation[i].setSelected(i == index);
            destinations[i].setSelected(i == index);
            styleNavigation(i);
        }
        scrollSelected(); scrollSelectedLater();
    }
    public int getSelectedPage() { return selected; }
    public boolean isCompact() { return compact; }
    public void setCaptureState(boolean running) {
        captureFailure.setText(""); captureFailure.setVisible(false);
        capture.setText(running ? "Stop capture" : "Start capture");
        status.setText(running ? "Capture enabled" : "Capture is off");
        hint.setText(running ? "Waiting for game traffic or receiving packets." : "Start capture, then enter the Realm to see activity.");
        status.setToolTipText(hint.getText());
    }
    public void setCaptureDetail(String detail) {
        hint.setText(detail);
        hint.setToolTipText(detail);
        status.setToolTipText(detail);
    }
    public void setCaptureFailure(String reason) {
        setCaptureState(false);
        status.setText("Capture stopped — error");
        setCaptureDetail("Restart capture after resolving the error below.");
        captureFailure.setText(reason); captureFailure.setVisible(true);
        revalidate(); repaint();
    }
    private void adapt() {
        boolean changed = compact != (getWidth() < 1000);
        compact = getWidth() < 1000;
        ((CardLayout) branding.getLayout()).show(branding, compact ? "menu" : "brand");
        eyebrow.setVisible(!compact); sideFooter.setVisible(!compact);
        workspace.setBorder(compact ? new EmptyBorder(8, 8, 8, 8) : new EmptyBorder(12, 12, 10, 12));
        description.setVisible(getWidth() >= 850); hint.setVisible(getWidth() >= 820);
        for (int i = 0; i < navigation.length; i++) {
            navigation[i].setText(compact ? "" : TITLES[i]);
            navigation[i].setHorizontalAlignment(compact ? SwingConstants.CENTER : SwingConstants.LEFT);
            navigation[i].setMargin(new Insets(2, compact ? 2 : 6, 2, compact ? 2 : 6));
        }
        if (changed) refreshTheme();
        revalidate(); scrollSelectedLater();
    }

    @Override public void doLayout() {
        // Reserve the scrollbar as well as the complete focus border and label/icon at the current font.
        Insets insets = sidebar.getInsets();
        int width = nav.getPreferredSize().width + navScroll.getVerticalScrollBar().getPreferredSize().width;
        Insets brandingInsets = branding.getInsets();
        width = Math.max(width, compact ? compactNavigation.getPreferredSize().width + brandingInsets.left + brandingInsets.right
            : branding.getPreferredSize().width);
        if (!compact) width = Math.max(width, sideFooter.getPreferredSize().width);
        Dimension size = new Dimension(Math.max(compact ? 60 : 188, width + insets.left + insets.right), 0);
        if (!size.equals(sidebar.getPreferredSize())) sidebar.setPreferredSize(size);
        super.doLayout();
        scrollSelectedLater();
    }

    private void scrollSelected() {
        JToggleButton button = navigation[selected];
        button.scrollRectToVisible(new Rectangle(0, 0, button.getWidth(), button.getHeight()));
    }

    private void scrollSelectedLater() {
        if (scrollPending) return;
        scrollPending = true;
        SwingUtilities.invokeLater(() -> { scrollPending = false; scrollSelected(); });
    }
}
