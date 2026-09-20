package tomato.gui.modern;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.*;
import java.util.Properties;
import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;

/** The default desktop theme. Legacy themes remain available in Edit &gt; Theme. */
public final class VioletTheme extends FlatDarkLaf {
    /** Capture action colors. Every state keeps a 4.5:1 contrast with white text. */
    public static final Color CAPTURE_BACKGROUND = new Color(0x7041BD);
    public static final Color CAPTURE_HOVER = new Color(0x8052CD);
    public static final Color CAPTURE_PRESSED = new Color(0x6036A5);

    /**
     * A violet-tinted neutral ramp. Surfaces share one hue, so depth reads as elevation
     * instead of as a second color, and each step keeps the luminance of the flat charcoal
     * step it replaces.
     */
    static final int BASE = 0x131120;           // window and panel base
    static final int NAVIGATION = 0x0E0C18;     // sidebar, menu bar and title bar
    static final int SURFACE = 0x181627;        // tables, lists and scroll panes
    static final int SURFACE_ALTERNATE = 0x1C1A2D;
    static final int SURFACE_RAISED = 0x201D33; // inputs, combo boxes and cards
    static final int CONTROL = 0x252139;
    static final int CONTROL_HOVER = 0x322C4C;
    static final int CONTROL_PRESSED = 0x1C1930;

    /**
     * Two border weights. Structural dividers stay quiet so the data reads first, while
     * interactive outlines stay visible enough to find the edge of a control.
     */
    static final int BORDER_SUBTLE = 0x252236;
    static final int BORDER = 0x38334F;
    static final int BORDER_DISABLED = 0x221F31;

    static final int ACCENT = 0xAD8CFF;
    static final int ACCENT_BRIGHT = 0xC4ADFF;
    static final int ACCENT_WASH = 0x2A2142;    // a quiet violet fill for hovered rows and selected tabs
    static final int SELECTION = 0x3B2E5E;
    static final int SELECTION_TEXT = 0xF4F0FF;
    static final int TEXT = 0xE9E6F7;
    static final int TEXT_MUTED = 0xA9A4C2;
    static final int TEXT_HEADER = 0xB7B1D0;

    @Override public String getName() { return "RealmShark Violet"; }

    /**
     * Declares the accent before the defaults are resolved. Sliders, progress bars, check boxes
     * and radio buttons derive their color from this variable while it loads, so overriding
     * Component.accentColor afterwards would leave those controls on the stock blue. A ticked
     * box fills with the accent here rather than showing a checkmark on a grey square.
     */
    @Override protected Properties getAdditionalDefaults() {
        Properties properties = super.getAdditionalDefaults();
        if (properties == null) properties = new Properties();
        properties.put("@accentColor", "#AD8CFF");
        // The stock dark theme hardcodes a grey checkmark rather than deriving it from the accent,
        // and the filled icon takes its fill from that same value.
        properties.put("CheckBox.icon.checkmarkColor", "#AD8CFF");
        properties.put("CheckBox.icon.style", "filled");
        properties.put("RadioButton.icon.style", "filled");
        return properties;
    }

    @Override public UIDefaults getDefaults() {
        UIDefaults d = super.getDefaults();
        surfaces(d);
        borders(d);
        interaction(d);
        typography(d);
        metrics(d);
        return d;
    }

    /** Backgrounds and foregrounds, from the deepest chrome up to raised controls. */
    private static void surfaces(UIDefaults d) {
        color(d, "Panel.background", BASE);
        color(d, "TabbedPane.background", BASE);
        color(d, "MenuBar.background", NAVIGATION);
        color(d, "TitlePane.background", NAVIGATION);
        color(d, "TitlePane.inactiveBackground", NAVIGATION);
        color(d, "Table.background", SURFACE);
        color(d, "Table.alternateRowColor", SURFACE_ALTERNATE);
        color(d, "List.background", SURFACE);
        color(d, "Tree.background", SURFACE);
        color(d, "ScrollPane.background", SURFACE);
        color(d, "Viewport.background", SURFACE);
        color(d, "TextArea.background", SURFACE);
        color(d, "TextArea.inactiveBackground", SURFACE);
        color(d, "TextArea.disabledBackground", SURFACE);
        color(d, "TextField.background", SURFACE_RAISED);
        color(d, "FormattedTextField.background", SURFACE_RAISED);
        color(d, "PasswordField.background", SURFACE_RAISED);
        color(d, "ComboBox.background", SURFACE_RAISED);
        color(d, "ComboBox.buttonBackground", SURFACE_RAISED);
        color(d, "Spinner.background", SURFACE_RAISED);
        color(d, "TableHeader.background", SURFACE_RAISED);
        color(d, "PopupMenu.background", SURFACE_RAISED);
        color(d, "Button.background", CONTROL);
        color(d, "ToggleButton.background", CONTROL);

        color(d, "Label.foreground", TEXT);
        color(d, "TextArea.foreground", TEXT);
        color(d, "List.foreground", TEXT);
        color(d, "Table.foreground", TEXT);
        color(d, "TextArea.inactiveForeground", TEXT_MUTED);
        color(d, "Label.disabledForeground", TEXT_MUTED);
        color(d, "TableHeader.foreground", TEXT_HEADER);
    }

