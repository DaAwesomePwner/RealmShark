package tomato.history;

import com.google.gson.*;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class SessionWriteTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static FileChannel open(Path file) throws IOException {
        return FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }
    private static Path journal(SessionStore store, String module) {
        return store.directory().resolve(store.currentId()).resolve(module + ".jsonl");
    }
    private static final class Hold implements AutoCloseable {
        final CountDownLatch release = new CountDownLatch(1);
        Hold(SessionStore store) throws Exception {
            Field field = SessionStore.class.getDeclaredField("worker"); field.setAccessible(true);
            CountDownLatch entered = new CountDownLatch(1);
            ((ExecutorService)field.get(store)).submit(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            if (!entered.await(5, TimeUnit.SECONDS)) { release.countDown(); fail("Writer did not reach barrier"); }
        }
        @Override public void close() { release.countDown(); }
    }
    @Test public void interleavedModulesUseOneOpenEachAndKeepExactJsonLinesAndOrder() throws Exception {
        Map<Path, AtomicInteger> opens = new ConcurrentHashMap<>();
        SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "test", file -> {
            opens.computeIfAbsent(file, key -> new AtomicInteger()).incrementAndGet(); return open(file);
        });
        try {
            Map<String, StringBuilder> expected = new LinkedHashMap<>();
            try (Hold hold = new Hold(store)) {
                for (int i = 0; i < 1500; i++) {
                    String module = i % 2 == 0 ? "chat" : "timeline";
                    Map<String, Object> value = new LinkedHashMap<>(); value.put("index", i); value.put("text", "Unicode \u96ea\n\"quoted\"");
                    expected.computeIfAbsent(module, key -> new StringBuilder()).append(SessionStore.JSON.toJson(value)).append('\n');
                    store.append(module, value);
                }
                store.put("runs", "one", Map.of("final", true));
            }
            store.flush();
            for (Map.Entry<String, StringBuilder> entry : expected.entrySet()) {
                Path file = journal(store, entry.getKey());
                assertEquals(entry.getValue().toString(), Files.readString(file, StandardCharsets.UTF_8));
                assertEquals("One channel per module in the drain", 1, opens.get(file).get());
            }
            assertEquals(1, store.read(store.currentId(), "runs", JsonObject.class).size());
        } finally { store.close(); }
    }
    @Test public void partialBatchFailureRollsBackAndRetryDoesNotDuplicateEvenIfRollbackFailsOnce() throws Exception {
        AtomicBoolean failWrite = new AtomicBoolean(), failRollback = new AtomicBoolean();
        SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "test", file ->
            file.getFileName().toString().equals("chat.jsonl") ? new FailingChannel(open(file), failWrite, failRollback) : open(file));
        try {
            store.append("chat", "prefix"); store.flush();
            String prefix = Files.readString(journal(store, "chat"));
            try (Hold hold = new Hold(store)) {
                failWrite.set(true); failRollback.set(true);
                store.append("timeline", "committed before failure");
                store.append("chat", "first"); store.append("chat", "second");
            }
            try { store.flush(); fail("Expected IO failure"); } catch (IOException expected) { }
            assertTrue(store.error(), store.error().startsWith(SessionStore.SAVE_FAILED));
            // A subsequent retry restores the original prefix before attempting another batch.
            try { store.flush(); fail("Expected IO failure"); } catch (IOException expected) { }
            try (Hold hold = new Hold(store)) {
                assertEquals(prefix, Files.readString(journal(store, "chat")));
                failWrite.set(false);
            }
            store.flush();
            assertEquals(prefix + "\"first\"\n\"second\"\n", Files.readString(journal(store, "chat")));
            assertEquals(List.of("committed before failure"), store.read(store.currentId(), "timeline", String.class));
            assertEquals("", store.error());
        } finally { failWrite.set(false); failRollback.set(false); store.close(); }
    }
    @Test public void poisonEventsAndCheckpointsAreSkippedCountedAndDoNotBlockLaterWrites() throws Exception {
        SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "test");
        try {
            try (Hold hold = new Hold(store)) {
                store.append("chat", "before");
                store.append("chat", new Bad(0)); store.append("chat", new Bad(1)); store.append("chat", new Bad(2));
                store.append("chat", "after"); store.put("notes", "bad", new Bad(0)); store.put("notes", "good", "saved");
            }
            store.flush();
            assertEquals(List.of("before", "after"), store.read(store.currentId(), "chat", String.class));
            assertEquals(List.of("saved"), store.read(store.currentId(), "notes", String.class));
            assertEquals("4" + SessionStore.RECORDS_SKIPPED, store.error());
            store.flush(); assertEquals("4" + SessionStore.RECORDS_SKIPPED, store.error());
        } finally { store.close(); }
    }
    @Test public void fullQueueRefusesNewEventsButKeepsCheckpointsAndReportsCurrentIoFailure() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "test", file -> {
            if (fail.get()) throw new IOException("synthetic disk failure"); return open(file);
        });
        try {
            try (Hold hold = new Hold(store)) {
                for (int i = 0; i < SessionStore.EVENT_LIMIT + 3; i++) store.append("chat", i);
                store.put("notes", "one", "checkpoint");
            }
            try { store.flush(); fail("Expected IO failure"); } catch (IOException expected) { }
            assertTrue(store.error(), store.error().startsWith(SessionStore.SAVE_FAILED));
            assertTrue(store.error(), store.error().endsWith("3" + SessionStore.RECORDS_SKIPPED));
            fail.set(false); store.flush();
            List<Integer> saved = store.read(store.currentId(), "chat", Integer.class);
            assertEquals(SessionStore.EVENT_LIMIT, saved.size());
            for (int i = 0; i < saved.size(); i++) assertEquals(Integer.valueOf(i), saved.get(i));
            assertEquals(List.of("checkpoint"), store.read(store.currentId(), "notes", String.class));
            assertEquals("3" + SessionStore.RECORDS_SKIPPED, store.error());
        } finally { fail.set(false); store.close(); }
    }
    @Test public void serializationIoFailuresAreSkippedCountedAndNeverRetried() throws Exception {
        SessionStore store = new SessionStore(temp.newFolder().toPath(), true, "test");
        Bad value = new Bad(3);
        try {
            try (Hold hold = new Hold(store)) {
                store.append("chat", value); store.append("chat", "after"); store.put("notes", "good", "saved");
            }
            store.flush();
            assertEquals("1" + SessionStore.RECORDS_SKIPPED, store.error());
            assertEquals(List.of("after"), store.read(store.currentId(), "chat", String.class));
            assertEquals(List.of("saved"), store.read(store.currentId(), "notes", String.class));
            value.retryReady.set(true); store.flush();
            assertEquals(List.of("after"), store.read(store.currentId(), "chat", String.class));
            assertEquals("1" + SessionStore.RECORDS_SKIPPED, store.error());
        } finally { store.close(); }
    }
    @JsonAdapter(BadAdapter.class) private static final class Bad {
        final int kind;
        final AtomicBoolean retryReady = new AtomicBoolean();
        Bad(int kind) { this.kind = kind; }
    }
    public static final class BadAdapter extends TypeAdapter<Bad> {
        @Override public void write(JsonWriter out, Bad value) throws IOException {
            if (value.kind == 3) {
                if (!value.retryReady.get()) throw new IOException("temporary serialization IO failure");
                out.value("recovered"); return;
            }
            if (value.kind == 0) throw new JsonIOException("bad value");
            if (value.kind == 1) throw new IllegalStateException("bad value");
            throw new StackOverflowError("recursive value");
        }
        @Override public Bad read(JsonReader in) { throw new UnsupportedOperationException(); }
    }
    private static final class FailingChannel extends FileChannel {
        final FileChannel delegate;
        final AtomicBoolean failWrite, failRollback;
        FailingChannel(FileChannel delegate, AtomicBoolean failWrite, AtomicBoolean failRollback) {
            this.delegate=delegate; this.failWrite=failWrite; this.failRollback=failRollback;
        }
        @Override public int write(ByteBuffer src) throws IOException {
            if (!failWrite.get()) return delegate.write(src);
            ByteBuffer prefix=src.slice(); prefix.limit(Math.min(5,prefix.remaining())); delegate.write(prefix);
            throw new IOException("partial batch");
        }
        @Override public FileChannel truncate(long size) throws IOException {
            if (failRollback.compareAndSet(true,false)) throw new IOException("rollback failed once");
            delegate.truncate(size); return this;
        }
        @Override public long position() throws IOException { return delegate.position(); }
        @Override public FileChannel position(long value) throws IOException { delegate.position(value); return this; }
        @Override public long size() throws IOException { return delegate.size(); }
        @Override protected void implCloseChannel() throws IOException { delegate.close(); }
        @Override public int read(ByteBuffer dst) throws IOException { return delegate.read(dst); }
        @Override public long read(ByteBuffer[] dst,int offset,int length) throws IOException { return delegate.read(dst,offset,length); }
        @Override public int read(ByteBuffer dst,long position) throws IOException { return delegate.read(dst,position); }
        @Override public long write(ByteBuffer[] src,int offset,int length) throws IOException { return delegate.write(src,offset,length); }
        @Override public int write(ByteBuffer src,long position) throws IOException { return delegate.write(src,position); }
        @Override public void force(boolean metadata) throws IOException { delegate.force(metadata); }
        @Override public long transferTo(long position,long count,WritableByteChannel target) throws IOException { return delegate.transferTo(position,count,target); }
        @Override public long transferFrom(ReadableByteChannel src,long position,long count) throws IOException { return delegate.transferFrom(src,position,count); }
        @Override public MappedByteBuffer map(MapMode mode,long position,long size) throws IOException { return delegate.map(mode,position,size); }
        @Override public FileLock lock(long position,long size,boolean shared) throws IOException { return delegate.lock(position,size,shared); }
        @Override public FileLock tryLock(long position,long size,boolean shared) throws IOException { return delegate.tryLock(position,size,shared); }
    }
}
