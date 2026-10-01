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
