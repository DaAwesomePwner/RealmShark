package tomato.gui.modern;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/** Responsive navigation around the original feature panels; no data is duplicated. */
public final class WorkspaceShell extends JPanel {
    public static final String[] TITLES = {"Chat", "Key-pops", "Security", "Characters", "Statistics", "Daily Quests", "My Info", "DPS Logger"};
    private static final String[] DESCRIPTIONS = {
        "Your conversations across the Realm, in one place.",
        "Follow dungeon openings and configure your notifications.",
        "Inspect players, equipment and ability activity.",
        "Explore your character's exaltations and pets.",
        "Track fame, loot and dungeon progress over time.",
        "Review quests collected from the Daily Quest Room.",
        "Inspect your current character, equipment and damage.",
        "Review encounters, compare damage and fine-tune your filters."
    };
    private static final Color BG = new Color(0x111118), BORDER = new Color(0x2D2B3D);
    private static final Color TEXT = new Color(0xEEEDF7), MUTED = new Color(0xACA8BF), VIOLET = new Color(0xB99AFF);
    private final JPanel sidebar = new JPanel(new BorderLayout());
    private final JPanel cards = new JPanel(new CardLayout());
    private final JToggleButton[] navigation = new JToggleButton[TITLES.length];
    private final JLabel title = new JLabel(), description = new JLabel();
    private final JLabel brand = new JLabel("RealmShark"), eyebrow = new JLabel("WORKSPACE");
    private final JLabel status = new JLabel("Capture is off"), hint = new JLabel("Start capture, then enter the Realm to see activity.");
    private final JLabel sideFooter = new JLabel("Powered by RealmShark");
    private final JButton capture = new JButton("Start capture");
    private final JLabel previewLabel = new JLabel("PREVIEW");
    private boolean compact;
    private int selected;

