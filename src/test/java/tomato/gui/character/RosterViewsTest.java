package tomato.gui.character;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.gui.glance.character.CharacterCardModel;
import tomato.gui.glance.character.CharacterFixtures;
import tomato.gui.glance.character.CharacterGallery;
import tomato.gui.history.WrapRow;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.FilterBar;
import tomato.gui.kit.SegmentedControl;
import tomato.gui.modern.ContentStyle;
import ui.UiTestLayout;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeModels.named;

/** Gallery | Table: sort orders and memory, the view switch per mode, exactly the table's visible rows, and 500 characters on the EDT. */
public class RosterViewsTest {
    private static final long NOW = 1_790_000_000_000L, HOUR = 3_600_000L;
    @Rule public final TemporaryFolder temp = new TemporaryFolder();
    private final Map<String, String> prefs = new HashMap<>();
    private final DisplayModeModel mode = new DisplayModeModel(prefs::get, prefs::put);
    private final List<String> opened = new ArrayList<>();
    private final String[] live = {null};
    private FilterBar bar;
    private WrapRow row;
    private JComponent table;

    /**
     * Alive: 1 Wizard 7/8 fame 1,234 played 1 h ago; 2 Warrior 8/8 fame 5,400 played 3 h ago; 3 Priest (fame and maxed unknown,
     * never played, seen 10 min ago); 4 Archer 6/8 fame 15,020 played 2 h ago. Dead: 5 Archer.
     */
    private static List<CharacterRosterQuery.Row> rows(RosterDefinitions d) {
        return List.of(
            CharacterFixtures.row(d, 1, 782, "Wizard", 20, 1_234L, 1, NOW - HOUR, NOW - HOUR, false),
            CharacterFixtures.row(d, 2, 797, "Warrior", 20, 5_400L, 0, NOW - 3 * HOUR, NOW - 3 * HOUR, false),
            CharacterFixtures.row(d, 3, 784, "Priest", 20, null, -1, 0, NOW - 600_000L, false),
            CharacterFixtures.row(d, 4, 775, "Archer", 20, 15_020L, 2, NOW - 2 * HOUR, NOW - 2 * HOUR, false),
            CharacterFixtures.row(d, 5, 775, "Archer", 20, 640L, 4, NOW - 5 * HOUR, NOW - 5 * HOUR, true));
    }

    /** A new roster view over {@code rows} with fresh stand-ins for the table and the filter bar, refreshed once. EDT. */
    private RosterViews views(List<CharacterRosterQuery.Row> rows) {
        table = new JPanel();
        table.setName("table-stand-in");
        bar = new FilterBar("characters-test");
        row = new WrapRow();
        bar.search(row);
        RosterViews views = new RosterViews(table, bar, row, new RosterViews.Source() {
            @Override public List<CharacterRosterQuery.Row> rows() { return rows; }
            @Override public boolean saved() { return !rows.isEmpty(); }
            @Override public String problem() { return null; }
            @Override public String liveKey() { return live[0]; }
            @Override public String selectedKey() { return null; }
            @Override public void select(String key) { }
            @Override public void open(String key) { opened.add(key); }
        }, mode, prefs::get, prefs::put);
        views.refresh(); // CharacterJournalGUI's first filter() does this through its rows listener
        return views;
    }

    @Test public void eachSortOrdersTheCardsWithUnknownsLastAndIsRemembered() throws Exception {
        List<CharacterRosterQuery.Row> rows = rows(CharacterFixtures.definitions());
        SwingUtilities.invokeAndWait(() -> {
            RosterViews views = views(rows);
            assertEquals("Last played is the default", RosterViews.Sort.LAST_PLAYED, views.sort());
            assertEquals("Played 1 h, 2 h, 3 h ago, then never", List.of("1", "4", "2", "3"), ids(views.gallery().alive()));
            assertEquals("The dead sit in the Graveyard", List.of("5"), ids(views.gallery().dead()));
            assertEquals("Wizard", views.gallery().alive().get(0).className());
            JComboBox<?> sort = named(bar, "character-sort", JComboBox.class);
            sort.setSelectedItem(RosterViews.Sort.FAME);
            assertEquals("fame", prefs.get(RosterViews.SORT_KEY));
            assertEquals("Unknown fame sorts last, never as 0", List.of("4", "2", "1", "3"), ids(views.gallery().alive()));
            sort.setSelectedItem(RosterViews.Sort.CLASS);
            assertEquals(List.of("4", "3", "2", "1"), ids(views.gallery().alive()));
            sort.setSelectedItem(RosterViews.Sort.MAXED);
            assertEquals("Unknown maxed sorts last, never as 0/8", List.of("2", "1", "4", "3"), ids(views.gallery().alive()));
            assertEquals("maxed", prefs.get(RosterViews.SORT_KEY));
            RosterViews again = views(rows);
            assertEquals("A new roster view restores the sort", RosterViews.Sort.MAXED, again.sort());
            assertEquals(List.of("2", "1", "4", "3"), ids(again.gallery().alive()));
        });
    }

