package tomato.gui.glance.character;

import java.util.regex.Pattern;

/**
 * Route payload for {@code Destination.CHARACTER_SHEET}. The key is the journal key "<64 hex>:<characterId>". The tab is
 * a sheet tab id to show and select, or null to select the remembered tab. The tab is not checked against the tabs that exist.
 */
public record SheetFocus(String key, String tab) {
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}:[0-9]+"), TAB = Pattern.compile("[a-z0-9][a-z0-9-]*");

    public SheetFocus {
        if (!validKey(key)) throw new IllegalArgumentException("Not a journal character key");
        if (tab != null && !TAB.matcher(tab).matches()) throw new IllegalArgumentException("Not a sheet tab id");
    }

    public static boolean validKey(String key) { return key != null && KEY.matcher(key).matches(); }
}
