package tomato.gui.character;

import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.*;
import javax.swing.*;
import tomato.gui.glance.character.CharacterCardModel;
import tomato.gui.glance.character.CharacterGallery;
import tomato.gui.history.WrapRow;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.OverflowMenu;
import tomato.gui.kit.SegmentedControl;
import tomato.gui.kit.Tokens;
import tomato.gui.kit.Type;
import tomato.gui.modern.ContentStyle;

/**
 * The roster's Gallery | Table views (spec §6.2, §3.2) below the one characters FilterBar, so both share its search, facets and
 * saved view. The gallery shows exactly the roster's visible rows ({@link Source#rows}) in the Sort order; the table keeps its
 * header sorting. The Sort combo and, in Analyst, the Gallery/Table toggle sit in the filter row's own wrapping row, so at 680 px
 * or font 18 they wrap below the search instead of squeezing it. Simple offers the other view in the ⋯ menu. The view and the sort
 * persist ({@link #VIEW_KEY}, {@link #SORT_KEY}). While the gallery shows, a 1 s check re-marks "Playing now" when the character in
 * game changes without a journal change. The gallery's selection is the roster's selection. EDT only.
 */
final class RosterViews {
    static final String VIEW_KEY = "ui.characters.view", SORT_KEY = "ui.characters.sort";
    /** Most recently played first: last observed in game, then last seen in any capture, then the key, so ties stay stable. */
    private static final Comparator<CharacterRosterQuery.Row> RECENT =
        Comparator.comparingLong((CharacterRosterQuery.Row row) -> row.record.lastObservedAlive).reversed()
            .thenComparing(Comparator.comparingLong((CharacterRosterQuery.Row row) -> row.record.lastSeen).reversed())
            .thenComparing(row -> row.record.key);

    /** What the roster list gives the views. EDT. */
    interface Source {
        /** The rows the search and filters keep (CharacterJournalGUI.visibleRows()). */
        List<CharacterRosterQuery.Row> rows();
        /** Whether the journal holds any character. */
        boolean saved();
        /** The journal's storage problem (CharacterJournal.storageProblem()), or null. */
        String problem();
        /** The in-game character's journal key (with Home's map-change grace), or null. */
        String liveKey();
        /** The list's selected character, or null. */
        String selectedKey();
        /** Selects a character in the list (the gallery's selection). */
        void select(String key);
        /** Opens a character's sheet. */
        void open(String key);
    }

    /** Gallery orders; unknown values sort last and are never read as zero. */
    enum Sort {
        LAST_PLAYED("Last played", "last-played"), FAME("Fame", "fame"), CLASS("Class", "class"), MAXED("Maxed", "maxed");
        final String label, id;
        Sort(String label, String id) { this.label = label; this.id = id; }
        @Override public String toString() { return label; }
        static Sort of(String id) { for (Sort sort : values()) if (sort.id.equals(id)) return sort; return LAST_PLAYED; }
        Comparator<CharacterRosterQuery.Row> order() {
            switch (this) {
                case FAME: return Comparator.comparing((CharacterRosterQuery.Row row) -> row.record.fame,
                    Comparator.nullsLast(Comparator.<Long>reverseOrder())).thenComparing(RECENT);
                case CLASS: return Comparator.comparing((CharacterRosterQuery.Row row) ->
                        CharacterCardModel.className(row.record.classId, row.record.className), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing((CharacterRosterQuery.Row row) -> row.record.level, Comparator.nullsLast(Comparator.<Integer>reverseOrder()))
                    .thenComparing(RECENT);
                case MAXED: return Comparator.comparing((CharacterRosterQuery.Row row) -> row.maxed,
                    Comparator.nullsLast(Comparator.<Integer>reverseOrder())).thenComparing(RECENT);
                default: return RECENT;
            }
        }
    }

