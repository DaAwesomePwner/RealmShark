package tomato.gui.loot.explore;

import java.time.ZoneId;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.kit.ItemSlot;
import tomato.gui.kit.TileList;
import tomato.gui.loot.HighlightsModel;
import tomato.gui.stats.LootFacts;
import tomato.history.link.VisitRef;
import static org.junit.Assert.*;
import static tomato.gui.loot.explore.CollectionModelTest.*;
import static tomato.gui.loot.explore.RunsLevelTest.*;

/** One item's drops over a fake catalog: the header, the cards newest first, opening a drop's run, the newest read winning. */
public class ItemLevelTest {
    static final VisitRef RUN = new VisitRef("00000000-0000-4000-8000-000000000001", "v1");

    @Test public void theHeaderAndTheDropsAndADropOpensItsRun() throws Exception {
        List<LootFacts.Bag> bags = List.of(new LootFacts.Bag("s", 100, false, "White", RUN, List.of(ut(1, 2)), "Lost Halls", "Synthetic boss"),
            bag(200, ut(1, 3), potion(4)));
        ItemLevel level = edt(() -> new ItemLevel(cancel -> bags, Runnable::run, ZoneId.of("UTC")));
        List<VisitRef> opened = new ArrayList<>();
        edt(() -> { level.onOpenRun(opened::add); level.open(1); return null; });
        edt(() -> null);
        edt(() -> {
            assertEquals(1, level.itemId());
            assertEquals("2 drops · 1 Rare · 1 Legendary", named(level, "loot-item-summary", JLabel.class).getText());
            @SuppressWarnings("unchecked") TileList<HighlightsModel.Notable> drops = named(level, "loot-item-drops", TileList.class);
            assertEquals(List.of(200L, 100L), drops.items().stream().map(HighlightsModel.Notable::time).toList());
            drops.setSelectedIndex(1);
            Object key = drops.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("ENTER"));
            drops.getActionMap().get(key).actionPerformed(new java.awt.event.ActionEvent(drops, 0, null));
            assertEquals(List.of(RUN), opened);
            drops.setSelectedIndex(0);
            drops.getActionMap().get(key).actionPerformed(new java.awt.event.ActionEvent(drops, 0, null));
            assertEquals("A drop without a run opens nothing", 1, opened.size());
            return null;
        });
        edt(() -> { level.close(); return null; });
    }

    @Test public void onlyTheNewestItemApplies() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        ItemLevel level = edt(() -> new ItemLevel(cancel -> List.of(bag(100, ut(1, null), st(2))), queued::add, ZoneId.of("UTC")));
        edt(() -> { level.open(1); level.open(2); for (Runnable task : List.copyOf(queued)) task.run(); return null; });
        edt(() -> null);
        assertEquals(2, (int) edt(() -> level.model().itemId()));
        edt(() -> { level.close(); return null; });
    }

    @Test public void returningToTheShownItemResetsTheHeaderBeforePendingReadsApply() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        ItemLevel level = edt(() -> new ItemLevel(cancel -> List.of(bag(100, ut(1, null), st(2))), queued::add, ZoneId.of("UTC")));
        try {
            edt(() -> { level.open(1); return null; });
            queued.remove(0).run();
            edt(() -> null);
            edt(() -> {
                assertEquals(1, level.model().itemId());
                level.open(2);
                level.open(1);
                assertEquals(1, named(level, "loot-item-slot", ItemSlot.class).itemId());
                assertEquals(ItemLevel.LOADING, level.status().getText());
                return null;
            });
        } finally { edt(() -> { level.close(); return null; }); }
    }

    @Test public void aFailedReadSaysWhy() throws Exception {
        ItemLevel level = edt(() -> new ItemLevel(cancel -> { throw new java.io.IOException("synthetic read failure"); }, Runnable::run, ZoneId.of("UTC")));
        edt(() -> { level.open(1); return null; });
        edt(() -> null);
        assertEquals("This item's drops could not be read: synthetic read failure", edt(() -> level.status().getText()));
        edt(() -> { level.close(); return null; });
    }
}
