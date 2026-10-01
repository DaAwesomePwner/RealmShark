package tomato.history.index;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import tomato.history.SessionStore;
import static org.junit.Assert.*;

public class IndexUnavailableTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static int retained(HistoryIndex index,String fieldName) throws Exception {
        java.lang.reflect.Field field=HistoryIndex.class.getDeclaredField(fieldName); field.setAccessible(true);
        Object value=field.get(index);
        return value instanceof java.util.Map<?,?> map ? map.size() : ((java.util.Collection<?>)value).size();
    }
    @Test public void nativeFailureClearsPendingOffersAndRejectsFurtherRetention() throws Exception {
        Path root=temp.newFolder("unavailable-history").toPath(), file=temp.getRoot().toPath().resolve("index/search.db");
        try (SessionStore store=new SessionStore(root,false,"test")) {
            HistoryIndex index=new HistoryIndex(store,file,true,1,path -> { throw new UnsatisfiedLinkError("Synthetic native failure"); },id -> {});
            try {
                index.offer(store.currentId(),"timeline",0,null,new Object());
                index.offer(store.currentId(),"timeline",1,null,new Object());
                index.removeSession("00000000-0000-0000-0000-000000000001");
                assertEquals(1,retained(index,"offers")); assertEquals(1,retained(index,"stale"));
                assertEquals(1,retained(index,"removed"));
                assertEquals(HistoryIndex.Phase.UNAVAILABLE,index.start().get(10,TimeUnit.SECONDS).phase());
                for (String field:java.util.List.of("offers","stale","removed","sessions","retries"))
                    assertEquals(field,0,retained(index,field));
                long overflows=index.overflowCount();
                for (int i=0;i<20;i++) {
                    String session=java.util.UUID.randomUUID().toString();
                    index.offer(session,"timeline",i,null,new Object());
                    index.offer(session,"timeline",-1,null,new Object());
                    index.markSessionChanged(session); index.removeSession(session);
                }
                for (String field:java.util.List.of("offers","stale","removed","sessions","retries"))
                    assertEquals(field,0,retained(index,field));
                assertEquals(overflows,index.overflowCount());
            } finally { index.closeAsync().get(10,TimeUnit.SECONDS); }
        }
    }
    @Test public void failedIndexDoesNotInterfereWithTheLivePersistenceFeed() throws Exception {
        Path root=temp.newFolder("live-history").toPath(), file=temp.getRoot().toPath().resolve("unavailable/search.db");
        try (SessionStore store=new SessionStore(root,true,"test")) {
            HistoryIndex index=new HistoryIndex(store,file,true,1,path -> { throw new UnsatisfiedLinkError("Synthetic native failure"); },id -> {});
            store.setPersistenceListener(index::offer);
            try {
                assertEquals(HistoryIndex.Phase.UNAVAILABLE,index.start().get(10,TimeUnit.SECONDS).phase());
                for (int i=0;i<4;i++) store.append("timeline",java.util.Map.of("kind","Boss","detail","Saved "+i));
                store.put("notes","key","Still saved"); store.flush();
                assertEquals(4,store.read(store.currentId(),"timeline",com.google.gson.JsonObject.class).size());
                assertEquals(java.util.List.of("Still saved"),store.read(store.currentId(),"notes",String.class));
                assertEquals("",store.error());
            } finally { index.closeAsync().get(10,TimeUnit.SECONDS); }
        }
    }
    @Test public void nativeFailureIsContainedAndMutationsNeverThrow() throws Exception {
        Path root=temp.newFolder("history").toPath(), file=temp.getRoot().toPath().resolve("index/search.db");
        try (SessionStore store=new SessionStore(root,false,"test")) {
            HistoryIndex index=new HistoryIndex(store,file,true,1,path -> { throw new UnsatisfiedLinkError("Synthetic native failure"); },id -> {});
            try {
                assertEquals(HistoryIndex.Phase.UNAVAILABLE,index.start().get(10,TimeUnit.SECONDS).phase());
                assertTrue(index.state().reason().contains("UnsatisfiedLinkError"));
                index.offer(store.currentId(),"timeline",0,null,new Object()); index.markSessionChanged(store.currentId());
                index.removeSession(store.currentId()); index.setIncludeChat(false); index.flush().get(10,TimeUnit.SECONDS);
                assertFalse(java.nio.file.Files.exists(file));
            } finally { index.closeAsync().get(10,TimeUnit.SECONDS); }
        }
    }
}
