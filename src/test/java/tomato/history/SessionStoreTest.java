package tomato.history;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import tomato.gui.history.HistoryPage;
import static org.junit.Assert.*;

public class SessionStoreTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    static final class Event {
        final String text;final Instant time;final LocalDateTime local;
        Event(String text){this.text=text;time=Instant.parse("2026-09-20T12:00:00Z");local=LocalDateTime.of(2026,9,20,12,0);}
    }
    @Test public void sessionsSurviveNewBuildsKeepUnicodeAndUpdateCheckpointsWithoutDuplicates()throws Exception{
        Path root=temp.newFolder().toPath();SessionStore first=new SessionStore(root,true,"build-one");String old=first.currentId();
        first.append("chat",new Event("<html>Hi \"friend\"\nUnicode: 雪 🦈"));
        first.put("runs","visit-1",new Event("old"));first.put("runs","visit-1",new Event("latest"));first.flush();first.close();
        SessionStore next=new SessionStore(root,true,"build-two");
        try{
            assertNotEquals(old,next.currentId());assertTrue(next.read(next.currentId(),"chat",Event.class).isEmpty());
            Event saved=next.read(old,"chat",Event.class).get(0);
            assertEquals("<html>Hi \"friend\"\nUnicode: 雪 🦈",saved.text);assertEquals(Instant.parse("2026-09-20T12:00:00Z"),saved.time);
            assertEquals(LocalDateTime.of(2026,9,20,12,0),saved.local);
            assertEquals(1,next.read(old,"runs",Event.class).size());assertEquals("latest",next.read(old,"runs",Event.class).get(0).text);
            assertTrue(next.sessions().stream().anyMatch(s->s.id.equals(old)&&s.ended>0&&s.version.equals("build-one")));
            next.append("chat",new Event("new"));next.flush();assertEquals(2,next.read(SessionStore.ALL,"chat",Event.class).size());
        }finally{next.close();}
    }
    @Test public void archiveRetainsMoreThanLiveBuffersAndPagesAndSearchReachOldRecords()throws Exception{
        SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"test");
        try{
            for(int i=0;i<12005;i++)store.append("keypops",new Event("event "+i));store.flush();
            assertEquals(12005,store.read(store.currentId(),"keypops",Event.class).size());
            HistoryPage<Event> page=HistoryPage.read(store,store.currentId(),"keypops",Event.class,12,"",e->e.text);
            assertEquals(12005,page.matches);assertEquals(5,page.values.size());assertEquals("event 12004",page.values.get(4).text);assertFalse(page.more());
            page=HistoryPage.read(store,store.currentId(),"keypops",Event.class,0,"event 0",e->e.text);
            assertEquals(1,page.matches);assertEquals("event 0",page.values.get(0).text);
        }finally{store.close();}
    }
    @Test public void blockedSnapshotWorkerDoesNotBlockCaptureOrEdtAndKeepsNewestCheckpoint()throws Exception{
        SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"test");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        store.collect("blocked",()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try{
            Future<?> flush=executor.submit(()->{try{store.flush();}catch(Exception e){throw new RuntimeException(e);}});
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(()->{for(int i=0;i<2000;i++){store.append("chat",new Event("event"+i));store.put("runs","one",new Event("revision"+i));}});
            release.countDown();flush.get(10,TimeUnit.SECONDS);store.flush();
            assertEquals(2000,store.read(store.currentId(),"chat",Event.class).size());
            assertEquals("revision1999",store.read(store.currentId(),"runs",Event.class).get(0).text);
        }finally{release.countDown();executor.shutdownNow();store.close();}
    }
    @Test public void writeFailuresRetainPendingDataAndRetryAfterFolderIsRepaired()throws Exception{
        Path root=temp.getRoot().toPath().resolve("profile");Files.write(root,new byte[]{1});
        SessionStore store=new SessionStore(root,true,"test");
        try{
            store.append("chat",new Event("must survive"));
            try{store.flush();fail("Expected save failure");}catch(java.io.IOException expected){assertFalse(store.error().isEmpty());}
            Files.delete(root);Files.createDirectory(root);store.flush();
            assertEquals("",store.error());assertEquals("must survive",store.read(store.currentId(),"chat",Event.class).get(0).text);
        }finally{store.close();}
    }
    @Test public void catalogKeepsCurrentSessionReadableUntilInitialMetadataIsPublished()throws Exception{
        Path root=temp.getRoot().toPath().resolve("starting-profile");Files.write(root,new byte[]{1});
        SessionStore store=new SessionStore(root,true,"test");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try{
            store.append("chat",new Event("pending"));
            try{store.flush();fail("Expected blocked initial publication");}catch(java.io.IOException expected){ }
            store.collect("hold-startup",()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            Future<?> flush=executor.submit(()->{try{store.flush();}catch(Exception e){throw new RuntimeException(e);}});
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            Files.delete(root);Files.createDirectory(root);
            // The observable filesystem prefix of ensureCurrent: directory exists, atomic metadata move has not run.
            Path current=Files.createDirectory(root.resolve(store.currentId()));
            String broken=UUID.randomUUID().toString();Files.createDirectory(root.resolve(broken));
            SessionStore.SessionEntry starting=store.catalog().stream().filter(s->s.id.equals(store.currentId())).findFirst().get();
            assertTrue("Starting current session uses its known in-memory metadata",starting.readable());
            assertFalse("Pending metadata is not persisted evidence",starting.persisted);
            assertTrue(store.read(store.currentId(),"chat",Event.class).isEmpty());
            assertFalse("An unrelated missing archive remains an error",store.catalog().stream().filter(s->s.id.equals(broken)).findFirst().get().readable());
            release.countDown();flush.get(10,TimeUnit.SECONDS);
            SessionStore.SessionEntry published=store.catalog().stream().filter(s->s.id.equals(store.currentId())).findFirst().get();
            assertTrue(published.readable());assertTrue(published.persisted);
            assertEquals("pending",store.read(store.currentId(),"chat",Event.class).get(0).text);
            // Stop the writer so it cannot repair the deliberate post-publication corruption.
            store.close();Files.delete(current.resolve("session.json"));
            assertFalse("Published current metadata corruption must remain visible",store.catalog().stream().filter(s->s.id.equals(store.currentId())).findFirst().get().readable());
        }finally{release.countDown();executor.shutdownNow();store.close();}
    }
    @Test public void deletionProtectsActiveSessionsAndImportsDoNotReturnAfterBeingDeleted()throws Exception{
        Path root=temp.newFolder().toPath();SessionStore first=new SessionStore(root,true,"one");first.flush();
        SessionStore second=new SessionStore(root,true,"two");
        try{
            try{second.delete(first.currentId());fail("Active session deletion must fail");}catch(java.io.IOException expected){ }
            first.close();second.delete(first.currentId());assertFalse(Files.exists(root.resolve(first.currentId())));
            second.importSnapshot("legacy","Imported",1000,"runs","run",new Event("old"));
            String imported=second.sessions().stream().filter(s->s.label.equals("Imported")).findFirst().get().id;
            second.importSnapshot("legacy","Imported",1000,"runs","run",new Event("old"));assertEquals(1,second.read(imported,"runs",Event.class).size());
            second.delete(imported);second.importSnapshot("legacy","Imported",1000,"runs","run",new Event("old"));
            assertTrue(second.read(imported,"runs",Event.class).isEmpty());
            try{second.delete("../outside");fail("Invalid path");}catch(IllegalArgumentException expected){ }
        }finally{first.close();second.close();}
    }
    @Test public void previewDoesNotWriteAndIncompleteCrashTailDoesNotLoseEarlierRecords()throws Exception{
        Path root=temp.newFolder().toPath();SessionStore first=new SessionStore(root,true,"one");String id=first.currentId();
        first.append("chat",new Event("complete"));first.flush();first.close();
        Path journal=root.resolve(id).resolve("chat.jsonl");Files.write(journal,"{\"text\":\"unfinished".getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
        SessionStore preview=new SessionStore(root,false,"two");
        try{
            preview.append("chat",new Event("preview"));preview.flush();assertFalse(Files.exists(root.resolve(preview.currentId())));
            assertEquals(1,preview.read(id,"chat",Event.class).size());
        }finally{preview.close();}
    }
    @Test public void legacyRunAndFameImportIsRepeatableAndKeepsOriginalFiles()throws Exception{
        Path old=temp.newFolder().toPath();Files.createDirectories(old.resolve("logs/discovery"));Files.createDirectories(old.resolve("FameSessions"));
        packets.packetcapture.logger.ActivityJournal.State state=new packets.packetcapture.logger.ActivityJournal.State();
        packets.packetcapture.logger.ActivityJournal.Visit visit=new packets.packetcapture.logger.ActivityJournal.Visit();visit.id="old-run";visit.map="Ice Citadel";visit.started=1000;visit.lastSeen=visit.ended=2000;state.visits.add(visit);
        Path runFile=old.resolve("logs/discovery/activity-history.json");Files.write(runFile,SessionStore.JSON.toJson(state).getBytes(StandardCharsets.UTF_8));byte[] before=Files.readAllBytes(runFile);
        tomato.gui.stats.session.FameSession fame=new tomato.gui.stats.session.FameSession("Old session");
        fame.addCharacterData(7,"Wizard",Arrays.asList(new tomato.gui.stats.Fame(100,1000),new tomato.gui.stats.Fame(125,2000)));
        Path fameFile=old.resolve("FameSessions/old.fame");Files.write(fameFile,SessionStore.JSON.toJson(fame).getBytes(StandardCharsets.UTF_8));
        SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"new-build");
        try{
            AppHistory.importLegacy(store,old);AppHistory.importLegacy(store,old);
            assertEquals(1,store.read(SessionStore.ALL,"runs",packets.packetcapture.logger.ActivityJournal.Visit.class).size());
            assertEquals(1,store.read(SessionStore.ALL,"fame-snapshots",tomato.gui.stats.session.FameSession.class).size());
            assertArrayEquals(before,Files.readAllBytes(runFile));assertTrue(Files.exists(fameFile));
        }finally{store.close();}
    }
    @Test public void browsingAnOpenSessionUsesAFixedJournalPrefixWhileCaptureContinues()throws Exception{
        SessionStore store=new SessionStore(temp.newFolder().toPath(),true,"test");
        try{
            store.append("chat",new Event("before"));store.flush();java.util.concurrent.atomic.AtomicInteger count=new java.util.concurrent.atomic.AtomicInteger();
            store.read(store.currentId(),"chat",Event.class,(session,event)->{
                if(count.incrementAndGet()==1){store.append("chat",new Event("arriving during read"));try{store.flush();}catch(Exception e){throw new AssertionError(e);}}
            });
            assertEquals(1,count.get());assertEquals(2,store.read(store.currentId(),"chat",Event.class).size());
        }finally{store.close();}
    }
}
