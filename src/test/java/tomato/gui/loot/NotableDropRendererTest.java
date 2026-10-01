package tomato.gui.loot;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.ZoneId;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import org.junit.Rule;
import org.junit.Test;
import tomato.gui.kit.Sprites;
import tomato.gui.kit.ItemTiers;
import tomato.gui.kit.Tokens;
import tomato.gui.kit.Type;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.Themes;
import tomato.history.link.VisitRef;
import tomato.realmshark.EnchantInfo;
import static org.junit.Assert.*;
import static tomato.gui.glance.home.HomeHistoryFixture.*;

/** Painted notable-drop and per-dungeon cells: the bag-tinted well, the kind chip, area and time, links in words, long names. */
public class NotableDropRendererTest {
    @Rule public ui.VisualEvidence evidence = new ui.VisualEvidence("p6a");
    private static final ZoneId ZONE_NY = ZONE;
    private static final long NOON = at(0, 12, 0);
    private static final VisitRef RUN = new VisitRef("s", "v1");

    private static HighlightsModel.Notable notable(String bag, String dungeon, long time, VisitRef visit, HighlightsModel.Kind kind) {
        return new HighlightsModel.Notable(4242, bag, dungeon, time, visit, kind);
    }

    private static HighlightsModel.Notable enchanted(EnchantInfo enchant) {
        return new HighlightsModel.Notable(4242, "White", "Lost Halls", NOON, RUN, HighlightsModel.Kind.ENCHANTED, enchant);
    }

    @Test public void loadedItemTypesStaySeparateFromTheNotabilityReason() throws Exception {
        try (AutoCloseable restore = tomato.gui.glance.character.CharacterFixtures.installDefinitions()) {
            var definitions = tomato.backend.data.RosterDefinitions.parse(null, new java.io.StringReader(
                "<Objects><Object type='4242'><Tier>12</Tier></Object>"
                + "<Object type='4243'><Labels>UT</Labels></Object>"
                + "<Object type='4244'><Labels>ST</Labels></Object></Objects>"));
            var current = tomato.backend.data.RosterDefinitions.class.getDeclaredField("current");
            current.setAccessible(true);
            current.set(null, definitions);
            SwingUtilities.invokeAndWait(() -> {
                NotableDropRenderer renderer = new NotableDropRenderer(ZONE_NY, () -> NOON);
                for (int id = 4242; id <= 4244; id++) {
                    HighlightsModel.Notable drop = new HighlightsModel.Notable(id, "White", "Lost Halls", NOON, RUN,
                        HighlightsModel.Kind.ENCHANTED, EnchantInfo.ofSlotCount(3));
                    String type = id == 4242 ? "T12" : id == 4243 ? "UT" : "ST";
                    renderer.getListCellRendererComponent(new JList<>(), drop, 0, false, false);
                    assertEquals(type, renderer.shown().chip());
                    assertEquals(id == 4242 ? Tokens.Tone.NEUTRAL : id == 4243 ? Tokens.Tone.WARN : Tokens.Tone.BAD, renderer.shown().tone());
                    assertTrue(renderer.getAccessibleContext().getAccessibleName().contains(type + " (Legendary · 3 enchant slots)"));
                    assertTrue(renderer.getToolTipText().contains(type + ";"));
                    List<String> painted = paint(renderer, renderer.getPreferredSize());
                    assertTrue(painted.toString(), painted.contains(type) && painted.contains("Legendary"));
                }
                assertEquals("Potion", NotableDropRenderer.lines(notable("Orange", "Lost Halls", NOON, RUN,
                    HighlightsModel.Kind.POTION), ZONE_NY, NOON).chip());
            });
        }
    }

