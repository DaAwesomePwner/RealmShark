package ui;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.ErrorCollector;
import org.junit.rules.TemporaryFolder;
import tomato.Tomato;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.PetDefinitions;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.character.CharacterPetsGUI;
import tomato.gui.chat.ChatGUI;
import tomato.gui.glance.character.CharacterFixtures;
import tomato.gui.glance.character.CharacterGallery;
import tomato.gui.glance.character.CharacterSheet;
import tomato.gui.glance.character.SheetFocus;
import tomato.gui.kit.Card;
import tomato.gui.kit.Chip;
import tomato.gui.kit.Collapsible;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.DisplayValue;
import tomato.gui.kit.EmptyState;
import tomato.gui.kit.StatTile;
import tomato.gui.kit.TileList;
import tomato.gui.modern.DisplayFormat;
import tomato.gui.modern.WorkspaceShell;
import tomato.gui.myinfo.MyInfoGUI;
import tomato.gui.route.Destination;
import tomato.gui.route.Navigator;
import tomato.gui.route.Route;
import tomato.history.AppHistory;
import tomato.history.SessionStore;
import tomato.planning.PlanData;
import tomato.planning.PlanningMetadata;
import tomato.planning.PlanningStore;
import util.PropertiesManager;
import static org.junit.Assert.*;

/**
 * Characters evidence (spec §6.2, §11) in the real workspace. Synthetic journal, history, definitions and goals; preview mode;
 * no capture.
 * - P3a (21 screenshots, {@code redesign-p3a-characters}): the gallery (populated, Graveyard open, no match, empty, the Table
 *   view) and the sheet (Overview, Gear, Exalts, Build for the live and another character, a key not in the journal) at
 *   1240×800 and 680×520, fonts 13 and 18, Simple and Analyst.
 * - P3b (21 screenshots, {@code redesign-p3b-characters}): the account Exalts grid (two accounts with the selector; empty) and one
 *   class detail; the Pets gallery (equipped only; with Pet Yard pets; empty) and its feeding drawer open; the sheet's Pet tab
 *   (known, no pet, unknown), the Overview pet card, the Fame tab (with the older-readings caption; empty) and the Goals tab
 *   (cards in Simple; cards and Manage goals in Analyst; empty). Each at 1240×800, font 13, Simple; the grid, the gallery and
 *   the Pet, Fame and Goals tabs also at 680×520, font 18, Analyst.
 * Every capture: no view-state warning and nothing scrolls or is cut off sideways.
 */
