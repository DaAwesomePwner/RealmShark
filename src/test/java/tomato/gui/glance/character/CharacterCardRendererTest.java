package tomato.gui.glance.character;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Locale;
import javax.swing.*;
import org.junit.*;
import tomato.gui.modern.ContentStyle;
import static org.junit.Assert.*;

/** The painted card: its text, unknown never shown as zero, the Playing now marker and the Seasonal chip; one component for every cell. */
public class CharacterCardRendererTest {
    private static final long NOW = System.currentTimeMillis(), HOUR = 3_600_000L; // KitFormat.relative reads the real clock
    private Locale format;
    private Font font;

    @Before public void usFormatAndFont13() throws Exception {
        format = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
        SwingUtilities.invokeAndWait(() -> { font = ContentStyle.body(); ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 13)); });
    }
    @After public void restore() throws Exception {
        Locale.setDefault(Locale.Category.FORMAT, format);
        SwingUtilities.invokeAndWait(() -> ContentStyle.setBodyFont(font));
    }

    @Test public void aLiveCardShowsClassLevelFameMaxedSeasonAndPlayingNow() {
        CharacterCardModel card = CharacterFixtures.card(101, "Wizard", 20, 1_234L, 7, true, true, false, NOW - 2 * HOUR);
        CharacterCardRenderer.Lines simple = CharacterCardRenderer.lines(card, false);
        assertEquals("Wizard", simple.title());
        assertEquals("Level 20 · Fame 1,234", simple.meta());
        assertEquals("Simple hides the character ID (spec §3.2)", "Sample", simple.identity());
        assertEquals("7/8", simple.maxed());
        assertEquals(7, simple.pips());
        assertEquals("The last time in game, as the Last played sort", "Played 2 h ago", simple.seen());
        assertEquals("Playing now", simple.status());
        assertEquals("Seasonal", simple.chip());
        assertEquals("Analyst shows it", "Sample · #101", CharacterCardRenderer.lines(card, true).identity());
        assertEquals("Sample, Wizard level 20, 7 of 8 maxed, playing now", card.accessibleName());
    }

    @Test public void unknownValuesShowADashNeverZero() {
        CharacterCardModel card = CharacterFixtures.card(104, "Knight", null, null, null, null, false, false, 0);
        CharacterCardRenderer.Lines lines = CharacterCardRenderer.lines(card, false);
        assertEquals("Level — · Fame —", lines.meta());
        assertEquals("An unknown maxed count is \"—\", never 0/8", "—", lines.maxed());
        assertEquals("No pip counts as filled", -1, lines.pips());
        assertEquals("Last played unknown", lines.seen());
        assertEquals("No chip for an unknown season", "", lines.chip());
        assertEquals("", lines.status());
        assertEquals("Sample, Knight, maxed stats unknown", card.accessibleName());
    }

    @Test public void aCharacterNeverPlayedShowsWhenItWasLastSeen() {
        CharacterCardModel listed = new CharacterCardModel(CharacterFixtures.ACCOUNT + ":109", "Sample", CharacterFixtures.WIZARD, "Wizard", null,
            20, 10L, 8, false, 0, NOW - 10 * 60_000L, false, false);
        assertEquals("Only a character list saw it: Seen, not Played", "Seen 10 min ago", CharacterCardRenderer.lines(listed, false).seen());
    }

    @Test public void aDeadCardSaysSoAndARegularCardHasNoSeasonChip() {
        CharacterCardModel card = CharacterFixtures.card(107, "Necromancer", 20, 640L, 4, false, false, true, NOW - HOUR);
        CharacterCardRenderer.Lines lines = CharacterCardRenderer.lines(card, false);
        assertEquals("Dead", lines.status());
        assertEquals("", lines.chip());
        assertEquals(4, lines.pips());
        assertEquals("Sample, Necromancer level 20, 4 of 8 maxed, marked dead", card.accessibleName());
    }

    @Test public void oneComponentServesEveryCellPaintsTheCardAndGrowsWithTheFont() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CharacterCardRenderer renderer = new CharacterCardRenderer();
            JList<CharacterCardModel> list = new JList<>();
            CharacterCardModel live = CharacterFixtures.card(101, "Wizard", 20, 1_234L, 7, true, true, false, NOW - HOUR);
            CharacterCardModel unknown = CharacterFixtures.card(104, "Knight", 14, null, null, null, false, false, NOW - HOUR);
            Component first = renderer.getListCellRendererComponent(list, live, 0, true, true);
            Component second = renderer.getListCellRendererComponent(list, unknown, 1, false, false);
            assertSame("One reusable component, no per-card trees (spec §9)", first, second);
            assertEquals(unknown.accessibleName(), renderer.getAccessibleContext().getAccessibleName());
            assertTrue(renderer.getToolTipText(), renderer.getToolTipText().contains("Maxed stats unknown"));
            Dimension small = renderer.cellSize();
            renderer.setSize(small);
            BufferedImage image = new BufferedImage(small.width, small.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            renderer.paint(g);
            g.dispose();
            assertTrue("The card is painted", painted(image));
            ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 18));
            Dimension large = renderer.cellSize();
            assertTrue("Cards grow with the body font: " + small + " -> " + large, large.width > small.width && large.height > small.height);
        });
    }

    private static boolean painted(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if ((image.getRGB(x, y) >>> 24) != 0) return true;
        return false;
    }
}
