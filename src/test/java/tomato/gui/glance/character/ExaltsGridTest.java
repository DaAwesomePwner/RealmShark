package tomato.gui.glance.character;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.RosterDefinitions;
import tomato.backend.data.TomatoData;
import tomato.gui.kit.*;
import tomato.planning.PlanningMetadata;
import tomato.planning.PlanningStore;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;
import static tomato.gui.glance.character.ExaltFixtures.*;
import static tomato.gui.glance.character.SheetFixtures.named;

/** Characters › Exalts: header tiles, the account selector, the empty and pending states, drill-down and Back, and names apart from the sheet's. */
public class ExaltsGridTest {
    /** Fixed identities, so only the journal and the live character move a test grid's token. */
    private static final RosterDefinitions DEFINITIONS = RosterDefinitions.empty();
    private static final PlanningMetadata PLANNING = PlanningMetadata.unavailable();
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private CharacterJournal journal(boolean second) { return ExaltFixtures.journal(temp.getRoot().toPath().resolve("journal.json"), second); }
    private static SheetContext context(CharacterJournal journal) {
        return new SheetContext(new TomatoData(), journal, () -> DEFINITIONS, DisplayModeModel.application(), () -> NOW, PlanningStore.shared());
    }
    /** The grid with the fixture weapon groups and class names; {@code worker} runs its builds. */
    private static ExaltsGrid grid(CharacterJournal journal, Executor worker) {
        return new ExaltsGrid(context(journal), worker, ExaltFixtures::weaponGroup, ExaltFixtures::className, () -> PLANNING);
    }
    /** Builds on the EDT's own turn and applies on the next one, so a test only waits for the EDT. */
    private static ExaltsGrid refreshed(CharacterJournal journal) throws Exception {
        ExaltsGrid[] grid = new ExaltsGrid[1];
        SwingUtilities.invokeAndWait(() -> { grid[0] = grid(journal, Runnable::run); grid[0].refresh(); });
        await(() -> grid[0].model() != null);
        return grid[0];
    }
    @SuppressWarnings("unchecked")
    private static TileList<AccountExalts.Tile> tiles(Container grid) { return named(grid, "character-exalt-tiles", TileList.class); }
    /** Shown in its window's tree: it and every ancestor up to {@code root} are visible (CardLayout hides the other card). */
    private static boolean visible(Component component, Container root) {
        for (Component c = component; c != null; c = c.getParent()) { if (!c.isVisible()) return false; if (c == root) return true; }
        return true;
    }
    private static List<Integer> classes(TileList<AccountExalts.Tile> tiles) { return tiles.items().stream().map(AccountExalts.Tile::classId).toList(); }

    @Test public void headerTilesNameTheClassItsBasisAndTheFullyExaltedCount() throws Exception {
        try (CharacterJournal journal = journal(true)) {
            ExaltsGrid grid = refreshed(journal);
            SwingUtilities.invokeAndWait(() -> {
                StatTile boost = named(grid, "tile-loot-boost", StatTile.class), full = named(grid, "tile-fully-exalted", StatTile.class);
                assertEquals("The Wizard #7 is the account's last played character; Wizard and Priest share a weapon", "+15%", boost.value().display());
                assertEquals("Loot boost: +15%, Wizard · last played", boost.getAccessibleContext().getAccessibleName());
                assertEquals("Fully exalted: 1, of 3 observed classes", full.getAccessibleContext().getAccessibleName());
                assertEquals(List.of(WIZARD, PRIEST, WARRIOR), classes(tiles(grid)));
                assertTrue(visible(tiles(grid), grid));
                assertFalse(visible(named(grid, "character-exalts-grid-empty", EmptyState.class), grid));
                assertEquals("character-exalts-grid", grid.getName());
            });
        }
    }

