package tomato.gui.kit;

import java.awt.Color;
import javax.swing.SwingUtilities;
import org.junit.*;
import tomato.gui.modern.Themes;
import static org.junit.Assert.*;

public class TokensTest {
    @After public void restore() throws Exception {
        SwingUtilities.invokeAndWait(() -> Themes.install(new Themes.Choice(Themes.Variant.DARK, false)));
    }

    @Test public void rolesFollowTheInstalledVariant() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
            assertTrue(Tokens.dark());
            assertEquals(new Color(0x181627), Tokens.color(Tokens.Role.SURFACE));
            assertEquals(new Color(0xAD8CFF), Tokens.color(Tokens.Role.ACCENT));
            assertEquals(new Color(0xC4ADFF), Tokens.color(Tokens.Role.ACCENT_TEXT));
            assertEquals(new Color(0x7041BD), Tokens.color(Tokens.Role.PRIMARY));
            Themes.install(new Themes.Choice(Themes.Variant.LIGHT, false));
            assertFalse(Tokens.dark());
            assertEquals(new Color(0x6241AA), Tokens.color(Tokens.Role.ACCENT_TEXT));
            assertEquals(new Color(0xFFFFFF), Tokens.color(Tokens.Role.SURFACE));
        });
    }

    @Test public void bagAndTierColorsReuseSemanticRoles() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
            assertEquals(Tokens.color(Tokens.Role.TEXT), Tokens.bag("White"));
            assertEquals(Tokens.color(Tokens.Role.TEXT), Tokens.bag("B.White"));
            assertEquals(Tokens.color(Tokens.Role.WARN), Tokens.bag("Orange"));
            assertEquals(Tokens.color(Tokens.Role.GOOD), Tokens.bag("Egg Basket"));
            assertEquals(Tokens.color(Tokens.Role.TEXT_MUTED), Tokens.bag("Soulbound"));
            assertEquals(Tokens.color(Tokens.Role.TEXT_MUTED), Tokens.bag(null));
            assertEquals(Tokens.color(Tokens.Role.WARN), Tokens.tier("UT"));
            assertEquals(Tokens.color(Tokens.Role.BAD), Tokens.tier("st"));
            assertEquals(Tokens.color(Tokens.Role.INFO), Tokens.tier("CONSUMABLE"));
            assertEquals(Tokens.color(Tokens.Role.BORDER), Tokens.tier("T13"));
        });
    }

    @Test public void tintSitsBetweenTheSurfaceAndTheInk() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
            Color surface = Tokens.color(Tokens.Role.SURFACE), tint = Tokens.tint(Color.WHITE);
            assertTrue(tint.getRed() > surface.getRed() && tint.getRed() < 255);
            assertEquals(new Color(50, 100, 150), Tokens.blend(new Color(0, 100, 200), new Color(100, 100, 100), .5f));
        });
    }
}
