package tomato.gui.modern;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.ui.FlatRoundBorder;
import com.formdev.flatlaf.ui.FlatTextBorder;
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
     * A FlatLaf style class for a button that works as an on/off toggle (the filter bar's Filters button): while it is
     * selected it is drawn pressed, on the selection wash with the accent edge and a selected segment's ink.
     */
    public static final String PRESSED_TOGGLE = "pressedToggle";

    /**
     * One variant's colors. Surfaces share one hue, so depth reads as elevation instead of as a
     * second color. Two border weights keep structure quiet while control edges stay findable.
     */
    static final class Palette {
        final int base, navigation, surface, surfaceAlternate, surfaceRaised;
        final int control, controlHover, controlPressed;
        final int borderSubtle, border, borderDisabled, fieldBorder;
        final int accent, accentBright, accentWash, selection, selectionText;
        final int text, textMuted, textHeader, scrollThumb, scrollThumbHover;
        /** Increase contrast replaces these three roles and widens focus rings. */
        final int contrastBorder, contrastSubtle, contrastMuted;

        Palette(int base, int navigation, int surface, int surfaceAlternate, int surfaceRaised,
                int control, int controlHover, int controlPressed,
                int borderSubtle, int border, int borderDisabled, int fieldBorder,
                int accent, int accentBright, int accentWash, int selection, int selectionText,
                int text, int textMuted, int textHeader, int scrollThumb, int scrollThumbHover,
                int contrastBorder, int contrastSubtle, int contrastMuted) {
            this.base = base; this.navigation = navigation; this.surface = surface;
            this.surfaceAlternate = surfaceAlternate; this.surfaceRaised = surfaceRaised;
            this.control = control; this.controlHover = controlHover; this.controlPressed = controlPressed;
            this.borderSubtle = borderSubtle; this.border = border; this.borderDisabled = borderDisabled;
            this.fieldBorder = fieldBorder;
            this.accent = accent; this.accentBright = accentBright; this.accentWash = accentWash;
            this.selection = selection; this.selectionText = selectionText;
            this.text = text; this.textMuted = textMuted; this.textHeader = textHeader;
            this.scrollThumb = scrollThumb; this.scrollThumbHover = scrollThumbHover;
            this.contrastBorder = contrastBorder; this.contrastSubtle = contrastSubtle; this.contrastMuted = contrastMuted;
        }

        static final Palette DARK = new Palette(
            0x131120, 0x0E0C18, 0x181627, 0x1C1A2D, 0x201D33,
            0x252139, 0x322C4C, 0x1C1930,
            0x252236, 0x38334F, 0x221F31, 0x4A4366,
            0xAD8CFF, 0xC4ADFF, 0x2A2142, 0x3B2E5E, 0xF4F0FF,
            0xE9E6F7, 0xA9A4C2, 0xB7B1D0, 0x332E4A, 0x453E63,
            0x5A5378, 0x3D3854, 0xCFCBE0);

        static final Palette LIGHT = new Palette(
            0xF5F4F9, 0xEEECF4, 0xFFFFFF, 0xF8F7FB, 0xFAF9FC,
            0xEFEDF5, 0xE5E1F0, 0xDAD5E8,
            0xE0DDE8, 0xC9C5D6, 0xE6E3EC, 0xB3ADC6,
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
        color(d, "Violet.fieldBorder", p.fieldBorder);
        for (String type : new String[]{"TextField", "FormattedTextField", "PasswordField"})
            d.put(type + ".border", (UIDefaults.LazyValue) defaults -> new FieldTextBorder());
        for (String type : new String[]{"ComboBox", "Spinner"})
            d.put(type + ".border", (UIDefaults.LazyValue) defaults -> new FieldRoundBorder());
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
        for (String type : new String[]{"TextField", "FormattedTextField", "PasswordField", "ComboBox", "Spinner"}) {
            color(d, type + ".foreground", p.text);
            color(d, type + ".focusedBackground", blend(p.surfaceRaised, p.accent, .09f));
        }
        color(d, "ComboBox.buttonFocusedBackground", blend(p.surfaceRaised, p.accent, .09f));
        for (String type : new String[]{"ComboBox", "Spinner"}) {
            color(d, type + ".buttonArrowColor", p.accent);
            color(d, type + ".buttonHoverArrowColor", p.accentBright);
        }
        color(d, "ComboBox.selectionBackground", p.selection);
        color(d, "ComboBox.selectionForeground", p.selectionText);
        color(d, "SearchField.searchIconColor", p.accent);
        color(d, "SearchField.searchIconHoverColor", p.accentBright);
        color(d, "SearchField.searchIconPressedColor", p.accentBright);

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
        // A style class, not Button.selectedBorderColor: that key is shared with every JToggleButton (the Simple/Analyst segments).
        d.put("[style]Button." + PRESSED_TOGGLE, String.format("selectedBackground: %s; selectedForeground: %s; selectedBorderColor: %s",
            hex(p.selection), hex(p.accentBright), hex(p.accent)));

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

        // FlatLaf gives hover fill priority over focus fill; the brighter bar still marks focus.
        color(d, "TabbedPane.underlineColor", p.accentBright);
        color(d, "TabbedPane.inactiveUnderlineColor", p.accent);
        color(d, "TabbedPane.foreground", p.text);
        color(d, "TabbedPane.selectedBackground", p.selection);
        color(d, "TabbedPane.selectedForeground", p.selectionText);
        color(d, "TabbedPane.hoverColor", p.controlHover);
        color(d, "TabbedPane.hoverForeground", p.text);
        color(d, "TabbedPane.focusColor", blend(p.selection, p.accent, .2f));
        color(d, "TabbedPane.focusForeground", p.selectionText);
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
        d.put("TabbedPane.font", new FontUIResource(ContentStyle.tabFont()));
        d.put("TableHeader.font", new FontUIResource(ContentStyle.emphasis(ContentStyle.metadata(ContentStyle.body()))));
    }

    /** Roomier fields and inset tab pills, with compact button metrics preserved. */
    private static void metrics(UIDefaults d) {
        d.put("Button.arc", 6);
        d.put("ToggleButton.arc", 6);
        d.put("Component.arc", 8);
        d.put("TextComponent.arc", 8);
        d.put("ProgressBar.arc", 6);
        d.put("Component.focusWidth", 2);
        d.put("Component.innerFocusWidth", 1);
        d.put("Component.arrowType", "chevron");
        d.put("Button.margin", new Insets(3, 8, 3, 8));
        d.put("ToggleButton.margin", new Insets(3, 8, 3, 8));
        d.put("Button.minimumWidth", 64);
        d.put("Button.minimumHeight", 28);
        d.put("Button.default.boldText", false);
        d.put("Component.minimumHeight", 30);
        for (String type : new String[]{"TextField", "FormattedTextField", "PasswordField"})
            d.put(type + ".margin", new Insets(4, 10, 4, 10));
        d.put("ComboBox.padding", new Insets(4, 10, 4, 10));
        d.put("Spinner.padding", new Insets(4, 10, 4, 10));
        d.put("ComboBox.selectionArc", 6);
        d.put("ComboBox.selectionInsets", new Insets(1, 4, 1, 4));
        d.put("ComboBox.popupInsets", new Insets(4, 0, 4, 0));
        d.put("TabbedPane.tabHeight", 32);
        d.put("TabbedPane.tabInsets", new Insets(4, 14, 4, 14));
        d.put("TabbedPane.tabArc", 8);
        d.put("TabbedPane.selectedInsets", new Insets(2, 2, 2, 2));
        d.put("TabbedPane.tabSelectionHeight", 3);
        d.put("TabbedPane.tabSelectionArc", 3);
        // Negative top moves the three-pixel underline onto the pill without reducing its height.
        d.put("TabbedPane.tabSelectionInsets", new Insets(-2, 14, 2, 14));
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
        d.put("Popup.borderCornerRadius", 8);
        d.put("PopupMenu.borderCornerRadius", 8);
        d.put("ComboBox.borderCornerRadius", 8);
    }

    /** Stronger outlines, dividers and secondary text for the users of the retired high-contrast themes. */
    private static void contrast(UIDefaults d, Palette p) {
        color(d, "Component.borderColor", p.contrastBorder);
        color(d, "Violet.fieldBorder", p.contrastBorder);
        color(d, "PopupMenu.borderColor", p.contrastBorder);
        color(d, "Separator.foreground", p.contrastSubtle);
        color(d, "TableHeader.separatorColor", p.contrastSubtle);
        color(d, "TableHeader.bottomSeparatorColor", p.contrastSubtle);
        color(d, "TabbedPane.contentAreaColor", p.contrastSubtle);
        color(d, "Label.disabledForeground", p.contrastMuted);
        color(d, "TextArea.inactiveForeground", p.contrastMuted);
        color(d, "TableHeader.foreground", p.text);
        d.put("Component.focusWidth", 3);
    }

    /** Resting edge only; public constructors let FlatLaf clone borders for component styles. */
    public static final class FieldTextBorder extends FlatTextBorder {
        public FieldTextBorder() { borderColor = UIManager.getColor("Violet.fieldBorder"); }
    }

    public static final class FieldRoundBorder extends FlatRoundBorder {
        public FieldRoundBorder() { borderColor = UIManager.getColor("Violet.fieldBorder"); }
    }

    private static int blend(int base, int tint, float amount) {
        Color a = new Color(base), b = new Color(tint);
        return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * amount),
            Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * amount),
            Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * amount)).getRGB();
    }

    private static void color(UIDefaults d, String key, int rgb) {
        d.put(key, new ColorUIResource(rgb));
    }

    private static String hex(int rgb) { return String.format("#%06X", rgb & 0xFFFFFF); }

    /** Installs Violet Dark without contrast; kept for callers that predate {@link Themes}. */
    public static boolean install() {
        return Themes.install(new Themes.Choice(Themes.Variant.DARK, false));
    }
}
