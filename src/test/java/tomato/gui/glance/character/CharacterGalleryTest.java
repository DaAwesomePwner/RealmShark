package tomato.gui.glance.character;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.*;
import javax.swing.*;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import org.junit.*;
import tomato.gui.kit.Banner;
import tomato.gui.kit.Collapsible;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.Tokens;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeModels.named;

/** The gallery: living cards and a collapsed Graveyard, accessible names, Enter/Space/double-click, selection and empty states. */
public class CharacterGalleryTest {
    private static final long NOW = System.currentTimeMillis(), HOUR = 3_600_000L;
    private static final String GRAVEYARD_KEY = Collapsible.PREFIX + CharacterGallery.GRAVEYARD;
    private static final List<CharacterCardModel> ALIVE = List.of(
        CharacterFixtures.card(101, "Wizard", 20, 1_234L, 7, false, true, false, NOW - HOUR),
        CharacterFixtures.card(102, "Warrior", 20, 5_400L, 8, true, false, false, NOW - 2 * HOUR),
        CharacterFixtures.card(104, "Knight", 14, null, null, null, false, false, NOW - 3 * HOUR));
    private static final List<CharacterCardModel> DEAD = List.of(
        CharacterFixtures.card(107, "Necromancer", 20, 640L, 4, false, false, true, NOW - 5 * HOUR),
        CharacterFixtures.card(108, "Paladin", 18, 95L, 0, false, false, true, NOW - 6 * HOUR));
    private final Map<String, String> prefs = new HashMap<>();
    private final DisplayModeModel mode = new DisplayModeModel(prefs::get, prefs::put); // application() is never changed
    private final List<String> opened = new ArrayList<>();
    private String graveyard;
    private Locale format;

    @Before public void isolate() {
        graveyard = PropertiesManager.getProperty(GRAVEYARD_KEY);
        PropertiesManager.setProperties(GRAVEYARD_KEY, "");
        format = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
    }
    @After public void restore() {
        PropertiesManager.setProperties(GRAVEYARD_KEY, graveyard == null ? "" : graveyard);
        Locale.setDefault(Locale.Category.FORMAT, format);
    }

