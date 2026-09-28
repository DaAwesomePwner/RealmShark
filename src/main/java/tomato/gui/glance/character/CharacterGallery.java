package tomato.gui.glance.character;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.*;
import tomato.gui.kit.Banner;
import tomato.gui.kit.Collapsible;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.KitLayouts;
import tomato.gui.kit.TileList;
import tomato.gui.kit.Tokens;

/**
 * The roster gallery (spec §6.2, §9, §10): living characters as painted cards in a wrapping JList, dead ones in a collapsed
 * Graveyard right below them. Arrow keys move between cards; Enter, Space or a double-click opens the selected character's sheet
 * through {@code open} (its journal key). Both lists are kit {@link TileList}s: one painted renderer per list and one model event
 * per apply, so hundreds of characters stay fast. Analyst shows character IDs on the cards (spec §3.2). A storage problem is a
 * warn banner above the cards or the empty state; only an unreadable journal with nothing to show is an unavailable state, never
 * "No characters yet" (spec §7). A failed save keeps the normal empty or no-match state under its banner. EDT only.
 */
public final class CharacterGallery extends JPanel {
    static final String GRAVEYARD = "characters-graveyard";
    /** Enter and Space run this action on either list (the P3a name, which the roster tests drive). */
    static final String OPEN = "open-character";
    private final Consumer<String> open;
    private final CharacterCardRenderer aliveRenderer = new CharacterCardRenderer(), deadRenderer = new CharacterCardRenderer();
    private final TileList<CharacterCardModel> alive = cards("character-cards", "Characters", aliveRenderer);
    private final TileList<CharacterCardModel> dead = cards("character-graveyard-cards", "Graveyard", deadRenderer);
    private final Collapsible graveyard = new Collapsible(GRAVEYARD, "Graveyard (0)", dead, false);
    private final Banner storage = new Banner("character-gallery-storage");
    /** The living cards and the Graveyard, each at its preferred height from the top (no stretched gap). */
    private final JPanel content = KitLayouts.stack(Tokens.L, alive, graveyard);
    private final EmptyState none = new EmptyState("No characters yet", "Start capture and enter the game on a character to save it here.", null);
    private final EmptyState noMatch = new EmptyState("No characters match", "Reset filters to show every saved character.", null);
    private EmptyState unavailable;
    private String unavailableReason;
    private Consumer<String> selected = key -> { };
    private boolean selecting;

    public CharacterGallery(Consumer<String> open, DisplayModeModel mode) {
        super(new BorderLayout(0, Tokens.L)); // BorderLayout skips the hidden banner and its gap
        this.open = Objects.requireNonNull(open, "open");
        setName("character-gallery");
        setOpaque(false);
        none.setName("character-gallery-empty");
        noMatch.setName("character-gallery-no-match");
        graveyard.setName("character-graveyard");
        graveyard.setVisible(false);
        storage.setTone(Tokens.Tone.WARN);
        storage.setVisible(false);
        alive.onOpen(OPEN, card -> open.accept(card.key()));
        dead.onOpen(OPEN, card -> open.accept(card.key()));
        bindSelection(alive, dead);
        bindSelection(dead, alive);
        mode.bind(this, value -> {
            boolean analyst = value == DisplayModeModel.Mode.ANALYST;
            aliveRenderer.setAnalyst(analyst);
            deadRenderer.setAnalyst(analyst);
            alive.repaint();
            dead.repaint();
        });
        // The banner sits above whatever body shows, so a save failure stays visible when an empty state replaces the cards.
        add(storage, BorderLayout.NORTH);
        showBody(none);
    }

    public void apply(List<CharacterCardModel> living, List<CharacterCardModel> fallen) { apply(living, fallen, false, null, false); }

    public void apply(List<CharacterCardModel> living, List<CharacterCardModel> fallen, boolean filtered) { apply(living, fallen, filtered, null, false); }