    private final JComponent table;
    private final CharacterGallery gallery;
    private final Body body = new Body();
    private final JComboBox<Sort> sort = new JComboBox<>(Sort.values());
    private final JPanel sortRow = new JPanel(new FlowLayout(FlowLayout.LEADING, Tokens.XS, 0));
    private final SegmentedControl view = new SegmentedControl("character-view", "Gallery", "Table");
    private final JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEADING, Tokens.S, 0));
    private final OverflowMenu overflow;
    private final JMenuItem viewItem;
    private final Source source;
    private final BiConsumer<String, String> write;
    private final Timer live;
    private boolean galleryShown, ready;
    private String shownLive;

    /**
     * {@code row}: the filter row's wrapping row (FilterBar.search's content), which gets the Sort combo and the view toggle;
     * {@code bar}: the same FilterBar, whose ⋯ menu gets the other view in Simple mode.
     */
    RosterViews(JComponent table, FilterBar bar, WrapRow row, Source source, DisplayModeModel mode, Function<String, String> read,
                BiConsumer<String, String> write) {
        this.table = table;
        this.source = Objects.requireNonNull(source, "source");
        this.write = write;
        gallery = new CharacterGallery(source::open, mode);
        gallery.onSelect(source::select);
        body.add(table);
        body.add(gallery);
        sort.setName("character-sort");
        sort.getAccessibleContext().setAccessibleName("Sort characters");
        sort.setToolTipText("Order of the character cards; the table sorts by its column headers");
        sort.setSelectedItem(Sort.of(read.apply(SORT_KEY)));
        sort.addActionListener(e -> { write.accept(SORT_KEY, sort().id); refresh(); });
        JLabel label = new JLabel("Sort");
        label.setLabelFor(sort);
        ContentStyle.font(label, Type.caption());
        sortRow.setName("character-sort-row");
        sortRow.setOpaque(false);
        sortRow.add(label);
        sortRow.add(sort);
        view.getAccessibleContext().setAccessibleName("Roster view");
        view.onChange(index -> showGallery(index == 0, true));
        controls.setName("character-view-controls");
        controls.setOpaque(false);
        controls.add(sortRow);
        controls.add(view);
        row.add(controls); // wraps below the search and Reset filters when the row is narrow
        overflow = bar.overflow();
        viewItem = overflow.add("Table view", () -> showGallery(!galleryShown, true));
        viewItem.setName("character-view-item");
        mode.bind(controls, this::modeChanged);
        live = new Timer(1000, e -> { if (galleryShown && !Objects.equals(source.liveKey(), shownLive)) refresh(); });
        gallery.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
            if (gallery.isShowing()) live.start(); else live.stop();
        });
        showGallery(!"table".equals(read.apply(VIEW_KEY)), false);
        ready = true;
    }

    JComponent body() { return body; }
    CharacterGallery gallery() { return gallery; }
    boolean galleryShown() { return galleryShown; }
    Sort sort() { return (Sort) sort.getSelectedItem(); }

    /** Re-reads the visible rows into the gallery; while the table shows, the next switch to the gallery does it instead. EDT. */
    void refresh() {
        if (!galleryShown) return;
        List<CharacterRosterQuery.Row> visible = new ArrayList<>(source.rows());
        visible.sort(sort().order());
        String key = source.liveKey();
        List<CharacterCardModel> alive = new ArrayList<>(), dead = new ArrayList<>();
        for (CharacterRosterQuery.Row row : visible) {
            CharacterCardModel card = CharacterCardModel.of(row, key);
            (card.dead() ? dead : alive).add(card);
        }
        shownLive = key;
        gallery.apply(alive, dead, visible.isEmpty() && source.saved(), source.problem());
        // A refresh repeats the same selection far more often than it changes it (e.g. the periodic live-key check while in
        // game), so it must not fight a user who scrolled elsewhere; only a genuine selection change reveals it here.
        gallery.select(source.selectedKey(), false);
    }

    /** Shows the gallery or the table; the other stays in the tree, hidden and unmeasured. A user's choice is remembered. */
    void showGallery(boolean show, boolean remember) {
        galleryShown = show;
        if (remember) write.accept(VIEW_KEY, show ? "gallery" : "table");
        table.setVisible(!show);
        gallery.setVisible(show);
        sortRow.setVisible(show);
        view.setSelected(show ? 0 : 1);
        viewItem.setText(show ? "Table view" : "Gallery view");
        body.revalidate();
        body.repaint();
        if (show && ready) refresh();
    }

    /** Analyst: the Gallery/Table toggle in the filter row; Simple: the other view in the ⋯ menu, hidden when nothing else is there. */
    private void modeChanged(DisplayModeModel.Mode mode) {
        boolean analyst = mode == DisplayModeModel.Mode.ANALYST;
        view.setVisible(analyst);
        viewItem.setVisible(!analyst);
        boolean items = false;
        for (Component item : overflow.menu().getComponents()) items |= item instanceof JMenuItem && item.isVisible();
        overflow.setVisible(items);
        controls.revalidate();
        controls.repaint();
    }

    /** Holds the table and the gallery; only the visible one is laid out and measured, so a long hidden gallery never sets the page height. */
    private static final class Body extends JPanel {
        Body() {
            super(null);
            setOpaque(false);
            setName("character-roster-body");
        }
        private Component shown() { for (Component child : getComponents()) if (child.isVisible()) return child; return null; }
        @Override public Dimension getPreferredSize() { Component c = shown(); return c == null ? new Dimension(0, 0) : c.getPreferredSize(); }
        @Override public Dimension getMinimumSize() { Component c = shown(); return c == null ? new Dimension(0, 0) : c.getMinimumSize(); }
        @Override public void doLayout() { for (Component child : getComponents()) if (child.isVisible()) child.setBounds(0, 0, getWidth(), getHeight()); }
    }
}
