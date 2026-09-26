package tomato.gui.kit;

import java.awt.*;
import java.awt.event.*;
import java.util.Arrays;
import java.util.Objects;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.modern.LineIcon;

/** A rounded surface with optional header, footer, drill-down action and Evidence disclosure. */
public class Card extends JPanel {
    private final DisplayModeModel mode;
    private final JPanel top = new JPanel(new BorderLayout(Tokens.S, 0));
    private final JPanel center = new JPanel(new BorderLayout(0, Tokens.S));
    private SectionHeader header;
    private EvidenceNote evidence;
    private KitButton evidenceToggle;
    private Runnable open;
    private boolean hovered;
    /** Children with tooltips receive their own mouse events, so clicks are listened for on every non-button descendant. */
    private final MouseAdapter clicks = new MouseAdapter() {
        @Override public void mouseClicked(MouseEvent e) { if (open != null && SwingUtilities.isLeftMouseButton(e)) open.run(); }
        @Override public void mouseEntered(MouseEvent e) { setHovered(true); }
        @Override public void mouseExited(MouseEvent e) {
            setHovered(contains(SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), Card.this)));
        }
    };
    private final ContainerListener adoption = new ContainerAdapter() {
        @Override public void componentAdded(ContainerEvent e) { if (open != null) listen(e.getChild()); }
    };

    public Card() { this(DisplayModeModel.application()); }

    public Card(DisplayModeModel mode) {
        super(new BorderLayout(0, Tokens.S));
        this.mode = mode;
        setOpaque(false);
        setBorder(new EmptyBorder(Tokens.M, Tokens.M, Tokens.M, Tokens.M));
        top.setOpaque(false);
        center.setOpaque(false);
        top.setVisible(false);
        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
    }

    public Card title(String title) {
        if (header == null) {
            header = new SectionHeader(title);
            top.add(header, BorderLayout.CENTER);
            top.setVisible(true);
        } else {
            header.setTitle(title);
        }
        return this;
    }

    public SectionHeader header() {
        if (header == null) title("");
        return header;
    }

    public Card body(JComponent body) { center.add(body, BorderLayout.CENTER); return this; }
    public Card footer(JComponent footer) { add(footer, BorderLayout.SOUTH); return this; }

    /**
     * Makes the whole card a drill-down target: click (including on labels, tiles and slots inside it),
     * Enter or Space. Buttons inside the card keep their own action.
     */
    public Card onOpen(String accessibleName, Runnable action) {
        open = Objects.requireNonNull(action, "action");
        setFocusable(true);
        getAccessibleContext().setAccessibleName(accessibleName);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        listen(this);
        InputMap keys = getInputMap(WHEN_FOCUSED);
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open-card");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "open-card");
        getActionMap().put("open-card", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { open.run(); }
        });
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { repaint(); }
            @Override public void focusLost(FocusEvent e) { repaint(); }
        });
        return this;
    }

    /** Full provenance behind an info toggle; expanded by default in Analyst mode. */
    public Card evidence(String text) {
        if (evidence == null) {
            evidence = new EvidenceNote(text);
            evidenceToggle = KitButton.icon(new LineIcon(LineIcon.INFO, 16), "Show evidence");
            evidenceToggle.setName("card-evidence");
            evidenceToggle.addActionListener(e -> showEvidence(!evidence.isVisible()));
            header().actions().add(evidenceToggle);
            center.add(evidence, BorderLayout.SOUTH);
            mode.bind(this, value -> showEvidence(value == DisplayModeModel.Mode.ANALYST));
        } else {
            evidence.setText(text);
        }
        return this;
    }

    public boolean evidenceShown() { return evidence != null && evidence.isVisible(); }

    private void listen(Component component) {
        if (component instanceof AbstractButton || component instanceof javax.swing.text.JTextComponent
                || component instanceof JComboBox || component instanceof JList || component instanceof JTable
                || component instanceof JTree || component instanceof JSlider || component instanceof JSpinner) return;
        if (!Arrays.asList(component.getMouseListeners()).contains(clicks)) component.addMouseListener(clicks);
        if (component instanceof Container) {
            Container container = (Container) component;
            if (!Arrays.asList(container.getContainerListeners()).contains(adoption)) container.addContainerListener(adoption);
            for (Component child : container.getComponents()) listen(child);
        }
    }

    private void setHovered(boolean value) {
        if (hovered == value) return;
        hovered = value;
        repaint();
    }

    private void showEvidence(boolean shown) {
        evidence.setVisible(shown);
        String label = shown ? "Hide evidence" : "Show evidence";
        evidenceToggle.setToolTipText(label);
        evidenceToggle.getAccessibleContext().setAccessibleName(label);
        revalidate();
        repaint();
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int width = getWidth() - 1, height = getHeight() - 1;
        Color surface = Tokens.color(Tokens.Role.SURFACE);
        g.setColor(hovered ? Tokens.blend(surface, Tokens.color(Tokens.Role.ACCENT_WASH), .6f) : surface);
        g.fillRoundRect(0, 0, width, height, Tokens.ARC_CARD, Tokens.ARC_CARD);
        if (open != null && isFocusOwner()) {
            g.setColor(Tokens.color(Tokens.Role.ACCENT));
            g.setStroke(new BasicStroke(2f));
            g.drawRoundRect(1, 1, width - 2, height - 2, Tokens.ARC_CARD, Tokens.ARC_CARD);
        } else {
            g.setColor(Tokens.color(Tokens.Role.BORDER_SUBTLE));
            g.drawRoundRect(0, 0, width, height, Tokens.ARC_CARD, Tokens.ARC_CARD);
        }
        g.dispose();
    }
}
