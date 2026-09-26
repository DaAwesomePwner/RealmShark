package tomato.gui.modern;

import com.formdev.flatlaf.FlatDarkLaf;
import java.awt.*;
import java.util.Properties;
import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;

/** The default desktop theme. Violet Light shares these rules through {@link #apply}. */
public final class VioletTheme extends FlatDarkLaf {
    /** Capture action colors. Every state keeps a 4.5:1 contrast with white text. */
    public static final Color CAPTURE_BACKGROUND = new Color(0x7041BD);
    public static final Color CAPTURE_HOVER = new Color(0x8052CD);
    public static final Color CAPTURE_PRESSED = new Color(0x6036A5);

    /**
     * One variant's colors. Surfaces share one hue, so depth reads as elevation instead of as a
     * second color. Two border weights keep structure quiet while control edges stay findable.
     */
    static final class Palette {
        final int base, navigation, surface, surfaceAlternate, surfaceRaised;
        final int control, controlHover, controlPressed;
        final int borderSubtle, border, borderDisabled;
        final int accent, accentBright, accentWash, selection, selectionText;
        final int text, textMuted, textHeader, scrollThumb, scrollThumbHover;
        /** Increase contrast replaces these three roles and widens focus rings. */
        final int contrastBorder, contrastSubtle, contrastMuted;

        Palette(int base, int navigation, int surface, int surfaceAlternate, int surfaceRaised,
                int control, int controlHover, int controlPressed,
                int borderSubtle, int border, int borderDisabled,
                int accent, int accentBright, int accentWash, int selection, int selectionText,
                int text, int textMuted, int textHeader, int scrollThumb, int scrollThumbHover,
                int contrastBorder, int contrastSubtle, int contrastMuted) {
            this.base = base; this.navigation = navigation; this.surface = surface;
            this.surfaceAlternate = surfaceAlternate; this.surfaceRaised = surfaceRaised;
            this.control = control; this.controlHover = controlHover; this.controlPressed = controlPressed;
            this.borderSubtle = borderSubtle; this.border = border; this.borderDisabled = borderDisabled;
            this.accent = accent; this.accentBright = accentBright; this.accentWash = accentWash;
            this.selection = selection; this.selectionText = selectionText;
            this.text = text; this.textMuted = textMuted; this.textHeader = textHeader;
            this.scrollThumb = scrollThumb; this.scrollThumbHover = scrollThumbHover;
            this.contrastBorder = contrastBorder; this.contrastSubtle = contrastSubtle; this.contrastMuted = contrastMuted;
        }

        static final Palette DARK = new Palette(
            0x131120, 0x0E0C18, 0x181627, 0x1C1A2D, 0x201D33,
            0x252139, 0x322C4C, 0x1C1930,
            0x252236, 0x38334F, 0x221F31,
            0xAD8CFF, 0xC4ADFF, 0x2A2142, 0x3B2E5E, 0xF4F0FF,
            0xE9E6F7, 0xA9A4C2, 0xB7B1D0, 0x332E4A, 0x453E63,
            0x5A5378, 0x3D3854, 0xCFCBE0);

        static final Palette LIGHT = new Palette(
            0xF5F4F9, 0xEEECF4, 0xFFFFFF, 0xF8F7FB, 0xFAF9FC,
            0xEFEDF5, 0xE5E1F0, 0xDAD5E8,
            0xE0DDE8, 0xC9C5D6, 0xE6E3EC,
            0x6241AA, 0x4E3291, 0xF0E9FD, 0xE5DCF8, 0x302048,
            0x24222E, 0x626071, 0x4A4757, 0xD3CFE0, 0xBDB7D0,
            0x8C86A3, 0xB9B4C9, 0x4A4757);
    }

    @Override public String getName() { return "RealmShark Violet"; }

    /**
     * Declares the accent before the defaults are resolved. Sliders, progress bars, check boxes
     * and radio buttons derive their color from this variable while it loads, so overriding
     * Component.accentColor afterwards would leave those controls on the stock blue. A ticked
     * box fills with the accent here rather than showing a checkmark on a grey square.
     */
    @Override protected Properties getAdditionalDefaults() {
        return accentDefaults(super.getAdditionalDefaults(), Palette.DARK);
    }

    static Properties accentDefaults(Properties inherited, Palette palette) {
        Properties properties = inherited == null ? new Properties() : inherited;
        String accent = String.format("#%06X", palette.accent);
        properties.put("@accentColor", accent);
        // The stock themes hardcode a grey checkmark rather than deriving it from the accent,
        // and the filled icon takes its fill from that same value.
        properties.put("CheckBox.icon.checkmarkColor", accent);
        properties.put("CheckBox.icon.style", "filled");
        properties.put("RadioButton.icon.style", "filled");
        return properties;
    }

