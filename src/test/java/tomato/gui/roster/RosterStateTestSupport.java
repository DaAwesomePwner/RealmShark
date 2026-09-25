package tomato.gui.roster;

import java.awt.*;
import java.util.*;
import java.util.concurrent.*;
import tomato.gui.history.ViewStateStore;
import util.PreferencesStore;

public final class RosterStateTestSupport {
    public static final class Memory implements ViewStateStore.Storage {
        public final Map<String, String> values = new LinkedHashMap<>();
        public final ViewStateStore store = new ViewStateStore(this);
        public boolean fail;
        public int writes;
        public String get(String key) { return values.get(key); }
        public CompletionStage<PreferencesStore.SaveResult> put(String key, String value) {
            writes++;
            if (fail) return CompletableFuture.completedFuture(PreferencesStore.SaveResult.failed(writes, new java.io.IOException("Synthetic save failure")));
            values.put(key, value); return CompletableFuture.completedFuture(PreferencesStore.SaveResult.saved(writes));
        }
    }
    public static <T> T named(Container root, String name, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (name.equals(c.getName()) && type.isInstance(c)) return type.cast(c);
            if (c instanceof Container) { T value = named((Container)c, name, type); if (value != null) return value; }
        }
        return null;
    }
    private RosterStateTestSupport() { }
}