    /** Quiet structure, visible controls. */
    private static void borders(UIDefaults d) {
        color(d, "Component.borderColor", BORDER);
        color(d, "Component.disabledBorderColor", BORDER_DISABLED);
        color(d, "Component.focusedBorderColor", ACCENT);
        color(d, "Separator.foreground", BORDER_SUBTLE);
        color(d, "TableHeader.separatorColor", BORDER_SUBTLE);
        color(d, "TableHeader.bottomSeparatorColor", BORDER_SUBTLE);
        color(d, "PopupMenu.borderColor", BORDER);
        color(d, "SplitPaneDivider.gripColor", BORDER);
        color(d, "SplitPane.background", BASE);
        color(d, "ToolTip.background", SURFACE_RAISED);
        color(d, "ToolTip.foreground", TEXT);
    }

    /** Hover, pressed, selected and focused states, so every control answers the pointer. */
    private static void interaction(UIDefaults d) {
        color(d, "Component.focusColor", ACCENT);
        color(d, "Component.accentColor", ACCENT);

        color(d, "Button.hoverBackground", CONTROL_HOVER);
        color(d, "Button.pressedBackground", CONTROL_PRESSED);
        color(d, "Button.focusedBackground", CONTROL_HOVER);
        color(d, "Button.default.background", CAPTURE_BACKGROUND.getRGB());
        color(d, "Button.default.foreground", 0xFFFFFF);
        color(d, "Button.default.hoverBackground", CAPTURE_HOVER.getRGB());
        color(d, "Button.default.focusedBackground", CAPTURE_HOVER.getRGB());
        color(d, "Button.default.pressedBackground", CAPTURE_PRESSED.getRGB());
        color(d, "Button.default.hoverForeground", 0xFFFFFF);
        color(d, "Button.default.pressedForeground", 0xFFFFFF);

        color(d, "ToggleButton.hoverBackground", CONTROL_HOVER);
        color(d, "ToggleButton.pressedBackground", CONTROL_PRESSED);
        color(d, "ToggleButton.selectedBackground", ACCENT_WASH);
        color(d, "ToggleButton.selectedForeground", ACCENT_BRIGHT);

        color(d, "Table.selectionBackground", SELECTION);
        color(d, "Table.selectionForeground", SELECTION_TEXT);
        color(d, "Table.selectionInactiveBackground", ACCENT_WASH);
        color(d, "Table.selectionInactiveForeground", TEXT);
        color(d, "List.selectionBackground", SELECTION);
        color(d, "List.selectionForeground", SELECTION_TEXT);
        color(d, "List.selectionInactiveBackground", ACCENT_WASH);
        color(d, "List.selectionInactiveForeground", TEXT);
        color(d, "Tree.selectionBackground", SELECTION);
        color(d, "Tree.selectionForeground", SELECTION_TEXT);
        color(d, "TextComponent.selectionBackground", SELECTION);

        color(d, "MenuItem.selectionBackground", SELECTION);
        color(d, "MenuItem.selectionForeground", SELECTION_TEXT);
        color(d, "MenuBar.hoverBackground", ACCENT_WASH);
        color(d, "MenuItem.underlineSelectionBackground", ACCENT_WASH);

        color(d, "TabbedPane.underlineColor", ACCENT);
        color(d, "TabbedPane.selectedBackground", ACCENT_WASH);
        color(d, "TabbedPane.hoverColor", SURFACE_RAISED);
        color(d, "TabbedPane.focusColor", ACCENT_WASH);
        color(d, "TabbedPane.contentAreaColor", BORDER_SUBTLE);

        // A track-free scroll bar: the thumb is the only mark, and it brightens under the pointer.
        color(d, "ScrollBar.track", SURFACE);
        color(d, "ScrollBar.hoverTrackColor", SURFACE);
        color(d, "ScrollBar.thumb", 0x332E4A);
        color(d, "ScrollBar.hoverThumbColor", 0x453E63);
        color(d, "ScrollBar.pressedThumbColor", ACCENT);
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

    private static void color(UIDefaults d, String key, int rgb) {
        d.put(key, new ColorUIResource(rgb));
    }

    public static boolean install() {
        boolean installed = FlatLaf.setup(new VioletTheme());
        FlatLaf.updateUI();
        return installed;
    }
}
