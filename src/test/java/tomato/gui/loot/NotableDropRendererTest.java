package tomato.gui.loot;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.ZoneId;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import org.junit.Rule;
import org.junit.Test;
import tomato.gui.kit.Sprites;
import tomato.gui.kit.Tokens;
import tomato.gui.kit.Type;
import tomato.gui.modern.ContentStyle;
import tomato.history.link.VisitRef;
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

    @Test public void linesSayTheKindTheAreaAndWhenAndAMissingRunLink() {
        NotableDropRenderer.Lines lines = NotableDropRenderer.lines(notable("Orange", "Lost Halls", at(0, 9, 5), RUN, HighlightsModel.Kind.UT), ZONE_NY, NOON);
        assertEquals(Sprites.name(4242), lines.name());
        assertEquals("UT", lines.chip()); assertEquals(Tokens.Tone.WARN, lines.tone());
        assertEquals("09:05", lines.when()); assertEquals("Lost Halls", lines.where());
        assertEquals("A linked drop needs no note", "", lines.link());
        NotableDropRenderer.Lines unlinked = NotableDropRenderer.lines(notable(null, null, at(-1, 22, 10), null, HighlightsModel.Kind.ENCHANTED), ZONE_NY, NOON);
        assertEquals("Yesterday 22:10", unlinked.when()); assertEquals("Unknown area", unlinked.where());
        assertEquals("Not linked to a run", unlinked.link());
        assertEquals("Enchanted", unlinked.chip()); assertEquals(Tokens.Tone.ACCENT, unlinked.tone());
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

    @Test public void stripCellsSayTheBagsAndNotableCounts() {
        DungeonStripRenderer.Lines halls = DungeonStripRenderer.lines(new HighlightsModel.DungeonCell("Lost Halls", 0, 3, 1, 0, 2));
        assertEquals("Lost Halls", halls.name()); assertEquals("3 bags", halls.bags()); assertEquals("1 UT · 2 potions", halls.summary());
        DungeonStripRenderer.Lines unknown = DungeonStripRenderer.lines(new HighlightsModel.DungeonCell(null, 0, 1, 0, 1, 1));
        assertEquals("Unknown area", unknown.name()); assertEquals("1 bag", unknown.bags()); assertEquals("1 ST · 1 potion", unknown.summary());
        assertEquals("No UT, ST or potions", DungeonStripRenderer.lines(new HighlightsModel.DungeonCell("Snake Pit", 0, 2, 0, 0, 0)).summary());
        assertEquals("Lost Halls; 3 bags; 1 UT · 2 potions", DungeonStripRenderer.accessibleName(new HighlightsModel.DungeonCell("Lost Halls", 0, 3, 1, 0, 2)));
    }

    private static boolean close(Color a, Color b) {
        return Math.abs(a.getRed() - b.getRed()) <= 8 && Math.abs(a.getGreen() - b.getGreen()) <= 8 && Math.abs(a.getBlue() - b.getBlue()) <= 8;
    }
}
