package tomato.gui.stats;

import java.awt.Component;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.MenuElement;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import tomato.gui.maingui.TomatoMenuBar;
import tomato.realmshark.enums.LootBags;
import util.PropertiesManager;
import static org.junit.Assert.*;

/** P6a: one Filter Loot model for the menu, a later Settings section and Loot › Highlights. */
public class LootFiltersTest {
    private static final String[] KEYS = {"filterWhiteBag", "filterOrangeBag", "filterRedBag", "filterGoldBag", "filterEggBag",
        "filterBlueBag", "filterTealBag", "filterPurpleBag", "filterPinkBag", "filterBrownBag"};
    private final String[] saved = new String[KEYS.length];
    private final List<Runnable> added = new ArrayList<>();

    @Before public void clearKeys() throws Exception {
        PropertiesManager.flush().toCompletableFuture().get(3, TimeUnit.SECONDS);
        for (int i = 0; i < KEYS.length; i++) { saved[i] = PropertiesManager.getProperty(KEYS[i]); preferences().remove(KEYS[i]); }
    }

    @After public void restoreKeys() throws Exception {
        for (Runnable listener : added) LootFilters.get().removeListener(listener);
        SwingUtilities.invokeAndWait(() -> { });
        PropertiesManager.flush().toCompletableFuture().get(3, TimeUnit.SECONDS);
        for (int i = 0; i < KEYS.length; i++) {
            if (saved[i] == null) preferences().remove(KEYS[i]); else PropertiesManager.setProperties(KEYS[i], saved[i]);
        }
        PropertiesManager.flush().toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    @Test public void keysLabelsAndOrderAreUnchanged() {
        LootFilters.Kind[] kinds = LootFilters.Kind.values();
        assertEquals(KEYS.length, kinds.length);
        for (int i = 0; i < KEYS.length; i++) assertEquals(KEYS[i], kinds[i].key());
        assertEquals(Arrays.asList("White bags", "Orange bags", "Red bags", "Gold bags", "Egg bags", "Blue bags", "Teal bags",
            "Purple bags", "Pink bags", "Brown bags"), labels(kinds));
    }

    @Test public void anAbsentPropertyIsShownAndOnlyTrueShowsWhenSet() {
        LootFilters filters = LootFilters.get();
        for (LootFilters.Kind kind : LootFilters.Kind.values()) assertTrue(kind + " defaults to shown", filters.shows(kind));
        for (LootBags bag : LootBags.values()) assertTrue(bag + " shown by default", filters.showsBag(bag.getId()));
        filters.set(LootFilters.Kind.WHITE, false);
        assertEquals("false", PropertiesManager.getProperty("filterWhiteBag"));
        assertFalse(filters.shows(LootFilters.Kind.WHITE));
        assertFalse(filters.showsBag(LootBags.WHITE.getId())); assertFalse(filters.showsBag(LootBags.BOOSTED_WHITE.getId()));
        assertFalse(filters.showsBagName("White")); assertFalse(filters.showsBagName("B.White"));
        assertTrue("Other kinds are untouched", filters.showsBag(LootBags.ORANGE.getId()));
        filters.set(LootFilters.Kind.WHITE, true);
        assertEquals("true", PropertiesManager.getProperty("filterWhiteBag"));
        assertTrue(filters.showsBag(LootBags.BOOSTED_WHITE.getId()));
        // As the menu read it: anything but an absent value or "true" hides the kind.
        PropertiesManager.setProperties("filterRedBag", "yes");
        assertFalse(filters.shows(LootFilters.Kind.RED));
    }

    @Test public void bagKindsIncludeBoostedVariantsAndUnknownOrOtherBagsAreAlwaysShown() {
        LootBags[][] pairs = {{LootBags.WHITE, LootBags.BOOSTED_WHITE}, {LootBags.ORANGE, LootBags.BOOSTED_ORANGE},
            {LootBags.RED, LootBags.BOOSTED_RED}, {LootBags.GOLD, LootBags.BOOSTED_GOLD}, {LootBags.EGG, LootBags.BOOSTED_EGG},
            {LootBags.BLUE, LootBags.BOOSTED_BLUE}, {LootBags.TEAL, LootBags.BOOSTED_TEAL}, {LootBags.PURPLE, LootBags.BOOSTED_PURPLE},
            {LootBags.PINK, LootBags.BOOSTED_PINK}, {LootBags.BROWN, LootBags.BOOSTED_BROWN}};
        LootFilters.Kind[] kinds = LootFilters.Kind.values();
        for (int i = 0; i < pairs.length; i++) {
            for (LootBags bag : pairs[i]) {
                assertEquals(bag.toString(), kinds[i], LootFilters.of(bag.getId()));
                assertEquals(bag.toString(), kinds[i], LootFilters.ofBagName(LootBags.lootBagName(bag.getId())));
            }
        }
        assertEquals("The persisted plain egg name", LootFilters.Kind.EGG, LootFilters.ofBagName("Egg Basket"));
        assertEquals(LootFilters.Kind.EGG, LootFilters.ofBagName("B.Egg"));
        assertNull(LootFilters.of(LootBags.SOULBOUND.getId())); assertNull(LootFilters.of(0)); assertNull(LootFilters.of(123456));
        for (String other : new String[]{null, "", "Soulbound", "Unknown (1234)", "white", "B."}) assertNull(other, LootFilters.ofBagName(other));
        LootFilters filters = LootFilters.get();
        for (LootFilters.Kind kind : kinds) filters.set(kind, false);
        assertTrue("Other bags are always shown", filters.showsBag(LootBags.SOULBOUND.getId()));
        assertTrue(filters.showsBag(123456));
        assertTrue(filters.showsBagName(null)); assertTrue(filters.showsBagName("Unknown (1234)"));
        assertFalse(filters.showsBagName("Egg Basket"));
    }

    @Test public void listenersRunOnTheEdt() throws Exception {
        LootFilters filters = LootFilters.get();
        List<Boolean> onEdt = new CopyOnWriteArrayList<>();
        CountDownLatch fired = new CountDownLatch(1);
        Runnable listener = () -> { onEdt.add(SwingUtilities.isEventDispatchThread()); fired.countDown(); };
        filters.addListener(listener); added.add(listener);
        Thread background = new Thread(() -> filters.set(LootFilters.Kind.TEAL, false), "loot-filters-test");
        background.start(); background.join(5000);
        assertTrue(fired.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            filters.set(LootFilters.Kind.TEAL, true);
            assertEquals("A change made on the EDT notifies before set returns", 2, onEdt.size());
        });
        assertEquals(Arrays.asList(true, true), onEdt);
        filters.removeListener(listener);
        SwingUtilities.invokeAndWait(() -> filters.set(LootFilters.Kind.TEAL, false));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals("Removed listeners are not called", 2, onEdt.size());
    }

