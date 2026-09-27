package ui;

import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.ErrorCollector;
import org.junit.rules.TemporaryFolder;
import tomato.Tomato;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.chat.ChatGUI;
import tomato.gui.glance.character.CharacterFixtures;
import tomato.gui.glance.character.CharacterGallery;
import tomato.gui.glance.character.CharacterSheet;
import tomato.gui.glance.character.SheetFocus;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.EmptyState;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.myinfo.MyInfoGUI;
import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import util.PropertiesManager;
import static org.junit.Assert.*;

/**
 * P3a Characters evidence (spec §6.2, §11) in the real workspace: the gallery (populated, Graveyard open, no match, empty, the
 * Table view) and the sheet (Overview, Gear, Exalts, Build for the live and another character, a key not in the journal) at
 * 1240×800 and 680×520, fonts 13 and 18, Simple and Analyst: 21 screenshots. Synthetic journal, history and definitions;
 * preview mode; no capture.
 */
public class CharactersEvidenceTest {
    private static final Map<String, String> DEFAULTS = Map.of("chat.filters", "{}", "chat.showIgnoredPlayers", "false",
        "ui.characters.view", "", "ui.characters.sort", "", "ui.collapse.characters-graveyard", "", "ui.filters.characters.open", "");
    private static final DisplayModeModel.Mode SIMPLE = DisplayModeModel.Mode.SIMPLE, ANALYST = DisplayModeModel.Mode.ANALYST;
    private static final String ROSTER_KEY = "ux.archive.characters-live-roster";
    @Rule public final VisualEvidence evidence = new VisualEvidence("redesign-p3a-characters");
    @Rule public final TemporaryFolder temp = new TemporaryFolder();
    @Rule public final ErrorCollector errors = new ErrorCollector();
    private final Map<Field, Object> statics = new LinkedHashMap<>();
    private final Map<String, String> saved = new HashMap<>(), archive = new HashMap<>();
    /** The Characters roster's saved view goes here, not to the shared realmShark.properties (P3a finding 10). */
    private final tomato.gui.roster.RosterStateTestSupport.Memory views = new tomato.gui.roster.RosterStateTestSupport.Memory();
    private String applicationRoster;
    private DisplayModeModel.Mode savedMode;
    private String temporaryDirectory;
    private SessionStore store;
    private AutoCloseable definitions;
    private CharacterJournal journal;
    private TomatoData data;
    private TomatoGUI gui;
    private WorkspaceShell shell;

