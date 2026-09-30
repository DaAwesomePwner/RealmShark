package tomato.gui.runs;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import org.junit.Test;
import tomato.gui.kit.Tokens;
import tomato.gui.kit.Type;
import tomato.gui.modern.ContentStyle;
import tomato.history.link.VisitRef;
import tomato.realmshark.EnchantInfo;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeHistoryFixture.*;

/** The painted run card: the lines each state paints and the accessible name that says every fact and every missing link. */
public class RunCardRendererTest {
    private static final VisitRef REF = new VisitRef(RunFixtures.A, "v1");

    /** A linked, completed run: your verified row (#2 of 6, 30 %, one death) from the longest of two recordings, 6 items, +240 fame. */
    static RunCardModel linked() {
        return new RunCardModel(REF, "Lost Halls", "Lost Halls", 0, RunOutcome.COMPLETED, at(0, 8, 5), 25 * MINUTE, 6,
            new RunCardModel.Combat("r-v1-long", 2, 6_000L, 20d, 2, 6, 30d, 1, null), null,
            List.of(new RunCardModel.LootItem(502, "White", "UT"), new RunCardModel.LootItem(506, "Orange", "ST"),
                new RunCardModel.LootItem(501, "White", ""), new RunCardModel.LootItem(505, "Orange", ""),
                new RunCardModel.LootItem(504, "Orange", "T12"), new RunCardModel.LootItem(503, "Orange", "")),
            6, "1 UT · 1 ST · 2 potions", null, 240L, 2);
    }

