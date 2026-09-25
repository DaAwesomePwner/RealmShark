package tomato.gui.history;

import tomato.history.archive.*;
import util.PreferencesStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static tomato.history.archive.ArchiveFixtures.*;

public class ViewStateStoreTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void independentWorkspaceAndNamedViewSurviveARealPreferencesRestart()throws Exception{
        Path path=temp.getRoot().toPath().resolve("preferences.properties");String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();
        ViewState<Facets,Sort> initial=ViewState.initial(query(a));ArchiveRow.Ref ref=new ArchiveRow.Ref(a,"chat","journal:100","");
        ViewState<Facets,Sort> first=initial.withArchive(true).withQuery(query(a).withFacets(new Facets(11000,"even"))).withPage(2)
                .withPosition("events",Collections.singletonList(ref),ref,7)
                .withTable("events",new ViewState.Table("Compact",Arrays.asList(new ViewState.Column("value",140,true),new ViewState.Column("time",190,false))));
        PreferencesStore preferences=new PreferencesStore(path);assertTrue(preferences.preload().isSuccess());
        ViewStateStore states=ViewStateStore.preferences(preferences);
        assertTrue(states.save("chat",first).toCompletableFuture().get().isSuccess());
        assertTrue(states.saveNamed("chat","Even older",first).toCompletableFuture().get().isSuccess());
        ViewState<Facets,Sort> second=initial.withQuery(query(b).withText("guild"));states.save("keypops",second).toCompletableFuture().get();
        preferences.shutdown(5,TimeUnit.SECONDS,message->{throw new AssertionError(message);});
        PreferencesStore restarted=new PreferencesStore(path);assertTrue(restarted.preload().isSuccess());
        try{
            ViewStateStore loaded=ViewStateStore.preferences(restarted);ViewState<Facets,Sort> restored=loaded.load("chat",initial);
            assertEquals(first.toJson(),restored.toJson());assertEquals(first.toJson(),loaded.loadNamed("chat","Even older",initial).toJson());
            assertEquals(Collections.singletonList("Even older"),loaded.names("chat"));assertEquals(second.toJson(),loaded.load("keypops",initial).toJson());
            loaded.deleteNamed("chat","Even older").toCompletableFuture().get();assertTrue(loaded.names("chat").isEmpty());
        }finally{restarted.shutdown(5,TimeUnit.SECONDS,message->{throw new AssertionError(message);});}
    }
    @Test public void failedSaveRetainsStateInMemoryAndDoesNotClaimDurability()throws Exception{
        Path file=temp.getRoot().toPath().resolve("missing-parent/settings.properties");PreferencesStore preferences=new PreferencesStore(file);
        assertTrue(preferences.preload().isSuccess());ViewStateStore states=ViewStateStore.preferences(preferences);
        ViewState<Facets,Sort> state=ViewState.initial(query(ArchiveQuery.CURRENT)).withQuery(query(ArchiveQuery.CURRENT).withText("keep draft"));
        try{
            PreferencesStore.SaveResult result=states.save("chat",state).toCompletableFuture().get();assertFalse(result.isSuccess());
            assertEquals("keep draft",states.load("chat",ViewState.initial(query(ArchiveQuery.CURRENT))).query.text());
            assertFalse(Files.exists(file));
        }finally{preferences.shutdown(5,TimeUnit.SECONDS,message->{});}
    }
    @Test public void corruptOrFutureStateIsNotSilentlyReplacedByDefaultFilters()throws Exception{
        Path path=temp.getRoot().toPath().resolve("preferences.properties");PreferencesStore preferences=new PreferencesStore(path);preferences.preload();
        try{
            preferences.setProperties("ux.archive.chat","{\"version\":99,\"named\":{}}").toCompletableFuture().get();
            ViewStateStore states=ViewStateStore.preferences(preferences);
            try{states.load("chat",ViewState.initial(query(ArchiveQuery.CURRENT)));fail();}catch(IllegalArgumentException expected){ }
            try{states.save("chat",ViewState.initial(query(ArchiveQuery.CURRENT)));fail();}catch(IllegalArgumentException expected){ }
            assertTrue(new String(Files.readAllBytes(path),StandardCharsets.UTF_8).contains("99"));
            states.reset("chat").toCompletableFuture().get();assertEquals(ArchiveQuery.CURRENT,states.load("chat",ViewState.initial(query(ArchiveQuery.CURRENT))).query.scope());
        }finally{preferences.shutdown(5,TimeUnit.SECONDS,message->{});}
    }
}
