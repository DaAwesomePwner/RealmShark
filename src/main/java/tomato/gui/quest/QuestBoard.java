package tomato.gui.quest;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.*;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.KitLayouts;
import tomato.gui.kit.SectionHeader;
import tomato.gui.kit.TileList;
import tomato.gui.kit.Tokens;

/**
 * The quest Board's Cards view (spec §6.5, §9, §10): the detail drawer on top, then one section per group of the applied
 * {@link QuestBoardModel} in its order: a kit SectionHeader with the group's title and count over a kit TileList of painted cards
 * named {@code quest-cards-<group key>}, or one empty state when no card shows.
 * - Each list keeps its cards through {@link TileList#setItems}: an unchanged group fires nothing and a changed one keeps its
 *   selection by the quest's pin key. Sections of groups that are gone are dropped; the stack is only re-laid out when the group
 *   order changes.
 * - Arrow keys move within a group and Tab moves between groups (spec §10); a card the user selects clears the other groups'
 *   selection and is reported once. Enter, Space or a double-click opens the card ({@link #onOpen}).
 * EDT only.
 */
final class QuestBoard extends JPanel {
    private final QuestCardRenderer.SpriteLookup sprites;
    private final Map<String, Section> sections = new HashMap<>();
    private final JPanel groups = new JPanel(new GridBagLayout());
    private final JPanel emptyHolder = new JPanel(new BorderLayout());
    private EmptyState empty;
    private String emptyTitle;
    private List<String> order = List.of();
    private QuestBoardModel model = QuestBoardModel.build(List.of(), QuestBoardModel.GroupBy.NONE, false, 0, false);
    private Consumer<String> selected = key -> { };
    private Consumer<QuestCardModel> open = card -> { };
    private boolean selecting;

    /** One group's header and card list. */
    private record Section(SectionHeader header, TileList<QuestCardModel> list, JPanel panel) {}

    QuestBoard(QuestDetail detail, QuestCardRenderer.SpriteLookup sprites) {
        super(new BorderLayout());
        this.sprites = sprites;
        setName("quest-board");
        setOpaque(false);
        groups.setName("quest-groups");
        groups.setOpaque(false);
        emptyHolder.setOpaque(false);
        emptyHolder.setVisible(false);
        add(KitLayouts.stack(Tokens.L, Objects.requireNonNull(detail, "detail"), groups, emptyHolder), BorderLayout.CENTER);
    }

    /** The quest's stable identity on the Board: its pin key (the server ID, or name and category without one). */
    static String key(QuestCardModel card) { return QuestPins.key(card.id(), card.name(), card.category()); }

