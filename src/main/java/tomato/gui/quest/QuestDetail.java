package tomato.gui.quest;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Objects;
import javax.swing.*;
import tomato.gui.kit.KitButton;
import tomato.gui.kit.KitLayouts;
import tomato.gui.kit.Tokens;
import tomato.gui.kit.Type;
import tomato.gui.modern.ContentStyle;

/**
 * The Board's detail drawer (spec §6.5): the quest a card opened, with everything the fixed card cannot hold. The name, badges, the
 * user's type label and the pin state; the pin (and a legacy global interest's removal) and "Add to account plan"; the description;
 * "You get" / "Pick 1 of N" and "Bring" as full lists with sprites, counts and names ("not captured" for unknown lists, never
 * "none"). Analyst adds the stable ID, the server category and the raw server expiration verbatim ({@code quest-detail-expiration});
 * Simple shows none of them (spec §3.2; the countdown is deferred, so nothing is parsed). Close or Escape hands focus back to the
 * card. Hidden and empty until opened: a closed drawer holds no quest text. Its buttons stay the same components while it shows, so
 * pressing Pin keeps keyboard focus on it. EDT only.
 */
final class QuestDetail extends JPanel {
    /** The action Escape runs anywhere inside the drawer. */
    static final String CLOSE = "quest-detail-close";

    /** What the page knows beside the card: the pin button's text and state, a legacy global interest, and the display mode. */
    record State(String pinText, boolean pinEnabled, boolean globalInterest, boolean analyst) {}

    private final QuestCardRenderer.SpriteLookup sprites;
    private final JTextArea title = text("quest-detail-name", Type.title(), false);
    private final JTextArea meta = text("quest-detail-meta", Type.caption(), false);
    private final JTextArea description = text("quest-detail-description", Type.body(), true);
    private final JTextArea rewardsTitle = text("quest-detail-rewards-title", Type.emphasis(), false);
    private final JTextArea requirementsTitle = text("quest-detail-requirements-title", Type.emphasis(), false);
    private final JPanel rewards = column("quest-detail-rewards"), requirements = column("quest-detail-requirements");
    private final JTextArea id = text("quest-detail-id", Type.caption(), false);
    private final JTextArea category = text("quest-detail-category", Type.caption(), false);
    private final JTextArea expiration = text("quest-detail-expiration", Type.caption(), false);
    private final JTextArea legacy = text("quest-detail-legacy", Type.caption(), false);
    private final KitButton close = KitButton.ghost("Close");
    private final JButton pin = new JButton(), removeGlobal = new JButton("Remove global interest"), plan = new JButton("Add to account plan");
    private final JPanel actions = ContentStyle.controls();
    private final JPanel body;
    private Runnable onPin = () -> { }, onRemoveGlobal = () -> { }, onPlan = () -> { }, onClose = () -> { };
    private QuestCardModel card;
    private State state;