public class CharactersEvidenceTest {
    private static final Map<String, String> DEFAULTS = Map.ofEntries(Map.entry("chat.filters", "{}"), Map.entry("chat.showIgnoredPlayers", "false"),
        Map.entry("ui.characters.view", ""), Map.entry("ui.characters.sort", ""), Map.entry("ui.collapse.characters-graveyard", ""),
        Map.entry("ui.filters.characters.open", ""), Map.entry("ui.tabs.characters", ""), Map.entry("ui.tabs.character", ""),
        Map.entry("ui.collapse.pets-feeding", ""), Map.entry("ui.collapse.character-pet-feeding", ""), Map.entry("ui.collapse.character-goals-manage", ""));
    private static final DisplayModeModel.Mode SIMPLE = DisplayModeModel.Mode.SIMPLE, ANALYST = DisplayModeModel.Mode.ANALYST;
    private static final String ROSTER_KEY = "ux.archive.characters-live-roster";
    private static final String PRIEST = CharacterFixtures.ACCOUNT + ":103", WARRIOR = CharacterFixtures.ACCOUNT + ":102";
    @Rule public final VisualEvidence evidence = new VisualEvidence("redesign-p3a-characters");
    @Rule public final TemporaryFolder temp = new TemporaryFolder();
    @Rule public final ErrorCollector errors = new ErrorCollector();
    /** P3b captures: prints the window {@link #evidence} shows into their own folder (not a rule: it holds no window or theme). */
    private final VisualEvidence p3b = new VisualEvidence("redesign-p3b-characters");
    private final Map<Field, Object> statics = new LinkedHashMap<>();
    private final Map<String, String> saved = new HashMap<>(), archive = new HashMap<>();
    /** The Characters roster's saved view goes here, not to the shared realmShark.properties (P3a finding 10). */
    private final tomato.gui.roster.RosterStateTestSupport.Memory views = new tomato.gui.roster.RosterStateTestSupport.Memory();
    /** Test pins (pet names, weapon groups, dungeon mapping), closed newest first. */
    private final List<AutoCloseable> pins = new ArrayList<>();
    private String applicationRoster;
    private DisplayModeModel.Mode savedMode;
    private String temporaryDirectory;
    private Path history;
    private SessionStore store;
    private AutoCloseable definitions;
    /** The shared goals store's plan for the synthetic account before a Goals test pinned goals (restored after); null: untouched. */
    private PlanData.AccountPlan savedPlan;
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
        history = temp.newFolder("history").toPath();
        store = new SessionStore(history, true, "p3a-characters");
        remember(AppHistory.class, "store", store);
        remember(Tomato.class, "preview", true);
        remember(TomatoGUI.class, "characterViewStates", views.store);
        applicationRoster = PropertiesManager.getProperty(ROSTER_KEY);
        for (Class<?> type : new Class<?>[] {TomatoGUI.class, ChatGUI.class, CharacterPetsGUI.class})
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
        for (int i = pins.size() - 1; i >= 0; i--) pins.get(i).close();
        if (journal != null) journal.close();
        for (Map.Entry<String, String> entry : saved.entrySet())
            PropertiesManager.setProperties(entry.getKey(), entry.getValue() == null ? DEFAULTS.get(entry.getKey()) : entry.getValue());
        for (String key : archiveKeys()) { String value = archive.get(key); PropertiesManager.setProperties(key, value == null ? "" : value); }
        PropertiesManager.flush().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (temporaryDirectory != null) System.setProperty("java.io.tmpdir", temporaryDirectory);
        if (store != null) store.close();
        if (savedPlan != null) { // last: the preferences and statics above are restored even if this fails
            PlanningStore plans = PlanningStore.shared();
            assertTrue("The goals store's plan is restored", plans.update(CharacterFixtures.ACCOUNT, plans.snapshot(CharacterFixtures.ACCOUNT).revision,
                savedPlan).get(5, TimeUnit.SECONDS).saved);
        }
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

    /**
     * P3b, 3 captures: Characters › Exalts for the account in game, with a second account offered by the selector (both sizes),
     * then the Priest's class detail, opened as Enter opens a tile.
     */
    @Test public void exaltsGridShowsTheAccountInGameWithTheSelectorAndAClassDetail() throws Exception {
        pins.add(CharacterFixtures.installWeaponGroups());
        pins.add(CharacterFixtures.installDungeonMapping(temp.newFolder("assets").toPath()));
        build(CharacterFixtures.evidenceJournal(temp.newFolder("journal").toPath().resolve("Characters").resolve("journal.json"), System.currentTimeMillis()), true);
        Runnable grid = () -> {
            JComboBox<?> account = VisualEvidence.named(shell, "character-exalts-account", JComboBox.class);
            assertTrue("Two accounts hold saved exalts: the selector shows", account.isShowing());
            assertEquals(2, account.getItemCount());
            assertEquals("Every observed class of the account in game", 8, tiles().getModel().getSize());
            assertTile("tile-loot-boost", "+0%");
            assertTile("tile-fully-exalted", "2");
            assertTrue(accessibleNames(tiles()).toString(), accessibleNames(tiles()).contains("Archer: 600 completions, lowest tier 5 of 5, loot boost unknown"));
            assertTrue(accessibleNames(tiles()).toString(), accessibleNames(tiles()).contains("Priest: 515 completions, lowest tier 4 of 5, loot boost 20%"));
        };
        charactersTab("Exalts", "exalts-grid", 1240, 800, 13, SIMPLE, () -> {}, () -> tiles().isShowing() && tiles().getModel().getSize() == 8, grid);
        charactersTab("Exalts", "exalts-grid", 680, 520, 18, ANALYST, () -> {}, () -> tiles().isShowing() && tiles().getModel().getSize() == 8, grid);
        charactersTab("Exalts", "exalts-class", 1240, 800, 13, SIMPLE, () -> {
            TileList<?> tiles = tiles();
            tiles.selectKey("784", true);
            tiles.getActionMap().get(TileList.OPEN).actionPerformed(new ActionEvent(tiles, ActionEvent.ACTION_PERFORMED, TileList.OPEN));
        }, () -> shown(shell, "character-exalts-back") != null && shown(shell, "account-exalt-pips-0") != null, () -> {
            assertEquals("Priest", VisualEvidence.named(shell, "character-exalts-class", JLabel.class).getText());
            assertNull("The grid is swapped out while the detail shows", shown(shell, "character-exalt-tiles"));
            assertNull("The sheet's own Exalts names are not duplicated", shown(shell, "character-exalt-pips-0"));
        });
    }

