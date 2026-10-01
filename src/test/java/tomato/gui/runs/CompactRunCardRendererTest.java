package tomato.gui.runs;

import java.util.List;
import javax.swing.*;
import org.junit.Test;
import tomato.gui.kit.Sprites;
import tomato.realmshark.EnchantInfo;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeHistoryFixture.*;

public class CompactRunCardRendererTest {
    @Test public void accessibleNameIncludesDayOutcomeBestDropBagEnchantAndRemainingItems() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RunCardModel card = card(List.of(new RunCardModel.LootItem(502, "White", "UT", EnchantInfo.ofSlotCount(3))), 6, null, at(0, 8, 5));
            String name = CompactRunCardRenderer.accessibleName(card, ZONE, NOW);
            assertTrue(name.contains("Lost Halls"));
            assertTrue(name.contains("Today 08:05 · Completed"));
            assertTrue(name.contains("Best drop: " + Sprites.name(502)));
            assertTrue(name.contains("White bag"));
            assertTrue(name.contains("UT"));
            assertTrue(name.contains("Legendary"));
            assertTrue(name.contains("+5 items"));
            CompactRunCardRenderer renderer = new CompactRunCardRenderer(ZONE, () -> NOW);
            renderer.getListCellRendererComponent(new JList<>(), card, 0, true, true);
            assertEquals(name, renderer.getAccessibleContext().getAccessibleName());
            assertEquals(32, CompactRunCardRenderer.LOOT);
            assertTrue(renderer.getPreferredSize().width >= 180);
            RunCardModel yesterday = card(List.of(), 0, null, at(-1, 18, 5));
            assertTrue(CompactRunCardRenderer.when(yesterday, ZONE, NOW).startsWith("Yesterday 18:05"));
        });
    }

    @Test public void unknownAndKnownEmptyKeepTheExistingLootWords() {
        RunCardModel empty = card(List.of(), 0, null, NOW);
        assertEquals(RunCardRenderer.NO_LOOT, CompactRunCardRenderer.note(empty));
        RunCardModel unknown = card(List.of(), 0, RunCardModel.LOOT_NOT_SAVED, NOW);
        assertTrue(CompactRunCardRenderer.accessibleName(unknown, ZONE, NOW).contains(RunCardModel.LOOT_NOT_SAVED));
        assertFalse(CompactRunCardRenderer.accessibleName(unknown, ZONE, NOW).contains("+0 items"));
    }

    @Test public void aSingleBestDropHasNoRemainingItemsLabelOrAnnouncement() {
        RunCardModel single = card(List.of(new RunCardModel.LootItem(502, "White", "UT")), 1, null, NOW);
        assertEquals("", CompactRunCardRenderer.rest(single));
        String name = CompactRunCardRenderer.accessibleName(single, ZONE, NOW);
        assertTrue(name.contains("Best drop: " + Sprites.name(502)));
        assertFalse(name.contains("+0 items"));
        assertFalse("No dangling separator", name.endsWith("; "));
        assertEquals("", CompactRunCardRenderer.rest(card(List.of(), 0, null, NOW)));
        assertEquals("+1 item", CompactRunCardRenderer.rest(card(single.loot(), 2, null, NOW)));
    }

    private static RunCardModel card(List<RunCardModel.LootItem> loot, int count, String reason, long entered) {
        return new RunCardModel(RunFixtures.A1, "Lost Halls", "Lost Halls", 0, RunOutcome.COMPLETED, entered,
            null, null, null, RunCardModel.NO_RECORDING, loot, count, "", reason, null, null);
    }
}
