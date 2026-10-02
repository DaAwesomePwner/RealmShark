package tomato.gui.loot.explore;

import java.awt.event.ActionEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.After;
import org.junit.Test;
import tomato.gui.kit.TileList;
import tomato.gui.stats.LootFacts;
import static org.junit.Assert.*;
import static tomato.gui.loot.explore.AtlasModelTest.*;
import static tomato.gui.loot.explore.CollectionModelTest.ut;
import static tomato.gui.loot.explore.RunsLevelTest.*;

/** Dungeon tiles, local sorting/search, activation, honest states and stale-read rejection. */
public class DungeonsLevelTest {
    private final List<DungeonsLevel> made = new ArrayList<>();
    private final AtomicInteger cardReads = new AtomicInteger(), bagReads = new AtomicInteger();

    @After public void release() throws Exception { edt(() -> { made.forEach(DungeonsLevel::close); return null; }); }

    private DungeonsLevel level() throws Exception {
        DungeonsLevel level = edt(() -> new DungeonsLevel(cancel -> {
            cardReads.incrementAndGet();
            return List.of(card("Snake Pit", 2, 300), card("Lost Halls", 10, 100));
        }, cancel -> {
            bagReads.incrementAndGet();
            return List.of(bag("Lost Halls", "a", 100, false, ut(1, null)), bag("Snake Pit", "b", 200, true));
        }, Runnable::run));
        made.add(level);
        edt(() -> { level.reload(); return null; });
        edt(() -> null);
        return level;
    }

    @Test public void mostRunsFirstAndSortDoesNotReadAgain() throws Exception {
        DungeonsLevel level = level();
        edt(() -> {
            assertEquals(List.of("Lost Halls", "Snake Pit"), level.model().tiles().stream().map(AtlasModel.Tile::dungeon).toList());
            assertEquals("2 dungeons", named(level, "loot-dungeons-summary", JLabel.class).getText());
            named(level, "loot-dungeons-sort-1", AbstractButton.class).doClick();
            assertEquals(AtlasModel.Sort.WHITES, level.sort());
            assertEquals("Snake Pit", level.model().tiles().get(0).dungeon());
            assertEquals(1, cardReads.get());
            assertEquals(1, bagReads.get());
            return null;
        });
    }

    @Test public void searchAndNoMatchUseTheLoadedCards() throws Exception {
        DungeonsLevel level = level();
        edt(() -> {
            level.search().setText(" snake ");
            assertEquals(List.of("Snake Pit"), level.model().tiles().stream().map(AtlasModel.Tile::dungeon).toList());
            assertEquals("1 dungeon", named(level, "loot-dungeons-summary", JLabel.class).getText());
            level.search().setText("not a dungeon");
            assertTrue(level.model().tiles().isEmpty());
            assertEquals(DungeonsLevel.NO_MATCH, level.status().getText());
            assertEquals(1, cardReads.get());
            assertEquals(1, bagReads.get());
            return null;
        });
    }

    @Test public void enterAndSpaceOpenTheCanonicalDungeonIncludingLootOnly() throws Exception {
        DungeonsLevel level = edt(() -> new DungeonsLevel(cancel -> List.of(),
            cancel -> List.of(bag("Sprite World", null, 200, true)), Runnable::run));
        made.add(level);
        List<String> opened = new ArrayList<>();
        edt(() -> { level.onOpenDungeon(opened::add); level.reload(); return null; });
        edt(() -> {
            @SuppressWarnings("unchecked") TileList<AtlasModel.Tile> tiles = named(level, "loot-dungeons-tiles", TileList.class);
            assertNull(tiles.items().get(0).runs());
            tiles.setSelectedIndex(0);
            for (String stroke : List.of("ENTER", "SPACE")) {
                Object key = tiles.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(stroke));
                tiles.getActionMap().get(key).actionPerformed(new ActionEvent(tiles, 0, null));
            }
            assertEquals(List.of("Sprite World", "Sprite World"), opened);
            return null;
        });
    }

    @Test public void emptyAndFailedReadsExplainTheirState() throws Exception {
        DungeonsLevel empty = edt(() -> new DungeonsLevel(cancel -> List.of(), cancel -> List.of(), Runnable::run));
        DungeonsLevel failed = edt(() -> new DungeonsLevel(cancel -> { throw new IOException("synthetic failure"); },
            cancel -> List.of(), Runnable::run));
        made.add(empty);
        made.add(failed);
        edt(() -> {
            empty.reload(); failed.reload();
            assertEquals(DungeonsLevel.LOADING, empty.status().getText());
            return null;
        });
        edt(() -> {
            assertEquals(DungeonsLevel.EMPTY, empty.status().getText());
            assertEquals("Dungeons could not be read: synthetic failure", failed.status().getText());
            return null;
        });
    }

    @Test public void olderQueuedReadsCannotReplaceNewerResultsOrApplyAfterClose() throws Exception {
        List<Runnable> tasks = new ArrayList<>();
        AtomicInteger reads = new AtomicInteger();
        DungeonsLevel level = edt(() -> new DungeonsLevel(cancel -> {
            assertFalse(SwingUtilities.isEventDispatchThread());
            return List.of(card(reads.incrementAndGet() == 1 ? "Snake Pit" : "Lost Halls", 1, 100));
        }, cancel -> {
            assertFalse(SwingUtilities.isEventDispatchThread());
            return List.<LootFacts.Bag>of();
        }, tasks::add));
        made.add(level);
        edt(() -> { level.reload(); level.reload(); return null; });
        tasks.get(1).run();
        edt(() -> null);
        tasks.get(0).run();
        edt(() -> {
            assertEquals("Snake Pit", level.model().tiles().get(0).dungeon());
            level.reload(); level.close();
            return null;
        });
        tasks.get(2).run();
        edt(() -> {
            assertEquals("Snake Pit", level.model().tiles().get(0).dungeon());
            return null;
        });
    }

    @Test public void rendererAnnouncesUnknownRunsAndExplainsThemInTheTooltip() throws Exception {
        edt(() -> {
            AtlasModel.Tile tile = new AtlasModel.Tile("Lost Halls", 0, null, 0, 1, 2, List.of(), 100);
            DungeonTileRenderer renderer = new DungeonTileRenderer();
            renderer.getListCellRendererComponent(new JList<>(), tile, 0, false, false);
            String name = renderer.getAccessibleContext().getAccessibleName();
            assertTrue(name.contains("— runs"));
            assertTrue(name.contains("1 white bag · 2 UTs"));
            assertTrue(name.contains("No runs with loot yet"));
            assertTrue(renderer.getToolTipText().contains("No saved run record"));
            return null;
        });
    }
}