    /**
     * P3b, 5 captures: Characters › Pets with the account's equipped pets only, then with the Pet Yard's pets (both sizes), the
     * feeding calculator's drawer open, and (in {@link #anEmptyJournalShowsTheExaltsAndPetsEmptyStates}) the empty gallery.
     */
    @Test public void petsGalleryShowsEquippedAndPetYardPetsAndTheFeedingDrawer() throws Exception {
        pins.add(PetDefinitions.install(CharacterFixtures.petNames(temp.newFolder("assets").toPath())));
        build(CharacterFixtures.evidenceJournal(temp.newFolder("journal").toPath().resolve("Characters").resolve("journal.json"), System.currentTimeMillis()), true);
        charactersTab("Pets", "pets-equipped", 1240, 800, 13, SIMPLE, () -> {}, () -> petCards().isShowing() && petCards().getModel().getSize() == 2, () -> {
            List<String> names = accessibleNames(petCards());
            // One card per pet: the Wizard's (on the dead Necromancer six hours earlier; the newer values win) and the Archer's.
            assertTrue(names.toString(), names.get(0).startsWith("Sample pet, Legendary, family Canine, Heal level 90 of 90")
                && names.get(0).endsWith("equipped by Wizard #101, Necromancer #107 (dead)"));
            assertTrue(names.toString(), names.get(1).startsWith("Second pet, Rare, family Feline") && names.get(1).endsWith("equipped by Archer #105"));
        });
        SwingUtilities.invokeAndWait(() -> CharacterFixtures.enterPetYard(data.progression(), System.currentTimeMillis()));
        Runnable yard = () -> {
            List<String> names = accessibleNames(petCards());
            assertEquals(names.toString(), 3, names.size());
            assertTrue("Yard pets first; the Archer's merges into its card: " + names, names.get(0).startsWith("Second pet") && names.get(0).contains("in the Pet Yard now"));
            assertTrue(names.toString(), names.get(1).startsWith("Yard pet") && names.get(1).contains("in the Pet Yard now"));
            assertTrue(names.toString(), names.get(2).startsWith("Sample pet"));
        };
        charactersTab("Pets", "pets-yard", 1240, 800, 13, SIMPLE, () -> {}, () -> petCards().getModel().getSize() == 3, yard);
        charactersTab("Pets", "pets-yard", 680, 520, 18, ANALYST, () -> {}, () -> petCards().getModel().getSize() == 3, yard);
        charactersTab("Pets", "pets-feeding", 1240, 800, 13, SIMPLE, () -> {
            VisualEvidence.named(shell, "collapsible-pets-feeding", AbstractButton.class).doClick();
        }, () -> VisualEvidence.named(shell, "pets-feeding", Collapsible.class).expanded(), () -> {
            assertTrue("The calculator shows", VisualEvidence.named(shell, "pet-feed-power", JTextField.class).isShowing());
            assertTrue("…for the first card's pet", VisualEvidence.find(shell, JTextArea.class, area -> area.isShowing()
                && area.getText().startsWith("Estimates for Second pet (instance 5105)")) != null);
        });
    }

