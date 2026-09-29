package tomato.gui.modern;

import java.awt.*;
import java.awt.dnd.DragSource;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    /** Settings sits below the scrolling destination list, so it is always in reach. */
    private static final String SETTINGS = "settings";
    /** Shells open on Chat; the app then shows the landing page ({@link #selectLanding}). */
    private static final String INITIAL = "chat";
    /** Row accessible descriptions: the keyboard alternatives to dragging (WCAG 2.2 SC 2.5.7); the tooltips keep title and Alt key. */
    private static final String MOVE_HINT = "Ctrl+Shift+Up or Down to move; Shift+F10 for options", MENU_HINT = "Shift+F10 for options";
    /** The sidebar drag's line thickness, and the band at the list's top and bottom edges (half a row) that autoscrolls. */
    private static final int DROP_LINE = 2, AUTOSCROLL_EDGE = 16;
    private final NavLayout layout;
    private final DisplayModeModel mode;
    private final Sidebar sidebar = new Sidebar();
    private final JPanel workspace = new JPanel(new BorderLayout(0, 8));
    private final JPanel branding = new JPanel(new CardLayout());
    /** The destination list; while a row is dragged it paints the drop line over the rows, in the accent resolved at paint time. */
    private final JPanel nav = new JPanel(new GridBagLayout()) {
        @Override protected void paintChildren(Graphics graphics) {
            super.paintChildren(graphics);
            Rectangle line = dropLine;
            if (line == null) return;
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Tokens.color(Tokens.Role.ACCENT));
            g.fillRoundRect(line.x, line.y, line.width, line.height, DROP_LINE, DROP_LINE);
            g.dispose();
        }
    };
    /**
     * The destination list (and the compact rail) has no box. The look and feel reinstalls a scroll pane border on every UI update, and a
     * live theme switch updates this child after the shell's own {@link #refreshTheme}, so the list clears it again after each update.
     */
    private final JScrollPane navScroll = new JScrollPane(nav) {
        @Override public void updateUI() { super.updateUI(); setBorder(null); }
    };
    private final Component navGlue = Box.createVerticalGlue();
    private final JButton advancedToggle = new JButton();
    private final JPanel settingsRow = new JPanel(new BorderLayout());
    private final JPanel sideBottom = new JPanel(new BorderLayout());
    private final JPanel cards = ContentStyle.card(new CardLayout());
    // Pages, sidebar rows and compact-menu items by destination ID, in NavEntry order.
    private final Map<String, JComponent> pages = new LinkedHashMap<>();
    private final Map<String, JToggleButton> navigation = new LinkedHashMap<>();
    private final Map<String, JRadioButtonMenuItem> destinations = new LinkedHashMap<>();
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
    private String selected = INITIAL;
    private JToggleButton scrollAnchor;
    private boolean scrollPending;
    private final RowDrag drag = new RowDrag();
    /** The drop line in {@link #nav} coordinates while a drag has somewhere to drop, else null. */
    private Rectangle dropLine;
    private final Timer autoscroll = new Timer(40, e -> autoscrollStep());

    public WorkspaceShell(Map<String, ? extends JComponent> panels, Runnable toggleCapture, boolean preview) {
        this(panels, toggleCapture, preview, null, null, null);
    }

    public WorkspaceShell(Map<String, ? extends JComponent> panels, Runnable toggleCapture, boolean preview,
                          Runnable choose, Runnable retry, Runnable browse) {
        this(panels, toggleCapture, preview, choose, retry, browse, new NavLayout());
    }

    /** As above, with the sidebar arrangement read from and saved to {@code layout}; tests pass an in-memory one. */
    public WorkspaceShell(Map<String, ? extends JComponent> panels, Runnable toggleCapture, boolean preview,
                          Runnable choose, Runnable retry, Runnable browse, NavLayout layout) {
        this(panels, toggleCapture, preview, choose, retry, browse, layout, DisplayModeModel.application());
    }

    /**
     * The full form: tests pass in-memory layout and display-mode models so they never touch saved preferences. {@code panels}
     * holds one page per {@link NavEntry} ID, no more and no fewer.
     */
    public WorkspaceShell(Map<String, ? extends JComponent> panels, Runnable toggleCapture, boolean preview,
                          Runnable choose, Runnable retry, Runnable browse, NavLayout layout, DisplayModeModel mode) {
        super(new BorderLayout());
        this.preview = preview;
        this.layout = Objects.requireNonNull(layout, "layout");
        this.mode = Objects.requireNonNull(mode, "mode");
        Set<String> ids = new LinkedHashSet<>();
        for (NavEntry entry : NavEntry.defaults()) ids.add(entry.id());
        if (!panels.keySet().equals(ids)) throw new IllegalArgumentException("All feature panels are required");
        for (String id : ids) pages.put(id, panels.get(id));
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
        // CardLayout leaves its first card showing; the initial page goes first, so no other page is shown and hidden before it.
        cards.add(pages.get(INITIAL), INITIAL);
        for (NavEntry entry : NavEntry.defaults()) {
            final String id = entry.id();
            JToggleButton button = new NavigationButton(entry.title(), new LineIcon(entry.icon()));
            button.setFont(ContentStyle.body());
            button.setName("nav-" + id); button.setToolTipText(entry.title() + shortcutHint(entry));
            button.getAccessibleContext().setAccessibleName(entry.title());
            button.setHorizontalAlignment(SwingConstants.LEFT); button.setIconTextGap(8);
            // FlatLaf paints keyboard focus in its border, even with explicit navigation colors.
            button.setMargin(new Insets(2, 6, 2, 6)); button.setFocusPainted(true);
            button.putClientProperty("JComponent.minimumWidth", 0);
            button.addActionListener(e -> select(id));
            button.addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) {
                    scrollAnchor = button;
                    scrollSelectedLater();
                }
            });
            // Right-click, Shift+F10 or the context-menu key opens the row's menu; Ctrl+Shift+Up/Down moves it.
            button.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { if (e.isPopupTrigger()) showContextMenu(id, e.getPoint()); }
                @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) showContextMenu(id, e.getPoint()); }
            });
            InputMap keys = button.getInputMap(WHEN_FOCUSED);
            keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK), "nav-menu");
            keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0), "nav-menu");
            keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "nav-move-up");
            keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "nav-move-down");
            button.getActionMap().put("nav-menu", action(() -> showContextMenu(id, null)));
            button.getActionMap().put("nav-move-up", action(() -> moveEntry(id, -1)));
            button.getActionMap().put("nav-move-down", action(() -> moveEntry(id, 1)));
            // Listed core rows also drag (RowDrag); Esc cancels a drag and otherwise falls through, its action disabled.
            button.addMouseListener(drag); button.addMouseMotionListener(drag); button.addFocusListener(drag);
            keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "nav-drag-cancel");
            button.getActionMap().put("nav-drag-cancel", drag.cancel);
            navigation.put(id, button); group.add(button);
            // applyLayout places the rows in the user's order; Settings stays below the scrolling list.
            if (id.equals(SETTINGS)) settingsRow.add(button); else nav.add(button);
            if (!id.equals(INITIAL)) cards.add(pages.get(id), id);
            // The Alt key comes from the NavEntry, so it follows the ID; bindShortcut may give the key another action.
            KeyStroke shortcut = entry.shortcut() == 0 ? null : KeyStroke.getKeyStroke(entry.shortcut(), InputEvent.ALT_DOWN_MASK);
            if (shortcut != null) getInputMap(WHEN_IN_FOCUSED_WINDOW).put(shortcut, "page-" + id);
            getActionMap().put("page-" + id, action(() -> { select(id); focusPage(id); }));
            JRadioButtonMenuItem destination = new JRadioButtonMenuItem(entry.title(), button.getIcon());
            destination.setName("compact-nav-" + id); destination.setAccelerator(shortcut);
            destination.addActionListener(e -> { select(id); focusPage(id); });
            destinations.put(id, destination); menuGroup.add(destination); // rebuildPopup adds it in sidebar order
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
            // Borderless after a live theme switch too, as the destination list.
            @Override public void updateUI() { super.updateUI(); setBorder(null); }
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
        cards.setName("workspace-cards");
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
            @Override public void actionPerformed(ActionEvent e) { select(SETTINGS); navigation.get(SETTINGS).requestFocusInWindow(); }
        });
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "toggle-display-mode");
        getActionMap().put("toggle-display-mode", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { mode.toggle(); }
        });
        refreshTheme();
        select(INITIAL);
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
        drag.end();
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
        for (String id : navigation.keySet()) styleNavigation(id);
    }

    private void styleNavigation(String id) {
        boolean current = id.equals(selected);
        JToggleButton row = navigation.get(id);
        row.setForeground(Tokens.color(current ? Tokens.Role.SELECTION_TEXT : Tokens.Role.TEXT));
        row.setBackground(Tokens.color(current ? Tokens.Role.SELECTION : Tokens.Role.NAV));
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
        row.putClientProperty("FlatLaf.style", style);
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
    JPopupMenu contextMenu(String page) {
        NavEntry entry = entry(page);
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
        // Settings is always listed, so only core and Advanced rows hide.
        if (entry.group() == NavEntry.Group.CORE || entry.group() == NavEntry.Group.ADVANCED) {
            if (layout.isHidden(id)) menu.add(menuItem("nav-menu-show", "Show in sidebar", true, () -> change(() -> layout.show(id), page)));
            else menu.add(menuItem("nav-menu-hide", "Hide", layout.canHide(id), () -> change(() -> layout.hide(id), page)));
            menu.addSeparator();
        }
        JMenu hidden = new JMenu("Show hidden");
        hidden.setName("nav-menu-show-hidden");
        for (NavEntry item : layout.hidden()) {
            JMenuItem restore = new JMenuItem(item.title(), new LineIcon(item.icon(), 16));
            restore.setName("nav-menu-show-" + item.id());
            restore.addActionListener(e -> change(() -> layout.show(item.id()), item.id()));
            hidden.add(restore);
        }
        hidden.setEnabled(hidden.getItemCount() > 0);
        menu.add(hidden);
        menu.add(menuItem("nav-menu-reset", "Reset navigation", true, () -> change(() -> { layout.reset(); return true; }, page)));
        return menu;
    }

    private void showContextMenu(String page, Point at) {
        JToggleButton button = navigation.get(page);
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

    private void moveEntry(String page, int delta) {
        change(() -> layout.move(entry(page).id(), delta), page);
    }

    /**
     * Applies a saved-layout change, then re-lays the rows and keeps focus on the row the user acted on. When that row is
     * hidden now (Hide), focus goes where page navigation puts it ({@link #focusPage}); a hidden row never anchors scrolling.
     */
    private void change(BooleanSupplier operation, String focus) {
        if (!operation.getAsBoolean()) return;
        applyLayout();
        JToggleButton row = navigation.get(focus), current = navigation.get(selected);
        if (row.isVisible()) { scrollAnchor = row; row.requestFocusInWindow(); }
        else { scrollAnchor = current.isVisible() ? current : null; focusPage(selected); }
        scrollSelectedLater();
    }

    /** The drop line of the drag in progress, in the destination list's coordinates, or null (tests). */
    Rectangle dropIndicator() { return dropLine == null ? null : new Rectangle(dropLine); }

    /**
     * One autoscroll step, half a scroll unit, while a drag holds the pointer near the list's top or bottom edge; the drop then
     * follows the same pointer over the moved rows. True when the list moved; the timer stops at either end (tests call it).
     */
    boolean autoscrollStep() {
        int direction = drag.edgeDirection();
        JViewport viewport = navScroll.getViewport();
        Point at = viewport.getViewPosition();
        int end = Math.max(0, viewport.getViewSize().height - viewport.getExtentSize().height);
        int y = Math.max(0, Math.min(end, at.y + direction * Math.max(1, navScroll.getVerticalScrollBar().getUnitIncrement() / 2)));
        if (direction == 0 || y == at.y) { autoscroll.stop(); return false; }
        viewport.setViewPosition(new Point(at.x, y));
        drag.update();
        return true;
    }

    /** Moves the drop line, repainting only the old and the new line. */
    private void showDropLine(Rectangle line) {
        if (Objects.equals(line, dropLine)) return;
        if (dropLine != null) nav.repaint(dropLine.x - 1, dropLine.y - 1, dropLine.width + 2, dropLine.height + 2);
        dropLine = line;
        if (line != null) nav.repaint(line.x - 1, line.y - 1, line.width + 2, line.height + 2);
    }

    /** The destination ID of a sidebar row, or null. */
    private String rowId(Component row) {
        for (Map.Entry<String, JToggleButton> entry : navigation.entrySet()) if (entry.getValue() == row) return entry.getKey();
        return null;
    }

    /**
     * Drags a listed core row, pinned Advanced rows included, to a new place (spec §4.1). The whole row drags once the left button
     * moves it the system drag threshold ({@link DragSource#getDragThreshold()}, 5 px by default); a line marks the gap under the
     * pointer, and the release drops the row there through {@link NavLayout#moveTo}: one ORDER write, the page unchanged, the
     * moved row focused and in view. Below the core rows (over Advanced) it drops last, so a drop never pins. Esc, losing focus
     * or a release beside the sidebar cancels and writes nothing. One instance listens on every row, the compact rail included;
     * Ctrl+Shift+Up/Down and the row menu stay the keyboard and single-pointer alternatives (WCAG 2.2 SC 2.5.7).
     */
    private final class RowDrag extends MouseAdapter implements FocusListener {
        /** Esc on a row; enabled only while dragging, so the key otherwise reaches the page. */
        final Action cancel = action(this::end);
        /** The pressed row that may drag, its ID and the press point in its coordinates; null without such a press. */
        private JToggleButton row;
        private String id;
        private Point pressed;
        /** The pointer in viewport coordinates, which stay put while the list scrolls under it. */
        private Point pointer;
        private boolean dragging, beside;
        /** The row's final index among the visible core rows at the pointer, or -1 where a release drops nothing. */
        private int index = -1;

        RowDrag() { cancel.setEnabled(false); }

        boolean active() { return dragging; }

        @Override public void mousePressed(MouseEvent e) {
            end(); // another button during a drag cancels it
            if (!SwingUtilities.isLeftMouseButton(e) || e.isPopupTrigger()) return;
            String page = rowId(e.getComponent());
            // Settings, unpinned Advanced rows and a hidden current page (listed while current) keep their places.
            if (page == null || !layout.inCore(page) || layout.isHidden(page)) return;
            row = (JToggleButton) e.getComponent(); id = page; pressed = e.getPoint();
        }

        @Override public void mouseDragged(MouseEvent e) {
            if (row == null || e.getComponent() != row) return;
            if (!dragging) {
                int threshold = DragSource.getDragThreshold();
                if (!SwingUtilities.isLeftMouseButton(e)
                    || Math.abs(e.getX() - pressed.x) < threshold && Math.abs(e.getY() - pressed.y) < threshold) return;
                dragging = true;
                // The button selects on release while armed, and a drag back over the row re-arms a pressed model: release both.
                row.getModel().setArmed(false); row.getModel().setPressed(false);
                row.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                cancel.setEnabled(true);
            }
            pointer = SwingUtilities.convertPoint(row, e.getPoint(), navScroll.getViewport());
            update();
        }

        @Override public void mouseReleased(MouseEvent e) {
            if (row == null || e.getComponent() != row) return;
            if (!dragging) { end(); return; } // a click: the button selects the page
            pointer = SwingUtilities.convertPoint(row, e.getPoint(), navScroll.getViewport());
            update();
            String moved = id;
            int at = index;
            end();
            if (at >= 0) change(() -> layout.moveTo(moved, at), moved);
        }

        @Override public void focusGained(FocusEvent e) { }
        @Override public void focusLost(FocusEvent e) { if (e.getComponent() == row) end(); }

        /**
         * The drop at the pointer. Gap g counts the visible core rows whose centre lies above the pointer (0…n, so any point below
         * the core rows is n); the row's final index is g - 1 past its own place, else g, and its own place shows no line.
         */
        void update() {
            List<JToggleButton> rows = new ArrayList<>();
            int from = -1;
            for (NavEntry entry : layout.core()) {
                if (entry.id().equals(id)) from = rows.size();
                rows.add(navigation.get(entry.id()));
            }
            if (from < 0) { end(); return; } // the row left the list mid-drag (hidden from the keyboard)
            JViewport viewport = navScroll.getViewport();
            Point inSidebar = SwingUtilities.convertPoint(viewport, pointer, sidebar);
            beside = inSidebar.x < 0 || inSidebar.x >= sidebar.getWidth();
            Rectangle line = null;
            index = -1;
            if (!beside) {
                int y = SwingUtilities.convertPoint(viewport, pointer, nav).y, gap = 0;
                for (JToggleButton each : rows) if (each.getY() + each.getHeight() / 2 < y) gap++;
                boolean down = gap > from;
                int at = down ? gap - 1 : gap;
                if (at != from) {
                    index = at;
                    // Under row g-1 dragging down, over row g dragging up; the two differ only around a hidden current row, and
                    // each is where move() puts the row. Listed rows sit DROP_LINE apart, so the line fills the gap between two.
                    JToggleButton edge = rows.get(at);
                    int lineY = down ? edge.getY() + edge.getHeight() : edge.getY() - DROP_LINE;
                    line = new Rectangle(edge.getX() + 2, Math.max(0, lineY), Math.max(1, edge.getWidth() - 4), DROP_LINE);
                }
            }
            showDropLine(line);
            if (edgeDirection() == 0) autoscroll.stop();
            else if (!autoscroll.isRunning()) autoscroll.start();
        }

        /** -1 or 1 while a drag holds the pointer within {@link #AUTOSCROLL_EDGE} of the list's top or bottom edge, else 0. */
        int edgeDirection() {
            if (!dragging || beside || pointer == null) return 0;
            int height = navScroll.getViewport().getHeight();
            return pointer.y < AUTOSCROLL_EDGE ? -1 : pointer.y >= height - AUTOSCROLL_EDGE ? 1 : 0;
        }

        /** Ends a press or drag without a drop: no write, no line, no autoscroll, and the release is ignored. */
        void end() {
            if (row != null && dragging) row.setCursor(null);
            row = null; id = null; pressed = null; pointer = null;
            dragging = false; beside = false; index = -1;
            cancel.setEnabled(false);
            autoscroll.stop();
            showDropLine(null);
        }
    }

    /** The row the sidebar keeps in view after a change, or null (tests). */
    JToggleButton scrollAnchor() { return scrollAnchor; }

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
        // Every destination has a menu item, and the current page's shows even while hidden or collapsed, so keyboard selection
        // starts on it.
        Component row = navigation.get(selected);
        Component anchor = compact ? compactNavigation : row.isShowing() ? row : navScroll;
        navigationPopup.show(anchor, 0, anchor == navScroll ? 0 : anchor.getHeight());
        MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[] {navigationPopup, destinations.get(selected)});
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

    /** Shows the page with this {@link NavEntry} ID; an ID this shell does not have is rejected without any change. */
    public void select(String id) {
        NavEntry entry = entry(id);
        selected = id; ((CardLayout) cards.getLayout()).show(cards, id);
        scrollAnchor = navigation.get(id);
        title.setText(entry.title());
        // The description lives in the title's tooltip, so the header keeps a single line (spec §4.3).
        title.setToolTipText(entry.description());
        title.getAccessibleContext().setAccessibleDescription(entry.description());
        for (String page : navigation.keySet()) {
            navigation.get(page).setSelected(page.equals(id));
            destinations.get(page).setSelected(page.equals(id));
            styleNavigation(page);
        }
        applyLayout(); // A hidden or collapsed destination shows while it is the current page.
        scrollSelected(); scrollSelectedLater();
        if (navigator != null) refreshBack(); // The label depends on whether Back returns to this page.
    }
    /** The {@link NavEntry} ID of the page shown. */
    public String selectedPage() { return selected; }

    /** Shows the first visible core destination in the user's order; the app calls this once after startup wiring. */
    public void selectLanding() { select(layout.landing().id()); }

    /** The page ({@link NavEntry} ID) of a routed destination, or {@link ShellNavigator#NO_PAGE} for dialog destinations. */
    public static String pageOf(Destination destination) {
        switch (destination) {
            case INSPECT: return "party";
            case CHARACTERS: case CHARACTER_SHEET: return "characters"; // The sheet is a card on the Characters Roster tab.
            case QUESTS: return "quests";
            // Build is a tab of the character sheet: BuildRoute redirects to the sheet, or with no character to the Characters
            // list, so a Build route always lands on Characters, and every routed destination keeps a real page.
            case MY_INFO: return "characters";
            // The live meter (with Resources & buffs nested in it) is the Live meter tab of Runs & DPS.
            case ENCOUNTER: case RESOURCES: return "runs";
            case LOOT: return "loot";
            case LOGGING: return "logging";
            case RUNS: case RUN_RECAP: return "runs"; // The recap is a card on the Runs page, the Feed tab of Runs & DPS.
            case TIMELINE: return "timeline";
            case BRIDGE_REVIEW: return "bridge-review";
            case NOTIFICATIONS: return "settings";
            case HOME: return "home";
            default: return ShellNavigator.NO_PAGE; // ALERT_DRAFT opens beside the current page.
        }
    }

    /**
     * Binds Alt+{@code keyCode}, anywhere in this shell's focused window, to {@code action} under {@code actionName}. A page's own
     * Alt key, or an earlier binding, for that key is replaced; this is for keys that open a route rather than a page.
     */
    public void bindShortcut(int keyCode, String actionName, Runnable action) {
        if (keyCode <= 0) throw new IllegalArgumentException("A shortcut needs a key: " + keyCode);
        Objects.requireNonNull(actionName, "actionName"); Objects.requireNonNull(action, "action");
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(keyCode, InputEvent.ALT_DOWN_MASK), actionName);
        getActionMap().put(actionName, action(action));
    }

    /** Creates this shell's navigator; the caller installs it with {@code Navigator.install}. */
    public ShellNavigator createNavigator() {
        ShellNavigator created = new ShellNavigator(this::selectedPage, this::select, WorkspaceShell::pageOf, ShellNavigator.DEFAULT_CAPACITY);
        navigator = created;
        created.addChangeListener(this::refreshBack);
        refreshBack();
        return created;
    }

    /** Visible only while a routed origin can be restored; Alt+Left works whenever it is shown. */
    private void refreshBack() {
        boolean available = navigator != null && navigator.canGoBack();
        String page = available ? navigator.backPage() : ShellNavigator.NO_PAGE;
        NavEntry origin = page == null ? null : NavEntry.forId(page);
        // A route within the current page (for example Runs to a filtered Runs view) returns to that page's earlier
        // view; naming the page the user is already on would read as a no-op.
        String label = page != null && page.equals(selected) ? BACK_TO_PREVIOUS_VIEW : origin != null ? "Back to " + origin.title() : "Back";
        back.setText(label); back.getAccessibleContext().setAccessibleName(label);
        back.setToolTipText("Return to the view you came from, with its filters and selection (Alt+Left)");
        if (back.isVisible() != available) { back.setVisible(available); revalidate(); repaint(); }
    }

    private void navigateBack() {
        if (navigator == null || !navigator.canGoBack()) return;
        // Focus follows the restored page, as with the Alt destination shortcuts; the hidden page loses it.
        if (navigator.back()) focusPage(selected);
    }

    /** Keyboard focus for the page just shown ({@link #focusTarget}). */
    private void focusPage(String page) { focusTarget(page).requestFocusInWindow(); }

    /**
     * Where page navigation puts keyboard focus: the page's sidebar row while it is visible; otherwise (a hidden row) the page's
     * first focusable component in traversal order, else the page container.
     */
    Component focusTarget(String page) {
        JToggleButton row = navigation.get(entry(page).id()); // an ID with no page is an IllegalArgumentException
        if (row.isVisible()) return row;
        Container root = cards.getFocusCycleRootAncestor();
        FocusTraversalPolicy policy = root == null ? null : root.getFocusTraversalPolicy();
        Component first = policy == null ? null : policy.getFirstComponent(pages.get(page));
        return first != null ? first : cards;
    }

    /** The destination {@code id}; an ID this shell has no page for is an {@code IllegalArgumentException}. */
    private NavEntry entry(String id) {
        NavEntry entry = id == null || !pages.containsKey(id) ? null : NavEntry.forId(id);
        if (entry == null) throw new IllegalArgumentException("Invalid page");
        return entry;
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
        for (NavEntry entry : NavEntry.defaults()) {
            JToggleButton row = navigation.get(entry.id());
            row.setText(compact ? "" : entry.title());
            row.setHorizontalAlignment(compact ? SwingConstants.CENTER : SwingConstants.LEFT);
            row.setMargin(new Insets(2, compact ? 2 : 6, 2, compact ? 2 : 6));
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
            JToggleButton row = navigation.get(entry.id());
            grid.setConstraints(row, gc);
            row.setVisible(!layout.isHidden(entry.id()) || entry.id().equals(selected));
        }
        gc.gridy++; gc.insets = new Insets(10, 0, 1, 0);
        grid.setConstraints(advancedToggle, gc);
        advancedToggle.setVisible(!layout.advanced().isEmpty());
        gc.insets = new Insets(1, 0, 1, 0);
        for (NavEntry entry : layout.advancedOrder()) {
            gc.gridy++;
            JToggleButton row = navigation.get(entry.id());
            grid.setConstraints(row, gc);
            boolean listed = layout.advancedOpen() && !layout.isHidden(entry.id());
            row.setVisible(listed || entry.id().equals(selected));
        }
        gc.gridy++; gc.weighty = 1; gc.insets = new Insets(0, 0, 0, 0);
        grid.setConstraints(navGlue, gc);
        // Listed core rows move (drag, Ctrl+Shift+Up/Down, the menu); the others only have their menu.
        for (Map.Entry<String, JToggleButton> each : navigation.entrySet())
            each.getValue().getAccessibleContext().setAccessibleDescription(
                layout.inCore(each.getKey()) && !layout.isHidden(each.getKey()) ? MOVE_HINT : MENU_HINT);
        refreshAdvancedToggle();
        rebuildPopup();
        nav.revalidate(); nav.repaint();
    }

    /**
     * The compact menu lists every sidebar destination in sidebar order and groups: core, then Advanced after
     * a labelled separator, then Settings. Hidden destinations stay attached but invisible, so keyboard
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
        JRadioButtonMenuItem item = destinations.get(entry.id());
        item.setVisible(!layout.isHidden(entry.id()) || entry.id().equals(selected));
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

    /** The destination's keyboard shortcuts as its tooltip shows them after the title, or nothing when it has none. */
    private static String shortcutHint(NavEntry entry) {
        if (entry.shortcut() == 0) return "";
        String key = "Alt+" + (char) entry.shortcut(); // VK_0..VK_9 and VK_A..VK_Z are their ASCII characters.
        return "  (" + (entry.id().equals(SETTINGS) ? "Alt+, or " + key : key) + ")";
    }

    @Override public void doLayout() {
        // Reserve the scrollbar and each row's complete focus border and label/icon at the current font.
        // Every row counts, collapsed and hidden ones too, so opening Advanced never shifts the page.
        Insets insets = sidebar.getInsets(), listInsets = nav.getInsets();
        int rows = Math.max(eyebrow.isVisible() ? eyebrow.getPreferredSize().width : 0, advancedToggle.getPreferredSize().width);
        for (JToggleButton row : navigation.values()) rows = Math.max(rows, row.getPreferredSize().width);
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
        // A drag keeps the list where the user and autoscroll put it; its drop anchors the moved row (change()).
        if (drag.active()) return;
        // Customizing a different row must not scroll its keyboard focus back to the selected page.
        JToggleButton button = scrollAnchor != null && scrollAnchor.isVisible() ? scrollAnchor : navigation.get(selected);
        if (!button.isVisible()) return; // a row that is not shown has nothing to reveal; the list stays where the user left it.
        button.scrollRectToVisible(new Rectangle(0, 0, button.getWidth(), button.getHeight()));
    }

    private void scrollSelectedLater() {
        if (scrollPending) return;
        scrollPending = true;
        SwingUtilities.invokeLater(() -> { scrollPending = false; scrollSelected(); });
    }
}
