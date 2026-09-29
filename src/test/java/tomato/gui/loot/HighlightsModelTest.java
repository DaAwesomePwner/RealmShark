package tomato.gui.loot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Test;
import tomato.gui.kit.DisplayValue;
import tomato.gui.stats.LootFacts;
import tomato.history.link.VisitRef;
import tomato.realmshark.ParseDungeon;
import static org.junit.Assert.*;
import static tomato.gui.loot.HighlightsFixture.*;
import static tomato.gui.loot.HighlightsModel.Kind.*;
import static tomato.gui.loot.HighlightsModel.Source.LIVE_UNSAVED;
import static tomato.gui.loot.HighlightsModel.Source.SAVED;
import static tomato.gui.loot.HighlightsModel.Window.SESSION;
import static tomato.gui.loot.HighlightsModel.Window.TODAY;

/**
 * Loot Highlights' rules (spec §6.4; P6a decisions): notable drops in notability order, "enchanted" only from recorded slots,
 * potions by stat, unknown tiles when nothing was saved (never 0), Unknown area kept, white bags counted by name. In-memory bags.
 */
public class HighlightsModelTest {
    private static final VisitRef RUN = new VisitRef("s", "v1");

    private static HighlightsModel model(List<LootFacts.Bag> bags) { return HighlightsModel.of(TODAY, SAVED, bags, true, 0, false, 9_000); }
    private static List<HighlightsModel.Kind> kinds(HighlightsModel model) {
        return model.notable().stream().map(HighlightsModel.Notable::kind).collect(Collectors.toList());
    }

    @Test public void notableDropsFollowNotabilityOrderNewestBagFirstAndListEachItemOnce() {
        HighlightsModel model = model(List.of(
            bag("Orange", "Lost Halls", 1_000, RUN, item(1, false, false, false, 3), item(2, false, false, true, null), item(3, false, true, false, 2), item(4, true, false, false, null)),
            bag("White", "Lost Halls", 2_000, null, item(LIFE, false, false, true, null))));
        assertEquals(List.of(POTION, UT, ST, ENCHANTED), kinds(model));
        assertEquals("The newest bag first", 2_000, model.notable().get(0).time());
        assertEquals("Then the older bag's items in notability order; item 2 is a potion but not a stat potion", List.of(LIFE, 4, 3, 1),
            model.notable().stream().map(HighlightsModel.Notable::itemId).collect(Collectors.toList()));
        HighlightsModel.Notable st = model.notable().get(2);
        assertEquals("An enchanted ST is listed once, as ST", ST, st.kind());
        assertEquals(RUN, st.visit()); assertEquals("Orange", st.bag()); assertEquals("Lost Halls", st.dungeon());
        assertNull("No recorded visit: none is guessed", model.notable().get(0).visit());
        assertEquals(4, model.notableTotal());
        assertEquals("UT before ST before POTION before ENCHANTED", List.of(UT, ST, POTION, ENCHANTED), List.of(HighlightsModel.Kind.values()));
    }

    @Test public void onlyStatPotionsAreNotableAndOtherPotionsStillCount() {
        HighlightsModel model = model(List.of(bag("Brown", "Lost Halls", 1_000, null, item(OTHER_POTION, false, false, true, null))));
        assertTrue("Other potions are not notable", model.notable().isEmpty());
        assertNull(HighlightsModel.kind(item(OTHER_POTION, false, false, true, null)));
        assertEquals(POTION, HighlightsModel.kind(item(GREATER_DEFENSE, false, false, true, null)));
        assertEquals("…but counted as potions", "1", model.potions().text());
        assertEquals(Map.of(HighlightsModel.OTHER_POTIONS, 1), model.potionsByStat());
    }