    /** P3b, 5 captures of Sheet › Pet (a known pet at both sizes, "No pet", not captured yet) and 1 of the Overview's pet card. */
    @Test public void sheetPetTabShowsKnownNoneAndUnknownPetsAndTheOverviewPetCard() throws Exception {
        pins.add(PetDefinitions.install(CharacterFixtures.petNames(temp.newFolder("assets").toPath())));
        build(CharacterFixtures.evidenceJournal(temp.newFolder("journal").toPath().resolve("Characters").resolve("journal.json"), System.currentTimeMillis()), true);
        String key = CharacterFixtures.KEY;
        Consumer<CharacterSheet> known = sheet -> {
            assertEquals("Sample pet", VisualEvidence.named(sheet, "character-pet-name", JLabel.class).getText());
            assertEquals("Legendary", VisualEvidence.named(sheet, "character-pet-rarity", Chip.class).getText());
            assertEquals("Family: Canine", VisualEvidence.named(sheet, "character-pet-family", JLabel.class).getText());
            assertEquals("Max level 90", VisualEvidence.named(sheet, "character-pet-max", JLabel.class).getText());
            assertEquals("Level 90 / 90", VisualEvidence.named(sheet, "character-pet-ability-level-0", JLabel.class).getText());
        };
        p3bSheet("sheet-pet", "pet", key, 1240, 800, 13, SIMPLE, () -> shown(shell, "character-pet-content") != null, known);
        p3bSheet("sheet-pet", "pet", key, 680, 520, 18, ANALYST, () -> shown(shell, "character-pet-content") != null, known);
        p3bSheet("sheet-pet-none", "pet", PRIEST, 1240, 800, 13, SIMPLE, () -> shown(shell, "character-pet-none") != null,
            sheet -> assertNull(shown(sheet, "character-pet-content")));
        p3bSheet("sheet-pet-unknown", "pet", WARRIOR, 1240, 800, 13, SIMPLE, () -> shown(shell, "character-pet-empty") != null,
            sheet -> assertNull(shown(sheet, "character-pet-none")));
        p3bSheet("sheet-overview-pet", "overview", key, 1240, 800, 13, SIMPLE, () -> shown(shell, "character-overview-pet") != null
                && "Sample pet".equals(((JLabel) shown(shell, "character-overview-pet-name")).getText()), sheet -> {
            assertEquals("Legendary", VisualEvidence.named(sheet, "character-overview-pet-rarity", Chip.class).getText());
            assertWholeInWindow(VisualEvidence.named(sheet, "character-overview-pet", Card.class), "The pet card");
        });
    }