    @Override public UIDefaults getDefaults() {
        UIDefaults d = super.getDefaults();
        apply(d, Palette.DARK);
        return d;
    }

    /** Applies a variant's colors, typography and metrics, then the contrast overrides when enabled. */
    static void apply(UIDefaults d, Palette p) {
        surfaces(d, p);
        borders(d, p);
        interaction(d, p);
        typography(d);
        metrics(d);
        if (Themes.increaseContrast()) contrast(d, p);
    }

    /** Backgrounds and foregrounds, from the deepest chrome up to raised controls. */
    private static void surfaces(UIDefaults d, Palette p) {
        color(d, "Panel.background", p.base);
        color(d, "TabbedPane.background", p.base);
        color(d, "MenuBar.background", p.navigation);
        color(d, "TitlePane.background", p.navigation);
        color(d, "TitlePane.inactiveBackground", p.navigation);
        color(d, "Table.background", p.surface);
        color(d, "Table.alternateRowColor", p.surfaceAlternate);
        color(d, "List.background", p.surface);
        color(d, "Tree.background", p.surface);
        color(d, "ScrollPane.background", p.surface);
        color(d, "Viewport.background", p.surface);
        color(d, "TextArea.background", p.surface);
        color(d, "TextArea.inactiveBackground", p.surface);
        color(d, "TextArea.disabledBackground", p.surface);
        color(d, "TextField.background", p.surfaceRaised);
        color(d, "FormattedTextField.background", p.surfaceRaised);
        color(d, "PasswordField.background", p.surfaceRaised);
        color(d, "ComboBox.background", p.surfaceRaised);
        color(d, "ComboBox.buttonBackground", p.surfaceRaised);
        color(d, "Spinner.background", p.surfaceRaised);
        color(d, "TableHeader.background", p.surfaceRaised);
        color(d, "PopupMenu.background", p.surfaceRaised);
        color(d, "Button.background", p.control);
        color(d, "ToggleButton.background", p.control);

        color(d, "Label.foreground", p.text);
        color(d, "TextArea.foreground", p.text);
        color(d, "List.foreground", p.text);
        color(d, "Table.foreground", p.text);
        color(d, "TextArea.inactiveForeground", p.textMuted);
        color(d, "Label.disabledForeground", p.textMuted);
        color(d, "TableHeader.foreground", p.textHeader);
    }

    /** Quiet structure, visible controls. */
    private static void borders(UIDefaults d, Palette p) {
        color(d, "Component.borderColor", p.border);
        color(d, "Component.disabledBorderColor", p.borderDisabled);
        color(d, "Component.focusedBorderColor", p.accent);
        color(d, "Separator.foreground", p.borderSubtle);
        color(d, "TableHeader.separatorColor", p.borderSubtle);
        color(d, "TableHeader.bottomSeparatorColor", p.borderSubtle);
        color(d, "PopupMenu.borderColor", p.border);
        color(d, "SplitPaneDivider.gripColor", p.border);
        color(d, "SplitPane.background", p.base);
        color(d, "ToolTip.background", p.surfaceRaised);
        color(d, "ToolTip.foreground", p.text);
    }

    /** Hover, pressed, selected and focused states, so every control answers the pointer. */
    private static void interaction(UIDefaults d, Palette p) {
        color(d, "Component.focusColor", p.accent);
        color(d, "Component.accentColor", p.accent);

        color(d, "Button.hoverBackground", p.controlHover);
        color(d, "Button.pressedBackground", p.controlPressed);
        color(d, "Button.focusedBackground", p.controlHover);
        color(d, "Button.default.background", CAPTURE_BACKGROUND.getRGB());
        color(d, "Button.default.foreground", 0xFFFFFF);
        color(d, "Button.default.hoverBackground", CAPTURE_HOVER.getRGB());
        color(d, "Button.default.focusedBackground", CAPTURE_HOVER.getRGB());
        color(d, "Button.default.pressedBackground", CAPTURE_PRESSED.getRGB());
        color(d, "Button.default.hoverForeground", 0xFFFFFF);
        color(d, "Button.default.pressedForeground", 0xFFFFFF);

        color(d, "ToggleButton.hoverBackground", p.controlHover);
        color(d, "ToggleButton.pressedBackground", p.controlPressed);
        color(d, "ToggleButton.selectedBackground", p.accentWash);
        color(d, "ToggleButton.selectedForeground", p.accentBright);

        color(d, "Table.selectionBackground", p.selection);
        color(d, "Table.selectionForeground", p.selectionText);
        // Keep the selected record identifiable while its detail/editor controls own focus.
        color(d, "Table.selectionInactiveBackground", p.selection);
        color(d, "Table.selectionInactiveForeground", p.selectionText);
        color(d, "List.selectionBackground", p.selection);
        color(d, "List.selectionForeground", p.selectionText);
        color(d, "List.selectionInactiveBackground", p.accentWash);
        color(d, "List.selectionInactiveForeground", p.text);
        color(d, "Tree.selectionBackground", p.selection);
        color(d, "Tree.selectionForeground", p.selectionText);
        color(d, "TextComponent.selectionBackground", p.selection);

        color(d, "MenuItem.selectionBackground", p.selection);
        color(d, "MenuItem.selectionForeground", p.selectionText);
        color(d, "MenuItem.acceleratorSelectionForeground", p.selectionText);
        color(d, "MenuBar.hoverBackground", p.accentWash);
        color(d, "MenuItem.underlineSelectionBackground", p.accentWash);

        color(d, "TabbedPane.underlineColor", p.accent);
        color(d, "TabbedPane.selectedBackground", p.accentWash);
        color(d, "TabbedPane.hoverColor", p.surfaceRaised);
        color(d, "TabbedPane.focusColor", p.accentWash);
        color(d, "TabbedPane.contentAreaColor", p.borderSubtle);

        // A track-free scroll bar: the thumb is the only mark, and it brightens under the pointer.
        color(d, "ScrollBar.track", p.surface);
        color(d, "ScrollBar.hoverTrackColor", p.surface);
        color(d, "ScrollBar.thumb", p.scrollThumb);
        color(d, "ScrollBar.hoverThumbColor", p.scrollThumbHover);
        color(d, "ScrollBar.pressedThumbColor", p.accent);
        d.put("ScrollBar.showButtons", false);
    }

