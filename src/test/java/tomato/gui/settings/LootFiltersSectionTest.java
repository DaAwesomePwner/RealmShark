package tomato.gui.settings;

import java.awt.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import tomato.gui.kit.SectionHeader;
import tomato.gui.kit.Tokens;
import tomato.gui.maingui.TomatoMenuBar;
import tomato.gui.modern.Themes;
import tomato.gui.stats.LootFilters;
import ui.VisualEvidence;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.settings.SettingsPageTest.named;

/** P6a: Settings › Loot filters edits the same LootFilters model as Edit › Filter Loot, both ways. */
public class LootFiltersSectionTest {
    @Rule public final VisualEvidence evidence = new VisualEvidence("redesign-p6a-settings");
    @Rule public final ErrorCollector errors = new ErrorCollector();
    private static final String[] KINDS = {"white", "orange", "red", "gold", "egg", "blue", "teal", "purple", "pink", "brown"};
    private final String[] saved = new String[LootFilters.Kind.values().length];
    private final List<String> openedSettings = new ArrayList<>();
    private Object sniffer;

    @Before public void isolateFilterKeys() throws Exception {
        PropertiesManager.flush().toCompletableFuture().get(3, TimeUnit.SECONDS);
        for (LootFilters.Kind kind : LootFilters.Kind.values()) {
            saved[kind.ordinal()] = PropertiesManager.getProperty(kind.key());
            preferences().remove(kind.key());
        }
        sniffer = snifferField().get(null); // TomatoMenuBar.make() replaces the shared capture item; restored below
    }

