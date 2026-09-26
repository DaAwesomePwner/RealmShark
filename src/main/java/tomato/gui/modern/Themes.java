package tomato.gui.modern;

import com.formdev.flatlaf.FlatLaf;
import java.util.Objects;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import util.PropertiesManager;

/** The supported theme choices and their installation. Install on the EDT once a UI exists. */
public final class Themes {
    public enum Variant { DARK, LIGHT }

    public static final class Choice {
        public final Variant variant;
        public final boolean increaseContrast;
        public Choice(Variant variant, boolean increaseContrast) {
            this.variant = Objects.requireNonNull(variant, "variant");
            this.increaseContrast = increaseContrast;
        }
        @Override public boolean equals(Object other) {
            return other instanceof Choice && ((Choice) other).variant == variant && ((Choice) other).increaseContrast == increaseContrast;
        }
        @Override public int hashCode() { return variant.hashCode() * 31 + (increaseContrast ? 1 : 0); }
        @Override public String toString() { return variant + (increaseContrast ? " + increase contrast" : ""); }
    }

    private static volatile boolean increaseContrast;

    private Themes() {}

    public static boolean increaseContrast() { return increaseContrast; }

    public static final String THEME_KEY = "theme", CONTRAST_KEY = "increaseContrast";

    /** Maps saved values, including the retired Darklaf theme names, to a supported choice. */
    public static Choice resolve(String theme, String contrast) {
        boolean high = "true".equals(contrast);
        if (theme == null || theme.isEmpty()) return new Choice(Variant.DARK, high);
        switch (theme) {
            case "violetLight": case "intelliJ": case "solarizedLight": return new Choice(Variant.LIGHT, high);
            case "contrastLight": return new Choice(Variant.LIGHT, true);
            case "contrastDark": return new Choice(Variant.DARK, true);
            default: return new Choice(Variant.DARK, high); // violet, darcula, solarizedDark and unknown values
        }
    }

    public static String preferenceValue(Variant variant) { return variant == Variant.LIGHT ? "violetLight" : "violet"; }

    /** The preference writes that replace a retired theme value; empty when the saved value is current. */
    public static Map<String, String> migration(String theme, String contrast) {
        if (theme == null || theme.isEmpty() || "violet".equals(theme) || "violetLight".equals(theme)) return Collections.emptyMap();
        Choice choice = resolve(theme, contrast);
        Map<String, String> updates = new HashMap<>();
        updates.put(THEME_KEY, preferenceValue(choice.variant));
        updates.put(CONTRAST_KEY, Boolean.toString(choice.increaseContrast));
        return updates;
    }

    public static Choice saved() {
        return resolve(PropertiesManager.getProperty(THEME_KEY), PropertiesManager.getProperty(CONTRAST_KEY));
    }

    /** Rewrites a retired value once, so the menu and later launches agree with what is shown. */
    public static Choice migrateSaved() {
        String theme = PropertiesManager.getProperty(THEME_KEY), contrast = PropertiesManager.getProperty(CONTRAST_KEY);
        Map<String, String> updates = migration(theme, contrast);
        if (!updates.isEmpty()) PropertiesManager.setProperties(updates);
        return resolve(theme, contrast);
    }

    /** A user choice from the menu: install and remember it. */
    public static void select(Choice choice) {
        if (!install(choice)) throw new IllegalStateException("Unable to install " + choice);
        Map<String, String> updates = new HashMap<>();
        updates.put(THEME_KEY, preferenceValue(choice.variant));
        updates.put(CONTRAST_KEY, Boolean.toString(choice.increaseContrast));
        PropertiesManager.setProperties(updates);
    }

    /**
     * Installs the variant; the contrast flag is read while the new defaults are built. Startup
     * (Tomato.java) installs before any window exists, as VioletTheme.install() always did.
     */
    public static boolean install(Choice choice) {
        Objects.requireNonNull(choice, "choice");
        boolean previousContrast = increaseContrast;
        increaseContrast = choice.increaseContrast;
        FlatLaf laf = choice.variant == Variant.LIGHT ? new VioletLightTheme() : new VioletTheme();
        boolean installed = FlatLaf.setup(laf);
        if (installed) FlatLaf.updateUI();
        else increaseContrast = previousContrast;
        return installed;
    }
}
