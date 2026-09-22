package tomato.gui.chat;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import tomato.history.SessionStore;

/** One EDT intent order per history store, shared by live and saved Chat. Disk acknowledgements never replay state. */
final class ChatBookmarkIntents {
    interface Persistence {
        void enqueue(ChatExplorer.Bookmark bookmark);
        void flush(ChatExplorer.Bookmark bookmark) throws Exception;
    }
    static final class Intent {
        final ChatExplorer.Bookmark bookmark;
        final CompletableFuture<Void> saved = new CompletableFuture<>();
        private Intent(ChatExplorer.Bookmark bookmark) { this.bookmark = bookmark; }
    }
    private static final Map<SessionStore,WeakReference<ChatBookmarkIntents>> STORES = new WeakHashMap<>();
    static synchronized ChatBookmarkIntents forStore(SessionStore store) {
        WeakReference<ChatBookmarkIntents> reference = STORES.get(store);
        ChatBookmarkIntents intents = reference == null ? null : reference.get();
        if (intents == null) {
            intents = new ChatBookmarkIntents(new Persistence() {
                public void enqueue(ChatExplorer.Bookmark bookmark) {
                    if (!store.writable()) throw new IllegalStateException("History is read-only.");
                    store.put("chat-stars", bookmark.id, bookmark);
                }
                public void flush(ChatExplorer.Bookmark bookmark) throws Exception { store.flush(); }
            }, System::currentTimeMillis);
            STORES.put(store, new WeakReference<>(intents));
        }
        return intents;
    }
    private final Persistence persistence;
    private final LongSupplier clock;
    private final Map<String,Intent> latest = new HashMap<>();
    ChatBookmarkIntents(Persistence persistence, LongSupplier clock) { this.persistence = persistence; this.clock = clock; }

    Intent toggle(String id, boolean observedStarred, long observedVersion) {
        requireEdt();
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("Message ID was not captured.");
        Intent previous = latest.get(id);
        boolean before = previous != null && previous.bookmark.changed >= observedVersion ? previous.bookmark.starred : observedStarred;
        return enqueue(id, !before, observedVersion);
    }
    Intent retry(Intent failed) {
        requireEdt();
        if (!current(failed)) throw new IllegalStateException("A newer star choice is active. Refresh instead of retrying this old choice.");
        return enqueue(failed.bookmark.id, failed.bookmark.starred, failed.bookmark.changed);
    }
    private Intent enqueue(String id, boolean starred, long observedVersion) {
        Intent previous = latest.get(id);
        long floor = Math.max(clock.getAsLong(), observedVersion);
        if (previous != null) floor = Math.max(floor, previous.bookmark.changed);
        // 'changed' remains the compatible durable ordering field. Same-millisecond clicks
        // receive distinct versions, including when an old pinned row supplied the baseline.
        long version = Math.addExact(floor, 1);
        Intent intent = new Intent(new ChatExplorer.Bookmark(id, starred, version));
        persistence.enqueue(intent.bookmark); // Memory-only SessionStore queue, before another EDT intent can run.
        latest.put(id, intent);
        new SwingWorker<Void,Void>() {
            protected Void doInBackground() throws Exception {
                persistence.flush(intent.bookmark);
                return null;
            }
            protected void done() {
                try { get(); intent.saved.complete(null); }
                catch (Exception failure) { intent.saved.completeExceptionally(failure.getCause() == null ? failure : failure.getCause()); }
            }
        }.execute();
        return intent;
    }
    boolean current(Intent intent) { requireEdt(); return latest.get(intent.bookmark.id) == intent; }
    Intent latest(String id) { requireEdt(); return latest.get(id); }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Bookmark intents require the EDT.");
    }
}
