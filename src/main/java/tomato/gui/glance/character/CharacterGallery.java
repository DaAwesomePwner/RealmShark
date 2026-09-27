package tomato.gui.glance.character;

import java.awt.*;
import java.awt.event.*;
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
import tomato.gui.kit.Tokens;

/**
 * The roster gallery (spec §6.2, §9, §10): living characters as painted cards in a wrapping JList, dead ones in a collapsed
 * Graveyard right below them. Arrow keys move between cards; Enter, Space or a double-click opens the selected character's sheet
 * through {@code open} (its journal key). One painted renderer per list and one model event per apply, so hundreds of characters
 * stay fast. Analyst shows character IDs on the cards (spec §3.2). A storage problem is a warn banner above the cards; an
 * unreadable journal with nothing to show is an unavailable state, never "No characters yet" (spec §7). EDT only.
 */
public final class CharacterGallery extends JPanel {
    static final String GRAVEYARD = "characters-graveyard";
    private final Consumer<String> open;
    private final CharacterCardRenderer aliveRenderer = new CharacterCardRenderer(), deadRenderer = new CharacterCardRenderer();
    private final Cards alive = new Cards("character-cards", "Characters", aliveRenderer);
    private final Cards dead = new Cards("character-graveyard-cards", "Graveyard", deadRenderer);
    private final Collapsible graveyard = new Collapsible(GRAVEYARD, "Graveyard (0)", dead, false);
    private final Banner storage = new Banner("character-gallery-storage");
    /** The storage warning, the living cards and the Graveyard, each at its preferred height from the top (no stretched gap). */
    private final JPanel content = KitLayouts.stack(Tokens.L, storage, alive, graveyard);
    private final EmptyState none = new EmptyState("No characters yet", "Start capture and enter the game on a character to save it here.", null);
    private final EmptyState noMatch = new EmptyState("No characters match", "Reset filters to show every saved character.", null);
    private EmptyState unavailable;
    private String unavailableReason;
    private Consumer<String> selected = key -> { };
    private boolean selecting;

    public CharacterGallery(Consumer<String> open, DisplayModeModel mode) {
        super(new BorderLayout());
        this.open = Objects.requireNonNull(open, "open");
        setName("character-gallery");
        setOpaque(false);
        none.setName("character-gallery-empty");
        noMatch.setName("character-gallery-no-match");
        graveyard.setName("character-graveyard");
        graveyard.setVisible(false);
        storage.setTone(Tokens.Tone.WARN);
        storage.setVisible(false);
        bindOpen(alive);
        bindOpen(dead);
        bindSelection(alive, dead);
        bindSelection(dead, alive);
        mode.bind(this, value -> {
            boolean analyst = value == DisplayModeModel.Mode.ANALYST;
            aliveRenderer.setAnalyst(analyst);
            deadRenderer.setAnalyst(analyst);
            alive.repaint();
            dead.repaint();
        });
        showBody(none);
    }

    public void apply(List<CharacterCardModel> living, List<CharacterCardModel> fallen) { apply(living, fallen, false, null); }

    public void apply(List<CharacterCardModel> living, List<CharacterCardModel> fallen, boolean filtered) { apply(living, fallen, filtered, null); }

