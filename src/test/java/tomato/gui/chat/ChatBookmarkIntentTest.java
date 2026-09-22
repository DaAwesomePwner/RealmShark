package tomato.gui.chat;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.history.*;
import tomato.history.SessionStore;
import tomato.history.archive.*;
import util.PreferencesStore;
import static org.junit.Assert.*;
import static tomato.gui.chat.SocialArchiveTestSupport.*;

public class ChatBookmarkIntentTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void delayedArchiveFlushCannotOverwriteNewerLiveChoicesOrTheLatestSavedPin() throws Exception {
        Path root = temp.newFolder().toPath(), scratch = root.resolve(".query-scratch/chat");
        ChatMessage message = new ChatMessage(LocalDateTime.of(2026,9,22,12,0), ChatMessage.Channel.WORLD, "Ann", "", "Ann", "Synthetic message", "");
        CountDownLatch oldFlushed = new CountDownLatch(1), releaseOld = new CountDownLatch(1);
        AtomicInteger flushes = new AtomicInteger(); AtomicBoolean oldReturned = new AtomicBoolean();
        List<ChatExplorer.Bookmark> writes = new CopyOnWriteArrayList<>();
        try (SessionStore store = new SessionStore(root,true,"synthetic")) {
            store.append("chat", message); store.flush();
            ChatBookmarkIntents intents = new ChatBookmarkIntents(new ChatBookmarkIntents.Persistence() {
                public void enqueue(ChatExplorer.Bookmark bookmark) {
                    assertTrue("Writes enter the store in EDT intent order", SwingUtilities.isEventDispatchThread());
                    writes.add(bookmark); store.put("chat-stars",bookmark.id,bookmark);
                }
                public void flush(ChatExplorer.Bookmark bookmark) throws Exception {
                    int number = flushes.incrementAndGet(); store.flush();
                    if (number == 1) { oldFlushed.countDown(); assertTrue(releaseOld.await(15,TimeUnit.SECONDS)); oldReturned.set(true); }
                }
            }, () -> 100L);
            ViewStateStore states = new ViewStateStore(new ViewStateStore.Storage() {
                final Map<String,String> values = new HashMap<>();
                public String get(String key) { return values.get(key); }
                public CompletionStage<PreferencesStore.SaveResult> put(String key,String value) { values.put(key,value); return CompletableFuture.completedFuture(PreferencesStore.SaveResult.saved(1)); }
            });
            ChatFilters filters = new ChatFilters();
            ChatExplorer live = edt(() -> { ChatExplorer v = new ChatExplorer(() -> {},filters,() -> "",false);v.loadHistory(Collections.singletonList(message),Collections.emptySet(),store);return v; });
            JPanel host = edt(() -> { JPanel panel = new JPanel();panel.setVisible(false);panel.addNotify();return panel; });
            ArchiveWorkspace<ChatArchiveClient.Row,ChatArchiveClient.Facets,ChatArchiveClient.Sort> ws = edt(() -> {
                ArchiveWorkspace<ChatArchiveClient.Row,ChatArchiveClient.Facets,ChatArchiveClient.Sort> value = SessionPanel.queried(store,"chat",live,new ChatArchiveClient(store,filters,live,scratch,intents),states);
                host.add(value);value.showSaved();return value;
            });
            try {
                await(() -> ws.displayedPage()!=null && !ws.loading());
                ArchiveResult.Lease<ChatArchiveClient.Row> original = edt(() -> ws.displayedPage().lease());
                try {
                    ChatBookmarkIntents.Intent old = edt(() -> { named(ws,"chat-archive-messages",JTable.class).setRowSelectionInterval(0,0);button(ws,"Toggle star").doClick();return intents.latest(message.id); });
                    assertTrue(oldFlushed.await(5,TimeUnit.SECONDS));assertTrue(writes.get(0).starred);
                    edt(() -> {
                        button(ws,"Current live view").doClick();assertFalse(ws.state().archive);
                        JTable table=named(live,"chat-messages",JTable.class);table.setRowSelectionInterval(0,0);assertEquals("★",table.getValueAt(0,0));
                        // All four intents use the same wall-clock millisecond. Live controls
                        // change unstar -> star -> unstar while the first archive flush is held.
                        for(int i=0;i<3;i++) { table.setRowSelectionInterval(0,0);table.getActionMap().get("star-message").actionPerformed(null); }
                        assertEquals("",table.getValueAt(0,0));return null;
                    });
                    edt(() -> {
                        assertEquals(4,writes.size());for(int i=1;i<writes.size();i++)assertTrue(writes.get(i).changed>writes.get(i-1).changed);
                        assertFalse(intents.latest(message.id).bookmark.starred);return null;
                    });
                    store.flush();
                    await(() -> live.bookmarkRevision()>0);
                    assertFalse("The newest state is saved before the old completion returns",oldReturned.get());
                    List<ChatExplorer.Bookmark> disk=store.read(store.currentId(),"chat-stars",ChatExplorer.Bookmark.class);
                    assertEquals(1,disk.size());assertFalse(disk.get(0).starred);assertEquals(104,disk.get(0).changed);
                    long revision=edt(live::bookmarkRevision);releaseOld.countDown();old.saved.get(5,TimeUnit.SECONDS);
                    edt(() -> null);edt(() -> null);
                    assertEquals("",edt(() -> named(live,"chat-messages",JTable.class).getValueAt(0,0)));
                    assertEquals(revision,edt(live::bookmarkRevision).longValue());
                    edt(() -> {button(ws,"Browse saved").doClick();return null;});
                    await(() -> !ws.loading() && ws.displayedPage().rows.get(0).value.bookmarkChanged==104);
                    assertFalse(edt(() -> ws.displayedPage().rows.get(0).value.starred));
                    List<ChatArchiveClient.Row> frozen=new ArrayList<>();original.stream(ExportSelection.all(),r->frozen.add(r.value),new Cancellation());assertFalse(frozen.get(0).starred);
                } finally { original.close(); }
            } finally { releaseOld.countDown();edt(() -> { ws.close();host.removeNotify();return null; }); }
        }
    }

    @Test public void staleBaselinesUseLatestIntentAndRestartUsesTheCapturedDurableVersion() throws Exception {
        List<ChatExplorer.Bookmark> writes = new CopyOnWriteArrayList<>();
        ChatBookmarkIntents.Persistence memory = new ChatBookmarkIntents.Persistence() {
            public void enqueue(ChatExplorer.Bookmark value) { writes.add(value); }
            public void flush(ChatExplorer.Bookmark value) { }
        };
        ChatBookmarkIntents first = new ChatBookmarkIntents(memory, () -> 100L);
        ChatBookmarkIntents.Intent old = edt(() -> first.toggle("id",false,500));
        ChatBookmarkIntents.Intent newest = edt(() -> first.toggle("id",false,0));
        newest.saved.get(5,TimeUnit.SECONDS);
        assertEquals(501,old.bookmark.changed);assertEquals(502,newest.bookmark.changed);assertFalse(newest.bookmark.starred);
        assertFalse(edt(() -> first.current(old)));assertTrue(edt(() -> first.current(newest)));
        ChatBookmarkIntents restarted = new ChatBookmarkIntents(memory, () -> 100L);
        ChatBookmarkIntents.Intent afterRestart = edt(() -> restarted.toggle("id",newest.bookmark.starred,newest.bookmark.changed));
        afterRestart.saved.get(5,TimeUnit.SECONDS);assertEquals(503,afterRestart.bookmark.changed);assertTrue(afterRestart.bookmark.starred);
        assertEquals(3,writes.size());
    }
    @Test public void retryKeepsTheChoiceButCannotEnqueueAnIntentSupersededByANewerToggle() throws Exception {
        List<ChatExplorer.Bookmark> writes=new CopyOnWriteArrayList<>();AtomicBoolean fail=new AtomicBoolean(true);
        ChatBookmarkIntents intents=new ChatBookmarkIntents(new ChatBookmarkIntents.Persistence(){
            public void enqueue(ChatExplorer.Bookmark bookmark){writes.add(bookmark);}
            public void flush(ChatExplorer.Bookmark bookmark)throws Exception{if(fail.getAndSet(false))throw new java.io.IOException("Synthetic flush failure");}
        },()->100L);
        ChatBookmarkIntents.Intent first=edt(()->intents.toggle("id",false,0));
        try{first.saved.get(5,TimeUnit.SECONDS);fail("Expected a failed save");}catch(ExecutionException expected){}
        ChatBookmarkIntents.Intent retry=edt(()->intents.retry(first));retry.saved.get(5,TimeUnit.SECONDS);
        assertTrue(retry.bookmark.starred);assertEquals(102,retry.bookmark.changed);
        ChatBookmarkIntents.Intent newer=edt(()->intents.toggle("id",false,0));newer.saved.get(5,TimeUnit.SECONDS);assertFalse(newer.bookmark.starred);
        edt(()->{try{intents.retry(first);fail("A stale retry must not write");}catch(IllegalStateException expected){}return null;});
        assertEquals(3,writes.size());assertFalse(writes.get(2).starred);
    }
}
