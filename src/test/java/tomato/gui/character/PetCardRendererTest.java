package tomato.gui.character;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.backend.data.CharacterJournal;
import tomato.backend.data.PetDefinitions;
import tomato.gui.glance.character.PetSummary;
import tomato.gui.modern.ContentStyle;
import static org.junit.Assert.*;
import static tomato.gui.character.PetFixtures.*;

/** The painted pet card: name, rarity chip, family, three ability bars ("—" never 0 when unknown), the footer and its accessible name. */
public class PetCardRendererTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Font font;

    @Before public void font13() throws Exception {
        SwingUtilities.invokeAndWait(() -> { font = ContentStyle.body(); ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 13)); });
    }
    @After public void restoreFont() throws Exception { SwingUtilities.invokeAndWait(() -> ContentStyle.setBodyFont(font)); }

    private static PetGalleryModel.PetCard card(CharacterJournal.PetRecord pet, PetDefinitions defs, List<String> equippedBy, boolean inYard) {
        return new PetGalleryModel.PetCard(pet.instanceId == null ? "object:5" : "pet:" + pet.instanceId, PetSummary.of(pet, defs), equippedBy, inYard, pet.observedAt);
    }

    @Test public void aKnownPetShowsItsNameRarityFamilyAbilityBarsAndWhereItIs() throws Exception {
        PetGalleryModel.PetCard card = card(rex(NOW), defs(temp.newFolder().toPath()), List.of("Wizard #7"), true);
        PetCardRenderer.Lines lines = PetCardRenderer.lines(card);
        assertEquals("Rex", lines.title());
        assertEquals("Rare", lines.chip());
        assertEquals("Family: Canine", lines.family());
        assertEquals(List.of(new PetCardRenderer.Bar("Heal", "45/70", 45, 70, false), new PetCardRenderer.Bar("Magic heal", "30/70", 30, 70, false),
            new PetCardRenderer.Bar("Electric", "Locked", 1, 70, true)), lines.bars());
        assertEquals("Two footer lines: where it is, then who carries it", List.of("In the Pet Yard now", "Equipped by Wizard #7"), lines.footer());
        assertEquals("Rex, Rare, family Canine, Heal level 45 of 70, Magic heal level 30 of 70, Electric locked, in the Pet Yard now, equipped by Wizard #7",
            PetCardRenderer.accessibleName(card));
    }

    @Test public void unknownValuesShowADashNeverZero() {
        CharacterJournal.PetRecord pet = pet(5L, null, null, null, null, NOW, new int[]{-1, 403, -1}, new int[]{45, -1, -1}, new int[]{-1, -1, -1});
        PetGalleryModel.PetCard card = card(pet, PetDefinitions.loading(), List.of("Wizard #7", "Knight #8 (dead)"), false);
        PetCardRenderer.Lines lines = PetCardRenderer.lines(card);
        assertEquals("Pet", lines.title());
        assertEquals("No chip for an unknown rarity", "", lines.chip());
        assertEquals("Family: —", lines.family());
        assertEquals("Short names fit the card; the accessible name below says them in full",
            List.of(new PetCardRenderer.Bar("Ability —", "45/—", 45, null, false), new PetCardRenderer.Bar("Ability #403", "—", null, null, false),
            new PetCardRenderer.Bar("Ability —", "—", null, null, false)), lines.bars());
        assertEquals("Only the lines that apply", List.of("Equipped by Wizard #7, Knight #8 (dead)"), lines.footer());
        assertEquals("Pet, rarity unknown, family unknown, Ability not captured level 45, Unknown ability #403 level unknown, "
            + "Ability not captured level unknown, equipped by Wizard #7, Knight #8 (dead)", PetCardRenderer.accessibleName(card));
        assertEquals(List.of("In the Pet Yard now"), PetCardRenderer.lines(card(rex(NOW), PetDefinitions.loading(), List.of(), true)).footer());
    }

    @Test public void oneComponentServesEveryCellPaintsTheCardAndGrowsWithTheFont() throws Exception {
        PetDefinitions defs = defs(temp.newFolder().toPath());
        SwingUtilities.invokeAndWait(() -> {
            PetCardRenderer renderer = new PetCardRenderer();
            JList<PetGalleryModel.PetCard> list = new JList<>();
            PetGalleryModel.PetCard known = card(rex(NOW), defs, List.of("Wizard #7"), true);
            CharacterJournal.PetRecord typeless = rex(NOW); typeless.type = null;
            PetGalleryModel.PetCard unknown = card(typeless, defs, List.of(), true);
            Component first = renderer.getListCellRendererComponent(list, known, 0, true, true);
            assertEquals(PetCardRenderer.lines(known), renderer.shown());
            Component second = renderer.getListCellRendererComponent(list, unknown, 1, false, false);
            assertSame("One reusable component, no per-card trees (spec §9)", first, second);
            assertEquals(PetCardRenderer.accessibleName(unknown), renderer.getAccessibleContext().getAccessibleName());
            assertTrue("The tooltip says why the family is unknown: " + renderer.getToolTipText(),
                renderer.getToolTipText().contains("Family: — (pet type not captured)"));
            renderer.setDefinitions(PetDefinitions.loading());
            renderer.getListCellRendererComponent(list, card(rex(NOW), PetDefinitions.loading(), List.of(), true), 0, false, false);
            assertTrue(renderer.getToolTipText(), renderer.getToolTipText().contains("Family: — (Pet names load with the selected game assets)"));
            Dimension small = renderer.getPreferredSize();
            renderer.setSize(small);
            BufferedImage image = new BufferedImage(small.width, small.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            renderer.getListCellRendererComponent(list, known, 0, true, true);
            renderer.paint(g);
            g.dispose();
            assertTrue("The card is painted", painted(image));
            ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 18));
            Dimension large = renderer.getPreferredSize();
            assertTrue("Cards grow with the body font: " + small + " -> " + large, large.width > small.width && large.height > small.height);
        });
    }

    /**
     * Every card has room for both footer lines (all cards one height, as the tile list's fixed cells need), at 13 and at 18. A line
     * too long for the card is ellipsized when painted; the tooltip and the accessible name keep the full text.
     */
    @Test public void everyCardHasRoomForBothFooterLinesAndLongOnesKeepTheirFullTextElsewhere() throws Exception {
        PetDefinitions defs = defs(temp.newFolder().toPath());
        SwingUtilities.invokeAndWait(() -> {
            PetCardRenderer renderer = new PetCardRenderer();
            JList<PetGalleryModel.PetCard> list = new JList<>();
            List<String> carriers = List.of("Wizard #101", "Necromancer #102", "Knight #103 (dead)");
            PetGalleryModel.PetCard both = card(rex(NOW), defs, carriers, true);
            for (int size : new int[]{13, 18}) {
                ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, size));
                Dimension cell = renderer.getListCellRendererComponent(list, card(rex(NOW), defs, List.of(), false), 0, false, false).getPreferredSize();
                renderer.getListCellRendererComponent(list, both, 0, false, false);
                assertEquals("One height for every card, whatever its footer", cell, renderer.getPreferredSize());
                FontMetrics caption = renderer.getFontMetrics(tomato.gui.kit.Type.caption());
                int bottom = cell.height - PetCardRenderer.GAP / 2 - tomato.gui.kit.Tokens.M; // the card's inner bottom edge
                assertTrue(size + ": the second footer line fits the card: " + renderer.footerBaseline(1) + " + " + caption.getDescent() + " > " + bottom,
                    renderer.footerBaseline(1) + caption.getDescent() <= bottom);
                assertTrue("…below the first", renderer.footerBaseline(1) >= renderer.footerBaseline(0) + caption.getHeight());
            }
            String full = "Equipped by Wizard #101, Necromancer #102, Knight #103 (dead)";
            assertEquals(List.of("In the Pet Yard now", full), renderer.shown().footer());
            assertTrue(renderer.getToolTipText(), renderer.getToolTipText().contains(full));
            assertTrue(renderer.getAccessibleContext().getAccessibleName(),
                renderer.getAccessibleContext().getAccessibleName().endsWith("in the Pet Yard now, equipped by Wizard #101, Necromancer #102, Knight #103 (dead)"));
        });
    }

    private static boolean painted(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if ((image.getRGB(x, y) >>> 24) != 0) return true;
        return false;
    }
}