    /**
     * {@code filtered}: nothing is shown although characters are saved, so the empty state points at the filters.
     * {@code problem}: the journal's storage problem (null when none), a warn banner above the cards or the empty state.
     * {@code unreadable}: the journal could not be read (CharacterJournal.readable() is false); with no card at all that is the
     * unavailable state, which names the problem itself, so the banner then hides. A failed save alone never makes the gallery
     * unavailable.
     */
    public void apply(List<CharacterCardModel> living, List<CharacterCardModel> fallen, boolean filtered, String problem, boolean unreadable) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Apply the gallery on the EDT");
        alive.setItems(living);
        dead.setItems(fallen);
        graveyard.toggle().setText("Graveyard (" + fallen.size() + ")");
        graveyard.setVisible(!fallen.isEmpty());
        boolean empty = living.isEmpty() && fallen.isEmpty(), unavailableShown = empty && unreadable;
        storage.setText(problem == null ? "" : problem);
        storage.setVisible(problem != null && !unavailableShown);
        showBody(!empty ? content : unavailableShown ? unavailable(problem == null ? "Cannot read Characters/journal.json." : problem)
            : filtered ? noMatch : none);
    }

    public List<CharacterCardModel> alive() { return alive.items(); }
    public List<CharacterCardModel> dead() { return dead.items(); }
    /** Every shown card: the living, then the Graveyard. */
    public List<CharacterCardModel> cards() { List<CharacterCardModel> all = new ArrayList<>(alive()); all.addAll(dead()); return all; }

    /** Where the user's card selection goes (the roster list selects the same character, so views and saved state follow it). */
    public void onSelect(Consumer<String> listener) { selected = Objects.requireNonNull(listener, "listener"); }

    /** Selects {@code key}'s card, living or in the Graveyard, and always scrolls it into view; null or an unknown key clears both lists. */
    public void select(String key) { select(key, true); }

    /**
     * Selects {@code key}'s card; scrolls it into view when the selection actually changes, or always when {@code reveal} is
     * true. A refresh that repeats the same selection (e.g. the gallery's periodic live-key check) passes {@code reveal=false}
     * so it never fights a user who scrolled elsewhere; an explicit selection (Back from the sheet) always reveals.
     */
    public void select(String key, boolean reveal) {
        selecting = true;
        try { alive.selectKey(key, reveal); dead.selectKey(key, reveal); } finally { selecting = false; }
    }

    /** The list keyboard focus returns to from the sheet: the Graveyard's while it is open and holds the selection, else the cards. */
    public JComponent focusTarget() { return dead.getSelectedIndex() >= 0 && graveyard.isVisible() && graveyard.expanded() ? dead : alive; }

    /** The page scrolls the gallery instead of squeezing it. */
    @Override public Dimension getMinimumSize() { return getPreferredSize(); }

    private EmptyState unavailable(String reason) {
        if (!reason.equals(unavailableReason)) {
            unavailable = new EmptyState("Characters unavailable", reason, null);
            unavailable.setName("character-gallery-unavailable");
            unavailableReason = reason;
        }
        return unavailable;
    }

    /** Swaps the body below the banner: the cards, or one empty or unavailable state. */
    private void showBody(JComponent next) {
        Component current = ((BorderLayout) getLayout()).getLayoutComponent(BorderLayout.CENTER);
        if (current == next) return;
        if (current != null) remove(current);
        add(next, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    /** A card the user selects in one list clears the other list's selection and is reported once. */
    private void bindSelection(TileList<CharacterCardModel> list, TileList<CharacterCardModel> other) {
        list.addListSelectionListener(e -> {
            CharacterCardModel card = list.getSelectedValue();
            if (e.getValueIsAdjusting() || selecting || card == null) return;
            selecting = true;
            try { other.clearSelection(); } finally { selecting = false; }
            selected.accept(card.key());
        });
    }

    /** A wrapping list of fixed-size cards whose height follows its width, so the page scrolls the cards and the Graveyard together. */
    private static TileList<CharacterCardModel> cards(String name, String title, CharacterCardRenderer renderer) {
        TileList<CharacterCardModel> list = new TileList<>(name, renderer, CharacterCardModel::key, CharacterCardModel::accessibleName);
        list.getAccessibleContext().setAccessibleName(title);
        return list;
    }
}