    @Test public void livingCardsFillTheGalleryAndTheDeadACollapsedGraveyard() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterGallery gallery = new CharacterGallery(opened::add, mode);
            gallery.apply(ALIVE, DEAD);
            JList<CharacterCardModel> cards = list(gallery, "character-cards"), graves = list(gallery, "character-graveyard-cards");
            assertEquals(ALIVE, gallery.alive());
            assertEquals(DEAD, gallery.dead());
            assertEquals(3, cards.getModel().getSize());
            assertEquals(2, graves.getModel().getSize());
            assertEquals("A wrapping grid of cards", JList.HORIZONTAL_WRAP, cards.getLayoutOrientation());
            CharacterCardRenderer renderer = (CharacterCardRenderer) cards.getCellRenderer();
            assertEquals("Fixed cells: no per-card measuring", renderer.cellSize(), new java.awt.Dimension(cards.getFixedCellWidth(), cards.getFixedCellHeight()));
            Collapsible section = named(gallery, "character-graveyard", Collapsible.class);
            assertTrue(section.isVisible());
            assertEquals("Graveyard (2)", section.toggle().getText());
            assertFalse("The Graveyard starts collapsed", section.expanded());
            gallery.apply(ALIVE, List.of());
            assertFalse("No Graveyard without dead characters", section.isVisible());
        });
    }

    @Test public void eachCardHasAnAccessibleNameAndUnknownIsNeverZero() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterGallery gallery = new CharacterGallery(opened::add, mode);
            gallery.apply(ALIVE, DEAD);
            JList<CharacterCardModel> cards = list(gallery, "character-cards"), graves = list(gallery, "character-graveyard-cards");
            assertEquals("Characters", cards.getAccessibleContext().getAccessibleName());
            assertEquals("Sample, Wizard level 20, 7 of 8 maxed, playing now", child(cards, 0));
            assertEquals("Sample, Knight level 14, maxed stats unknown", child(cards, 2));
            assertEquals("Sample, Necromancer level 20, 4 of 8 maxed, marked dead", child(graves, 0));
        });
    }

    @Test public void enterSpaceAndDoubleClickOpenTheSelectedCharacter() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterGallery gallery = new CharacterGallery(opened::add, mode);
            gallery.apply(ALIVE, DEAD);
            JList<CharacterCardModel> cards = list(gallery, "character-cards");
            for (String key : new String[] {"ENTER", "SPACE"})
                assertEquals(key + " opens the card", "open-character", cards.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key)));
            cards.setSelectedIndex(1);
            cards.getActionMap().get("open-character").actionPerformed(null);
            assertEquals(List.of(ALIVE.get(1).key()), opened);
            Rectangle cell = cards.getCellBounds(2, 2);
            cards.dispatchEvent(click(cards, cell, 1));
            assertEquals("A single click only selects", 1, opened.size());
            cards.dispatchEvent(click(cards, cell, 2));
            assertEquals(List.of(ALIVE.get(1).key(), ALIVE.get(2).key()), opened);
            JList<CharacterCardModel> graves = list(gallery, "character-graveyard-cards");
            graves.setSelectedIndex(0);
            graves.getActionMap().get("open-character").actionPerformed(null);
            assertEquals("Graveyard cards open their sheet too", DEAD.get(0).key(), opened.get(2));
        });
    }

    @Test public void aRefreshKeepsTheSelectedCharacterAndAnEqualOneChangesNothing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterGallery gallery = new CharacterGallery(opened::add, mode);
            gallery.apply(ALIVE, DEAD);
            JList<CharacterCardModel> cards = list(gallery, "character-cards");
            cards.setSelectedIndex(1);
            List<CharacterCardModel> reordered = List.of(ALIVE.get(2), ALIVE.get(0), ALIVE.get(1));
            gallery.apply(reordered, DEAD);
            assertEquals("The selection follows the character, not the index", ALIVE.get(1).key(), cards.getSelectedValue().key());
            int[] events = new int[1];
            cards.getModel().addListDataListener(new ListDataListener() {
                @Override public void intervalAdded(ListDataEvent e) { events[0]++; }
                @Override public void intervalRemoved(ListDataEvent e) { events[0]++; }
                @Override public void contentsChanged(ListDataEvent e) { events[0]++; }
            });
            gallery.apply(new ArrayList<>(reordered), DEAD);
            assertEquals("Equal cards are not applied again", 0, events[0]);
        });
    }

    @Test public void emptyStatesSayWhetherFiltersHideSavedCharacters() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterGallery gallery = new CharacterGallery(opened::add, mode);
            gallery.apply(List.of(), List.of(), false);
            assertNotNull("Nothing saved yet", named(gallery, "character-gallery-empty", EmptyState.class));
            assertNull(named(gallery, "character-cards", JList.class));
            gallery.apply(List.of(), List.of(), true);
            assertNotNull("Saved characters hidden by filters", named(gallery, "character-gallery-no-match", EmptyState.class));
            gallery.apply(ALIVE, List.of());
            assertNotNull(named(gallery, "character-cards", JList.class));
            assertNull(named(gallery, "character-gallery-no-match", EmptyState.class));
        });
    }

    @Test public void theGraveyardSitsRightBelowTheLivingCardsInATallPage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterGallery gallery = new CharacterGallery(opened::add, mode);
            gallery.apply(ALIVE, DEAD);
            gallery.setSize(900, 2_000); // the page's viewport is far taller than three cards
            for (int pass = 0; pass < 3; pass++) layout(gallery);
            JList<CharacterCardModel> cards = list(gallery, "character-cards");
            Collapsible graveyard = named(gallery, "character-graveyard", Collapsible.class);
            java.awt.Rectangle above = SwingUtilities.convertRectangle(cards.getParent(), cards.getBounds(), gallery);
            java.awt.Rectangle below = SwingUtilities.convertRectangle(graveyard.getParent(), graveyard.getBounds(), gallery);
            assertEquals("The cards keep their wrapped height", cards.getPreferredSize().height, above.height);
            assertTrue("The Graveyard follows the cards, not the page's bottom: " + above + " then " + below,
                below.y - (above.y + above.height) <= Tokens.L + 1);
        });
    }

    @Test public void anUnreadableJournalIsUnavailableAndAFailedSaveWarnsAboveTheCards() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterGallery gallery = new CharacterGallery(opened::add, mode);
            String unreadable = "Cannot read Characters/journal.json. Original preserved; saving disabled.";
            gallery.apply(List.of(), List.of(), false, unreadable);
            EmptyState unavailable = named(gallery, "character-gallery-unavailable", EmptyState.class);
            assertNotNull("Not \"No characters yet\"", unavailable);
            assertNull(named(gallery, "character-gallery-empty", EmptyState.class));
            assertEquals(unreadable, unavailable.getAccessibleContext().getAccessibleDescription());
            gallery.apply(ALIVE, DEAD, false, "Save failed • check access to Characters/journal.json");
            Banner storage = named(gallery, "character-gallery-storage", Banner.class);
            assertTrue(storage.isVisible()); assertTrue(storage.warns());
            assertEquals("Save failed • check access to Characters/journal.json", storage.text());
            gallery.apply(ALIVE, DEAD, false, null);
            assertFalse(storage.isVisible());
        });
    }

    @Test public void aUserSelectionIsReportedOnceAcrossBothListsAndTheRosterCanSelectACard() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<String> selected = new ArrayList<>();
            CharacterGallery gallery = new CharacterGallery(opened::add, mode);
            gallery.onSelect(selected::add);
            gallery.apply(ALIVE, DEAD);
            JList<CharacterCardModel> cards = list(gallery, "character-cards"), graves = list(gallery, "character-graveyard-cards");
            cards.setSelectedIndex(2);
            assertEquals(List.of(ALIVE.get(2).key()), selected);
            graves.setSelectedIndex(0);
            assertEquals("One selection across the cards and the Graveyard", -1, cards.getSelectedIndex());
            assertEquals(List.of(ALIVE.get(2).key(), DEAD.get(0).key()), selected);
            assertSame("The Graveyard is collapsed: focus returns to the cards", cards, gallery.focusTarget());
            gallery.select(ALIVE.get(1).key());
            assertEquals(1, cards.getSelectedIndex()); assertEquals(-1, graves.getSelectedIndex());
            assertEquals("A selection made by the roster is not reported back", 2, selected.size());
        });
    }

    @Test public void analystModeShowsCharacterIdsOnTheCards() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterGallery gallery = new CharacterGallery(opened::add, mode);
            gallery.apply(ALIVE, DEAD);
            JList<CharacterCardModel> cards = list(gallery, "character-cards");
            CharacterCardRenderer renderer = (CharacterCardRenderer) cards.getCellRenderer();
            renderer.getListCellRendererComponent(cards, ALIVE.get(0), 0, false, false);
            assertEquals("Sample", renderer.shown().identity());
            mode.set(DisplayModeModel.Mode.ANALYST);
            renderer.getListCellRendererComponent(cards, ALIVE.get(0), 0, false, false);
            assertEquals("Sample · #101", renderer.shown().identity());
        });
    }

    @SuppressWarnings("unchecked")
    private static JList<CharacterCardModel> list(CharacterGallery gallery, String name) { return named(gallery, name, JList.class); }
    private static void layout(java.awt.Container root) {
        root.doLayout();
        for (java.awt.Component child : root.getComponents()) if (child instanceof java.awt.Container) layout((java.awt.Container) child);
    }
    private static String child(JList<?> list, int index) {
        return list.getAccessibleContext().getAccessibleChild(index).getAccessibleContext().getAccessibleName();
    }
    private static MouseEvent click(JList<?> list, Rectangle cell, int count) {
        return new MouseEvent(list, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, cell.x + cell.width / 2, cell.y + cell.height / 2,
            count, false, MouseEvent.BUTTON1);
    }
}
