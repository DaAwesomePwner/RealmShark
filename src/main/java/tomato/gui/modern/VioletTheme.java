package tomato.gui.modern;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.*;
import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;

/** The default desktop theme. Legacy themes remain available in Edit > Theme. */
public final class VioletTheme extends FlatDarkLaf {
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
        color(d, "TableHeader.background", 0x242432);
        color(d, "TabbedPane.underlineColor", 0xAD8CFF);
        color(d, "TabbedPane.selectedBackground", 0x292338);
        color(d, "Component.focusColor", 0xAD8CFF);
        color(d, "Component.accentColor", 0xAD8CFF);
        color(d, "Button.default.background", 0x9063ED);
        color(d, "Button.default.foreground", 0xFFFFFF);
        color(d, "Button.default.hoverBackground", 0xA77CF5);
        color(d, "Button.background", 0x292936);
        color(d, "Separator.foreground", 0x313140);
        color(d, "MenuBar.background", 0x111118);
        color(d, "TitlePane.background", 0x111118);
        color(d, "TitlePane.inactiveBackground", 0x111118);
        color(d, "Label.disabledForeground", 0xA7A3B8);
        color(d, "ScrollPane.background", 0x191922);
        d.put("defaultFont", new FontUIResource("Segoe UI", Font.PLAIN, 13));
        d.put("Button.arc", 12);
        d.put("Component.arc", 12);
        d.put("TextComponent.arc", 12);
        d.put("Component.focusWidth", 1);
        d.put("Button.margin", new Insets(8, 14, 8, 14));
        d.put("TabbedPane.tabHeight", 40);
        d.put("TabbedPane.tabInsets", new Insets(8, 16, 8, 16));
        d.put("Table.rowHeight", 30);
        d.put("ScrollBar.width", 11);
        d.put("ScrollBar.thumbArc", 999);
        d.put("ScrollBar.trackArc", 999);
        d.put("SplitPane.dividerSize", 7);
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
