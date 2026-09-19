package tomato.gui.modern;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.*;
import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;

/** The default desktop theme. Legacy themes remain available in Edit > Theme. */
public final class VioletTheme extends FlatDarkLaf {
    public static final Color CAPTURE_BACKGROUND = new Color(0x7041BD);
    public static final Color CAPTURE_HOVER = new Color(0x8052CD);
    public static final Color CAPTURE_PRESSED = new Color(0x6036A5);

    @Override public String getName() { return "RealmShark Violet"; }

    @Override public UIDefaults getDefaults() {
        UIDefaults d = super.getDefaults();
        color(d, "Panel.background", 0x15151E);
        color(d, "TextArea.background", 0x191922);
        color(d, "TextArea.inactiveBackground", 0x191922);
        color(d, "TextArea.disabledBackground", 0x191922);
        color(d, "TextArea.foreground", 0xE0DFEC);
        color(d, "TextArea.inactiveForeground", 0xC5C3D4);
        color(d, "TextField.background", 0x20202C);
        color(d, "ComboBox.background", 0x242431);
        color(d, "TabbedPane.background", 0x15151E);
        color(d, "ToggleButton.selectedBackground", 0x30243F);
        color(d, "ToggleButton.selectedForeground", 0xB99AFF);
        color(d, "TextComponent.selectionBackground", 0x554080);
        color(d, "Table.background", 0x191922);
        color(d, "Table.alternateRowColor", 0x1E1E29);
        color(d, "Table.selectionBackground", 0x403258);
        color(d, "List.background", 0x191922);
        color(d, "List.foreground", 0xE0DFEC);
        color(d, "List.selectionBackground", 0x403258);
        color(d, "List.selectionForeground", 0xF4F0FF);
        color(d, "TableHeader.background", 0x242432);
        color(d, "TabbedPane.underlineColor", 0xAD8CFF);
        color(d, "TabbedPane.selectedBackground", 0x292338);
        color(d, "Component.focusColor", 0xAD8CFF);
        color(d, "Component.accentColor", 0xAD8CFF);
        color(d, "Button.default.background", CAPTURE_BACKGROUND.getRGB());
        color(d, "Button.default.foreground", 0xFFFFFF);
        color(d, "Button.default.hoverBackground", CAPTURE_HOVER.getRGB());
        color(d, "Button.default.focusedBackground", CAPTURE_HOVER.getRGB());
        color(d, "Button.default.pressedBackground", CAPTURE_PRESSED.getRGB());
        color(d, "Button.default.hoverForeground", 0xFFFFFF);
        color(d, "Button.default.pressedForeground", 0xFFFFFF);
        color(d, "Button.background", 0x25262E);
        color(d, "Button.hoverBackground", 0x30313B);
        color(d, "Component.borderColor", 0x383944);
        color(d, "Component.disabledBorderColor", 0x2C2D36);
        color(d, "TableHeader.separatorColor", 0x33343E);
        color(d, "TableHeader.bottomSeparatorColor", 0x33343E);
        color(d, "Separator.foreground", 0x313140);
        color(d, "MenuBar.background", 0x111118);
        color(d, "TitlePane.background", 0x111118);
        color(d, "TitlePane.inactiveBackground", 0x111118);
        color(d, "Label.disabledForeground", 0xA7A3B8);
        color(d, "ScrollPane.background", 0x191922);
        d.put("defaultFont", new FontUIResource(ContentStyle.body()));
        d.put("Table.font", new FontUIResource(ContentStyle.body()));
        d.put("TextArea.font", new FontUIResource(ContentStyle.body()));
        d.put("TableHeader.font", new FontUIResource(ContentStyle.emphasis(ContentStyle.metadata(ContentStyle.body()))));
        color(d, "TableHeader.foreground", 0xB4AFC8);
        color(d, "Table.selectionForeground", 0xF4F0FF);
        d.put("Button.arc", 4);
        d.put("ToggleButton.arc", 4);
        d.put("Component.arc", 4);
        d.put("TextComponent.arc", 4);
        d.put("Component.focusWidth", 1);
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
        d.put("Table.rowHeight", 28);
        d.put("Table.cellMargins", new Insets(3, 8, 3, 8));
        d.put("TableHeader.height", 24);
        d.put("List.cellMargins", new Insets(3, 8, 3, 8));
        d.put("ScrollBar.width", 10);
        d.put("ScrollBar.thumbArc", 4);
        d.put("ScrollBar.trackArc", 0);
        d.put("SplitPane.dividerSize", 5);
        return d;
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
