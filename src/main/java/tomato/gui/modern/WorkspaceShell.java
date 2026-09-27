package tomato.gui.modern;

import java.awt.*;
import java.awt.event.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import packets.packetcapture.CaptureState;
import tomato.gui.kit.Chip;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.KitButton;
import tomato.gui.kit.SegmentedControl;
import tomato.gui.kit.Tokens;
import tomato.gui.kit.Type;
import tomato.gui.route.Destination;
import tomato.gui.route.ShellNavigator;
import util.PreferencesStore;
import util.PropertiesManager;

/** Responsive navigation around the original feature panels; no data is duplicated. */
public final class WorkspaceShell extends JPanel {
    /** Back label when the routed origin is the page already shown. */
    public static final String BACK_TO_PREVIOUS_VIEW = "Back to previous view";
    /** Titles by page index. Pages keep their indices until P6; NavEntry carries the stable IDs and grouping. */
    public static final String[] TITLES = NavEntry.titles();
    /** Settings sits below the scrolling destination list, so it is always in reach. */
    private static final int SETTINGS = NavEntry.forId("settings").page();
    private final NavLayout layout;
    private final DisplayModeModel mode;
    private final Sidebar sidebar = new Sidebar();
    private final JPanel workspace = new JPanel(new BorderLayout(0, 8));
    private final JPanel branding = new JPanel(new CardLayout());
    private final JPanel nav = new JPanel(new GridBagLayout());
    private final JScrollPane navScroll = new JScrollPane(nav);
    private final Component navGlue = Box.createVerticalGlue();
    private final JButton advancedToggle = new JButton();
    private final JPanel settingsRow = new JPanel(new BorderLayout());
    private final JPanel sideBottom = new JPanel(new BorderLayout());
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
    private final JLabel popupAdvanced = new JLabel("Advanced");
    private final JPopupMenu.Separator advancedSeparator = new JPopupMenu.Separator(), settingsSeparator = new JPopupMenu.Separator();
    private final JLabel title = new JLabel();
    private final JLabel mark = new JLabel(new LineIcon(8, 22));
    private final JLabel brand = new JLabel("RealmShark"), eyebrow = new JLabel("WORKSPACE");
    private final JLabel status = new JLabel("Capture is off"), hint = new JLabel("Start capture, then enter the Realm to see activity.");
    private final JLabel preferencesStatus = new JLabel();
    private final Timer preferencesTimer = new Timer(250, e -> refreshPreferencesStatus());
    private final JLabel sideFooter = new JLabel("Powered by RealmShark");
    private final KitButton capture = KitButton.primary("Start capture");
    private final JButton back = new JButton("Back");
    private ShellNavigator navigator;
    private final Chip previewLabel = new Chip("Preview", Tokens.Tone.ACCENT);
    private final Chip capturePill = new Chip("Capture off", Tokens.Tone.NEUTRAL);
    private final SegmentedControl modeSwitch = new SegmentedControl("display-mode", "Simple", "Analyst");
    private boolean compact;
    private final JTextArea captureFailure = new JTextArea();
    private final Banner setupBanner = new Banner();
    private final JTextArea setupMessage = ContentStyle.wrappingText("", 2);
    private final KitButton chooseAssets = KitButton.primary("Choose assets…"), retrySetup = KitButton.secondary("Retry assets"),
        browseHistory = KitButton.ghost("Browse saved history");
    private boolean assetsReady = true, setupBusy, captureRunning, captureFailed, preview;
    private CaptureState readiness = CaptureState.STOPPED;
    private int selected;
    private boolean scrollPending;

    public WorkspaceShell(JComponent[] panels, Runnable toggleCapture, boolean preview) {
        this(panels, toggleCapture, preview, null, null, null);
    }

    public WorkspaceShell(JComponent[] panels, Runnable toggleCapture, boolean preview,
                          Runnable choose, Runnable retry, Runnable browse) {
        this(panels, toggleCapture, preview, choose, retry, browse, new NavLayout());
    }

    /** As above, with the sidebar arrangement read from and saved to {@code layout}; tests pass an in-memory one. */
    public WorkspaceShell(JComponent[] panels, Runnable toggleCapture, boolean preview,
                          Runnable choose, Runnable retry, Runnable browse, NavLayout layout) {
        this(panels, toggleCapture, preview, choose, retry, browse, layout, DisplayModeModel.application());
    }

