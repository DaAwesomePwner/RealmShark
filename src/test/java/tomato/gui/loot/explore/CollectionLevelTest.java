package tomato.gui.loot.explore;

import java.io.IOException;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.kit.TileList;
import tomato.gui.stats.LootFacts;
import static org.junit.Assert.*;
import static tomato.gui.loot.explore.CollectionModelTest.*;
import static tomato.gui.loot.explore.RunsLevelTest.*;

/** The cabinet over a fake catalog: shelves of tiles, the 60-item cap with "Show all", search, opening an item, empty and failed reads. */
public class CollectionLevelTest {
    private static CollectionLevel level(LootCatalog.Reader catalog) throws Exception {
        return edt(() -> new CollectionLevel(catalog, Runnable::run, id -> NAMES.getOrDefault(id, "Synthetic item " + id)));
    }

    @Test public void shelvesShowTheirTilesAndATileOpensItsItem() throws Exception {
        CollectionLevel level = level(cancel -> List.of(bag(100, ut(1, 2), potion(4), potion(4)), bag(200, st(2))));
        List<Integer> opened = new ArrayList<>();
        edt(() -> { level.onOpenItem(opened::add); level.reload(); return null; });
        edt(() -> null);   // the read applies on a later EDT turn
        edt(() -> {
            @SuppressWarnings("unchecked") TileList<CollectionModel.Entry> uts = named(level, "loot-collection-shelf-ut", TileList.class);
            assertEquals(List.of(1), uts.items().stream().map(CollectionModel.Entry::itemId).toList());
            assertEquals("3 items · 4 drops", named(level, "loot-collection-summary", JLabel.class).getText());
            uts.setSelectedIndex(0);
            Object key = uts.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("ENTER"));
            uts.getActionMap().get(key).actionPerformed(new java.awt.event.ActionEvent(uts, 0, null));
            assertEquals(List.of(1), opened);
            return null;
        });
        level.close();
    }

    @Test public void aBigShelfShowsSixtyThenShowAll() throws Exception {
        List<LootFacts.Item> many = new ArrayList<>();
        for (int i = 0; i < 70; i++) many.add(potion(1000 + i));
        CollectionLevel level = level(cancel -> List.of(new LootFacts.Bag("s", 1, false, "Purple", null, many, null, null)));
        edt(() -> { level.reload(); return null; });
        edt(() -> null);
        edt(() -> {
            @SuppressWarnings("unchecked") TileList<CollectionModel.Entry> potions = named(level, "loot-collection-shelf-potions", TileList.class);
            assertEquals(CollectionLevel.SHELF_LIMIT, potions.items().size());
            AbstractButton more = named(level, "loot-collection-more-potions", AbstractButton.class);
            assertEquals("Show all 70", more.getText());
            more.doClick();
            @SuppressWarnings("unchecked") TileList<CollectionModel.Entry> all = named(level, "loot-collection-shelf-potions", TileList.class);
            assertEquals(70, all.items().size());
            return null;
        });
        level.close();
    }

    @Test public void searchFiltersEveryShelf() throws Exception {
        CollectionLevel level = level(cancel -> List.of(bag(100, ut(1, null), st(2), potion(4))));
        edt(() -> { level.reload(); return null; });
        edt(() -> null);
        edt(() -> {
            level.search().setText("robe");
            assertEquals(1, level.model().shelves().size());
            assertTrue(all(level, "loot-collection-shelf-ut", TileList.class).isEmpty());
            level.search().setText("nothing like this");
            assertEquals(CollectionLevel.NO_MATCH, level.status().getText());
            return null;
        });
        level.close();
    }

    @Test public void anEmptyOrFailedReadSaysWhy() throws Exception {
        CollectionLevel empty = level(cancel -> List.of());
        edt(() -> { empty.reload(); return null; });
        edt(() -> null);
        assertEquals(CollectionLevel.EMPTY, edt(() -> empty.status().getText()));
        empty.close();
        CollectionLevel failed = level(cancel -> { throw new IOException("synthetic read failure"); });
        edt(() -> { failed.reload(); return null; });
        edt(() -> null);
        assertEquals("Your collection could not be read: synthetic read failure", edt(() -> failed.status().getText()));
        failed.close();
    }
}
