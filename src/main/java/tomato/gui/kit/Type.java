package tomato.gui.kit;

import java.awt.Font;
import tomato.gui.modern.ContentStyle;

/** Font roles in em of the body font, so every role follows Edit › Font. Apply with ContentStyle.font. */
public final class Type {
    private Type() {}
    public static Font caption() { return ContentStyle.metadata(ContentStyle.body()); }
    public static Font body() { return ContentStyle.body(); }
    public static Font emphasis() { return ContentStyle.emphasis(ContentStyle.body()); }
    public static Font title() { return scaled(1.15f); }
    public static Font pageTitle() { return scaled(1.4f); }
    public static Font metric() { return scaled(1.35f); }

    private static Font scaled(float scale) {
        Font body = ContentStyle.body();
        return ContentStyle.emphasis(body.deriveFont(body.getSize2D() * scale));
    }
}
