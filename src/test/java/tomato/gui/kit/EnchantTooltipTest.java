package tomato.gui.kit;

import java.util.HashMap;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Test;
import tomato.gui.modern.Themes;
import tomato.realmshark.EnchantInfo;
import tomato.realmshark.ParseEnchants;
import static org.junit.Assert.*;

/** The shared item tooltip: heading, rarity line in the pip color, then each slot's name and effect, all escaped. */
public class EnchantTooltipTest {
    private HashMap<Short, ParseEnchants.Definition> saved;

    @Test public void itemNameCellsAppendOnlyKnownEnchantedRarity() {
        assertEquals("Sword <of> & Things · Legendary", EnchantTooltip.itemName("Sword <of> & Things", EnchantInfo.ofSlotCount(3)));
        for (EnchantInfo info : new EnchantInfo[] {null, EnchantInfo.notRecorded(), EnchantInfo.unreadable(), EnchantInfo.ofSlotCount(0)})
            assertEquals("Sword", EnchantTooltip.itemName("Sword", info));
    }

    @After public void restore() throws Exception {
        if (saved != null) ParseEnchants.ENCHANT_DEFINITIONS = saved;
        SwingUtilities.invokeAndWait(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
    }

    @Test public void listsTheRarityThenEachSlotsNameAndEffect() throws Exception {
        saved = ParseEnchants.ENCHANT_DEFINITIONS;
        HashMap<Short, ParseEnchants.Definition> definitions = new HashMap<>();
        definitions.put((short) 42, new ParseEnchants.Definition("Attack Bonus I", "Increases Attack by 1.4"));
        ParseEnchants.ENCHANT_DEFINITIONS = definitions;
        EnchantInfo info = new EnchantInfo(EnchantInfo.State.RECORDED, EnchantInfo.Rarity.LEGENDARY,
            List.of(new EnchantInfo.Slot(42), new EnchantInfo.Slot(7), new EnchantInfo.Slot(-1)));
        SwingUtilities.invokeAndWait(() -> {
            String html = EnchantTooltip.html("Doom Bow · UT", info);
            assertTrue(html, html.startsWith("<html><b>Doom Bow · UT</b><br>"));
            assertTrue(html, html.contains(hex(Tokens.rarity(EnchantInfo.Rarity.LEGENDARY)) + "'>Legendary · 3 enchant slots</span>"));
            assertTrue(html, html.contains("<br>Attack Bonus I<br>"));
            assertTrue(html, html.contains("Increases Attack by 1.4"));
            assertTrue(html, html.contains("<br>Unknown enchant (0x7)"));
            assertTrue(html, html.contains("(empty slot)"));
            assertTrue(html, html.endsWith("</html>"));
        });
    }

    @Test public void escapesMarkupAndToleratesAMissingHeading() {
        EnchantInfo unreadable = EnchantInfo.unreadable();
        assertTrue(EnchantTooltip.html("Sword <of> & Things", unreadable).contains("Sword &lt;of&gt; &amp; Things"));
        String none = EnchantTooltip.html(null, EnchantInfo.notRecorded());
        assertTrue(none, none.startsWith("<html><b></b><br>Enchants not recorded"));
    }

    @Test public void theRarityColorFollowsTheTheme() throws Exception {
        EnchantInfo rare = new EnchantInfo(EnchantInfo.State.RECORDED, EnchantInfo.Rarity.RARE, List.of(new EnchantInfo.Slot(-1), new EnchantInfo.Slot(-1)));
        SwingUtilities.invokeAndWait(() -> {
            for (Themes.Variant variant : Themes.Variant.values()) {
                Themes.install(new Themes.Choice(variant, false));
                assertTrue(variant.toString(), EnchantTooltip.html("Item", rare).contains(hex(Tokens.rarity(EnchantInfo.Rarity.RARE))));
            }
        });
    }

    @Test public void aRarityOnlyTooltipSaysTheNamesAreNotAvailable() {
        String html = EnchantTooltip.html("Old Bow", EnchantInfo.ofSlotCount(2));
        assertTrue(html, html.contains("Rare · 2 enchant slots"));
        assertTrue(html, html.contains("Enchant names not available"));
        assertFalse("Unenchanted needs no names line", EnchantTooltip.html("Old Bow", EnchantInfo.ofSlotCount(0)).contains("not available"));
    }

    @Test public void theHeadingIsTheNameThenTheTier() {
        assertEquals("Doom Bow · UT", EnchantTooltip.heading("Doom Bow", "UT"));
        assertEquals("Doom Bow", EnchantTooltip.heading("Doom Bow", ""));
        assertEquals("Doom Bow", EnchantTooltip.heading("Doom Bow", null));
        assertEquals("", EnchantTooltip.heading(null, null));
    }

    @Test public void escapesEnchantNamesAndEffects() {
        saved = ParseEnchants.ENCHANT_DEFINITIONS;
        HashMap<Short, ParseEnchants.Definition> definitions = new HashMap<>();
        definitions.put((short) 9, new ParseEnchants.Definition("<b>Bold</b> & Co", "Adds <i>1</i>"));
        ParseEnchants.ENCHANT_DEFINITIONS = definitions;
        String html = EnchantTooltip.html("Bow", new EnchantInfo(EnchantInfo.State.RECORDED, EnchantInfo.Rarity.UNCOMMON, List.of(new EnchantInfo.Slot(9))));
        assertTrue(html, html.contains("&lt;b&gt;Bold&lt;/b&gt; &amp; Co"));
        assertTrue(html, html.contains("Adds &lt;i&gt;1&lt;/i&gt;"));
        assertFalse(html, html.contains("<i>"));
    }

    private static String hex(java.awt.Color color) { return String.format("#%06x", color.getRGB() & 0xFFFFFF); }
}