    QuestDetail(QuestCardRenderer.SpriteLookup sprites) {
        super(new BorderLayout());
        this.sprites = sprites;
        setName("quest-detail");
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(Tokens.M, Tokens.M, Tokens.M, Tokens.M));
        getAccessibleContext().setAccessibleName("Quest details");
        close.setName(CLOSE);
        close.setToolTipText("Close the details (Escape)");
        close.getAccessibleContext().setAccessibleName("Close quest details");
        close.addActionListener(e -> onClose.run());
        pin.setName("quest-detail-pin");
        pin.addActionListener(e -> onPin.run());
        removeGlobal.setName("quest-detail-remove-global");
        removeGlobal.addActionListener(e -> onRemoveGlobal.run());
        plan.setName("quest-detail-plan");
        plan.addActionListener(e -> onPlan.run());
        actions.setOpaque(false);
        actions.add(pin); actions.add(removeGlobal); actions.add(plan);
        for (JComponent button : new JComponent[] {close, pin, removeGlobal, plan}) button.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) { // keyboard focus scrolls the page to the control
                ContentStyle.reveal(button, new Rectangle(0, 0, button.getWidth(), button.getHeight()));
            }
        });
        JPanel head = new JPanel(new BorderLayout(Tokens.S, 0));
        head.setOpaque(false);
        head.add(title, BorderLayout.CENTER);
        JPanel closeRow = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
        closeRow.setOpaque(false);
        closeRow.add(close);
        head.add(closeRow, BorderLayout.EAST);
        // Rewards first (spec §6.5), then what to bring; side by side when wide, stacked when narrow.
        JPanel exchange = ContentStyle.responsiveGrid(2, 220, Tokens.M);
        exchange.setOpaque(false);
        exchange.add(KitLayouts.stack(Tokens.XS, rewardsTitle, rewards));
        exchange.add(KitLayouts.stack(Tokens.XS, requirementsTitle, requirements));
        body = KitLayouts.stack(Tokens.S, head, meta, actions, description, exchange, id, category, expiration, legacy);
        add(body, BorderLayout.CENTER);
        // Escape anywhere inside the drawer closes it, as Close does.
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CLOSE);
        getActionMap().put(CLOSE, new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { onClose.run(); } });
        clear();
    }

    void onPin(Runnable action) { onPin = Objects.requireNonNull(action); }
    void onRemoveGlobal(Runnable action) { onRemoveGlobal = Objects.requireNonNull(action); }
    void onPlan(Runnable action) { onPlan = Objects.requireNonNull(action); }
    void onClose(Runnable action) { onClose = Objects.requireNonNull(action); }

    /** The card shown, or null while closed. */
    QuestCardModel card() { return card; }
    JButton closeButton() { return close; }

    /** Shows {@code card} with the page's {@code state}; an equal card and state change nothing (a refresh keeps focus and scroll). */
    void show(QuestCardModel card, State state) {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(state, "state");
        if (card.equals(this.card) && state.equals(this.state) && isVisible()) return;
        this.card = card;
        this.state = state;
        title.setText(card.name());
        StringBuilder line = new StringBuilder();
        for (String badge : card.badges()) line.append(line.length() == 0 ? "" : " · ").append(QuestCardRenderer.glyph(badge, Type.caption()));
        line.append(" · ").append(card.typeLabel().isEmpty() ? "No type label (Name types… in Filters)" : card.typeLabel());
        if (card.pinned()) line.append(" · Pinned");
        meta.setText(line.toString());
        pin.setText(state.pinText());
        pin.setEnabled(state.pinEnabled());
        pin.setToolTipText(state.pinEnabled() ? null : "Pins need the quest list of the current capture");
        removeGlobal.setVisible(state.globalInterest());
        description.setText(card.description());
        description.setVisible(!card.description().isEmpty());
        rewardsTitle.setText(card.rewardsTitle());
        items(rewards, card.rewards(), card.rewardsKnown(), "Rewards not captured");
        requirementsTitle.setText("Bring");
        items(requirements, card.requirements(), card.requirementsKnown(), "Requirements not captured");
        boolean analyst = state.analyst();
        analyst(id, analyst ? card.id().isEmpty() ? "Stable ID unavailable · provisional interest only" : "Stable quest ID: " + card.id() : "");
        analyst(category, analyst ? "Server category: " + card.category() : "");
        analyst(expiration, analyst ? "Expiration (raw server value): " + (card.rawExpiration().isEmpty() ? "Not supplied" : card.rawExpiration()) : "");
        legacy.setText(state.globalInterest() ? "Legacy global interest · Not an account-specific plan." : "");
        legacy.setVisible(state.globalInterest());
        getAccessibleContext().setAccessibleName("Quest details: " + card.name());
        setVisible(true);
        revalidate();
        repaint();
    }

    /** Updates only the pin's availability (the capture can go stale between publications). */
    void pinEnabled(boolean enabled) {
        if (state == null || state.pinEnabled() == enabled) return;
        state = new State(state.pinText(), enabled, state.globalInterest(), state.analyst());
        pin.setEnabled(enabled);
        pin.setToolTipText(enabled ? null : "Pins need the quest list of the current capture");
    }

    /** Closes the drawer and forgets its quest: no text of it stays in the tree. */
    void clear() {
        card = null;
        state = null;
        for (JTextArea area : new JTextArea[] {title, meta, description, rewardsTitle, requirementsTitle, id, category, expiration, legacy}) area.setText("");
        rewards.removeAll();
        requirements.removeAll();
        pin.setText(""); // no pin label (or action) while no quest shows; the Table footer's "Pin quest" stays the only one
        pin.setEnabled(false);
        removeGlobal.setVisible(false);
        getAccessibleContext().setAccessibleName("Quest details");
        setVisible(false);
        revalidate();
        repaint();
    }

    /** One row per item (its sprite in a tier well, "count × name", the id as tooltip), or the note for an unknown or empty list. */
    private void items(JPanel column, List<QuestCardModel.Item> items, boolean known, String unknown) {
        column.removeAll();
        if (!known || items.isEmpty()) column.add(text(null, Type.body(), false, !known ? unknown : QuestCardRenderer.NOT_LISTED, true));
        for (QuestCardModel.Item item : items) {
            JPanel row = new JPanel(new BorderLayout(Tokens.S, 0));
            row.setOpaque(false);
            row.add(new JLabel(QuestCardRenderer.slotIcon(sprites, item.id(), 24)), BorderLayout.WEST);
            JTextArea name = text(null, Type.body(), false, item.count() + " × " + item.name(), false);
            name.setToolTipText("Item ID: " + item.id());
            row.add(name, BorderLayout.CENTER);
            column.add(row);
        }
        column.revalidate();
    }

    private static void analyst(JTextArea area, String text) { area.setText(text); area.setVisible(!text.isEmpty()); }

    private static JPanel column(String name) {
        JPanel column = new JPanel(new GridLayout(0, 1, 0, Tokens.XS));
        column.setName(name);
        column.setOpaque(false);
        return column;
    }

    private static JTextArea text(String name, Font font, boolean focusable) { return text(name, font, focusable, "", false); }

    /** Wrapping text in a kit font role; only the description takes focus (to be read and copied), the rest are static labels. */
    private static JTextArea text(String name, Font font, boolean focusable, String value, boolean muted) {
        JTextArea area = ContentStyle.wrappingText(value);
        if (name != null) area.setName(name);
        ContentStyle.font(area, font);
        area.setFocusable(focusable);
        if (muted) {
            area.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
            area.addPropertyChangeListener("UI", e -> area.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED))); // follows the theme
        }
        if (focusable) area.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) {
                ContentStyle.reveal(area, new Rectangle(0, 0, area.getWidth(), area.getHeight()));
            }
        });
        return area;
    }

    /** A raised rounded surface under the drawer's content. */
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Tokens.color(Tokens.Role.RAISED));
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tokens.ARC_CARD, Tokens.ARC_CARD);
            g.setColor(Tokens.color(Tokens.Role.ACCENT));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tokens.ARC_CARD, Tokens.ARC_CARD);
        } finally {
            g.dispose();
        }
    }
}