    /** The full form: tests pass in-memory layout and display-mode models so they never touch saved preferences. */
    public WorkspaceShell(JComponent[] panels, Runnable toggleCapture, boolean preview,
                          Runnable choose, Runnable retry, Runnable browse, NavLayout layout, DisplayModeModel mode) {
        super(new BorderLayout());
        this.preview = preview;
        this.layout = Objects.requireNonNull(layout, "layout");
        this.mode = Objects.requireNonNull(mode, "mode");
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
        eyebrow.setFont(ContentStyle.metadata(ContentStyle.body()));
        eyebrow.setBorder(new EmptyBorder(0, 10, 6, 0)); nav.add(eyebrow);
        popupAdvanced.setName("compact-nav-advanced");
        popupAdvanced.setBorder(new EmptyBorder(4, 10, 2, 10));
        ContentStyle.font(popupAdvanced, Type.caption());
        ButtonGroup group = new ButtonGroup(), menuGroup = new ButtonGroup();
        for (int i = 0; i < panels.length; i++) {
            final int index = i;
            NavEntry entry = NavEntry.forPage(i);
            JToggleButton button = new NavigationButton(TITLES[i], new LineIcon(entry.icon()));
            button.setFont(ContentStyle.body());
            button.setName("nav-" + i); button.setToolTipText(TITLES[i] + "  (" + shortcutHint(i) + ")");
            button.getAccessibleContext().setAccessibleName(TITLES[i]);
            button.setHorizontalAlignment(SwingConstants.LEFT); button.setIconTextGap(8);
            // FlatLaf paints keyboard focus in its border, even with explicit navigation colors.
            button.setMargin(new Insets(2, 6, 2, 6)); button.setFocusPainted(true);
            button.putClientProperty("JComponent.minimumWidth", 0);
            button.addActionListener(e -> select(index));
            // Right-click, Shift+F10 or the context-menu key opens the row's menu; Ctrl+Shift+Up/Down moves it.
            button.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { if (e.isPopupTrigger()) showContextMenu(index, e.getPoint()); }
                @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) showContextMenu(index, e.getPoint()); }
            });
            InputMap keys = button.getInputMap(WHEN_FOCUSED);
            keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK), "nav-menu");
            keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0), "nav-menu");
            keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "nav-move-up");
            keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "nav-move-down");
            button.getActionMap().put("nav-menu", action(() -> showContextMenu(index, null)));
            button.getActionMap().put("nav-move-up", action(() -> moveEntry(index, -1)));
            button.getActionMap().put("nav-move-down", action(() -> moveEntry(index, 1)));
            navigation[i] = button; group.add(button);
            // applyLayout places the rows in the user's order; Settings stays below the scrolling list.
            if (i == SETTINGS) settingsRow.add(button); else nav.add(button);
            cards.add(panels[i], Integer.toString(i));
            KeyStroke shortcut = KeyStroke.getKeyStroke(i == 13 ? KeyEvent.VK_N : i == 12 ? KeyEvent.VK_B : i == 10 ? KeyEvent.VK_R : i == 11 ? KeyEvent.VK_T : i == 9 ? KeyEvent.VK_0 : KeyEvent.VK_1 + i, InputEvent.ALT_DOWN_MASK);
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(shortcut, "page-" + i);
            getActionMap().put("page-" + i, new AbstractAction() { public void actionPerformed(ActionEvent e) { select(index); navigation[index].requestFocusInWindow(); }});
            JRadioButtonMenuItem destination = new JRadioButtonMenuItem(TITLES[i], button.getIcon());
            destination.setName("compact-nav-" + i); destination.setAccelerator(shortcut);
            destination.addActionListener(e -> { select(index); navigation[index].requestFocusInWindow(); });
            destinations[i] = destination; menuGroup.add(destination); // rebuildPopup adds it in sidebar order
        }
        advancedToggle.setName("nav-advanced");
        advancedToggle.setHorizontalAlignment(SwingConstants.LEFT); advancedToggle.setIconTextGap(6);
        advancedToggle.setMargin(new Insets(2, 6, 2, 6)); advancedToggle.setFocusPainted(true);
        advancedToggle.putClientProperty("JComponent.minimumWidth", 0);
        ContentStyle.font(advancedToggle, Type.caption());
        advancedToggle.addActionListener(e -> toggleAdvanced());
        nav.add(advancedToggle); nav.add(navGlue);
        navScroll.setBorder(null); navScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        navScroll.getVerticalScrollBar().setUnitIncrement(34);
        sidebar.add(navScroll, BorderLayout.CENTER);
        settingsRow.setOpaque(false);
        sideFooter.setFont(ContentStyle.metadata(ContentStyle.body())); sideFooter.setBorder(new EmptyBorder(8, 4, 0, 0));
        sideBottom.setOpaque(false);
        sideBottom.add(settingsRow, BorderLayout.NORTH); sideBottom.add(sideFooter, BorderLayout.SOUTH);
        sidebar.add(sideBottom, BorderLayout.SOUTH);
        add(sidebar, BorderLayout.WEST);

        workspace.setName("workspace-content"); workspace.setBorder(new EmptyBorder(12, 12, 10, 12));
        title.setName("page-title");
        title.putClientProperty("html.disable", true);
        ContentStyle.font(title, Type.pageTitle());
        back.setName("navigate-back");
        back.setVisible(false);
        back.addActionListener(e -> navigateBack());
        browseHistory.setName("browse-history");
        browseHistory.setToolTipText("Open every saved session in Runs without starting capture");
        browseHistory.setVisible(browse != null);
        if (browse != null) browseHistory.addActionListener(e -> browse.run());
        previewLabel.setName("preview-chip");
        previewLabel.setToolTipText("Preview · Capture disabled. Saved history is available.");
        previewLabel.setVisible(preview);
        capturePill.setName("capture-pill");
        capturePill.setVisible(!preview);
        for (Component option : modeSwitch.getComponents())
            ((JComponent) option).setToolTipText("Simple hides provenance, IDs and diagnostic tabs; Analyst shows them (Ctrl+Shift+A)");
        modeSwitch.onChange(index -> mode.set(index == 1 ? DisplayModeModel.Mode.ANALYST : DisplayModeModel.Mode.SIMPLE));
        mode.bind(this, value -> modeSwitch.setSelected(value == DisplayModeModel.Mode.ANALYST ? 1 : 0));
        capture.setName("capture-toggle");
        capture.setToolTipText("Start or stop the network sniffer (Ctrl+Shift+S)");
        capture.addActionListener(e -> toggleCapture.run());
        capture.setEnabled(!preview);
        JPanel actions = ContentStyle.controls();
        actions.setName("workspace-actions");
        actions.setOpaque(false);
        for (JComponent action : new JComponent[] {back, browseHistory, previewLabel, capturePill, modeSwitch, capture}) actions.add(action);
        JPanel header = new JPanel(new HeaderLayout(12, 6)) {
            @Override public void setBounds(int x, int y, int width, int height) {
                boolean changed = width != getWidth();
                super.setBounds(x, y, width, height);
                // Whether the actions share the title's line depends on this width; lay out once more with it.
                if (changed) SwingUtilities.invokeLater(this::revalidate);
            }
        };
        header.setName("workspace-header");
        header.setOpaque(false);
        header.add(title); header.add(actions);
        setupMessage.setName("capture-setup-message");
        setupMessage.getAccessibleContext().setAccessibleName("Capture readiness and asset setup");
        JScrollPane setupScroll = new JScrollPane(setupMessage) {
            @Override public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                size.height = Math.min(size.height, setupMessage.getFontMetrics(setupMessage.getFont()).getHeight() * 3 + 8);
                return size;
            }
        };
        setupScroll.setBorder(null); setupScroll.setOpaque(false); setupScroll.getViewport().setOpaque(false);
        setupScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        setupBanner.setName("setup-banner");
        setupBanner.add(setupScroll, BorderLayout.CENTER);
        JPanel setupActions = ContentStyle.controls();
        setupActions.setOpaque(false);
        chooseAssets.setName("choose-assets"); retrySetup.setName("retry-assets");
        chooseAssets.setVisible(choose != null); retrySetup.setVisible(retry != null);
        setupActions.setVisible(choose != null || retry != null);
        if (choose != null) chooseAssets.addActionListener(e -> choose.run());
        if (retry != null) retrySetup.addActionListener(e -> retry.run());
        setupActions.add(chooseAssets); setupActions.add(retrySetup);
        setupBanner.add(setupActions, BorderLayout.SOUTH);
        JPanel headingAndSetup = new JPanel(new BorderLayout(0, 6));
        headingAndSetup.setOpaque(false);
        headingAndSetup.add(header, BorderLayout.NORTH); headingAndSetup.add(setupBanner);
        workspace.add(headingAndSetup, BorderLayout.NORTH);
        setSetupState("Saved capture preference is applied after assets are ready. Saved history is available without capture.", true, false);
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
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK), "navigate-back");
        getActionMap().put("navigate-back", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { navigateBack(); }
        });
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.ALT_DOWN_MASK), "open-navigation");
        getActionMap().put("open-navigation", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { showNavigation(); }
        });
        // Alt+N keeps its page; Alt+, is the conventional settings shortcut (spec §4.1).
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, InputEvent.ALT_DOWN_MASK), "open-settings");
        getActionMap().put("open-settings", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { select(SETTINGS); navigation[SETTINGS].requestFocusInWindow(); }
        });
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "toggle-display-mode");
        getActionMap().put("toggle-display-mode", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { mode.toggle(); }
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
        preferencesStatus.setForeground(Tokens.color(saving.state == PreferencesStore.State.FAILED ? Tokens.Role.BAD : Tokens.Role.TEXT_MUTED));
    }

    /** Refreshes explicit shell colors from the active LAF rather than retaining a dark sidebar. */
    public void refreshTheme() {
        Color background = Tokens.color(Tokens.Role.NAV);
        setBackground(Tokens.color(Tokens.Role.CANVAS));
        sidebar.setBackground(background); nav.setBackground(background);
        // The wash ends at the branding row, so the destinations below it sit on an even backdrop.
        sidebar.wash(Tokens.blend(background, Tokens.color(Tokens.Role.ACCENT_TEXT), .12f), background);
        sidebar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Tokens.color(Tokens.Role.BORDER_SUBTLE)),
            new EmptyBorder(12, compact ? 6 : 8, 10, compact ? 6 : 8)));
        settingsRow.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Tokens.color(Tokens.Role.BORDER_SUBTLE)),
            new EmptyBorder(6, 0, 0, 0)));
        cards.setBackground(Tokens.color(Tokens.Role.CANVAS));
        cards.setBorder(new EmptyBorder(6, 6, 6, 6));
        brand.setForeground(Tokens.color(Tokens.Role.TEXT)); title.setForeground(Tokens.color(Tokens.Role.TEXT));
        mark.setForeground(Tokens.color(Tokens.Role.ACCENT_TEXT));
        for (JLabel label : new JLabel[] {eyebrow, sideFooter, hint, popupAdvanced}) label.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
        status.setForeground(Tokens.color(Tokens.Role.TEXT)); captureFailure.setForeground(Tokens.color(Tokens.Role.BAD));
        setupMessage.setForeground(Tokens.color(Tokens.Role.TEXT));
        refreshPreferencesStatus();
        // KitButton styles the primary action; the fill is also set directly so it holds before the style is reapplied.
        capture.setBackground(Tokens.color(Tokens.Role.PRIMARY));
        styleAdvancedToggle();
        for (int i = 0; i < navigation.length; i++) if (navigation[i] != null) styleNavigation(i);
    }

    private void styleNavigation(int index) {
        boolean current = index == selected;
        navigation[index].setForeground(Tokens.color(current ? Tokens.Role.SELECTION_TEXT : Tokens.Role.TEXT));
        navigation[index].setBackground(Tokens.color(current ? Tokens.Role.SELECTION : Tokens.Role.NAV));
        // Hover and pressed live in the style map, so the background stays the destination's own color.
        Map<String, Object> style = new HashMap<>();
        style.put("selectedBackground", Tokens.color(Tokens.Role.SELECTION));
        style.put("selectedForeground", Tokens.color(Tokens.Role.SELECTION_TEXT));
        style.put("hoverBackground", hover());
        style.put("pressedBackground", Tokens.color(Tokens.Role.ACCENT_WASH));
        // Destinations are a rail, not a stack of buttons, so the resting outline matches its own
        // fill. The real border stays in place, and keyboard focus still recolors it.
        Color rest = Tokens.color(current ? Tokens.Role.SELECTION : Tokens.Role.NAV);
        style.put("borderColor", rest);
        style.put("disabledBorderColor", rest);
        navigation[index].putClientProperty("FlatLaf.style", style);
    }

    /** The Advanced header reads as a quiet group label, with the rows' hover and focus treatment. */
    private void styleAdvancedToggle() {
        Color rest = Tokens.color(Tokens.Role.NAV);
        advancedToggle.setBackground(rest);
        advancedToggle.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
        Map<String, Object> style = new HashMap<>();
        style.put("hoverBackground", hover());
        style.put("pressedBackground", Tokens.color(Tokens.Role.ACCENT_WASH));
        style.put("borderColor", rest);
        style.put("disabledBorderColor", rest);
        advancedToggle.putClientProperty("FlatLaf.style", style);
    }

    /** The pointer-over fill for rail rows: a violet wash over the rail. */
    private static Color hover() {
        return Tokens.blend(Tokens.color(Tokens.Role.NAV), Tokens.color(Tokens.Role.ACCENT_WASH), .6f);
    }

    /**
     * Actions for one destination: reorder, hide or unpin core rows, pin Advanced rows, restore hidden rows
     * and reset. Items that cannot apply stay visible but disabled, so the reason is discoverable.
     */
    JPopupMenu contextMenu(int page) {
        NavEntry entry = NavEntry.forPage(page);
        String id = entry.id();
        JPopupMenu menu = new JPopupMenu(entry.title());
        menu.setName("nav-menu");
        menu.getAccessibleContext().setAccessibleName(entry.title() + " navigation options");
        if (layout.inCore(id)) {
            menu.add(menuItem("nav-menu-move-up", "Move up", layout.canMove(id, -1), () -> moveEntry(page, -1)));
            menu.add(menuItem("nav-menu-move-down", "Move down", layout.canMove(id, 1), () -> moveEntry(page, 1)));
            if (layout.isPinned(id))
                menu.add(menuItem("nav-menu-unpin", "Unpin from top", layout.canUnpin(id), () -> change(() -> layout.unpin(id), page)));
        } else if (entry.group() == NavEntry.Group.ADVANCED) {
            menu.add(menuItem("nav-menu-pin", "Pin to top", true, () -> change(() -> layout.pin(id), page)));
        }
        if (entry.group() != NavEntry.Group.SETTINGS) {
            if (layout.isHidden(id)) menu.add(menuItem("nav-menu-show", "Show in sidebar", true, () -> change(() -> layout.show(id), page)));
            else menu.add(menuItem("nav-menu-hide", "Hide", layout.canHide(id), () -> change(() -> layout.hide(id), page)));
            menu.addSeparator();
        }
        JMenu hidden = new JMenu("Show hidden");
        hidden.setName("nav-menu-show-hidden");
        for (NavEntry item : layout.hidden()) {
            JMenuItem restore = new JMenuItem(item.title(), new LineIcon(item.icon(), 16));
            restore.setName("nav-menu-show-" + item.page());
            restore.addActionListener(e -> change(() -> layout.show(item.id()), item.page()));
            hidden.add(restore);
        }
        hidden.setEnabled(hidden.getItemCount() > 0);
        menu.add(hidden);
        menu.add(menuItem("nav-menu-reset", "Reset navigation", true, () -> change(() -> { layout.reset(); return true; }, page)));
        return menu;
    }

    private void showContextMenu(int page, Point at) {
        JToggleButton button = navigation[page];
        if (!button.isShowing()) return;
        JPopupMenu menu = contextMenu(page);
        Point where = at != null ? at : new Point(0, button.getHeight());
        menu.show(button, where.x, where.y);
        // Keyboard users start on the first available action, as in the compact menu.
        for (Component item : menu.getComponents())
            if (item instanceof JMenuItem && item.isEnabled()) {
                MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[] {menu, (MenuElement) item});
                break;
            }
    }

    private void moveEntry(int page, int delta) {
        change(() -> layout.move(NavEntry.forPage(page).id(), delta), page);
    }

    /** Applies a saved-layout change, then re-lays the rows and keeps focus on the row the user acted on. */
    private void change(BooleanSupplier operation, int focus) {
        if (!operation.getAsBoolean()) return;
        applyLayout();
        JToggleButton target = navigation[focus].isVisible() ? navigation[focus] : navigation[selected];
        target.requestFocusInWindow();
        scrollSelectedLater();
    }

    private static JMenuItem menuItem(String name, String text, boolean enabled, Runnable run) {
        JMenuItem item = new JMenuItem(text);
        item.setName(name);
        item.setEnabled(enabled);
        item.addActionListener(e -> run.run());
        return item;
    }

    private static Action action(Runnable run) {
        return new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { run.run(); } };
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
            if (rail == null) rail = Tokens.color(Tokens.Role.ACCENT_TEXT);
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

    /**
     * Title on the left and actions on the right. When both do not fit on one line, the actions move
     * below the title and wrap there, so no action is clipped at narrow widths or large fonts.
     * Child 0 is the title; child 1 is a {@link ContentStyle#controls()} row.
     */
    private static final class HeaderLayout implements LayoutManager {
        private final int hgap, vgap;

        HeaderLayout(int hgap, int vgap) { this.hgap = hgap; this.vgap = vgap; }

        @Override public void addLayoutComponent(String name, Component component) { }
        @Override public void removeLayoutComponent(Component component) { }

        /** The unwrapped width of the action row, or 0 when every action is hidden. */
        private static int lineWidth(Container actions) {
            FlowLayout flow = (FlowLayout) actions.getLayout();
            Insets insets = actions.getInsets();
            int width = 0, count = 0;
            for (Component child : actions.getComponents()) {
                if (!child.isVisible()) continue;
                width += child.getPreferredSize().width + (count++ == 0 ? 0 : flow.getHgap());
            }
            return count == 0 ? 0 : width + flow.getHgap() * 2 + insets.left + insets.right;
        }

        private static int lineHeight(Container actions) {
            FlowLayout flow = (FlowLayout) actions.getLayout();
            Insets insets = actions.getInsets();
            int height = 0;
            for (Component child : actions.getComponents()) if (child.isVisible()) height = Math.max(height, child.getPreferredSize().height);
            return height + flow.getVgap() * 2 + insets.top + insets.bottom;
        }

        private boolean oneLine(Component title, int line, int width) {
            return line == 0 || title.getPreferredSize().width + hgap + line <= width;
        }

        private static int width(Container parent) {
            if (parent.getWidth() > 0) return parent.getWidth();
            Container outer = parent.getParent();
            return outer == null ? 0 : outer.getWidth() - outer.getInsets().left - outer.getInsets().right;
        }

        @Override public Dimension preferredLayoutSize(Container parent) {
            synchronized (parent.getTreeLock()) {
                Insets insets = parent.getInsets();
                Component title = parent.getComponent(0);
                Container actions = (Container) parent.getComponent(1);
                Dimension text = title.getPreferredSize();
                int line = lineWidth(actions), available = width(parent) - insets.left - insets.right;
                int extraWidth = insets.left + insets.right, extraHeight = insets.top + insets.bottom;
                if (available <= 0 || oneLine(title, line, available))
                    return new Dimension(text.width + (line == 0 ? 0 : hgap + line) + extraWidth,
                        Math.max(text.height, line == 0 ? 0 : lineHeight(actions)) + extraHeight);
                return new Dimension(Math.max(text.width, actions.getPreferredSize().width) + extraWidth,
                    text.height + vgap + actions.getPreferredSize().height + extraHeight);
            }
        }

        @Override public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(0, preferredLayoutSize(parent).height);
        }

        @Override public void layoutContainer(Container parent) {
            synchronized (parent.getTreeLock()) {
                Insets insets = parent.getInsets();
                Component title = parent.getComponent(0);
                Container actions = (Container) parent.getComponent(1);
                int x = insets.left, y = insets.top;
                int width = parent.getWidth() - insets.left - insets.right, height = parent.getHeight() - insets.top - insets.bottom;
                Dimension text = title.getPreferredSize();
                int line = lineWidth(actions);
                if (oneLine(title, line, width)) {
                    int row = Math.max(text.height, line == 0 ? 0 : lineHeight(actions));
                    int actionWidth = Math.min(line, width);
                    title.setBounds(x, y + (row - text.height) / 2, Math.max(0, width - actionWidth - (line == 0 ? 0 : hgap)), text.height);
                    actions.setBounds(x + width - actionWidth, y, actionWidth, row);
                } else {
                    title.setBounds(x, y, width, text.height);
                    actions.setBounds(x, y + text.height + vgap, width, Math.max(0, height - text.height - vgap));
                }
            }
        }
    }

    /** A tinted strip for setup that needs attention: information while busy, a warning when assets are missing. */
    private static final class Banner extends JPanel {
        private Tokens.Tone tone = Tokens.Tone.WARN;

        Banner() {
            super(new BorderLayout(0, 4));
            setOpaque(false);
            setBorder(new EmptyBorder(Tokens.S, Tokens.M, Tokens.S, Tokens.M));
        }

        void setTone(Tokens.Tone value) { tone = value; repaint(); }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Tokens.tint(Tokens.tone(tone)));
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tokens.ARC_CARD, Tokens.ARC_CARD);
            g.setColor(Tokens.tone(tone));
            g.fillRoundRect(0, 0, 3, getHeight() - 1, 3, 3);
            g.dispose();
        }
    }

    /** The capture pill's dot in the chip's own tone: filled while capture is on, hollow when it is off. */
    private static final class DotIcon implements Icon {
        private final boolean filled;

        DotIcon(boolean filled) { this.filled = filled; }

        @Override public int getIconWidth() { return 8; }
        @Override public int getIconHeight() { return 8; }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(component.getForeground());
            if (filled) g.fillOval(x, y, 7, 7);
            else { g.setStroke(new BasicStroke(1.4f)); g.drawOval(x, y, 7, 7); }
            g.dispose();
        }
    }

    public void select(int index) {
        if (index < 0 || index >= navigation.length) throw new IllegalArgumentException("Invalid page");
        selected = index; ((CardLayout) cards.getLayout()).show(cards, Integer.toString(index));
        NavEntry entry = NavEntry.forPage(index);
        title.setText(entry.title());
        // The description lives in the title's tooltip, so the header keeps a single line (spec §4.3).
        title.setToolTipText(entry.description());
        title.getAccessibleContext().setAccessibleDescription(entry.description());
        for (int i = 0; i < navigation.length; i++) {
            navigation[i].setSelected(i == index);
            destinations[i].setSelected(i == index);
            styleNavigation(i);
        }
        applyLayout(); // A hidden or collapsed destination shows while it is the current page.
        scrollSelected(); scrollSelectedLater();
        if (navigator != null) refreshBack(); // The label depends on whether Back returns to this page.
    }
    public int getSelectedPage() { return selected; }

    /** Shows the first visible core destination in the user's order; the app calls this once after startup wiring. */
    public void selectLanding() { select(layout.landing().page()); }

    /** Shell page for a routed destination, or {@link ShellNavigator#NO_PAGE} for dialog destinations. */
    public static int pageOf(Destination destination) {
        switch (destination) {
            case INSPECT: return 2;
            case CHARACTERS: return 3;
            case STATISTICS: return 4;
            case QUESTS: return 5;
            case MY_INFO: return 6;
            case ENCOUNTER: case RESOURCES: return 7;
            case LOOT: return 8;
            case LOGGING: return 9;
            case RUNS: return 10;
            case TIMELINE: return 11;
            case BRIDGE_REVIEW: return 12;
            case NOTIFICATIONS: return 13;
            default: return ShellNavigator.NO_PAGE; // ALERT_DRAFT opens beside the current page.
        }
    }

    /** Creates this shell's navigator; the caller installs it with {@code Navigator.install}. */
    public ShellNavigator createNavigator() {
        ShellNavigator created = new ShellNavigator(this::getSelectedPage, this::select, WorkspaceShell::pageOf, ShellNavigator.DEFAULT_CAPACITY);
        navigator = created;
        created.addChangeListener(this::refreshBack);
        refreshBack();
        return created;
    }

    /** Visible only while a routed origin can be restored; Alt+Left works whenever it is shown. */
    private void refreshBack() {
        boolean available = navigator != null && navigator.canGoBack();
        int page = available ? navigator.backPage() : -1;
        // A route within the current page (for example Runs to a filtered Runs view) returns to that page's earlier
        // view; naming the page the user is already on would read as a no-op.
        String label = page == selected ? BACK_TO_PREVIOUS_VIEW : page >= 0 && page < TITLES.length ? "Back to " + TITLES[page] : "Back";
        back.setText(label); back.getAccessibleContext().setAccessibleName(label);
        back.setToolTipText("Return to the view you came from, with its filters and selection (Alt+Left)");
        if (back.isVisible() != available) { back.setVisible(available); revalidate(); repaint(); }
    }

    private void navigateBack() {
        if (navigator == null || !navigator.canGoBack()) return;
        // Focus follows the restored page, as with the Alt destination shortcuts; the hidden page loses it.
        if (navigator.back()) navigation[selected].requestFocusInWindow();
    }
    public boolean isCompact() { return compact; }
    public void setCaptureState(boolean running) {
        captureRunning = running;
        captureFailed = false;
        readiness = running ? CaptureState.WAITING : CaptureState.STOPPED;
        captureFailure.setText(""); captureFailure.setVisible(false);
        capture.setText(running ? "Stop capture" : "Start capture");
        status.setText(running ? "Waiting for game connection" : "Capture connection stopped");
        hint.setText(running ? "Enter a fresh area or reconnect the game to begin decoding." : "Saved history is available. Start capture when ready.");
        status.setToolTipText(hint.getText());
        updateSetupActions();
    }
    public void setSetupState(String message, boolean ready, boolean busy) {
        assetsReady = ready; setupBusy = busy;
        setupMessage.setText(message);
        updateSetupActions();
    }
    /**
     * The banner appears only when setup needs attention: while assets are being checked, or when they
     * are not ready. Preview never shows it; its chip says capture is disabled.
     */
    private void updateSetupActions() {
        capture.setEnabled(!preview && !setupBusy && (assetsReady || captureRunning));
        chooseAssets.setEnabled(!preview && !setupBusy && !captureRunning);
        retrySetup.setEnabled(!preview && !setupBusy && !captureRunning);
        boolean attention = !preview && (setupBusy || !assetsReady);
        setupBanner.setTone(setupBusy ? Tokens.Tone.INFO : Tokens.Tone.WARN);
        if (setupBanner.isVisible() != attention) { setupBanner.setVisible(attention); revalidate(); repaint(); }
        refreshCapturePill();
    }
    /** One short state and a dot; the footer keeps the detail, and the tooltip carries both. */
    private void refreshCapturePill() {
        String text;
        Tokens.Tone tone;
        boolean on = false;
        if (captureFailed) { text = "Capture error"; tone = Tokens.Tone.BAD; }
        else switch (readiness) {
            case RECEIVING: text = "Capturing"; tone = Tokens.Tone.GOOD; on = true; break;
            case WAITING: text = "Waiting for game"; tone = Tokens.Tone.WARN; on = true; break;
            case STOPPING: text = "Stopping capture"; tone = Tokens.Tone.NEUTRAL; break;
            case NPCAP_UNAVAILABLE: text = "Npcap unavailable"; tone = Tokens.Tone.BAD; break;
            case FAILED: text = "Capture failed"; tone = Tokens.Tone.BAD; break;
            default: text = "Capture off"; tone = Tokens.Tone.NEUTRAL;
        }
        capturePill.setText(text);
        capturePill.setTone(tone);
        capturePill.setIcon(new DotIcon(on));
        capturePill.getAccessibleContext().setAccessibleName("Capture status: " + text);
        String setup = assetsReady && !setupBusy && !setupMessage.getText().isEmpty() ? " · " + setupMessage.getText() : "";
        capturePill.setToolTipText(status.getText() + " · " + hint.getText() + setup);
    }
    public void setCaptureReadiness(CaptureState state) {
        readiness = state;
        status.setText(state.toString());
        if (state == CaptureState.STOPPING) capture.setEnabled(false);
        refreshCapturePill();
    }
    public void setCaptureDetail(String detail) {
        hint.setText(detail);
        hint.setToolTipText(detail);
        status.setToolTipText(detail);
        refreshCapturePill();
    }
    public void setCaptureFailure(String reason) {
        setCaptureState(false);
        captureFailed = true;
        status.setText("Capture stopped — error");
        setCaptureDetail("Restart capture after resolving the error below.");
        captureFailure.setText(reason); captureFailure.setVisible(true);
        refreshCapturePill();
        revalidate(); repaint();
    }
    private void adapt() {
        boolean changed = compact != (getWidth() < 1000);
        compact = getWidth() < 1000;
        ((CardLayout) branding.getLayout()).show(branding, compact ? "menu" : "brand");
        eyebrow.setVisible(!compact); sideFooter.setVisible(!compact);
        workspace.setBorder(compact ? new EmptyBorder(8, 8, 8, 8) : new EmptyBorder(12, 12, 10, 12));
        hint.setVisible(getWidth() >= 820);
        for (int i = 0; i < navigation.length; i++) {
            navigation[i].setText(compact ? "" : TITLES[i]);
            navigation[i].setHorizontalAlignment(compact ? SwingConstants.CENTER : SwingConstants.LEFT);
            navigation[i].setMargin(new Insets(2, compact ? 2 : 6, 2, compact ? 2 : 6));
        }
        advancedToggle.setHorizontalAlignment(compact ? SwingConstants.CENTER : SwingConstants.LEFT);
        advancedToggle.setMargin(new Insets(2, compact ? 2 : 6, 2, compact ? 2 : 6));
        refreshAdvancedToggle();
        if (changed) refreshTheme();
        revalidate(); scrollSelectedLater();
    }

    /**
     * Orders and groups the rows from the saved layout: core rows, then the Advanced header and its
     * rows, with Settings fixed below the list. A hidden or collapsed row still shows while it is the
     * current page. Rows are repositioned rather than re-added, so focus and listeners stay put and the
     * layout-based focus order follows the new positions.
     */
    private void applyLayout() {
        GridBagLayout grid = (GridBagLayout) nav.getLayout();
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0; gc.gridy = 0; gc.weightx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
        grid.setConstraints(eyebrow, gc);
        gc.insets = new Insets(1, 0, 1, 0);
        for (NavEntry entry : layout.coreOrder()) {
            gc.gridy++;
            grid.setConstraints(navigation[entry.page()], gc);
            navigation[entry.page()].setVisible(!layout.isHidden(entry.id()) || entry.page() == selected);
        }
        gc.gridy++; gc.insets = new Insets(10, 0, 1, 0);
        grid.setConstraints(advancedToggle, gc);
        advancedToggle.setVisible(!layout.advanced().isEmpty());
        gc.insets = new Insets(1, 0, 1, 0);
        for (NavEntry entry : layout.advancedOrder()) {
            gc.gridy++;
            grid.setConstraints(navigation[entry.page()], gc);
            boolean listed = layout.advancedOpen() && !layout.isHidden(entry.id());
            navigation[entry.page()].setVisible(listed || entry.page() == selected);
        }
        gc.gridy++; gc.weighty = 1; gc.insets = new Insets(0, 0, 0, 0);
        grid.setConstraints(navGlue, gc);
        refreshAdvancedToggle();
        rebuildPopup();
        nav.revalidate(); nav.repaint();
    }

    /**
     * The compact menu lists every destination in sidebar order and groups: core, then Advanced after a
     * labelled separator, then Settings. Hidden destinations stay attached but invisible, so keyboard
     * traversal skips them and they keep the current look and feel; the current page is always listed.
     */
    private void rebuildPopup() {
        navigationPopup.removeAll();
        for (NavEntry entry : layout.coreOrder()) addDestination(entry);
        navigationPopup.add(advancedSeparator);
        navigationPopup.add(popupAdvanced);
        boolean advanced = false;
        for (NavEntry entry : layout.advancedOrder()) advanced |= addDestination(entry);
        advancedSeparator.setVisible(advanced);
        popupAdvanced.setVisible(advanced);
        navigationPopup.add(settingsSeparator);
        addDestination(layout.settings());
    }

    private boolean addDestination(NavEntry entry) {
        JRadioButtonMenuItem item = destinations[entry.page()];
        item.setVisible(!layout.isHidden(entry.id()) || entry.page() == selected);
        navigationPopup.add(item);
        return item.isVisible();
    }

    private void refreshAdvancedToggle() {
        boolean open = layout.advancedOpen();
        int count = layout.advanced().size();
        advancedToggle.setIcon(new LineIcon(open ? LineIcon.CHEVRON_DOWN : LineIcon.CHEVRON_RIGHT, 14));
        advancedToggle.setText(compact ? "" : "Advanced (" + count + ")");
        advancedToggle.setToolTipText((open ? "Hide" : "Show") + " advanced pages (" + count + ")");
        advancedToggle.getAccessibleContext().setAccessibleName("Advanced pages");
        advancedToggle.getAccessibleContext().setAccessibleDescription(open ? "Expanded" : "Collapsed");
    }

    /** Opens or closes the Advanced group at once and remembers it (no animation; spec §5.8 allows but does not require one). */
    private void toggleAdvanced() {
        layout.setAdvancedOpen(!layout.advancedOpen());
        applyLayout();
        scrollSelectedLater();
    }

    /** The destination's keyboard shortcuts, as its tooltip shows them. */
    private static String shortcutHint(int page) {
        if (page == SETTINGS) return "Alt+, or Alt+N";
        return "Alt+" + (page == 12 ? "B" : page == 10 ? "R" : page == 11 ? "T" : Integer.toString((page + 1) % 10));
    }

    @Override public void doLayout() {
        // Reserve the scrollbar and each row's complete focus border and label/icon at the current font.
        // Every row counts, collapsed and hidden ones too, so opening Advanced never shifts the page.
        Insets insets = sidebar.getInsets(), listInsets = nav.getInsets();
        int rows = Math.max(eyebrow.isVisible() ? eyebrow.getPreferredSize().width : 0, advancedToggle.getPreferredSize().width);
        for (JToggleButton row : navigation) rows = Math.max(rows, row.getPreferredSize().width);
        int width = rows + listInsets.left + listInsets.right + navScroll.getVerticalScrollBar().getPreferredSize().width;
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
