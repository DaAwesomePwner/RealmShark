package tomato.gui.glance.character;

import java.awt.BorderLayout;
import java.util.List;
import java.util.Objects;
import javax.swing.*;
import tomato.gui.glance.character.GoalCardsModel.GoalCard;
import tomato.gui.kit.*;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;
import static tomato.gui.glance.character.SheetViews.*;

/**
 * Sheet › Goals (spec §6.2): a header naming the character, one card per goal (GoalCardsModel: this character's stat goals and
 * this class's exalt goals) in a wrapping grid, or "No goals for this character", then Manage goals, the account-wide goals panel
 * (explicit account, table and editors), so goals stay editable in both modes. Analyst shows that panel expanded below the cards;
 * Simple keeps it in a "Manage goals" section, collapsed by default and remembered (ui.collapse.character-goals-manage).
 * - A component has one parent, so the panel moves between the two hosts when the mode changes: the Simple section keeps its own
 *   open/closed choice (a mode switch never writes it) and Analyst always shows the panel, with no toggle to hide it.
 * - An unknown remaining draws no bar (an empty track reads as 0%) and says why instead of a number (spec §1).
 * - A goal card has no action of its own (editing is Manage goals') and takes no focus, so rebuilding the cards never moves it.
 * EDT only.
 */
final class GoalCards extends JPanel {
    static final String EMPTY_TITLE = "No goals for this character", EMPTY_BODY = "Add goals under Manage goals.";
    private final KitText title = named(KitText.emphasis("Goals"), "character-goals-title");
    /** The goals store's status while it has not read the saved plans, or cannot: goals are then unknown, never "none". */
    private final JTextArea status = named(ContentStyle.wrappingText(""), "character-goals-status");
    /** Three cards across on a wide sheet, fewer when narrower (responsiveGrid balances the rows). */
    private final JPanel grid = named(ContentStyle.responsiveGrid(3, 260, Tokens.M), "character-goals-cards");
    private final EmptyState empty = named(new EmptyState(EMPTY_TITLE, EMPTY_BODY, null), "character-goals-empty");
    private final DisplayModeModel mode;
    private final JComponent manage;
    /** Simple: the section's content, which holds {@link #manage} while Simple mode is on. */
    private final JPanel simpleHost = new JPanel(new BorderLayout());
    private final Collapsible section;
    /** Analyst: {@link #manage} expanded under a "Manage goals" header. */
    private final JPanel analystHost = new JPanel(new BorderLayout());
    private final JPanel analyst;
    private List<GoalCard> shown;
    private boolean applied;