    @Test public void enchantedNeedsTwoOrMoreKnownSlotsAndUnknownIsNeverCounted() {
        HighlightsModel model = model(List.of(bag("Orange", "Lost Halls", 1_000, RUN,
            item(1, false, false, false, 2), item(2, false, false, false, 1), item(3, false, false, false, 0),
            item(4, false, false, false, null), item(5, false, false, false, null), item(6, true, false, false, null), item(7, false, false, true, null))));
        assertEquals("The UT, then the only item with 2 known slots", List.of(6, 1),
            model.notable().stream().map(HighlightsModel.Notable::itemId).collect(Collectors.toList()));
        assertEquals(List.of(UT, ENCHANTED), kinds(model));
        assertEquals("Items 4 and 5 have no recorded enchant slots (the UT and the potion are listed or never enchanted)", 2, model.enchantUnknown());
        assertNull("Unknown enchant data: not enchanted and not unenchanted", HighlightsModel.kind(item(4, false, false, false, null)));
        assertNull(HighlightsModel.kind(item(2, false, false, false, 1)));
    }

    @Test public void potionsAreCountedByStatWithSmallAndGreaterTogetherAndOtherPotionsLast() {
        HighlightsModel model = model(List.of(
            bag("White", "Lost Halls", 1_000, RUN, item(OTHER_POTION, false, false, true, null), item(LIFE, false, false, true, null)),
            bag("Blue", "Lost Halls", 2_000, RUN, item(GREATER_LIFE, false, false, true, null), item(DEFENSE, false, false, true, null),
                item(GREATER_DEFENSE, false, false, true, null), item(DEFENSE, false, false, true, null), item(MANA, false, false, true, null))));
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("Life", 2); expected.put("Mana", 1); expected.put("Defense", 3); expected.put(HighlightsModel.OTHER_POTIONS, 1);
        assertEquals(expected, model.potionsByStat());
        assertEquals("In stat order, Other potions last", new ArrayList<>(expected.keySet()), new ArrayList<>(model.potionsByStat().keySet()));
        assertEquals("The tile is the sum of the stats (Home's potion flag)", "7", model.potions().text());
        assertEquals("2 Life · 1 Mana · 3 Def · 1 other", HighlightsModel.potionLine(model.potionsByStat()));
        String tooltip = model.potions().tooltip();
        assertTrue(tooltip, tooltip.contains("2 Life") && tooltip.contains("3 Defense") && tooltip.contains("1 other potion"));
        assertTrue(tooltip, tooltip.contains(HighlightsModel.OBSERVED));
    }

    @Test public void tilesAreUnknownWhenNoBagWasSavedAndARealZeroOtherwise() {
        HighlightsModel none = HighlightsModel.of(TODAY, SAVED, List.of(), false, 0, false, 9_000);
        for (DisplayValue tile : List.of(none.ut(), none.st(), none.potions(), none.whites())) {
            assertEquals("Never 0 when nothing was saved", DisplayValue.State.UNKNOWN, tile.state);
            assertEquals("—", tile.text());
            assertEquals(HighlightsModel.NO_LOOT, tile.detail);
        }
        assertEquals("No loot was saved for this period", HighlightsModel.NO_LOOT);
        HighlightsModel zero = HighlightsModel.of(SESSION, SAVED, List.of(), true, 0, false, 9_000);
        for (DisplayValue tile : List.of(zero.ut(), zero.st(), zero.potions(), zero.whites())) {
            assertEquals("Loot was saved in the window's sessions: a real zero", DisplayValue.State.ZERO, tile.state);
            assertTrue(tile.detail, tile.detail.contains(HighlightsModel.OBSERVED));
        }
        HighlightsModel live = HighlightsModel.of(TODAY, LIVE_UNSAVED, List.of(), false, 0, false, 9_000);
        assertEquals(HighlightsModel.NO_LIVE_LOOT, live.ut().detail);
        HighlightsModel unavailable = HighlightsModel.unavailable(TODAY, SAVED, "Saved history could not be listed (IOException)", 9_000);
        assertEquals("Saved history could not be listed (IOException)", unavailable.unavailable());
        assertEquals(DisplayValue.State.UNKNOWN, unavailable.ut().state);
        assertTrue(unavailable.notable().isEmpty() && unavailable.dungeons().isEmpty());
    }