    @Test public void filterLootMenuWritesAndFollowsTheModel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JMenuBar bar = new TomatoMenuBar().make();
            JMenu menu = (JMenu)find(bar, "Filter Loot");
            assertNotNull(menu);
            List<String> texts = new ArrayList<>();
            for (Component item : menu.getMenuComponents()) texts.add(((JMenuItem)item).getText());
            assertEquals(Arrays.asList("Show White Bags", "Show Orange Bags", "Show Red Bags", "Show Gold Bags", "Show Egg Bags",
                "Show Blue Bags", "Show Teal Bags", "Show Purple Bags", "Show Pink Bags", "Show Brown Bags"), texts);
            JCheckBoxMenuItem white = (JCheckBoxMenuItem)menu.getMenuComponent(0), brown = (JCheckBoxMenuItem)menu.getMenuComponent(9);
            assertTrue("Absent keys load as shown", white.isSelected() && brown.isSelected());
            white.doClick();
            assertEquals("false", PropertiesManager.getProperty("filterWhiteBag"));
            assertFalse(LootFilters.get().shows(LootFilters.Kind.WHITE));
            LootFilters.get().set(LootFilters.Kind.BROWN, false);
            assertFalse("The checkbox follows a change made elsewhere", brown.isSelected());
            LootFilters.get().set(LootFilters.Kind.WHITE, true);
            assertTrue(white.isSelected());
        });
    }

    private static List<String> labels(LootFilters.Kind[] kinds) {
        List<String> result = new ArrayList<>();
        for (LootFilters.Kind kind : kinds) result.add(kind.label());
        return result;
    }

    private static JMenuItem find(MenuElement root, String text) {
        for (MenuElement child : root.getSubElements()) {
            if (child instanceof JMenuItem && text.equals(((JMenuItem)child).getText())) return (JMenuItem)child;
            JMenuItem found = find(child, text);
            if (found != null) return found;
        }
        return null;
    }

    private static Properties preferences() throws Exception {
        Field field = PropertiesManager.class.getDeclaredField("properties"); field.setAccessible(true);
        return (Properties)field.get(null);
    }
}