    /** {@code manage}: the Manage goals panel (CharacterPlanningPanel), hosted below the cards and never rebuilt here. */
    GoalCards(DisplayModeModel mode, JComponent manage) {
        super(new BorderLayout());
        this.mode = Objects.requireNonNull(mode, "mode");
        this.manage = Objects.requireNonNull(manage, "manage");
        setOpaque(false);
        setName("character-goals");
        grid.setOpaque(false);
        status.setVisible(false);
        simpleHost.setOpaque(false);
        analystHost.setOpaque(false);
        section = named(new Collapsible("character-goals-manage", "Manage goals", simpleHost, false), "character-goals-manage");
        analyst = named(KitLayouts.stack(Tokens.S, new SectionHeader("Manage goals"), analystHost), "character-goals-manage-panel");
        add(KitLayouts.stack(Tokens.M, title, status, grid, empty, section, analyst), BorderLayout.NORTH);
        section.toggle().addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) {
                JComponent toggle = section.toggle();
                ContentStyle.reveal(toggle, new java.awt.Rectangle(0, 0, toggle.getWidth(), toggle.getHeight()));
            }
        });
        mode.bind(this, value -> host(value == DisplayModeModel.Mode.ANALYST));
        apply("Goals", null, null);
    }

    /**
     * EDT. {@code heading}: "Goals for Wizard #7". {@code cards}: the character's goal cards; null while the sheet loads its
     * character (nothing shows). {@code storeStatus}: non-null while the goals store has not read the saved plans or cannot
     * (loading, unavailable): it shows instead of the empty state, since goals are then unknown. Equal cards are not rebuilt.
     */
    void apply(String heading, List<GoalCard> cards, String storeStatus) {
        title.setText(heading);
        boolean unknown = storeStatus != null;
        status.setText(unknown ? storeStatus : "");
        status.setVisible(unknown);
        boolean some = cards != null && !cards.isEmpty();
        grid.setVisible(some);
        empty.setVisible(cards != null && cards.isEmpty() && !unknown);
        if (!applied || !Objects.equals(cards, shown)) {
            applied = true;
            shown = cards;
            grid.removeAll();
            if (some) for (GoalCard card : cards) grid.add(card(card));
        }
        revalidate();
        repaint();
    }

    /** Puts the Manage goals panel in Analyst's always-shown host or in Simple's section, and shows only that host. */
    private void host(boolean analystMode) {
        JPanel target = analystMode ? analystHost : simpleHost;
        if (manage.getParent() != target) { target.add(manage, BorderLayout.CENTER); simpleHost.revalidate(); analystHost.revalidate(); }
        section.setVisible(!analystMode);
        analyst.setVisible(analystMode);
        revalidate();
        repaint();
    }

    /** One goal: its title, the bar and numbers toward the fixed target, what remains, its state chips and, for an exalt, where to earn it. */
    private Card card(GoalCard goal) {
        String name = "character-goal-" + goal.key();
        boolean known = goal.remaining() != null;
        JTextArea heading = named(text(goal.title(), Type.emphasis()), name + "-title");
        StatBar bar = named(new StatBar(), name + "-bar");
        bar.set(goal.current(), goal.goal());
        bar.setVisible(known); // an unknown remaining draws nothing that could read as 0%
        bar.getAccessibleContext().setAccessibleName(goal.title() + " progress");
        boolean stat = goal.kind() == GoalCard.Kind.STAT;
        String now = goal.current() == null ? DisplayFormat.UNAVAILABLE : Integer.toString(goal.current());
        KitText numbers = named(KitText.caption(stat ? "Base " + now + " of " + goal.goal() : now + " of " + goal.goal() + " completions"), name + "-progress");
        numbers.setToolTipText(goal.current() == null ? (stat ? "Base stat not captured" : "Exalt completions not captured") : null);
        JTextArea remaining = named(text(goal.remainingText(), Type.body()), name + "-remaining");
        remaining.setToolTipText(stat ? "Standard potions (+5 Life/Mana, +1 other stats) to the fixed target" : "Dungeon completions to the tier's threshold");
        // The chip names the state briefly ("Complete"; a chip cannot wrap in a narrow card at font 18) and its tooltip keeps
        // CharacterGoals' full state ("Complete; fixed target retained": targets never advance). The remaining line already
        // carries an unknown state's reason, so a chip would only repeat it.
        Chip state = named(new Chip(brief(goal.state()), goal.remaining() != null && goal.remaining() == 0 ? Tokens.Tone.GOOD : Tokens.Tone.NEUTRAL), name + "-state");
        state.setToolTipText(goal.state());
        state.getAccessibleContext().setAccessibleDescription(goal.state());
        state.setVisible(known);
        Chip reconfirm = named(new Chip("Reconfirm target", Tokens.Tone.WARN), name + "-reconfirm");
        reconfirm.setVisible(goal.reconfirm());
        reconfirm.setToolTipText(stat ? "The class's local caps changed since this target was saved, or it exceeds the cap: review it under Manage goals"
            : "The dungeon mapping changed since this target was saved: review it under Manage goals");
        JPanel chips = row(state, reconfirm);
        chips.setVisible(known || goal.reconfirm());
        JComponent[] rows = {heading, bar, numbers, remaining, chips};
        if (!stat) {
            boolean mapped = !goal.earnIn().isEmpty();
            JTextArea earn = named(text("Earn in: " + (mapped ? goal.earnIn() : DisplayFormat.UNAVAILABLE), Type.caption()), name + "-earn");
            earn.setToolTipText(mapped ? "From the selected game assets' dungeon mapping"
                : "The selected game assets' dungeon mapping does not name where this stat is earned");
            rows = new JComponent[]{heading, bar, numbers, remaining, chips, earn};
        }
        Card card = named(new Card(mode).body(KitLayouts.stack(Tokens.XS, rows)), name);
        card.setFocusable(false); // no action (no Card.onOpen): a read-only summary
        card.setToolTipText("Fixed target: " + goal.target() + (goal.updatedAt() > 0 ? " · saved " + DisplayFormat.formatTimestamp(goal.updatedAt()) : ""));
        card.getAccessibleContext().setAccessibleName(goal.title() + ": " + goal.remainingText());
        return card;
    }

    /** A state's lead phrase: "Complete; fixed target retained" → "Complete"; "Near complete" and "In progress" as they are. */
    static String brief(String state) { int end = state.indexOf(';'); return end < 0 ? state : state.substring(0, end); }

    /**
     * Wrapping text in a kit font role (the theme's text color): a long title or reason must not be cut in a narrow card or at font
     * 18. Not focusable: a card is a read-only summary (the Manage goals table holds the same rows), so Tab moves from the tabs to
     * Manage goals instead of stopping on every line of every card, and a rebuild never drops the focus.
     */
    private static JTextArea text(String value, java.awt.Font font) {
        JTextArea area = ContentStyle.wrappingText(value);
        ContentStyle.font(area, font);
        area.setFocusable(false);
        return area;
    }
}