    /**
     * Shows {@code next}'s groups; with no card at all, the empty state titled {@code emptyTitle} with {@code emptyBody} (the page
     * says whether nothing is captured, the list is empty, or the filters match nothing).
     */
    void apply(QuestBoardModel next, String emptyTitle, String emptyBody) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Apply the Board on the EDT");
        model = Objects.requireNonNull(next, "next");
        List<String> keys = new ArrayList<>();
        selecting = true; // setItems re-selects by key: not a user's choice
        try {
            for (QuestBoardModel.Group group : next.groups()) {
                Section section = sections.computeIfAbsent(group.key(), this::section);
                section.header().setTitle(group.title());
                section.header().setCount(Integer.toString(group.cards().size()));
                section.list().getAccessibleContext().setAccessibleName(group.title());
                section.list().setItems(group.cards());
                keys.add(group.key());
            }
            sections.keySet().retainAll(keys);
        } finally {
            selecting = false;
        }
        if (!keys.equals(order)) relayout(keys);
        groups.setVisible(!keys.isEmpty());
        showEmpty(keys.isEmpty() ? emptyTitle : null, emptyBody);
        revalidate();
        repaint();
    }

    /** The applied model's groups, in order. */
    List<QuestBoardModel.Group> groups() { return model.groups(); }

    /** Every card shown, group by group. */
    List<QuestCardModel> cards() {
        List<QuestCardModel> all = new ArrayList<>();
        for (QuestBoardModel.Group group : model.groups()) all.addAll(group.cards());
        return all;
    }

    /** The card list of the group {@code key}, or null when that group does not show. */
    TileList<QuestCardModel> list(String key) { Section section = sections.get(key); return section == null ? null : section.list(); }

    /** The header of the group {@code key}, or null when that group does not show. */
    SectionHeader header(String key) { Section section = sections.get(key); return section == null ? null : section.header(); }

    /** Where the user's card selection goes (its pin key). */
    void onSelect(Consumer<String> listener) { selected = Objects.requireNonNull(listener, "listener"); }

    /** What Enter, Space or a double-click on a card runs. */
    void onOpen(Consumer<QuestCardModel> action) { open = Objects.requireNonNull(action, "action"); }

    /**
     * Selects the card whose pin key is {@code key} in its group and clears the others; scrolls it into view when the selection
     * changes, or always with {@code reveal}. Null or an unknown key clears every group.
     */
    void select(String key, boolean reveal) {
        selecting = true;
        try { for (Section section : sections.values()) section.list().selectKey(key, reveal); } finally { selecting = false; }
    }

    /** The selected card's pin key, or null. */
    String selectedKey() {
        for (Section section : sections.values()) {
            QuestCardModel card = section.list().getSelectedValue();
            if (card != null) return key(card);
        }
        return null;
    }

    /** Selects, reveals and focuses the card {@code key} (Close hands focus back to the card it opened). */
    void focus(String key) {
        select(key, true);
        for (Section section : sections.values())
            if (section.list().getSelectedValue() != null) { section.list().requestFocusInWindow(); return; }
    }

    /**
     * Focuses the selected card's list, else the first group's list (a view switch brings keyboard focus here), and once the page is
     * laid out reveals that card; false when no card shows.
     */
    boolean focusCards() {
        TileList<QuestCardModel> target = null;
        for (QuestBoardModel.Group group : model.groups()) {
            TileList<QuestCardModel> list = list(group.key());
            if (list == null) continue;
            if (list.getSelectedValue() != null) { target = list; break; }
            if (target == null) target = list;
        }
        if (target == null) return false;
        TileList<QuestCardModel> focused = target;
        focused.requestFocusInWindow();
        SwingUtilities.invokeLater(() -> { if (focused.isShowing()) focused.ensureIndexIsVisible(Math.max(0, focused.getSelectedIndex())); });
        return true;
    }

    /** The page scrolls the cards instead of squeezing them. */
    @Override public Dimension getMinimumSize() { return getPreferredSize(); }

    private Section section(String key) {
        SectionHeader header = new SectionHeader("");
        header.setName("quest-group-" + key);
        // A type-label section's header already names the type (QuestBoardModel's "type-<id>" keys): its cards paint no chip.
        TileList<QuestCardModel> list = new TileList<>("quest-cards-" + key, new QuestCardRenderer(sprites, !key.startsWith("type-")),
            QuestBoard::key, QuestCardRenderer::accessibleName);
        list.getAccessibleContext().setAccessibleDescription("Arrow keys move between quests; Enter or Space shows the quest's details");
        list.onOpen(card -> open.accept(card));
        list.addListSelectionListener(e -> {
            QuestCardModel card = list.getSelectedValue();
            if (e.getValueIsAdjusting() || selecting || card == null) return;
            selecting = true;
            try { for (Section other : sections.values()) if (other.list() != list) other.list().clearSelection(); } finally { selecting = false; }
            selected.accept(key(card));
        });
        JPanel panel = KitLayouts.stack(Tokens.S, header, list);
        panel.setName("quest-section-" + key);
        return new Section(header, list, panel);
    }

    /** Stacks the sections in the model's order, a section gap apart, from the top. */
    private void relayout(List<String> keys) {
        groups.removeAll();
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        for (int i = 0; i < keys.size(); i++) {
            c.gridy = i;
            c.insets = new Insets(i == 0 ? 0 : Tokens.L, 0, 0, 0);
            groups.add(sections.get(keys.get(i)).panel(), c);
        }
        order = List.copyOf(keys);
        groups.revalidate();
        groups.repaint();
    }

    /** One empty state below the drawer, or none; a new title builds a new state, a new body only replaces its line. */
    private void showEmpty(String title, String body) {
        if (title == null) { emptyHolder.setVisible(false); return; }
        if (!title.equals(emptyTitle)) {
            emptyHolder.removeAll();
            empty = new EmptyState(title, body, null);
            empty.setName("quest-board-empty");
            emptyHolder.add(empty, BorderLayout.CENTER);
            emptyTitle = title;
        } else {
            empty.setBody(body);
        }
        emptyHolder.setVisible(true);
    }
}
