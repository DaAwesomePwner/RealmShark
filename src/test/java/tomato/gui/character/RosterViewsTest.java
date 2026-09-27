package tomato.gui.character;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
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
import tomato.gui.kit.Banner;
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
            @Override public boolean unreadable() { return false; }
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

    /**
     * Regression for the P3a final review: without a live-key change, the 1 s timer used to skip the gallery entirely, so
     * "Played <ago>" never aged once capture stopped (the timer only ever called refresh() on a live-key change). It must
     * still repaint the gallery every tick while it shows, so the painted relative time keeps advancing.
     */
    @Test public void theLiveTimerRepaintsTheGalleryEverySecondEvenWithoutALiveKeyChange() throws Exception {
        List<CharacterRosterQuery.Row> rows = rows(CharacterFixtures.definitions());
        RosterViews[] views = new RosterViews[1];
        JFrame[] frame = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            views[0] = views(rows); // Source.liveKey() stays null throughout: no live-key change ever happens
            frame[0] = new JFrame("Roster aging - synthetic validation");
            frame[0].setContentPane(views[0].gallery());
            frame[0].setSize(900, 400);
            frame[0].setVisible(true);
        });
        try {
            SwingUtilities.invokeAndWait(() -> {
                UiTestLayout.settle(frame[0]);
                RepaintManager manager = RepaintManager.currentManager(views[0].gallery());
                manager.markCompletelyClean(views[0].gallery());
                assertTrue("Sanity: nothing pending before the tick", manager.getDirtyRegion(views[0].gallery()).isEmpty());
                fireLiveTick(views[0]);
                assertFalse("The 1 s tick must still repaint the gallery so relative \"Played <ago>\" text keeps advancing",
                    manager.getDirtyRegion(views[0].gallery()).isEmpty());
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> frame[0].dispose());
        }
    }

    /** Fires RosterViews' private per-second aging/live-key Timer once, as if a real tick had elapsed. */
    private static void fireLiveTick(RosterViews views) {
        try {
            java.lang.reflect.Field field = RosterViews.class.getDeclaredField("live");
            field.setAccessible(true);
            javax.swing.Timer timer = (javax.swing.Timer) field.get(views);
            for (var listener : timer.getActionListeners()) listener.actionPerformed(null);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
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

    /**
     * Regression for the P3a review fix: in game, lastObservedAlive advances every tick, so refresh() runs about once a second.
     * That must not fight a user who scrolled away from the selected card (e.g. to look at the Graveyard); only a genuine
     * selection change, or an explicit select (as focusRoster() does), may scroll the gallery.
     */
    @Test public void refreshWithAnUnchangedSelectionNeverScrollsButAChangeOrAnExplicitSelectStillReveals() throws Exception {
        List<CharacterRosterQuery.Row> rows = CharacterFixtures.manyRows(CharacterFixtures.definitions(), 60, NOW);
        String near = rows.get(1).record.key, far = rows.get(59).record.key, farther = rows.get(58).record.key; // all three alive (i % 10 != 0)
        String[] selected = {near};
        RosterViews[] views = new RosterViews[1];
        JFrame[] frame = new JFrame[1];
        JScrollPane[] page = new JScrollPane[1];
        SwingUtilities.invokeAndWait(() -> {
            table = new JPanel(); table.setName("table-stand-in");
            bar = new FilterBar("characters-scroll-test");
            row = new WrapRow();
            bar.search(row);
            views[0] = new RosterViews(table, bar, row, new RosterViews.Source() {
                @Override public List<CharacterRosterQuery.Row> rows() { return rows; }
                @Override public boolean saved() { return true; }
                @Override public String problem() { return null; }
                @Override public boolean unreadable() { return false; }
                @Override public String liveKey() { return null; }
                @Override public String selectedKey() { return selected[0]; }
                @Override public void select(String key) { }
                @Override public void open(String key) { }
            }, mode, prefs::get, prefs::put);
            views[0].refresh();
            page[0] = ContentStyle.page(null, views[0].body(), null);
            frame[0] = new JFrame("Gallery scroll - synthetic validation");
            frame[0].setContentPane(page[0]);
            frame[0].setSize(900, 340); // short viewport: 54 alive cards overflow it
            frame[0].setVisible(true);
        });
        try {
            SwingUtilities.invokeAndWait(() -> {
                UiTestLayout.settle(frame[0]);
                JList<?> cards = named(views[0].body(), "character-cards", JList.class);
                selected[0] = far;
                views[0].gallery().select(far); // explicit, as focusRoster() does: always reveals
                UiTestLayout.settle(frame[0]);
                assertTrue("An explicit select scrolls the far card into view", visible(cards, indexOfKey(cards, far)));
                page[0].getViewport().setViewPosition(new Point(0, 0)); // the user scrolls back to the top
                assertFalse("Sanity: the far card is indeed off-screen from the top", visible(cards, indexOfKey(cards, far)));
                views[0].refresh(); // e.g. the periodic rows/live refresh, same selection
                UiTestLayout.settle(frame[0]);
                assertEquals("An unchanged selection must not fight the user's scroll", 0, page[0].getViewport().getViewPosition().y);
                assertFalse("Still off-screen: the refresh did not reveal it", visible(cards, indexOfKey(cards, far)));
                selected[0] = farther; // a genuinely different (but still off-screen from the top) selection
                views[0].refresh();
                UiTestLayout.settle(frame[0]);
                assertTrue("A changed selection still scrolls to reveal it", visible(cards, indexOfKey(cards, farther)));
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> frame[0].dispose());
        }
    }

    /**
     * Regression for the P3a review fix: CharacterJournal.save() runs on a background writer and changes storageStatus
     * without bumping revision, so CharacterJournalGUI.refresh() must recheck the storage problem on every call, not only
     * when filter() runs from a revision change.
     */
    @Test public void aBackgroundSaveFailureShowsTheBannerWithoutARevisionChangeAndALaterSaveClearsIt() throws Exception {
        String view = PropertiesManager.getProperty(RosterViews.VIEW_KEY);
        PropertiesManager.setProperties(RosterViews.VIEW_KEY, "gallery");
        RosterDefinitions definitions = CharacterFixtures.definitions();
        Path blocked = temp.getRoot().toPath().resolve("blocked-save");
        Files.write(blocked, new byte[]{1}); // a regular file stands in for the journal's directory
        CharacterJournal failing = CharacterFixtures.journal(blocked.resolve("journal.json"), NOW);
        CharacterJournalGUI[] panel = new CharacterJournalGUI[1];
        Banner[] storage = new Banner[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel[0] = new CharacterJournalGUI(failing, () -> NOW, () -> definitions);
                CharacterGallery gallery = named(panel[0], "character-gallery", CharacterGallery.class);
                storage[0] = named(gallery, "character-gallery-storage", Banner.class);
                assertFalse("No problem yet", storage[0].isVisible());
            });
            long revisionBeforeSave = failing.revision();
            failing.save(); // fails: "blocked-save" is a regular file, not a directory
            assertEquals("A save never bumps the revision (the defect's premise)", revisionBeforeSave, failing.revision());
            SwingUtilities.invokeAndWait(() -> {
                panel[0].refresh(); // e.g. the page's 1 s timer tick; no rows/revision change happened
                assertTrue("A background save failure must show the banner without waiting for a data change", storage[0].isVisible());
                assertTrue(storage[0].warns());
                assertTrue(storage[0].text(), storage[0].text().startsWith("Save failed"));
            });
            Files.delete(blocked); // "blocked-save" is now free to become a real directory
            failing.save(); // now succeeds
            SwingUtilities.invokeAndWait(() -> {
                panel[0].refresh();
                assertFalse("A later successful save must clear the banner without a data change", storage[0].isVisible());
            });
        } finally {
            failing.close();
            PropertiesManager.setProperties(RosterViews.VIEW_KEY, view == null ? "" : view);
        }
    }

    /** P3a deferred finding 2, integrated: a failed save with every card filtered away is "no match" under its banner, not unavailable. */
    @Test public void aBlockedSaveWithNoVisibleCardSaysNoMatchNotUnavailable() throws Exception {
        String view = PropertiesManager.getProperty(RosterViews.VIEW_KEY);
        PropertiesManager.setProperties(RosterViews.VIEW_KEY, "gallery");
        RosterDefinitions definitions = CharacterFixtures.definitions();
        Path blocked = temp.getRoot().toPath().resolve("blocked-save");
        Files.write(blocked, new byte[]{1}); // a regular file stands in for the journal's directory
        CharacterJournal failing = CharacterFixtures.journal(blocked.resolve("journal.json"), NOW);
        CharacterJournalGUI[] panel = new CharacterJournalGUI[1];
        try {
            SwingUtilities.invokeAndWait(() -> panel[0] = new CharacterJournalGUI(failing, () -> NOW, () -> definitions));
            failing.save(); // fails: "blocked-save" is a regular file, not a directory
            SwingUtilities.invokeAndWait(() -> {
                panel[0].refresh();
                assertTrue("Sanity: the save failed", failing.readable() && failing.storageProblem() != null);
                named(panel[0], "character-search", JTextField.class).setText("no such character");
                CharacterGallery gallery = named(panel[0], "character-gallery", CharacterGallery.class);
                assertNotNull("Filters hid every card", named(gallery, "character-gallery-no-match", tomato.gui.kit.EmptyState.class));
                assertNull("A readable journal whose save failed is never \"unavailable\"",
                    named(gallery, "character-gallery-unavailable", tomato.gui.kit.EmptyState.class));
                Banner storage = named(gallery, "character-gallery-storage", Banner.class);
                assertTrue("The save failure stays visible above the empty state",
                    storage != null && storage.isVisible() && storage.text().startsWith("Save failed"));
                assertEquals("One \"nothing here\" message", List.of("No characters match"), nothingHere(panel[0]));
                assertEquals("The banner names the failure; the footer does not repeat it", 1,
                    shownText(panel[0]).stream().filter(text -> text.startsWith("Save failed")).count());
            });
        } finally {
            failing.close();
            PropertiesManager.setProperties(RosterViews.VIEW_KEY, view == null ? "" : view);
        }
    }

    /** P3a deferred finding 9: an empty journal says so exactly once, in the Gallery view and in the Table view. */
    @Test public void anEmptyJournalSaysNothingIsHereExactlyOnceInTheGalleryAndInTheTable() throws Exception {
        String view = PropertiesManager.getProperty(RosterViews.VIEW_KEY);
        PropertiesManager.setProperties(RosterViews.VIEW_KEY, "gallery");
        RosterDefinitions definitions = CharacterFixtures.definitions();
        CharacterJournal empty = new CharacterJournal(temp.getRoot().toPath().resolve("empty").resolve("journal.json"));
        try {
            SwingUtilities.invokeAndWait(() -> {
                CharacterJournalGUI panel = new CharacterJournalGUI(empty, () -> NOW, () -> definitions);
                panel.refresh();
                assertTrue(panel.views().galleryShown());
                assertEquals("Gallery: the empty state alone", List.of("No characters yet"), nothingHere(panel));
                assertFalse("No summary while nothing is saved", named(panel, "character-summary", JTextArea.class).isVisible());
                panel.views().showGallery(false, false); // the footer follows the view at once, not on the next 1 s refresh
                List<String> table = nothingHere(panel);
                assertEquals("Table: the footer's guidance alone: " + table, 1, table.size());
                assertTrue(table.get(0), table.get(0).startsWith("Start capture and enter the game on a character"));
                panel.refresh();
                assertEquals(table, nothingHere(panel));
                panel.views().showGallery(true, false);
                assertEquals(List.of("No characters yet"), nothingHere(panel));
            });
        } finally {
            empty.close();
            PropertiesManager.setProperties(RosterViews.VIEW_KEY, view == null ? "" : view);
        }
    }

    /** "Characters unavailable" is the unreadable journal's one message; the Table view says it once, in the footer. */
    @Test public void anUnreadableJournalIsUnavailableOnceInEitherView() throws Exception {
        String view = PropertiesManager.getProperty(RosterViews.VIEW_KEY);
        PropertiesManager.setProperties(RosterViews.VIEW_KEY, "gallery");
        RosterDefinitions definitions = CharacterFixtures.definitions();
        Path file = temp.getRoot().toPath().resolve("unreadable").resolve("journal.json");
        Files.createDirectories(file.getParent());
        Files.write(file, "{ not a journal".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        CharacterJournal unreadable = new CharacterJournal(file);
        try {
            SwingUtilities.invokeAndWait(() -> {
                assertFalse("Sanity: the journal cannot be read", unreadable.readable());
                CharacterJournalGUI panel = new CharacterJournalGUI(unreadable, () -> NOW, () -> definitions);
                panel.refresh();
                assertNotNull(named(panel, "character-gallery-unavailable", tomato.gui.kit.EmptyState.class));
                assertEquals("Gallery: the unavailable state alone", List.of("Characters unavailable"), nothingHere(panel));
                panel.views().showGallery(false, false);
                List<String> table = nothingHere(panel);
                assertEquals("Table: the footer names it once: " + table, 1, table.size());
                assertTrue(table.get(0), table.get(0).startsWith("Cannot read Characters/journal.json"));
            });
        } finally {
            unreadable.close();
            PropertiesManager.setProperties(RosterViews.VIEW_KEY, view == null ? "" : view);
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

    /** Texts that say there is nothing to show; an EmptyState counts once, by its title. */
    private static final List<String> NOTHING_HERE = List.of("No characters yet", "No characters match", "Characters unavailable",
        "Your saved characters will appear here", "Start capture", "No matching characters", "Cannot read");
    /** The visible "nothing here" messages under {@code root} (visible up to it: there is no window). */
    private static List<String> nothingHere(Container root) {
        List<String> found = new ArrayList<>();
        for (String text : shownText(root)) if (NOTHING_HERE.stream().anyMatch(text::contains)) found.add(text);
        return found;
    }
    /** Every visible EmptyState's title and every visible, non-empty text area's text under {@code root}. */
    private static List<String> shownText(Container root) {
        List<String> texts = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (!child.isVisible()) continue;
            if (child instanceof tomato.gui.kit.EmptyState) texts.add(child.getAccessibleContext().getAccessibleName());
            else if (child instanceof JTextArea) { String text = ((JTextArea) child).getText(); if (!text.isEmpty()) texts.add(text); }
            else if (child instanceof Container) texts.addAll(shownText((Container) child));
        }
        return texts;
    }
    /** Whether the card at {@code index} intersects the list's current visible rectangle (i.e. is on-screen, at least partly). */
    private static boolean visible(JList<?> list, int index) {
        Rectangle cell = list.getCellBounds(index, index);
        return cell != null && list.getVisibleRect().intersects(cell);
    }
    private static int indexOfKey(JList<?> list, String key) {
        for (int i = 0; i < list.getModel().getSize(); i++) if (((CharacterCardModel) list.getModel().getElementAt(i)).key().equals(key)) return i;
        throw new AssertionError("Key not found in gallery: " + key);
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
