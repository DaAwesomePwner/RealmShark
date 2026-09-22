package tomato.gui.logging;

import java.util.*;
import java.util.concurrent.*;
import tomato.gui.history.ViewStateStore;
import util.PreferencesStore;

/** Synthetic, memory-immediate storage with controllable durability completions. */
final class LoggingStateTestSupport {
    static ViewStateStore memoryStore() { return new Memory().store; }
    static final class Memory implements ViewStateStore.Storage {
        final Map<String,String> values=new HashMap<>();
        final List<CompletableFuture<PreferencesStore.SaveResult>> completions=new ArrayList<>();
        final ViewStateStore store=new ViewStateStore(this);
        boolean delayed;
        public String get(String key) { return values.get(key); }
        public CompletionStage<PreferencesStore.SaveResult> put(String key,String value) {
            values.put(key,value); CompletableFuture<PreferencesStore.SaveResult> result=new CompletableFuture<>();
            completions.add(result);
            if (!delayed) result.complete(PreferencesStore.SaveResult.saved(completions.size()));
            return result;
        }
    }
    private LoggingStateTestSupport() {}
}
