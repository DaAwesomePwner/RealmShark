package tomato.gui.kit;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import javax.swing.*;
import tomato.gui.modern.VioletTheme;

/** Button hierarchy: one PRIMARY per view, SECONDARY by default, GHOST for tertiary, DANGER for destructive, ICON for compact tools. */
public class KitButton extends JButton {
    public enum Variant { PRIMARY, SECONDARY, GHOST, DANGER, ICON }

    private final Variant variant;

    public KitButton(Variant variant, String text, Icon icon) {
        super(text, icon);
        this.variant = variant;
        applyVariant();
    }

    public static KitButton primary(String text) { return new KitButton(Variant.PRIMARY, text, null); }
    public static KitButton secondary(String text) { return new KitButton(Variant.SECONDARY, text, null); }
    public static KitButton ghost(String text) { return new KitButton(Variant.GHOST, text, null); }
    public static KitButton danger(String text) { return new KitButton(Variant.DANGER, text, null); }

    /** Icon-only; the label becomes the tooltip and accessible name. */
    public static KitButton icon(Icon icon, String label) {
        KitButton button = new KitButton(Variant.ICON, null, icon);
        button.setToolTipText(label);
        button.getAccessibleContext().setAccessibleName(label);
        return button;
    }

    public Variant variant() { return variant; }

    @Override public void updateUI() {
        super.updateUI();
        if (variant != null) applyVariant(); // null while JButton's constructor runs
    }

    private void applyVariant() {
        Map<String, Object> style = new HashMap<>();
        putClientProperty("JButton.buttonType", null);
        switch (variant) {
            case PRIMARY:
                Color primary = Tokens.color(Tokens.Role.PRIMARY);
                style.put("background", primary);
                style.put("foreground", Color.WHITE);
                style.put("borderColor", primary);
                style.put("hoverBackground", VioletTheme.CAPTURE_HOVER);
                style.put("focusedBackground", VioletTheme.CAPTURE_HOVER);
                style.put("pressedBackground", VioletTheme.CAPTURE_PRESSED);
                style.put("hoverForeground", Color.WHITE);
                style.put("pressedForeground", Color.WHITE);
                break;
            case GHOST:
                putClientProperty("JButton.buttonType", "borderless");
                style.put("foreground", Tokens.color(Tokens.Role.ACCENT_TEXT));
                break;
            case DANGER:
                style.put("foreground", Tokens.color(Tokens.Role.BAD));
                style.put("borderColor", Tokens.color(Tokens.Role.BAD));
                break;
            case ICON:
                putClientProperty("JButton.buttonType", "toolBarButton");
                setMargin(new Insets(4, 4, 4, 4));
                break;
            default:
                break;
        }
        putClientProperty("FlatLaf.style", style.isEmpty() ? null : style);
    }
}