    @Test public void simpleOffersTheOtherViewInTheOverflowMenuAndAnalystAToggle() throws Exception {
        List<CharacterRosterQuery.Row> rows = rows(CharacterFixtures.definitions());
        SwingUtilities.invokeAndWait(() -> {
            RosterViews views = views(rows);
            assertTrue("The gallery is the default view", views.galleryShown());
            assertTrue(views.gallery().isVisible());
            assertFalse(table.isVisible());
            assertSame("The table stays in the tree, hidden", views.body(), table.getParent());
            SegmentedControl toggle = named(bar, "character-view", SegmentedControl.class);
            assertFalse("Simple: no toggle in the filter row", toggle.isVisible());
            JMenuItem item = bar.overflow().item("Table view");
            assertNotNull("Simple: the Table view is in the ⋯ menu", item);
            assertTrue(item.isVisible());
            assertTrue(bar.overflow().isVisible());
            assertTrue("The gallery sorts in the filter row", shown(named(bar, "character-sort", JComboBox.class), bar));
            assertSame("…in the search's wrapping row, so it wraps below the search at narrow widths", row,
                named(bar, "character-view-controls", JPanel.class).getParent());
            item.doClick();
            assertFalse(views.galleryShown());
            assertTrue(table.isVisible());
            assertFalse(views.gallery().isVisible());
            assertEquals("table", prefs.get(RosterViews.VIEW_KEY));
            assertEquals("Gallery view", item.getText());
            assertFalse("The table sorts by its headers", shown(named(bar, "character-sort", JComboBox.class), bar));
            mode.set(DisplayModeModel.Mode.ANALYST);
            assertTrue("Analyst: a Gallery/Table toggle in the filter row", toggle.isVisible());
            assertEquals(1, toggle.selected());
            assertFalse(item.isVisible());
            assertFalse("A ⋯ menu with nothing to show is hidden", bar.overflow().isVisible());
            named(toggle, "character-view-0", JToggleButton.class).doClick();
            assertTrue(views.galleryShown());
            assertEquals("gallery", prefs.get(RosterViews.VIEW_KEY));
            prefs.put(RosterViews.VIEW_KEY, "table");
            assertFalse("A new roster view restores the saved view", views(rows).galleryShown());
        });
    }

    @Test public void theGalleryShowsExactlyTheRosterTablesVisibleRows() throws Exception {
        String view = PropertiesManager.getProperty(RosterViews.VIEW_KEY);
        PropertiesManager.setProperties(RosterViews.VIEW_KEY, "gallery");
        CharacterJournal journal = CharacterFixtures.journal(temp.getRoot().toPath().resolve("Characters").resolve("journal.json"), NOW);
        journal.notes(CharacterFixtures.ACCOUNT + ":105", "bow practice");
        RosterDefinitions definitions = CharacterFixtures.definitions();
        try {
            SwingUtilities.invokeAndWait(() -> {
                CharacterJournalGUI panel = new CharacterJournalGUI(journal, () -> NOW, () -> definitions);
                CharacterGallery gallery = named(panel, "character-gallery", CharacterGallery.class);
                assertEquals(8, panel.visibleRows().size());
                assertEquals(rowKeys(panel.visibleRows()), cardKeys(gallery));
                assertEquals("Marked-dead characters are in the Graveyard", List.of("107", "108"), ids(gallery.dead()));
                JTextField search = named(panel, "character-search", JTextField.class);
                search.setText("bow practice");
                assertEquals(List.of(CharacterFixtures.ACCOUNT + ":105"), rowKeys(panel.visibleRows()));
                assertEquals("One search serves both views", rowKeys(panel.visibleRows()), cardKeys(gallery));
                search.setText("");
                named(panel, "character-facet-2", JComboBox.class).setSelectedIndex(1); // Needs Life
                assertEquals(List.of("103", "104", "107", "108"), panel.visibleRows().stream().map(r -> String.valueOf(r.record.characterId)).sorted().collect(Collectors.toList()));
                assertEquals("One filter drawer serves both views", rowKeys(panel.visibleRows()), cardKeys(gallery));
                named(panel, "character-facet-2", JComboBox.class).setSelectedIndex(0);
                assertTrue(gallery.alive().stream().noneMatch(CharacterCardModel::playingNow));
                panel.setLiveKey(() -> CharacterFixtures.KEY);
                assertEquals("Playing now marks exactly the live character's journal key", List.of(CharacterFixtures.KEY),
                    gallery.alive().stream().filter(CharacterCardModel::playingNow).map(CharacterCardModel::key).collect(Collectors.toList()));
            });
        } finally {
            PropertiesManager.setProperties(RosterViews.VIEW_KEY, view == null ? "" : view);
            journal.close();
        }
    }

