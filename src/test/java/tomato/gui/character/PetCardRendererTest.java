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
        assertEquals("In the Pet Yard now · Equipped by Wizard #7", lines.footer());
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
        assertEquals("Equipped by Wizard #7, Knight #8 (dead)", lines.footer());
        assertEquals("Pet, rarity unknown, family unknown, Ability not captured level 45, Unknown ability #403 level unknown, "
            + "Ability not captured level unknown, equipped by Wizard #7, Knight #8 (dead)", PetCardRenderer.accessibleName(card));
        assertEquals("In the Pet Yard now", PetCardRenderer.lines(card(rex(NOW), PetDefinitions.loading(), List.of(), true)).footer());
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

    private static boolean painted(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if ((image.getRGB(x, y) >>> 24) != 0) return true;
        return false;
    }
}
