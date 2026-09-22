package tomato.history.archive;

import tomato.history.SessionStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static tomato.history.archive.ArchiveFixtures.*;

public class ArchiveCatalogTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void fiveHundredSessionsAndOneBadMetadataRemainEnumerableAndQueryable()throws Exception{
        Path root=temp.newFolder().toPath(),scratch=temp.newFolder().toPath();String bad=null;
        for(int i=0;i<500;i++){String id=session(root,1);if(i==275)bad=id;}
        Files.write(root.resolve(bad).resolve("session.json"),"{broken".getBytes(StandardCharsets.UTF_8));
        try(SessionStore store=new SessionStore(root,false,"test")){
            List<SessionStore.SessionEntry> entries=store.catalog();assertEquals(501,entries.size());
            assertEquals(1,entries.stream().filter(e->!e.readable()).count());assertEquals(500,store.sessions().size());
            for(SessionStore.SessionEntry entry:entries)assertEquals(SessionStore.ModuleAvailability.State.UNKNOWN,entry.availability("loot").state);
            try(ArchiveResult<Event> result=ArchiveResult.open(store,query(SessionStore.ALL),adapter(),scratch,new Cancellation())){
                assertEquals(499,result.matches);assertEquals(1,result.page(0,1000,new Cancellation()).issues.size());
            }
            try{ArchiveResult.open(store,query(bad),adapter(),scratch,new Cancellation());fail();}catch(java.io.IOException expected){assertEquals(0,children(scratch));}
        }
    }
    @Test public void declaredAvailabilityPersistsButPresenceAndImportsDoNotInventCoverage()throws Exception{
        Path root=temp.newFolder().toPath();String id;
        try(SessionStore store=new SessionStore(root,true,"test")){
            id=store.currentId();store.append("chat",new Event(1,1,"a","a"));store.flush();
            assertEquals(SessionStore.ModuleAvailability.State.UNKNOWN,store.catalog().stream().filter(e->e.id.equals(id)).findFirst().get().availability("chat").state);
            store.availability("chat",new SessionStore.ModuleAvailability(SessionStore.ModuleAvailability.State.PARTIAL,"Observed interval; gaps unknown",1L,2L)).toCompletableFuture().get();
            store.importSnapshot("synthetic-import","Imported",1,"runs","one",new Event(1,1,"a","a"));
        }
        try(SessionStore store=new SessionStore(root,false,"next")){
            SessionStore.SessionEntry entry=store.catalog().stream().filter(e->e.id.equals(id)).findFirst().get();
            assertEquals(SessionStore.ModuleAvailability.State.PARTIAL,entry.availability("chat").state);
            assertEquals(SessionStore.ModuleAvailability.State.UNKNOWN,entry.availability("loot").state);
            SessionStore.SessionEntry imported=store.catalog().stream().filter(e->e.readable()&&"Imported".equals(e.session().version)).findFirst().get();
            assertEquals(SessionStore.ModuleAvailability.State.UNKNOWN,imported.availability("loot").state);
        }
    }
    @Test public void unknownAvailabilityVersionAndRootFailureAreNotReportedAsHealthyEmptyHistory()throws Exception{
        Path root=temp.newFolder().toPath();String id=session(root,0);Path meta=root.resolve(id).resolve("session.json");
        com.google.gson.JsonObject json=com.google.gson.JsonParser.parseString(new String(Files.readAllBytes(meta),StandardCharsets.UTF_8)).getAsJsonObject();
        com.google.gson.JsonObject availability=new com.google.gson.JsonObject(),future=new com.google.gson.JsonObject();
        future.addProperty("schemaVersion",2);future.addProperty("state","NOT_CAPTURED");future.addProperty("reason","future");availability.add("loot",future);json.add("availability",availability);
        Files.write(meta,json.toString().getBytes(StandardCharsets.UTF_8));
        try(SessionStore store=new SessionStore(root,false,"test")){assertEquals(SessionStore.ModuleAvailability.State.UNKNOWN,store.catalog().stream().filter(e->e.id.equals(id)).findFirst().get().availability("loot").state);}
        Path file=temp.newFile().toPath();try(SessionStore store=new SessionStore(file,false,"test")){try{store.catalog();fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("directory"));}}
    }
    @Test public void renamePreservesUnknownMetadataAndRejectsActiveSessions()throws Exception{
        Path root=temp.newFolder().toPath();String id=session(root,1);Path meta=root.resolve(id).resolve("session.json");
        com.google.gson.JsonObject json=com.google.gson.JsonParser.parseString(new String(Files.readAllBytes(meta),StandardCharsets.UTF_8)).getAsJsonObject();
        json.addProperty("futureOptionalField","keep me");Files.write(meta,json.toString().getBytes(StandardCharsets.UTF_8));
        try(SessionStore store=new SessionStore(root,true,"test")){
            store.rename(id,"Review later");com.google.gson.JsonObject saved=com.google.gson.JsonParser.parseString(new String(Files.readAllBytes(meta),StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("Review later",saved.get("label").getAsString());assertEquals("keep me",saved.get("futureOptionalField").getAsString());
            try{store.rename(store.currentId(),"active");fail();}catch(java.io.IOException expected){ }
        }
    }
}