    /**
     * {@code filtered}: nothing is shown although characters are saved, so the empty state points at the filters. {@code problem}:
     * the journal's storage problem (null when none), a warn banner above the cards; with no card at all, the unavailable state.
     */
    public void apply(List<CharacterCardModel> living, List<CharacterCardModel> fallen, boolean filtered, String problem) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Apply the gallery on the EDT");
        alive.setCards(living);
        dead.setCards(fallen);
        graveyard.toggle().setText("Graveyard (" + fallen.size() + ")");
        graveyard.setVisible(!fallen.isEmpty());
        storage.setText(problem == null ? "" : problem);
        storage.setVisible(problem != null);
        boolean empty = living.isEmpty() && fallen.isEmpty();
        showBody(!empty ? content : problem != null ? unavailable(problem) : filtered ? noMatch : none);
    }

    public List<CharacterCardModel> alive() { return alive.cards.cards; }
    public List<CharacterCardModel> dead() { return dead.cards.cards; }
    /** Every shown card: the living, then the Graveyard. */
    public List<CharacterCardModel> cards() { List<CharacterCardModel> all = new ArrayList<>(alive()); all.addAll(dead()); return all; }

    /** Where the user's card selection goes (the roster list selects the same character, so views and saved state follow it). */
    public void onSelect(Consumer<String> listener) { selected = Objects.requireNonNull(listener, "listener"); }

    /** Selects {@code key}'s card, living or in the Graveyard, and scrolls it into view; null or an unknown key clears both lists. */
    public void select(String key) {
        selecting = true;
        try { alive.selectKey(key); dead.selectKey(key); } finally { selecting = false; }
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

    private void showBody(JComponent next) {
        if (getComponentCount() == 1 && getComponent(0) == next) return;
        removeAll();
        add(next, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    private void bindOpen(Cards list) {
        for (int key : new int[] {KeyEvent.VK_ENTER, KeyEvent.VK_SPACE})
            list.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), "open-character");
        list.getActionMap().put("open-character", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                CharacterCardModel card = list.getSelectedValue();
                if (card != null) open.accept(card.key());
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) return;
                int index = list.locationToIndex(e.getPoint());
                Rectangle cell = index < 0 ? null : list.getCellBounds(index, index);
                if (cell != null && cell.contains(e.getPoint())) open.accept(list.getModel().getElementAt(index).key());
            }
        });
    }

    /** A card the user selects in one list clears the other list's selection and is reported once. */
    private void bindSelection(Cards list, Cards other) {
        list.addListSelectionListener(e -> {
            CharacterCardModel card = list.getSelectedValue();
            if (e.getValueIsAdjusting() || selecting || card == null) return;
            selecting = true;
            try { other.clearSelection(); } finally { selecting = false; }
            selected.accept(card.key());
        });
    }

    /** A wrapping list of fixed-size cards whose height follows its width, so the page scrolls the cards and the Graveyard together. */
    private static final class Cards extends JList<CharacterCardModel> {
        private final CardListModel cards;
        private final CharacterCardRenderer renderer;
        private int measuredWidth = -1;

        Cards(String name, String title, CharacterCardRenderer renderer) {
            super(new CardListModel());
            this.cards = (CardListModel) getModel();
            this.renderer = renderer;
            setName(name);
            getAccessibleContext().setAccessibleName(title);
            setLayoutOrientation(HORIZONTAL_WRAP);
            setVisibleRowCount(0);
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            setOpaque(false);
            setCellRenderer(renderer);
            resize();
            addComponentListener(new ComponentAdapter() {
                @Override public void componentResized(ComponentEvent e) {
                    if (getWidth() != measuredWidth) { measuredWidth = getWidth(); revalidate(); } // the row count depends on the width
                }
            });
        }

        void setCards(List<CharacterCardModel> next) {
            CharacterCardModel selected = getSelectedValue();
            if (!cards.set(next)) return;
            resize();
            if (selected != null) for (int i = 0; i < cards.getSize(); i++)
                if (cards.getElementAt(i).key().equals(selected.key())) { setSelectedIndex(i); break; }
        }

        void selectKey(String key) {
            for (int i = 0; key != null && i < cards.getSize(); i++)
                if (cards.getElementAt(i).key().equals(key)) { if (getSelectedIndex() != i) setSelectedIndex(i); ensureIndexIsVisible(i); return; }
            if (!isSelectionEmpty()) clearSelection();
        }

        private void resize() {
            Dimension cell = renderer.cellSize();
            if (getFixedCellWidth() != cell.width) super.setFixedCellWidth(cell.width);
            if (getFixedCellHeight() != cell.height) super.setFixedCellHeight(cell.height);
        }

        /** ContentStyle.refreshFonts sizes text lists by line height; a card keeps the height its own layout needs. */
        @Override public void setFixedCellHeight(int height) { super.setFixedCellHeight(renderer == null ? height : renderer.cellSize().height); }
        @Override public void setFont(Font font) { super.setFont(font); if (renderer != null) resize(); }
        @Override public void addNotify() { super.addNotify(); resize(); }

        /** Rows of as many cards as fit the list's width (before its first layout, its nearest sized ancestor's). */
        @Override public Dimension getPreferredSize() {
            Insets insets = getInsets();
            int count = cards.getSize(), cellWidth = Math.max(1, getFixedCellWidth()), width = getWidth();
            for (Container parent = getParent(); width <= 0 && parent != null; parent = parent.getParent()) width = parent.getWidth();
            int columns = Math.max(1, (width - insets.left - insets.right) / cellWidth), rows = (count + columns - 1) / columns;
            return new Dimension(Math.min(count, columns) * cellWidth + insets.left + insets.right,
                rows * getFixedCellHeight() + insets.top + insets.bottom);
        }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }
    }

    /** The cards shown; a change fires one removal and one insertion, never one event per card, and an equal list fires nothing. */
    private static final class CardListModel extends AbstractListModel<CharacterCardModel> {
        private List<CharacterCardModel> cards = List.of();
        @Override public int getSize() { return cards.size(); }
        @Override public CharacterCardModel getElementAt(int index) { return cards.get(index); }

        boolean set(List<CharacterCardModel> next) {
            List<CharacterCardModel> copy = List.copyOf(next);
            if (copy.equals(cards)) return false;
            int before = cards.size();
            cards = List.of();
            if (before > 0) fireIntervalRemoved(this, 0, before - 1);
            cards = copy;
            if (!copy.isEmpty()) fireIntervalAdded(this, 0, copy.size() - 1);
            return true;
        }
    }
}