    @Before public void open() throws Exception {
        PropertiesManager.preload(); // merge the disk file first, so the archive keys snapshot below sees every saved key
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            saved.put(entry.getKey(), PropertiesManager.getProperty(entry.getKey()));
            PropertiesManager.setProperties(entry.getKey(), entry.getValue());
        }
        for (String key : archiveKeys()) { archive.put(key, PropertiesManager.getProperty(key)); PropertiesManager.setProperties(key, ""); }
        SwingUtilities.invokeAndWait(() -> savedMode = DisplayModeModel.application().mode());
        temporaryDirectory = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", temp.newFolder("scratch").getAbsolutePath());
        store = new SessionStore(temp.newFolder("history").toPath(), true, "p3a-characters");
        remember(AppHistory.class, "store", store);
        remember(Tomato.class, "preview", true);
        remember(TomatoGUI.class, "characterViewStates", views.store);
        applicationRoster = PropertiesManager.getProperty(ROSTER_KEY);
        for (Class<?> type : new Class<?>[] {TomatoGUI.class, ChatGUI.class})
            for (Field field : type.getDeclaredFields())
                if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers()) && !statics.containsKey(field)) {
                    field.setAccessible(true); statics.put(field, field.get(null));
                }
        definitions = CharacterFixtures.installDefinitions();
    }

    @After public void close() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            evidence.closeWindow();
            if (gui != null) gui.closeWorkspace();
            DisplayModeModel.application().set(savedMode);
            try { for (Map.Entry<Field, Object> entry : statics.entrySet()) entry.getKey().set(null, entry.getValue()); }
            catch (IllegalAccessException e) { throw new AssertionError(e); }
        });
        if (definitions != null) definitions.close();
        if (journal != null) journal.close();
        for (Map.Entry<String, String> entry : saved.entrySet())
            PropertiesManager.setProperties(entry.getKey(), entry.getValue() == null ? DEFAULTS.get(entry.getKey()) : entry.getValue());
        for (String key : archiveKeys()) { String value = archive.get(key); PropertiesManager.setProperties(key, value == null ? "" : value); }
        PropertiesManager.flush().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (temporaryDirectory != null) System.setProperty("java.io.tmpdir", temporaryDirectory);
        if (store != null) store.close();
    }

    /** 8 captures: populated at both sizes, fonts and modes, the Graveyard open, no match, and the Analyst Table view. */
    @Test public void galleryRendersPopulatedGraveyardNoMatchAndTableViews() throws Exception {
        build(CharacterFixtures.journal(temp.newFolder("journal").toPath().resolve("Characters").resolve("journal.json"), System.currentTimeMillis()), true);
        gallery("gallery", 1240, 800, 13, SIMPLE, () -> {});
        gallery("gallery", 1240, 800, 13, ANALYST, () -> {});
        gallery("gallery", 1240, 800, 18, SIMPLE, () -> {});
        gallery("gallery", 680, 520, 13, SIMPLE, () -> {});
        gallery("gallery", 680, 520, 18, ANALYST, () -> {});
        SwingUtilities.invokeAndWait(() -> {
            CharacterGallery gallery = VisualEvidence.find(shell, CharacterGallery.class, g -> true);
            errors.checkThat("Six living cards, one playing now", gallery.alive().stream().filter(c -> c.playingNow()).count(), org.hamcrest.CoreMatchers.is(1L));
            errors.checkThat(gallery.dead().size(), org.hamcrest.CoreMatchers.is(2));
        });
        gallery("gallery-graveyard", 1240, 800, 13, SIMPLE, () -> VisualEvidence.named(shell, "collapsible-characters-graveyard", AbstractButton.class).doClick());
        SwingUtilities.invokeAndWait(() -> VisualEvidence.named(shell, "collapsible-characters-graveyard", AbstractButton.class).doClick());
        gallery("gallery-no-match", 1240, 800, 13, SIMPLE, () -> VisualEvidence.named(shell, "character-search", JTextField.class).setText("no such character"));
        SwingUtilities.invokeAndWait(() -> {
            errors.checkSucceeds(() -> VisualEvidence.named(shell, "character-gallery-no-match", EmptyState.class));
            VisualEvidence.named(shell, "character-search", JTextField.class).setText("");
            DisplayModeModel.application().set(ANALYST);
            evidence.show(shell, "Characters table", 1240, 800, 13);
            VisualEvidence.named(shell, "character-view-1", AbstractButton.class).doClick();
        });
        pause();
        SwingUtilities.invokeAndWait(() -> {
            evidence.capture("p3a-table-1240-13-analyst");
            errors.checkSucceeds(() -> { assertTrue("The Table view shows the roster table", VisualEvidence.named(shell, "character-roster", JTable.class).isShowing()); return null; });
            assertNoViewStateWarning();
            VisualEvidence.named(shell, "character-view-0", AbstractButton.class).doClick();
        });
        SwingUtilities.invokeAndWait(() -> { }); // queued view-state saves run first
        errors.checkThat("The roster saved its view to the isolated store", views.writes > 0, org.hamcrest.CoreMatchers.is(true));
        errors.checkThat("…and never to the shared application preferences", PropertiesManager.getProperty(ROSTER_KEY),
            org.hamcrest.CoreMatchers.is(applicationRoster));
    }

    /** 12 captures: Overview, Gear and Exalts across sizes, fonts and modes; Build live (also 680×520 at font 18, Analyst) and for another character; an unknown key. */
    @Test public void sheetTabsRenderForTheLiveAndAnotherCharacter() throws Exception {
        build(CharacterFixtures.journal(temp.newFolder("journal").toPath().resolve("Characters").resolve("journal.json"), System.currentTimeMillis()), true);
        String key = CharacterFixtures.KEY, other = CharacterFixtures.ACCOUNT + ":102", missing = CharacterFixtures.ACCOUNT + ":999";
        sheet("overview", key, 1240, 800, 13, SIMPLE);
        sheet("overview", key, 1240, 800, 13, ANALYST);
        sheet("overview", key, 680, 520, 13, SIMPLE);
        sheet("overview", key, 1240, 800, 18, SIMPLE);
        sheet("gear", key, 1240, 800, 13, SIMPLE);
        sheet("gear", key, 680, 520, 18, SIMPLE);
        sheet("exalts", key, 1240, 800, 13, SIMPLE);
        sheet("exalts", key, 680, 520, 13, ANALYST);
        sheet("build", key, 1240, 800, 13, SIMPLE);
        sheet("build", key, 680, 520, 18, ANALYST);
        sheet("build", other, 1240, 800, 13, SIMPLE);
        sheet("overview", missing, 1240, 800, 13, SIMPLE);
    }

    /** 1 capture: no saved character at all. */
    @Test public void anEmptyJournalShowsTheGalleryEmptyState() throws Exception {
        build(new CharacterJournal(temp.newFolder("journal").toPath().resolve("Characters").resolve("journal.json")), false);
        gallery("gallery-empty", 1240, 800, 13, SIMPLE, () -> {});
        SwingUtilities.invokeAndWait(() -> errors.checkSucceeds(() -> {
            assertTrue(VisualEvidence.named(shell, "character-gallery-empty", EmptyState.class).isShowing()); return null;
        }));
    }

    private void build(CharacterJournal characters, boolean live) throws Exception {
        journal = characters;
        SwingUtilities.invokeAndWait(() -> {
            data = new TomatoData() { @Override public CharacterJournal characterJournal() { return journal; } };
            if (live) data.liveCharacter.publish(CharacterFixtures.live(System.currentTimeMillis()));
            gui = new TomatoGUI(data);
            shell = (WorkspaceShell) gui.createWorkspace();
        });
    }

    private void gallery(String state, int width, int height, int font, DisplayModeModel.Mode mode, Runnable arrange) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(mode);
            evidence.show(shell, "Characters " + state, width, height, font);
            assertTrue(Navigator.current().open(Route.to(Destination.CHARACTERS)));
            arrange.run();
        });
        pause();
        if ("gallery-graveyard".equals(state)) {
            SwingUtilities.invokeAndWait(() -> {
                JComponent graveyard = VisualEvidence.named(shell, "character-graveyard", JComponent.class);
                graveyard.scrollRectToVisible(new Rectangle(0, 0, graveyard.getWidth(), graveyard.getHeight()));
            });
            pause();
        }
        SwingUtilities.invokeAndWait(() -> {
            evidence.capture(name(state, width, font, mode));
            errors.checkSucceeds(() -> { assertGalleryWhole(width); return null; });
            assertNoViewStateWarning();
        });
    }

    private void sheet(String tab, String key, int width, int height, int font, DisplayModeModel.Mode mode) throws Exception {
        String variant = key.equals(CharacterFixtures.KEY) ? "" : key.endsWith(":999") ? "-unavailable" : "-other";
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(mode);
            evidence.show(shell, "Character sheet " + tab + variant, width, height, font);
            assertTrue(Navigator.current().open(Route.to(Destination.CHARACTER_SHEET).withPayload(new SheetFocus(key, tab))));
        });
        pause();
        SwingUtilities.invokeAndWait(() -> {
            evidence.capture(name("sheet-" + tab + variant, width, font, mode));
            assertNoViewStateWarning();
            CharacterSheet sheet = VisualEvidence.find(shell, CharacterSheet.class, s -> true);
            boolean unavailable = variant.equals("-unavailable");
            errors.checkSucceeds(() -> { assertSheetWhole(sheet, unavailable ? null : key, unavailable ? null : tab, width); return null; });
            if (variant.equals("-unavailable"))
                errors.checkSucceeds(() -> { assertTrue("An unknown key says so", showsText(sheet, "This character is not in the journal")); return null; });
            else if ("build".equals(tab)) errors.checkSucceeds(() -> {
                if (variant.isEmpty()) assertNotNull("The live character's Build", VisualEvidence.find(sheet, MyInfoGUI.class, Component::isShowing));
                else assertNotNull("Another character's Build points to the live one", VisualEvidence.find(sheet, EmptyState.class, Component::isShowing));
                return null;
            });
        });
    }

    /**
     * The gallery page never scrolls sideways; the Sort combo, the search and "Reset filters" are whole (inside their row and the
     * window, never narrower than they want); the Graveyard sits right below the living cards, not at the bottom of the page.
     */
    private void assertGalleryWhole(int width) {
        JScrollPane page = VisualEvidence.named(shell, "character-page-scroll", JScrollPane.class);
        assertEquals("No sideways scrolling at " + width + " px", page.getViewport().getWidth(), page.getViewport().getView().getWidth());
        JComponent sort = VisualEvidence.named(shell, "character-sort", JComponent.class);
        if (sort.isShowing()) assertWhole(sort, "Sort", width);
        assertWhole(VisualEvidence.named(shell, "character-search", JComponent.class), "The search", width);
        assertWhole(VisualEvidence.find(shell, JButton.class, button -> "Reset filters".equals(button.getText())), "Reset filters", width);
        CharacterGallery gallery = VisualEvidence.find(shell, CharacterGallery.class, g -> true);
        if (gallery.isShowing() && !gallery.alive().isEmpty() && !gallery.dead().isEmpty()) { // the cards and the Graveyard are both on the page
            JComponent cards = VisualEvidence.named(gallery, "character-cards", JComponent.class), graveyard = VisualEvidence.named(gallery, "character-graveyard", JComponent.class);
            Rectangle above = SwingUtilities.convertRectangle(cards.getParent(), cards.getBounds(), shell);
            Rectangle below = SwingUtilities.convertRectangle(graveyard.getParent(), graveyard.getBounds(), shell);
            assertTrue("The Graveyard follows the cards at " + width + " px: " + above + " then " + below, below.y - (above.y + above.height) <= 24);
        }
    }

    /** Sideways only, since the page may be scrolled: inside its row and the window, and at least its preferred width (not squeezed). */
    private void assertWhole(JComponent part, String what, int width) {
        Rectangle placed = SwingUtilities.convertRectangle(part.getParent(), part.getBounds(), shell);
        assertTrue(what + " is whole at " + width + " px: " + part.getBounds() + " in a row " + part.getParent().getWidth() + " wide, preferred "
                + part.getPreferredSize().width + ", in the window " + placed,
            part.getX() >= 0 && part.getX() + part.getWidth() <= part.getParent().getWidth() && placed.x >= 0
                && placed.x + placed.width <= shell.getWidth() && part.getWidth() >= part.getPreferredSize().width);
    }

    /**
     * Every capture: no "View state save failed" warning (P3a finding 10). The roster's saved view goes to an in-memory store, so
     * a failed write of the shared preferences file can no longer put that banner in a screenshot.
     */
    private void assertNoViewStateWarning() {
        errors.checkSucceeds(() -> {
            assertFalse("No view-state warning", VisualEvidence.named(shell, "character-view-state", tomato.gui.kit.Banner.class).isShowing());
            return null;
        });
    }

    /** {@code key} and {@code tab} null: an unknown key, whose sheet shows its unavailable state instead of tabs. */
    private void assertSheetWhole(CharacterSheet sheet, String key, String tab, int width) {
        assertTrue("The sheet shows", sheet.isShowing());
        if (key != null) assertEquals(key, sheet.key());
        if (tab != null) assertEquals(tab, sheet.selectedTab());
        JComponent back = VisualEvidence.named(sheet, "character-sheet-back", JComponent.class);
        Rectangle placed = SwingUtilities.convertRectangle(back.getParent(), back.getBounds(), shell);
        assertTrue("‹ Characters fits at " + width + " px: " + placed, back.isShowing() && placed.x >= 0 && placed.x + placed.width <= shell.getWidth());
    }

    private static boolean showsText(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c.isShowing() && (c instanceof JLabel && String.valueOf(((JLabel) c).getText()).contains(text)
                || c instanceof javax.swing.text.JTextComponent && ((javax.swing.text.JTextComponent) c).getText().contains(text))) return true;
            if (c instanceof Container && showsText((Container) c, text)) return true;
        }
        return false;
    }

    private static String name(String state, int width, int font, DisplayModeModel.Mode mode) {
        return "p3a-" + state + "-" + width + "-" + font + "-" + mode.name().toLowerCase(Locale.ROOT);
    }

    /** The sheet builds its model off the EDT and Collapsible motion takes at most 100 ms: settle, wait, settle. */
    private void pause() throws Exception { evidence.settle(); Thread.sleep(400); evidence.settle(); }

    private void remember(Class<?> type, String name, Object next) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); statics.put(field, field.get(null)); field.set(null, next);
    }

    private static Set<String> archiveKeys() throws Exception {
        Field field = PropertiesManager.class.getDeclaredField("properties"); field.setAccessible(true);
        Set<String> keys = new HashSet<>(((Properties) field.get(null)).stringPropertyNames());
        keys.removeIf(key -> !key.startsWith("ux.archive."));
        return keys;
    }
}
