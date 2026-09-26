package tomato.gui.modern;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import util.PropertiesManager;
import static org.junit.Assert.*;

public class ThemesTest {
    @Test public void savedMigrationIsAtomicIdempotentAndKeepsLaterContrastChoice() {
        String previousTheme = PropertiesManager.getProperty(Themes.THEME_KEY);
        String previousContrast = PropertiesManager.getProperty(Themes.CONTRAST_KEY);
        try {
            PropertiesManager.setProperties(Map.of(Themes.THEME_KEY, "contrastLight", Themes.CONTRAST_KEY, "false"));
            long before = PropertiesManager.status().generation;
            assertEquals(light(true), Themes.migrateSaved());
            assertEquals(before + 1, PropertiesManager.status().generation);
            assertEquals("violetLight", PropertiesManager.getProperty(Themes.THEME_KEY));
            assertEquals("true", PropertiesManager.getProperty(Themes.CONTRAST_KEY));
            assertEquals(light(true), Themes.migrateSaved());
            assertEquals(before + 1, PropertiesManager.status().generation);
            PropertiesManager.setProperties(Themes.CONTRAST_KEY, "false");
            assertEquals(light(false), Themes.migrateSaved());
        } finally {
            PropertiesManager.setProperties(Map.of(Themes.THEME_KEY, previousTheme == null ? "" : previousTheme,
                Themes.CONTRAST_KEY, previousContrast == null ? "" : previousContrast));
        }
    }

    private static Themes.Choice dark(boolean contrast) { return new Themes.Choice(Themes.Variant.DARK, contrast); }
    private static Themes.Choice light(boolean contrast) { return new Themes.Choice(Themes.Variant.LIGHT, contrast); }

    @Test public void missingOrBlankPreferencesMeanVioletDark() {
        assertEquals(dark(false), Themes.resolve(null, null));
        assertEquals(dark(false), Themes.resolve("", ""));
    }

    @Test public void supportedValuesRoundTrip() {
        assertEquals(dark(false), Themes.resolve("violet", "false"));
        assertEquals(light(false), Themes.resolve("violetLight", "false"));
        assertEquals(dark(true), Themes.resolve("violet", "true"));
        assertEquals(light(true), Themes.resolve("violetLight", "true"));
        assertEquals("violet", Themes.preferenceValue(Themes.Variant.DARK));
        assertEquals("violetLight", Themes.preferenceValue(Themes.Variant.LIGHT));
    }

    @Test public void retiredDarklafThemesMapToTheNearestVariant() {
        assertEquals(dark(false), Themes.resolve("darcula", null));
        assertEquals(dark(false), Themes.resolve("solarizedDark", null));
        assertEquals(light(false), Themes.resolve("intelliJ", null));
        assertEquals(light(false), Themes.resolve("solarizedLight", null));
        assertEquals(dark(true), Themes.resolve("contrastDark", null));
        assertEquals(light(true), Themes.resolve("contrastLight", null));
        assertEquals(dark(true), Themes.resolve("darcula", "true"));
        // Unknown values keep the old default-to-dark behavior rather than failing startup.
        assertEquals(dark(false), Themes.resolve("somethingElse", null));
    }

    @Test public void migrationRewritesOnlyRetiredValues() {
        assertEquals(Collections.emptyMap(), Themes.migration(null, null));
        assertEquals(Collections.emptyMap(), Themes.migration("violet", null));
        assertEquals(Collections.emptyMap(), Themes.migration("violetLight", "true"));
        Map<String, String> expected = new HashMap<>();
        expected.put(Themes.THEME_KEY, "violetLight"); expected.put(Themes.CONTRAST_KEY, "true");
        assertEquals(expected, Themes.migration("contrastLight", null));
        expected.put(Themes.THEME_KEY, "violet"); expected.put(Themes.CONTRAST_KEY, "false");
        assertEquals(expected, Themes.migration("darcula", ""));
    }
}