    @Test public void enchantedLootPaintsItsRarityGemAndKeepsTheCardsWords() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RunCardModel plain = linked();
            List<RunCardModel.LootItem> loot = new ArrayList<>(plain.loot());
            RunCardModel.LootItem first = loot.get(0);
            loot.set(0, new RunCardModel.LootItem(first.id(), first.bag(), first.tier(), EnchantInfo.ofSlotCount(3)));
            // linked()'s arguments with the enchanted loot list.
            RunCardModel gem = new RunCardModel(REF, "Lost Halls", "Lost Halls", 0, RunOutcome.COMPLETED, at(0, 8, 5), 25 * MINUTE, 6,
                new RunCardModel.Combat("r-v1-long", 2, 6_000L, 20d, 2, 6, 30d, 1, null), null, loot, 6, "1 UT · 1 ST · 2 potions", null, 240L, 2);
            assertEquals("The card's words are unchanged", RunCardRenderer.accessibleName(plain, ZONE, NOW), RunCardRenderer.accessibleName(gem, ZONE, NOW));
            BufferedImage before = card(plain), after = card(gem);
            int ink = Tokens.rarity(EnchantInfo.Rarity.LEGENDARY).getRGB(), changed = 0; boolean inked = false;
            for (int y = 0; y < before.getHeight(); y++) for (int x = 0; x < before.getWidth(); x++)
                if (before.getRGB(x, y) != after.getRGB(x, y)) { changed++; inked |= after.getRGB(x, y) == ink; }
            assertTrue("The gem is painted", changed > 8);
            assertTrue("…in the Legendary ink", inked);
        });
    }

    private static BufferedImage card(RunCardModel model) {
        RunCardRenderer renderer = new RunCardRenderer(ZONE, () -> NOW);
        Component painted = renderer.getListCellRendererComponent(new JList<>(), model, 0, false, false);
        Dimension size = renderer.getPreferredSize();
        painted.setSize(size);
        BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try { painted.paint(g); } finally { g.dispose(); }
        return image;
    }

    private static RunCardModel with(RunCardModel card, RunOutcome outcome, Long duration, Integer party, RunCardModel.Combat combat,
                                     String combatReason, List<RunCardModel.LootItem> loot, int lootCount, String lootSummary,
                                     String lootReason, Long fame, Integer exalt) {
        return new RunCardModel(card.ref(), card.map(), card.mapName(), card.portalId(), outcome, card.entered(), duration, party, combat,
            combatReason, loot, lootCount, lootSummary, lootReason, fame, exalt);
    }

    private static RunCardRenderer.Lines lines(RunCardModel card) { return RunCardRenderer.lines(card, ZONE, NOW); }
    private static String name(RunCardModel card) { return RunCardRenderer.accessibleName(card, ZONE, NOW); }

    @Test public void aLinkedRunShowsYourDpsRankShareLootFameDeathsAndExaltProgress() {
        RunCardModel card = linked();
        RunCardRenderer.Lines lines = lines(card);
        assertEquals("Lost Halls", lines.title());
        assertEquals("Completed", lines.chip());
        assertEquals(Tokens.Tone.GOOD, lines.tone());
        assertEquals("08:05 · 25 m observed · Party 6", lines.when());
        assertEquals("Your DPS 20 · #2 of 6 · 30%", lines.combat());
        assertEquals(Double.valueOf(30), lines.share());
        assertEquals("", lines.combatNote());
        assertEquals(card.loot(), lines.loot());
        assertEquals("", lines.lootMore());
        assertEquals("1 UT · 1 ST · 2 potions", lines.lootSummary());
        assertEquals("", lines.lootNote());
        assertEquals("+240 fame · Deaths 1 · Exalt progress +2", lines.facts());
        String name = name(card);
        for (String fact : new String[] {"Lost Halls, Completed", "entered today at 08:05", "25 m observed", "party of 6",
                "your DPS 20, rank 2 of 6, 30% of the damage, from the longest of 2 recordings", "6 loot items: 1 UT · 1 ST · 2 potions",
                "240 fame gained", "1 death", "exalt progress +2"})
            assertTrue("The name says '" + fact + "': " + name, name.contains(fact));
    }

    @Test public void anUnverifiedLocalRowShowsTheReasonAndNeverAnotherPlayersRow() {
        RunCardModel.Combat unverified = new RunCardModel.Combat("r-v2", 1, null, null, null, 4, null, null, RunCardModel.UNVERIFIED_LOCAL);
        RunCardModel card = with(linked(), RunOutcome.LEFT, 15 * MINUTE, 6, unverified, RunCardModel.UNVERIFIED_LOCAL, linked().loot(), 6,
            "1 UT · 1 ST · 2 potions", null, 0L, null);
        RunCardRenderer.Lines lines = lines(card);
        assertEquals("", lines.combat());
        assertNull("No share bar without your row", lines.share());
        assertEquals(RunCardModel.UNVERIFIED_LOCAL, lines.combatNote());
        assertEquals("Deaths need your verified row; a known zero fame still shows", "+0 fame", lines.facts());
        assertEquals("Left", lines.chip());
        assertEquals(Tokens.Tone.NEUTRAL, lines.tone());
        String name = name(card);
        assertTrue(name, name.contains("the local player's row was not verified for this encounter; another player's row is never substituted"));
        assertFalse("No DPS is claimed as yours: " + name, name.contains("your DPS"));
        assertFalse(name, name.contains("death"));
        assertTrue(name, name.contains("no exalt progress recorded"));
    }

    @Test public void aRunWithoutARecordingSaysSoAndUnknownsStayUnknown() {
        RunCardModel card = with(linked(), RunOutcome.APP_ENDED, null, null, null, RunCardModel.NO_RECORDING, List.of(), 0, "", null, null, null);
        RunCardRenderer.Lines lines = lines(card);
        assertEquals("App ended", lines.chip());
        assertEquals(Tokens.Tone.NEUTRAL, lines.tone());
        assertEquals("08:05 · duration — · Party —", lines.when());
        assertEquals("", lines.combat());
        assertEquals(RunCardModel.NO_RECORDING, lines.combatNote());
        assertEquals("A known none", RunCardRenderer.NO_LOOT, lines.lootNote());
        assertEquals("Unknown fame shows nothing, never +0", "", lines.facts());
        String name = name(card);
        for (String fact : new String[] {"Lost Halls, App ended", "duration unknown", "party not observed", "no combat recording is linked to this run",
                "no loot recorded in this run", "fame gained unknown", "no exalt progress recorded"})
            assertTrue("The name says '" + fact + "': " + name, name.contains(fact));
    }

    @Test public void unknownLootShowsItsReasonNeverNoLoot() {
        for (String reason : new String[] {RunCardModel.LOOT_NOT_SAVED, RunCardModel.LOOT_UNREADABLE}) {
            RunCardModel card = with(linked(), RunOutcome.COMPLETED, 25 * MINUTE, 6, linked().combat(), null, linked().loot(), 6, "1 UT", reason, 240L, 2);
            RunCardRenderer.Lines lines = lines(card);
            assertEquals(reason, lines.lootNote());
            assertTrue(lines.loot().isEmpty());
            assertEquals("", lines.lootSummary());
            String name = name(card);
            assertTrue(name, name.contains("loot unknown: " + Character.toLowerCase(reason.charAt(0)) + reason.substring(1, reason.length() - 1)));
            assertFalse(name, name.contains("no loot recorded"));
        }
    }

    @Test public void theLootStripShowsEightSpritesThenTheRestAsACount() {
        List<RunCardModel.LootItem> eight = new ArrayList<>();
        for (int i = 0; i < RunCardModel.LOOT_ICONS; i++) eight.add(new RunCardModel.LootItem(900 + i, i % 2 == 0 ? "White" : "Brown", ""));
        RunCardModel card = with(linked(), RunOutcome.COMPLETED, 25 * MINUTE, 6, linked().combat(), null, eight, 11, "11 items", null, 240L, null);
        RunCardRenderer.Lines lines = lines(card);
        assertEquals(8, lines.loot().size());
        assertEquals("+3", lines.lootMore());
        assertEquals("11 items", lines.lootSummary());
        assertTrue(name(card).contains("11 loot items: 11 items"));
    }

    /**
     * The evidence's Lost Halls card: eight sprites, "+2" and "1 UT · 1 ST · 3 potions". At font 13 (1240×800) and font 18 (680×520)
     * the summary is painted whole, never over the sprites or "+2", inside the loot row the cell reserves: under the sprites when it
     * does not fit beside them. A summary that fits beside the sprites stays there.
     */
    @Test public void theLootSummaryIsNeverCutAndGoesUnderTheSpritesWhenItDoesNotFitBeside() throws Exception {
        List<RunCardModel.LootItem> eight = new ArrayList<>();
        for (int i = 0; i < RunCardModel.LOOT_ICONS; i++) eight.add(new RunCardModel.LootItem(900 + i, i < 3 ? "White" : "Orange", i == 0 ? "UT" : ""));
        RunCardModel ten = with(linked(), RunOutcome.COMPLETED, 26 * MINUTE, 6, linked().combat(), null, eight, 10, "1 UT · 1 ST · 3 potions", null, 510L, 1);
        SwingUtilities.invokeAndWait(() -> {
            Font previous = ContentStyle.body();
            try {
                for (int font : new int[] {13, 18}) {
                    ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, font));
                    ContentStyle.applyFontDefaults();
                    RunCardRenderer renderer = new RunCardRenderer(ZONE, () -> NOW);
                    Dimension cell = renderer.getPreferredSize();
                    FontMetrics caption = renderer.getFontMetrics(Type.caption());
                    int width = cell.width - RunCardRenderer.GAP - RunCardRenderer.EDGE - 2 * Tokens.M, well = RunCardRenderer.LOOT + RunCardRenderer.WELL;
                    RunCardRenderer.Lines lines = lines(ten);
                    assertEquals("+2", lines.lootMore());
                    RunCardRenderer.Strip strip = RunCardRenderer.strip(lines, caption, width);
                    assertEquals("Font " + font + ": the notable counts are never elided", "1 UT · 1 ST · 3 potions", strip.summary());
                    assertTrue("Font " + font + ": inside the row", strip.summaryX() + caption.stringWidth(strip.summary()) <= width);
                    assertTrue("Font " + font + ": inside the loot row the cell reserves",
                        strip.summaryBaseline() + caption.getDescent() <= RunCardRenderer.lootHeight(caption));
                    boolean beside = strip.summaryBaseline() == strip.moreBaseline();
                    if (beside) assertTrue("Font " + font + ": after \"+2\"", strip.summaryX() >= strip.moreX() + caption.stringWidth("+2") + Tokens.S);
                    else assertTrue("Font " + font + ": under the sprites", strip.summaryBaseline() - caption.getAscent() >= strip.wellTop() + well);
                    assertTrue("\"+2\" follows the eighth sprite", strip.moreX() >= RunCardModel.LOOT_ICONS * well);

                    RunCardRenderer.Strip six = RunCardRenderer.strip(lines(linked()), caption, width);
                    assertEquals("1 UT · 1 ST · 2 potions", six.summary());
                    assertEquals("Font " + font + ": a summary that fits stays beside the sprites", six.moreBaseline(), six.summaryBaseline());
                    assertTrue(six.summaryX() >= 6 * well);
                }
            } finally {
                ContentStyle.setBodyFont(previous);
                ContentStyle.applyFontDefaults();
            }
        });
    }

    @Test public void aVerifiedLocalPlayerWithoutDamageHasARealZeroAndAnUnknownRank() {
        RunCardModel.Combat idle = new RunCardModel.Combat("r", 1, 0L, 0d, null, 5, 0d, null, null);
        RunCardModel card = with(linked(), RunOutcome.COMPLETED, 25 * MINUTE, 6, idle, null, linked().loot(), 6, "1 UT · 1 ST · 2 potions", null, null, null);
        RunCardRenderer.Lines lines = lines(card);
        assertEquals("Your DPS 0 · rank — · 0%", lines.combat());
        assertEquals("Deaths unknown with a verified row show nothing", "", lines.facts());
        String name = name(card);
        assertTrue(name, name.contains("your DPS 0, rank unknown, 0% of the damage"));
        assertTrue(name, name.contains("deaths unknown"));
    }

    @Test public void timesReadTodayYesterdayOrTheDateAndSharesNeverRoundToNone() {
        assertEquals("08:05", RunCardRenderer.time(at(0, 8, 5), ZONE, NOW));
        assertEquals("Yesterday 22:10", RunCardRenderer.time(at(-1, 22, 10), ZONE, NOW));
        assertEquals("13 Jan 14:32", RunCardRenderer.time(at(-2, 14, 32), ZONE, NOW));
        assertEquals("31 Dec 2024 23:59", RunCardRenderer.time(at(-15, 23, 59), ZONE, NOW));
        RunCardModel yesterday = new RunCardModel(REF, "Lost Halls", "Lost Halls", 0, RunOutcome.COMPLETED, at(-1, 22, 10), null, null, null,
            RunCardModel.NO_RECORDING, List.of(), 0, "", null, null, null);
        assertTrue(name(yesterday).contains("entered yesterday at 22:10"));
        assertEquals("34%", RunCardRenderer.percent(34.4));
        assertEquals("5.3%", RunCardRenderer.percent(5.26));
        assertEquals("0.4%", RunCardRenderer.percent(0.4));
        assertEquals("0%", RunCardRenderer.percent(0));
    }

    @Test public void everyStatePaintsInAFixedCellThatGrowsWithTheFont() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Font previous = ContentStyle.body();
            try {
                List<RunCardModel> cards = List.of(linked(),
                    with(linked(), RunOutcome.IN_PROGRESS, 3 * MINUTE, null, null, RunCardModel.NO_RECORDING, List.of(), 0, "", RunCardModel.LOOT_NOT_SAVED, null, null),
                    with(linked(), RunOutcome.UNKNOWN, null, null, new RunCardModel.Combat("r", 1, null, null, null, 4, null, null, RunCardModel.UNVERIFIED_LOCAL),
                        RunCardModel.UNVERIFIED_LOCAL, List.of(), 0, "", null, null, null));
                Dimension small = null;
                for (int font : new int[] {13, 18}) {
                    ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, font));
                    ContentStyle.applyFontDefaults();
                    RunCardRenderer renderer = new RunCardRenderer(ZONE, () -> NOW);
                    Dimension cell = renderer.getPreferredSize();
                    if (small == null) small = cell;
                    else assertTrue("The cell grows with the font: " + small + " → " + cell, cell.width > small.width && cell.height > small.height);
                    assertTrue("One card fits a 680 px window at font " + font + ": " + cell, cell.width <= 600);
                    JList<RunCardModel> list = new JList<>();
                    for (RunCardModel card : cards) {
                        Component painted = renderer.getListCellRendererComponent(list, card, 0, true, true);
                        assertSame(renderer, painted);
                        assertEquals(RunCardRenderer.accessibleName(card, ZONE, NOW), painted.getAccessibleContext().getAccessibleName());
                        painted.setSize(cell);
                        BufferedImage image = new BufferedImage(cell.width, cell.height, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D g = image.createGraphics();
                        try { painted.paint(g); } finally { g.dispose(); }
                        assertNotEquals("The card paints", 0, image.getRGB(cell.width / 2, cell.height / 2) >>> 24);
                    }
                }
            } finally {
                ContentStyle.setBodyFont(previous);
                ContentStyle.applyFontDefaults();
            }
        });
    }

    @Test public void reasonsWrapOnWordsIntoAtMostTwoLines() {
        FontMetrics metrics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().getFontMetrics(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        List<String> rows = RunCardRenderer.wrap(RunCardModel.UNVERIFIED_LOCAL, metrics, 250, 2);
        assertEquals(2, rows.size());
        assertTrue(rows.get(0), metrics.stringWidth(rows.get(0)) <= 250);
        assertTrue("Words are not split: " + rows, RunCardModel.UNVERIFIED_LOCAL.startsWith(rows.get(0) + " "));
        assertEquals(List.of("Short"), RunCardRenderer.wrap("Short", metrics, 250, 2));
    }
}