    @Test public void theSelectorShowsOnlyWithTwoAccountsAndSwitchesTheGrid() throws Exception {
        try (CharacterJournal journal = journal(true)) {
            ExaltsGrid grid = refreshed(journal);
            JComboBox<?>[] box = new JComboBox<?>[1];
            SwingUtilities.invokeAndWait(() -> {
                box[0] = named(grid, "character-exalts-account", JComboBox.class);
                assertTrue("Two accounts with saved counts", box[0].isVisible());
                assertEquals(2, box[0].getItemCount());
                assertEquals(new AccountExalts.Choice(FIRST, "Sample"), box[0].getSelectedItem());
                box[0].setSelectedIndex(1);
            });
            await(() -> SECOND.equals(grid.model().account()));
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(List.of(KNIGHT), classes(tiles(grid)));
                StatTile boost = named(grid, "tile-loot-boost", StatTile.class);
                assertEquals("No character of this account was played: unknown, never 0", "—", boost.value().display());
                assertNotNull(boost.value().tooltip());
                assertEquals("The user's choice stays selected", 1, box[0].getSelectedIndex());
                assertEquals("Fully exalted: 0, of 1 observed classes", named(grid, "tile-fully-exalted", StatTile.class).getAccessibleContext().getAccessibleName());
            });
        }
        try (CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("one.json"))) {
            journal.exalts(FIRST, first().exalts);
            ExaltsGrid grid = refreshed(journal);
            SwingUtilities.invokeAndWait(() -> assertFalse("One account: no selector", named(grid, "character-exalts-account", JComboBox.class).isVisible()));
        }
    }

    @Test public void anUnknownBoostShowsADashWithTheReason() throws Exception {
        try (CharacterJournal journal = journal(false)) {
            ExaltsGrid[] grid = new ExaltsGrid[1];
            SwingUtilities.invokeAndWait(() -> { grid[0] = new ExaltsGrid(context(journal), Runnable::run, id -> null, ExaltFixtures::className, () -> PLANNING); grid[0].refresh(); });
            await(() -> grid[0].model() != null);
            SwingUtilities.invokeAndWait(() -> {
                StatTile boost = named(grid[0], "tile-loot-boost", StatTile.class);
                assertEquals("—", boost.value().display());
                assertEquals("Needs saved exalts for every class that shares this class's weapon, and class data from the selected game assets",
                    boost.value().tooltip());
                assertEquals("The class is still named", "Loot boost: —, Wizard · last played", boost.getAccessibleContext().getAccessibleName());
            });
        }
    }

    @Test public void aFailedBuildShowsAWarnBannerUntilABuildApplies() throws Exception {
        java.util.function.Consumer<String> savedLog = ExaltsGrid.errorLog;
        List<String> logged = new ArrayList<>();
        ExaltsGrid.errorLog = logged::add;
        try (CharacterJournal journal = journal(false)) {
            boolean[] fail = {true, true};
            java.util.function.IntFunction<int[]> groups = id -> {
                if (fail[0]) throw new IllegalStateException("class data changed while reading");
                if (fail[1]) throw new IllegalStateException();
                return weaponGroup(id);
            };
            ExaltsGrid[] grid = new ExaltsGrid[1];
            SwingUtilities.invokeAndWait(() -> {
                grid[0] = new ExaltsGrid(context(journal), Runnable::run, groups, ExaltFixtures::className, () -> PLANNING);
                assertFalse("No banner before anything failed", visible(named(grid[0], "character-exalts-failed", Banner.class), grid[0]));
                grid[0].refresh();
            });
            await(() -> visible(named(grid[0], "character-exalts-failed", Banner.class), grid[0]));
            SwingUtilities.invokeAndWait(() -> {
                Banner failed = named(grid[0], "character-exalts-failed", Banner.class);
                assertEquals("Exalts could not be shown: class data changed while reading. It retries automatically.", failed.text());
                assertTrue("A warn banner (spec §7)", failed.warns());
                assertEquals("Still logged, with its stack trace", 1, logged.size());
                assertTrue(logged.get(0), logged.get(0).contains("IllegalStateException"));
                assertNull("Nothing applied: no tiles and no \"no progress\"", grid[0].model());
                assertFalse(visible(named(grid[0], "character-exalts-grid-empty", EmptyState.class), grid[0]));
                fail[0] = false;
            });
            journal.exalts(FIRST, Map.of(KNIGHT, counts(KNIGHT_COUNTS)));
            SwingUtilities.invokeAndWait(grid[0]::refresh);
            await(() -> named(grid[0], "character-exalts-failed", Banner.class).text().contains("IllegalStateException"));
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Without a message, the exception's name", "Exalts could not be shown: IllegalStateException. It retries automatically.",
                    named(grid[0], "character-exalts-failed", Banner.class).text());
                fail[1] = false;
                grid[0].refresh(); // a failure cleared the token: the next check retries
            });
            await(() -> grid[0].model() != null);
            SwingUtilities.invokeAndWait(() -> {
                assertFalse("An applied build hides the banner", visible(named(grid[0], "character-exalts-failed", Banner.class), grid[0]));
                assertEquals(List.of(WIZARD, PRIEST, WARRIOR, KNIGHT), classes(tiles(grid[0])));
            });
        } finally {
            ExaltsGrid.errorLog = savedLog;
        }
    }

    @Test public void theEmptyStateShowsOnlyOnceABuildFoundNoCounts() throws Exception {
        try (CharacterJournal journal = new CharacterJournal(temp.getRoot().toPath().resolve("empty.json"))) {
            journal.observe(tomato.backend.data.CharacterJournalTest.player("exalt-fixture-first", WIZARD), 7);
            ExaltsGrid[] grid = new ExaltsGrid[1];
            SwingUtilities.invokeAndWait(() -> {
                grid[0] = grid(journal, Runnable::run);
                assertFalse("Pending: no tiles", visible(tiles(grid[0]), grid[0]));
                assertFalse("Pending: never \"no progress\"", visible(named(grid[0], "character-exalts-grid-empty", EmptyState.class), grid[0]));
                assertFalse(visible(named(grid[0], "tile-loot-boost", StatTile.class), grid[0]));
                grid[0].refresh();
            });
            await(() -> grid[0].model() != null);
            SwingUtilities.invokeAndWait(() -> {
                EmptyState empty = named(grid[0], "character-exalts-grid-empty", EmptyState.class);
                assertTrue(visible(empty, grid[0]));
                assertEquals("No exalt progress yet", empty.getAccessibleContext().getAccessibleName());
                assertFalse(visible(tiles(grid[0]), grid[0]));
                assertFalse("No header over nothing", visible(named(grid[0], "tile-fully-exalted", StatTile.class), grid[0]));
            });
        }
    }

    @Test public void aTokenChangeRebuildsAndOnlyTheNewestResultApplies() throws Exception {
        try (CharacterJournal journal = journal(true)) {
            List<Runnable> held = new ArrayList<>();
            AtomicInteger builds = new AtomicInteger();
            boolean[] hold = {false};
            ExaltsGrid[] grid = new ExaltsGrid[1];
            SwingUtilities.invokeAndWait(() -> {
                grid[0] = grid(journal, r -> { builds.incrementAndGet(); if (hold[0]) held.add(r); else r.run(); });
                grid[0].refresh();
            });
            await(() -> grid[0].model() != null);
            SwingUtilities.invokeAndWait(() -> {
                grid[0].refresh();
                assertEquals("An unchanged token does not rebuild", 1, builds.get());
            });
            hold[0] = true;
            journal.exalts(FIRST, Map.of(KNIGHT, counts(KNIGHT_COUNTS)));
            SwingUtilities.invokeAndWait(() -> {
                grid[0].refresh();
                assertEquals("The journal revision moved", 2, builds.get());
                named(grid[0], "character-exalts-account", JComboBox.class).setSelectedIndex(1); // the user picks the second account
                assertEquals("A choice rebuilds at once", 3, builds.get());
                held.get(1).run(); // the newest request (the second account) finishes first
                held.get(0).run(); // then the older one (the first account, now with a Knight)
            });
            await(() -> SECOND.equals(grid[0].model().account()));
            SwingUtilities.invokeAndWait(() -> { }); // the older result's delivery has run too
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("The older result never replaces the newer one", SECOND, grid[0].model().account());
                assertEquals(List.of(KNIGHT), classes(tiles(grid[0])));
            });
        }
    }

    @Test public void drillDownShowsTheClassDetailAndBackRestoresSelectionAndFocus() throws Exception {
        JFrame frame = new JFrame("Exalts grid - synthetic validation");
        try (CharacterJournal journal = journal(true)) {
            ExaltsGrid[] grid = new ExaltsGrid[1];
            SwingUtilities.invokeAndWait(() -> {
                grid[0] = grid(journal, Runnable::run);
                frame.setContentPane(grid[0]);
                frame.setSize(900, 700);
                frame.setVisible(true);
                frame.toFront();
            });
            await(frame::isFocused);
            await(() -> grid[0].model() != null); // showing starts the refresh
            TileList<AccountExalts.Tile> tiles = tiles(grid[0]);
            AbstractButton back = named(grid[0], "character-exalts-back", AbstractButton.class);
            SwingUtilities.invokeAndWait(() -> {
                assertTrue("It polls while it shows", grid[0].polling());
                tiles.setSelectedIndex(1);
                tiles.getActionMap().get(TileList.OPEN).actionPerformed(null);
                assertTrue(visible(back, grid[0]));
                assertFalse("The detail replaces the grid in place", visible(tiles, grid[0]));
                assertEquals("Priest", named(grid[0], "character-exalts-class", JLabel.class).getText());
                for (int i = 0; i < 8; i++) assertEquals(5, named(grid[0], "account-exalt-pips-" + i, PipMeter.class).filled());
                assertEquals("Total completions 605 · Lowest tier 5/5", named(grid[0], "account-exalts-totals", JLabel.class).getText());
            });
            await(() -> focusOwner() == back);
            SwingUtilities.invokeAndWait(back::doClick);
            await(() -> focusOwner() == tiles);
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(visible(tiles, grid[0]));
                assertFalse(visible(back, grid[0]));
                assertEquals("Back selects the class that was open", PRIEST, tiles.getSelectedValue().classId());
                tiles.setSelectedIndex(0);
                tiles.getActionMap().get(TileList.OPEN).actionPerformed(null);
                assertEquals("Wizard", named(grid[0], "character-exalts-class", JLabel.class).getText());
                assertEquals(List.of(3, 4, 3, 5, 3, 3, 4, 3), pips(grid[0]));
            });
            await(() -> focusOwner() == back);
            SwingUtilities.invokeAndWait(() -> {
                Component owner = focusOwner();
                owner.dispatchEvent(new KeyEvent(owner, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED));
            });
            await(() -> focusOwner() == tiles);
            SwingUtilities.invokeAndWait(() -> {
                assertTrue("Escape inside the detail goes back too", visible(tiles, grid[0]));
                assertEquals(WIZARD, tiles.getSelectedValue().classId());
                frame.setContentPane(new JPanel());
                frame.validate();
                assertFalse("It stops polling once it no longer shows", grid[0].polling());
            });
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    @Test public void theGridNeverDuplicatesTheSheetsExaltNames() throws Exception {
        try (CharacterJournal journal = journal(true)) {
            SwingUtilities.invokeAndWait(() -> {
                Set<String> sheet = names(new ExaltsTab(), new HashSet<>()), grid = names(grid(journal, Runnable::run), new HashSet<>());
                assertTrue(sheet.contains("character-exalt-pips-0"));
                assertTrue("The class detail is the sheet's tab under the account prefix", grid.contains("account-exalt-pips-0"));
                Set<String> shared = new HashSet<>(grid);
                shared.retainAll(sheet);
                assertEquals("A second instance in one window never shares a name with the sheet's Exalts tab", Set.of(), shared);
            });
        }
    }

    private static List<Integer> pips(Container grid) {
        List<Integer> filled = new ArrayList<>();
        for (int i = 0; i < 8; i++) filled.add(named(grid, "account-exalt-pips-" + i, PipMeter.class).filled());
        return filled;
    }
    private static Set<String> names(Component root, Set<String> into) {
        if (root.getName() != null) into.add(root.getName());
        if (root instanceof Container) for (Component child : ((Container) root).getComponents()) names(child, into);
        return into;
    }
    private static Component focusOwner() { return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner(); }
}