    public WorkspaceShell(JComponent[] panels, Runnable toggleCapture, boolean preview) {
        super(new BorderLayout());
        if (panels.length != TITLES.length) throw new IllegalArgumentException("All eight feature panels are required");
        setBackground(BG);
        sidebar.setBackground(BG);
        sidebar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, BORDER), new EmptyBorder(22, 12, 16, 12)));
        sidebar.setPreferredSize(new Dimension(210, 0));
        JPanel branding = darkPanel(new BorderLayout(12, 0));
        JLabel mark = new JLabel(new LineIcon(8)); mark.setForeground(VIOLET);
        brand.setFont(new Font("Segoe UI", Font.BOLD, 20)); brand.setForeground(TEXT);
        branding.add(mark, BorderLayout.WEST); branding.add(brand, BorderLayout.CENTER);
        branding.setBorder(new EmptyBorder(0, 8, 26, 0));
        sidebar.add(branding, BorderLayout.NORTH);
        JPanel nav = darkPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints(); gc.gridx = 0; gc.weightx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
        eyebrow.setForeground(MUTED); eyebrow.setFont(new Font("Segoe UI", Font.BOLD, 10));
        eyebrow.setBorder(new EmptyBorder(0, 14, 12, 0)); gc.gridy = 0; nav.add(eyebrow, gc);
        ButtonGroup group = new ButtonGroup();
        for (int i = 0; i < panels.length; i++) {
            final int index = i;
            JToggleButton button = new JToggleButton(TITLES[i], new LineIcon(i));
            button.setName("nav-" + i); button.setToolTipText(TITLES[i] + "  (Alt+" + (i + 1) + ")");
            button.getAccessibleContext().setAccessibleName(TITLES[i]);
            button.setHorizontalAlignment(SwingConstants.LEFT); button.setIconTextGap(13);
            button.setBorder(new EmptyBorder(12, 14, 12, 10)); button.setFocusPainted(true);
            button.putClientProperty("JButton.buttonType", "roundRect");
            button.addActionListener(e -> select(index));
            navigation[i] = button; group.add(button); gc.gridy = i + 1; gc.insets = new Insets(2, 0, 2, 0); nav.add(button, gc);
            cards.add(panels[i], Integer.toString(i));
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_1 + i, InputEvent.ALT_DOWN_MASK), "page-" + i);
            getActionMap().put("page-" + i, new AbstractAction() { public void actionPerformed(ActionEvent e) { select(index); navigation[index].requestFocusInWindow(); }});
        }
        gc.gridy++; gc.weighty = 1; nav.add(Box.createVerticalGlue(), gc);
        JScrollPane navScroll = new JScrollPane(nav); navScroll.setBorder(null); navScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sidebar.add(navScroll, BorderLayout.CENTER);
        sideFooter.setForeground(MUTED); sideFooter.setFont(new Font("Segoe UI", Font.PLAIN, 11)); sideFooter.setBorder(new EmptyBorder(20, 8, 0, 0));
        sidebar.add(sideFooter, BorderLayout.SOUTH);
        add(sidebar, BorderLayout.WEST);

        JPanel workspace = new JPanel(new BorderLayout(0, 20)); workspace.setBorder(new EmptyBorder(24, 24, 16, 24));
        JPanel header = new JPanel(new BorderLayout(16, 0));
        JPanel heading = new JPanel(new BorderLayout(0, 7));
        title.setFont(new Font("Segoe UI", Font.BOLD, 28));
        description.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        heading.add(title, BorderLayout.NORTH); heading.add(description, BorderLayout.CENTER);
        header.add(heading, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        capture.setName("capture-toggle"); capture.putClientProperty("JButton.buttonType", "roundRect");
        capture.putClientProperty("FlatLaf.style", "background: #9063ED; foreground: #FFFFFF; focusedBackground: #A077EF; hoverBackground: #A077EF");
        capture.setToolTipText("Start or stop the network sniffer (Ctrl+Shift+S)");
        capture.addActionListener(e -> toggleCapture.run());
        previewLabel.setForeground(VIOLET); previewLabel.setFont(new Font("Segoe UI", Font.BOLD, 10));
        previewLabel.setVisible(preview); actions.add(previewLabel); actions.add(capture);
        capture.setEnabled(!preview);
        header.add(actions, BorderLayout.EAST); workspace.add(header, BorderLayout.NORTH);
        cards.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER), new EmptyBorder(12, 12, 12, 12)));
        cards.setMinimumSize(new Dimension(0, 0)); workspace.add(cards, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(16, 0));
        status.setFont(new Font("Segoe UI", Font.BOLD, 12));
        hint.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        footer.add(status, BorderLayout.WEST); footer.add(hint, BorderLayout.CENTER); workspace.add(footer, BorderLayout.SOUTH);
        add(workspace, BorderLayout.CENTER);
        addComponentListener(new ComponentAdapter() { @Override public void componentResized(ComponentEvent e) { adapt(); }});
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "capture");
        getActionMap().put("capture", new AbstractAction() { public void actionPerformed(ActionEvent e) { if (capture.isEnabled()) capture.doClick(); }});
        select(0);
    }

    private static JPanel darkPanel(LayoutManager layout) { JPanel panel = new JPanel(layout); panel.setBackground(BG); return panel; }
    public void select(int index) {
        if (index < 0 || index >= navigation.length) throw new IllegalArgumentException("Invalid page");
        selected = index; ((CardLayout) cards.getLayout()).show(cards, Integer.toString(index));
        title.setText(TITLES[index]); description.setText(DESCRIPTIONS[index]);
        for (int i = 0; i < navigation.length; i++) {
            navigation[i].setSelected(i == index);
            navigation[i].setForeground(i == index ? VIOLET : MUTED);
            navigation[i].setBackground(i == index ? new Color(0x30243F) : BG);
        }
    }
    public int getSelectedPage() { return selected; }
    public boolean isCompact() { return compact; }
    public void setCaptureState(boolean running) {
        capture.setText(running ? "Stop capture" : "Start capture");
        status.setText(running ? "Capture enabled" : "Capture is off");
        hint.setText(running ? "Waiting for game traffic or receiving packets." : "Start capture, then enter the Realm to see activity.");
    }
    private void adapt() {
        boolean nextCompact = getWidth() < 1000;
        compact = nextCompact;
        sidebar.setPreferredSize(new Dimension(compact ? 76 : 210, 0));
        brand.setVisible(!compact); eyebrow.setVisible(!compact); sideFooter.setVisible(!compact);
        description.setVisible(getWidth() >= 850); hint.setVisible(getWidth() >= 820);
        for (int i = 0; i < navigation.length; i++) {
            navigation[i].setText(compact ? "" : TITLES[i]);
            navigation[i].setHorizontalAlignment(compact ? SwingConstants.CENTER : SwingConstants.LEFT);
        }
        revalidate();
    }
}
