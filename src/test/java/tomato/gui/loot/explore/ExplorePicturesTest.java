package tomato.gui.loot.explore;

import java.time.ZoneId;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.After;
import org.junit.Test;
import tomato.gui.runs.RunFeedView;
import tomato.gui.runs.RunFixtures;
import static org.junit.Assert.*;
import static tomato.gui.loot.explore.CollectionModelTest.*;
import static tomato.gui.loot.explore.RunsLevelTest.*;

/** The Pictures frame: the remembered entry, the breadcrumb at every level, items and runs opening across levels, state round trips. */
public class ExplorePicturesTest {
    private final Map<String, String> prefs = new HashMap<>();
    private final List<String> writes = new ArrayList<>();
    private final List<ExplorePictures> made = new ArrayList<>();

    @After public void release() throws Exception { edt(() -> { for (ExplorePictures pictures : made) pictures.close(); return null; }); }

    ExplorePictures pictures() throws Exception {
        RunsLevelTest.FakeLoader loader = new RunsLevelTest.FakeLoader();
        loader.runs.put(RunFixtures.A1, RunsLevelTest.haul(RunFixtures.A1, "Synthetic Halls"));
        LootCatalog.Reader catalog = cancel -> List.of(bag(100, ut(1, 2), potion(4)));
        ExplorePictures pictures = edt(() -> new ExplorePictures(new RunsLevel(RunFeedView.picker(() -> null), loader, Runnable::run,
            new DungeonPanel(catalog, Runnable::run)),
            new CollectionLevel(catalog, Runnable::run, id -> NAMES.getOrDefault(id, "Synthetic item " + id)),
            new ItemLevel(catalog, Runnable::run, ZoneId.of("UTC")), id -> NAMES.getOrDefault(id, "Synthetic item " + id), prefs::get, (key, value) -> { writes.add(key + "=" + value); prefs.put(key, value); }));
        made.add(pictures);
        return pictures;
    }

    @Test public void theEntryIsRememberedAndThePathFollowsTheLevel() throws Exception {
        ExplorePictures pictures = pictures();
        edt(() -> {
            assertEquals(ExplorePictures.Level.RUNS, pictures.level());
            assertEquals(List.of("Runs"), pictures.path());
            named(pictures, "loot-explore-entry-1", AbstractButton.class).doClick();
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

    private static String timeOf(long epochMillis) {
        return tomato.gui.modern.DisplayFormat.formatTimestamp(java.time.Instant.ofEpochMilli(epochMillis), tomato.gui.modern.DisplayFormat.TimestampMode.TIME);
    }
}