    /**
     * P3b, 3 captures of Sheet › Fame: the Wizard's two saved sessions with three older readings that have no account (both sizes),
     * and a character without fame history.
     */
    @Test public void sheetFameTabShowsExactHistoryAndItsEmptyState() throws Exception {
        long now = System.currentTimeMillis();
        CharacterFixtures.fameHistory(history, now);
        build(CharacterFixtures.evidenceJournal(temp.newFolder("journal").toPath().resolve("Characters").resolve("journal.json"), now), true);
        Consumer<CharacterSheet> populated = sheet -> {
            assertEquals("3 older readings have no recorded account and are not shown",
                VisualEvidence.named(sheet, "character-fame-untagged", JTextArea.class).getText());
            assertNotNull("The older-readings caption shows", shown(sheet, "character-fame-untagged"));
            assertNull("No unreadable sessions", shown(sheet, "character-fame-unreadable"));
            assertTile(sheet, "tile-fame", DisplayFormat.formatInteger(1_234)); // the Wizard in game: live
            StatTile gained = VisualEvidence.named(sheet, "tile-recorded-gain", StatTile.class);
            assertEquals("Readings are estimates: +180 in the first session and +54 in the second", DisplayValue.State.ESTIMATE, gained.value().state);
            assertTrue(gained.value().text(), gained.value().text().endsWith("+" + DisplayFormat.formatInteger(234)));
        };
        p3bSheet("sheet-fame", "fame", CharacterFixtures.KEY, 1240, 800, 13, SIMPLE, () -> shown(shell, "character-fame-chart") != null, populated);
        p3bSheet("sheet-fame", "fame", CharacterFixtures.KEY, 680, 520, 18, ANALYST, () -> shown(shell, "character-fame-chart") != null, populated);
        p3bSheet("sheet-fame-empty", "fame", WARRIOR, 1240, 800, 13, SIMPLE, () -> shown(shell, "character-fame-empty") != null,
            sheet -> assertNull(shown(sheet, "character-fame-chart")));
    }

    /**
     * P3b, 4 captures of Sheet › Goals: the Wizard's six goal cards over the collapsed Manage goals section (Simple), the cards over
     * the expanded panel (Analyst, both sizes), and a character with no goals.
     */
    @Test public void sheetGoalsTabShowsCardsAndManageGoalsInBothModes() throws Exception {
        long now = System.currentTimeMillis();
        pins.add(CharacterFixtures.installDungeonMapping(temp.newFolder("assets").toPath()));
        PlanningStore plans = PlanningStore.shared();
        await("the goals store", () -> plans.snapshot(CharacterFixtures.ACCOUNT).ready);
        PlanningStore.Snapshot before = plans.snapshot(CharacterFixtures.ACCOUNT);
        assertFalse("The goals store is writable: " + before.status, before.readOnly);
        savedPlan = before.plan();
        assertTrue(plans.update(CharacterFixtures.ACCOUNT, before.revision,
            CharacterFixtures.goals(savedPlan, CharacterFixtures.definitions(), PlanningMetadata.current(), now)).get(5, TimeUnit.SECONDS).saved);
        build(CharacterFixtures.evidenceJournal(temp.newFolder("journal").toPath().resolve("Characters").resolve("journal.json"), now), true);
        BooleanSupplier cards = () -> shown(shell, "character-goals-cards") != null;
        p3bSheet("sheet-goals", "goals", CharacterFixtures.KEY, 1240, 800, 13, SIMPLE, cards, sheet -> {
            assertEquals(6, goalCards(sheet));
            assertFalse("Simple: Manage goals starts collapsed", VisualEvidence.named(sheet, "character-goals-manage", Collapsible.class).expanded());
            assertNull("…so its panel is not showing", shown(sheet, "planning-0"));
        });
        Consumer<CharacterSheet> analyst = sheet -> {
            assertEquals(6, goalCards(sheet));
            assertNotNull("Analyst: Manage goals is expanded below the cards", shown(sheet, "character-goals-manage-panel"));
            assertNull(shown(sheet, "character-goals-manage"));
        };
        p3bSheet("sheet-goals", "goals", CharacterFixtures.KEY, 1240, 800, 13, ANALYST, cards, analyst);
        p3bSheet("sheet-goals", "goals", CharacterFixtures.KEY, 680, 520, 18, ANALYST, cards, analyst);
        p3bSheet("sheet-goals-empty", "goals", WARRIOR, 1240, 800, 13, SIMPLE, () -> shown(shell, "character-goals-empty") != null,
            sheet -> assertEquals(0, goalCards(sheet)));
    }