    @Test public void bagsWithoutAKnownAreaAreKeptInOneUnknownAreaCellLast() {
        assertEquals("The literal matches the catalog's word for an area it does not know", HighlightsModel.UNRECOGNIZED,
            ParseDungeon.canonicalName("No such synthetic area"));
        HighlightsModel model = model(List.of(
            bag("White", null, 1_000, null, item(1, true, false, false, 0)),
            bag("White", HighlightsModel.UNRECOGNIZED, 2_000, null, item(LIFE, false, false, true, null), item(2, false, false, true, null)),
            bag("Orange", "Snake Pit", 3_000, RUN, item(3, false, true, false, 0)),
            bag("Orange", "Pirate Cave", 4_000, RUN),
            bag("Orange", "Pirate Cave", 5_000, RUN, item(4, false, false, false, 0))));
        List<HighlightsModel.DungeonCell> cells = model.dungeons();
        assertEquals(3, cells.size());
        assertEquals("Most bags first, then by name; Unknown area last even with more bags", List.of("Pirate Cave", "Snake Pit"),
            List.of(cells.get(0).dungeon(), cells.get(1).dungeon()));
        HighlightsModel.DungeonCell unknown = cells.get(cells.size() - 1);
        assertNull("null = Unknown area", unknown.dungeon());
        assertEquals(2, unknown.bags()); assertEquals(1, unknown.ut()); assertEquals(0, unknown.st()); assertEquals(2, unknown.potions());
        assertEquals(new HighlightsModel.DungeonCell("Snake Pit", cells.get(1).portalId(), 1, 0, 1, 0), cells.get(1));
        assertEquals(2, cells.get(0).bags());
        assertNull(HighlightsModel.area(null)); assertNull(HighlightsModel.area(HighlightsModel.UNRECOGNIZED));
        assertEquals("Lost Halls", HighlightsModel.area("Lost Halls"));
        assertNull("A notable drop in an unrecognized area is in Unknown area", model.notable().stream()
            .filter(n -> n.itemId() == LIFE).findFirst().orElseThrow().dungeon());
    }

    @Test public void whiteBagsAreCountedByNameAndBagsWithoutANameAreNoted() {
        HighlightsModel model = model(List.of(bag("White", "Lost Halls", 1_000, null), bag("B.White", "Lost Halls", 2_000, null),
            bag(null, "Lost Halls", 3_000, null), bag("Orange", "Lost Halls", 4_000, null)));
        assertEquals("2", model.whites().text());
        assertEquals(4, model.bags());
        assertEquals("The legacy bag without a saved name", 1, model.unnamedBags());
        assertTrue(model.whites().tooltip(), model.whites().tooltip().contains("1 bag without a saved bag name is not counted"));
        assertFalse(model(List.of(bag("White", "Lost Halls", 1_000, null))).whites().tooltip().contains("not counted"));
    }

    @Test public void skippedSessionsMakeTheTilesPartialAndTheLiveListSaysItIsCapped() {
        List<LootFacts.Bag> bags = List.of(bag("White", "Lost Halls", 1_000, null, item(1, true, false, false, 0)));
        HighlightsModel partial = HighlightsModel.of(TODAY, SAVED, bags, true, 2, false, 9_000);
        assertEquals(DisplayValue.State.PARTIAL, partial.ut().state);
        assertTrue(partial.ut().detail, partial.ut().detail.startsWith("2 saved sessions could not be read"));
        assertEquals(2, partial.sessionsSkipped());
        assertEquals("Saved history · Today", partial.sourceLabel());
        assertEquals("Saved history · This session", HighlightsModel.of(SESSION, SAVED, bags, true, 0, false, 9_000).sourceLabel());
        HighlightsModel live = HighlightsModel.of(SESSION, LIVE_UNSAVED, bags, true, 0, false, 9_000);
        assertEquals("This app run · not saved", live.sourceLabel());
        assertEquals(DisplayValue.State.KNOWN, live.ut().state);
        HighlightsModel capped = HighlightsModel.of(SESSION, LIVE_UNSAVED, bags, true, 0, true, 9_000);
        assertEquals("This app run · not saved · latest 1,000 bags", capped.sourceLabel());
        assertTrue(capped.capped());
        assertEquals(DisplayValue.State.PARTIAL, capped.ut().state);
        assertTrue(capped.ut().detail, capped.ut().detail.contains("latest 1,000 bags"));
    }

