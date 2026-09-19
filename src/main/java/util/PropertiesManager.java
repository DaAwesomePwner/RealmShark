package util;

import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Memory-immediate application preferences; disk work belongs to PreferencesStore.
 * Startup must call preload() off the EDT before creating models/views that cache preferences.
 */
public class PropertiesManager {
    // Retained for existing reflection-based test presets; production updates use the store.
    private static final Properties properties = new Properties();
    private static final PreferencesStore store = new PreferencesStore(
        Paths.get("realmShark.properties"), properties, new PreferencesStore.FileStorage());
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
            store.shutdown(3, TimeUnit.SECONDS, System.err::println), "preferences-shutdown"));
    }

    public static void setProperties(String name, String value) { store.setProperties(name, value); }
    public static void setProperties(Map<String, String> updates) { store.setProperties(updates); }
    public static String getProperty(String name) { return store.getProperty(name); }
    public static PreferencesStore.Status status() { return store.status(); }
    public static PreferencesStore.SaveResult preload() { return store.preload(); }

    public static CompletionStage<PreferencesStore.SaveResult> setPropertiesAsync(String name, String value) {
        return store.setProperties(name, value);
    }

    /** Await off the EDT when a caller/test needs disk durability rather than immediate memory. */
    public static CompletionStage<PreferencesStore.SaveResult> flush() { return store.flush(); }
}