    /** P3b, 2 captures: with nothing saved, Characters › Exalts and Pets each show their one empty state. */
    @Test public void anEmptyJournalShowsTheExaltsAndPetsEmptyStates() throws Exception {
        build(new CharacterJournal(temp.newFolder("journal").toPath().resolve("Characters").resolve("journal.json")), false);
        charactersTab("Exalts", "exalts-grid-empty", 1240, 800, 13, SIMPLE, () -> {}, () -> shown(shell, "character-exalts-grid-empty") != null,
            () -> assertNull("No tiles, no header", shown(shell, "tile-loot-boost")));
        charactersTab("Pets", "pets-empty", 1240, 800, 13, SIMPLE, () -> {}, () -> shown(shell, "pet-empty") != null, () -> {
            assertNull(shown(shell, "pet-cards"));
            assertNull("No pet: no feeding calculator", shown(shell, "pets-feeding"));
        });
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
     * P3b: one capture of a Characters tab ({@code tab}: "Exalts" or "Pets", selected as a click on its tab does) after the route to
     * Characters; {@code arrange} runs next, then the capture waits for {@code ready} (built off the EDT) and checks {@code check}.
     */
    private void charactersTab(String tab, String state, int width, int height, int font, DisplayModeModel.Mode mode, Runnable arrange,
                               BooleanSupplier ready, Runnable check) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(mode);
            evidence.show(shell, "Characters " + state, width, height, font);
            assertTrue(Navigator.current().open(Route.to(Destination.CHARACTERS)));
            JTabbedPane tabs = VisualEvidence.named(shell, "characters-tabs", JTabbedPane.class);
            tabs.setSelectedIndex(tabs.indexOfTab(tab));
        });
        pause();
        SwingUtilities.invokeAndWait(arrange);
        await(state, ready);
        pause();
        SwingUtilities.invokeAndWait(() -> {
            String name = p3bName(state, width, font, mode);
            p3b.capture(SwingUtilities.getWindowAncestor(shell), name);
            assertNoViewStateWarning();
            assertNothingSideways(name);
            errors.checkSucceeds(() -> { check.run(); return null; });
        });
    }

    /** P3b: one capture of a sheet tab opened by route, once {@code ready}; {@code check} reads the sheet. */
    private void p3bSheet(String state, String tab, String key, int width, int height, int font, DisplayModeModel.Mode mode,
                          BooleanSupplier ready, Consumer<CharacterSheet> check) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DisplayModeModel.application().set(mode);
            evidence.show(shell, "Character sheet " + state, width, height, font);
            assertTrue(Navigator.current().open(Route.to(Destination.CHARACTER_SHEET).withPayload(new SheetFocus(key, tab))));
        });
        await(state, ready);
        pause();
        if (width < 1000) { // compact: the header fills the first screen, so scroll the tab strip and its tab up as the user would
            SwingUtilities.invokeAndWait(() -> {
                JTabbedPane tabs = VisualEvidence.named(VisualEvidence.find(shell, CharacterSheet.class, s -> true), "character-tabs", JTabbedPane.class);
                tabs.scrollRectToVisible(new Rectangle(0, 0, tabs.getWidth(), tabs.getHeight()));
            });
            pause();
        }
        SwingUtilities.invokeAndWait(() -> {
            String name = p3bName(state, width, font, mode);
            p3b.capture(SwingUtilities.getWindowAncestor(shell), name);
            assertNoViewStateWarning();
            assertNothingSideways(name);
            CharacterSheet sheet = VisualEvidence.find(shell, CharacterSheet.class, s -> true);
            errors.checkSucceeds(() -> { assertSheetWhole(sheet, key, tab, width); check.accept(sheet); return null; });
        });
    }

    /** Polls {@code condition} on the EDT (views build their models on their own threads) for up to 15 s. */
    private static void await(String what, BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        boolean[] met = new boolean[1];
        while (true) {
            SwingUtilities.invokeAndWait(() -> met[0] = condition.getAsBoolean());
            if (met[0]) return;
            if (System.nanoTime() > end) fail(what + " did not settle within 15 s");
            Thread.sleep(50);
        }
    }

    /**
     * No showing scroll pane scrolls or cuts its content sideways: no horizontal scroll bar, and the view no wider than the viewport
     * (the page panes never show a horizontal bar, so a wider view would be cut off). A data table (Manage goals) scrolls its own
     * columns sideways by design and is listed on standard output instead.
     */
    private void assertNothingSideways(String capture) {
        errors.checkSucceeds(() -> { nothingSideways(shell, capture); return null; });
    }

    private static void nothingSideways(Container root, String capture) {
        for (Component child : root.getComponents()) {
            if (child instanceof JScrollPane && child.isShowing()) {
                JScrollPane scroll = (JScrollPane) child;
                Component view = scroll.getViewport().getView();
                String where = scroll.getName() != null ? scroll.getName() : view == null ? "an empty scroll pane" : view.getClass().getSimpleName();
                if (view instanceof JTable) System.out.println(capture + ": table " + where + " " + view.getWidth() + " px in a " + scroll.getViewport().getWidth()
                    + " px viewport, horizontal bar " + (scroll.getHorizontalScrollBar().isShowing() ? "shown" : "hidden"));
                else {
                    assertFalse(capture + ": a horizontal scroll bar in " + where, scroll.getHorizontalScrollBar().isShowing());
                    if (view != null) assertTrue(capture + ": " + where + " is " + view.getWidth() + " px wide in a " + scroll.getViewport().getWidth() + " px viewport",
                        view.getWidth() <= scroll.getViewport().getWidth());
                }
            }
            if (child instanceof Container) nothingSideways((Container) child, capture);
        }
    }

    /** Inside the window horizontally and at least as wide as it wants (not squeezed). */
    private void assertWholeInWindow(JComponent part, String what) {
        Rectangle placed = SwingUtilities.convertRectangle(part.getParent(), part.getBounds(), shell);
        assertTrue(what + " is whole: " + placed + " in a window " + shell.getWidth() + " wide", part.isShowing() && placed.x >= 0
            && placed.x + placed.width <= shell.getWidth());
    }

    /** The first showing component named {@code name} under {@code root}, or null. */
    private static Component shown(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName()) && child.isShowing()) return child;
            if (child instanceof Container) { Component found = shown((Container) child, name); if (found != null) return found; }
        }
        return null;
    }

    private TileList<?> tiles() { return VisualEvidence.named(shell, "character-exalt-tiles", TileList.class); }
    private TileList<?> petCards() { return VisualEvidence.named(shell, "pet-cards", TileList.class); }

    /** What a screen reader announces for each tile (the painted text is not in the component tree). */
    private static List<String> accessibleNames(JList<?> tiles) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tiles.getModel().getSize(); i++)
            names.add(tiles.getAccessibleContext().getAccessibleChild(i).getAccessibleContext().getAccessibleName());
        return names;
    }

    private void assertTile(String name, String value) { assertTile(shell, name, value); }
    private static void assertTile(Container root, String name, String value) {
        StatTile tile = VisualEvidence.named(root, name, StatTile.class);
        assertTrue(name + " shows", tile.isShowing());
        assertEquals(name, value, tile.value().text());
    }

    /** The showing goal cards ({@code Card}s named character-goal-<key>). */
    private static int goalCards(Container root) {
        int count = 0;
        for (Component child : root.getComponents()) {
            if (child instanceof Card && child.isShowing() && child.getName() != null && child.getName().startsWith("character-goal-")) count++;
            if (child instanceof Container) count += goalCards((Container) child);
        }
        return count;
    }

    private static String p3bName(String state, int width, int font, DisplayModeModel.Mode mode) {
        return "p3b-" + state + "-" + width + "-" + font + "-" + mode.name().toLowerCase(Locale.ROOT);
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