    @Test public void theNotableListKeepsTheNewest200AndSaysHowManyThereWere() {
        List<LootFacts.Bag> bags = new ArrayList<>();
        for (int i = 0; i < 250; i++) bags.add(bag("Orange", "Lost Halls", 1_000 + i, null, item(i, true, false, false, 0)));
        HighlightsModel model = model(bags);
        assertEquals(HighlightsModel.NOTABLE_LIMIT, model.notable().size());
        assertEquals(250, model.notableTotal());
        assertEquals("Newest first", 1_249, model.notable().get(0).time());
        assertEquals(1_050, model.notable().get(199).time());
        assertEquals("250", model.ut().text());
    }

    @Test public void hiddenBagColorsNeverCrowdOutOlderVisibleNotableDrops() {
        // Codex review (PR #27): the newest 200 were kept before Filter Loot applied, so newer drops of a hidden color could leave
        // older visible drops out. Each bag name keeps its own newest 200, so any filter shows the newest 200 visible drops.
        List<LootFacts.Bag> bags = new ArrayList<>();
        for (int i = 0; i < 50; i++) bags.add(bag("White", "Lost Halls", 1_000 + i, null, item(10_000 + i, true, false, false, 0)));
        for (int i = 0; i < 250; i++) bags.add(bag("Orange", "Lost Halls", 5_000 + i, null, item(i, true, false, false, 0)));
        HighlightsModel model = model(bags);
        assertEquals(300, model.notableTotal());
        assertTrue("At most the newest 200 per bag name are kept", model.notable().size() <= 2 * HighlightsModel.NOTABLE_LIMIT);
        HighlightsModel.Shown all = model.shown(bag -> true);
        assertEquals(HighlightsModel.NOTABLE_LIMIT, all.items().size());
        assertEquals(300, all.total());
        assertEquals(0, all.hidden());
        assertEquals("Newest first", 5_249, all.items().get(0).time());
        HighlightsModel.Shown noOrange = model.shown(bag -> !"Orange".equals(bag));
        assertEquals("Hiding orange shows the older white drops", 50, noOrange.items().size());
        assertEquals(50, noOrange.total());
        assertEquals(250, noOrange.hidden());
        assertEquals(1_049, noOrange.items().get(0).time());
        HighlightsModel.Shown none = model.shown(bag -> false);
        assertTrue(none.items().isEmpty());
        assertEquals(0, none.total());
        assertEquals(300, none.hidden());
        HighlightsModel nameless = model(List.of(bag(null, "Lost Halls", 1_000, null, item(1, true, false, false, 0))));
        assertEquals("A bag without a saved name is offered to the filter as null", 1, nameless.shown(bag -> bag == null).total());
    }

    @Test public void theModelIsImmutable() {
        List<LootFacts.Bag> bags = new ArrayList<>(List.of(bag("White", "Lost Halls", 1_000, null, item(LIFE, false, false, true, null))));
        HighlightsModel model = model(bags);
        assertThrows(UnsupportedOperationException.class, () -> model.notable().clear());
        assertThrows(UnsupportedOperationException.class, () -> model.dungeons().clear());
        assertThrows(UnsupportedOperationException.class, () -> model.potionsByStat().clear());
        assertEquals(model, model(bags));
    }
}