    @Test public void anEnchantedDropSaysItsRarityAndShowsTheEnchantTooltipOnHover() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EnchantInfo legendary = EnchantInfo.ofSlotCount(3);
            HighlightsModel.Notable drop = enchanted(legendary);
            String name = NotableDropRenderer.accessibleName(drop, ZONE_NY, NOON);
            assertTrue(name, name.contains("Gear (Legendary · 3 enchant slots)"));
            NotableDropRenderer renderer = new NotableDropRenderer(ZONE_NY, () -> NOON);
            renderer.getListCellRendererComponent(new JList<>(), drop, 0, false, false);
            String tip = renderer.getToolTipText();
            assertTrue(tip, tip.startsWith("<html>") && tip.contains("Legendary · 3 enchant slots") && tip.contains("Enchant names not available"));
            assertEquals("The tooltip says the rarity once: " + tip, 1, tip.split("Legendary · 3 enchant slots", -1).length - 1);
            assertTrue("Its heading still gives the facts: " + tip, tip.contains("Gear;"));
            assertEquals("Gear", renderer.shown().chip());
            List<String> painted = paint(renderer, renderer.getPreferredSize());
            assertTrue(painted.toString(), painted.contains("Legendary"));
            String plain = NotableDropRenderer.accessibleName(enchanted(EnchantInfo.notRecorded()), ZONE_NY, NOON);
            assertFalse("Without enchant data the name adds no rarity: " + plain, plain.contains("enchant slot"));
            renderer.getListCellRendererComponent(new JList<>(), enchanted(EnchantInfo.notRecorded()), 0, false, false);
            assertEquals("…and the tooltip stays the plain facts", plain + " · " + HighlightsModel.OBSERVED, renderer.getToolTipText());
        });
    }

    @Test public void thePipsArePaintedInsideTheWellsBottomEdge() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NotableDropRenderer renderer = new NotableDropRenderer(ZONE_NY, () -> NOON);
            renderer.getListCellRendererComponent(new JList<>(), enchanted(EnchantInfo.notRecorded()), 0, false, false);
            Dimension size = renderer.getPreferredSize();
            BufferedImage plain = image(renderer, size);
            renderer.getListCellRendererComponent(new JList<>(), enchanted(EnchantInfo.ofSlotCount(3)), 0, false, false);
            BufferedImage gem = image(renderer, size);
            Rectangle well = NotableDropRenderer.well(size.width, size.height);
            int ink = Tokens.rarity(EnchantInfo.Rarity.LEGENDARY).getRGB(), changed = 0; boolean inked = false;
            for (int y = well.y; y < well.y + well.height; y++) for (int x = well.x; x < well.x + well.width; x++) {
                if (plain.getRGB(x, y) == gem.getRGB(x, y)) continue;
                changed++; inked |= gem.getRGB(x, y) == ink;
                assertTrue("Pips stay inside the well's bottom edge at " + x + "," + y,
                    x > well.x && x < well.x + well.width - 1 && y > well.y + well.height / 2 && y < well.y + well.height - 1);
            }
            assertTrue("The pips are painted", changed > 8);
            assertTrue("…in the Legendary ink", inked);
        });
    }

    @Test public void anUnenchantedUtDropPaintsTierTextInsideItsFortyPixelWell() throws Exception {
        try (AutoCloseable restore = tomato.gui.glance.character.CharacterFixtures.installDefinitions()) {
            var definitions = tomato.backend.data.RosterDefinitions.parse(null, new java.io.StringReader(
                "<Objects><Object type='987654321'><Labels>UT</Labels></Object></Objects>"));
            var current = tomato.backend.data.RosterDefinitions.class.getDeclaredField("current");
            current.setAccessible(true);
            current.set(null, definitions);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("The renderer receives a real tier label from the fixture definitions", "UT", ItemTiers.label(987_654_321));
                HighlightsModel.Notable drop = new HighlightsModel.Notable(987_654_321, "White", "Lost Halls", NOON, RUN,
                    HighlightsModel.Kind.UT, EnchantInfo.ofSlotCount(0));
                NotableDropRenderer renderer = new NotableDropRenderer(ZONE_NY, () -> NOON);
                renderer.getListCellRendererComponent(new JList<>(), drop, 0, false, false);
                Dimension size = renderer.getPreferredSize();
                BufferedImage painted = image(renderer, size);
                Rectangle well = NotableDropRenderer.well(size.width, size.height);
                assertEquals(40, well.width); assertEquals(40, well.height);
                int warn = Tokens.color(Tokens.Role.WARN).getRGB(), textPixels = 0;
                // Inspect only the well's interior, so neither its bag border nor the separate UT chip can satisfy this test.
                for (int y = well.y + 4; y < well.y + well.height - 4; y++)
                    for (int x = well.x + 4; x < well.x + well.width - 4; x++) if (painted.getRGB(x, y) == warn) {
                        textPixels++;
                        assertTrue("UT text stays in the well's bottom-right quarter",
                            x >= well.x + well.width / 2 && y >= well.y + well.height / 2);
                    }
                assertTrue("The unenchanted UT drop paints WARN-colored tier text", textPixels > 0);
            });
        }
    }

    private static BufferedImage image(JComponent c, Dimension size) {
        c.setSize(size);
        BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics(); c.paint(g); g.dispose();
        return image;
    }

    @Test public void linesSayTheKindTheAreaAndWhenAndAMissingRunLink() {
        NotableDropRenderer.Lines lines = NotableDropRenderer.lines(notable("Orange", "Lost Halls", at(0, 9, 5), RUN, HighlightsModel.Kind.UT), ZONE_NY, NOON);
        assertEquals(Sprites.name(4242), lines.name());
        assertEquals("UT", lines.chip()); assertEquals(Tokens.Tone.WARN, lines.tone());
        assertEquals("09:05", lines.when()); assertEquals("Lost Halls", lines.where());
        assertEquals("A linked drop needs no note", "", lines.link());
        NotableDropRenderer.Lines unlinked = NotableDropRenderer.lines(notable(null, null, at(-1, 22, 10), null, HighlightsModel.Kind.ENCHANTED), ZONE_NY, NOON);
        assertEquals("Yesterday 22:10", unlinked.when()); assertEquals("Unknown area", unlinked.where());
        assertEquals("Not linked to a run", unlinked.link());
        assertEquals("Gear", unlinked.chip()); assertEquals(Tokens.Tone.NEUTRAL, unlinked.tone());
        assertEquals(Tokens.Tone.BAD, NotableDropRenderer.lines(notable("White", "Lost Halls", NOON, RUN, HighlightsModel.Kind.ST), ZONE_NY, NOON).tone());
        assertEquals(Tokens.Tone.INFO, NotableDropRenderer.lines(notable("White", "Lost Halls", NOON, RUN, HighlightsModel.Kind.POTION), ZONE_NY, NOON).tone());
        assertEquals("13 Jan 14:32", NotableDropRenderer.time(at(-2, 14, 32), ZONE_NY, NOON));
        assertEquals("13 Jan 2024 14:32", NotableDropRenderer.time(at(-2, 14, 32) - 366L * 24 * HOUR, ZONE_NY, NOON));
    }

    @Test public void theAccessibleNameSaysEveryFactInWords() {
        String linked = NotableDropRenderer.accessibleName(notable("Orange", "Lost Halls", at(0, 9, 5), RUN, HighlightsModel.Kind.UT), ZONE_NY, NOON);
        assertEquals(Sprites.name(4242) + ", UT; Lost Halls, today at 09:05; Orange bag; Enter opens the run recap", linked);
        String unlinked = NotableDropRenderer.accessibleName(notable(null, null, at(-1, 22, 10), null, HighlightsModel.Kind.POTION), ZONE_NY, NOON);
        assertEquals(Sprites.name(4242) + ", stat potion; Unknown area, yesterday at 22:10; bag not saved; not linked to a run", unlinked);
    }

    @Test public void theWellIsTintedInTheBagsColor() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (String bag : new String[] {"Orange", "B.White", null}) {
                NotableDropRenderer renderer = new NotableDropRenderer(ZONE_NY, () -> NOON);
                JList<HighlightsModel.Notable> list = new JList<>();
                renderer.getListCellRendererComponent(list, notable(bag, "Lost Halls", NOON, RUN, HighlightsModel.Kind.UT), 0, false, false);
                Dimension size = renderer.getPreferredSize();
                renderer.setSize(size);
                BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics();
                renderer.paint(g);
                g.dispose();
                Rectangle well = renderer.well(size.width, size.height);
                assertTrue("The well fits the cell: " + well + " in " + size, new Rectangle(size).contains(well));
                Color expected = Tokens.tint(Tokens.bag(bag)), actual = new Color(image.getRGB(well.x + 2, well.y + well.height / 2), true);
                assertTrue(bag + ": " + actual + " vs " + expected, close(expected, actual));
            }
        });
    }

    @Test public void longNamesAreCutWithAnEllipsisAndTheCellFollowsTheFont() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NotableDropRenderer renderer = new NotableDropRenderer(ZONE_NY, () -> NOON);
            FontMetrics metrics = renderer.getFontMetrics(Type.emphasis());
            String longName = "An Extraordinarily Long Synthetic Item Name That Never Fits In One Cell";
            String fitted = NotableDropRenderer.fit(longName, metrics, 120);
            assertTrue(fitted, fitted.endsWith("…") && metrics.stringWidth(fitted) <= 120);
            assertEquals("Short text is kept whole", "Short", NotableDropRenderer.fit("Short", metrics, 120));
            int small = renderer.getPreferredSize().width, smallHeight = renderer.getPreferredSize().height;
            ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 18));
            assertTrue("Font 18 cells are larger", renderer.getPreferredSize().width > small && renderer.getPreferredSize().height > smallHeight);
            DungeonStripRenderer strip = new DungeonStripRenderer();
            assertTrue(strip.getPreferredSize().width > 0 && strip.getPreferredSize().height > 0);
        });
    }

    /**
     * Time leads the area on a line below the chips, so "Lost Halls" (and "Unknown area") paint whole
     * beside any chip at font 13 (the 17 em cell of 1240×800) and font 18; "Not linked to a run" keeps its own line; every card is
     * the same size.
     */
    @Test public void timeAndAreaPaintTogetherBelowAnyChip() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JList<HighlightsModel.Notable> list = new JList<>();
            for (int font : new int[] {13, 18}) {
                ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, font));
                NotableDropRenderer renderer = new NotableDropRenderer(ZONE_NY, () -> NOON);
                Dimension cell = null;
                for (HighlightsModel.Kind kind : HighlightsModel.Kind.values())
                    for (String dungeon : new String[] {"Lost Halls", null})
                        for (VisitRef visit : new VisitRef[] {RUN, null}) {
                            HighlightsModel.Notable drop = new HighlightsModel.Notable(9065, "White", dungeon, at(0, 11, 30), visit, kind);
                            renderer.getListCellRendererComponent(list, drop, 0, false, false);
                            Dimension size = renderer.getPreferredSize();
                            if (cell == null) cell = size; else assertEquals("Every card is the same size", cell, size);
                            List<String> painted = paint(renderer, size);
                            String area = dungeon == null ? "Unknown area" : dungeon, what = "font " + font + ", " + kind + ", " + area
                                + (visit == null ? ", not linked" : "") + ": " + painted;
                            assertTrue(what, painted.contains("11:30 · " + area));
                            assertTrue(what, painted.contains(kind == HighlightsModel.Kind.ENCHANTED ? "Gear" : kind.label()));
                            assertTrue(what, painted.contains(Sprites.name(9065)));
                            assertEquals(what, visit == null, painted.contains("Not linked to a run"));
                            for (String text : painted) assertFalse(what + ": nothing is cut", text.endsWith("…"));
                        }
            }
        });
    }

    @Test public void enchantedCardsKeepTheFullTimeAtTheStartOfTheAreaLine() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Font previous = ContentStyle.body();
            try {
                ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 13));
                NotableDropRenderer renderer = new NotableDropRenderer(ZONE_NY, () -> NOON);
                for (int count : new int[] {0, 3}) for (long time : new long[] {at(0, 14, 32), at(-1, 22, 10)}) {
                    HighlightsModel.Notable drop = new HighlightsModel.Notable(4242, "White", "Lost Halls", time, RUN,
                        HighlightsModel.Kind.ENCHANTED, EnchantInfo.ofSlotCount(count));
                    renderer.getListCellRendererComponent(new JList<>(), drop, 0, false, false);
                    Dimension size = renderer.getPreferredSize();
                    List<String> painted = paint(renderer, size);
                    FontMetrics caption = renderer.getFontMetrics(Type.caption());
                    int width = size.width - NotableDropRenderer.GAP - 2 * NotableDropRenderer.PAD
                        - NotableDropRenderer.WELL_SIDE - Tokens.S;
                    String when = NotableDropRenderer.time(time, ZONE_NY, NOON);
                    String expected = NotableDropRenderer.fit(when + " · Lost Halls", caption, width);
                    assertTrue(painted.toString(), painted.contains(expected));
                    assertTrue("The whole time leads the combined line", expected.startsWith(when + " · "));
                    int typeIndex = painted.indexOf("Gear");
                    assertTrue(typeIndex >= 0);
                    if (count > 0) assertEquals("Legendary", painted.get(typeIndex + 1));
                    assertEquals("Only chips precede the combined line", expected, painted.get(typeIndex + (count > 0 ? 2 : 1)));
                }
            } finally { ContentStyle.setBodyFont(previous); }
        });
    }

    /** A name longer than the card is still cut with "…"; the tooltip says every fact in full (item, kind, area, time, bag, link). */
    @Test public void aTooLongAreaIsCutAndTheTooltipSaysItInFull() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String longArea = "An Extraordinarily Long Synthetic Dungeon Name";
            NotableDropRenderer renderer = new NotableDropRenderer(ZONE_NY, () -> NOON);
            HighlightsModel.Notable drop = new HighlightsModel.Notable(9065, "White", longArea, at(0, 11, 30), null, HighlightsModel.Kind.POTION);
            renderer.getListCellRendererComponent(new JList<>(), drop, 0, false, false);
            List<String> painted = paint(renderer, renderer.getPreferredSize());
            assertTrue("The area is cut after the full time: " + painted, painted.stream().anyMatch(text -> text.startsWith("11:30 · ")
                && text.endsWith("…") && ("11:30 · " + longArea).startsWith(text.substring(0, text.length() - 1))));
            String tip = renderer.getToolTipText();
            for (String fact : new String[] {Sprites.name(9065), "stat potion", longArea, "today at 11:30", "White bag", "not linked to a run", HighlightsModel.OBSERVED})
                assertTrue("The tooltip says '" + fact + "': " + tip, tip.contains(fact));
        });
    }

    /** Paints one cell off screen and returns the strings it drew (after fitting). */
    private static List<String> paint(NotableDropRenderer renderer, Dimension size) {
        renderer.setSize(size);
        BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        renderer.paint(g);
        g.dispose();
        return renderer.painted();
    }

    @Test public void stripCellsSayTheBagsAndNotableCounts() {
        DungeonStripRenderer.Lines halls = DungeonStripRenderer.lines(new HighlightsModel.DungeonCell("Lost Halls", 0, 3, 1, 0, 2));
        assertEquals("Lost Halls", halls.name()); assertEquals("3 bags", halls.bags()); assertEquals("1 UT · 2 potions", halls.summary());
        DungeonStripRenderer.Lines unknown = DungeonStripRenderer.lines(new HighlightsModel.DungeonCell(null, 0, 1, 0, 1, 1));
        assertEquals("Unknown area", unknown.name()); assertEquals("1 bag", unknown.bags()); assertEquals("1 ST · 1 potion", unknown.summary());
        assertEquals("No UT, ST or potions", DungeonStripRenderer.lines(new HighlightsModel.DungeonCell("Snake Pit", 0, 2, 0, 0, 0)).summary());
        assertEquals("Lost Halls; 3 bags; 1 UT · 2 potions", DungeonStripRenderer.accessibleName(new HighlightsModel.DungeonCell("Lost Halls", 0, 3, 1, 0, 2)));
    }

    /**
     * P6b Task 9 (R3 B9): a name wider than the card wraps to a second line at its spaces instead of ending in "…", and every card
     * is one title line taller for it (the same size whatever its name). A name too long for two lines is cut on the second; the
     * tooltip and the accessible name keep it whole and are unchanged.
     */
    @Test public void longNamesWrapToTwoLinesAndEveryCardIsOneTitleLineTaller() throws Exception {
        String wraps = "Synthetic Crystal Mail of Tides", endless = "Synthetic Everlasting Crystal Mail of the Very Deep and Endless Tides";
        Runnable restore = LootHighlightsTest.names(java.util.Map.of(9901, wraps, 9902, endless, 9903, "Short"));
        try {
            SwingUtilities.invokeAndWait(() -> {
                ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 13));
                NotableDropRenderer renderer = new NotableDropRenderer(ZONE_NY, () -> NOON);
                FontMetrics title = renderer.getFontMetrics(Type.emphasis()), caption = renderer.getFontMetrics(Type.caption());
                int text = 2 * title.getHeight() + Tokens.XS + caption.getHeight() + 2 + NotableDropRenderer.LINE + 2 * caption.getHeight();
                int height = 2 * NotableDropRenderer.PAD + Math.max(NotableDropRenderer.WELL_SIDE, text) + NotableDropRenderer.GAP;
                JList<HighlightsModel.Notable> list = new JList<>();
                Dimension cell = null;
                for (int id : new int[] {9901, 9902, 9903}) {
                    HighlightsModel.Notable drop = new HighlightsModel.Notable(id, "Orange", "Lost Halls", at(0, 11, 30), RUN, HighlightsModel.Kind.UT);
                    renderer.getListCellRendererComponent(list, drop, 0, false, false);
                    Dimension size = renderer.getPreferredSize();
                    assertEquals("The cell is one title line taller: " + size, height, size.height);
                    if (cell == null) cell = size; else assertEquals("Every card is the same size", cell, size);
                    List<String> painted = paint(renderer, size);
                    String name = Sprites.name(id);
                    assertEquals("The tooltip is unchanged", NotableDropRenderer.accessibleName(drop, ZONE_NY, NOON) + " · " + HighlightsModel.OBSERVED, renderer.getToolTipText());
                    assertTrue("…and says the whole name", renderer.getToolTipText().startsWith(name + ", UT; Lost Halls"));
                    assertEquals(NotableDropRenderer.accessibleName(drop, ZONE_NY, NOON), renderer.getAccessibleContext().getAccessibleName());
                    int index = painted.indexOf("UT");
                    List<String> nameLines = painted.subList(0, index);
                    assertTrue(name + " paints its time and area after its name: " + painted, painted.contains("11:30 · Lost Halls"));
                    if (id == 9903) assertEquals("A short name keeps one line", List.of("Short"), nameLines);
                    else if (id == 9901) {
                        assertEquals(wraps + " takes two lines: " + nameLines, 2, nameLines.size());
                        assertEquals("…wrapped at a space, nothing cut", wraps, String.join(" ", nameLines));
                    } else {
                        assertEquals(endless + " takes two lines: " + nameLines, 2, nameLines.size());
                        assertTrue("…the second cut with \"…\": " + nameLines, nameLines.get(1).endsWith("…") && !nameLines.get(0).endsWith("…"));
                        assertTrue(endless.startsWith(nameLines.get(0) + " "));
                    }
                    int width = size.width - NotableDropRenderer.GAP - 2 * NotableDropRenderer.PAD - NotableDropRenderer.WELL_SIDE - Tokens.S;
                    for (String line : nameLines) assertTrue("'" + line + "' fits the card", title.stringWidth(line) <= width);
                }
            });
        } finally { restore.run(); }
    }

    /**
     * P6b Task 9 (R3 B9): a strip cell's bag count and summary are one " · " sequence wrapped at its " · " boundaries across the two
     * caption lines, so "4 bags · 1 UT · 1 ST · 3 potions" paints whole where "1 UT · 1 ST · 3 potions" alone was cut; the cell keeps
     * its size and its tooltip and accessible name are unchanged.
     */
    @Test public void stripCaptionsWrapAtTheirSeparatorsAcrossTheTwoCaptionLines() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 13));
            DungeonStripRenderer strip = new DungeonStripRenderer();
            FontMetrics title = strip.getFontMetrics(Type.emphasis()), caption = strip.getFontMetrics(Type.caption());
            int height = 2 * DungeonStripRenderer.PAD + Math.max(DungeonStripRenderer.PORTAL, title.getHeight() + 2 * caption.getHeight()) + DungeonStripRenderer.GAP;
            JList<HighlightsModel.DungeonCell> list = new JList<>();
            for (HighlightsModel.DungeonCell cell : List.of(new HighlightsModel.DungeonCell("Lost Halls", 0, 4, 1, 1, 3),
                    new HighlightsModel.DungeonCell(null, 0, 12, 10, 11, 14), new HighlightsModel.DungeonCell("Snake Pit", 0, 2, 0, 0, 0))) {
                strip.getListCellRendererComponent(list, cell, 0, false, false);
                Dimension size = strip.getPreferredSize();
                assertEquals("The cell keeps its height", height, size.height);
                strip.setSize(size);
                BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics();
                strip.paint(g);
                g.dispose();
                List<String> painted = strip.painted();
                DungeonStripRenderer.Lines lines = DungeonStripRenderer.lines(cell);
                assertEquals("The name first", lines.name(), painted.get(0));
                assertEquals("Two caption lines at most: " + painted, 3, painted.size());
                String joined = lines.bags() + " · " + lines.summary();
                assertEquals(joined, DungeonStripRenderer.caption(cell));
                assertEquals("Tooltip unchanged", DungeonStripRenderer.accessibleName(cell) + " · " + HighlightsModel.OBSERVED, strip.getToolTipText());
                assertEquals("Accessible name unchanged", DungeonStripRenderer.accessibleName(cell), strip.getAccessibleContext().getAccessibleName());
                if (cell.dungeon() != null && cell.dungeon().equals("Lost Halls")) {
                    for (String line : painted) assertFalse("Nothing is cut: " + painted, line.endsWith("…"));
                    assertEquals("Split at a separator, nothing lost", joined, painted.get(1) + " · " + painted.get(2));
                }
            }
            assertEquals("A caption wider than a line breaks at its separator",
                List.of("1 bag", "1 UT"), DungeonStripRenderer.captionLines("1 bag · 1 UT", caption, Math.max(caption.stringWidth("1 bag"), caption.stringWidth("1 UT"))));
            assertEquals("A caption that fits one line keeps one", List.of("1 bag · 1 UT"), DungeonStripRenderer.captionLines("1 bag · 1 UT", caption, 1000));
            List<String> cut = DungeonStripRenderer.captionLines("12 bags · 10 UT · 11 ST · 14 potions", caption, caption.stringWidth("12 bags · 10 UT"));
            assertEquals("12 bags · 10 UT", cut.get(0));
            assertTrue("The rest on the second line, cut only when it does not fit: " + cut, cut.get(1).equals("11 ST · 14 potions") || cut.get(1).endsWith("…"));
        });
    }

    /**
     * P6b Task 9: the notable cards (and the strip's cells) outline with {@code Tokens.outline} in the light theme, BORDER under
     * Increase contrast; the dark theme keeps its own subtle edge (dark captures do not move), and selection keeps the accent.
     */
    @Test public void notableCardsAndStripCellsOutlineInTheLightThemeAndKeepTheirDarkEdge() throws Exception {
        try {
            SwingUtilities.invokeAndWait(() -> {
                for (Themes.Variant variant : Themes.Variant.values())
                    for (boolean contrast : new boolean[] {false, true}) {
                        Themes.install(new Themes.Choice(variant, contrast));
                        Color expected = Tokens.color(variant == Themes.Variant.LIGHT && contrast ? Tokens.Role.BORDER : Tokens.Role.BORDER_SUBTLE);
                        String what = variant + (contrast ? " with Increase contrast" : "");
                        NotableDropRenderer card = new NotableDropRenderer(ZONE_NY, () -> NOON);
                        card.getListCellRendererComponent(new JList<>(), notable("Orange", "Lost Halls", NOON, RUN, HighlightsModel.Kind.UT), 0, false, false);
                        assertEquals(what + ": the notable card's edge", expected.getRGB(), edge(card, card.getPreferredSize()));
                        DungeonStripRenderer strip = new DungeonStripRenderer();
                        strip.getListCellRendererComponent(new JList<>(), new HighlightsModel.DungeonCell("Lost Halls", 0, 3, 1, 0, 2), 0, false, false);
                        assertEquals(what + ": the strip cell's edge", expected.getRGB(), edge(strip, strip.getPreferredSize()));
                        card.getListCellRendererComponent(new JList<>(), notable("Orange", "Lost Halls", NOON, RUN, HighlightsModel.Kind.UT), 0, true, false);
                        assertEquals(what + ": a selected card keeps the accent", Tokens.color(Tokens.Role.ACCENT).getRGB(), edge(card, card.getPreferredSize()));
                    }
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
        }
    }

    /** The pixel at the middle of the card's left edge (the card starts half a gap in), painted on an opaque canvas. */
    private static int edge(JComponent cell, Dimension size) {
        cell.setSize(size);
        BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Tokens.color(Tokens.Role.SURFACE));
        g.fillRect(0, 0, size.width, size.height);
        cell.paint(g);
        g.dispose();
        return image.getRGB(NotableDropRenderer.GAP / 2, size.height / 2);
    }

    private static boolean close(Color a, Color b) {
        return Math.abs(a.getRed() - b.getRed()) <= 8 && Math.abs(a.getGreen() - b.getGreen()) <= 8 && Math.abs(a.getBlue() - b.getBlue()) <= 8;
    }
}
