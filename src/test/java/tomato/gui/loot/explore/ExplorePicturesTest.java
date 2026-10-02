package tomato.gui.loot.explore;

import java.time.ZoneId;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.*;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tomato.gui.runs.RunFeedView;
import tomato.gui.runs.RunCardModel;
import tomato.gui.runs.RunOutcome;
import tomato.gui.runs.RunFixtures;
import tomato.gui.kit.TileList;
import tomato.gui.kit.SegmentedControl;
import tomato.history.SessionStore;
import static org.junit.Assert.*;
import static tomato.gui.loot.explore.CollectionModelTest.*;
import static tomato.gui.loot.explore.RunsLevelTest.*;

/** The Pictures frame: the remembered entry, the breadcrumb at every level, items and runs opening across levels, state round trips. */
public class ExplorePicturesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final Map<String, String> prefs = new HashMap<>();
    private final List<String> writes = new ArrayList<>();
    private final List<ExplorePictures> made = new ArrayList<>();

    @After public void release() throws Exception { edt(() -> { for (ExplorePictures pictures : made) pictures.close(); return null; }); }

    ExplorePictures pictures() throws Exception {
        RunsLevelTest.FakeLoader loader = new RunsLevelTest.FakeLoader();
        loader.runs.put(RunFixtures.A1, RunsLevelTest.haul(RunFixtures.A1, "Synthetic Halls"));
        loader.runs.put(RunFixtures.A2, RunsLevelTest.haul(RunFixtures.A2, "Synthetic Pit"));
        return pictures(loader, () -> null);
    }

    private ExplorePictures pictures(RunsLevelTest.FakeLoader loader, Supplier<SessionStore> store) throws Exception {
        LootCatalog.Reader catalog = cancel -> List.of(bag(100, ut(1, 2), potion(4)));
        ExplorePictures pictures = edt(() -> new ExplorePictures(new RunsLevel(RunFeedView.picker(store), loader, Runnable::run,
            new DungeonPanel(catalog, Runnable::run)),
            new CollectionLevel(catalog, Runnable::run, id -> NAMES.getOrDefault(id, "Synthetic item " + id)),
            new DungeonsLevel(cancel -> List.of(AtlasModelTest.card("Lost Halls", 10, 100)), catalog, Runnable::run),
            new ItemLevel(catalog, Runnable::run, ZoneId.of("UTC")), id -> NAMES.getOrDefault(id, "Synthetic item " + id), prefs::get, (key, value) -> { writes.add(key + "=" + value); prefs.put(key, value); }));
        made.add(pictures);
        return pictures;
    }

    @Test public void theEntryIsRememberedAndThePathFollowsTheLevel() throws Exception {
        ExplorePictures pictures = pictures();
        edt(() -> {
            assertEquals(ExplorePictures.Level.RUNS, pictures.level());
            assertEquals(List.of("Runs"), pictures.path());
            named(pictures, "loot-explore-entry-2", AbstractButton.class).doClick();
            assertEquals(ExplorePictures.Level.COLLECTION, pictures.level());
            assertEquals(List.of(ExplorePictures.ENTRY_KEY + "=collection"), writes);
            assertEquals(List.of("Collection"), pictures.path());
            return null;
        });
        ExplorePictures again = pictures();
        assertEquals("The entry is remembered", ExplorePictures.Level.COLLECTION, edt(again::level));
    }

    @Test public void anItemOpensFromEitherWayInAndThePathLeadsBack() throws Exception {
        ExplorePictures pictures = pictures();
        edt(() -> { pictures.openRun(RunFixtures.A1); return null; });
        edt(() -> null);
        edt(() -> {
            assertEquals(List.of("Runs", "Synthetic Halls · " + timeOf(1L)), pictures.path());
            pictures.openItem(1);
            return null;
        });
        edt(() -> null);
        edt(() -> {
            assertEquals(ExplorePictures.Level.ITEM, pictures.level());
            assertEquals(3, pictures.path().size());
            assertEquals("Synthetic Seal", pictures.path().get(2));
            named(pictures, "loot-explore-path-1", AbstractButton.class).doClick();   // the run crumb
            assertEquals(ExplorePictures.Level.RUNS, pictures.level());
            assertEquals(RunFixtures.A1, pictures.selectedRun());

            pictures.showCollection();
            pictures.openItem(4);
            assertEquals(List.of("Collection", "Potion of Life"), pictures.path());
            return null;
        });
    }

    @Test public void aHaulsItemClickOpensItsItem() throws Exception {
        ExplorePictures pictures = pictures();
        List<Integer> opened = new ArrayList<>();
        edt(() -> { pictures.onOpenItem(opened::add); return null; });
        assertEquals(101, ExplorePictures.itemOf("101/2/0"));
        assertEquals(8, ExplorePictures.itemOf("8/null/null"));
        edt(() -> { pictures.openRun(RunFixtures.A1); return null; });
        edt(() -> null);
        edt(() -> {
            tomato.gui.kit.ItemSlot slot = (tomato.gui.kit.ItemSlot) named(pictures, "loot-haul-grid", JPanel.class).getComponent(0);
            slot.dispatchEvent(new java.awt.event.MouseEvent(slot, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false));
            assertEquals(List.of(101), opened);
            return null;
        });
    }

    @Test public void aDungeonItemUsesTheSameItemHistoryRoute() throws Exception {
        ExplorePictures pictures = pictures();
        List<Integer> opened = new ArrayList<>();
        edt(() -> {
            pictures.onOpenItem(opened::add);
            pictures.runs().dungeon().showDungeon("Lost Halls");
            return null;
        });
        edt(() -> {
            named(pictures.runs().dungeon(), "loot-dungeon-item-1", AbstractButton.class).doClick();
            assertEquals(List.of(1), opened);
            return null;
        });
    }

    @Test public void stateRoundTripsThroughEveryLevel() throws Exception {
        ExplorePictures pictures = pictures();
        edt(() -> {
            pictures.openUnlinked();
            ExplorePictures.State unlinked = pictures.state();
            pictures.showCollection();
            pictures.openItem(1);
            ExplorePictures.State item = pictures.state();
            pictures.restore(unlinked);
            assertEquals(ExplorePictures.Level.RUNS, pictures.level());
            assertTrue(pictures.showingUnlinked());
            pictures.restore(item);
            assertEquals(ExplorePictures.Level.ITEM, pictures.level());
            assertEquals(1, pictures.itemId());
            assertEquals(List.of("Collection", "Synthetic Seal"), pictures.path());
            return null;
        });
    }

    @Test public void dungeonsEntryIsRememberedAndATileOpensItsNewestRun() throws Exception {
        ExplorePictures pictures = pictures();
        edt(() -> {
            pictures.openRun(RunFixtures.A2);
            named(pictures, "loot-explore-entry-1", AbstractButton.class).doClick();
            assertEquals(ExplorePictures.Level.DUNGEONS, pictures.level());
            assertEquals(List.of("Dungeons"), pictures.path());
            assertEquals(List.of(ExplorePictures.ENTRY_KEY + "=dungeons"), writes);
            return null;
        });
        edt(() -> {
            @SuppressWarnings("unchecked") TileList<AtlasModel.Tile> tiles = named(pictures, "loot-dungeons-tiles", TileList.class);
            tiles.setSelectedIndex(0);
            tiles.getActionMap().get(TileList.OPEN).actionPerformed(new java.awt.event.ActionEvent(tiles, 0, null));
            assertEquals(ExplorePictures.Level.RUNS, pictures.level());
            assertEquals("Lost Halls", pictures.dungeon());
            assertEquals("Lost Halls", pictures.runs().feed().query().map());
            assertNull("The previous dungeon's run is forgotten", pictures.selectedRun());
            assertEquals(List.of("Dungeons", "Lost Halls"), pictures.path());
            pictures.runs().loaded(List.of(card(RunFixtures.A1, "Lost Halls")));
            assertEquals(RunFixtures.A1, pictures.selectedRun());
            return null;
        });
        edt(() -> {
            assertEquals(RunFixtures.A1, pictures.runs().shownRun());
            assertEquals(1, named(pictures, "loot-explore-entry", SegmentedControl.class).selected());
            pictures.openDungeon("Snake Pit");
            assertNull(pictures.selectedRun());
            pictures.runs().loaded(List.of(card(RunFixtures.A2, "Snake Pit")));
            assertEquals(RunFixtures.A2, pictures.selectedRun());
            return null;
        });
        ExplorePictures again = pictures();
        assertEquals(ExplorePictures.Level.DUNGEONS, edt(again::level));
    }

    @Test public void allItemsAndChipsKeepTheDungeonAndBreadcrumbInSync() throws Exception {
        ExplorePictures pictures = pictures();
        edt(() -> {
            pictures.openDungeon("Lost Halls");
            pictures.runs().loaded(List.of()); // mount the loot-only dungeon panel through the Runs view
            assertTrue(SwingUtilities.isDescendingFrom(pictures.runs().dungeon(), pictures));
            return null;
        });
        edt(() -> null); // apply the dungeon read before activating its link
        edt(() -> {
            assertEquals("Lost Halls", pictures.runs().dungeon().model().dungeon());
            AbstractButton link = named(pictures, "loot-dungeon-collection", AbstractButton.class);
            assertTrue(link.isVisible());
            link.doClick();
            assertEquals(ExplorePictures.Level.COLLECTION, pictures.level());
            assertEquals("Lost Halls", pictures.collection().dungeonFilter());
            assertEquals(List.of("Dungeons", "Lost Halls", "All items"), pictures.path());
            pictures.openItem(1);
            assertEquals(List.of("Dungeons", "Lost Halls", "All items", "Synthetic Seal"), pictures.path());
            named(pictures, "loot-explore-path-2", AbstractButton.class).doClick();
            JComponent chip = named(pictures, "loot-collection-dungeon", JComponent.class);
            named(chip, "remove-filter", AbstractButton.class).doClick();
            assertNull(pictures.dungeon());
            assertNull(pictures.state().dungeon());
            assertEquals(List.of("Collection"), pictures.path());
            pictures.openDungeon("Lost Halls");
            pictures.runs().feed().showDungeon(null); // the strip's chip clears the query
            pictures.runs().loaded(List.of());
            assertNull(pictures.dungeon());
            assertEquals(List.of("Runs"), pictures.path());
            assertEquals(0, named(pictures, "loot-explore-entry", SegmentedControl.class).selected());
            return null;
        });
    }

    @Test public void runsEntryAndRunsCrumbClearAnyDungeonFilter() throws Exception {
        ExplorePictures pictures = pictures();
        edt(() -> {
            pictures.openDungeon("Lost Halls");
            named(pictures, "loot-explore-entry-0", AbstractButton.class).doClick();
            assertNull(pictures.runs().feed().query().map());
            assertNull(pictures.dungeon());
            pictures.openRun(RunFixtures.A1);
            pictures.openItem(1);
            // A stale strip filter must also be cleared when returning via the Runs root crumb.
            pictures.runs().feed().showDungeon("Snake Pit");
            named(pictures, "loot-explore-path-0", AbstractButton.class).doClick();
            assertEquals(ExplorePictures.Level.RUNS, pictures.level());
            assertNull(pictures.runs().feed().query().map());
            assertNull(pictures.dungeon());
            return null;
        });
    }

    @Test public void navigationHookReceivesEntryTilePanelAndBreadcrumbMovesWithoutApplyingThem() throws Exception {
        ExplorePictures pictures = pictures();
        List<ExplorePictures.Focus> moves = new ArrayList<>();
        edt(() -> {
            pictures.onNavigate(moves::add);
            named(pictures, "loot-explore-entry-1", AbstractButton.class).doClick();
            assertEquals(ExplorePictures.Level.RUNS, pictures.level());
            assertEquals(0, named(pictures, "loot-explore-entry", SegmentedControl.class).selected());
            pictures.go(moves.get(0));
            return null;
        });
        edt(() -> {
            @SuppressWarnings("unchecked") TileList<AtlasModel.Tile> tiles = named(pictures, "loot-dungeons-tiles", TileList.class);
            tiles.setSelectedIndex(0);
            tiles.getActionMap().get(TileList.OPEN).actionPerformed(new java.awt.event.ActionEvent(tiles, 0, null));
            assertEquals(ExplorePictures.Level.DUNGEONS, pictures.level());
            pictures.go(moves.get(1));
            pictures.runs().loaded(List.of());
            assertEquals(ExplorePictures.Level.RUNS, pictures.level());
            assertTrue(SwingUtilities.isDescendingFrom(pictures.runs().dungeon(), pictures));
            return null;
        });
        edt(() -> null); // apply the dungeon read after the tile's route is explicitly applied
        edt(() -> {
            assertEquals("Lost Halls", pictures.runs().dungeon().model().dungeon());
            AbstractButton link = named(pictures, "loot-dungeon-collection", AbstractButton.class);
            assertTrue(link.isVisible());
            link.doClick();
            assertEquals("The collection hook does not apply its move", ExplorePictures.Level.RUNS, pictures.level());
            assertEquals("Lost Halls", pictures.dungeon());
            pictures.go(moves.get(2));
            named(pictures, "loot-explore-path-1", AbstractButton.class).doClick();
            assertEquals(ExplorePictures.Level.COLLECTION, pictures.level());
            named(pictures, "loot-explore-path-0", AbstractButton.class).doClick();
            assertEquals(List.of(new ExplorePictures.Focus(ExplorePictures.Level.DUNGEONS, null),
                new ExplorePictures.Focus(ExplorePictures.Level.RUNS, "Lost Halls"),
                new ExplorePictures.Focus(ExplorePictures.Level.COLLECTION, "Lost Halls"),
                new ExplorePictures.Focus(ExplorePictures.Level.RUNS, "Lost Halls"),
                new ExplorePictures.Focus(ExplorePictures.Level.DUNGEONS, null)), moves);
            return null;
        });
    }

    @Test public void stateRoundTripsDungeonsFilteredRunsCollectionAndItem() throws Exception {
        ExplorePictures pictures = pictures();
        edt(() -> {
            List<ExplorePictures.State> states = new ArrayList<>();
            pictures.showDungeons(); states.add(pictures.state());
            pictures.openDungeon("Lost Halls");
            pictures.runs().openRun(RunFixtures.A1); states.add(pictures.state());
            pictures.openDungeonCollection("Lost Halls"); states.add(pictures.state());
            pictures.openItem(1); states.add(pictures.state());
            Collections.reverse(states);
            for (ExplorePictures.State state : states) {
                pictures.restore(state);
                assertEquals(state, pictures.state());
                if (state.level() == ExplorePictures.Level.RUNS) assertEquals("Lost Halls", pictures.runs().feed().query().map());
                if (state.level() == ExplorePictures.Level.COLLECTION || state.level() == ExplorePictures.Level.ITEM)
                    assertEquals("Lost Halls", pictures.collection().dungeonFilter());
            }
            pictures.openDungeon("Sprite World"); // loot-only dungeon: no saved run is required
            pictures.runs().loaded(List.of());
            assertNull(pictures.selectedRun());
            assertEquals(List.of("Dungeons", "Sprite World"), pictures.path());
            pictures.openDungeonCollection("Sprite World");
            assertEquals("Sprite World", pictures.collection().dungeonFilter());
            return null;
        });
    }

    @Test public void restoringALootOnlyDungeonReloadsItsEmptyDetailWhenTheQueryHasNotChanged() throws Exception {
        try (SessionStore store = new SessionStore(temp.newFolder("empty-history").toPath(), false, "fixture")) {
            ExplorePictures pictures = pictures(new RunsLevelTest.FakeLoader(), () -> store);
            try {
                edt(() -> { pictures.openDungeon("Lost Halls"); return null; });
                await("the loot-only dungeon panel", () -> pictures.runs().dungeon().model() != null);
                ExplorePictures.State saved = edt(() -> {
                    assertNull(pictures.selectedRun());
                    assertEquals("No saved runs in this dungeon.", pictures.runs().status().getText());
                    AbstractButton link = named(pictures, "loot-dungeon-collection", AbstractButton.class);
                    assertTrue(link.isVisible());
                    ExplorePictures.State state = pictures.state();
                    link.doClick();
                    assertEquals(ExplorePictures.Level.COLLECTION, pictures.level());
                    assertEquals("Lost Halls", pictures.runs().feed().query().map());
                    return state;
                });
                edt(() -> { pictures.restore(saved); return null; });
                await("the restored loot-only dungeon panel", () -> pictures.runs().dungeon().model() != null);
                edt(() -> {
                    assertEquals(saved, pictures.state());
                    assertEquals("No saved runs in this dungeon.", pictures.runs().status().getText());
                    AbstractButton link = named(pictures, "loot-dungeon-collection", AbstractButton.class);
                    assertTrue(link.isVisible());
                    link.doClick();
                    assertEquals(ExplorePictures.Level.COLLECTION, pictures.level());
                    assertEquals("Lost Halls", pictures.collection().dungeonFilter());
                    return null;
                });
            } finally { edt(() -> { pictures.close(); return null; }); }
        }
    }

    /** Back to an all-runs snapshot taken before the strip picked a run: the loaded strip opens its newest run again, never "Choose a run". */
    @Test public void restoringAnEmptyAllRunsSnapshotReopensTheNewestRunWhenTheQueryHasNotChanged() throws Exception {
        java.nio.file.Path root = temp.newFolder("history").toPath();
        RunFixtures.write(root);
        try (SessionStore store = new SessionStore(root, false, "fixture")) {
            RunsLevelTest.FakeLoader loader = new RunsLevelTest.FakeLoader();
            loader.runs.put(RunFixtures.B4, RunsLevelTest.haul(RunFixtures.B4, "Synthetic B4"));
            ExplorePictures pictures = pictures(loader, () -> store);
            try {
                ExplorePictures.State saved = edt(() -> {
                    ExplorePictures.State now = pictures.state();
                    return new ExplorePictures.State(ExplorePictures.Level.RUNS, null, false, now.itemId(), now.itemFrom(), null);
                });
                edt(() -> { pictures.runs().feed().refresh(); return null; });
                await("the newest run's haul", () -> RunFixtures.B4.equals(pictures.runs().shownRun()));
                edt(() -> { pictures.showCollection(); pictures.restore(saved); return null; });
                await("the newest run's haul after Back", () -> RunFixtures.B4.equals(pictures.runs().shownRun())
                    && pictures.runs().detailShown() == pictures.runs().haul());
                edt(() -> {
                    assertEquals(ExplorePictures.Level.RUNS, pictures.level());
                    assertNull(pictures.runs().feed().query().map());
                    assertEquals(RunFixtures.B4, pictures.selectedRun());
                    return null;
                });
            } finally { edt(() -> { pictures.close(); return null; }); }
        }
    }

    @Test public void restoringTheShownHaulKeepsItsContentAndDoesNotReadItAgain() throws Exception {
        RunsLevelTest.FakeLoader loader = new RunsLevelTest.FakeLoader();
        RunHauls.RunHaul haul = RunsLevelTest.haul(RunFixtures.A1, "Lost Halls");
        loader.runs.put(RunFixtures.A1, new RunHauls.RunHaul(haul.ref(), haul.haul(), null, null, "Lost Halls"));
        ExplorePictures pictures = pictures(loader, () -> null);
        edt(() -> { pictures.openDungeon("Lost Halls"); pictures.runs().openRun(RunFixtures.A1); return null; });
        edt(() -> null); // haul apply queues the dungeon read's apply
        edt(() -> null);
        edt(() -> {
            assertEquals(RunFixtures.A1, pictures.runs().shownRun());
            assertEquals(List.of(RunFixtures.A1), loader.reads);
            ExplorePictures.State saved = pictures.state();
            JComponent content = pictures.runs().detailShown();
            AbstractButton link = named(pictures, "loot-dungeon-collection", AbstractButton.class);
            DungeonStats stats = pictures.runs().dungeon().model();
            assertTrue(link.isVisible());
            assertNotNull(stats);
            pictures.openItem(1);
            pictures.restore(saved);
            assertEquals(saved, pictures.state());
            assertSame(content, pictures.runs().detailShown());
            assertSame(stats, pictures.runs().dungeon().model());
            assertSame(link, named(pictures, "loot-dungeon-collection", AbstractButton.class));
            assertTrue("Restoring did not start another dungeon read", link.isVisible());
            assertEquals("Restoring did not start another haul read", List.of(RunFixtures.A1), loader.reads);
            pictures.openItem(1);
            pictures.restore(saved);
            assertEquals(List.of(RunFixtures.A1), loader.reads);
            return null;
        });
    }

    @Test public void restoringLootOutsideRunsDoesNotReadItAgain() throws Exception {
        RunsLevelTest.FakeLoader loader = new RunsLevelTest.FakeLoader();
        ExplorePictures pictures = pictures(loader, () -> null);
        edt(() -> { pictures.openUnlinked(); return null; });
        edt(() -> null);
        edt(() -> {
            ExplorePictures.State saved = pictures.state();
            assertEquals(1, loader.unlinkedReads);
            assertEquals(RunsLevel.NO_UNLINKED, pictures.runs().status().getText());
            pictures.showCollection();
            pictures.restore(saved);
            assertEquals(saved, pictures.state());
            assertEquals(1, loader.unlinkedReads);
            assertEquals(RunsLevel.NO_UNLINKED, pictures.runs().status().getText());
            return null;
        });
    }

    private static RunCardModel card(tomato.history.link.VisitRef ref, String dungeon) {
        return new RunCardModel(ref, dungeon, dungeon, 0, RunOutcome.COMPLETED, 1L, null, null, null,
            "No combat recording", List.of(), 0, "", null, null, null);
    }

    private static String timeOf(long epochMillis) {
        return tomato.gui.modern.DisplayFormat.formatTimestamp(java.time.Instant.ofEpochMilli(epochMillis), tomato.gui.modern.DisplayFormat.TimestampMode.TIME);
    }
}
