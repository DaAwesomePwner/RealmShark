package tomato.gui.kit;

import java.awt.Color;
import java.awt.Insets;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Banner: tones, wrapping text and accessible name. KitText: role colors that follow the theme. */
public class BannerTest {
    @Test public void neutralIsPlainMutedTextAndOtherTonesAreTintedRows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Banner banner = new Banner("sample-banner");
            JTextArea text = (JTextArea) banner.getComponent(0);
            assertEquals("sample-banner-text", text.getName()); assertTrue("Text wraps instead of clipping", text.getLineWrap());
            assertEquals(Tokens.Tone.NEUTRAL, banner.tone()); assertFalse(banner.warns()); assertNull("Plain text has no inset", banner.getBorder());
            assertEquals(Tokens.color(Tokens.Role.TEXT_MUTED), text.getForeground());
            banner.setText("Loading…");
            assertEquals("Loading…", banner.text()); assertEquals("Loading…", banner.getAccessibleContext().getAccessibleName());
            banner.setTone(Tokens.Tone.WARN);
            Insets inset = banner.getInsets();
            assertTrue(banner.warns()); assertTrue("The stripe gets room at the left", inset.left > inset.right);
            assertEquals(Tokens.color(Tokens.Role.TEXT), text.getForeground());
            banner.setTone(Tokens.Tone.INFO);
            assertFalse("Only WARN warns", banner.warns()); assertNotNull(banner.getBorder());
            banner.setTone(null); banner.setText(null);
            assertEquals(Tokens.Tone.NEUTRAL, banner.tone()); assertNull(banner.getBorder()); assertEquals("", banner.text());
        });
    }

    @Test public void colorsFollowTheThemeAfterUpdateUi() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Banner banner = new Banner("theme-banner"); banner.setTone(Tokens.Tone.WARN);
            JTextArea text = (JTextArea) banner.getComponent(0);
            KitText muted = KitText.caption("Muted"), accent = new KitText("Accent", Type.body(), Tokens.Role.ACCENT_TEXT);
            for (JComponent part : new JComponent[] {text, muted, accent}) part.setForeground(Color.MAGENTA);
            banner.updateUI(); muted.updateUI(); accent.updateUI();
            assertEquals(Tokens.color(Tokens.Role.TEXT), text.getForeground());
            assertEquals(Tokens.color(Tokens.Role.TEXT_MUTED), muted.getForeground());
            assertEquals(Tokens.color(Tokens.Role.ACCENT_TEXT), accent.getForeground());
            accent.role(Tokens.Role.TEXT);
            assertEquals(Tokens.Role.TEXT, accent.role()); assertEquals(Tokens.color(Tokens.Role.TEXT), accent.getForeground());
            assertEquals(Boolean.TRUE, muted.getClientProperty("html.disable"));
            assertEquals(Tokens.Role.TEXT, KitText.body("Body").role()); assertEquals(Tokens.Role.TEXT, KitText.emphasis("Title").role());
        });
    }
}
