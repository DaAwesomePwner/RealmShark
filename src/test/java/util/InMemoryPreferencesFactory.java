package util;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/** Test-JVM preferences that never read or write the platform's persistent store. */
public final class InMemoryPreferencesFactory implements PreferencesFactory {
    private final Preferences user = new MemoryPreferences(null, "");
    private final Preferences system = new MemoryPreferences(null, "");

    @Override public Preferences userRoot() { return user; }
    @Override public Preferences systemRoot() { return system; }

    static final class MemoryPreferences extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();

        MemoryPreferences(AbstractPreferences parent, String name) {
            super(parent, name);
            newNode = parent != null;
        }

        // AbstractPreferences holds this node's lock when invoking each SPI method.
        @Override protected void putSpi(String key, String value) { values.put(key, value); }
        @Override protected String getSpi(String key) { return values.get(key); }
        @Override protected void removeSpi(String key) { values.remove(key); }
        @Override protected void removeNodeSpi() { values.clear(); }
        @Override protected String[] keysSpi() { return values.keySet().toArray(new String[0]); }
        @Override protected String[] childrenNamesSpi() {
            return Arrays.stream(cachedChildren()).map(AbstractPreferences::name).toArray(String[]::new);
        }
        @Override protected AbstractPreferences childSpi(String name) { return new MemoryPreferences(this, name); }

        // The superclass owns the child cache; all updates are already in memory.
        @Override protected void syncSpi() {}
        @Override protected void flushSpi() {}
    }
}