    /** Spec §9: 500 characters sort, map and apply within 50 ms on the EDT (every sample); one viewport paint is logged. */
    @Test public void fiveHundredCharactersRefreshWithinFiftyMillisecondsAndPaintOnePass() throws Exception {
        List<CharacterRosterQuery.Row> rows = CharacterFixtures.manyRows(CharacterFixtures.definitions(), 500, NOW);
        RosterViews[] views = new RosterViews[1];
        JFrame[] frame = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            views[0] = views(rows);
            frame[0] = new JFrame("Gallery timing - synthetic validation");
            frame[0].setContentPane(ContentStyle.page(null, views[0].body(), null));
            frame[0].setSize(1240, 800);
            frame[0].setVisible(true);
        });
        try {
            long[] micros = new long[30];
            for (int i = 0; i < 50; i++) {
                int sample = i - 20;
                String key = rows.get(1 + i % 2).record.key; // a different card plays each turn, so no refresh is skipped as equal
                SwingUtilities.invokeAndWait(() -> {
                    live[0] = key;
                    long start = System.nanoTime();
                    views[0].refresh();
                    if (sample >= 0) micros[sample] = (System.nanoTime() - start) / 1_000;
                });
            }
            Arrays.sort(micros);
            System.out.println("Gallery refresh of 500 characters on the EDT over 30 samples: median " + micros[15] + " us, p95 "
                + micros[28] + " us, max " + micros[29] + " us");
            assertTrue("Sort, map and apply take at most 50 ms on every sample; max " + micros[29] + " us", micros[29] <= 50_000);
            SwingUtilities.invokeAndWait(() -> {
                UiTestLayout.settle(frame[0]);
                BufferedImage image = new BufferedImage(frame[0].getWidth(), frame[0].getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                long start = System.nanoTime();
                frame[0].getContentPane().paint(g);
                long painted = (System.nanoTime() - start) / 1_000;
                g.dispose();
                JList<?> cards = named(views[0].body(), "character-cards", JList.class);
                Rectangle visible = cards.getVisibleRect();
                int first = cards.locationToIndex(visible.getLocation());
                int last = cards.locationToIndex(new Point(visible.x + visible.width - 1, visible.y + visible.height - 1));
                System.out.println("Gallery paint of one 1240x800 viewport (cards " + first + ".." + last + " of " + cards.getModel().getSize()
                    + " visible): " + painted + " us");
                assertTrue("The cards wrap into rows taller than the window, so only the visible ones paint", cards.getHeight() > 800);
                assertTrue("More than one card per row at 1240 px", cards.getCellBounds(1, 1).y == cards.getCellBounds(0, 0).y);
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> frame[0].dispose());
        }
    }

    private static List<String> ids(List<CharacterCardModel> cards) { return cards.stream().map(CharacterCardModel::characterId).collect(Collectors.toList()); }
    private static List<String> rowKeys(List<CharacterRosterQuery.Row> rows) { return rows.stream().map(r -> r.record.key).sorted().collect(Collectors.toList()); }
    private static List<String> cardKeys(CharacterGallery gallery) { return gallery.cards().stream().map(CharacterCardModel::key).sorted().collect(Collectors.toList()); }
    /** Visible up to {@code root}: there is no window, so isShowing is false everywhere. */
    private static boolean shown(Component component, Container root) {
        for (Component c = component; c != null; c = c.getParent()) { if (!c.isVisible()) return false; if (c == root) return true; }
        return false;
    }
}