    @After public void restoreFilterKeys() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
        snifferField().set(null, sniffer);
        PropertiesManager.flush().toCompletableFuture().get(3, TimeUnit.SECONDS);
        for (LootFilters.Kind kind : LootFilters.Kind.values()) {
            String value = saved[kind.ordinal()];
            if (value == null) preferences().remove(kind.key()); else PropertiesManager.setProperties(kind.key(), value);
        }
        PropertiesManager.flush().toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    @Test public void tenBagColorsInTheMenusOrderWithTheNote() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LootFiltersSection section = new LootFiltersSection();
            assertEquals("settings-loot-filters", section.getName());
            assertNotNull(VisualEvidence.find(section, SectionHeader.class, header -> "Loot filters".equals(header.title())));
            assertEquals("Which bag colors show in Loot › Highlights' notable drops. Tiles and counts always include every observed drop. "
                + "Bag sounds are under Notifications.", named(section, "settings-loot-filters-help", JTextArea.class).getText());
            LootFilters.Kind[] kinds = LootFilters.Kind.values();
            assertEquals(KINDS.length, kinds.length);
            List<JCheckBox> boxes = checkboxes(section);
            assertEquals("Exactly the ten bag colors", KINDS.length, boxes.size());
            for (int i = 0; i < KINDS.length; i++) {
                assertEquals("settings-loot-filter-" + KINDS[i], boxes.get(i).getName());
                assertEquals(kinds[i].label(), boxes.get(i).getText());
                assertTrue("Absent keys show every color", boxes.get(i).isSelected());
            }
            AbstractButton showAll = named(section, "settings-loot-filters-show-all", AbstractButton.class);
            assertEquals("Show all", showAll.getText());
            assertFalse("Nothing to reset while every color shows", showAll.isEnabled());
        });
    }

    @Test public void checkboxesWriteLootFiltersAndFollowTheModelAndTheMenu() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PropertiesManager.setProperties(LootFilters.Kind.GOLD.key(), "false");
            LootFiltersSection section = new LootFiltersSection();
            JMenu menu = (JMenu) find(new TomatoMenuBar().make(), "Filter Loot");
            JCheckBox white = named(section, "settings-loot-filter-white", JCheckBox.class);
            JCheckBox brown = named(section, "settings-loot-filter-brown", JCheckBox.class);
            JCheckBox red = named(section, "settings-loot-filter-red", JCheckBox.class);
            assertFalse("A saved hidden color loads hidden", named(section, "settings-loot-filter-gold", JCheckBox.class).isSelected());
            JCheckBoxMenuItem menuWhite = (JCheckBoxMenuItem) menu.getMenuComponent(0), menuBrown = (JCheckBoxMenuItem) menu.getMenuComponent(9);

            white.doClick();
            assertEquals("The menu's key and value", "false", PropertiesManager.getProperty("filterWhiteBag"));
            assertFalse(LootFilters.get().shows(LootFilters.Kind.WHITE));
            assertFalse("Edit › Filter Loot follows the section", menuWhite.isSelected());
            menuBrown.doClick();
            assertFalse("The section follows Edit › Filter Loot", brown.isSelected());
            LootFilters.get().set(LootFilters.Kind.RED, false);
            assertFalse("The section follows any other editor", red.isSelected());
            white.doClick();
            assertEquals("true", PropertiesManager.getProperty("filterWhiteBag"));
            assertTrue(menuWhite.isSelected());
        });
    }

    @Test public void showAllResetsEveryColorToShown() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LootFiltersSection section = new LootFiltersSection();
            AbstractButton showAll = named(section, "settings-loot-filters-show-all", AbstractButton.class);
            named(section, "settings-loot-filter-teal", JCheckBox.class).doClick();
            LootFilters.get().set(LootFilters.Kind.PINK, false);
            LootFilters.get().set(LootFilters.Kind.WHITE, false);
            assertTrue("Show all is offered once a color is hidden", showAll.isEnabled());
            showAll.doClick();
            for (LootFilters.Kind kind : LootFilters.Kind.values()) {
                assertTrue(kind + " shown again", LootFilters.get().shows(kind));
                String value = PropertiesManager.getProperty(kind.key());
                assertTrue(kind + " is absent or \"true\": " + value, value == null || "true".equals(value));
            }
            for (JCheckBox box : checkboxes(section)) assertTrue(box.getName(), box.isSelected());
            assertFalse(showAll.isEnabled());
        });
    }

    /** With the settings hook, Edit › Filter Loot ends with a link to this section; without it the menu is exactly as before. */
    @Test public void filterLootMenuLinksToTheSectionOnlyWhileTheHookIsSet() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TomatoMenuBar bar = new TomatoMenuBar();
            JMenuBar menus = bar.make();
            JMenu filter = (JMenu) find(menus, "Filter Loot");
            List<String> today = texts(filter);
            assertEquals("No hook: the ten checkboxes only", 10, today.size());
            bar.onOpenSettings(openedSettings::add);
            List<String> linked = texts(filter);
            assertEquals(today, linked.subList(0, 10));
            assertEquals(List.of("—", "Loot filter settings…"), linked.subList(10, linked.size()));
            JMenuItem open = (JMenuItem) filter.getMenuComponent(filter.getMenuComponentCount() - 1);
            open.doClick();
            assertEquals(List.of(SettingsPage.LOOT_FILTERS), openedSettings);
            bar.onOpenSettings(openedSettings::add);
            assertEquals("Setting the hook again adds nothing twice", linked, texts(filter));
            bar.onOpenSettings(null);
            assertEquals("Clearing the hook restores today's menu", today, texts(filter));

            TomatoMenuBar early = new TomatoMenuBar();
            early.onOpenSettings(openedSettings::add);
            assertEquals("A hook set before make() applies when the menus are built", linked, texts((JMenu) find(early.make(), "Filter Loot")));
        });
    }

    @Test public void fitsAt680By520WithFont18AndFollowsTheTheme() throws Exception {
        SettingsPage[] page = new SettingsPage[1];
        SwingUtilities.invokeAndWait(() -> {
            LootFilters.get().set(LootFilters.Kind.EGG, false);
            page[0] = new SettingsPage(new JPanel(), () -> {}, new JPanel(), new JPanel(), new LootFiltersSection(), new JPanel(), new JPanel());
            page[0].showSection(SettingsPage.LOOT_FILTERS);
            evidence.show(page[0], "Settings Loot filters", 680, 520, 18);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            LootFiltersSection section = VisualEvidence.find(page[0], LootFiltersSection.class, component -> true);
            JViewport viewport = VisualEvidence.find(section, JScrollPane.class, scroll -> true).getViewport();
            assertEquals("The section never scrolls sideways", viewport.getWidth(), viewport.getView().getWidth());
            for (JCheckBox box : checkboxes(section)) VisualEvidence.completeButton(box);
            VisualEvidence.completeButton(named(section, "settings-loot-filters-show-all", AbstractButton.class));
            VisualEvidence.completeText(named(section, "settings-loot-filters-help", JTextArea.class));
            evidence.capture("settings-loot-filters-680-18");
            Themes.install(new Themes.Choice(Themes.Variant.LIGHT, false));
            SwingUtilities.updateComponentTreeUI(page[0]);
            assertEquals("The note follows the theme", Tokens.color(Tokens.Role.TEXT_MUTED),
                named(section, "settings-loot-filters-help", JTextArea.class).getForeground());
        });
        SwingUtilities.invokeAndWait(() -> evidence.show(page[0], "Settings Loot filters light", 1240, 800, 13));
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            LootFiltersSection section = VisualEvidence.find(page[0], LootFiltersSection.class, component -> true);
            for (JCheckBox box : checkboxes(section)) VisualEvidence.completeButton(box);
            evidence.capture("settings-loot-filters-light-1240-13");
        });
    }

    /**
     * Polish A: the ten colors pack into two columns at their natural width (the widest label) with a normal gap, from the section's
     * left edge (where Show all starts), instead of spreading across the page; below about 420 px they stack in one column.
     */
    @Test public void theColorsPackIntoTwoNaturalColumnsAndStackWhenNarrow() throws Exception {
        SettingsPage[] page = new SettingsPage[1];
        LootFiltersSection[] narrow = new LootFiltersSection[1];
        SwingUtilities.invokeAndWait(() -> {
            page[0] = new SettingsPage(new JPanel(), () -> {}, new JPanel(), new JPanel(), new LootFiltersSection(), new JPanel(), new JPanel());
            page[0].showSection(SettingsPage.LOOT_FILTERS);
            evidence.show(page[0], "Settings Loot filters 1240", 1240, 800, 13);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            captureAndCheck(section(page[0]), 2, "1240x800 font 13", "settings-loot-filters-columns-1240-13-dark");
            evidence.show(page[0], "Settings Loot filters 680", 680, 520, 18);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            captureAndCheck(section(page[0]), 2, "680x520 font 18", "settings-loot-filters-columns-680-18-dark");
            Themes.install(new Themes.Choice(Themes.Variant.LIGHT, false));
            SwingUtilities.updateComponentTreeUI(SwingUtilities.getWindowAncestor(page[0]));
            evidence.show(page[0], "Settings Loot filters 1240 light", 1240, 800, 13);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            captureAndCheck(section(page[0]), 2, "1240x800 font 13, light", "settings-loot-filters-columns-1240-13-light");
            narrow[0] = new LootFiltersSection();
            JPanel host = new JPanel(new BorderLayout());
            host.add(narrow[0]);
            evidence.show(host, "Settings Loot filters narrow", 400, 620, 13);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            assertTrue("A narrow section: " + narrow[0].getWidth(), narrow[0].getWidth() < 420);
            captureAndCheck(narrow[0], 1, "400 px wide, font 13", "settings-loot-filters-columns-400-13-light");
            evidence.show((JComponent) narrow[0].getParent(), "Settings Loot filters widened", 900, 620, 13);
        });
        // The same section follows its width both ways: its height (the rows) is laid out again after the width changes.
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> {
            errors.checkSucceeds(() -> { assertColumns(narrow[0], 2, "widened to 900 px"); return null; });
            evidence.show((JComponent) narrow[0].getParent(), "Settings Loot filters narrowed", 400, 620, 13);
        });
        evidence.settle();
        SwingUtilities.invokeAndWait(() -> errors.checkSucceeds(() -> { assertColumns(narrow[0], 1, "narrowed to 400 px again"); return null; }));
    }

    private static LootFiltersSection section(SettingsPage page) { return VisualEvidence.find(page, LootFiltersSection.class, component -> true); }

    /** Captures first, then checks; the collector reports every size's failure at the end, so each size is captured and checked. */
    private void captureAndCheck(LootFiltersSection section, int columns, String size, String capture) {
        evidence.capture(capture);
        errors.checkSucceeds(() -> { assertColumns(section, columns, size); return null; });
    }

    /** The boxes in {@code columns} columns, row by row in the menu's order, each column as wide as the widest label. */
    private static void assertColumns(LootFiltersSection section, int columns, String size) {
        List<JCheckBox> boxes = checkboxes(section);
        Component view = VisualEvidence.find(section, JScrollPane.class, scroll -> true).getViewport().getView();
        int widest = 0;
        for (JCheckBox box : boxes) {
            VisualEvidence.completeButton(box);
            widest = Math.max(widest, box.getPreferredSize().width);
        }
        int first = x(boxes.get(0), view);
        System.out.println(size + ": boxes at " + first + " and " + x(boxes.get(1), view) + ", widest " + widest + ", Show all at "
            + x(named(section, "settings-loot-filters-show-all", JComponent.class), view) + ", section " + section.getWidth());
        for (int i = 0; i < boxes.size(); i++) {
            JCheckBox box = boxes.get(i), rowStart = boxes.get(i - i % columns);
            assertEquals(size + ": " + box.getName() + " is in column " + i % columns, first + (i % columns) * (widest + Tokens.XL), x(box, view), 1);
            assertEquals(size + ": " + box.getName() + " is in row " + i / columns, rowStart.getY(), box.getY());
            if (i >= columns) assertTrue(size + ": row " + i / columns + " is below the one before", box.getY() > boxes.get(i - columns).getY());
        }
        JComponent showAll = named(section, "settings-loot-filters-show-all", JComponent.class);
        assertEquals(size + ": the boxes start where Show all starts", x(showAll, view), first, 1);
        JCheckBox last = boxes.get(boxes.size() - 1);
        assertTrue(size + ": Show all is below the last row", SwingUtilities.convertPoint(showAll.getParent(), 0, showAll.getY(), view).y
            >= SwingUtilities.convertPoint(last.getParent(), 0, last.getY() + last.getHeight(), view).y);
    }

    private static int x(Component component, Component root) { return SwingUtilities.convertPoint(component.getParent(), component.getX(), 0, root).x; }

    private static List<JCheckBox> checkboxes(Container root) {
        List<JCheckBox> boxes = new ArrayList<>();
        collect(root, boxes);
        return boxes;
    }

    private static void collect(Container root, List<JCheckBox> boxes) {
        for (Component child : root.getComponents()) {
            if (child instanceof JCheckBox) boxes.add((JCheckBox) child);
            else if (child instanceof Container) collect((Container) child, boxes);
        }
    }

    /** Menu texts in order; a separator reads "—". */
    static List<String> texts(JMenu menu) {
        List<String> texts = new ArrayList<>();
        for (Component item : menu.getMenuComponents()) texts.add(item instanceof JMenuItem ? ((JMenuItem) item).getText() : "—");
        return texts;
    }

    static JMenuItem find(MenuElement root, String text) {
        for (MenuElement child : root.getSubElements()) {
            if (child instanceof JMenuItem && text.equals(((JMenuItem) child).getText())) return (JMenuItem) child;
            JMenuItem found = find(child, text);
            if (found != null) return found;
        }
        return null;
    }

    static Field snifferField() throws Exception {
        Field field = TomatoMenuBar.class.getDeclaredField("sniffer");
        field.setAccessible(true);
        return field;
    }

    private static Properties preferences() throws Exception {
        Field field = PropertiesManager.class.getDeclaredField("properties"); field.setAccessible(true);
        return (Properties) field.get(null);
    }
}
