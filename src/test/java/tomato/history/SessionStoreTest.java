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
    @Test public void runsCheckpointOrderAndContentsUseStartedThenPathWithEndedFixup()throws Exception{
        Path root=temp.newFolder().toPath();
        String id=UUID.randomUUID().toString();Path folder=Files.createDirectories(root.resolve(id).resolve("runs"));
        SessionStore.Session metadata=new SessionStore.Session(id,1,"","test");metadata.ended=900;
        Files.writeString(folder.getParent().resolve("session.json"),SessionStore.JSON.toJson(metadata));
        Map<String,packets.packetcapture.logger.ActivityJournal.Visit> visits=new HashMap<>();
        for(String name:List.of("c","a","b")){
            packets.packetcapture.logger.ActivityJournal.Visit visit=new packets.packetcapture.logger.ActivityJournal.Visit();
            visit.id=name;visit.map="Lost Halls";visit.started=name.equals("b")?100:200;visit.lastSeen=500;
            Files.writeString(folder.resolve(name+".json"),SessionStore.JSON.toJson(visit));
            visit.ended=visit.lastSeen;visit.endReason="App ended";visits.put(name,visit);
        }
        try(SessionStore store=new SessionStore(root,false,"test")){
            List<packets.packetcapture.logger.ActivityJournal.Visit> actual=store.read(id,"runs",packets.packetcapture.logger.ActivityJournal.Visit.class);
            assertEquals(List.of("a","c","b"),actual.stream().map(v->v.id).toList());
            assertEquals(SessionStore.JSON.toJson(List.of(visits.get("a"),visits.get("c"),visits.get("b"))),SessionStore.JSON.toJson(actual));
            Files.writeString(folder.resolve("bad.json"),"{\"started\":300,broken");
            assertThrows(RuntimeException.class,()->store.read(id,"runs",packets.packetcapture.logger.ActivityJournal.Visit.class));
        }
    }
    @Test public void runsCheckpointsReplaceMalformedUtf8AndKeepEmptyAndNonObjectFailureKinds()throws Exception{
        Path root=temp.newFolder().toPath();String id=UUID.randomUUID().toString();
        Path folder=Files.createDirectories(root.resolve(id).resolve("runs")),file=folder.resolve("run.json");
        SessionStore.Session metadata=new SessionStore.Session(id,1,"","test");
        Files.writeString(folder.getParent().resolve("session.json"),SessionStore.JSON.toJson(metadata));
        byte[] saved=("{\"started\":1,\"text\":\""+"x".repeat(9000)+"?\"}").getBytes(StandardCharsets.UTF_8);
        assertTrue(saved.length-3>8192);saved[saved.length-3]=(byte)0x80;
        Files.write(file,saved);
        try(SessionStore store=new SessionStore(root,false,"test")){
            List<Event> values=store.read(id,"runs",Event.class);
            assertEquals(1,values.size());assertEquals("x".repeat(9000)+"\uFFFD",values.get(0).text);
            for(String empty:List.of(""," \r\n")){
                Files.writeString(file,empty);
                assertThrows(java.io.EOFException.class,()->store.read(id,"runs",Event.class));
            }
            for(String nonObject:List.of("[]","null","123","\"string\"")){
                Files.writeString(file,nonObject);
                assertThrows(IllegalStateException.class,()->store.read(id,"runs",Event.class));
            }
        }
    }
    @Test public void incrementalJournalConsumesOnlyCompleteUtf8LinesAndDetectsShrinkAndReplacement()throws Exception{
        Path root=temp.newFolder().toPath();
        try(SessionStore store=new SessionStore(root,false,"test")){
            SessionStore.Session session=store.sessions().get(0);
            Path file=Files.createDirectories(root.resolve(session.id)).resolve("chat.jsonl");
            String first=SessionStore.JSON.toJson(new Event("snow 雪"))+"\r\n",second=SessionStore.JSON.toJson(new Event("next"));
            Files.writeString(file,first+second);
            List<Event> values=new ArrayList<>();
            SessionStore.JournalCursor cursor=store.readJournalFrom(session,"chat",null,Event.class,values::add,new tomato.history.archive.Cancellation());
            assertEquals(first.getBytes(StandardCharsets.UTF_8).length,cursor.offset());assertEquals(1,values.size());
            Files.writeString(file,"\n",StandardOpenOption.APPEND);
            cursor=store.readJournalFrom(session,"chat",cursor,Event.class,values::add,new tomato.history.archive.Cancellation());
            assertEquals(Files.size(file),cursor.offset());assertEquals(List.of("snow 雪","next"),values.stream().map(v->v.text).toList());
            SessionStore.JournalCursor complete=cursor;
            store.readJournalFrom(session,"chat",complete,Event.class,v->fail("No records repeated"),new tomato.history.archive.Cancellation());
            Files.writeString(file,first);
            assertThrows(SessionStore.JournalChangedException.class,()->store.readJournalFrom(session,"chat",complete,Event.class,values::add,new tomato.history.archive.Cancellation()));
            SessionStore.JournalCursor beforeReplace=store.readJournalFrom(session,"chat",null,Event.class,v->{},new tomato.history.archive.Cancellation());
            Path replacement=file.resolveSibling("replacement.jsonl");Files.writeString(replacement,second+"\n"+first);
            assertTrue(Files.size(replacement)>=beforeReplace.offset());
            Files.move(replacement,file,StandardCopyOption.REPLACE_EXISTING);
            assertThrows(SessionStore.JournalChangedException.class,()->store.readJournalFrom(session,"chat",beforeReplace,Event.class,values::add,new tomato.history.archive.Cancellation()));
            SessionStore.JournalCursor restarted=store.readJournalFrom(session,"chat",null,Event.class,v->{},new tomato.history.archive.Cancellation());
            assertEquals(Files.size(file),restarted.offset());
            // Without a changed file key, an identical consumed prefix is safe to continue as an append.
            String retained=Files.readString(file);
            if(restarted.fileKey()==null){
                Files.writeString(replacement,retained+first);Files.move(replacement,file,StandardCopyOption.REPLACE_EXISTING);
            }else Files.writeString(file,retained+first);
            values.clear();
            SessionStore.JournalCursor continued=store.readJournalFrom(session,"chat",restarted,Event.class,values::add,new tomato.history.archive.Cancellation());
            assertEquals(1,values.size());assertEquals(SessionStore.JSON.fromJson(first,Event.class).text,values.get(0).text);
            assertEquals(Files.size(file),continued.offset());
            Throwable[] edt=new Throwable[1];
            SwingUtilities.invokeAndWait(()->{try{store.readJournalFrom(session,"chat",restarted,Event.class,v->{},new tomato.history.archive.Cancellation());}catch(Throwable failure){edt[0]=failure;}});
            assertTrue(edt[0] instanceof IllegalStateException);
        }
    }
    @Test public void incrementalJournalDetectsChangedConsumedWindowBeyondAnIdenticalLeadingBlock()throws Exception{
        Path root=temp.newFolder().toPath();
        try(SessionStore store=new SessionStore(root,false,"test")){
            SessionStore.Session session=store.sessions().get(0);
            Path file=Files.createDirectories(root.resolve(session.id)).resolve("chat.jsonl");
            String prefix=(SessionStore.JSON.toJson(new Event("unchanged"))+"\n").repeat(100);
            String old=SessionStore.JSON.toJson(new Event("old"))+"\n",changed=SessionStore.JSON.toJson(new Event("new"))+"\n";
            assertTrue(prefix.getBytes(StandardCharsets.UTF_8).length>4096);
            assertEquals(old.getBytes(StandardCharsets.UTF_8).length,changed.getBytes(StandardCharsets.UTF_8).length);
            Files.writeString(file,prefix+old);
            SessionStore.JournalCursor cursor=store.readJournalFrom(session,"chat",null,Event.class,v->{},new tomato.history.archive.Cancellation());
            Path replacement=file.resolveSibling("replacement.jsonl");Files.writeString(replacement,prefix+changed);
            Files.move(replacement,file,StandardCopyOption.REPLACE_EXISTING);
            assertEquals(cursor.offset(),Files.size(file));
            assertThrows(SessionStore.JournalChangedException.class,()->store.readJournalFrom(session,"chat",cursor,Event.class,
                v->fail("A changed consumed prefix must be rejected before any new records are emitted"),new tomato.history.archive.Cancellation()));
            // An in-place same-length rewrite leaves file identity unchanged on every provider, exercising the content check.
            SessionStore.JournalCursor replaced=store.readJournalFrom(session,"chat",null,Event.class,v->{},new tomato.history.archive.Cancellation());
            Files.writeString(file,prefix+old);
            assertThrows(SessionStore.JournalChangedException.class,()->store.readJournalFrom(session,"chat",replaced,Event.class,v->{},new tomato.history.archive.Cancellation()));
            List<Event> values=new ArrayList<>();
            SessionStore.JournalCursor restarted=store.readJournalFrom(session,"chat",null,Event.class,values::add,new tomato.history.archive.Cancellation());
            assertEquals(101,values.size());assertEquals("old",values.get(100).text);
            Files.writeString(file,changed,StandardOpenOption.APPEND);
            values.clear();
            SessionStore.JournalCursor appended=store.readJournalFrom(session,"chat",restarted,Event.class,values::add,new tomato.history.archive.Cancellation());
            assertEquals(1,values.size());assertEquals("new",values.get(0).text);assertEquals(Files.size(file),appended.offset());
        }
    }
    @Test public void incrementalJournalPinsItsInitialPrefixAndFixesEndedVisits()throws Exception{
        Path root=temp.newFolder().toPath();
        try(SessionStore store=new SessionStore(root,false,"test")){
            SessionStore.Session session=store.sessions().get(0);session.ended=900;
            Path file=Files.createDirectories(root.resolve(session.id)).resolve("runs.jsonl");
            String visit="{\"id\":\"v\",\"started\":100,\"lastSeen\":200}";
            Files.writeString(file,visit+"\n"+visit);
            List<packets.packetcapture.logger.ActivityJournal.Visit> values=new ArrayList<>();
            SessionStore.JournalCursor cursor=store.readJournalFrom(session,"runs",null,packets.packetcapture.logger.ActivityJournal.Visit.class,v->{
                values.add(v);try{Files.writeString(file,"\n",StandardOpenOption.APPEND);}catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
            },new tomato.history.archive.Cancellation());
            assertEquals(1,values.size());assertEquals(200,values.get(0).ended);assertEquals("App ended",values.get(0).endReason);
            assertEquals(visit.length()+1,cursor.offset());
        }
    }
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
    @Test public void collectorFailuresStayVisibleAfterASuccessfulSaveUntilACollectionSucceeds()throws Exception{
        SessionStore store=new SessionStore(temp.getRoot().toPath().resolve("collecting"),true,"test");
        try{
            store.collect("failing",()->{throw new IllegalStateException("synthetic");});
            store.append("chat",new Event("saved anyway"));store.flush();
            assertEquals(SessionStore.SNAPSHOT_FAILED+"IllegalStateException",store.error());
            assertEquals("saved anyway",store.read(store.currentId(),"chat",Event.class).get(0).text);
            store.collect("failing",()->{});store.flush();
            assertEquals("",store.error());
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
    @Test public void readCheckpointReadsTheFileThatPutWroteOffTheEdtOnly()throws Exception{
        Path root=temp.newFolder().toPath();SessionStore first=new SessionStore(root,true,"build-one");String old=first.currentId();
        first.put("encounters","recording-1",new Event("old"));first.put("encounters","recording-1",new Event("latest"));
        first.put("encounters","recording-2",new Event("other"));first.flush();
        try{
            assertEquals("latest",first.readCheckpoint(old,"encounters","recording-1",Event.class).get().text);
            assertEquals(Instant.parse("2026-09-20T12:00:00Z"),first.readCheckpoint(old,"encounters","recording-1",Event.class).get().time);
            assertFalse("absent key",first.readCheckpoint(old,"encounters","recording-3",Event.class).isPresent());
            assertFalse("absent module",first.readCheckpoint(old,"encounter-detail","recording-1",Event.class).isPresent());
            String unknown=UUID.randomUUID().toString();
            assertFalse("absent session",first.readCheckpoint(unknown,"encounters","recording-1",Event.class).isPresent());
            try{first.readCheckpoint("../escape","encounters","recording-1",Event.class);fail("Session IDs are validated");}catch(IllegalArgumentException expected){}
            try{first.readCheckpoint(old,"Bad/Module","recording-1",Event.class);fail("Module names are validated");}catch(IllegalArgumentException expected){}
            Throwable[] edt=new Throwable[1];
            SwingUtilities.invokeAndWait(()->{try{first.readCheckpoint(old,"encounters","recording-1",Event.class);}catch(Throwable t){edt[0]=t;}});
            assertTrue(String.valueOf(edt[0]),edt[0] instanceof IllegalStateException);
            Path file=root.resolve(old).resolve("encounters").resolve(UUID.nameUUIDFromBytes("recording-2".getBytes(StandardCharsets.UTF_8))+".json");
            assertTrue("the name put writes",Files.isRegularFile(file));
            Files.write(file,"{\"text\":".getBytes(StandardCharsets.UTF_8));
            try{first.readCheckpoint(old,"encounters","recording-2",Event.class);fail("A damaged checkpoint is not an absent one");}
            catch(java.io.IOException expected){assertTrue(expected.getMessage(),expected.getMessage().startsWith("Unreadable history"));}
        }finally{first.close();}
        SessionStore next=new SessionStore(root,false,"build-two");
        try{assertEquals("other session after restart","latest",next.readCheckpoint(old,"encounters","recording-1",Event.class).get().text);}
        finally{next.close();}
    }
    @Test public void currentDirectoryIsTheWritableCurrentSessionFolderAndEmptyInPreview()throws Exception{
        Path root=temp.newFolder().toPath();SessionStore store=new SessionStore(root,true,"one");SessionStore preview=new SessionStore(root,false,"two");
        try{
            assertEquals(Optional.of(root.toAbsolutePath().normalize().resolve(store.currentId())),store.currentDirectory());
            assertEquals("Preview writes nothing, so it has no folder to write in",Optional.empty(),preview.currentDirectory());
        }finally{store.close();preview.close();}
        assertEquals("A closed store takes no more side files",Optional.empty(),store.currentDirectory());
    }
    @Test public void deleteFilesPrunesOneModuleOfClosedSessionsOnly()throws Exception{
        Path root=temp.newFolder().toPath();SessionStore first=new SessionStore(root,true,"one");String old=first.currentId();
        first.put("encounters","keep",new Event("keep"));first.put("encounters","drop",new Event("drop"));first.put("runs","drop",new Event("run"));first.flush();
        Path side=Files.createDirectories(root.resolve(old).resolve("combat-full"));Files.write(side.resolve("a.dps"),new byte[]{1,2,3});
        SessionStore second=new SessionStore(root,true,"two");
        try{
            try{second.deleteFiles(old,"encounters",file->true);fail("A session open in another store is refused");}
            catch(java.io.IOException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("still open"));}
            try{first.deleteFiles(old,"encounters",file->true);fail("The current session is refused");}
            catch(java.io.IOException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("still recording"));}
            assertEquals(2,first.read(old,"encounters",Event.class).size());
            first.close();
            String dropped=SessionStore.checkpointName("drop")+".json";
            assertEquals(1,second.deleteFiles(old,"encounters",file->file.getFileName().toString().equals(dropped)));
            assertEquals("Only the matched file of that module",List.of("keep"),
                second.read(old,"encounters",Event.class).stream().map(e->e.text).collect(java.util.stream.Collectors.toList()));
            assertEquals("Other modules are untouched",1,second.read(old,"runs",Event.class).size());
            assertEquals("Side files of any module name",1,second.deleteFiles(old,"combat-full",file->true));assertFalse(Files.exists(side.resolve("a.dps")));
            assertEquals("An absent module deletes nothing",0,second.deleteFiles(old,"encounter-detail",file->true));
            assertTrue("The session itself stays",Files.isRegularFile(root.resolve(old).resolve("session.json")));
            try{second.deleteFiles(old,"Bad/Module",file->true);fail("Module names are validated");}catch(IllegalArgumentException expected){}
            try{second.deleteFiles("../outside","encounters",file->true);fail("Session IDs are validated");}catch(IllegalArgumentException expected){}
        }finally{first.close();second.close();}
        SessionStore preview=new SessionStore(root,false,"three");
        try{preview.deleteFiles(old,"encounters",file->true);fail("Preview deletes nothing");}
        catch(java.io.IOException expected){ }
        finally{preview.close();}
    }
}