    private static void typography(UIDefaults d) {
        d.put("defaultFont", new FontUIResource(ContentStyle.body()));
        d.put("Table.font", new FontUIResource(ContentStyle.body()));
        d.put("TextArea.font", new FontUIResource(ContentStyle.body()));
        d.put("TableHeader.font", new FontUIResource(ContentStyle.emphasis(ContentStyle.metadata(ContentStyle.body()))));
    }

    /**
     * Compact metrics. Corner arcs move from 4 to 6 pixels, which reads as rounded at both
     * 100% and 200% scaling without spending the vertical space the data views need.
     */
    private static void metrics(UIDefaults d) {
        d.put("Button.arc", 6);
        d.put("ToggleButton.arc", 6);
        d.put("Component.arc", 6);
        d.put("TextComponent.arc", 6);
        d.put("ProgressBar.arc", 6);
        d.put("Component.focusWidth", 1);
        d.put("Component.innerFocusWidth", 1);
        d.put("Component.arrowType", "chevron");
        d.put("Button.margin", new Insets(3, 8, 3, 8));
        d.put("ToggleButton.margin", new Insets(3, 8, 3, 8));
        d.put("Button.minimumWidth", 64);
        d.put("Button.minimumHeight", 28);
        d.put("Button.default.boldText", false);
        d.put("Component.minimumHeight", 26);
        d.put("TextField.margin", new Insets(3, 6, 3, 6));
        d.put("ComboBox.padding", new Insets(3, 6, 3, 6));
        d.put("Spinner.padding", new Insets(3, 6, 3, 6));
        d.put("TabbedPane.tabHeight", 28);
        d.put("TabbedPane.tabInsets", new Insets(3, 10, 3, 10));
        d.put("TabbedPane.showTabSeparators", false);
        d.put("Table.rowHeight", 28);
        d.put("Table.cellMargins", new Insets(3, 8, 3, 8));
        d.put("TableHeader.height", 24);
        d.put("List.cellMargins", new Insets(3, 8, 3, 8));
        d.put("ScrollBar.width", 11);
        d.put("ScrollBar.thumbArc", 999);
        d.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
        d.put("ScrollBar.trackArc", 999);
        d.put("SplitPane.dividerSize", 5);
        d.put("PopupMenu.borderInsets", new Insets(4, 1, 4, 1));
    }

    /** Stronger outlines, dividers and secondary text for the users of the retired high-contrast themes. */
    private static void contrast(UIDefaults d, Palette p) {
        color(d, "Component.borderColor", p.contrastBorder);
        color(d, "PopupMenu.borderColor", p.contrastBorder);
        color(d, "Separator.foreground", p.contrastSubtle);
        color(d, "TableHeader.separatorColor", p.contrastSubtle);
        color(d, "TableHeader.bottomSeparatorColor", p.contrastSubtle);
        color(d, "TabbedPane.contentAreaColor", p.contrastSubtle);
        color(d, "Label.disabledForeground", p.contrastMuted);
        color(d, "TextArea.inactiveForeground", p.contrastMuted);
        color(d, "TableHeader.foreground", p.text);
        d.put("Component.focusWidth", 2);
    }

    private static void color(UIDefaults d, String key, int rgb) {
        d.put(key, new ColorUIResource(rgb));
    }

    /** Installs Violet Dark without contrast; kept for callers that predate {@link Themes}. */
    public static boolean install() {
        return Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
    }
}
