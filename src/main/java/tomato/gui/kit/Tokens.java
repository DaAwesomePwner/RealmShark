package tomato.gui.kit;

import java.awt.Color;
import java.util.Locale;
import javax.swing.UIManager;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.VioletTheme;

/** Semantic colors and spacing for kit components. Resolve colors when painting or in updateUI; they follow the theme. */
public final class Tokens {
    public enum Role {
        CANVAS, NAV, SURFACE, SURFACE_ALT, RAISED, CONTROL, BORDER_SUBTLE, BORDER,
        ACCENT, ACCENT_TEXT, ACCENT_WASH, SELECTION, SELECTION_TEXT, TEXT, TEXT_MUTED,
        GOOD, WARN, BAD, INFO, PRIMARY
    }

    /** Status meaning only; game meaning (bags, tiers) has its own helpers. */
    public enum Tone { NEUTRAL, ACCENT, GOOD, WARN, BAD, INFO }

    public static final int XS = 4, S = 8, M = 12, L = 16, XL = 24;
    /** Graphics arc diameters: controls match FlatLaf's Component.arc, cards match ContentStyle.card. */
    public static final int ARC_CONTROL = 6, ARC_CARD = 10, ARC_CHIP = 8;

    private Tokens() {}

    public static Color color(Role role) {
        switch (role) {
            case CANVAS: return ContentStyle.color("background");
            case NAV: return ContentStyle.color("navigation");
            case SURFACE: return ContentStyle.color("surface");
            case SURFACE_ALT: return ui("Table.alternateRowColor", ContentStyle.color("surface"));
            case RAISED: return ContentStyle.color("surfaceRaised");
            case CONTROL: return ui("Button.background", ContentStyle.color("surfaceRaised"));
            case BORDER_SUBTLE: return ContentStyle.color("border");
            case BORDER: return ContentStyle.color("controlBorder");
            case ACCENT: return ui("Component.accentColor", ContentStyle.color("violet"));
            case ACCENT_TEXT: return ContentStyle.color("violet");
            case ACCENT_WASH: return ContentStyle.color("accentWash");
            case SELECTION: return ContentStyle.color("selection");
            case SELECTION_TEXT: return ContentStyle.color("selectionText");
            case TEXT: return ContentStyle.color("text");
            case TEXT_MUTED: return ContentStyle.color("muted");
            case GOOD: return ContentStyle.color("mint");
            case WARN: return ContentStyle.color("amber");
            case BAD: return ContentStyle.color("rose");
            case INFO: return ContentStyle.color("blue");
            case PRIMARY: return VioletTheme.CAPTURE_BACKGROUND;
            default: throw new IllegalArgumentException("Unknown role " + role);
        }
    }

    public static Color tone(Tone tone) {
        switch (tone) {
            case ACCENT: return color(Role.ACCENT_TEXT);
            case GOOD: return color(Role.GOOD);
            case WARN: return color(Role.WARN);
            case BAD: return color(Role.BAD);
            case INFO: return color(Role.INFO);
            default: return color(Role.TEXT_MUTED);
        }
    }

    public static boolean dark() {
        Color surface = color(Role.SURFACE);
        return surface.getRed() * .2126 + surface.getGreen() * .7152 + surface.getBlue() * .0722 < 128;
    }

    /** An opaque wash of the ink over the surface, for chip and badge backgrounds. */
    public static Color tint(Color ink) { return blend(color(Role.SURFACE), ink, dark() ? .18f : .14f); }

    public static Color blend(Color from, Color to, float weight) {
        return new Color(Math.round(from.getRed() + (to.getRed() - from.getRed()) * weight),
            Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * weight),
            Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * weight));
    }

    /** Bag color by LootBags display name ("White", "B.White", "Egg Basket"); boosted bags share their base color. */
    public static Color bag(String name) {
        String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("b.")) key = key.substring(2).trim();
        if (key.endsWith(" bag")) key = key.substring(0, key.length() - 4);
        switch (key) {
            case "white": return color(Role.TEXT);
            case "orange": case "gold": return color(Role.WARN);
            case "red": case "pink": return color(Role.BAD);
            case "blue": case "teal": return color(Role.INFO);
            case "purple": return color(Role.ACCENT_TEXT);
            case "egg": case "egg basket": return color(Role.GOOD);
            default: return color(Role.TEXT_MUTED);
        }
    }

    /** Sprite border by item label (RosterDefinitions/ParseEquipment): UT, ST, CONSUMABLE, T0–T15. */
    public static Color tier(String label) {
        String key = label == null ? "" : label.trim().toUpperCase(Locale.ROOT);
        switch (key) {
            case "UT": return color(Role.WARN);
            case "ST": return color(Role.BAD);
            case "CONSUMABLE": return color(Role.INFO);
            default: return color(Role.BORDER);
        }
    }

    private static Color ui(String key, Color fallback) {
        Color value = UIManager.getColor(key);
        return value == null ? fallback : new Color(value.getRGB(), true);
    }
}
