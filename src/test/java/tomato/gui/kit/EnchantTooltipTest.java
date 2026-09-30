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

/** The shared item tooltip: heading, rarity line in the gem color, then each slot's name and effect, all escaped. */
public class EnchantTooltipTest {
    private HashMap<Short, ParseEnchants.Definition> saved;

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

    private static String hex(java.awt.Color color) { return String.format("#%06x", color.getRGB() & 0xFFFFFF); }
}
